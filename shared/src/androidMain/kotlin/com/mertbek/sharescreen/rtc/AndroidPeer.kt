package com.mertbek.sharescreen.rtc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.webrtc.IceCandidate as NativeIceCandidate
import org.webrtc.SessionDescription as NativeDescription

class AndroidPeer(private val engine: AndroidRtcEngine, config: PeerConnection.RTCConfiguration) : RtcPeer {
    private val eventChannel = Channel<RtcEvent>(Channel.UNLIMITED)
    private val pendingRemoteCandidates = mutableListOf<NativeIceCandidate>()
    private val dataChannels = mutableListOf<AndroidDataChannel>()
    private val lock = Any()

    override val events: Flow<RtcEvent> = eventChannel.receiveAsFlow()

    @Volatile
    override var isClosed = false
        private set

    override val hasLocalOffer: Boolean
        get() = !isClosed && connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: NativeIceCandidate) {
            eventChannel.trySend(RtcEvent.LocalIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)))
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            eventChannel.trySend(RtcEvent.ConnectionState(state.toCommon()))
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            when (val track = transceiver.receiver.track()) {
                is VideoTrack -> eventChannel.trySend(RtcEvent.RemoteVideoTrack(AndroidRemoteVideo(track)))
                is AudioTrack -> eventChannel.trySend(RtcEvent.RemoteAudioTrack(AndroidRemoteAudio(track)))
            }
        }

        override fun onDataChannel(channel: DataChannel) {
            val wrapped = AndroidDataChannel(channel)
            val accepted = synchronized(lock) { !isClosed && dataChannels.add(wrapped) }
            if (accepted) eventChannel.trySend(RtcEvent.RemoteDataChannel(wrapped)) else wrapped.close()
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out NativeIceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private val connection: PeerConnection = engine.factory.createPeerConnection(config, observer)
        ?: throw RtcException("Could not create the peer connection")

    override suspend fun createOffer(): SessionDescription =
        createDescription { connection.createOffer(it, MediaConstraints()) }

    override suspend fun createAnswer(): SessionDescription =
        createDescription { connection.createAnswer(it, MediaConstraints()) }

    override suspend fun setLocalDescription(description: SessionDescription) =
        setDescription { connection.setLocalDescription(it, description.toNative()) }

    override suspend fun setRemoteDescription(description: SessionDescription) {
        setDescription { connection.setRemoteDescription(it, description.toNative()) }
        val queued = synchronized(lock) { pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() } }
        queued.forEach(connection::addIceCandidate)
    }

    override fun addRemoteIceCandidate(candidate: IceCandidate) {
        val native = NativeIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        val queued = synchronized(lock) {
            (connection.remoteDescription == null).also { if (it) pendingRemoteCandidates += native }
        }
        if (!queued) connection.addIceCandidate(native)
    }

    override fun createDataChannel(label: String): RtcDataChannel {
        val channel = connection.createDataChannel(label, DataChannel.Init())
            ?: throw RtcException("Could not create the data channel $label")
        val wrapped = AndroidDataChannel(channel)
        val accepted = synchronized(lock) { !isClosed && dataChannels.add(wrapped) }
        if (!accepted) {
            wrapped.close()
            throw RtcException("The peer is closed")
        }
        return wrapped
    }

    override fun addMedia(media: CapturedMedia) {
        val android = media as? AndroidCapturedMedia ?: throw RtcException("Unsupported media")
        val sendOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(STREAM_ID))
        val video = connection.addTransceiver(android.video, sendOnly)
        video.sender.configureForScreenContent(android.maxVideoBitrateBps)
        engine.preferHardwareVideoCodec(video)
        android.audio?.let { connection.addTransceiver(it, sendOnly) }
    }

    override fun restartIce() = connection.restartIce()

    override suspend fun stats(): StreamInfo? {
        if (isClosed) return null
        val report = suspendCancellableCoroutine { continuation -> connection.getStats { continuation.resume(it) } }
        val stats = report.statsMap.values
        val video = stats.find { it.type == "inbound-rtp" && it.members["kind"] == "video" }?.members ?: return null
        val width = (video["frameWidth"] as? Number)?.toInt() ?: return null
        val height = (video["frameHeight"] as? Number)?.toInt() ?: return null
        val roundTrip = stats.find { it.type == "candidate-pair" && it.members["nominated"] == true }
            ?.members?.get("currentRoundTripTime") as? Number
        return StreamInfo(
            width = width,
            height = height,
            framesPerSecond = (video["framesPerSecond"] as? Number)?.toInt(),
            roundTripMillis = roundTrip?.let { (it.toDouble() * 1000).toInt() },
        )
    }

    override fun close() {
        val channels = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            dataChannels.toList().also { dataChannels.clear() }
        }
        eventChannel.close()
        channels.forEach(AndroidDataChannel::close)
        connection.dispose()
    }

    private fun RtpSender.configureForScreenContent(maxBitrateBps: Int) {
        val parameters = parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        parameters.encodings.forEach {
            it.maxBitrateBps = maxBitrateBps
            it.maxFramerate = MAX_FRAMERATE
        }
        setParameters(parameters)
    }

    private suspend fun createDescription(start: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            start(object : SdpObserver {
                override fun onCreateSuccess(description: NativeDescription) = continuation.resume(description.toCommon())

                override fun onCreateFailure(error: String?) =
                    continuation.resumeWithException(RtcException("Creating the description failed: $error"))

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            })
        }

    private suspend fun setDescription(start: (SdpObserver) -> Unit): Unit =
        suspendCancellableCoroutine { continuation ->
            start(object : SdpObserver {
                override fun onSetSuccess() = continuation.resume(Unit)

                override fun onSetFailure(error: String?) =
                    continuation.resumeWithException(RtcException("Setting the description failed: $error"))

                override fun onCreateSuccess(description: NativeDescription) = Unit
                override fun onCreateFailure(error: String?) = Unit
            })
        }

    private fun SessionDescription.toNative() = NativeDescription(
        when (type) {
            SdpType.OFFER -> NativeDescription.Type.OFFER
            SdpType.ANSWER -> NativeDescription.Type.ANSWER
            SdpType.ROLLBACK -> NativeDescription.Type.ROLLBACK
        },
        sdp,
    )

    private fun NativeDescription.toCommon() = SessionDescription(
        when (type) {
            NativeDescription.Type.OFFER -> SdpType.OFFER
            NativeDescription.Type.ANSWER, NativeDescription.Type.PRANSWER -> SdpType.ANSWER
            NativeDescription.Type.ROLLBACK -> SdpType.ROLLBACK
        },
        description,
    )

    private fun PeerConnection.PeerConnectionState.toCommon() = when (this) {
        PeerConnection.PeerConnectionState.NEW -> PeerConnectionState.NEW
        PeerConnection.PeerConnectionState.CONNECTING -> PeerConnectionState.CONNECTING
        PeerConnection.PeerConnectionState.CONNECTED -> PeerConnectionState.CONNECTED
        PeerConnection.PeerConnectionState.DISCONNECTED -> PeerConnectionState.DISCONNECTED
        PeerConnection.PeerConnectionState.FAILED -> PeerConnectionState.FAILED
        PeerConnection.PeerConnectionState.CLOSED -> PeerConnectionState.CLOSED
    }

    private companion object {
        const val STREAM_ID = "screen"
        const val MAX_FRAMERATE = 30
    }
}
