package com.mertbek.sharescreen.server

import com.mertbek.sharescreen.rtc.CapturedMedia
import com.mertbek.sharescreen.rtc.DataChannelState
import com.mertbek.sharescreen.rtc.RemoteAudio
import com.mertbek.sharescreen.rtc.RemoteVideo
import com.mertbek.sharescreen.rtc.RtcDataChannel
import com.mertbek.sharescreen.rtc.RtcEngine
import com.mertbek.sharescreen.rtc.RtcPeer
import com.mertbek.sharescreen.signaling.IceServerConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeMedia(override val hasAudio: Boolean = false) : CapturedMedia {
    override val width = 1280
    override val height = 720
    override val maxVideoBitrateBps = 4_000_000
    override val preview: RemoteVideo? = null
}

class FakeVideo : RemoteVideo

class FakeAudio : RemoteAudio {
    override fun setEnabled(enabled: Boolean) = Unit
}

class FakeRtcEngine : RtcEngine {
    private val ids = AtomicInteger()
    val descriptions = ConcurrentHashMap<String, FakePeer>()
    val peers = mutableListOf<FakePeer>()

    override fun createPeer(iceServers: List<IceServerConfig>): RtcPeer =
        FakePeer(this, ids.incrementAndGet()).also { synchronized(peers) { peers += it } }
}

class FakeChannel(override val label: String) : RtcDataChannel {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(DataChannelState.CONNECTING)
    var other: FakeChannel? = null

    override val state: StateFlow<DataChannelState> = _state
    override val messages: Flow<String> = incoming.receiveAsFlow()

    fun open() {
        _state.value = DataChannelState.OPEN
    }

    override fun send(text: String): Boolean {
        if (_state.value != DataChannelState.OPEN) return false
        return other?.incoming?.trySend(text)?.isSuccess == true
    }

    override fun close() {
        _state.value = DataChannelState.CLOSED
        incoming.close()
    }
}
