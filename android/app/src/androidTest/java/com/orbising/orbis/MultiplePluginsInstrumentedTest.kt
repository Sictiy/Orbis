package com.orbising.orbis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MultiplePluginsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachPluginStatusAndActionAreAccessibleInTheDrawer() = exerciseDrawer(secondHasStatus = true)

    @Test fun anActionIsAccessibleEvenWhenItsPluginProvidesNoBadge() = exerciseDrawer(secondHasStatus = false)

    private fun exerciseDrawer(secondHasStatus: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val second = first.copy(serviceClassName = "com.orbising.orbis.plugintest.SecondPageFixtureService")
        val hostId = if (secondHasStatus) "multi-two-status-fixture" else "multi-no-status-fixture"
        val app = IsolatedTestApplication(context, prefix = hostId)
        val controller = PluginControl(app, hostId, discover = { listOf(first, second) })
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put(hostId, model)
        val state = RemoteState(hostId = hostId, deviceId = "multi-device", e2eReady = false).withReadyHomeFixture()
        val pairing = HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "multi-device", credential = "test-only")
        try {
            compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
            } } } }
            compose.runOnIdle { controller.connect(first); controller.connect(second) }
            compose.waitUntil(20_000) {
                listOf(first, second).all { candidate -> controller.session(candidate)?.let {
                    it.snapshot != null && it.hostActionSnapshot != null && it.feedback.canStart
                } == true }
            }
            compose.onNodeWithContentDescription("打开会话目录").performClick()
            compose.onNodeWithText("测试状态").assertIsDisplayed()
            if (secondHasStatus) compose.onNodeWithText("第二插件状态").assertIsDisplayed()
            else compose.onNodeWithText("第二插件状态").assertDoesNotExist()

            compose.onNodeWithText("测试操作").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { controller.session(first)?.feedback?.state == PluginOperationState.Succeeded }
            assertTrue(controller.session(second)?.feedback?.state == null)
            compose.onNodeWithText("第二插件操作").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { controller.session(second)?.feedback?.state == PluginOperationState.Succeeded }
        } finally {
            compose.runOnIdle { controller.disable(first); controller.disable(second); controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }

    @Test fun disablingOnePluginKeepsTheOtherConnected() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = IsolatedTestApplication(context, "multiple-plugins")
        val first = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val second = first.copy(serviceClassName = "com.orbising.orbis.plugintest.SecondPageFixtureService")
        val controller = PluginControl(app, "multiple-plugin-fixture", discover = { listOf(first, second) })
        try {
            compose.runOnIdle { controller.connect(first); controller.connect(second) }
            compose.waitUntil(20_000) { controller.session(first)?.navigationSnapshot != null && controller.session(second)?.navigationSnapshot != null }
            compose.runOnIdle {
                assertTrue(controller.activeCount >= 2)
                controller.disable(first)
                assertTrue(controller.session(second)?.connected == true)
                assertTrue(controller.session(second)?.navigationSnapshot != null)
            }
        } finally {
            compose.runOnIdle { controller.disable(first); controller.disable(second); controller.close() }
            app.disposePluginTestStorage()
        }
    }

    @Test fun onlineHostKeepsInlinePluginActionAndFailureVisible() =
        exerciseOnlineFeedback(multiple = false, failureMode = "sync-failure", expected = PluginOperationState.Failed)

    @Test fun onlineHostKeepsInlinePluginActionAndUnknownResultVisible() =
        exerciseOnlineFeedback(multiple = false, failureMode = "timeout", expected = PluginOperationState.Unknown)

    @Test fun onlineHostKeepsMultiplePluginActionsAndFailureVisible() =
        exerciseOnlineFeedback(multiple = true, failureMode = "sync-failure", expected = PluginOperationState.Failed)

    @Test fun onlineHostKeepsMultiplePluginActionsAndUnknownResultVisible() =
        exerciseOnlineFeedback(multiple = true, failureMode = "timeout", expected = PluginOperationState.Unknown)

    @Test fun multiplePluginResultsCanBeScrolledToAndAcknowledged() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = IsolatedTestApplication(context, "multiple-plugin-results")
        val first = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val second = first.copy(serviceClassName = "com.orbising.orbis.plugintest.SecondPageFixtureService")
        val hostId = "multiple-results-${UUID.randomUUID()}"
        val controller = PluginControl(app, hostId, discover = { listOf(first, second) })
        try {
            compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) {
                CompositionLocalProvider(LocalPluginControl provides controller) {
                    Box(Modifier.width(320.dp)) { PluginHostFeedback(hostId) }
                }
            } } }
            compose.runOnIdle { controller.connect(first); controller.connect(second) }
            compose.waitUntil(20_000) {
                listOf(first, second).all { controller.session(it)?.feedback?.canStart == true }
            }
            compose.runOnIdle {
                controller.session(first)!!.feedback.update(PluginOperationState.Failed)
                controller.session(second)!!.feedback.update(PluginOperationState.Unknown)
            }
            compose.waitUntil(10_000) {
                controller.session(first)?.feedback?.state == PluginOperationState.Failed &&
                    controller.session(second)?.feedback?.state == PluginOperationState.Unknown
            }

            val unknownButton = compose.onNodeWithText("已检查")
            val beforeScroll = unknownButton.getUnclippedBoundsInRoot().top
            unknownButton.performScrollTo().assertIsDisplayed()
            assertTrue("The second result must be reachable by scrolling", unknownButton.getUnclippedBoundsInRoot().top < beforeScroll)
            unknownButton.performClick()
            compose.waitUntil(10_000) { controller.session(second)?.feedback?.state == null }
            compose.onNodeWithText("知道了").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { controller.session(first)?.feedback?.state == null }
        } finally {
            compose.runOnIdle { controller.close() }
            app.disposePluginTestStorage()
        }
    }

    private fun exerciseOnlineFeedback(multiple: Boolean, failureMode: String, expected: PluginOperationState) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = IsolatedTestApplication(context, "online-plugin-feedback")
        val first = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val second = first.copy(serviceClassName = "com.orbising.orbis.plugintest.SecondPageFixtureService")
        val candidates = if (multiple) listOf(first, second) else listOf(first)
        val hostId = "online-$failureMode-${UUID.randomUUID()}"
        val controller = PluginControl(app, hostId, discover = { candidates })
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put(hostId, model)
        val state = RemoteState(hostId = hostId, deviceId = "online-device", e2eReady = true).withReadyHomeFixture()
        val pairing = HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "online-device", credential = "test-only")
        try {
            compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
            } } } }
            compose.runOnIdle { candidates.forEach(controller::connect) }
            compose.waitUntil(20_000) {
                candidates.all { candidate -> controller.session(candidate)?.let {
                    it.snapshot != null && it.hostActionSnapshot != null && it.feedback.canStart
                } == true }
            }
            compose.onNodeWithContentDescription("打开会话目录").performClick()
            compose.onNodeWithText("已连接").assertIsDisplayed()
            compose.onNodeWithText("测试操作").assertIsDisplayed().assertIsEnabled().performClick()
            if (multiple) compose.onNodeWithText("第二插件操作").assertIsDisplayed().assertIsEnabled()

            compose.waitUntil(15_000) { controller.session(first)?.feedback?.state == expected }
            compose.onNodeWithText("已连接").assertIsDisplayed()
            compose.onNodeWithTag("plugin-operation-status").assertIsDisplayed().assertTextEquals(pluginOperationText(expected))
            val action = compose.onNodeWithText("测试操作").assertIsDisplayed()
            if (expected == PluginOperationState.Unknown) action.assertIsNotEnabled() else action.assertIsEnabled()
            if (multiple) compose.onNodeWithText("第二插件操作").assertIsDisplayed().assertIsEnabled()

            compose.onNodeWithText(if (expected == PluginOperationState.Unknown) "已检查" else "知道了").performClick()
            compose.waitUntil(10_000) { controller.session(first)?.feedback?.state == null }
            compose.onNodeWithTag("plugin-operation-status").assertDoesNotExist()
        } finally {
            compose.runOnIdle { controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }
}
