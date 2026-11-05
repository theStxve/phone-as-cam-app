package com.example.cameralive

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        const val MAX_CONCURRENT_PEERS = 4
    }

    class PeerSession(
        val peerId: String,
        val peerConnection: PeerConnection,
        var videoSender: RtpSender? = null,
        val gatheredCandidates: MutableList<IceCandidate> = Collections.synchronizedList(mutableListOf()),
        val pendingRemoteCandidates: MutableList<IceCandidate> = Collections.synchronizedList(mutableListOf()),
        @Volatile var isRemoteDescriptionSet: Boolean = false,
        var dataChannel: DataChannel? = null,
        var onLocalCandidateGathered: ((IceCandidate) -> Unit)? = null,
        val isConnected: AtomicBoolean = AtomicBoolean(false),
        val createdAt: Long = System.currentTimeMillis()
    )

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var audioDeviceModule: org.webrtc.audio.JavaAudioDeviceModule? = null
    private var sharedAudioSource: AudioSource? = null
    private var sharedAudioTrack: AudioTrack? = null

    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private var talkbackActive = false

    var onLocalCandidateGathered: ((IceCandidate) -> Unit)? = null

    @Volatile private var currentWidth = 640
    @Volatile private var currentHeight = 480
    @Volatile private var currentTargetFps = 20

    fun hasActivePeer(): Boolean = sessions.values.any { it.isConnected.get() }
    fun getActivePeerCount(): Int = sessions.values.count { it.isConnected.get() }

    val gatheredCandidates: List<IceCandidate>
        get() = sessions.values.firstOrNull()?.gatheredCandidates ?: emptyList()

    fun getGatheredCandidates(peerId: String? = null): List<IceCandidate> {
        if (peerId != null) {
            return sessions[peerId]?.gatheredCandidates ?: emptyList()
        }
        return sessions.values.firstOrNull()?.gatheredCandidates ?: emptyList()
    }

    // The camera pipeline already paces frames to the target rate. A second limiter with the same
    // rate would drop every frame that arrives slightly early and lower the real frame rate.
    private fun adapterFps(fps: Int): Int = (fps * 2).coerceAtMost(60)

    fun updateResolution(width: Int, height: Int) {
        currentWidth = width
        currentHeight = height
        try {
            videoSource?.adaptOutputFormat(currentWidth, currentHeight, adapterFps(currentTargetFps))
            Log.i(TAG, "WebRTC Resolution adapted to ${currentWidth}x${currentHeight} @ ${currentTargetFps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WebRTC resolution", e)
        }
    }

    fun updateFps(fps: Int) {
        currentTargetFps = fps
        try {
            videoSource?.adaptOutputFormat(currentWidth, currentHeight, adapterFps(fps))
            sessions.values.forEach { session ->
                session.videoSender?.let { sender ->
                    try {
                        val params = sender.parameters
                        for (encoding in params.encodings) {
                            encoding.maxFramerate = fps
                        }
                        sender.parameters = params
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update WebRTC FPS for ${session.peerId}", e)
                    }
                }
            }
            Log.i(TAG, "WebRTC FPS adapted to $fps across ${sessions.size} peer(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WebRTC FPS", e)
        }
    }

    fun updateQuality(quality: Int) {
        try {
            val maxBitrateBps = StreamBitrate.maxBps(quality)
            val minBitrateBps = StreamBitrate.minBps(quality)

            sessions.values.forEach { session ->
                session.videoSender?.let { sender ->
                    try {
                        val params = sender.parameters
                        for (encoding in params.encodings) {
                            encoding.maxBitrateBps = maxBitrateBps
                            encoding.minBitrateBps = minBitrateBps
                        }
                        sender.parameters = params
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update bitrate for ${session.peerId}", e)
                    }
                }
            }
            Log.i(TAG, "WebRTC Bitrate updated for quality $quality%: max=${maxBitrateBps / 1000}kbps, min=${minBitrateBps / 1000}kbps across ${sessions.size} peer(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WebRTC bitrate for quality $quality", e)
        }
    }

    fun init(initialFps: Int = 20) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        eglBase = EglBase.create()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase?.eglBaseContext,
            true, // enableIntelVp8Encoder
            false // enableH264HighProfile: false for real-time Baseline profile (no B-frames)
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        val pcfOptions = PeerConnectionFactory.Options().apply {
            networkIgnoreMask = 0 // Do not ignore VPN or any interface!
        }

        val adm = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(org.webrtc.audio.JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(org.webrtc.audio.JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .createAudioDeviceModule() as org.webrtc.audio.JavaAudioDeviceModule
        audioDeviceModule = adm

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(pcfOptions)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Video
        videoSource = peerConnectionFactory?.createVideoSource(false)
        videoSource?.adaptOutputFormat(640, 480, adapterFps(initialFps))
        videoSource?.capturerObserver?.onCapturerStarted(true)
        videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)

        // Shared Audio
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }
        sharedAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        sharedAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa1", sharedAudioSource)
        sharedAudioTrack?.setEnabled(false)
    }

    fun pushFrame(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        val source = videoSource ?: return
        try {
            val buffer = NV21Buffer(nv21, width, height, null)
            val videoFrame = VideoFrame(buffer, rotationDegrees, System.nanoTime())
            source.capturerObserver.onFrameCaptured(videoFrame)
            videoFrame.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push frame to WebRTC", e)
        }
    }

    /**
     * Munge SDP to force H.264 Constrained Baseline profile with zero-latency parameters.
     * Replaces any profile-level-id starting with "64" (High) or "4d" (Main)
     * with "42e01f" (Constrained Baseline Level 3.1) to prevent CABAC and B-frame latency.
     */
    private fun forceBaselineProfile(sdp: String): String {
        return sdp.replace(Regex("profile-level-id=[0-9a-fA-F]{6}")) { match ->
            val plid = match.value.substringAfter("=")
            if (plid.startsWith("64", ignoreCase = true) || plid.startsWith("4d", ignoreCase = true)) {
                Log.d(TAG, "SDP munge: $plid → 42e01f")
                "profile-level-id=42e01f"
            } else {
                match.value
            }
        }
    }

    private fun getAllDeviceIpv4s(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null) list.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating interfaces", e)
        }
        return list
    }

    fun addRemoteCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, peerId: String? = null, clientIp: String? = null) {
        val cand = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        val targetSession = if (peerId != null) {
            sessions[peerId]
        } else if (!clientIp.isNullOrBlank()) {
            sessions[clientIp] ?: sessions.values.firstOrNull()
        } else {
            sessions.values.firstOrNull()
        }

        if (targetSession == null) {
            Log.w(TAG, "No session found for candidate (peerId=$peerId, clientIp=$clientIp)")
            return
        }

        val pc = targetSession.peerConnection
        if (!targetSession.isRemoteDescriptionSet) {
            targetSession.pendingRemoteCandidates.add(cand)
            return
        }

        try {
            pc.addIceCandidate(cand)
            // A browser hides its address behind an mDNS name. If we know the client IP, add it directly.
            if (candidateSdp.contains(".local") && !clientIp.isNullOrBlank()) {
                val replaced = candidateSdp.replace(Regex("[a-zA-Z0-9-]+\\.local"), clientIp)
                pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, replaced))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding remote candidate for ${targetSession.peerId}", e)
        }
    }

    /**
     * Handles an incoming WebRTC offer from a peer.
     * Multiple peers can be connected simultaneously.
     */
    @Synchronized
    fun handleOffer(
        offerJsonStr: String,
        peerId: String = "default",
        clientIp: String? = null,
        audioOnByDefault: Boolean = false,
        onLocalCandidate: ((IceCandidate) -> Unit)? = null
    ): String? {
        val factory = peerConnectionFactory ?: return null
        val offerObj = JSONObject(offerJsonStr)
        val offerSdp = offerObj.getString("sdp")

        (context as? CameraStreamingService)?.onClientActivity()

        // 1. If this exact peer already had an open session (e.g. browser refreshed), replace it cleanly
        sessions[peerId]?.let { oldSession ->
            Log.i(TAG, "Replacing existing session for peerId: $peerId")
            disposeSession(oldSession)
        }

        // 2. If at capacity, evict disconnected or oldest session
        if (sessions.size >= MAX_CONCURRENT_PEERS) {
            val disconnected = sessions.values.firstOrNull { !it.isConnected.get() }
            val toEvict = disconnected ?: sessions.values.minByOrNull { it.createdAt }
            if (toEvict != null) {
                Log.w(TAG, "Evicting peer ${toEvict.peerId} to accept new peer $peerId (max $MAX_CONCURRENT_PEERS reached)")
                disposeSession(toEvict)
            }
        }

        // Stop fallback mic so WebRTC gets exclusive access to microphone hardware
        (context as? CameraStreamingService)?.stopMicIfIdle(force = true)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.syncthing.net:3478").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        val gatheringLatch = CountDownLatch(1)
        lateinit var session: PeerSession

        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "[$peerId] Signaling State: $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "[$peerId] ICE Connection State: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        session.isConnected.set(true)
                        (context as? CameraStreamingService)?.let {
                            CameraStreamingService.activeViewers.value = getActivePeerCount()
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        session.isConnected.set(false)
                        (context as? CameraStreamingService)?.let {
                            CameraStreamingService.activeViewers.value = getActivePeerCount()
                        }
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "[$peerId] ICE Gathering State: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    gatheringLatch.countDown()
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    Log.d(TAG, "[$peerId] Gathered candidate: ${candidate.sdp}")
                    session.gatheredCandidates.add(candidate)
                    session.onLocalCandidateGathered?.invoke(candidate)
                    onLocalCandidateGathered?.invoke(candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                if (channel == null) return
                Log.i(TAG, "[$peerId] DataChannel received: ${channel.label()}")
                session.dataChannel = channel
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {
                        Log.i(TAG, "[$peerId] DataChannel state changed: ${channel.state()}")
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        try {
                            val data = ByteArray(buffer.data.remaining())
                            buffer.data.get(data)
                            val str = String(data, Charsets.UTF_8)
                            handleDataChannelMessage(channel, str)
                        } catch (e: Exception) {
                            Log.e(TAG, "[$peerId] Error reading DataChannel message", e)
                        }
                    }
                })
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "[$peerId] Incoming WebRTC track: ${receiver?.track()?.kind()} id=${receiver?.track()?.id()}")
                receiver?.track()?.setEnabled(true)
            }
        }

        val pc = factory.createPeerConnection(rtcConfig, pcObserver) ?: return null
        session = PeerSession(
            peerId = peerId,
            peerConnection = pc,
            onLocalCandidateGathered = onLocalCandidate
        )
        sessions[peerId] = session

        pc.setAudioRecording(audioOnByDefault)
        pc.setAudioPlayout(talkbackActive)
        if (audioOnByDefault) {
            sharedAudioTrack?.setEnabled(true)
        }

        val streamIds = listOf("ARDAMS")
        val rtpSender = videoTrack?.let { pc.addTrack(it, streamIds) }
        session.videoSender = rtpSender
        sharedAudioTrack?.let { pc.addTrack(it, streamIds) }

        val currentFps = (context as? CameraStreamingService)?.maxFps?.get() ?: 20
        val currentQuality = (context as? CameraStreamingService)?.jpegQuality?.get() ?: 20
        val initialMaxBitrate = StreamBitrate.maxBps(currentQuality)
        val initialMinBitrate = StreamBitrate.minBps(currentQuality)
        rtpSender?.let { sender ->
            try {
                val params = sender.parameters
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                for (encoding in params.encodings) {
                    encoding.maxBitrateBps = initialMaxBitrate
                    encoding.minBitrateBps = initialMinBitrate
                    encoding.maxFramerate = currentFps
                }
                sender.parameters = params
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set sender parameters for $peerId", e)
            }
        }

        val sdpLatch = CountDownLatch(1)
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                session.isRemoteDescriptionSet = true

                // Add any candidates included in the offer
                val browserCandidates = offerObj.optJSONArray("candidates")
                if (browserCandidates != null) {
                    for (i in 0 until browserCandidates.length()) {
                        val c = browserCandidates.getJSONObject(i)
                        val sdp = c.getString("candidate")
                        val mid = c.optString("sdpMid", "0")
                        val mline = c.optInt("sdpMLineIndex", 0)
                        addRemoteCandidate(mid, mline, sdp, peerId = peerId, clientIp = clientIp)
                    }
                }

                // Drain any pending remote candidates that arrived before setRemoteDescription
                synchronized(session.pendingRemoteCandidates) {
                    for (c in session.pendingRemoteCandidates) {
                        try {
                            pc.addIceCandidate(c)
                        } catch (e: Exception) {
                            Log.e(TAG, "[$peerId] Error adding drained remote candidate", e)
                        }
                    }
                    session.pendingRemoteCandidates.clear()
                }

                // 2. Create Answer
                val constraints = MediaConstraints()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        val mungedSdp = forceBaselineProfile(desc.description)
                        val mungedDesc = SessionDescription(desc.type, mungedSdp)
                        Log.d(TAG, "[$peerId] SDP answer munged to Constrained Baseline")

                        // 3. Set Local Description (Answer)
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                sdpLatch.countDown()
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "[$peerId] setLocalDescription failed: $error")
                                sdpLatch.countDown()
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, mungedDesc)
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "[$peerId] createAnswer failed: $error")
                        sdpLatch.countDown()
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[$peerId] setRemoteDescription failed: $error")
                sdpLatch.countDown()
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDesc)

        try { sdpLatch.await(3, TimeUnit.SECONDS) } catch (e: InterruptedException) {}
        try { gatheringLatch.await(1200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}

        val finalLocalDesc = pc.localDescription ?: run {
            disposeSession(session)
            return null
        }

        // SYNTHESIZE CANDIDATES FOR TAILSCALE / VPN / ALL LOCAL INTERFACES
        val deviceIps = getAllDeviceIpv4s()
        val existingCandSdps = synchronized(session.gatheredCandidates) { session.gatheredCandidates.map { it.sdp }.toMutableList() }

        var udpPort: Int? = null
        var ufrag: String? = null
        for (candSdp in existingCandSdps) {
            val parts = candSdp.split(" ")
            if (parts.size >= 6 && parts[2].equals("udp", ignoreCase = true)) {
                udpPort = parts[5].toIntOrNull()
                val ufragIdx = parts.indexOf("ufrag")
                if (ufragIdx != -1 && ufragIdx + 1 < parts.size) {
                    ufrag = parts[ufragIdx + 1]
                }
                break
            }
        }

        if (udpPort != null) {
            var synthIdx = 100
            for (ip in deviceIps) {
                val alreadyPresent = existingCandSdps.any { it.contains(" $ip ") }
                if (!alreadyPresent) {
                    val ufragPart = if (ufrag != null) " ufrag $ufrag" else ""
                    val synthSdp = "candidate:synth${synthIdx++} 1 udp 2122000000 $ip $udpPort typ host generation 0$ufragPart"
                    val synthCand0 = IceCandidate("0", 0, synthSdp)
                    val synthCand1 = IceCandidate("1", 1, synthSdp)
                    session.gatheredCandidates.add(synthCand0)
                    session.gatheredCandidates.add(synthCand1)
                    Log.i(TAG, "[$peerId] Synthesized local/VPN candidate: $synthSdp")
                }
            }
        }

        val candidatesJsonArr = JSONArray()
        synchronized(session.gatheredCandidates) {
            for (c in session.gatheredCandidates) {
                val cJson = JSONObject().apply {
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                }
                candidatesJsonArr.put(cJson)
            }
        }

        val responseObj = JSONObject().apply {
            put("type", "answer")
            put("sdp", finalLocalDesc.description)
            put("candidates", candidatesJsonArr)
        }
        return responseObj.toString()
    }

    /** Starts or stops the phone microphone for all active WebRTC sessions. */
    fun setAudioEnabled(enabled: Boolean) {
        sharedAudioTrack?.setEnabled(enabled)
        sessions.values.forEach { session ->
            session.peerConnection.setAudioRecording(enabled)
        }
        Log.d(TAG, "Microphone for WebRTC enabled=$enabled across ${sessions.size} peer(s)")
    }

    /** Starts or stops audio output for browser talkback. */
    fun setTalkbackEnabled(enabled: Boolean) {
        talkbackActive = enabled
        sessions.values.forEach { session ->
            session.peerConnection.setAudioPlayout(enabled)
        }
    }

    private fun handleDataChannelMessage(channel: DataChannel, msgStr: String) {
        try {
            val json = JSONObject(msgStr)
            val action = json.optString("action")
            val token = json.optString("token").ifBlank { json.optString("pin") }

            // Security check for data channel commands
            val requiredPin = P2pSignalingManager.roomPin
            if (requiredPin.isBlank() || token.isBlank() || !secretsMatch(token, requiredPin)) {
                Log.w(TAG, "DataChannel action '$action' blocked: invalid or missing PIN")
                val errJson = JSONObject().apply {
                    put("type", "error")
                    put("message", "Nicht autorisiert: PIN erforderlich")
                }
                val errBytes = errJson.toString().toByteArray(Charsets.UTF_8)
                channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(errBytes), false))
                return
            }

            val svc = context as? CameraStreamingService
            when (action) {
                "toggle_flashlight" -> svc?.toggleFlashlight()
                "toggle_wide"       -> svc?.toggleWideAngle()
                "switch_camera"     -> svc?.switchCameraFacing()
                "set_zoom"          -> json.optDouble("value", 1.0).toFloat().let { svc?.setZoomRatio(it) }
                "set_exposure"      -> json.optInt("index", 0).let { svc?.setExposureCompensation(it) }
                "play_siren"        -> AlarmSoundManager.playSiren(json.optInt("duration", 5))
                "stop_siren"        -> AlarmSoundManager.stopSiren()
                "toggle_siren"      -> if (AlarmSoundManager.isSirenPlaying()) AlarmSoundManager.stopSiren()
                                       else AlarmSoundManager.playSiren(0)
                "set_audio"         -> setAudioEnabled(json.optBoolean("enabled", false))
                "set_ai"            -> {
                    AiMotionDetector.isEnabled = json.optBoolean("enabled", AiMotionDetector.isEnabled)
                    svc?.let { AiMotionDetector.save(it) }
                }
                "set_night"         -> svc?.autoNightBoostEnabled?.set(json.optBoolean("enabled", false))
                "get_boxes"         -> {
                    val reply = """{"type":"ai_boxes","boxes":${AiMotionDetector.getBoxesJson()}}"""
                    channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(reply.toByteArray(Charsets.UTF_8)), false))
                    return
                }
                "get_state"         -> { /* state reply sent below */ }
            }

            broadcastStateUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DataChannel message", e)
        }
    }

    /** Broadcasts state update across all active data channels. */
    fun broadcastStateUpdate() {
        val svc = context as? CameraStreamingService
        val response = JSONObject().apply {
            put("type", "state_update")
            put("flashlight", svc?.isFlashlightActive() ?: false)
            put("wideAngle",  svc?.isWideAngleActive() ?: false)
            put("zoom",       svc?.getZoomRatio() ?: 1.0f)
            put("exposure",   svc?.getExposureCompensation() ?: 0)
            put("siren",      AlarmSoundManager.isSirenPlaying())
            put("nightBoost", svc?.autoNightBoostEnabled?.get() ?: false)
            put("facing",     if (svc?.isFrontFacing() == true) "front" else "back")
            try {
                val ai = JSONObject(AiMotionDetector.getAiStateJson())
                put("aiEnabled",   ai.optBoolean("enabled", false))
                put("aiStatus",    ai.optString("status", ""))
                put("aiLastAlarm", ai.optString("lastAlarm", ""))
            } catch (e: Exception) {
                put("aiEnabled", false)
                put("aiStatus", "")
                put("aiLastAlarm", "")
            }
        }
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        sessions.values.forEach { s ->
            s.dataChannel?.let { dc ->
                if (dc.state() == DataChannel.State.OPEN) {
                    try {
                        dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send state update to ${s.peerId}", e)
                    }
                }
            }
        }
    }

    private fun disposeSession(session: PeerSession) {
        session.isConnected.set(false)
        sessions.remove(session.peerId)
        try {
            session.dataChannel?.unregisterObserver()
            session.dataChannel?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing data channel for ${session.peerId}", e)
        }
        session.dataChannel = null
        try {
            session.peerConnection.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing peer connection for ${session.peerId}", e)
        }
        (context as? CameraStreamingService)?.let {
            CameraStreamingService.activeViewers.value = getActivePeerCount()
        }
        Log.i(TAG, "Disposed session for peer ${session.peerId}. Active peers: ${getActivePeerCount()}")
    }

    @Synchronized
    fun closePeerConnection(peerId: String? = null) {
        if (peerId != null) {
            sessions[peerId]?.let { disposeSession(it) }
        } else {
            sessions.values.toList().forEach { disposeSession(it) }
        }
        Log.i(TAG, "Closed WebRTC peer connection(s)")
    }

    @Synchronized
    fun release() {
        closePeerConnection(null)

        sharedAudioTrack?.dispose()
        sharedAudioSource?.dispose()
        sharedAudioTrack = null
        sharedAudioSource = null

        videoSource?.capturerObserver?.onCapturerStopped()
        videoTrack?.dispose()
        videoSource?.dispose()
        videoTrack = null
        videoSource = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        audioDeviceModule?.release()
        audioDeviceModule = null

        eglBase?.release()
        eglBase = null
    }
}
