package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUiValidatorTest {
    private fun validate(
        contributions: String,
        slot: PluginUiSlot = PluginUiSlot.HOST_ACTION,
        hostId: String? = "host-1",
    ): PluginUiValidation = PluginUiValidator.validate(
        Json.parseToJsonElement("""{"schemaVersion":1,"contributions":$contributions}""").jsonObject, slot, hostId,
    )

    private fun item(component: String, slot: String = "paired_host.trailing_actions", host: String = "\"host-1\"", id: String = "test-action"): String =
        """{"id":"$id","slot":"$slot","hostId":$host,"component":$component}"""

    private val action = """{"type":"icon_action","icon":"power","label":"Run","enabled":true,"actionId":"run"}"""

    @Test fun acceptsHostActionForTheOriginalHost() {
        val result = validate("[${item(action)}]") as PluginUiValidation.Valid
        assertEquals("host-1", result.contributions.single().hostId)
        assertEquals("run", (result.contributions.single().component as PluginUiComponent.IconAction).actionId)
    }

    @Test fun rejectsCrossHostAndWrongSlot() {
        assertEquals("invalid_host", (validate("[${item(action, host = "\"host-2\"")}]") as PluginUiValidation.Invalid).reason)
        assertEquals("invalid_slot", (validate("[${item(action, slot = "settings.section")}]") as PluginUiValidation.Invalid).reason)
        assertEquals("invalid_context", (validate("[]", PluginUiSlot.HOST_ACTION, null) as PluginUiValidation.Invalid).reason)
    }

    @Test fun rejectsUnknownTypesFieldsAndUntrustedResourceReferences() {
        val unknownType = action.replace("icon_action", "webview")
        val externalUrl = action.dropLast(1) + ",\"url\":\"https://example.com\"}"
        assertEquals("invalid_component", (validate("[${item(unknownType)}]") as PluginUiValidation.Invalid).reason)
        assertEquals("invalid_component", (validate("[${item(externalUrl)}]") as PluginUiValidation.Invalid).reason)
        assertEquals("invalid_component", (validate("[${item(action.replace("power", "external-icon"))}]") as PluginUiValidation.Invalid).reason)
    }

    @Test fun enforcesCapacityAndDuplicateIdentity() {
        assertEquals("too_many_contributions", (validate("[${item(action)},${item(action, id = "two")}]") as PluginUiValidation.Invalid).reason)
        val row = """{"type":"text_row","title":"Title","description":"Description"}"""
        val duplicate = item(row, "settings.section", "null")
        assertEquals("duplicate_contribution_id", (validate("[$duplicate,$duplicate]", PluginUiSlot.SETTINGS, null) as PluginUiValidation.Invalid).reason)
    }

    @Test fun acceptsOnlyTheComponentTypesAllowedInEachSlot() {
        val nav = """{"type":"navigation_item","icon":"device","label":"Control","actionId":"open"}"""
        assertTrue(validate("[${item(nav, "navigation.bottom_tab", "null")} ]", PluginUiSlot.NAVIGATION, null) is PluginUiValidation.Valid)
        assertEquals("invalid_component", (validate("[${item(action, "navigation.bottom_tab", "null")} ]", PluginUiSlot.NAVIGATION, null) as PluginUiValidation.Invalid).reason)
    }

    @Test fun validatesOptionalCardActionAndTextBounds() {
        val card = """{"type":"status_card","title":"Sample","description":"Unavailable","tone":"info","action":{"label":"Run","enabled":true,"actionId":"run"}}"""
        val row = item(card, "chat.host.sidebar.section")
        assertTrue(validate("[$row]", PluginUiSlot.HOST_PANEL) is PluginUiValidation.Valid)
        assertEquals("invalid_component", (validate("[${item(card.replace("\"info\"", "\"custom\""), "chat.host.sidebar.section")} ]", PluginUiSlot.HOST_PANEL) as PluginUiValidation.Invalid).reason)
        assertEquals("invalid_component", (validate("[${item(action.replace("Run", "x".repeat(121)))}]") as PluginUiValidation.Invalid).reason)
    }

    @Test fun rejectsStringBooleansAndMissingOrExtraFields() {
        assertEquals("invalid_component", (validate("[${item(action.replace("true", "\"true\""))}]") as PluginUiValidation.Invalid).reason)
        assertEquals("invalid_contribution", (validate("[${item(action).replace(",\"hostId\":\"host-1\"", "")}]") as PluginUiValidation.Invalid).reason)
        val payload = Json.parseToJsonElement("""{"schemaVersion":"1","contributions":[]}""").jsonObject
        assertEquals("invalid_payload", (PluginUiValidator.validate(payload, PluginUiSlot.SETTINGS, null) as PluginUiValidation.Invalid).reason)
    }
}
