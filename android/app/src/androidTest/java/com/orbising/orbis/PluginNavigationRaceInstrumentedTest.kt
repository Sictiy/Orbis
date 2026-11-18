package com.orbising.orbis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.IBinder
import android.os.Parcel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import com.orbising.orbis.plugin.sdk.PluginUiComponent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PluginNavigationRaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun laterDownloadsNavigationWinsOverDelayedPluginReply() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = PluginServiceDiscovery.find(context.packageManager).single {
            it.peer.packageName == "com.orbising.orbis.plugintest"
        }
        val candidate = base.copy(serviceClassName = "com.orbising.orbis.plugintest.DelayedPageFixtureService")
        val app = IsolatedTestApplication(context, "plugin-navigation-race")
        val hostId = "plugin-navigation-race-fixture"
        val controller = PluginControl(app, hostId, discover = { listOf(candidate) })
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put(hostId, model)
        val state = RemoteState(hostId = hostId, deviceId = "fixture-device", e2eReady = true).withReadyHomeFixture()
        val pairing = HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "fixture-device", credential = "test-only")
        val connected = CountDownLatch(1)
        val remote = AtomicReference<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { remote.set(service); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { remote.set(null) }
        }
        val controlIntent = Intent("com.orbising.orbis.plugintest.NAVIGATION_CONTROL")
            .setClassName(candidate.peer.packageName, candidate.serviceClassName)
        val bound = context.bindService(controlIntent, connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val binder = remote.get()!!
            fun command(code: Int): Boolean {
                val request = Parcel.obtain()
                val response = Parcel.obtain()
                try {
                    assertTrue(binder.transact(code, request, response, 0))
                    response.readException()
                    return response.readInt() == 1
                } finally { request.recycle(); response.recycle() }
            }
            compose.setContent {
                val compact = Configuration(LocalConfiguration.current).apply { screenWidthDp = 400 }
                CompositionLocalProvider(LocalConfiguration provides compact) {
                    OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                        RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
                    } } }
                }
            }
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.session(candidate)?.let { it.navigationSnapshot != null && it.snapshot != null } == true }

            compose.onNodeWithTag("plugin-navigation").performClick()
            compose.waitUntil(2_000) { command(1) }
            compose.onNodeWithTag("home-downloads").performClick()
            assertTrue("The fixture must release a pending plugin response", command(2))

            // The fixture acknowledges delivery synchronously, then reports a marker in a
            // later UI read. The host callback executor and main queue process those in order.
            compose.waitUntil(10_000) {
                var received = false
                compose.runOnIdle {
                    val session = controller.session(candidate)!!
                    received = (session.snapshot?.contributions?.firstOrNull()?.component as? PluginUiComponent.StatusBadge)
                        ?.label == "延迟回复已发送"
                    if (!received) session.refreshHostContributions()
                }
                received
            }
            compose.onNodeWithText("暂无下载").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("home-tab-chat").assertIsSelected()
            compose.onNodeWithTag("plugin-navigation").assertIsNotSelected()

            // Prove that the same delayed fixture can open the plugin when its request
            // remains current, rather than passing because replies were never delivered.
            compose.onNodeWithTag("plugin-navigation").performClick()
            compose.waitUntil(2_000) { command(1) }
            assertTrue(command(2))
            compose.waitUntil(5_000) { controller.session(candidate)?.page != null }
            compose.onNodeWithTag("plugin-navigation").assertIsSelected()
        } finally {
            if (bound) context.unbindService(connection)
            compose.runOnIdle { controller.disable(candidate); controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }
}
