package com.orbising.orbis

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginPendingOperationInstrumentedTest {
    private val app = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-pending")
    private val approval = ApprovedPluginService("test.plugin.one", "a".repeat(64), "test.plugin.one.Service")

    @After fun cleanup() { app.dispose() }

    @Test fun unfinishedMarkerSurvivesRecreationAndIsScopedToTheExactIdentity() {
        fun store(host: String?, identity: ApprovedPluginService = approval) = PluginPendingOperationStore(app, host, identity)
        val original = PluginOperationFeedback(store("host-a"))
        assertTrue(original.begin())
        val restored = PluginOperationFeedback(store("host-a"))
        assertEquals(PluginOperationState.Unknown, restored.state)
        assertFalse(restored.canStart)
        assertNull(PluginOperationFeedback(store("host-b")).state)
        assertNull(PluginOperationFeedback(store(null)).state)
        assertNull(PluginOperationFeedback(store("host-a", approval.copy(signerSha256 = "b".repeat(64)))).state)
        assertNull(PluginOperationFeedback(store("host-a", approval.copy(className = "test.plugin.one.Other"))).state)
        restored.acknowledge()
        assertNull(PluginOperationFeedback(store("host-a")).state)
    }

    @Test fun failedClearKeepsTheGateClosedAcrossControllersUntilAConfirmedClear() {
        val name = "orbis_plugin_pending_operations_v1"
        val preferences = FailingClearPreferences(app.getSharedPreferences(name, Context.MODE_PRIVATE))
        fun context() = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "orbis_plugin_pending_operations_v1") preferences else app.getSharedPreferences(name, mode)
        }
        fun store(host: String?, identity: ApprovedPluginService = approval, preferenceName: String = name) =
            PluginPendingOperationStore(context(), host, identity, preferenceName)

        for (failure in ClearFailure.entries) {
            val original = PluginOperationFeedback(store("host-a"))
            assertTrue(original.begin())
            original.disconnected()
            preferences.nextClearFailure = failure
            original.acknowledge()
            assertEquals(PluginOperationState.Unknown, original.state)
            assertTrue("The failed commit must have removed the in-memory key", preferences.all.isEmpty())

            val restored = PluginOperationFeedback(store("host-a"))
            assertEquals(PluginOperationState.Unknown, restored.state)
            assertFalse(restored.canStart)
            assertTrue(PluginOperationFeedback(store("host-b")).canStart)
            assertTrue(PluginOperationFeedback(store("host-a", approval.copy(signerSha256 = "b".repeat(64)))).canStart)
            assertTrue(PluginOperationFeedback(store("host-a", preferenceName = "other-pending-store")).canStart)

            restored.acknowledge()
            assertTrue(PluginOperationFeedback(store("host-a")).canStart)
        }
    }
}

private enum class ClearFailure { ReturnFalse, Throw }

/** Emulates commit failures after the in-process preference view has changed. */
private class FailingClearPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    var nextClearFailure: ClearFailure? = null

    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            private var removed = false

            override fun remove(key: String): SharedPreferences.Editor {
                removed = true
                editor.remove(key)
                return this
            }

            override fun commit(): Boolean {
                val failure = if (removed) nextClearFailure.also { nextClearFailure = null } else null
                val committed = editor.commit()
                if (failure == ClearFailure.Throw) throw IllegalStateException("simulated commit failure")
                return committed && failure != ClearFailure.ReturnFalse
            }
        }
    }
}
