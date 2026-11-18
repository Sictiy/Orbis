package com.orbising.orbis

import android.content.Context
import android.content.SharedPreferences
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/** Writes and reads for all identities are ordered even if a controller is replaced. */
internal object PluginPendingOperationIo {
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "plugin-pending-io").apply { isDaemon = true } }
}

/** A failed commit may already have removed the key from SharedPreferences' in-memory view. */
private object UnconfirmedPendingClears {
    private val keys = IdentityHashMap<SharedPreferences, MutableSet<String>>()

    fun exists(preferences: SharedPreferences, key: String): Boolean = synchronized(this) {
        keys[preferences]?.contains(key) == true ||
            runCatching { preferences.getBoolean(key, false) }.getOrDefault(true)
    }

    fun set(preferences: SharedPreferences, key: String, pending: Boolean): Boolean = synchronized(this) {
        if (!pending) keys.getOrPut(preferences) { mutableSetOf() }.add(key)
        val written = runCatching {
            val edit = preferences.edit()
            if (pending) edit.putBoolean(key, true) else edit.remove(key)
            edit.commit()
        }.getOrDefault(false)
        if (!pending && written) {
            keys[preferences]?.let { outstanding ->
                outstanding.remove(key)
                if (outstanding.isEmpty()) keys.remove(preferences)
            }
        }
        written
    }
}

/** A separate key per exact host and signed plugin service; no action is retried on restore. */
internal class PluginPendingOperationStore(
    private val context: Context,
    private val hostId: String?,
    private val approval: ApprovedPluginService,
    private val preferenceName: String = "orbis_plugin_pending_operations_v1",
) : PluginPendingOperation {
    private val preferences by lazy { context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE) }
    private val key: String by lazy {
        val identity = buildString {
            listOf(hostId, approval.packageName, approval.className, approval.signerSha256).forEach { part ->
                if (part == null) append("-1:")
                else { append(part.toByteArray(Charsets.UTF_8).size); append(':'); append(part) }
            }
        }
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun exists(): Boolean = runCatching { UnconfirmedPendingClears.exists(preferences, key) }.getOrDefault(true)

    override fun set(pending: Boolean): Boolean =
        runCatching { UnconfirmedPendingClears.set(preferences, key, pending) }.getOrDefault(false)
}
