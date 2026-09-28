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
