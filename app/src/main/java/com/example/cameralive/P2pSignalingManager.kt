package com.example.cameralive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object P2pSignalingManager {
    private const val TAG = "P2pSignaling"
    private const val PREFS_NAME = "CameraLiveP2pPrefs"
    private const val PEERJS_HOST = "0.peerjs.com"
    private const val PEERJS_KEY = "peerjs"

    private const val KEY_ENABLED = "KEY_ENABLED"
    private const val KEY_ROOM_ID = "KEY_ROOM_ID"
    private const val KEY_ROOM_PIN = "KEY_ROOM_PIN"
    private const val KEY_PIN_ROTATION_INTERVAL = "KEY_PIN_ROTATION_INTERVAL"
    private const val KEY_BROKER_TOKEN = "KEY_BROKER_TOKEN"
    private const val PIN_GUARD_KEY = "p2p"
    private const val MAX_ID_TAKEN_RETRIES = 6
    private const val MAX_RECONNECT_DELAY_SEC = 120L
    private const val HEARTBEAT_SEC = 30L

    private val secureRandom = java.security.SecureRandom()
    private fun newPin(): String = (1000 + secureRandom.nextInt(9000)).toString()

    var isEnabled: Boolean = true
    var roomId: String = "cam-" + UUID.randomUUID().toString().take(6).lowercase()
        get() {
            if (field.isBlank()) {
                field = "cam-" + UUID.randomUUID().toString().take(6).lowercase()
            }
            return field
        }
    var roomPin: String = newPin()
        get() {
            if (field.isBlank()) {
                field = newPin()
            }
            return field
        }
    var autoRotateIntervalMin: Int = 0 // 0 = Aus (Manuell), 15 = 15 Min, 30 = 30 Min, 60 = 1 Std

    val liveRoomId = kotlinx.coroutines.flow.MutableStateFlow(roomId)
    val liveRoomPin = kotlinx.coroutines.flow.MutableStateFlow(roomPin)
    val liveAutoRotateInterval = kotlinx.coroutines.flow.MutableStateFlow(0)

    val isConnected = AtomicBoolean(false)
    var lastError: String = ""

    /**
     * Identifies this camera to the PeerJS broker. It used to be the PIN, which sent the PIN to a
     * public server and made the broker answer ID-TAKEN after every PIN change.
     */
    private var brokerToken: String = ""

    // Guessing is global here: a remote attacker can pick any peer ID per attempt.
    private val pinGuard = PinGuard(maxDistinctFailures = 12, lockMs = 60_000L)

    /** False while the camera service is stopped: nothing may reconnect in the background. */
    @Volatile private var shouldRun = false
    private var reconnectAttempts = 0
    private var idTakenRetries = 0
    private var reconnectFuture: ScheduledFuture<*>? = null

    @Volatile private var currentRemotePeer: String? = null
    @Volatile private var currentConnectionId: String? = null

    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var rotationTimerFuture: ScheduledFuture<*>? = null
    private var service: CameraStreamingService? = null

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean(KEY_ENABLED, true)

        val savedRoomId = prefs.getString(KEY_ROOM_ID, "") ?: ""
        if (savedRoomId.isNotBlank()) {
            roomId = savedRoomId
        } else {
            val newId = "cam-" + UUID.randomUUID().toString().take(6).lowercase()
            roomId = newId
            prefs.edit().putString(KEY_ROOM_ID, newId).apply()
        }
        liveRoomId.value = roomId

        val savedPin = prefs.getString(KEY_ROOM_PIN, "") ?: ""
        if (savedPin.isNotBlank()) {
            roomPin = savedPin
        } else {
            val newPin = newPin()
            roomPin = newPin
            prefs.edit().putString(KEY_ROOM_PIN, newPin).apply()
        }
        liveRoomPin.value = roomPin

        brokerToken = prefs.getString(KEY_BROKER_TOKEN, "") ?: ""
        if (brokerToken.isBlank()) {
            brokerToken = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_BROKER_TOKEN, brokerToken).apply()
        }

        autoRotateIntervalMin = prefs.getInt(KEY_PIN_ROTATION_INTERVAL, 0)
        liveAutoRotateInterval.value = autoRotateIntervalMin
    }

    fun init(context: Context, streamingService: CameraStreamingService) {
        service = streamingService
        load(context)

        // Hook up live candidate streaming from WebRtcManager
        streamingService.webRtcManager?.onLocalCandidateGathered = { cand ->
            forwardLocalCandidate(cand)
        }

        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Detects dead sockets. HEARTBEAT below keeps the broker session alive.
            .pingInterval(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        shouldRun = true
        reconnectAttempts = 0
        idTakenRetries = 0

        startAutoRotationTimer(context.applicationContext)

        if (isEnabled) {
            connect()
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, isEnabled)
            .putString(KEY_ROOM_ID, roomId)
            .putString(KEY_ROOM_PIN, roomPin)
            .putInt(KEY_PIN_ROTATION_INTERVAL, autoRotateIntervalMin)
            .apply()
        liveRoomId.value = roomId
        liveRoomPin.value = roomPin
        liveAutoRotateInterval.value = autoRotateIntervalMin
    }

    fun setAutoRotateInterval(context: Context, intervalMin: Int) {
        autoRotateIntervalMin = intervalMin
        save(context)
        startAutoRotationTimer(context.applicationContext)
    }

    private fun startAutoRotationTimer(context: Context) {
        rotationTimerFuture?.cancel(true)
        rotationTimerFuture = null
        if (autoRotateIntervalMin > 0) {
            val delay = autoRotateIntervalMin.toLong()
            rotationTimerFuture = executor.scheduleWithFixedDelay({
                Log.i(TAG, "⏰ Auto-rotating dynamic PIN ($autoRotateIntervalMin min interval)...")
                regeneratePin(context)
            }, delay, delay, TimeUnit.MINUTES)
        }
    }

    fun regeneratePin(context: Context): String {
        val pin = newPin()
        roomPin = pin
        save(context)
        pinGuard.reset()

        // Terminate all current web & peer sessions immediately so only new PIN is accepted.
        // The broker connection stays: PIN checks happen here, not at the broker.
        currentRemotePeer = null
        currentConnectionId = null
        service?.invalidateAllSessions()
        return pin
    }

    fun updatePin(context: Context, newPin: String) {
        roomPin = newPin.trim().ifBlank { newPin() }
        save(context)
        pinGuard.reset()

        // Terminate all current web & peer sessions immediately
        currentRemotePeer = null
        currentConnectionId = null
        service?.invalidateAllSessions()
    }

    fun regenerateRoomId(context: Context): String {
        val newId = "cam-" + UUID.randomUUID().toString().take(6).lowercase()
        roomId = newId
        roomPin = newPin()
        save(context)
        pinGuard.reset()

        // Terminate all current web & peer sessions immediately
        currentRemotePeer = null
        currentConnectionId = null
        service?.invalidateAllSessions()

        // The room ID changed, so the broker needs a new registration.
        idTakenRetries = 0
        if (isEnabled && shouldRun) {
            connect()
        }
        return roomId
    }

    private fun forwardLocalCandidate(cand: org.webrtc.IceCandidate) {
        val peer = currentRemotePeer ?: return
        val connId = currentConnectionId ?: return
        if (!isConnected.get()) return

        executor.submit {
            try {
                val candMsg = JSONObject().apply {
                    put("type", "CANDIDATE")
                    put("src", roomId)
                    put("dst", peer)
                    put("payload", JSONObject().apply {
                        put("candidate", JSONObject().apply {
                            put("candidate", cand.sdp)
                            put("sdpMid", cand.sdpMid)
                            put("sdpMLineIndex", cand.sdpMLineIndex)
                        })
                        put("type", "media")
                        put("connectionId", connId)
                    })
                }
                webSocket?.send(candMsg.toString())
                Log.d(TAG, "Sent live candidate to $peer: ${cand.sdp}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send live candidate", e)
            }
        }
    }

    @Synchronized
    fun connect() {
        if (!isEnabled || !shouldRun || roomId.isBlank()) return
        closeSocket()

        val wsUrl = "wss://$PEERJS_HOST/peerjs?key=$PEERJS_KEY&id=$roomId&token=$brokerToken"
        Log.i(TAG, "Connecting P2P Signaling to PeerJS: Room=$roomId")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
            // Late callbacks of a replaced socket must not touch the current connection.
            private fun isCurrent(ws: WebSocket) = ws === this@P2pSignalingManager.webSocket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) return
                Log.i(TAG, "P2P Signaling WebSocket Connected for room: $roomId")
                isConnected.set(true)
                reconnectAttempts = 0
                lastError = ""
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCurrent(webSocket)) handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                Log.w(TAG, "P2P Signaling closing: $code / $reason")
                isConnected.set(false)
                // OkHttp reports onClosed only after we answer the close, and the broker may simply
                // stay silent. Without this, a server-side close ended remote access for good.
                webSocket.close(1000, null)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                Log.w(TAG, "P2P Signaling closed: $code / $reason")
                isConnected.set(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(webSocket)) return
                Log.w(TAG, "P2P Signaling failure: ${t.localizedMessage}")
                isConnected.set(false)
                lastError = t.localizedMessage ?: "Verbindungsfehler"
                scheduleReconnect()
            }
        })
    }

    @Synchronized
    private fun closeSocket() {
        try {
            heartbeatFuture?.cancel(false)
            heartbeatFuture = null
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            val old = webSocket
            webSocket = null
            isConnected.set(false)
            old?.close(1000, "Reconnect")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing websocket", e)
        }
    }

    /** [permanent] stops all background reconnects until the service starts again. */
    @Synchronized
    fun disconnect(permanent: Boolean = false) {
        if (permanent) {
            shouldRun = false
            rotationTimerFuture?.cancel(false)
            rotationTimerFuture = null
            service = null
        }
        closeSocket()
    }

    /** Waits 5, 10, 20 ... up to 120 seconds, so a phone without network doesn't keep waking up. */
    @Synchronized
    private fun scheduleReconnect() {
        if (!isEnabled || !shouldRun) return
        if (reconnectFuture?.isDone == false) return
        val delay = (5L shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(MAX_RECONNECT_DELAY_SEC)
        reconnectAttempts++
        reconnectFuture = executor.schedule({
            if (isEnabled && shouldRun && !isConnected.get()) {
                Log.i(TAG, "Reconnecting P2P signaling...")
                connect()
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = executor.scheduleWithFixedDelay({
            if (isConnected.get()) {
                try {
                    webSocket?.send("{\"type\":\"HEARTBEAT\"}")
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed", e)
                }
            }
        }, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS)
    }

    private fun handleIncomingMessage(jsonStr: String) {
        try {
            val msg = JSONObject(jsonStr)
            val type = msg.optString("type")
            val src = msg.optString("src")

            when (type) {
                "OPEN" -> {
                    Log.i(TAG, "P2P Registered successfully on PeerJS network with ID: $roomId")
                    idTakenRetries = 0
                    lastError = ""
                }
                "OFFER" -> {
                    val payload = msg.optJSONObject("payload") ?: return
                    val connectionId = payload.optString("connectionId", "mc_${System.currentTimeMillis()}")
                    val sdpObj = payload.optJSONObject("sdp")
                    val offerSdp = sdpObj?.optString("sdp") ?: payload.optString("sdp")

                    // Strict PIN / Security Token check
                    val providedToken = msg.optString("token").trim().ifBlank {
                        payload.optString("token").trim().ifBlank {
                            payload.optString("pin").trim()
                        }
                    }

                    val requiredPin = roomPin.trim()
                    val now = android.os.SystemClock.elapsedRealtime()
                    val locked = pinGuard.isLocked(PIN_GUARD_KEY, now)
                    val pinValid = !locked && requiredPin.isNotBlank() && providedToken.isNotBlank() &&
                        secretsMatch(providedToken, requiredPin)
                    if (!pinValid) {
                        if (!locked && providedToken.isNotBlank()) pinGuard.recordFailure(PIN_GUARD_KEY, providedToken, now)
                        Log.w(TAG, "Blocked P2P client '$src': wrong PIN or too many attempts")
                        // No reply on purpose: the public broker closed our socket right after every
                        // rejection we sent, so one wrong guess took the camera off the relay. The
                        // viewer reports the missing answer itself.
                        return
                    }

                    pinGuard.recordSuccess(PIN_GUARD_KEY)
                    if (offerSdp.isNotBlank()) {
                        Log.i(TAG, "Received authenticated P2P WebRTC OFFER from client: $src (connId: $connectionId)")
                        currentRemotePeer = src
                        currentConnectionId = connectionId
                        handleRemoteOffer(src, connectionId, offerSdp)
                    }
                }
                "CANDIDATE" -> {
                    // Only accept candidates from current authenticated remote peer
                    if (currentRemotePeer == null || src != currentRemotePeer) {
                        Log.w(TAG, "Ignored candidate from unauthenticated peer: $src")
                        return
                    }
                    val payload = msg.optJSONObject("payload") ?: return
                    val candObj = payload.optJSONObject("candidate") ?: payload
                    val candSdp = candObj.optString("candidate")
                    val sdpMid = candObj.optString("sdpMid", "0")
                    val sdpMLineIndex = candObj.optInt("sdpMLineIndex", 0)

                    if (candSdp.isNotBlank()) {
                        service?.webRtcManager?.addRemoteCandidate(sdpMid, sdpMLineIndex, candSdp, null)
                    }
                }
                "ID-TAKEN" -> {
                    // Usually our own previous session, still alive at the broker after a network
                    // drop. A new ID would break every saved viewer link, so wait first.
                    idTakenRetries++
                    if (idTakenRetries > MAX_ID_TAKEN_RETRIES) {
                        Log.w(TAG, "PeerJS ID $roomId is taken by someone else, generating a new one")
                        roomId = "cam-" + UUID.randomUUID().toString().take(6).lowercase()
                        service?.let { save(it) }
                        idTakenRetries = 0
                    } else {
                        Log.w(TAG, "PeerJS ID $roomId still registered, retry $idTakenRetries")
                    }
                    isConnected.set(false)
                    scheduleReconnect()
                }
                "ERROR" -> {
                    val payload = msg.optJSONObject("payload")
                    val errMsg = payload?.optString("msg") ?: msg.optString("msg", "PeerJS Error")
                    Log.w(TAG, "P2P PeerJS Error: $errMsg")
                    lastError = errMsg
                }
                "PING" -> {
                    webSocket?.send("{\"type\":\"PONG\"}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming signaling message", e)
        }
    }

    private fun handleRemoteOffer(remoteSrc: String, connectionId: String, offerSdp: String) {
        val rtcMgr = service?.webRtcManager ?: return

        // Make sure the trickle ICE callback is attached to current manager
        rtcMgr.onLocalCandidateGathered = { cand ->
            forwardLocalCandidate(cand)
        }

        executor.submit {
            try {
                val offerJson = JSONObject().apply {
                    put("sdp", offerSdp)
                }.toString()

                val answerJsonStr = rtcMgr.handleOffer(offerJson, null, audioOnByDefault = true)
                if (answerJsonStr != null) {
                    val answerParsed = JSONObject(answerJsonStr)
                    val answerSdp = answerParsed.optString("sdp")

                    // 1. Send ANSWER back to remote peer
                    val answerMsg = JSONObject().apply {
                        put("type", "ANSWER")
                        put("src", roomId)
                        put("dst", remoteSrc)
                        put("payload", JSONObject().apply {
                            put("sdp", JSONObject().apply {
                                put("type", "answer")
                                put("sdp", answerSdp)
                            })
                            put("type", "media")
                            put("connectionId", connectionId)
                        })
                    }
                    webSocket?.send(answerMsg.toString())
                    Log.i(TAG, "Sent P2P WebRTC ANSWER to $remoteSrc")

                    // 2. Send already-gathered ICE candidates
                    val candidates = rtcMgr.gatheredCandidates
                    synchronized(candidates) {
                        for (cand in candidates) {
                            val candMsg = JSONObject().apply {
                                put("type", "CANDIDATE")
                                put("src", roomId)
                                put("dst", remoteSrc)
                                put("payload", JSONObject().apply {
                                    put("candidate", JSONObject().apply {
                                        put("candidate", cand.sdp)
                                        put("sdpMid", cand.sdpMid)
                                        put("sdpMLineIndex", cand.sdpMLineIndex)
                                    })
                                    put("type", "media")
                                    put("connectionId", connectionId)
                                })
                            }
                            webSocket?.send(candMsg.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling remote P2P offer", e)
            }
        }
    }

    /** [includePin] only for authenticated clients. */
    fun getP2pStateJson(includePin: Boolean): String {
        val obj = JSONObject()
        obj.put("enabled", isEnabled)
        obj.put("roomId", roomId)
        if (includePin) obj.put("roomPin", roomPin)
        obj.put("connected", isConnected.get())
        obj.put("error", lastError)
        return obj.toString()
    }
}
