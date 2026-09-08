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
}

class MjpegServer(port: Int, private val controller: CameraController) : NanoHTTPD(port) {

    private val micClients = mutableListOf<BlockingQueue<ByteArray>>()
    private val isRunning = AtomicBoolean(true)
    
    // Latest frames for fallback polling mode
    val latestBackFrame = AtomicReference<ByteArray>(null)
    val latestFrontFrame = AtomicReference<ByteArray>(null)
    
    @Volatile var currentLat: Double = 0.0
    @Volatile var currentLng: Double = 0.0
    
    @Volatile var lastSnapshotRequestTime: Long = 0L
    @Volatile var lastFrontSnapshotRequestTime: Long = 0L

    var service: CameraStreamingService? = null

    fun hasActiveClients(): Boolean {
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
        val uri = session.uri
        
        when (uri) {
            "/" -> {
                val currentQuality = service?.jpegQuality?.get() ?: 20
                val currentFps = service?.maxFps?.get() ?: 20
                val currentRes = service?.targetResolution?.get() ?: "480p"
                val currentPhotoQuality = service?.photoJpegQuality?.get() ?: 95
                val isSelfieOn = service?.isFrontCameraEnabled?.get() ?: false
                val selfieDisplay = if (isSelfieOn) "block" else "none"
                val selfieBtnClass = if (isSelfieOn) "btn-selfie active" else "btn-selfie"
                val selfieBtnText = if (isSelfieOn) "🤳 Selfie: An" else "🤳 Selfie: Aus"

                val isFlashOn = controller.isFlashlightActive()
                val isWideOn = controller.isWideAngleActive()
                val isFrontFacing = controller.isFrontFacing()
                val flashClass = if (isFlashOn) "btn-flash active" else "btn-flash"
                val wideClass = if (isWideOn) "btn-zoom active" else "btn-zoom"
                val wideText = if (isWideOn) "🔍 Weit: An" else "🔍 Weitwinkel"
                val switchCamText = if (isFrontFacing) "🔄 Kamera: Selfie" else "🔄 Kamera wechseln"

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Camera Live Stream</title>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin=""/>
                        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
                        <style>
                            * { box-sizing: border-box; }
                            body { margin: 0; padding: 0; background: #000; color: white; font-family: sans-serif; overflow: hidden; height: 100vh; width: 100vw; }
                            
                            .main-stream { position: absolute; top: 0; left: 0; width: 100%; height: 100%; display: flex; justify-content: center; align-items: center; }
                            .main-stream video, .main-stream img { width: 100%; height: 100%; object-fit: contain; }
                            
                            .overlay { position: absolute; border: 2px solid rgba(255,255,255,0.4); border-radius: 8px; overflow: hidden; box-shadow: 0 4px 15px rgba(0,0,0,0.5); z-index: 100; background: #222; }
                            .overlay-label { position: absolute; top: 5px; left: 5px; background: rgba(0,0,0,0.6); padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; z-index: 101; }
                            
                            .selfie-pip { bottom: 15px; right: 15px; width: 240px; height: 180px; }
                            .selfie-pip img { width: 100%; height: 100%; object-fit: cover; }
                            
                            .map-pip { bottom: 15px; left: 15px; width: 280px; height: 200px; }
                            #map { width: 100%; height: 100%; }
                            
                            .controls { position: absolute; top: 15px; right: 15px; display: flex; gap: 8px; z-index: 200; }
                            button { padding: 10px 16px; font-size: 13px; font-weight: bold; border: none; border-radius: 8px; cursor: pointer; color: white; opacity: 0.9; transition: all 0.2s; }
                            button:hover { opacity: 1; transform: scale(1.04); }
                            .btn-flash { background: #007bff; }
                            .btn-flash.active { background: #ffc107; color: #000; box-shadow: 0 0 10px rgba(255, 193, 7, 0.7); }
                            .btn-zoom { background: #6f42c1; }
                            .btn-zoom.active { background: #9d4edd; box-shadow: 0 0 10px rgba(157, 78, 221, 0.7); }
                            .btn-switch { background: #0d6efd; }
                            .btn-rotate { background: #17a2b8; }
                            .btn-selfie { background: #495057; }
                            .btn-selfie.active { background: #e8590c; box-shadow: 0 0 10px rgba(232, 89, 12, 0.6); }
                            .btn-audio { background: #28a745; }
                            .btn-audio.muted { background: #6c757d; }
                            .btn-photo { background: #198754; }
                            .btn-photo:active { background: #146c43; }
                            .btn-megafon { background: #d63384; }
                            .btn-megafon.active { background: #dc3545; box-shadow: 0 0 12px #dc3545; animation: megaPulse 1.2s infinite; }
                            @keyframes megaPulse { 0% { transform: scale(1); } 50% { transform: scale(1.08); } 100% { transform: scale(1); } }
                            
                            .status-badge { position: absolute; top: 15px; left: 15px; z-index: 200; background: rgba(0,0,0,0.75); padding: 8px 14px; border-radius: 20px; font-size: 13px; font-weight: bold; display: flex; align-items: center; gap: 8px; backdrop-filter: blur(4px); }
                            .dot { width: 10px; height: 10px; border-radius: 50%; background: #ffc107; display: inline-block; }
                            .dot.live { background: #28a745; box-shadow: 0 0 8px #28a745; }
                            
                            .settings { position: absolute; top: 55px; left: 15px; z-index: 200; background: rgba(0,0,0,0.8); padding: 12px 14px; border-radius: 10px; font-size: 12px; backdrop-filter: blur(6px); max-width: 250px; border: 1px solid rgba(255,255,255,0.15); box-shadow: 0 4px 15px rgba(0,0,0,0.5); }
                            .settings label { display: flex; justify-content: space-between; align-items: center; margin-bottom: 7px; }
                            .settings input[type=range] { width: 110px; vertical-align: middle; }
                            .settings select { background: #222; color: #fff; border: 1px solid rgba(255,255,255,0.3); border-radius: 4px; padding: 3px 6px; font-size: 11px; }
                            .settings span { display: inline-block; width: 30px; text-align: right; font-weight: bold; }
                            .preset-group { display: grid; grid-template-columns: 1fr 1fr; gap: 5px; margin-bottom: 10px; }
                            .btn-preset { padding: 6px 4px; font-size: 11px; border: 1px solid rgba(255,255,255,0.2); border-radius: 6px; background: rgba(255,255,255,0.08); color: white; cursor: pointer; text-align: center; transition: all 0.2s; font-weight: normal; }
                            .btn-preset:hover { background: rgba(255,255,255,0.22); transform: translateY(-1px); }
                            .btn-preset.active { background: #0d6efd; border-color: #0d6efd; font-weight: bold; box-shadow: 0 0 8px rgba(13,110,253,0.6); }
                            .fps-warning { color: #ffc107; font-size: 11px; margin-top: 4px; margin-bottom: 6px; line-height: 1.3; font-weight: bold; background: rgba(255, 193, 7, 0.15); padding: 5px 8px; border-radius: 6px; border: 1px solid rgba(255, 193, 7, 0.4); }
                        </style>
                    </head>
                    <body>
                        <div class="main-stream">
                            <video id="webrtcVideo" autoplay playsinline muted></video>
                            <img id="mainImg" style="display:none;" alt="Back Camera Fallback" />
                        </div>
                        
                        <div class="overlay selfie-pip" id="selfiePip" style="display: $selfieDisplay;">
                            <div class="overlay-label">🤳 Selfie</div>
                            <img id="frontImg" alt="Front Camera" />
                        </div>
                        
                        <div class="overlay map-pip">
                            <div class="overlay-label">📍 GPS</div>
                            <div id="map"></div>
                        </div>
                        
                        <div class="status-badge" id="badge">
                            <span class="dot" id="statusDot"></span>
                            <span id="statusText">Verbinde WebRTC (H.264)...</span>
                        </div>
                        
                        <div class="settings">
                            <div style="font-weight: bold; margin-bottom: 6px; color: #aaa; font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px;">⚡ Streaming-Presets</div>
                            <div class="preset-group">
                                <button class="btn-preset" onclick="applyPreset('eco')" title="640x480, 15 FPS, 1.2 Mbps">🔋 Eco</button>
                                <button class="btn-preset active" onclick="applyPreset('balanced')" title="720p HD, 20 FPS, 2.5 Mbps">⚖️ Standard</button>
                                <button class="btn-preset" onclick="applyPreset('smooth')" title="720p HD, 30 FPS, 4.5 Mbps">🚀 Smooth</button>
                                <button class="btn-preset" onclick="applyPreset('ultra')" title="1080p FHD, 60 FPS, 8.0 Mbps">🔥 Ultra 60</button>
                            </div>
                            <label>Auflösung:
                                <select id="resSelect" onchange="onResChange(this.value)">
                                    <option value="480p" ${if (currentRes == "480p") "selected" else ""}>480p (VGA)</option>
                                    <option value="720p" ${if (currentRes == "720p") "selected" else ""}>720p (HD)</option>
                                    <option value="1080p" ${if (currentRes == "1080p") "selected" else ""}>1080p (Full HD)</option>
                                </select>
                            </label>
                            <label>FPS: <input type="range" id="fps" min="5" max="60" step="1" value="$currentFps"> <span id="fVal">$currentFps</span></label>
                            <div id="fpsWarning" class="fps-warning" style="display: ${if (currentFps > 20) "block" else "none"};">⚠️ Über 20 FPS steigt der Akkuverbrauch &amp; Hitze an!</div>
                            <label>Stream-Qualität: <input type="range" id="quality" min="10" max="95" step="5" value="$currentQuality"> <span id="qVal">$currentQuality</span></label>
                            <div style="border-top: 1px solid rgba(255,255,255,0.15); margin: 8px 0;"></div>
                            <div style="font-weight: bold; margin-bottom: 6px; color: #aaa; font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px;">📸 Foto-Einstellungen</div>
                            <label>Foto-Qualität: <input type="range" id="photoQuality" min="50" max="100" step="5" value="$currentPhotoQuality"> <span id="pqVal">$currentPhotoQuality</span></label>
                        </div>
                        
                        <div class="controls">
                            <button class="btn-photo" id="photoBtn" onclick="takePhoto()" title="Foto in voller Qualität aufnehmen und herunterladen">📸 Foto</button>
                            <button class="$flashClass" id="flashBtn" onclick="toggleFlash()" title="Taschenlampe an/aus">💡</button>
                            <button class="$wideClass" id="wideBtn" onclick="toggleWideAngle()" title="Weitwinkel (0.5x / Ultra-Wide)">$wideText</button>
                            <button class="btn-switch" id="switchBtn" onclick="switchCamera()" title="Kamera wechseln (Hauptkamera / Frontkamera)">$switchCamText</button>
                            <button class="btn-rotate" onclick="rotateStream()" title="Bild um 90° nach links drehen">⟲ 90°</button>
                            <button class="$selfieBtnClass" id="selfieBtn" onclick="toggleSelfie()" title="Selfie-Kamera PiP an/aus">$selfieBtnText</button>
                            <button class="btn-audio muted" id="audioBtn" title="Audio">🔇 Ton an</button>
                            <button class="btn-megafon" id="megafonBtn" onclick="toggleMegafon()" title="Megafon: Mikrofon an Handy-Lautsprecher">📢 Megafon</button>
                        </div>

                        <script>
                            const videoEl = document.getElementById('webrtcVideo');
                            const mainImg = document.getElementById('mainImg');
                            const statusDot = document.getElementById('statusDot');
                            const statusText = document.getElementById('statusText');
                            const audioBtn = document.getElementById('audioBtn');
                            const selfieBtn = document.getElementById('selfieBtn');
                            const selfiePip = document.getElementById('selfiePip');
                            
                            let pc = null;
                            let webrtcConnected = false;
                            let offerSent = false;
                            const addedCandidateKeys = new Set();
                            let selfieActive = ${if (isSelfieOn) "true" else "false"};
                            let currentRotation = 0;
                            let isAudioEnabled = false;
                            let audioCtx = null;
                            let pcmAbortController = null;

                            // --- Remote Photo Capture & Instant Download ---
                            async function takePhoto() {
                                const btn = document.getElementById('photoBtn');
                                const origText = btn.textContent;
                                btn.textContent = '⏳ Aufnahme...';
                                btn.disabled = true;
                                try {
                                    const res = await fetch('/capture');
                                    if (res.ok) {
                                        const blob = await res.blob();
                                        const url = URL.createObjectURL(blob);
                                        const a = document.createElement('a');
                                        a.href = url;
                                        a.download = 'capture_' + Date.now() + '.jpg';
                                        document.body.appendChild(a);
                                        a.click();
                                        document.body.removeChild(a);
                                        URL.revokeObjectURL(url);
                                    } else {
                                        alert('Fehler bei der Fotoaufnahme (Kamera möglicherweise ausgelastet).');
                                    }
                                } catch (e) {
                                    alert('Foto-Fehler: ' + e);
                                } finally {
                                    btn.textContent = origText;
                                    btn.disabled = false;
                                }
                            }

                            // --- Rotate Stream ---
                            function rotateStream() {
                                currentRotation = (currentRotation - 90) % 360;
                                videoEl.style.transform = "rotate(" + currentRotation + "deg)";
                                mainImg.style.transform = "rotate(" + currentRotation + "deg)";
                            }

                            // --- Flashlight ---
                            async function toggleFlash() {
                                try {
                                    const resp = await fetch('/toggle_flashlight', { method: 'POST' });
                                    const data = await resp.json();
                                    const btn = document.getElementById('flashBtn');
                                    if (data.flashlight) {
                                        btn.classList.add('active');
                                    } else {
                                        btn.classList.remove('active');
                                    }
                                } catch(e) {}
                            }

                            // --- Wide Angle Toggle ---
                            async function toggleWideAngle() {
                                try {
                                    const resp = await fetch('/toggle_wide_angle', { method: 'POST' });
                                    const data = await resp.json();
                                    const btn = document.getElementById('wideBtn');
                                    if (data.wideAngle) {
                                        btn.classList.add('active');
                                        btn.textContent = '🔍 Weit: An';
                                    } else {
                                        btn.classList.remove('active');
                                        btn.textContent = '🔍 Weitwinkel';
                                    }
                                } catch(e) {}
                            }

                            // --- Main Camera Switch (Front <-> Back) ---
                            async function switchCamera() {
                                try {
                                    const resp = await fetch('/switch_camera', { method: 'POST' });
                                    const data = await resp.json();
                                    const switchBtn = document.getElementById('switchBtn');
                                    if (data.facing === 'front') {
                                        switchBtn.textContent = '🔄 Kamera: Selfie';
                                    } else {
                                        switchBtn.textContent = '🔄 Kamera wechseln';
                                    }
                                } catch(e) {}
                            }

                            // --- Selfie Cam Toggle (PiP) ---
                            async function toggleSelfie() {
                                try {
                                    const resp = await fetch('/toggle_selfie', { method: 'POST' });
                                    const data = await resp.json();
                                    if (data.supported === false) {
                                        alert('Dieses Smartphone unterstützt keine gleichzeitige Hardware-Doppelkamera (Concurrent Camera) für Bild-in-Bild.\n\nNutze den Button "🔄 Kamera wechseln", um die Kamera umzuschalten.');
                                        selfiePip.style.display = 'none';
                                        selfieBtn.classList.remove('active');
                                        selfieBtn.textContent = '🤳 Selfie: Aus';
                                        return;
                                    }
                                    selfieActive = data.enabled;
                                    if (selfieActive) {
                                        selfiePip.style.display = 'block';
                                        selfieBtn.classList.add('active');
                                        selfieBtn.textContent = '🤳 Selfie: An';
                                        pollFront();
                                    } else {
                                        selfiePip.style.display = 'none';
                                        selfieBtn.classList.remove('active');
                                        selfieBtn.textContent = '🤳 Selfie: Aus';
                                    }
                                } catch (e) {
                                    console.error('Failed to toggle selfie camera:', e);
                                }
                            }

                            // --- Audio Toggle ---
                            audioBtn.addEventListener('click', async () => {
                                isAudioEnabled = !isAudioEnabled;
                                
                                // Unlock AudioContext on user click gesture (required for Safari & Chrome)
                                if (!audioCtx) {
                                    try {
                                        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                                    } catch (e) {}
                                }
                                if (audioCtx && audioCtx.state === 'suspended') {
                                    try { await audioCtx.resume(); } catch (e) {}
                                }

                                if (isAudioEnabled) {
                                    audioBtn.textContent = '🔊 Ton aus';
                                    audioBtn.classList.remove('muted');

                                    // Unmute video element for WebRTC audio
                                    videoEl.muted = false;
                                    videoEl.volume = 1.0;
                                    videoEl.play().catch(e => console.log('videoEl play:', e));

                                    // If WebRTC is not connected (Snapshot fallback), start PCM stream
                                    if (!webrtcConnected) {
                                        startPcmFallbackAudio();
                                    }
                                } else {
                                    audioBtn.textContent = '🔇 Ton an';
                                    audioBtn.classList.add('muted');
                                    videoEl.muted = true;
                                    stopPcmFallbackAudio();
                                }
                            });

                            // --- Megafon (Talkback to Phone Loudspeaker) ---
                            let audioTc = null;
                            let isMegafonActive = false;
                            let megafonStream = null;
                            let pcmMegafonContext = null;
                            let pcmMegafonProcessor = null;

                            async function toggleMegafon() {
                                isMegafonActive = !isMegafonActive;
                                const megafonBtn = document.getElementById('megafonBtn');

                                if (isMegafonActive) {
                                    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                                        alert('Mikrofon-Zugriff im Browser erfordert HTTPS oder http://localhost / http://127.0.0.1.\n\nIn Chrome kann die IP freigegeben werden unter:\nchrome://flags/#unsafely-treat-insecure-origin-as-secure');
                                        isMegafonActive = false;
                                        return;
                                    }

                                    try {
                                        megafonStream = await navigator.mediaDevices.getUserMedia({
                                            audio: {
                                                echoCancellation: true,
                                                noiseSuppression: true,
                                                autoGainControl: true
                                            }
                                        });

                                        // Tell phone to set volume to max & route to loudspeaker
                                        await fetch('/megafon?active=true', { method: 'POST' });

                                        const micTrack = megafonStream.getAudioTracks()[0];

                                        if (webrtcConnected && audioTc && audioTc.sender) {
                                            await audioTc.sender.replaceTrack(micTrack);
                                            console.log('Megafon active via WebRTC');
                                        } else {
                                            startPcmMegafonFallback(megafonStream);
                                            console.log('Megafon active via PCM fallback');
                                        }

                                        megafonBtn.classList.add('active');
                                        megafonBtn.textContent = '📢 Megafon: An';
                                    } catch (err) {
                                        console.error('Megafon error:', err);
                                        alert('Mikrofon-Fehler: ' + (err.message || err));
                                        isMegafonActive = false;
                                        megafonBtn.classList.remove('active');
                                        megafonBtn.textContent = '📢 Megafon';
                                    }
                                } else {
                                    try {
                                        await fetch('/megafon?active=false', { method: 'POST' });
                                    } catch (e) {}

                                    if (audioTc && audioTc.sender) {
                                        try { await audioTc.sender.replaceTrack(null); } catch (e) {}
                                    }
                                    if (megafonStream) {
                                        megafonStream.getTracks().forEach(t => t.stop());
                                        megafonStream = null;
                                    }
                                    stopPcmMegafonFallback();

                                    megafonBtn.classList.remove('active');
                                    megafonBtn.textContent = '📢 Megafon';
                                }
                            }

                            function startPcmMegafonFallback(stream) {
                                try {
                                    stopPcmMegafonFallback();
                                    pcmMegafonContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 44100 });
                                    const source = pcmMegafonContext.createMediaStreamSource(stream);
                                    pcmMegafonProcessor = pcmMegafonContext.createScriptProcessor(4096, 1, 1);
                                    source.connect(pcmMegafonProcessor);
                                    pcmMegafonProcessor.connect(pcmMegafonContext.destination);

                                    pcmMegafonProcessor.onaudioprocess = (e) => {
                                        if (!isMegafonActive) return;
                                        const input = e.inputBuffer.getChannelData(0);
                                        const pcm16 = new Int16Array(input.length);
                                        for (let i = 0; i < input.length; i++) {
                                            const s = Math.max(-1, Math.min(1, input[i]));
                                            pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
                                        }
                                        fetch('/megafon_pcm', {
                                            method: 'POST',
                                            headers: { 'Content-Type': 'application/octet-stream' },
                                            body: pcm16.buffer
                                        }).catch(() => {});
                                    };
                                } catch (e) {
                                    console.warn('PCM Megafon fallback error:', e);
                                }
                            }

                            function stopPcmMegafonFallback() {
                                if (pcmMegafonProcessor) {
                                    try { pcmMegafonProcessor.disconnect(); } catch (e) {}
                                    pcmMegafonProcessor = null;
                                }
                                if (pcmMegafonContext) {
                                    try { pcmMegafonContext.close(); } catch (e) {}
                                    pcmMegafonContext = null;
                                }
                            }

                            // --- WebRTC Connection ---
                            async function startWebRtc() {
                                try {
                                    pc = new RTCPeerConnection({
                                        bundlePolicy: 'max-bundle',
                                        rtcpMuxPolicy: 'require',
                                        iceServers: [
                                            { urls: 'stun:stun.cloudflare.com:3478' },
                                            { urls: 'stun:stun.l.google.com:19302' },
                                            { urls: 'stun:stun1.l.google.com:19302' },
                                            { urls: 'stun:stun2.l.google.com:19302' }
                                        ]
                                    });

                                    const browserCandidates = [];

                                    pc.onicecandidate = (event) => {
                                        if (event.candidate) {
                                            console.log('Browser gathered candidate:', event.candidate.candidate);
                                            browserCandidates.push({
                                                candidate: event.candidate.candidate,
                                                sdpMid: event.candidate.sdpMid,
                                                sdpMLineIndex: event.candidate.sdpMLineIndex
                                            });
                                            if (offerSent) {
                                                fetch('/webrtc_candidate', {
                                                    method: 'POST',
                                                    headers: { 'Content-Type': 'application/json' },
                                                    body: JSON.stringify(event.candidate)
                                                }).catch(() => {});
                                            }
                                        }
                                    };

                                    const videoTc = pc.addTransceiver('video', { direction: 'recvonly' });
                                    audioTc = pc.addTransceiver('audio', { direction: 'sendrecv' });

                                    // Prefer H.264 Baseline codec (profile-level-id 42e01f) for zero-latency real-time stream
                                    if (typeof RTCRtpReceiver.getCapabilities === 'function') {
                                        const capabilities = RTCRtpReceiver.getCapabilities('video');
                                        if (capabilities && capabilities.codecs) {
                                            const h264Baseline = capabilities.codecs.filter(c => c.mimeType.toLowerCase() === 'video/h264' && c.sdpFmtpLine && c.sdpFmtpLine.includes('42e01f'));
                                            const h264Other = capabilities.codecs.filter(c => c.mimeType.toLowerCase() === 'video/h264' && (!c.sdpFmtpLine || !c.sdpFmtpLine.includes('42e01f')));
                                            const others = capabilities.codecs.filter(c => c.mimeType.toLowerCase() !== 'video/h264');
                                            if (videoTc.setCodecPreferences) {
                                                videoTc.setCodecPreferences([...h264Baseline, ...h264Other, ...others]);
                                            }
                                        }
                                    }

                                    pc.ontrack = (event) => {
                                        console.log('WebRTC track received:', event.track.kind, event.track.id);
                                        const incomingStream = (event.streams && event.streams[0]) ? event.streams[0] : null;
                                        if (incomingStream) {
                                            if (videoEl.srcObject !== incomingStream) {
                                                videoEl.srcObject = incomingStream;
                                            }
                                        } else {
                                            let stream = videoEl.srcObject;
                                            if (!stream) {
                                                stream = new MediaStream();
                                                videoEl.srcObject = stream;
                                            }
                                            if (!stream.getTracks().some(t => t.id === event.track.id)) {
                                                stream.addTrack(event.track);
                                            }
                                        }
                                        if (isAudioEnabled) {
                                            videoEl.muted = false;
                                            videoEl.volume = 1.0;
                                        }
                                        videoEl.play().catch(e => console.log('videoEl play:', e));

                                        // Zero playout delay & zero jitter buffer for minimal latency
                                        try {
                                            if (event.receiver) {
                                                if ('playoutDelayHint' in event.receiver) event.receiver.playoutDelayHint = 0;
                                                if ('jitterBufferTarget' in event.receiver) event.receiver.jitterBufferTarget = 0;
                                            }
                                            pc.getReceivers().forEach(r => {
                                                if ('playoutDelayHint' in r) r.playoutDelayHint = 0;
                                                if ('jitterBufferTarget' in r) r.jitterBufferTarget = 0;
                                            });
                                        } catch (e) {}
                                    };

                                    pc.oniceconnectionstatechange = () => {
                                        console.log('ICE state:', pc.iceConnectionState);
                                        if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
                                            webrtcConnected = true;
                                            stopPcmFallbackAudio();
                                            statusDot.classList.add('live');
                                            statusDot.style.background = '';
                                            statusText.textContent = '● WebRTC H.264 (<100ms)';
                                            videoEl.style.display = 'block';
                                            mainImg.style.display = 'none';
                                            if (isAudioEnabled) {
                                                videoEl.muted = false;
                                                videoEl.volume = 1.0;
                                            }
                                            videoEl.play().catch(e => console.log('videoEl play:', e));
                                            try {
                                                pc.getReceivers().forEach(r => {
                                                    if ('playoutDelayHint' in r) r.playoutDelayHint = 0;
                                                    if ('jitterBufferTarget' in r) r.jitterBufferTarget = 0;
                                                });
                                            } catch (e) {}
                                        } else if (pc.iceConnectionState === 'failed') {
                                            console.warn('ICE connection failed, checking fallback');
                                            if (!webrtcConnected) {
                                                fallbackToPolling();
                                            }
                                        }
                                    };

                                    // Live RTT monitor
                                    setInterval(async () => {
                                        if (!pc || !webrtcConnected) return;
                                        try {
                                            const stats = await pc.getStats();
                                            let selectedPair = null;
                                            stats.forEach(report => {
                                                if (report.type === 'transport' && report.selectedCandidatePairId) {
                                                    selectedPair = stats.get(report.selectedCandidatePairId);
                                                }
                                            });
                                            if (!selectedPair) {
                                                stats.forEach(report => {
                                                    if (report.type === 'candidate-pair' && (report.nominated || report.selected) && report.state === 'succeeded') {
                                                        selectedPair = report;
                                                    }
                                                });
                                            }
                                            if (!selectedPair) {
                                                stats.forEach(report => {
                                                    if (report.type === 'candidate-pair' && report.state === 'succeeded') {
                                                        selectedPair = report;
                                                    }
                                                });
                                            }
                                            if (selectedPair && selectedPair.currentRoundTripTime !== undefined) {
                                                const rtt = Math.round(selectedPair.currentRoundTripTime * 1000);
                                                statusText.textContent = '● WebRTC H.264 (' + rtt + 'ms RTT)';
                                            }
                                        } catch (e) {}
                                    }, 1500);

                                    // Ultra-fast auto catch-up: Keep video playback tightly locked to live edge (< 35ms)
                                    setInterval(() => {
                                         if (!videoEl || !webrtcConnected) return;
                                         try {
                                             if (videoEl.buffered && videoEl.buffered.length > 0) {
                                                 const liveEnd = videoEl.buffered.end(videoEl.buffered.length - 1);
                                                 const lag = liveEnd - videoEl.currentTime;
                                                 if (lag > 0.12) {
                                                     videoEl.currentTime = liveEnd - 0.01;
                                                 } else if (lag > 0.03) {
                                                     videoEl.playbackRate = 1.06;
                                                 } else {
                                                     videoEl.playbackRate = 1.0;
                                                 }
                                             }
                                         } catch (e) {}
                                     }, 40);

                                    // Create Offer
                                    const offer = await pc.createOffer();
                                    await pc.setLocalDescription(offer);

                                    // Give 800ms for initial candidates
                                    await new Promise(resolve => {
                                        if (pc.iceGatheringState === 'complete') {
                                            resolve();
                                        } else {
                                            const check = () => {
                                                if (pc.iceGatheringState === 'complete') {
                                                    pc.removeEventListener('icegatheringstatechange', check);
                                                    resolve();
                                                }
                                            };
                                            pc.addEventListener('icegatheringstatechange', check);
                                            setTimeout(resolve, 800);
                                        }
                                    });

                                    offerSent = true;

                                    // Send offer with candidates
                                    const resp = await fetch('/webrtc_offer', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({
                                            type: pc.localDescription.type,
                                            sdp: pc.localDescription.sdp,
                                            candidates: browserCandidates
                                        })
                                    });
                                    if (!resp.ok) throw new Error('Offer error: ' + resp.status);

                                    const answerData = await resp.json();
                                    await pc.setRemoteDescription(new RTCSessionDescription({
                                        type: answerData.type,
                                        sdp: answerData.sdp
                                    }));

                                    // Add phone's initial candidates
                                    if (answerData.candidates && Array.isArray(answerData.candidates)) {
                                        for (const c of answerData.candidates) {
                                            addPhoneCandidate(c);
                                        }
                                    }

                                    // Poll for any additional phone candidates
                                    pollPhoneCandidates();

                                    // Watchdog: If no connection after 12 seconds, fallback
                                    setTimeout(() => {
                                        if (!webrtcConnected) {
                                            console.warn('WebRTC watchdog timed out, using fallback');
                                            fallbackToPolling();
                                        }
                                    }, 12000);

                                } catch (e) {
                                    console.error('WebRTC error:', e);
                                    fallbackToPolling();
                                }
                            }

                            async function addPhoneCandidate(c) {
                                if (!c || !c.candidate) return;
                                if (addedCandidateKeys.has(c.candidate)) return;
                                addedCandidateKeys.add(c.candidate);
                                try {
                                    console.log('Adding phone candidate:', c.candidate);
                                    await pc.addIceCandidate(new RTCIceCandidate(c));
                                } catch (err) {
                                    console.warn('Failed to add candidate:', err);
                                }
                            }

                            async function pollPhoneCandidates() {
                                // Continue polling for 6 seconds to gather all direct STUN/reflexive candidates
                                // allowing WebRTC ICE to re-nominate and switch from slow relay (200ms) to fast direct link (50ms)
                                for (let i = 0; i < 15; i++) {
                                    await sleep(350);
                                    try {
                                        const res = await fetch('/webrtc_candidates');
                                        if (res.ok) {
                                            const list = await res.json();
                                            if (Array.isArray(list)) {
                                                for (const c of list) {
                                                    addPhoneCandidate(c);
                                                }
                                            }
                                        }
                                    } catch (e) {}
                                }
                            }

                            async function startPcmFallbackAudio() {
                                stopPcmFallbackAudio();
                                if (!audioCtx) {
                                    try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch(e){}
                                }
                                if (audioCtx && audioCtx.state === 'suspended') {
                                    try { await audioCtx.resume(); } catch(e){}
                                }
                                pcmAbortController = new AbortController();
                                try {
                                    const resp = await fetch('/mic_stream', { signal: pcmAbortController.signal });
                                    const reader = resp.body.getReader();
                                    const sampleRate = 44100;
                                    let nextPlayTime = audioCtx.currentTime;

                                    while (isAudioEnabled) {
                                        const { done, value } = await reader.read();
                                        if (done) break;
                                        if (!value || value.length < 2) continue;

                                        const numSamples = Math.floor(value.length / 2);
                                        const int16 = new Int16Array(value.buffer, value.byteOffset, numSamples);
                                        const float32 = new Float32Array(numSamples);
                                        for (let i = 0; i < numSamples; i++) {
                                            float32[i] = int16[i] / 32768.0;
                                        }

                                        const buf = audioCtx.createBuffer(1, numSamples, sampleRate);
                                        buf.copyToChannel(float32, 0);
                                        const src = audioCtx.createBufferSource();
                                        src.buffer = buf;
                                        src.connect(audioCtx.destination);

                                        const now = audioCtx.currentTime;
                                        if (nextPlayTime < now) {
                                            nextPlayTime = now + 0.01;
                                        }
                                        src.start(nextPlayTime);
                                        nextPlayTime += buf.duration;
                                    }
                                } catch (e) {
                                    if (e.name !== 'AbortError') console.error('PCM Audio fallback error:', e);
                                }
                            }

                            function stopPcmFallbackAudio() {
                                if (pcmAbortController) {
                                    pcmAbortController.abort();
                                    pcmAbortController = null;
                                }
                            }

                            function fallbackToPolling() {
                                if (webrtcConnected) return;
                                statusDot.style.background = '#fd7e14';
                                statusText.textContent = 'Snapshot-Modus (Fallback)';
                                videoEl.style.display = 'none';
                                mainImg.style.display = 'block';
                                if (isAudioEnabled) {
                                    startPcmFallbackAudio();
                                }
                                pollBack();
                            }

                            async function pollBack() {
                                while (!webrtcConnected) {
                                    try {
                                        const resp = await fetch('/snapshot?t=' + Date.now());
                                        if (!resp.ok) { await sleep(200); continue; }
                                        const blob = await resp.blob();
                                        const url = URL.createObjectURL(blob);
                                        const oldUrl = mainImg.src;
                                        mainImg.src = url;
                                        if (oldUrl && oldUrl.startsWith('blob:')) URL.revokeObjectURL(oldUrl);
                                    } catch (e) {
                                        await sleep(500);
                                    }
                                }
                            }

                            async function pollFront() {
                                const frontImg = document.getElementById('frontImg');
                                while (selfieActive) {
                                    try {
                                        const resp = await fetch('/snapshot_front?t=' + Date.now());
                                        if (resp.ok) {
                                            const blob = await resp.blob();
                                            const url = URL.createObjectURL(blob);
                                            const oldUrl = frontImg.src;
                                            frontImg.src = url;
                                            if (oldUrl && oldUrl.startsWith('blob:')) URL.revokeObjectURL(oldUrl);
                                        }
                                    } catch (e) {}
                                    await sleep(350);
                                }
                            }

                            function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

                            startWebRtc();
                            if (selfieActive) {
                                pollFront();
                            }

                            // --- Settings & Presets ---
                            const fpsSlider = document.getElementById('fps');
                            const qualitySlider = document.getElementById('quality');
                            const photoQualitySlider = document.getElementById('photoQuality');
                            const resSelect = document.getElementById('resSelect');
                            const fVal = document.getElementById('fVal');
                            const qVal = document.getElementById('qVal');
                            const pqVal = document.getElementById('pqVal');
                            const fpsWarning = document.getElementById('fpsWarning');

                            function onFpsChange(val) {
                                fVal.textContent = val;
                                if (fpsWarning) {
                                    fpsWarning.style.display = (parseInt(val, 10) > 20) ? 'block' : 'none';
                                }
                                clearPresetActive();
                                fetch('/settings?fps=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
                            }

                            function onQualityChange(val) {
                                qVal.textContent = val;
                                clearPresetActive();
                                fetch('/settings?quality=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
                            }

                            function onPhotoQualityChange(val) {
                                pqVal.textContent = val;
                                fetch('/set_photo_quality?value=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
                            }

                            function onResChange(val) {
                                clearPresetActive();
                                fetch('/settings?res=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
                            }

                            function clearPresetActive() {
                                document.querySelectorAll('.btn-preset').forEach(b => b.classList.remove('active'));
                            }

                            function applyPreset(name) {
                                clearPresetActive();
                                let targetRes = "720p";
                                let targetFps = 20;
                                let targetQuality = 50;

                                if (name === 'eco') {
                                    targetRes = "480p";
                                    targetFps = 15;
                                    targetQuality = 20;
                                } else if (name === 'balanced') {
                                    targetRes = "720p";
                                    targetFps = 20;
                                    targetQuality = 50;
                                } else if (name === 'smooth') {
                                    targetRes = "720p";
                                    targetFps = 30;
                                    targetQuality = 70;
                                } else if (name === 'ultra') {
                                    targetRes = "1080p";
                                    targetFps = 60;
                                    targetQuality = 90;
                                }

                                if (event && event.target) {
                                    event.target.classList.add('active');
                                }

                                resSelect.value = targetRes;
                                fpsSlider.value = targetFps;
                                qualitySlider.value = targetQuality;
                                fVal.textContent = targetFps;
                                qVal.textContent = targetQuality;
                                if (fpsWarning) {
                                    fpsWarning.style.display = (targetFps > 20) ? 'block' : 'none';
                                }

                                fetch('/settings?res=' + encodeURIComponent(targetRes) + '&fps=' + encodeURIComponent(targetFps) + '&quality=' + encodeURIComponent(targetQuality), { method: 'POST' }).catch(() => {});
                            }

                            fpsSlider.addEventListener('input', function() { onFpsChange(this.value); });
                            fpsSlider.addEventListener('change', function() { onFpsChange(this.value); });
                            qualitySlider.addEventListener('input', function() { onQualityChange(this.value); });
                            qualitySlider.addEventListener('change', function() { onQualityChange(this.value); });
                            photoQualitySlider.addEventListener('input', function() { onPhotoQualityChange(this.value); });
                            photoQualitySlider.addEventListener('change', function() { onPhotoQualityChange(this.value); });

                            // --- Map ---
                            var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([0, 0], 15);
                            L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
                            var marker = L.marker([0, 0]).addTo(map);
                            var firstUpdate = true;

                            async function updateLocation() {
                                try {
                                    const resp = await fetch('/location');
                                    const data = await resp.json();
                                    if (data.lat !== 0.0 || data.lng !== 0.0) {
                                        const ll = new L.LatLng(data.lat, data.lng);
                                        marker.setLatLng(ll);
                                        if (firstUpdate) { map.setView(ll, 16); firstUpdate = false; }
                                        else { map.panTo(ll); }
                                    }
                                } catch (e) {}
                            }
                            updateLocation();
                            setInterval(updateLocation, 3000);
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                val resp = newFixedLengthResponse(Response.Status.OK, "text/html", html)
                resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                resp.addHeader("Pragma", "no-cache")
                resp.addHeader("Expires", "0")
                return resp
            }
            "/webrtc_offer" -> {
                if (session.method == Method.POST) {
                    try {
                        val lengthStr = session.headers["content-length"]
                        val length = lengthStr?.toIntOrNull() ?: 0
                        val buffer = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val r = session.inputStream.read(buffer, read, length - read)
                            if (r == -1) break
                            read += r
                        }
                        val offerJsonStr = String(buffer, Charsets.UTF_8)
                        val answerJson = service?.webRtcManager?.handleOffer(offerJsonStr, session.remoteIpAddress)
                        if (answerJson != null) {
                            val res = newFixedLengthResponse(Response.Status.OK, "application/json", answerJson)
                            res.addHeader("Access-Control-Allow-Origin", "*")
                            return res
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "WebRTC offer failed")
                }
            }
            "/webrtc_candidate" -> {
                if (session.method == Method.POST) {
                    try {
                        val lengthStr = session.headers["content-length"]
                        val length = lengthStr?.toIntOrNull() ?: 0
                        val buffer = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val r = session.inputStream.read(buffer, read, length - read)
                            if (r == -1) break
                            read += r
                        }
                        val candidateJson = JSONObject(String(buffer, Charsets.UTF_8))
                        val candidateSdp = candidateJson.getString("candidate")
                        val sdpMid = candidateJson.optString("sdpMid", "0")
                        val sdpMLineIndex = candidateJson.optInt("sdpMLineIndex", 0)
                        service?.webRtcManager?.addRemoteCandidate(sdpMid, sdpMLineIndex, candidateSdp, session.remoteIpAddress)
                        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            "/webrtc_candidates" -> {
                val list = service?.webRtcManager?.gatheredCandidates
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
            "/set_photo_quality" -> {
                if (session.method == Method.POST) {
                    val q = session.parms["value"]?.toIntOrNull()?.coerceIn(10, 100) ?: 95
                    service?.setPhotoQuality(q)
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"photoQuality\": $q}")
                }
            }
            "/location" -> {
                val json = "{\"lat\": $currentLat, \"lng\": $currentLng}"
                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
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
            "/megafon_pcm" -> {
                if (session.method == Method.POST) {
                    try {
                        val lengthStr = session.headers["content-length"]
                        val length = lengthStr?.toIntOrNull() ?: 0
                        if (length > 0) {
                            val buffer = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                val r = session.inputStream.read(buffer, read, length - read)
                                if (r == -1) break
                                read += r
                            }
                            service?.playMegafonPcm(buffer)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                    res.addHeader("Access-Control-Allow-Origin", "*")
                    return res
                }
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }
    
    override fun stop() {
        isRunning.set(false)
        synchronized(micClients) { for (c in micClients) c.offer(ByteArray(0)); micClients.clear() }
        super.stop()
    }
}
