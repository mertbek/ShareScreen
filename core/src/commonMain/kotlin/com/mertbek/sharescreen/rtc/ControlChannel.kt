package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.control.ControlCodec
import com.mertbek.sharescreen.control.ControlMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

class ControlChannel(private val channel: RtcDataChannel) {

    val messages: Flow<ControlMessage> = channel.messages.mapNotNull(ControlCodec::decode)

    suspend fun awaitOpen(): Boolean =
        channel.state.first { it != DataChannelState.CONNECTING } == DataChannelState.OPEN

    fun send(message: ControlMessage): Boolean = channel.send(ControlCodec.encode(message))

    fun close() = channel.close()
}
