package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryStatusTest {
    @Test
    fun acceptedServerResponsePromotesOnlyMatchingOwnLocalEcho() {
        val localEcho = ChatMessage(
            id = "txn-1",
            eventId = null,
            isRemote = false,
            sender = "@me:example.test",
            body = "hello",
            timestampMillis = 10L,
            isOwn = true,
            deliveryState = "Sending",
            isOptimisticTextEcho = true,
        )

        val promoted = promoteAcceptedLocalMessage(
            message = localEcho,
            transactionId = "txn-1",
            eventId = "\$event:example.test",
            deliveryState = "Sent",
        )

        assertEquals("\$event:example.test", promoted?.id)
        assertEquals("\$event:example.test", promoted?.eventId)
        assertEquals(true, promoted?.isRemote)
        assertEquals("Sent", promoted?.deliveryState)
        assertEquals("hello", promoted?.body)
        assertEquals(true, promoted?.canReply)
        assertEquals(true, promoted?.canEdit)
        assertEquals(true, promoted?.canRedact)
        assertNull(promoteAcceptedLocalMessage(localEcho, "other-txn", "\$other:example.test", "Sent"))
        assertNull(promoteAcceptedLocalMessage(localEcho.copy(isOwn = false), "txn-1", "\$event:example.test", "Sent"))
    }

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
    fun groupRecipientDetailsAreStableAndIgnoreUnexpectedAcknowledgements() {
        assertEquals(
            listOf(
                DeliveryMemberStatus("member-a", delivered = true),
                DeliveryMemberStatus("member-b", delivered = false),
            ),
            deliveryMemberStatuses(
                expectedMemberIds = setOf("member-b", "member-a"),
                acknowledgedMemberIds = setOf("member-a", "not-in-snapshot"),
            ),
        )
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
