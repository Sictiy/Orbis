package com.orbising.orbis

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbising.orbis.plugin.sdk.PluginServiceCandidate
import com.orbising.orbis.plugin.sdk.EmbeddedPageTheme
import com.orbising.orbis.plugin.sdk.EmbeddedSheet
import com.orbising.orbis.plugin.sdk.PluginUiComponent

internal val LocalPluginControl = staticCompositionLocalOf<PluginControl?> { null }

@Composable
internal fun PluginSettings(hostId: String?) {
    val controller = LocalPluginControl.current ?: return
    var approval by remember { mutableStateOf<PluginServiceCandidate?>(null) }
    var selected by remember { mutableStateOf<PluginServiceCandidate?>(null) }
    var managing by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var identityExpanded by remember { mutableStateOf(false) }
    val activeCount = controller.activeCount
    SettingsGroup(L10n.tr("插件"), padding = 8.dp, tag = "settings-plugins-group") {
        SettingsRow(L10n.tr("管理插件"), L10n.tr("已激活 {0} 个插件", activeCount), "settings-manage-plugins",
            onClick = { selected = null; controller.search(); managing = true })
    }
    if (managing) {
        val current = selected
        val name = current?.let { rememberPluginAppPresentation(it.peer.packageName).label }
        val approving = approval
        val sheetTitle = when {
            approving != null -> L10n.tr("允许激活插件？")
            confirmDisable -> L10n.tr("移除插件？")
            else -> name ?: L10n.tr("插件管理")
        }
        OrbisChoiceSheet(sheetTitle, onDismiss = { managing = false; selected = null; approval = null; confirmDisable = false },
            headerAction = if (current != null || approving != null || confirmDisable) { {
                NeumorphTextButton(L10n.tr("返回"), dismissOnClick = false, onClick = {
                    when { approving != null -> approval = null; confirmDisable -> confirmDisable = false; else -> selected = null }
                })
            } } else null) {
            if (approving != null) {
                Text(L10n.tr("激活后，插件可以显示界面、查看已配对主机名称和标识，并为当前主机提供操作。请确认插件来源后继续。"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(L10n.tr(if (identityExpanded) "收起插件身份" else "查看插件身份"),
                    modifier = Modifier.fillMaxWidth().clickable { identityExpanded = !identityExpanded }.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                if (identityExpanded) Text(L10n.tr("包名：{0}\n签名 SHA-256：{1}\n当前主机：{2}", approving.peer.packageName,
                    approving.peer.signerSha256, hostId ?: L10n.tr("（无）")), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeumorphTextButton(L10n.tr("取消"), onClick = { approval = null },
                        modifier = Modifier.weight(1f).testTag("plugin-approval-cancel"), dismissOnClick = false)
                    NeumorphTextButton(L10n.tr("允许并激活"), onClick = { approval = null; controller.requestUserActivation(approving) },
                        modifier = Modifier.weight(1f).testTag("plugin-approval-allow"), filled = true, dismissOnClick = false)
                }
            } else if (confirmDisable && current != null) {
                Text(L10n.tr("仅从 Orbis 中移除，插件 App 及其数据会保留。"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeumorphTextButton(L10n.tr("取消"), onClick = { confirmDisable = false }, modifier = Modifier.weight(1f), dismissOnClick = false)
                    NeumorphTextButton(L10n.tr("移除插件"), onClick = { controller.disable(current); confirmDisable = false; selected = null },
                        modifier = Modifier.weight(1f).testTag("plugin-confirm-remove"), danger = true, dismissOnClick = false)
                }
            } else if (current == null) {
                val plugins = (controller.candidates + controller.activeSessions.map { it.selected })
                    .distinctBy { it.peer.packageName to it.serviceClassName }
                if (plugins.isEmpty() && controller.missingApprovals.isEmpty()) {
                    Text(pluginNoticeText(controller.message), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    plugins.forEach { plugin ->
                        val presentation = rememberPluginAppPresentation(plugin.peer.packageName, includeIcon = true)
                        val active = controller.session(plugin)?.connected == true
                        val approved = controller.isApproved(plugin)
                        val approvedOnAnyHost = controller.isApprovedOnAnyHost(plugin)
                        val status = pluginActivationStatus(active, approved, controller.session(plugin)?.message)
                        val icon = presentation.icon
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("plugin-candidate")
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            else NeumorphSurface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), style = NeumorphStyle.Pressed) {
                                Icon(OrbisSymbols.Computer, null, modifier = Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(presentation.label, style = MaterialTheme.typography.titleMedium)
                                Text(status,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            NeumorphTextButton(if (active || approvedOnAnyHost) L10n.tr("管理") else L10n.tr("激活"),
                                modifier = Modifier.testTag("plugin-manage-${plugin.peer.packageName}"),
                                filled = !approvedOnAnyHost, dismissOnClick = false,
                                onClick = { if (active || approvedOnAnyHost) selected = plugin else { selected = plugin; identityExpanded = false; approval = plugin } })
                        }
                    }
                }
                PluginMissingApprovals(controller, hostId)
                TextButton(onClick = controller::search, modifier = Modifier.padding(top = 8.dp)) { Text(L10n.tr("重新查找")) }
            } else {
                val session = controller.session(current)
                val active = session?.connected == true
                val approved = controller.isApproved(current)
                val approvedOnAnyHost = controller.isApprovedOnAnyHost(current)
                val status = pluginActivationStatus(active, approved, session?.message)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("plugin-detail-status"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(L10n.tr("插件状态"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.labelLarge,
                        color = if (active) LocalOrbisPalette.current.success else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!active) {
                    SettingsRow(L10n.tr("激活插件"), "", "plugin-activate", onClick = {
                        if (approved) controller.requestUserActivation(current) else { identityExpanded = false; approval = current }
                    })
                }
                if (active || approvedOnAnyHost) SettingsRow(L10n.tr("移除插件"), "", "plugin-disconnect", onClick = { confirmDisable = true })
            }
        }
    }
}

private fun pluginActivationStatus(active: Boolean, approved: Boolean, notice: PluginNotice?): String = when {
    active -> L10n.tr("已激活")
    notice in setOf(PluginNotice.BindingFailed, PluginNotice.ConnectionFailed,
        PluginNotice.ConnectionDenied, PluginNotice.BindingRejected, PluginNotice.AuthorizationLost,
        PluginNotice.IdentityChangedSearch, PluginNotice.IdentityChanged, PluginNotice.InvalidHost) -> L10n.tr("连接失败")
    approved -> L10n.tr("等待连接")
    else -> L10n.tr("未激活")
}

@Composable
internal fun PluginRootPage(session: PluginSession) {
    if (android.os.Build.VERSION.SDK_INT < 30 || !session.connected || session.embeddedPageSupported == false) {
        LegacyPluginRootPage(session)
        return
    }
    if (session.embeddedPageSupported == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(L10n.tr("正在加载…"), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val darkTheme = LocalOrbisDarkTheme.current
    val palette = LocalOrbisPalette.current
    val theme = remember(darkTheme, palette) {
        EmbeddedPageTheme(darkTheme, palette.material.base.toArgb(), palette.material.lift.toArgb(),
            palette.ink.toArgb(), palette.muted.toArgb(), palette.accent.toArgb(), palette.success.toArgb(),
            palette.danger.toArgb(), palette.material.lightShadow.toArgb(), palette.material.darkShadow.toArgb(),
            palette.material.scrim.toArgb())
    }
    var failed by remember(session, session.connected) { mutableStateOf(false) }
    var controller by remember(session, session.connected) { mutableStateOf<PluginEmbeddedPage?>(null) }
    var pageCanGoBack by remember(session, session.connected) { mutableStateOf(false) }
    var sheet by remember(session, session.connected) { mutableStateOf<EmbeddedSheet?>(null) }
    androidx.activity.compose.BackHandler(enabled = pageCanGoBack && !failed) { controller?.back() }
    DisposableEffect(session, session.connected) { onDispose { controller?.close(); controller = null; session.embeddedPageVisible = false } }
    LaunchedEffect(controller, theme) { controller?.setTheme(theme) }
    if (failed) LegacyPluginRootPage(session)
    else androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("plugin-embedded-surface"),
        factory = { context ->
            android.view.SurfaceView(context).apply {
                setZOrderOnTop(true)
                controller = PluginEmbeddedPage(context, session, this, theme, isForeground = { lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }, ready = { session.embeddedPageVisible = true }, unavailable = { session.embeddedPageVisible = false; failed = true }, backStateChanged = { pageCanGoBack = it }, sheetChanged = { sheet = it }).also { it.start() }
            }
        },
    )
    sheet?.takeUnless { failed }?.let { current ->
        key(current.id) { EmbeddedPluginSheet(current) { action -> controller?.sheetAction(current.id, action) } }
    }
}

/** Renders only the generic, bounded presentation supplied by the current plugin page. */
@Composable
private fun EmbeddedPluginSheet(sheet: EmbeddedSheet, onAction: (String) -> Unit) {
    OrbisChoiceSheet(sheet.title, onDismiss = { onAction("dismiss") }, dismissible = sheet.dismissible) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("plugin-native-sheet")) {
            var index = 0
            while (index < sheet.items.size) {
                val item = sheet.items[index]
                when (item.kind) {
                    "heading" -> Text(item.title, style = MaterialTheme.typography.titleMedium)
                    "text" -> Text(item.title, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    "choice" -> ChoiceRow(item.title, item.detail, item.selected, "plugin-sheet-choice-${item.action}",
                        enabled = item.enabled, onClick = { onAction(item.action) })
                    "button" -> {
                        val next = sheet.items.getOrNull(index + 1)?.takeIf { it.kind == "button" }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NeumorphTextButton(item.title, onClick = { onAction(item.action) }, modifier = Modifier.weight(1f),
                                enabled = item.enabled, danger = item.danger, filled = item.primary, dismissOnClick = false)
                            if (next != null) NeumorphTextButton(next.title, onClick = { onAction(next.action) }, modifier = Modifier.weight(1f),
                                enabled = next.enabled, danger = next.danger, filled = next.primary, dismissOnClick = false)
                        }
                        if (next != null) index++
                    }
                }
                index++
            }
        }
    }
}

@Composable
private fun LegacyPluginRootPage(session: PluginSession) {
    val page = session.page
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            session.refreshPage()
            kotlinx.coroutines.delay(1500)
            while (true) { session.refreshPage(); kotlinx.coroutines.delay(5_000) }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        RemoteTopAppBar(title = { Text(page?.title ?: L10n.tr("插件")) })
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val error = session.pageError
            if (error != null) {
                Text(pluginNoticeText(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                NeumorphTextButton(L10n.tr("重试"), onClick = { session.refreshPage() })
            }
            if (page == null && error == null) Text(L10n.tr("正在加载…"), style = MaterialTheme.typography.bodyMedium)
            if (page != null) {
                if (page.rows.isEmpty()) Text(page.message, style = MaterialTheme.typography.bodyMedium)
                else SettingsGroup(page.message, padding = 8.dp, tag = "plugin-page-content") {
                    page.rows.forEachIndexed { index, row ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp).testTag("plugin-page-row-$index"),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(row.title, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                            Text(row.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (page.settings) NeumorphTextButton(L10n.tr("插件设置"), onClick = session::openSetup)
            }
        }
    }
}

@Composable
internal fun PluginHostFeedback(hostId: String?) {
    val controller = LocalPluginControl.current ?: return
    if (hostId == null || controller.hostId != hostId) return
    val sessions = controller.allSessions.filter {
        it.feedback.state == PluginOperationState.Failed || it.feedback.state == PluginOperationState.Unknown
    }
    if (sessions.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
        sessions.forEach { session ->
            val state = session.feedback.state ?: return@forEach
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val label = rememberPluginAppPresentation(session.selected.peer.packageName).label
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pluginOperationText(state), modifier = Modifier.testTag("plugin-operation-status"), style = MaterialTheme.typography.bodySmall)
                NeumorphTextButton(if (state == PluginOperationState.Unknown) L10n.tr("已检查") else L10n.tr("知道了"), onClick = session.feedback::acknowledge)
            }
        }
    }
}

@Composable
internal fun PluginHostActions(hostId: String?, selectedSession: PluginSession) {
    val controller = LocalPluginControl.current ?: return
    if (hostId == null || controller.hostId != hostId) return
    val session = selectedSession
    val snapshot = session.hostActionSnapshot?.takeIf { it.hostId == hostId } ?: return
    snapshot.contributions.forEach contributionLoop@ { contribution ->
        val action = contribution.component as? PluginUiComponent.IconAction ?: return@contributionLoop
        val handle = snapshot.handle(contribution.id)
        NeumorphTextButton(action.label, onClick = { if (handle != null) session.performHostAction(handle) }, icon = pluginIcon(action.icon),
            modifier = Modifier.widthIn(max = 144.dp).testTag("plugin-host-action"), enabled = action.enabled && handle != null && session.feedback.canStart)
    }
}

internal data class PluginHostContributions(
    val session: PluginSession,
    val snapshot: com.orbising.orbis.plugin.sdk.PluginUiSnapshots.Snapshot?,
    val hasAction: Boolean,
) {
    val statuses: List<PluginUiComponent.StatusBadge>
        get() = snapshot?.contributions.orEmpty().mapNotNull { it.component as? PluginUiComponent.StatusBadge }

    val canDisplayInline: Boolean
        get() = snapshot?.contributions.orEmpty().all { it.component is PluginUiComponent.StatusBadge } && statuses.size <= 1
}

@Composable
internal fun pluginHostContributions(hostId: String?): List<PluginHostContributions> {
    val controller = LocalPluginControl.current ?: return emptyList()
    if (hostId == null || controller.hostId != hostId) return emptyList()
    return controller.activeSessions.mapNotNull { session ->
        val snapshot = session.snapshot?.takeIf { it.hostId == hostId }
        val hasAction = session.hostActionSnapshot?.takeIf { it.hostId == hostId }?.contributions.orEmpty()
            .any { it.component is PluginUiComponent.IconAction }
        if (snapshot?.contributions.isNullOrEmpty() && !hasAction) null else PluginHostContributions(session, snapshot, hasAction)
    }
}

/** A compact list is used only when the footer has multiple contributions to distinguish. */
@Composable
internal fun PluginHostContributionRows(hostId: String?, groups: List<PluginHostContributions>) {
    Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
        groups.forEach { group ->
            val packageName = group.session.selected.peer.packageName
            val label = rememberPluginAppPresentation(packageName).label
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (group.hasAction) PluginHostActions(hostId, group.session)
                }
                group.snapshot?.let { snapshot ->
                    PluginHostPanelItems(snapshot, group.session.feedback.canStart, group.session::performHostAction)
                }
            }
        }
    }
}

internal fun pluginNoticeText(notice: PluginNotice): String = when (notice) {
    PluginNotice.SearchInstalled -> L10n.tr("搜索已安装的插件")
    PluginNotice.Searching -> L10n.tr("正在查找插件…")
    PluginNotice.NoneFound -> L10n.tr("未发现兼容的插件")
    PluginNotice.Choose -> L10n.tr("选择插件")
    PluginNotice.Connecting -> L10n.tr("正在连接插件…")
    PluginNotice.InvalidHost -> L10n.tr("主机标识不受插件协议支持")
    PluginNotice.BindingFailed -> L10n.tr("无法绑定插件；请检查插件与 Orbis 的签名及兼容性")
    PluginNotice.ConnectedNoHost -> L10n.tr("插件已连接；当前没有已配对主机")
    PluginNotice.Connected -> L10n.tr("插件已连接")
    PluginNotice.Disconnected -> L10n.tr("插件已断开")
    PluginNotice.BindingDied -> L10n.tr("插件已更新或断开，请重新授权")
    PluginNotice.BindingRejected -> L10n.tr("插件拒绝绑定")
    PluginNotice.ConnectionFailed -> L10n.tr("无法连接插件")
    PluginNotice.ConnectionDenied -> L10n.tr("系统拒绝连接插件")
    PluginNotice.AuthorizationLost -> L10n.tr("插件已断开或授权失效，请重新连接")
    PluginNotice.StateChangedReconnect -> L10n.tr("状态已变化，请重新连接")
    PluginNotice.PageOpenFailed -> L10n.tr("无法打开插件页面")
    PluginNotice.InvalidPageResponse -> L10n.tr("插件页面响应无效")
    PluginNotice.StateChangedRefresh -> L10n.tr("状态已变化，请刷新")
    PluginNotice.IdentityChangedSearch -> L10n.tr("插件身份已变化，请重新查找")
    PluginNotice.IdentityChanged -> L10n.tr("插件身份已变化，请重新授权")
    PluginNotice.SetupFailed -> L10n.tr("无法打开插件设置")
    PluginNotice.PageReadFailed -> L10n.tr("无法读取插件页面，请重试")
}

internal fun pluginOperationText(state: PluginOperationState): String = when (state) {
    PluginOperationState.Submitting -> L10n.tr("正在提交")
    PluginOperationState.Waiting -> L10n.tr("正在等待确认")
    PluginOperationState.Succeeded -> L10n.tr("操作已完成")
    PluginOperationState.Failed -> L10n.tr("操作未成功，请检查操作状态")
    PluginOperationState.Unknown -> L10n.tr("结果未知，请先检查操作状态")
}
