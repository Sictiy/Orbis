package com.orbising.orbis.plugintest

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.TextView
import com.orbising.orbis.plugin.sdk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.serialization.json.*

/** Launcher makes this separate test package visible to the plugin's approval screen. */
class Launcher : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Orbis plugin IPC test host" })
    }
}

/** Explicitly invoked via adb; probes discovery, scoped UI reads and binding lifecycle. */
class ProbeRunner : Instrumentation() {
    private var mode = "authorized"
    private var pluginPackage = ""
    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        mode = arguments.getString("mode", "authorized")
        pluginPackage = arguments.getString("pluginPackage", "")
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            runProbe()
            output.putString("result", "PASS: $mode")
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("result", "FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun runProbe() {
        val context = targetContext
        check(pluginPackage.isNotBlank()) { "pluginPackage argument required" }
        val candidate = PluginServiceDiscovery.find(context.packageManager)
            .singleOrNull { it.peer.packageName == pluginPackage }
            ?: error("Plugin not discovered")
        val approval = ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName)
        val latch = CountDownLatch(1)
        val service = AtomicReference<IOrbisPlugin>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.set(IOrbisPlugin.Stub.asInterface(binder)); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        check(context.bindService(Intent(PluginProtocol.SERVICE_ACTION).setComponent(candidate.componentName()), connection, Context.BIND_AUTO_CREATE)) { "bindService returned false for ${candidate.componentName()}" }
        try {
            check(latch.await(10, TimeUnit.SECONDS)) { "binding timed out" }
            val plugin = checkNotNull(service.get())
            val bootstrap = PluginCallChannel(context.packageManager, candidate, approval, plugin, null, null)
            try {
                val descriptor = call(bootstrap, "descriptor.read", null, JsonObject(emptyMap()))
                if (mode == "unauthorized") {
                    check(descriptor == PluginCallChannel.Result.Failure("TIMEOUT")) { "unapproved host was not rejected: $descriptor" }
                    return
                }
                check(mode == "authorized") { "unknown mode: $mode" }
                val desc = response(descriptor)
                check(desc.status == "ok" && desc.payload["pluginId"]?.jsonPrimitive?.content == candidate.peer.packageName) { "invalid descriptor: $desc" }
                val scopePayload = buildJsonObject {
                    put("grants", buildJsonArray { add(JsonPrimitive("ui")) })
                    put("hostIds", buildJsonArray { add(JsonPrimitive("test-host")) })
                }
                val scope = (PluginBindingScopeValidator.validate(scopePayload) as PluginBindingScopeValidation.Valid).scope
                val host = object : IOrbisHost.Stub() {
                    override fun request(envelopeJson: String?, reply: IPluginReply?) = Unit
                    override fun cancel(bindingId: String?, requestId: String?) = Unit
                }
                val opened = response(call(bootstrap, "binding.open", null, scopePayload, host))
                check(opened.status == "ok") { "binding.open ${opened.errorCode}" }
                val binding = opened.payload["bindingId"]?.jsonPrimitive?.content ?: error("missing binding ID")
                val channel = PluginCallChannel(context.packageManager, candidate, approval, plugin, binding, scope)
                try {
                    for (slot in PluginUiSlot.entries) {
                        val hostId = if (slot.requiresHost) "test-host" else null
                        val payload = buildJsonObject { put("slot", slot.wireName) }
                        val result = response(call(channel, "ui.read", hostId, payload))
                        check(result.status == "ok") { "ui.read ${slot.wireName}: ${result.errorCode}" }
                        check(PluginUiValidator.validate(result.payload, slot, hostId) is PluginUiValidation.Valid) { "invalid ${slot.wireName}" }
                    }
                    val denied = call(channel, "ui.read", "other-host", buildJsonObject { put("slot", "paired_host.trailing_actions") })
                    check(denied == PluginCallChannel.Result.Failure("PERMISSION_DENIED")) { "host scope not enforced: $denied" }
                    val remoteDenied = rawOutOfScopeRequest(context, plugin, candidate, approval, binding)
                    check(remoteDenied.status == "error" && remoteDenied.errorCode == "PERMISSION_DENIED") {
                        "plugin accepted an out-of-scope Host: $remoteDenied"
                    }
                    check(response(call(channel, "binding.close", null, JsonObject(emptyMap()))).status == "ok") { "binding.close failed" }
                } finally { channel.close() }
            } finally { bootstrap.close() }
        } finally { context.unbindService(connection) }
    }

    private fun response(result: PluginCallChannel.Result): PluginResponseEnvelope =
        (result as? PluginCallChannel.Result.Response)?.envelope ?: error("request failed: $result")

    // Deliberately bypass the host-side grant guard to test enforcement by the
    // remote plugin. The callback still verifies actual Binder identity.
    private fun rawOutOfScopeRequest(context: Context, plugin: IOrbisPlugin, candidate: PluginServiceCandidate,
                                     approval: ApprovedPluginService, binding: String): PluginResponseEnvelope {
        val requestId = UUID.randomUUID().toString()
        val raw = buildJsonObject {
            put("apiVersion", PluginProtocol.API_VERSION); put("payloadSchemaVersion", PluginProtocol.PAYLOAD_SCHEMA_VERSION)
            put("requestId", requestId); put("bindingId", binding); put("pluginId", candidate.peer.packageName)
            put("capability", "ui"); put("op", "ui.read"); put("hostId", "other-host")
            put("deadlineElapsedRealtimeMs", SystemClock.elapsedRealtime() + PluginProtocol.MAX_REQUEST_DEADLINE_MS)
            put("payload", buildJsonObject { put("slot", "paired_host.trailing_actions") })
        }.toString()
        val done = CountDownLatch(1)
        val result = AtomicReference<PluginResponseEnvelope>()
        val callback = object : IPluginReply.Stub() {
            override fun onMessage(envelopeJson: String?) {
                val peer = BinderPeerVerifier.captureCallingPeer(context.packageManager) ?: return
                if (!approval.matchesCaller(candidate, peer) || envelopeJson == null) return
                val envelope = (PluginResponseValidator.validate(envelopeJson) as? ResponseValidation.Valid)?.envelope ?: return
                if (envelope.requestId != requestId || envelope.bindingId != binding ||
                    envelope.pluginId != candidate.peer.packageName || envelope.operation != "ui.read" ||
                    envelope.capability != "ui" || envelope.hostId != "other-host") return
                result.set(envelope); done.countDown()
            }
        }
        plugin.request(raw, callback)
        check(done.await(5, TimeUnit.SECONDS)) { "plugin did not answer out-of-scope request" }
        return checkNotNull(result.get())
    }

    private fun call(channel: PluginCallChannel, operation: String, hostId: String?, payload: JsonObject, host: IOrbisHost? = null): PluginCallChannel.Result {
        val result = AtomicReference<PluginCallChannel.Result>()
        val done = CountDownLatch(1)
        channel.request(operation, hostId, payload, host) { result.set(it); done.countDown() }
        check(done.await(8, TimeUnit.SECONDS)) { "callback missing for $operation" }
        return result.get()
    }
}
