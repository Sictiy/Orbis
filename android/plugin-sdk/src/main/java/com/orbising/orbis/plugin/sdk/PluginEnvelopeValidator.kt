package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Checks the request envelope, not the caller, capability grant or operation payload. */
object PluginEnvelopeValidator {
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val capabilityName = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    private val fields = setOf(
        "apiVersion", "payloadSchemaVersion", "requestId", "bindingId", "pluginId",
        "capability", "op", "hostId", "deadlineElapsedRealtimeMs", "payload",
    )

    /** [nowElapsedRealtimeMs] must come from SystemClock.elapsedRealtime() at receipt. */
    fun validate(raw: String, nowElapsedRealtimeMs: Long, endpoint: PluginRequestEndpoint): EnvelopeValidation {
        fun invalid(reason: String) = EnvelopeValidation.Invalid(reason)

        val parsed = parseBoundedObject(raw)
        if (parsed is BoundedObject.Invalid) return invalid(parsed.reason)
        val root = (parsed as BoundedObject.Valid).value
        if (nowElapsedRealtimeMs < 0) return invalid("invalid_clock")

        if (root.keys != fields) return invalid("invalid_fields")
        if (root.integer("apiVersion") != PluginProtocol.API_VERSION.toLong() ||
            root.integer("payloadSchemaVersion") != PluginProtocol.PAYLOAD_SCHEMA_VERSION.toLong()
        ) return invalid("unsupported_version")

        val requestId = root.string("requestId")?.takeIf(identifier::matches) ?: return invalid("invalid_request_id")
        val pluginId = root.string("pluginId")?.takeIf(identifier::matches) ?: return invalid("invalid_plugin_id")
        val capability = root.string("capability")?.takeIf(capabilityName::matches) ?: return invalid("invalid_capability")
        val op = root.string("op")?.takeIf { it in PluginProtocol.requestOperations } ?: return invalid("invalid_operation")
        val routedCorrectly = when (endpoint) {
            PluginRequestEndpoint.BINDING_OPEN -> op == "binding.open"
            PluginRequestEndpoint.PLUGIN -> op != "binding.open" && op != "host.action.request" && op != "plugin.event.publish"
            PluginRequestEndpoint.HOST -> op == "host.action.request" || op == "plugin.event.publish"
        }
        if (!routedCorrectly) return invalid("invalid_endpoint")

        val binding = root.nullableIdentifier("bindingId") ?: return invalid("invalid_binding_id")
        val host = root.nullableIdentifier("hostId") ?: return invalid("invalid_host_id")
        if (op == "binding.open" && binding.value != null) return invalid("invalid_binding_id")
        if (binding.value == null && op != "descriptor.read" && op != "binding.open") {
            return invalid("binding_required")
        }

        val deadline = root.integer("deadlineElapsedRealtimeMs") ?: return invalid("invalid_deadline")
        val maxDeadline = if (op == "action.preflight") PluginProtocol.MAX_PREFLIGHT_DEADLINE_MS else PluginProtocol.MAX_REQUEST_DEADLINE_MS
        if (deadline <= nowElapsedRealtimeMs || deadline - nowElapsedRealtimeMs > maxDeadline) {
            return invalid("expired_or_excessive_deadline")
        }
        val payload = root["payload"] as? JsonObject ?: return invalid("invalid_payload")
        return EnvelopeValidation.Valid(
            PluginRequestEnvelope(requestId, binding.value, pluginId, capability, op, host.value, deadline, payload),
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

internal sealed interface BoundedObject {
    data class Valid(val value: JsonObject) : BoundedObject
    data class Invalid(val reason: String) : BoundedObject
}

/** Common syntax and resource limits for both requests and responses. */
private val strictPluginJson = Json { isLenient = false; ignoreUnknownKeys = false }

internal fun parseBoundedObject(raw: String): BoundedObject {
    if (raw.length > PluginProtocol.MAX_ENVELOPE_UTF8_BYTES || !wellFormedUtf16(raw) ||
        raw.toByteArray(Charsets.UTF_8).size > PluginProtocol.MAX_ENVELOPE_UTF8_BYTES
    ) {
        return BoundedObject.Invalid("invalid_or_oversized_envelope")
    }
    try {
        // Reject duplicates before JsonObject collapses them, and bound nesting before parsing.
        StrictJsonScanner(raw, strictPluginJson).scan()
    } catch (_: InvalidJson) {
        return BoundedObject.Invalid("invalid_json")
    }
    val root = try {
        strictPluginJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return BoundedObject.Invalid("invalid_root")
    return BoundedObject.Valid(root)
}

private class InvalidJson : RuntimeException()

/** Scan grammar, uniqueness and limits without building an unbounded intermediate tree. */
private class StrictJsonScanner(private val input: String, private val json: Json) {
    private var position = 0
    private var values = 0

    fun scan() {
        value(0)
        whitespace()
        if (position != input.length) throw InvalidJson()
    }

    private fun value(depth: Int) {
        whitespace()
        if (depth > PluginProtocol.MAX_JSON_DEPTH || ++values > PluginProtocol.MAX_JSON_VALUES || position >= input.length) throw InvalidJson()
        when (input[position]) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> throw InvalidJson()
        }
    }

    private fun objectValue(depth: Int) {
        position++
        whitespace()
        if (consume('}')) return
        val keys = HashSet<String>()
        do {
            whitespace()
            if (position >= input.length || input[position] != '"') throw InvalidJson()
            if (!keys.add(string()) || keys.size > PluginProtocol.MAX_COLLECTION_SIZE) throw InvalidJson()
            whitespace()
            if (!consume(':')) throw InvalidJson()
            value(depth + 1)
            whitespace()
            if (consume('}')) return
            if (!consume(',')) throw InvalidJson()
        } while (true)
    }

    private fun arrayValue(depth: Int) {
        position++
        whitespace()
        if (consume(']')) return
        var count = 0
        do {
            if (++count > PluginProtocol.MAX_COLLECTION_SIZE) throw InvalidJson()
            value(depth + 1)
            whitespace()
            if (consume(']')) return
            if (!consume(',')) throw InvalidJson()
        } while (true)
    }

    private fun string(): String {
        val start = position++
        while (position < input.length) {
            when (val char = input[position++]) {
                '"' -> {
                    val decoded = try {
                        (json.parseToJsonElement(input.substring(start, position)) as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                    } catch (_: IllegalArgumentException) {
                        null
                    } ?: throw InvalidJson()
                    if (!wellFormedUtf16(decoded) || decoded.toByteArray(Charsets.UTF_8).size > PluginProtocol.MAX_STRING_UTF8_BYTES) throw InvalidJson()
                    return decoded
                }
                '\\' -> {
                    if (position >= input.length) throw InvalidJson()
                    when (input[position++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            val first = hexQuad()
                            if (first in 0xD800..0xDBFF) {
                                if (!consume('\\') || !consume('u') || hexQuad() !in 0xDC00..0xDFFF) throw InvalidJson()
                            } else if (first in 0xDC00..0xDFFF) {
                                throw InvalidJson()
                            }
                        }
                        else -> throw InvalidJson()
                    }
                }
                in '\u0000'..'\u001f' -> throw InvalidJson()
            }
        }
        throw InvalidJson()
    }

    private fun hexQuad(): Int {
        var value = 0
        repeat(4) {
            if (position >= input.length) throw InvalidJson()
            val digit = when (val char = input[position++]) {
                in '0'..'9' -> char - '0'
                in 'a'..'f' -> char - 'a' + 10
                in 'A'..'F' -> char - 'A' + 10
                else -> throw InvalidJson()
            }
            value = (value shl 4) or digit
        }
        return value
    }

    private fun number() {
        consume('-')
        if (consume('0')) {
            if (position < input.length && input[position] in '0'..'9') throw InvalidJson()
        } else {
            if (position >= input.length || input[position] !in '1'..'9') throw InvalidJson()
            digits()
        }
        if (consume('.')) digits()
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            digits()
        }
    }

    private fun digits() {
        val start = position
        while (position < input.length && input[position] in '0'..'9') position++
        if (position == start) throw InvalidJson()
    }

    private fun literal(literal: String) {
        if (!input.startsWith(literal, position)) throw InvalidJson()
        position += literal.length
    }

    private fun whitespace() {
        while (position < input.length && input[position] in " \t\r\n") position++
    }

    private fun consume(char: Char): Boolean {
        if (position >= input.length || input[position] != char) return false
        position++
        return true
    }
}

private fun wellFormedUtf16(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (Character.isHighSurrogate(char)) {
            if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
        } else if (Character.isLowSurrogate(char)) return false
    }
    return true
}
