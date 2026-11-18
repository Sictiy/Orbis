package com.orbising.orbis

import com.orbising.orbis.plugin.sdk.EmbeddedSheet
import com.orbising.orbis.plugin.sdk.EmbeddedSheetItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedSheetPayloadInstrumentedTest {
    @Test fun acceptsBoundedPresentationAndRejectsAmbiguousOrExcessiveActions() {
        val choice = EmbeddedSheetItem("choice", "设备", "在线", "opaque-token", selected = true)
        val valid = EmbeddedSheet("sheet-token", "选择设备", listOf(choice), dismissible = false)
        assertEquals(valid, EmbeddedSheet.fromBundle(valid.toBundle()))

        assertNull(EmbeddedSheet.fromBundle(valid.copy(items = listOf(choice, choice)).toBundle()))
        assertNull(EmbeddedSheet.fromBundle(valid.copy(items = listOf(choice.copy(title = "x".repeat(513)))).toBundle()))
        assertNull(EmbeddedSheet.fromBundle(valid.copy(items = List(61) { choice.copy(action = "token-$it") }).toBundle()))
        assertNull(EmbeddedSheet.fromBundle(valid.copy(items = listOf(choice.copy(action = "device:private-id"))).toBundle()))
        assertNull(EmbeddedSheet.fromBundle(valid.toBundle().apply { putString("unexpected", "value") }))
    }
}
