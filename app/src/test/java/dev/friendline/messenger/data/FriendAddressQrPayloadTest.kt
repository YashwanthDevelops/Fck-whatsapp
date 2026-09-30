package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FriendAddressQrPayloadTest {
    @Test
    fun androidCompatibleAddressRoundTripsWithVersionedPayload() {
        val encoded = FriendAddressQrPayload.encode("@alice:matrix.example", "https://matrix.example/")

        assertEquals(
            "friendline://friend?v=1&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
            encoded,
        )
        assertEquals(
            FriendAddressContact("@alice:matrix.example", "https://matrix.example"),
            FriendAddressQrPayload.parse(encoded),
        )
        assertEquals(
            "@alice:matrix.example",
            FriendAddressQrPayload.resolveForHomeserver(encoded, "https://MATRIX.example/"),
        )
    }

    @Test
    fun rejectsWrongSchemeHostVersionDuplicateFieldsAndMalformedIds() {
        listOf(
            "https://matrix.to/#/@alice:matrix.example",
            "friendline://other?v=1&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
            "friendline://friend?v=2&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
            "friendline://friend?v=1&v=1&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
            "friendline://friend?v=1&matrix_id=alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
        ).forEach { assertThrows(IllegalArgumentException::class.java) { FriendAddressQrPayload.parse(it) } }
    }

    @Test
    fun acceptsOnlySecureProductionHomeserversAndPrivateDevelopmentHttp() {
        assertThrows(IllegalArgumentException::class.java) {
            FriendAddressQrPayload.encode("@alice:example.com", "http://example.com")
        }
        val local = FriendAddressQrPayload.encode(
            "@alice:localhost",
            "http://127.0.0.1:8008/",
            allowPrivateHttp = true,
        )
        assertEquals(
            "http://127.0.0.1:8008",
            FriendAddressQrPayload.parse(local, allowPrivateHttp = true).homeserverUrl,
        )
        assertThrows(IllegalArgumentException::class.java) { FriendAddressQrPayload.parse(local) }
    }

    @Test
    fun rejectsContactAddressForAnotherHomeserver() {
        val encoded = FriendAddressQrPayload.encode("@alice:other.example", "https://other.example")
        assertThrows(IllegalArgumentException::class.java) {
            FriendAddressQrPayload.resolveForHomeserver(encoded, "https://matrix.example")
        }
    }
}
