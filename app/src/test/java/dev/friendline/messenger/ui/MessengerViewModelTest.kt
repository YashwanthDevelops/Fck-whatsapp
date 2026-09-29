package dev.friendline.messenger.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerViewModelTest {
    @Test
    fun failedVoiceDraftAfterClosingRoomCannotBeSentToAnotherConversation() {
        val ownerRoomId = "!voice-owner:example.org"
        val otherRoomId = "!other-room:example.org"

        // Closing the owner room during an in-flight send keeps the draft available after a
        // failure, but the preserved file must not become sendable in the newly opened room.
        assertFalse(voiceNoteDraftCanBeSentTo(ownerRoomId, otherRoomId))
        assertTrue(voiceNoteDraftCanBeSentTo(ownerRoomId, ownerRoomId))
        assertFalse(voiceNoteDraftCanBeSentTo(null, ownerRoomId))
    }
}
