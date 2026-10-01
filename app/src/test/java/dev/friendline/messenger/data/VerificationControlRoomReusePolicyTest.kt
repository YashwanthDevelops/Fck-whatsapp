package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationControlRoomReusePolicyTest {
    @Test
    fun reusesTheOnlySharedVerificationRoom() {
        assertEquals(
            "!one:localhost",
            VerificationControlRoomReusePolicy.uniqueReusableRoomId(listOf("!one:localhost")),
        )
    }

    @Test
    fun duplicateRoomIdsDoNotCreateFalseAmbiguity() {
        assertEquals(
            "!one:localhost",
            VerificationControlRoomReusePolicy.uniqueReusableRoomId(
                listOf("!one:localhost", "!one:localhost", " "),
            ),
        )
    }

    @Test
    fun multipleVerificationRoomsRequireANewSharedInvitation() {
        assertNull(
            VerificationControlRoomReusePolicy.uniqueReusableRoomId(
                listOf("!phone-one:localhost", "!phone-two:localhost"),
            ),
        )
    }

    @Test
    fun noRoomRequiresCreation() {
        assertNull(VerificationControlRoomReusePolicy.uniqueReusableRoomId(emptyList()))
    }
}
