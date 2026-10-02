package com.mertbek.sharescreen.ui.host

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.session.HostState
import com.mertbek.sharescreen.ui.theme.ShareScreenTheme

/**
 * Shows the waiting watch or control request above all other windows while the app's own window is
 * out of sight, so it can be answered from whatever is on screen.
 */
@Composable
fun RequestWindow(services: AppServices, appInFront: Boolean) {
    val media by services.hosting.media.collectAsState()
    val hostState by services.host.state.collectAsState()
    val live = hostState as? HostState.Live
    val request = live?.pendingViewers?.firstOrNull()?.id ?: live?.viewers?.firstOrNull { it.control == ControlRole.REQUESTED }?.id
    if (appInFront || media == null || request == null) return

    // A window for each request, sized to it. Without an owner it stays up while the app's window is
    // minimised, and as it never takes the focus, typing in the window in front goes on.
    key(request) {
        DialogWindow(
            onCloseRequest = {},
            state = rememberDialogState(position = WindowPosition(Alignment.Center), size = DpSize.Unspecified),
            title = "ShareScreen",
            undecorated = true,
            transparent = true,
            resizable = false,
            focusable = false,
            alwaysOnTop = true,
        ) {
            ShareScreenTheme {
                HostRequestCard(services, Modifier.padding(24.dp))
            }
        }
    }
}
