package com.orbising.orbis.plugin.sdk

import java.util.UUID

/**
 * Tracks outgoing calls for one binding. A response must also pass Binder caller,
 * operation and payload validation before it is delivered to the application.
 */
class PendingPluginRequests(
    val bindingId: String?,
    private val capacity: Int = PluginProtocol.MAX_IN_FLIGHT_PER_PLUGIN,
) {
    init {
        require(capacity in 1..PluginProtocol.MAX_IN_FLIGHT_PER_PLUGIN)
    }

    data class RequestIdentity(
        val pluginId: String,
        val capability: String,
        val operation: String,
        val hostId: String?,
    )

    data class Ticket(
        val requestId: String,
        val bindingId: String?,
        val identity: RequestIdentity,
        val deadlineElapsedRealtimeMs: Long,
    )

    sealed interface StartResult {
        data class Started(val ticket: Ticket) : StartResult
        data object AtCapacity : StartResult
        data object Closed : StartResult
        data object InvalidDeadline : StartResult
        data object UnsupportedOperation : StartResult
    }

    private val pending = LinkedHashMap<String, Ticket>()
    private var closed = false

    /** The owner schedules expiry independently and invokes [expire] when due. */
    @Synchronized
    fun start(identity: RequestIdentity, deadlineElapsedRealtimeMs: Long, nowElapsedRealtimeMs: Long): StartResult {
        if (closed) return StartResult.Closed
        if (nowElapsedRealtimeMs < 0 || deadlineElapsedRealtimeMs <= nowElapsedRealtimeMs) return StartResult.InvalidDeadline
        if (identity.operation !in PluginProtocol.requestOperations) return StartResult.UnsupportedOperation
        if (pending.size >= capacity) return StartResult.AtCapacity
        val ticket = Ticket(UUID.randomUUID().toString(), bindingId, identity, deadlineElapsedRealtimeMs)
        pending[ticket.requestId] = ticket
        return StartResult.Started(ticket)
    }

    /**
     * Consumes a matching live ticket exactly once. Mismatched or late replies
     * leave the ticket for its owner or expiry timer; neither can deliver it.
     */
    @Synchronized
    fun takeReply(response: PluginResponseEnvelope, nowElapsedRealtimeMs: Long): Ticket? {
        if (closed || bindingId != response.bindingId || nowElapsedRealtimeMs < 0) return null
        val ticket = pending[response.requestId] ?: return null
        val identity = ticket.identity
        if (identity.pluginId != response.pluginId || identity.capability != response.capability ||
            identity.operation != response.operation || identity.hostId != response.hostId ||
            nowElapsedRealtimeMs >= ticket.deadlineElapsedRealtimeMs
        ) return null
        return pending.remove(response.requestId)
    }

    @Synchronized
    fun cancel(requestId: String): Ticket? = pending.remove(requestId)

    /** Returns expired tickets so the owner can cancel remote work and report timeout. */
    @Synchronized
    fun expire(nowElapsedRealtimeMs: Long): List<Ticket> {
        if (nowElapsedRealtimeMs < 0) return emptyList()
        val expired = pending.values.filter { it.deadlineElapsedRealtimeMs <= nowElapsedRealtimeMs }
        expired.forEach { pending.remove(it.requestId) }
        return expired
    }

    /** Binder death, revocation or package replacement closes the whole binding. */
    @Synchronized
    fun close(): List<Ticket> {
        if (closed) return emptyList()
        closed = true
        val abandoned = pending.values.toList()
        pending.clear()
        return abandoned
    }
}
