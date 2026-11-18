package com.orbising.orbis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PluginAppPresentation(val label: String, val icon: ImageBitmap? = null)

/** Package metadata can require a system service call, so never load it during composition. */
@Composable
internal fun rememberPluginAppPresentation(packageName: String, includeIcon: Boolean = false): PluginAppPresentation {
    val context = LocalContext.current.applicationContext
    return key(context, packageName, includeIcon) {
        val presentation by produceState(PluginAppPresentation(packageName)) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = context.packageManager
                    val info = pm.getApplicationInfo(packageName, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    val icon = if (includeIcon) runCatching { pm.getApplicationIcon(info).toBitmap(64, 64).asImageBitmap() }.getOrNull() else null
                    PluginAppPresentation(label, icon)
                }.getOrDefault(PluginAppPresentation(packageName))
            }
        }
        presentation
    }
}
