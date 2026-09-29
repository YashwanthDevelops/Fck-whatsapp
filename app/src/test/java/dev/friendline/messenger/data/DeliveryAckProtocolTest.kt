package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryAckProtocolTest {
    @Test
    fun encodedReceiptRoundTripsWithoutMatchingOrdinaryMessages() {
        val eventId = "\$event:example.org"

        assertEquals(eventId, DeliveryAckProtocol.targetEventId(DeliveryAckProtocol.encode(eventId)))
        assertNull(DeliveryAckProtocol.targetEventId("A normal message"))
        assertNull(DeliveryAckProtocol.targetEventId(DeliveryAckProtocol.TEXT_PREFIX))
    }

    @Test
    fun malformedReceiptBodiesAreIgnored() {
        assertNull(DeliveryAckProtocol.targetEventId(DeliveryAckProtocol.TEXT_PREFIX + "   "))
        assertNull(DeliveryAckProtocol.targetEventId(DeliveryAckProtocol.TEXT_PREFIX + "event id"))
    }
}
