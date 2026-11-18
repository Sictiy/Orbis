package com.orbising.orbis

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Delays Activity results so configuration and Host changes can happen first. */
class PluginActivationLifecycleInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-activation-lifecycle")
    private val stoppedFixture = StoppedPluginFixture(app)
    private val controllers = mutableListOf<PluginControl>()
    private val candidate by lazy {
        PluginServiceDiscovery.find(app.packageManager).single { it.peer.packageName == SYNTHETIC_PLUGIN_PACKAGE }
    }
    private val approval by lazy { ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName) }

    private class DeferredRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Int>()
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) { launches.add(requestCode) }

        fun succeed(index: Int) { dispatchResult(launches[index], Activity.RESULT_OK, Intent()) }
        fun cancel(index: Int) { dispatchResult(launches[index], Activity.RESULT_CANCELED, Intent()) }
    }

    private fun controller(host: String, coordinator: PluginActivationCoordinator = PluginActivationCoordinator()) =
        PluginControl(app, host, activationCoordinator = coordinator).also(controllers::add)

    private fun host() = "activation-lifecycle-${UUID.randomUUID()}"

    @After fun cleanup() {
        compose.runOnIdle { controllers.forEach { it.close() } }
        try { stoppedFixture.restore() } finally { app.disposePluginTestStorage() }
    }

    @Test fun manualResultSurvivesSavedStateRestoration() {
        val control = controller(host())
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { PluginActivationHost(control) } }

        compose.runOnIdle { control.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { registry.launches.size == 1 }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(1, registry.launches.size); registry.succeed(0) }
        compose.waitUntil(20_000) { control.session(candidate)?.connected == true }
        compose.runOnIdle { assertTrue(control.isApproved(candidate)) }
    }

    @Test fun automaticResultSurvivesSavedStateRestoration() {
        stoppedFixture.requireSupported()
        val hostId = host()
        PluginApprovalStore(app).save(hostId, approval)
        stoppedFixture.stop()
        val control = controller(hostId)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { PluginActivationHost(control) } }

        compose.waitUntil(15_000) { registry.launches.size == 1 }
        restoration.emulateSavedInstanceStateRestore()
        // The fake registry did not actually start the Activity; clear the
        // stopped state before delivering its simulated successful result.
        stoppedFixture.restore()
        compose.runOnIdle { assertEquals(1, registry.launches.size); registry.succeed(0) }
        compose.waitUntil(20_000) { control.session(candidate)?.connected == true }
        compose.runOnIdle { assertTrue(control.isApproved(candidate)) }
    }

    @Test fun removalAndHostSwitchDoNotTransferAnOldResult() {
        val coordinator = PluginActivationCoordinator()
        val first = controller(host(), coordinator)
        val second = controller(host(), coordinator)
        var selected by mutableStateOf(first)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { PluginActivationHost(selected) } }

        compose.runOnIdle { first.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { registry.launches.size == 1 }
        compose.runOnIdle { first.disable(candidate); selected = second; second.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { coordinator.hasQueuedManual }
        compose.runOnIdle { registry.succeed(0) }
        compose.waitUntil(10_000) { registry.launches.size == 2 }
        compose.runOnIdle {
            assertFalse(first.isApproved(candidate))
            assertFalse(second.isApproved(candidate))
            assertFalse(second.session(candidate)?.connected == true)
            registry.succeed(1)
        }
        compose.waitUntil(20_000) { second.session(candidate)?.connected == true }
        compose.runOnIdle { assertTrue(second.isApproved(candidate)); assertFalse(first.isApproved(candidate)) }
    }

    @Test fun resultFromOldProcessCannotCompleteANewActivation() {
        val old = controller(host())
        val replacement = controller(host())
        var selected by mutableStateOf(old)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { PluginActivationHost(selected) } }

        compose.runOnIdle { old.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { registry.launches.size == 1 }
        compose.runOnIdle { old.close(); selected = replacement; replacement.requestUserActivation(candidate) }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { registry.launches.size == 2 }
        compose.runOnIdle {
            assertNotEquals(registry.launches[0], registry.launches[1])
            registry.succeed(0)
            assertFalse(replacement.isApproved(candidate))
            assertFalse(replacement.session(candidate)?.connected == true)
            registry.succeed(1)
        }
        compose.waitUntil(20_000) { replacement.session(candidate)?.connected == true }
        compose.runOnIdle { assertTrue(replacement.isApproved(candidate)) }
    }

    @Test fun processRecreationReleasesReservedButUnlaunchedSlot() {
        val old = controller(host())
        val replacement = controller(host())
        var selected by mutableStateOf(old)
        var showHost by mutableStateOf(true)
        val initialStateRegistry = SaveableStateRegistry(null) { true }
        var stateRegistry by mutableStateOf(initialStateRegistry)
        val activityRegistry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = activityRegistry }
        compose.setContent {
            if (showHost) {
                CompositionLocalProvider(
                    LocalSaveableStateRegistry provides stateRegistry,
                    LocalActivityResultRegistryOwner provides owner,
                ) { PluginActivationHost(selected, maxOutstanding = 1) }
            }
        }

        // Pause frames after the admission effect reserves an id, before the slot
        // can be composed and launch an Activity. Save exactly that state.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { old.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { old.activationCoordinator.hasQueuedManual }
        compose.mainClock.advanceTimeByFrame()
        var saved = emptyMap<String, List<Any?>>()
        compose.runOnIdle {
            assertTrue(old.activationCoordinator.inFlight != null)
            assertEquals(0, activityRegistry.launches.size)
            saved = initialStateRegistry.performSave()
            old.close()
            selected = replacement
            showHost = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            stateRegistry = SaveableStateRegistry(saved) { true }
            showHost = true
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.runOnIdle { replacement.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { activityRegistry.launches.size == 1 }
        compose.runOnIdle { activityRegistry.succeed(0) }
        compose.waitUntil(20_000) { replacement.session(candidate)?.connected == true }
        compose.runOnIdle { assertTrue(replacement.isApproved(candidate)) }
    }

    @Test fun removedActivationIsNotLaunchedAfterSlotWasReserved() {
        val coordinator = PluginActivationCoordinator()
        val first = controller(host(), coordinator)
        val second = controller(host(), coordinator)
        var selected by mutableStateOf(first)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PluginActivationHost(selected, maxOutstanding = 1)
            }
        }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { first.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { coordinator.hasQueuedManual }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue(coordinator.inFlight?.owner === first)
            assertEquals(0, registry.launches.size)
            first.disable(candidate)
            first.suspendConnections()
            selected = second
            second.requestUserActivation(candidate)
        }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { registry.launches.size == 1 }
        compose.runOnIdle {
            assertTrue(coordinator.inFlight?.owner === second)
            assertFalse(first.isApproved(candidate))
            registry.succeed(0)
        }
        compose.waitUntil(20_000) { second.session(candidate)?.connected == true }
        compose.runOnIdle { assertTrue(second.isApproved(candidate)) }
    }

    @Test fun timedOutResultDoesNotCompleteTheNextActivation() {
        val coordinator = PluginActivationCoordinator()
        val first = controller(host(), coordinator)
        val second = controller(host(), coordinator)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PluginActivationHost(first, resultTimeoutMs = 3_000L)
            }
        }

        compose.runOnIdle { first.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { registry.launches.size == 1 }
        compose.runOnIdle { second.requestUserActivation(candidate) }
        compose.waitUntil(15_000) { registry.launches.size == 2 }
        compose.runOnIdle {
            assertEquals(PluginNotice.ConnectionFailed, first.session(candidate)?.message)
            assertNotEquals(registry.launches[0], registry.launches[1])
            registry.succeed(0)
            assertFalse(second.isApproved(candidate))
            assertFalse(second.session(candidate)?.connected == true)
            assertTrue(coordinator.inFlight?.owner === second)
            registry.succeed(1)
        }
        compose.waitUntil(20_000) { second.session(candidate)?.connected == true }
        compose.runOnIdle { assertFalse(first.isApproved(candidate)); assertTrue(second.isApproved(candidate)) }
    }

    @Test fun unansweredAutomaticActivationIsNotRepeatedOnRefresh() {
        stoppedFixture.requireSupported()
        val hostId = host()
        PluginApprovalStore(app).save(hostId, approval)
        stoppedFixture.stop()
        val discoveries = AtomicInteger()
        val control = PluginControl(app, hostId, discover = {
            discoveries.incrementAndGet()
            listOf(candidate)
        }).also(controllers::add)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PluginActivationHost(control, resultTimeoutMs = 1_000L)
            }
        }

        compose.waitUntil(10_000) { registry.launches.size == 1 }
        compose.waitUntil(15_000) { control.session(candidate)?.message == PluginNotice.ConnectionFailed }
        compose.runOnIdle {
            assertFalse(control.activationCoordinator.canRestore(candidate))
            control.refresh()
        }
        compose.waitUntil(10_000) { discoveries.get() >= 2 }
        compose.runOnIdle {
            control.refresh()
            assertEquals(1, registry.launches.size)
            assertFalse(control.session(candidate)?.connected == true)
        }
    }

    @Test fun cancelledAutomaticActivationRetriesWithBackoff() {
        stoppedFixture.requireSupported()
        val hostId = host()
        PluginApprovalStore(app).save(hostId, approval)
        stoppedFixture.stop()
        val control = controller(hostId)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { PluginActivationHost(control) } }

        compose.waitUntil(10_000) { registry.launches.size == 1 }
        compose.runOnIdle { registry.cancel(0) }
        compose.waitUntil(15_000) { registry.launches.size == 2 }
        compose.runOnIdle {
            assertTrue(control.activationCoordinator.canRestore(candidate))
            assertNotEquals(registry.launches[0], registry.launches[1])
        }
    }

    @Test fun outstandingLimitRecoversAfterLateResult() {
        val coordinator = PluginActivationCoordinator()
        val first = controller(host(), coordinator)
        val second = controller(host(), coordinator)
        val registry = DeferredRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PluginActivationHost(first, resultTimeoutMs = 1_000L, maxOutstanding = 1)
            }
        }

        compose.runOnIdle { first.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { registry.launches.size == 1 }
        compose.waitUntil(15_000) { first.session(candidate)?.message == PluginNotice.ConnectionFailed }
        compose.runOnIdle { second.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { second.session(candidate)?.message == PluginNotice.ConnectionFailed }
        compose.runOnIdle {
            assertEquals(1, registry.launches.size)
            registry.succeed(0)
        }
        compose.runOnIdle { second.requestUserActivation(candidate) }
        compose.waitUntil(10_000) { registry.launches.size == 2 }
        compose.runOnIdle {
            assertFalse(second.isApproved(candidate))
            registry.succeed(1)
        }
        compose.waitUntil(20_000) { second.session(candidate)?.connected == true }
        compose.runOnIdle { assertTrue(second.isApproved(candidate)) }
    }
}
