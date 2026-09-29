package com.mertbek.sharescreen.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.mertbek.sharescreen.app.App
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.config.ServerConfig
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.rtc.WebRtcEngine
import com.mertbek.sharescreen.rtc.WebScreenSource
import com.mertbek.sharescreen.settings.SettingsRepository
import com.russhwolf.settings.StorageSettings

private fun deviceName(): String {
    val agent = userAgent()
    val browser = when {
        "Edg/" in agent -> "Edge"
        "Firefox/" in agent -> "Firefox"
        "Chrome/" in agent -> "Chrome"
        "Safari/" in agent -> "Safari"
        else -> "Browser"
    }
    return "$browser (web)"
}

private fun createServices() = AppServices(
    rtc = WebRtcEngine(),
    deviceName = DeviceName(deviceName()),
    settings = SettingsRepository(StorageSettings(), ServerConfig.defaultServer),
    ui = WebUi(),
    screenSource = WebScreenSource(),
    versionName = ServerConfig.versionName,
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val services = createServices()
    val initialLink = ConnectLink.parse(pageUrl())
    if (initialLink != null) clearPageFragment()
    ComposeViewport {
        LaunchedEffect(Unit) { hideSplash() }
        var link by remember { mutableStateOf(initialLink) }
        App(services, incomingLink = link, onIncomingLinkHandled = { link = null })
    }
}
