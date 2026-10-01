package dev.friendline.messenger.ui

import dev.friendline.messenger.data.DeviceVerificationStatus
import dev.friendline.messenger.data.DeviceVerificationUiState
import dev.friendline.messenger.data.PeerTrustStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerVerificationActionPolicyTest {
    private val peer = "@bob:example.org"

    @Test
    fun verifiedPeerTrustHidesTheActionAfterRefresh() {
        assertFalse(shouldShow(trust = PeerTrustStatus.VERIFIED))
    }

    @Test
    fun verifiedFlowHidesTheActionForItsPeerBeforeTrustRefreshFinishes() {
        assertFalse(shouldShow(verification = verification(peer, DeviceVerificationStatus.VERIFIED)))
        assertTrue(shouldShow(verification = verification("@carol:example.org", DeviceVerificationStatus.VERIFIED)))
    }

    @Test
    fun activeFlowHidesTheActionButFailedAndCancelledFlowsAllowRetry() {
        assertFalse(shouldShow(verification = verification(peer, DeviceVerificationStatus.COMPARING)))
        assertTrue(shouldShow(verification = verification(peer, DeviceVerificationStatus.FAILED)))
        assertTrue(shouldShow(verification = verification(peer, DeviceVerificationStatus.CANCELLED)))
    }

    @Test
    fun actionRequiresAnEncryptedOneToOneConversation() {
        assertTrue(shouldShow())
        assertFalse(shouldShow(encrypted = false))
        assertFalse(shouldShow(group = true))
        assertFalse(shouldShow(peerUserId = null))
    }

    private fun shouldShow(
        encrypted: Boolean = true,
        group: Boolean = false,
        peerUserId: String? = peer,
        trust: PeerTrustStatus = PeerTrustStatus.UNVERIFIED,
        verification: DeviceVerificationUiState? = null,
    ) = PeerVerificationActionPolicy.shouldShowVerifyAction(
        isEncrypted = encrypted,
        isGroup = group,
        currentPeerUserId = peerUserId,
        peerTrust = trust,
        verification = verification,
    )

    private fun verification(userId: String, status: DeviceVerificationStatus) =
        DeviceVerificationUiState(peerUserId = userId, status = status)
}
