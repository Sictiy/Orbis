package com.orbising.orbis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executor

internal enum class PluginOperationState { Submitting, Waiting, Succeeded, Failed, Unknown }

/** One durable duplicate-prevention marker for a host and plugin identity. */
internal interface PluginPendingOperation {
    fun exists(): Boolean
    fun set(pending: Boolean): Boolean
}

private class InMemoryPendingOperation : PluginPendingOperation {
    private var pending = false
    override fun exists() = pending
    override fun set(pending: Boolean): Boolean { this.pending = pending; return true }
}

/** Feedback and the duplicate gate survive reconnection and an interrupted process. */
internal class PluginOperationFeedback(
    private val pending: PluginPendingOperation = InMemoryPendingOperation(),
    private val io: Executor = Executor { it.run() },
    private val dispatch: (Runnable) -> Unit = { it.run() },
    private val onChange: () -> Unit = {},
) {
    var state by mutableStateOf<PluginOperationState?>(null); private set
    private var loaded by mutableStateOf(false)
    private var settling by mutableStateOf(false)
    private var generation = 0L
    val canStart: Boolean get() = loaded && !settling && state != PluginOperationState.Submitting &&
        state != PluginOperationState.Waiting && state != PluginOperationState.Unknown

    init {
        io.execute {
            val exists = runCatching { pending.exists() }.getOrDefault(true)
            dispatch(Runnable {
                loaded = true
                if (exists) publish(PluginOperationState.Unknown) else onChange()
            })
        }
    }

    /** Close the gate immediately; invoke ready only after the marker is durable. */
    fun begin(ready: (Boolean) -> Unit = {}): Boolean {
        if (!canStart) return false
        val attempt = ++generation
        publish(PluginOperationState.Submitting)
        io.execute {
            val written = writePending(true)
            dispatch(Runnable {
                val current = attempt == generation && state == PluginOperationState.Submitting && !settling
                if (current && !written) publish(PluginOperationState.Failed)
                ready(current && written)
            })
        }
        return true
    }

    fun update(value: PluginOperationState) {
        if (settling || state == value) return
        if (value == PluginOperationState.Succeeded || value == PluginOperationState.Failed) {
            settle(value)
        } else publish(value)
    }

    fun completedWithoutOperation() { settle(null) }

    fun disconnected() {
        if (!settling && (state == PluginOperationState.Submitting || state == PluginOperationState.Waiting)) {
            publish(PluginOperationState.Unknown)
        }
    }

    fun acknowledge() {
        if (settling) return
        if (state == PluginOperationState.Submitting || state == PluginOperationState.Waiting) return
        if (state == PluginOperationState.Unknown) settle(null) else publish(null)
    }

    /** Serial I/O ensures a clear can never overtake an earlier pending write. */
    private fun settle(result: PluginOperationState?) {
        if (settling) return
        settling = true
        io.execute {
            val cleared = writePending(false)
            dispatch(Runnable {
                settling = false
                publish(if (cleared) result else PluginOperationState.Unknown)
            })
        }
    }

    private fun writePending(value: Boolean) = runCatching { pending.set(value) }.getOrDefault(false)

    private fun publish(value: PluginOperationState?) {
        if (state == value) return
        state = value
        onChange()
    }
}
