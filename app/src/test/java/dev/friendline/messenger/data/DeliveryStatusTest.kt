package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryStatusTest {
    @Test
    fun oneToOneKeepsDeliveredSemantics() {
        assertEquals("Sent", deliveryStatusLabel(setOf("peer"), emptySet()))
        assertEquals("Delivered", deliveryStatusLabel(setOf("peer"), setOf("peer")))
    }

    @Test
    fun groupAcknowledgementRemainsPartialUntilEveryExpectedMemberAcknowledges() {
        // A room with the sender and two other members has two recipients.
        val roomMembers = setOf("self", "member-a", "member-b")
        val expected = roomMembers - "self"
        var acknowledged = emptySet<String>()

        assertEquals("Sent", deliveryStatusLabel(expected, emptySet()))
        acknowledged = checkNotNull(addDeliveryAcknowledgement(expected, acknowledged, "member-a"))
        assertEquals("Delivered to 1 of 2", deliveryStatusLabel(expected, acknowledged))
        acknowledged = checkNotNull(addDeliveryAcknowledgement(expected, acknowledged, "member-b"))
        assertEquals("Delivered to all 2", deliveryStatusLabel(expected, acknowledged))
    }

    @Test
    fun acknowledgementsFromOutsideTheExpectedGroupDoNotAdvanceDelivery() {
        val expected = setOf("member-a", "member-b")

        assertEquals(null, addDeliveryAcknowledgement(expected, emptySet(), "self"))
        assertEquals(null, addDeliveryAcknowledgement(expected, emptySet(), "outside-member"))
        val afterMemberAcknowledges = checkNotNull(addDeliveryAcknowledgement(expected, emptySet(), "member-a"))
        assertEquals(afterMemberAcknowledges, addDeliveryAcknowledgement(expected, afterMemberAcknowledges, "member-a"))
        assertEquals(null, addDeliveryAcknowledgement(expected, afterMemberAcknowledges, "outside-member"))
        assertEquals("Delivered to 1 of 2", deliveryStatusLabel(expected, afterMemberAcknowledges))
    }

    @Test
    fun membershipChangesAfterEnqueueDoNotChangeTheOutgoingEventsRecipientSnapshot() {
        val membersAtEnqueue = mutableSetOf("self", "member-a", "member-b")
        val expected = snapshotDeliveryRecipients(membersAtEnqueue, "self")

        // One original recipient leaves and a new member joins after the send.
        membersAtEnqueue.remove("member-b")
        membersAtEnqueue.add("member-c")

        assertEquals(setOf("member-a", "member-b"), expected)
        assertEquals(null, addDeliveryAcknowledgement(expected, emptySet(), "member-c"))
        val acknowledged = checkNotNull(addDeliveryAcknowledgement(expected, emptySet(), "member-a"))
        assertEquals("Delivered to 1 of 2", deliveryStatusLabel(expected, acknowledged))
        val fullyAcknowledged = checkNotNull(addDeliveryAcknowledgement(expected, acknowledged, "member-b"))
        assertEquals("Delivered to all 2", deliveryStatusLabel(expected, fullyAcknowledged))
    }

    @Test
    fun provisionalAcknowledgementsAreRevalidatedAgainstThePromotedSnapshot() {
        val frozenExpected = setOf("member-a", "member-b")
        val provisional = setOf("member-a", "member-c")

        assertEquals(
            setOf("member-a"),
            validateProvisionalDeliveryAcknowledgements(frozenExpected, provisional),
        )
    }

    @Test
    fun provisionalOnlyAckRowsCanBeReplacedForAnObservedIncomingEvent() {
        assertEquals(true, isProvisionalOnlyDeliveryAckRecord("none", false, setOf("peer")))
        assertEquals(false, isProvisionalOnlyDeliveryAckRecord("pending", false, setOf("peer")))
        assertEquals(false, isProvisionalOnlyDeliveryAckRecord("none", true, setOf("peer")))
        assertEquals(false, isProvisionalOnlyDeliveryAckRecord("none", false, emptySet()))
    }

    @Test
    fun legacyDeliveredReceiptsStillWorkForDirectMessages() {
        assertEquals("Delivered", deliveryStatusLabel(setOf("peer"), emptySet(), legacyDelivered = true))
        assertEquals("Sent", deliveryStatusLabel(setOf("peer-a", "peer-b"), emptySet(), legacyDelivered = true))
    }
}
