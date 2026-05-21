package com.systemlinker.features.webrtc

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcManager(private val context: Context, private val signalingSender: (String) -> Unit) {

    private val eglBase: EglBase by lazy { EglBase.create() }
    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null

    private val actionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var sdpGeneration = 0
    private var isRemoteDescriptionSet = false
    private val iceCache = mutableListOf<IceCandidate>()

    init {
        scope.launch {
            for (action in actionQueue) {
                try {
                    withTimeout(5000) { action() }
                } catch (e: Exception) {
                    terminate()
                }
            }
        }
    }

    private fun enqueueAction(action: suspend () -> Unit) {
        scope.launch { actionQueue.send(action) }
    }

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
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
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
                    put("gen", sdpGeneration)
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
    }

    fun setLocalVideoTrack(track: VideoTrack?) {
        enqueueAction {
            val transceivers = peerConnection?.transceivers ?: return@enqueueAction
            val transceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            transceiver?.sender?.setTrack(track, false)
        }
    }

    fun setLocalAudioTrack(track: AudioTrack?) {
        enqueueAction {
            val transceivers = peerConnection?.transceivers ?: return@enqueueAction
            val transceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            transceiver?.sender?.setTrack(track, false)
        }
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        val transceivers = peerConnection?.transceivers ?: return
        val transceiver = transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
        val audioTrack = transceiver?.receiver?.track() as? AudioTrack
        audioTrack?.setEnabled(enabled)
    }

    fun terminate() {
        enqueueAction {
            peerConnection?.close()
            peerConnection = null
            iceCache.clear()
            isRemoteDescriptionSet = false
            sdpGeneration = 0
        }
    }

    fun handleSignalingMessage(json: JSONObject) {
        val cmd = json.optString("cmd")
        when (cmd) {
            "webrtc_offer" -> {
                enqueueAction {
                    sdpGeneration = json.optInt("gen", 0)
                    iceCache.clear()
                    isRemoteDescriptionSet = false
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp"))
                    suspendSetRemoteDescription(sdp)
                    isRemoteDescriptionSet = true
                    iceCache.forEach { peerConnection?.addIceCandidate(it) }
                    iceCache.clear()
                    val answer = suspendCreateAnswer(MediaConstraints())
                    suspendSetLocalDescription(answer)
                    signalingSender(JSONObject().put("cmd", "webrtc_answer").put("sdp", answer.description).put("gen", sdpGeneration).toString())
                }
            }
            "webrtc_ice" -> {
                enqueueAction {
                    val gen = json.optInt("gen", 0)
                    if (gen == sdpGeneration) {
                        val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
                        if (isRemoteDescriptionSet) {
                            peerConnection?.addIceCandidate(candidate)
                        } else {
                            iceCache.add(candidate)
                        }
                    }
                }
            }
        }
    }

    private suspend fun suspendCreateAnswer(constraints: MediaConstraints): SessionDescription = suspendCancellableCoroutine { cont ->
        val pc = peerConnection
        if (pc == null) {
            if (cont.isActive) cont.resumeWithException(Exception("PeerConnection is null"))
            return@suspendCancellableCoroutine
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(Exception(error ?: "Unknown error")) }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private suspend fun suspendSetLocalDescription(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        val pc = peerConnection
        if (pc == null) {
            if (cont.isActive) cont.resumeWithException(Exception("PeerConnection is null"))
            return@suspendCancellableCoroutine
        }
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(Exception(e ?: "Unknown error")) }
        }, sdp)
    }

    private suspend fun suspendSetRemoteDescription(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        val pc = peerConnection
        if (pc == null) {
            if (cont.isActive) cont.resumeWithException(Exception("PeerConnection is null"))
            return@suspendCancellableCoroutine
        }
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(Exception(e ?: "Unknown error")) }
        }, sdp)
    }
    
    fun getEglBaseContext(): EglBase.Context = eglBase.eglBaseContext
    fun getFactory(): PeerConnectionFactory? = peerConnectionFactory
}