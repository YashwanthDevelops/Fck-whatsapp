package dev.friendline.messenger.push

import java.security.MessageDigest

internal data class PushNotificationRoute(val roomId: String, val eventId: String?)

/** Accept only opaque Matrix identifiers; push payload text is never used as notification copy. */
internal object PushNotificationRoutePolicy {
    const val EXTRA_ROOM_ID = "dev.friendline.messenger.push.ROOM_ID"
    const val EXTRA_EVENT_ID = "dev.friendline.messenger.push.EVENT_ID"

    private val roomIdPattern = Regex("^![A-Za-z0-9._=+-]+:[A-Za-z0-9.\\-:\\[\\]]{1,255}$")
    private val eventIdPattern = Regex("^\\$[A-Za-z0-9._=:/+-]{1,500}$")

    fun parse(data: Map<String, String>): PushNotificationRoute? {
        val roomId = data["room_id"]?.takeIf(roomIdPattern::matches) ?: return null
        val eventId = data["event_id"]?.takeIf(eventIdPattern::matches)
        return PushNotificationRoute(roomId, eventId)
    }

    fun stableNotificationId(route: PushNotificationRoute?): Int {
        val identity = route?.eventId ?: route?.roomId ?: "private-messenger-generic"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toInt() and 0x7f) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
        return value.coerceAtLeast(1)
    }
}
