package dev.friendline.messenger.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeserverFailurePolicyTest {
    @Test
    fun recognizesLoopbackHomeserverRequestsInNestedSdkErrors() {
        val failure = IllegalStateException(
            "Conversation request failed",
            IllegalArgumentException("request URL: http://localhost:8008/_matrix/client/versions"),
        )

        assertTrue(HomeserverFailurePolicy.targetsLoopback(failure))
    }

    @Test
    fun recognizesIpv4AndIpv6LoopbackButNotLanHomeserver() {
        assertTrue(HomeserverFailurePolicy.targetsLoopback(IllegalStateException("http://127.0.0.1:8008/_matrix/client")))
        assertTrue(HomeserverFailurePolicy.targetsLoopback(IllegalStateException("http://[::1]:8008/_matrix/client")))
        assertFalse(HomeserverFailurePolicy.targetsLoopback(IllegalStateException("http://192.168.1.6:8008/_matrix/client")))
    }
}
