package dev.friendline.messenger.data

/**
 * Formats delivery acknowledgements without treating the first group member's
 * acknowledgement as delivery to the whole group.
 */
internal fun deliveryStatusLabel(
    expectedMemberIds: Set<String>,
    acknowledgedMemberIds: Set<String>,
    fallback: String = "Sent",
    legacyDelivered: Boolean = false,
): String {
    val expected = expectedMemberIds
    val acknowledged = acknowledgedMemberIds.intersect(expected)
    if (expected.size <= 1) {
        return if (legacyDelivered || (expected.isNotEmpty() && acknowledged.containsAll(expected))) {
            "Delivered"
        } else {
            fallback
        }
    }

    if (acknowledged.isEmpty()) return fallback
    return if (acknowledged.containsAll(expected)) {
        "Delivered to all ${expected.size}"
    } else {
        "Delivered to ${acknowledged.size} of ${expected.size}"
    }
}

/** Returns the updated ACK set only when the sender is an expected recipient. */
internal fun addDeliveryAcknowledgement(
    expectedMemberIds: Set<String>,
    acknowledgedMemberIds: Set<String>,
    acknowledgingMemberId: String,
): Set<String>? {
    if (acknowledgingMemberId !in expectedMemberIds) return null
    return acknowledgedMemberIds.intersect(expectedMemberIds) + acknowledgingMemberId
}

/** Copy the joined-human recipient set at enqueue time, excluding the sender. */
internal fun snapshotDeliveryRecipients(activeMemberIds: Iterable<String>, ownUserId: String): Set<String> =
    activeMemberIds.asSequence()
        .filter { it.isNotBlank() && it != ownUserId }
        .toSet()

/** Provisional ACK senders are counted only after their IDs pass the frozen snapshot. */
internal fun validateProvisionalDeliveryAcknowledgements(
    expectedMemberIds: Set<String>,
    provisionalMemberIds: Set<String>,
): Set<String> = provisionalMemberIds.intersect(expectedMemberIds)

internal fun deliveryMemberStatuses(
    expectedMemberIds: Set<String>,
    acknowledgedMemberIds: Set<String>,
): List<DeliveryMemberStatus> {
    val acknowledged = acknowledgedMemberIds.intersect(expectedMemberIds)
    return expectedMemberIds.sorted().map { userId ->
        DeliveryMemberStatus(userId = userId, delivered = userId in acknowledged)
    }
}

internal fun isProvisionalOnlyDeliveryAckRecord(
    state: String,
    snapshotKnown: Boolean,
    provisionalMemberIds: Set<String>,
): Boolean = state == "none" && !snapshotKnown && provisionalMemberIds.isNotEmpty()

/** Payload-free delivery diagnostics for the isolated instrumentation acceptance harness. */
internal data class DeliveryAckDiagnosticSnapshot(
    val observerInstalled: Boolean,
    val recordPresent: Boolean,
    val state: String,
    val snapshotKnown: Boolean,
    val expectedRecipientCount: Int,
    val acknowledgedRecipientCount: Int,
    val provisionalAcknowledgementCount: Int,
    val remoteAckMessageCount: Int,
    val sendQueueUpdatesObserverInstalled: Boolean,
    val activeSnapshotReservation: Boolean,
)

/** Promote an optimistic own-message row only when the send response confirms its transaction. */
internal fun promoteAcceptedLocalMessage(
    message: ChatMessage,
    transactionId: String,
    eventId: String,
    deliveryState: String,
): ChatMessage? {
    if (!message.isOwn || message.id != transactionId || message.eventId != null) {
        return null
    }
    return message.copy(
        id = eventId,
        eventId = eventId,
        isRemote = true,
        deliveryState = deliveryState,
    )
}
