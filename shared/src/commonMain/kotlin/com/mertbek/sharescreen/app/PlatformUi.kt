package com.mertbek.sharescreen.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.rtc.RemoteVideo

sealed interface QrScan {
    data class Found(val link: ConnectLink) : QrScan
    data object Invalid : QrScan
    data object CameraDenied : QrScan
}

interface PlatformUi {
    @Composable
    fun VideoView(video: RemoteVideo, modifier: Modifier, zoom: Zoom, onVideoSize: (width: Int, height: Int) -> Unit)

    @Composable
    fun PreviewView(video: RemoteVideo, modifier: Modifier) = VideoView(video, modifier, Zoom()) { _, _ -> }

    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    fun WhileWatching() = Unit

    val canScanQr: Boolean get() = false

    val canOverlayVideo: Boolean get() = true

    val audioNeedsPermission: Boolean get() = false

    fun scanQr(onResult: (QrScan) -> Unit) = Unit

    fun share(text: String) = Unit

    fun openUrl(url: String) = Unit

    fun copyToClipboard(text: String) = Unit

    fun clipboardText(): String? = null

    @Composable
    fun InputAccessDialog(onDismiss: () -> Unit) = onDismiss()
}
