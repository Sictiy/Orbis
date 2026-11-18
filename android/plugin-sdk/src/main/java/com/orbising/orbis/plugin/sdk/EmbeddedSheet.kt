package com.orbising.orbis.plugin.sdk

import android.os.Bundle

/** Presentation only: all actions remain opaque, page-scoped plugin capabilities. */
data class EmbeddedSheetItem(
    val kind: String,
    val title: String,
    val detail: String = "",
    val action: String = "",
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val primary: Boolean = false,
)

data class EmbeddedSheet(val id: String, val title: String, val items: List<EmbeddedSheetItem>, val dismissible: Boolean = true) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("id", id)
        putString("title", title)
        putBoolean("dismissible", dismissible)
        putStringArrayList("kinds", ArrayList(items.map { it.kind }))
        putStringArrayList("titles", ArrayList(items.map { it.title }))
        putStringArrayList("details", ArrayList(items.map { it.detail }))
        putStringArrayList("actions", ArrayList(items.map { it.action }))
        putIntArray("flags", items.map { (if (it.selected) 1 else 0) or (if (!it.enabled) 2 else 0) or
            (if (it.danger) 4 else 0) or (if (it.primary) 8 else 0) }.toIntArray())
    }

    companion object {
        const val MAX_ITEMS = 60
        private val token = Regex("^[A-Za-z0-9_-]{1,64}$")
        private fun safeText(text: String, limit: Int) = text.length <= limit &&
            text.none { it.isISOControl() && it != '\n' }

        /** Reject malformed or oversized content before exposing it to the host UI. */
        fun fromBundle(value: Bundle): EmbeddedSheet? = runCatching {
            if (value.keySet() != setOf("id", "title", "dismissible", "kinds", "titles", "details", "actions", "flags")) return null
            val id = value.getString("id") ?: return null
            val title = value.getString("title") ?: return null
            val kinds = value.getStringArrayList("kinds") ?: return null
            val titles = value.getStringArrayList("titles") ?: return null
            val details = value.getStringArrayList("details") ?: return null
            val actions = value.getStringArrayList("actions") ?: return null
            val flags = value.getIntArray("flags") ?: return null
            val count = kinds.size
            if (!token.matches(id) || title.isBlank() || !safeText(title, 256) || count > MAX_ITEMS ||
                titles.size != count || details.size != count || actions.size != count || flags.size != count) return null
            var characters = title.length
            val items = (0 until count).map { index ->
                val kind = kinds[index]
                val itemTitle = titles[index]
                val detail = details[index]
                val action = actions[index]
                val flag = flags[index]
                characters += itemTitle.length + detail.length
                if (kind !in setOf("text", "heading", "choice", "button") || itemTitle.isBlank() ||
                    !safeText(itemTitle, 512) || !safeText(detail, 256) || flag !in 0..15 ||
                    (kind in setOf("choice", "button") && !token.matches(action)) ||
                    (kind !in setOf("choice", "button") && action.isNotEmpty())) return null
                EmbeddedSheetItem(kind, itemTitle, detail, action, flag and 1 != 0, flag and 2 == 0,
                    flag and 4 != 0, flag and 8 != 0)
            }
            if (characters > 12_000 || items.filter { it.action.isNotEmpty() }.map { it.action }.toSet().size !=
                items.count { it.action.isNotEmpty() }) return null
            EmbeddedSheet(id, title, items, value.getBoolean("dismissible"))
        }.getOrNull()
    }
}
