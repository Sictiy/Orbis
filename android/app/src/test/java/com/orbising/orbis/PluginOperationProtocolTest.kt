package com.orbising.orbis

import com.orbising.orbis.plugin.sdk.PluginResponseEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginOperationProtocolTest {
    private fun response(payload: String, status: String = "ok") = PluginResponseEnvelope(
        requestId = "request-1", bindingId = "binding-1", pluginId = "example.plugin", capability = "ui",
        operation = "operation.read", hostId = null, status = status, errorCode = null,
        payload = Json.parseToJsonElement(payload).jsonObject,
    )

    @Test fun onlyPreExecutionErrorsReleaseTheDuplicateGate() {
        listOf("PERMISSION_DENIED", "UNSUPPORTED_VERSION", "INVALID_PAYLOAD", "CAPACITY_EXCEEDED").forEach {
            assertEquals(PluginOperationState.Failed, PluginOperationProtocol.actionError(it))
        }
        listOf("TIMEOUT", "CANCELLED", "PLUGIN_UNAVAILABLE", "INTERNAL_ERROR", null).forEach {
            assertEquals(PluginOperationState.Unknown, PluginOperationProtocol.actionError(it))
        }
    }

    @Test fun readsCanonicalAndLegacyStatesWithoutAssumingAmbiguousFailures() {
        fun read(state: String) = PluginOperationProtocol.read(response("""{"operationId":"op-1","state":"$state"}"""), "op-1", false)
        listOf("pending", "submitting", "requested", "dispatched", "accepted", "executing", "verifying").forEach {
            assertEquals(PluginOperationProtocol.ReadResult.Pending, read(it))
        }
        assertEquals(PluginOperationProtocol.ReadResult.Succeeded(null), read("succeeded"))
        assertEquals(PluginOperationProtocol.ReadResult.Failed, read("failed"))
        listOf("rejected", "cancelled", "expired", "unknown", "future-state").forEach {
            assertEquals(PluginOperationProtocol.ReadResult.Unknown, read(it))
        }
    }

    @Test fun asyncNavigationRequiresACorrelatedAndValidDestination() {
        val success = response("""{"operationId":"op-1","state":"succeeded","result":{"destination":"page"}}""")
        assertEquals(PluginOperationProtocol.ReadResult.Succeeded("page"), PluginOperationProtocol.read(success, "op-1", true))
        assertEquals(PluginOperationProtocol.ReadResult.Unknown, PluginOperationProtocol.read(success, "op-2", true))
        listOf(
            """{"operationId":"op-1","state":"succeeded"}""",
            """{"operationId":"op-1","state":"succeeded","result":{"destination":"other"}}""",
            """{"operationId":"op-1","state":"succeeded","result":{"destination":"page","extra":true}}""",
            """{"operationId":"op-1","state":"succeeded","extra":true}""",
        ).forEach {
            assertEquals(PluginOperationProtocol.ReadResult.Unknown, PluginOperationProtocol.read(response(it), "op-1", true))
        }
    }

    @Test fun acceptedHandlesMustBeBoundedAndExact() {
        assertEquals("op-1", PluginOperationProtocol.acceptedId(response("""{"operationId":"op-1"}""", "accepted")))
        assertNull(PluginOperationProtocol.acceptedId(response("""{"operationId":"op-1","extra":true}""", "accepted")))
        assertNull(PluginOperationProtocol.acceptedId(response("""{"operationId":"bad/id"}""", "accepted")))
    }
}
