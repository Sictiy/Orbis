package com.orbising.orbis.plugin.sdk

import java.util.UUID
import kotlinx.serialization.json.JsonObject

/**
 * Binding-scoped snapshots for UI the host has authenticated and authorized.
 * Changing a snapshot invalidates all of its old action handles. The owner must
 * also close this registry on Binder death, replacement or grant revocation.
 */
class PluginUiSnapshots(private val bindingId: String) {
    init { require(bindingId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))) }

    class ActionHandle internal constructor(
        val bindingId: String,
        val snapshotId: String,
        val slot: PluginUiSlot,
        val hostId: String?,
        val contributionId: String,
    )

    class Snapshot internal constructor(
        val id: String,
        val slot: PluginUiSlot,
        val hostId: String?,
        val contributions: List<PluginUiContribution>,
        private val ownerBindingId: String,
    ) {
        fun handle(contributionId: String): ActionHandle? =
            contributions.firstOrNull { it.id == contributionId && it.component.activeAction() != null }
                ?.let { ActionHandle(ownerBindingId, id, slot, hostId, contributionId) }
    }

    sealed interface PublishResult {
        data class Published(val snapshot: Snapshot) : PublishResult
        data class Invalid(val reason: String) : PublishResult
        data object Closed : PublishResult
    }

    data class ResolvedAction(val hostId: String?, val actionId: String)
    private data class Key(val slot: PluginUiSlot, val hostId: String?)

    private val snapshots = mutableMapOf<Key, Snapshot>()
    private var closed = false

    /** Invalid replacement data also invalidates the previous actionable snapshot. */
    @Synchronized
    fun publish(slot: PluginUiSlot, hostId: String?, payload: JsonObject): PublishResult {
        if (closed) return PublishResult.Closed
        val key = Key(slot, hostId)
        return when (val validation = PluginUiValidator.validate(payload, slot, hostId)) {
            is PluginUiValidation.Invalid -> {
                snapshots.remove(key)
                PublishResult.Invalid(validation.reason)
            }
            is PluginUiValidation.Valid -> {
                // Retain handles across a refresh only when the entire validated
                // presentation and action mapping are unchanged.
                val snapshot = snapshots[key]?.takeIf { it.contributions == validation.contributions }
                    ?: Snapshot(UUID.randomUUID().toString(), slot, hostId,
                        java.util.Collections.unmodifiableList(validation.contributions.toList()), bindingId)
                snapshots[key] = snapshot
                PublishResult.Published(snapshot)
            }
        }
    }

    /** Returns only enabled actions from the current snapshot of this binding. */
    @Synchronized
    fun resolve(handle: ActionHandle): ResolvedAction? {
        if (closed || handle.bindingId != bindingId) return null
        val snapshot = snapshots[Key(handle.slot, handle.hostId)] ?: return null
        if (snapshot.id != handle.snapshotId) return null
        val actionId = snapshot.contributions.firstOrNull { it.id == handle.contributionId }?.component?.activeAction() ?: return null
        return ResolvedAction(snapshot.hostId, actionId)
    }

    @Synchronized
    fun remove(slot: PluginUiSlot, hostId: String?) { snapshots.remove(Key(slot, hostId)) }

    @Synchronized
    fun close() { closed = true; snapshots.clear() }
}

private fun PluginUiComponent.activeAction(): String? = when (this) {
    is PluginUiComponent.IconAction -> actionId.takeIf { enabled }
    is PluginUiComponent.NavigationItem -> actionId
    is PluginUiComponent.SectionAction -> actionId.takeIf { enabled }
    is PluginUiComponent.StatusCard -> action?.actionId?.takeIf { action.enabled }
    is PluginUiComponent.TextRow, is PluginUiComponent.StatusBadge -> null
}
