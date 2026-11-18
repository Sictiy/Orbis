package com.orbising.orbis

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class PluginOwnedActivityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plugin = "com.orbising.orbis.plugintest"

    private fun request(className: String, extras: Bundle = Bundle()) = Bundle().apply {
        putString("className", className)
        putBundle("extras", extras)
    }

    @Test fun acceptsOnlyAnExplicitActivityInTheApprovedPackage() {
        val extras = Bundle().apply { putString("scanId", "fixture-token") }
        val intent = pluginOwnedActivityIntent(context, plugin, request("$plugin.Launcher", extras))
        assertEquals(plugin, intent?.component?.packageName)
        assertEquals("$plugin.Launcher", intent?.component?.className)
        assertEquals("fixture-token", intent?.getStringExtra("scanId"))
        assertNull(pluginOwnedActivityIntent(context, plugin, request("com.orbising.orbis.MainActivity")))
        assertNull(pluginOwnedActivityIntent(context, plugin, request("$plugin.DoesNotExist")))
    }

    @Test fun rejectsUnexpectedFieldsAndUnboundedOrNonStringExtras() {
        val target = "$plugin.Launcher"
        assertNull(pluginOwnedActivityIntent(context, plugin, request(target).apply { putString("unexpected", "x") }))
        assertNull(pluginOwnedActivityIntent(context, plugin, request(target, Bundle().apply { putInt("id", 1) })))
        assertNull(pluginOwnedActivityIntent(context, plugin, request(target, Bundle().apply { putString("bad/key", "x") })))
        assertNull(pluginOwnedActivityIntent(context, plugin, request(target, Bundle().apply { putString("id", "x".repeat(513)) })))
        assertNull(pluginOwnedActivityIntent(context, plugin, request(target, Bundle().apply { putString("id", "\n") })))
        assertNull(pluginOwnedActivityIntent(context, plugin, request(target, Bundle().apply { repeat(17) { putString("id$it", "x") } })))
    }
}
