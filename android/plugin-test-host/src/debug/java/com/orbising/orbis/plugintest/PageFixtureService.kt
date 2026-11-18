package com.orbising.orbis.plugintest

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.orbising.orbis.plugin.sdk.IOrbisHost
import com.orbising.orbis.plugin.sdk.IOrbisPlugin
import com.orbising.orbis.plugin.sdk.IPluginReply
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, synthetic IPC fixture. It contains no real device actions or data. */
open class PageFixtureService : Service() {
    private val secondFixture get() = this is SecondPageFixtureService
    private var reads = 0
    private val timedActions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val openBindings = java.util.concurrent.atomic.AtomicInteger()
    private val binder = object : IOrbisPlugin.Stub() {
        override fun openBinding(envelopeJson: String?, host: IOrbisHost?, reply: IPluginReply?) {
            openBindings.incrementAndGet()
            respond(envelopeJson, reply, JSONObject().put("bindingId", "fixture-binding"))
        }

        override fun request(envelopeJson: String?, reply: IPluginReply?) {
            val request = JSONObject(envelopeJson ?: return)
            val payload = when (request.getString("op")) {
                "ui.read" -> {
                    val slot = request.getJSONObject("payload").getString("slot")
                    val hostId = request.optString("hostId")
                    if (hostId.contains("slot-failure") && slot == "chat.host.sidebar.section") {
                        respond(envelopeJson, reply, JSONObject(), "error", "INVALID_PAYLOAD")
                        return
                    }
                    val started = timedActions[hostId]
                    val remaining = started?.let { 60_000L - (android.os.SystemClock.elapsedRealtime() - it) }
                    val seconds = if (remaining != null && remaining > 0) (remaining + 999) / 1_000 else {
                        if (started != null) timedActions.remove(hostId, started)
                        null
                    }
                    val contributions = JSONArray()
                    if (slot == "settings.section") contributions.put(JSONObject()
                        .put("id", "fixture.settings").put("slot", slot).put("hostId", JSONObject.NULL)
                        .put("component", JSONObject().put("type", "section_action").put("label", "打开测试页面")
                            .put("description", "测试页面导航").put("enabled", true).put("actionId", "fixture.page")))
                    if (slot == "navigation.bottom_tab") contributions.put(JSONObject()
                        .put("id", "fixture.nav").put("slot", slot).put("hostId", JSONObject.NULL)
                        .put("component", JSONObject().put("type", "navigation_item").put("icon", "device").put("label", "测试插件").put("actionId", "fixture.page")))
                    if (slot == "chat.host.sidebar.section" && hostId.contains("generic-components")) {
                        fun add(id: String, component: JSONObject) {
                            contributions.put(JSONObject().put("id", id).put("slot", slot)
                                .put("hostId", request.get("hostId")).put("component", component))
                        }
                        add("fixture.row", JSONObject().put("type", "text_row").put("title", "测试文本行").put("description", "文本行详情"))
                        add("fixture.badge", JSONObject().put("type", "status_badge").put("label", "测试状态标签").put("tone", "info"))
                        add("fixture.card", JSONObject().put("type", "status_card").put("title", "测试状态卡片")
                            .put("description", "卡片详情").put("tone", "info")
                            .put("action", JSONObject().put("label", "卡片操作").put("enabled", true).put("actionId", "fixture.card")))
                        add("fixture.section", JSONObject().put("type", "section_action").put("label", "测试区块操作")
                            .put("description", "区块详情").put("enabled", true).put("actionId", "fixture.section"))
                    } else if (slot == "chat.host.sidebar.section" && !(secondFixture && hostId.contains("multi-no-status"))) contributions.put(JSONObject()
                        .put("id", "fixture.panel").put("slot", slot).put("hostId", request.get("hostId"))
                        .put("component", JSONObject().put("type", "status_badge").put("label", fixtureStatus(when {
                            secondFixture -> "第二插件状态"
                            hostId.contains("binding-count-probe") -> "bindings:${openBindings.get()}"
                            seconds != null -> "处理中${seconds}秒"
                            else -> "测试状态"
                        })).put("tone", "info")))
                    if (slot == "paired_host.trailing_actions") contributions.put(JSONObject()
                        .put("id", "fixture.action").put("slot", slot).put("hostId", request.get("hostId"))
                        .put("component", JSONObject().put("type", "icon_action").put("icon", "play").put("label", if (secondFixture) "第二插件操作" else "测试操作")
                            .put("enabled", seconds == null).put("actionId", "fixture.action")))
                    JSONObject().put("schemaVersion", 1).put("contributions", contributions)
                }
                "action.perform" -> {
                    if (request.getJSONObject("payload").getString("actionId") == "fixture.page") {
                        if (deferPageNavigation(envelopeJson, reply)) return
                        JSONObject().put("destination", "page")
                    }
                    else {
                        if (request.optString("hostId").contains("timeout")) return
                        if (request.optString("hostId").contains("sync-success")) {
                            respond(envelopeJson, reply, JSONObject().put("detail", "completed"))
                            return
                        }
                        if (request.optString("hostId").contains("sync-failure")) {
                            respond(envelopeJson, reply, JSONObject(), "error", "INVALID_PAYLOAD")
                            return
                        }
                        if (request.optString("hostId").contains("timed-operation-fixture"))
                            timedActions[request.getString("hostId")] = android.os.SystemClock.elapsedRealtime()
                        synchronized(this) { reads = 0 }
                        respond(envelopeJson, reply, JSONObject().put("operationId", "fixture-operation"), "accepted")
                        return
                    }
                }
                "operation.read" -> {
                    if (deferOperationRead(envelopeJson, reply)) return
                    JSONObject().put("operationId", "fixture-operation")
                        .put("state", if (request.optString("hostId").contains("timed-operation-fixture") || synchronized(this) { reads++ } == 0) "executing" else "succeeded")
                }
                "binding.close" -> { openBindings.updateAndGet { count -> (count - 1).coerceAtLeast(0) }; JSONObject() }
                "page.read" -> JSONObject().put("schemaVersion", 2).put("title", "插件测试页面")
                    .put("message", "测试内容").put("rows", JSONArray()).put("settings", true)
                else -> JSONObject()
            }
            respond(envelopeJson, reply, payload)
        }

        override fun cancel(bindingId: String?, requestId: String?) = Unit
    }

