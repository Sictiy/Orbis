package com.orbising.orbis

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import com.orbising.orbis.plugin.sdk.BinderPeerIdentity
import com.orbising.orbis.plugin.sdk.PluginServiceCandidate
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PluginMissingApprovalInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-missing-approval")
    private val controllers = mutableListOf<PluginControl>()
    private val missing = ApprovedPluginService("test.missing.plugin", "a".repeat(64), "test.missing.plugin.Service")
    private fun track(controller: PluginControl) = controller.also { controllers.add(it) }

    @After fun cleanup() {
        compose.runOnIdle { controllers.forEach { it.close() } }
        app.disposePluginTestStorage()
    }

    @Test fun unavailableApprovalFromAnotherHostCanBeRemovedInSettings() {
        val store = PluginApprovalStore(app)
        val other = ApprovedPluginService("test.other.plugin", "b".repeat(64), "test.other.plugin.Service")
        store.save("host-b", missing)
        store.save(null, missing)
        store.save("host-b", other)
        val controller = track(PluginControl(app, "host-a", discover = { emptyList() }))
        compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) {
            CompositionLocalProvider(LocalPluginControl provides controller) { PluginSettings("host-a") }
        } } }
        compose.onNodeWithTag("settings-manage-plugins").performClick()
        compose.waitUntil(10_000) { controller.message == PluginNotice.NoneFound }
        compose.onNodeWithTag("plugin-missing-${missing.packageName}").assertIsDisplayed()
        compose.onNodeWithTag("plugin-missing-remove-${missing.packageName}").performClick()
        compose.onNodeWithTag("plugin-missing-confirm-${missing.packageName}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("plugin-missing-${missing.packageName}").assertDoesNotExist()
        assertFalse(store.allApprovals().contains(missing))
        assertEquals(listOf(other), store.forHost("host-b"))
        assertTrue(store.forHost(null).isEmpty())
    }

    @Test fun installedPluginApprovedOnAnotherHostCanBeRemovedWithoutActivatingHere() {
        val store = PluginApprovalStore(app)
        val candidate = PluginServiceCandidate(BinderPeerIdentity(20001, missing.packageName, missing.signerSha256), missing.className)
        store.save("host-a", missing)
        val controller = track(PluginControl(app, "host-b", discover = { listOf(candidate) }))
        compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) {
            CompositionLocalProvider(LocalPluginControl provides controller) { PluginSettings("host-b") }
        } } }

        compose.onNodeWithTag("settings-manage-plugins").performClick()
        compose.waitUntil(10_000) { controller.message == PluginNotice.Choose }
        compose.onNodeWithTag("plugin-manage-${missing.packageName}").performClick()
        compose.onNodeWithTag("plugin-activate").assertIsDisplayed()
        compose.onNodeWithTag("plugin-activate").performClick()
        compose.onNodeWithTag("plugin-approval-allow").assertIsDisplayed()
        compose.onNodeWithTag("plugin-approval-cancel").performClick()
        compose.onNodeWithTag("plugin-disconnect").performClick()
        compose.onNodeWithTag("plugin-confirm-remove").performClick()
        compose.runOnIdle {
            assertTrue(store.allApprovals().isEmpty())
            assertFalse(controller.isApproved(candidate))
            assertNull(controller.session(candidate))
        }
    }

    @Test fun removalWhileDiscoveryIsInFlightCannotRestoreThePlugin() {
        PluginApprovalStore(app).save("host-a", missing)
        val candidate = PluginServiceCandidate(BinderPeerIdentity(20001, missing.packageName, missing.signerSha256), missing.className)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val controller = track(PluginControl(app, "host-a", discover = {
            started.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            listOf(candidate)
        }))
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            compose.runOnIdle {
                controller.removeMissingApproval(missing)
                controller.search()
            }
            proceed.countDown()
            compose.waitUntil(10_000) { controller.message == PluginNotice.Choose }
            compose.runOnIdle {
                assertNull(controller.session(candidate))
                assertFalse(controller.isApproved(candidate))
                assertTrue(PluginApprovalStore(app).allApprovals().isEmpty())
            }
        } finally { proceed.countDown() }
    }

    @Test fun removalDisconnectsOtherHostsStillHoldingAnOperation() {
        val candidate = PluginServiceDiscovery.find(app.packageManager)
            .single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val approval = ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName)
        val firstHost = "timed-operation-fixture-${UUID.randomUUID()}"
        val secondHost = "approval-second-host"
        val store = PluginApprovalStore(app)
        store.save(firstHost, approval)
        store.save(secondHost, approval)
        val pool = PluginControlPool(app)
        try {
            lateinit var first: PluginControl
            lateinit var second: PluginControl
            compose.runOnIdle { first = pool.forHost(firstHost) }
            compose.waitUntil(20_000) {
                first.session(candidate)?.let { it.connected && it.hostActionSnapshot != null && it.feedback.canStart } == true
            }
            compose.runOnIdle {
                val session = first.session(candidate)!!
                val snapshot = session.hostActionSnapshot!!
                session.performHostAction(snapshot.handle(snapshot.contributions.first().id)!!)
            }
            compose.waitUntil(10_000) { first.session(candidate)?.feedback?.state == PluginOperationState.Waiting }
            compose.runOnIdle { second = pool.forHost(secondHost) }
            compose.waitUntil(20_000) { second.session(candidate)?.connected == true }
            compose.runOnIdle {
                second.removeMissingApproval(approval)
                assertFalse(first.session(candidate)?.connected == true)
                assertFalse(second.session(candidate)?.connected == true)
                assertFalse(first.isApproved(candidate))
                assertTrue(store.allApprovals().isEmpty())
            }
        } finally { compose.runOnIdle { pool.close() } }
    }
}
