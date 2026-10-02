package com.mertbek.sharescreen.app

import com.mertbek.sharescreen.client.SignalingClient
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.platform.InputInjector
import com.mertbek.sharescreen.platform.LanAdvertiser
import com.mertbek.sharescreen.platform.LanBrowser
import com.mertbek.sharescreen.platform.LanServer
import com.mertbek.sharescreen.platform.LocalAddressProvider
import com.mertbek.sharescreen.platform.ScreenSource
import com.mertbek.sharescreen.rtc.RtcEngine
import com.mertbek.sharescreen.session.HostSession
import com.mertbek.sharescreen.session.ViewerSession
import com.mertbek.sharescreen.settings.SettingsRepository

class AppServices(
    val rtc: RtcEngine,
    val deviceName: DeviceName,
    val settings: SettingsRepository,
    val ui: PlatformUi,
    val screenSource: ScreenSource? = null,
    val lanServer: LanServer? = null,
    val localAddresses: LocalAddressProvider? = null,
    val lanAdvertiser: LanAdvertiser? = null,
    val lanBrowser: LanBrowser? = null,
    val inputInjector: InputInjector? = null,
    val webApp: String? = null,
    val versionName: String = "dev",
) {
    private val signalingClient = SignalingClient()

    val host = HostSession(rtc, signalingClient, deviceName, lanServer, localAddresses, lanAdvertiser, inputInjector)

    val hosting = HostController(this)

    val canHost: Boolean get() = screenSource != null

    val canBeControlled: Boolean get() = inputInjector != null

    fun newViewer() = ViewerSession(rtc, signalingClient, deviceName)
}
