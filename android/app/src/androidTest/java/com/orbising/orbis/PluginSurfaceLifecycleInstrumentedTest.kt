package com.orbising.orbis

import android.content.Context
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.orbising.orbis.plugin.sdk.EmbeddedPageTheme
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PluginSurfaceLifecycleInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedPageOpenSurvivesSurfaceDestructionAndRestoresItsContent() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = IsolatedTestApplication(context, prefix = "plugin-surface-lifecycle")
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val controller = PluginControl(app, "surface-lifecycle-fixture")
        val opening = AtomicBoolean()
        val destroyed = AtomicBoolean()
        val ready = AtomicBoolean()
        val unavailable = AtomicBoolean()
        val theme = EmbeddedPageTheme(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        var view: SurfaceView? = null
        var page: PluginEmbeddedPage? = null
        val observer = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { destroyed.set(true) }
        }
        fun containsText(node: AccessibilityNodeInfo?, text: String): Boolean {
            if (node == null) return false
            if (node.text?.toString() == text) return true
            return (0 until node.childCount).any { containsText(node.getChild(it), text) }
        }
        try {
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.session(candidate)?.connected == true }
            val session = controller.session(candidate)!!
            compose.runOnIdle { session.embeddedEntryPoint = "fixture.delayed_open" }
            compose.setContent {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { activityContext ->
                    SurfaceView(activityContext).apply {
                        view = this
                        setZOrderOnTop(true)
                        holder.addCallback(observer)
                        page = PluginEmbeddedPage(
                            app, session, this, theme,
                            isForeground = { true },
                            ready = { ready.set(true) },
                            unavailable = { unavailable.set(true) },
                            backStateChanged = { opening.set(true) },
                            sheetChanged = {},
                        ).also { it.start() }
                    }
                })
            }
            compose.waitUntil(10_000) { opening.get() }
            compose.runOnIdle { view!!.visibility = View.INVISIBLE }
            compose.waitUntil(5_000) { destroyed.get() }
            compose.waitUntil(10_000) { ready.get() || unavailable.get() }
            assertTrue("The late page should remain open while its Surface is absent", ready.get())
            assertFalse(unavailable.get())

            compose.runOnIdle { view!!.visibility = View.VISIBLE }
            compose.waitUntil(10_000) { view?.holder?.surface?.isValid == true }
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            compose.waitUntil(10_000) { containsText(ui.rootInActiveWindow, "插件嵌入页面") }
        } finally {
            compose.runOnIdle { view?.holder?.removeCallback(observer); page?.close(); controller.disable(candidate); controller.close() }
            app.disposePluginTestStorage()
        }
    }
}
