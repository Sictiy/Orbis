package com.orbising.orbis

import org.junit.Assert.assertEquals
import org.junit.Test

/** Delivers plugin callbacks in chosen order, as can happen with independent Binder requests. */
class PluginNavigationRequestsTest {
    private class Navigation {
        private val requests = PluginNavigationRequests()
        var selected = "chat"; private set

        fun requestPlugin(id: String): () -> Unit {
            val request = requests.begin()
            return { if (requests.isCurrent(request)) selected = id }
        }

        fun selectBuiltIn(id: String) {
            requests.cancel()
            selected = id
        }

        fun dispose() { requests.cancel() }
    }

    @Test fun selectingAnExistingTabCancelsAnEarlierPluginRequest() {
        val navigation = Navigation()
        val delayedPlugin = navigation.requestPlugin("plugin-a")
        navigation.selectBuiltIn("chat")
        delayedPlugin()
        assertEquals("chat", navigation.selected)
    }

    @Test fun selectingADifferentTabCancelsAnEarlierPluginRequest() {
        val navigation = Navigation()
        val delayedPlugin = navigation.requestPlugin("plugin-a")
        navigation.selectBuiltIn("settings")
        delayedPlugin()
        assertEquals("settings", navigation.selected)
    }

    @Test fun anOlderResponseCannotOpenWhileANewerRequestIsStillPending() {
        val navigation = Navigation()
        val first = navigation.requestPlugin("plugin-a")
        val second = navigation.requestPlugin("plugin-b")
        first()
        assertEquals("chat", navigation.selected)
        second()
        assertEquals("plugin-b", navigation.selected)
    }

    @Test fun anOlderResponseCannotOverrideANewerCompletedRequest() {
        val navigation = Navigation()
        val first = navigation.requestPlugin("plugin-a")
        val second = navigation.requestPlugin("plugin-b")
        second()
        first()
        assertEquals("plugin-b", navigation.selected)
    }

    @Test fun disposalDoesNotInvalidateASubsequentRequest() {
        val navigation = Navigation()
        val stale = navigation.requestPlugin("plugin-a")
        navigation.dispose()
        val current = navigation.requestPlugin("plugin-b")
        stale()
        assertEquals("chat", navigation.selected)
        current()
        assertEquals("plugin-b", navigation.selected)
    }
}
