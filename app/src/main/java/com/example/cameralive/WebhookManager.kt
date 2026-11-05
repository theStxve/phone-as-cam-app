package com.example.cameralive

import android.content.Context
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object WebhookManager {
    private const val TAG = "WebhookManager"
    private const val PREFS_NAME = "CameraLiveWebhookPrefs"

    const val KEY_BATTERY_PROTECTION_ENABLED = "KEY_BATTERY_PROTECTION_ENABLED"
    const val KEY_PLUG_OFF_URL = "KEY_PLUG_OFF_URL"
    const val KEY_PLUG_ON_URL = "KEY_PLUG_ON_URL"
    const val KEY_ALARM_WEBHOOK_ENABLED = "KEY_ALARM_WEBHOOK_ENABLED"
    const val KEY_ALARM_WEBHOOK_URL = "KEY_ALARM_WEBHOOK_URL"
    const val KEY_BATTERY_MAX = "KEY_BATTERY_MAX"
    const val KEY_BATTERY_MIN = "KEY_BATTERY_MIN"

    private val executor = Executors.newCachedThreadPool()

    @Volatile var batteryProtectionEnabled = false
    @Volatile var plugOffUrl = ""
    @Volatile var plugOnUrl = ""
    @Volatile var alarmWebhookEnabled = false
    @Volatile var alarmWebhookUrl = ""
    @Volatile var batteryMax = 80
    @Volatile var batteryMin = 20

    @Volatile var currentBatteryLevel = -1
    @Volatile var isCurrentlyCharging = false
    @Volatile var lastBatteryEventLog = "Noch kein Ereignis"

    private var lastPlugAction: String? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        batteryProtectionEnabled = prefs.getBoolean(KEY_BATTERY_PROTECTION_ENABLED, false)
        plugOffUrl = prefs.getString(KEY_PLUG_OFF_URL, "") ?: ""
        plugOnUrl = prefs.getString(KEY_PLUG_ON_URL, "") ?: ""
        alarmWebhookEnabled = prefs.getBoolean(KEY_ALARM_WEBHOOK_ENABLED, false)
        alarmWebhookUrl = prefs.getString(KEY_ALARM_WEBHOOK_URL, "") ?: ""
        batteryMax = prefs.getInt(KEY_BATTERY_MAX, 80)
        batteryMin = prefs.getInt(KEY_BATTERY_MIN, 20)
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_BATTERY_PROTECTION_ENABLED, batteryProtectionEnabled)
            .putString(KEY_PLUG_OFF_URL, plugOffUrl)
            .putString(KEY_PLUG_ON_URL, plugOnUrl)
            .putBoolean(KEY_ALARM_WEBHOOK_ENABLED, alarmWebhookEnabled)
            .putString(KEY_ALARM_WEBHOOK_URL, alarmWebhookUrl)
            .putInt(KEY_BATTERY_MAX, batteryMax)
            .putInt(KEY_BATTERY_MIN, batteryMin)
            .apply()
    }

    fun onBatteryChanged(level: Int, isCharging: Boolean) {
        currentBatteryLevel = level
        isCurrentlyCharging = isCharging

        if (!batteryProtectionEnabled) return

        // If charged up to or above max threshold (e.g. >= 80%) and still charging -> send Turn Off webhook
        if (level >= batteryMax && isCharging && lastPlugAction != "off") {
            if (plugOffUrl.isNotBlank()) {
                Log.i(TAG, "Battery reached ${level}% (>= ${batteryMax}%). Triggering Smart-Plug AUS webhook.")
                lastBatteryEventLog = "AUS-Webhook gesendet bei ${level}%"
                lastPlugAction = "off"
                sendWebhook(plugOffUrl, null) { success, msg ->
                    Log.i(TAG, "Smart-Plug AUS Webhook result: success=$success ($msg)")
                }
            }
        }
        // If discharged down to or below min threshold (e.g. <= 20%) and not charging -> send Turn On webhook
        else if (level <= batteryMin && !isCharging && lastPlugAction != "on") {
            if (plugOnUrl.isNotBlank()) {
                Log.i(TAG, "Battery dropped to ${level}% (<= ${batteryMin}%). Triggering Smart-Plug AN webhook.")
                lastBatteryEventLog = "AN-Webhook gesendet bei ${level}%"
                lastPlugAction = "on"
                sendWebhook(plugOnUrl, null) { success, msg ->
                    Log.i(TAG, "Smart-Plug AN Webhook result: success=$success ($msg)")
                }
            }
        }
        // Reset debounce when in intermediate range
        else if (level in (batteryMin + 5)..(batteryMax - 5)) {
            lastPlugAction = null
        }
    }

    fun sendWebhook(
        urlString: String,
        jsonBody: String? = null,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (urlString.isBlank()) {
            onResult(false, "URL ist leer")
            return
        }

        executor.submit {
            try {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.instanceFollowRedirects = true

                if (jsonBody != null) {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                        writer.write(jsonBody)
                        writer.flush()
                    }
                } else {
                    conn.requestMethod = "GET"
                }

                val code = conn.responseCode
                val isSuccess = code in 200..299
                val msg = "HTTP $code"
                Log.i(TAG, "Webhook call to $urlString finished: $msg")
                onResult(isSuccess, msg)
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Webhook call to $urlString failed", e)
                onResult(false, e.localizedMessage ?: "Verbindungsfehler")
            }
        }
    }

    fun sendAlarm(message: String, imageBytes: ByteArray? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!alarmWebhookEnabled || alarmWebhookUrl.isBlank()) {
            onResult(false, "Alarm-Webhook ist deaktiviert")
            return
        }

        val urlLower = alarmWebhookUrl.lowercase()
        
        // If we have an image and it's a Discord webhook, we must use multipart/form-data
        if (imageBytes != null && urlLower.contains("discord.com")) {
            executor.submit {
                try {
                    val url = URL(alarmWebhookUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.requestMethod = "POST"
                    conn.doOutput = true

                    val boundary = "=====CameraLiveBoundary${System.currentTimeMillis()}====="
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                    val jsonPayload = org.json.JSONObject().apply {
                        put("content", "🚨 **Camera Live Alarm:** $message")
                    }.toString()

                    val os = conn.outputStream
                    val writer = os.writer(Charsets.UTF_8)

                    // Part 1: payload_json
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"payload_json\"\r\n")
                    writer.write("Content-Type: application/json\r\n\r\n")
                    writer.write(jsonPayload)
                    writer.write("\r\n")

                    // Part 2: file
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"alarm.jpg\"\r\n")
                    writer.write("Content-Type: image/jpeg\r\n\r\n")
                    writer.flush()

                    os.write(imageBytes)
                    os.flush()

                    writer.write("\r\n--$boundary--\r\n")
                    writer.flush()
                    
                    val code = conn.responseCode
                    val msg = "HTTP $code"
                    Log.i(TAG, "Discord Webhook with image finished: $msg")
                    onResult(code in 200..299, msg)
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Discord Webhook with image failed", e)
                    onResult(false, e.localizedMessage ?: "Upload Fehler")
                }
            }
            return
        }
        
        // If Telegram and we have an image, try to use sendPhoto API
        if (imageBytes != null && urlLower.contains("api.telegram.org")) {
            val photoUrl = alarmWebhookUrl.replace("sendMessage", "sendPhoto", ignoreCase = true)
            executor.submit {
                try {
                    val url = URL(photoUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.requestMethod = "POST"
                    conn.doOutput = true

                    val boundary = "=====CameraLiveBoundary${System.currentTimeMillis()}====="
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                    val os = conn.outputStream
                    val writer = os.writer(Charsets.UTF_8)

                    // Part 1: caption
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                    writer.write("🚨 *Camera Live Alarm:*\n$message")
                    writer.write("\r\n")

                    // Part 2: parse_mode
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"parse_mode\"\r\n\r\n")
                    writer.write("Markdown")
                    writer.write("\r\n")

                    // Part 3: chat_id (extract from URL query params if exists, Telegram usually needs it)
                    // (Assuming chat_id is either in URL or not needed depending on how user set it up)
                    val query = url.query
                    if (query != null && query.contains("chat_id=")) {
                        val chatId = query.split("&").firstOrNull { it.startsWith("chat_id=") }?.substringAfter("=")
                        if (chatId != null) {
                            writer.write("--$boundary\r\n")
                            writer.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                            writer.write(chatId)
                            writer.write("\r\n")
                        }
                    }

                    // Part 4: photo
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"photo\"; filename=\"alarm.jpg\"\r\n")
                    writer.write("Content-Type: image/jpeg\r\n\r\n")
                    writer.flush()

                    os.write(imageBytes)
                    os.flush()

                    writer.write("\r\n--$boundary--\r\n")
                    writer.flush()
                    
                    val code = conn.responseCode
                    Log.i(TAG, "Telegram Webhook with image finished: HTTP $code")
                    onResult(code in 200..299, "HTTP $code")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Telegram Webhook with image failed", e)
                    onResult(false, e.localizedMessage ?: "Upload Fehler")
                }
            }
            return
        }

        // Fallback: Standard JSON POST without image
        val jsonPayload = when {
            urlLower.contains("discord.com") -> {
                org.json.JSONObject().apply {
                    put("content", "🚨 **Camera Live Alarm:** $message")
                }.toString()
            }
            urlLower.contains("api.telegram.org") -> {
                org.json.JSONObject().apply {
                    put("text", "🚨 *Camera Live Alarm:*\n$message")
                    put("parse_mode", "Markdown")
                }.toString()
            }
            else -> {
                org.json.JSONObject().apply {
                    put("event", "camera_alarm")
                    put("message", message)
                    put("timestamp", System.currentTimeMillis())
                    put("battery", currentBatteryLevel)
                }.toString()
            }
        }

        sendWebhook(alarmWebhookUrl, jsonPayload, onResult)
    }

    fun getWebhookStateJson(): String {
        return org.json.JSONObject().apply {
            put("batteryProtectionEnabled", batteryProtectionEnabled)
            put("plugOffUrl", plugOffUrl)
            put("plugOnUrl", plugOnUrl)
            put("alarmWebhookEnabled", alarmWebhookEnabled)
            put("alarmWebhookUrl", alarmWebhookUrl)
            put("batteryMax", batteryMax)
            put("batteryMin", batteryMin)
            put("currentBatteryLevel", currentBatteryLevel)
            put("isCurrentlyCharging", isCurrentlyCharging)
            put("lastBatteryEventLog", lastBatteryEventLog)
        }.toString()
    }
}