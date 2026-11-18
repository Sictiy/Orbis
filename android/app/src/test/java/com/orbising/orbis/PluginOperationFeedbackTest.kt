package com.orbising.orbis

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class PluginOperationFeedbackTest {
    private class Pending : PluginPendingOperation {
        var value = false
        var writable = true
        override fun exists() = value
        override fun set(pending: Boolean): Boolean {
            if (!writable) return false
            value = pending
            return true
        }
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() = tasks.removeFirst().run()
    }

    @Test fun interruptedOperationRequiresExplicitAcknowledgement() {
        val feedback = PluginOperationFeedback()
        assertTrue(feedback.begin())
        feedback.update(PluginOperationState.Waiting)
        feedback.disconnected()
        assertEquals(PluginOperationState.Unknown, feedback.state)
        assertFalse(feedback.canStart)
        assertFalse(feedback.begin())
        feedback.acknowledge()
        assertTrue(feedback.canStart)
        assertTrue(feedback.begin())
    }

    @Test fun finishedResultsSurviveDisconnectAndCanBeDismissed() {
        val feedback = PluginOperationFeedback()
        feedback.begin()
        feedback.update(PluginOperationState.Succeeded)
        feedback.disconnected()
        assertEquals(PluginOperationState.Succeeded, feedback.state)
        feedback.acknowledge()
        assertNull(feedback.state)
        feedback.begin()
        feedback.update(PluginOperationState.Failed)
        feedback.disconnected()
        assertEquals(PluginOperationState.Failed, feedback.state)
    }

    @Test fun activeOperationCannotBeDismissed() {
        val feedback = PluginOperationFeedback()
        feedback.begin()
        feedback.acknowledge()
        assertEquals(PluginOperationState.Submitting, feedback.state)
        assertFalse(feedback.begin())
    }

    @Test fun restartRestoresAnUnfinishedOperationAsUnknown() {
        val pending = Pending()
        val first = PluginOperationFeedback(pending)
        assertTrue(first.begin())
        first.update(PluginOperationState.Waiting)

        val restored = PluginOperationFeedback(pending)
        assertEquals(PluginOperationState.Unknown, restored.state)
        assertFalse(restored.begin())
        restored.acknowledge()
        assertTrue(restored.begin())
    }

    @Test fun failedPersistencePreventsSendingAndFailedClearKeepsTheGateClosed() {
        val pending = Pending()
        val feedback = PluginOperationFeedback(pending)
        pending.writable = false
        var ready = true
        assertTrue(feedback.begin { ready = it })
        assertFalse(ready)
        assertEquals(PluginOperationState.Failed, feedback.state)

        pending.writable = true
        assertTrue(feedback.begin())
        pending.writable = false
        feedback.update(PluginOperationState.Succeeded)
        assertEquals(PluginOperationState.Unknown, feedback.state)
        feedback.acknowledge()
        assertFalse(feedback.canStart)
        pending.writable = true
        feedback.acknowledge()
        assertTrue(feedback.canStart)
    }

    @Test fun actionAndCompletionWaitForBackgroundStorageWithoutBlockingTheGate() {
        val pending = Pending()
        val io = QueueExecutor()
        val main = QueueExecutor()
        val feedback = PluginOperationFeedback(pending, io, { main.execute(it) })
        assertFalse(feedback.canStart)
        io.runNext(); main.runNext()
        assertTrue(feedback.canStart)

        var ready: Boolean? = null
        assertTrue(feedback.begin { ready = it })
        assertEquals(PluginOperationState.Submitting, feedback.state)
        assertFalse(feedback.begin())
        assertNull(ready)
        assertFalse(pending.value)
        io.runNext()
        assertTrue(pending.value)
        assertNull(ready)
        main.runNext()
        assertEquals(true, ready)

        feedback.update(PluginOperationState.Succeeded)
        assertFalse(feedback.canStart)
        feedback.disconnected()
        assertEquals(PluginOperationState.Submitting, feedback.state)
        io.runNext(); main.runNext()
        assertEquals(PluginOperationState.Succeeded, feedback.state)
        assertFalse(pending.value)
        assertTrue(feedback.canStart)
    }

    @Test fun disconnectAndAcknowledgementCannotOvertakeThePendingWrite() {
        val pending = Pending()
        val io = QueueExecutor()
        val main = QueueExecutor()
        val feedback = PluginOperationFeedback(pending, io, { main.execute(it) })
        io.runNext(); main.runNext()
        var ready: Boolean? = null
        feedback.begin { ready = it }
        feedback.disconnected()
        feedback.acknowledge()
        assertFalse(feedback.canStart)
        io.runNext()
        assertTrue(pending.value)
        io.runNext()
        assertFalse(pending.value)
        main.runNext()
        assertEquals(false, ready)
        assertEquals(PluginOperationState.Unknown, feedback.state)
        main.runNext()
        assertNull(feedback.state)
        assertTrue(feedback.canStart)
    }

    @Test fun replacementControllerReadsTheEarlierWriteBeforeAllowingActions() {
        val pending = Pending()
        val io = QueueExecutor()
        val main = QueueExecutor()
        val first = PluginOperationFeedback(pending, io, { main.execute(it) })
        io.runNext(); main.runNext()
        first.begin()
        first.disconnected()
        val restored = PluginOperationFeedback(pending, io, { main.execute(it) })
        assertFalse(restored.canStart)
        io.runNext(); io.runNext()
        main.runNext(); main.runNext()
        assertEquals(PluginOperationState.Unknown, restored.state)
        assertFalse(restored.canStart)
    }
}
