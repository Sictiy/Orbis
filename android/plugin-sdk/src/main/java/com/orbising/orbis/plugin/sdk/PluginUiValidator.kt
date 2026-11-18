package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A host-owned location. Plugins never supply executable UI or an Intent. */
enum class PluginUiSlot(val wireName: String, val requiresHost: Boolean, val limit: Int) {
    SETTINGS("settings.section", false, 4),
    NAVIGATION("navigation.bottom_tab", false, 1),
    HOST_PANEL("chat.host.sidebar.section", true, 4),
    HOST_ACTION("paired_host.trailing_actions", true, 1),
}

data class PluginUiContribution(
    val id: String,
    val slot: PluginUiSlot,
    val hostId: String?,
    val component: PluginUiComponent,
)

/** Strings are plain text; icons and tones are interpreted only by the host. */
sealed interface PluginUiComponent {
    data class IconAction(val icon: String, val label: String, val enabled: Boolean, val actionId: String) : PluginUiComponent
    data class TextRow(val title: String, val description: String) : PluginUiComponent
    data class StatusBadge(val label: String, val tone: String) : PluginUiComponent
    data class StatusCard(val title: String, val description: String, val tone: String, val action: Action?) : PluginUiComponent
    data class NavigationItem(val icon: String, val label: String, val actionId: String) : PluginUiComponent
    data class SectionAction(val label: String, val description: String, val enabled: Boolean, val actionId: String) : PluginUiComponent

    data class Action(val label: String, val enabled: Boolean, val actionId: String)
}

sealed interface PluginUiValidation {
    data class Valid(val contributions: List<PluginUiContribution>) : PluginUiValidation
    data class Invalid(val reason: String) : PluginUiValidation
}

/**
 * Validates a ui.read response payload after envelope, peer, binding and grant checks.
 * The expected slot and host come from the original local request, never from the reply.
 */
object PluginUiValidator {
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val icons = setOf("info", "settings", "power", "refresh", "link", "more", "play", "check", "warning", "device")
    private val tones = setOf("neutral", "info", "success", "warning", "error")

    fun validate(payload: JsonObject, expectedSlot: PluginUiSlot, expectedHostId: String?): PluginUiValidation {
        fun invalid(reason: String) = PluginUiValidation.Invalid(reason)
        if (expectedSlot.requiresHost != (expectedHostId != null) ||
            (expectedHostId != null && !identifier.matches(expectedHostId))
        ) return invalid("invalid_context")
        if (payload.keys != setOf("schemaVersion", "contributions") || payload.number("schemaVersion") != "1") {
            return invalid("invalid_payload")
        }
        val contributions = payload["contributions"] as? JsonArray ?: return invalid("invalid_contributions")
        if (contributions.size > expectedSlot.limit) return invalid("too_many_contributions")
        val ids = HashSet<String>()
        val result = ArrayList<PluginUiContribution>(contributions.size)
        for (item in contributions) {
            val obj = item as? JsonObject ?: return invalid("invalid_contribution")
            if (obj.keys != setOf("id", "slot", "hostId", "component")) return invalid("invalid_contribution")
            val id = obj.id("id") ?: return invalid("invalid_contribution_id")
            if (!ids.add(id)) return invalid("duplicate_contribution_id")
            if (obj.string("slot") != expectedSlot.wireName) return invalid("invalid_slot")
            val host = obj["hostId"]
            if (if (expectedHostId == null) host !is JsonNull else obj.string("hostId") != expectedHostId) {
                return invalid("invalid_host")
            }
            val component = (obj["component"] as? JsonObject)?.let { component(it, expectedSlot) }
                ?: return invalid("invalid_component")
            result.add(PluginUiContribution(id, expectedSlot, expectedHostId, component))
        }
        return PluginUiValidation.Valid(result)
    }

    private fun component(obj: JsonObject, slot: PluginUiSlot): PluginUiComponent? {
        return when (obj.string("type")) {
            "icon_action" -> {
                if (slot != PluginUiSlot.HOST_ACTION || !obj.has("type", "icon", "label", "enabled", "actionId")) return null
                PluginUiComponent.IconAction(obj.icon() ?: return null, obj.label("label") ?: return null,
                    obj.bool("enabled") ?: return null, obj.id("actionId") ?: return null)
            }
            "text_row" -> {
                if (slot !in contentSlots || !obj.has("type", "title", "description")) return null
                PluginUiComponent.TextRow(obj.label("title") ?: return null, obj.description() ?: return null)
            }
            "status_badge" -> {
                if (slot !in contentSlots || !obj.has("type", "label", "tone")) return null
                PluginUiComponent.StatusBadge(obj.label("label") ?: return null, obj.tone() ?: return null)
            }
            "status_card" -> {
                if (slot !in contentSlots || !obj.has("type", "title", "description", "tone", "action")) return null
                val action = when (val raw = obj["action"]) {
                    is JsonNull -> null
                    is JsonObject -> {
                        if (!raw.has("label", "enabled", "actionId")) return null
                        PluginUiComponent.Action(raw.label("label") ?: return null, raw.bool("enabled") ?: return null,
                            raw.id("actionId") ?: return null)
                    }
                    else -> return null
                }
                PluginUiComponent.StatusCard(obj.label("title") ?: return null, obj.description() ?: return null,
                    obj.tone() ?: return null, action)
            }
            "navigation_item" -> {
                if (slot != PluginUiSlot.NAVIGATION || !obj.has("type", "icon", "label", "actionId")) return null
                PluginUiComponent.NavigationItem(obj.icon() ?: return null, obj.label("label") ?: return null,
                    obj.id("actionId") ?: return null)
            }
            "section_action" -> {
                if (slot !in contentSlots || !obj.has("type", "label", "description", "enabled", "actionId")) return null
                PluginUiComponent.SectionAction(obj.label("label") ?: return null, obj.description() ?: return null,
                    obj.bool("enabled") ?: return null, obj.id("actionId") ?: return null)
            }
            else -> null
        }
    }

    private val contentSlots = setOf(PluginUiSlot.SETTINGS, PluginUiSlot.HOST_PANEL)
    private fun JsonObject.has(vararg keys: String) = this.keys == keys.toSet()
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content
    private fun JsonObject.id(key: String): String? = string(key)?.takeIf(identifier::matches)
    private fun JsonObject.label(key: String): String? = text(key, 120, false)
    private fun JsonObject.description(): String? = text("description", 512, true)
    private fun JsonObject.text(key: String, max: Int, allowEmpty: Boolean): String? = string(key)?.takeIf {
        it.length <= max && (allowEmpty || it.isNotBlank()) && it.none { char -> Character.isISOControl(char) }
    }
    private fun JsonObject.icon(): String? = string("icon")?.takeIf { it in icons }
    private fun JsonObject.tone(): String? = string("tone")?.takeIf { it in tones }
    private fun JsonObject.bool(key: String): Boolean? = when (number(key)) { "true" -> true; "false" -> false; else -> null }
}
