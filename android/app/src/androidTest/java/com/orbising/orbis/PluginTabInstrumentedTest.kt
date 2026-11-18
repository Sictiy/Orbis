package com.orbising.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.orbising.orbis.plugin.sdk.PluginServiceDiscovery
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream

class PluginTabInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val pluginStorage = IsolatedTestApplication(ApplicationProvider.getApplicationContext<android.content.Context>(), "plugin-tab-storage")
    private val controllers = mutableListOf<PluginControl>()
    private fun track(controller: PluginControl): PluginControl = controller.also { controllers.add(it) }

    @After fun cleanupPluginStorage() {
        compose.runOnIdle { controllers.forEach { it.close() } }
        pluginStorage.disposePluginTestStorage()
    }

    @Test fun storedHostSelectionIsAvailableBeforeRemoteStateHydrates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = IsolatedTestApplication(context, prefix = "plugin-startup-host")
        val hostId = "plugin-startup-selected-host"
        HostPairingStore(app).add(HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "fixture-device", credential = "test-only"))
        val owner = ViewModelStore()
        try {
            val model = RemoteViewModel(app)
            owner.put("plugin-startup-host", model)
            assertTrue(model.selectedHostId.value == hostId)
        } finally { owner.clear(); app.dispose() }
    }

    @Test fun approvedPluginConnectsOnControllerRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "plugin-startup-restore-fixture"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val first = track(PluginControl(pluginStorage, hostId))
        compose.runOnIdle { first.connect(candidate) }
        compose.waitUntil(20_000) { first.session(candidate)?.connected == true }
        compose.runOnIdle { first.close() }
        val restored = track(PluginControl(pluginStorage, hostId))
        try {
            compose.waitUntil(20_000) { restored.session(candidate)?.connected == true && restored.session(candidate)?.navigationSnapshot != null }
            compose.runOnIdle { assertTrue(restored.isApproved(candidate)) }
        } finally { compose.runOnIdle { restored.disable(candidate); restored.close() } }
    }

    @Test fun restorationRetriesDiscoveryAndReconnectsAfterDisconnect() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "plugin-retry-restore-fixture"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val first = track(PluginControl(pluginStorage, hostId))
        compose.runOnIdle { first.connect(candidate) }
        compose.waitUntil(20_000) { first.session(candidate)?.connected == true }
        compose.runOnIdle { first.close() }
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val restored = track(PluginControl(pluginStorage, hostId, discover = {
            when (attempts.getAndIncrement()) {
                0 -> throw IllegalStateException("Temporary discovery failure")
                1 -> emptyList()
                else -> PluginServiceDiscovery.find(context.packageManager)
            }
        }))
        try {
            compose.waitUntil(20_000) { restored.session(candidate)?.connected == true }
            assertTrue(attempts.get() >= 3)
            val beforeDisconnect = attempts.get()
            compose.runOnIdle { restored.session(candidate)!!.disconnect() }
            compose.waitUntil(20_000) { attempts.get() > beforeDisconnect && restored.session(candidate)?.connected == true }
        } finally { compose.runOnIdle { restored.disable(candidate); restored.close() } }
    }

    @Test fun settingsActionOpensPluginPageAndClosesSheet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "plugin-settings-page-test-host-fixture-v2"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val controller = track(PluginControl(pluginStorage, hostId))
        val app = IsolatedTestApplication(context, prefix = "plugin-settings-page")
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put("plugin-settings-page", model)
        val state = RemoteState(hostId = hostId, deviceId = "plugin-settings-device", e2eReady = true).withReadyHomeFixture()
        val pairing = HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "plugin-settings-device", credential = "test-only")
        try {
            compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
            } } } }
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.session(candidate)?.navigationSnapshot != null }
            compose.onNodeWithTag("home-tab-settings").performClick()
            compose.onNodeWithTag("settings-manage-plugins").performScrollTo().performClick()
            compose.onNodeWithText("插件管理").assertIsDisplayed()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("plugin-manage-${candidate.peer.packageName}").fetchSemanticsNodes().isNotEmpty()
            }
            Thread.sleep(500) // Capture the sheet after its native window has drawn a frame.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                FileOutputStream(File(context.getExternalFilesDir(null), "plugin-management-list-test.png")).use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            compose.onNodeWithTag("plugin-manage-${candidate.peer.packageName}").performClick()
            compose.onNodeWithTag("plugin-detail-status").assertIsDisplayed()
            compose.onNodeWithText(candidate.peer.packageName).assertDoesNotExist()
            compose.onNodeWithTag("plugin-disconnect").performClick()
            compose.onNodeWithText("移除插件？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("打开测试页面").assertDoesNotExist()
            compose.onNodeWithTag("orbis-sheet-scrim").performClick()
            compose.onNodeWithText("测试插件").performClick()
            compose.waitUntil(10_000) { controller.session(candidate)?.embeddedPageVisible == true }
            compose.onNodeWithTag("plugin-embedded-surface").assertIsDisplayed()
            compose.onNodeWithTag("orbis-choice-sheet").assertDoesNotExist()
        } finally {
            compose.runOnIdle { controller.disable(candidate); controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }

    @Test fun hostContributionsAppearInTheDrawer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "plugin-drawer-test-host-fixture-v2"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val controller = track(PluginControl(pluginStorage, hostId))
        val app = IsolatedTestApplication(context, prefix = "plugin-drawer")
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put("plugin-drawer", model)
        val state = RemoteState(hostId = hostId, deviceId = "plugin-drawer-device", e2eReady = false).withReadyHomeFixture()
        val pairing = HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "plugin-drawer-device", credential = "test-only")
        try {
            compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
            } } } }
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.activeSessions.any { it.snapshot != null && it.hostActionSnapshot != null && it.feedback.canStart } }
            compose.onNodeWithContentDescription("打开会话目录").performClick()
            compose.onNodeWithTag("plugin-host-content").assertDoesNotExist()
            compose.onNodeWithText("测试状态").assertIsDisplayed()
            compose.onNodeWithTag("plugin-host-action").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { controller.session(candidate)?.feedback?.state == PluginOperationState.Waiting }
            compose.waitUntil(10_000) { controller.session(candidate)?.feedback?.state == PluginOperationState.Succeeded }
            compose.runOnIdle { controller.session(candidate)!!.feedback.acknowledge() }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            FileOutputStream(File(context.getExternalFilesDir(null), "plugin-host-drawer-test.png")).use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            // Disconnect only after an actual Binder action reaches the waiting state.
            compose.onNodeWithTag("plugin-host-action").performClick()
            compose.waitUntil(10_000) { controller.session(candidate)?.feedback?.state == PluginOperationState.Waiting }
            compose.runOnIdle { controller.session(candidate)!!.disconnect() }
            compose.onNodeWithTag("plugin-operation-status").assertIsDisplayed().assertTextEquals("结果未知，请先检查操作状态")
            val unknown = compose.onRoot().captureToImage().asAndroidBitmap()
            FileOutputStream(File(context.getExternalFilesDir(null), "plugin-operation-unknown-test.png")).use {
                assertTrue(unknown.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            compose.runOnIdle { controller.refresh() }
            compose.waitUntil(20_000) { controller.session(candidate)?.connected == true && controller.session(candidate)?.hostActionSnapshot != null }
            compose.onNodeWithTag("plugin-operation-status").assertIsDisplayed()
            compose.onNodeWithTag("plugin-host-action").assertIsNotEnabled()
            compose.onNodeWithText("已检查").performClick()
            compose.waitUntil(10_000) { controller.session(candidate)?.feedback?.state == null }
            compose.onNodeWithTag("plugin-operation-status").assertDoesNotExist()
        } finally {
            compose.runOnIdle { controller.disable(candidate); controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }

    @Test fun pluginProvidedStatusUpdatesInTheDrawerWithoutHostActionSpecialCases() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "plugin-timed-operation-fixture-${java.util.UUID.randomUUID()}"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val controller = track(PluginControl(pluginStorage, hostId))
        val app = IsolatedTestApplication(context, prefix = "plugin-status-countdown")
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put("plugin-status-countdown", model)
        val state = RemoteState(hostId = hostId, deviceId = "status-test-device", e2eReady = false).withReadyHomeFixture()
        val pairing = HostPairing(hostId, "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "status-test-device", credential = "test-only")
        try {
            compose.setContent { OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
            } } } }
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.activeSessions.any { it.snapshot != null && it.hostActionSnapshot != null && it.feedback.canStart } }
            compose.onNodeWithContentDescription("打开会话目录").performClick()
            compose.onNodeWithTag("plugin-host-status", useUnmergedTree = true).assertTextEquals("测试状态")
            compose.onNodeWithTag("plugin-host-action").assertIsEnabled().performClick()
            compose.waitUntil(10_000) {
                (controller.session(candidate)?.snapshot?.contributions?.firstOrNull()?.component as?
                    com.orbising.orbis.plugin.sdk.PluginUiComponent.StatusBadge)?.label?.startsWith("处理中") == true
            }
            val first = (controller.session(candidate)!!.snapshot!!.contributions.first().component as
                com.orbising.orbis.plugin.sdk.PluginUiComponent.StatusBadge).label
            compose.onNodeWithTag("plugin-host-status", useUnmergedTree = true).assertTextContains("处理中", substring = true)
            compose.onNodeWithTag("plugin-host-action").assertIsNotEnabled()
            compose.onNodeWithTag("plugin-operation-status").assertDoesNotExist()
            compose.waitUntil(10_000) {
                (controller.session(candidate)?.snapshot?.contributions?.firstOrNull()?.component as?
                    com.orbising.orbis.plugin.sdk.PluginUiComponent.StatusBadge)?.label?.let { it != first } == true
            }
            compose.runOnIdle { controller.session(candidate)!!.disconnect() }
            compose.onNodeWithTag("plugin-operation-status").assertIsDisplayed().assertTextEquals("结果未知，请先检查操作状态")
            compose.onNodeWithText("已检查").performClick()
            compose.waitUntil(10_000) { controller.session(candidate)?.feedback?.state == null }
            compose.onNodeWithTag("plugin-operation-status").assertDoesNotExist()
        } finally {
            compose.runOnIdle { controller.disable(candidate); controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }

    @Test fun disablingDuringRestoreCancelsPendingReconnect() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostId = "plugin-reconnect-test-host-fixture-v2"
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val initial = track(PluginControl(pluginStorage, hostId))
        compose.runOnIdle { initial.connect(candidate) }
        compose.waitUntil(20_000) { initial.session(candidate)?.connected == true }
        compose.runOnIdle { initial.close() }
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val discovered = java.util.concurrent.CountDownLatch(1)
        val controller = track(PluginControl(pluginStorage, hostId, discover = {
            started.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            PluginServiceDiscovery.find(context.packageManager).also { discovered.countDown() }
        }))
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            compose.runOnIdle { controller.disable(candidate) }
            release.countDown()
            assertTrue(discovered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // A subsequent search runs on the same worker; its main-thread result proves
            // the older restoration callback has also been delivered.
            compose.runOnIdle { controller.search() }
            compose.waitUntil(20_000) { controller.message != PluginNotice.Searching }
            compose.runOnIdle {
                assertTrue(controller.session(candidate)?.connected != true)
                assertTrue(!controller.isApproved(candidate))
            }
        } finally {
            release.countDown()
            compose.runOnIdle { controller.disable(candidate); controller.close() }
        }
    }

    @Test fun lostActionReplyLeavesUnknownResult() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val controller = track(PluginControl(pluginStorage, "plugin-timeout-test-host-fixture-v2"))
        try {
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.session(candidate)?.hostActionSnapshot != null && controller.session(candidate)?.feedback?.canStart == true }
            compose.runOnIdle {
                val session = controller.session(candidate)!!
                val snapshot = session.hostActionSnapshot!!
                session.performHostAction(snapshot.handle("fixture.action")!!)
            }
            compose.waitUntil(30_000) { controller.session(candidate)?.feedback?.state == PluginOperationState.Unknown }
            compose.runOnIdle { assertTrue(!controller.session(candidate)!!.feedback.canStart) }
        } finally {
            compose.runOnIdle { controller.disable(candidate); controller.close() }
        }
    }

    @Test fun installedPluginIsAnOrdinaryRootTab() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val candidate = PluginServiceDiscovery.find(context.packageManager).single { it.peer.packageName == "com.orbising.orbis.plugintest" }
        val controller = track(PluginControl(pluginStorage, "plugin-ui-test-host-fixture-v2"))
        val app = IsolatedTestApplication(context, prefix = "plugin-tab")
        val owner = ViewModelStore()
        val model = RemoteViewModel(app)
        owner.put("plugin-tab", model)
        val state = RemoteState(hostId = "plugin-ui-test-host-fixture-v2", deviceId = "plugin-ui-test-device", e2eReady = true).withReadyHomeFixture()
        val pairing = HostPairing("plugin-ui-test-host-fixture-v2", "Fixture computer", "test-public", "test-root",
            relayUrl = "wss://relay.example", deviceId = "plugin-ui-test-device", credential = "test-only")
        try {
            compose.setContent {
                OrbisTheme { IsolatedPluginUiContext(app) { CompositionLocalProvider(LocalPluginControl provides controller) {
                    RemoteApp(state, listOf(HostSnapshot(pairing, state)), null, false, model)
                } } }
            }
            compose.runOnIdle { controller.connect(candidate) }
            compose.waitUntil(20_000) { controller.activeSessions.any { it.navigationSnapshot != null } }
            compose.onNodeWithTag("plugin-navigation").assertIsDisplayed().assertIsNotSelected().performClick()
            compose.waitUntil(10_000) { controller.activeSessions.any { it.embeddedPageVisible } }
            compose.onNodeWithTag("plugin-navigation").assertIsSelected()
            compose.onNodeWithTag("home-tab-chat").assertIsNotSelected()
            compose.onNodeWithTag("home-tabs").assertIsDisplayed()
            compose.onNodeWithTag("plugin-embedded-surface").assertIsDisplayed()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val screenshot = File(context.getExternalFilesDir(null), "plugin-tab-test.png")
            FileOutputStream(screenshot).use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            compose.onNodeWithTag("home-tab-chat").performClick().assertIsSelected()
            compose.onNodeWithTag("plugin-navigation").assertIsNotSelected()
        } finally {
            compose.runOnIdle { controller.disable(candidate); controller.close(); owner.clear() }
            app.disposePluginTestStorage()
        }
    }
}
