package com.mertbek.sharescreen.session

import com.mertbek.sharescreen.control.ControlRole

data class PendingViewer(val id: String, val deviceName: String, val viaInternet: Boolean = false)

data class ViewerInfo(
    val id: String,
    val deviceName: String,
    val isConnected: Boolean,
    val viaInternet: Boolean = false,
    val control: ControlRole = ControlRole.NONE,
    /** This host remembers the viewer, which can come back without asking. */
    val remembered: Boolean = false,
)

enum class RemoteControlAvailability {
    OFF,
    NEEDS_SERVICE,
    READY,
}

sealed interface InternetRoom {
    val server: String

    data class Connecting(override val server: String) : InternetRoom
    data class Open(override val server: String, val roomCode: String, val reconnecting: Boolean = false) : InternetRoom
    data class Failed(override val server: String) : InternetRoom
    data class Closed(override val server: String) : InternetRoom
}

sealed interface HostState {
    data object Idle : HostState
    data object Starting : HostState
    data class Live(
        val deviceName: String,
        val addresses: List<String>,
        val port: Int,
        val pin: String,
        /** Whether viewers on the local network need the PIN too; over the internet they always do. */
        val lanPin: Boolean = false,
        val internetRoom: InternetRoom? = null,
        val viewers: List<ViewerInfo> = emptyList(),
        val pendingViewers: List<PendingViewer> = emptyList(),
        val remoteControl: RemoteControlAvailability = RemoteControlAvailability.OFF,
    ) : HostState {
        val controller: ViewerInfo? get() = viewers.find { it.control == ControlRole.GRANTED }
    }
    data class Failed(val message: String) : HostState
}
