package com.example.cameralive

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object GoogleDriveBackupManager {
    private const val TAG = "GoogleDriveBackup"
    private const val PREFS_NAME = "CameraLiveDrivePrefs"
    private const val KEY_DRIVE_BACKUP_ENABLED = "KEY_DRIVE_BACKUP_ENABLED"
    private const val KEY_CONNECTED_ACCOUNT = "KEY_CONNECTED_ACCOUNT"

    val DRIVE_SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

    @Volatile var isAutoBackupEnabled = false
    @Volatile var connectedAccountEmail: String? = null
    @Volatile var lastBackupStatus = "Kein Backup bisher"

    private val executor = Executors.newSingleThreadExecutor()

    fun getGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_SCOPE)
            .build()
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isAutoBackupEnabled = prefs.getBoolean(KEY_DRIVE_BACKUP_ENABLED, false)
        val savedEmail = prefs.getString(KEY_CONNECTED_ACCOUNT, null)

        val currentAccount = GoogleSignIn.getLastSignedInAccount(context)
        if (currentAccount != null && GoogleSignIn.hasPermissions(currentAccount, DRIVE_SCOPE)) {
            connectedAccountEmail = currentAccount.email
        } else {
            connectedAccountEmail = savedEmail
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_DRIVE_BACKUP_ENABLED, isAutoBackupEnabled)
            .putString(KEY_CONNECTED_ACCOUNT, connectedAccountEmail)
            .apply()
    }

    fun setAccountConnected(email: String?, enabled: Boolean, context: Context) {
        connectedAccountEmail = email
        isAutoBackupEnabled = enabled
        save(context)
    }

    fun uploadPhotoAsync(
        context: Context,
        photoBytes: ByteArray,
        filename: String = "capture_${System.currentTimeMillis()}.jpg",
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (!isAutoBackupEnabled) {
            onResult(false, "Google Drive Backup ist deaktiviert")
            return
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || account.account == null) {
            lastBackupStatus = "Fehler: Kein Google Account verbunden"
            onResult(false, "Kein Google Account verbunden")
            return
        }

        executor.submit {
            try {
                val scopeString = "oauth2:https://www.googleapis.com/auth/drive.file"
                val token = GoogleAuthUtil.getToken(context, account.account!!, scopeString)

                if (token.isNullOrBlank()) {
                    lastBackupStatus = "Fehler: Authentifizierung fehlgeschlagen"
                    onResult(false, "Token konnte nicht abgerufen werden")
                    return@submit
                }

                val boundary = "=====PhoneAsCamBoundary" + System.currentTimeMillis() + "====="
                val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")

                val metaJson = """{"name":"$filename","mimeType":"image/jpeg"}"""

                val os: OutputStream = conn.outputStream
                val writer = os.writer(Charsets.UTF_8)

                // Part 1: Metadata
                writer.write("--$boundary\r\n")
                writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                writer.write(metaJson)
                writer.write("\r\n")
                writer.flush()

                // Part 2: Media
                writer.write("--$boundary\r\n")
                writer.write("Content-Type: image/jpeg\r\n\r\n")
                writer.flush()
                os.write(photoBytes)
                os.flush()

                // End
                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
                os.close()

                val code = conn.responseCode
                if (code in 200..299) {
                    val sizeKb = photoBytes.size / 1024
                    lastBackupStatus = "Erfolgreich: $filename ($sizeKb KB)"
                    Log.i(TAG, "Uploaded $filename to Google Drive successfully ($sizeKb KB)")
                    onResult(true, "Gesichert auf Google Drive")
                } else {
                    val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    lastBackupStatus = "Upload-Fehler: HTTP $code"
                    Log.e(TAG, "Google Drive Upload failed (HTTP $code): $errText")
                    onResult(false, "HTTP $code: $errText")
                }
                conn.disconnect()
            } catch (e: Exception) {
                lastBackupStatus = "Fehler: ${e.localizedMessage}"
                Log.e(TAG, "Error uploading to Google Drive", e)
                onResult(false, e.localizedMessage ?: "Upload-Fehler")
            }
        }
    }
}