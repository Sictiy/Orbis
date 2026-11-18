package com.orbising.orbis

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.PluginUiComponent
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PluginSessionFailureInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-session-failure")
    @After fun cleanup() { context.disposePluginTestStorage() }

    private fun controller(host: String): Pair<PluginControl, com.orbising.orbis.plugin.sdk.PluginServiceCandidate> {
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val control = PluginControl(context, host)
        compose.runOnIdle { control.connect(candidate) }
        compose.waitUntil(20_000) { control.session(candidate)?.hostActionSnapshot != null && control.session(candidate)?.feedback?.canStart == true }
        return control to candidate
    }

    @Test fun synchronousActionSuccessAndExplicitFailureAreNotUnknown() {
        for ((host, expected) in listOf("sync-success" to PluginOperationState.Succeeded, "sync-failure" to PluginOperationState.Failed)) {
            val (control, candidate) = controller("plugin-$host-fixture")
            try {
                compose.runOnIdle {
                    val session = control.session(candidate)!!
                    session.performHostAction(session.hostActionSnapshot!!.handle("fixture.action")!!)
                }
                compose.waitUntil(10_000) { control.session(candidate)?.feedback?.state == expected }
                assertTrue(control.session(candidate)!!.feedback.canStart)
            } finally { compose.runOnIdle { control.disable(candidate); control.close() } }
        }
    }

    @Test fun aSingleRejectedSlotDoesNotDisconnectOtherContributions() {
        val (control, candidate) = controller("plugin-slot-failure-fixture")
        try {
            compose.waitUntil(10_000) { control.session(candidate)?.navigationSnapshot != null }
            compose.runOnIdle {
                val session = control.session(candidate)!!
                assertTrue(session.connected)
                assertNull(session.snapshot)
                assertNotNull(session.hostActionSnapshot)
                assertNotNull(session.navigationSnapshot)
            }
        } finally { compose.runOnIdle { control.disable(candidate); control.close() } }
    }

    @Test fun closingOneBindingReleasesItWhileAnotherClientStaysBound() {
        val (anchor, candidate) = controller("binding-count-probe")
        fun currentCount(): Int? = (anchor.session(candidate)?.snapshot?.contributions?.firstOrNull()?.component as?
            PluginUiComponent.StatusBadge)?.label?.substringAfter("bindings:")?.toIntOrNull()
        fun waitForCount(expected: Int) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                compose.runOnIdle { anchor.refreshHostContributions() }
                Thread.sleep(100)
                if (currentCount() == expected) return
            }
            fail("Expected $expected open bindings, got ${currentCount()}")
        }
        try {
            compose.waitUntil(10_000) { currentCount() != null }
            val baseline = currentCount()!!
            repeat(10) { index ->
                val temporary = PluginControl(context, "binding-temporary-$index")
                try {
                    compose.runOnIdle { temporary.connect(candidate) }
                    compose.waitUntil(10_000) { temporary.session(candidate)?.connected == true }
                    waitForCount(baseline + 1)
                } finally { compose.runOnIdle { temporary.close() } }
                waitForCount(baseline)
            }
            assertTrue(anchor.session(candidate)!!.connected)
        } finally { compose.runOnIdle { anchor.disable(candidate); anchor.close() } }
    }

    @Test fun removalRevokesThePluginForEveryRetainedHost() {
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val pool = PluginControlPool(context)
        lateinit var first: PluginControl
        lateinit var second: PluginControl
        try {
            compose.runOnIdle { first = pool.forHost("remove-host-a"); first.connect(candidate) }
            compose.waitUntil(20_000) { first.session(candidate)?.connected == true && first.session(candidate)?.feedback?.canStart == true }
            compose.runOnIdle {
                assertTrue(first.session(candidate)!!.feedback.begin())
                first.session(candidate)!!.feedback.update(PluginOperationState.Unknown)
                second = pool.forHost("remove-host-b")
                second.connect(candidate)
            }
            compose.waitUntil(20_000) { second.session(candidate)?.connected == true }
            compose.runOnIdle {
                second.disable(candidate)
                assertFalse(first.isApproved(candidate))
                assertFalse(second.isApproved(candidate))
                assertFalse(first.session(candidate)!!.connected)
                assertFalse(second.session(candidate)!!.connected)
                assertFalse(PluginApprovalStore(context).forHost("remove-host-a").any { it.matches(candidate) })
                assertFalse(PluginApprovalStore(context).forHost("remove-host-b").any { it.matches(candidate) })
            }
        } finally { compose.runOnIdle { pool.close() } }
    }
}
