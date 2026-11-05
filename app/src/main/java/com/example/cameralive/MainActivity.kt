package com.example.cameralive

import com.example.cameralive.theme.CameraLiveTheme
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.net.NetworkInterface
import android.widget.Toast
import android.util.Log

import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import com.example.cameralive.theme.Amber
import com.example.cameralive.theme.Bad
import com.example.cameralive.theme.Good
import com.example.cameralive.theme.PlexMono
import com.example.cameralive.theme.Surface3
import com.example.cameralive.theme.TextMuted
import com.example.cameralive.theme.TextPrimary
import com.example.cameralive.theme.TextSecondary
import androidx.compose.ui.text.style.TextAlign

class MainActivity : ComponentActivity() {

    private var savedPort = "8080"

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                if (account != null) {
                    GoogleDriveBackupManager.setAccountConnected(account.email, true, this)
                    Toast.makeText(this, "Google Drive verknüpft: ${account.email}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: com.google.android.gms.common.api.ApiException) {
                Log.e("MainActivity", "Google Sign-In failed: code=${e.statusCode}", e)
                val msg = when (e.statusCode) {
                    10 -> "Fehler 10 (DEVELOPER_ERROR): Die App ist in Google Cloud noch nicht mit dem SHA-1 Schlüssel verknüpft."
                    12500 -> "Fehler 12500: Sign-In fehlgeschlagen (Google Play Services)."
                    7 -> "Fehler 7: Keine Internetverbindung."
                    16 -> "Anmeldung abgebrochen."
                    else -> "Fehler ${e.statusCode}: ${e.localizedMessage ?: "Unbekannt"}"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Google Sign-In failed", e)
                Toast.makeText(this, "Google Anmeldung fehlgeschlagen: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

    fun startGoogleSignIn() {
        val gso = GoogleDriveBackupManager.getGoogleSignInOptions()
        val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(client.signInIntent)
    }

    fun disconnectGoogleAccount() {
        val gso = GoogleDriveBackupManager.getGoogleSignInOptions()
        val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso)
        client.signOut().addOnCompleteListener {
            GoogleDriveBackupManager.setAccountConnected(null, false, this)
            Toast.makeText(this, "Google Account getrennt", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
            if (cameraGranted && audioGranted) {
                // Start after permissions granted (app is in foreground at this point)
                startStreamService(savedPort.toIntOrNull() ?: 8080)
            } else {
                Toast.makeText(this, "Kamera und Mikrofon werden für den Stream gebraucht", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure P2P ID and PIN are loaded immediately
        P2pSignalingManager.load(this)

        // Prevent system from killing stream when device locks / screen turns off
        requestBatteryOptimizationExemption()

        val prefs = getSharedPreferences("CameraLivePrefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("KEY_DEFAULT_IP", null)
        savedPort = prefs.getString("KEY_DEFAULT_PORT", "8080") ?: "8080"

        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA, 
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Check if permissions already granted – we'll auto-start in onResume if so
        val allGranted = requiredPermissions.filter {
            it != Manifest.permission.POST_NOTIFICATIONS
        }.all { perm ->
            checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            requestPermissionLauncher.launch(requiredPermissions.toTypedArray())
        }

        setContent {
            CameraLiveTheme {
                var isBlackoutMode by remember { mutableStateOf(false) }

                LaunchedEffect(isBlackoutMode) {
                    val lp = window.attributes
                    if (isBlackoutMode) {
                        lp.screenBrightness = 0.01f
                    } else {
                        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                    window.attributes = lp
                }

                if (isBlackoutMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable { isBlackoutMode = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Stream läuft weiter\n\nTippen zum Aufwecken",
                            color = Color.DarkGray,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val ipAddresses = remember { getIpAddresses() }
                        val initialIp = if (savedIp != null && ipAddresses.contains(savedIp)) savedIp else (ipAddresses.firstOrNull() ?: "127.0.0.1")
                        var selectedIp by remember { mutableStateOf(initialIp) }
                        var portText by remember { mutableStateOf(savedPort) }
                        var currentDefaultIp by remember { mutableStateOf(savedIp) }
                        var currentDefaultPort by remember { mutableStateOf(savedPort) }
                        
                        MainScreen(
                            ipAddresses = ipAddresses,
                            selectedIp = selectedIp,
                            port = portText,
                            isDefault = (selectedIp == currentDefaultIp && portText == currentDefaultPort),
                            onIpSelected = { selectedIp = it },
                            onPortChanged = { portText = it },
                            onSetAsDefault = {
                                prefs.edit()
                                    .putString("KEY_DEFAULT_IP", selectedIp)
                                    .putString("KEY_DEFAULT_PORT", portText)
                                    .apply()
                                currentDefaultIp = selectedIp
                                currentDefaultPort = portText
                                Toast.makeText(this@MainActivity, "Als Standard gespeichert: $selectedIp:$portText", Toast.LENGTH_SHORT).show()
                            },
                            onStartStream = {
                                savedPort = portText
                                startStreamService(portText.toIntOrNull() ?: 8080)
                            },
                            onStopStream = {
                                stopStreamService()
                            },
                            onEnterBlackoutMode = {
                                isBlackoutMode = true
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                // Ignore if prompt not supported on custom ROM
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A stream the user stopped stays stopped, and a running one is left alone.
        val userStopped = getSharedPreferences(CameraStreamingService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(CameraStreamingService.KEY_USER_STOPPED, false)
        if (CameraStreamingService.runningInstance == null && !userStopped) {
            val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (cameraGranted && audioGranted) {
                startStreamService(savedPort.toIntOrNull() ?: 8080)
            }
        }
    }

    private fun startStreamService(port: Int) {
        val intent = Intent(this, CameraStreamingService::class.java).apply {
            putExtra("PORT", port)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopStreamService() {
        getSharedPreferences(CameraStreamingService.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CameraStreamingService.KEY_USER_STOPPED, true).apply()
        stopService(Intent(this, CameraStreamingService::class.java))
    }

    private fun getIpAddresses(): List<String> {
        val ipList = mutableListOf<String>()
        try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        // Check if IPv4
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) {
                            ipList.add(sAddr)
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        if (ipList.isEmpty()) {
            ipList.add("127.0.0.1")
        }
        return ipList
    }
}

/** Viewfinder corners, the same mark the browser draws around detections and the PIN. */
private fun Modifier.finderCorners(color: Color, length: Dp = 14.dp, stroke: Dp = 2.dp): Modifier = drawBehind {
    val l = length.toPx()
    val s = stroke.toPx()
    val w = size.width
    val h = size.height
    fun line(a: Offset, b: Offset) = drawLine(color, a, b, s, StrokeCap.Square)
    line(Offset(0f, l), Offset.Zero); line(Offset.Zero, Offset(l, 0f))
    line(Offset(w - l, 0f), Offset(w, 0f)); line(Offset(w, 0f), Offset(w, l))
    line(Offset(0f, h - l), Offset(0f, h)); line(Offset(0f, h), Offset(l, h))
    line(Offset(w - l, h), Offset(w, h)); line(Offset(w, h), Offset(w, h - l))
}

@Composable
private fun StatusLine(running: Boolean, viewerCount: Int) {
    val (dot, text) = when {
        !running -> TextMuted to "Gestoppt"
        viewerCount == 1 -> Good to "Live, 1 Zuschauer"
        viewerCount > 1 -> Good to "Live, $viewerCount Zuschauer"
        AiMotionDetector.isEnabled -> Amber to "Bereit, KI wacht"
        else -> Amber to "Bereit, Kamera schläft bis jemand zuschaut"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = TextPrimary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    ipAddresses: List<String>,
    selectedIp: String,
    port: String,
    isDefault: Boolean,
    onIpSelected: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onSetAsDefault: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onEnterBlackoutMode: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    val running by CameraStreamingService.isRunning.collectAsState()
    val viewerCount by CameraStreamingService.activeViewers.collectAsState()
    val currentPin by P2pSignalingManager.liveRoomPin.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val url = "http://$selectedIp:$port"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Text("Phone as Cam", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        StatusLine(running, viewerCount)

        Spacer(Modifier.height(32.dp))

        // The one rich moment on this screen: what you need to reach this camera.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .finderCorners(Amber)
                .padding(20.dp)
        ) {
            Text("Im Browser öffnen", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            Text(
                url,
                fontFamily = PlexMono,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = TextPrimary,
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stream URL", url))
                    Toast.makeText(context, "Adresse kopiert", Toast.LENGTH_SHORT).show()
                }
            )
            Spacer(Modifier.height(20.dp))
            Text("PIN", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    currentPin,
                    fontFamily = PlexMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 40.sp,
                    lineHeight = 48.sp,
                    letterSpacing = 4.sp,
                    color = Amber
                )
                TextButton(onClick = {
                    P2pSignalingManager.regeneratePin(context)
                    Toast.makeText(context, "Neue PIN gilt ab sofort", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Neue PIN", color = TextSecondary)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (running) {
            OutlinedButton(
                onClick = onStopStream,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, Surface3)
            ) {
                Icon(painterResource(R.drawable.ic_square), null, Modifier.size(18.dp), tint = Bad)
                Spacer(Modifier.width(8.dp))
                Text("Stream stoppen", color = TextPrimary)
            }
        } else {
            Button(
                onClick = onStartStream,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stream starten")
            }
        }

        Spacer(Modifier.height(32.dp))
        SectionLabel("Verbindung")

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedIp,
                onValueChange = {},
                readOnly = true,
                label = { Text("IP-Adresse") },
                textStyle = LocalTextStyle.current.copy(fontFamily = PlexMono),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ipAddresses.forEach { ip ->
                    DropdownMenuItem(
                        text = { Text(ip, fontFamily = PlexMono) },
                        onClick = {
                            onIpSelected(ip)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = port,
            onValueChange = onPortChanged,
            label = { Text("Port") },
            textStyle = LocalTextStyle.current.copy(fontFamily = PlexMono),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(onClick = onSetAsDefault, enabled = !isDefault) {
            Text(if (isDefault) "Ist als Standard gespeichert" else "Als Standard speichern")
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Mehr")

        ListRow("KI-Alarm, Webhooks und Cloud", R.drawable.ic_bell) { showConfigDialog = true }
        ListRow("Bildschirm abdunkeln", R.drawable.ic_moon, onEnterBlackoutMode)

        Spacer(Modifier.height(16.dp))
        Text(
            "Der Stream läuft auch bei ausgeschaltetem Bildschirm weiter, der Power-Button reicht.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )

        if (showConfigDialog) {
            AutomationConfigDialog(onDismiss = { showConfigDialog = false })
        }
    }
}

@Composable
private fun ListRow(title: String, icon: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = TextSecondary)
        Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

@Composable
fun AutomationConfigDialog(
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? MainActivity

    var batteryProtection by remember { mutableStateOf(WebhookManager.batteryProtectionEnabled) }
    var plugOffUrl by remember { mutableStateOf(WebhookManager.plugOffUrl) }
    var plugOnUrl by remember { mutableStateOf(WebhookManager.plugOnUrl) }

    var alarmEnabled by remember { mutableStateOf(WebhookManager.alarmWebhookEnabled) }
    var alarmUrl by remember { mutableStateOf(WebhookManager.alarmWebhookUrl) }

    var driveEnabled by remember(GoogleDriveBackupManager.isAutoBackupEnabled) { mutableStateOf(GoogleDriveBackupManager.isAutoBackupEnabled) }
    var clipEnabled by remember(GoogleDriveBackupManager.isClipBackupEnabled) { mutableStateOf(GoogleDriveBackupManager.isClipBackupEnabled) }
    val connectedAccount = GoogleDriveBackupManager.connectedAccountEmail

    var aiEnabled by remember { mutableStateOf(AiMotionDetector.isEnabled) }
    var aiConfidence by remember { mutableFloatStateOf(AiMotionDetector.confidenceThreshold) }
    var aiTargetClass by remember { mutableStateOf(AiMotionDetector.targetClass) }
    var aiCooldown by remember { mutableIntStateOf(AiMotionDetector.cooldownSeconds) }
    var aiWebhookAlarm by remember { mutableStateOf(AiMotionDetector.isWebhookAlarmEnabled) }
    var aiAutoClip by remember { mutableStateOf(AiMotionDetector.isAutoClipEnabled) }
    var aiAutoVideoDrive by remember { mutableStateOf(AiMotionDetector.isAutoVideoDriveEnabled) }
    var aiAutoDrive by remember { mutableStateOf(AiMotionDetector.isAutoDriveEnabled) }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("KI-Alarm", "Fernzugriff", "Akku", "Webhook", "Drive")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Automatisierung",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 8.dp
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> { // AI & Motion Alarm
                        Text(
                            text = "Objekterkennung und Alarm",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Erkennt Personen und Objekte direkt auf dem Handy, ohne Cloud. Sendet Webhooks und sichert Aufnahmen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("KI-Erkennung", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = aiEnabled, onCheckedChange = { aiEnabled = it })
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Auslöser", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = aiTargetClass == "person",
                                onClick = { aiTargetClass = "person" },
                                label = { Text("Personen") }
                            )
                            FilterChip(
                                selected = aiTargetClass == "person_animal",
                                onClick = { aiTargetClass = "person_animal" },
                                label = { Text("+ Tiere") }
                            )
                            FilterChip(
                                selected = aiTargetClass == "all",
                                onClick = { aiTargetClass = "all" },
                                label = { Text("Alle") }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Mindest-Sicherheit ${(aiConfidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = aiConfidence,
                            onValueChange = { aiConfidence = it },
                            valueRange = 0.30f..0.90f,
                            steps = 11
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Text("Pause zwischen Alarmen ${aiCooldown}s", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = aiCooldown.toFloat(),
                            onValueChange = { aiCooldown = it.toInt() },
                            valueRange = 10f..120f,
                            steps = 10
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Webhook senden", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = aiWebhookAlarm, onCheckedChange = { aiWebhookAlarm = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Clip aufnehmen, 10 s", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = aiAutoClip, onCheckedChange = { aiAutoClip = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Video in die Cloud", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = aiAutoVideoDrive, onCheckedChange = { aiAutoVideoDrive = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Foto in die Cloud", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = aiAutoDrive, onCheckedChange = { aiAutoDrive = it })
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Status: ${AiMotionDetector.lastDetectionStatus}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Letzter Alarm: ${AiMotionDetector.lastAlarmLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    1 -> { // P2P Remote Access
                        Text(
                            text = "Fernzugriff von überall",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Direkter Zugriff über Mobilfunk, ohne Portfreigabe im Router.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val livePin by P2pSignalingManager.liveRoomPin.collectAsState()
                        val liveRoomId by P2pSignalingManager.liveRoomId.collectAsState()
                        val autoInterval by P2pSignalingManager.liveAutoRotateInterval.collectAsState()

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Kamera-ID", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(liveRoomId, fontFamily = PlexMono, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("PIN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = livePin,
                                        fontFamily = PlexMono,
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Relay", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val isConn = P2pSignalingManager.isConnected.get()
                                    Text(if (isConn) "Online" else "Verbinde", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text("PIN automatisch wechseln", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = autoInterval == 0,
                                onClick = { P2pSignalingManager.setAutoRotateInterval(context, 0) },
                                label = { Text("Aus") }
                            )
                            FilterChip(
                                selected = autoInterval == 15,
                                onClick = { P2pSignalingManager.setAutoRotateInterval(context, 15) },
                                label = { Text("15 Min") }
                            )
                            FilterChip(
                                selected = autoInterval == 30,
                                onClick = { P2pSignalingManager.setAutoRotateInterval(context, 30) },
                                label = { Text("30 Min") }
                            )
                            FilterChip(
                                selected = autoInterval == 60,
                                onClick = { P2pSignalingManager.setAutoRotateInterval(context, 60) },
                                label = { Text("1 Std") }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val newPin = P2pSignalingManager.regeneratePin(context)
                                    Toast.makeText(context, "Neue PIN: $newPin", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Neue PIN")
                            }
                            OutlinedButton(
                                onClick = {
                                    val newId = P2pSignalingManager.regenerateRoomId(context)
                                    Toast.makeText(context, "Neue Kamera-ID und PIN erstellt", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Neue ID und PIN")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val worldwideUrl = "https://theStxve.github.io/phone-as-cam-app/#room=${liveRoomId}"

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("P2P Remote Stream Link", worldwideUrl)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Link kopiert", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Link für unterwegs kopieren")
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(worldwideUrl))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Browser konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Im Browser öffnen")
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("P2P Remote Info", "Room: ${liveRoomId}, PIN: ${livePin}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Kamera-ID und PIN kopiert", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Kamera-ID und PIN kopieren")
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Mit jeder neuen PIN werden laufende Verbindungen sofort getrennt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    2 -> { // Smart Plug
                        Text(
                            text = "Dauerbetrieb und Akkuschutz",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Schaltet eine smarte Steckdose (Shelly, Home Assistant, Tasmota), damit der Akku zwischen 20 und 80 % bleibt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Akkuschutz, 20 bis 80 %", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = batteryProtection, onCheckedChange = { batteryProtection = it })
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = plugOffUrl,
                            onValueChange = { plugOffUrl = it },
                            label = { Text("URL zum Ausschalten, bei 80 %") },
                            placeholder = { Text("http://192.168.1.50/relay/0?turn=off") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                WebhookManager.sendWebhook(plugOffUrl, null) { success, msg ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, if (success) "Signal gesendet ($msg)" else "Fehler: $msg", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text("Ausschalten testen")
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = plugOnUrl,
                            onValueChange = { plugOnUrl = it },
                            label = { Text("URL zum Einschalten, bei 20 %") },
                            placeholder = { Text("http://192.168.1.50/relay/0?turn=on") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                WebhookManager.sendWebhook(plugOnUrl, null) { success, msg ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, if (success) "Signal gesendet ($msg)" else "Fehler: $msg", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text("Einschalten testen")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Akku ${WebhookManager.currentBatteryLevel} %, ${if (WebhookManager.isCurrentlyCharging) "lädt" else "entlädt"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    3 -> { // Alarm Webhooks
                        Text(
                            text = "Alarm-Benachrichtigung",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Schickt bei jedem Alarm eine Nachricht, etwa an Discord, Home Assistant oder n8n.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Alarm-Webhook", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = alarmEnabled, onCheckedChange = { alarmEnabled = it })
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = alarmUrl,
                            onValueChange = { alarmUrl = it },
                            label = { Text("Webhook-URL") },
                            placeholder = { Text("https://discord.com/api/webhooks/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                WebhookManager.alarmWebhookEnabled = true
                                WebhookManager.alarmWebhookUrl = alarmUrl
                                WebhookManager.sendAlarm("Test-Alarm von CameraLive App") { success, msg ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, if (success) "Test-Alarm zugestellt" else "Fehler: $msg", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text("Test-Alarm senden")
                            }
                        }
                    }
                    4 -> { // Google Drive
                        Text(
                            text = "Google Drive",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Fotos und Alarm-Aufnahmen automatisch in deinem Drive sichern.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        if (connectedAccount != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Verbunden",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = connectedAccount,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = {
                                        activity?.disconnectGoogleAccount()
                                    }) {
                                        Text("Konto trennen", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Fotos sichern", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = driveEnabled, onCheckedChange = { driveEnabled = it })
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Clips sichern", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = clipEnabled, onCheckedChange = { clipEnabled = it })
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Status: ${GoogleDriveBackupManager.lastBackupStatus}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Kein Konto verbunden",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(onClick = {
                                        activity?.startGoogleSignIn()
                                    }) {
                                        Text("Google-Konto verknüpfen")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val videoChanged = AiMotionDetector.isAutoVideoDriveEnabled != aiAutoVideoDrive
                AiMotionDetector.isEnabled = aiEnabled
                AiMotionDetector.confidenceThreshold = aiConfidence
                AiMotionDetector.targetClass = aiTargetClass
                AiMotionDetector.cooldownSeconds = aiCooldown
                AiMotionDetector.isWebhookAlarmEnabled = aiWebhookAlarm
                AiMotionDetector.isAutoClipEnabled = aiAutoClip
                AiMotionDetector.isAutoVideoDriveEnabled = aiAutoVideoDrive
                AiMotionDetector.isAutoDriveEnabled = aiAutoDrive
                AiMotionDetector.save(context)

                // Rebinding interrupts the stream, so only do it when the use cases changed.
                if (videoChanged) CameraStreamingService.runningInstance?.requestCameraRestart()

                WebhookManager.batteryProtectionEnabled = batteryProtection
                WebhookManager.plugOffUrl = plugOffUrl
                WebhookManager.plugOnUrl = plugOnUrl
                WebhookManager.alarmWebhookEnabled = alarmEnabled
                WebhookManager.alarmWebhookUrl = alarmUrl
                WebhookManager.save(context)

                GoogleDriveBackupManager.isAutoBackupEnabled = driveEnabled
                GoogleDriveBackupManager.isClipBackupEnabled = clipEnabled
                GoogleDriveBackupManager.save(context)

                Toast.makeText(context, "Gespeichert", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
