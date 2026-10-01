package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectConversationReusePolicyTest {
    private val ownUserId = "@alice:example.org"
    private val peerUserId = "@bob:example.org"

    @Test
    fun reusesJoinedEncryptedRoomWithPeerInvitedOrJoined() {
        val room = candidate(active = setOf(ownUserId, peerUserId))

        assertEquals("!existing:example.org", find(room))
    }

    @Test
    fun doesNotReuseGroupsUnencryptedRoomsInvitationsOrVerificationRooms() {
        val candidates = listOf(
            candidate(roomId = "!group:example.org", active = setOf(ownUserId, peerUserId, "@carol:example.org")),
            candidate(roomId = "!plain:example.org", encrypted = false),
            candidate(roomId = "!incoming:example.org", membership = "INVITED"),
            candidate(roomId = "!verify:example.org", verificationControl = true),
        )

        assertNull(find(*candidates.toTypedArray()))
    }

    @Test
    fun selectionIsStableWhenAnAccountAlreadyHasOlderDuplicateRooms() {
        val rooms = listOf(
            candidate(roomId = "!z-existing:example.org"),
            candidate(roomId = "!a-existing:example.org"),
        )

        assertEquals("!a-existing:example.org", find(*rooms.toTypedArray()))
    }

    private fun find(vararg candidates: DirectConversationCandidate): String? =
        DirectConversationReusePolicy.findReusableRoomId(candidates.asIterable(), ownUserId, peerUserId)

    private fun candidate(
        roomId: String = "!existing:example.org",
        encrypted: Boolean = true,
        membership: String = "JOINED",
        verificationControl: Boolean = false,
        active: Set<String> = setOf(ownUserId, peerUserId),
    ) = DirectConversationCandidate(roomId, encrypted, membership, verificationControl, active)
}
