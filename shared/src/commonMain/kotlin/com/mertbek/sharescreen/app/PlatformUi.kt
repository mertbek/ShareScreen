package com.mertbek.sharescreen.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.rtc.RemoteVideo

interface PlatformUi {
    @Composable
    fun VideoView(video: RemoteVideo, modifier: Modifier, zoom: Zoom)

    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    fun WhileWatching() = Unit

    val canScanQr: Boolean get() = false

    fun scanQr(onResult: (ConnectLink?) -> Unit) = Unit

    fun share(text: String) = Unit

    fun openUrl(url: String) = Unit

    fun copyToClipboard(text: String) = Unit

    fun clipboardText(): String? = null

    fun openInputSettings() = Unit
}
