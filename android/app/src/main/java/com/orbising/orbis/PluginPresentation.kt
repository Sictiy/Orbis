package com.orbising.orbis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbising.orbis.plugin.sdk.PluginUiComponent
import com.orbising.orbis.plugin.sdk.PluginUiSnapshots

/** Map the protocol's finite vocabulary to host-owned icons and palette roles. */
internal fun pluginIcon(name: String): ImageVector = when (name) {
    "info" -> OrbisSymbols.Info
    "settings" -> OrbisSymbols.Settings
    "power" -> Icons.Rounded.PowerSettingsNew
    "refresh" -> OrbisSymbols.Refresh
    "link" -> Icons.Rounded.Link
    "more" -> OrbisSymbols.MoreHoriz
    "play" -> Icons.Rounded.PlayArrow
    "check" -> OrbisSymbols.Check
    "warning" -> OrbisSymbols.Warning
    "device" -> OrbisSymbols.Computer
    else -> OrbisSymbols.Info
}

internal fun pluginToneColor(tone: String, palette: OrbisPalette): Color = when (tone) {
    "info" -> palette.accent
    "success" -> palette.success
    "warning" -> palette.warning
    "error" -> palette.danger
    else -> palette.muted
}

/** Render a validated host-panel snapshot as compact rows, without a plugin-specific card. */
@Composable
internal fun PluginHostPanelItems(
    snapshot: PluginUiSnapshots.Snapshot,
    canStart: Boolean,
    onAction: (PluginUiSnapshots.ActionHandle) -> Unit,
) {
    val palette = LocalOrbisPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        snapshot.contributions.forEach { contribution ->
            val handle = snapshot.handle(contribution.id)
            when (val component = contribution.component) {
                is PluginUiComponent.TextRow -> PluginHostText(component.title, component.description)
                is PluginUiComponent.StatusBadge -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val color = pluginToneColor(component.tone, palette)
                    Box(Modifier.size(5.dp).background(color, CircleShape))
                    Text(component.label, Modifier.testTag("plugin-host-status"),
                        style = MaterialTheme.typography.labelSmall, color = color)
                }
                is PluginUiComponent.StatusCard -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(component.title, color = pluginToneColor(component.tone, palette),
                            style = MaterialTheme.typography.labelMedium)
                        PluginHostDescription(component.description)
                    }
                    component.action?.let { action ->
                        NeumorphTextButton(action.label, onClick = { handle?.let(onAction) },
                            modifier = Modifier.testTag("plugin-host-contribution-${contribution.id}"),
                            enabled = action.enabled && canStart && handle != null)
                    }
                }
                is PluginUiComponent.SectionAction -> NeumorphSurface(
                    Modifier.fillMaxWidth().heightIn(min = RemoteUi.TouchTarget)
                        .testTag("plugin-host-contribution-${contribution.id}"),
                    shape = RoundedCornerShape(12.dp), shadowScale = 0.4f, flat = true,
                    enabled = component.enabled && canStart && handle != null,
                    onClick = { handle?.let(onAction) },
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(component.label, style = MaterialTheme.typography.labelMedium)
                            PluginHostDescription(component.description)
                        }
                        Icon(OrbisSymbols.ChevronRight, contentDescription = null,
                            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is PluginUiComponent.IconAction, is PluginUiComponent.NavigationItem -> Unit
            }
        }
    }
}

@Composable
private fun PluginHostText(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PluginHostDescription(description)
    }
}

@Composable
private fun PluginHostDescription(description: String) {
    if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
