package com.orbising.orbis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginApprovalStoreInstrumentedTest {
    private val context = IsolatedTestApplication(ApplicationProvider.getApplicationContext<Context>(), "plugin-approval-store")
    private val name = "plugin_approvals_instrumented_test"
    private val first = ApprovedPluginService("test.plugin.one", "a".repeat(64), "test.plugin.one.Service")
    private val second = ApprovedPluginService("test.plugin.two", "b".repeat(64), "test.plugin.two.Service")
    private fun store() = PluginApprovalStore(context, name)

    @Before fun clear() { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun cleanup() { context.disposePluginTestStorage() }

    @Test fun approvalsPersistAndRemainScopedToTheApprovedHost() {
        store().save("host-a", first)
        store().save("host-a", second)
        store().save("host-b", first)
        store().save(null, second)
        assertEquals(setOf(first, second), store().forHost("host-a").toSet())
        assertEquals(listOf(first), store().forHost("host-b"))
        assertEquals(listOf(second), store().forHost(null))
        store().remove("host-a", first)
        assertEquals(listOf(second), store().forHost("host-a"))
        assertEquals(listOf(first), store().forHost("host-b"))
    }

    @Test fun changedSignerReplacesOnlyTheMatchingServiceAndHost() {
        val changed = first.copy(signerSha256 = "c".repeat(64))
        store().save("host-a", first)
        store().save("host-a", second)
        store().save("host-b", first)
        store().save("host-a", changed)
        assertEquals(setOf(changed, second), store().forHost("host-a").toSet())
        assertEquals(listOf(first), store().forHost("host-b"))
    }

    @Test fun removingServiceRevokesEveryHostButKeepsOtherPlugins() {
        store().save("host-a", first)
        store().save("host-b", first)
        store().save(null, first)
        store().save("host-b", second)
        store().removeService(first.packageName, first.className)
        assertEquals(emptyList<ApprovedPluginService>(), store().forHost("host-a"))
        assertEquals(listOf(second), store().forHost("host-b"))
        assertEquals(emptyList<ApprovedPluginService>(), store().forHost(null))
    }

    @Test fun unpairingRevokesOnlyTheRemovedHost() {
        store().save("host-a", first)
        store().save("host-a", second)
        store().save("host-b", first)
        store().save(null, second)
        store().removeHost("host-a")
        assertEquals(emptyList<ApprovedPluginService>(), store().forHost("host-a"))
        assertEquals(listOf(first), store().forHost("host-b"))
        assertEquals(listOf(second), store().forHost(null))
    }
}
