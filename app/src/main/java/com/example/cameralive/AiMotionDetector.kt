package com.example.cameralive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DetectedBox(
    val label: String,
    val score: Float,
    val left: Float,   // Normalized 0.0 .. 1.0
    val top: Float,    // Normalized 0.0 .. 1.0
    val right: Float,  // Normalized 0.0 .. 1.0
    val bottom: Float  // Normalized 0.0 .. 1.0
)

object AiMotionDetector {
    private const val TAG = "AiMotionDetector"
    private const val PREFS_NAME = "CameraLiveAiPrefs"
    private const val MODEL_NAME = "efficientdet_lite0.tflite"

    // Preferences keys
    private const val KEY_AI_ENABLED = "KEY_AI_ENABLED"
    private const val KEY_CONFIDENCE = "KEY_CONFIDENCE"
    private const val KEY_TARGET_CLASS = "KEY_TARGET_CLASS" // "person", "animals", "all"
    private const val KEY_COOLDOWN_SEC = "KEY_COOLDOWN_SEC"
    private const val KEY_ALARM_WEBHOOK = "KEY_ALARM_WEBHOOK"
    private const val KEY_AUTO_CLIP = "KEY_AUTO_CLIP"
    private const val KEY_AUTO_DRIVE = "KEY_AUTO_DRIVE"

    private val executor = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    private var detector: ObjectDetector? = null

    // Reactive Compose / State properties
    var isEnabled by mutableStateOf(true)
    var confidenceThreshold by mutableFloatStateOf(0.50f)
    var targetClass by mutableStateOf("person") // "person", "person_animal", "all"
    var cooldownSeconds by mutableIntStateOf(30)
    var isWebhookAlarmEnabled by mutableStateOf(true)
    var isAutoClipEnabled by mutableStateOf(true)
    var isAutoDriveEnabled by mutableStateOf(true)

    var lastDetectionStatus by mutableStateOf("Initialisiere KI-Modell...")
    var lastAlarmTimestamp by mutableStateOf(0L)
    var lastAlarmLabel by mutableStateOf("Kein Alarm bisher")

    // Real-time bounding boxes for Web UI rendering
    @Volatile var currentBoxes: List<DetectedBox> = emptyList()
    @Volatile var lastDetectionTimeMs: Long = 0L

    // Flag that Web UI or Service can poll to trigger a client/service clip
    @Volatile var pendingClipTriggerTimestamp: Long = 0L

    private var lastAlarmRealtime = 0L

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean(KEY_AI_ENABLED, true)
        confidenceThreshold = prefs.getFloat(KEY_CONFIDENCE, 0.50f)
        targetClass = prefs.getString(KEY_TARGET_CLASS, "person") ?: "person"
        cooldownSeconds = prefs.getInt(KEY_COOLDOWN_SEC, 30)
        isWebhookAlarmEnabled = prefs.getBoolean(KEY_ALARM_WEBHOOK, true)
        isAutoClipEnabled = prefs.getBoolean(KEY_AUTO_CLIP, true)
        isAutoDriveEnabled = prefs.getBoolean(KEY_AUTO_DRIVE, true)

