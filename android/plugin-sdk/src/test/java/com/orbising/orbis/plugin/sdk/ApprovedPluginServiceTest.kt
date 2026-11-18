package com.orbising.orbis.plugin.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPluginServiceTest {
    private val peer = BinderPeerIdentity(10123, "example.plugin", "a".repeat(64))
    private val candidate = PluginServiceCandidate(peer, "example.plugin.Service")
    private val approval = ApprovedPluginService(peer.packageName, peer.signerSha256, candidate.serviceClassName)

    @Test fun approvalRequiresPackageSignerAndExactService() {
        assertTrue(approval.matches(candidate))
        assertFalse(approval.matches(candidate.copy(serviceClassName = "example.plugin.OtherService")))
        assertFalse(approval.matches(candidate.copy(peer = peer.copy(packageName = "other.plugin"))))
        assertFalse(approval.matches(candidate.copy(peer = peer.copy(signerSha256 = "b".repeat(64)))))
    }

    @Test fun callbackMustMatchTheFreshlyResolvedUidToo() {
        assertTrue(approval.matchesCaller(candidate, peer))
        assertFalse(approval.matchesCaller(candidate, peer.copy(uid = 10999)))
        assertFalse(approval.matchesCaller(candidate, peer.copy(signerSha256 = "b".repeat(64))))
    }
}
