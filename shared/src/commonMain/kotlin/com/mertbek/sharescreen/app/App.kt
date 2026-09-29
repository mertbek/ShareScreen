package com.mertbek.sharescreen.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.resources.Res
import com.mertbek.sharescreen.resources.action_cancel
import com.mertbek.sharescreen.resources.discover_connect
import com.mertbek.sharescreen.resources.link_confirm_message
import com.mertbek.sharescreen.resources.link_confirm_title
import org.jetbrains.compose.resources.stringResource
import com.mertbek.sharescreen.ui.discover.DiscoverScreen
import com.mertbek.sharescreen.ui.home.HomeScreen
import com.mertbek.sharescreen.ui.host.HostScreen
import com.mertbek.sharescreen.ui.settings.SettingsScreen
import com.mertbek.sharescreen.ui.viewer.ViewerScreen
import com.mertbek.sharescreen.ui.theme.ShareScreenTheme

@Composable
fun App(
    services: AppServices,
    incomingLink: ConnectLink? = null,
    onIncomingLinkHandled: () -> Unit = {},
    showHost: Boolean = false,
    onShowHostHandled: () -> Unit = {},
) {
    ShareScreenTheme {
        val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
        val current = stack.last()
        var unconfirmedLink by remember { mutableStateOf<ConnectLink?>(null) }
        val defaultServer = services.settings.settings.value.defaultServer
        LaunchedEffect(incomingLink) {
            if (incomingLink == null) return@LaunchedEffect
            if (incomingLink is ConnectLink.Internet && incomingLink.server == defaultServer) {
                stack.retainAll { it.depth < 2 }
                stack += Screen.Viewer(incomingLink)
            } else {
                unconfirmedLink = incomingLink
            }
            onIncomingLinkHandled()
        }
        unconfirmedLink?.let { link ->
            ConfirmLinkDialog(
                link = link,
                onConfirm = {
                    unconfirmedLink = null
                    stack.retainAll { it.depth < 2 }
                    stack += Screen.Viewer(link)
                },
                onDismiss = { unconfirmedLink = null },
            )
        }
        LaunchedEffect(showHost) {
            if (!showHost) return@LaunchedEffect
            if (stack.last() != Screen.Host) {
                stack.clear()
                stack += Screen.Home
                stack += Screen.Host
            }
            onShowHostHandled()
        }
        services.ui.BackHandler(enabled = stack.size > 1) { stack.removeLast() }

        AnimatedContent(
            targetState = current,
            transitionSpec = {
                if (stack.size >= initialState.depth) {
                    (fadeIn() + slideInHorizontally { it / 10 }) togetherWith fadeOut()
                } else {
                    fadeIn() togetherWith (fadeOut() + slideOutHorizontally { it / 10 })
                }
            },
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(
                    canHost = services.canHost,
                    canBeControlled = services.canBeControlled,
                    canFindNearby = services.lanBrowser != null,
                    onShareClick = { stack += Screen.Host },
                    onWatchClick = { stack += Screen.Discover },
                    onSettingsClick = { stack += Screen.Settings },
                )
                Screen.Settings -> SettingsScreen(
                    repository = services.settings,
                    canBeControlled = services.canBeControlled,
                    versionName = APP_VERSION,
                    privacyUrl = services.settings.settings.value.defaultServer?.let {
                        "https://" + it.removePrefix("wss://").removePrefix("ws://") + "/privacy"
                    },
                    onOpenUrl = services.ui::openUrl,
                    onBack = { stack.removeLast() },
                )
                Screen.Host -> HostScreen(services, onBack = { stack.removeLast() })
                Screen.Discover -> DiscoverScreen(
                    services = services,
                    onBack = { stack.removeLast() },
                    onConnect = { link -> stack += Screen.Viewer(link) },
                    onOpenSettings = { stack += Screen.Settings },
                )
                is Screen.Viewer -> ViewerScreen(services, screen.link, onBack = { stack.removeLast() })
            }
        }
    }
}

@Composable
private fun ConfirmLinkDialog(link: ConnectLink, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.link_confirm_title, link.name ?: link.address)) },
        text = { Text(stringResource(Res.string.link_confirm_message, link.address)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.discover_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

const val APP_VERSION = "0.1.0"

sealed interface Screen {
    val depth: Int

    data object Home : Screen {
        override val depth = 0
    }

    data object Host : Screen {
        override val depth = 1
    }

    data object Discover : Screen {
        override val depth = 1
    }

    data object Settings : Screen {
        override val depth = 1
    }

    data class Viewer(val link: ConnectLink) : Screen {
        override val depth = 2
    }
}
