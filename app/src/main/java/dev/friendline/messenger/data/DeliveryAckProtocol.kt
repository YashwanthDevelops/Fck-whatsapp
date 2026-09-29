package dev.friendline.messenger.data

/**
 * Encrypted room timelines reliably deliver standard Matrix message types across SDK versions.
 * Keep delivery receipts inside ordinary encrypted text events and consume them before display.
 */
internal object DeliveryAckProtocol {
    const val TEXT_PREFIX = "\u2063org.friendline.delivery-ack.v1:"

    fun encode(eventId: String): String {
        require(eventId.isNotBlank()) { "A delivery receipt needs an event identifier" }
        return TEXT_PREFIX + eventId
    }

    fun targetEventId(body: String): String? {
        if (!body.startsWith(TEXT_PREFIX)) return null
        return body.removePrefix(TEXT_PREFIX).takeIf { target ->
            target.isNotBlank() && target.length <= MAX_EVENT_ID_LENGTH &&
                target.none(Char::isWhitespace)
        }
    }

    private const val MAX_EVENT_ID_LENGTH = 1024
}
