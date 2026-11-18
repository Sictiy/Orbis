package com.orbising.orbis

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the synthetic plugin and isolated preferences. */
class PluginLifecycleRecoveryInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val app = IsolatedTestApplication(base, "plugin-lifecycle")
    private val candidate by lazy {
        PluginServiceDiscovery.find(base.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
    }
    private val approval by lazy { ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName) }
    private fun host() = "lifecycle-${UUID.randomUUID()}"

    @After fun cleanup() { app.disposePluginTestStorage() }

    @Test fun removalCancelsAnInProgressManualDiscovery() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val control = PluginControl(app, host(), discover = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            listOf(candidate)
        })
        var ready = false
        try {
            compose.runOnIdle { control.prepareUserActivation(candidate) { _, _ -> ready = true } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { control.disable(candidate); control.search() }
            release.countDown()
            compose.waitUntil(10_000) { control.message == PluginNotice.Choose }
            compose.runOnIdle { assertFalse(ready); assertNull(control.session(candidate)); assertFalse(control.isApproved(candidate)) }
        } finally { release.countDown(); compose.runOnIdle { control.close() } }
    }

    @Test fun removalCancelsTheDiscoveryAfterAnActivityResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val control = PluginControl(app, host(), discover = {
            if (calls.incrementAndGet() == 2) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            listOf(candidate)
        })
        var activation: PluginUserActivation? = null
        try {
            compose.runOnIdle { control.prepareUserActivation(candidate) { pending, _ -> activation = pending } }
            compose.waitUntil(10_000) { activation != null }
            compose.runOnIdle { control.connectAfterUserActivation(checkNotNull(activation)) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { control.disable(candidate); control.search() }
            release.countDown()
            compose.waitUntil(10_000) { control.message == PluginNotice.Choose }
            compose.runOnIdle { assertNull(control.session(candidate)); assertFalse(control.isApproved(candidate)) }
        } finally { release.countDown(); compose.runOnIdle { control.close() } }
    }

    @Test fun aStaleActivityResultCannotCompleteANewerActivation() {
        val calls = AtomicInteger()
        val control = PluginControl(app, host(), discover = { calls.incrementAndGet(); listOf(candidate) })
        var first: PluginUserActivation? = null
        var second: PluginUserActivation? = null
        try {
            compose.runOnIdle { control.prepareUserActivation(candidate) { pending, _ -> first = pending } }
            compose.waitUntil(10_000) { first != null }
            compose.runOnIdle {
                control.disable(candidate)
                control.prepareUserActivation(candidate) { pending, _ -> second = pending }
            }
            compose.waitUntil(10_000) { second != null }
            compose.runOnIdle {
                control.connectAfterUserActivation(checkNotNull(first))
                control.activationFailed(checkNotNull(first))
                control.search()
            }
            compose.waitUntil(10_000) { control.message == PluginNotice.Choose }
            compose.runOnIdle {
                assertEquals(3, calls.get())
                assertNull(control.session(candidate))
                control.connectAfterUserActivation(checkNotNull(second))
            }
            compose.waitUntil(20_000) { control.session(candidate)?.connected == true }
            compose.runOnIdle { assertTrue(control.isApproved(candidate)) }
        } finally { compose.runOnIdle { control.close() } }
    }

    @Test fun unpairRevokesOnlyThatHostAndKeepsAnInterruptedOperationUnknown() {
        val hostId = "timed-operation-fixture-${UUID.randomUUID()}"
        val otherHost = host()
        val approvals = PluginApprovalStore(app)
        val pool = PluginControlPool(app)
        lateinit var old: PluginControl
        lateinit var oldSession: PluginSession
        var activation: PluginUserActivation? = null
        try {
            approvals.save(otherHost, approval)
            approvals.save(null, approval)
            compose.runOnIdle { old = pool.forHost(hostId); old.connect(candidate) }
            compose.waitUntil(20_000) {
                old.session(candidate)?.let { it.hostActionSnapshot != null && it.feedback.canStart } == true
            }
            compose.runOnIdle { old.prepareUserActivation(candidate) { pending, _ -> activation = pending } }
            compose.waitUntil(10_000) { activation != null }
            compose.runOnIdle {
                oldSession = checkNotNull(old.session(candidate))
                oldSession.performHostAction(checkNotNull(oldSession.hostActionSnapshot!!.handle("fixture.action")))
            }
            compose.waitUntil(10_000) { oldSession.feedback.state == PluginOperationState.Waiting }
            compose.runOnIdle {
                pool.removeHost(hostId)
                old.connectAfterUserActivation(checkNotNull(activation))
                old.activationFailed(checkNotNull(activation))
                assertFalse(oldSession.connected)
                assertTrue(approvals.forHost(hostId).isEmpty())
                assertEquals(listOf(approval), approvals.forHost(otherHost))
                assertEquals(listOf(approval), approvals.forHost(null))
            }
            lateinit var restored: PluginControl
            compose.runOnIdle { restored = pool.forHost(hostId); assertFalse(restored.isApproved(candidate)); restored.connect(candidate) }
            compose.waitUntil(20_000) {
                restored.session(candidate)?.let { it.connected && it.feedback.state == PluginOperationState.Unknown } == true
            }
            compose.runOnIdle {
                val feedback = checkNotNull(restored.session(candidate)).feedback
                assertEquals(PluginOperationState.Unknown, feedback.state)
                assertFalse(feedback.canStart)
                feedback.acknowledge()
            }
            compose.waitUntil(10_000) { restored.session(candidate)?.feedback?.canStart == true }
        } finally { compose.runOnIdle { pool.close() } }
    }
}
