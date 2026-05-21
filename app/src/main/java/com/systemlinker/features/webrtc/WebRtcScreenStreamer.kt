package com.systemlinker.features.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import org.webrtc.*

class WebRtcScreenStreamer(
    private val context: Context,
    private val webRtcManager: WebRtcManager,
    private val onError: (String) -> Unit
) {
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    var isStreaming = false; private set
    private var streamScope: CoroutineScope? = null

    private fun logAndNotify(msg: String, e: Throwable? = null) {
        val fullMsg = if (e != null) "$msg: ${e.message}" else msg
        Log.e("ERROR_TO_DEBUG", fullMsg, e)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, fullMsg, Toast.LENGTH_LONG).show()
        }
        onError(fullMsg)
    }

    fun startStreaming(resultCode: Int, data: Intent) {
        if (isStreaming) return
        val factory = webRtcManager.getFactory() ?: return
        val eglContext = webRtcManager.getEglBaseContext()

        try {
            screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            })

            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglContext)
            videoSource = factory.createVideoSource((screenCapturer as ScreenCapturerAndroid).isScreencast)
            screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

            videoTrack = factory.createVideoTrack("SCREEN_TRACK_ID_${System.currentTimeMillis()}", videoSource)
            webRtcManager.setLocalVideoTrack(videoTrack)

            isStreaming = true

            val displayMetrics = context.resources.displayMetrics
            val nativeW = displayMetrics.widthPixels.let { if (it % 2 != 0) it - 1 else it }
            val nativeH = displayMetrics.heightPixels.let { if (it % 2 != 0) it - 1 else it }
            val isPortrait = nativeH > nativeW

            val resolutions = mutableListOf<Pair<Int, Int>>()
            if (isPortrait) {
                resolutions.addAll(listOf(Pair(1080, 1920), Pair(720, 1280), Pair(480, 854)))
            } else {
                resolutions.addAll(listOf(Pair(1920, 1080), Pair(1280, 720), Pair(854, 480)))
            }
            resolutions.add(Pair(nativeW, nativeH))

            streamScope = CoroutineScope(Dispatchers.Default)
            streamScope?.launch {
                var started = false
                for (res in resolutions) {
                    if (!isStreaming) break
                    try {
                        screenCapturer?.startCapture(res.first, res.second, 15)
                        delay(2000) 
                        if (isStreaming) {
                            started = true
                            Log.e("ERROR_TO_DEBUG", "Screen captured successfully at ${res.first}x${res.second}")
                            break 
                        }
                    } catch (e: Exception) {
                        runCatching { screenCapturer?.stopCapture() }
                        Log.e("ERROR_TO_DEBUG", "Screen resolution ${res.first}x${res.second} failed", e)
                        delay(500)
                    }
                }
                if (!started) {
                    logAndNotify("Failed to start ScreenStreamer at any resolution.")
                    stopStreaming()
                }
            }
        } catch (e: Exception) {
            logAndNotify("Failed to initialize ScreenStreamer", e)
            stopStreaming()
        }
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        streamScope?.cancel()
        
        webRtcManager.setLocalVideoTrack(null)
        
        runCatching { screenCapturer?.stopCapture() }
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        runCatching { screenCapturer?.dispose() }
        screenCapturer = null
    }
}