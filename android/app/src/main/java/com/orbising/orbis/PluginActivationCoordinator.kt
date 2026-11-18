package com.orbising.orbis

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.orbising.orbis.plugin.sdk.ApprovedPluginService
import com.orbising.orbis.plugin.sdk.PluginServiceCandidate
import java.util.UUID

/** Serializes activation Activities across Hosts and retains their exact owners. */
internal class PluginActivationCoordinator {
    internal class Launch(
        val id: String,
        val intent: Intent,
        val owner: PluginControl,
        val manual: PluginUserActivation? = null,
        val restoration: PluginRestorationActivation? = null,
    )

    private var queuedManual by mutableStateOf<List<Launch>>(emptyList())
    private val blockedRestorations = mutableSetOf<ApprovedPluginService>()
    var inFlight by mutableStateOf<Launch?>(null); private set
    val hasQueuedManual: Boolean get() = queuedManual.isNotEmpty()
    val queuedManualCount: Int get() = queuedManual.size

    fun enqueueManual(owner: PluginControl, activation: PluginUserActivation, intent: Intent) {
        queuedManual = queuedManual + Launch(UUID.randomUUID().toString(), intent, owner, manual = activation)
    }

    fun canRestore(candidate: PluginServiceCandidate): Boolean = blockedRestorations.none { it.matches(candidate) }

    fun connected(approval: ApprovedPluginService) { blockedRestorations.remove(approval) }

    fun isCurrent(launch: Launch): Boolean {
        if (inFlight !== launch) return false
        launch.manual?.let { return launch.owner.isCurrentActivation(it) }
        launch.restoration?.let { return launch.owner.isCurrentRestorationActivation(it) }
        return false
    }

    /** A previously launched Activity remains owned by its original request. */
    fun beginNext(controller: PluginControl): Launch? {
        inFlight?.let { return it }
        while (queuedManual.isNotEmpty()) {
            val next = queuedManual.first()
            queuedManual = queuedManual.drop(1)
            if (!next.owner.isCurrentActivation(checkNotNull(next.manual))) continue
            inFlight = next
            return next
        }
        while (true) {
            val next = controller.nextRestorationActivation ?: return null
            if (!controller.beginRestorationActivation(next)) return null
            if (!canRestore(next.candidate)) {
                controller.finishRestorationActivation(next, succeeded = false, retry = false)
                continue
            }
            return Launch(UUID.randomUUID().toString(), next.intent, controller, restoration = next)
                .also { inFlight = it }
        }
    }

    fun finish(id: String, succeeded: Boolean, retryOnFailure: Boolean = true) {
        val launch = inFlight?.takeIf { it.id == id } ?: return
        inFlight = null
        launch.manual?.let {
            if (succeeded) launch.owner.connectAfterUserActivation(it) else launch.owner.activationFailed(it)
        }
        launch.restoration?.let {
            if (!succeeded && !retryOnFailure && launch.owner.isCurrentRestorationActivation(it)) blockedRestorations.add(ApprovedPluginService(
                it.candidate.peer.packageName, it.candidate.peer.signerSha256, it.candidate.serviceClassName))
            launch.owner.finishRestorationActivation(it, succeeded, retry = retryOnFailure)
        }
    }
}
