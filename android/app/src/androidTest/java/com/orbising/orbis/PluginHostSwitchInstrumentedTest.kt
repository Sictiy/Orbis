package com.orbising.orbis

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import com.orbising.orbis.plugin.sdk.PluginUiComponent
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Exercises host switching against a separate synthetic plugin process. */
class PluginHostSwitchInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<android.content.Context>(), "plugin-host-switch")
    @After fun cleanup() { app.disposePluginTestStorage() }

    @Test fun switchingHostsRetainsThePendingOperationAndDuplicateGate() {
        val context = app
        val candidate = PluginServiceDiscovery.find(context.packageManager)
            .single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val pool = PluginControlPool(context)
        val firstHost = "pool-timed-operation-fixture-${UUID.randomUUID()}"
        val secondHost = "pool-second-fixture"
        lateinit var first: PluginControl
        lateinit var session: PluginSession
        try {
            compose.runOnIdle {
                first = pool.forHost(firstHost)
                first.connect(candidate)
            }
            compose.waitUntil(20_000) { first.session(candidate)?.hostActionSnapshot != null && first.session(candidate)?.feedback?.canStart == true }
            compose.runOnIdle {
                session = first.session(candidate)!!
                session.performHostAction(session.hostActionSnapshot!!.handle("fixture.action")!!)
            }
            compose.waitUntil(10_000) { session.feedback.state == PluginOperationState.Waiting }
            compose.runOnIdle {
                pool.forHost(secondHost)
                val returned = pool.forHost(firstHost)
                assertSame("The controller containing an in-flight operation must survive a host switch", first, returned)
                assertSame(session, returned.session(candidate))
                assertTrue(session.connected)
                assertFalse("A host switch must not permit a duplicate action", session.feedback.canStart)
            }
            compose.waitUntil(10_000) { session.feedback.state == PluginOperationState.Waiting }
        } finally {
            compose.runOnIdle {
                first.disable(candidate)
                pool.close()
            }
        }
    }

    @Test fun completedOperationsReleaseInactiveBindingsAcrossManyHosts() {
        val context = app
        val candidate = PluginServiceDiscovery.find(context.packageManager)
            .single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val pool = PluginControlPool(context)
        val unique = UUID.randomUUID().toString()
        val hosts = (0 until 10).map { "pool-settled-$unique-$it" }
        val anchor = PluginControl(context, "binding-count-probe-$unique")
        fun bindingCount(): Int? = (anchor.session(candidate)?.snapshot?.contributions?.firstOrNull()?.component as?
            PluginUiComponent.StatusBadge)?.label?.substringAfter("bindings:")?.toIntOrNull()
        fun waitForCount(expected: Int) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                compose.runOnIdle { anchor.refreshHostContributions() }
                Thread.sleep(100)
                if (bindingCount() == expected) return
            }
            fail("Expected $expected bindings, got ${bindingCount()}")
        }
        try {
            compose.runOnIdle { anchor.connect(candidate) }
            compose.waitUntil(20_000) { bindingCount() != null }
            val baseline = bindingCount()!!
            hosts.forEachIndexed { index, host ->
                lateinit var controller: PluginControl
                compose.runOnIdle { controller = pool.forHost(host); controller.connect(candidate) }
                compose.waitUntil(20_000) { controller.session(candidate)?.hostActionSnapshot != null && controller.session(candidate)?.feedback?.canStart == true }
                val session = controller.session(candidate)!!
                compose.runOnIdle { session.performHostAction(session.hostActionSnapshot!!.handle("fixture.action")!!) }
                compose.waitUntil(10_000) { session.feedback.state == PluginOperationState.Waiting }
                compose.runOnIdle { pool.forHost("pool-park-$unique-$index") }
                compose.waitUntil(10_000) { session.feedback.state == PluginOperationState.Succeeded && !session.connected }
                waitForCount(baseline)
            }
            lateinit var returned: PluginControl
            compose.runOnIdle { returned = pool.forHost(hosts.first()) }
            compose.waitUntil(20_000) { returned.session(candidate)?.connected == true }
            waitForCount(baseline + 1)
        } finally { compose.runOnIdle { pool.close(); anchor.disable(candidate); anchor.close() } }
    }

    @Test fun inactiveFailedAndUnknownResultsKeepFeedbackWithoutHoldingBindings() {
        val context = app
        val candidate = PluginServiceDiscovery.find(context.packageManager)
            .single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val pool = PluginControlPool(context)
        val unique = UUID.randomUUID().toString()
        try {
            for ((suffix, expected) in listOf("sync-failure" to PluginOperationState.Failed, "timeout" to PluginOperationState.Unknown)) {
                val host = "pool-$suffix-$unique"
                lateinit var controller: PluginControl
                lateinit var session: PluginSession
                compose.runOnIdle { controller = pool.forHost(host); controller.connect(candidate) }
                compose.waitUntil(20_000) { controller.session(candidate)?.hostActionSnapshot != null && controller.session(candidate)?.feedback?.canStart == true }
                compose.runOnIdle {
                    session = controller.session(candidate)!!
                    session.performHostAction(session.hostActionSnapshot!!.handle("fixture.action")!!)
                    pool.forHost("pool-park-$suffix-$unique")
                }
                compose.waitUntil(10_000) { session.feedback.state == expected && !session.connected }
                lateinit var returned: PluginControl
                compose.runOnIdle { returned = pool.forHost(host) }
                assertSame(controller, returned)
                compose.waitUntil(20_000) { returned.session(candidate)?.connected == true }
                assertEquals(expected, returned.session(candidate)!!.feedback.state)
                if (expected == PluginOperationState.Unknown) {
                    assertFalse(returned.session(candidate)!!.feedback.canStart)
                    compose.runOnIdle { returned.session(candidate)!!.feedback.acknowledge() }
                    compose.waitUntil(10_000) { returned.session(candidate)!!.feedback.canStart }
                }
            }
        } finally { compose.runOnIdle { pool.forHost("pool-cleanup-$unique").disable(candidate); pool.close() } }
    }
}
