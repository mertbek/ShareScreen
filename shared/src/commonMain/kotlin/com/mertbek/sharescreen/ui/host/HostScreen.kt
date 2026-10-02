package com.mertbek.sharescreen.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mertbek.sharescreen.resources.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.rtc.CapturedMedia
import com.mertbek.sharescreen.session.HostState
import com.mertbek.sharescreen.session.InternetRoom
import com.mertbek.sharescreen.session.PendingViewer
import com.mertbek.sharescreen.session.RemoteControlAvailability
import com.mertbek.sharescreen.session.ViewerInfo
import com.mertbek.sharescreen.ui.components.QrCode
import org.jetbrains.compose.resources.stringResource
import com.mertbek.sharescreen.ui.components.BrandGradient
import com.mertbek.sharescreen.ui.components.IconBadge
import com.mertbek.sharescreen.ui.components.InfoRow
import com.mertbek.sharescreen.ui.components.InitialAvatar
import com.mertbek.sharescreen.ui.components.PulsingDot
import com.mertbek.sharescreen.ui.components.ScreenPadding
import com.mertbek.sharescreen.ui.components.SectionTitle
import com.mertbek.sharescreen.ui.components.SoftCard
import com.mertbek.sharescreen.ui.components.StatusPill
import com.mertbek.sharescreen.ui.theme.CodeTextStyle

private val WideLayout = 700.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    services: AppServices,
    onBack: () -> Unit,
) {
    val media by services.hosting.media.collectAsState()
    val hostState by services.host.state.collectAsState()
    val settings by services.settings.settings.collectAsState()
    val host = services.host

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.home_share_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val capture = media
        if (capture == null) {
            IdleContent(
                canBeControlled = services.canBeControlled,
                canFindNearby = services.lanServer != null,
                onStart = services.hosting::start,
                modifier = Modifier.fillMaxSize().padding(padding).padding(ScreenPadding),
            )
        } else {
            SharingContent(
                services = services,
                capture = capture,
                hostState = hostState,
                audioEnabledInSettings = settings.shareAudio,
                onStop = services.hosting::stop,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    val live = hostState as? HostState.Live
    val pending = live?.pendingViewers?.firstOrNull()
    val controlRequest = live?.viewers?.firstOrNull { it.control == ControlRole.REQUESTED }
    if (media != null) {
        if (pending != null) {
            JoinRequestDialog(
                viewer = pending,
                canRemember = !pending.viaInternet && services.rememberedDevices != null,
                onApprove = { remember -> host.approve(pending.id, remember) },
                onReject = { host.reject(pending.id) },
            )
        } else if (controlRequest != null) {
            ControlRequestDialog(
                viewer = controlRequest,
                desktop = services.inputInjector?.platform == HostPlatform.DESKTOP,
                canRemember = !controlRequest.viaInternet && services.rememberedDevices != null,
                onGrant = { remember -> host.grantControl(controlRequest.id, remember) },
                onDeny = { host.denyControl(controlRequest.id) },
            )
        }
    }
}

@Composable
private fun IdleContent(canBeControlled: Boolean, canFindNearby: Boolean, onStart: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = ContentMaxWidth)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(112.dp).background(BrandGradient, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ScreenShare,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp),
                )
            }
            Text(
                text = stringResource(if (canFindNearby) Res.string.host_idle_description else Res.string.host_idle_description_internet),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
        }
        Button(
            onClick = onStart,
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ScreenShare, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.host_start))
        }
    }
}

@Composable
private fun SharingContent(
    services: AppServices,
    capture: CapturedMedia,
    hostState: HostState,
    audioEnabledInSettings: Boolean,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val live = hostState as? HostState.Live
    val connection = @Composable {
        if (live != null) LiveHeader(live.viewers.size)
        ConnectionSection(services, hostState)
    }
    val activity = @Composable {
        if (live != null) {
            if (live.remoteControl != RemoteControlAvailability.OFF) {
                RemoteControlCard(services, live)
            }
            ViewersSection(live.viewers, services.host::kick)
        }
        val dialogOpen = live?.pendingViewers?.isNotEmpty() == true ||
            live?.viewers?.any { it.control == ControlRole.REQUESTED } == true
        PreviewSection(services, capture, audioEnabledInSettings, showVideo = services.ui.canOverlayVideo || !dialogOpen)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= WideLayout) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { connection() }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { activity() }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    connection()
                    activity()
                }
            }
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(ScreenPadding)
                .heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Outlined.StopCircle, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.host_stop))
        }
    }
}

