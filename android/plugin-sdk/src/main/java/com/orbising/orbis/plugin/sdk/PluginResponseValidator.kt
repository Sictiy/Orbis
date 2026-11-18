package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Checks response syntax and bounds; the caller must authenticate and correlate it. */
object PluginResponseValidator {
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val capabilityName = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    private val statuses = setOf("ok", "accepted", "error")
    private val fields = setOf(
        "apiVersion", "payloadSchemaVersion", "requestId", "bindingId", "pluginId",
        "capability", "op", "hostId", "status", "errorCode", "payload",
    )

    fun validate(raw: String): ResponseValidation {
        fun invalid(reason: String) = ResponseValidation.Invalid(reason)
        val parsed = parseBoundedObject(raw)
        if (parsed is BoundedObject.Invalid) return invalid(parsed.reason)
        val root = (parsed as BoundedObject.Valid).value

        if (root.keys != fields) return invalid("invalid_fields")
        if (root.integer("apiVersion") != PluginProtocol.API_VERSION.toLong() ||
            root.integer("payloadSchemaVersion") != PluginProtocol.PAYLOAD_SCHEMA_VERSION.toLong()
        ) return invalid("unsupported_version")

        val requestId = root.string("requestId")?.takeIf(identifier::matches) ?: return invalid("invalid_request_id")
        val pluginId = root.string("pluginId")?.takeIf(identifier::matches) ?: return invalid("invalid_plugin_id")
        val capability = root.string("capability")?.takeIf(capabilityName::matches) ?: return invalid("invalid_capability")
        val op = root.string("op")?.takeIf { it in PluginProtocol.requestOperations } ?: return invalid("invalid_operation")
        val binding = root.nullableIdentifier("bindingId") ?: return invalid("invalid_binding_id")
        val host = root.nullableIdentifier("hostId") ?: return invalid("invalid_host_id")
        if (binding.value == null && op != "descriptor.read" && op != "binding.open") {
            return invalid("binding_required")
        }

        val status = root.string("status")?.takeIf { it in statuses }
            ?: return invalid("invalid_status")
        val errorCode = when (val value = root["errorCode"]) {
            is JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.content ?: return invalid("invalid_error_code")
            else -> return invalid("invalid_error_code")
        }
        if ((status == "error" && (errorCode == null || errorCode !in PluginProtocol.errorCodes)) ||
            (status != "error" && errorCode != null)
        ) return invalid("invalid_error_code")
        val payload = root["payload"] as? JsonObject ?: return invalid("invalid_payload")
        if (status == "error" && payload.isNotEmpty()) return invalid("invalid_error_payload")

        return ResponseValidation.Valid(
            PluginResponseEnvelope(requestId, binding.value, pluginId, capability, op, host.value, status, errorCode, payload),
        )
    }

    private data class NullableIdentifier(val value: String?)

    private fun JsonObject.nullableIdentifier(key: String): NullableIdentifier? {
        val value = this[key]
        if (value is JsonNull) return NullableIdentifier(null)
        return string(key)?.takeIf(identifier::matches)?.let(::NullableIdentifier)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.integer(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull }?.longOrNull
}
