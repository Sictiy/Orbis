package com.orbising.orbis

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Rule
import org.junit.Test

class PluginInvalidHostInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-invalid-host")
    @After fun cleanup() { app.disposePluginTestStorage() }

    @Test fun invalidHostReleasesTheAndroidServiceConnectionBeforeOpeningABinding() {
        val base = app
        val candidate = PluginServiceDiscovery.find(base.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val bound = AtomicInteger()
        val unbound = AtomicInteger()
        val context = object : ContextWrapper(base) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                val accepted = super.bindService(intent, connection, flags)
                if (accepted && intent.component == candidate.componentName()) bound.incrementAndGet()
                return accepted
            }

            override fun unbindService(connection: ServiceConnection) {
                super.unbindService(connection)
                unbound.incrementAndGet()
            }
        }
        val controller = PluginControl(context, "invalid/host")
        try {
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(15_000) { controller.session(candidate)?.message == PluginNotice.InvalidHost }
            compose.runOnIdle {
                assertEquals(1, bound.get())
                assertEquals("A rejected scope must release its Android binding", 1, unbound.get())
                assertFalse(controller.session(candidate)!!.connected)
            }
        } finally { compose.runOnIdle { controller.close() } }
    }
}
