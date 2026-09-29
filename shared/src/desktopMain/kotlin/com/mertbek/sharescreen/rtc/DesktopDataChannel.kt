package com.mertbek.sharescreen.rtc

import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.ByteBuffer

class DesktopDataChannel(private val channel: RTCDataChannel) : RtcDataChannel {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(map(channel.state))

    override val label: String = channel.label
    override val state: StateFlow<DataChannelState> = _state
    override val messages: Flow<String> = incoming.receiveAsFlow()

    init {
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                update(map(channel.state))
            }

            override fun onMessage(buffer: RTCDataChannelBuffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining()).also(buffer.data::get)
                incoming.trySend(bytes.decodeToString())
            }
        })
    }

    override fun send(text: String): Boolean {
        if (_state.value != DataChannelState.OPEN) return false
        return try {
            channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(text.encodeToByteArray()), false))
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        if (_state.value == DataChannelState.CLOSED) return
        _state.value = DataChannelState.CLOSED
        incoming.close()
        runCatching {
            channel.unregisterObserver()
            channel.close()
            channel.dispose()
        }
    }

    private fun update(state: DataChannelState) {
        if (_state.value == DataChannelState.CLOSED) return
        _state.value = state
        if (state == DataChannelState.CLOSED) incoming.close()
    }

    private fun map(state: RTCDataChannelState) = when (state) {
        RTCDataChannelState.CONNECTING -> DataChannelState.CONNECTING
        RTCDataChannelState.OPEN -> DataChannelState.OPEN
        RTCDataChannelState.CLOSING, RTCDataChannelState.CLOSED -> DataChannelState.CLOSED
    }
}
