package dev.friendline.messenger.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTransferPolicyTest {
    private val maximumBytes = MAX_MEDIA_DOWNLOAD_BYTES

    @Test
    fun uploadLimitUsesTheSmallerServerAndClientBound() {
        assertEquals(32L * 1024 * 1024, effectiveMediaUploadLimit(100L * 1024 * 1024, MAX_MEDIA_UPLOAD_BYTES))
        assertEquals(10L * 1024 * 1024, effectiveMediaUploadLimit(10L * 1024 * 1024, MAX_MEDIA_UPLOAD_BYTES))
        assertEquals(MAX_MEDIA_UPLOAD_BYTES, effectiveMediaUploadLimit(null, MAX_MEDIA_UPLOAD_BYTES))
        assertEquals(MAX_MEDIA_UPLOAD_BYTES, effectiveMediaUploadLimit(0, MAX_MEDIA_UPLOAD_BYTES))
        assertEquals(0L, effectiveMediaUploadLimit(10, 0))
    }

    @Test
    fun advertisedDownloadSizeMustBePositiveAndWithinTheAppLimit() {
        assertTrue(advertisedMediaSizeIsAllowed(1, maximumBytes))
        assertTrue(advertisedMediaSizeIsAllowed(maximumBytes, maximumBytes))
        assertFalse(advertisedMediaSizeIsAllowed(null, maximumBytes))
        assertFalse(advertisedMediaSizeIsAllowed(0, maximumBytes))
        assertFalse(advertisedMediaSizeIsAllowed(-1, maximumBytes))
        assertFalse(advertisedMediaSizeIsAllowed(maximumBytes + 1, maximumBytes))
    }

    @Test
    fun pendingMediaReplayUsesFiniteExponentialBackoff() {
        assertEquals(2_000L, pendingMediaReplayRetryDelayMillis(1))
        assertEquals(4_000L, pendingMediaReplayRetryDelayMillis(2))
        assertEquals(8_000L, pendingMediaReplayRetryDelayMillis(3))
        assertEquals(16_000L, pendingMediaReplayRetryDelayMillis(MAX_PENDING_MEDIA_REPLAY_RETRIES))
        assertEquals(null, pendingMediaReplayRetryDelayMillis(0))
        assertEquals(null, pendingMediaReplayRetryDelayMillis(MAX_PENDING_MEDIA_REPLAY_RETRIES + 1))
    }

    @Test
    fun pendingMediaRecoveryRequiresARecentDeviceLocalTimestampAfterRecordCreation() {
        val createdAt = 1_000_000L
        val window = 60_000L

        assertTrue(isPendingMediaLocalEchoInRecoveryWindow(createdAt, createdAt, window))
        assertTrue(isPendingMediaLocalEchoInRecoveryWindow(createdAt, createdAt + window, window))
        assertFalse(isPendingMediaLocalEchoInRecoveryWindow(createdAt, createdAt - 1, window))
        assertFalse(isPendingMediaLocalEchoInRecoveryWindow(createdAt, createdAt + window + 1, window))
        assertFalse(isPendingMediaLocalEchoInRecoveryWindow(createdAt, null, window))
    }

    @Test
    fun voiceRetryIdentityMatchesOnlyTheOriginalDraft() {
        val original = VoiceMediaDraftIdentity("!room:server", "voice-123.m4a", "audio/mp4", 42_000, 8_000)

        assertTrue(original.matchesRetry(original.copy(mimeType = "AUDIO/MP4")))
        assertFalse(original.matchesRetry(original.copy(roomId = "!other:server")))
        assertFalse(original.matchesRetry(original.copy(fileName = "voice-other.m4a")))
        assertFalse(original.matchesRetry(original.copy(sizeBytes = 42_001)))
        assertFalse(original.matchesRetry(original.copy(durationMillis = 8_001)))
    }

    @Test
    fun secondSendTapAfterAutomaticSendUsesSentTombstoneInsteadOfCreatingAnotherEvent() {
        val original = VoiceMediaDraftIdentity("!room:server", "voice-123.m4a", "audio/mp4", 42_000, 8_000)
        val sentTombstone = original.copy()

        assertTrue(original.matchesRetry(sentTombstone))
        assertEquals(
            VoiceMediaSendAction.ALREADY_SENT,
            voiceMediaSendAction(
                hasOutboxRecord = true,
                isSent = true,
                transactionId = "txn-123",
                hasRecoverableSendHandle = false,
            ),
        )
        assertEquals(
            "A local echo may have an event identity before the send is confirmed",
            VoiceMediaSendAction.RETRY_EXISTING,
            voiceMediaSendAction(
                hasOutboxRecord = true,
                isSent = false,
                transactionId = "txn-123",
                hasRecoverableSendHandle = true,
            ),
        )
        assertEquals(
            "Do not enqueue a second event when retry cannot recover the existing handle",
            VoiceMediaSendAction.WAIT_FOR_STATUS,
            voiceMediaSendAction(
                hasOutboxRecord = true,
                isSent = false,
                transactionId = "txn-123",
                hasRecoverableSendHandle = false,
            ),
        )
        assertEquals(
            VoiceMediaSendAction.CREATE_NEW,
            voiceMediaSendAction(
                hasOutboxRecord = false,
                isSent = false,
                transactionId = null,
                hasRecoverableSendHandle = false,
            ),
        )
    }
}
