package com.orbising.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbising.orbis.plugin.sdk.ApprovedPluginService

/** Keeps an unavailable plugin's persisted approval visible and revocable. */
@Composable
internal fun PluginMissingApprovals(controller: PluginControl, hostId: String?) {
    var selected by remember(controller, hostId) { mutableStateOf<ApprovedPluginService?>(null) }
    controller.missingApprovals.forEach { approval ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 6.dp)
                .testTag("plugin-missing-${approval.packageName}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(approval.packageName, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(L10n.tr("不可用"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NeumorphTextButton(L10n.tr("移除插件"), modifier = Modifier.testTag("plugin-missing-remove-${approval.packageName}"), dismissOnClick = false,
                onClick = { selected = approval })
        }
    }
    selected?.let { approval ->
        OrbisChoiceSheet(L10n.tr("移除插件？"), onDismiss = { selected = null }) {
            Text(L10n.tr("仅从 Orbis 中移除，插件 App 及其数据会保留。"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeumorphTextButton(L10n.tr("取消"), onClick = { selected = null },
                    modifier = Modifier.weight(1f), dismissOnClick = false)
                NeumorphTextButton(L10n.tr("移除插件"), onClick = {
                    controller.removeMissingApproval(approval)
                    selected = null
                }, modifier = Modifier.weight(1f).testTag("plugin-missing-confirm-${approval.packageName}"), danger = true, dismissOnClick = false)
            }
        }
    }
}
