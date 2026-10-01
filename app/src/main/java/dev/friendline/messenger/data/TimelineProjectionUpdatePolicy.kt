package dev.friendline.messenger.data

/** Keeps SDK index updates attached to the right message when a local echo overlay shifts the list. */
internal object TimelineProjectionUpdatePolicy {
    fun targetIndices(
        messages: List<ChatMessage?>,
        sdkIndex: Int,
        projected: ChatMessage,
    ): List<Int> {
        val matchingIndices = messages.indices.filter { index ->
            val current = messages[index] ?: return@filter false
            current.id == projected.id ||
                (projected.eventId != null && current.eventId == projected.eventId)
        }
        if (matchingIndices.isNotEmpty()) return matchingIndices
        return if (sdkIndex in messages.indices) listOf(sdkIndex) else emptyList()
    }
}
