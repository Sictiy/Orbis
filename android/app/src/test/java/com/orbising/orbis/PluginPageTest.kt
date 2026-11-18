package com.orbising.orbis

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PluginPageTest {
    private fun page(rows: JsonArray = JsonArray(emptyList())) = buildJsonObject {
        put("schemaVersion", 2); put("title", "测试插件"); put("message", "内容")
        put("rows", rows); put("settings", true)
    }

    @Test fun acceptsBoundedDeclarativePage() {
        val row = buildJsonObject { put("title", "电脑"); put("value", "在线") }
        val result = parsePluginPage(page(JsonArray(listOf(row))))!!
        assertEquals(listOf(PluginPageRow("电脑", "在线")), result.rows)
        assertTrue(result.settings)
    }

    @Test fun rejectsUntrustedPageFields() {
        val badRow = buildJsonObject { put("title", "电脑\n隐藏内容"); put("value", "在线") }
        assertNull(parsePluginPage(page(JsonArray(listOf(badRow)))))
        assertNull(parsePluginPage(page(JsonArray(List(65) { buildJsonObject { put("title", "a"); put("value", "b") } }))))
        assertNull(parsePluginPage(JsonObject(page() + ("settings" to JsonPrimitive("true")))))
        assertNull(parsePluginPage(JsonObject(page() + ("extra" to JsonPrimitive("x")))))
    }

    @Test fun acceptsLegacyPage() {
        val result = parsePluginPage(buildJsonObject { put("schemaVersion", 1); put("title", "旧插件"); put("message", "内容") })!!
        assertTrue(result.rows.isEmpty())
        assertFalse(result.settings)
    }
}
