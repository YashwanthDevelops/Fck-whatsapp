package dev.friendline.messenger.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SendQueueGatePolicyTest {
    @Test
    fun failedSdkQueueToggleDoesNotCacheTheTargetAsApplied() = runBlocking {
        val state = SendQueueGateState()

        assertTrue(state.needsApply(targetEnabled = true))
        try {
            state.apply(targetEnabled = true) { throw IllegalStateException("transient SDK failure") }
            fail("the simulated SDK failure should be returned to the caller")
        } catch (_: IllegalStateException) {
            // The state remains unknown so the repository's retry loop attempts the toggle again.
        }
        assertTrue(state.needsApply(targetEnabled = true))

        var sdkApplyCount = 0
        state.apply(targetEnabled = true) { sdkApplyCount += 1 }
        state.apply(targetEnabled = true) { sdkApplyCount += 1 }
        assertEquals(1, sdkApplyCount)
        assertFalse(state.needsApply(targetEnabled = true))
        assertTrue(state.needsApply(targetEnabled = false))
    }

    @Test
    fun aNewClientMustApplyItsQueueStateAgain() = runBlocking {
        val state = SendQueueGateState()
        state.apply(targetEnabled = true) { }
        state.markUnknown()

        assertTrue(state.needsApply(targetEnabled = false))

        var disabledClientQueueCount = 0
        state.apply(targetEnabled = false) { disabledClientQueueCount += 1 }
        assertEquals(1, disabledClientQueueCount)
        assertFalse(state.needsApply(targetEnabled = false))
    }

    @Test
    fun queueGateRetryDelayIsBounded() {
        assertEquals(250L, SendQueueGateRetryPolicy.delayMillis(0))
        assertEquals(500L, SendQueueGateRetryPolicy.delayMillis(1))
        assertEquals(1_000L, SendQueueGateRetryPolicy.delayMillis(2))
        assertEquals(5_000L, SendQueueGateRetryPolicy.delayMillis(5))
        assertEquals(5_000L, SendQueueGateRetryPolicy.delayMillis(20))
    }
}
