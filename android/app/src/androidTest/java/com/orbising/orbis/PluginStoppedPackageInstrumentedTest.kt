package com.orbising.orbis

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PluginStoppedPackageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stoppedFixture = StoppedPluginFixture(ApplicationProvider.getApplicationContext())

    @After fun restoreFixture() { stoppedFixture.restore() }

    @Test fun userActivationConnectsStoppedSyntheticPlugin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        stoppedFixture.stop()
        val candidate = checkNotNull(PluginServiceDiscovery.find(context.packageManager).singleOrNull {
            it.peer.packageName == SYNTHETIC_PLUGIN_PACKAGE
        }) { "Installed stopped plugin must be discoverable" }
        val app = IsolatedTestApplication(context, "plugin-stopped")
        val controller = PluginControl(app, "stopped-package-test")
        val finished = CountDownLatch(1)
        var result = Activity.RESULT_CANCELED
        var activation: PluginUserActivation? = null
        var launchError: Throwable? = null
        var launcher: ActivityResultLauncher<Intent>? = null
        try {
            val hostActivity = compose.activity
            instrumentation.runOnMainSync {
                // The empty test Activity supplies a real foreground registry without
                // starting MainActivity or reading the user's host state.
                val registered = hostActivity.activityResultRegistry.register("stopped-plugin-test", ActivityResultContracts.StartActivityForResult()) {
                    result = it.resultCode
                    finished.countDown()
                }
                launcher = registered
                controller.prepareUserActivation(candidate) { pending, intent ->
                    try {
                        activation = pending
                        checkNotNull(intent) { "No trusted activation activity was found" }
                        registered.launch(intent)
                    } catch (e: Throwable) { launchError = e; finished.countDown() }
                }
            }
            assertTrue("Activation did not return", finished.await(20, TimeUnit.SECONDS))
            assertTrue("Activation failed: $launchError, result=$result", launchError == null && result == Activity.RESULT_OK)
            instrumentation.runOnMainSync { controller.connectAfterUserActivation(checkNotNull(activation)) }
            var connected = false
            val deadline = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < deadline && !connected) {
                instrumentation.runOnMainSync { connected = controller.session(candidate)?.connected == true }
                if (!connected) Thread.sleep(100)
            }
            var message: PluginNotice? = null
            instrumentation.runOnMainSync { message = controller.session(candidate)?.message }
            assertTrue("Activated plugin did not connect: $message", connected)
        } finally {
            instrumentation.runOnMainSync { launcher?.unregister(); controller.disable(candidate); controller.close() }
            app.disposePluginTestStorage()
        }
    }
}
