package dev.friendline.messenger.data

/** Room membership facts used to freeze the human recipients of a sent message. */
internal data class DeliveryRecipientCandidate(
    val userId: String,
    val isJoined: Boolean,
    val isServiceMember: Boolean,
)

/** Invited users cannot decrypt room events yet, so only joined humans count as recipients. */
internal fun snapshotJoinedDeliveryRecipients(
    candidates: Iterable<DeliveryRecipientCandidate>,
    ownUserId: String,
): Set<String> = candidates.asSequence()
    .filter { it.isJoined && !it.isServiceMember }
    .map { it.userId }
    .filter { it.isNotBlank() && it != ownUserId }
    .toSet()
