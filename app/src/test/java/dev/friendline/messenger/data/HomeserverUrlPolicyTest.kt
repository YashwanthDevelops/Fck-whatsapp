package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeserverUrlPolicyTest {
    @Test
    fun acceptsHttpsAndNormalizesTrailingSlash() {
        assertEquals("https://chat.example.org", HomeserverUrlPolicy.normalize(" https://chat.example.org/ ", false))
    }

    @Test
    fun permitsPrivateHttpOnlyForDebugBuilds() {
        assertEquals("http://10.0.2.2:8008", HomeserverUrlPolicy.normalize("http://10.0.2.2:8008/", true))
        assertThrows(IllegalArgumentException::class.java) {
            HomeserverUrlPolicy.normalize("http://10.0.2.2:8008", false)
        }
    }

    @Test
    fun permitsIpv4LoopbackHttpOnlyForDebugBuilds() {
        assertEquals("http://127.0.0.1:18009", HomeserverUrlPolicy.normalize("http://127.0.0.1:18009", true))
        assertThrows(IllegalArgumentException::class.java) {
            HomeserverUrlPolicy.normalize("http://127.0.0.1:18009", false)
        }
    }

    @Test
    fun rejectsRemoteHttpAndPublicHostsThatResemblePrivateAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeserverUrlPolicy.normalize("http://chat.example.org", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HomeserverUrlPolicy.normalize("http://10.attacker.example", true)
        }
    }

    @Test
    fun rejectsCredentialBearingAndAmbiguousUrls() {
        listOf(
            "https://user:password@chat.example.org",
            "https://chat.example.org?redirect=http://attacker.example",
            "https://chat.example.org#fragment",
            "http://192.168.1.10:0",
        ).forEach { value ->
            assertThrows("$value must be rejected", IllegalArgumentException::class.java) {
                HomeserverUrlPolicy.normalize(value, true)
            }
        }
    }
}
