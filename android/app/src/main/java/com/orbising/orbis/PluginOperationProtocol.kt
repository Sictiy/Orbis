package com.orbising.orbis

import com.orbising.orbis.plugin.sdk.PluginResponseEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Interprets the public operation contract without exposing plugin implementation states. */
internal object PluginOperationProtocol {
    const val POLL_INTERVAL_MS = 1_500L
    const val WAIT_LIMIT_MS = 90_000L

    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val definitelyNotPerformed = setOf("PERMISSION_DENIED", "UNSUPPORTED_VERSION", "INVALID_PAYLOAD", "CAPACITY_EXCEEDED")
    private val legacyPending = setOf("submitting", "requested", "dispatched", "accepted", "executing", "verifying")

    sealed interface ReadResult {
        data object Pending : ReadResult
        data class Succeeded(val destination: String?) : ReadResult
        data object Failed : ReadResult
        data object Unknown : ReadResult
    }

    fun actionError(code: String?): PluginOperationState =
        if (code in definitelyNotPerformed) PluginOperationState.Failed else PluginOperationState.Unknown

    fun acceptedId(response: PluginResponseEnvelope?): String? {
        if (response?.status != "accepted" || response.payload.keys != setOf("operationId")) return null
        return response.payload.string("operationId")?.takeIf(identifier::matches)
    }

    fun destination(result: JsonObject?): String? {
        if (result?.keys != setOf("destination")) return null
        return result.string("destination")?.takeIf { it == "page" || it == "setup" }
    }

    fun read(response: PluginResponseEnvelope?, id: String, navigation: Boolean): ReadResult {
        if (response?.status != "ok") return ReadResult.Unknown
        val payload = response.payload
        if (!payload.keys.containsAll(setOf("operationId", "state")) ||
            payload.keys.any { it !in setOf("operationId", "state", "result") } || payload.string("operationId") != id
        ) return ReadResult.Unknown
        return when (payload.string("state")) {
            "pending", in legacyPending -> ReadResult.Pending
            "succeeded" -> if (navigation) {
                destination(payload["result"] as? JsonObject)?.let(ReadResult::Succeeded) ?: ReadResult.Unknown
            } else ReadResult.Succeeded(null)
            "failed" -> ReadResult.Failed
            // Older plugins used these additional terminal states without defining
            // whether a side effect may already have occurred.
            "rejected", "cancelled", "expired" -> ReadResult.Unknown
            else -> ReadResult.Unknown
        }
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