    protected open fun deferPageNavigation(raw: String?, reply: IPluginReply?): Boolean = false
    protected open fun deferOperationRead(raw: String?, reply: IPluginReply?): Boolean = false

    protected open fun fixtureStatus(fallback: String): String = fallback

    protected fun response(raw: String?, payload: JSONObject, status: String = "ok", errorCode: String? = null): String? {
        val request = JSONObject(raw ?: return null)
        val response = JSONObject()
        listOf("apiVersion", "payloadSchemaVersion", "requestId", "bindingId", "pluginId", "capability", "op", "hostId")
            .forEach { response.put(it, request.get(it)) }
        response.put("status", status).put("errorCode", errorCode ?: JSONObject.NULL).put("payload", payload)
        return response.toString()
    }

    private fun respond(raw: String?, reply: IPluginReply?, payload: JSONObject, status: String = "ok", errorCode: String? = null) {
        reply?.onMessage(response(raw, payload, status, errorCode) ?: return)
    }

    private val pages = object : com.orbising.orbis.plugin.sdk.IEmbeddedPageService.Stub() {
        override fun openPage(bindingId: String?, requestId: String?, hostToken: IBinder?, displayId: Int, width: Int, height: Int, pageContext: android.os.Bundle?,
            callback: com.orbising.orbis.plugin.sdk.IEmbeddedPageCallback?) {
            if (bindingId != "fixture-binding" || requestId == null || hostToken == null || callback == null || width !in 1..8192 || height !in 1..8192 || android.os.Build.VERSION.SDK_INT < 30) return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
                    val displayContext = createDisplayContext(display)
                    val host = android.view.SurfaceControlViewHost(displayContext, display, hostToken)
                    val root = android.widget.LinearLayout(displayContext).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setBackgroundColor(android.graphics.Color.WHITE)
                        addView(android.widget.TextView(displayContext).apply { text = "插件嵌入页面"; textSize = 24f })
                        addView(android.widget.Button(displayContext).apply { text = "插件按钮" })
                    }
                    host.setView(root, width, height)
                    val page = object : com.orbising.orbis.plugin.sdk.IEmbeddedPageControl.Stub() {
                        override fun setDarkTheme(dark: Boolean) = Unit
                        override fun setTheme(theme: android.os.Bundle?) = Unit
                        override fun resize(w: Int, h: Int) { if (w in 1..8192 && h in 1..8192) android.os.Handler(android.os.Looper.getMainLooper()).post { host.relayout(w, h) } }
                        override fun close() { android.os.Handler(android.os.Looper.getMainLooper()).post { host.release() } }
                        override fun back() {
                            if (pageContext?.getString("entryPoint") != "fixture.activity_request") return
                            val request = android.os.Bundle().apply {
                                putString("className", "com.orbising.orbis.plugintest.Launcher")
                                putBundle("extras", android.os.Bundle())
                            }
                            runCatching { callback.onRequestOwnActivity(requestId, request) }
                        }
                        override fun activityLaunchFailed() {
                            // Relay the result through an existing observable callback for the fixture test.
                            runCatching { callback.onBackStateChanged(requestId, true) }
                        }
                        override fun sheetAction(sheetId: String?, actionId: String?) = Unit
                    }
                    val payload = android.os.Bundle().apply { putParcelable("surface", host.surfacePackage) }
                    if (pageContext?.getString("entryPoint") == "fixture.delayed_open") {
                        // Let the host destroy its Surface after openPage, but before onOpened.
                        callback.onBackStateChanged(requestId, false)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try { callback.onOpened(requestId, payload, page) }
                            catch (_: Exception) { host.release() }
                        }, 2_000)
                    } else callback.onOpened(requestId, payload, page)
                } catch (_: Exception) { runCatching { callback.onError(requestId) } }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = if (intent?.action == com.orbising.orbis.plugin.sdk.PluginProtocol.EMBEDDED_PAGE_ACTION) pages else binder
}

