package dev.friendline.messenger.push

/** Matrix account and app identity for one device pusher. This intentionally excludes Matrix tokens. */
internal data class PushPusherIdentity(
    val homeserverUrl: String,
    val userId: String,
    val deviceId: String,
    val appId: String,
)

/** The provider token is sensitive routing metadata and is stored only inside DeviceVault encryption. */
internal data class PushPusherRecord(
    val identity: PushPusherIdentity,
    val pushToken: String?,
    val operation: PushPusherOperation,
) {
    init {
        require(pushToken == null || pushToken.isNotBlank())
        require(
            pushToken != null || operation in setOf(
                PushPusherOperation.REMOVE_PENDING,
                PushPusherOperation.ROTATION_REMOVE_PENDING,
            ),
        )
    }
}

internal enum class PushPusherOperation {
    REGISTER_PENDING,
    REGISTERED,
    REMOVE_PENDING,
    ROTATION_REMOVE_PENDING,
}

internal enum class PushPusherPlan {
    NO_CHANGE,
    REGISTER_DESIRED,
    REMOVE_EXISTING,
    IDENTITY_MISMATCH,
}

/** Decide whether to preserve, replace, or remove a durable pusher before making network calls. */
internal fun planPushPusherSync(
    existing: PushPusherRecord?,
    desiredIdentity: PushPusherIdentity,
    desiredToken: String?,
    optedIn: Boolean,
): PushPusherPlan = when {
    existing != null && existing.identity != desiredIdentity -> PushPusherPlan.IDENTITY_MISMATCH
    existing?.operation in setOf(PushPusherOperation.REMOVE_PENDING, PushPusherOperation.ROTATION_REMOVE_PENDING) ->
        PushPusherPlan.REMOVE_EXISTING
    !optedIn -> if (existing == null) PushPusherPlan.NO_CHANGE else PushPusherPlan.REMOVE_EXISTING
    desiredToken.isNullOrBlank() -> PushPusherPlan.NO_CHANGE
    existing == null -> PushPusherPlan.REGISTER_DESIRED
    existing.pushToken != desiredToken -> PushPusherPlan.REMOVE_EXISTING
    existing.operation == PushPusherOperation.REGISTER_PENDING -> PushPusherPlan.REGISTER_DESIRED
    else -> PushPusherPlan.NO_CHANGE
}

internal fun shouldDisablePushAfterRecoveredRemoval(record: PushPusherRecord): Boolean =
    record.operation == PushPusherOperation.REMOVE_PENDING

/** A removal marker survives the crash window before its detailed pusher record is written. */
internal fun shouldRecoverPushOptOutRemoval(
    durableRemovalMarker: Boolean,
    record: PushPusherRecord?,
): Boolean = durableRemovalMarker || record?.operation == PushPusherOperation.REMOVE_PENDING

internal fun shouldResumeRegistrationAfterRecoveredRotation(
    record: PushPusherRecord,
    optedIn: Boolean,
): Boolean = record.operation == PushPusherOperation.ROTATION_REMOVE_PENDING && optedIn
