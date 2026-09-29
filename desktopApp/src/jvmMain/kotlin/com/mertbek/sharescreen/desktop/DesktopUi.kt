package com.mertbek.sharescreen.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mertbek.sharescreen.app.PlatformUi
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.rtc.DesktopVideoView
import com.mertbek.sharescreen.rtc.RemoteVideo
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI

class DesktopUi : PlatformUi {

    @Composable
    override fun VideoView(video: RemoteVideo, modifier: Modifier, zoom: Zoom) = DesktopVideoView(video, modifier, zoom)

    override fun share(text: String) = copyToClipboard(text)

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun clipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
