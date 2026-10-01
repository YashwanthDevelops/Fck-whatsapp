package dev.friendline.messenger.ui

import dev.friendline.messenger.data.DeviceVerificationStatus
import dev.friendline.messenger.data.DeviceVerificationUiState
import dev.friendline.messenger.data.PeerTrustStatus

internal object PeerVerificationActionPolicy {
    fun shouldShowVerifyAction(
        isEncrypted: Boolean,
        isGroup: Boolean,
        currentPeerUserId: String?,
        peerTrust: PeerTrustStatus,
        verification: DeviceVerificationUiState?,
    ): Boolean {
        if (!isEncrypted || isGroup || currentPeerUserId.isNullOrBlank()) return false
        if (peerTrust == PeerTrustStatus.VERIFIED) return false

        val flow = verification ?: return true
        if (flow.status in ACTIVE_STATUSES) return false
        if (flow.peerUserId == currentPeerUserId && flow.status == DeviceVerificationStatus.VERIFIED) return false
        return true
    }

    private val ACTIVE_STATUSES = setOf(
        DeviceVerificationStatus.REQUESTING,
        DeviceVerificationStatus.INCOMING_REQUEST,
        DeviceVerificationStatus.WAITING_FOR_ACCEPT,
        DeviceVerificationStatus.COMPARING,
        DeviceVerificationStatus.CONFIRMING,
    )
}
