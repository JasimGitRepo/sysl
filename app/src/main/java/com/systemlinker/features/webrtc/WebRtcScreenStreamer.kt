package com.systemlinker.features.webrtc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.UUID

class WebRtcScreenStreamer(
    context: Context,
    private val webRtcManager: WebRtcManager,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    
    @Volatile
    var isStreaming = false
        private set

    @Volatile
    private var isStopping = false

    @Volatile
    private var hasReceivedFirstFrame = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastToastTime = 0L
    private var lastRotation = -1
    private var lastWidth = -1
    private var lastHeight = -1

    private val watchdogSink = object : VideoSink {
        override fun onFrame(frame: VideoFrame) {
            hasReceivedFirstFrame = true
        }
    }

    private val watchdogRunnable = Runnable {
        if (isStreaming && !hasReceivedFirstFrame) {
            logAndNotify("Screen capture watchdog timeout: no frames received")
            stopStreaming()
        }
    }

    private val rotationRunnable = Runnable {
        val capturer = screenCapturer ?: return@Runnable
        if (isStopping || !isStreaming) return@Runnable
        
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return@Runnable
        val currentRotation = display.rotation
        
        val (width, height) = getScaledDisplayBounds(appContext)
        
        if (currentRotation != lastRotation || width != lastWidth || height != lastHeight) {
            lastRotation = currentRotation
            lastWidth = width
            lastHeight = height
            capturer.changeCaptureFormat(width, height, 15)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                mainHandler.removeCallbacks(rotationRunnable)
                mainHandler.postDelayed(rotationRunnable, 500)
            }
        }
    }

    private fun logAndNotify(msg: String, e: Throwable? = null) {
        val fullMsg = if (e != null) "$msg: ${e.message}" else msg
        Log.e("ERROR_TO_DEBUG", fullMsg, e)
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 2000) {
            lastToastTime = now
            mainHandler.post {
                Toast.makeText(appContext, fullMsg, Toast.LENGTH_LONG).show()
            }
        }
        onError(fullMsg)
    }

    fun startStreaming(resultCode: Int, data: Intent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startStreaming(resultCode, data) }
            return
        }

        if (isStreaming || isStopping) return

        if (resultCode != Activity.RESULT_OK) {
            logAndNotify("Screen capture permission denied")
            return
        }

        val factory = webRtcManager.getFactory()
        if (factory == null) {
            logAndNotify("Factory is not initialized")
            return
        }
        
        val eglContext = webRtcManager.getEglBaseContext() as? EglBase.Context
        if (eglContext == null) {
            logAndNotify("EGL context unavailable")
            return
        }

        try {
            screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        logAndNotify("MediaProjection Stopped by OS")
                        stopStreaming()
                    }
                }
            })

            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglContext)
            if (surfaceTextureHelper == null) {
                logAndNotify("Failed to create SurfaceTextureHelper")
                stopStreaming()
                return
            }

            videoSource = factory.createVideoSource((screenCapturer as ScreenCapturerAndroid).isScreencast)
            screenCapturer?.initialize(surfaceTextureHelper, appContext, videoSource?.capturerObserver)

            videoTrack = factory.createVideoTrack("SCREEN_TRACK_ID_${UUID.randomUUID()}", videoSource)
            
            webRtcManager.setLocalVideoTrack(videoTrack)
            webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)

            isStreaming = true
            hasReceivedFirstFrame = false

            videoTrack?.addSink(watchdogSink)
            mainHandler.postDelayed(watchdogRunnable, 5000)

            val (width, height) = getScaledDisplayBounds(appContext)
            lastWidth = width
            lastHeight = height
            
            val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            lastRotation = display?.rotation ?: -1

            screenCapturer?.startCapture(width, height, 15)
            displayManager.registerDisplayListener(displayListener, mainHandler)

        } catch (e: Exception) {
            logAndNotify("Failed to initialize ScreenStreamer", e)
            stopStreaming()
        }
    }

    private fun getScaledDisplayBounds(context: Context, maxBoundingDim: Int = 1080): Pair<Int, Int> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val displayMetrics = DisplayMetrics()
        display?.getRealMetrics(displayMetrics)
        val nativeW = displayMetrics.widthPixels
        val nativeH = displayMetrics.heightPixels
        val aspectRatio = nativeW.toFloat() / nativeH.toFloat()
        var targetW = nativeW
        var targetH = nativeH
        if (nativeW > maxBoundingDim || nativeH > maxBoundingDim) {
            if (nativeW > nativeH) {
                targetW = maxBoundingDim
                targetH = (maxBoundingDim / aspectRatio).toInt()
            } else {
                targetH = maxBoundingDim
                targetW = (maxBoundingDim * aspectRatio).toInt()
            }
        }
        if (targetW % 2 != 0) targetW--
        if (targetH % 2 != 0) targetH--
        return Pair(targetW, targetH)
    }

    fun stopStreaming() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopStreaming() }
            return
        }

        if (!isStreaming || isStopping) return
        isStopping = true
        isStreaming = false

        runCatching {
            val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(displayListener)
        }.onFailure { e ->
            Log.e("ERROR_TO_DEBUG", "Failed to unregister display listener", e)
        }
        
        mainHandler.removeCallbacks(rotationRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)

        runCatching {
            videoTrack?.removeSink(watchdogSink)
        }

        webRtcManager.setLocalVideoTrack(null)

        val capturerToDispose = screenCapturer
        val trackToDispose = videoTrack
        val sourceToDispose = videoSource
        val helperToDispose = surfaceTextureHelper

        screenCapturer = null
        videoTrack = null
        videoSource = null
        surfaceTextureHelper = null

        streamerScope.launch {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3000) {
                val activeTrack = webRtcManager.peerConnection?.transceivers
                    ?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                    ?.sender?.track()
                if (activeTrack == null) {
                    break
                }
                try { delay(50) } catch (e: CancellationException) { break }
            }

            runCatching {
                capturerToDispose?.stopCapture()
            }.onFailure { e ->
                Log.e("ERROR_TO_DEBUG", "Failed to stop screen capturer", e)
            }
            runCatching {
                trackToDispose?.dispose()
            }.onFailure { e ->
                Log.e("ERROR_TO_DEBUG", "Failed to dispose video track", e)
            }
            runCatching {
                sourceToDispose?.dispose()
            }.onFailure { e ->
                Log.e("ERROR_TO_DEBUG", "Failed to dispose video source", e)
            }
            runCatching {
                helperToDispose?.dispose()
            }.onFailure { e ->
                Log.e("ERROR_TO_DEBUG", "Failed to dispose surface texture helper", e)
            }
            runCatching {
                capturerToDispose?.dispose()
            }.onFailure { e ->
                Log.e("ERROR_TO_DEBUG", "Failed to dispose screen capturer", e)
            }
            mainHandler.post {
                isStopping = false
            }
        }
    }

    fun release() {
        stopStreaming()
        streamerScope.cancel()
    }
}