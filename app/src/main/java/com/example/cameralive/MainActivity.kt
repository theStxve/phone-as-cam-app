package com.example.cameralive

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
import com.example.cameralive.theme.CameraLiveTheme
import java.net.NetworkInterface

import android.widget.Toast

import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

class MainActivity : ComponentActivity() {

    private var savedPort = "8080"
    private var serviceStarted = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
            if (cameraGranted && audioGranted) {
                // Start after permissions granted (app is in foreground at this point)
                startStreamService(savedPort.toIntOrNull() ?: 8080)
                serviceStarted = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                            text = "🌙 AMOLED Sparmodus aktiv\n\nStream läuft zuverlässig im Hintergrund\n(auch bei ausgeschaltetem Bildschirm)\n\n👉 Tippen zum Aufwecken",
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
                                serviceStarted = true
                                startStreamService(portText.toIntOrNull() ?: 8080)
                            },
                            onStopStream = {
                                serviceStarted = false
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
        if (!serviceStarted) {
            val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (cameraGranted && audioGranted) {
                startStreamService(savedPort.toIntOrNull() ?: 8080)
                serviceStarted = true
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

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Camera Live Stream", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(text = "Connect to:")
        Spacer(modifier = Modifier.height(8.dp))
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedIp,
                onValueChange = {},
                readOnly = true,
                label = { Text("IP Address") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(0.85f)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                ipAddresses.forEach { ip ->
                    DropdownMenuItem(
                        text = { Text(ip) },
                        onClick = {
                            onIpSelected(ip)
                            expanded = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        OutlinedTextField(
            value = port,
            onValueChange = onPortChanged,
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(0.85f)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedButton(
            onClick = onSetAsDefault,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(if (isDefault) "✓ Als Standard gespeichert" else "⭐ Als Standard speichern")
        }

        Spacer(modifier = Modifier.height(12.dp))
        
        Text(text = "URL: http://$selectedIp:$port", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(24.dp))
        Row {
            Button(onClick = onStartStream) {
                Text("Start Stream")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onStopStream) {
                Text("Stop Stream")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        FilledTonalButton(
            onClick = onEnterBlackoutMode,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text("🌙 AMOLED Sparmodus (Bildschirm schwarz)")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "💡 Tipp: Sie können auch einfach den Power-Button drücken. Die Kamera streamt bei ausgeschaltetem Bildschirm weiter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.85f)
        )
    }
}
