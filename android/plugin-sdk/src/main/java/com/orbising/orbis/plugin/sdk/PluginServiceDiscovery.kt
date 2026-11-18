package com.orbising.orbis.plugin.sdk

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

/** Discovery is informational; it does not enable, bind or authorize a service. */
data class PluginServiceCandidate(val peer: BinderPeerIdentity, val serviceClassName: String) {
    fun componentName(): ComponentName = ComponentName(peer.packageName, serviceClassName)
}

/** A user's approval is specific to the service and its current signing certificate. */
data class ApprovedPluginService(val packageName: String, val signerSha256: String, val className: String) {
    fun matches(candidate: PluginServiceCandidate): Boolean =
        candidate.peer.packageName == packageName && candidate.peer.signerSha256 == signerSha256 &&
            candidate.serviceClassName == className

    /** The candidate must be freshly resolved before binding and invalidated on package change. */
    fun matchesCaller(candidate: PluginServiceCandidate, caller: BinderPeerIdentity): Boolean =
        matches(candidate) && candidate.peer == caller
}

object PluginServiceDiscovery {
    /**
     * Call off the main thread. The host manifest needs a narrow <queries> entry
     * for [PluginProtocol.SERVICE_ACTION]. Each candidate must be shown to the
     * user for explicit, capability-specific approval before it is bound.
     */
    @Suppress("DEPRECATION")
    fun find(packageManager: PackageManager): List<PluginServiceCandidate> {
        val results = try {
            packageManager.queryIntentServices(Intent(PluginProtocol.SERVICE_ACTION), PackageManager.GET_META_DATA)
        } catch (_: SecurityException) {
            return emptyList()
        }
        return results.mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            val application = service.applicationInfo ?: return@mapNotNull null
            val packageName = service.packageName ?: return@mapNotNull null
            val className = service.name ?: return@mapNotNull null
            if (!service.exported || !service.enabled || !application.enabled ||
                application.packageName != packageName || className.isBlank() || className.length > 512 ||
                service.metaData?.getInt(PluginProtocol.API_VERSION_METADATA, -1) != PluginProtocol.API_VERSION
            ) return@mapNotNull null
            val peer = BinderPeerVerifier.captureInstalledPeer(packageManager, packageName) ?: return@mapNotNull null
            if (peer.uid == Process.myUid() || peer.uid != application.uid) return@mapNotNull null
            PluginServiceCandidate(peer, className)
        }.distinctBy { it.peer.packageName to it.serviceClassName }.sortedWith(compareBy({ it.peer.packageName }, { it.serviceClassName }))
    }
}
