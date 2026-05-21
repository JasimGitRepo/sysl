package com.systemlinker.features.webrtc

import org.webrtc.*

class WebRtcAudioStreamer(private val webRtcManager: WebRtcManager) {
    
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    var isStreaming = false; private set

    fun startStreaming() {
        if (isStreaming) return
        val factory = webRtcManager.getFactory() ?: return

        // CRITICAL FIX: Enforce WebRTC Software AEC/AGC to prevent Hardware deadlocks during bidirectional Calls
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

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