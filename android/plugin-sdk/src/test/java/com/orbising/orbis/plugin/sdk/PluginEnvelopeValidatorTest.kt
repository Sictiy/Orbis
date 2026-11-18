package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginEnvelopeValidatorTest {
    private val now = 10_000L

    private fun request(
        payload: String = "{}",
        operation: String = "action.preflight",
        deadline: Long = 10_900,
        binding: String = "\"binding-1\"",
        host: String = "\"host-1\"",
    ): String = """
        {
          "apiVersion": 1, "payloadSchemaVersion": 1,
          "requestId": "request-1", "bindingId": $binding,
          "pluginId": "com.example.plugin", "capability": "host.control",
          "op": "$operation", "hostId": $host,
          "deadlineElapsedRealtimeMs": $deadline, "payload": $payload
        }
    """.trimIndent()

    private fun validate(raw: String, endpoint: PluginRequestEndpoint = PluginRequestEndpoint.PLUGIN): EnvelopeValidation =
        PluginEnvelopeValidator.validate(raw, now, endpoint)

    private fun reason(raw: String, endpoint: PluginRequestEndpoint = PluginRequestEndpoint.PLUGIN): String =
        (validate(raw, endpoint) as EnvelopeValidation.Invalid).reason

    @Test
    fun acceptsBoundPreflightAndReturnsDecodedPayload() {
        val result = validate(request(payload = """{"label":"设备"}"""))
        assertTrue(result is EnvelopeValidation.Valid)
        val envelope = (result as EnvelopeValidation.Valid).envelope
        assertEquals("binding-1", envelope.bindingId)
        assertEquals("host-1", envelope.hostId)
        assertEquals("设备", (envelope.payload["label"] as JsonPrimitive).content)
    }

    @Test
    fun onlyDescriptorAndBindingOpenCanOmitBinding() {
        assertTrue(validate(request(operation = "descriptor.read", binding = "null")) is EnvelopeValidation.Valid)
        assertTrue(validate(request(operation = "binding.open", binding = "null"), PluginRequestEndpoint.BINDING_OPEN) is EnvelopeValidation.Valid)
        assertEquals("binding_required", reason(request(binding = "null")))
    }

    @Test
    fun rejectsDuplicateKeysEvenWhenEscapedOrNested() {
        assertEquals("invalid_json", reason(request(payload = """{"id":1,"\u0069d":2}""")))
        assertEquals("invalid_json", reason(request(payload = """{"nested":{"x":1,"x":2}}""")))
        assertEquals("invalid_json", reason(request().replace("\"apiVersion\": 1,", "\"apiVersion\": 1, \"apiVersion\": 1,")))
    }

    @Test
    fun rejectsUnknownOrMissingFieldsAndVersion() {
        assertEquals("invalid_fields", reason(request().replace("\"payload\": {}", "\"payload\": {}, \"extra\": true")))
        assertEquals("invalid_fields", reason(request().replace("\"hostId\": \"host-1\",", "")))
        assertEquals("unsupported_version", reason(request().replace("\"apiVersion\": 1", "\"apiVersion\": 2")))
        assertEquals("unsupported_version", reason(request().replace("\"apiVersion\": 1", "\"apiVersion\": \"1\"")))
    }

    @Test
    fun rejectsExpiredOrUnreasonablyDistantDeadlines() {
        assertEquals("expired_or_excessive_deadline", reason(request(deadline = now)))
        assertEquals("expired_or_excessive_deadline", reason(request(deadline = now + 1_001)))
        assertEquals("expired_or_excessive_deadline", reason(request(operation = "page.read", deadline = now + 3_001)))
        assertTrue(validate(request(operation = "page.read", deadline = now + 3_000)) is EnvelopeValidation.Valid)
    }

    @Test
    fun rejectsMalformedValuesAndUnsupportedOperations() {
        assertEquals("invalid_operation", reason(request(operation = "anything")))
        assertEquals("invalid_payload", reason(request(payload = "[]")))
        assertEquals("invalid_json", reason(request(payload = """{"text":"\q"}""")))
        assertEquals("invalid_json", reason(request(payload = "{\"text\":\"line\nbreak\"}")))
        assertEquals("invalid_json", reason(request(payload = """{"n":01}""")))
    }

    @Test
    fun rejectsInvalidSurrogatesAndAcceptsValidEmoji() {
        assertEquals("invalid_json", reason(request(payload = """{"text":"\uD800"}""")))
        assertEquals("invalid_json", reason(request(payload = """{"text":"\uDE00"}""")))
        assertEquals("invalid_json", reason(request(payload = """{"text":"\uD800x"}""")))
        assertEquals("invalid_json", reason(request(payload = """{"text":"\uD800\u0041"}""")))
        assertEquals("invalid_json", reason(request(payload = """{"text":"\u00ＦＦ"}""")))
        assertTrue(validate(request(payload = """{"text":"\uD83D\uDE00"}""")) is EnvelopeValidation.Valid)
        assertTrue(validate(request(payload = """{"text":"\\uD800"}""")) is EnvelopeValidation.Valid)
    }

    @Test
    fun boundsUtf8BytesRatherThanCharacterCount() {
        val withinStringLimit = JsonPrimitive("鸟".repeat(2_700)).toString()
        assertTrue(validate(request(payload = """{"text":$withinStringLimit}""")) is EnvelopeValidation.Valid)
        val overStringLimit = JsonPrimitive("鸟".repeat(2_800)).toString()
        assertEquals("invalid_json", reason(request(payload = """{"text":$overStringLimit}""")))
        val overEnvelopeLimit = JsonPrimitive("鸟".repeat(6_000)).toString()
        assertEquals("invalid_or_oversized_envelope", reason(request(payload = """{"text":$overEnvelopeLimit}""")))
    }

    @Test
    fun boundsNestingAndCollectionSizes() {
        val tooDeep = "[".repeat(PluginProtocol.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(PluginProtocol.MAX_JSON_DEPTH + 1)
        assertEquals("invalid_json", reason(request(payload = """{"value":$tooDeep}""")))
        val tooManyItems = (0..PluginProtocol.MAX_COLLECTION_SIZE).joinToString(",", "[", "]")
        assertEquals("invalid_json", reason(request(payload = """{"items":$tooManyItems}""")))
    }

    @Test
    fun rejectsOperationsSentToTheWrongEndpoint() {
        assertEquals("invalid_endpoint", reason(request(operation = "binding.open", binding = "null")))
        assertEquals("invalid_endpoint", reason(request(operation = "host.action.request")))
        assertEquals("invalid_endpoint", reason(request(operation = "plugin.event.publish")))
        assertEquals("invalid_endpoint", reason(request(operation = "ui.read"), PluginRequestEndpoint.HOST))
        assertEquals("invalid_binding_id", reason(request(operation = "binding.open"), PluginRequestEndpoint.BINDING_OPEN))
        assertTrue(validate(request(operation = "host.action.request"), PluginRequestEndpoint.HOST) is EnvelopeValidation.Valid)
        assertTrue(validate(request(operation = "plugin.event.publish"), PluginRequestEndpoint.HOST) is EnvelopeValidation.Valid)
    }
}
