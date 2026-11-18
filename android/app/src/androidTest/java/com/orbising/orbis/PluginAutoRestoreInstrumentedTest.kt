package com.orbising.orbis

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PluginAutoRestoreInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-auto-restore")
    private val stoppedFixture = StoppedPluginFixture(app)
    @After fun cleanup() { try { stoppedFixture.restore() } finally { app.disposePluginTestStorage() } }

    @Test fun stoppedApprovedPluginIsActivatedWhenHostResumes() {
        val context = app
        stoppedFixture.requireSupported()
        val hostId = "stopped-auto-restore-fixture"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == SYNTHETIC_PLUGIN_PACKAGE }
        val approval = ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName)
        PluginApprovalStore(context).save(hostId, approval)
        stoppedFixture.stop()
        val controller = PluginControl(context, hostId)
        var allowActivation by mutableStateOf(false)
        try {
            compose.setContent { if (allowActivation) PluginActivationHost(controller) }
            compose.waitUntil(15_000) { controller.nextRestorationActivation != null }
            assertFalse(controller.session(candidate)?.connected == true)
            compose.runOnIdle { allowActivation = true }
            compose.waitUntil(20_000) { controller.session(candidate)?.connected == true }
            compose.runOnIdle { assertTrue(controller.isApproved(candidate)) }
        } finally { compose.runOnIdle { controller.disable(candidate); controller.close() } }
    }

    @Test fun removalCancelsPendingAutomaticActivation() {
        val context = app
        stoppedFixture.requireSupported()
        val hostId = "stopped-removed-restore-fixture"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == SYNTHETIC_PLUGIN_PACKAGE }
        PluginApprovalStore(context).save(hostId, ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName))
        stoppedFixture.stop()
        val controller = PluginControl(context, hostId)
        var allowActivation by mutableStateOf(false)
        try {
            compose.setContent { if (allowActivation) PluginActivationHost(controller) }
            compose.waitUntil(15_000) { controller.nextRestorationActivation != null }
            compose.runOnIdle {
                controller.disable(candidate)
                allowActivation = true
                assertFalse(controller.isApproved(candidate))
                assertTrue(controller.nextRestorationActivation == null)
            }
            compose.waitForIdle()
            assertFalse(controller.session(candidate)?.connected == true)
        } finally { compose.runOnIdle { controller.close() } }
    }

    @Test fun failedStoppedActivationIsQueuedAgain() {
        val context = app
        stoppedFixture.requireSupported()
        val hostId = "stopped-failed-activation-fixture"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == SYNTHETIC_PLUGIN_PACKAGE }
        PluginApprovalStore(context).save(hostId, ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName))
        stoppedFixture.stop()
        val controller = PluginControl(context, hostId)
        try {
            compose.setContent { }
            compose.waitUntil(15_000) { controller.nextRestorationActivation != null }
            compose.runOnIdle {
                val first = controller.nextRestorationActivation!!
                assertTrue(controller.beginRestorationActivation(first))
                controller.finishRestorationActivation(first, false)
            }
            compose.waitUntil(15_000) { controller.nextRestorationActivation != null }
            compose.runOnIdle { assertTrue(controller.nextRestorationActivation!!.candidate == candidate) }
        } finally { compose.runOnIdle { controller.disable(candidate); controller.close() } }
    }

    @Test fun bindingWithNoCallbackTimesOutAndRetries() {
        val base = app
        val hostId = "stalled-bind-restore-fixture"
        val candidate = PluginServiceDiscovery.find(base.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val attempts = AtomicInteger()
        var stalled: ServiceConnection? = null
        val context = object : ContextWrapper(base) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                if (intent.component == candidate.componentName() && attempts.incrementAndGet() == 1) {
                    stalled = connection
                    return true // Simulate a system that accepts a bind but never calls back.
                }
                return super.bindService(intent, connection, flags)
            }
            override fun unbindService(connection: ServiceConnection) {
                if (connection === stalled) stalled = null else super.unbindService(connection)
            }
        }
        PluginApprovalStore(context).save(hostId, ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName))
        val controller = PluginControl(context, hostId)
        try {
            compose.setContent { }
            compose.waitUntil(20_000) { controller.session(candidate)?.connected == true }
            assertTrue("A stalled bind must be retried", attempts.get() >= 2)
        } finally { compose.runOnIdle { controller.disable(candidate); controller.close() } }
    }

    @Test fun discoveryFinishingAfterSuspendAndResumeStillRestores() {
        val context = app
        val hostId = "suspended-discovery-restore-fixture"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        PluginApprovalStore(context).save(hostId, ApprovedPluginService(candidate.peer.packageName, candidate.peer.signerSha256, candidate.serviceClassName))
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val controller = PluginControl(context, hostId, discover = {
            started.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            listOf(candidate)
        })
        try {
            assertTrue("Discovery must be in flight", started.await(10, TimeUnit.SECONDS))
            compose.runOnIdle {
                controller.suspendConnections()
                controller.resumeConnections()
            }
            proceed.countDown()
            compose.waitUntil(20_000) { controller.session(candidate)?.connected == true }
        } finally {
            proceed.countDown()
            compose.runOnIdle { controller.disable(candidate); controller.close() }
        }
    }
}
