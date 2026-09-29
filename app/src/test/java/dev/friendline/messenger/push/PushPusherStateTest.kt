package dev.friendline.messenger.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushPusherStateTest {
    private val identity = PushPusherIdentity(
        homeserverUrl = "https://matrix.example.org",
        userId = "@alice:example.org",
        deviceId = "ANDROID-DEVICE",
        appId = MatrixPushClient.APP_ID,
    )

    @Test
    fun initialOptInPersistsAnIdempotentRegistrationIntent() {
        assertEquals(
            PushPusherPlan.REGISTER_DESIRED,
            planPushPusherSync(
                existing = null,
                desiredIdentity = identity,
                desiredToken = "fcm-token-a",
                optedIn = true,
            ),
        )
    }

    @Test
    fun tokenRotationRemovesTheOldPusherBeforeRegisteringTheNewToken() {
        val existing = PushPusherRecord(identity, "fcm-token-old", PushPusherOperation.REGISTERED)

        assertEquals(
            PushPusherPlan.REMOVE_EXISTING,
            planPushPusherSync(
                existing = existing,
                desiredIdentity = identity,
                desiredToken = "fcm-token-new",
                optedIn = true,
            ),
        )
    }

    @Test
    fun pendingRemovalAlwaysTakesPriorityOverARequestedRegistration() {
        val pendingRemoval = PushPusherRecord(identity, "fcm-token-old", PushPusherOperation.ROTATION_REMOVE_PENDING)

        assertEquals(
            PushPusherPlan.REMOVE_EXISTING,
            planPushPusherSync(
                existing = pendingRemoval,
                desiredIdentity = identity,
                desiredToken = "fcm-token-new",
                optedIn = true,
            ),
        )
    }

    @Test
    fun optOutRemovesExistingPusherAndLeavesAnEmptyStateUnchanged() {
        val existing = PushPusherRecord(identity, "fcm-token-a", PushPusherOperation.REGISTERED)

        assertEquals(
            PushPusherPlan.REMOVE_EXISTING,
            planPushPusherSync(existing, identity, desiredToken = null, optedIn = false),
        )
        assertEquals(
            PushPusherPlan.NO_CHANGE,
            planPushPusherSync(existing = null, identity, desiredToken = null, optedIn = false),
        )
    }

    @Test
    fun differentAccountOrDeviceCannotReuseAnotherPushersIdentity() {
        val existing = PushPusherRecord(identity, "fcm-token-a", PushPusherOperation.REGISTERED)

        assertEquals(
            PushPusherPlan.IDENTITY_MISMATCH,
            planPushPusherSync(
                existing = existing,
                desiredIdentity = identity.copy(userId = "@bob:example.org"),
                desiredToken = "fcm-token-a",
                optedIn = true,
            ),
        )
        assertEquals(
            PushPusherPlan.IDENTITY_MISMATCH,
            planPushPusherSync(
                existing = existing,
                desiredIdentity = identity.copy(deviceId = "OTHER-DEVICE"),
                desiredToken = "fcm-token-a",
                optedIn = false,
            ),
        )
    }

    @Test
    fun matchingRegisteredPusherDoesNotNeedASecondRequest() {
        val existing = PushPusherRecord(identity, "fcm-token-a", PushPusherOperation.REGISTERED)

        assertEquals(
            PushPusherPlan.NO_CHANGE,
            planPushPusherSync(existing, identity, desiredToken = "fcm-token-a", optedIn = true),
        )
    }

    @Test
    fun recoveredLogoutRemovalTurnsOffOptInBeforeItCanBeRetried() {
        val pendingRemoval = PushPusherRecord(identity, "fcm-token-a", PushPusherOperation.REMOVE_PENDING)

        assertEquals(true, shouldDisablePushAfterRecoveredRemoval(pendingRemoval))
        assertEquals(false, shouldResumeRegistrationAfterRecoveredRotation(pendingRemoval, optedIn = true))
    }

    @Test
    fun durableOptOutMarkerRecoversWhenCrashHappensBeforePusherRecordIsWritten() {
        assertEquals(true, shouldRecoverPushOptOutRemoval(durableRemovalMarker = true, record = null))
        assertEquals(false, shouldRecoverPushOptOutRemoval(durableRemovalMarker = false, record = null))
    }

    @Test
    fun legacyPendingRemovalRecordStillForcesOptOutRecoveryWithoutTheNewMarker() {
        val pendingRemoval = PushPusherRecord(identity, "fcm-token-a", PushPusherOperation.REMOVE_PENDING)

        assertEquals(false, shouldRecoverPushOptOutRemoval(durableRemovalMarker = false, record = null))
        assertEquals(true, shouldRecoverPushOptOutRemoval(durableRemovalMarker = false, record = pendingRemoval))
    }

    @Test
    fun recoveredTokenRotationKeepsOptInAndContinuesRegistrationOnlyWhenStillEnabled() {
        val pendingRotation = PushPusherRecord(identity, "fcm-token-old", PushPusherOperation.ROTATION_REMOVE_PENDING)

        assertEquals(false, shouldDisablePushAfterRecoveredRemoval(pendingRotation))
        assertEquals(true, shouldResumeRegistrationAfterRecoveredRotation(pendingRotation, optedIn = true))
        assertEquals(false, shouldResumeRegistrationAfterRecoveredRotation(pendingRotation, optedIn = false))
    }
}