        initDetector(context.applicationContext)
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_AI_ENABLED, isEnabled)
            .putFloat(KEY_CONFIDENCE, confidenceThreshold)
            .putString(KEY_TARGET_CLASS, targetClass)
            .putInt(KEY_COOLDOWN_SEC, cooldownSeconds)
            .putBoolean(KEY_ALARM_WEBHOOK, isWebhookAlarmEnabled)
            .putBoolean(KEY_AUTO_CLIP, isAutoClipEnabled)
            .putBoolean(KEY_AUTO_DRIVE, isAutoDriveEnabled)
            .apply()
    }

    private fun initDetector(context: Context) {
        executor.submit {
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_NAME)
                    .build()

                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(8)
                    .setScoreThreshold(0.30f) // Keep low for tracking, filter in logic
                    .build()

                detector = ObjectDetector.createFromOptions(context, options)
                lastDetectionStatus = "Bereit (EfficientDet-Lite0)"
                Log.i(TAG, "MediaPipe ObjectDetector initialized successfully with $MODEL_NAME")
            } catch (e: Exception) {
                lastDetectionStatus = "Fehler: ${e.localizedMessage}"
                Log.e(TAG, "Failed to initialize MediaPipe ObjectDetector", e)
            }
        }
    }

    fun analyzeBitmap(
        bitmap: Bitmap,
        service: CameraStreamingService?
    ) {
        if (!isEnabled || detector == null) {
            currentBoxes = emptyList()
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            // Drop frame if previous analysis is still ongoing
            return
        }

        executor.submit {
            try {
                val det = detector
                if (det == null) return@submit

                val mpImage = BitmapImageBuilder(bitmap).build()
                val results = det.detect(mpImage)

                val bWidth = bitmap.width.toFloat()
                val bHeight = bitmap.height.toFloat()

                val detectedList = mutableListOf<DetectedBox>()
                var highestTargetDetection: Pair<String, Float>? = null

                results.detections().forEach { detection ->
                    val cat = detection.categories().firstOrNull() ?: return@forEach
                    val label = cat.categoryName().lowercase()
                    val score = cat.score()

                    val rect = detection.boundingBox()
                    val normLeft = (rect.left / bWidth).coerceIn(0f, 1f)
                    val normTop = (rect.top / bHeight).coerceIn(0f, 1f)
                    val normRight = (rect.right / bWidth).coerceIn(0f, 1f)
                    val normBottom = (rect.bottom / bHeight).coerceIn(0f, 1f)

                    detectedList.add(
                        DetectedBox(
                            label = cat.categoryName(),
                            score = score,
                            left = normLeft,
                            top = normTop,
                            right = normRight,
                            bottom = normBottom
                        )
                    )

                    // Check if this detection matches our target alarm filter
                    if (score >= confidenceThreshold && isTargetClass(label)) {
                        val currentMax = highestTargetDetection?.second ?: 0f
                        if (score > currentMax) {
                            highestTargetDetection = Pair(cat.categoryName(), score)
                        }
                    }
                }

                currentBoxes = detectedList
                lastDetectionTimeMs = System.currentTimeMillis()

                if (detectedList.isNotEmpty()) {
                    val top = detectedList.maxByOrNull { it.score }
                    if (top != null) {
                        lastDetectionStatus = "${top.label} (${(top.score * 100).toInt()}%) erkannt"
                    }
                } else {
                    lastDetectionStatus = "Kein Objekt erkannt"
                }

                // If alarm target matched -> check cooldown and trigger actions
                if (highestTargetDetection != null) {
                    val (label, score) = highestTargetDetection!!
                    val nowRealtime = SystemClock.elapsedRealtime()
                    val cooldownMs = cooldownSeconds * 1000L

                    if (nowRealtime - lastAlarmRealtime >= cooldownMs) {
                        lastAlarmRealtime = nowRealtime
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
                        val pct = (score * 100).toInt()
                        lastAlarmTimestamp = System.currentTimeMillis()
                        lastAlarmLabel = "$label ($pct%) um $timeStr"
                        pendingClipTriggerTimestamp = System.currentTimeMillis()

                        Log.i(TAG, "🚨 Alarm Triggered: $label ($pct%) at $timeStr")

                        // 1. Send Webhook Alarm
                        if (isWebhookAlarmEnabled) {
                            val msg = "$label erkannt mit $pct% Zuverlässigkeit!"
                            WebhookManager.sendAlarm(msg) { success, res ->
                                Log.i(TAG, "AI Alarm Webhook sent: success=$success ($res)")
                            }
                        }

                        // 2. Trigger Auto-Snapshot & Google Drive Upload
                        if (isAutoDriveEnabled && service != null) {
                            service.triggerAlarmSnapshotUpload()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during AI frame analysis", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun isTargetClass(label: String): Boolean {
        return when (targetClass) {
            "person" -> label == "person"
            "person_animal" -> label == "person" || label == "cat" || label == "dog" || label == "bird" || label == "horse"
            "all" -> true
            else -> label == targetClass
        }
    }

    fun getBoxesJson(): String {
        // Return JSON array of active bounding boxes if detection is fresh (< 1200ms old)
        val now = System.currentTimeMillis()
        if (now - lastDetectionTimeMs > 1200L) {
            return "[]"
        }
        val array = JSONArray()
        currentBoxes.forEach { box ->
            val obj = JSONObject()
            obj.put("label", box.label)
            obj.put("score", (box.score * 100).toInt())
            obj.put("left", box.left)
            obj.put("top", box.top)
            obj.put("right", box.right)
            obj.put("bottom", box.bottom)
            array.put(obj)
        }
        return array.toString()
    }

    fun getAiStateJson(): String {
        val obj = JSONObject()
        obj.put("enabled", isEnabled)
        obj.put("model", MODEL_NAME)
        obj.put("confidence", (confidenceThreshold * 100).toInt())
        obj.put("targetClass", targetClass)
        obj.put("cooldown", cooldownSeconds)
        obj.put("webhookAlarm", isWebhookAlarmEnabled)
        obj.put("autoClip", isAutoClipEnabled)
        obj.put("autoDrive", isAutoDriveEnabled)
        obj.put("status", lastDetectionStatus)
        obj.put("lastAlarm", lastAlarmLabel)
        obj.put("lastAlarmTime", lastAlarmTimestamp)
        obj.put("pendingClipTrigger", pendingClipTriggerTimestamp)
        return obj.toString()
    }
}
