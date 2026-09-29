package com.mertbek.sharescreen.rtc

import com.mertbek.sharescreen.platform.ScreenSource
import com.mertbek.sharescreen.settings.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopScreenSource(private val capture: DesktopScreenCapture) : ScreenSource {

    override suspend fun start(quality: VideoQuality, shareAudio: Boolean, wholeScreenOnly: Boolean): CapturedMedia =
        withContext(Dispatchers.IO) { capture.start(quality) }

    override fun stop() = capture.stop()
}
