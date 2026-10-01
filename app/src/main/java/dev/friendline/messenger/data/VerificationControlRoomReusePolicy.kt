package dev.friendline.messenger.data

/** A Matrix DM route is reusable only when both devices can select the same single room. */
internal object VerificationControlRoomReusePolicy {
    fun uniqueReusableRoomId(roomIds: Collection<String>): String? = roomIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .singleOrNull()
}
