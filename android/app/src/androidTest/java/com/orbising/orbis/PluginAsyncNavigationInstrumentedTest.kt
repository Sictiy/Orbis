package com.orbising.orbis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises a real binding with a null-Host asynchronous navigation action. */
class PluginAsyncNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture(context: Context) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val remote = AtomicReference<IBinder>()
        val candidate = PluginServiceDiscovery.find(context.packageManager).single {
            it.peer.packageName == "com.orbising.orbis.plugintest"
        }.copy(serviceClassName = "com.orbising.orbis.plugintest.AsyncNavigationFixtureService")
        private val app = context
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { remote.set(service); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { remote.set(null) }
        }
        private val bound = context.bindService(Intent("com.orbising.orbis.plugintest.ASYNC_NAVIGATION_CONTROL")
            .setClassName(candidate.peer.packageName, candidate.serviceClassName), connection, Context.BIND_AUTO_CREATE)

        init { assertTrue(bound); assertTrue(connected.await(10, TimeUnit.SECONDS)); assertEquals(1, command(6)) }

        fun command(code: Int): Int {
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                assertTrue(remote.get()!!.transact(code, request, response, 0))
                response.readException()
                return response.readInt()
            } finally { request.recycle(); response.recycle() }
        }

        override fun close() { if (bound) app.unbindService(connection) }
    }

    @Test fun nullHostNavigationSurvivesHostSwitchButDoesNotOpenAnObsoletePage() {
        val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "async-nav-switch")
        val fixture = Fixture(app)
        val pool = PluginControlPool(app)
        lateinit var first: PluginControl
        lateinit var second: PluginControl
        val current = AtomicBoolean(true)
        val opened = AtomicInteger()
        try {
            compose.runOnIdle { first = pool.forHost("async-nav-first"); first.connect(fixture.candidate) }
            compose.waitUntil(20_000) { first.session(fixture.candidate)?.let { it.navigationSnapshot != null && it.navigationFeedback.canStart } == true }
            val source = first.session(fixture.candidate)!!
            compose.runOnIdle {
                source.navigate(source.navigationSnapshot!!.handle("fixture.nav")!!, current::get) { opened.incrementAndGet() }
            }
            compose.waitUntil(10_000) { fixture.command(1) == 1 && source.navigationFeedback.state == PluginOperationState.Waiting }
            compose.runOnIdle {
                current.set(false)
                second = pool.forHost("async-nav-second")
                second.connect(fixture.candidate)
            }
            compose.waitUntil(20_000) { second.session(fixture.candidate)?.navigationSnapshot != null }
            val target = second.session(fixture.candidate)!!
            assertSame(source.navigationFeedback, target.navigationFeedback)
            assertFalse(target.navigationFeedback.canStart)
            assertEquals(1, fixture.command(3))
            assertEquals(1, fixture.command(2))
            compose.waitUntil(10_000) { target.navigationFeedback.canStart }
            assertEquals(0, opened.get())

            compose.runOnIdle {
                target.navigate(target.navigationSnapshot!!.handle("fixture.nav")!!) { opened.incrementAndGet() }
            }
            compose.waitUntil(10_000) { fixture.command(1) == 1 }
            assertEquals(1, fixture.command(2))
            compose.waitUntil(10_000) { opened.get() == 1 && target.page != null }
            assertEquals(2, fixture.command(3))
        } finally {
            compose.runOnIdle { pool.close() }
            fixture.close()
            app.disposePluginTestStorage()
        }
    }

    @Test fun expiredReadKeepsTheGateAndIgnoresALateSuccess() {
        val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "async-nav-timeout")
        val fixture = Fixture(app)
        val controller = PluginControl(app, "async-nav-timeout", operationWaitLimitMs = 1_500,
            discover = { listOf(fixture.candidate) })
        val opened = AtomicInteger()
        val selected = mutableStateOf(HomeTab.Chat)
        try {
            compose.setContent {
                OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                    HomeTabBar(selected = selected.value, onSelect = { selected.value = it })
                } } }
            }
            compose.runOnIdle { controller.connect(fixture.candidate) }
            compose.waitUntil(20_000) { controller.session(fixture.candidate)?.let { it.navigationSnapshot != null && it.navigationFeedback.canStart } == true }
            val session = controller.session(fixture.candidate)!!
            compose.runOnIdle { session.navigate(session.navigationSnapshot!!.handle("fixture.nav")!!) { opened.incrementAndGet() } }
            compose.waitUntil(10_000) { fixture.command(1) == 1 }
            compose.waitUntil(10_000) { session.navigationFeedback.state == PluginOperationState.Unknown }
            assertFalse(session.navigationFeedback.canStart)
            compose.onNodeWithTag("plugin-navigation-feedback").assertIsDisplayed()
            compose.onNodeWithTag("plugin-navigation-feedback-status").assertTextEquals("结果未知，请先检查操作状态")
            compose.onNodeWithTag("plugin-navigation").assertIsNotEnabled()
            compose.onNodeWithTag("home-tab-settings").performClick().assertIsSelected()
            compose.onNodeWithTag("plugin-navigation-feedback").assertIsDisplayed()
            assertFalse(session.navigationFeedback.canStart)
            val approval = ApprovedPluginService(fixture.candidate.peer.packageName, fixture.candidate.peer.signerSha256, fixture.candidate.serviceClassName)
            assertTrue(PluginPendingOperationStore(app, null, approval, "orbis_plugin_navigation_pending_v1").exists())
            assertEquals(1, fixture.command(2))
            compose.waitForIdle()
            assertEquals(0, opened.get())
            assertEquals(PluginOperationState.Unknown, session.navigationFeedback.state)
            assertEquals(1, fixture.command(3))
            compose.onNodeWithTag("plugin-navigation-feedback-acknowledge").performClick()
            compose.waitUntil(10_000) { session.navigationFeedback.canStart }
            compose.onNodeWithTag("plugin-navigation-feedback").assertDoesNotExist()
            assertFalse(PluginPendingOperationStore(app, null, approval, "orbis_plugin_navigation_pending_v1").exists())
        } finally {
            compose.runOnIdle { controller.close() }
            fixture.close()
            app.disposePluginTestStorage()
        }
    }

    @Test fun pendingNavigationIsRestoredForAnUnrelatedSelectedHost() {
        val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "async-nav-restore")
        val approval = ApprovedPluginService("test.plugin", "a".repeat(64), "test.plugin.Service")
        val pending = PluginPendingOperationStore(app, null, approval, "orbis_plugin_navigation_pending_v1")
        assertTrue(pending.set(true))
        PluginApprovalStore(app).save("previous-host", approval)
        val pool = PluginControlPool(app)
        try {
            lateinit var controller: PluginControl
            compose.runOnIdle { controller = pool.forHost("different-host") }
            compose.waitUntil(10_000) { controller.navigationFeedbacks.singleOrNull()?.feedback?.state == PluginOperationState.Unknown }
            val feedback = controller.navigationFeedbacks.single().feedback
            assertEquals(approval, controller.navigationFeedbacks.single().approval)
            assertFalse(feedback.canStart)
            PluginApprovalStore(app).removeService(approval.packageName, approval.className)
            assertTrue("Removing approval must retain the unresolved operation", pending.exists())
            compose.runOnIdle { feedback.acknowledge() }
            compose.waitUntil(10_000) { feedback.canStart }
        } finally {
            compose.runOnIdle { pool.close() }
            app.disposePluginTestStorage()
        }
    }

    @Test fun oldPluginFallsBackToTheLegacyReadWithoutResubmitting() {
        val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "async-nav-legacy")
        val fixture = Fixture(app)
        val controller = PluginControl(app, "async-nav-legacy", discover = { listOf(fixture.candidate) })
        val opened = AtomicInteger()
        try {
            assertEquals(1, fixture.command(4))
            compose.runOnIdle { controller.connect(fixture.candidate) }
            compose.waitUntil(20_000) { controller.session(fixture.candidate)?.let { it.navigationSnapshot != null && it.navigationFeedback.canStart } == true }
            val session = controller.session(fixture.candidate)!!
            compose.runOnIdle { session.navigate(session.navigationSnapshot!!.handle("fixture.nav")!!) { opened.incrementAndGet() } }
            compose.waitUntil(10_000) { fixture.command(5) == 1 }
            assertEquals(1, fixture.command(3))
            assertEquals(1, fixture.command(2))
            compose.waitUntil(10_000) { opened.get() == 1 }
            assertEquals(1, fixture.command(3))
        } finally {
            compose.runOnIdle { controller.close() }
            fixture.close()
            app.disposePluginTestStorage()
        }
    }
}
