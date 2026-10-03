@file:OptIn(ExperimentalSerializationApi::class)

package com.mertbek.sharescreen.signaling

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1
const val SIGNALING_PATH = "/ws"

@Serializable
enum class PeerRole {
    @SerialName("host") HOST,
    @SerialName("viewer") VIEWER,
}

@Serializable
enum class ErrorCode {
    @SerialName("protocol") PROTOCOL,
    @SerialName("unsupported_version") UNSUPPORTED_VERSION,
    @SerialName("unauthorized") UNAUTHORIZED,
    @SerialName("host_exists") HOST_EXISTS,
    @SerialName("room_not_found") ROOM_NOT_FOUND,
    @SerialName("invalid_pin") INVALID_PIN,
    @SerialName("too_many_attempts") TOO_MANY_ATTEMPTS,
    @SerialName("room_full") ROOM_FULL,
    @SerialName("rejected") REJECTED,
    @SerialName("kicked") KICKED,
    @SerialName("resume_failed") RESUME_FAILED,
}

/**
 * Shows that a host remembered this viewer: a counter that only grows, signed with the secret the
 * host handed over, so a copied pass cannot be used again.
 */
@Serializable
data class DevicePass(val key: String, val counter: Long, val proof: String)

interface Routed {
    val from: String
    val to: String
    fun withFrom(from: String): SignalMessage
}

@Serializable
sealed interface SignalMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val role: PeerRole,
        val deviceName: String,
        val pin: String? = null,
        val roomCode: String? = null,
        val hostSecret: String? = null,
        val resumeToken: String? = null,
        val protocolVersion: Int = PROTOCOL_VERSION,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val pass: DevicePass? = null,
    ) : SignalMessage

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val peerId: String,
        val roomCode: String,
        val hostId: String,
        val iceServers: List<IceServerConfig> = emptyList(),
        val resumeToken: String? = null,
        val resumed: Boolean = false,
    ) : SignalMessage

    @Serializable
    @SerialName("join_request")
    data class JoinRequest(
        val viewerId: String,
        val deviceName: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val pass: DevicePass? = null,
    ) : SignalMessage

    @Serializable
    @SerialName("join_decision")
    data class JoinDecision(val viewerId: String, val accepted: Boolean) : SignalMessage

    @Serializable
    @SerialName("kick")
    data class Kick(val viewerId: String) : SignalMessage

    @Serializable
    @SerialName("offer")
    data class Offer(
        override val to: String,
        val sdp: String,
        override val from: String = "",
    ) : SignalMessage, Routed {
        override fun withFrom(from: String) = copy(from = from)
    }

    @Serializable
    @SerialName("answer")
    data class Answer(
        override val to: String,
        val sdp: String,
        override val from: String = "",
    ) : SignalMessage, Routed {
        override fun withFrom(from: String) = copy(from = from)
    }

    @Serializable
    @SerialName("ice")
    data class Ice(
        override val to: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        override val from: String = "",
    ) : SignalMessage, Routed {
        override fun withFrom(from: String) = copy(from = from)
    }

    @Serializable
    @SerialName("peer_left")
    data class PeerLeft(val peerId: String) : SignalMessage

    @Serializable
    @SerialName("session_ended")
    data object SessionEnded : SignalMessage

    @Serializable
    @SerialName("leave")
    data object Leave : SignalMessage

    @Serializable
    @SerialName("ping")
    data object Ping : SignalMessage

    @Serializable
    @SerialName("pong")
    data object Pong : SignalMessage

    @Serializable
    @SerialName("error")
    data class Error(val code: ErrorCode, val message: String = "") : SignalMessage
}

object SignalCodec {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: SignalMessage): String = json.encodeToString(SignalMessage.serializer(), message)

    fun decode(text: String): SignalMessage = json.decodeFromString(SignalMessage.serializer(), text)
}
