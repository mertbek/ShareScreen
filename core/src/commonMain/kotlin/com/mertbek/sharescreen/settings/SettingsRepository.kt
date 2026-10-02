package com.mertbek.sharescreen.settings

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VideoQuality(val maxLongEdge: Int, val maxBitrateBps: Int) {
    LOW(maxLongEdge = 854, maxBitrateBps = 1_500_000),
    STANDARD(maxLongEdge = 1280, maxBitrateBps = 4_000_000),
    HIGH(maxLongEdge = 1920, maxBitrateBps = 8_000_000),
}

data class SharingSettings(
    val quality: VideoQuality = VideoQuality.STANDARD,
    val shareAudio: Boolean = true,
    val internetEnabled: Boolean = true,
    val customServer: String? = null,
    val defaultServer: String? = null,
    val allowRemoteControl: Boolean = false,
    val lanPin: Boolean = false,
) {
    val internetServer: String? get() = if (internetEnabled) customServer ?: defaultServer else null
}

class SettingsRepository(private val store: Settings, private val defaultServer: String? = null) {

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SharingSettings> = _settings.asStateFlow()

    fun setQuality(quality: VideoQuality) = change { store.putString(QUALITY, quality.name) }

    fun setShareAudio(shareAudio: Boolean) = change { store.putBoolean(SHARE_AUDIO, shareAudio) }

    fun setAllowRemoteControl(allow: Boolean) = change { store.putBoolean(ALLOW_REMOTE_CONTROL, allow) }

    fun setInternetEnabled(enabled: Boolean) = change { store.putBoolean(INTERNET_ENABLED, enabled) }

    fun setLanPin(required: Boolean) = change { store.putBoolean(LAN_PIN, required) }

    fun setCustomServer(server: String?) = change {
        if (server == null) store.remove(INTERNET_SERVER) else store.putString(INTERNET_SERVER, server)
    }

    private fun change(edit: () -> Unit) {
        edit()
        _settings.update { read() }
    }

    private fun read() = SharingSettings(
        quality = store.getStringOrNull(QUALITY)
            ?.let { name -> VideoQuality.entries.find { it.name == name } }
            ?: VideoQuality.STANDARD,
        shareAudio = store.getBoolean(SHARE_AUDIO, true),
        internetEnabled = store.getBoolean(INTERNET_ENABLED, true),
        customServer = store.getStringOrNull(INTERNET_SERVER),
        defaultServer = defaultServer,
        allowRemoteControl = store.getBoolean(ALLOW_REMOTE_CONTROL, false),
        lanPin = store.getBoolean(LAN_PIN, false),
    )

    private companion object {
        const val QUALITY = "video_quality"
        const val SHARE_AUDIO = "share_audio"
        const val INTERNET_ENABLED = "internet_enabled"
        const val INTERNET_SERVER = "internet_server"
        const val ALLOW_REMOTE_CONTROL = "allow_remote_control"
        const val LAN_PIN = "lan_pin"
    }
}
