package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class PluginCapability(val wireName: String) {
    UI("ui"), HOST_STATE_OBSERVE("host_state_observe"), PREFLIGHT("preflight"), HOST_ACTION("host_action"),
}

/** Immutable authorization scope for one binding, constructed from explicit grants. */
class PluginBindingScope internal constructor(
    private val grants: Set<PluginCapability>,
    private val hostIds: Set<String>,
) {
    /** A null host is unscoped, not permission to access all Hosts. */
    fun allows(capability: PluginCapability, hostId: String?): Boolean =
        capability in grants && (hostId == null || hostId in hostIds)
}

sealed interface PluginBindingScopeValidation {
    data class Valid(val scope: PluginBindingScope) : PluginBindingScopeValidation
    data class Invalid(val reason: String) : PluginBindingScopeValidation
}

/** Payload parser only; the host must derive these grants from the user's approvals. */
object PluginBindingScopeValidator {
    const val MAX_HOSTS = 16
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    fun validate(payload: JsonObject): PluginBindingScopeValidation {
        fun invalid(reason: String) = PluginBindingScopeValidation.Invalid(reason)
        if (payload.keys != setOf("grants", "hostIds")) return invalid("invalid_fields")
        val grantItems = payload["grants"] as? JsonArray ?: return invalid("invalid_grants")
        val hostItems = payload["hostIds"] as? JsonArray ?: return invalid("invalid_hosts")
        if (grantItems.size > PluginCapability.entries.size || hostItems.size > MAX_HOSTS) return invalid("scope_too_large")
        val grants = mutableSetOf<PluginCapability>()
        for (item in grantItems) {
            val name = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return invalid("invalid_grants")
            val grant = PluginCapability.entries.firstOrNull { it.wireName == name } ?: return invalid("invalid_grants")
            if (!grants.add(grant)) return invalid("invalid_grants")
        }
        val hosts = mutableSetOf<String>()
        for (item in hostItems) {
            val id = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return invalid("invalid_hosts")
            if (!identifier.matches(id) || !hosts.add(id)) return invalid("invalid_hosts")
        }
        return PluginBindingScopeValidation.Valid(PluginBindingScope(grants.toSet(), hosts.toSet()))
    }
}
