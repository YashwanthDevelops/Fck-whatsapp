package dev.friendline.messenger.data

internal const val MAX_MEDIA_DOWNLOAD_BYTES = 100L * 1024 * 1024
internal const val MAX_MEDIA_UPLOAD_BYTES = 32L * 1024 * 1024
internal const val MAX_PENDING_MEDIA_REPLAY_RETRIES = 4

/** Delay before retry number [retryNumber], after the initial replay attempt failed. */
internal fun pendingMediaReplayRetryDelayMillis(retryNumber: Int): Long? = when (retryNumber) {
    1 -> 2_000L
    2 -> 4_000L
    3 -> 8_000L
    4 -> 16_000L
    else -> null
}

internal fun effectiveMediaUploadLimit(serverLimitBytes: Long?, clientLimitBytes: Long): Long =
    if (clientLimitBytes <= 0) 0 else serverLimitBytes?.takeIf { it > 0 }?.coerceAtMost(clientLimitBytes)
        ?: clientLimitBytes

internal fun advertisedMediaSizeIsAllowed(advertisedBytes: Long?, maximumBytes: Long): Boolean =
    maximumBytes > 0 && advertisedBytes != null && advertisedBytes in 1..maximumBytes

/**
 * Match an uncorrelated SDK local echo only when its device-local creation time falls
 * shortly after the app's durable outbox record was created. Remote server timestamps
 * must not be substituted here: they can make an older, otherwise identical event look
 * like the event represented by a newer outbox record.
 */
internal fun isPendingMediaLocalEchoInRecoveryWindow(
    recordCreatedAtMillis: Long,
    localCreatedAtMillis: Long?,
    maximumDelayMillis: Long,
): Boolean {
    if (localCreatedAtMillis == null || maximumDelayMillis < 0 || localCreatedAtMillis < recordCreatedAtMillis) {
        return false
    }
    return localCreatedAtMillis - recordCreatedAtMillis <= maximumDelayMillis
}

internal data class VoiceMediaDraftIdentity(
    val roomId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMillis: Long,
)

internal enum class VoiceMediaSendAction {
    CREATE_NEW,
    RETRY_EXISTING,
    ALREADY_SENT,
    WAIT_FOR_STATUS,
}

internal fun VoiceMediaDraftIdentity.matchesRetry(other: VoiceMediaDraftIdentity): Boolean =
    roomId == other.roomId &&
        fileName == other.fileName &&
        mimeType.equals(other.mimeType, ignoreCase = true) &&
        sizeBytes == other.sizeBytes &&
        durationMillis == other.durationMillis

internal fun voiceMediaSendAction(
    hasOutboxRecord: Boolean,
    isSent: Boolean,
    transactionId: String?,
    hasRecoverableSendHandle: Boolean,
): VoiceMediaSendAction = when {
    !hasOutboxRecord -> VoiceMediaSendAction.CREATE_NEW
    isSent -> VoiceMediaSendAction.ALREADY_SENT
    transactionId == null || !hasRecoverableSendHandle -> VoiceMediaSendAction.WAIT_FOR_STATUS
    else -> VoiceMediaSendAction.RETRY_EXISTING
}
