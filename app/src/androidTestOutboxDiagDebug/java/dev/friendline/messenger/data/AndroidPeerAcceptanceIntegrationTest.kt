package dev.friendline.messenger.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Fresh-account, same-emulator Android peer acceptance. All Matrix stores are rooted below
 * independent diagnostic directories; this test never restores the regular app account.
 * The host harness provisions the accounts on its loopback-only Synapse and invokes the
 * core, attachment-offline, and attachment-resume stages.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPeerAcceptanceIntegrationTest {
    @Test
    fun freshAndroidPeersExerciseEncryptedMessagingAndAttachmentRecovery() = runBlocking {
        initializeSdkVerificationDiagnostics()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var stage = "argument-validation"
        var step = "argument-validation"
        var sender: MatrixRepository? = null
        var recipient: MatrixRepository? = null
        var senderIdentityState: String? = null
        var recipientIdentityState: String? = null
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("outbox_diag_boundary", "test-method-entered")
        })
        println("OUTBOX_DIAG_BOUNDARY reached=test-method")

        try {
            val args = InstrumentationRegistry.getArguments()
            val marker = checkNotNull(args.getString("marker"))
            val homeserver = checkNotNull(args.getString("homeserver_url"))
            val senderId = checkNotNull(args.getString("sender_user_id"))
            val senderPassword = checkNotNull(args.getString("sender_password"))
            val recipientId = checkNotNull(args.getString("recipient_user_id"))
            val recipientPassword = checkNotNull(args.getString("recipient_password"))
            stage = checkNotNull(args.getString("stage"))
            val appContext = instrumentation.targetContext
            val stateFile = File(appContext.noBackupFilesDir, "peer-acceptance/session.json")
            val activeSender = newIsolatedRepository(appContext, "sender")
            val activeRecipient = newIsolatedRepository(appContext, "recipient")
            sender = activeSender
            recipient = activeRecipient
            reportProgress(instrumentation, stage, "repository-construction", "complete")

            when (stage) {
                "core" -> runCoreAcceptance(
                    homeserver,
                    senderId,
                    senderPassword,
                    recipientId,
                    recipientPassword,
                    marker,
                    activeSender,
                    activeRecipient,
                    stateFile,
                    instrumentation,
                    onStep = { step = it },
                    onIdentityState = { account, state ->
                        val safeState = allowlistedIdentityState(state)
                        when (account) {
                            "sender" -> senderIdentityState = safeState
                            "recipient" -> recipientIdentityState = safeState
                        }
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("outbox_diag_identity", "$account|$safeState")
                        })
                        println("OUTBOX_DIAG_IDENTITY account=$account state=$safeState")
                    },
                )

                "attachment-offline" -> queueAttachmentWhileServerIsOffline(
                    homeserver,
                    senderId,
                    senderPassword,
                    recipientId,
                    recipientPassword,
                    marker,
                    activeSender,
                    activeRecipient,
                    stateFile,
                    instrumentation,
                    onStep = { step = it },
                )

                "attachment-resume" -> verifyAttachmentAfterServerRestarts(
                    homeserver,
                    senderId,
                    senderPassword,
                    recipientId,
                    recipientPassword,
                    marker,
                    activeSender,
                    activeRecipient,
                    stateFile,
                    instrumentation,
                    onStep = { step = it },
                )

                else -> error("Unsupported peer acceptance stage")
            }
        } catch (failure: Throwable) {
            val fingerprint = safeFailureFingerprint(stage, step, failure)
            val identityFields = buildString {
                senderIdentityState?.let { append(" senderIdentity=$it") }
                recipientIdentityState?.let { append(" recipientIdentity=$it") }
            }
            println(
                "OUTBOX_DIAG_EXCEPTION stage=${safeToken(stage)} step=${safeToken(step)} " +
                    "state=complete category=${failureCategory(failure)} fingerprint=$fingerprint " +
                    "classChain=${safeClassChain(failure)}$identityFields",
            )
            if (step == "verificationRequest") {
                val verificationStages = "${sender?.verificationStageForDiagnostic() ?: "none"}|" +
                    "${recipient?.verificationStageForDiagnostic() ?: "none"}"
                println("OUTBOX_DIAG_VERIFY_STAGE sender=${verificationStages.substringBefore('|')} recipient=${verificationStages.substringAfter('|')}")
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("outbox_diag_verification_stage", verificationStages)
                })
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString(
                    "outbox_diag_failure",
                    "${safeToken(stage)}|${safeToken(step)}|complete|${failureCategory(failure)}|$fingerprint|${safeClassChain(failure)}" +
                        "|${senderIdentityState ?: "none"}|${recipientIdentityState ?: "none"}",
                )
            })
            throw failure
        } finally {
            runCatching { recipient?.close() }
            runCatching { sender?.close() }
        }
    }

    private suspend fun runCoreAcceptance(
        homeserver: String,
        senderId: String,
        senderPassword: String,
        recipientId: String,
        recipientPassword: String,
        marker: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        stateFile: File,
        instrumentation: android.app.Instrumentation,
        onStep: (String) -> Unit,
        onIdentityState: (String, String) -> Unit,
    ) {
        onStep("repositoryLogin")
        reportProgress(instrumentation, "core", "repositoryLogin", "start")
        loginFresh(sender, homeserver, senderId, senderPassword)
        loginFresh(recipient, homeserver, recipientId, recipientPassword)
        reportProgress(instrumentation, "core", "repositoryLogin", "complete")

        onStep("createEncryptedConversation")
        reportProgress(instrumentation, "core", "createEncryptedConversation", "start")
        val roomId = sender.createEncryptedConversation(
            invitedUserIds = listOf(recipientId),
            name = "Private peer acceptance",
        )
        reportProgress(instrumentation, "core", "createEncryptedConversation", "complete")
        onStep("awaitRoomReady")
        reportProgress(instrumentation, "core", "awaitRoomReady", "start")
        await("recipient invitation") {
            recipient.refreshConversations()
            recipient.conversations.value.any { it.roomId == roomId }
        }
        recipient.joinConversation(roomId)
        await("joined encrypted room") {
            sender.refreshConversations()
            recipient.refreshConversations()
            isJoinedEncrypted(sender, roomId) && isJoinedEncrypted(recipient, roomId)
        }
        reportProgress(instrumentation, "core", "awaitRoomReady", "complete")
        onStep("verifyDiagnosticRoom")
        reportProgress(instrumentation, "core", "verifyDiagnosticRoom", "start")
        onStep("openConversation")
        sender.openConversation(roomId)
        recipient.openConversation(roomId)
        onStep("verifyPeers")
        reportProgress(instrumentation, "core", "verifyPeers", "start")
        verifyPeers(
            sender,
            recipient,
            roomId,
            onVerificationStep = { step ->
                onStep(step)
                reportProgress(instrumentation, "core", step, "start")
            },
            onIdentityState = onIdentityState,
        )
        reportProgress(instrumentation, "core", "verifyDiagnosticRoom", "complete")

        val backgroundRoomId = sender.createEncryptedConversation(
            invitedUserIds = listOf(recipientId),
            name = "Private peer background receipt check",
        )
        await("background-room invitation") {
            recipient.refreshConversations()
            recipient.conversations.value.any { it.roomId == backgroundRoomId }
        }
        recipient.joinConversation(backgroundRoomId)
        await("joined background room") {
            sender.refreshConversations()
            recipient.refreshConversations()
            isJoinedEncrypted(sender, backgroundRoomId) && isJoinedEncrypted(recipient, backgroundRoomId)
        }
        recipient.openConversation(backgroundRoomId)
        sender.openConversation(roomId)

        val backgroundMessage = "peer-background-room-$marker"
        onStep("sendText")
        sender.sendText(roomId, backgroundMessage)
        onStep("awaitDelivery")
        try {
            awaitMessage(sender, "delivery acknowledgement while recipient is in another room") {
                it.body == backgroundMessage && it.isOwn && it.deliveryState == "Delivered"
            }
        } catch (failure: Throwable) {
            val sentMessage = sender.messages.value.firstOrNull {
                it.body == backgroundMessage && it.isOwn
            }
            val senderState = when (sentMessage?.deliveryState) {
                "Queued" -> "queued"
                "Sending" -> "sending"
                "Sent" -> "sent"
                "Delivered" -> "delivered"
                "Retry needed" -> "retry"
                else -> "other"
            }
            val recipientSawMessage = recipient.messages.value.any {
                it.body == backgroundMessage && !it.isOwn
            }
            val recipientConnected = when (recipient.connection.value) {
                "Connected" -> "true"
                "Disconnected" -> "false"
                else -> "other"
            }
            val observation = "${sentMessage != null}|$senderState|$recipientSawMessage|$recipientConnected"
            val eventId = sentMessage?.eventId
            val ackTrace = eventId?.let {
                val senderAck = sender.deliveryAckDiagnosticSnapshot(roomId, it)
                val recipientAck = recipient.deliveryAckDiagnosticSnapshot(roomId, it)
                listOf(
                    senderAck.observerInstalled,
                    senderAck.recordPresent,
                    senderAck.state,
                    senderAck.snapshotKnown,
                    senderAck.expectedRecipientCount,
                    senderAck.acknowledgedRecipientCount,
                    senderAck.provisionalAcknowledgementCount,
                    recipientAck.observerInstalled,
                    recipientAck.recordPresent,
                    recipientAck.state,
                    recipientAck.snapshotKnown,
                    recipientAck.expectedRecipientCount,
                    recipientAck.acknowledgedRecipientCount,
                    recipientAck.provisionalAcknowledgementCount,
                ).joinToString("|")
            }
            val senderRemoteAckCount = sender.deliveryAckDiagnosticSnapshot(
                roomId,
                eventId ?: "diagnostic-event-not-found",
            ).remoteAckMessageCount
            val recipientRemoteAckCount = recipient.deliveryAckDiagnosticSnapshot(
                roomId,
                eventId ?: "diagnostic-event-not-found",
            ).remoteAckMessageCount
            if (eventId != null) {
                runCatching { sender.openConversation(roomId) }
                delay(1_000)
                val replayedAck = sender.deliveryAckDiagnosticSnapshot(roomId, eventId)
                val senderSawUndecryptableEvent = sender.messages.value.any {
                    !it.isOwn && it.body == "Unable to decrypt this message"
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString(
                        "outbox_diag_ack_replay",
                        "${replayedAck.recordPresent}|${replayedAck.state}|${replayedAck.snapshotKnown}|" +
                            "${replayedAck.expectedRecipientCount}|${replayedAck.acknowledgedRecipientCount}|" +
                            senderSawUndecryptableEvent,
                    )
                })
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("outbox_diag_background_delivery", observation)
                if (ackTrace != null) putString("outbox_diag_ack_trace", ackTrace)
                putString("outbox_diag_ack_messages", "$senderRemoteAckCount|$recipientRemoteAckCount")
            })
            throw failure
        }
        assertTrue(
            "The recipient must keep room B open while room A is acknowledged",
            recipient.messages.value.none { it.body == backgroundMessage },
        )

        val offlineMessage = "peer-offline-$marker"
        onStep("openConversation")
        recipient.close()
        onStep("sendText")
        sender.sendText(roomId, offlineMessage)
        onStep("awaitLocalEcho")
        val sentWhileRecipientOffline = awaitMessage(sender, "recipient-offline local echo") {
            it.body == offlineMessage && it.isOwn && it.eventId != null
        }
        assertFalse(
            "A disconnected recipient must not be marked delivered before reconnecting",
            sentWhileRecipientOffline.deliveryState == "Delivered",
        )

        val restoredRecipient = recipient.restoreSession()
        assertEquals(recipientId, restoredRecipient)
        awaitConnected(recipient)
        recipient.openConversation(backgroundRoomId)
        onStep("awaitDelivery")
        awaitMessage(sender, "offline acknowledgement while recipient remains in room B") {
            it.body == offlineMessage && it.isOwn && it.deliveryState == "Delivered"
        }
        recipient.openConversation(roomId)
        onStep("awaitDelivery")
        val decryptedOfflineMessage = awaitMessage(recipient, "decrypted offline backlog") {
            it.body == offlineMessage && !it.isOwn
        }
        recipient.setReadReceiptsEnabled(true)
        recipient.markMessageAsRead(
            roomId,
            checkNotNull(decryptedOfflineMessage.eventId),
            decryptedOfflineMessage.timestampMillis,
        )
        awaitMessage(sender, "Matrix read receipt") {
            it.body == offlineMessage && it.isOwn && it.hasBeenRead
        }

        recipient.sendTypingNotice(roomId, true)
        onStep("awaitDelivery")
        check(awaitCondition("typing notice", 15_000) {
            sender.typingUsers.value.contains(recipientId)
        }) { "The sender did not receive the peer's typing notice" }
        recipient.sendTypingNotice(roomId, false)

        val replyTargetBody = "reply-target-$marker"
        onStep("sendText")
        sender.sendText(roomId, replyTargetBody)
        val replyTarget = awaitMessage(recipient, "reply target") {
            it.body == replyTargetBody && !it.isOwn && it.eventId != null
        }
        recipient.sendText(roomId, "reply-$marker", checkNotNull(replyTarget.eventId))
        onStep("verifyExactlyOnce")
        awaitMessage(sender, "reply relation") {
            it.body == "reply-$marker" && it.replyToEventId == replyTarget.eventId
        }

        val reactionTarget = awaitMessage(recipient, "reaction target") {
            it.body == replyTargetBody && !it.isOwn && it.eventId != null
        }
        recipient.toggleReaction(roomId, reactionTarget, "👍")
        awaitMessage(sender, "encrypted reaction") {
            it.body == replyTargetBody && it.reactions.any { reaction -> reaction.key == "👍" && reaction.count == 1 }
        }

        val searchMarker = "search-$marker"
        onStep("sendText")
        sender.sendText(roomId, searchMarker)
        awaitMessage(recipient, "searchable message") { it.body == searchMarker && !it.isOwn }
        recipient.searchMessages(searchMarker)
        check(awaitCondition("local encrypted search", 30_000) {
            recipient.searchResults.value.any { it.roomId == roomId && it.body.contains(searchMarker) }
        }) { "The recipient's local search index did not find a recent encrypted message" }

        stateFile.parentFile?.mkdirs()
        stateFile.writeText(JSONObject().put("roomId", roomId).toString())
        reportSafeResult(instrumentation, "core", "completed", "verified")
        println(
            "OUTBOX_DIAG_RESULT stage=core roomEncrypted=true recipientOfflineBacklog=true " +
                "delivered=true read=true typing=true replies=true reactions=true search=true",
        )
    }

    private suspend fun queueAttachmentWhileServerIsOffline(
        homeserver: String,
        senderId: String,
        senderPassword: String,
        recipientId: String,
        recipientPassword: String,
        marker: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        stateFile: File,
        instrumentation: android.app.Instrumentation,
        onStep: (String) -> Unit,
    ) {
        onStep("restoreSession")
        reportProgress(instrumentation, "attachment-offline", "restoreSession", "start")
        restoreFresh(sender, homeserver, senderId, senderPassword, waitForConnection = false)
        restoreFresh(recipient, homeserver, recipientId, recipientPassword, waitForConnection = false)
        reportProgress(instrumentation, "attachment-offline", "restoreSession", "complete")
        val roomId = readRoomId(stateFile)
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val senderContext = isolatedContext(appContext, "sender")
        val fixture = attachmentFixture(appContext, "upload-$marker.txt")
        val preEnqueueFixture = attachmentFixture(appContext, "pre-enqueue-$marker.txt")
        fixture.parentFile?.mkdirs()
        val payload = "encrypted-retry-fixture-$marker".toByteArray(Charsets.UTF_8)
        val preEnqueuePayload = "encrypted-pre-enqueue-fixture-$marker".toByteArray(Charsets.UTF_8)
        fixture.writeBytes(payload)
        preEnqueueFixture.writeBytes(preEnqueuePayload)
        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            fixture,
        )
        val preEnqueueContentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            preEnqueueFixture,
        )

        try {
            onStep("awaitLocalEcho")
            reportProgress(instrumentation, "attachment-offline", "awaitLocalEcho", "start")
            runCatching { sender.openConversation(roomId) }
            runCatching { recipient.openConversation(roomId) }
            runCatching { sender.sendAttachment(roomId, contentUri.toString()) }
            val queued = awaitMessage(sender, "offline attachment local echo", 25_000) {
                it.attachment?.fileName == fixture.name && it.isOwn
            }
            reportProgress(instrumentation, "attachment-offline", "awaitLocalEcho", "complete")
            assertTrue(
                "The offline attachment must not already have a server event",
                queued.eventId == null || queued.deliveryState in setOf("Queued", "Sending", "Retry needed"),
            )
            onStep("persistPreEnqueueArchive")
            reportProgress(instrumentation, "attachment-offline", "persistPreEnqueueArchive", "start")
            sender.preparePendingMediaForDiagnosticCrashWindow(roomId, preEnqueueContentUri.toString())
            reportProgress(instrumentation, "attachment-offline", "persistPreEnqueueArchive", "complete")
            val encryptedArchiveCount = assertPendingAttachmentIsEncryptedOnly(
                senderContext,
                appContext,
                listOf(fixture, preEnqueueFixture),
                marker,
            )
            stateFile.writeText(
                JSONObject()
                    .put("roomId", roomId)
                    .put("sha256", sha256(payload))
                    .put("size", payload.size)
                    .put("preEnqueueSha256", sha256(preEnqueuePayload))
                    .put("preEnqueueSize", preEnqueuePayload.size)
                    .toString(),
            )
            reportSafeResult(instrumentation, "attachment-offline", "offline", "encrypted")
            println(
                "OUTBOX_DIAG_RESULT stage=attachment-offline localEcho=true serverEvent=false " +
                    "preEnqueueArchive=true " +
                    "plaintextCacheMarker=false noBackupMarker=false encryptedArchiveCount=$encryptedArchiveCount " +
                    "payloadSha256=${sha256(payload)}",
            )
        } catch (failure: Throwable) {
            fixture.delete()
            preEnqueueFixture.delete()
            throw failure
        }
    }

    private suspend fun verifyAttachmentAfterServerRestarts(
        homeserver: String,
        senderId: String,
        senderPassword: String,
        recipientId: String,
        recipientPassword: String,
        marker: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        stateFile: File,
        instrumentation: android.app.Instrumentation,
        onStep: (String) -> Unit,
    ) {
        onStep("restoreSession")
        reportProgress(instrumentation, "attachment-resume", "restoreSession", "start")
        restoreFresh(sender, homeserver, senderId, senderPassword)
        restoreFresh(recipient, homeserver, recipientId, recipientPassword)
        reportProgress(instrumentation, "attachment-resume", "restoreSession", "complete")
        val state = JSONObject(stateFile.readText())
        val roomId = checkNotNull(state.optString("roomId").takeIf(String::isNotBlank))
        val fileName = "upload-$marker.txt"
        val preEnqueueFileName = "pre-enqueue-$marker.txt"
        val expectedHash = checkNotNull(state.optString("sha256").takeIf(String::isNotBlank))
        val expectedSize = state.getInt("size")
        val preEnqueueHash = checkNotNull(state.optString("preEnqueueSha256").takeIf(String::isNotBlank))
        val preEnqueueSize = state.getInt("preEnqueueSize")
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = attachmentFixture(
            appContext,
            fileName,
        )
        val preEnqueueFixture = attachmentFixture(appContext, preEnqueueFileName)
        assertTrue("The encrypted media queue recovery fixture was lost", fixture.isFile && preEnqueueFixture.isFile)
        awaitConnected(sender)
        awaitConnected(recipient)
        sender.openConversation(roomId)
        recipient.openConversation(roomId)

        onStep("awaitDelivery")
        reportProgress(instrumentation, "attachment-resume", "awaitDelivery", "start")
        val deliveredAttachment = awaitMessage(sender, "automatically restored attachment upload", 90_000) {
            it.isOwn && it.attachment?.fileName == fileName && it.eventId != null &&
                it.deliveryState in setOf("Sent", "Delivered")
        }
        val recoveredPreEnqueueAttachment = awaitMessage(sender, "pre-enqueue attachment recovery", 90_000) {
            it.isOwn && it.attachment?.fileName == preEnqueueFileName && it.eventId != null &&
                it.deliveryState in setOf("Sent", "Delivered")
        }
        reportProgress(instrumentation, "attachment-resume", "awaitDelivery", "complete")
        onStep("verifyExactlyOnce")
        reportProgress(instrumentation, "attachment-resume", "verifyExactlyOnce", "start")
        assertNotNull(deliveredAttachment.attachment)
        assertNotNull(recoveredPreEnqueueAttachment.attachment)
        assertExactlyOneAttachmentAfterQuietWindow(sender, recipient, fileName)
        assertExactlyOneAttachmentAfterQuietWindow(sender, recipient, preEnqueueFileName)
        verifyDecryptedAttachment(recipient, fileName, expectedSize, expectedHash)
        verifyDecryptedAttachment(recipient, preEnqueueFileName, preEnqueueSize, preEnqueueHash)
        assertNoPlaintextAttachmentCache(
            isolatedContext(appContext, "sender"),
            appContext,
            listOf(fixture, preEnqueueFixture),
            marker,
        )

        fixture.delete()
        preEnqueueFixture.delete()
        stateFile.delete()
        reportProgress(instrumentation, "attachment-resume", "verifyExactlyOnce", "complete")
        reportSafeResult(instrumentation, "attachment-resume", "sentOnce=true", "recipientDecrypted=true")
        println("OUTBOX_DIAG_RESULT stage=attachment-resume sentOnce=true recipientDecrypted=true sha256Match=true")
    }

    private suspend fun verifyPeers(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        roomId: String,
        onVerificationStep: (String) -> Unit,
        onIdentityState: (String, String) -> Unit,
    ) {
        onVerificationStep("verificationPeerCheck")
        if (sender.isPeerVerified(roomId)) return
        onVerificationStep("verificationPrepareSender")
        try {
            sender.prepareVerificationListenerForDiagnostic()
        } finally {
            onIdentityState("sender", sender.ownVerificationIdentityStateForDiagnostic())
        }
        onVerificationStep("verificationPrepareRecipient")
        try {
            recipient.prepareVerificationListenerForDiagnostic()
        } finally {
            onIdentityState("recipient", recipient.ownVerificationIdentityStateForDiagnostic())
        }
        onVerificationStep("verificationRequest")
        var controlInviteSeen = false
        var controlRoomHiddenAfterJoin = false
        coroutineScope {
            val request = async { sender.requestPeerVerification(roomId) }
            val invitation = awaitCondition("verification-only room invitation", 45_000) {
                recipient.refreshConversations()
                recipient.conversations.value.any {
                    it.isVerificationControl && it.membership == "INVITED"
                }
            }
            check(invitation) { "The recipient did not receive a protocol-only verification invitation" }
            val controlRoomInvite = checkNotNull(
                recipient.conversations.value.firstOrNull {
                    it.isVerificationControl && it.membership == "INVITED"
                },
            )
            controlInviteSeen = true
            recipient.joinVerificationControlRoom(controlRoomInvite.roomId)
            controlRoomHiddenAfterJoin = recipient.conversations.value.none {
                it.isVerificationControl && it.membership != "INVITED"
            }
            request.await()
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("outbox_diag_verification_control", "$controlInviteSeen|$controlRoomHiddenAfterJoin")
        })
        println(
            "OUTBOX_DIAG_VERIFY_CONTROL inviteSeen=$controlInviteSeen " +
                "hiddenAfterJoin=$controlRoomHiddenAfterJoin",
        )
        onVerificationStep("verificationIncomingRequest")
        check(awaitCondition("incoming SAS verification", 30_000) {
            recipient.verification.value?.status == DeviceVerificationStatus.INCOMING_REQUEST
        }) { "The recipient did not receive the safety-code verification request" }
        onVerificationStep("verificationAccept")
        recipient.acceptVerificationRequest()
        onVerificationStep("verificationSafetyCode")
        check(awaitCondition("matching safety codes", 45_000) {
            sender.verification.value?.sas != null && recipient.verification.value?.sas != null
        }) { "The two fresh accounts did not produce safety codes" }
        assertEquals(sender.verification.value?.sas, recipient.verification.value?.sas)
        onVerificationStep("verificationApprove")
        sender.approveVerification()
        recipient.approveVerification()
        onVerificationStep("verificationComplete")
        check(awaitCondition("verified peers", 30_000) {
            sender.verification.value?.status == DeviceVerificationStatus.VERIFIED &&
                recipient.verification.value?.status == DeviceVerificationStatus.VERIFIED
        }) { "Both fresh accounts did not complete device verification" }
        onVerificationStep("verificationPeerTrust")
        val senderTrustsRecipient = runCatching { sender.isPeerVerified(roomId) }.getOrDefault(false)
        val recipientTrustsSender = runCatching { recipient.isPeerVerified(roomId) }.getOrDefault(false)
        val trustObservation = "$senderTrustsRecipient|$recipientTrustsSender"
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("outbox_diag_peer_trust", trustObservation)
        })
        println("OUTBOX_DIAG_PEER_TRUST senderTrustsRecipient=$senderTrustsRecipient recipientTrustsSender=$recipientTrustsSender")
        check(senderTrustsRecipient && recipientTrustsSender) {
            "The completed safety-code flow did not establish trusted peer identities on both clients"
        }
    }

    private suspend fun assertExactlyOneAttachmentAfterQuietWindow(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        fileName: String,
    ) {
        var stableSinceMillis: Long? = null
        check(awaitCondition("settled attachment event counts", 20_000) {
            val senderEvents = sender.messages.value
                .filter { it.isOwn && it.attachment?.fileName == fileName }
                .mapNotNull(ChatMessage::eventId)
                .toSet()
            val recipientEvents = recipient.messages.value
                .filter { !it.isOwn && it.attachment?.fileName == fileName }
                .mapNotNull(ChatMessage::eventId)
                .toSet()
            assertTrue("The sender observed duplicate attachment events", senderEvents.size <= 1)
            assertTrue("The recipient observed duplicate attachment events", recipientEvents.size <= 1)
            if (senderEvents.size == 1 && recipientEvents.size == 1) {
                val now = System.nanoTime() / 1_000_000L
                val since = stableSinceMillis ?: now.also { stableSinceMillis = it }
                now - since >= ATTACHMENT_EVENT_QUIET_WINDOW_MS
            } else {
                stableSinceMillis = null
                false
            }
        }) { "The attachment event count did not settle at one for both peers" }
    }

    private suspend fun verifyDecryptedAttachment(
        recipient: MatrixRepository,
        fileName: String,
        expectedSize: Int,
        expectedHash: String,
    ) {
        val recipientAttachment = awaitMessage(recipient, "recipient decrypted attachment") {
            !it.isOwn && it.attachment?.fileName == fileName && it.eventId != null
        }
        val decrypted = recipient.loadAttachmentForViewing(checkNotNull(recipientAttachment))
        try {
            val actualBytes = decrypted.readBytes()
            assertEquals(expectedSize, actualBytes.size)
            assertEquals(expectedHash, sha256(actualBytes))
        } finally {
            recipient.deleteTemporaryMediaFile(decrypted)
        }
    }

    private suspend fun loginFresh(
        repository: MatrixRepository,
        homeserver: String,
        userId: String,
        password: String,
    ) {
        val existing = repository.restoreSession()
        if (existing == null) {
            repository.login(homeserver, userId, password)
        } else {
            assertEquals("A diagnostic store was reused for the wrong account", userId, existing)
        }
        awaitConnected(repository)
    }

    private suspend fun restoreFresh(
        repository: MatrixRepository,
        homeserver: String,
        userId: String,
        password: String,
        waitForConnection: Boolean = true,
    ) {
        val existing = repository.restoreSession()
        if (existing == null) {
            repository.login(homeserver, userId, password)
        } else {
            assertEquals("A diagnostic store was reused for the wrong account", userId, existing)
        }
        if (waitForConnection) awaitConnected(repository)
    }

    private suspend fun awaitConnected(repository: MatrixRepository) {
        check(awaitCondition("Matrix sync connection", 60_000) {
            repository.connection.value == "Connected"
        }) {
            "A fresh diagnostic account did not connect to the isolated homeserver " +
                "(state=${repository.connection.value})"
        }
    }

    private suspend fun awaitMessage(
        repository: MatrixRepository,
        description: String,
        timeoutMillis: Long = 45_000,
        predicate: (ChatMessage) -> Boolean,
    ): ChatMessage {
        var result: ChatMessage? = null
        val found = awaitCondition(description, timeoutMillis) {
            result = repository.messages.value.firstOrNull(predicate)
            result != null
        }
        check(found) {
            "Timed out waiting for $description (connection=${repository.connection.value}, " +
                "visibleMessages=${repository.messages.value.size})"
        }
        return checkNotNull(result)
    }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 45_000,
        predicate: suspend () -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMillis) {
        while (!predicate()) delay(200)
        true
    } == true

    private suspend fun await(
        description: String,
        timeoutMillis: Long = 45_000,
        predicate: suspend () -> Boolean,
    ) {
        check(awaitCondition(description, timeoutMillis, predicate)) {
            "Timed out waiting for $description"
        }
    }

    private fun reportProgress(
        instrumentation: android.app.Instrumentation,
        stage: String,
        step: String,
        state: String,
    ) {
        val safeStage = safeToken(stage)
        val safeStep = safeToken(step)
        val safeState = if (state == "complete") "complete" else "start"
        instrumentation.sendStatus(0, Bundle().apply {
            putString("outbox_diag_progress", "$safeStage|$safeStep|$safeState")
        })
        println("OUTBOX_DIAG_PROGRESS stage=$safeStage step=$safeStep state=$safeState")
    }

    private fun reportSafeResult(instrumentation: android.app.Instrumentation, stage: String, vararg facts: String) {
        val safeStage = safeToken(stage)
        val safeFacts = facts.map(::safeToken).take(2)
        val safeValue = (listOf(safeStage) + safeFacts).joinToString("|")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("outbox_diag_result", safeValue)
        })
    }

    private fun isJoinedEncrypted(repository: MatrixRepository, roomId: String): Boolean =
        repository.conversations.value.any {
            it.roomId == roomId && it.membership == "JOINED" && it.isEncrypted
        }

    private fun newIsolatedRepository(base: Context, account: String): MatrixRepository =
        MatrixRepository(isolatedContext(base, account))

    private fun initializeSdkVerificationDiagnostics() {
        Log.i("OUTBOX_DIAG_SDK_BOUNDARY", "verification-sdk-log-start")
        val configuration = TracingConfiguration(
            logLevel = LogLevel.ERROR,
            traceLogPacks = emptyList(),
            extraTargets = emptyList(),
            writeToStdoutOrSystem = true,
            writeToFiles = null,
            sentryConfig = null,
        )
        runCatching { initPlatform(configuration, false) }
            .onSuccess { println("OUTBOX_DIAG_SDK_LOGGING state=enabled") }
            .onFailure { println("OUTBOX_DIAG_SDK_LOGGING state=unavailable") }
    }

    private fun attachmentFixture(context: Context, fileName: String): File =
        File(context.cacheDir, "private-messenger/media/peer-acceptance-upload/$fileName").apply {
            parentFile?.mkdirs()
        }

    private fun assertPendingAttachmentIsEncryptedOnly(
        context: Context,
        diagnosticAppContext: Context,
        excludedFixtures: List<File>,
        marker: String,
    ): Int {
        val cacheRoots = listOf(
            File(context.cacheDir, "private-messenger/media-outbox"),
            File(context.cacheDir, "private-messenger/media"),
            File(diagnosticAppContext.cacheDir, "private-messenger/media-outbox"),
            File(diagnosticAppContext.cacheDir, "private-messenger/media"),
        )
        val cacheContainsMarker = cacheRoots.any { root ->
            treeContainsPlaintextMarker(root, marker, excludedFixtures)
        }
        assertFalse(
            "The attachment marker appeared in the diagnostic media outbox or transfer cache",
            cacheContainsMarker,
        )

        val encryptedOutbox = File(context.noBackupFilesDir, "private-messenger/media-outbox")
        val encryptedFiles = encryptedOutbox.walkTopDown().filter(File::isFile).toList()
        val archives = encryptedFiles.filter { it.extension == "media" && it.length() > 0L }
        assertTrue("The durable encrypted attachment archive was not found", archives.isNotEmpty())
        val plaintextInOutbox = encryptedFiles.any { file ->
            file.name.contains(marker) || fileContainsPlaintextMarker(file, marker)
        }
        assertFalse(
            "The attachment marker appeared unencrypted in the no-backup media outbox",
            plaintextInOutbox,
        )
        return archives.size
    }

    private fun assertNoPlaintextAttachmentCache(
        context: Context,
        diagnosticAppContext: Context,
        excludedFixtures: List<File>,
        marker: String,
    ) {
        val cacheRoots = listOf(
            File(context.cacheDir, "private-messenger/media-outbox"),
            File(context.cacheDir, "private-messenger/media"),
            File(diagnosticAppContext.cacheDir, "private-messenger/media-outbox"),
            File(diagnosticAppContext.cacheDir, "private-messenger/media"),
        )
        assertFalse(
            "The attachment marker remained in a plaintext media cache after recovery",
            cacheRoots.any { root -> treeContainsPlaintextMarker(root, marker, excludedFixtures) },
        )
    }

    private fun treeContainsPlaintextMarker(root: File, marker: String, excludedFixtures: List<File>): Boolean {
        if (!root.exists()) return false
        val excludedPaths = excludedFixtures.mapNotNull { file -> runCatching { file.canonicalPath }.getOrNull() }.toSet()
        return root.walkTopDown().any { entry ->
            val isExcludedFixture = entry.isFile &&
                runCatching { entry.canonicalPath in excludedPaths }.getOrDefault(false)
            !isExcludedFixture && (entry.name.contains(marker) ||
                (entry.isFile && fileContainsPlaintextMarker(entry, marker)))
        }
    }

    private fun fileContainsPlaintextMarker(file: File, marker: String): Boolean = runCatching {
        String(file.readBytes(), Charsets.ISO_8859_1).contains(marker)
    }.getOrDefault(false)

    private fun isolatedContext(base: Context, account: String): Context = object : ContextWrapper(base) {
        private val root = File(base.noBackupFilesDir, "peer-acceptance/$account")
        private val privateCache = File(base.cacheDir, "peer-acceptance/$account")

        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root.apply { mkdirs() }
        override fun getCacheDir(): File = privateCache.apply { mkdirs() }
    }

    private fun readRoomId(stateFile: File): String {
        check(stateFile.isFile) { "The core peer acceptance stage has not completed" }
        return checkNotNull(JSONObject(stateFile.readText()).optString("roomId").takeIf(String::isNotBlank))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun safeToken(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)

    private fun failureCategory(failure: Throwable): String {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val names = chain.map { it.javaClass.simpleName.lowercase() }
        return when {
            names.any { "timeout" in it } -> "timeout"
            names.any { "socket" in it || "http" in it || "network" in it } -> "network"
            names.any { "crypto" in it || "encrypt" in it || "decrypt" in it } -> "encryption"
            names.any { "auth" in it || "login" in it } -> "auth"
            names.any { "sqlite" in it || "store" in it } -> "store"
            names.any { "room" in it } -> "room"
            else -> "other"
        }
    }

    private fun safeFailureFingerprint(stage: String, step: String, failure: Throwable): String {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val names = chain.map { it.javaClass.simpleName }.toSet()
        val message = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-cache-lookup" in message -> "verification-control-room-sync"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-members" in message -> "verification-control-room-members"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-create-request" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-encryption-state" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-topic-state" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-member-count" in message -> "verification-control-room-members"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-search" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-room-state" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "still syncing" in message -> "verification-control-room-sync"
            stage == "core" && step == "verificationRequest" && "unexpected participant" in message -> "verification-control-room-members"
            stage == "core" && step == "verificationRequest" && "does not include the intended peer" in message -> "verification-control-room-members"
            stage == "core" && step == "verificationRequest" && "does not match the friendline verification-channel marker" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "unexpectedly changed encryption state" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-create" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-route" in message -> "verification-control-room-sync"
            stage == "core" && step == "verificationRequest" && "verification-stage-control-room-join" in message -> "verification-request-timeout"
            stage == "core" && step == "verificationRequest" && "verification-stage-peer-identity" in message -> "verification-identity-missing"
            stage == "core" && step == "verificationRequest" && "verification-stage-sdk-room-selection" in message -> "verification-control-room-selection"
            stage == "core" && step == "verificationRequest" && "verification-stage-sdk-request" in message -> "verification-request-failed"
            stage == "core" && "MatrixVerificationIdentityUnavailable" in names -> "verification-identity-missing"
            stage == "core" && step == "verificationRequest" && "did not select the private verification" in message -> "verification-control-room-selection"
            stage == "core" && step == "verificationRequest" && "did not route verification" in message -> "verification-control-room-selection"
            stage == "core" && step == "verificationRequest" && "did not sync the private verification" in message -> "verification-control-room-sync"
            stage == "core" && step == "verificationRequest" && "did not save the private verification" in message -> "verification-control-room-sync"
            stage == "core" && step == "verificationRequest" && "still syncing" in message -> "verification-control-room-sync"
            stage == "core" && step == "verificationRequest" && "unexpected participant" in message -> "verification-control-room-members"
            stage == "core" && step == "verificationRequest" && "verification channel" in message -> "verification-control-room-unavailable"
            stage == "core" && step == "verificationIncomingRequest" -> "verification-request-timeout"
            stage == "core" && step == "verificationSafetyCode" -> "verification-sas-timeout"
            stage == "core" && step == "verificationComplete" -> "verification-completion-timeout"
            stage == "core" && step == "verificationRequest" -> "verification-request-failed"
            else -> "none"
        }
    }

    private fun allowlistedIdentityState(state: String): String = when (state) {
        "missing", "verified", "unverified", "unavailable" -> state
        else -> "unavailable"
    }

    private fun safeClassChain(failure: Throwable): String = generateSequence(failure) { it.cause }
        .take(8)
        .joinToString(">") { it.javaClass.name.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '$' } }
        .ifBlank { "UnknownFailure" }
        .take(180)

    private companion object {
        const val ATTACHMENT_EVENT_QUIET_WINDOW_MS = 5_000L
    }
}
