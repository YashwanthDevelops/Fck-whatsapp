package dev.friendline.messenger.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PushNotificationRoutePolicyTest {
    @Test
    fun acceptsOnlyOpaqueMatrixRoomAndEventIds() {
        val route = PushNotificationRoutePolicy.parse(
            mapOf("room_id" to "!room-123:example.org", "event_id" to "\$event-456"),
        )

        assertEquals(PushNotificationRoute("!room-123:example.org", "\$event-456"), route)
        assertNull(PushNotificationRoutePolicy.parse(mapOf("room_id" to "room-123")))
        assertNull(PushNotificationRoutePolicy.parse(mapOf("room_id" to "!room-123:example.org/path")))
    }

    @Test
    fun invalidEventIdIsDiscardedWithoutLosingSafeRoomRoute() {
        val route = PushNotificationRoutePolicy.parse(
            mapOf("room_id" to "!room-123:example.org", "event_id" to "\$event\nprivate text"),
        )

        assertNotNull(route)
        assertEquals("!room-123:example.org", route?.roomId)
        assertNull(route?.eventId)
    }

    @Test
    fun notificationIdentityIsStablePerEventAndDistinctBetweenEvents() {
        val first = PushNotificationRoute("!room-123:example.org", "\$event-1")
        val second = first.copy(eventId = "\$event-2")

        assertEquals(
            PushNotificationRoutePolicy.stableNotificationId(first),
            PushNotificationRoutePolicy.stableNotificationId(first),
        )
        assertNotEquals(
            PushNotificationRoutePolicy.stableNotificationId(first),
            PushNotificationRoutePolicy.stableNotificationId(second),
        )
    }
}
