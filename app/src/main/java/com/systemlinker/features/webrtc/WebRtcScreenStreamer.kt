package com.systemlinker.features.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*

class WebRtcScreenStreamer(
    private val context: Context,
    private val webRtcManager: WebRtcManager
) {
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    var isStreaming = false; private set

    fun startStreaming(resultCode: Int, data: Intent) {
        if (isStreaming) return
        val factory = webRtcManager.getFactory() ?: return
        val eglContext = webRtcManager.getEglBaseContext()

        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { stopStreaming() }
        })

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglContext)
        videoSource = factory.createVideoSource((screenCapturer as ScreenCapturerAndroid).isScreencast)
        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        
        // CRITICAL FIX: Ensure perfect even-numbered resolutions.
        // Android hardware encoders (H.264) will crash and deliver black frames if fed odd-numbered or fractured dimensions.
        val displayMetrics = context.resources.displayMetrics
        val targetWidth = (displayMetrics.widthPixels / 2).let { if (it % 2 != 0) it - 1 else it }
        val targetHeight = (displayMetrics.heightPixels / 2).let { if (it % 2 != 0) it - 1 else it }
        
        screenCapturer?.startCapture(targetWidth, targetHeight, 15)

        videoTrack = factory.createVideoTrack("SCREEN_TRACK_ID_${System.currentTimeMillis()}", videoSource)
        webRtcManager.setLocalVideoTrack(videoTrack)

        val sender = webRtcManager.peerConnection?.transceivers?.firstOrNull { 
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO 
        }?.sender
        
        val parameters = sender?.parameters
        if (parameters != null && parameters.encodings.isNotEmpty()) {
            for (encoding in parameters.encodings) {
                encoding.maxBitrateBps = 800_000 
            }
            sender.parameters = parameters
        }

        isStreaming = true
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