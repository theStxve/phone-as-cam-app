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
}

class MjpegServer(port: Int, private val controller: CameraController) : NanoHTTPD(port) {

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
        val uri = session.uri
        val clientSessionId = session.parms["session_id"] ?: session.headers["x-session-id"]
        if (!clientSessionId.isNullOrBlank()) {
            recordSessionActivity(clientSessionId)
        }
        
        when (uri) {
            "/" -> {
                val currentQuality = service?.jpegQuality?.get() ?: 20
                val currentFps = service?.maxFps?.get() ?: 20
                val currentRes = service?.targetResolution?.get() ?: "480p"
                val currentPhotoQuality = service?.photoJpegQuality?.get() ?: 95
                val currentGpsInterval = service?.gpsIntervalSec ?: 300
                val isFastPhoto = service?.fastPhotoMode?.get() ?: false
                val bestSensorLabel = service?.detectedCameraLabel ?: "Wird ermittelt..."
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
                val driveEmail = GoogleDriveBackupManager.connectedAccountEmail ?: ""
                val isDriveLinked = GoogleDriveBackupManager.isAutoBackupEnabled && driveEmail.isNotBlank()

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Camera Live Stream</title>
                        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin=""/>
                        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
                        <style>
                            * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                            html, body { margin: 0; padding: 0; background: #000; color: white; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; height: 100%; width: 100%; overflow: hidden; }

                            /* ── STREAM LAYER ── */
                            .main-stream { position: fixed; inset: 0; display: flex; justify-content: center; align-items: center; background: #000; }
                            .main-stream video, .main-stream img { width: 100%; height: 100%; object-fit: contain; }

                            /* ── TOP-LEFT STATUS BADGES ── */
                            .top-bar { position: fixed; top: 15px; left: 15px; z-index: 200; display: flex; align-items: center; gap: 8px; pointer-events: none; }
                            .top-bar > * { pointer-events: auto; }
                            .status-badge { background: rgba(15,15,22,0.85); padding: 7px 14px; border-radius: 20px; font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 8px; backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.15); box-shadow: 0 4px 15px rgba(0,0,0,0.4); white-space: nowrap; }
                            .viewer-badge { background: rgba(15,15,22,0.85); padding: 7px 14px; border-radius: 20px; font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 6px; backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.15); box-shadow: 0 4px 15px rgba(0,0,0,0.4); white-space: nowrap; }
                            .dot { width: 9px; height: 9px; border-radius: 50%; background: #ffc107; display: inline-block; flex-shrink: 0; }
                            .dot.live { background: #28a745; box-shadow: 0 0 10px #28a745; }

                            /* ── CONTROL BUTTONS COMMON STYLES ── */
                            .controls-bar button { padding: 9px 14px; font-size: 13px; font-weight: 600; border: none; border-radius: 10px; cursor: pointer; color: white; opacity: 0.92; transition: all 0.15s ease-out; display: inline-flex; align-items: center; justify-content: center; gap: 6px; white-space: nowrap; user-select: none; }
                            .controls-bar button:hover { opacity: 1; transform: translateY(-1px); filter: brightness(1.1); }
                            .controls-bar button:active { transform: scale(0.96); }
                            
                            .btn-photo   { background: #198754; }
                            .btn-clip    { background: #e8590c; }
                            .btn-clip.active { background: #dc3545; box-shadow: 0 0 14px #dc3545; animation: megaPulse 1s infinite; }
                            .btn-audio   { background: #28a745; }
                            .btn-audio.muted { background: #495057; }
                            .btn-megafon { background: #c2255c; }
                            .btn-megafon.active { background: #dc3545; box-shadow: 0 0 14px #dc3545; animation: megaPulse 1.2s infinite; }
                            @keyframes megaPulse { 0%,100% { transform: scale(1); } 50% { transform: scale(1.06); } }
                            .btn-flash   { background: #1971c2; }
                            .btn-flash.active { background: #fcc419; color: #000; box-shadow: 0 0 12px rgba(252,196,25,0.7); }
                            .btn-zoom    { background: #6f42c1; }
                            .btn-zoom.active { background: #9d4edd; box-shadow: 0 0 12px rgba(157,78,221,0.7); }
                            .btn-switch  { background: #1864ab; }
                            .btn-rotate  { background: #0b7285; }
                            .btn-selfie  { background: #495057; }
                            .btn-selfie.active { background: #e8590c; box-shadow: 0 0 12px rgba(232,89,12,0.7); }
                            .btn-map     { background: #087f5b; }
                            .btn-map.active { background: #12b886; box-shadow: 0 0 12px rgba(18,184,134,0.7); }
                            .btn-settings { background: rgba(255,255,255,0.12); border: 1px solid rgba(255,255,255,0.2) !important; }
                            .btn-settings.open { background: #3b5bdb; border-color: #3b5bdb !important; }

                            /* ── PiP OVERLAYS COMMON ── */
                            .overlay { position: fixed; border: 2px solid rgba(255,255,255,0.3); border-radius: 10px; overflow: hidden; box-shadow: 0 8px 24px rgba(0,0,0,0.6); z-index: 100; background: #16161e; }
                            .overlay-label { position: absolute; top: 6px; left: 6px; background: rgba(0,0,0,0.7); padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; z-index: 101; backdrop-filter: blur(4px); }
                            .btn-pip-close { position: absolute; top: 5px; right: 5px; z-index: 102; background: rgba(0,0,0,0.7); color: #fff; border: 1px solid rgba(255,255,255,0.25); border-radius: 4px; width: 24px; height: 24px; padding: 0; display: flex; align-items: center; justify-content: center; font-size: 12px; cursor: pointer; transition: all 0.15s; }
                            .btn-pip-close:hover { background: #e03131; border-color: #e03131; }

                            /* ── DESKTOP LAYOUT (>= 768px) ── */
                            @media (min-width: 768px) {
                                .controls-bar { position: fixed; top: 15px; right: 15px; z-index: 200; display: flex; align-items: center; gap: 8px; background: rgba(15,15,22,0.85); backdrop-filter: blur(12px); padding: 8px 12px; border-radius: 14px; border: 1px solid rgba(255,255,255,0.15); box-shadow: 0 6px 25px rgba(0,0,0,0.5); }
                                .controls-bar button { padding: 9px 13px; font-size: 13px; }
                                
                                /* Settings panel as floating glassmorphic sidebar card */
                                .settings-overlay { display: none !important; }
                                .settings-drawer { position: fixed; top: 65px; left: 15px; z-index: 250; width: 330px; max-height: calc(100vh - 85px); background: rgba(15,15,22,0.92); backdrop-filter: blur(16px); border-radius: 14px; border: 1px solid rgba(255,255,255,0.15); box-shadow: 0 10px 35px rgba(0,0,0,0.6); display: flex; flex-direction: column; opacity: 0; pointer-events: none; transform: translateY(-8px); transition: opacity 0.2s ease, transform 0.2s ease; }
                                .settings-drawer.open { opacity: 1; pointer-events: auto; transform: translateY(0); }
                                .drawer-handle { display: none; }
                                .drawer-header { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px 8px; border-bottom: 1px solid rgba(255,255,255,0.1); }
                                .drawer-title { font-size: 13px; font-weight: 700; color: #adb5bd; letter-spacing: 0.5px; text-transform: uppercase; margin: 0; }
                                .btn-drawer-close { background: none; border: none; color: #868e96; font-size: 18px; cursor: pointer; padding: 0 4px; line-height: 1; }
                                .btn-drawer-close:hover { color: #fff; }

                                .selfie-pip { bottom: 20px; right: 20px; width: 260px; height: 195px; }
                                .selfie-pip img { width: 100%; height: 100%; object-fit: cover; }
                                .map-pip { bottom: 20px; left: 20px; width: 320px; height: 230px; }
                                #map { width: 100%; height: 100%; }

                                #braveHelpModal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.75); z-index: 9999; justify-content: center; align-items: center; backdrop-filter: blur(8px); }
                                .modal-card { background: #161622; border: 1px solid #ff922b; border-radius: 16px; width: 520px; max-width: 90%; padding: 22px; color: #fff; box-shadow: 0 16px 40px rgba(0,0,0,0.7); max-height: 85vh; overflow-y: auto; }
                            }

                            /* ── MOBILE LAYOUT (< 768px) ── */
                            @media (max-width: 767px) {
                                .controls-bar { position: fixed; bottom: 0; left: 0; right: 0; z-index: 300; background: rgba(12,12,18,0.94); backdrop-filter: blur(14px); border-top: 1px solid rgba(255,255,255,0.12); display: flex; align-items: center; padding: 8px 6px; gap: 6px; overflow-x: auto; overflow-y: hidden; -webkit-overflow-scrolling: touch; }
                                .controls-bar::-webkit-scrollbar { display: none; }
                                .controls-bar button { flex-shrink: 0; padding: 10px 12px; font-size: 13px; min-width: 52px; border-radius: 10px; }
                                
                                .btn-label-desktop { display: none; }

                                .settings-overlay { position: fixed; inset: 0; z-index: 400; background: rgba(0,0,0,0.6); backdrop-filter: blur(4px); display: none; }
                                .settings-overlay.open { display: block; }
                                .settings-drawer { position: fixed; bottom: 0; left: 0; right: 0; z-index: 500; background: #14141e; border-radius: 20px 20px 0 0; border-top: 1px solid rgba(255,255,255,0.15); max-height: 80vh; display: flex; flex-direction: column; transform: translateY(100%); transition: transform 0.28s cubic-bezier(0.32,1,0.23,1); box-shadow: 0 -8px 30px rgba(0,0,0,0.7); }
                                .settings-drawer.open { transform: translateY(0); }
                                .drawer-handle { width: 38px; height: 4px; background: rgba(255,255,255,0.3); border-radius: 2px; margin: 10px auto 4px; flex-shrink: 0; }
                                .drawer-header { display: flex; justify-content: space-between; align-items: center; padding: 4px 16px 10px; border-bottom: 1px solid rgba(255,255,255,0.08); }
                                .drawer-title { font-size: 13px; font-weight: 700; color: #adb5bd; letter-spacing: 0.5px; text-transform: uppercase; margin: 0; }
                                .btn-drawer-close { background: none; border: none; color: #868e96; font-size: 20px; cursor: pointer; padding: 0 4px; line-height: 1; }

                                .selfie-pip { bottom: 72px; right: 10px; width: 120px; height: 90px; }
                                .selfie-pip img { width: 100%; height: 100%; object-fit: cover; }
                                .map-pip { bottom: 72px; left: 10px; width: 210px; height: 155px; }
                                #map { width: 100%; height: 100%; }

                                #braveHelpModal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.85); z-index: 9999; justify-content: center; align-items: flex-end; backdrop-filter: blur(8px); }
                                .modal-card { background: #161622; border: 1px solid #ff922b; border-radius: 18px 18px 0 0; width: 100%; padding: 20px; color: #fff; max-height: 82vh; overflow-y: auto; }
                            }

                            /* ── DRAWER CONTENT STYLING ── */
                            .drawer-body { overflow-y: auto; -webkit-overflow-scrolling: touch; padding: 12px 16px 20px; }
                            .drawer-body::-webkit-scrollbar { width: 4px; }
                            .drawer-body::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 2px; }
                            .section-label { font-size: 11px; font-weight: 700; color: #868e96; text-transform: uppercase; letter-spacing: 0.8px; margin: 14px 0 8px; }
                            .section-divider { border: none; border-top: 1px solid rgba(255,255,255,0.1); margin: 12px 0; }
                            .setting-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 13px; gap: 8px; }
                            .setting-row label { display: flex; justify-content: space-between; align-items: center; width: 100%; gap: 8px; font-size: 12px; }
                            .setting-row input[type=range] { flex: 1; min-width: 0; accent-color: #4dabf7; cursor: pointer; }
                            .setting-row select { background: #1e1e2c; color: #fff; border: 1px solid rgba(255,255,255,0.25); border-radius: 6px; padding: 5px 8px; font-size: 12px; flex: 1; }
                            .val-badge { font-size: 12px; font-weight: 700; color: #4dabf7; width: 42px; text-align: right; flex-shrink: 0; }
                            .preset-group { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-bottom: 12px; }
                            .btn-preset { padding: 7px 4px; font-size: 11px; border: 1px solid rgba(255,255,255,0.18); border-radius: 8px; background: rgba(255,255,255,0.06); color: white; cursor: pointer; text-align: center; font-weight: 500; transition: all 0.15s; }
                            .btn-preset:hover { background: rgba(255,255,255,0.15); }
                            .btn-preset.active { background: #1971c2; border-color: #1971c2; font-weight: 700; box-shadow: 0 0 10px rgba(25,113,194,0.6); }
                            .fps-warning { color: #ffd43b; font-size: 11px; margin: -4px 0 8px; padding: 5px 8px; border-radius: 6px; background: rgba(255,212,59,0.12); border: 1px solid rgba(255,212,59,0.35); font-weight: 600; line-height: 1.3; }
                            .toggle-row { display: flex; align-items: center; justify-content: space-between; font-size: 12px; margin-bottom: 8px; }
                            .toggle-hint { font-size: 10px; color: #8ce99a; margin-top: -4px; margin-bottom: 8px; }
                            .vu-row { display: flex; align-items: center; gap: 8px; font-size: 12px; margin-bottom: 6px; }
                            .vu-bar { flex: 1; height: 8px; background: #1e1e2c; border-radius: 4px; overflow: hidden; border: 1px solid rgba(255,255,255,0.15); }
                            .vu-fill { width: 0%; height: 100%; background: linear-gradient(90deg, #37b24d 60%, #f59f00 85%, #f03e3e 100%); transition: width 0.05s ease-out; }
                            .brave-link { color: #ff922b; font-size: 11px; text-decoration: underline; cursor: pointer; display: block; text-align: right; margin-top: 4px; }
                            .modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; border-bottom: 1px solid rgba(255,255,255,0.1); padding-bottom: 8px; }
                            .modal-close { background: none; border: none; color: #aaa; font-size: 22px; cursor: pointer; line-height: 1; padding: 0; }
                        </style>
                    </head>
                    <body>
                        <div class="main-stream">
                            <video id="webrtcVideo" autoplay playsinline muted></video>
                            <img id="mainImg" style="display:none;" alt="Back Camera Fallback" />
                        </div>

                        <!-- PiP Overlays -->
                        <div class="overlay selfie-pip" id="selfiePip" style="display: $selfieDisplay;">
                            <div class="overlay-label">🤳 Selfie</div>
                            <img id="frontImg" alt="Front Camera" />
                        </div>
                        <div class="overlay map-pip" id="mapPip">
                            <div class="overlay-label">📍 GPS</div>
                            <button class="btn-pip-close" onclick="toggleMapVisibility(false)" title="Karte schließen">✕</button>
                            <div id="map"></div>
                        </div>

                        <!-- Top-Left Status Bar -->
                        <div class="top-bar">
                            <div class="status-badge" id="badge">
                                <span class="dot" id="statusDot"></span>
                                <span id="statusText">Verbinde...</span>
                            </div>
                            <div class="viewer-badge" id="viewerBadge">
                                <span>👥</span><span id="viewerText">1 Zuschauer</span>
                            </div>
                        </div>

                        <!-- Controls Bar (Top-Right on Desktop, Bottom on Mobile) -->
                        <div class="controls-bar" id="controlsBar">
                            <button class="btn-photo" id="photoBtn" onclick="takePhoto()" title="Foto aufnehmen">📸<span class="btn-label-desktop"> Foto</span></button>
                            <button class="btn-clip" id="clipBtn" onclick="recordClip(10)" title="10-Sekunden Clip aufnehmen">🎥<span class="btn-label-desktop"> Clip (10s)</span></button>
                            <button class="btn-audio muted" id="audioBtn" title="Ton ein/aus">🔇<span class="btn-label-desktop"> Ton an</span></button>
                            <button class="btn-megafon" id="megafonBtn" onclick="toggleMegafon()" title="Megafon an/aus">📢<span class="btn-label-desktop"> Megafon</span></button>
                            <button class="$flashClass" id="flashBtn" onclick="toggleFlash()" title="Taschenlampe">💡<span class="btn-label-desktop"> Blitz</span></button>
                            <button class="$wideClass" id="wideBtn" onclick="toggleWideAngle()" title="Weitwinkel">🔍<span class="btn-label-desktop"> $wideText</span></button>
                            <button class="btn-switch" id="switchBtn" onclick="switchCamera()" title="Kamera wechseln">🔄<span class="btn-label-desktop"> Kamera</span></button>
                            <button class="btn-rotate" onclick="rotateStream()" title="90° drehen">⟲<span class="btn-label-desktop"> Drehen</span></button>
                            <button class="$selfieBtnClass" id="selfieBtn" onclick="toggleSelfie()" title="Selfie PiP">🤳<span class="btn-label-desktop"> Selfie</span></button>
                            <button class="btn-map active" id="mapBtn" onclick="toggleMapVisibility()" title="GPS-Karte">📍<span class="btn-label-desktop"> Karte</span></button>
                            <button class="btn-settings" id="settingsBtn" onclick="toggleDrawer()" title="Einstellungen">⚙️<span class="btn-label-desktop"> Setup</span></button>
                        </div>

                        <!-- Settings Drawer / Sidebar Card -->
                        <div class="settings-overlay" id="settingsOverlay" onclick="closeDrawer()"></div>
                        <div class="settings-drawer" id="settingsDrawer">
                            <div class="drawer-handle"></div>
                            <div class="drawer-header">
                                <div class="drawer-title">⚙️ Stream Setup</div>
                                <button class="btn-drawer-close" onclick="closeDrawer()" title="Schließen">✕</button>
                            </div>
                            <div class="drawer-body">

                                <div class="section-label">⚡ Streaming-Presets</div>
                                <div class="preset-group">
                                    <button class="btn-preset" onclick="applyPreset('eco')" title="640x480, 15 FPS">🔋 Eco</button>
                                    <button class="btn-preset active" onclick="applyPreset('balanced')" title="720p, 20 FPS">⚖️ HD</button>
                                    <button class="btn-preset" onclick="applyPreset('smooth')" title="720p, 30 FPS">🚀 Smooth</button>
                                    <button class="btn-preset" onclick="applyPreset('ultra')" title="1080p, 60 FPS">🔥 Ultra</button>
                                </div>
                                <div class="setting-row">
                                    <label>Auflösung:
                                        <select id="resSelect" onchange="onResChange(this.value)">
                                            <option value="480p" ${if (currentRes == "480p") "selected" else ""}>480p (VGA)</option>
                                            <option value="720p" ${if (currentRes == "720p") "selected" else ""}>720p (HD)</option>
                                            <option value="1080p" ${if (currentRes == "1080p") "selected" else ""}>1080p (FHD)</option>
                                        </select>
                                    </label>
                                </div>
                                <div class="setting-row">
                                    <label>FPS: <input type="range" id="fps" min="5" max="60" step="1" value="$currentFps"> <span class="val-badge" id="fVal">$currentFps</span></label>
                                </div>
                                <div id="fpsWarning" class="fps-warning" style="display: ${if (currentFps > 20) "block" else "none"};">⚠️ Über 20 FPS = mehr Hitze &amp; Akku!</div>
                                <div class="setting-row">
                                    <label>Stream-Qualität: <input type="range" id="quality" min="10" max="95" step="5" value="$currentQuality"> <span class="val-badge" id="qVal">$currentQuality</span></label>
                                </div>

                                <hr class="section-divider">
                                <div class="section-label">📸 Foto-Einstellungen</div>
                                <div class="setting-row">
                                    <label>Foto-Qualität: <input type="range" id="photoQuality" min="50" max="100" step="5" value="$currentPhotoQuality"> <span class="val-badge" id="pqVal">$currentPhotoQuality</span></label>
                                </div>
                                <div class="toggle-row">
                                    <span>⚡ Schnell-Foto (ohne Pause):</span>
                                    <input type="checkbox" id="fastPhotoCheck" ${if (isFastPhoto) "checked" else ""} onchange="onFastPhotoToggle(this.checked)" style="width:18px;height:18px;cursor:pointer;">
                                </div>
                                <div style="font-size:10px;color:#4dabf7;text-align:right;margin-top:-4px;margin-bottom:8px;">🔍 $bestSensorLabel</div>

                                <hr class="section-divider">
                                <div class="section-label">☁️ Google Drive Cloud</div>
                                <div class="setting-row" style="font-size:12px;">
                                    <span>Status:</span>
                                    <span id="driveAccountBadge" style="font-weight:700;color:${if (isDriveLinked) "#51cf66" else "#adb5bd"};">${if (isDriveLinked) "🟢 $driveEmail" else "⚪ Nicht verknüpft (in App)"}</span>
                                </div>
                                <div class="toggle-row">
                                    <span>📸 Fotos automatisch sichern:</span>
                                    <input type="checkbox" id="drivePhotoCheck" ${if (GoogleDriveBackupManager.isAutoBackupEnabled) "checked" else ""} onchange="onDriveToggle()" style="width:18px;height:18px;cursor:pointer;">
                                </div>
                                <div class="toggle-row">
                                    <span>🎥 Video-Clips sichern:</span>
                                    <input type="checkbox" id="driveClipCheck" ${if (GoogleDriveBackupManager.isClipBackupEnabled) "checked" else ""} onchange="onDriveToggle()" style="width:18px;height:18px;cursor:pointer;">
                                </div>
                                <div style="margin-top:6px;margin-bottom:6px;">
                                    <a href="https://drive.google.com/drive/u/0/my-drive" target="_blank" style="width:100%;text-align:center;background:rgba(66,133,244,0.15);border:1px solid #4285f4;color:#4dabf7;padding:8px 10px;border-radius:8px;text-decoration:none;font-size:12px;font-weight:600;display:block;box-sizing:border-box;">📁 Google Drive Ordner öffnen ↗</a>
                                </div>
                                <div id="driveLastStatus" style="font-size:10px;color:#868e96;text-align:right;margin-top:2px;">${GoogleDriveBackupManager.lastBackupStatus}</div>

                                <hr class="section-divider">
                                <div class="section-label">🔊 Audio &amp; Lautstärke</div>
                                <div class="setting-row">
                                    <label>🎤 Mikrofon: <input type="range" id="volSlider" min="0" max="300" step="10" value="250" oninput="onVolumeChange(this.value)"> <span class="val-badge" id="volVal" style="color:#4dabf7;">250%</span></label>
                                </div>
                                <div class="setting-row">
                                    <label>📢 Megafon: <input type="range" id="megafonVolSlider" min="0" max="100" step="5" value="100" oninput="onMegafonVolumeChange(this.value)"> <span class="val-badge" id="megafonVolVal" style="color:#ff6b6b;">100%</span></label>
                                </div>
                                <div class="toggle-row">
                                    <span>🛡️ Anti-Clipping (Limiter):</span>
                                    <input type="checkbox" id="limiterCheck" checked onchange="toggleLimiter(this.checked)" style="width:18px;height:18px;cursor:pointer;">
                                </div>
                                <div class="toggle-hint">Dämpft Rückkopplungen &amp; schützt Ohren</div>
                                <div class="vu-row">
                                    <span>🎤 Pegel:</span>
                                    <div class="vu-bar"><div class="vu-fill" id="vuMeter"></div></div>
                                </div>
                                <a class="brave-link" onclick="showBraveHelpModal()">🦁 Brave / Chrome Mikrofon-Hilfe</a>

                                <hr class="section-divider">
                                <div class="section-label">📍 GPS &amp; Standort</div>
                                <div class="setting-row">
                                    <label>GPS-Intervall:
                                        <select id="gpsSelect" onchange="onGpsIntervalChange(this.value)">
                                            <option value="0" ${if (currentGpsInterval == 0) "selected" else ""}>Aus (Stationär)</option>
                                            <option value="900" ${if (currentGpsInterval == 900) "selected" else ""}>Alle 15 Min</option>
                                            <option value="300" ${if (currentGpsInterval == 300) "selected" else ""}>Alle 5 Min</option>
                                            <option value="60" ${if (currentGpsInterval == 60) "selected" else ""}>Jede Minute</option>
                                            <option value="10" ${if (currentGpsInterval == 10) "selected" else ""}>Alle 10 Sek (Live)</option>
                                        </select>
                                    </label>
                                </div>
                            </div>
                        </div>

                        <!-- Brave Help Modal -->
                        <div id="braveHelpModal" style="display:none; position:fixed; inset:0; background:rgba(0,0,0,0.85); z-index:9999; justify-content:center; align-items:center; backdrop-filter:blur(8px);">
                            <div class="modal-card">
                                <div class="modal-header">
                                    <h3 style="margin:0;font-size:15px;color:#ff922b;">🦁 Mikrofon in Brave &amp; Chrome freigeben</h3>
                                    <button class="modal-close" onclick="closeBraveHelpModal()">✕</button>
                                </div>
                                <p style="color:#ddd;font-size:12px;margin-top:0;">Browser blockieren Mikrofon-Zugriff auf lokalen IP-Adressen (HTTP). So aktivierst du Megafon:</p>
                                <ol style="padding-left:18px;margin-bottom:14px;color:#f1f3f5;font-size:12px;">
                                    <li style="margin-bottom:8px;">Öffne einen neuen Tab:<br>
                                        <div style="display:flex;gap:6px;margin-top:4px;">
                                            <input type="text" id="flagUrlInput" readonly value="brave://flags/#unsafely-treat-insecure-origin-as-secure" style="background:#111;color:#ff922b;border:1px solid #444;border-radius:4px;padding:4px 8px;font-size:11px;flex:1;min-width:0;">
                                            <button onclick="copyToClipboard('flagUrlInput')" style="background:#ff922b;color:#000;border:none;border-radius:4px;padding:4px 10px;font-size:11px;font-weight:bold;cursor:pointer;flex-shrink:0;">📋</button>
                                        </div>
                                        <span style="font-size:10px;color:#aaa;">(Chrome: <code>chrome://flags/...</code>)</span>
                                    </li>
                                    <li style="margin-bottom:8px;">Trage diese URL ein:<br>
                                        <div style="display:flex;gap:6px;margin-top:4px;">
                                            <input type="text" id="streamUrlInput" readonly value="" style="background:#111;color:#51cf66;border:1px solid #444;border-radius:4px;padding:4px 8px;font-size:11px;flex:1;min-width:0;">
                                            <button onclick="copyToClipboard('streamUrlInput')" style="background:#51cf66;color:#000;border:none;border-radius:4px;padding:4px 10px;font-size:11px;font-weight:bold;cursor:pointer;flex-shrink:0;">📋</button>
                                        </div>
                                    </li>
                                    <li style="margin-bottom:8px;">Dropdown auf <b>"Enabled"</b> stellen.</li>
                                    <li>Auf <b>"Relaunch"</b> klicken.</li>
                                </ol>
                                <div style="background:rgba(255,146,43,0.15);border:1px solid rgba(255,146,43,0.4);border-radius:6px;padding:8px 10px;font-size:11px;color:#ffc078;margin-bottom:14px;">🛡️ <b>Brave Shields:</b> Klicke auf das Löwen-Symbol → Mikrofon erlauben.</div>
                                <button onclick="closeBraveHelpModal()" style="width:100%;background:#339af0;color:#fff;border:none;border-radius:8px;padding:10px;font-weight:bold;cursor:pointer;font-size:14px;">Schließen</button>
                            </div>
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
                            let userVolume = 2.5;
                            let megafonVolume = 100;
                            let audioCtx = null;
                            let audioGainNode = null;
                            let audioAnalyser = null;
                            let audioSourceNode = null;
                            let vuAnimationId = null;
                            let pcmAbortController = null;

                            function toggleDrawer() {
                                const drawer = document.getElementById('settingsDrawer');
                                const overlay = document.getElementById('settingsOverlay');
                                const btn = document.getElementById('settingsBtn');
                                if (!drawer) return;
                                const isOpen = drawer.classList.contains('open');
                                if (isOpen) {
                                    closeDrawer();
                                } else {
                                    drawer.classList.add('open');
                                    if (overlay) overlay.classList.add('open');
                                    if (btn) btn.classList.add('open');
                                }
                            }

                            function closeDrawer() {
                                const drawer = document.getElementById('settingsDrawer');
                                const overlay = document.getElementById('settingsOverlay');
                                const btn = document.getElementById('settingsBtn');
                                if (drawer) drawer.classList.remove('open');
                                if (overlay) overlay.classList.remove('open');
                                if (btn) btn.classList.remove('open');
                            }

                            let isLimiterEnabled = true;
                            let audioWaveShaper = null;
                            let audioHighpass = null;

                            function makeTanhCurve(samples = 1024) {
                                const curve = new Float32Array(samples);
                                for (let i = 0; i < samples; ++i) {
                                    const x = (i * 2) / samples - 1;
                                    // Soft saturation curve: enables full 2.5x volume boost while softly rounding extreme peaks
                                    curve[i] = Math.tanh(x * 1.5) * 1.8;
                                }
                                return curve;
                            }

                            async function getMicStream() {
                                if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                                    try {
                                        return await navigator.mediaDevices.getUserMedia({
                                            audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
                                        });
                                    } catch (e) {
                                        if (e.name === 'NotAllowedError' || e.name === 'PermissionDeniedError' || e.name === 'NotFoundError' || e.name === 'NotReadableError') {
                                            throw e;
                                        }
                                        return await navigator.mediaDevices.getUserMedia({ audio: true });
                                    }
                                }
                                const legacyGUM = navigator.getUserMedia || navigator.webkitGetUserMedia || navigator.mozGetUserMedia || navigator.msGetUserMedia;
                                if (legacyGUM) {
                                    return new Promise((resolve, reject) => {
                                        legacyGUM.call(navigator, { audio: true }, resolve, reject);
                                    });
                                }
                                const err = new Error('INSECURE_ORIGIN');
                                err.name = 'InsecureOriginError';
                                throw err;
                            }

                            function initAudioPipeline() {
                                if (!audioCtx) {
                                    try {
                                        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                                    } catch (e) {}
                                }
                                if (audioCtx && !audioGainNode) {
                                    // 1. Gain Node (Starts at clean 250%)
                                    audioGainNode = audioCtx.createGain();
                                    audioGainNode.gain.value = isAudioEnabled ? userVolume : 0.0;

                                    // 2. High-pass filter to eliminate sub-bass rumble & feedback resonance
                                    audioHighpass = audioCtx.createBiquadFilter();
                                    audioHighpass.type = 'highpass';
                                    audioHighpass.frequency.value = 120;

                                    // 3. Clean WaveShaper Soft-Clipper: Clamps peaks without ANY automatic make-up gain (prevents feedback pumping)
                                    audioWaveShaper = audioCtx.createWaveShaper();
                                    audioWaveShaper.curve = makeTanhCurve(1024);
                                    audioWaveShaper.oversample = '2x';

                                    // 4. Analyser
                                    audioAnalyser = audioCtx.createAnalyser();
                                    audioAnalyser.fftSize = 128;
                                    audioAnalyser.smoothingTimeConstant = 0.5;

                                    connectAudioChain();
                                    startVuMeter();
                                }
                            }

                            function connectAudioChain() {
                                if (!audioGainNode || !audioCtx) return;
                                try {
                                    audioGainNode.disconnect();
                                    if (audioHighpass) audioHighpass.disconnect();
                                    if (audioWaveShaper) audioWaveShaper.disconnect();
                                    if (audioAnalyser) audioAnalyser.disconnect();

                                    if (isLimiterEnabled && audioHighpass && audioWaveShaper) {
                                        audioGainNode.connect(audioHighpass);
                                        audioHighpass.connect(audioWaveShaper);
                                        audioWaveShaper.connect(audioAnalyser);
                                    } else {
                                        audioGainNode.connect(audioAnalyser);
                                    }
                                    audioAnalyser.connect(audioCtx.destination);
                                } catch (e) {
                                    console.warn('Audio chain connection error:', e);
                                }
                            }

                            function toggleLimiter(enabled) {
                                isLimiterEnabled = enabled;
                                connectAudioChain();
                            }

                            function showBraveHelpModal() {
                                const streamUrlInput = document.getElementById('streamUrlInput');
                                if (streamUrlInput) {
                                    streamUrlInput.value = window.location.origin;
                                }
                                const modal = document.getElementById('braveHelpModal');
                                if (modal) modal.style.display = 'flex';
                            }

                            function closeBraveHelpModal() {
                                const modal = document.getElementById('braveHelpModal');
                                if (modal) modal.style.display = 'none';
                            }

                            function copyToClipboard(elementId) {
                                const el = document.getElementById(elementId);
                                if (!el) return;
                                el.select();
                                el.setSelectionRange(0, 99999);
                                if (navigator.clipboard) {
                                    navigator.clipboard.writeText(el.value).then(() => {
                                        alert('In die Zwischenablage kopiert:\n' + el.value);
                                    }).catch(() => {
                                        document.execCommand('copy');
                                        alert('In die Zwischenablage kopiert:\n' + el.value);
                                    });
                                } else {
                                    document.execCommand('copy');
                                    alert('In die Zwischenablage kopiert:\n' + el.value);
                                }
                            }

                            function startVuMeter() {
                                if (vuAnimationId) return;
                                const vuMeter = document.getElementById('vuMeter');
                                const dataArray = new Uint8Array(64);
                                function update() {
                                    if (audioAnalyser && isAudioEnabled && (!isMegafonActive)) {
                                        audioAnalyser.getByteFrequencyData(dataArray);
                                        let sum = 0;
                                        for (let i = 0; i < dataArray.length; i++) {
                                            sum += dataArray[i];
                                        }
                                        const avg = sum / dataArray.length;
                                        const pct = Math.min(100, Math.round((avg / 110) * 100));
                                        if (vuMeter) vuMeter.style.width = pct + '%';
                                    } else {
                                        if (vuMeter) vuMeter.style.width = '0%';
                                    }
                                    vuAnimationId = requestAnimationFrame(update);
                                }
                                vuAnimationId = requestAnimationFrame(update);
                            }

                            function onVolumeChange(val) {
                                userVolume = val / 100.0;
                                const volValEl = document.getElementById('volVal');
                                if (volValEl) volValEl.textContent = val + '%';
                                if (audioGainNode && audioCtx) {
                                    if (isAudioEnabled && !isMegafonActive) {
                                        audioGainNode.gain.setTargetAtTime(userVolume, audioCtx.currentTime, 0.02);
                                    }
                                }
                            }

                            function onMegafonVolumeChange(val) {
                                megafonVolume = parseInt(val, 10);
                                const megaVolValEl = document.getElementById('megafonVolVal');
                                if (megaVolValEl) megaVolValEl.textContent = val + '%';
                                fetch('/set_speaker_volume?vol=' + megafonVolume, { method: 'POST' }).catch(() => {});
                            }

                            // --- Remote Photo Capture & Instant Download ---
                            async function takePhoto() {
                                const btn = document.getElementById('photoBtn');
                                const origHtml = btn.innerHTML;
                                btn.innerHTML = '⏳<span class="btn-label-desktop"> Foto...</span>';
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
                                        fetchDriveState();
                                    } else {
                                        alert('Fehler bei der Fotoaufnahme (Kamera möglicherweise ausgelastet).');
                                    }
                                } catch (e) {
                                    alert('Foto-Fehler: ' + e);
                                } finally {
                                    btn.innerHTML = origHtml;
                                    btn.disabled = false;
                                }
                            }

                            // --- 10-Second Video Clip Recording & Cloud Upload ---
                            let mediaRecorder = null;
                            let recordedChunks = [];
                            let isRecordingClip = false;

                            async function recordClip(durationSec = 10) {
                                if (isRecordingClip) return;
                                const clipBtn = document.getElementById('clipBtn');

                                let stream = videoEl.srcObject;
                                if (!stream && videoEl.captureStream) {
                                    stream = videoEl.captureStream();
                                }

                                if (!stream) {
                                    alert('Kein aktiver Video-Stream für Clip-Aufnahme vorhanden.');
                                    return;
                                }

                                try {
                                    isRecordingClip = true;
                                    recordedChunks = [];
                                    
                                    let mimeType = 'video/webm;codecs=vp8,opus';
                                    if (!MediaRecorder.isTypeSupported(mimeType)) {
                                        mimeType = 'video/webm';
                                    }
                                    if (!MediaRecorder.isTypeSupported(mimeType)) {
                                        mimeType = 'video/mp4';
                                    }
                                    if (!MediaRecorder.isTypeSupported(mimeType)) {
                                        mimeType = '';
                                    }
                                    
                                    const options = mimeType ? { mimeType: mimeType } : {};
                                    mediaRecorder = new MediaRecorder(stream, options);

                                    mediaRecorder.ondataavailable = (e) => {
                                        if (e.data && e.data.size > 0) {
                                            recordedChunks.push(e.data);
                                        }
                                    };

                                    mediaRecorder.onstop = async () => {
                                        const actualMime = mimeType || 'video/webm';
                                        const blob = new Blob(recordedChunks, { type: actualMime });
                                        const ext = actualMime.includes('mp4') ? '.mp4' : '.webm';
                                        const filename = 'clip_' + Date.now() + ext;

                                        // 1. Download in browser
                                        const url = URL.createObjectURL(blob);
                                        const a = document.createElement('a');
                                        a.href = url;
                                        a.download = filename;
                                        document.body.appendChild(a);
                                        a.click();
                                        document.body.removeChild(a);
                                        URL.revokeObjectURL(url);

                                        // 2. Upload to Google Drive via Phone Server
                                        try {
                                            fetch('/upload_clip?name=' + filename, {
                                                method: 'POST',
                                                headers: { 'Content-Type': actualMime },
                                                body: blob
                                            }).then(() => fetchDriveState()).catch(() => {});
                                        } catch(e) {}

                                        isRecordingClip = false;
                                        if (clipBtn) {
                                            clipBtn.classList.remove('active');
                                            clipBtn.innerHTML = '🎥<span class="btn-label-desktop"> Clip (10s)</span>';
                                        }
                                    };

                                    mediaRecorder.start(500);
                                    if (clipBtn) clipBtn.classList.add('active');

                                    let remaining = durationSec;
                                    if (clipBtn) clipBtn.innerHTML = '🔴<span class="btn-label-desktop"> ' + remaining + 's</span>';

                                    const timer = setInterval(() => {
                                        remaining--;
                                        if (remaining > 0) {
                                            if (clipBtn) clipBtn.innerHTML = '🔴<span class="btn-label-desktop"> ' + remaining + 's</span>';
                                        } else {
                                            clearInterval(timer);
                                            if (mediaRecorder && mediaRecorder.state === 'recording') {
                                                mediaRecorder.stop();
                                            }
                                        }
                                    }, 1000);

                                } catch (e) {
                                    console.error('Clip error:', e);
                                    isRecordingClip = false;
                                    if (clipBtn) {
                                        clipBtn.classList.remove('active');
                                        clipBtn.innerHTML = '🎥<span class="btn-label-desktop"> Clip (10s)</span>';
                                    }
                                    alert('Clip-Fehler: ' + e.message);
                                }
                            }

                            // --- Google Drive State Sync & Toggle ---
                            async function fetchDriveState() {
                                try {
                                    const resp = await fetch('/drive_state');
                                    const data = await resp.json();
                                    const badge = document.getElementById('driveAccountBadge');
                                    const photoCheck = document.getElementById('drivePhotoCheck');
                                    const clipCheck = document.getElementById('driveClipCheck');
                                    const statusEl = document.getElementById('driveLastStatus');

                                    if (badge) {
                                        if (data.connected && data.email) {
                                            badge.textContent = '🟢 ' + data.email;
                                            badge.style.color = '#51cf66';
                                        } else {
                                            badge.textContent = '⚪ Nicht verknüpft (in App)';
                                            badge.style.color = '#adb5bd';
                                        }
                                    }
                                    if (photoCheck && data.photoBackup !== undefined) photoCheck.checked = data.photoBackup;
                                    if (clipCheck && data.clipBackup !== undefined) clipCheck.checked = data.clipBackup;
                                    if (statusEl && data.lastStatus) statusEl.textContent = data.lastStatus;
                                } catch(e) {}
                            }

                            async function onDriveToggle() {
                                const photoCheck = document.getElementById('drivePhotoCheck');
                                const clipCheck = document.getElementById('driveClipCheck');
                                const photoVal = photoCheck ? photoCheck.checked : true;
                                const clipVal = clipCheck ? clipCheck.checked : true;
                                try {
                                    await fetch('/drive_state?photoBackup=' + photoVal + '&clipBackup=' + clipVal, { method: 'POST' });
                                    fetchDriveState();
                                } catch(e) {}
                            }

                            fetchDriveState();
                            setInterval(fetchDriveState, 8000);

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
                                        btn.innerHTML = '🔍<span class="btn-label-desktop"> Weit: An</span>';
                                    } else {
                                        btn.classList.remove('active');
                                        btn.innerHTML = '🔍<span class="btn-label-desktop"> Weitwinkel</span>';
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
                                        switchBtn.innerHTML = '🔄<span class="btn-label-desktop"> Selfie</span>';
                                    } else {
                                        switchBtn.innerHTML = '🔄<span class="btn-label-desktop"> Kamera</span>';
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
                                        selfieBtn.innerHTML = '🤳<span class="btn-label-desktop"> Selfie: Aus</span>';
                                        return;
                                    }
                                    selfieActive = data.enabled;
                                    if (selfieActive) {
                                        selfiePip.style.display = 'block';
                                        selfieBtn.classList.add('active');
                                        selfieBtn.innerHTML = '🤳<span class="btn-label-desktop"> Selfie: An</span>';
                                        pollFront();
                                    } else {
                                        selfiePip.style.display = 'none';
                                        selfieBtn.classList.remove('active');
                                        selfieBtn.innerHTML = '🤳<span class="btn-label-desktop"> Selfie: Aus</span>';
                                    }
                                } catch (e) {
                                    console.error('Failed to toggle selfie camera:', e);
                                }
                            }

                            // --- Audio Toggle ---
                            audioBtn.addEventListener('click', async () => {
                                isAudioEnabled = !isAudioEnabled;
                                initAudioPipeline();
                                
                                // Unlock AudioContext on user click gesture (required for Safari & Chrome)
                                if (audioCtx && audioCtx.state === 'suspended') {
                                    try { await audioCtx.resume(); } catch (e) {}
                                }

                                // Inform the phone so it can stop/start the mic (battery saving)
                                fetch('/audio_state?enabled=' + isAudioEnabled, { method: 'POST' }).catch(() => {});

                                if (isAudioEnabled) {
                                    audioBtn.innerHTML = '🔊<span class="btn-label-desktop"> Ton an</span>';
                                    audioBtn.classList.remove('muted');
                                    if (audioGainNode && audioCtx) {
                                        audioGainNode.gain.setTargetAtTime(userVolume, audioCtx.currentTime, 0.02);
                                    }

                                    // If WebRTC is not connected (Snapshot fallback), start PCM stream
                                    if (!webrtcConnected) {
                                        startPcmFallbackAudio();
                                    }
                                } else {
                                    audioBtn.innerHTML = '🔇<span class="btn-label-desktop"> Ton aus</span>';
                                    audioBtn.classList.add('muted');
                                    if (audioGainNode && audioCtx) {
                                        audioGainNode.gain.setTargetAtTime(0, audioCtx.currentTime, 0.02);
                                    }
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
                                    try {
                                        megafonStream = await getMicStream();

                                        // Auto-duck incoming stream to prevent acoustic feedback howl
                                        if (audioGainNode && audioCtx) {
                                            audioGainNode.gain.setTargetAtTime(0, audioCtx.currentTime, 0.04);
                                        }

                                        // Tell phone to route to loudspeaker and set volume
                                        await fetch('/megafon?active=true', { method: 'POST' });
                                        fetch('/set_speaker_volume?vol=' + megafonVolume, { method: 'POST' }).catch(() => {});

                                        const micTrack = megafonStream.getAudioTracks()[0];

                                        if (webrtcConnected && audioTc && audioTc.sender) {
                                            await audioTc.sender.replaceTrack(micTrack);
                                            console.log('Megafon active via WebRTC');
                                        } else {
                                            startPcmMegafonFallback(megafonStream);
                                            console.log('Megafon active via PCM fallback');
                                        }

                                        megafonBtn.classList.add('active');
                                        megafonBtn.innerHTML = '📢<span class="btn-label-desktop"> Megafon: An</span>';
                                    } catch (err) {
                                        console.error('Megafon error:', err);
                                        isMegafonActive = false;
                                        megafonBtn.classList.remove('active');
                                        megafonBtn.innerHTML = '📢<span class="btn-label-desktop"> Megafon</span>';
                                        if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
                                            alert('🎤 Mikrofon-Zugriff im Browser verweigert!\n\n1. Klicke links in der Adressleiste auf das Schloss 🔒 bzw. Kamera/Mikrofon-Icon.\n2. Ändere "Mikrofon" auf "Erlauben".\n3. Lade die Seite neu.');
                                        } else if (err.name === 'NotFoundError') {
                                            alert('❌ Kein Mikrofon an diesem PC / Gerät gefunden.');
                                        } else if (err.name === 'NotReadableError') {
                                            alert('⚠️ Mikrofon wird gerade von einer anderen App belegt (z.B. Discord/Teams).');
                                        } else {
                                            showBraveHelpModal();
                                        }
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
                                    megafonBtn.innerHTML = '📢<span class="btn-label-desktop"> Megafon</span>';

                                    // Restore incoming stream volume smoothly after Megafon stops
                                    setTimeout(() => {
                                        if (isAudioEnabled && audioGainNode && audioCtx && !isMegafonActive) {
                                            audioGainNode.gain.setTargetAtTime(userVolume, audioCtx.currentTime, 0.08);
                                        }
                                    }, 300);
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
                                        if (event.track.kind === 'audio') {
                                            initAudioPipeline();
                                            try {
                                                const audioStream = (event.streams && event.streams[0]) ? event.streams[0] : new MediaStream([event.track]);
                                                if (audioSourceNode) {
                                                    try { audioSourceNode.disconnect(); } catch (e) {}
                                                }
                                                if (audioCtx && audioGainNode) {
                                                    audioSourceNode = audioCtx.createMediaStreamSource(audioStream);
                                                    audioSourceNode.connect(audioGainNode);
                                                    console.log('WebRTC Audio Track connected to AudioContext GainNode!');
                                                }
                                            } catch (e) {
                                                console.warn('Audio node connection error:', e);
                                            }
                                        }

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
                                            videoEl.play().catch(e => console.log('videoEl play:', e));
                                        } else if (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed') {
                                            console.warn('ICE connection state:', pc.iceConnectionState);
                                            if (webrtcConnected) {
                                                webrtcConnected = false;
                                                fallbackToPolling();
                                                setTimeout(() => {
                                                    if (!webrtcConnected) {
                                                        console.log('Auto-reconnecting WebRTC...');
                                                        startWebRtc();
                                                    }
                                                }, 1500);
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

                                    // Ultra-fast auto catch-up: Keep video playback tightly locked to live edge without dropping audio decoder clock
                                    setInterval(() => {
                                         if (!videoEl || !webrtcConnected) return;
                                         try {
                                             if (videoEl.buffered && videoEl.buffered.length > 0) {
                                                 const liveEnd = videoEl.buffered.end(videoEl.buffered.length - 1);
                                                 const lag = liveEnd - videoEl.currentTime;
                                                 if (lag > 0.08) {
                                                     videoEl.playbackRate = 1.05;
                                                 } else {
                                                     videoEl.playbackRate = 1.0;
                                                 }
                                             }
                                         } catch (e) {}
                                     }, 100);

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
                                initAudioPipeline();
                                if (audioCtx && audioCtx.state === 'suspended') {
                                    try { await audioCtx.resume(); } catch(e){}
                                }
                                pcmAbortController = new AbortController();
                                try {
                                    const resp = await fetch('/mic_stream', { signal: pcmAbortController.signal });
                                    const reader = resp.body.getReader();
                                    const sampleRate = 44100;
                                    let nextPlayTime = (audioCtx ? audioCtx.currentTime : 0);

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

                                        if (!audioCtx) break;
                                        const buf = audioCtx.createBuffer(1, numSamples, sampleRate);
                                        buf.copyToChannel(float32, 0);
                                        const src = audioCtx.createBufferSource();
                                        src.buffer = buf;
                                        if (audioGainNode) {
                                            src.connect(audioGainNode);
                                        } else {
                                            src.connect(audioCtx.destination);
                                        }

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

                            function onFastPhotoToggle(checked) {
                                fetch('/toggle_fast_photo?enabled=' + checked, { method: 'POST' }).catch(() => {});
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

                            function onGpsIntervalChange(val) {
                                fetch('/set_gps_interval?interval=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
                            }

                            // --- Map & Location ---
                            var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([0, 0], 15);
                            L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
                            var marker = L.marker([0, 0]).addTo(map);
                            var firstUpdate = true;
                            var locationIntervalId = null;

                            let isMapVisible = localStorage.getItem('pac_show_map') !== 'false';

                            function toggleMapVisibility(targetState) {
                                if (typeof targetState === 'boolean') {
                                    isMapVisible = targetState;
                                } else {
                                    isMapVisible = !isMapVisible;
                                }
                                localStorage.setItem('pac_show_map', isMapVisible);
                                const mapPip = document.getElementById('mapPip');
                                const mapBtn = document.getElementById('mapBtn');
                                if (isMapVisible) {
                                    if (mapPip) mapPip.style.display = 'block';
                                    if (mapBtn) mapBtn.classList.add('active');
                                    setTimeout(() => { map.invalidateSize(); }, 150);
                                    startLocationPolling();
                                } else {
                                    if (mapPip) mapPip.style.display = 'none';
                                    if (mapBtn) mapBtn.classList.remove('active');
                                    stopLocationPolling();
                                }
                            }

                            async function updateLocation() {
                                if (!isMapVisible) return;
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

                            function startLocationPolling() {
                                if (locationIntervalId) clearInterval(locationIntervalId);
                                updateLocation();
                                locationIntervalId = setInterval(updateLocation, 4000);
                            }

                            function stopLocationPolling() {
                                if (locationIntervalId) {
                                    clearInterval(locationIntervalId);
                                    locationIntervalId = null;
                                }
                            }

                            toggleMapVisibility(isMapVisible);

                            // --- Session & Viewer Heartbeat ---
                            let mySessionId = sessionStorage.getItem('pac_session_id');
                            if (!mySessionId) {
                                mySessionId = 's_' + Math.random().toString(36).substring(2, 9) + '_' + Date.now().toString(36);
                                sessionStorage.setItem('pac_session_id', mySessionId);
                            }

                            function updateViewerBadge(count) {
                                const el = document.getElementById('viewerText');
                                if (!el) return;
                                if (count <= 1) {
                                    el.textContent = '1 Zuschauer (Du)';
                                } else {
                                    el.textContent = count + ' Zuschauer (inkl. Dir)';
                                }
                            }

                            async function sendHeartbeat() {
                                try {
                                    const resp = await fetch('/heartbeat?session_id=' + encodeURIComponent(mySessionId));
                                    if (resp.ok) {
                                        const data = await resp.json();
                                        updateViewerBadge(data.viewers);
                                    }
                                } catch(e) {}
                            }
                            setInterval(sendHeartbeat, 2000);
                            sendHeartbeat();

                            window.addEventListener('beforeunload', () => {
                                try {
                                    navigator.sendBeacon('/leave_session?session_id=' + encodeURIComponent(mySessionId));
                                } catch(e) {}
                            });
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
                val json = """{"connected":$isConnected,"email":"$email","photoBackup":${GoogleDriveBackupManager.isAutoBackupEnabled},"clipBackup":${GoogleDriveBackupManager.isClipBackupEnabled},"lastStatus":"${GoogleDriveBackupManager.lastBackupStatus.replace("\"", "\\\"")}"}"""
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                res.addHeader("Access-Control-Allow-Origin", "*")
                res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                return res
            }
            "/upload_clip" -> {
                if (session.method == Method.POST) {
                    val filename = session.parms["name"] ?: "clip_${System.currentTimeMillis()}.webm"
                    val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                    val svc = service
                    if (contentLength > 0 && svc != null) {
                        val buffer = ByteArray(contentLength)
                        var totalRead = 0
                        while (totalRead < contentLength) {
                            val r = session.inputStream.read(buffer, totalRead, contentLength - totalRead)
                            if (r == -1) break
                            totalRead += r
                        }
                        if (totalRead > 0) {
                            val clipData = if (totalRead == contentLength) buffer else buffer.copyOf(totalRead)
                            GoogleDriveBackupManager.uploadClipAsync(
                                svc,
                                clipData,
                                filename,
                                if (filename.endsWith(".mp4")) "video/mp4" else "video/webm"
                            )
                            val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true,\"bytes\":$totalRead}")
                            res.addHeader("Access-Control-Allow-Origin", "*")
                            return res
                        }
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
