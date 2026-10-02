package com.mertbek.sharescreen.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mertbek.sharescreen.app.AppServices
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.resources.*
import com.mertbek.sharescreen.session.HostState
import org.jetbrains.compose.resources.stringResource

/**
 * The next watch or control request, looking like the dialog the app shows but drawn as a plain card,
 * for places where no dialog window can open, such as over other apps.
 */
@Composable
fun HostRequestCard(services: AppServices, modifier: Modifier = Modifier) {
    val hostState by services.host.state.collectAsState()
    val live = hostState as? HostState.Live ?: return
    val pending = live.pendingViewers.firstOrNull()
    val control = live.viewers.firstOrNull { it.control == ControlRole.REQUESTED }
    val watch = pending != null
    val viewerId = pending?.id ?: control?.id ?: return
    val deviceName = pending?.deviceName ?: control?.deviceName.orEmpty()
    val viaInternet = pending?.viaInternet ?: control?.viaInternet ?: false
    val canRemember = !viaInternet && services.rememberedDevices != null
    var rememberDevice by remember(viewerId, watch) { mutableStateOf(false) }
    val host = services.host

    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 560.dp),
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(24.dp)) {
            Icon(
                imageVector = if (watch) Icons.Outlined.PersonAdd else Icons.Outlined.TouchApp,
                contentDescription = null,
                tint = AlertDialogDefaults.iconContentColor,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(if (watch) Res.string.host_join_request_title else Res.string.host_control_request_title),
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val message = when {
                    !watch && services.inputInjector?.platform == HostPlatform.DESKTOP -> Res.string.host_control_request_message_desktop
                    !watch -> Res.string.host_control_request_message
                    viaInternet -> Res.string.host_join_request_message_internet
                    else -> Res.string.host_join_request_message
                }
                Text(
                    text = stringResource(message, deviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlertDialogDefaults.textContentColor,
                )
                if (canRemember) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = rememberDevice, role = Role.Checkbox, onValueChange = { rememberDevice = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = rememberDevice, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                when {
                                    watch -> Res.string.host_join_request_remember
                                    control?.remembered == true -> Res.string.host_control_request_remember
                                    else -> Res.string.host_control_request_remember_new
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlertDialogDefaults.textContentColor,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (watch) host.reject(viewerId) else host.denyControl(viewerId) }) {
                    Text(stringResource(Res.string.host_join_request_deny))
                }
                TextButton(onClick = { if (watch) host.approve(viewerId, rememberDevice) else host.grantControl(viewerId, rememberDevice) }) {
                    Text(stringResource(Res.string.host_join_request_allow))
                }
            }
        }
    }
}
