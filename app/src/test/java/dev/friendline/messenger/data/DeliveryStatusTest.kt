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

        assertEquals("Sent", deliveryStatusLabel(expected, emptySet()))
        val firstAcknowledgement = setOf("member-a")
        assertEquals("Delivered to 1 of 2", deliveryStatusLabel(expected, firstAcknowledgement))
        val secondAcknowledgement = firstAcknowledgement + "member-b"
        assertEquals("Delivered to all 2", deliveryStatusLabel(expected, secondAcknowledgement))
    }

    @Test
    fun acknowledgementsFromOutsideTheExpectedGroupDoNotAdvanceDelivery() {
        val expected = setOf("member-a", "member-b")

        assertEquals("Sent", deliveryStatusLabel(expected, setOf("outside-member")))
        assertEquals("Delivered to 1 of 2", deliveryStatusLabel(expected, setOf("member-a", "outside-member")))
    }

    @Test
    fun legacyDeliveredReceiptsStillWorkForDirectMessages() {
        assertEquals("Delivered", deliveryStatusLabel(setOf("peer"), emptySet(), legacyDelivered = true))
        assertEquals("Sent", deliveryStatusLabel(setOf("peer-a", "peer-b"), emptySet(), legacyDelivered = true))
    }
}
