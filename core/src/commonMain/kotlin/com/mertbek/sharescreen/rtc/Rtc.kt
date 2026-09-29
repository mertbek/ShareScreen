package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.signaling.IceServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class RtcException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class SdpType { OFFER, ANSWER, ROLLBACK }

data class SessionDescription(val type: SdpType, val sdp: String)

data class IceCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

enum class PeerConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

data class StreamStats(val width: Int, val height: Int, val framesPerSecond: Int?, val roundTripMillis: Int?)

interface CapturedMedia {
    val width: Int
    val height: Int
    val hasAudio: Boolean
    val maxVideoBitrateBps: Int
}

interface RemoteVideo

interface RemoteAudio {
    fun setEnabled(enabled: Boolean)
}

sealed interface RtcEvent {
    data class LocalIceCandidate(val candidate: IceCandidate) : RtcEvent
    data class ConnectionState(val state: PeerConnectionState) : RtcEvent
    data class RemoteVideoTrack(val track: RemoteVideo) : RtcEvent
    data class RemoteAudioTrack(val track: RemoteAudio) : RtcEvent
    data class RemoteDataChannel(val channel: RtcDataChannel) : RtcEvent
}

enum class DataChannelState { CONNECTING, OPEN, CLOSED }

interface RtcDataChannel {
    val label: String
    val state: StateFlow<DataChannelState>
    val messages: Flow<String>
    fun send(text: String): Boolean
    fun close()
}

interface RtcPeer {
    val events: Flow<RtcEvent>
    val isClosed: Boolean
    val hasLocalOffer: Boolean

    suspend fun createOffer(): SessionDescription
    suspend fun createAnswer(): SessionDescription
    suspend fun setLocalDescription(description: SessionDescription)
    suspend fun setRemoteDescription(description: SessionDescription)
    fun addRemoteIceCandidate(candidate: IceCandidate)
    fun createDataChannel(label: String): RtcDataChannel
    fun addMedia(media: CapturedMedia)
    fun restartIce()
    suspend fun stats(): StreamStats?
    fun close()
}

interface RtcEngine {
    val logsStats: Boolean
    fun createPeer(iceServers: List<IceServerConfig>): RtcPeer
}
