package com.systemlinker.features

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ScreenCaptureActivity : ComponentActivity() {

    private var pendingResultCode: Int = 0
    private var pendingData: Intent? = null

    private val fgsUpgradeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.systemlinker.FGS_UPGRADE_COMPLETE") {
                // FIX: Strictly serialize lifecycle execution using the completion lambda 
                // to prevent System Server MediaProjection crashes caused by premature finish() calls.
                LiveSessionManager.screenCastCallback?.invoke(pendingResultCode, pendingData) {
                    finish()
                }
            }
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            pendingResultCode = result.resultCode
            pendingData = result.data
            val upgradeIntent = Intent("com.systemlinker.UPGRADE_FGS_MP")
            sendBroadcast(upgradeIntent)
        } else {
            LiveSessionManager.screenCastCallback?.invoke(result.resultCode, null) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter("com.systemlinker.FGS_UPGRADE_COMPLETE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fgsUpgradeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fgsUpgradeReceiver, filter)
        }
        
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(fgsUpgradeReceiver)
        } catch (e: Exception) {}
    }
}