package com.mertbek.sharescreen.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler as ActivityBackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mertbek.sharescreen.android.control.RemoteControlService
import com.mertbek.sharescreen.app.PlatformUi
import com.mertbek.sharescreen.app.QrScan
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.rtc.AndroidVideoView
import com.mertbek.sharescreen.rtc.RemoteVideo
import org.webrtc.EglBase

class AndroidUi(
    private val context: Context,
    private val eglContext: EglBase.Context,
) : PlatformUi {

    @Volatile
    var activity: MainActivity? = null

    override val canScanQr: Boolean get() = true

    override val audioNeedsPermission: Boolean get() = true

    @Composable
    override fun VideoView(video: RemoteVideo, modifier: Modifier, zoom: Zoom, onVideoSize: (Int, Int) -> Unit) =
        AndroidVideoView(video, eglContext, modifier, zoom, onVideoSize)

    @Composable
    override fun PreviewView(video: RemoteVideo, modifier: Modifier) =
        AndroidVideoView(video, eglContext, modifier, Zoom(), { _, _ -> }, preview = true)

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = ActivityBackHandler(enabled, onBack)

    @Composable
    override fun WhileWatching() {
        val activity = LocalActivity.current ?: return
        val view = LocalView.current
        DisposableEffect(activity, view) {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = true
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                view.keepScreenOn = false
            }
        }
    }

    @Composable
    override fun requestsOverOtherApps(): Boolean? {
        var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        // The permission is given in the system settings, so look again when the app comes back.
        val lifecycle = (LocalActivity.current as? ComponentActivity)?.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) allowed = Settings.canDrawOverlays(context)
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }
        return allowed
    }

    override fun allowRequestsOverOtherApps() {
        activity?.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
    }

    override fun scanQr(onResult: (QrScan) -> Unit) {
        activity?.scanQr(onResult)
    }

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity?.startActivity(Intent.createChooser(send, null))
    }

    override fun openUrl(url: String) {
        activity?.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    override fun copyToClipboard(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
    }

    override fun clipboardText(): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()

    @Composable
    override fun InputAccessDialog(onDismiss: () -> Unit) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.TouchApp, contentDescription = null) },
            title = { Text(stringResource(R.string.control_disclosure_title)) },
            text = {
                Text(
                    stringResource(R.string.control_disclosure_message) + "\n\n" + stringResource(R.string.host_control_restricted_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.startActivity(RemoteControlService.settingsIntent())
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.control_disclosure_agree))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
