package com.orbising.orbis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import com.orbising.orbis.plugin.sdk.BinderPeerVerifier
import com.orbising.orbis.plugin.sdk.EmbeddedPageTheme
import com.orbising.orbis.plugin.sdk.EmbeddedSheet
import com.orbising.orbis.plugin.sdk.IEmbeddedPageCallback
import com.orbising.orbis.plugin.sdk.IEmbeddedPageControl
import com.orbising.orbis.plugin.sdk.IEmbeddedPageService
import com.orbising.orbis.plugin.sdk.PluginProtocol
import java.util.UUID
import java.util.concurrent.Executors

/** Builds an explicit intent only after validating both the payload and resolved target. */
@Suppress("DEPRECATION")
internal fun pluginOwnedActivityIntent(context: Context, packageName: String, request: Bundle?): Intent? = runCatching {
    if (request == null || request.keySet() != setOf("className", "extras")) return null
    val className = request.getString("className") ?: return null
    if (className.length > 255 || '.' !in className || !className.matches(Regex("[A-Za-z0-9_.$]+"))) return null
    val supplied = request.getBundle("extras") ?: return null
    if (supplied.size() > 16) return null
    val extras = Bundle()
    for (key in supplied.keySet()) {
        if (!key.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) return null
        val value = supplied.get(key) as? String ?: return null
        if (value.length > 512 || value.any(Char::isISOControl)) return null
        extras.putString(key, value)
    }
    val component = ComponentName(packageName, className)
    val info = context.packageManager.getActivityInfo(component, 0)
    if (info.packageName != packageName || !info.exported || !info.enabled || !info.applicationInfo.enabled) return null
    if (info.permission != null && context.packageManager.checkPermission(info.permission, context.packageName) != PackageManager.PERMISSION_GRANTED) return null
    info.targetActivity?.let { target ->
        val targetInfo = context.packageManager.getActivityInfo(ComponentName(packageName, target), 0)
        if (targetInfo.packageName != packageName || !targetInfo.enabled) return null
    }
    Intent().setComponent(component).putExtras(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}.getOrNull()

/** A single, foreground, authenticated page. It never interprets plugin content. */
@RequiresApi(30)
internal class PluginEmbeddedPage(
    private val context: Context,
    private val session: PluginSession,
    private val surface: SurfaceView,
    private var theme: EmbeddedPageTheme,
    private val isForeground: () -> Boolean,
    private val ready: () -> Unit,
    private val unavailable: () -> Unit,
    private val backStateChanged: (Boolean) -> Unit,
    private val sheetChanged: (EmbeddedSheet?) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val bindingId = session.embeddedBindingId
    private val approval = session.embeddedApproval
    private val requestId = UUID.randomUUID().toString()
    private var remote: IEmbeddedPageService? = null
    private var control: IEmbeddedPageControl? = null
    private var packageValue: SurfaceControlViewHost.SurfacePackage? = null
    private var bound = false
    private var closed = false
    private var opened = false
    private var requested = false
    private var currentSheet: EmbeddedSheet? = null
    private val hostReader = Executors.newSingleThreadExecutor()
    private var pageHosts: List<Pair<String, String?>>? = null
    private val timeout = Runnable { if (!opened) fail() }

    private val callback = object : IEmbeddedPageCallback.Stub() {
        private fun authenticated(): Boolean {
            val peer = BinderPeerVerifier.captureCallingPeer(context.packageManager) ?: return false
            return approval?.matchesCaller(session.selected, peer) == true
        }
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            if (!BinderPeerVerifier.isParcelWithinLimit(data)) return false
            return super.onTransact(code, data, reply, flags)
        }
        override fun onOpened(id: String?, value: Bundle?, page: IEmbeddedPageControl?) {
            if (!authenticated() || id != requestId || value == null || page == null || value.keySet() != setOf("surface")) return
            value.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader
            @Suppress("DEPRECATION") val pkg = runCatching { value.getParcelable<SurfaceControlViewHost.SurfacePackage>("surface") }.getOrNull() ?: return
            main.post {
                if (closed || opened || !session.connected || session.embeddedBindingId != bindingId || !surface.isAttachedToWindow) {
                    runCatching { page.close() }; pkg.release(); return@post
                }
                try {
                    surface.setChildSurfacePackage(pkg)
                    control = page; packageValue = pkg; opened = true
                    runCatching { page.setDarkTheme(theme.dark) }
                    runCatching { page.setTheme(theme.toBundle()) }
                    main.removeCallbacks(timeout)
                    ready()
                } catch (_: Exception) { runCatching { page.close() }; pkg.release(); fail() }
            }
        }
        override fun onError(id: String?) {
            if (authenticated() && id == requestId) main.post { if (!closed) fail() }
        }
        override fun onBackStateChanged(id: String?, canGoBack: Boolean) {
            if (authenticated() && id == requestId) main.post {
                if (!closed && session.connected && session.embeddedBindingId == bindingId) backStateChanged(canGoBack)
            }
        }
        override fun onSheetChanged(id: String?, value: Bundle?) {
            if (!authenticated() || id != requestId) return
            val sheet = if (value == null) null else EmbeddedSheet.fromBundle(value) ?: return
            main.post {
                if (!closed && opened && session.connected && session.embeddedBindingId == bindingId && surface.isAttachedToWindow) {
                    currentSheet = sheet
                    sheetChanged(sheet)
                }
            }
        }
        override fun onRequestActivity(id: String?, activity: android.app.PendingIntent?) {
            if (!authenticated() || id != requestId) return
            // Android exposes the creator but not the actual target of a PendingIntent.
            // Reject the legacy request instead of granting it host launch authority.
            main.post { if (!closed && opened) runCatching { control?.activityLaunchFailed() } }
        }
        override fun onRequestOwnActivity(id: String?, request: Bundle?) {
            if (!authenticated() || id != requestId) return
            val launch = pluginOwnedActivityIntent(context, session.selected.peer.packageName, request)
            main.post {
                if (closed || !opened || !session.connected || session.embeddedBindingId != bindingId) return@post
                if (!isForeground() || !surface.isAttachedToWindow || !surface.isShown || !surface.holder.surface.isValid || launch == null) {
                    runCatching { control?.activityLaunchFailed() }
                    return@post
                }
                runCatching { context.startActivity(launch) }.onFailure { runCatching { control?.activityLaunchFailed() } }
            }
        }
    }
    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { openIfReady() }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (width > 0 && height > 0) {
                if (opened) runCatching { control?.resize(width, height) }
                else openIfReady()
            }
        }
        // A Surface can disappear temporarily while an Activity covers the host.
        // SurfaceView reparents its child package when a new Surface is created;
        // the page itself must live until its view is disposed or binding is lost.
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed || name != session.selected.componentName() || binder == null) { if (!closed) fail(); return }
            remote = IEmbeddedPageService.Stub.asInterface(binder)
            openIfReady()
        }
        override fun onServiceDisconnected(name: ComponentName?) { fail() }
        override fun onBindingDied(name: ComponentName?) { fail() }
        override fun onNullBinding(name: ComponentName?) { fail() }
    }
    fun start() {
        if (bindingId == null || approval == null || !session.connected) { fail(); return }
        // Read pairings away from the UI thread. Only the public identity fields
        // leave the worker, and a disposed page ignores a late result.
        hostReader.execute {
            val hosts = HostPairingStore(context).hosts()
                .filter { it.hostId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) }
                .distinctBy { it.hostId }.take(128)
                .map { it.hostId to it.hostName?.filterNot(Char::isISOControl)?.take(256) }
            main.post {
                if (!closed) {
                    pageHosts = hosts
                    openIfReady()
                }
            }
        }
        surface.holder.addCallback(holderCallback)
        try {
            // The remote process owns this visible surface's input channel. Keep it
            // foreground-important while the page is attached so OEM background
            // freezers do not leave touch events queued until the next IPC wakeup.
            bound = context.bindService(Intent(PluginProtocol.EMBEDDED_PAGE_ACTION).setComponent(session.selected.componentName()),
                connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ADJUST_WITH_ACTIVITY)
            if (!bound) { fail(); return }
            main.postDelayed(timeout, 5000)
            openIfReady()
        } catch (_: Exception) { fail() }
    }
    private fun openIfReady() {
        if (closed || requested || !surface.isAttachedToWindow || !surface.holder.surface.isValid) return
        val endpoint = remote ?: return
        val token = surface.hostToken ?: return
        val display = surface.display ?: return
        if (surface.width <= 0 || surface.height <= 0) return
        val hosts = pageHosts ?: return
        requested = true
        val pageContext = Bundle().apply {
            putString("entryPoint", session.embeddedEntryPoint)
            putBoolean("darkTheme", theme.dark)
            putBundle("theme", theme.toBundle())
            putBoolean("hostSheet", true)
            putBoolean("ownActivityRequests", true)
            putStringArrayList("hostIds", ArrayList(hosts.map { it.first }))
            putStringArrayList("hostNames", ArrayList(hosts.map { it.second ?: L10n.tr("未命名主机") }))
        }
        try { endpoint.openPage(bindingId, requestId, token, display.displayId, surface.width, surface.height, pageContext, callback) }
        catch (_: Exception) { fail() }
    }
    fun back() { if (!closed && opened) runCatching { control?.back() } }
    fun sheetAction(sheetId: String, actionId: String) {
        if (closed || !opened || !isForeground() || currentSheet?.id != sheetId) return
        if (actionId == "dismiss" && currentSheet?.dismissible != true) return
        if (actionId != "dismiss" && currentSheet?.items?.none { it.enabled && it.action == actionId } != false) return
        runCatching { control?.sheetAction(sheetId, actionId) }
    }
    fun setTheme(value: EmbeddedPageTheme) {
        theme = value
        if (!closed && opened) {
            runCatching { control?.setDarkTheme(value.dark) }
            runCatching { control?.setTheme(value.toBundle()) }
        }
    }
    private fun fail() { if (!closed) { close(); unavailable() } }
    override fun close() {
        if (closed) return
        closed = true
        currentSheet = null; sheetChanged(null)
        main.removeCallbacks(timeout)
        hostReader.shutdownNow()
        surface.holder.removeCallback(holderCallback)
        runCatching { control?.close() }; control = null
        packageValue?.release(); packageValue = null
        remote = null
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
    }
}
