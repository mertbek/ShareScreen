package com.mertbek.sharescreen.rtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaType
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DesktopPeer(private val factory: PeerConnectionFactory, config: RTCConfiguration) : RtcPeer {
    private val eventChannel = Channel<RtcEvent>(Channel.UNLIMITED)
    private val pendingRemoteCandidates = mutableListOf<RTCIceCandidate>()
    private val dataChannels = mutableListOf<DesktopDataChannel>()
    private val disposers = mutableListOf<() -> Unit>()
    private val lock = Any()

    override val events: Flow<RtcEvent> = eventChannel.receiveAsFlow()

    @Volatile
    override var isClosed = false
        private set

    override val hasLocalOffer: Boolean
        get() = !isClosed && connection.signalingState == RTCSignalingState.HAVE_LOCAL_OFFER

    private val observer = object : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            eventChannel.trySend(RtcEvent.LocalIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)))
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            eventChannel.trySend(RtcEvent.ConnectionState(state.toCommon()))
        }

        override fun onTrack(transceiver: RTCRtpTransceiver) {
            synchronized(lock) { disposers += { transceiver.receiver.dispose(); transceiver.dispose() } }
            when (val track = transceiver.receiver.track) {
                is VideoTrack -> eventChannel.trySend(RtcEvent.RemoteVideoTrack(DesktopRemoteVideo(track)))
                is AudioTrack -> eventChannel.trySend(RtcEvent.RemoteAudioTrack(DesktopRemoteAudio(track)))
            }
        }

        override fun onDataChannel(channel: dev.onvoid.webrtc.RTCDataChannel) {
            val wrapped = DesktopDataChannel(channel)
            synchronized(lock) { dataChannels += wrapped }
            eventChannel.trySend(RtcEvent.RemoteDataChannel(wrapped))
        }
    }

    private val connection: RTCPeerConnection = factory.createPeerConnection(config, observer)
        ?: throw RtcException("Could not create the peer connection")

    override suspend fun createOffer(): SessionDescription =
        createDescription { connection.createOffer(RTCOfferOptions(), it) }

    override suspend fun createAnswer(): SessionDescription =
        createDescription { connection.createAnswer(RTCAnswerOptions(), it) }

    override suspend fun setLocalDescription(description: SessionDescription) =
        setDescription { connection.setLocalDescription(description.toNative(), it) }

    override suspend fun setRemoteDescription(description: SessionDescription) {
        setDescription { connection.setRemoteDescription(description.toNative(), it) }
        val queued = synchronized(lock) { pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() } }
        queued.forEach(connection::addIceCandidate)
    }

    override fun addRemoteIceCandidate(candidate: IceCandidate) {
        val native = RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        val queued = synchronized(lock) {
            if (connection.remoteDescription == null) pendingRemoteCandidates += native
            connection.remoteDescription == null
        }
        if (!queued) connection.addIceCandidate(native)
    }

    override fun createDataChannel(label: String): RtcDataChannel {
        val channel = connection.createDataChannel(label, RTCDataChannelInit())
        return DesktopDataChannel(channel).also { synchronized(lock) { dataChannels += it } }
    }

    override fun addMedia(media: CapturedMedia) {
        val desktop = media as? DesktopCapturedMedia ?: throw RtcException("Unsupported media")
        val init = RTCRtpTransceiverInit().apply {
            direction = RTCRtpTransceiverDirection.SEND_ONLY
            streamIds = listOf(STREAM_ID)
        }
        val transceiver = connection.addTransceiver(desktop.video, init)
        val sender = transceiver.sender
        synchronized(lock) { disposers += { sender.dispose(); transceiver.dispose() } }
        val parameters = sender.parameters
        parameters.encodings.forEach {
            it.maxBitrate = desktop.maxVideoBitrateBps
            it.maxFramerate = MAX_FRAMERATE
        }
        sender.parameters = parameters
        preferH264(transceiver)
        desktop.audio?.let { audio ->
            val audioInit = RTCRtpTransceiverInit().apply {
                direction = RTCRtpTransceiverDirection.SEND_ONLY
                streamIds = listOf(STREAM_ID)
            }
            val audioTransceiver = connection.addTransceiver(audio, audioInit)
            val audioSender = audioTransceiver.sender
            synchronized(lock) { disposers += { audioSender.dispose(); audioTransceiver.dispose() } }
        }
    }

    override fun restartIce() = connection.restartIce()

    override suspend fun stats(): StreamInfo? {
        if (isClosed) return null
        val report = suspendCancellableCoroutine { continuation -> connection.getStats { continuation.resume(it) } }
        val stats = report.stats.values
        val video = stats.firstOrNull { it.type == RTCStatsType.INBOUND_RTP && it.attributes["kind"] == "video" }?.attributes
            ?: return null
        val width = (video["frameWidth"] as? Number)?.toInt() ?: return null
        val height = (video["frameHeight"] as? Number)?.toInt() ?: return null
        val roundTrip = stats.firstOrNull { it.type == RTCStatsType.CANDIDATE_PAIR && it.attributes["nominated"] == true }
            ?.attributes?.get("currentRoundTripTime") as? Number
        return StreamInfo(
            width = width,
            height = height,
            framesPerSecond = (video["framesPerSecond"] as? Number)?.toInt(),
            roundTripMillis = roundTrip?.let { (it.toDouble() * 1000).toInt() },
        )
    }

    override fun close() {
        val (channels, native) = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            dataChannels.toList().also { dataChannels.clear() } to disposers.toList().also { disposers.clear() }
        }
        eventChannel.close()
        channels.forEach(DesktopDataChannel::close)
        connection.close()
        native.forEach { runCatching(it) }
    }

    private fun preferH264(transceiver: RTCRtpTransceiver) {
        val receivable = factory.getRtpReceiverCapabilities(MediaType.VIDEO).codecs
        val codecs = factory.getRtpSenderCapabilities(MediaType.VIDEO).codecs
            .filter { sent -> receivable.any { it.mimeType == sent.mimeType && it.getSDPFmtp() == sent.getSDPFmtp() } }
            .sortedBy { if (it.name.equals(H264, ignoreCase = true)) 0 else 1 }
        if (codecs.none { it.name.equals(H264, ignoreCase = true) }) return
        runCatching { transceiver.codecPreferences = codecs }
    }

    private suspend fun createDescription(start: (CreateSessionDescriptionObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            start(object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) = continuation.resume(description.toCommon())

                override fun onFailure(error: String?) =
                    continuation.resumeWithException(RtcException("Creating the description failed: $error"))
            })
        }

    private suspend fun setDescription(start: (SetSessionDescriptionObserver) -> Unit): Unit =
        suspendCancellableCoroutine { continuation ->
            start(object : SetSessionDescriptionObserver {
                override fun onSuccess() = continuation.resume(Unit)

                override fun onFailure(error: String?) =
                    continuation.resumeWithException(RtcException("Setting the description failed: $error"))
            })
        }

    private fun SessionDescription.toNative() = RTCSessionDescription(
        when (type) {
            SdpType.OFFER -> RTCSdpType.OFFER
            SdpType.ANSWER -> RTCSdpType.ANSWER
            SdpType.ROLLBACK -> RTCSdpType.ROLLBACK
        },
        sdp,
    )

    private fun RTCSessionDescription.toCommon() = SessionDescription(
        when (sdpType) {
            RTCSdpType.OFFER -> SdpType.OFFER
            RTCSdpType.ANSWER, RTCSdpType.PR_ANSWER -> SdpType.ANSWER
            RTCSdpType.ROLLBACK -> SdpType.ROLLBACK
        },
        sdp,
    )

    private fun RTCPeerConnectionState.toCommon() = when (this) {
        RTCPeerConnectionState.NEW -> PeerConnectionState.NEW
        RTCPeerConnectionState.CONNECTING -> PeerConnectionState.CONNECTING
        RTCPeerConnectionState.CONNECTED -> PeerConnectionState.CONNECTED
        RTCPeerConnectionState.DISCONNECTED -> PeerConnectionState.DISCONNECTED
        RTCPeerConnectionState.FAILED -> PeerConnectionState.FAILED
        RTCPeerConnectionState.CLOSED -> PeerConnectionState.CLOSED
    }

    private companion object {
        const val STREAM_ID = "screen"
        const val H264 = "H264"
        const val MAX_FRAMERATE = 30.0
    }
}
