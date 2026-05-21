package com.systemlinker.features.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import org.webrtc.*

class WebRtcCameraStreamer(
    private val context: Context,
    private val webRtcManager: WebRtcManager
) {
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    var isStreaming = false; private set
    
    private var streamScope: CoroutineScope? = null

    private fun logAndToast(msg: String, e: Throwable? = null) {
        val fullMsg = if (e != null) "$msg: ${e.message}" else msg
        Log.e("ERROR_TO_DEBUG", fullMsg, e)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, fullMsg, Toast.LENGTH_LONG).show()
        }
    }

    fun startStreaming(isFront: Boolean) {
        if (isStreaming) stopStreaming()
        val factory = webRtcManager.getFactory() ?: return
        val eglContext = webRtcManager.getEglBaseContext()

        val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (isFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull() ?: return

        try {
            videoCapturer = enumerator.createCapturer(deviceName, null)
            surfaceTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglContext)
            videoSource = factory.createVideoSource(false)

            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            
            videoTrack = factory.createVideoTrack("CAM_TRACK_ID_${System.currentTimeMillis()}", videoSource)
            webRtcManager.setLocalVideoTrack(videoTrack)
            isStreaming = true

            streamScope = CoroutineScope(Dispatchers.Default)
            streamScope?.launch {
                val resolutions = listOf(Pair(640, 480), Pair(1024, 768), Pair(1280, 720), Pair(320, 240))
                var started = false
                
                for (res in resolutions) {
                    if (!isStreaming) break
                    try {
                        videoCapturer?.startCapture(res.first, res.second, 30)
                        delay(1500) 
                        if (isStreaming) {
                            started = true
                            Log.e("ERROR_TO_DEBUG", "Camera started successfully at ${res.first}x${res.second}")
                            break
                        }
                    } catch (e: Exception) {
                        runCatching { videoCapturer?.stopCapture() }
                        Log.e("ERROR_TO_DEBUG", "Camera start failed at ${res.first}x${res.second}", e)
                        delay(500)
                    }
                }
                
                if (!started) {
                    logAndToast("Failed to start Camera at any resolution.")
                    stopStreaming()
                }
            }
        } catch (e: Exception) {
            logAndToast("Failed to initialize CameraStreamer", e)
            stopStreaming()
        }
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        streamScope?.cancel()
        
        webRtcManager.setLocalVideoTrack(null)
        
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
    }
}