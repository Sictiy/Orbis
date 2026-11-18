package com.orbising.orbis.plugin.sdk

import android.os.Bundle

/** Generic, opaque display colors supplied by the host to an embedded page. */
data class EmbeddedPageTheme(
    val dark: Boolean,
    val base: Int,
    val lift: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val success: Int,
    val danger: Int,
    val highlight: Int,
    val shadow: Int,
    val scrim: Int,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("dark", dark)
        putInt("base", base); putInt("lift", lift); putInt("ink", ink); putInt("muted", muted)
        putInt("accent", accent); putInt("success", success); putInt("danger", danger)
        putInt("highlight", highlight); putInt("shadow", shadow); putInt("scrim", scrim)
    }

    companion object {
        private val keys = setOf("dark", "base", "lift", "ink", "muted", "accent", "success", "danger", "highlight", "shadow", "scrim")
        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle?): EmbeddedPageTheme? = runCatching {
            if (bundle == null || bundle.keySet() != keys || bundle.get("dark") !is Boolean ||
                keys.filter { it != "dark" }.any { bundle.get(it) !is Int }) return null
            EmbeddedPageTheme(bundle.getBoolean("dark"), bundle.getInt("base"), bundle.getInt("lift"),
                bundle.getInt("ink"), bundle.getInt("muted"), bundle.getInt("accent"), bundle.getInt("success"),
                bundle.getInt("danger"), bundle.getInt("highlight"), bundle.getInt("shadow"), bundle.getInt("scrim"))
        }.getOrNull()
    }
}
