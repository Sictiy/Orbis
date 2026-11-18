package com.orbising.orbis.plugin.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginResponseValidatorTest {
    private fun response(
        status: String = "ok",
        errorCode: String = "null",
        payload: String = "{}",
    ) = """
        {
          "apiVersion": 1, "payloadSchemaVersion": 1,
          "requestId": "request-1", "bindingId": "binding-1",
          "pluginId": "com.example.plugin", "capability": "ui",
          "op": "ui.read", "hostId": "host-1",
          "status": "$status", "errorCode": $errorCode, "payload": $payload
        }
    """.trimIndent()

    private fun reason(raw: String): String =
        (PluginResponseValidator.validate(raw) as ResponseValidation.Invalid).reason

    @Test
    fun acceptsSuccessfulAndAcceptedResponses() {
        assertTrue(PluginResponseValidator.validate(response(payload = """{"items":[]}""")) is ResponseValidation.Valid)
        assertTrue(PluginResponseValidator.validate(response(status = "accepted", payload = """{"operationId":"op-1"}""")) is ResponseValidation.Valid)
    }

    @Test
    fun acceptsOnlyKnownErrorsWithEmptyPayload() {
        assertTrue(PluginResponseValidator.validate(response(status = "error", errorCode = "\"TIMEOUT\"")) is ResponseValidation.Valid)
        assertEquals("invalid_error_code", reason(response(status = "error")))
        assertEquals("invalid_error_code", reason(response(status = "error", errorCode = "\"UNKNOWN\"")))
        assertEquals("invalid_error_code", reason(response(errorCode = "\"TIMEOUT\"")))
        assertEquals("invalid_error_payload", reason(response(status = "error", errorCode = "\"TIMEOUT\"", payload = """{"details":"secret"}""")))
    }

    @Test
    fun rejectsMalformedResponseFieldsAndVersion() {
        assertEquals("invalid_status", reason(response(status = "done")))
        assertEquals("invalid_payload", reason(response(payload = "[]")))
        assertEquals("invalid_fields", reason(response().replace("\"payload\": {}", "\"payload\": {}, \"extra\": 1")))
        assertEquals("unsupported_version", reason(response().replace("\"apiVersion\": 1", "\"apiVersion\": 2")))
    }

    @Test
    fun enforcesSameJsonBoundsAsRequests() {
        assertEquals("invalid_json", reason(response(payload = """{"id":1,"\u0069d":2}""")))
        assertEquals("invalid_json", reason(response(payload = """{"text":"\uD800"}""")))
        val huge = "a".repeat(PluginProtocol.MAX_ENVELOPE_UTF8_BYTES)
        assertEquals("invalid_or_oversized_envelope", reason(response(payload = """{"text":"$huge"}""")))
    }
}
