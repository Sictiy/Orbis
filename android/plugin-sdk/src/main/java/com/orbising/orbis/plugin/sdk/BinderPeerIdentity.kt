package com.orbising.orbis.plugin.sdk

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.Process
import java.security.MessageDigest

/** A snapshot captured from an incoming Binder transaction, never from its JSON. */
data class BinderPeerIdentity(
    val uid: Int,
    val packageName: String,
    val signerSha256: String,
)

/** An explicit user approval must create this record before privileged access. */
data class ApprovedPackageSigner(val packageName: String, val signerSha256: String) {
    fun matches(peer: BinderPeerIdentity): Boolean =
        packageName == peer.packageName && signerSha256 == peer.signerSha256
}

object BinderPeerVerifier {
    /**
     * Call directly inside each Stub transaction, before switching threads. Null means
     * reject. Shared UIDs, multiple current signers and unknown packages fail closed.
     * Signature rotation is a new identity and needs approval again.
     */
    @Suppress("DEPRECATION")
    fun captureCallingPeer(packageManager: PackageManager): BinderPeerIdentity? {
        val uid = Binder.getCallingUid()
        if (uid <= 0 || uid == Process.myUid()) return null
        return try {
            val packages = packageManager.getPackagesForUid(uid) ?: return null
            if (packages.size != 1) return null
            captureInstalledPeer(packageManager, packages.single())?.takeIf { it.uid == uid }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Resolves an installed package's current identity for discovery/approval.
     * This is not proof of a Binder caller: callers must still use
     * [captureCallingPeer] inside each transaction and compare both snapshots.
     */
    @Suppress("DEPRECATION")
    fun captureInstalledPeer(packageManager: PackageManager, packageName: String): BinderPeerIdentity? {
        return try {
            val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val info = packageManager.getPackageInfo(packageName, flag)
            val uid = info.applicationInfo?.uid ?: return null
            if (uid <= 0 || info.packageName != packageName || info.sharedUserId != null) return null
            val packages = packageManager.getPackagesForUid(uid) ?: return null
            if (packages.size != 1 || packages.single() != packageName) return null
            val signatures = (if (Build.VERSION.SDK_INT >= 28) {
                val signingInfo = info.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) return null
                signingInfo.apkContentsSigners
            } else info.signatures) ?: return null
            if (signatures.size != 1) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
            val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            BinderPeerIdentity(uid, packageName, hex)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Invoke in onTransact before generated code unmarshals a request or callback. */
    fun isParcelWithinLimit(data: Parcel): Boolean =
        data.dataSize() <= PluginProtocol.MAX_PARCEL_BYTES
}
