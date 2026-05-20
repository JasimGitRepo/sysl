package com.systemlinker.features.webrtc

import org.webrtc.*

class WebRtcAudioStreamer(private val webRtcManager: WebRtcManager) {
    
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    var isStreaming = false; private set

    fun startStreaming() {
        if (isStreaming) return
        val factory = webRtcManager.getFactory() ?: return

        // CRITICAL FIX: Removed "googEchoCancellation" and other software filters.
        // Doing this forces WebRTC to use Android's native hardware JavaAudioDeviceModule.
        // This eliminates the "double-gating" software loop that caused the pulsing/hiccupping audio.
        val audioConstraints = MediaConstraints()

        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("AUDIO_TRACK_ID_${System.currentTimeMillis()}", audioSource)

        webRtcManager.setLocalAudioTrack(audioTrack)
        isStreaming = true
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        
        webRtcManager.setLocalAudioTrack(null)
        
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        
        runCatching { audioSource?.dispose() }
        audioSource = null
    }
}