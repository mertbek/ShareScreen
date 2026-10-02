package com.mertbek.sharescreen.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mertbek.sharescreen.app.App
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.config.ServerConfig
import com.mertbek.sharescreen.control.DesktopInputInjector
import com.mertbek.sharescreen.lan.EmbeddedLanServer
import com.mertbek.sharescreen.lan.JmDnsAdvertiser
import com.mertbek.sharescreen.lan.JmDnsBrowser
import com.mertbek.sharescreen.lan.NetworkAddresses
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.rtc.DesktopRtcEngine
import com.mertbek.sharescreen.rtc.DesktopScreenCapture
import com.mertbek.sharescreen.rtc.DesktopScreenSource
import com.mertbek.sharescreen.settings.RememberedDevices
import com.mertbek.sharescreen.settings.SettingsRepository
import com.mertbek.sharescreen.ui.host.RequestWindow
import com.russhwolf.settings.PreferencesSettings
import kotlinx.coroutines.channels.Channel
import java.awt.Desktop
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.net.InetAddress
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.concurrent.thread
import kotlin.io.path.Path

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
        rememberedDevices = RememberedDevices(PreferencesSettings(Preferences.userRoot().node("com/mertbek/sharescreen/remembered"))),
        webApp = ServerConfig.webApp,
        versionName = ServerConfig.versionName,
    )
}

private fun appDirectory(): Path {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("windows") -> Path(System.getenv("APPDATA") ?: home, "ShareScreen")
        os.startsWith("mac") -> Path(home, "Library", "Application Support", "ShareScreen")
        else -> Path(System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/state", "sharescreen")
    }
}

fun main(args: Array<String>) {
    val directory = appDirectory()
    val launchLink = args.firstOrNull { ConnectLink.parse(it) != null }
    val inbox = LinkInbox.open(directory)
    if (inbox == null && launchLink != null && LinkInbox.forward(directory, launchLink)) return
    thread(isDaemon = true, name = "LinkScheme") { LinkScheme.register() }

    val links = Channel<ConnectLink>(Channel.UNLIMITED)
    val receive = { value: String -> ConnectLink.parse(value)?.let { links.trySend(it) } }
    launchLink?.let(receive)
    inbox?.listen { receive(it) }
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
        Desktop.getDesktop().setOpenURIHandler { receive(it.uri.toString()) }
    }

    application {
        val services = remember { createServices() }
        val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
        val windowState = rememberWindowState()
        var incomingLink by remember { mutableStateOf<ConnectLink?>(null) }
        var appInFront by remember { mutableStateOf(true) }
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "ShareScreen", icon = icon) {
            DisposableEffect(window) {
                val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                // The app's own dialogs count as the app being in front.
                fun update() {
                    val active = focus.activeWindow
                    appInFront = active != null && (active === window || active.owner === window)
                }
                val listener = PropertyChangeListener { update() }
                focus.addPropertyChangeListener("activeWindow", listener)
                update()
                onDispose { focus.removePropertyChangeListener("activeWindow", listener) }
            }
            LaunchedEffect(Unit) {
                for (link in links) {
                    incomingLink = link
                    windowState.isMinimized = false
                    window.toFront()
                }
            }
            App(services, incomingLink = incomingLink, onIncomingLinkHandled = { incomingLink = null })
        }
        RequestWindow(services, appInFront)
    }
}
