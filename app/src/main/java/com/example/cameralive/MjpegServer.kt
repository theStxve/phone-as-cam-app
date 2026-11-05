package com.example.cameralive

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface CameraController {
    fun toggleFlashlight(): Boolean
    fun toggleWideAngle(): Boolean
    fun switchCameraFacing(): String
    fun toggleSelfieCamera(): Boolean
    fun setMegafon(active: Boolean): Boolean
    fun isFrontFacing(): Boolean
    fun isWideAngleActive(): Boolean
    fun isFlashlightActive(): Boolean
    fun setRemoteAudioEnabled(enabled: Boolean)
    fun setExposureCompensation(index: Int): Int
    fun getExposureCompensation(): Int
    fun getExposureRange(): Pair<Int, Int>
    fun getExposureStep(): Float
    fun setZoomRatio(ratio: Float): Float
    fun getZoomRatio(): Float
    fun getZoomRange(): Pair<Float, Float>
}

class MjpegServer(port: Int, private val controller: CameraController) : NanoHTTPD(port) {

    private companion object {
        const val MAX_SIGNALING_BODY = 256 * 1024
        const val MAX_PCM_BODY = 256 * 1024
        const val MAX_CLIP_BODY = 48 * 1024 * 1024
    }

    private val micClients = mutableListOf<BlockingQueue<ByteArray>>()
    private val isRunning = AtomicBoolean(true)
    
    // Active web sessions map: sessionId -> lastSeenTimestamp
    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordSessionActivity(sessionId: String) {
        if (sessionId.isNotBlank()) {
            activeSessions[sessionId] = System.currentTimeMillis()
            updateViewerCount()
        }
    }

    fun removeSession(sessionId: String) {
        if (sessionId.isNotBlank()) {
            activeSessions.remove(sessionId)
            updateViewerCount()
        }
    }

    fun updateViewerCount(): Int {
        val now = System.currentTimeMillis()
        activeSessions.entries.removeIf { now - it.value > 6000L }
        val count = activeSessions.size
        CameraStreamingService.activeViewers.value = count
        return count
    }

    fun getViewerCount(): Int = updateViewerCount()

    // Security & PIN Authentication Store
    val authenticatedSessions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val pinGuard = PinGuard()

    /** Reads a request body, refusing anything larger than [maxBytes]. Null if refused or empty. */
    private fun readBody(session: IHTTPSession, maxBytes: Int): ByteArray? {
        val length = session.headers["content-length"]?.toLongOrNull() ?: return null
        if (length <= 0 || length > maxBytes) return null
        val buffer = ByteArray(length.toInt())
        var read = 0
        while (read < buffer.size) {
            val r = session.inputStream.read(buffer, read, buffer.size - read)
            if (r == -1) break
            read += r
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    fun isRequestAuthorized(session: IHTTPSession): Boolean {
        val currentPin = P2pSignalingManager.roomPin.trim()
        if (currentPin.isBlank()) return false
        val client = session.remoteIpAddress ?: "unknown"
        val nowMs = System.currentTimeMillis()

        val cookieHeader = session.headers["cookie"]
        val cookiePin = cookieHeader?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("auth_pin=") }
            ?.substringAfter("auth_pin=")
            ?.trim()

        val provided = (session.headers["x-auth-pin"]
            ?: session.headers["x-auth-token"]
            ?: session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: session.parms["pin"]
            ?: session.parms["token"]
            ?: session.parms["auth"]
            ?: cookiePin)?.trim()

        if (!provided.isNullOrBlank()) {
            // Every route accepts the PIN, so every route counts against the guessing limit.
            if (pinGuard.isLocked(client, nowMs)) return false
            if (secretsMatch(provided, currentPin)) {
                pinGuard.recordSuccess(client)
                val sid = session.parms["session_id"] ?: session.headers["x-session-id"]
                if (!sid.isNullOrBlank()) {
                    authenticatedSessions[sid] = System.currentTimeMillis()
                }
                return true
            }
            pinGuard.recordFailure(client, provided, nowMs)
        }

        val sid = session.parms["session_id"] ?: session.headers["x-session-id"]
        if (!sid.isNullOrBlank()) {
            val lastAuth = authenticatedSessions[sid]
            if (lastAuth != null && System.currentTimeMillis() - lastAuth < 86400000L) { // 24 hours valid
                return true
            }
        }

        return false
    }

    private fun unauthorizedResponse(): Response {
        val json = """{"error":"Nicht autorisiert: PIN erforderlich","unauthorized":true}"""
        val resp = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        return resp
    }

    // Latest frames for fallback polling mode
    val latestBackFrame = AtomicReference<ByteArray>(null)
    val latestFrontFrame = AtomicReference<ByteArray>(null)
    
    @Volatile var currentLat: Double = 0.0
    @Volatile var currentLng: Double = 0.0
    
    @Volatile var lastSnapshotRequestTime: Long = 0L
    @Volatile var lastFrontSnapshotRequestTime: Long = 0L

    var service: CameraStreamingService? = null

    fun hasActiveClients(): Boolean {
        if (getViewerCount() > 0) return true
        if (service?.webRtcManager?.hasActivePeer() == true) return true
        if (hasMicClients()) return true
        val now = System.currentTimeMillis()
        if (now - lastSnapshotRequestTime < 5000L) return true
        if (now - lastFrontSnapshotRequestTime < 5000L) return true
        return false
    }

    fun hasActiveFrontClients(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastFrontSnapshotRequestTime < 5000L)
    }

