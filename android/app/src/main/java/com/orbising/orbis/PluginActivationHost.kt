package com.orbising.orbis

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Owns activation results across page and Host changes; only launches in the foreground. */
@Composable
internal fun PluginActivationHost(
    controller: PluginControl,
    resultTimeoutMs: Long = 10_000L,
    maxOutstanding: Int = 8,
) {
    require(resultTimeoutMs > 0 && maxOutstanding > 0)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val coordinator = controller.activationCoordinator
    // Each surviving id owns its own Android result key. Timed-out ids stay here
    // until a late result arrives, so it can never be delivered to a newer launch.
    var outstandingIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val inFlight = coordinator.inFlight
    val next = controller.nextRestorationActivation
    val queuedManual = coordinator.queuedManualCount

    outstandingIds.forEach { id ->
        key(id) {
            PluginActivationResultSlot(id, inFlight?.takeIf { it.id == id }, coordinator,
                lifecycleState, resultTimeoutMs) {
                outstandingIds = ArrayList(outstandingIds.filterNot { existing -> existing == id })
            }
        }
    }

    LaunchedEffect(controller, next, queuedManual, inFlight, lifecycleState, outstandingIds.size) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED) || inFlight != null) return@LaunchedEffect
        val launch = coordinator.beginNext(controller) ?: return@LaunchedEffect
        if (outstandingIds.size >= maxOutstanding) {
            // Android retains request-code mappings for launched Activities until
            // they return. Fail visibly instead of accumulating unbounded keys.
            coordinator.finish(launch.id, succeeded = false, retryOnFailure = false)
        } else outstandingIds = ArrayList(outstandingIds + launch.id)
    }
}

@Composable
private fun PluginActivationResultSlot(
    id: String,
    launch: PluginActivationCoordinator.Launch?,
    coordinator: PluginActivationCoordinator,
    lifecycleState: Lifecycle.State,
    timeoutMs: Long,
    onDone: () -> Unit,
) {
    var launched by rememberSaveable { mutableStateOf(false) }
    val currentCoordinator by rememberUpdatedState(coordinator)
    val currentOnDone by rememberUpdatedState(onDone)
    val handler = remember { Handler(Looper.getMainLooper()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        currentCoordinator.finish(id, result.resultCode == Activity.RESULT_OK)
        // Registry dispatch removes its launched marker after this callback.
        // Dispose the slot afterwards so unregister also removes its mapping.
        handler.post { currentOnDone() }
    }

    LaunchedEffect(launch, launched, lifecycleState) {
        if (launch == null) {
            // The process may have been recreated after reserving an id but before
            // actually starting its Activity. Such a slot can never have a result.
            if (!launched) currentOnDone()
            return@LaunchedEffect
        }
        if (launched || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (!coordinator.isCurrent(launch)) {
            coordinator.finish(id, succeeded = false)
            currentOnDone()
            return@LaunchedEffect
        }
        launched = true
        try { launcher.launch(launch.intent) }
        catch (_: Exception) { coordinator.finish(id, succeeded = false); currentOnDone() }
    }
    LaunchedEffect(launch, launched, lifecycleState, timeoutMs) {
        if (launch == null || !launched || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        // Time spent in the external Activity does not count against this grace period.
        delay(timeoutMs)
        coordinator.finish(id, succeeded = false, retryOnFailure = false)
    }
}
