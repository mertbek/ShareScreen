package com.mertbek.sharescreen.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.settings.DEFAULT_SERVER
import com.mertbek.sharescreen.ui.discover.DiscoverScreen
import com.mertbek.sharescreen.ui.home.HomeScreen
import com.mertbek.sharescreen.ui.host.HostScreen
import com.mertbek.sharescreen.ui.settings.SettingsScreen
import com.mertbek.sharescreen.ui.theme.ShareScreenTheme

@Composable
fun App(services: AppServices) {
    ShareScreenTheme {
        val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
        val current = stack.last()
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
                    onShareClick = { stack += Screen.Host },
                    onWatchClick = { stack += Screen.Discover },
                    onSettingsClick = { stack += Screen.Settings },
                )
                Screen.Settings -> SettingsScreen(
                    repository = services.settings,
                    canBeControlled = services.canBeControlled,
                    versionName = APP_VERSION,
                    privacyUrl = "https://" + DEFAULT_SERVER.removePrefix("wss://") + "/privacy",
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
                else -> Unit
            }
        }
    }
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