    fun hasActiveBackSnapshotClients(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastSnapshotRequestTime < 5000L)
    }

    fun broadcastFrame(jpegData: ByteArray) {
        latestBackFrame.set(jpegData)
    }
    
    fun broadcastFrameFront(jpegData: ByteArray) {
        latestFrontFrame.set(jpegData)
    }

    fun hasMicClients(): Boolean {
        synchronized(micClients) {
            return micClients.isNotEmpty()
        }
    }

    fun broadcastAudio(pcmData: ByteArray) {
        synchronized(micClients) {
            val iterator = micClients.iterator()
            while (iterator.hasNext()) {
                val queue = iterator.next()
                if (queue.remainingCapacity() == 0) queue.poll()
                try { queue.offer(pcmData) } catch (e: Exception) { iterator.remove() }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
            res.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, x-auth-pin, x-auth-token, x-session-id")
            return res
        }

        val uri = session.uri
        val clientSessionId = session.parms["session_id"] ?: session.headers["x-session-id"]
        if (!clientSessionId.isNullOrBlank()) {
            recordSessionActivity(clientSessionId)
        }
        
        // Block unauthenticated access to protected endpoints
        val publicEndpoints = setOf("/auth", "/p2p_state", "/remote", "/p2p", "/p2p_viewer.html", "/download_viewer")
        if (uri.startsWith("/static/")) return serveStatic(session, uri.removePrefix("/static/"))
        if (uri !in publicEndpoints && uri != "/") {
            if (!isRequestAuthorized(session)) return unauthorizedResponse()
            service?.onClientActivity()
        }

        when (uri) {
            "/auth" -> {
                if (isRequestAuthorized(session)) {
                    val currentPin = P2pSignalingManager.roomPin
                    val sid = session.parms["session_id"] ?: session.headers["x-session-id"] ?: java.util.UUID.randomUUID().toString()
                    authenticatedSessions[sid] = System.currentTimeMillis()
                    val json = JSONObject().put("success", true).put("authenticated", true).put("sessionId", sid).put("token", currentPin).toString()
                    val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    resp.addHeader("Set-Cookie", "auth_pin=$currentPin; Path=/; Max-Age=86400; SameSite=Lax; HttpOnly")
                    return resp
                } else {
                    val locked = pinGuard.isLocked(session.remoteIpAddress ?: "unknown", System.currentTimeMillis())
                    val message = if (locked) "Zu viele Versuche. Bitte 5 Minuten warten." else "PIN ist falsch"
                    val json = JSONObject().put("success", false).put("authenticated", false).put("locked", locked).put("error", message).toString()
                    val resp = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", json)
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    return resp
                }
            }
            "/" -> {
                if (!isRequestAuthorized(session)) {
                    val loginHtml = WebPages.login()
                    val res = newFixedLengthResponse(Response.Status.OK, "text/html", loginHtml)
                    res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    return res
                }

                val resp = newFixedLengthResponse(Response.Status.OK, "text/html", WebPages.index(controller, service))
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return resp
            }
            "/webrtc_offer" -> {
                if (session.method == Method.POST) {
                    try {
                        val buffer = readBody(session, MAX_SIGNALING_BODY)
                            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid offer")
                        val offerJsonStr = String(buffer, Charsets.UTF_8)
                        val peerId = session.remoteIpAddress ?: "lan-viewer"
                        val answerJson = service?.webRtcManager?.handleOffer(offerJsonStr, peerId = peerId, clientIp = session.remoteIpAddress)
                        if (answerJson != null) {
                            val res = newFixedLengthResponse(Response.Status.OK, "application/json", answerJson)
                            res.addHeader("Access-Control-Allow-Origin", "*")
                            return res
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MjpegServer", "WebRTC offer failed", e)
                    }
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "WebRTC offer failed")
                }
            }
            "/webrtc_candidate" -> {
                if (session.method == Method.POST) {
                    try {
                        val buffer = readBody(session, MAX_SIGNALING_BODY)
                            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid candidate")
                        val candidateJson = JSONObject(String(buffer, Charsets.UTF_8))
                        val candidateSdp = candidateJson.getString("candidate")
                        val sdpMid = candidateJson.optString("sdpMid", "0")
                        val sdpMLineIndex = candidateJson.optInt("sdpMLineIndex", 0)
                        val peerId = session.remoteIpAddress ?: "lan-viewer"
                        service?.webRtcManager?.addRemoteCandidate(sdpMid, sdpMLineIndex, candidateSdp, peerId = peerId, clientIp = session.remoteIpAddress)
                        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                    } catch (e: Exception) {
                        android.util.Log.w("MjpegServer", "Invalid ICE candidate", e)
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid candidate")
                    }
                }
            }
            "/webrtc_candidates" -> {
                val list = service?.webRtcManager?.getGatheredCandidates(session.remoteIpAddress)
                val arr = JSONArray()
                if (list != null) {
                    synchronized(list) {
                        for (c in list) {
                            val cJson = JSONObject().apply {
                                put("candidate", c.sdp)
                                put("sdpMid", c.sdpMid)
                                put("sdpMLineIndex", c.sdpMLineIndex)
                            }
                            arr.put(cJson)
                        }
                    }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
                res.addHeader("Access-Control-Allow-Origin", "*")
                return res
            }
            "/snapshot", "/snapshot_front" -> {
                val isFront = uri == "/snapshot_front"
                if (isFront) {
                    lastFrontSnapshotRequestTime = System.currentTimeMillis()
                    if (service?.isFrontCameraEnabled?.get() != true) {
                        return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "Front camera disabled")
                    }
                } else {
                    lastSnapshotRequestTime = System.currentTimeMillis()
                }
                val frame = if (isFront) latestFrontFrame.get() else latestBackFrame.get()
                if (frame == null) {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No frame yet")
                }
                val response = newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(frame), frame.size.toLong())
                response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                response.addHeader("Pragma", "no-cache")
                return response
            }
            "/capture" -> {
                val photo = service?.takeHighQualityPhoto()
                if (photo != null) {
                    val timestamp = System.currentTimeMillis()
                    val response = newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(photo), photo.size.toLong())
                    response.addHeader("Content-Disposition", "attachment; filename=\"capture_$timestamp.jpg\"")
                    response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    response.addHeader("Access-Control-Allow-Origin", "*")
                    return response
                } else {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Could not capture photo")
                }
            }
            "/drive_state" -> {
                val email = GoogleDriveBackupManager.connectedAccountEmail ?: ""
                val isConnected = email.isNotBlank()
                if (session.method == Method.POST) {
                    session.parms["photoBackup"]?.toBooleanStrictOrNull()?.let {
                        GoogleDriveBackupManager.isAutoBackupEnabled = it
                    }
                    session.parms["clipBackup"]?.toBooleanStrictOrNull()?.let {
                        GoogleDriveBackupManager.isClipBackupEnabled = it
                    }
                    service?.let { GoogleDriveBackupManager.save(it) }
                }
                val json = JSONObject()
                    .put("connected", isConnected)
                    .put("email", email)
                    .put("photoBackup", GoogleDriveBackupManager.isAutoBackupEnabled)
                    .put("clipBackup", GoogleDriveBackupManager.isClipBackupEnabled)
                    .put("lastStatus", GoogleDriveBackupManager.lastBackupStatus)
                    .toString()
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                res.addHeader("Access-Control-Allow-Origin", "*")
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return res
            }
            "/ai_state" -> {
                if (session.method == Method.POST) {
                    session.parms["enabled"]?.toBooleanStrictOrNull()?.let {
                        AiMotionDetector.isEnabled = it
                    }
                    session.parms["confidence"]?.toFloatOrNull()?.let {
                        AiMotionDetector.confidenceThreshold = (it / 100f).coerceIn(0.2f, 0.95f)
                    }
                    session.parms["targetClass"]?.let {
                        if (it.isNotBlank()) AiMotionDetector.targetClass = it
                    }
                    session.parms["cooldown"]?.toIntOrNull()?.let {
                        AiMotionDetector.cooldownSeconds = it.coerceIn(5, 600)
                    }
                    session.parms["autoSiren"]?.toBooleanStrictOrNull()?.let {
                        AiMotionDetector.isAutoSirenEnabled = it
                    }
                    session.parms["zoneEnabled"]?.toBooleanStrictOrNull()?.let {
                        AiMotionDetector.isZoneEnabled = it
                    }
                    session.parms["zoneLeft"]?.toFloatOrNull()?.let {
                        AiMotionDetector.zoneLeft = it.coerceIn(0.0f, 1.0f)
                    }
                    session.parms["zoneTop"]?.toFloatOrNull()?.let {
                        AiMotionDetector.zoneTop = it.coerceIn(0.0f, 1.0f)
                    }
                    session.parms["zoneRight"]?.toFloatOrNull()?.let {
                        AiMotionDetector.zoneRight = it.coerceIn(0.0f, 1.0f)
                    }
                    session.parms["zoneBottom"]?.toFloatOrNull()?.let {
                        AiMotionDetector.zoneBottom = it.coerceIn(0.0f, 1.0f)
                    }
                    session.parms["webhookAlarm"]?.toBooleanStrictOrNull()?.let {
                        AiMotionDetector.isWebhookAlarmEnabled = it
                    }
                    session.parms["autoClip"]?.toBooleanStrictOrNull()?.let {
                        AiMotionDetector.isAutoClipEnabled = it
                    }
                    session.parms["autoDrive"]?.toBooleanStrictOrNull()?.let {
                        AiMotionDetector.isAutoDriveEnabled = it
                    }
                    service?.let { AiMotionDetector.save(it) }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", AiMotionDetector.getAiStateJson())
                res.addHeader("Access-Control-Allow-Origin", "*")
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return res
            }
            "/ai_boxes" -> {
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", AiMotionDetector.getBoxesJson())
                res.addHeader("Access-Control-Allow-Origin", "*")
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return res
            }
            "/test_alarm" -> {
                if (session.method == Method.POST) {
                    // sendAlarm delivers asynchronously, so this only confirms the request was queued.
                    WebhookManager.sendAlarm("Test-Alarm aus dem Web-Interface")
                    val json = JSONObject().put("success", true).put("message", "Test-Alarm gesendet").toString()
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/upload_clip" -> {
                if (session.method == Method.POST) {
                    val filename = (session.parms["name"] ?: "clip_${System.currentTimeMillis()}.webm")
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                    val svc = service
                    val clipData = if (svc != null) readBody(session, MAX_CLIP_BODY) else null
                    if (clipData != null && svc != null) {
                        GoogleDriveBackupManager.uploadClipAsync(
                            svc,
                            clipData,
                            filename,
                            if (filename.endsWith(".mp4")) "video/mp4" else "video/webm"
                        )
                        val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true,\"bytes\":${clipData.size}}")
                        res.addHeader("Access-Control-Allow-Origin", "*")
                        return res
                    }
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No data")
                }
            }
            "/set_photo_quality" -> {
                if (session.method == Method.POST) {
                    val q = session.parms["value"]?.toIntOrNull()?.coerceIn(10, 100) ?: 95
                    service?.setPhotoQuality(q)
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"photoQuality\": $q}")
                }
            }
            "/toggle_fast_photo" -> {
                if (session.method == Method.POST) {
                    val enabled = session.parms["enabled"]?.toBoolean() ?: false
                    service?.setFastPhotoMode(enabled)
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"fastPhotoMode\": $enabled}")
                }
            }
            "/set_exposure" -> {
                if (session.method == Method.POST || session.method == Method.GET) {
                    val indexStr = session.parms["index"] ?: session.parms["value"] ?: "0"
                    val idx = indexStr.toIntOrNull() ?: 0
                    val newIdx = controller.setExposureCompensation(idx)
                    val step = controller.getExposureStep()
                    val ev = newIdx * step
                    val json = """{"success":true,"exposure":$newIdx,"ev":$ev}"""
                    val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    return resp
                }
            }
            "/exposure_state" -> {
                val current = controller.getExposureCompensation()
                val range = controller.getExposureRange()
                val step = controller.getExposureStep()
                val ev = current * step
                val json = """{"min":${range.first},"max":${range.second},"step":$step,"current":$current,"ev":$ev}"""
                val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                resp.addHeader("Access-Control-Allow-Origin", "*")
                return resp
            }
            "/set_zoom" -> {
                if (session.method == Method.POST || session.method == Method.GET) {
                    val ratioStr = session.parms["ratio"] ?: session.parms["value"] ?: "1.0"
                    val ratio = ratioStr.toFloatOrNull() ?: 1.0f
                    val newRatio = controller.setZoomRatio(ratio)
                    val json = """{"success":true,"zoom":$newRatio}"""
                    val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    return resp
                }
            }
            "/zoom_state" -> {
                val current = controller.getZoomRatio()
                val range = controller.getZoomRange()
                val json = """{"min":${range.first},"max":${range.second},"current":$current}"""
                val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                resp.addHeader("Access-Control-Allow-Origin", "*")
                return resp
            }
            "/heartbeat" -> {
                val sid = session.parms["session_id"]
                if (sid != null) recordSessionActivity(sid)
                val count = getViewerCount()
                val json = "{\"viewers\": $count}"
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                res.addHeader("Access-Control-Allow-Origin", "*")
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return res
            }
            "/leave_session" -> {
                val sid = session.parms["session_id"]
                if (sid != null) removeSession(sid)
                val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                res.addHeader("Access-Control-Allow-Origin", "*")
                return res
            }
            "/location" -> {
                val json = "{\"lat\": $currentLat, \"lng\": $currentLng}"
                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            "/set_gps_interval" -> {
                if (session.method == Method.POST) {
                    val interval = session.parms["interval"]?.toIntOrNull() ?: 300
                    service?.setLocationInterval(interval)
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"gpsInterval\": $interval}")
                }
            }
            "/settings" -> {
                if (session.method == Method.POST) {
                    val params = session.parms
                    params["quality"]?.toIntOrNull()?.let { q ->
                        service?.setStreamingQuality(q.coerceIn(10, 95))
                    }
                    params["fps"]?.toIntOrNull()?.let { f ->
                        service?.setStreamingFps(f.coerceIn(2, 60))
                    }
                    params["res"]?.let { r ->
                        service?.setStreamingResolution(r)
                    }
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                }
            }
            "/toggle_selfie" -> {
                if (session.method == Method.POST) {
                    val supported = service?.isConcurrentSupported() ?: false
                    val enabled = if (supported) {
                        controller.toggleSelfieCamera()
                    } else {
                        false
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"enabled\": $enabled, \"supported\": $supported}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/mic_stream" -> {
                val queue = ArrayBlockingQueue<ByteArray>(15)
                synchronized(micClients) { micClients.add(queue) }
                service?.startMicIfNeeded()
                val stream = object : InputStream() {
                    var currentStream: ByteArrayInputStream? = null
                    override fun read(): Int {
                        if (currentStream != null) {
                            val b = currentStream!!.read()
                            if (b != -1) return b
                        }
                        if (!isRunning.get()) return -1
                        return try {
                            val chunk = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                            if (chunk != null) {
                                currentStream = ByteArrayInputStream(chunk)
                                currentStream!!.read()
                            } else {
                                0
                            }
                        } catch (e: InterruptedException) {
                            -1
                        }
                    }
                    override fun close() {
                        super.close()
                        synchronized(micClients) { micClients.remove(queue) }
                        service?.stopMicIfIdle()
                    }
                }
                val response = newChunkedResponse(Response.Status.OK, "application/octet-stream", stream)
                response.addHeader("Connection", "keep-alive")
                response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                response.addHeader("Access-Control-Allow-Origin", "*")
                return response
            }
            "/audio_state" -> {
                if (session.method == Method.POST) {
                    val enabled = session.parameters["enabled"]?.firstOrNull()?.lowercase() == "true"
                    controller.setRemoteAudioEnabled(enabled)
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"audioEnabled\": $enabled}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/toggle_flashlight" -> {
                if (session.method == Method.POST) {
                    val on = controller.toggleFlashlight()
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"flashlight\": $on}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/toggle_camera", "/toggle_wide_angle" -> {
                if (session.method == Method.POST) {
                    val isWide = controller.toggleWideAngle()
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"wideAngle\": $isWide}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/switch_camera" -> {
                if (session.method == Method.POST) {
                    val facing = controller.switchCameraFacing()
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"facing\": \"$facing\"}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/megafon" -> {
                if (session.method == Method.POST) {
                    val active = session.parms["active"] == "true"
                    val result = controller.setMegafon(active)
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"active\": $result}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/set_speaker_volume" -> {
                if (session.method == Method.POST) {
                    val volPercent = session.parms["vol"]?.toIntOrNull() ?: 100
                    service?.setSpeakerVolumePercent(volPercent)
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"volume\": $volPercent}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/megafon_pcm" -> {
                if (session.method == Method.POST) {
                    try {
                        readBody(session, MAX_PCM_BODY)?.let { service?.playMegafonPcm(it) }
                    } catch (e: Exception) {
                        android.util.Log.w("MjpegServer", "Megafon PCM failed", e)
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/play_siren" -> {
                if (session.method == Method.POST) {
                    val activeParam = session.parms["active"]
                    if (activeParam != null) {
                        val active = activeParam == "true"
                        if (active) {
                            AlarmSoundManager.playSiren(0)
                        } else {
                            AlarmSoundManager.stopSiren()
                        }
                    } else {
                        if (AlarmSoundManager.isSirenPlaying()) {
                            AlarmSoundManager.stopSiren()
                        } else {
                            val duration = session.parms["duration"]?.toIntOrNull() ?: 0
                            AlarmSoundManager.playSiren(duration)
                        }
                    }
                    val isPlaying = AlarmSoundManager.isSirenPlaying()
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"siren\": $isPlaying}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/night_boost" -> {
                if (session.method == Method.POST) {
                    val boost = session.parms["boost"]?.toBooleanStrictOrNull() ?: false
                    val torch = session.parms["torch"]?.toBooleanStrictOrNull() ?: false
                    service?.autoNightBoostEnabled?.set(boost)
                    service?.autoTorchNightEnabled?.set(torch)
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"boost\": $boost, \"torch\": $torch}")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/set_webhook_url" -> {
                if (session.method == Method.POST) {
                    val url = session.parms["url"] ?: ""
                    WebhookManager.alarmWebhookUrl = url
                    WebhookManager.alarmWebhookEnabled = url.isNotBlank()
                    service?.let { WebhookManager.save(it) }
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().put("success", true).put("url", url).toString())
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
            "/p2p_state" -> {
                val json = P2pSignalingManager.getP2pStateJson(includePin = isRequestAuthorized(session))
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                res.addHeader("Access-Control-Allow-Origin", "*")
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return res
            }
            "/remote", "/p2p", "/p2p_viewer.html" -> {
                val res = newFixedLengthResponse(Response.Status.OK, "text/html", WebPages.viewer())
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return res
            }
            "/download_viewer" -> {
                val res = newFixedLengthResponse(Response.Status.OK, "text/html", WebPages.viewer())
                res.addHeader("Content-Disposition", "attachment; filename=\"CameraLive_Remote_Viewer.html\"")
                return res
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun serveStatic(session: IHTTPSession, path: String): Response {
        val asset = WebPages.staticAsset(path)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        val cacheControl = "public, max-age=86400, must-revalidate"
        if (session.headers["if-none-match"] == asset.etag) {
            val notModified = newFixedLengthResponse(Response.Status.NOT_MODIFIED, asset.mime, "")
            notModified.addHeader("ETag", asset.etag)
            notModified.addHeader("Cache-Control", cacheControl)
            return notModified
        }
        val res = newFixedLengthResponse(Response.Status.OK, asset.mime, ByteArrayInputStream(asset.bytes), asset.bytes.size.toLong())
        res.addHeader("ETag", asset.etag)
        res.addHeader("Cache-Control", cacheControl)
        res.addHeader("Access-Control-Allow-Origin", "*")
        return res
    }

    override fun stop() {
        isRunning.set(false)
        synchronized(micClients) { for (c in micClients) c.offer(ByteArray(0)); micClients.clear() }
        super.stop()
    }
}
