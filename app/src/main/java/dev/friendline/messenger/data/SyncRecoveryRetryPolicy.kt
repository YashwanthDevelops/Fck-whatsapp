package dev.friendline.messenger.data

/** Retry spacing for sync-service recovery while a signed-in app is foregrounded. */
internal object SyncRecoveryRetryPolicy {
    fun delayMillis(attempt: Int): Long = minOf(1_000L shl attempt.coerceAtLeast(0).coerceAtMost(5), 30_000L)
}
