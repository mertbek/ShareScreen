package com.mertbek.sharescreen.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mertbek.sharescreen.resources.*
import androidx.compose.runtime.collectAsState
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.link.ConnectLink
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import com.mertbek.sharescreen.platform.DiscoveredHost
import com.mertbek.sharescreen.link.PIN_LENGTH
import com.mertbek.sharescreen.link.ROOM_CODE_LENGTH
import com.mertbek.sharescreen.link.isIpv4Address
import com.mertbek.sharescreen.link.isValidPin
import com.mertbek.sharescreen.link.isValidRoomCode
import com.mertbek.sharescreen.ui.components.IconBadge
import com.mertbek.sharescreen.ui.components.ScreenPadding
import com.mertbek.sharescreen.ui.components.SectionTitle
import com.mertbek.sharescreen.ui.components.SoftCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    services: AppServices,
    onBack: () -> Unit,
    onConnect: (ConnectLink) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val browser = services.lanBrowser
    val hosts by (browser?.hosts ?: remember { MutableStateFlow(emptyList<DiscoveredHost>()) }).collectAsState()
    val settings by services.settings.settings.collectAsState()
    val internetServer = settings.internetServer
    var selectedHost by remember { mutableStateOf<DiscoveredHost?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val invalidQrMessage = stringResource(Res.string.discover_qr_invalid)
    var qrError by remember { mutableStateOf(false) }
    LaunchedEffect(qrError) {
        if (qrError) {
            snackbarHostState.showSnackbar(invalidQrMessage)
            qrError = false
        }
    }

    DisposableEffect(browser) {
        browser?.start()
        onDispose { browser?.stop() }
    }

    val scanQrCode = {
        services.ui.scanQr { link -> if (link != null) onConnect(link) else qrError = true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.home_watch_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (services.ui.canScanQr) {
                    FilledTonalButton(
                        onClick = scanQrCode,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(Res.string.discover_scan_qr))
                    }
                }
                NearbyHostsCard(hosts = hosts, onSelect = { selectedHost = it })
                InternetJoinCard(
                    server = internetServer,
                    onJoin = { roomCode, pin -> internetServer?.let { onConnect(ConnectLink.Internet(it, roomCode, pin)) } },
                    onOpenSettings = onOpenSettings,
                )
                ManualConnectCard(onConnect = { host, port, pin -> onConnect(ConnectLink.Lan(host, port, pin)) })
            }
        }
    }

    selectedHost?.let { host ->
        PinDialog(
            host = host,
            onConnect = { pin ->
                selectedHost = null
                onConnect(ConnectLink.Lan(host.host, host.port, pin, host.name))
            },
            onDismiss = { selectedHost = null },
        )
    }
}

@Composable
private fun NearbyHostsCard(hosts: List<DiscoveredHost>, onSelect: (DiscoveredHost) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(Res.string.discover_nearby_title), Modifier.weight(1f))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        SoftCard {
            if (hosts.isEmpty()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        icon = Icons.Outlined.WifiFind,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(Res.string.discover_nearby_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            hosts.forEachIndexed { index, host ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(host) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(Icons.Outlined.PhoneAndroid)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(host.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${host.host}:${host.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PinDialog(host: DiscoveredHost, onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
        title = { Text(host.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.discover_pin_prompt))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(PIN_LENGTH) },
                    label = { Text(stringResource(Res.string.discover_pin_label)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (isValidPin(pin)) onConnect(pin) }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(pin) }, enabled = isValidPin(pin)) {
                Text(stringResource(Res.string.discover_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun ManualConnectCard(onConnect: (host: String, port: Int, pin: String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val canConnect = isIpv4Address(host.trim()) && portNumber != null && isValidPin(pin)
    val connect = { if (canConnect) onConnect(host.trim(), portNumber, pin) }

    SoftCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Outlined.Keyboard,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Text(stringResource(Res.string.discover_manual_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(Res.string.discover_manual_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
                        label = { Text(stringResource(Res.string.discover_ip_label)) },
                        placeholder = { Text("192.168.1.20") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(Res.string.discover_port_label)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(PIN_LENGTH) },
                    label = { Text(stringResource(Res.string.discover_pin_label)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = connect, enabled = canConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(Res.string.discover_connect))
                }
            }
        }
    }
}

@Composable
private fun InternetJoinCard(
    server: String?,
    onJoin: (roomCode: String, pin: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var roomCode by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    val code = roomCode.uppercase()
    val canJoin = server != null && isValidRoomCode(code) && isValidPin(pin)
    val join = { if (canJoin) onJoin(code, pin) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.discover_internet_title))
        SoftCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (server == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            icon = Icons.Outlined.Language,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(Res.string.discover_internet_no_server),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.settings_title)) }
                    }
                    return@Column
                }
                Text(
                    text = stringResource(Res.string.discover_internet_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it.filter(Char::isLetterOrDigit).take(ROOM_CODE_LENGTH) },
                        label = { Text(stringResource(Res.string.discover_room_code_label)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(PIN_LENGTH) },
                        label = { Text(stringResource(Res.string.discover_pin_label)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { join() }),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(onClick = join, enabled = canJoin, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(Res.string.discover_internet_join))
                }
            }
        }
    }
}
