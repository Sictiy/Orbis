package com.orbising.orbis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.orbising.orbis.plugin.sdk.*
import java.util.concurrent.Executors
import kotlinx.serialization.json.*

internal enum class PluginNotice { SearchInstalled, Searching, NoneFound, Choose, Connecting, InvalidHost, BindingFailed, ConnectedNoHost, Connected, Disconnected, BindingDied, BindingRejected, ConnectionFailed, ConnectionDenied, AuthorizationLost, StateChangedReconnect, PageOpenFailed, InvalidPageResponse, StateChangedRefresh, IdentityChangedSearch, IdentityChanged, SetupFailed, PageReadFailed }

internal data class PluginRestorationActivation(val candidate: PluginServiceCandidate, val intent: Intent)
internal class PluginUserActivation internal constructor(val candidate: PluginServiceCandidate)

/** Owns independent bindings and UI state for every activated plugin. */
internal class PluginControl(private val context: Context, val hostId: String?,
    private val onRemoved: (ComponentName) -> Unit = {},
    private val onApprovalsChanged: () -> Unit = {},
    private val onFeedbackChanged: () -> Unit = {},
    private val navigationFeedbackRegistry: PluginNavigationFeedbackRegistry = PluginNavigationFeedbackRegistry(context, onFeedbackChanged),
    internal val activationCoordinator: PluginActivationCoordinator = PluginActivationCoordinator(),
    private val operationWaitLimitMs: Long = PluginOperationProtocol.WAIT_LIMIT_MS,
    private val discover: () -> List<PluginServiceCandidate> = { PluginServiceDiscovery.find(context.packageManager) }) : AutoCloseable {
    var candidates by mutableStateOf<List<PluginServiceCandidate>>(emptyList()); private set
    var message by mutableStateOf(PluginNotice.SearchInstalled); private set
    private val sessions = mutableStateMapOf<ComponentName, PluginSession>()
    private val approvals = PluginApprovalStore(context)
    private var savedApprovals by mutableStateOf(approvals.forHost(hostId))
    private var allApprovals by mutableStateOf(approvals.allApprovals())
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var suspended = false
    private var restoring = false
    private var retryDelayMs = 1_000L
    private val restorationActivationAttempts = mutableSetOf<ComponentName>()
    private val userActivations = mutableMapOf<ComponentName, PluginUserActivation>()
    private var restorationQueue by mutableStateOf<List<PluginRestorationActivation>>(emptyList())
    private var restoringActivation by mutableStateOf<PluginRestorationActivation?>(null)
    val nextRestorationActivation: PluginRestorationActivation?
        get() = if (restoringActivation == null) restorationQueue.firstOrNull() else null
    private val retryRestoration = Runnable { if (!closed) restoreApprovals() }
    val allSessions: List<PluginSession> get() = sessions.values.sortedBy { it.selected.componentName().flattenToString() }
    val activeSessions: List<PluginSession> get() = allSessions.filter { it.connected }
    val activeCount: Int get() = activeSessions.size
    val navigationFeedbacks: List<PluginNavigationFeedbackEntry> get() = navigationFeedbackRegistry.entries
    val missingApprovals: List<ApprovedPluginService> get() {
        val available = candidates + activeSessions.map { it.selected }
        return allApprovals.filter { approval -> available.none(approval::matches) }
            .sortedWith(compareBy({ it.packageName }, { it.className }, { it.signerSha256 }))
            .distinctBy { it.packageName to it.className }
    }
    val hasFeedback: Boolean get() = allSessions.any {
        it.feedback.state in setOf(PluginOperationState.Submitting, PluginOperationState.Waiting, PluginOperationState.Failed, PluginOperationState.Unknown)
    }
    val hasPendingOperation: Boolean get() = allSessions.any { it.hasPendingOperation }

    init { restoreApprovals() }

    private fun scheduleRestorationRetry() {
        if (closed || suspended || savedApprovals.isEmpty()) return
        handler.removeCallbacks(retryRestoration)
        handler.postDelayed(retryRestoration, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000)
    }

    private fun restoreApprovals() {
        val saved = savedApprovals
        if (closed || suspended || saved.isEmpty() || restoring) return
        restoring = true
        worker.execute {
            val discovered = runCatching { discover() }.getOrNull()
            // Some systems refuse to bind a force-stopped package until one of its
            // Activities is launched. Resolve that optional entry point off the UI thread.
            val activationIntents = discovered.orEmpty().mapNotNull { candidate ->
                if (saved.none { it.matches(candidate) } || !isPackageStopped(candidate)) null
                else trustedActivationIntent(candidate)?.let { candidate to it }
            }.toMap()
            handler.post {
                restoring = false
                if (!closed && !suspended) {
                    var missing = false
                    saved.forEach { approval ->
                        // A user may have disabled the plugin while discovery was in progress.
                        if (approval !in savedApprovals) return@forEach
                        val candidate = discovered?.singleOrNull { approval.matches(it) }
                        if (candidate != null) {
                            if (!activationCoordinator.canRestore(candidate)) return@forEach
                            val activation = activationIntents[candidate]
                            val alreadyQueued = restoringActivation?.candidate == candidate || restorationQueue.any { it.candidate == candidate }
                            if (alreadyQueued) return@forEach
                            if (activation != null && restorationActivationAttempts.add(candidate.componentName())) {
                                restorationQueue = restorationQueue + PluginRestorationActivation(candidate, activation)
                                return@forEach
                            }
                            val existing = sessions[candidate.componentName()]
                            if (existing == null || !existing.connected || existing.selected == candidate) getOrCreate(candidate).connectIfIdle()
                        } else missing = true
                    }
                    if (missing) scheduleRestorationRetry()
                }
            }
        }
    }

    fun session(candidate: PluginServiceCandidate): PluginSession? = sessions[candidate.componentName()]
        ?.takeIf { it.selected == candidate }
    fun isApproved(candidate: PluginServiceCandidate): Boolean = savedApprovals.any { it.matches(candidate) }
    fun isApprovedOnAnyHost(candidate: PluginServiceCandidate): Boolean = allApprovals.any { it.matches(candidate) }

    internal fun reloadApprovals() {
        if (closed) return
        savedApprovals = approvals.forHost(hostId)
        allApprovals = approvals.allApprovals()
    }

    private fun getOrCreate(candidate: PluginServiceCandidate): PluginSession {
        val key = candidate.componentName()
        val existing = sessions[key]
        if (existing != null && existing.selected == candidate) return existing
        val approval = ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName)
        val feedback = PluginOperationFeedback(
            PluginPendingOperationStore(context, hostId, approval),
            io = PluginPendingOperationIo.executor,
            dispatch = { handler.post(it) },
        ) {
            // Let the response handler finish before an inactive controller is suspended.
            handler.post { if (!closed) onFeedbackChanged() }
        }
        existing?.close()
        return PluginSession(context, hostId, candidate, feedback, navigationFeedbackRegistry.forService(approval), operationWaitLimitMs,
            onConnectionLost = {
                restorationActivationAttempts.remove(candidate.componentName())
                scheduleRestorationRetry()
            }, onActivated = { approval ->
                retryDelayMs = 1_000
                restorationActivationAttempts.remove(candidate.componentName())
                activationCoordinator.connected(approval)
                approvals.save(hostId, approval)
                reloadApprovals()
                onApprovalsChanged()
            }).also { sessions[key] = it }
    }

    fun search() {
        if (closed || suspended) return
        message = PluginNotice.Searching
        worker.execute {
            val found = discover()
            handler.post { if (!closed) { candidates = found; message = if (found.isEmpty()) PluginNotice.NoneFound else PluginNotice.Choose } }
        }
    }
    fun connect(candidate: PluginServiceCandidate) {
        if (!closed && !suspended) getOrCreate(candidate).connect(candidate)
    }

    private fun isPackageStopped(candidate: PluginServiceCandidate): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(candidate.peer.packageName, 0).flags and ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(false)

    /** Only a protected, same-signer Activity may be used as an activation entry point. */
    @Suppress("DEPRECATION")
    private fun trustedActivationIntent(candidate: PluginServiceCandidate): Intent? {
            val pm = context.packageManager
            return if (pm.checkSignatures(context.packageName, candidate.peer.packageName) == PackageManager.SIGNATURE_MATCH) {
                val query = Intent(PluginProtocol.ACTIVATE_ACTION).setPackage(candidate.peer.packageName)
                val activities = runCatching { pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
                val activity = activities.mapNotNull { it.activityInfo }.singleOrNull { info ->
                    val permission = info.permission ?: return@singleOrNull false
                    val definition = runCatching { pm.getPermissionInfo(permission, 0) }.getOrNull() ?: return@singleOrNull false
                    info.packageName == candidate.peer.packageName && info.exported && info.enabled &&
                        (definition.packageName == candidate.peer.packageName ||
                            (permission == context.packageName + PluginProtocol.HOST_PERMISSION_SUFFIX && definition.packageName == context.packageName)) &&
                        definition.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_SIGNATURE &&
                        pm.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
                }
                activity?.let { query.setComponent(ComponentName(it.packageName, it.name)) }
            } else null
    }

    /** Freshly validate a manually requested activation before launching its Activity. */
    fun prepareUserActivation(candidate: PluginServiceCandidate, ready: (PluginUserActivation, Intent?) -> Unit) {
        if (closed || suspended || userActivations.containsKey(candidate.componentName())) return
        val activation = PluginUserActivation(candidate)
        userActivations[candidate.componentName()] = activation
        val identity = ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName)
        worker.execute {
            val fresh = runCatching { discover().singleOrNull { identity.matches(it) } }.getOrNull()
            val intent = fresh?.let(::trustedActivationIntent)
            handler.post {
                if (isCurrentActivation(activation)) {
                    if (fresh == null) {
                        userActivations.remove(candidate.componentName())
                        getOrCreate(candidate).activationIdentityChanged()
                    } else ready(activation, intent)
                }
            }
        }
    }

    fun requestUserActivation(candidate: PluginServiceCandidate) {
        prepareUserActivation(candidate) { activation, intent ->
            if (intent == null) connectAfterUserActivation(activation)
            else activationCoordinator.enqueueManual(this, activation, intent)
        }
    }

    /** Claim only when the host is resumed; never reactivate a removed approval. */
    fun beginRestorationActivation(activation: PluginRestorationActivation): Boolean {
        if (closed || suspended || nextRestorationActivation !== activation || !isApproved(activation.candidate)) return false
        restorationQueue = restorationQueue.drop(1)
        restoringActivation = activation
        return true
    }

    internal fun isCurrentRestorationActivation(activation: PluginRestorationActivation): Boolean =
        !closed && !suspended && restoringActivation === activation && isApproved(activation.candidate)

    fun finishRestorationActivation(activation: PluginRestorationActivation, succeeded: Boolean, retry: Boolean = true) {
        if (restoringActivation !== activation) return
        restoringActivation = null
        if (closed || suspended || !isApproved(activation.candidate)) return
        if (!succeeded) {
            restorationActivationAttempts.remove(activation.candidate.componentName())
            if (retry) scheduleRestorationRetry() else getOrCreate(activation.candidate).activationFailed()
            return
        }
        val identity = ApprovedPluginService(activation.candidate.peer.packageName, activation.candidate.peer.signerSha256, activation.candidate.serviceClassName)
        worker.execute {
            val fresh = runCatching { discover().singleOrNull { identity.matches(it) } }.getOrNull()
            handler.post {
                if (!closed && !suspended && fresh != null && isApproved(fresh)) getOrCreate(fresh).connectIfIdle()
                else if (!closed && !suspended) {
                    restorationActivationAttempts.remove(activation.candidate.componentName())
                    scheduleRestorationRetry()
                }
            }
        }
    }

    internal fun isCurrentActivation(activation: PluginUserActivation): Boolean =
        !closed && !suspended && userActivations[activation.candidate.componentName()] === activation

    fun connectAfterUserActivation(activation: PluginUserActivation) {
        if (!isCurrentActivation(activation)) return
        val candidate = activation.candidate
        val identity = ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName)
        worker.execute {
            val fresh = runCatching { discover().singleOrNull { identity.matches(it) } }.getOrNull()
            handler.post { if (isCurrentActivation(activation)) {
                userActivations.remove(candidate.componentName())
                if (fresh == null) getOrCreate(candidate).activationIdentityChanged()
                else getOrCreate(fresh).connect(fresh)
            } }
        }
    }
    fun activationFailed(activation: PluginUserActivation) {
        if (!isCurrentActivation(activation)) return
        userActivations.remove(activation.candidate.componentName())
        getOrCreate(activation.candidate).activationFailed()
    }
    fun disable(candidate: PluginServiceCandidate) {
        removeService(candidate.componentName())
    }
    fun removeMissingApproval(approval: ApprovedPluginService) {
        removeService(ComponentName(approval.packageName, approval.className))
    }
    private fun removeService(component: ComponentName) {
        if (closed) return
        approvals.removeService(component.packageName, component.className)
        removedFromOtherHost(component)
        onRemoved(component)
    }
    internal fun removedFromOtherHost(component: ComponentName) {
        reloadApprovals()
        userActivations.remove(component)
        restorationQueue = restorationQueue.filterNot { it.candidate.componentName() == component }
        if (restoringActivation?.candidate?.componentName() == component) restoringActivation = null
        restorationActivationAttempts.remove(component)
        sessions[component]?.disable()
    }
    fun refresh() {
        allSessions.forEach { it.refresh() }
        // Rediscover before reconnecting: an approval applies to a specific signer,
        // not merely to a package name that may have been replaced.
        restoreApprovals()
    }
    fun refreshHostContributions() { allSessions.forEach { it.refreshHostContributions() } }
    /** Disconnect an inactive Host after its pending operations settle, keeping result feedback. */
    fun suspendConnections() {
        if (closed || suspended) return
        check(!hasPendingOperation)
        suspended = true
        userActivations.clear()
        handler.removeCallbacks(retryRestoration)
        restorationQueue = emptyList()
        restoringActivation = null
        restorationActivationAttempts.clear()
        allSessions.forEach { it.disconnect() }
    }

    fun resumeConnections() {
        if (closed || !suspended) return
        suspended = false
        restoreApprovals()
    }
    override fun close() {
        closed = true
        userActivations.clear()
        restorationQueue = emptyList()
        restoringActivation = null
        allSessions.forEach { it.close() }
        sessions.clear()
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }
}

