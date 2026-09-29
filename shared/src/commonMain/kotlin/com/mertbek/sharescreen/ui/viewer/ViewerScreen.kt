package com.mertbek.sharescreen.ui.viewer

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.DoNotTouch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mertbek.sharescreen.resources.*
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.unit.IntSize
import com.mertbek.sharescreen.control.PointerAction
import com.mertbek.sharescreen.control.PointerButton
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.control.ControlKey
import com.mertbek.sharescreen.control.FittedRect
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.control.Zoom
import com.mertbek.sharescreen.control.fitVideo
import com.mertbek.sharescreen.control.mapToPicture
import com.mertbek.sharescreen.control.toJoinTarget
import com.mertbek.sharescreen.link.ConnectLink
import com.mertbek.sharescreen.rtc.StreamInfo
import com.mertbek.sharescreen.session.ControlNotice
import com.mertbek.sharescreen.session.ControlStatus
import com.mertbek.sharescreen.session.EndReason
import com.mertbek.sharescreen.session.ViewerSession
import com.mertbek.sharescreen.session.ViewerState
import org.jetbrains.compose.resources.stringResource
import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.control.NavAction
import com.mertbek.sharescreen.control.TouchPointer
import com.mertbek.sharescreen.control.TypingBox
import com.mertbek.sharescreen.ui.components.IconBadge
import kotlinx.coroutines.delay

