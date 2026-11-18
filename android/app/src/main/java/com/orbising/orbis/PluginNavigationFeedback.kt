package com.orbising.orbis

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.orbising.orbis.plugin.sdk.ApprovedPluginService

internal data class PluginNavigationFeedbackEntry(
    val approval: ApprovedPluginService,
    val feedback: PluginOperationFeedback,
)

/** One global navigation gate per signed service, shared by all Host bindings. */
internal class PluginNavigationFeedbackRegistry(context: Context, private val onChange: () -> Unit) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val feedback = mutableStateMapOf<ApprovedPluginService, PluginOperationFeedback>()

    val entries: List<PluginNavigationFeedbackEntry>
        get() = feedback.map { (approval, value) -> PluginNavigationFeedbackEntry(approval, value) }

    fun forService(approval: ApprovedPluginService): PluginOperationFeedback = feedback.getOrPut(approval) {
        PluginOperationFeedback(
            PluginPendingOperationStore(appContext, null, approval, "orbis_plugin_navigation_pending_v1"),
            io = PluginPendingOperationIo.executor,
            dispatch = { handler.post(it) },
            onChange = { handler.post { onChange() } },
        )
    }
}