internal data class PluginPageRow(val title: String, val value: String)
internal data class PluginPage(val title: String, val message: String, val rows: List<PluginPageRow>, val settings: Boolean)

internal fun parsePluginPage(payload: JsonObject?): PluginPage? {
    if (payload == null) return null
    fun field(obj: JsonObject?, name: String, max: Int): String? = (obj?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() && it.length <= max && it.none(Char::isISOControl) }
    val title = field(payload, "title", 120); val text = field(payload, "message", 512)
    val version = payload["schemaVersion"]
    val rows = if (version == JsonPrimitive(2)) {
        (payload["rows"] as? JsonArray)?.takeIf { it.size <= 64 }?.map { element ->
            val row = element as? JsonObject
            val rowTitle = field(row, "title", 120); val value = field(row, "value", 120)
            if (row?.keys != setOf("title", "value") || rowTitle == null || value == null) null else PluginPageRow(rowTitle, value)
        }?.takeIf { it.none { row -> row == null } }?.filterNotNull()
    } else if (version == JsonPrimitive(1)) emptyList() else null
    val settings = if (version == JsonPrimitive(2)) (payload["settings"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull else false
    val keysValid = if (version == JsonPrimitive(2)) payload.keys == setOf("schemaVersion", "title", "message", "rows", "settings")
        else payload.keys == setOf("schemaVersion", "title", "message")
    if (!keysValid || title == null || text == null || rows == null || settings == null) return null
    return PluginPage(title, text, rows, settings)
}

/** A binding, authorization and snapshots belong to one plugin only. */
internal class PluginSession(private val context: Context, private val hostId: String?, val selected: PluginServiceCandidate,
    val feedback: PluginOperationFeedback = PluginOperationFeedback(),
    val navigationFeedback: PluginOperationFeedback = PluginOperationFeedback(),
    private val operationWaitLimitMs: Long = PluginOperationProtocol.WAIT_LIMIT_MS,
    private val onConnectionLost: () -> Unit = {},
    private val onActivated: (ApprovedPluginService) -> Unit) : AutoCloseable {
    var message by mutableStateOf(PluginNotice.SearchInstalled); private set
    var snapshot by mutableStateOf<PluginUiSnapshots.Snapshot?>(null); private set
    var hostActionSnapshot by mutableStateOf<PluginUiSnapshots.Snapshot?>(null); private set
    var navigationSnapshot by mutableStateOf<PluginUiSnapshots.Snapshot?>(null); private set
    var actionNotice by mutableStateOf<PluginNotice?>(null); private set
    var page by mutableStateOf<PluginPage?>(null); private set
    var pageError by mutableStateOf<PluginNotice?>(null); private set
    private var pageReading = false
    private val handler = Handler(Looper.getMainLooper())
    // Detached binding opens/closes may finish after the session is closed.
    private val teardownHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    private var connection: ServiceConnection? = null
    private var connectTimeout: Runnable? = null
    private var channel: PluginCallChannel? = null
    private var bootstrap: PluginCallChannel? = null
    private var registry: PluginUiSnapshots? = null
    private var approved: ApprovedPluginService? = null
    private var candidate: PluginServiceCandidate? = null
    internal var embeddedEntryPoint: String = "navigation.bottom_tab"
    internal val embeddedBindingId: String? get() = channel?.bindingId
    internal val embeddedApproval: ApprovedPluginService? get() = approved
    var embeddedPageSupported by mutableStateOf<Boolean?>(null); private set

    private class ActionAttempt(
        val channel: PluginCallChannel,
        val actionHostId: String?,
        val feedback: PluginOperationFeedback,
        val navigation: Boolean,
        val slot: PluginUiSlot,
        val stillCurrent: () -> Boolean,
        val onPage: () -> Unit,
        var operationId: String? = null,
        var legacyRead: Boolean = false,
        var deadlineMs: Long = 0,
        var timeout: Runnable? = null,
    )
    private var hostAttempt: ActionAttempt? = null
    private var navigationAttempt: ActionAttempt? = null
    val hasPendingOperation: Boolean get() = hostAttempt != null || navigationAttempt != null ||
        feedback.state in setOf(PluginOperationState.Submitting, PluginOperationState.Waiting)
    private val readsInFlight = mutableSetOf<PluginUiSlot>()
    var connected by mutableStateOf(false); private set
    var embeddedPageVisible by mutableStateOf(false); internal set
    private val host = object : IOrbisHost.Stub() {
        // This binding only grants UI. No callbacks from the plugin are accepted.
        override fun request(envelopeJson: String?, reply: IPluginReply?) = Unit
        override fun cancel(bindingId: String?, requestId: String?) = Unit
    }

    fun activationIdentityChanged() { message = PluginNotice.IdentityChangedSearch }
    fun activationFailed() { message = PluginNotice.ConnectionFailed }

    fun connectIfIdle() {
        if (connection == null && !connected) connect(selected)
    }

    fun connect(selected: PluginServiceCandidate) {
        disconnect()
        message = PluginNotice.Connecting
        val approval = ApprovedPluginService(selected.peer.packageName, selected.peer.signerSha256, selected.serviceClassName)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (closed || connection !== this || binder == null) return
                connectTimeout?.let(handler::removeCallbacks); connectTimeout = null
                val plugin = IOrbisPlugin.Stub.asInterface(binder)
                val initial = PluginCallChannel(context.packageManager, selected, approval, plugin, null, null)
                val scopePayload = buildJsonObject {
                    put("grants", buildJsonArray { add(JsonPrimitive("ui")) })
                    put("hostIds", buildJsonArray { hostId?.let { add(JsonPrimitive(it)) } })
                }
                val scope = (PluginBindingScopeValidator.validate(scopePayload) as? PluginBindingScopeValidation.Valid)?.scope
                if (scope == null) { initial.close(); message = PluginNotice.InvalidHost; disconnect(); return }
                bootstrap = initial
                initial.request("binding.open", null, scopePayload, host) { result -> teardownHandler.post {
                    val response = (result as? PluginCallChannel.Result.Response)?.envelope
                    val id = (response?.payload?.get("bindingId") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val validId = id?.takeIf { response.status == "ok" && response.payload.keys == setOf("bindingId") && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) }
                    if (connection !== this || closed) {
                        initial.close()
                        if (validId == null) unbind(this)
                        else closeBindingAndUnbind(PluginCallChannel(context.packageManager, selected, approval, plugin, validId, scope), this)
                        return@post
                    }
                    bootstrap = null
                    initial.close()
                    if (validId == null) { message = PluginNotice.BindingFailed; disconnect(); return@post }
                    approved = approval; candidate = selected
                    channel = PluginCallChannel(context.packageManager, selected, approval, plugin, validId, scope)
                    connected = true
                    readEmbeddedPageSupport(channel!!)
                    onActivated(approval)
                    registry = PluginUiSnapshots(validId)
                    message = if (hostId == null) PluginNotice.ConnectedNoHost else PluginNotice.Connected
                    refresh()
                    handler.postDelayed({ if (channel != null) refresh() }, 1500)
                } }
            }
            override fun onServiceDisconnected(name: ComponentName?) { if (connection === this) { disconnect(); message = PluginNotice.Disconnected } }
            override fun onBindingDied(name: ComponentName?) { if (connection === this) { disconnect(); message = PluginNotice.BindingDied } }
            override fun onNullBinding(name: ComponentName?) { if (connection === this) { disconnect(); message = PluginNotice.BindingRejected } }
        }
        connection = conn
        try {
            if (!context.bindService(Intent(PluginProtocol.SERVICE_ACTION).setComponent(selected.componentName()), conn, Context.BIND_AUTO_CREATE)) {
                connection = null; message = PluginNotice.ConnectionFailed; onConnectionLost()
            } else {
                val timeout = Runnable {
                    if (connection === conn && !connected) { disconnect(); message = PluginNotice.ConnectionFailed }
                }
                connectTimeout = timeout
                handler.postDelayed(timeout, 5_000)
            }
        } catch (_: SecurityException) { connection = null; message = PluginNotice.ConnectionDenied; onConnectionLost() }
    }

    private fun readEmbeddedPageSupport(current: PluginCallChannel) {
        embeddedPageSupported = null
        worker.execute {
            @Suppress("DEPRECATION")
            val supported = runCatching {
                context.packageManager.getServiceInfo(selected.componentName(), PackageManager.GET_META_DATA)
                    .metaData?.getBoolean(PluginProtocol.EMBEDDED_PAGE_METADATA, false) == true
            }.getOrDefault(false)
            onMain { if (channel === current) embeddedPageSupported = supported }
        }
    }

    fun refresh() {
        if (channel == null) return
        readContribution(PluginUiSlot.NAVIGATION)
        refreshHostContributions()
    }

    fun refreshHostContributions() {
        val id = hostId ?: return
        val current = channel ?: return
        listOf(PluginUiSlot.HOST_PANEL, PluginUiSlot.HOST_ACTION).forEach { slot ->
        if (!readsInFlight.add(slot)) return@forEach
        current.request("ui.read", id, buildJsonObject { put("slot", slot.wireName) }) { result -> onMain {
            if (channel !== current) return@onMain
            readsInFlight.remove(slot)
            val response = (result as? PluginCallChannel.Result.Response)?.envelope
            if (response?.status != "ok") registry?.remove(slot, id)
            val published = if (response?.status == "ok") registry?.publish(slot, id, response.payload) else null
            val value = (published as? PluginUiSnapshots.PublishResult.Published)?.snapshot
            if (slot == PluginUiSlot.HOST_PANEL) snapshot = value else hostActionSnapshot = value
            if (value == null) message = PluginNotice.StateChangedRefresh
        } }
        }
    }

    private fun readContribution(slot: PluginUiSlot) {
        val current = channel ?: return
        if (!readsInFlight.add(slot)) return
        current.request("ui.read", null, buildJsonObject { put("slot", slot.wireName) }) { result -> onMain {
            if (channel !== current) return@onMain
            readsInFlight.remove(slot)
            val response = (result as? PluginCallChannel.Result.Response)?.envelope
            if (response?.status != "ok") registry?.remove(slot, null)
            val published = if (response?.status == "ok") registry?.publish(slot, null, response.payload) else null
            val value = (published as? PluginUiSnapshots.PublishResult.Published)?.snapshot
            if (slot == PluginUiSlot.NAVIGATION) navigationSnapshot = value
            if (value == null) message = PluginNotice.StateChangedRefresh
        } }
    }

    fun navigate(handle: PluginUiSnapshots.ActionHandle, stillCurrent: () -> Boolean = { true }, onPage: () -> Unit = {}) {
        performAction(handle, hostOnly = false, stillCurrent = stillCurrent, onPage = onPage)
    }

    fun performHostAction(handle: PluginUiSnapshots.ActionHandle) {
        performAction(handle, hostOnly = true)
    }

    fun consumeActionNotice(notice: PluginNotice) {
        if (actionNotice == notice) actionNotice = null
    }

    private fun performAction(handle: PluginUiSnapshots.ActionHandle, hostOnly: Boolean,
        stillCurrent: () -> Boolean = { true }, onPage: () -> Unit = {}) {
        val action = registry?.resolve(handle) ?: run {
            actionNotice = PluginNotice.StateChangedRefresh
            return
        }
        val current = channel ?: run { actionNotice = PluginNotice.StateChangedRefresh; return }
        val navigation = !hostOnly
        if (if (navigation) navigationAttempt != null else hostAttempt != null) return
        val attempt = ActionAttempt(current, action.hostId, if (navigation) navigationFeedback else feedback,
            navigation, handle.slot, stillCurrent, onPage)
        if (navigation) navigationAttempt = attempt else hostAttempt = attempt
        if (!attempt.feedback.begin { persisted ->
            if (!persisted) { clearAttempt(attempt); return@begin }
            if (closed || channel !== current || registry?.resolve(handle) != action || (!hostOnly && !stillCurrent())) {
                // No request was sent; release the durable marker before allowing another.
                clearAttempt(attempt)
                attempt.feedback.completedWithoutOperation()
                if (!closed && (hostOnly || stillCurrent())) actionNotice = PluginNotice.StateChangedRefresh
                return@begin
            }
            current.request("action.perform", action.hostId, buildJsonObject {
                put("slot", handle.slot.wireName); put("contributionId", handle.contributionId); put("actionId", action.actionId)
            }) { result -> onMain {
                if (!isCurrent(attempt)) return@onMain
                val response = (result as? PluginCallChannel.Result.Response)?.envelope
                when (response?.status) {
                    "accepted" -> {
                        val id = PluginOperationProtocol.acceptedId(response)
                        if (id == null) finishAttempt(attempt, PluginOperationState.Unknown)
                        else {
                            attempt.operationId = id
                            attempt.deadlineMs = android.os.SystemClock.elapsedRealtime() + operationWaitLimitMs
                            val timeout = Runnable { if (isCurrent(attempt)) finishAttempt(attempt, PluginOperationState.Unknown) }
                            attempt.timeout = timeout
                            handler.postDelayed(timeout, operationWaitLimitMs)
                            attempt.feedback.update(PluginOperationState.Waiting)
                            poll(attempt)
                        }
                    }
                    "error" -> finishAttempt(attempt, PluginOperationProtocol.actionError(response.errorCode))
                    "ok" -> if (!navigation) finishAttempt(attempt, PluginOperationState.Succeeded)
                        else PluginOperationProtocol.destination(response.payload)?.let { finishNavigation(attempt, it) }
                            ?: finishAttempt(attempt, PluginOperationState.Unknown)
                    else -> finishAttempt(attempt, PluginOperationState.Unknown)
                }
            } }
        }) clearAttempt(attempt)
    }

    private fun isCurrent(attempt: ActionAttempt): Boolean = !closed && channel === attempt.channel &&
        (if (attempt.navigation) navigationAttempt === attempt else hostAttempt === attempt)

    private fun clearAttempt(attempt: ActionAttempt) {
        attempt.timeout?.let(handler::removeCallbacks)
        if (attempt.navigation && navigationAttempt === attempt) navigationAttempt = null
        if (!attempt.navigation && hostAttempt === attempt) hostAttempt = null
    }

    private fun finishAttempt(attempt: ActionAttempt, state: PluginOperationState) {
        if (!isCurrent(attempt)) return
        clearAttempt(attempt)
        attempt.feedback.update(state)
        refresh()
    }

    private fun finishNavigation(attempt: ActionAttempt, destination: String) {
        if (!isCurrent(attempt)) return
        clearAttempt(attempt)
        attempt.feedback.completedWithoutOperation()
        if (!attempt.stillCurrent()) return
        when (destination) {
            "setup" -> openSetup()
            "page" -> { embeddedEntryPoint = attempt.slot.wireName; attempt.onPage(); refreshPage() }
        }
    }

    fun refreshPage(version2: Boolean = true) {
        val current = channel ?: return
        if (pageReading) return
        pageReading = true
        current.request("page.read", null, if (version2) buildJsonObject { put("schemaVersion", 2) } else JsonObject(emptyMap())) { result -> onMain {
            if (channel !== current) return@onMain
            pageReading = false
            val response = (result as? PluginCallChannel.Result.Response)?.envelope
            if (version2 && response?.status != "ok") { refreshPage(false); return@onMain }
            val payload = response?.payload
            val parsed = if (response?.status == "ok") parsePluginPage(payload) else null
            if (parsed == null) pageError = PluginNotice.PageReadFailed
            else { page = parsed; pageError = null }
        } }
    }

    private fun poll(attempt: ActionAttempt) {
        if (!isCurrent(attempt)) return
        if (android.os.SystemClock.elapsedRealtime() >= attempt.deadlineMs) {
            finishAttempt(attempt, PluginOperationState.Unknown)
            return
        }
        val id = attempt.operationId ?: return
        attempt.channel.request("operation.read", attempt.actionHostId, buildJsonObject {
            put("operationId", id)
            if (!attempt.legacyRead) put("schemaVersion", 2)
        }) { result -> onMain {
            if (!isCurrent(attempt)) return@onMain
            if (android.os.SystemClock.elapsedRealtime() >= attempt.deadlineMs) {
                finishAttempt(attempt, PluginOperationState.Unknown)
                return@onMain
            }
            val response = (result as? PluginCallChannel.Result.Response)?.envelope
            if (!attempt.legacyRead && response?.status == "error" && response.errorCode == "INVALID_PAYLOAD") {
                // Reading status has no side effect; older plugins only accept the v1 shape.
                attempt.legacyRead = true
                poll(attempt)
                return@onMain
            }
            when (val read = PluginOperationProtocol.read(response, id, attempt.navigation)) {
                PluginOperationProtocol.ReadResult.Pending -> handler.postDelayed({ poll(attempt) }, PluginOperationProtocol.POLL_INTERVAL_MS)
                is PluginOperationProtocol.ReadResult.Succeeded -> if (attempt.navigation) finishNavigation(attempt, read.destination!!)
                    else finishAttempt(attempt, PluginOperationState.Succeeded)
                PluginOperationProtocol.ReadResult.Failed -> finishAttempt(attempt, PluginOperationState.Failed)
                PluginOperationProtocol.ReadResult.Unknown -> finishAttempt(attempt, PluginOperationState.Unknown)
            }
        } }
    }

    fun openSetup() {
        val selected = candidate ?: return
        val approval = approved ?: return
        worker.execute {
            val fresh = PluginServiceDiscovery.find(context.packageManager).singleOrNull { approval.matches(it) }
            val launch = if (fresh != null) pluginSetupIntent(context, selected.peer.packageName) else null
            onMain {
                if (fresh == null || launch == null || candidate != selected) { message = PluginNotice.IdentityChanged; disconnect() }
                else try { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { message = PluginNotice.SetupFailed }
            }
        }
    }

    private fun onMain(action: () -> Unit) { handler.post { if (!closed) action() } }
    fun disable() { disconnect(); message = PluginNotice.Disconnected }
    fun disconnect() {
        val wasBound = connection != null || connected
        connectTimeout?.let(handler::removeCallbacks); connectTimeout = null
        feedback.disconnected()
        hostAttempt?.let(::clearAttempt)
        navigationAttempt?.let { it.feedback.disconnected(); clearAttempt(it) }
        embeddedPageSupported = null
        readsInFlight.clear(); snapshot = null; hostActionSnapshot = null; navigationSnapshot = null; page = null; pageError = null; pageReading = false; registry?.close(); registry = null
        val retiring = channel; channel = null
        val opening = bootstrap; bootstrap = null
        val oldConnection = connection; connection = null
        connected = false
        embeddedPageVisible = false
        candidate = null; approved = null
        if (retiring != null && oldConnection != null) closeBindingAndUnbind(retiring, oldConnection)
        else if (opening == null && oldConnection != null) unbind(oldConnection)
        // An in-flight binding.open owns its connection until its callback or timeout.
        // If it returns a binding after disconnect, the callback closes it explicitly.
        if (wasBound && !closed) onConnectionLost()
    }

    private fun unbind(connection: ServiceConnection) {
        try { context.unbindService(connection) } catch (_: IllegalArgumentException) { }
    }

    private fun closeBindingAndUnbind(retiring: PluginCallChannel, connection: ServiceConnection) {
        val deadline = android.os.SystemClock.elapsedRealtime() + PluginProtocol.MAX_REQUEST_DEADLINE_MS + 500
        fun attempt() {
            retiring.request("binding.close", null, JsonObject(emptyMap())) { result ->
                if (result == PluginCallChannel.Result.Failure("CAPACITY_EXCEEDED") && android.os.SystemClock.elapsedRealtime() < deadline) {
                    teardownHandler.postDelayed({ attempt() }, 100)
                } else {
                    retiring.close()
                    unbind(connection)
                }
            }
        }
        attempt()
    }
    override fun close() { closed = true; disconnect(); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}
