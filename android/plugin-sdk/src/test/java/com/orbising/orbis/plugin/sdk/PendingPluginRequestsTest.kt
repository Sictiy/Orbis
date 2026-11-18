package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingPluginRequestsTest {
    private val identity = PendingPluginRequests.RequestIdentity(
        pluginId = "com.example.plugin",
        capability = "ui",
        operation = "ui.read",
        hostId = "host-1",
    )

    private fun PendingPluginRequests.started(deadline: Long = 2_000): PendingPluginRequests.Ticket =
        (start(identity, deadline, 1_000) as PendingPluginRequests.StartResult.Started).ticket

    private fun response(ticket: PendingPluginRequests.Ticket) = PluginResponseEnvelope(
        requestId = ticket.requestId,
        bindingId = ticket.bindingId,
        pluginId = ticket.identity.pluginId,
        capability = ticket.identity.capability,
        operation = ticket.identity.operation,
        hostId = ticket.identity.hostId,
        status = "ok",
        errorCode = null,
        payload = JsonObject(emptyMap()),
    )

    @Test
    fun acceptsOnlyOneResponseAndKeepsWrongBindingFromConsumingIt() {
        val calls = PendingPluginRequests("binding")
        val ticket = calls.started()
        val response = response(ticket)
        assertNull(calls.takeReply(response.copy(bindingId = "other"), 1_001))
        assertNull(calls.takeReply(response.copy(operation = "page.read"), 1_001))
        assertNull(calls.takeReply(response.copy(pluginId = "com.example.other"), 1_001))
        assertNull(calls.takeReply(response.copy(capability = "preflight"), 1_001))
        assertNull(calls.takeReply(response.copy(hostId = "other-host"), 1_001))
        assertEquals(ticket, calls.takeReply(response, 1_002))
        assertNull(calls.takeReply(response, 1_003))
    }

    @Test
    fun boundsConcurrencyAndReturnsExpiredWorkForCancellation() {
        val calls = PendingPluginRequests("binding", capacity = 2)
        val first = calls.started(1_100)
        calls.started(1_200)
        assertEquals(PendingPluginRequests.StartResult.AtCapacity, calls.start(identity, 2_000, 1_000))
        assertEquals(listOf(first), calls.expire(1_100))
        assertTrue(calls.start(identity, 2_000, 1_100) is PendingPluginRequests.StartResult.Started)
    }

    @Test
    fun lateReplyCannotBeDelivered() {
        val calls = PendingPluginRequests("binding")
        val ticket = calls.started()
        assertNull(calls.takeReply(response(ticket), 2_000))
        assertEquals(listOf(ticket), calls.expire(2_001))
    }

    @Test
    fun closeInvalidatesAllCallsAndPreventsNewOnes() {
        val calls = PendingPluginRequests("binding")
        val first = calls.started()
        val second = calls.started()
        assertEquals(listOf(first, second), calls.close())
        assertNull(calls.takeReply(response(first), 1_001))
        assertEquals(PendingPluginRequests.StartResult.Closed, calls.start(identity, 2_000, 1_001))
        assertTrue(calls.close().isEmpty())
    }

    @Test
    fun racingCallbacksCanDeliverAtMostOnce() {
        val calls = PendingPluginRequests("binding")
        val ticket = calls.started()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<PendingPluginRequests.Ticket?> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    calls.takeReply(response(ticket), 1_001)
                }
            }
            start.countDown()
            val delivered = results.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, delivered.size)
            assertNotNull(delivered.single())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectsInvalidDeadlinesAndUnknownOperationsWithoutUsingCapacity() {
        val calls = PendingPluginRequests("binding", capacity = 1)
        assertEquals(PendingPluginRequests.StartResult.InvalidDeadline, calls.start(identity, 1_000, 1_000))
        assertEquals(PendingPluginRequests.StartResult.UnsupportedOperation, calls.start(identity.copy(operation = "unknown"), 2_000, 1_000))
        assertTrue(calls.start(identity, 2_000, 1_000) is PendingPluginRequests.StartResult.Started)
    }
}
