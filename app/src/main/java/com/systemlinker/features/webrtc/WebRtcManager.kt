package com.systemlinker.features.webrtc

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.PeerConnection.IceServer
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcManager(private val context: Context, private val signalingSender: (String) -> Unit) {

    private val eglBase: EglBase by lazy { EglBase.create() }
    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null

    fun initialize() {
        if (peerConnectionFactory != null) return
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
            
        audioDeviceModule.release()
    }

    fun createPeerConnection(isCaller: Boolean, onTrackReceived: ((MediaStreamTrack) -> Unit)? = null) {
        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED 
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().apply {
                    put("cmd", "webrtc_ice")
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                signalingSender(json.toString())
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track != null) {
                    onTrackReceived?.invoke(track)
                }
            }

            override fun onAddStream(stream: MediaStream) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
        
        // CRITICAL FIX: The CALLEE must NEVER add transceivers manually before receiving the Offer.
        // WebRTC will automatically instantiate the Transceivers via SDP mapping when setRemoteDescription happens.
        // Therefore, we removed the local peerConnection.addTransceiver() calls entirely.
    }

    fun setLocalVideoTrack(track: VideoTrack?) {
        val transceivers = peerConnection?.transceivers ?: return
        val transceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
        transceiver?.sender?.setTrack(track, false)
    }

    fun setLocalAudioTrack(track: AudioTrack?) {
        val transceivers = peerConnection?.transceivers ?: return
        val transceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
        transceiver?.sender?.setTrack(track, false)
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        val transceivers = peerConnection?.transceivers ?: return
        val transceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
        val audioTrack = transceiver?.receiver?.track() as? AudioTrack
        audioTrack?.setEnabled(enabled)
    }

    fun terminate() {
        peerConnection?.close()
        peerConnection = null
    }

    fun handleSignalingMessage(json: JSONObject) {
        val cmd = json.optString("cmd")
        when (cmd) {
            "webrtc_offer" -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp"))
                peerConnection?.setRemoteDescription(SdpObserverImpl("setRemoteOffer"), sdp)
                peerConnection?.createAnswer(SdpObserverImpl("answer"), MediaConstraints())
            }
            "webrtc_answer" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
                peerConnection?.setRemoteDescription(SdpObserverImpl("setRemoteAnswer"), sdp)
            }
            "webrtc_ice" -> {
                val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private inner class SdpObserverImpl(val type: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            peerConnection?.setLocalDescription(this, sdp)
            val json = JSONObject().apply {
                put("cmd", if (sdp.type == SessionDescription.Type.OFFER) "webrtc_offer" else "webrtc_answer")
                put("sdp", sdp.description)
            }
            signalingSender(json.toString())
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
    
    fun getEglBaseContext(): EglBase.Context = eglBase.eglBaseContext
    fun getFactory(): PeerConnectionFactory? = peerConnectionFactory
}