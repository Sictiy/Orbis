package com.orbising.orbis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.orbising.orbis.plugin.sdk.PluginProtocol

/** Optional setup Activity for any plugin; host metadata is sent only to a protected same-signer component. */
@Suppress("DEPRECATION")
internal fun pluginSetupIntent(context: Context, packageName: String): Intent? {
    val pm = context.packageManager
    val query = Intent(PluginProtocol.SETUP_ACTION).setPackage(packageName)
    val match = if (pm.checkSignatures(context.packageName, packageName) == PackageManager.SIGNATURE_MATCH) {
        runCatching { pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
            .mapNotNull { it.activityInfo }.singleOrNull { info ->
                val permission = info.permission ?: return@singleOrNull false
                val definition = runCatching { pm.getPermissionInfo(permission, 0) }.getOrNull() ?: return@singleOrNull false
                info.packageName == packageName && info.enabled && info.exported &&
                    (definition.packageName == packageName ||
                        (permission == context.packageName + PluginProtocol.HOST_PERMISSION_SUFFIX && definition.packageName == context.packageName)) &&
                    definition.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_SIGNATURE &&
                    pm.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
            }
    } else null
    if (match == null) return pm.getLaunchIntentForPackage(packageName)
    val hosts = HostPairingStore(context).hosts().take(128)
    return query.setComponent(ComponentName(match.packageName, match.name))
        .putStringArrayListExtra("hostIds", ArrayList(hosts.map { it.hostId }))
        .putStringArrayListExtra("hostNames", ArrayList(hosts.map { it.hostName ?: L10n.tr("未命名主机") }))
}
