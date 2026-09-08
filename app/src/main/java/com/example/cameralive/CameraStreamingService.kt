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
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CameraStreamingService : LifecycleService(), CameraController, LocationListener {

    companion object {
        private const val TAG = "CameraStreaming"
    }

    private val CHANNEL_ID = "CameraStreamingChannel"
    private var mjpegServer: MjpegServer? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioExecutor: ExecutorService
    
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
    
    @Volatile private var lastBackFrameTime = 0L
    @Volatile private var lastFrontFrameTime = 0L
    @Volatile private var lastSnapshotTime = 0L

    var webRtcManager: WebRtcManager? = null
    @Volatile private var cameraRetryPending = false
    @Volatile private var backNv21Buffer: ByteArray? = null
    @Volatile private var frontNv21Buffer: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cameraExecutor = Executors.newFixedThreadPool(2)
        audioExecutor = Executors.newSingleThreadExecutor()

        // Call startForeground() IMMEDIATELY in onCreate to avoid the 5-second FGS deadline.
        // Android 14 requires this before any slow operations like WebRTC init.
        val placeholderNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Streaming Active")
            .setContentText("Starting…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                1, placeholderNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, placeholderNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1, placeholderNotification)
        }

        // Acquire Partial WakeLock & WifiLock to keep CPU & streaming alive when the screen is turned off
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraLive::StreamingWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CameraLive::StreamingWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock & WifiLock acquired successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock / WifiLock", e)
        }

        // Init WebRTC on a background thread so we don't block the main thread
        cameraExecutor.submit {
            val mgr = WebRtcManager(this)
            mgr.init(maxFps.get())
            webRtcManager = mgr
        }
    }

    fun setStreamingFps(fps: Int) {
        maxFps.set(fps)
        webRtcManager?.updateFps(fps)
    }

    fun setStreamingQuality(quality: Int) {
        val clamped = quality.coerceIn(10, 95)
        jpegQuality.set(clamped)
        webRtcManager?.updateQuality(clamped)
    }

    fun setStreamingResolution(res: String) {
        val validRes = when (res.lowercase()) {
            "720p", "hd" -> "720p"
            "1080p", "fhd" -> "1080p"
            else -> "480p"
        }
        val prev = targetResolution.getAndSet(validRes)
        val (w, h) = when (validRes) {
            "1080p" -> Pair(1920, 1080)
            "720p" -> Pair(1280, 720)
            else -> Pair(640, 480)
        }
        webRtcManager?.updateResolution(w, h)
        if (prev != validRes) {
            android.os.Handler(mainLooper).post { startCamera() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val port = intent?.getIntExtra("PORT", 8080) ?: 8080

        // Update notification with real port
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Streaming Active")
            .setContentText("Streaming on port $port")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(1, notification)

        startServer(port)
        startLocationTracking()
        // Delay camera start slightly to ensure WebRTC init is complete
        cameraExecutor.submit {
            Thread.sleep(300)
            android.os.Handler(mainLooper).post { startCamera() }
        }

        return Service.START_NOT_STICKY
    }

    private fun startServer(port: Int) {
        try {
            mjpegServer?.stop()
            mjpegServer = MjpegServer(port, this)
            mjpegServer?.service = this
            mjpegServer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
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
        
        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this)
        locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this)
    }

    override fun onLocationChanged(location: Location) {
        mjpegServer?.currentLat = location.latitude
        mjpegServer?.currentLng = location.longitude
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

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

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            if (ultraWideCameraId == null) {
                ultraWideCameraId = findUltraWideCameraId()
            }

            val currentRes = targetResolution.get() ?: "480p"
            val (targetSize, targetAspect) = when (currentRes) {
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

            val targetFps = maxFps.get().coerceIn(5, 60)
            androidx.camera.camera2.interop.Camera2Interop.Extender(backBuilder).apply {
                setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(targetFps.coerceAtLeast(15), targetFps)
                )
                // Optimize camera sensor pipeline for minimal frame-latency
                setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_ENABLE_ZSL,
                        true
                    )
                }
            }

            val primaryAnalyzer = backBuilder.build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    val now = System.currentTimeMillis()
                    // When WebRTC peer is active, pass every frame immediately (WebRTC handles hardware rate limiting)
                    // If no WebRTC peer, throttle to maxFps for JPEG snapshots
                    if (webRtcManager?.hasActivePeer() != true) {
                        val minInterval = 1000L / maxFps.get()
                        if (now - lastBackFrameTime < minInterval - 4) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                    }
                    lastBackFrameTime = now
                    processImage(imageProxy, isFront = false)
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
                        if (!isFrontCameraEnabled.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val now = System.currentTimeMillis()
                        val minInterval = 1000L / maxFps.get()
                        if (now - lastFrontFrameTime >= minInterval) {
                            lastFrontFrameTime = now
                            processImage(imageProxy, isFront = true)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            // 1. Try concurrent camera API ONLY if front camera is requested AND phone supports it
            val concurrentInfos = cameraProvider.availableConcurrentCameraInfos
            if (isFrontCameraEnabled.get() && concurrentInfos.isNotEmpty() && currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                Log.i(TAG, "Concurrent camera mode active for selfie PiP")
                try {
                    val configs = listOf(
                        ConcurrentCamera.SingleCameraConfig(
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            UseCaseGroup.Builder().addUseCase(primaryAnalyzer).build(),
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
                    applyTorchState(isFlashlightOn)
                    applyWideAngleIfActive()
                    observeCameraErrors(activeCamera)
                    Log.i(TAG, "Concurrent cameras bound successfully")
                    return@addListener
                } catch (e: Exception) {
                    Log.w(TAG, "Concurrent camera binding failed, falling back to single camera", e)
                    cameraProvider.unbindAll()
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
                activeCamera = cameraProvider.bindToLifecycle(this, selector, primaryAnalyzer)
                if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                    applyTorchState(isFlashlightOn)
                    applyWideAngleIfActive()
                }
                observeCameraErrors(activeCamera)
                Log.i(TAG, "Bound primary camera successfully: facing=$currentLensFacing, wideAngle=$isWideAngle")
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to bind camera with selector: $selector", exc)
                try {
                    currentLensFacing = CameraSelector.LENS_FACING_BACK
                    activeCamera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, primaryAnalyzer)
                } catch (e2: Exception) {
                    Log.e(TAG, "Fatal: failed to bind default back camera", e2)
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyWideAngleIfActive() {
        val cam = activeCamera ?: return
        if (currentLensFacing != CameraSelector.LENS_FACING_BACK) return
        val zoomRatio = if (isWideAngle) {
            val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
            if (minZoom < 1.0f) minZoom else 1.0f
        } else {
            1.0f
        }
        try {
            cam.cameraControl.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting zoom ratio: $zoomRatio", e)
        }
    }

    private fun observeCameraErrors(camera: Camera?) {
        camera?.cameraInfo?.cameraState?.observe(this) { state ->
            if (state.error?.code == androidx.camera.core.CameraState.ERROR_CAMERA_DISABLED
                && !cameraRetryPending) {
                cameraRetryPending = true
                Log.w(TAG, "Camera disabled error – will retry in 2s")
                android.os.Handler(mainLooper).postDelayed({
                    cameraRetryPending = false
                    startCamera()
                }, 2000)
            }
        }
    }
    
    private fun processImage(imageProxy: androidx.camera.core.ImageProxy, isFront: Boolean) {
        try {
            // ZERO-CLIENT IDLE CHECK: If no viewer is watching, drop frame immediately to save CPU and battery
            if (isFront) {
                if (!isFrontCameraEnabled.get() || mjpegServer?.hasActiveFrontClients() != true) {
                    return
                }
            } else {
                if (mjpegServer?.hasActiveClients() != true) {
                    return
                }
            }

            val width = imageProxy.width
            val height = imageProxy.height
            
            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]
            
            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            
            val bufferSize = width * height * 3 / 2
            val nv21 = if (isFront) {
                var buf = frontNv21Buffer
                if (buf == null || buf.size != bufferSize) {
                    buf = ByteArray(bufferSize)
                    frontNv21Buffer = buf
                }
                buf
            } else {
                var buf = backNv21Buffer
                if (buf == null || buf.size != bufferSize) {
                    buf = ByteArray(bufferSize)
                    backNv21Buffer = buf
                }
                buf
            }
            
            // Copy Y plane row by row (handles stride != width)
            val yBuffer = yPlane.buffer
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
            
            // Copy VU interleaved for NV21
            val vBuffer = vPlane.buffer
            val uBuffer = uPlane.buffer
            val uvHeight = height / 2
            val uvWidth = width / 2
            
            if (uvPixelStride == 2) {
                val uvRowBytes = width
                for (row in 0 until uvHeight) {
                    val rowLen = if (row == uvHeight - 1) uvRowBytes - 1 else uvRowBytes
                    vBuffer.position(row * uvRowStride)
                    vBuffer.get(nv21, pos, rowLen)
                    if (row == uvHeight - 1) {
                        val lastUIndex = (uvHeight - 1) * uvRowStride + (uvWidth - 1) * uvPixelStride
                        nv21[pos + uvRowBytes - 1] = uBuffer.get(lastUIndex)
                    }
                    pos += uvRowBytes
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
            
            if (isFront) {
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality.get(), out)
                mjpegServer?.broadcastFrameFront(out.toByteArray())
            } else {
                // 1. PUSH DIRECTLY TO WEBRTC FIRST
                if (webRtcManager?.hasActivePeer() == true) {
                    val baseRotation = imageProxy.imageInfo.rotationDegrees
                    val rotatedDegrees = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                        (baseRotation + 90) % 360
                    } else {
                        (baseRotation + 270) % 360
                    }
                    webRtcManager?.pushFrame(nv21, width, height, rotatedDegrees)
                }

                // 2. Non-blocking async JPEG snapshot ONLY when snapshot clients are active
                if (mjpegServer?.hasActiveBackSnapshotClients() == true) {
                    val now = System.currentTimeMillis()
                    if (now - lastSnapshotTime >= 500) {
                        lastSnapshotTime = now
                        val snapshotCopy = nv21.clone()
                        val q = jpegQuality.get()
                        audioExecutor.submit {
                            try {
                                val yuvImage = YuvImage(snapshotCopy, ImageFormat.NV21, width, height, null)
                                val out = ByteArrayOutputStream()
                                yuvImage.compressToJpeg(Rect(0, 0, width, height), q, out)
                                mjpegServer?.broadcastFrame(out.toByteArray())
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            imageProxy.close()
        }
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
                while (isRecordingAudio.get()) {
                    val rec = audioRecord ?: break
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val chunk = ByteArray(read)
                        System.arraycopy(buffer, 0, chunk, 0, read)
                        mjpegServer?.broadcastAudio(chunk)
                    } else if (read < 0) {
                        break
                    }
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
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        Log.d(TAG, "Stopped PCM fallback AudioRecord")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Camera Streaming Channel", NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
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
                Log.i(TAG, "Megafon DEACTIVATED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting megafon state", e)
        }
        return isMegafonActive.get()
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
        super.onDestroy()
        if (isMegafonActive.get()) {
            setMegafon(false)
        }
        locationManager?.removeUpdates(this)
        
        stopMicIfIdle(force = true)
        
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock / WifiLock", e)
        }
        
        webRtcManager?.release()
        mjpegServer?.stop()
        cameraExecutor.shutdown()
        audioExecutor.shutdown()
    }
}
