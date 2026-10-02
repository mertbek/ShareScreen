package com.mertbek.sharescreen.control

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val CONTROL_CHANNEL_LABEL = "control"

@Serializable
enum class ControlRole {
    @SerialName("none") NONE,
    @SerialName("requested") REQUESTED,
    @SerialName("granted") GRANTED,
}

@Serializable
enum class NavAction {
    @SerialName("back") BACK,
    @SerialName("home") HOME,
    @SerialName("recents") RECENTS,
}

@Serializable
enum class ControlKey {
    @SerialName("enter") ENTER,
}

@Serializable
enum class HostPlatform {
    @SerialName("android") ANDROID,
    @SerialName("desktop") DESKTOP,
}

@Serializable
enum class PointerAction {
    @SerialName("move") MOVE,
    @SerialName("down") DOWN,
    @SerialName("up") UP,
    @SerialName("scroll") SCROLL,
}

@Serializable
enum class PointerButton {
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("middle") MIDDLE,
}

@Serializable
data class TouchPointer(val id: Long, val x: Float, val y: Float)

@Serializable
sealed interface ControlMessage {

    @Serializable
    @SerialName("status")
    data class Status(
        val available: Boolean,
        val role: ControlRole,
        val platform: HostPlatform = HostPlatform.ANDROID,
    ) : ControlMessage

    @Serializable
    @SerialName("pointer")
    data class Pointer(
        val action: PointerAction,
        val x: Float,
        val y: Float,
        val button: PointerButton = PointerButton.LEFT,
        val scrollX: Float = 0f,
        val scrollY: Float = 0f,
    ) : ControlMessage

    @Serializable
    @SerialName("keyboard")
    data class Keyboard(
        val key: String,
        val down: Boolean,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
        val meta: Boolean = false,
    ) : ControlMessage

    @Serializable
    @SerialName("request")
    data object Request : ControlMessage

    @Serializable
    @SerialName("release")
    data object Release : ControlMessage

    @Serializable
    @SerialName("touch")
    data class Touch(val time: Long, val pointers: List<TouchPointer>) : ControlMessage

    @Serializable
    @SerialName("navigate")
    data class Navigate(val action: NavAction) : ControlMessage

    @Serializable
    @SerialName("type")
    data class Type(val deleteBefore: Int = 0, val text: String = "") : ControlMessage

    @Serializable
    @SerialName("key")
    data class Press(val key: ControlKey) : ControlMessage

    /** The host remembers the viewer, which can come back on the local network without asking. */
    @Serializable
    @SerialName("remember")
    data class Remember(val hostId: String, val key: String, val secret: String) : ControlMessage
}

object ControlCodec {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: ControlMessage): String = json.encodeToString(ControlMessage.serializer(), message)

    fun decode(text: String): ControlMessage? = try {
        json.decodeFromString(ControlMessage.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
