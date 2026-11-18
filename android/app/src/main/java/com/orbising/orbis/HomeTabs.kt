package com.orbising.orbis

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orbising.orbis.OrbisActionIcon as Icon
import com.orbising.orbis.plugin.sdk.PluginUiComponent

/** S32 / SC51: these are peer roots, not pages pushed over the chat list. */
internal enum class HomeTab(val key: String) { Chat("chat"), Providers("providers"), Settings("settings") }

/** Only the latest user navigation request can change the displayed page. */
internal class PluginNavigationRequests {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun cancel() { generation++ }
    fun isCurrent(request: Long): Boolean = generation == request
}

@Composable
internal fun HomeTabBar(selected: HomeTab, selectedPlugin: String? = null, onSelect: (HomeTab) -> Unit,
    onSelectPlugin: (String) -> Unit = {}, modifier: Modifier = Modifier,
    navigation: PluginNavigationRequests = remember { PluginNavigationRequests() }) {
    OrbisMaterialTheme {
        val haptic = rememberActionHaptic()
        val controller = LocalPluginControl.current
        val context = LocalContext.current
        LaunchedEffect(selected, selectedPlugin) { navigation.cancel() }
        DisposableEffect(navigation) { onDispose { navigation.cancel() } }
        controller?.allSessions?.forEach { session ->
            val notice = session.actionNotice
            LaunchedEffect(session, notice) {
                if (notice != null) {
                    android.widget.Toast.makeText(context, pluginNoticeText(notice), android.widget.Toast.LENGTH_SHORT).show()
                    session.consumeActionNotice(notice)
                }
            }
        }
        val pluginItemCount = controller?.activeSessions?.sumOf { session ->
            session.navigationSnapshot?.contributions?.count { it.component is PluginUiComponent.NavigationItem } ?: 0
        } ?: 0
        val itemCount = HomeTab.entries.size + pluginItemCount
        Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            controller?.let { PluginNavigationFeedback(it) }
            BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
                val contentWidth = if (itemCount <= 4) maxWidth else maxOf(maxWidth, 40.dp + 88.dp * itemCount + 12.dp * (itemCount - 1))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).width(contentWidth)
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
                        .selectableGroup().testTag("home-tabs"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HomeTab.entries.forEach { tab ->
                        val label = when (tab) {
                            HomeTab.Chat -> L10n.tr("聊天")
                            HomeTab.Providers -> L10n.tr("供应商")
                            HomeTab.Settings -> L10n.tr("设置")
                        }
                        val active = selectedPlugin == null && selected == tab
                        val interaction = remember { MutableInteractionSource() }
                        val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        NeumorphSurface(
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                                .testTag("home-tab-${tab.key}")
                                .selectable(selected = active, role = Role.Tab, interactionSource = interaction, indication = null) {
                                    navigation.cancel()
                                    if (!active) { haptic(); onSelect(tab) }
                                }.semantics { contentDescription = label },
                            shape = RoundedCornerShape(16.dp),
                            style = if (active) NeumorphStyle.Pressed else NeumorphStyle.Raised,
                            shadowScale = 0.4f,
                            interactionSource = interaction,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                androidx.compose.animation.Crossfade(active,
                                    animationSpec = androidx.compose.animation.core.tween(InteractionMotion.duration(InteractionMotion.State)), label = "tab-glyph") { filled ->
                                    Icon(when (tab) {
                                        HomeTab.Chat -> if (filled) OrbisSymbols.ChatBubbleFill else OrbisSymbols.ChatBubble
                                        HomeTab.Providers -> if (filled) OrbisSymbols.TuneFill else OrbisSymbols.Tune
                                        HomeTab.Settings -> if (filled) OrbisSymbols.SettingsFill else OrbisSymbols.Settings
                                    }, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                                }
                                // Give scaled/fallback glyphs the whole slot instead of a rounded natural-width box.
                                Text(label, modifier = Modifier.fillMaxWidth(), color = color,
                                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                    controller?.activeSessions?.forEach { session ->
                        val snapshot = session.navigationSnapshot ?: return@forEach
                        snapshot.contributions.forEach contributionLoop@ { contribution ->
                            val item = contribution.component as? PluginUiComponent.NavigationItem ?: return@contributionLoop
                            val handle = snapshot.handle(contribution.id) ?: return@contributionLoop
                            val interaction = remember { MutableInteractionSource() }
                            val pluginId = session.selected.componentName().flattenToString()
                            val active = selectedPlugin == pluginId
                            val enabled = session.navigationFeedback.canStart
                            val color = when {
                                !enabled -> LocalOrbisPalette.current.faint
                                active -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            NeumorphSurface(
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                                    .testTag("plugin-navigation")
                                    .selectable(selected = active, enabled = enabled, role = Role.Tab, interactionSource = interaction, indication = null) {
                                        if (active) navigation.cancel()
                                        else {
                                            haptic()
                                            val request = navigation.begin()
                                            session.navigate(handle, stillCurrent = { navigation.isCurrent(request) }) { onSelectPlugin(pluginId) }
                                        }
                                    }.semantics { contentDescription = item.label },
                                shape = RoundedCornerShape(16.dp), style = if (active) NeumorphStyle.Pressed else NeumorphStyle.Raised,
                                shadowScale = 0.4f, interactionSource = interaction,
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(pluginIcon(item.icon), contentDescription = null,
                                        tint = color, modifier = Modifier.size(22.dp))
                                    Text(item.label, modifier = Modifier.fillMaxWidth(), color = color,
                                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A pending navigation can outlive the current host or plugin session. */
@Composable
private fun PluginNavigationFeedback(controller: PluginControl) {
    val entry = controller.navigationFeedbacks.firstOrNull {
        it.feedback.state == PluginOperationState.Failed || it.feedback.state == PluginOperationState.Unknown
    } ?: return
    val state = entry.feedback.state ?: return
    val label = rememberPluginAppPresentation(entry.approval.packageName).label
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)
            .testTag("plugin-navigation-feedback"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluginOperationText(state), modifier = Modifier.testTag("plugin-navigation-feedback-status"),
                style = MaterialTheme.typography.bodySmall)
        }
        NeumorphTextButton(if (state == PluginOperationState.Unknown) L10n.tr("已检查") else L10n.tr("知道了"),
            onClick = entry.feedback::acknowledge, modifier = Modifier.testTag("plugin-navigation-feedback-acknowledge"),
            dismissOnClick = false)
    }
}
