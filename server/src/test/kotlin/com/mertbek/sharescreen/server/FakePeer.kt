package com.mertbek.sharescreen.server

import com.mertbek.sharescreen.rtc.CapturedMedia
import com.mertbek.sharescreen.rtc.IceCandidate
import com.mertbek.sharescreen.rtc.PeerConnectionState
import com.mertbek.sharescreen.rtc.RtcDataChannel
import com.mertbek.sharescreen.rtc.RtcEvent
import com.mertbek.sharescreen.rtc.RtcPeer
import com.mertbek.sharescreen.rtc.SdpType
import com.mertbek.sharescreen.rtc.SessionDescription
import com.mertbek.sharescreen.rtc.StreamInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakePeer(private val engine: FakeRtcEngine, private val id: Int) : RtcPeer {
    private val eventChannel = Channel<RtcEvent>(Channel.UNLIMITED)
    private val channels = mutableListOf<FakeChannel>()
    private var remote: FakePeer? = null
    private var media: CapturedMedia? = null

    override val events: Flow<RtcEvent> = eventChannel.receiveAsFlow()

    @Volatile
    override var isClosed = false
        private set

    @Volatile
    override var hasLocalOffer = false
        private set

    val addedMedia: CapturedMedia? get() = media

    override suspend fun createOffer() = SessionDescription(SdpType.OFFER, "offer:$id")

    override suspend fun createAnswer() = SessionDescription(SdpType.ANSWER, "answer:$id")

    override suspend fun setLocalDescription(description: SessionDescription) {
        hasLocalOffer = description.type == SdpType.OFFER
        if (description.type != SdpType.ROLLBACK) engine.descriptions[description.sdp] = this
    }

    override suspend fun setRemoteDescription(description: SessionDescription) {
        remote = engine.descriptions[description.sdp]
        if (description.type == SdpType.ANSWER) {
            hasLocalOffer = false
            connectWithRemote()
        }
    }

    override fun addRemoteIceCandidate(candidate: IceCandidate) = Unit

    override fun createDataChannel(label: String): RtcDataChannel = FakeChannel(label).also { channels += it }

    override fun addMedia(media: CapturedMedia) {
        this.media = media
    }

    override fun restartIce() = Unit

    override suspend fun stats() = StreamInfo(1280, 720, 30, 5)

    override fun close() {
        isClosed = true
        eventChannel.close()
    }

    private fun connectWithRemote() {
        val viewer = remote ?: return
        for (channel in channels) {
            val counterpart = FakeChannel(channel.label)
            channel.other = counterpart
            counterpart.other = channel
            channel.open()
            counterpart.open()
            viewer.eventChannel.trySend(RtcEvent.RemoteDataChannel(counterpart))
        }
        if (media != null) viewer.eventChannel.trySend(RtcEvent.RemoteVideoTrack(FakeVideo()))
        if (media?.hasAudio == true) viewer.eventChannel.trySend(RtcEvent.RemoteAudioTrack(FakeAudio()))
        viewer.eventChannel.trySend(RtcEvent.ConnectionState(PeerConnectionState.CONNECTED))
        eventChannel.trySend(RtcEvent.ConnectionState(PeerConnectionState.CONNECTED))
    }
}
