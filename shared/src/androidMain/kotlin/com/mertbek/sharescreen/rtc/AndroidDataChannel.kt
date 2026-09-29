package com.mertbek.sharescreen.rtc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.webrtc.DataChannel
import java.nio.ByteBuffer

class AndroidDataChannel(private val channel: DataChannel) : RtcDataChannel {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(map(channel.state()))

    override val label: String = channel.label()
    override val state: StateFlow<DataChannelState> = _state
    override val messages: Flow<String> = incoming.receiveAsFlow()

    init {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() = update(map(channel.state()))

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining()).also(buffer.data::get)
                incoming.trySend(bytes.decodeToString())
            }
        })
    }

    override fun send(text: String): Boolean {
        if (_state.value != DataChannelState.OPEN) return false
        return try {
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.encodeToByteArray()), false))
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun close() {
        if (_state.value == DataChannelState.CLOSED) return
        _state.value = DataChannelState.CLOSED
        incoming.close()
        runCatching {
            channel.unregisterObserver()
            channel.dispose()
        }
    }

    private fun update(state: DataChannelState) {
        if (_state.value == DataChannelState.CLOSED) return
        _state.value = state
        if (state == DataChannelState.CLOSED) incoming.close()
    }

    private fun map(state: DataChannel.State) = when (state) {
        DataChannel.State.CONNECTING -> DataChannelState.CONNECTING
        DataChannel.State.OPEN -> DataChannelState.OPEN
        DataChannel.State.CLOSING, DataChannel.State.CLOSED -> DataChannelState.CLOSED
    }
}
