package com.example.cameralive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.camera.video.VideoCapture
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CameraStreamingService : LifecycleService(), CameraController, LocationListener {

    companion object {
        private const val TAG = "CameraStreaming"
        private const val ACTION_STOP = "com.example.cameralive.STOP"
        private const val NOTIFICATION_ID = 1
        const val PREFS = "CameraLivePrefs"
        const val KEY_USER_STOPPED = "KEY_USER_STOPPED"
        private const val KEY_STREAM_FPS = "KEY_STREAM_FPS"
        private const val KEY_STREAM_QUALITY = "KEY_STREAM_QUALITY"
        private const val KEY_STREAM_RES = "KEY_STREAM_RES"
        private const val POWER_TICK_MS = 2_000L
        private const val CLIENT_ACTIVITY_MS = 3_000L
        private const val FRAME_WATCHDOG_MS = 8_000L
        private const val LOW_POWER_FPS = 5
        private const val SNAPSHOT_INTERVAL_MS = 500L
        private const val AI_INTERVAL_LIVE_MS = 300L
        private const val AI_INTERVAL_IDLE_MS = 400L
        private const val AI_MAX_EDGE = 640
        val activeViewers = kotlinx.coroutines.flow.MutableStateFlow(0)
        val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
        @Volatile var runningInstance: CameraStreamingService? = null
    }

    private val CHANNEL_ID = "PhoneAsCamStream"
    var mjpegServer: MjpegServer? = null
    private val cameraExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val aiExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun invalidateAllSessions() {
        mjpegServer?.authenticatedSessions?.clear()
        // Waits for a running offer, so never on the main thread.
        val rtc = webRtcManager
        if (rtc != null) cameraExecutor.execute { rtc.closePeerConnection() }
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    private var activeCamera: Camera? = null
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var isFlashlightOn = false
    private var isWideAngle = false
    private var ultraWideCameraId: String? = null
    
    private var audioRecord: AudioRecord? = null
    private val isRecordingAudio = AtomicBoolean(false)
    
    private var locationManager: LocationManager? = null
    
    // Performance settings - default to 20 FPS for optimal thermal & battery efficiency
    val jpegQuality = AtomicInteger(20)
    val maxFps = AtomicInteger(20)
    val targetResolution = AtomicReference<String>("480p") // "480p", "720p", "1080p"
    val isFrontCameraEnabled = AtomicBoolean(false)
    val currentExposureIndex = AtomicInteger(0)
    val currentZoomRatio = AtomicReference<Float>(1.0f)

    // Auto-Night-Boost settings
    val autoNightBoostEnabled = AtomicBoolean(false)
    val autoTorchNightEnabled = AtomicBoolean(false)
    @Volatile private var isNightBoostActive = false
    @Volatile private var isAutoTorchActive = false
    @Volatile private var darkFrameCount = 0
    @Volatile private var brightFrameCount = 0
    
    @Volatile private var lastSnapshotTime = 0L
    @Volatile private var lastAiAnalysisTime = 0L

    var webRtcManager: WebRtcManager? = null
    @Volatile private var cameraRetryPending = false
    @Volatile private var backNv21Buffer: ByteArray? = null
    @Volatile private var frontNv21Buffer: ByteArray? = null

    @Volatile private var isPhotoRequested = false
    private val photoLock = Any()
    @Volatile private var latestCapturedPhoto: ByteArray? = null
    @Volatile private var photoLatch: CountDownLatch? = null

    // Separate quality for on-demand photos (independent of stream quality)
    val photoJpegQuality = AtomicInteger(95)

    // false = high-res mode (best sensor, ~1-2s stream pause)
    // true  = fast mode (stream camera, no pause, lower resolution)
    val fastPhotoMode = AtomicBoolean(false)

    // Human-readable label of the auto-detected best camera (filled on first use)
    @Volatile var detectedCameraLabel: String = "Erkenne..."

    // ImageCapture use-case for native full-sensor-resolution photos
    @Volatile private var imageCapture: ImageCapture? = null

    // Video recording properties
    @Volatile private var videoCapture: VideoCapture<Recorder>? = null
    @Volatile private var currentRecording: Recording? = null
    private val recordingExecutor = Executors.newSingleThreadExecutor()
    private val snapshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Camera power management, see evaluatePower()
    private val powerPolicy = CameraPowerPolicy()
    @Volatile private var cameraPowerState = CameraPowerState.OFF
    @Volatile private var cameraBound = false
    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var lastClientActivityMs = Long.MIN_VALUE / 2
    @Volatile private var highResCaptureRunning = false
    private var powerTickRunning = false
    private var rebindAttempts = 0
    private var lastRebindMs = 0L
    private var locationWanted = false
    private val wakePosted = AtomicBoolean(false)
    private var observedCameraState: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null

    // Frame pipeline
    private val backThrottle = FrameThrottle(20)
    private val frontThrottle = FrameThrottle(10)
    private val motionGate = MotionGate()
    private val snapshotBusy = AtomicBoolean(false)
    private val aiBusy = AtomicBoolean(false)
    @Volatile private var snapshotBuffer: ByteArray? = null
    @Volatile private var aiFrame: ByteArray? = null
    private var aiPixels: IntArray? = null
    private var aiBitmap: Bitmap? = null
    @Volatile var isCurrentlyRecordingAlarmVideo = false

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == android.os.BatteryManager.BATTERY_STATUS_FULL
                if (pct >= 0) {
                    WebhookManager.onBatteryChanged(pct, isCharging)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        createNotificationChannel()

        // Call startForeground() IMMEDIATELY in onCreate to avoid the 5-second FGS deadline.
        // Android 14 requires this before any slow operations like WebRTC init.
        try {
            startForegroundWithGrantedTypes(buildNotification("Startet"))
        } catch (e: Exception) {
            // Without the camera permission Android refuses a camera service. Stop instead of crashing.
            Log.e(TAG, "Could not enter foreground", e)
            runningInstance = null
            stopSelf()
            return
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
        isRunning.value = true
        currentExposureIndex.set(prefs.getInt("KEY_EXPOSURE_INDEX", 0))
        maxFps.set(prefs.getInt(KEY_STREAM_FPS, maxFps.get()))
        jpegQuality.set(prefs.getInt(KEY_STREAM_QUALITY, jpegQuality.get()))
        targetResolution.set(prefs.getString(KEY_STREAM_RES, null) ?: targetResolution.get())
        backThrottle.fps = maxFps.get()

        WebPages.init(this)
        WebhookManager.init(this)
        GoogleDriveBackupManager.init(this)
        AiMotionDetector.init(this)
        AlarmSoundManager.init(this)
        P2pSignalingManager.init(this, this)
        try {
            registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Log.e(TAG, "Error registering battery receiver", e)
        }

        // The partial wake lock keeps the server answering with the screen off. The Wi-Fi lock is
        // only taken while streaming, see evaluatePower().
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraLive::StreamingWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CameraLive::StreamingWifiLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up WakeLock / WifiLock", e)
        }

        // Init WebRTC on a background thread so we don't block the main thread
        cameraExecutor.execute {
            try {
                val mgr = WebRtcManager(this)
                mgr.init(maxFps.get())
                val (w, h) = resolutionSize(targetResolution.get())
                mgr.updateResolution(w, h)
                webRtcManager = mgr
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC init failed", e)
            }
        }
    }

    /** Declares only the service types whose permission is granted. Android 14 crashes on any other. */
    private fun startForegroundWithGrantedTypes(notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        fun granted(permission: String) =
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

        var types = 0
        if (granted(Manifest.permission.CAMERA)) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && granted(Manifest.permission.RECORD_AUDIO)) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        check(types and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0) { "Camera permission missing" }
        startForeground(NOTIFICATION_ID, notification, types)
    }

    private fun saveStreamSettings() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STREAM_FPS, maxFps.get())
            .putInt(KEY_STREAM_QUALITY, jpegQuality.get())
            .putString(KEY_STREAM_RES, targetResolution.get())
            .apply()
    }

    private fun resolutionSize(res: String?): Pair<Int, Int> = when (res) {
        "verlustfrei" -> Pair(1920, 1440)
        "1080p" -> Pair(1920, 1080)
        "720p" -> Pair(1280, 720)
        else -> Pair(640, 480)
    }

    fun setStreamingFps(fps: Int) {
        maxFps.set(fps)
        webRtcManager?.updateFps(fps)
        saveStreamSettings()
        mainHandler.post {
            if (cameraPowerState == CameraPowerState.FULL) {
                backThrottle.fps = fps
                applyFpsRange()
            }
        }
    }

    fun setStreamingQuality(quality: Int) {
        val clamped = quality.coerceIn(10, 95)
        jpegQuality.set(clamped)
        webRtcManager?.updateQuality(clamped)
        saveStreamSettings()
    }

    fun setStreamingResolution(res: String) {
        val validRes = when (res.lowercase()) {
            "verlustfrei", "4:3", "max_4_3" -> "verlustfrei"
            "1080p", "fhd" -> "1080p"
            "720p", "hd" -> "720p"
            else -> "480p"
        }
        val prev = targetResolution.getAndSet(validRes)
        val (w, h) = resolutionSize(validRes)
        webRtcManager?.updateResolution(w, h)
        saveStreamSettings()
        if (prev != validRes) {
            mainHandler.post { startCamera() }
        }
    }

    fun setPhotoQuality(quality: Int) {
        photoJpegQuality.set(quality.coerceIn(10, 100))
    }

    fun setFastPhotoMode(enabled: Boolean) {
        fastPhotoMode.set(enabled)
    }

    /** Hands the camera back to the stream after a high-res photo. */
    private fun finishHighResCapture() {
        if (!highResCaptureRunning) return
        highResCaptureRunning = false
        try {
            ProcessCameraProvider.getInstance(this).get(500, TimeUnit.MILLISECONDS).unbindAll()
        } catch (_: Exception) {}
        cameraBound = false
        activeCamera = null
        rebindAttempts = 0
        startCamera()
    }

    /** Standby releases the camera. The request that woke it has to wait for the first frames. */
    private fun awaitCameraReady(timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cameraBound && imageCapture != null) return
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    fun takeHighQualityPhoto(): ByteArray? {
        onClientActivity()
        if (fastPhotoMode.get()) {
            awaitCameraReady(3000)
            // --- FAST MODE (0s stream pause, uses currently bound active camera stream / imageCapture) ---
            val capture = imageCapture
            if (capture != null) {
                val latch = CountDownLatch(1)
                var resultBytes: ByteArray? = null
                val outputStream = ByteArrayOutputStream()
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(outputStream).build(),
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            resultBytes = outputStream.toByteArray()
                            latch.countDown()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Fast ImageCapture failed, falling back to frame", exception)
                            latch.countDown()
                        }
                    }
                )
                try {
                    latch.await(2000, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "Fast ImageCapture timeout", e)
                }
                if (resultBytes != null && resultBytes.isNotEmpty()) {
                    Log.i(TAG, "Fast photo captured: ${resultBytes.size / 1024} KB")
                    if (GoogleDriveBackupManager.isAutoBackupEnabled) {
                        GoogleDriveBackupManager.uploadPhotoAsync(this, resultBytes)
                    }
                    return resultBytes
                }
            }

            // Fallback to active NV21 frame immediately without pausing stream
            val streamLatch = CountDownLatch(1)
            synchronized(photoLock) {
                latestCapturedPhoto = null
                this.photoLatch = streamLatch
                isPhotoRequested = true
            }
            try {
                streamLatch.await(2000, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Fast stream-frame fallback timeout", e)
            } finally {
                synchronized(photoLock) {
                    isPhotoRequested = false
                    this.photoLatch = null
                }
            }
            latestCapturedPhoto?.let { photo ->
                if (GoogleDriveBackupManager.isAutoBackupEnabled) {
                    GoogleDriveBackupManager.uploadPhotoAsync(this, photo)
                }
            }
            return latestCapturedPhoto
        }

        // --- HIGH-RES NATIVE MODE (Auto-detects best 50MP+ sensor, brief pause & resume) ---
        if (highResCameraId == null) {
            findHighResCamera()
        }

        val photoLatch = CountDownLatch(1)
        var resultBytes: ByteArray? = null

        // Build a CameraX CameraSelector targeting the highest-res physical back camera
        val highResSelector = highResCameraId?.let { id ->
            try {
                CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .addCameraFilter { infos ->
                        val matched = infos.filter {
                            Camera2CameraInfo.from(it).cameraId == id
                        }
                        if (matched.isNotEmpty()) matched else infos
                    }
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "Could not build high-res camera selector", e)
                null
            }
        }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(photoJpegQuality.get())

        if (highResTargetSize != null) {
            captureBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            highResTargetSize!!,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
        } else {
            captureBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(Int.MAX_VALUE, Int.MAX_VALUE),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                        )
                    )
                    .build()
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isUltraHighResMode) {
            androidx.camera.camera2.interop.Camera2Interop.Extender(captureBuilder).apply {
                setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_PIXEL_MODE,
                    android.hardware.camera2.CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                )
            }
        }

        val captureUseCase = captureBuilder.build()

        // Perform binding + capture on the main thread (CameraX requirement)
        highResCaptureRunning = true
        mainHandler.post {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(this).get(2, TimeUnit.SECONDS)

                // Temporarily unbind everything so we can open the high-res sensor exclusively
                cameraProvider.unbindAll()
                cameraBound = false
                imageCapture = null

                val selector = highResSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.bindToLifecycle(this, selector, captureUseCase)
                Log.i(TAG, "High-res capture session bound (selector=${highResCameraId ?: "default"})")

                val outputStream = ByteArrayOutputStream()
                captureUseCase.takePicture(
                    ImageCapture.OutputFileOptions.Builder(outputStream).build(),
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            resultBytes = outputStream.toByteArray()
                            Log.i(TAG, "High-res photo: ${(resultBytes?.size ?: 0) / 1024} KB")
                            mainHandler.post { finishHighResCapture() }
                            photoLatch.countDown()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "High-res ImageCapture failed", exception)
                            mainHandler.post { finishHighResCapture() }
                            photoLatch.countDown()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind high-res capture session", e)
                finishHighResCapture()
                photoLatch.countDown()
            }
        }

        // Wait for the photo (up to 8s for the full round-trip on slow sensors)
        try {
            if (!photoLatch.await(8000, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "High-res capture timed out")
                mainHandler.post { finishHighResCapture() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "High-res capture wait interrupted", e)
        }

        val finalPhoto = if (resultBytes != null && resultBytes.isNotEmpty()) resultBytes else latestCapturedPhoto
        if (finalPhoto != null && finalPhoto.isNotEmpty()) {
            if (GoogleDriveBackupManager.isAutoBackupEnabled) {
                GoogleDriveBackupManager.uploadPhotoAsync(this, finalPhoto)
            }
        }
        return finalPhoto
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USER_STOPPED, true).apply()
            stopSelf()
            return Service.START_NOT_STICKY
        }

        val port = intent?.getIntExtra("PORT", 8080) ?: 8080
        // Repeated starts, like an activity recreate, must not interrupt a running stream.
        if (mjpegServer?.isAlive != true || mjpegServer?.listeningPort != port) {
            startServer(port)
        }
        startLocationTracking()
        if (!powerTickRunning) {
            powerTickRunning = true
            mainHandler.post(powerTick)
        }
        updateNotification()

        return Service.START_NOT_STICKY
    }

    private val powerTick = object : Runnable {
        override fun run() {
            evaluatePower()
            mainHandler.postDelayed(this, POWER_TICK_MS)
        }
    }

    private fun startServer(port: Int) {
        try {
            mjpegServer?.stop()
            mjpegServer = MjpegServer(port, this)
            mjpegServer?.service = this
            mjpegServer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Web server could not start on port $port", e)
        }
    }
    
    @Volatile var gpsIntervalSec: Int = 300 // Default 5 minutes for stationary deployment (0 = off)

    fun setLocationInterval(seconds: Int) {
        gpsIntervalSec = seconds.coerceAtLeast(0)
        android.os.Handler(mainLooper).post {
            updateLocationTracking()
        }
    }

    private fun updateLocationTracking() {
        locationManager?.removeUpdates(this)
        if (gpsIntervalSec <= 0 || !locationWanted) {
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val minTimeMs = gpsIntervalSec * 1000L
        try {
            val manager = locationManager ?: return
            (manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { onLocationChanged(it) }
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, 10f, this)
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, 10f, this)
            }
            Log.i(TAG, "GPS tracking on: interval = ${gpsIntervalSec}s")
        } catch (e: Exception) {
            Log.w(TAG, "Error updating location updates", e)
        }
    }
    
    private fun startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        val lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        lastLocation?.let {
            mjpegServer?.currentLat = it.latitude
            mjpegServer?.currentLng = it.longitude
        }
        
        updateLocationTracking()
    }

    override fun onLocationChanged(location: Location) {
        mjpegServer?.currentLat = location.latitude
        mjpegServer?.currentLng = location.longitude
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // Physical camera ID and native full resolution for highest quality photo
    @Volatile private var highResCameraId: String? = null
    @Volatile private var highResTargetSize: Size? = null
    @Volatile private var isUltraHighResMode = false

    /** Scans all back-facing physical cameras (including logical multi-cam sub-sensors and maximum resolution maps) */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun findHighResCamera(): String? {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var bestId: String? = null
            var bestSize: Size? = null
            var ultra = false
            var maxPixels = 0L

            fun evaluateSizes(id: String, sizes: Array<Size>?, isMaxRes: Boolean) {
                if (sizes == null) return
                for (size in sizes) {
                    val pixels = size.width.toLong() * size.height
                    if (pixels > maxPixels) {
                        maxPixels = pixels
                        bestId = id
                        bestSize = size
                        ultra = isMaxRes
                    }
                }
            }

            val allCameraIds = cm.cameraIdList.toMutableSet()
            for (id in cm.cameraIdList) {
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        allCameraIds.addAll(chars.physicalCameraIds)
                    }
                } catch (e: Exception) {}
            }

            for (id in allCameraIds) {
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing != null && facing != CameraCharacteristics.LENS_FACING_BACK) continue

                    // 1. Check standard stream configuration map
                    val stdMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    evaluateSizes(id, stdMap?.getOutputSizes(ImageFormat.JPEG), false)

                    // 2. Check Android 12+ (API 31+) ultra-high-resolution maximum resolution map (50MP/108MP/200MP Quad-Bayer unbinned)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                        evaluateSizes(id, maxResMap?.getOutputSizes(ImageFormat.JPEG), true)
                    }

                    // 3. Check pixel array size as fallback
                    val arraySize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                            ?: chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    } else {
                        chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    }
                    if (arraySize != null) {
                        val p = arraySize.width.toLong() * arraySize.height
                        if (p > maxPixels && bestSize == null) {
                            maxPixels = p
                            bestId = id
                            bestSize = arraySize
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying camera $id", e)
                }
            }

            highResCameraId = bestId
            highResTargetSize = bestSize
            isUltraHighResMode = ultra

            if (bestId != null && bestSize != null) {
                val mp = (maxPixels + 500_000) / 1_000_000
                detectedCameraLabel = "Sensor #$bestId (${bestSize.width}x${bestSize.height}, ~${mp} MP)"
                Log.i(TAG, "Highest-res photo sensor: ID=$bestId (${bestSize.width}x${bestSize.height}, $mp MP, ultra=$ultra)")
            } else {
                detectedCameraLabel = "Standard-Sensor"
            }
            return bestId
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning cameras for highest resolution", e)
            detectedCameraLabel = "Auto"
            return null
        }
    }

    private fun findUltraWideCameraId(): String? {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var bestWideId: String? = null
            var minFocal = Float.MAX_VALUE
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    if (focals != null && focals.isNotEmpty()) {
                        val f = focals.minOrNull() ?: Float.MAX_VALUE
                        if (f < minFocal) {
                            minFocal = f
                            bestWideId = id
                        }
                    }
                }
            }
            // Ultra wide focal length is typically < 3.2mm (standard lens is ~4.2-5.5mm)
            if (minFocal < 3.2f) {
                Log.i(TAG, "Discovered physical Ultra-Wide Camera ID: $bestWideId (focal length: ${minFocal}mm)")
                return bestWideId
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying CameraManager for ultra-wide lens", e)
        }
        return null
    }

    fun requestCameraRestart() {
        android.os.Handler(mainLooper).post { startCamera() }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun startCamera() {
        if (cameraPowerState == CameraPowerState.OFF || runningInstance !== this) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            if (cameraPowerState == CameraPowerState.OFF || runningInstance !== this) return@addListener
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            cameraBound = false

            if (ultraWideCameraId == null) {
                ultraWideCameraId = findUltraWideCameraId()
            }
            if (highResCameraId == null) {
                findHighResCamera()
            }

            val currentRes = targetResolution.get() ?: "480p"
            val (targetSize, targetAspect) = when (currentRes) {
                "verlustfrei", "4:3", "max_4_3" -> Pair(Size(1920, 1440), AspectRatio.RATIO_4_3)
                "1080p" -> Pair(Size(1920, 1080), AspectRatio.RATIO_16_9)
                "720p" -> Pair(Size(1280, 720), AspectRatio.RATIO_16_9)
                else -> Pair(Size(640, 480), AspectRatio.RATIO_4_3)
            }

            val backResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        targetSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .setAspectRatioStrategy(
                    AspectRatioStrategy(
                        targetAspect,
                        AspectRatioStrategy.FALLBACK_RULE_AUTO
                    )
                )
                .build()

            val backBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(backResolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            val primaryAnalyzer = backBuilder.build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    val now = SystemClock.elapsedRealtime()
                    lastFrameAtMs = now
                    if (!backThrottle.shouldPass(now)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    processImage(imageProxy)
                }
            }

            val frontResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(320, 240),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val frontAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(frontResolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isFrontCameraEnabled.get() || !frontThrottle.shouldPass(SystemClock.elapsedRealtime())) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        processFrontImage(imageProxy)
                    }
                }

            // Build ImageCapture for native full-resolution on-demand photos
            val primaryImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(photoJpegQuality.get())
                .build()

            // Build VideoCapture for alarm recordings
            val qualitySelector = QualitySelector.from(
                Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .setQualitySelector(qualitySelector)
                .build()
            val primaryVideoCapture = VideoCapture.withOutput(recorder)

            // 1. Try concurrent camera API ONLY if front camera is requested AND phone supports it
            val concurrentInfos = cameraProvider.availableConcurrentCameraInfos
            if (isFrontCameraEnabled.get() && concurrentInfos.isNotEmpty() && currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                Log.i(TAG, "Concurrent camera mode active for selfie PiP")
            try {
                    val configs = listOf(
                        ConcurrentCamera.SingleCameraConfig(
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            UseCaseGroup.Builder()
                                .addUseCase(primaryAnalyzer)
                                .addUseCase(primaryImageCapture)
                                .build(),
                            this
                        ),
                        ConcurrentCamera.SingleCameraConfig(
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            UseCaseGroup.Builder().addUseCase(frontAnalyzer).build(),
                            this
                        )
                    )
                    val concurrentCamera = cameraProvider.bindToLifecycle(configs)
                    activeCamera = concurrentCamera.cameras.firstOrNull()
                    imageCapture = primaryImageCapture
                    videoCapture = null
                    onCameraBound()
                    Log.i(TAG, "Concurrent cameras bound successfully (with ImageCapture)")
                    return@addListener
                } catch (e: Exception) {
                    Log.w(TAG, "Concurrent camera binding failed, falling back to single camera", e)
                    cameraProvider.unbindAll()
                    imageCapture = null
                }
            }

            // 2. Single Camera Mode: select between Front or Back (with Ultra-Wide support)
            val selector: CameraSelector = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                // Back camera: use physical ultra wide camera if active and discovered
                if (isWideAngle && ultraWideCameraId != null) {
                    CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .addCameraFilter { cameraInfos ->
                            val matched = cameraInfos.filter {
                                Camera2CameraInfo.from(it).cameraId == ultraWideCameraId
                            }
                            if (matched.isNotEmpty()) matched else cameraInfos
                        }
                        .build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            }

            try {
                // Try binding with ImageCapture (+ optional VideoCapture for alarm recording)
                if (AiMotionDetector.isAutoVideoDriveEnabled) {
                    activeCamera = cameraProvider.bindToLifecycle(
                        this, selector, primaryAnalyzer, primaryImageCapture, primaryVideoCapture
                    )
                    videoCapture = primaryVideoCapture
                } else {
                    activeCamera = cameraProvider.bindToLifecycle(
                        this, selector, primaryAnalyzer, primaryImageCapture
                    )
                    videoCapture = null
                }
                imageCapture = primaryImageCapture
                onCameraBound()
                Log.i(TAG, "Bound primary camera + ImageCapture (VideoAlarm=${AiMotionDetector.isAutoVideoDriveEnabled}): facing=$currentLensFacing")
            } catch (exc: Exception) {
                Log.w(TAG, "Failed to bind with full use-cases, retrying minimal set", exc)
                imageCapture = null
                videoCapture = null
                try {
                    cameraProvider.unbindAll()
                    activeCamera = cameraProvider.bindToLifecycle(this, selector, primaryAnalyzer)
                    onCameraBound()
                    Log.i(TAG, "Bound primary camera with ImageAnalysis only (fallback)")
                } catch (exc2: Exception) {
                    Log.e(TAG, "Failed to bind camera: $selector", exc2)
                    try {
                        currentLensFacing = CameraSelector.LENS_FACING_BACK
                        activeCamera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, primaryAnalyzer)
                        onCameraBound()
                    } catch (e2: Exception) {
                        // The power tick retries with backoff.
                        Log.e(TAG, "Could not bind any camera", e2)
                        activeCamera = null
                        cameraBound = false
                    }
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyExposureCompensation() {
        val cam = activeCamera ?: return
        val exposureState = cam.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) {
            Log.i(TAG, "Exposure compensation not supported on active camera")
            return
        }
        val range = exposureState.exposureCompensationRange
        val targetIndex = currentExposureIndex.get().coerceIn(range.lower, range.upper)
        try {
            cam.cameraControl.setExposureCompensationIndex(targetIndex)
            Log.i(TAG, "Applied exposure compensation index: $targetIndex (Range: $range, Step: ${exposureState.exposureCompensationStep})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply exposure compensation index $targetIndex", e)
        }
    }

    private fun applyWideAngleIfActive() {
        val cam = activeCamera ?: return
        val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 10.0f
        val zoomRatio = if (isWideAngle) {
            if (minZoom < 1.0f) minZoom else 1.0f
        } else {
            currentZoomRatio.get().coerceIn(minZoom, maxZoom)
        }
        try {
            cam.cameraControl.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting zoom ratio: $zoomRatio", e)
        }
    }

    private fun observeCameraErrors(camera: Camera?) {
        val state = camera?.cameraInfo?.cameraState ?: return
        observedCameraState?.removeObserver(cameraStateObserver)
        observedCameraState = state
        state.observe(this, cameraStateObserver)
    }

    private val cameraStateObserver = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { state ->
        val error = state.error ?: return@Observer
        // CameraX retries recoverable errors itself. These two need a fresh bind.
        val needsRebind = error.code == androidx.camera.core.CameraState.ERROR_CAMERA_DISABLED ||
            error.code == androidx.camera.core.CameraState.ERROR_CAMERA_FATAL_ERROR
        if (needsRebind) {
            Log.w(TAG, "Camera error ${error.code}, the watchdog will rebind")
            cameraBound = false
        }
    }

    private fun onCameraBound() {
        cameraBound = true
        lastFrameAtMs = SystemClock.elapsedRealtime()
        if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            applyTorchState(isFlashlightOn)
            applyWideAngleIfActive()
        }
        applyExposureCompensation()
        observeCameraErrors(activeCamera)
        applyFpsRange()
    }

    /** Applies the sensor speed for the current state without tearing the session down. */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyFpsRange() {
        val cam = activeCamera ?: return
        try {
            val target = if (cameraPowerState == CameraPowerState.LOW) LOW_POWER_FPS else maxFps.get()
            val ranges = Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower to it.upper } ?: return
            val picked = pickFpsRange(ranges, target) ?: return
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(picked.first, picked.second)
                )
                .build()
            androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
                .setCaptureRequestOptions(options)
            Log.i(TAG, "Sensor range ${picked.first}-${picked.second} fps (state=$cameraPowerState, target=$target)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply FPS range", e)
        }
    }

    /** Runs every 2 seconds on the main thread and whenever a client shows up. */
    private fun evaluatePower() {
        if (runningInstance !== this) return
        val now = SystemClock.elapsedRealtime()
        val viewerActive = mjpegServer?.hasActiveClients() == true || now - lastClientActivityMs < CLIENT_ACTIVITY_MS
        val newState = powerPolicy.evaluate(
            nowMs = now,
            viewerActive = viewerActive,
            aiEnabled = AiMotionDetector.isEnabled && !AiMotionDetector.isUnavailable,
            recording = isCurrentlyRecordingAlarmVideo || highResCaptureRunning,
            torchOn = isFlashlightOn
        )
        val changed = newState != cameraPowerState
        cameraPowerState = newState
        backThrottle.fps = if (newState == CameraPowerState.FULL) maxFps.get() else LOW_POWER_FPS

        // High-performance Wi-Fi only matters while streaming. Idle, normal power save still answers.
        setWifiLockHeld(newState == CameraPowerState.FULL)
        setLocationWanted(viewerActive)

        when {
            newState == CameraPowerState.OFF -> releaseCamera()
            highResCaptureRunning -> Unit
            !cameraBound -> rebindWithBackoff(now)
            now - lastFrameAtMs > FRAME_WATCHDOG_MS -> {
                Log.w(TAG, "No camera frames for ${now - lastFrameAtMs} ms, rebinding")
                cameraBound = false
                rebindWithBackoff(now)
            }
            else -> rebindAttempts = 0
        }
        if (changed) {
            Log.i(TAG, "Camera power state: $newState")
            if (cameraBound) applyFpsRange()
            updateNotification()
        }
    }

    private fun rebindWithBackoff(now: Long) {
        val delay = (2_000L shl rebindAttempts.coerceAtMost(5)).coerceAtMost(60_000L)
        if (rebindAttempts > 0 && now - lastRebindMs < delay) return
        rebindAttempts++
        lastRebindMs = now
        lastFrameAtMs = now
        startCamera()
    }

    private fun releaseCamera() {
        if (!cameraBound && activeCamera == null) return
        try {
            ProcessCameraProvider.getInstance(this).get(500, TimeUnit.MILLISECONDS).unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release camera", e)
        }
        activeCamera = null
        imageCapture = null
        videoCapture = null
        cameraBound = false
        Log.i(TAG, "Camera released (standby)")
    }

    /** Called for every authorized request and offer: wakes the camera without waiting for the next tick. */
    fun onClientActivity() {
        lastClientActivityMs = SystemClock.elapsedRealtime()
        if (cameraPowerState != CameraPowerState.FULL && wakePosted.compareAndSet(false, true)) {
            mainHandler.post {
                wakePosted.set(false)
                evaluatePower()
            }
        }
    }

    private fun setWifiLockHeld(held: Boolean) {
        try {
            val lock = wifiLock ?: return
            if (held && !lock.isHeld) lock.acquire()
            if (!held && lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock change failed", e)
        }
    }

    private fun setLocationWanted(wanted: Boolean) {
        if (locationWanted == wanted) return
        locationWanted = wanted
        updateLocationTracking()
    }
    
    /** Display rotation the browser receives. Same mapping everywhere: stream, photos and AI. */
    private fun streamRotation(baseRotation: Int): Int =
        if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) (baseRotation + 90) % 360
        else (baseRotation + 270) % 360

    /** Copies a YUV_420_888 frame into [nv21]. Handles row stride != width. */
    private fun copyToNv21(imageProxy: androidx.camera.core.ImageProxy, nv21: ByteArray) {
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yBuffer = yPlane.buffer
        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val uvHeight = height / 2
        val uvWidth = width / 2
        if (uvPixelStride == 2) {
            for (row in 0 until uvHeight) {
                val isLast = row == uvHeight - 1
                vBuffer.position(row * uvRowStride)
                vBuffer.get(nv21, pos, if (isLast) width - 1 else width)
                if (isLast) {
                    val lastUIndex = (uvHeight - 1) * uvRowStride + (uvWidth - 1) * uvPixelStride
                    nv21[pos + width - 1] = uBuffer.get(lastUIndex)
                }
                pos += width
            }
        } else {
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    nv21[pos++] = vBuffer.get(uvIndex)
                    nv21[pos++] = uBuffer.get(uvIndex)
                }
            }
        }
    }

    private fun processFrontImage(imageProxy: androidx.camera.core.ImageProxy) {
        try {
            if (mjpegServer?.hasActiveFrontClients() != true) return
            val width = imageProxy.width
            val height = imageProxy.height
            val size = width * height * 3 / 2
            val nv21 = frontNv21Buffer?.takeIf { it.size == size } ?: ByteArray(size).also { frontNv21Buffer = it }
            copyToNv21(imageProxy, nv21)
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), jpegQuality.get(), out)
            mjpegServer?.broadcastFrameFront(out.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Error processing front frame", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Back camera pipeline. Copying a frame is the expensive part, so it only happens when something
     * consumes this particular frame: a WebRTC peer, a due snapshot, a due AI tick or a photo.
     */
    private fun processImage(imageProxy: androidx.camera.core.ImageProxy) {
        try {
            val server = mjpegServer
            val now = SystemClock.elapsedRealtime()
            val rtc = webRtcManager
            val wantsWebRtc = rtc?.hasActivePeer() == true
            val wantsSnapshot = server?.hasActiveBackSnapshotClients() == true &&
                now - lastSnapshotTime >= SNAPSHOT_INTERVAL_MS && !snapshotBusy.get()
            val wantsPhoto = isPhotoRequested
            val aiEnabled = AiMotionDetector.isEnabled && !AiMotionDetector.isUnavailable
            val wantsBrightness = autoNightBoostEnabled.get() && cameraPowerState == CameraPowerState.FULL
            val aiInterval = if (cameraPowerState == CameraPowerState.FULL) AI_INTERVAL_LIVE_MS else AI_INTERVAL_IDLE_MS
            val wantsAi = (aiEnabled || wantsBrightness) && now - lastAiAnalysisTime >= aiInterval && !aiBusy.get()

            if (!wantsWebRtc && !wantsSnapshot && !wantsPhoto && !wantsAi) return

            val width = imageProxy.width
            val height = imageProxy.height
            val rotation = streamRotation(imageProxy.imageInfo.rotationDegrees)
            val size = width * height * 3 / 2
            val nv21 = backNv21Buffer?.takeIf { it.size == size } ?: ByteArray(size).also { backNv21Buffer = it }
            copyToNv21(imageProxy, nv21)

            if (wantsPhoto) capturePhotoFromFrame(nv21, width, height, rotation)
            if (wantsWebRtc) rtc?.pushFrame(nv21, width, height, rotation)

            if (wantsSnapshot && snapshotBusy.compareAndSet(false, true)) {
                lastSnapshotTime = now
                val copy = snapshotBuffer?.takeIf { it.size == size } ?: ByteArray(size).also { snapshotBuffer = it }
                System.arraycopy(nv21, 0, copy, 0, size)
                val quality = jpegQuality.get()
                snapshotExecutor.execute {
                    try {
                        val out = ByteArrayOutputStream()
                        YuvImage(copy, ImageFormat.NV21, width, height, null)
                            .compressToJpeg(Rect(0, 0, width, height), quality, out)
                        mjpegServer?.broadcastFrame(out.toByteArray())
                    } catch (e: Exception) {
                        Log.w(TAG, "Snapshot encoding failed", e)
                    } finally {
                        snapshotBusy.set(false)
                    }
                }
            }

            if (wantsAi && aiBusy.compareAndSet(false, true)) {
                lastAiAnalysisTime = now
                val copy = aiFrame?.takeIf { it.size == size } ?: ByteArray(size).also { aiFrame = it }
                System.arraycopy(nv21, 0, copy, 0, size)
                aiExecutor.execute {
                    try {
                        analyzeFrame(copy, width, height, rotation, aiEnabled)
                    } catch (e: Exception) {
                        Log.e(TAG, "AI frame processing error", e)
                    } finally {
                        aiBusy.set(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun capturePhotoFromFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        try {
            val photo = encodeFrameJpeg(nv21, width, height, rotation, 95)
            synchronized(photoLock) {
                latestCapturedPhoto = photo
                isPhotoRequested = false
                photoLatch?.countDown()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo frame", e)
            synchronized(photoLock) {
                isPhotoRequested = false
                photoLatch?.countDown()
            }
        }
    }

    /** Runs on aiExecutor. [frame] stays untouched until aiBusy is cleared. */
    private fun analyzeFrame(frame: ByteArray, width: Int, height: Int, rotation: Int, aiEnabled: Boolean) {
        val grid = Nv21.lumaGrid(frame, width, height)
        processNightBoost(Nv21.meanOf(grid))
        if (!aiEnabled) return

        // A viewer sees live boxes, so the gate only applies while nobody is watching.
        val now = SystemClock.elapsedRealtime()
        val shouldInfer = motionGate.shouldInfer(grid, now) || cameraPowerState == CameraPowerState.FULL
        if (!shouldInfer) return

        val step = Nv21.scaleStep(width, height, AI_MAX_EDGE)
        val srcW = width / step
        val srcH = height / step
        val (outW, outH) = if (rotation == 90 || rotation == 270) srcH to srcW else srcW to srcH
        val pixels = aiPixels?.takeIf { it.size == outW * outH } ?: IntArray(outW * outH).also { aiPixels = it }
        Nv21.toArgb(frame, width, height, step, rotation, pixels)
        val bitmap = aiBitmap?.takeIf { it.width == outW && it.height == outH }
            ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { aiBitmap = it }
        bitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH)

        val targetSeen = AiMotionDetector.analyzeBitmap(bitmap, this) {
            encodeFrameJpeg(frame, width, height, rotation, 85)
        }
        if (targetSeen) motionGate.holdFor(now)
    }

    private fun processNightBoost(avgLuma: Int) {
        if (!autoNightBoostEnabled.get()) return
        if (avgLuma < 28) {
            darkFrameCount++
            brightFrameCount = 0
            if (darkFrameCount >= 3 && !isNightBoostActive) {
                isNightBoostActive = true
                val maxRange = getExposureRange().second
                setExposureCompensation((maxRange * 0.75f).toInt().coerceAtLeast(2))
                if (autoTorchNightEnabled.get() && !isFlashlightActive()) {
                    isAutoTorchActive = true
                    toggleFlashlight()
                }
                Log.i(TAG, "Auto night boost on (avgLuma=$avgLuma)")
            }
        } else if (avgLuma > 65) {
            brightFrameCount++
            darkFrameCount = 0
            if (brightFrameCount >= 3 && isNightBoostActive) {
                isNightBoostActive = false
                setExposureCompensation(0)
                if (isAutoTorchActive && isFlashlightActive()) {
                    isAutoTorchActive = false
                    toggleFlashlight()
                }
                Log.i(TAG, "Auto night boost off (avgLuma=$avgLuma)")
            }
        }
    }
    /** [photo] is a ready JPEG of the frame that caused the alarm. */
    fun triggerAlarmActions(message: String, photo: ByteArray?) {
        // 1. START VIDEO IMMEDIATELY (Main thread to avoid any executor queuing delays)
        if (AiMotionDetector.isAutoVideoDriveEnabled && !isCurrentlyRecordingAlarmVideo) {
            mainHandler.post { startAlarmVideoRecording() }
        }

        // Use a background thread for the rest so we don't block the camera pipeline
        recordingExecutor.execute {
            try {
                Log.i(TAG, "Processing AI alarm notification and photo")
                if (AiMotionDetector.isWebhookAlarmEnabled && WebhookManager.alarmWebhookEnabled) {
                    WebhookManager.sendAlarm(message, photo) { _, _ -> }
                }
                if (photo != null && photo.isNotEmpty() &&
                    AiMotionDetector.isAutoDriveEnabled && GoogleDriveBackupManager.isAutoBackupEnabled) {
                    GoogleDriveBackupManager.uploadPhotoAsync(this, photo)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AI alarm background actions", e)
            }
        }
    }

    /** Full-resolution JPEG of a raw frame, upright. */
    private fun encodeFrameJpeg(nv21: ByteArray, width: Int, height: Int, rotation: Int, quality: Int): ByteArray {
        val rawOut = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, rawOut)
        val rawBytes = rawOut.toByteArray()
        if (rotation == 0) return rawBytes
        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        val rotOut = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, rotOut)
        if (rotated != bmp) rotated.recycle()
        bmp.recycle()
        return rotOut.toByteArray()
    }

    private fun startAlarmVideoRecording() {
        val vCap = videoCapture ?: return
        if (isCurrentlyRecordingAlarmVideo) return

        // Record to a local file in cache first - this is faster to start than MediaStore
        val videoFile = File(externalCacheDir, "alarm_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        try {
            isCurrentlyRecordingAlarmVideo = true
            val pendingRecording = vCap.output.prepareRecording(this, outputOptions)
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pendingRecording.withAudioEnabled()
            }

            currentRecording = pendingRecording.start(recordingExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        isCurrentlyRecordingAlarmVideo = false
                        if (!event.hasError()) {
                            Log.i(TAG, "Alarm video recording finalized: ${videoFile.absolutePath}")
                            uploadVideoFileToDrive(videoFile)
                        } else {
                            Log.e(TAG, "Alarm video recording error: ${event.error}")
                            if (videoFile.exists()) videoFile.delete()
                        }
                    }
                }
            }

            Log.i(TAG, "Started alarm video recording to file...")
            
            // Auto-stop after 10 seconds
            android.os.Handler(mainLooper).postDelayed({
                stopAlarmVideoRecording()
            }, 10000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video recording", e)
            isCurrentlyRecordingAlarmVideo = false
        }
    }

    private fun stopAlarmVideoRecording() {
        currentRecording?.stop()
        currentRecording = null
    }

    private fun uploadVideoFileToDrive(file: File) {
        recordingExecutor.submit {
            try {
                if (!file.exists()) return@submit
                val bytes = file.readBytes()
                GoogleDriveBackupManager.uploadClipAsync(
                    this,
                    bytes,
                    file.name,
                    "video/mp4"
                ) { success, msg ->
                    Log.i(TAG, "Alarm video upload result: success=$success ($msg)")
                    // Delete local file after upload attempts
                    if (file.exists()) file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading video file to Drive", e)
                if (file.exists()) file.delete()
            }
        }
    }

    /**
     * Called when the browser viewer toggles audio on/off.
     * Mutes/unmutes the WebRTC audio track and stops/starts the PCM microphone
     * so the phone mic is fully idle when nobody is listening — saving battery.
     */
    override fun setRemoteAudioEnabled(enabled: Boolean, peerId: String?) {
        webRtcManager?.setAudioEnabled(enabled, peerId)
        if (!enabled) {
            // Stop PCM mic recording if no one is listening. Not forced: other viewers may still
            // be on the PCM fallback stream.
            stopMicIfIdle()
        }
        Log.i(TAG, "Remote audio enabled=$enabled")
    }

    @Synchronized
    fun startMicIfNeeded() {
        if (isRecordingAudio.get()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                record.release()
                return
            }
            record.startRecording()
            audioRecord = record
            isRecordingAudio.set(true)
            Log.d(TAG, "Started PCM fallback AudioRecord")

            audioExecutor.execute {
                val buffer = ByteArray(4096)
                try {
                    while (isRecordingAudio.get()) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read == 0) continue
                        val chunk = ByteArray(read)
                        // Apply 2.5x gain boost with soft limiter to 16-bit PCM samples
                        var i = 0
                        while (i + 1 < read) {
                            val low = buffer[i].toInt() and 0xFF
                            val high = buffer[i + 1].toInt()
                            val sample = ((high shl 8) or low).toShort()
                            val boosted = (sample * 2.5f).toInt().coerceIn(-32768, 32767)
                            chunk[i] = (boosted and 0xFF).toByte()
                            chunk[i + 1] = ((boosted shr 8) and 0xFF).toByte()
                            i += 2
                        }
                        mjpegServer?.broadcastAudio(chunk)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PCM microphone loop stopped", e)
                } finally {
                    // Released here, on the reading thread, so a concurrent stop can't free it mid-read.
                    try { record.release() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting microphone for PCM stream", e)
        }
    }

    @Synchronized
    fun stopMicIfIdle(force: Boolean = false) {
        if (!isRecordingAudio.get()) return
        if (!force && (mjpegServer?.hasMicClients() == true)) return

        isRecordingAudio.set(false)
        // stop() unblocks read(); the reading thread then releases the recorder.
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        Log.d(TAG, "Stopped PCM fallback AudioRecord")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Kamera-Stream", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CameraStreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone as Cam")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, "Stoppen", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val port = mjpegServer?.listeningPort ?: return
        val text = when (cameraPowerState) {
            CameraPowerState.FULL -> "Live auf Port $port"
            CameraPowerState.LOW -> "KI wacht, Port $port"
            CameraPowerState.OFF -> "Bereit, Kamera aus, Port $port"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
    
    private fun applyTorchState(enabled: Boolean) {
        if (currentLensFacing != CameraSelector.LENS_FACING_BACK) return
        try {
            // First attempt to control torch via CameraX activeCamera
            val hasFlashUnit = activeCamera?.cameraInfo?.hasFlashUnit() ?: false
            if (hasFlashUnit) {
                activeCamera?.cameraControl?.enableTorch(enabled)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Secondary physical lenses (like standalone Ultra-Wide) might not expose a flash unit in CameraX,
                // but the device's main flash unit on CameraManager (id "0" or primary back lens) can still be activated.
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val primaryBackId = cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                }
                if (primaryBackId != null) {
                    cm.setTorchMode(primaryBackId, enabled)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying torch state: $enabled", e)
        }
    }

    override fun toggleFlashlight(): Boolean {
        if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            isFlashlightOn = !isFlashlightOn
            applyTorchState(isFlashlightOn)
        } else {
            isFlashlightOn = false
        }
        return isFlashlightOn
    }

    override fun switchCameraFacing(): String {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            isFlashlightOn = false
            applyTorchState(false)
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        android.os.Handler(mainLooper).post { startCamera() }
        return if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
    }

    override fun toggleWideAngle(): Boolean {
        if (currentLensFacing != CameraSelector.LENS_FACING_BACK) {
            currentLensFacing = CameraSelector.LENS_FACING_BACK
        }
        isWideAngle = !isWideAngle

        val minZoom = activeCamera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
        if (minZoom < 1.0f && ultraWideCameraId == null) {
            applyWideAngleIfActive()
        } else {
            android.os.Handler(mainLooper).post { startCamera() }
        }
        return isWideAngle
    }

    fun isConcurrentSupported(): Boolean {
        return try {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            cameraProvider.availableConcurrentCameraInfos.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    override fun toggleSelfieCamera(): Boolean {
        val newState = !isFrontCameraEnabled.get()
        isFrontCameraEnabled.set(newState)
        android.os.Handler(mainLooper).post { startCamera() }
        return newState
    }

    override fun isFrontFacing(): Boolean = currentLensFacing == CameraSelector.LENS_FACING_FRONT
    override fun isWideAngleActive(): Boolean = isWideAngle
    override fun isFlashlightActive(): Boolean = isFlashlightOn

    override fun setExposureCompensation(index: Int): Int {
        val cam = activeCamera
        var finalIndex = index
        if (cam != null) {
            val exposureState = cam.cameraInfo.exposureState
            val range = exposureState.exposureCompensationRange
            finalIndex = index.coerceIn(range.lower, range.upper)
            try {
                cam.cameraControl.setExposureCompensationIndex(finalIndex)
                Log.i(TAG, "Set exposure compensation index: $finalIndex")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set exposure compensation index: $finalIndex", e)
            }
        }
        currentExposureIndex.set(finalIndex)
        getSharedPreferences("CameraLivePrefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("KEY_EXPOSURE_INDEX", finalIndex)
            .apply()
        return finalIndex
    }

    override fun getExposureCompensation(): Int = currentExposureIndex.get()

    override fun getExposureRange(): Pair<Int, Int> {
        val cam = activeCamera
        val range = cam?.cameraInfo?.exposureState?.exposureCompensationRange
        return if (range != null) Pair(range.lower, range.upper) else Pair(-6, 6)
    }

    override fun getExposureStep(): Float {
        val cam = activeCamera
        val step = cam?.cameraInfo?.exposureState?.exposureCompensationStep
        return if (step != null && step.denominator != 0) step.numerator.toFloat() / step.denominator.toFloat() else 0.333f
    }

    override fun setZoomRatio(ratio: Float): Float {
        val cam = activeCamera
        var target = ratio
        if (cam != null) {
            val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
            val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 10.0f
            target = ratio.coerceIn(min, max)
            try {
                cam.cameraControl.setZoomRatio(target)
                Log.i(TAG, "Hardware zoom set to: ${target}x (Range: $min - $max)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set zoom ratio to $target", e)
            }
        }
        currentZoomRatio.set(target)
        return target
    }

    override fun getZoomRatio(): Float = currentZoomRatio.get()

    override fun getZoomRange(): Pair<Float, Float> {
        val cam = activeCamera
        val min = cam?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
        val max = cam?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10.0f
        return Pair(min, max)
    }

    private val isMegafonActive = AtomicBoolean(false)
    private var previousVolume: Int = -1
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var fallbackSpeakerTrack: AudioTrack? = null

    override fun setMegafon(active: Boolean): Boolean {
        isMegafonActive.set(active)
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (active) {
                previousMode = audioManager.mode
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                webRtcManager?.setTalkbackEnabled(true)

                // Route audio to loudspeaker
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                    } else {
                        audioManager.isSpeakerphoneOn = true
                    }
                } else {
                    audioManager.isSpeakerphoneOn = true
                }

                // Set volume to 100% on both voice and media streams
                val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, AudioManager.FLAG_SHOW_UI)
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                Log.i(TAG, "Megafon ACTIVE: volume maxed ($maxVoice / $maxMusic), speakerphone ON")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = previousMode
                if (previousVolume >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
                }
                fallbackSpeakerTrack?.stop()
                fallbackSpeakerTrack?.release()
                fallbackSpeakerTrack = null
                webRtcManager?.setTalkbackEnabled(false)
                Log.i(TAG, "Megafon DEACTIVATED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting megafon state", e)
        }
        return isMegafonActive.get()
    }

    fun setSpeakerVolumePercent(percent: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val clamped = percent.coerceIn(0, 100)
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetMusic = Math.round((clamped / 100f) * maxMusic).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)

            val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val targetVoice = Math.round((clamped / 100f) * maxVoice).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVoice, 0)
            Log.i(TAG, "Speaker volume set to $clamped% (Music: $targetMusic/$maxMusic, Voice: $targetVoice/$maxVoice)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speaker volume", e)
        }
    }

    @Synchronized
    fun playMegafonPcm(pcmBytes: ByteArray) {
        try {
            if (fallbackSpeakerTrack == null) {
                val sampleRate = 44100
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 2
                fallbackSpeakerTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                fallbackSpeakerTrack?.play()
            }
            fallbackSpeakerTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing megafon PCM", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed from Recents. Terminating CameraStreamingService.")
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runningInstance = null
        activeViewers.value = 0
        isRunning.value = false
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        AlarmSoundManager.stopSiren()
        P2pSignalingManager.disconnect(permanent = true)
        if (isMegafonActive.get()) {
            setMegafon(false)
        }
        locationManager?.removeUpdates(this)
        stopMicIfIdle(force = true)
        try { currentRecording?.stop() } catch (_: Exception) {}
        currentRecording = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock / WifiLock", e)
        }

        mjpegServer?.stop()
        mjpegServer = null
        // WebRTC teardown can block on native threads, so it must not run on the main thread.
        val rtc = webRtcManager
        webRtcManager = null
        cameraExecutor.execute {
            try { rtc?.release() } catch (e: Exception) { Log.w(TAG, "WebRTC release failed", e) }
        }
        cameraExecutor.shutdown()
        audioExecutor.shutdown()
        aiExecutor.shutdown()
        snapshotExecutor.shutdown()
        recordingExecutor.shutdown()
        super.onDestroy()
    }
}
