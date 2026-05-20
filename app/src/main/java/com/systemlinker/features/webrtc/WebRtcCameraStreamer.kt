package com.systemlinker.features.webrtc

import android.content.Context
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

    fun startStreaming(isFront: Boolean, width: Int = 640, height: Int = 480, fps: Int = 30) {
        if (isStreaming) stopStreaming()
        val factory = webRtcManager.getFactory() ?: return
        val eglContext = webRtcManager.getEglBaseContext()

        val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (isFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull() ?: return

        videoCapturer = enumerator.createCapturer(deviceName, null)
        surfaceTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglContext)
        videoSource = factory.createVideoSource(false)

        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(width, height, fps)

        videoTrack = factory.createVideoTrack("CAM_TRACK_ID_${System.currentTimeMillis()}", videoSource)
        webRtcManager.setLocalVideoTrack(videoTrack)

        val sender = webRtcManager.peerConnection?.transceivers?.firstOrNull { 
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO 
        }?.sender
        
        val parameters = sender?.parameters
        if (parameters != null && parameters.encodings.isNotEmpty()) {
            for (encoding in parameters.encodings) {
                encoding.maxBitrateBps = 600_000 
            }
            sender.parameters = parameters
        }

        isStreaming = true
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        
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