@Composable
fun ViewerScreen(services: AppServices, link: ConnectLink, onBack: () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    val session = remember(attempt) { services.newViewer() }
    DisposableEffect(session) {
        session.connect(link.toJoinTarget(), link.pin)
        onDispose { session.close() }
    }
    val state by session.state.collectAsState()
    val streamInfo by session.streamInfo.collectAsState()
    val hasAudio by session.hasAudio.collectAsState()
    val muted by session.muted.collectAsState()
    val control by session.control.collectAsState()
    val reconnecting by session.reconnecting.collectAsState()
    services.ui.WhileWatching()
    services.ui.BackHandler(enabled = true, onBack = onBack)

    val watching = state is ViewerState.Watching
    val controlling = watching && control.role == ControlRole.GRANTED
    var controlsVisible by remember { mutableStateOf(true) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(Zoom()) }
    var zoomMode by remember { mutableStateOf(false) }
    var videoSize by remember { mutableStateOf(IntSize.Zero) }
    if (!controlling) {
        keyboardOpen = false
        zoomMode = false
    }
    LaunchedEffect(controlsVisible, watching, muted, control) {
        if (controlsVisible && watching) {
            delay(CONTROLS_TIMEOUT_MILLIS)
            controlsVisible = false
        }
    }

    var notice by remember { mutableStateOf<ControlNotice?>(null) }
    LaunchedEffect(session) { session.controlNotices.collect { notice = it } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_TIMEOUT_MILLIS)
            notice = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val current = state) {
                is ViewerState.Watching -> Box(Modifier.fillMaxSize()) {
                    services.ui.VideoView(current.video, Modifier.fillMaxSize(), zoom) { width, height ->
                        videoSize = IntSize(width, height)
                    }
                    if (controlling && !zoomMode) {
                        InputSurface(
                            platform = control.platform,
                            zoom = zoom,
                            videoSize = videoSize,
                            session = session,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        ZoomGestures(
                            zoom = zoom,
                            onZoom = { zoom = it },
                            onTap = { if (!controlling) controlsVisible = !controlsVisible },
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                is ViewerState.Ended -> EndedMessage(
                    current.reason,
                    onRetry = { attempt++ },
                    onBack = onBack,
                )
                ViewerState.Connecting -> Progress(stringResource(Res.string.viewer_connecting))
                ViewerState.WaitingForApproval -> Progress(stringResource(Res.string.viewer_waiting_approval))
                ViewerState.Negotiating -> Progress(stringResource(Res.string.viewer_negotiating))
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible && watching,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopControls(
                    title = link.name ?: link.address,
                    streamInfo = streamInfo,
                    hasAudio = hasAudio,
                    muted = muted,
                    control = control,
                    onToggleMute = { session.setMuted(!muted) },
                    onRequestControl = session::requestControl,
                    onReleaseControl = session::releaseControl,
                    onBack = onBack,
                )
            }

            val message = when {
                !watching -> null
                reconnecting -> Res.string.viewer_reconnecting
                control.role == ControlRole.REQUESTED -> Res.string.viewer_control_waiting
                notice == ControlNotice.DENIED -> Res.string.viewer_control_denied
                notice == ControlNotice.ENDED -> Res.string.viewer_control_ended
                else -> null
            }
            if (message != null) {
                Text(
                    text = stringResource(message),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (controlling) {
            if (keyboardOpen) {
                KeyboardBar(onType = session::type, onEnter = { session.press(ControlKey.ENTER) })
            }
            ControlBar(
                zoomMode = zoomMode,
                onNavigate = session::navigate,
                onToggleKeyboard = { keyboardOpen = !keyboardOpen },
                onPaste = {
                    services.ui.clipboardText()?.takeIf { it.isNotEmpty() }
                        ?.let { session.type(ControlMessage.Type(text = it.take(MAX_PASTE_LENGTH))) }
                },
                onToggleZoomMode = { zoomMode = !zoomMode },
                onShowDetails = { controlsVisible = true },
                onRelease = session::releaseControl,
            )
        }
    }
}

@Composable
private fun ZoomGestures(zoom: Zoom, onZoom: (Zoom) -> Unit, onTap: () -> Unit, modifier: Modifier) {
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnTap by rememberUpdatedState(onTap)
    val tapLabel = stringResource(Res.string.viewer_toggle_controls)
    Box(
        modifier
            .semantics { onClick(label = tapLabel) { currentOnTap(); true } }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { currentOnZoom(currentZoom.toggled(it.x / size.width, it.y / size.height)) },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, factor, _ ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    currentOnZoom(
                        currentZoom.transformed(
                            factor = factor,
                            fromX = (centroid.x - pan.x) / width,
                            fromY = (centroid.y - pan.y) / height,
                            toX = centroid.x / width,
                            toY = centroid.y / height,
                        )
                    )
                }
            }
    )
}

@Composable
private fun InputSurface(
    platform: HostPlatform,
    zoom: Zoom,
    videoSize: IntSize,
    session: ViewerSession,
    modifier: Modifier,
) {
    val currentZoom by rememberUpdatedState(zoom)
    val currentVideo by rememberUpdatedState(videoSize)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event -> forwardKey(session, event) }
            .pointerInput(platform) {
                awaitPointerEventScope {
                    var sent = emptyList<TouchPointer>()
                    var activeButton = PointerButton.LEFT
                    while (true) {
                        val event = awaitPointerEvent()
                        val fit = fitVideo(size.width.toFloat(), size.height.toFloat(), currentVideo.width, currentVideo.height)
                        if (platform == HostPlatform.DESKTOP) {
                            if (event.type == PointerEventType.Press) {
                                activeButton = when {
                                    event.buttons.isSecondaryPressed -> PointerButton.RIGHT
                                    event.buttons.isTertiaryPressed -> PointerButton.MIDDLE
                                    else -> PointerButton.LEFT
                                }
                            }
                            forwardPointer(session, event, fit, currentZoom, activeButton)
                        } else {
                            val pointers = event.changes.filter { it.pressed }.take(MAX_POINTERS).map {
                                val (x, y) = mapToPicture(fit, currentZoom, it.position.x, it.position.y)
                                TouchPointer(it.id.value, x, y)
                            }
                            if (pointers != sent) {
                                sent = pointers
                                session.touch(event.changes.maxOf { it.uptimeMillis }, pointers)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    )
}

private fun forwardPointer(session: ViewerSession, event: PointerEvent, fit: FittedRect, zoom: Zoom, button: PointerButton) {
    val change = event.changes.first()
    val (x, y) = mapToPicture(fit, zoom, change.position.x, change.position.y)
    val message = when (event.type) {
        PointerEventType.Press -> ControlMessage.Pointer(PointerAction.DOWN, x, y, button)
        PointerEventType.Release -> ControlMessage.Pointer(PointerAction.UP, x, y, button)
        PointerEventType.Move -> ControlMessage.Pointer(PointerAction.MOVE, x, y, button)
        PointerEventType.Scroll -> ControlMessage.Pointer(PointerAction.SCROLL, x, y, scrollX = change.scrollDelta.x, scrollY = change.scrollDelta.y)
        else -> return
    }
    session.pointer(message)
}

private fun forwardKey(session: ViewerSession, event: KeyEvent): Boolean {
    val name = keyName(event) ?: return false
    session.keyboard(
        ControlMessage.Keyboard(
            key = name,
            down = event.type == KeyEventType.KeyDown,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            meta = event.isMetaPressed,
        )
    )
    return true
}

private fun keyName(event: KeyEvent): String? = when (event.key) {
    Key.Enter, Key.NumPadEnter -> "Enter"
    Key.Backspace -> "Backspace"
    Key.Tab -> "Tab"
    Key.Escape -> "Escape"
    Key.Delete -> "Delete"
    Key.Insert -> "Insert"
    Key.MoveHome -> "Home"
    Key.MoveEnd -> "End"
    Key.PageUp -> "PageUp"
    Key.PageDown -> "PageDown"
    Key.DirectionLeft -> "ArrowLeft"
    Key.DirectionRight -> "ArrowRight"
    Key.DirectionUp -> "ArrowUp"
    Key.DirectionDown -> "ArrowDown"
    Key.Spacebar -> "Space"
    Key.F1 -> "F1"
    Key.F2 -> "F2"
    Key.F3 -> "F3"
    Key.F4 -> "F4"
    Key.F5 -> "F5"
    Key.F6 -> "F6"
    Key.F7 -> "F7"
    Key.F8 -> "F8"
    Key.F9 -> "F9"
    Key.F10 -> "F10"
    Key.F11 -> "F11"
    Key.F12 -> "F12"
    else -> event.utf16CodePoint.takeIf { it > 0x20 }?.let { it.toChar().toString().lowercase() }
}

@Composable
private fun ControlBar(
    zoomMode: Boolean,
    onNavigate: (NavAction) -> Unit,
    onToggleKeyboard: () -> Unit,
    onPaste: () -> Unit,
    onToggleZoomMode: () -> Unit,
    onShowDetails: () -> Unit,
    onRelease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1C), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Outlined.Info, stringResource(Res.string.viewer_show_details), onClick = onShowDetails)
        BarButton(Icons.AutoMirrored.Filled.ArrowLeft, stringResource(Res.string.viewer_nav_back)) { onNavigate(NavAction.BACK) }
        BarButton(Icons.Outlined.Circle, stringResource(Res.string.viewer_nav_home)) { onNavigate(NavAction.HOME) }
        BarButton(Icons.Outlined.CropSquare, stringResource(Res.string.viewer_nav_recents)) { onNavigate(NavAction.RECENTS) }
        BarButton(Icons.Outlined.Keyboard, stringResource(Res.string.viewer_keyboard), onClick = onToggleKeyboard)
        BarButton(Icons.Outlined.ContentPaste, stringResource(Res.string.viewer_paste), onClick = onPaste)
        BarButton(
            icon = Icons.Outlined.ZoomIn,
            description = stringResource(Res.string.viewer_zoom_mode),
            onClick = onToggleZoomMode,
            selected = zoomMode,
        )
        BarButton(Icons.Outlined.DoNotTouch, stringResource(Res.string.viewer_control_release), onClick = onRelease)
    }
}

@Composable
private fun RowScope.BarButton(icon: ImageVector, description: String, selected: Boolean? = null, onClick: () -> Unit) {
    if (selected == null) {
        IconButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = description, tint = Color.White)
        }
    } else {
        IconToggleButton(
            checked = selected,
            onCheckedChange = { onClick() },
            modifier = Modifier.weight(1f),
            colors = IconButtonDefaults.iconToggleButtonColors(
                contentColor = Color.White,
                checkedContentColor = Color.Black,
                checkedContainerColor = Color.White,
            ),
        ) {
            Icon(icon, contentDescription = description)
        }
    }
}

