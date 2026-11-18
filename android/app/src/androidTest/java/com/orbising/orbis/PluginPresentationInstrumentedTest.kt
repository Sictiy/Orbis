package com.orbising.orbis

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.PluginServiceCandidate
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PluginPresentationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlineHostShowsBothItsConnectionAndThePluginStatus() {
        withDrawer("online-status") { _, _ ->
            compose.onNodeWithText("已连接").assertIsDisplayed()
            val status = compose.onNodeWithTag("plugin-host-status", useUnmergedTree = true)
                .assertIsDisplayed().assertTextEquals("测试状态")
            val layouts = mutableListOf<TextLayoutResult>()
            status.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("The plugin status must remain readable", layouts.single().isLineEllipsized(0))
            val bounds = status.fetchSemanticsNode().boundsInRoot
            val full = status.getUnclippedBoundsInRoot()
            assertTrue("The status must not be clipped", with(compose.density) {
                bounds.width + 1 >= (full.right - full.left).toPx() &&
                    bounds.height + 1 >= (full.bottom - full.top).toPx()
            })
            compose.onNodeWithTag("plugin-host-action").assertIsEnabled()
        }
    }

    @Test fun largerTextKeepsPluginStatusAndActionVisibleAndUsable() {
        withDrawer("large-text-status", fontScale = 1.6f) { controller, candidate ->
            val status = compose.onNodeWithTag("plugin-host-status", useUnmergedTree = true)
                .assertIsDisplayed().assertTextEquals("测试状态")
            val action = compose.onNodeWithTag("plugin-host-action").assertIsDisplayed().assertIsEnabled()
            val layouts = mutableListOf<TextLayoutResult>()
            status.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("The plugin status must remain readable", layouts.single().isLineEllipsized(0))
            val statusBounds = status.fetchSemanticsNode().boundsInRoot
            val actionBounds = action.fetchSemanticsNode().boundsInRoot
            val fullStatus = status.getUnclippedBoundsInRoot()
            val fullAction = action.getUnclippedBoundsInRoot()
            assertTrue("The action should move below the status at large type", actionBounds.top >= statusBounds.bottom)
            assertTrue("The status must not be clipped", with(compose.density) {
                statusBounds.width + 1 >= (fullStatus.right - fullStatus.left).toPx() &&
                    statusBounds.height + 1 >= (fullStatus.bottom - fullStatus.top).toPx()
            })
            assertTrue("The action must not be clipped", with(compose.density) {
                actionBounds.width + 1 >= (fullAction.right - fullAction.left).toPx() &&
                    actionBounds.height + 1 >= (fullAction.bottom - fullAction.top).toPx()
            })
            action.performClick()
            compose.waitUntil(10_000) {
                controller.session(candidate)?.feedback?.state in setOf(PluginOperationState.Waiting, PluginOperationState.Succeeded)
            }
        }
    }

    @Test fun hostPanelRendersEverySupportedComponentAndRunsTheirActions() {
        withDrawer("generic-components") { controller, candidate ->
            listOf("测试文本行", "文本行详情", "测试状态标签", "测试状态卡片", "卡片详情", "区块详情")
                .forEach { text -> compose.onNodeWithText(text).performScrollTo().assertIsDisplayed() }
            compose.onNodeWithTag("plugin-host-content").assertDoesNotExist()

            val session = controller.session(candidate)!!
            for (id in listOf("fixture.card", "fixture.section")) {
                compose.onNodeWithTag("plugin-host-contribution-$id").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { session.feedback.state == PluginOperationState.Waiting }
                compose.waitUntil(10_000) { session.feedback.state == PluginOperationState.Succeeded }
            }
        }
    }

    @Test fun protocolIconsAndTonesHaveDistinctHostMappings() {
        val icons = listOf("info", "settings", "power", "refresh", "link", "more", "play", "check", "warning", "device")
        assertEquals(icons.size, icons.map { pluginIcon(it).name }.toSet().size)
        assertEquals(OrbisSymbols.Computer, pluginIcon("device"))
        for (dark in listOf(false, true)) {
            val palette = orbisPalette(dark)
            assertEquals(palette.muted, pluginToneColor("neutral", palette))
            assertEquals(palette.accent, pluginToneColor("info", palette))
            assertEquals(palette.success, pluginToneColor("success", palette))
            assertEquals(palette.warning, pluginToneColor("warning", palette))
            assertEquals(palette.danger, pluginToneColor("error", palette))
        }
    }

    private fun withDrawer(prefix: String, fontScale: Float = 1f, verify: (PluginControl, PluginServiceCandidate) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "$prefix-${UUID.randomUUID()}"
        val app = IsolatedTestApplication(context, prefix = prefix)
        val candidate = PluginServiceDiscovery.find(context.packageManager)
            .single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val controller = PluginControl(app, hostId)
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put(hostId, model)
        val state = RemoteState(hostId = hostId, deviceId = "fixture-device", e2eReady = true).withReadyHomeFixture()
        val pairing = HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "fixture-device", credential = "test-only")
        try {
            compose.setContent {
                OrbisTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                            RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
                        } }
                    }
                }
            }
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) {
                controller.session(candidate)?.let { it.snapshot != null && it.hostActionSnapshot != null && it.feedback.canStart } == true
            }
            compose.onNodeWithContentDescription("打开会话目录").performClick()
            verify(controller, candidate)
        } finally {
            compose.runOnIdle { controller.disable(candidate); controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }
}
