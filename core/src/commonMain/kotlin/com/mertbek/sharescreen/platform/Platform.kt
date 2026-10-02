package com.mertbek.sharescreen.platform

import com.mertbek.sharescreen.control.ControlKey
import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.control.NavAction
import com.mertbek.sharescreen.control.TouchPointer
import com.mertbek.sharescreen.rtc.CapturedMedia
import com.mertbek.sharescreen.settings.VideoQuality
import kotlinx.coroutines.flow.StateFlow

class DeviceName(val value: String)

interface LocalAddressProvider {
    fun ipv4Addresses(): List<String>
}

interface LanAdvertiser {
    fun register(name: String, port: Int, pinRequired: Boolean, id: String?)
    fun unregister()
}

/**
 * A host that does not say whether it needs a PIN runs an older version, which always does.
 * The [id] tells a viewer which host remembers it.
 */
data class DiscoveredHost(val name: String, val host: String, val port: Int, val pinRequired: Boolean = true, val id: String? = null)

interface LanBrowser {
    val hosts: StateFlow<List<DiscoveredHost>>
    fun start()
    fun stop()
}

interface LanServer {
    suspend fun start(hostSecret: String): Int
    suspend fun stop()
}

interface InputInjector {
    val platform: HostPlatform
    val isAvailable: StateFlow<Boolean>

    fun touch(time: Long, pointers: List<TouchPointer>)
    fun navigate(action: NavAction)
    fun type(deleteBefore: Int, text: String)
    fun press(key: ControlKey)
    fun pointer(message: ControlMessage.Pointer)
    fun keyboard(message: ControlMessage.Keyboard)
    fun releaseInput()
}

interface ScreenSource {
    var onEnded: (() -> Unit)?
        get() = null
        set(_) = Unit

    suspend fun start(quality: VideoQuality, shareAudio: Boolean, wholeScreenOnly: Boolean): CapturedMedia?
    fun stop()
}
