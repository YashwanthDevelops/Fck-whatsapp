package dev.friendline.messenger.data

/** Minimal room facts used to recognize an existing encrypted one-to-one relationship. */
internal data class DirectConversationCandidate(
    val roomId: String,
    val isEncrypted: Boolean,
    val ownMembership: String,
    val isVerificationControlRoom: Boolean,
    val activeHumanMemberIds: Set<String>,
)

internal object DirectConversationReusePolicy {
    fun findReusableRoomId(
        candidates: Iterable<DirectConversationCandidate>,
        ownUserId: String,
        peerUserId: String,
    ): String? {
        val expectedMembers = setOf(ownUserId, peerUserId)
        return candidates.asSequence()
            .filter { candidate ->
                candidate.isEncrypted &&
                    candidate.ownMembership == "JOINED" &&
                    !candidate.isVerificationControlRoom &&
                    candidate.activeHumanMemberIds == expectedMembers
            }
            .map(DirectConversationCandidate::roomId)
            .minOrNull()
    }
}
