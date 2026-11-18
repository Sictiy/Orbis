package com.orbising.orbis.plugin.sdk

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bounded host-to-plugin calls for an already explicitly bound, approved service.
 * The owner verifies discovery/approval and controls binding, lifecycle, grants,
 * request-specific payload schemas and response payload schemas. This class does
 * not grant a plugin access to Orbis state or make its response data trustworthy.
 */
class PluginCallChannel(
    private val packageManager: PackageManager,
    private val candidate: PluginServiceCandidate,
    private val approval: ApprovedPluginService,
    private val plugin: IOrbisPlugin,
    val bindingId: String?,
    private val scope: PluginBindingScope?,
) : AutoCloseable {
    sealed interface Result {
        data class Response(val envelope: PluginResponseEnvelope) : Result
        data class Failure(val code: String) : Result
    }

    private class Entry(val callback: (Result) -> Unit) {
        val completed = AtomicBoolean(false)
        @Volatile var timeout: ScheduledFuture<*>? = null
    }

    private val closed = AtomicBoolean(false)
    private val pending = PendingPluginRequests(bindingId)
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "orbis-plugin-ipc").apply { isDaemon = true }
    }
    private val deathRecipient = IBinder.DeathRecipient { close() }

    init {
        require(approval.matches(candidate))
        require((bindingId == null) == (scope == null))
        try { plugin.asBinder().linkToDeath(deathRecipient, 0) } catch (_: RemoteException) { closed.set(true); worker.shutdown() }
    }

    /**
     * Callback runs on a background executor, never a Binder thread or UI thread.
     * A successful envelope still needs operation-specific payload validation.
     * For `binding.open` supply a host Stub that independently checks every caller.
     */
    fun request(
        operation: String,
        hostId: String?,
        payload: JsonObject,
        hostEndpoint: IOrbisHost? = null,
        callback: (Result) -> Unit,
    ) {
        if (closed.get()) { dispatch(callback, Result.Failure("PLUGIN_UNAVAILABLE")); return }
        val capability = when (operation) {
            "descriptor.read" -> "descriptor"
            "binding.open", "binding.close" -> "binding"
            "ui.read", "page.read", "content.read", "action.perform", "operation.read" -> "ui"
            "event.observe" -> "host_state_observe"
            "action.preflight" -> "preflight"
            else -> { dispatch(callback, Result.Failure("PERMISSION_DENIED")); return }
        }
        val bootstrap = operation == "binding.open" || operation == "descriptor.read"
        val permission = PluginCapability.entries.firstOrNull { it.wireName == capability }
        if ((bootstrap && bindingId != null) || (!bootstrap && bindingId == null) ||
            ((operation == "binding.open") != (hostEndpoint != null)) ||
            (permission != null && scope?.allows(permission, hostId) != true)
        ) { dispatch(callback, Result.Failure("PERMISSION_DENIED")); return }
        if ((bootstrap && hostId != null) ||
            (operation == "binding.open" && PluginBindingScopeValidator.validate(payload) !is PluginBindingScopeValidation.Valid) ||
            (operation == "descriptor.read" && payload.isNotEmpty())
        ) { dispatch(callback, Result.Failure("INVALID_PAYLOAD")); return }
        val now = SystemClock.elapsedRealtime()
        val timeout = if (operation == "action.preflight") PluginProtocol.MAX_PREFLIGHT_DEADLINE_MS else PluginProtocol.MAX_REQUEST_DEADLINE_MS
        val identity = PendingPluginRequests.RequestIdentity(candidate.peer.packageName, capability, operation, hostId)
        val ticket = when (val started = pending.start(identity, now + timeout, now)) {
            is PendingPluginRequests.StartResult.Started -> started.ticket
            PendingPluginRequests.StartResult.AtCapacity -> { dispatch(callback, Result.Failure("CAPACITY_EXCEEDED")); return }
            else -> { dispatch(callback, Result.Failure("PLUGIN_UNAVAILABLE")); return }
        }
        val raw = envelope(ticket, payload)
        val endpoint = if (operation == "binding.open") PluginRequestEndpoint.BINDING_OPEN else PluginRequestEndpoint.PLUGIN
        if (PluginEnvelopeValidator.validate(raw, now, endpoint) !is EnvelopeValidation.Valid) {
            pending.cancel(ticket.requestId); dispatch(callback, Result.Failure("INVALID_PAYLOAD")); return
        }
        val entry = Entry(callback)
        entries[ticket.requestId] = entry
        if (closed.get()) { pending.cancel(ticket.requestId); finish(ticket.requestId, Result.Failure("PLUGIN_UNAVAILABLE")); return }
        val reply = object : IPluginReply.Stub() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (!BinderPeerVerifier.isParcelWithinLimit(data)) return false
                return super.onTransact(code, data, reply, flags)
            }

            override fun onMessage(envelopeJson: String?) {
                val peer = BinderPeerVerifier.captureCallingPeer(packageManager) ?: return
                if (!approval.matchesCaller(candidate, peer) || closed.get() || envelopeJson == null) return
                val result = PluginResponseValidator.validate(envelopeJson) as? ResponseValidation.Valid ?: return
                val response = result.envelope
                if (response.requestId != ticket.requestId) return
                if (pending.takeReply(response, SystemClock.elapsedRealtime()) == null) return
                finish(response.requestId, Result.Response(response))
            }
        }
        try {
            entry.timeout = worker.schedule({
                pending.expire(SystemClock.elapsedRealtime()).forEach { expired ->
                    finish(expired.requestId, Result.Failure("TIMEOUT"))
                    try { plugin.cancel(expired.bindingId, expired.requestId) } catch (_: RemoteException) { }
                }
            }, timeout, TimeUnit.MILLISECONDS)
            worker.execute {
                if (closed.get() || !entries.containsKey(ticket.requestId)) return@execute
                try {
                    if (operation == "binding.open") plugin.openBinding(raw, hostEndpoint, reply)
                    else plugin.request(raw, reply)
                } catch (_: RemoteException) {
                    pending.cancel(ticket.requestId)
                    finish(ticket.requestId, Result.Failure("PLUGIN_UNAVAILABLE"))
                    close()
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.cancel(ticket.requestId)
            finish(ticket.requestId, Result.Failure("PLUGIN_UNAVAILABLE"))
        }
    }

    private fun envelope(ticket: PendingPluginRequests.Ticket, payload: JsonObject): String = buildJsonObject {
        put("apiVersion", PluginProtocol.API_VERSION); put("payloadSchemaVersion", PluginProtocol.PAYLOAD_SCHEMA_VERSION)
        put("requestId", ticket.requestId); put("bindingId", ticket.bindingId?.let(::JsonPrimitive) ?: JsonNull)
        put("pluginId", ticket.identity.pluginId); put("capability", ticket.identity.capability); put("op", ticket.identity.operation)
        put("hostId", ticket.identity.hostId?.let(::JsonPrimitive) ?: JsonNull)
        put("deadlineElapsedRealtimeMs", ticket.deadlineElapsedRealtimeMs); put("payload", payload)
    }.toString()

    private fun finish(requestId: String, result: Result) {
        val entry = entries.remove(requestId) ?: return
        if (!entry.completed.compareAndSet(false, true)) return
        entry.timeout?.cancel(false)
        dispatch(entry.callback, result)
    }

    private fun dispatch(callback: (Result) -> Unit, result: Result) {
        callbacks.execute { callback(if (closed.get()) Result.Failure("PLUGIN_UNAVAILABLE") else result) }
    }

    /** Also call on package replacement, service disconnect, grant revocation or user disable. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val abandoned = pending.close()
        abandoned.forEach { finish(it.requestId, Result.Failure("PLUGIN_UNAVAILABLE")) }
        try { plugin.asBinder().unlinkToDeath(deathRecipient, 0) } catch (_: NoSuchElementException) { }
        try {
            worker.execute {
                abandoned.forEach { ticket ->
                    try { plugin.cancel(ticket.bindingId, ticket.requestId) } catch (_: RemoteException) { }
                }
            }
        } catch (_: RejectedExecutionException) { }
        worker.shutdown()
    }

    private companion object {
        val callbacks = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orbis-plugin-callbacks").apply { isDaemon = true }
        }
    }
}
