package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.JsonObject

/** Transport limits are shared by hosts and independently installed plugins. */
object PluginProtocol {
    const val SERVICE_ACTION = "com.orbising.orbis.plugin.BIND"
    const val EMBEDDED_PAGE_ACTION = "com.orbising.orbis.plugin.EMBEDDED_PAGE"
    const val EMBEDDED_PAGE_METADATA = "com.orbising.orbis.plugin.EMBEDDED_PAGE"
    const val ACTIVATE_ACTION = "com.orbising.orbis.plugin.ACTIVATE"
    const val SETUP_ACTION = "com.orbising.orbis.plugin.SETUP"
    const val HOST_PERMISSION_SUFFIX = ".permission.PLUGIN_ACCESS"
    const val API_VERSION_METADATA = "com.orbising.orbis.plugin.API_VERSION"
    const val API_VERSION = 1
    const val PAYLOAD_SCHEMA_VERSION = 1
    const val MAX_ENVELOPE_UTF8_BYTES = 16 * 1024
    const val MAX_PARCEL_BYTES = 64 * 1024
    const val MAX_STRING_UTF8_BYTES = 8 * 1024
    const val MAX_JSON_DEPTH = 16
    const val MAX_JSON_VALUES = 1024
    const val MAX_COLLECTION_SIZE = 128
    const val MAX_PREFLIGHT_DEADLINE_MS = 1_000L
    const val MAX_REQUEST_DEADLINE_MS = 3_000L
    const val MAX_IN_FLIGHT_PER_PLUGIN = 8
    const val MAX_OBSERVER_QUEUE = 32
    const val DEFAULT_CONTENT_CHUNK_BYTES = 4 * 1024
    const val MAX_CONTENT_CHUNK_BYTES = 8 * 1024

    val requestOperations: Set<String> = setOf(
        "descriptor.read", "binding.open", "binding.close", "ui.read", "page.read",
        "content.read", "event.observe", "action.perform", "action.preflight",
        "operation.read", "host.action.request", "plugin.event.publish",
    )

    val errorCodes: Set<String> = setOf(
        "PLUGIN_UNAVAILABLE", "PERMISSION_DENIED", "UNSUPPORTED_VERSION",
        "TIMEOUT", "INVALID_PAYLOAD", "CANCELLED", "CAPACITY_EXCEEDED",
        "INTERNAL_ERROR",
    )
}

enum class PluginRequestEndpoint {
    BINDING_OPEN,
    PLUGIN,
    HOST,
}

/** Validation of an envelope alone does not authenticate or authorize its sender. */
data class PluginRequestEnvelope(
    val requestId: String,
    val bindingId: String?,
    val pluginId: String,
    val capability: String,
    val operation: String,
    val hostId: String?,
    val deadlineElapsedRealtimeMs: Long,
    val payload: JsonObject,
)

sealed interface EnvelopeValidation {
    data class Valid(val envelope: PluginRequestEnvelope) : EnvelopeValidation
    data class Invalid(val reason: String) : EnvelopeValidation
}

/** The receiver must correlate every field with the authenticated pending request. */
data class PluginResponseEnvelope(
    val requestId: String,
    val bindingId: String?,
    val pluginId: String,
    val capability: String,
    val operation: String,
    val hostId: String?,
    val status: String,
    val errorCode: String?,
    val payload: JsonObject,
)

sealed interface ResponseValidation {
    data class Valid(val envelope: PluginResponseEnvelope) : ResponseValidation
    data class Invalid(val reason: String) : ResponseValidation
}
