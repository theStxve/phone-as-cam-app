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

    fun sendAlarm(message: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!alarmWebhookEnabled || alarmWebhookUrl.isBlank()) {
            onResult(false, "Alarm-Webhook ist deaktiviert")
            return
        }

        val jsonPayload = if (alarmWebhookUrl.contains("discord.com")) {
            org.json.JSONObject().apply {
                put("content", "🚨 **Camera Live Alarm:** $message")
            }.toString()
        } else {
            org.json.JSONObject().apply {
                put("event", "camera_alarm")
                put("message", message)
                put("timestamp", System.currentTimeMillis())
                put("battery", currentBatteryLevel)
            }.toString()
        }

        sendWebhook(alarmWebhookUrl, jsonPayload, onResult)
    }
}