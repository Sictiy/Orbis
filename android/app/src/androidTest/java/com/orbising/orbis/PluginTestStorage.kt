package com.orbising.orbis

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit

/** Close controllers first, then drain their marker I/O before removing test storage. */
internal fun IsolatedTestApplication.disposePluginTestStorage() {
    repeat(2) {
        PluginPendingOperationIo.executor.submit {}.get(10, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    dispose()
}

/** Keep Activity/window services while routing app metadata reads to the test store. */
@Composable
internal fun IsolatedPluginUiContext(app: IsolatedTestApplication, content: @Composable () -> Unit) {
    val activityContext = LocalContext.current
    val isolated = remember(activityContext, app) {
        object : ContextWrapper(activityContext) {
            override fun getApplicationContext(): Context = app
        }
    }
    CompositionLocalProvider(LocalContext provides isolated, content = content)
}