@Composable
private fun KeyboardBar(onType: (ControlMessage.Type) -> Unit, onEnter: () -> Unit) {
    var value by remember { mutableStateOf(emptyTypingBox()) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(
        value = value,
        onValueChange = { new ->
            val (edit, box) = TypingBox.edit(value.text, new.text)
            edit?.let(onType)
            value = if (box == new.text) new else TextFieldValue(box, TextRange(box.length))
        },
        placeholder = { Text(stringResource(Res.string.viewer_keyboard_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            onEnter()
            value = emptyTypingBox()
        }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

private fun emptyTypingBox() = TextFieldValue(TypingBox.EMPTY, TextRange(TypingBox.EMPTY.length))

@Composable
private fun TopControls(
    title: String,
    streamInfo: StreamInfo?,
    hasAudio: Boolean,
    muted: Boolean,
    control: ControlStatus,
    onToggleMute: () -> Unit,
    onRequestControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.8f), 0.6f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
            .safeDrawingPadding()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (streamInfo != null) {
                Text(
                    text = streamInfo.describe(),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (hasAudio) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(if (muted) Res.string.viewer_unmute else Res.string.viewer_mute),
                    tint = Color.White,
                )
            }
        }
        when {
            control.role == ControlRole.REQUESTED -> IconButton(onClick = onReleaseControl) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            }
            control.available && control.role == ControlRole.NONE -> IconButton(onClick = onRequestControl) {
                Icon(Icons.Outlined.TouchApp, stringResource(Res.string.viewer_control_request), tint = Color.White)
            }
        }
    }
}

@Composable
private fun StreamInfo.describe(): String = buildList {
    add("$width×$height")
    framesPerSecond?.let { add(stringResource(Res.string.viewer_info_fps, it)) }
    roundTripMillis?.let { add(stringResource(Res.string.viewer_info_latency, it)) }
}.joinToString(" · ")

@Composable
private fun Progress(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = Color.White)
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EndedMessage(reason: EndReason, onRetry: () -> Unit, onBack: () -> Unit) {
    val message = when (reason) {
        EndReason.CONNECTION_FAILED -> Res.string.viewer_end_connection_failed
        EndReason.INVALID_PIN -> Res.string.viewer_end_invalid_pin
        EndReason.TOO_MANY_ATTEMPTS -> Res.string.viewer_end_too_many_attempts
        EndReason.REJECTED -> Res.string.viewer_end_rejected
        EndReason.ROOM_FULL -> Res.string.viewer_end_room_full
        EndReason.NO_SESSION -> Res.string.viewer_end_no_session
        EndReason.KICKED -> Res.string.viewer_end_kicked
        EndReason.HOST_ENDED -> Res.string.viewer_end_host_ended
        EndReason.CONNECTION_LOST -> Res.string.viewer_end_connection_lost
        EndReason.UNSUPPORTED_VERSION -> Res.string.viewer_end_unsupported_version
    }
    val canRetry = reason == EndReason.CONNECTION_FAILED || reason == EndReason.CONNECTION_LOST
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        IconBadge(
            icon = when {
                canRetry -> Icons.Outlined.WifiOff
                reason == EndReason.HOST_ENDED -> Icons.Outlined.StopCircle
                else -> Icons.Outlined.Block
            },
            size = 72.dp,
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
        )
        Text(
            text = stringResource(message),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canRetry) {
                OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.action_back)) }
                Button(onClick = onRetry) { Text(stringResource(Res.string.viewer_retry)) }
            } else {
                Button(onClick = onBack) { Text(stringResource(Res.string.action_back)) }
            }
        }
    }
}

private const val CONTROLS_TIMEOUT_MILLIS = 3_000L
private const val NOTICE_TIMEOUT_MILLIS = 3_000L
private const val MAX_POINTERS = 10

private const val MAX_PASTE_LENGTH = 8_000
