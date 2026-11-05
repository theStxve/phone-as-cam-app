package com.example.cameralive

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the web interface from assets/web. Pages are templates with {{name}} placeholders;
 * JavaScript, styles and fonts are static files the browser can cache.
 */
object WebPages {
    class StaticAsset(val bytes: ByteArray, val mime: String, val etag: String)

    private lateinit var appContext: Context
    private val textCache = ConcurrentHashMap<String, String>()
    private val staticCache = ConcurrentHashMap<String, StaticAsset>()

    private val staticFiles = mapOf(
        "app.js" to "application/javascript; charset=utf-8",
        "app.css" to "text/css; charset=utf-8",
        "qrcode.js" to "application/javascript; charset=utf-8",
        "plex-sans-400.woff2" to "font/woff2",
        "plex-sans-500.woff2" to "font/woff2",
        "plex-mono-400.woff2" to "font/woff2",
        "plex-mono-500.woff2" to "font/woff2"
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun readText(name: String): String = textCache.getOrPut(name) {
        appContext.assets.open("web/$name").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Only names on the list above are reachable, so a request can't walk through the assets. */
    fun staticAsset(name: String): StaticAsset? {
        val mime = staticFiles[name] ?: return null
        return staticCache.getOrPut(name) {
            val bytes = appContext.assets.open("web/$name").use { it.readBytes() }
            StaticAsset(bytes, mime, "\"${bytes.size}-${bytes.contentHashCode()}\"")
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun render(template: String, values: Map<String, String>): String {
        var out = readText(template)
        for ((key, value) in values) out = out.replace("{{$key}}", value)
        return out
    }

    fun login(): String = render("login.html", mapOf("room" to escapeHtml(P2pSignalingManager.roomId)))

    fun viewer(): String = render(
        "viewer.html",
        mapOf(
            "room" to escapeHtml(P2pSignalingManager.liveRoomId.value),
            "scope" to "WLAN"
        )
    )

    /** The main page. Only ever rendered for an authenticated client, because it contains the PIN. */
    fun index(controller: CameraController, service: CameraStreamingService?): String {
        val quality = service?.jpegQuality?.get() ?: 20
        val fps = service?.maxFps?.get() ?: 20
        val res = service?.targetResolution?.get() ?: "480p"
        val photoQuality = service?.photoJpegQuality?.get() ?: 95
        val gps = service?.gpsIntervalSec ?: 300
        val selfie = service?.isFrontCameraEnabled?.get() ?: false
        val expRange = controller.getExposureRange()
        val exposure = controller.getExposureCompensation()
        val expStep = controller.getExposureStep()
        val zoomRange = controller.getZoomRange()
        val zoom = controller.getZoomRatio()

        val boot = JSONObject()
            .put("pin", P2pSignalingManager.roomPin)
            .put("room", P2pSignalingManager.roomId)
            .put("selfie", selfie)
            .put("expStep", expStep.toDouble())
            .put("zoom", zoom.toDouble())
            .put("zoomMin", zoomRange.first.toDouble())
            .put("zoomMax", zoomRange.second.toDouble())
            .put("quality", quality)
            .put("fps", fps)
            .put("res", res)
            .put("photoQuality", photoQuality)
            .put("gps", gps)
            .put("fastPhoto", service?.fastPhotoMode?.get() ?: false)
            .put("sensor", service?.detectedCameraLabel ?: "")
            .put("flash", controller.isFlashlightActive())
            .put("wide", controller.isWideAngleActive())
            .put("front", controller.isFrontFacing())
            .put("exposure", exposure)
            .put("expMin", expRange.first)
            .put("expMax", expRange.second)
            .put("nightBoost", service?.autoNightBoostEnabled?.get() ?: false)
            .put("nightTorch", service?.autoTorchNightEnabled?.get() ?: false)
            .put("webhookUrl", WebhookManager.alarmWebhookUrl)
            .toString()
            // Keeps a value like </script> from ending the script block.
            .replace("<", "\\u003c")

        return render(
            "index.html",
            mapOf(
                "boot" to boot,
                "room" to escapeHtml(P2pSignalingManager.roomId),
                "pin" to escapeHtml(P2pSignalingManager.roomPin),
                "zoomLabel" to String.format(Locale.US, "%.1fx", zoom)
            )
        )
    }
}
