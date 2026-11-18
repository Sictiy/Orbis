package com.orbising.orbis

import android.content.Context
import android.content.ComponentName

/** Retains operation feedback across Host switches without holding idle bindings. */
internal class PluginControlPool(private val context: Context) : AutoCloseable {
    private val controls = mutableMapOf<String?, PluginControl>()
    private val navigationFeedback = PluginNavigationFeedbackRegistry(context, ::reconcileInactive)
    private val activations = PluginActivationCoordinator()
    private var selected: String? = null
    private var hasSelection = false

    init {
        // Restore navigation gates even when their original Host is not selected.
        PluginApprovalStore(context).allApprovals().forEach(navigationFeedback::forService)
    }

    fun forHost(hostId: String?): PluginControl {
        hasSelection = true
        selected = hostId
        reconcileInactive()
        return controls.getOrPut(hostId) {
            PluginControl(context, hostId, onRemoved = ::removed, onApprovalsChanged = ::reloadApprovals,
                onFeedbackChanged = ::reconcileInactive,
                navigationFeedbackRegistry = navigationFeedback, activationCoordinator = activations)
        }.also { it.resumeConnections() }
    }

    private fun reconcileInactive() {
        if (!hasSelection) return
        controls.toMap().forEach { (hostId, control) ->
            if (hostId == selected || control.hasPendingOperation) return@forEach
            if (control.hasFeedback) control.suspendConnections()
            else {
                controls.remove(hostId)
                control.close()
            }
        }
    }

    private fun removed(component: ComponentName) {
        controls.values.toList().forEach { it.removedFromOtherHost(component) }
    }

    private fun reloadApprovals() {
        controls.values.toList().forEach { it.reloadApprovals() }
    }

    /** Revoke this Host's approvals and invalidate its binding and pending callbacks. */
    fun removeHost(hostId: String) {
        controls.remove(hostId)?.close()
        PluginApprovalStore(context).removeHost(hostId)
        reloadApprovals()
        if (hasSelection && selected == hostId) {
            hasSelection = false
            selected = null
        }
    }

    override fun close() {
        controls.values.forEach { it.close() }
        controls.clear()
        hasSelection = false
    }
}
