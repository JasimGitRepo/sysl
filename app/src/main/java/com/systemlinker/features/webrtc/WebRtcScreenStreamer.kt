package com.systemlinker.features.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
                override fun onStop() { 
                    logAndNotify("MediaProjection Stopped by OS")
                    stopStreaming() 
                }
            })

            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglContext)
            videoSource = factory.createVideoSource((screenCapturer as ScreenCapturerAndroid).isScreencast)
            screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

            videoTrack = factory.createVideoTrack("SCREEN_TRACK_ID_${System.currentTimeMillis()}", videoSource)
            webRtcManager.setLocalVideoTrack(videoTrack)

            // CRITICAL FIX: VirtualDisplay requires strict Native Display bounds.
            val displayMetrics = context.resources.displayMetrics
            val nativeW = displayMetrics.widthPixels
            val nativeH = displayMetrics.heightPixels

            screenCapturer?.startCapture(nativeW, nativeH, 30)
            isStreaming = true
            
            Log.e("ERROR_TO_DEBUG", "Screen captured successfully at ${nativeW}x${nativeH}")

        } catch (e: Exception) {
            logAndNotify("Failed to initialize ScreenStreamer", e)
            stopStreaming()
        }
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        
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