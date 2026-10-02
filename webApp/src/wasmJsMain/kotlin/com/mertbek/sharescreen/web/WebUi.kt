package com.mertbek.sharescreen.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mertbek.sharescreen.app.PlatformUi
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.rtc.RemoteVideo
import com.mertbek.sharescreen.rtc.WebVideoView

class WebUi : PlatformUi {
    override val canOverlayVideo: Boolean get() = false

    override val canShareInApp: Boolean = isAndroid()

    override fun shareInApp() = openAndroidShare(RELEASES_URL)

    @Composable
    override fun VideoView(video: RemoteVideo, modifier: Modifier, zoom: Zoom, onVideoSize: (Int, Int) -> Unit) =
        WebVideoView(video, modifier, zoom, onVideoSize)

    override fun share(text: String) = shareText(text)

    override fun openUrl(url: String) = openWindow(url)

    override fun copyToClipboard(text: String) = writeClipboard(text)

    private companion object {
        const val RELEASES_URL = "https://github.com/mertbek/ShareScreen/releases/latest"
    }
}
