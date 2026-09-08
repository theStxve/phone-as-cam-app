package com.example.cameralive

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    // Base audio created in init() – never disposed until release()
    private var baseAudioSource: AudioSource? = null
    private var baseAudioTrack: AudioTrack? = null

    // Per-session audio created in handleOffer() – only these are safe to dispose on reconnect
    private var sessionAudioSource: AudioSource? = null
    private var sessionAudioTrack: AudioTrack? = null

    private var currentPeerConnection: PeerConnection? = null
    private var currentVideoSender: RtpSender? = null
    private val isPeerConnected = java.util.concurrent.atomic.AtomicBoolean(false)
    val gatheredCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())

    @Volatile private var currentWidth = 640
    @Volatile private var currentHeight = 480
    @Volatile private var currentTargetFps = 20

    fun hasActivePeer(): Boolean = isPeerConnected.get()

    fun updateResolution(width: Int, height: Int) {
        currentWidth = width
        currentHeight = height
        try {
            videoSource?.adaptOutputFormat(currentWidth, currentHeight, currentTargetFps)
            Log.i(TAG, "WebRTC Resolution adapted to ${currentWidth}x${currentHeight} @ ${currentTargetFps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WebRTC resolution", e)
        }
    }

    fun updateFps(fps: Int) {
        currentTargetFps = fps
        try {
            videoSource?.adaptOutputFormat(currentWidth, currentHeight, fps)
            currentVideoSender?.let { sender ->
                val params = sender.parameters
                for (encoding in params.encodings) {
                    encoding.maxFramerate = fps
                }
                sender.parameters = params
            }
            Log.i(TAG, "WebRTC FPS adapted to $fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WebRTC FPS", e)
        }
    }

    fun updateQuality(quality: Int) {
        try {
            // Map quality (10 - 100) to bitrate:
            // 10% -> 400 kbps, 50% -> 2.5 Mbps, 80% -> 5.0 Mbps, 100% -> 8.0 Mbps
            val clampedQuality = quality.coerceIn(10, 100)
            val maxBitrateBps = (clampedQuality * 75_000 + 200_000).coerceIn(400_000, 8_500_000)
            val minBitrateBps = (maxBitrateBps / 4).coerceAtLeast(150_000)

            currentVideoSender?.let { sender ->
                val params = sender.parameters
                for (encoding in params.encodings) {
                    encoding.maxBitrateBps = maxBitrateBps
                    encoding.minBitrateBps = minBitrateBps
                }
                sender.parameters = params
            }
            Log.i(TAG, "WebRTC Bitrate updated for quality $quality%: max=${maxBitrateBps / 1000}kbps, min=${minBitrateBps / 1000}kbps")
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

        val audioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(org.webrtc.audio.JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(org.webrtc.audio.JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(pcfOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Video
        videoSource = peerConnectionFactory?.createVideoSource(false)
        videoSource?.adaptOutputFormat(640, 480, initialFps)
        videoSource?.capturerObserver?.onCapturerStarted(true)
        videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)

        // Base audio
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        baseAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        baseAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", baseAudioSource)
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
                "profile-level-id=42e01f;packetization-mode=1;level-asymmetry-allowed=1"
            } else {
                "${match.value};packetization-mode=1;level-asymmetry-allowed=1"
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

    fun addRemoteCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, clientIp: String?) {
        val pc = currentPeerConnection ?: return
        try {
            val cand = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
            pc.addIceCandidate(cand)
            Log.d(TAG, "Added remote candidate: $candidateSdp")

            // If the candidate contains an mDNS .local hostname and we know clientIp, add an unmasked candidate
            if (candidateSdp.contains(".local") && !clientIp.isNullOrBlank()) {
                val parts = candidateSdp.split(" ")
                if (parts.size >= 5) {
                    val replaced = candidateSdp.replace(Regex("[a-zA-Z0-9-]+\\.local"), clientIp)
                    val unmaskedCand = IceCandidate(sdpMid, sdpMLineIndex, replaced)
                    pc.addIceCandidate(unmaskedCand)
                    Log.d(TAG, "Added unmasked browser candidate: $replaced")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding remote candidate", e)
        }
    }

    fun handleOffer(offerJsonStr: String, clientIp: String? = null): String? {
        val factory = peerConnectionFactory ?: return null
        val offerObj = JSONObject(offerJsonStr)
        val offerSdp = offerObj.getString("sdp")

        // Clear previous candidates and close previous PC
        gatheredCandidates.clear()
        currentPeerConnection?.close()
        currentPeerConnection = null

        // Stop fallback mic so WebRTC gets exclusive access to microphone hardware
        (context as? CameraStreamingService)?.stopMicIfIdle(force = true)

        // Dispose only the PREVIOUS SESSION audio tracks (never the base ones from init())
        try {
            sessionAudioTrack?.dispose()
            sessionAudioSource?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing previous session audio", e)
        }
        sessionAudioTrack = null
        sessionAudioSource = null

        // Create fresh session audio with high-quality constraints for this peer connection
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }
        val freshAudioSource = factory.createAudioSource(audioConstraints)
        sessionAudioSource = freshAudioSource
        val freshAudioTrack = factory.createAudioTrack("ARDAMSa1", freshAudioSource)
        freshAudioTrack.setEnabled(true)
        sessionAudioTrack = freshAudioTrack

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        val gatheringLatch = CountDownLatch(1)

        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling State: $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        isPeerConnected.set(true)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        isPeerConnected.set(false)
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE Gathering State: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    gatheringLatch.countDown()
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    Log.d(TAG, "Phone gathered candidate: ${candidate.sdp}")
                    gatheredCandidates.add(candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Incoming WebRTC track: ${receiver?.track()?.kind()} id=${receiver?.track()?.id()}")
                receiver?.track()?.setEnabled(true)
            }
        }

        val pc = factory.createPeerConnection(rtcConfig, pcObserver) ?: return null
        currentPeerConnection = pc

        val streamIds = listOf("ARDAMS")
        val rtpSender = videoTrack?.let { pc.addTrack(it, streamIds) }
        currentVideoSender = rtpSender
        pc.addTrack(freshAudioTrack, streamIds)

        val currentFps = (context as? CameraStreamingService)?.maxFps?.get() ?: 20
        val currentQuality = (context as? CameraStreamingService)?.jpegQuality?.get() ?: 20
        val clampedQuality = currentQuality.coerceIn(10, 100)
        val initialMaxBitrate = (clampedQuality * 40_000).coerceIn(400_000, 5_000_000)
        val initialMinBitrate = (initialMaxBitrate / 4).coerceAtLeast(150_000)
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
                Log.w(TAG, "Failed to set sender parameters", e)
            }
        }

        val sdpLatch = CountDownLatch(1)

        // 1. Set Remote Description (Offer)
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // Add any candidates included in the offer
                val browserCandidates = offerObj.optJSONArray("candidates")
                if (browserCandidates != null) {
                    for (i in 0 until browserCandidates.length()) {
                        val c = browserCandidates.getJSONObject(i)
                        val sdp = c.getString("candidate")
                        val mid = c.optString("sdpMid", "0")
                        val mline = c.optInt("sdpMLineIndex", 0)
                        addRemoteCandidate(mid, mline, sdp, clientIp)
                    }
                }

                // 2. Create Answer
                val constraints = MediaConstraints()
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        // Force Constrained Baseline profile via SDP munging:
                        // Replace any H.264 High Profile (profile-level-id=64xxxx) with
                        // Constrained Baseline (42e01f) to avoid CABAC/B-frame encoding latency.
                        val mungedSdp = forceBaselineProfile(desc.description)
                        val mungedDesc = SessionDescription(desc.type, mungedSdp)
                        Log.d(TAG, "SDP answer munged: High Profile → Constrained Baseline")

                        // 3. Set Local Description (Answer)
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                sdpLatch.countDown()
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "setLocalDescription failed: $error")
                                sdpLatch.countDown()
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, mungedDesc)
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "createAnswer failed: $error")
                        sdpLatch.countDown()
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed: $error")
                sdpLatch.countDown()
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDesc)

        sdpLatch.await(3, TimeUnit.SECONDS)

        // Wait up to 1.2 seconds for ICE candidates to gather
        gatheringLatch.await(1200, TimeUnit.MILLISECONDS)

        val finalLocalDesc = pc.localDescription ?: return null

        // SYNTHESIZE CANDIDATES FOR TAILSCALE / VPN / ALL LOCAL INTERFACES
        // If WebRTC gathered a UDP candidate, we know the UDP port listening on 0.0.0.0
        val deviceIps = getAllDeviceIpv4s()
        val existingCandSdps = synchronized(gatheredCandidates) { gatheredCandidates.map { it.sdp }.toMutableList() }
        
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
                    gatheredCandidates.add(synthCand0)
                    gatheredCandidates.add(synthCand1)
                    Log.i(TAG, "Synthesized local/VPN candidate: $synthSdp")
                }
            }
        }

        val candidatesJsonArr = JSONArray()
        synchronized(gatheredCandidates) {
            for (c in gatheredCandidates) {
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

    /** Mutes/unmutes the phone microphone track sent via WebRTC.
     *  Call this when the browser viewer enables/disables audio to save battery. */
    fun setAudioEnabled(enabled: Boolean) {
        sessionAudioTrack?.setEnabled(enabled)
        baseAudioTrack?.setEnabled(enabled)
        android.util.Log.d("WebRtcManager", "Audio track enabled=$enabled")
    }

    fun release() {
        isPeerConnected.set(false)
        currentVideoSender = null
        currentPeerConnection?.close()
        currentPeerConnection = null

        videoSource?.capturerObserver?.onCapturerStopped()
        videoTrack?.dispose()
        videoSource?.dispose()

        // Dispose session audio first, then base audio
        try { sessionAudioTrack?.dispose() } catch (e: Exception) {}
        try { sessionAudioSource?.dispose() } catch (e: Exception) {}
        sessionAudioTrack = null
        sessionAudioSource = null

        try { baseAudioTrack?.dispose() } catch (e: Exception) {}
        try { baseAudioSource?.dispose() } catch (e: Exception) {}

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        eglBase?.release()
        eglBase = null
    }
}
