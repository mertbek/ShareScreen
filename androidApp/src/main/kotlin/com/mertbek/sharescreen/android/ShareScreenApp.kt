package com.mertbek.sharescreen.android

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log as AndroidLog
import com.mertbek.sharescreen.android.capture.AndroidScreenSource
import com.mertbek.sharescreen.android.capture.CaptureRequests
import com.mertbek.sharescreen.android.control.AndroidInputInjector
import com.mertbek.sharescreen.android.lan.AndroidLocalAddresses
import com.mertbek.sharescreen.android.lan.NsdAdvertiser
import com.mertbek.sharescreen.android.lan.NsdBrowser
import com.mertbek.sharescreen.android.lan.WifiLowLatencyLock
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.capture.AndroidScreenCapture
import com.mertbek.sharescreen.capture.ScreenAudioInput
import com.mertbek.sharescreen.lan.EmbeddedLanServer
import com.mertbek.sharescreen.platform.DeviceName
import com.mertbek.sharescreen.rtc.AndroidRtcEngine
import com.mertbek.sharescreen.settings.SettingsRepository
import com.mertbek.sharescreen.util.Log
import com.russhwolf.settings.SharedPreferencesSettings

class ShareScreenApp : Application() {

    val screenAudioInput by lazy { ScreenAudioInput(this) }
    val engine by lazy { AndroidRtcEngine(this, screenAudioInput) }
    val capture by lazy { AndroidScreenCapture(this, engine) }
    val captureRequests = CaptureRequests()
    val screenSource by lazy { AndroidScreenSource(this, captureRequests, capture) }
    val wifiLock by lazy { WifiLowLatencyLock(this) }
    val ui by lazy { AndroidUi(this, engine.eglBase.eglBaseContext) }
    val inputInjector: AndroidInputInjector? by lazy {
        if (BuildConfig.REMOTE_CONTROL_HOST) AndroidInputInjector(this) else null
    }

    val services: AppServices by lazy {
        val addresses = AndroidLocalAddresses()
        AppServices(
            rtc = engine,
            deviceName = DeviceName(deviceName()),
            settings = SettingsRepository(
                SharedPreferencesSettings(getSharedPreferences("settings", MODE_PRIVATE)),
                BuildConfig.DEFAULT_SERVER.ifEmpty { null },
            ),
            ui = ui,
            screenSource = screenSource,
            lanServer = EmbeddedLanServer(),
            localAddresses = addresses,
            lanAdvertiser = NsdAdvertiser(this),
            lanBrowser = NsdBrowser(this, addresses),
            inputInjector = inputInjector,
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.sink = { level, tag, message, error ->
            when (level) {
                "E" -> AndroidLog.e(tag, message, error)
                "W" -> AndroidLog.w(tag, message, error)
                "I" -> AndroidLog.i(tag, message)
                else -> AndroidLog.d(tag, message)
            }
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