/** Separate explicit service to exercise independently authenticated contributions. */
class SecondPageFixtureService : PageFixtureService()

/** Accepted navigation with a manually released read-only operation response. */
class AsyncNavigationFixtureService : PageFixtureService() {
    private val pending = java.util.concurrent.atomic.AtomicReference<Pair<String, IPluginReply>?>(null)
    private val actions = java.util.concurrent.atomic.AtomicInteger()
    private val rejectVersion2 = java.util.concurrent.atomic.AtomicBoolean()

    override fun deferPageNavigation(raw: String?, reply: IPluginReply?): Boolean {
        if (raw == null || reply == null) return false
        actions.incrementAndGet()
        reply.onMessage(response(raw, JSONObject().put("operationId", "fixture-nav-operation"), "accepted"))
        return true
    }

    override fun deferOperationRead(raw: String?, reply: IPluginReply?): Boolean {
        if (raw == null || reply == null) return false
        if (rejectVersion2.get() && JSONObject(raw).getJSONObject("payload").has("schemaVersion")) {
            reply.onMessage(response(raw, JSONObject(), "error", "INVALID_PAYLOAD"))
            return true
        }
        check(pending.compareAndSet(null, raw to reply))
        return true
    }

    private val control = object : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, result: android.os.Parcel?, flags: Int): Boolean {
            if (packageManager.checkSignatures(android.os.Binder.getCallingUid(), android.os.Process.myUid()) !=
                android.content.pm.PackageManager.SIGNATURE_MATCH) throw SecurityException()
            val out = result ?: return false
            when (code) {
                1 -> {
                    val raw = pending.get()?.first
                    val request = raw?.let(::JSONObject)
                    val valid = request != null && request.isNull("hostId") &&
                        request.getJSONObject("payload").optInt("schemaVersion") == 2
                    out.writeNoException(); out.writeInt(if (valid) 1 else 0)
                }
                2 -> {
                    val held = pending.getAndSet(null)
                    if (held != null) {
                        val payload = JSONObject().put("operationId", "fixture-nav-operation")
                            .put("state", "succeeded").put("result", JSONObject().put("destination", "page"))
                        held.second.onMessage(response(held.first, payload))
                    }
                    out.writeNoException(); out.writeInt(if (held != null) 1 else 0)
                }
                3 -> { out.writeNoException(); out.writeInt(actions.get()) }
                4 -> { rejectVersion2.set(true); out.writeNoException(); out.writeInt(1) }
                5 -> {
                    val request = pending.get()?.first?.let(::JSONObject)
                    val legacy = request != null && request.isNull("hostId") &&
                        !request.getJSONObject("payload").has("schemaVersion")
                    out.writeNoException(); out.writeInt(if (legacy) 1 else 0)
                }
                6 -> {
                    pending.set(null); actions.set(0); rejectVersion2.set(false)
                    out.writeNoException(); out.writeInt(1)
                }
                else -> return super.onTransact(code, data, result, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder =
        if (intent?.action == "com.orbising.orbis.plugintest.ASYNC_NAVIGATION_CONTROL") control else super.onBind(intent)
}

/** Explicit-only test control holds a page reply until the test chooses to deliver it. */
class DelayedPageFixtureService : PageFixtureService() {
    private val pending = java.util.concurrent.atomic.AtomicReference<Pair<String, IPluginReply>?>(null)
    @Volatile private var replySent = false

    override fun deferPageNavigation(raw: String?, reply: IPluginReply?): Boolean {
        if (raw == null || reply == null) return false
        replySent = false
        check(pending.compareAndSet(null, raw to reply))
        return true
    }

    override fun fixtureStatus(fallback: String): String = if (replySent) "延迟回复已发送" else fallback

    private val control = object : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, result: android.os.Parcel?, flags: Int): Boolean {
            if (packageManager.checkSignatures(android.os.Binder.getCallingUid(), android.os.Process.myUid()) !=
                android.content.pm.PackageManager.SIGNATURE_MATCH) throw SecurityException()
            return when (code) {
                1 -> { result!!.writeNoException(); result.writeInt(if (pending.get() != null) 1 else 0); true }
                2 -> {
                    val held = pending.getAndSet(null)
                    if (held != null) {
                        val payload = response(held.first, JSONObject().put("destination", "page"))!!
                        val request = android.os.Parcel.obtain()
                        val responseParcel = android.os.Parcel.obtain()
                        try {
                            // A synchronous transaction ensures the host has queued this callback
                            // before the test asks for the status marker below.
                            request.writeInterfaceToken("com.orbising.orbis.plugin.sdk.IPluginReply")
                            request.writeString(payload)
                            check(held.second.asBinder().transact(android.os.IBinder.FIRST_CALL_TRANSACTION, request, responseParcel, 0))
                        } finally { request.recycle(); responseParcel.recycle() }
                        replySent = true
                    }
                    result!!.writeNoException(); result.writeInt(if (held != null) 1 else 0); true
                }
                else -> super.onTransact(code, data, result, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder =
        if (intent?.action == "com.orbising.orbis.plugintest.NAVIGATION_CONTROL") control else super.onBind(intent)
}
