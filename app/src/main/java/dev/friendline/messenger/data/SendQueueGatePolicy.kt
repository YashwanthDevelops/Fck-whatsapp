package dev.friendline.messenger.data

/** Tracks the SDK queue state only after the corresponding native call succeeds. */
internal class SendQueueGateState {
    @Volatile
    var appliedState: Boolean? = null
        private set

    fun needsApply(targetEnabled: Boolean): Boolean = appliedState != targetEnabled

    fun markApplied(enabled: Boolean) {
        appliedState = enabled
    }

    fun markUnknown() {
        appliedState = null
    }

    suspend fun apply(targetEnabled: Boolean, applyNativeState: suspend (Boolean) -> Unit) {
        if (!needsApply(targetEnabled)) return
        try {
            applyNativeState(targetEnabled)
            markApplied(targetEnabled)
        } catch (failure: Throwable) {
            markUnknown()
            throw failure
        }
    }
}

internal object SendQueueGateRetryPolicy {
    private const val INITIAL_DELAY_MILLIS = 250L
    private const val MAX_DELAY_MILLIS = 5_000L

    fun delayMillis(attempt: Int): Long =
        (INITIAL_DELAY_MILLIS * (1L shl attempt.coerceIn(0, 5))).coerceAtMost(MAX_DELAY_MILLIS)
}
