package com.mertbek.sharescreen.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.mertbek.sharescreen.app.App
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.config.ServerConfig
import com.mertbek.sharescreen.control.DesktopInputInjector
import com.mertbek.sharescreen.lan.EmbeddedLanServer
import com.mertbek.sharescreen.lan.JmDnsAdvertiser
import com.mertbek.sharescreen.lan.JmDnsBrowser
import com.mertbek.sharescreen.lan.NetworkAddresses
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.rtc.DesktopRtcEngine
import com.mertbek.sharescreen.rtc.DesktopScreenCapture
import com.mertbek.sharescreen.rtc.DesktopScreenSource
import com.mertbek.sharescreen.settings.SettingsRepository
import com.russhwolf.settings.PreferencesSettings
import java.net.InetAddress
import java.util.prefs.Preferences

private fun createServices(): AppServices {
    val rtc = DesktopRtcEngine()
    val addresses = NetworkAddresses()
    val name = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop")
    return AppServices(
        rtc = rtc,
        deviceName = DeviceName(name),
        settings = SettingsRepository(
            PreferencesSettings(Preferences.userRoot().node("com/mertbek/sharescreen")),
            ServerConfig.defaultServer,
        ),
        ui = DesktopUi(),
        screenSource = DesktopScreenSource(DesktopScreenCapture(rtc)),
        lanServer = EmbeddedLanServer(),
        localAddresses = addresses,
        lanAdvertiser = JmDnsAdvertiser(addresses),
        lanBrowser = JmDnsBrowser(addresses),
        inputInjector = DesktopInputInjector(),
    )
}

fun main() = application {
    val services = remember { createServices() }
    val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
    Window(onCloseRequest = ::exitApplication, title = "ShareScreen", icon = icon) {
        App(services)
    }
}
