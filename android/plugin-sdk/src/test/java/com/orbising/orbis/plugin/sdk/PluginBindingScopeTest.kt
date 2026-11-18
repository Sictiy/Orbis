package com.orbising.orbis.plugin.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBindingScopeTest {
    private fun validate(raw: String) = PluginBindingScopeValidator.validate(Json.parseToJsonElement(raw).jsonObject)

    @Test fun permitsOnlyExplicitCapabilitiesAndHosts() {
        val scope = (validate("""{"grants":["ui"],"hostIds":["host-a"]}""") as PluginBindingScopeValidation.Valid).scope
        assertTrue(scope.allows(PluginCapability.UI, null))
        assertTrue(scope.allows(PluginCapability.UI, "host-a"))
        assertFalse(scope.allows(PluginCapability.UI, "host-b"))
        assertFalse(scope.allows(PluginCapability.PREFLIGHT, "host-a"))
    }

    @Test fun rejectsDuplicatesUnknownGrantsWildcardsAndExtraFields() {
        listOf(
            """{"grants":["ui","ui"],"hostIds":[]}""",
            """{"grants":["all"],"hostIds":[]}""",
            """{"grants":["ui"],"hostIds":["*"]}""",
            """{"grants":["ui"],"hostIds":["host-a","host-a"]}""",
            """{"grants":["ui"],"hostIds":[],"allowAll":true}""",
        ).forEach { assertTrue(validate(it) is PluginBindingScopeValidation.Invalid) }
    }

    @Test fun refusesOversizedHostScopes() {
        val ids = (0..PluginBindingScopeValidator.MAX_HOSTS).joinToString(",") { "\"host-$it\"" }
        assertTrue(validate("""{"grants":["ui"],"hostIds":[$ids]}""") is PluginBindingScopeValidation.Invalid)
    }
}
