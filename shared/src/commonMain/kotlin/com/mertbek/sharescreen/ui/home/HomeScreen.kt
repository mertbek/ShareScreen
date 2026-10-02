package com.mertbek.sharescreen.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mertbek.sharescreen.resources.*
import com.mertbek.sharescreen.ui.components.ActionCard
import com.mertbek.sharescreen.ui.components.BrandGradient
import com.mertbek.sharescreen.ui.components.InfoRow
import com.mertbek.sharescreen.ui.components.ScreenPadding
import com.mertbek.sharescreen.ui.components.SectionTitle
import com.mertbek.sharescreen.ui.components.SoftCard
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val WideLayout = 700.dp

@Composable
fun HomeScreen(
    canHost: Boolean,
    canShareInApp: Boolean,
    canBeControlled: Boolean,
    canFindNearby: Boolean,
    onShareClick: () -> Unit,
    onShareInAppClick: () -> Unit,
    onWatchClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= WideLayout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Header(onSettingsClick)
                Column(
                    Modifier.widthIn(max = 920.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Hero()
                    val share = @Composable { modifier: Modifier ->
                        ActionCard(
                            icon = Icons.AutoMirrored.Outlined.ScreenShare,
                            title = stringResource(Res.string.home_share_title),
                            subtitle = stringResource(
                                when {
                                    !canHost -> Res.string.home_share_subtitle_app
                                    canFindNearby -> Res.string.home_share_subtitle
                                    else -> Res.string.home_share_subtitle_internet
                                },
                            ),
                            onClick = if (canHost) onShareClick else onShareInAppClick,
                            modifier = modifier,
                        )
                    }
                    val watch = @Composable { modifier: Modifier ->
                        ActionCard(
                            icon = Icons.Outlined.Visibility,
                            title = stringResource(Res.string.home_watch_title),
                            subtitle = stringResource(if (canFindNearby) Res.string.home_watch_subtitle else Res.string.home_watch_subtitle_internet),
                            onClick = onWatchClick,
                            modifier = modifier,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    if (!canHost && !canShareInApp) {
                        watch(Modifier)
                        SoftCard {
                            InfoRow(Icons.Outlined.Info, stringResource(Res.string.home_share_unavailable), Modifier.padding(20.dp))
                        }
                    } else if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            share(Modifier.weight(1f))
                            watch(Modifier.weight(1f))
                        }
                    } else {
                        share(Modifier)
                        watch(Modifier)
                    }
                    HowItWorks(canBeControlled, canFindNearby)
                }
            }
        }
    }
}

@Composable
private fun Header(onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(Res.string.settings_title))
        }
    }
}

@Composable
private fun Hero() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandGradient, MaterialTheme.shapes.extraLarge)
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.home_hero_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = stringResource(Res.string.home_hero_text),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Image(
            painter = painterResource(Res.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(132.dp),
        )
    }
}

@Composable
private fun HowItWorks(canBeControlled: Boolean, canFindNearby: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.home_how_title))
        SoftCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (canFindNearby) {
                    InfoRow(Icons.Outlined.Wifi, stringResource(Res.string.home_how_connect))
                } else {
                    InfoRow(Icons.Outlined.Public, stringResource(Res.string.home_how_connect_internet))
                }
                InfoRow(Icons.Outlined.Lock, stringResource(Res.string.home_how_private))
                if (canBeControlled) {
                    InfoRow(Icons.Outlined.TouchApp, stringResource(Res.string.home_how_control))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
