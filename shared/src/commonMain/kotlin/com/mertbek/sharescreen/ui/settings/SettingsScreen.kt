package com.mertbek.sharescreen.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mertbek.sharescreen.resources.*
import com.mertbek.sharescreen.settings.RememberedDevices
import com.mertbek.sharescreen.settings.SettingsRepository
import androidx.compose.runtime.collectAsState
import com.mertbek.sharescreen.link.ServerAddress
import com.mertbek.sharescreen.settings.VideoQuality
import com.mertbek.sharescreen.ui.components.IconBadge
import com.mertbek.sharescreen.ui.components.InitialAvatar
import com.mertbek.sharescreen.ui.components.ScreenPadding
import com.mertbek.sharescreen.ui.components.SectionTitle
import com.mertbek.sharescreen.ui.components.SoftCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    rememberedDevices: RememberedDevices?,
    canShareNearby: Boolean,
    canBeControlled: Boolean,
    versionName: String,
    privacyUrl: String?,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    val settings by repository.settings.collectAsState()
    val saveCustomServer = { input: String ->
        val server = if (input.isBlank()) null else ServerAddress.normalize(input)
        if (input.isBlank() || server != null) {
            repository.setCustomServer(server)
            true
        } else {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Section(stringResource(Res.string.settings_quality_title)) {
                    Column(Modifier.selectableGroup().padding(vertical = 8.dp)) {
                        VideoQuality.entries.forEach { quality ->
                            QualityOption(
                                quality = quality,
                                selected = quality == settings.quality,
                                onSelect = { repository.setQuality(quality) },
                            )
                        }
                    }
                }
                Section(stringResource(Res.string.settings_section_sharing)) {
                    SwitchRow(
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        title = stringResource(Res.string.settings_audio_title),
                        description = stringResource(Res.string.settings_audio_description),
                        checked = settings.shareAudio,
                        onCheckedChange = repository::setShareAudio,
                    )
                    if (canShareNearby) {
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(
                            icon = Icons.Outlined.Lock,
                            title = stringResource(Res.string.settings_lan_pin_title),
                            description = stringResource(Res.string.settings_lan_pin_description),
                            checked = settings.lanPin,
                            onCheckedChange = repository::setLanPin,
                        )
                    }
                    if (canBeControlled) {
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(
                            icon = Icons.Outlined.TouchApp,
                            title = stringResource(Res.string.settings_control_title),
                            description = stringResource(Res.string.settings_control_description),
                            checked = settings.allowRemoteControl,
                            onCheckedChange = repository::setAllowRemoteControl,
                        )
                    }
                }
                if (canShareNearby && rememberedDevices != null) {
                    Section(stringResource(Res.string.settings_remembered_title)) {
                        RememberedViewers(rememberedDevices, canBeControlled)
                    }
                }
                Section(stringResource(Res.string.settings_section_internet)) {
                    SwitchRow(
                        icon = Icons.Outlined.Language,
                        title = stringResource(Res.string.settings_internet_title),
                        description = stringResource(Res.string.settings_internet_description),
                        checked = settings.internetEnabled,
                        onCheckedChange = repository::setInternetEnabled,
                    )
                    AnimatedVisibility(visible = settings.internetEnabled) {
                        Column {
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            CustomServer(saved = settings.customServer, defaultServer = settings.defaultServer, onSave = saveCustomServer)
                        }
                    }
                }
                Text(
                    text = stringResource(Res.string.settings_applies_next_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Section(stringResource(Res.string.settings_section_about)) {
                    AboutRows(versionName, privacyUrl, onOpenUrl)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        SoftCard { content() }
    }
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun QualityOption(quality: VideoQuality, selected: Boolean, onSelect: () -> Unit) {
    val (title, description) = when (quality) {
        VideoQuality.LOW -> Res.string.settings_quality_low to Res.string.settings_quality_low_description
        VideoQuality.STANDARD -> Res.string.settings_quality_standard to Res.string.settings_quality_standard_description
        VideoQuality.HIGH -> Res.string.settings_quality_high to Res.string.settings_quality_high_description
    }
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(background, MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CustomServer(saved: String?, defaultServer: String?, onSave: (String) -> Boolean) {
    var text by rememberSaveable(saved) { mutableStateOf(saved.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val save = { invalid = !onSave(text) }

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Outlined.Dns,
                size = 44.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Text(stringResource(Res.string.settings_server_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = stringResource(Res.string.settings_server_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                invalid = false
            },
            label = { Text(stringResource(Res.string.settings_server_label)) },
            placeholder = { Text(defaultServer?.removePrefix("wss://") ?: "example.com") },
            singleLine = true,
            isError = invalid,
            shape = MaterialTheme.shapes.medium,
            supportingText = if (invalid) {
                { Text(stringResource(Res.string.settings_server_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = save, enabled = text.trim() != saved.orEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.settings_server_save))
            }
        }
    }
}

@Composable
private fun RememberedViewers(devices: RememberedDevices, canBeControlled: Boolean) {
    val viewers by devices.viewers.collectAsState()
    Text(
        text = stringResource(if (viewers.isEmpty()) Res.string.settings_remembered_empty else Res.string.settings_remembered_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
    viewers.forEach { viewer ->
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialAvatar(viewer.name, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            Text(viewer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = { devices.forgetViewer(viewer.key) }) {
                Text(stringResource(Res.string.settings_remembered_forget))
            }
        }
        if (canBeControlled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = viewer.control, role = Role.Switch, onValueChange = { devices.allowControl(viewer.key, it) })
                    .padding(start = 76.dp, end = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.settings_remembered_control),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = viewer.control, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun AboutRows(versionName: String, privacyUrl: String?, onOpenUrl: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Outlined.Info, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(Res.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (privacyUrl == null) return
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpenUrl(privacyUrl) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Outlined.PrivacyTip, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(Res.string.settings_privacy), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
