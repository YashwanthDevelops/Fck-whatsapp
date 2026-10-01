package dev.friendline.messenger.ui

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessagePresentationTest {
    @Test
    fun outgoingDeliveryLabelsExposeEachStateAndReadStatus() {
        assertEquals("Sending", messageDeliveryLabel("Sending", hasBeenRead = false, isGroup = false))
        assertEquals("Sent", messageDeliveryLabel("Sent", hasBeenRead = false, isGroup = false))
        assertEquals("Delivered", messageDeliveryLabel("Delivered", hasBeenRead = false, isGroup = false))
        assertEquals("Read", messageDeliveryLabel("Delivered", hasBeenRead = true, isGroup = false))
        assertEquals("Failed", messageDeliveryLabel("Failed", hasBeenRead = false, isGroup = false))
    }

    @Test
    fun groupDeliveryKeepsPartialCountWhenMarkedRead() {
        assertEquals(
            "Delivered to 1 of 2 · Seen",
            messageDeliveryLabel("Delivered to 1 of 2", hasBeenRead = true, isGroup = true),
        )
    }

    @Test
    fun todayMessageUsesCompactTime() {
        val now = localDateTime(year = 2026, month = Calendar.MARCH, day = 10, hour = 12, minute = 30)
        val sent = localDateTime(year = 2026, month = Calendar.MARCH, day = 10, hour = 12, minute = 5)
        val expected = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.US).format(Date(sent))

        assertEquals(expected, messageTimestampLabel(sent, now, Locale.US))
    }

    @Test
    fun olderMessageIncludesItsDateAndTime() {
        val now = localDateTime(year = 2026, month = Calendar.MARCH, day = 10, hour = 0, minute = 10)
        val sent = localDateTime(year = 2026, month = Calendar.MARCH, day = 9, hour = 23, minute = 55)
        val expected = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.US)
            .format(Date(sent))

        assertEquals(expected, messageTimestampLabel(sent, now, Locale.US))
    }

    private fun localDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
