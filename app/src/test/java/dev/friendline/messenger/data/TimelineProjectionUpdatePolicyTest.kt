package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineProjectionUpdatePolicyTest {
    @Test
    fun fallbackEchoDoesNotOccupyAnSdkTimelineIndexWhenTransactionRowExists() {
        val timeline = listOf(
            message(id = "first", eventId = "$first"),
            message(id = "txn-1", eventId = null, body = "hello").copy(isOwn = true),
            message(id = "target", eventId = "$target"),
        )
        val fallback = message(id = "txn-1", eventId = null, body = "hello").copy(isOwn = true)

        assertEquals(
            emptyList<ChatMessage>(),
            TimelineProjectionUpdatePolicy.unrepresentedFallbackEchoes(timeline, listOf(fallback)),
        )
        assertEquals(3, timeline.size)
        assertEquals(listOf(2), TimelineProjectionUpdatePolicy.targetIndices(
            timeline,
            sdkIndex = 1,
            projected = message(id = "target", eventId = "$target", body = "updated"),
        ))
    }

    @Test
    fun acceptedFallbackEchoIsSuppressedByTheExactEventId() {
        val timeline = listOf(message(id = "$acceptedEvent", eventId = "$acceptedEvent").copy(isOwn = true))
        val fallback = message(id = "$acceptedEvent", eventId = "$acceptedEvent").copy(isOwn = true)

        assertEquals(
            emptyList<ChatMessage>(),
            TimelineProjectionUpdatePolicy.unrepresentedFallbackEchoes(timeline, listOf(fallback)),
        )
    }

    @Test
    fun fallbackEchoRemainsVisibleWhenSdkHasNoMatchingMessageProjection() {
        val fallback = message(id = "txn-1", eventId = null, body = "hello").copy(isOwn = true)

        assertEquals(
            listOf(fallback),
            TimelineProjectionUpdatePolicy.unrepresentedFallbackEchoes(listOf(null), listOf(fallback)),
        )
    }

    @Test
    fun sdkIndexUpdateFindsEventAfterNonMessageTimelineSlot() {
        val timeline = listOf(
            message(id = "first", eventId = "$first"),
            null,
            message(id = "target", eventId = "$target"),
        )
        val changedTarget = message(id = "target", eventId = "$target", body = "updated")

        assertEquals(listOf(2), TimelineProjectionUpdatePolicy.targetIndices(timeline, 1, changedTarget))
    }

    @Test
    fun updatesEveryDuplicateProjectionWhenTheSdkEchoArrivedAfterFallbackEcho() {
        val timeline = listOf(
            message(id = "target", eventId = "$target"),
            message(id = "target", eventId = "$target"),
        )
        val changedTarget = message(id = "target", eventId = "$target", body = "updated")

        assertEquals(listOf(0, 1), TimelineProjectionUpdatePolicy.targetIndices(timeline, 1, changedTarget))
    }

    @Test
    fun fallsBackToSdkIndexWhenNoMessageIdentityMatches() {
        val timeline = listOf(message(id = "first", eventId = "$first"))
        val insertedProjection = message(id = "next", eventId = "$next")

        assertEquals(listOf(0), TimelineProjectionUpdatePolicy.targetIndices(timeline, 0, insertedProjection))
        assertEquals(emptyList<Int>(), TimelineProjectionUpdatePolicy.targetIndices(timeline, 2, insertedProjection))
    }

    private fun message(id: String, eventId: String?, body: String = id) = ChatMessage(
        id = id,
        eventId = eventId,
        isRemote = eventId != null,
        sender = "@alice:example.org",
        body = body,
        timestampMillis = 1L,
        isOwn = false,
        deliveryState = "Sent",
    )

    private companion object {
        const val acceptedEvent = "accepted-event-id"
        const val first = "event-first"
        const val target = "event-target"
        const val next = "event-next"
    }
}
