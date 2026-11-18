package com.orbising.orbis

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.EmbeddedPageTheme
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PluginActivityForegroundInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-activity-foreground")
    @After fun cleanup() { app.disposePluginTestStorage() }

    @Test fun backgroundActivityRequestReportsFailureToTheStillOpenPage() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageName = "com.orbising.orbis.plugintest"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == packageName }
        val request = Bundle().apply {
            putString("className", "$packageName.Launcher")
            putBundle("extras", Bundle())
        }
        assertNotNull(pluginOwnedActivityIntent(context, packageName, request))

        val controller = PluginControl(app, "activity-foreground-fixture")
        val foreground = AtomicBoolean(true)
        val ready = AtomicBoolean()
        val failed = AtomicBoolean()
        val launches = AtomicInteger()
        var surface: SurfaceView? = null
        var page: PluginEmbeddedPage? = null
        val theme = EmbeddedPageTheme(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        try {
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.session(candidate)?.connected == true }
            val session = controller.session(candidate)!!
            compose.runOnIdle { session.embeddedEntryPoint = "fixture.activity_request" }
            compose.setContent {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { activityContext ->
                    SurfaceView(activityContext).apply {
                        surface = this
                        setZOrderOnTop(true)
                        val launchContext = object : ContextWrapper(activityContext) {
                            override fun getApplicationContext(): Context = app
                            override fun startActivity(intent: Intent) { launches.incrementAndGet() }
                        }
                        page = PluginEmbeddedPage(
                            launchContext, session, this, theme,
                            isForeground = foreground::get,
                            ready = { ready.set(true) },
                            unavailable = { ready.set(false) },
                            backStateChanged = { failed.set(it) },
                            sheetChanged = {},
                        ).also { it.start() }
                    }
                })
            }
            compose.waitUntil(15_000) { ready.get() }
            compose.runOnIdle { page!!.back() }
            compose.waitUntil(10_000) { launches.get() == 1 }

            compose.runOnIdle { foreground.set(false); page!!.back() }
            compose.waitUntil(10_000) { failed.get() }
            assertEquals("A background page must not launch an Activity", 1, launches.get())

            compose.runOnIdle { foreground.set(true); failed.set(false); surface!!.visibility = View.INVISIBLE; page!!.back() }
            compose.waitUntil(10_000) { failed.get() }
            assertEquals("An invisible page must not launch an Activity", 1, launches.get())

            compose.runOnIdle { surface!!.visibility = View.VISIBLE }
            compose.waitUntil(10_000) { surface?.holder?.surface?.isValid == true }
            compose.runOnIdle { page!!.back() }
            compose.waitUntil(10_000) { launches.get() == 2 }
        } finally {
            compose.runOnIdle { page?.close(); controller.disable(candidate); controller.close() }
        }
    }
}