@Composable
private fun LiveHeader(viewerCount: Int) {
    SoftCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsingDot()
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(Res.string.host_live_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(if (viewerCount == 0) Res.string.host_no_viewers else Res.string.host_live_watching, viewerCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun ConnectionSection(services: AppServices, state: HostState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.host_connection_title))
        SoftCard {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    HostState.Idle, HostState.Starting -> Busy(stringResource(Res.string.host_starting))
                    is HostState.Live -> {
                        val room = state.internetRoom
                        val hasLan = services.lanServer != null
                        var showInternet by rememberSaveable { mutableStateOf(!hasLan) }
                        if (room != null && hasLan) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = !showInternet,
                                    onClick = { showInternet = false },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                ) { Text(stringResource(Res.string.host_tab_nearby)) }
                                SegmentedButton(
                                    selected = showInternet,
                                    onClick = { showInternet = true },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                ) { Text(stringResource(Res.string.host_tab_internet)) }
                            }
                        }
                        if (showInternet && room != null) {
                            InternetDetails(services, room, state.pin, state.deviceName)
                        } else if (hasLan) {
                            NearbyDetails(state, services.rememberedDevices?.hostId)
                        } else {
                            Text(text = stringResource(Res.string.host_no_network), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is HostState.Failed -> Text(
                        text = stringResource(Res.string.host_failed, state.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyDetails(state: HostState.Live, hostId: String?) {
    val pin = state.pin.takeIf { state.lanPin }
    val primaryAddress = state.addresses.firstOrNull()
    if (primaryAddress == null) {
        Text(text = stringResource(Res.string.host_no_network), color = MaterialTheme.colorScheme.error)
    } else {
        LinkQrCode(ConnectLink.Lan(primaryAddress, state.port, pin, state.deviceName, hostId).toUri())
    }
    state.addresses.forEach { address ->
        CodeChip(stringResource(Res.string.host_address_label), "$address:${state.port}", AddressTextStyle)
    }
    if (pin != null) {
        CodeChip(stringResource(Res.string.host_pin_label), pin.chunked(3).joinToString(" "))
    } else {
        Text(
            text = stringResource(Res.string.host_nearby_no_pin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InternetDetails(services: AppServices, room: InternetRoom, pin: String, deviceName: String) {
    when (room) {
        is InternetRoom.Connecting -> Busy(stringResource(Res.string.host_internet_connecting))
        is InternetRoom.Failed -> Text(
            text = stringResource(
                if (services.lanServer != null) Res.string.host_internet_failed else Res.string.host_internet_failed_only,
                room.server.substringAfter("://").substringBefore("/"),
            ),
            color = MaterialTheme.colorScheme.error,
        )
        is InternetRoom.Closed -> Text(
            text = stringResource(if (services.lanServer != null) Res.string.host_internet_closed else Res.string.host_internet_closed_only),
            color = MaterialTheme.colorScheme.error,
        )
        is InternetRoom.Open -> {
            val invite = ConnectLink.Internet(room.server, room.roomCode, pin, deviceName)
            val webLink = services.webApp?.let(invite::toWebUri)
            LinkQrCode(webLink ?: invite.toUri())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CodeChip(
                    label = stringResource(Res.string.host_room_code_label),
                    value = room.roomCode.chunked(3).joinToString(" "),
                    modifier = Modifier.weight(1f),
                )
                CodeChip(
                    label = stringResource(Res.string.host_pin_label),
                    value = pin.chunked(3).joinToString(" "),
                    modifier = Modifier.weight(1f),
                )
            }
            if (webLink != null) ShareInviteButton(services, webLink)
            if (room.reconnecting) Busy(stringResource(Res.string.host_internet_reconnecting))
        }
    }
}

@Composable
private fun LinkQrCode(link: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QrCode(
            content = link,
            contentDescription = stringResource(Res.string.host_qr_description),
            modifier = Modifier.size(QR_SIZE).clip(MaterialTheme.shapes.medium),
        )
        Text(
            text = stringResource(Res.string.host_qr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Busy(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(message)
    }
}

@Composable
private fun CodeChip(label: String, value: String, style: TextStyle = PairTextStyle, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = style, maxLines = 1)
    }
}

@Composable
private fun ViewersSection(viewers: List<ViewerInfo>, onKick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.host_viewers_title, viewers.size))
        SoftCard {
            if (viewers.isEmpty()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        icon = Icons.Outlined.Groups,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(Res.string.host_no_viewers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            viewers.forEachIndexed { index, viewer ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InitialAvatar(viewer.deviceName, size = 44.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(viewer.deviceName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(stringResource(if (viewer.isConnected) Res.string.host_viewer_watching else Res.string.host_viewer_connecting))
                            if (viewer.viaInternet) StatusPill(stringResource(Res.string.host_viewer_via_internet))
                            if (viewer.remembered) StatusPill(stringResource(Res.string.host_viewer_remembered))
                            if (viewer.control == ControlRole.GRANTED) {
                                StatusPill(
                                    text = stringResource(Res.string.host_viewer_controlling),
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onKick(viewer.id) }) {
                        Icon(Icons.Outlined.PersonRemove, contentDescription = stringResource(Res.string.host_kick))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSection(services: AppServices, capture: CapturedMedia, audioEnabledInSettings: Boolean, showVideo: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.host_preview, capture.width, capture.height))
        SoftCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                capture.preview?.let {
                    if (showVideo) {
                        services.ui.PreviewView(it, Modifier.fillMaxWidth().height(PREVIEW_HEIGHT))
                    } else {
                        Spacer(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT))
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val audioShared = capture.hasAudio
                    Icon(
                        imageVector = if (audioShared) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(
                            when {
                                audioShared -> Res.string.host_audio_shared
                                !audioEnabledInSettings -> Res.string.host_audio_off
                                services.ui.audioNeedsPermission -> Res.string.host_audio_not_shared
                                else -> Res.string.host_audio_unavailable
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinRequestDialog(viewer: PendingViewer, canRemember: Boolean, onApprove: (remember: Boolean) -> Unit, onReject: () -> Unit) {
    var rememberDevice by remember(viewer.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
        title = { Text(stringResource(Res.string.host_join_request_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val message = if (viewer.viaInternet) Res.string.host_join_request_message_internet else Res.string.host_join_request_message
                Text(stringResource(message, viewer.deviceName))
                if (canRemember) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = rememberDevice, role = Role.Checkbox, onValueChange = { rememberDevice = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = rememberDevice, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.host_join_request_remember), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApprove(rememberDevice) }) { Text(stringResource(Res.string.host_join_request_allow)) }
        },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(Res.string.host_join_request_deny)) } },
    )
}

@Composable
private fun RemoteControlCard(services: AppServices, state: HostState.Live) {
    val controller = state.controller
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.host_control_title))
        SoftCard(
            containerColor = if (controller != null) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        icon = Icons.Outlined.TouchApp,
                        containerColor = if (controller != null) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (controller != null) {
                            MaterialTheme.colorScheme.onTertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(16.dp))
                    val message = when {
                        state.remoteControl == RemoteControlAvailability.NEEDS_SERVICE ->
                            stringResource(Res.string.host_control_needs_service)
                        controller != null -> stringResource(Res.string.host_control_active, controller.deviceName)
                        else -> stringResource(Res.string.host_control_ready)
                    }
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    state.remoteControl == RemoteControlAvailability.NEEDS_SERVICE -> {
                        var setup by remember { mutableStateOf(false) }
                        Button(onClick = { setup = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.host_control_open_settings))
                        }
                        if (setup) services.ui.InputAccessDialog { setup = false }
                    }
                    controller != null -> Button(
                        onClick = services.host::revokeControl,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(Res.string.host_control_stop))
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlRequestDialog(
    viewer: ViewerInfo,
    desktop: Boolean,
    canRemember: Boolean,
    onGrant: (remember: Boolean) -> Unit,
    onDeny: () -> Unit,
) {
    var dontAskAgain by remember(viewer.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.TouchApp, contentDescription = null) },
        title = { Text(stringResource(Res.string.host_control_request_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (desktop) Res.string.host_control_request_message_desktop else Res.string.host_control_request_message,
                        viewer.deviceName,
                    ),
                )
                if (canRemember) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = dontAskAgain, role = Role.Checkbox, onValueChange = { dontAskAgain = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = dontAskAgain, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (viewer.remembered) Res.string.host_control_request_remember else Res.string.host_control_request_remember_new,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onGrant(dontAskAgain) }) { Text(stringResource(Res.string.host_join_request_allow)) }
        },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(Res.string.host_join_request_deny)) } },
    )
}

private val PREVIEW_HEIGHT = 220.dp
private val QR_SIZE = 200.dp

private val ContentMaxWidth = 640.dp

private val PairTextStyle = CodeTextStyle.copy(fontSize = 22.sp, letterSpacing = 1.sp)

private val AddressTextStyle = CodeTextStyle.copy(fontSize = 20.sp, letterSpacing = 0.5.sp)

@Composable
private fun ShareInviteButton(services: AppServices, link: String) {
    val message = stringResource(Res.string.host_invite_message, link)
    OutlinedButton(
        onClick = { services.ui.share(message) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.host_invite_share), modifier = Modifier.padding(start = 8.dp))
    }
}
