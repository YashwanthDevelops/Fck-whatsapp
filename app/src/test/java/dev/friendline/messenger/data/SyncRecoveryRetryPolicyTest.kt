package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRecoveryRetryPolicyTest {
    @Test
    fun retryDelayGrowsAndCapsAtThirtySeconds() {
        assertEquals(1_000L, SyncRecoveryRetryPolicy.delayMillis(0))
        assertEquals(2_000L, SyncRecoveryRetryPolicy.delayMillis(1))
        assertEquals(4_000L, SyncRecoveryRetryPolicy.delayMillis(2))
        assertEquals(30_000L, SyncRecoveryRetryPolicy.delayMillis(5))
        assertEquals(30_000L, SyncRecoveryRetryPolicy.delayMillis(12))
    }

    @Test
    fun negativeAttemptUsesInitialDelay() {
        assertEquals(1_000L, SyncRecoveryRetryPolicy.delayMillis(-1))
    }
}
