package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.signaling.IceServerConfig
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.js.JsAny

class WebRtcEngine : RtcEngine {
    override fun createPeer(iceServers: List<IceServerConfig>): RtcPeer = WebPeer(iceServers)
}

private val iceJson = Json { explicitNulls = false }

class WebPeer(iceServers: List<IceServerConfig>) : RtcPeer {
    private val connection: JsAny =
        newPeerConnection(iceJson.encodeToString(ListSerializer(IceServerConfig.serializer()), iceServers))
    private val eventChannel = Channel<RtcEvent>(Channel.UNLIMITED)
    private val dataChannels = mutableListOf<WebDataChannel>()
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteAudio: JsAny? = null

    override val events: Flow<RtcEvent> = eventChannel.receiveAsFlow()

    override var isClosed = false
        private set

    override val hasLocalOffer: Boolean
        get() = !isClosed && pcSignalingState(connection) == "have-local-offer"

    init {
        pcOnIceCandidate(connection) { mid, index, candidate ->
            eventChannel.trySend(RtcEvent.LocalIceCandidate(IceCandidate(mid.ifEmpty { null }, index, candidate)))
        }
        pcOnConnectionState(connection) { state ->
            eventChannel.trySend(RtcEvent.ConnectionState(connectionState(state)))
        }
        pcOnTrack(connection) { kind, track, stream ->
            when (kind) {
                "video" -> eventChannel.trySend(RtcEvent.RemoteVideoTrack(WebRemoteVideo(stream)))
                "audio" -> eventChannel.trySend(RtcEvent.RemoteAudioTrack(WebRemoteAudio(track)))
            }
        }
        pcOnDataChannel(connection) { channel ->
            if (isClosed) {
                dcClose(channel)
            } else {
                val wrapped = WebDataChannel(channel)
                dataChannels += wrapped
                eventChannel.trySend(RtcEvent.RemoteDataChannel(wrapped))
            }
        }
    }

    override suspend fun createOffer(): SessionDescription =
        SessionDescription(SdpType.OFFER, descriptionSdp(pcCreateOffer(connection).await()!!))

    override suspend fun createAnswer(): SessionDescription =
        SessionDescription(SdpType.ANSWER, descriptionSdp(pcCreateAnswer(connection).await()!!))

    override suspend fun setLocalDescription(description: SessionDescription) {
        pcSetLocalDescription(connection, description.type.wireName(), description.sdp).await<JsAny?>()
    }

    override suspend fun setRemoteDescription(description: SessionDescription) {
        pcSetRemoteDescription(connection, description.type.wireName(), description.sdp).await<JsAny?>()
        val queued = pendingCandidates.toList()
        pendingCandidates.clear()
        queued.forEach(::addCandidate)
    }

    override fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (isClosed) return
        if (pcHasRemoteDescription(connection)) addCandidate(candidate) else pendingCandidates += candidate
    }

    override fun createDataChannel(label: String): RtcDataChannel {
        if (isClosed) throw RtcException("The peer is closed")
        val wrapped = WebDataChannel(pcCreateDataChannel(connection, label))
        dataChannels += wrapped
        return wrapped
    }

    override fun addMedia(media: CapturedMedia) {
        val web = media as? WebCapturedMedia ?: throw RtcException("Unsupported media")
        pcAddSendingStream(connection, web.stream, web.maxVideoBitrateBps)
    }

    override fun restartIce() = pcRestartIce(connection)

    override suspend fun stats(): StreamInfo? {
        if (isClosed) return null
        val parts = pcStats(connection).await<kotlin.js.JsString>().toString().split('|')
        val width = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val height = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (width == 0 || height == 0) return null
        return StreamInfo(
            width = width,
            height = height,
            framesPerSecond = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 },
            roundTripMillis = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it >= 0 },
        )
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        eventChannel.close()
        dataChannels.forEach(WebDataChannel::close)
        dataChannels.clear()
        pcClose(connection)
    }

    private fun addCandidate(candidate: IceCandidate) =
        pcAddIceCandidate(connection, candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex, candidate.candidate)

    private fun SdpType.wireName() = when (this) {
        SdpType.OFFER -> "offer"
        SdpType.ANSWER -> "answer"
        SdpType.ROLLBACK -> "rollback"
    }

    private fun connectionState(state: String) = when (state) {
        "connecting" -> PeerConnectionState.CONNECTING
        "connected" -> PeerConnectionState.CONNECTED
        "disconnected" -> PeerConnectionState.DISCONNECTED
        "failed" -> PeerConnectionState.FAILED
        "closed" -> PeerConnectionState.CLOSED
        else -> PeerConnectionState.NEW
    }
}

class WebDataChannel(private val channel: JsAny) : RtcDataChannel {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(map(dcState(channel)))

    override val label: String = dcLabel(channel)
    override val state: StateFlow<DataChannelState> = _state
    override val messages: Flow<String> = incoming.receiveAsFlow()

    init {
        dcOnState(channel) { update(map(dcState(channel))) }
        dcOnMessage(channel) { incoming.trySend(it) }
    }

    override fun send(text: String): Boolean = _state.value == DataChannelState.OPEN && dcSend(channel, text)

    override fun close() {
        if (_state.value == DataChannelState.CLOSED) return
        _state.value = DataChannelState.CLOSED
        incoming.close()
        dcClose(channel)
    }

    private fun update(state: DataChannelState) {
        if (_state.value == DataChannelState.CLOSED) return
        _state.value = state
        if (state == DataChannelState.CLOSED) incoming.close()
    }

    private fun map(state: String) = when (state) {
        "open" -> DataChannelState.OPEN
        "connecting" -> DataChannelState.CONNECTING
        else -> DataChannelState.CLOSED
    }
}
