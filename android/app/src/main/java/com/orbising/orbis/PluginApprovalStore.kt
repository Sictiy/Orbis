package com.orbising.orbis

import android.content.Context
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import org.json.JSONObject

/** User-approved service identities are scoped to the host shown at approval time. */
internal class PluginApprovalStore(context: Context, preferenceName: String = "orbis_plugin_approvals_v1") {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val key = "approvals"

    private data class Entry(val hostId: String?, val approval: ApprovedPluginService)

    private fun read(): List<Entry> = preferences.getStringSet(key, emptySet()).orEmpty().mapNotNull { raw ->
        runCatching {
            val obj = JSONObject(raw)
            require(obj.getInt("version") == 1)
            val host = if (obj.isNull("host")) null else obj.getString("host")
            val pkg = obj.getString("package")
            val service = obj.getString("service")
            val signer = obj.getString("signer")
            require(pkg.isNotBlank() && service.isNotBlank() && signer.matches(Regex("[0-9a-f]{64}")))
            Entry(host, ApprovedPluginService(pkg, signer, service))
        }.getOrNull()
    }

    fun forHost(hostId: String?): List<ApprovedPluginService> = read().filter { it.hostId == hostId }.map { it.approval }

    /** Plugin removal applies to all Hosts, including approvals from another Host. */
    fun allApprovals(): List<ApprovedPluginService> = read().map { it.approval }.distinct()

    fun save(hostId: String?, approval: ApprovedPluginService) {
        val records = read().filterNot { it.hostId == hostId && it.approval.packageName == approval.packageName && it.approval.className == approval.className } + Entry(hostId, approval)
        write(records)
    }

    fun remove(hostId: String?, approval: ApprovedPluginService) {
        write(read().filterNot { it.hostId == hostId && it.approval == approval })
    }

    /** Unpairing revokes approvals only for the removed Host. */
    fun removeHost(hostId: String) {
        write(read().filterNot { it.hostId == hostId })
    }

    /** Removing a plugin from Orbis revokes this service for every paired Host. */
    fun removeService(packageName: String, className: String) {
        write(read().filterNot { it.approval.packageName == packageName && it.approval.className == className })
    }

    private fun write(entries: List<Entry>) {
        val records = entries.map { entry -> JSONObject().apply {
            put("version", 1)
            put("host", entry.hostId ?: JSONObject.NULL)
            put("package", entry.approval.packageName)
            put("service", entry.approval.className)
            put("signer", entry.approval.signerSha256)
        }.toString() }.toSet()
        preferences.edit().putStringSet(key, records).apply()
    }
}
