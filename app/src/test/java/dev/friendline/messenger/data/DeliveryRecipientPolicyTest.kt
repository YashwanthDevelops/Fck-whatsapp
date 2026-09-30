package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryRecipientPolicyTest {
    @Test
    fun deliverySnapshotCountsOnlyJoinedHumanMembersOtherThanTheSender() {
        val recipients = snapshotJoinedDeliveryRecipients(
            candidates = listOf(
                DeliveryRecipientCandidate("@self:example.org", isJoined = true, isServiceMember = false),
                DeliveryRecipientCandidate("@joined:example.org", isJoined = true, isServiceMember = false),
                DeliveryRecipientCandidate("@invited:example.org", isJoined = false, isServiceMember = false),
                DeliveryRecipientCandidate("@service:example.org", isJoined = true, isServiceMember = true),
                DeliveryRecipientCandidate("", isJoined = true, isServiceMember = false),
            ),
            ownUserId = "@self:example.org",
        )

        assertEquals(setOf("@joined:example.org"), recipients)
    }
}
