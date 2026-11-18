package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUiSnapshotsTest {
    private fun payload(host: String = "host-1", enabled: Boolean = true) = Json.parseToJsonElement("""
        {"schemaVersion":1,"contributions":[{
          "id":"one","slot":"paired_host.trailing_actions","hostId":"$host",
          "component":{"type":"icon_action","icon":"power","label":"Action","enabled":$enabled,"actionId":"do_it"}
        }]}
    """.trimIndent()).jsonObject

    private fun PluginUiSnapshots.publishHost(host: String = "host-1", enabled: Boolean = true): PluginUiSnapshots.Snapshot =
        (publish(PluginUiSlot.HOST_ACTION, host, payload(host, enabled)) as PluginUiSnapshots.PublishResult.Published).snapshot

    @Test fun unchangedRefreshPreservesHandlesButChangedOrMalformedRefreshRevokesThem() {
        val registry = PluginUiSnapshots("binding-1")
        val old = registry.publishHost().handle("one")!!
        assertEquals("do_it", registry.resolve(old)?.actionId)
        val current = registry.publishHost().handle("one")!!
        assertEquals("do_it", registry.resolve(old)?.actionId)
        assertNotNull(registry.resolve(current))
        registry.publishHost(enabled = false)
        assertNull(registry.resolve(old))
        val afterChange = registry.publishHost().handle("one")!!
        val malformed = payload("different-host")
        assertTrue(registry.publish(PluginUiSlot.HOST_ACTION, "host-1", malformed) is PluginUiSnapshots.PublishResult.Invalid)
        assertNull(registry.resolve(afterChange))
    }

    @Test fun disabledAndWrongBindingActionsCannotRun() {
        val first = PluginUiSnapshots("binding-1")
        val second = PluginUiSnapshots("binding-2")
        val handle = first.publishHost().handle("one")!!
        second.publishHost()
        assertNull(second.resolve(handle))
        assertNull(first.publishHost(enabled = false).handle("one"))
        assertNull(first.resolve(handle))
    }

    @Test fun removingOneHostDoesNotChangeAnotherAndCloseRevokesEverything() {
        val registry = PluginUiSnapshots("binding-1")
        val a = registry.publishHost("host-a").handle("one")!!
        val b = registry.publishHost("host-b").handle("one")!!
        registry.remove(PluginUiSlot.HOST_ACTION, "host-a")
        assertNull(registry.resolve(a))
        assertEquals("host-b", registry.resolve(b)?.hostId)
        registry.close()
        assertNull(registry.resolve(b))
        assertTrue(registry.publish(PluginUiSlot.HOST_ACTION, "host-b", payload("host-b")) is PluginUiSnapshots.PublishResult.Closed)
    }
}
