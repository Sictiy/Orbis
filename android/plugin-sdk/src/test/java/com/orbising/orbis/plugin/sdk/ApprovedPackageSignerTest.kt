package com.orbising.orbis.plugin.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPackageSignerTest {
    @Test
    fun approvalRequiresBothPackageAndCurrentCertificate() {
        val approved = ApprovedPackageSigner("com.example.plugin", "a".repeat(64))
        assertTrue(approved.matches(BinderPeerIdentity(1234, "com.example.plugin", "a".repeat(64))))
        assertFalse(approved.matches(BinderPeerIdentity(1234, "com.example.other", "a".repeat(64))))
        assertFalse(approved.matches(BinderPeerIdentity(1234, "com.example.plugin", "b".repeat(64))))
    }
}
