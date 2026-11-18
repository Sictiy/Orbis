package com.orbising.orbis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.orbising.orbis.plugin.sdk.PluginProtocol
import org.junit.Assume.assumeTrue

internal const val SYNTHETIC_PLUGIN_PACKAGE = "com.orbising.orbis.plugintest"

/** Force-stops only our debug fixture on an emulator and restores it after the test. */
internal class StoppedPluginFixture(private val context: Context) {
    private var stoppedByTest = false

    @Suppress("DEPRECATION")
    private fun isStopped(): Boolean = context.packageManager
        .getApplicationInfo(SYNTHETIC_PLUGIN_PACKAGE, 0).flags and ApplicationInfo.FLAG_STOPPED != 0

    @Suppress("DEPRECATION")
    fun requireSupported() {
        val emulator = Build.FINGERPRINT.startsWith("generic") ||
            Build.HARDWARE in setOf("goldfish", "ranchu") || Build.PRODUCT.startsWith("sdk_gphone")
        assumeTrue("Stopping the synthetic plugin requires an emulator", emulator)
        val pm = context.packageManager
        val fixture = pm.getApplicationInfo(SYNTHETIC_PLUGIN_PACKAGE, 0)
        check(fixture.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            pm.checkSignatures(context.packageName, SYNTHETIC_PLUGIN_PACKAGE) == PackageManager.SIGNATURE_MATCH) {
            "Install the same-signer debug synthetic plugin before this test"
        }
    }

    fun stop() {
        requireSupported()
        stoppedByTest = true
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am force-stop $SYNTHETIC_PLUGIN_PACKAGE").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        check(isStopped()) { "Synthetic plugin was not stopped" }
    }

    @Suppress("DEPRECATION")
    fun restore() {
        if (!stoppedByTest || !isStopped()) return
        val pm = context.packageManager
        val intent = Intent(PluginProtocol.ACTIVATE_ACTION).setPackage(SYNTHETIC_PLUGIN_PACKAGE)
        val activity = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo }
            .single { it.permission == context.packageName + PluginProtocol.HOST_PERMISSION_SUFFIX }
        intent.component = ComponentName(activity.packageName, activity.name)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (isStopped() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        check(!isStopped()) { "Could not restore the stopped synthetic fixture after the test" }
    }
}
