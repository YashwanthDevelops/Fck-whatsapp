package dev.friendline.messenger.data

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import dev.friendline.messenger.ui.PeerVerificationActionPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fresh-account, same-emulator Android peer acceptance. All Matrix stores are rooted below
 * independent diagnostic directories; this test never restores the regular app account.
 * The host harness provisions the accounts on its loopback-only Synapse and invokes the
 * core, room-invite, attachment-offline, and attachment-resume stages.
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
        var groupPeer: MatrixRepository? = null
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
            val groupPeerId = checkNotNull(args.getString("group_peer_user_id"))
            val groupPeerPassword = checkNotNull(args.getString("group_peer_password"))
            stage = checkNotNull(args.getString("stage"))
            val appContext = instrumentation.targetContext
            val stateFile = File(appContext.noBackupFilesDir, "peer-acceptance/session.json")
            val activeSender = newIsolatedRepository(appContext, "sender")
            val activeRecipient = newIsolatedRepository(appContext, "recipient")
            val activeGroupPeer = newIsolatedRepository(appContext, "group-peer")
            sender = activeSender
            recipient = activeRecipient
            groupPeer = activeGroupPeer
            reportProgress(instrumentation, stage, "repository-construction", "complete")

            when (stage) {
                "core" -> runCoreAcceptance(
                    homeserver,
                    senderId,
                    senderPassword,
                    recipientId,
                    recipientPassword,
                    groupPeerId,
                    groupPeerPassword,
                    marker,
                    activeSender,
                    activeRecipient,
                    activeGroupPeer,
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

                "room-invite" -> runRoomInvitationAcceptance(
                    homeserver,
                    senderId,
                    senderPassword,
                    recipientId,
                    recipientPassword,
                    groupPeerId,
                    groupPeerPassword,
                    marker,
                    activeSender,
                    activeRecipient,
                    activeGroupPeer,
                    instrumentation,
                    onStep = { step = it },
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
            if (step == "verificationRequest" || step.startsWith("verificationSeed")) {
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
            runCatching { groupPeer?.close() }
            runCatching { sender?.close() }
        }
    }

    private suspend fun runRoomInvitationAcceptance(
        homeserver: String,
        senderId: String,
        senderPassword: String,
        recipientId: String,
        recipientPassword: String,
        groupPeerId: String,
        groupPeerPassword: String,
        marker: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        groupPeer: MatrixRepository,
        instrumentation: android.app.Instrumentation,
        onStep: (String) -> Unit,
    ) {
        onStep("repositoryLogin")
        reportProgress(instrumentation, "room-invite", "repositoryLogin", "start")
        loginFresh(sender, homeserver, senderId, senderPassword)
        loginFresh(recipient, homeserver, recipientId, recipientPassword)
        loginFresh(groupPeer, homeserver, groupPeerId, groupPeerPassword)
        reportProgress(instrumentation, "room-invite", "repositoryLogin", "complete")

        onStep("createEncryptedConversation")
        reportProgress(instrumentation, "room-invite", "createEncryptedConversation", "start")
        val roomId = sender.createEncryptedConversation(
            invitedUserIds = listOf(recipientId),
            name = null,
            isGroup = false,
        )
        reportProgress(instrumentation, "room-invite", "createEncryptedConversation", "complete")

        onStep("awaitRoomReady")
        reportProgress(instrumentation, "room-invite", "awaitRoomReady", "start")
        await("direct conversation invitation") {
            recipient.refreshConversations()
            recipient.conversations.value.any {
                it.roomId == roomId && it.membership == "INVITED" && it.isEncrypted
            }
        }
        recipient.acceptConversationInvitation(roomId)
        await("joined encrypted direct conversation") {
            sender.refreshConversations()
            recipient.refreshConversations()
            isJoinedEncrypted(sender, roomId) && isJoinedEncrypted(recipient, roomId)
        }
        sender.openConversation(roomId)
        recipient.openConversation(roomId)
        reportProgress(instrumentation, "room-invite", "awaitRoomReady", "complete")

        onStep("verifyGroupPeer")
        reportProgress(instrumentation, "room-invite", "verifyGroupPeer", "start")
        sender.inviteConversationParticipant(roomId, groupPeerId)
        await("third participant invitation") {
            groupPeer.refreshConversations()
            groupPeer.conversations.value.any {
                it.roomId == roomId && it.membership == "INVITED" && it.isEncrypted
            }
        }
        groupPeer.acceptConversationInvitation(roomId)
        await("all invited members joined the encrypted room") {
            sender.refreshConversations()
            recipient.refreshConversations()
            groupPeer.refreshConversations()
            isJoinedEncrypted(sender, roomId) &&
                isJoinedEncrypted(recipient, roomId) &&
                isJoinedEncrypted(groupPeer, roomId) &&
                sender.conversations.value.any { it.roomId == roomId && it.isGroup }
        }
        reportProgress(instrumentation, "room-invite", "verifyGroupPeer", "complete")

        sender.openConversation(roomId)
        recipient.openConversation(roomId)
        groupPeer.openConversation(roomId)
        val body = "room-invite-$marker"
        onStep("sendGroupMessage")
        reportProgress(instrumentation, "room-invite", "sendGroupMessage", "start")
        assertTrue("The encrypted group message must enter the SDK send queue", sender.sendText(roomId, body))
        reportProgress(instrumentation, "room-invite", "sendGroupMessage", "complete")

        onStep("awaitDelivery")
        reportProgress(instrumentation, "room-invite", "awaitDelivery", "start")
        awaitMessage(sender, "sender's room-invite message") {
            it.body == body && it.isOwn && it.eventId != null
        }
        awaitMessage(recipient, "existing member's room-invite message") {
            it.body == body && !it.isOwn && it.eventId != null
        }
        awaitMessage(groupPeer, "new member's room-invite message") {
            it.body == body && !it.isOwn && it.eventId != null
        }
        reportProgress(instrumentation, "room-invite", "awaitDelivery", "complete")
        reportSafeResult(instrumentation, "room-invite", "joined", "encrypted-exchange")
        println(
            "OUTBOX_DIAG_RESULT stage=room-invite directPeerJoined=true thirdPeerInvitedAndJoined=true " +
                "encryptedMessageReceivedByBoth=true",
        )
    }

    private suspend fun runCoreAcceptance(
        homeserver: String,
        senderId: String,
        senderPassword: String,
        recipientId: String,
        recipientPassword: String,
        groupPeerId: String,
        groupPeerPassword: String,
        marker: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        groupPeer: MatrixRepository,
        stateFile: File,
        instrumentation: android.app.Instrumentation,
        onStep: (String) -> Unit,
        onIdentityState: (String, String) -> Unit,
    ) {
        onStep("repositoryLogin")
        reportProgress(instrumentation, "core", "repositoryLogin", "start")
        loginFresh(sender, homeserver, senderId, senderPassword)
        loginFresh(recipient, homeserver, recipientId, recipientPassword)
        loginFresh(groupPeer, homeserver, groupPeerId, groupPeerPassword)
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
        recipient.acceptConversationInvitation(roomId)
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
            recipientId,
            onVerificationStep = { step ->
                onStep(step)
                reportProgress(instrumentation, "core", step, "start")
            },
            onIdentityState = onIdentityState,
        )
        // Verification uses SDK timelines/control rooms. Reopen the conversation afterward
        // so the first message uses a live timeline projection rather than a stale handle.
        sender.openConversation(roomId)
        recipient.openConversation(roomId)
        reportProgress(instrumentation, "core", "verifyDiagnosticRoom", "complete")

        onStep("repeatDirectConversationCreation")
        reportProgress(instrumentation, "core", "repeatDirectConversationCreation", "start")
        verifyDirectConversationReuse(sender, recipient, recipientId, roomId)
        reportProgress(instrumentation, "core", "repeatDirectConversationCreation", "complete")
        println("OUTBOX_DIAG_DIRECT_ROOM_REUSE attempts=3 returnedSameRoom=true noDuplicateRows=true")

        onStep("rapidTextBurst")
        reportProgress(instrumentation, "core", "rapidTextBurst", "start")
        verifyRapidTextBurst(sender, recipient, roomId, marker)
        reportProgress(instrumentation, "core", "rapidTextBurst", "complete")

        onStep("backgroundForegroundRecovery")
        reportProgress(instrumentation, "core", "backgroundForegroundRecovery", "start")
        verifyAppLifecycleRecovery(sender, recipient, roomId, marker)
        reportProgress(instrumentation, "core", "backgroundForegroundRecovery", "complete")
        println("OUTBOX_DIAG_LIFECYCLE backgroundPaused=true foregroundConnected=true sendReceiveAfterResume=true")

        onStep("encryptedJpegRoundTrip")
        reportProgress(instrumentation, "core", "encryptedJpegRoundTrip", "start")
        verifyEncryptedJpegRoundTrip(sender, recipient, roomId, marker, instrumentation)
        reportProgress(instrumentation, "core", "encryptedJpegRoundTrip", "complete")

        onStep("editAndRedactEncryptedMessage")
        verifyMessageEditingAndRedaction(roomId, marker, sender, recipient, onStep)

        val backgroundRoomId = sender.createEncryptedConversation(
            invitedUserIds = listOf(recipientId, groupPeerId),
            name = "Private peer background receipt check",
            isGroup = true,
        )
        await("background-room invitation") {
            recipient.refreshConversations()
            recipient.conversations.value.any { it.roomId == backgroundRoomId }
        }
        recipient.acceptConversationInvitation(backgroundRoomId)
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
        onStep("awaitBackgroundDelivery")
        try {
            awaitMessage(sender, "delivery acknowledgement while recipient is in another room") {
                it.body == backgroundMessage && it.isOwn && it.deliveryState == "Delivered"
            }
            println("OUTBOX_DIAG_BACKGROUND_ACK delivered=true")
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
            val senderConnected = when (sender.connection.value) {
                "Connected" -> "true"
                "Disconnected" -> "false"
                else -> "other"
            }
            println(
                "OUTBOX_DIAG_DELIVERY_FAILURE senderMessage=${sentMessage != null} " +
                    "eventIdPresent=${eventId != null} senderState=$senderState " +
                    "senderConnected=$senderConnected recipientSawMessage=$recipientSawMessage " +
                    "recipientConnected=$recipientConnected",
            )
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
                val utdCauseSummary = sender.utdCauseCountsForDiagnostic()
                    .toSortedMap()
                    .entries
                    .joinToString(",") { (cause, count) -> "$cause=${count.coerceAtMost(999)}" }
                    .ifBlank { "none" }
                val timelineCategorySummary = sender.timelineCategoriesForDiagnostic(roomId)
                    .toSortedMap()
                    .entries
                    .joinToString(",") { (category, count) -> "$category=${count.coerceAtMost(999)}" }
                    .ifBlank { "none" }
                val serverEncryptedEventCounts = runCatching {
                    sender.recentEncryptedEventCountsForDiagnostic(roomId, recipientId)
                }.getOrDefault("0|0|0|0")
                val serverPeerEncryptedBefore = serverEncryptedEventCounts.split('|')
                    .getOrNull(1)?.toIntOrNull() ?: 0
                val reciprocalBody = "peer-reciprocal-$marker"
                val reciprocalSent = runCatching {
                    recipient.sendText(roomId, reciprocalBody)
                }.isSuccess
                if (reciprocalSent) {
                    withTimeoutOrNull(5_000) {
                        while (sender.messages.value.none { !it.isOwn && it.body == reciprocalBody }) {
                            delay(250)
                        }
                    }
                }
                runCatching { sender.openConversation(roomId) }
                delay(1_000)
                val senderReceivedReciprocal = sender.messages.value.any {
                    !it.isOwn && it.body == reciprocalBody
                }
                val serverEncryptedEventCountsAfter = runCatching {
                    sender.recentEncryptedEventCountsForDiagnostic(roomId, recipientId)
                }.getOrDefault("0|0|0|0")
                val serverPeerEncryptedAfter = serverEncryptedEventCountsAfter.split('|')
                    .getOrNull(1)?.toIntOrNull() ?: 0
                val reciprocalProbe = listOf(
                    reciprocalSent,
                    senderReceivedReciprocal,
                    serverPeerEncryptedBefore,
                    serverPeerEncryptedAfter,
                ).joinToString("|")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString(
                        "outbox_diag_ack_replay",
                        "${replayedAck.recordPresent}|${replayedAck.state}|${replayedAck.snapshotKnown}|" +
                            "${replayedAck.expectedRecipientCount}|${replayedAck.acknowledgedRecipientCount}|" +
                            senderSawUndecryptableEvent,
                    )
                    putString("outbox_diag_utd_causes", utdCauseSummary)
                    putString("outbox_diag_timeline_categories", timelineCategorySummary)
                    putString("outbox_diag_server_event_counts", serverEncryptedEventCounts)
                    putString("outbox_diag_reciprocal_probe", reciprocalProbe)
                })
                println("OUTBOX_DIAG_UTD_CAUSES observer=sender counts=$utdCauseSummary")
                println("OUTBOX_DIAG_TIMELINE_CATEGORIES observer=sender counts=$timelineCategorySummary")
                println("OUTBOX_DIAG_SERVER_ENCRYPTED_EVENTS statusAndCounts=$serverEncryptedEventCounts")
                println("OUTBOX_DIAG_RECIPROCAL_PROBE sent=$reciprocalSent senderReceived=$senderReceivedReciprocal peerEncryptedBefore=$serverPeerEncryptedBefore peerEncryptedAfter=$serverPeerEncryptedAfter")
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
        onStep("awaitOfflineDeliveryAck")
        try {
            awaitMessage(sender, "offline acknowledgement while recipient remains in room B") {
                it.body == offlineMessage && it.isOwn && it.deliveryState == "Delivered"
            }
        } catch (failure: Throwable) {
            runCatching { sender.openConversation(roomId) }
            delay(1_000)
            val offlineSenderMessage = sender.messages.value.firstOrNull {
                it.body == offlineMessage && it.isOwn
            }
            val offlineEventId = offlineSenderMessage?.eventId
            val offlineSenderState = when (offlineSenderMessage?.deliveryState) {
                "Queued" -> "queued"
                "Sending" -> "sending"
                "Sent" -> "sent"
                "Delivered" -> "delivered"
                "Retry needed" -> "retry"
                else -> "other"
            }
            val offlineSenderAck = offlineEventId?.let {
                runCatching { sender.deliveryAckDiagnosticSnapshot(roomId, it) }.getOrNull()
            }
            val offlineRecipientAck = offlineEventId?.let {
                runCatching { recipient.deliveryAckDiagnosticSnapshot(roomId, it) }.getOrNull()
            }
            val offlineServerCounts = runCatching {
                sender.recentEncryptedEventCountsForDiagnostic(roomId, recipientId)
            }.getOrDefault("0|0|0|0")
            val offlinePeerEncrypted = offlineServerCounts.split('|')
                .getOrNull(1)?.toIntOrNull() ?: 0
            val offlineAckState = listOf(
                offlineSenderAck?.state ?: "missing",
                offlineRecipientAck?.state ?: "missing",
                offlineRecipientAck?.observerInstalled ?: false,
                offlineSenderAck?.remoteAckMessageCount ?: 0,
                offlinePeerEncrypted,
            ).joinToString("|")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString(
                    "outbox_diag_offline_ack",
                    "${offlineSenderMessage != null}|${offlineEventId != null}|$offlineSenderState|$offlineAckState",
                )
            })
            println(
                "OUTBOX_DIAG_OFFLINE_ACK senderMessage=${offlineSenderMessage != null} " +
                    "eventIdPresent=${offlineEventId != null} senderState=$offlineSenderState " +
                    "senderAck=${offlineSenderAck?.state ?: "missing"} " +
                    "recipientAck=${offlineRecipientAck?.state ?: "missing"} " +
                    "recipientObserver=${offlineRecipientAck?.observerInstalled ?: false} " +
                    "senderRemoteAckMessages=${offlineSenderAck?.remoteAckMessageCount ?: 0} " +
                    "peerEncryptedEvents=$offlinePeerEncrypted",
            )
            throw failure
        }
        recipient.openConversation(roomId)
        onStep("awaitOfflineBacklog")
        val decryptedOfflineMessage = awaitMessage(recipient, "decrypted offline backlog") {
            it.body == offlineMessage && !it.isOwn
        }
        val offlineEventId = checkNotNull(decryptedOfflineMessage.eventId)
        sender.watchReadReceiptForDiagnostic(offlineEventId)
        recipient.setReadReceiptsEnabled(true)
        onStep("sendReadReceipt")
        recipient.markMessageAsRead(
            roomId,
            offlineEventId,
            decryptedOfflineMessage.timestampMillis,
        )
        println("OUTBOX_DIAG_READ_RECEIPT_SEND returned=true")
        onStep("awaitReadReceipt")
        val readReceiptObserved = awaitCondition("Matrix read receipt") {
            sender.messages.value.any {
                it.body == offlineMessage && it.isOwn && it.hasBeenRead
            }
        }
        val readReceiptSnapshot = sender.readReceiptDiagnosticSnapshot()
        val sdkReceiptCacheState = runCatching {
            sender.peerReadReceiptCacheStateForDiagnostic(roomId, recipientId, offlineEventId)
        }.getOrDefault("unavailable")
        val readReceiptTrace = listOf(
            readReceiptSnapshot.eventSeen,
            readReceiptSnapshot.isOwnEvent,
            readReceiptSnapshot.hasOtherReader,
            readReceiptSnapshot.mappedReadState,
            readReceiptSnapshot.appModelReadState,
            readReceiptSnapshot.updateCount,
            sdkReceiptCacheState,
        ).joinToString("|")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("outbox_diag_read_receipt_trace", readReceiptTrace)
        })
        println(
            "OUTBOX_DIAG_READ_RECEIPT_TRACE eventSeen=${readReceiptSnapshot.eventSeen} " +
                "ownEvent=${readReceiptSnapshot.isOwnEvent} otherReader=${readReceiptSnapshot.hasOtherReader} " +
                "mappedRead=${readReceiptSnapshot.mappedReadState} appRead=${readReceiptSnapshot.appModelReadState} " +
                "updates=${readReceiptSnapshot.updateCount} " +
                "sdkCache=$sdkReceiptCacheState",
        )
        check(readReceiptObserved) { "The sender timeline did not reflect the recipient's read receipt" }

        recipient.sendTypingNotice(roomId, true)
        onStep("awaitTyping")
        check(awaitCondition("typing notice", 15_000) {
            sender.typingUsers.value.contains(recipientId)
        }) { "The sender did not receive the peer's typing notice" }
        recipient.sendTypingNotice(roomId, false)

        val replyTargetBody = "reply-target-$marker"
        onStep("sendReplyTarget")
        sender.sendText(roomId, replyTargetBody)
        onStep("awaitReplyTarget")
        val replyTarget = awaitMessage(recipient, "reply target") {
            it.body == replyTargetBody && !it.isOwn && it.eventId != null
        }
        onStep("sendReply")
        recipient.sendText(roomId, "reply-$marker", checkNotNull(replyTarget.eventId))
        onStep("awaitReplyEcho")
        val replyEcho = awaitMessage(sender, "reply echo") {
            it.body == "reply-$marker" && !it.isOwn
        }
        onStep("awaitReplyRelation")
        val replyRelationMatched = awaitCondition("reply target relation", 15_000) {
            sender.messages.value.any {
                it.body == "reply-$marker" && !it.isOwn && it.replyToEventId == replyTarget.eventId
            }
        }
        val currentReplyRelation = sender.messages.value.firstOrNull { it.body == "reply-$marker" && !it.isOwn }
        val replyRelationSummary = listOf(
            replyRelationMatched,
            replyEcho.replyToEventId != null,
            replyTarget.eventId.length > 0,
            currentReplyRelation?.replyToEventId != null,
        ).joinToString("|") { it.toString() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("outbox_diag_reply_relation", replyRelationSummary)
        })
        println(
            "OUTBOX_DIAG_REPLY_RELATION matched=$replyRelationMatched " +
                "projectedRelation=${replyEcho.replyToEventId != null} " +
                "targetEvent=true " +
                "currentRelation=${currentReplyRelation?.replyToEventId != null}",
        )
        check(replyRelationMatched) {
            "The encrypted reply did not preserve its target event relation"
        }

        val reactionTarget = awaitMessage(recipient, "reaction target") {
            it.body == replyTargetBody && !it.isOwn && it.eventId != null
        }
        onStep("sendReaction")
        recipient.toggleReaction(roomId, reactionTarget, "👍")
        onStep("awaitReaction")
        val reactionObserved = awaitCondition("encrypted reaction") {
            sender.messages.value.any {
                it.body == replyTargetBody && it.reactions.any { reaction -> reaction.key == "👍" && reaction.count == 1 }
            }
        }
        val senderReactionTarget = sender.messages.value.firstOrNull { it.eventId == reactionTarget.eventId }
        val recipientReactionTarget = recipient.messages.value.firstOrNull { it.eventId == reactionTarget.eventId }
        val senderReaction = senderReactionTarget?.reactions?.firstOrNull { it.key == "👍" }
        val recipientReaction = recipientReactionTarget?.reactions?.firstOrNull { it.key == "👍" }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString(
                "outbox_diag_reaction_projection",
                listOf(
                    reactionObserved,
                    senderReactionTarget != null,
                    senderReaction?.count == 1,
                    recipientReaction?.count == 1,
                    sender.connection.value == "Connected",
                    recipient.connection.value == "Connected",
                ).joinToString("|") { it.toString() },
            )
        })
        println(
            "OUTBOX_DIAG_REACTION_PROJECTION observed=$reactionObserved " +
                "senderTarget=${senderReactionTarget != null} senderCount=${senderReaction?.count ?: 0} " +
                "recipientCount=${recipientReaction?.count ?: 0} " +
                "senderConnected=${sender.connection.value == "Connected"} " +
                "recipientConnected=${recipient.connection.value == "Connected"}",
        )
        check(reactionObserved) {
            "The encrypted reaction did not reach the sender's projected timeline"
        }

        val searchMarker = "search-$marker"
        onStep("sendSearchMarker")
        sender.sendText(roomId, searchMarker)
        onStep("awaitSearchMarker")
        awaitMessage(recipient, "searchable message") { it.body == searchMarker && !it.isOwn }
        onStep("executeLocalSearch")
        // SearchService queries indexed terms; punctuation-delimited run IDs are not a
        // stable query syntax across SDK index implementations. Search a word from the
        // body and verify the exact unique body appears in the local result set.
        recipient.searchMessages("search")
        onStep("awaitLocalSearch")
        val localSearchFound = awaitCondition("local encrypted search", 30_000) {
            recipient.searchResults.value.any { it.roomId == roomId && it.body.contains(searchMarker) }
        }
        if (!localSearchFound) {
            val searchState = listOf(
                recipient.searchResults.value.size.coerceAtMost(999).toString(),
                recipient.searchLoading.value.toString(),
                recipient.searchHasMore.value.toString(),
                recipient.searchResults.value.any { it.roomId == roomId }.toString(),
            ).joinToString("|")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("outbox_diag_search_state", searchState)
            })
            println(
                "OUTBOX_DIAG_SEARCH_STATE resultCount=${recipient.searchResults.value.size} " +
                    "loading=${recipient.searchLoading.value} hasMore=${recipient.searchHasMore.value} " +
                    "roomResult=${recipient.searchResults.value.any { it.roomId == roomId }}",
            )
            println(
                "OUTBOX_DIAG_SEARCH result=missing query=outbox " +
                    "resultCount=${recipient.searchResults.value.size.coerceAtMost(999)} " +
                    "loading=${recipient.searchLoading.value} hasMore=${recipient.searchHasMore.value}",
            )
        } else {
            println("OUTBOX_DIAG_SEARCH result=found query=outbox")
        }
        check(localSearchFound) { "The recipient's local search index did not find a recent encrypted message" }

        onStep("localStoreMarkerScan")
        val localStoreScan = scanIsolatedMessageStores(instrumentation.targetContext, marker)
        val localStoreMarkerClear = localStoreScan.markerMatchFiles == 0
        Log.i(
            "OUTBOX_DIAG_LOCAL_SCAN",
            "storeFiles=${localStoreScan.storeFiles} sqliteFiles=${localStoreScan.sqliteFiles} " +
                "walFiles=${localStoreScan.walFiles} cacheFiles=${localStoreScan.cacheFiles} " +
                "searchIndexFiles=${localStoreScan.searchIndexFiles} markerMatchFiles=${localStoreScan.markerMatchFiles}",
        )
        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                "outbox_diag_local_store_scan",
                listOf(
                    localStoreScan.storeFiles,
                    localStoreScan.sqliteFiles,
                    localStoreScan.walFiles,
                    localStoreScan.cacheFiles,
                    localStoreScan.searchIndexFiles,
                    localStoreScan.markerMatchFiles,
                ).joinToString("|"),
            )
        })

        onStep("voiceNoteRoundTrip")
        verifyVoiceNoteRoundTrip(
            context = instrumentation.targetContext,
            roomId = roomId,
            marker = marker,
            sender = sender,
            recipient = recipient,
            onStep = onStep,
        )

        onStep("unverifiedDeviceKeyExclusion")
        reportProgress(instrumentation, "core", "unverifiedDeviceKeyExclusion", "start")
        assertNewUnverifiedDeviceCannotDecrypt(
            homeserver = homeserver,
            recipientId = recipientId,
            recipientPassword = recipientPassword,
            marker = marker,
            roomId = roomId,
            sender = sender,
            recipient = recipient,
            appContext = instrumentation.targetContext,
            onStep = onStep,
        )
        reportProgress(instrumentation, "core", "unverifiedDeviceKeyExclusion", "complete")

        onStep("createGroupConversation")
        reportProgress(instrumentation, "core", "createGroupConversation", "start")
        val groupRoomId = sender.createEncryptedConversation(
            invitedUserIds = listOf(recipientId, groupPeerId),
            name = "Private group acceptance",
        )
        await("group invitations") {
            recipient.refreshConversations()
            groupPeer.refreshConversations()
            recipient.conversations.value.any { it.roomId == groupRoomId } &&
                groupPeer.conversations.value.any { it.roomId == groupRoomId }
        }
        recipient.acceptConversationInvitation(groupRoomId)
        groupPeer.acceptConversationInvitation(groupRoomId)
        await("all group members joined") {
            sender.refreshConversations()
            recipient.refreshConversations()
            groupPeer.refreshConversations()
            isJoinedEncrypted(sender, groupRoomId) &&
                isJoinedEncrypted(recipient, groupRoomId) &&
                isJoinedEncrypted(groupPeer, groupRoomId)
        }
        reportProgress(instrumentation, "core", "createGroupConversation", "complete")

        onStep("verifyGroupPeer")
        val groupVerificationRoomId = sender.createEncryptedConversation(
            invitedUserIds = listOf(groupPeerId),
            name = "Private group verification acceptance",
        )
        await("group peer verification invitation") {
            groupPeer.refreshConversations()
            groupPeer.conversations.value.any { it.roomId == groupVerificationRoomId }
        }
        groupPeer.acceptConversationInvitation(groupVerificationRoomId)
        await("group peer verification room joined") {
            sender.refreshConversations()
            groupPeer.refreshConversations()
            isJoinedEncrypted(sender, groupVerificationRoomId) &&
                isJoinedEncrypted(groupPeer, groupVerificationRoomId)
        }
        sender.openConversation(groupVerificationRoomId)
        groupPeer.openConversation(groupVerificationRoomId)
        verifyPeers(
            sender,
            groupPeer,
            groupVerificationRoomId,
            groupPeerId,
            onVerificationStep = { verificationStep ->
                onStep(verificationStep)
                reportProgress(instrumentation, "core", verificationStep, "start")
            },
            onIdentityState = { account, identityState ->
                val safeState = allowlistedIdentityState(identityState)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("outbox_diag_group_identity", "$account|$safeState")
                })
                println("OUTBOX_DIAG_GROUP_IDENTITY account=${safeToken(account)} state=$safeState")
            },
        )

        sender.openConversation(groupRoomId)
        recipient.openConversation(groupRoomId)
        groupPeer.openConversation(groupRoomId)
        groupPeer.close()

        val groupMessageBody = "group-delivery-$marker"
        onStep("sendGroupMessage")
        sender.sendText(groupRoomId, groupMessageBody)
        val senderGroupMessage = awaitMessage(sender, "group sender event") {
            it.body == groupMessageBody && it.isOwn && it.eventId != null
        }
        awaitMessage(recipient, "first group member decrypts message") {
            it.body == groupMessageBody && !it.isOwn && it.eventId == senderGroupMessage.eventId
        }
        val senderGroupEventId = checkNotNull(senderGroupMessage.eventId)
        onStep("awaitGroupDeliveryPartial")
        val partialDeliveryReached = awaitCondition("one-of-two group delivery acknowledgements") {
            val snapshot = sender.deliveryAckDiagnosticSnapshot(groupRoomId, senderGroupEventId)
            val message = sender.messages.value.firstOrNull { it.eventId == senderGroupEventId }
            snapshot.expectedRecipientCount == 2 &&
                snapshot.acknowledgedRecipientCount == 1 &&
                message?.deliveryState == "Delivered to 1 of 2"
        }
        if (!partialDeliveryReached) {
            val senderAck = sender.deliveryAckDiagnosticSnapshot(groupRoomId, senderGroupEventId)
            val recipientAck = recipient.deliveryAckDiagnosticSnapshot(groupRoomId, senderGroupEventId)
            val deliveryState = sender.messages.value.firstOrNull { it.eventId == senderGroupEventId }
                ?.deliveryState?.let(::safeToken) ?: "missing"
            val observation = listOf(
                senderAck.recordPresent,
                senderAck.snapshotKnown,
                senderAck.state,
                senderAck.expectedRecipientCount,
                senderAck.acknowledgedRecipientCount,
                senderAck.provisionalAcknowledgementCount,
                deliveryState,
                senderAck.remoteAckMessageCount,
                recipientAck.observerInstalled,
                recipientAck.recordPresent,
                recipientAck.state,
            ).joinToString("|")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("outbox_diag_group_delivery_failure", observation)
            })
            println(
                "OUTBOX_DIAG_GROUP_DELIVERY_FAILURE record=${senderAck.recordPresent} " +
                    "snapshotKnown=${senderAck.snapshotKnown} state=${senderAck.state} " +
                    "expected=${senderAck.expectedRecipientCount} " +
                    "acknowledged=${senderAck.acknowledgedRecipientCount} delivery=$deliveryState " +
                    "provisional=${senderAck.provisionalAcknowledgementCount} " +
                    "senderRemoteAckMessages=${senderAck.remoteAckMessageCount} " +
                    "recipientObserver=${recipientAck.observerInstalled} " +
                    "recipientAckRecord=${recipientAck.recordPresent} recipientAckState=${recipientAck.state}",
            )
        }
        check(partialDeliveryReached) {
            "The group message did not show the expected one-of-two delivery state"
        }
        println("OUTBOX_DIAG_GROUP_DELIVERY expected=2 acknowledged=1 delivered=false")

        restoreFresh(groupPeer, homeserver, groupPeerId, groupPeerPassword)
        groupPeer.openConversation(groupRoomId)
        awaitMessage(groupPeer, "offline group member decrypts message after reconnect") {
            it.body == groupMessageBody && !it.isOwn && it.eventId == senderGroupMessage.eventId
        }
        onStep("awaitGroupDeliveryComplete")
        check(awaitCondition("all group delivery acknowledgements") {
            val snapshot = sender.deliveryAckDiagnosticSnapshot(groupRoomId, senderGroupEventId)
            val message = sender.messages.value.firstOrNull { it.eventId == senderGroupEventId }
            snapshot.expectedRecipientCount == 2 &&
                snapshot.acknowledgedRecipientCount == 2 &&
                message?.deliveryState == "Delivered to all 2"
        }) { "The group message did not become delivered after every member acknowledged it" }
        println("OUTBOX_DIAG_GROUP_DELIVERY expected=2 acknowledged=2 delivered=true")

        onStep("verificationStateRestore")
        reportProgress(instrumentation, "core", "verificationStateRestore", "start")
        verifyPeerTrustStateRestore(sender, senderId, recipientId, roomId)
        reportProgress(instrumentation, "core", "verificationStateRestore", "complete")
        println("OUTBOX_DIAG_VERIFICATION_RESTORE sameSession=true trustVerified=true verifyActionHidden=true")

        stateFile.parentFile?.mkdirs()
        stateFile.writeText(JSONObject().put("roomId", roomId).toString())
        reportSafeResult(instrumentation, "core", "completed", "verified")
        println(
            "OUTBOX_DIAG_RESULT stage=core roomEncrypted=true recipientOfflineBacklog=true " +
                "delivered=true read=true typing=true replies=true reactions=true search=true voice=true " +
                "groupDeliveredPerMember=true localStoreMarkerClear=$localStoreMarkerClear " +
                "storeFiles=${localStoreScan.storeFiles} sqliteFiles=${localStoreScan.sqliteFiles} " +
                "walFiles=${localStoreScan.walFiles} cacheFiles=${localStoreScan.cacheFiles} " +
                "searchIndexFiles=${localStoreScan.searchIndexFiles} markerMatchFiles=${localStoreScan.markerMatchFiles}",
        )
        check(localStoreMarkerClear) {
            "The unique message marker appeared in an isolated sender/recipient store, cache, or search-index file " +
                "(files=${localStoreScan.markerMatchFiles})"
        }
    }

    private suspend fun verifyVoiceNoteRoundTrip(
        context: Context,
        roomId: String,
        marker: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        onStep: (String) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        onStep("voiceNotePermission")
        val hasRecordAudioPermission =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudioPermission) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO,
            )
        }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "The isolated app could not obtain microphone permission for the voice-note capture check"
        }

        val voiceFile = attachmentFixture(context, "voice-note-$marker.m4a")
        val durationMillis = 1_800L
        val recorder = MediaRecorder(context)
        try {
            onStep("voiceRecorderPrepare")
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(voiceFile.absolutePath)
            recorder.prepare()
            onStep("voiceRecorderStart")
            recorder.start()
            delay(durationMillis)
            onStep("voiceRecorderStop")
            recorder.stop()
        } catch (failure: Throwable) {
            voiceFile.delete()
            throw AssertionError("The isolated emulator could not record a short AAC voice-note fixture", failure)
        } finally {
            runCatching { recorder.release() }
        }

        try {
            assertTrue("The captured voice-note fixture was empty", voiceFile.isFile && voiceFile.length() > 0L)
            val fixtureBytes = voiceFile.readBytes()
            val fixtureHash = sha256(fixtureBytes)
            val voiceUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                voiceFile,
            )
            onStep("voiceUpload")
            sender.sendAttachment(roomId, voiceUri.toString(), audioDurationMillis = durationMillis)

            val sentVoiceMessage = awaitMessage(sender, "sender's uploaded voice note") {
                it.isOwn && it.eventId != null && it.attachment?.fileName == voiceFile.name
            }
            val attachment = checkNotNull(sentVoiceMessage.attachment)
            assertEquals(AttachmentKind.AUDIO, attachment.kind)
            assertTrue("The sent voice note did not retain an audio MIME type", attachment.mimeType.startsWith("audio/"))

            val receivedVoiceMessage = awaitMessage(recipient, "peer's encrypted voice note") {
                !it.isOwn && it.eventId == sentVoiceMessage.eventId &&
                    it.attachment?.let { received ->
                        received.fileName == voiceFile.name && received.kind == AttachmentKind.AUDIO
                    } == true
            }
            onStep("voicePeerDecrypt")
            assertTrue(
                "The received voice note did not expose encrypted Matrix media key metadata",
                listOf("\"key\"", "\"iv\"", "\"hashes\"").all {
                    receivedVoiceMessage.attachment?.sourceJson?.contains(it, ignoreCase = true) == true
                },
            )

            val decryptedFile = recipient.loadAttachmentForViewing(receivedVoiceMessage)
            try {
                assertEquals(fixtureBytes.size, decryptedFile.length().toInt())
                assertEquals(fixtureHash, sha256(decryptedFile.readBytes()))
            } finally {
                recipient.deleteTemporaryMediaFile(decryptedFile)
                fixtureBytes.fill(0)
            }
            onStep("voiceDeliveredAck")
            val eventId = checkNotNull(sentVoiceMessage.eventId)
            fun reportVoiceAckState(delivered: Boolean) {
                val senderAck = runCatching { sender.deliveryAckDiagnosticSnapshot(roomId, eventId) }.getOrNull()
                val recipientAck = runCatching { recipient.deliveryAckDiagnosticSnapshot(roomId, eventId) }.getOrNull()
                val summary =
                    "$delivered|true|${senderAck?.observerInstalled ?: "unavailable"}|" +
                        "${senderAck?.recordPresent ?: "unavailable"}|${senderAck?.state ?: "unavailable"}|" +
                        "${senderAck?.snapshotKnown ?: "unavailable"}|${senderAck?.expectedRecipientCount ?: -1}|" +
                        "${senderAck?.acknowledgedRecipientCount ?: -1}|" +
                        "${senderAck?.provisionalAcknowledgementCount ?: -1}|" +
                        "${senderAck?.remoteAckMessageCount ?: -1}|" +
                        "${recipientAck?.observerInstalled ?: "unavailable"}|" +
                        "${recipientAck?.recordPresent ?: "unavailable"}|${recipientAck?.state ?: "unavailable"}|" +
                        "${recipientAck?.remoteAckMessageCount ?: -1}|" +
                        "${senderAck?.sendQueueUpdatesObserverInstalled ?: "unavailable"}|" +
                        "${senderAck?.activeSnapshotReservation ?: "unavailable"}|" +
                        "${recipientAck?.sendQueueUpdatesObserverInstalled ?: "unavailable"}|" +
                        "${recipientAck?.activeSnapshotReservation ?: "unavailable"}"
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("outbox_diag_voice_ack", summary)
                })
                println(
                    "OUTBOX_DIAG_VOICE_ACK delivered=$delivered eventIdPresent=true " +
                        "senderObserver=${senderAck?.observerInstalled ?: "unavailable"} " +
                        "senderRecord=${senderAck?.recordPresent ?: "unavailable"} " +
                        "senderState=${senderAck?.state ?: "unavailable"} " +
                        "senderSnapshot=${senderAck?.snapshotKnown ?: "unavailable"} " +
                        "senderExpected=${senderAck?.expectedRecipientCount ?: -1} " +
                        "senderAcked=${senderAck?.acknowledgedRecipientCount ?: -1} " +
                        "senderProvisional=${senderAck?.provisionalAcknowledgementCount ?: -1} " +
                        "senderRemoteAckEvents=${senderAck?.remoteAckMessageCount ?: -1} " +
                        "recipientObserver=${recipientAck?.observerInstalled ?: "unavailable"} " +
                        "recipientRecord=${recipientAck?.recordPresent ?: "unavailable"} " +
                        "recipientState=${recipientAck?.state ?: "unavailable"} " +
                        "recipientRemoteAckEvents=${recipientAck?.remoteAckMessageCount ?: -1} " +
                        "senderQueueUpdates=${senderAck?.sendQueueUpdatesObserverInstalled ?: "unavailable"} " +
                        "senderSnapshotReservation=${senderAck?.activeSnapshotReservation ?: "unavailable"} " +
                        "recipientQueueUpdates=${recipientAck?.sendQueueUpdatesObserverInstalled ?: "unavailable"} " +
                        "recipientSnapshotReservation=${recipientAck?.activeSnapshotReservation ?: "unavailable"}",
                )
            }
            reportVoiceAckState(delivered = false)
            val voiceProjectionCategories = sender.timelineCategoriesForDiagnostic(roomId)
                .filterKeys { it.startsWith("OWN_LOCAL_") }
                .toSortedMap()
                .entries
                .joinToString(",") { (category, count) -> "$category=${count.coerceAtMost(999)}" }
                .ifBlank { "none" }
            println(
                "OUTBOX_DIAG_VOICE_PROJECTION pipeline=${sender.sendPipelineDiagnosticSnapshot(roomId)} " +
                    "categories=$voiceProjectionCategories",
            )
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString(
                    "outbox_diag_voice_projection",
                    "${sender.sendPipelineDiagnosticSnapshot(roomId)}|$voiceProjectionCategories",
                )
            })
            val delivered = awaitCondition("voice-note delivered acknowledgement", 45_000) {
                sender.messages.value.any {
                    it.isOwn && it.eventId == eventId && it.deliveryState == "Delivered"
                }
            }
            reportVoiceAckState(delivered)
            check(delivered) { "Voice-note delivery acknowledgement did not converge" }
            println(
                "OUTBOX_DIAG_VOICE_NOTE captured=true uploaded=true audioEvent=true encryptedSource=true " +
                    "peerDecrypted=true byteHashMatched=true deliveredAck=true",
            )
        } finally {
            voiceFile.delete()
        }
    }

    private fun scanIsolatedMessageStores(context: Context, marker: String): IsolatedStoreMarkerScan {
        val accountNames = listOf("sender", "recipient")
        val durableRoots = accountNames.map { account ->
            File(context.noBackupFilesDir, "peer-acceptance/$account")
        }
        val cacheRoots = accountNames.map { account ->
            File(context.cacheDir, "peer-acceptance/$account")
        }
        val allRoots = durableRoots + cacheRoots
        val files = allRoots.asSequence()
            .filter(File::exists)
            .flatMap { root -> root.walkTopDown().filter(File::isFile) }
            .distinctBy { file -> runCatching { file.canonicalPath }.getOrDefault(file.absolutePath) }
            .toList()
        val sqliteFiles = files.count { file ->
            file.name.lowercase().let { name ->
                name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3")
            }
        }
        val walFiles = files.count { file ->
            file.name.lowercase().let { name -> name.endsWith("-wal") || name.endsWith("-shm") }
        }
        val cacheFiles = files.count { file ->
            cacheRoots.any { root ->
                runCatching { file.canonicalPath.startsWith(root.canonicalPath + File.separator) }
                    .getOrDefault(false)
            }
        }
        val searchIndexFiles = files.count { file ->
            generateSequence(file.parentFile) { directory -> directory.parentFile }
                .any { directory -> directory.name == "search-index" }
        }
        val markerMatchFiles = files.count { file ->
            file.name.contains(marker, ignoreCase = true) ||
                String(
                    runCatching { file.readBytes() }.getOrElse { failure ->
                        // SQLite may remove/recreate a WAL or cache file while the
                        // acceptance scan walks live stores. A file that vanished
                        // between enumeration and reading has no remaining bytes to
                        // leak; preserve real read errors for files that still exist.
                        if (!file.exists()) byteArrayOf() else throw failure
                    },
                    Charsets.ISO_8859_1,
                ).contains(marker, ignoreCase = true)
        }
        return IsolatedStoreMarkerScan(
            storeFiles = files.size,
            sqliteFiles = sqliteFiles,
            walFiles = walFiles,
            cacheFiles = cacheFiles,
            searchIndexFiles = searchIndexFiles,
            markerMatchFiles = markerMatchFiles,
        )
    }

    private suspend fun verifyMessageEditingAndRedaction(
        roomId: String,
        marker: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        onStep: (String) -> Unit,
    ) {
        val originalBody = "peer-edit-original-$marker"
        val editedBody = "peer-edit-updated-$marker"
        onStep("editProbeSend")
        reportProgress(InstrumentationRegistry.getInstrumentation(), "core", "editProbeSend", "start")
        sender.sendText(roomId, originalBody)
        onStep("editProbeAwaitSenderEcho")
        reportProgress(InstrumentationRegistry.getInstrumentation(), "core", "editProbeAwaitSenderEcho", "start")
        val ownMessage = try {
            awaitMessage(sender, "own text message is editable") {
                it.body == originalBody && it.isOwn && it.isRemote && it.eventId != null
            }
        } catch (failure: Throwable) {
            val matching = sender.messages.value.filter { it.body == originalBody && it.isOwn }
            val categories = sender.timelineCategoriesForDiagnostic(roomId)
                .toSortedMap()
                .entries
                .joinToString(",") { (category, count) -> "$category=${count.coerceAtMost(999)}" }
                .ifBlank { "none" }
            println(
                "OUTBOX_DIAG_EDIT_ECHO pipeline=${sender.sendPipelineDiagnosticSnapshot(roomId)} " +
                    "matches=${matching.size.coerceAtMost(99)} " +
                    "remote=${matching.count { it.isRemote }.coerceAtMost(99)} " +
                    "eventId=${matching.count { it.eventId != null }.coerceAtMost(99)} " +
                    "states=${matching.map { it.deliveryState }.distinct().joinToString(",").ifBlank { "none" }} " +
                    "categories=$categories",
            )
            throw failure
        }
        reportProgress(InstrumentationRegistry.getInstrumentation(), "core", "editProbeAwaitSenderEcho", "complete")
        onStep("editProbeAwaitPeerEcho")
        reportProgress(InstrumentationRegistry.getInstrumentation(), "core", "editProbeAwaitPeerEcho", "start")
        val peerMessage = awaitMessage(recipient, "peer text message is not editable") {
            it.body == originalBody && !it.isOwn && it.isRemote && it.eventId == ownMessage.eventId
        }
        reportProgress(InstrumentationRegistry.getInstrumentation(), "core", "editProbeAwaitPeerEcho", "complete")
        val ownRows = sender.messages.value.filter { it.body == originalBody && it.isOwn }
        val rowCapabilities = ownRows.joinToString(",") {
            "remote=${it.isRemote}:event=${it.eventId != null}:edit=${it.canEdit}:" +
                "redact=${it.canRedact}:textFallback=${it.isOptimisticTextEcho}"
        }.ifBlank { "none" }
        assertTrue(
            "Only an acknowledged own remote text message should expose Edit " +
                "(selected remote=${ownMessage.isRemote} event=${ownMessage.eventId != null} " +
                "redact=${ownMessage.canRedact} fallback=${ownMessage.isOptimisticTextEcho}; rows=$rowCapabilities)",
            ownMessage.canEdit,
        )
        assertTrue("Only an acknowledged own remote message should expose Remove", ownMessage.canRedact)
        assertFalse("A peer message must not expose Edit", peerMessage.canEdit)
        assertFalse("A peer message must not expose Remove", peerMessage.canRedact)
        assertTrue("The SDK repository must reject editing a peer message", runCatching {
            recipient.editMessage(roomId, peerMessage, "unauthorized edit")
        }.isFailure)
        assertTrue("The SDK repository must reject removing a peer message", runCatching {
            recipient.redactMessage(roomId, peerMessage)
        }.isFailure)

        onStep("editProbeEdit")
        reportProgress(InstrumentationRegistry.getInstrumentation(), "core", "editProbeEdit", "start")
        try {
            sender.editMessage(roomId, ownMessage, editedBody)
        } catch (failure: Throwable) {
            val reason = when (failure.message) {
                "An edited message cannot be empty" -> "empty_body_guard"
                "The edited message is unchanged" -> "unchanged_body_guard"
                "Only your sent text messages can be edited" -> "ownership_or_editability_guard"
                "Editing is disabled because this conversation is not encrypted" -> "encryption_guard"
                "This edit could not be prepared" -> "content_creation_unavailable"
                else -> "sdk_or_internal_failure"
            }
            println(
                "OUTBOX_DIAG_EDIT_SUBMIT result=failed reason=$reason " +
                    "failureClass=${failure.javaClass.simpleName}",
            )
            throw failure
        }
        println("OUTBOX_DIAG_EDIT_SUBMIT result=accepted")
        reportProgress(InstrumentationRegistry.getInstrumentation(), "core", "editProbeEdit", "complete")
        onStep("awaitEditedSenderProjection")
        val senderEditObserved = awaitCondition("encrypted edit updates sender timeline") {
            sender.messages.value.any {
                it.eventId == ownMessage.eventId && it.body == editedBody && it.isOwn
            }
        }
        val senderProjection = sender.messages.value.firstOrNull { it.eventId == ownMessage.eventId }
        val senderEditRevisions = runCatching {
            sender.editRevisionDiagnosticForTest(roomId, checkNotNull(ownMessage.eventId), editedBody)
        }.getOrDefault("unavailable")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString(
                "outbox_diag_edit_projection_sender",
                listOf(
                    senderEditObserved,
                    senderProjection != null,
                    senderProjection?.isRemote == true,
                    senderProjection?.body == originalBody,
                    senderProjection?.body == editedBody,
                    senderProjection?.canEdit == true,
                    senderProjection?.isEdited == true,
                    senderEditRevisions,
                ).joinToString("|") { it.toString() },
            )
        })
        check(senderEditObserved) { "The sender timeline did not apply the encrypted edit" }
        val editedOwnMessage = checkNotNull(senderProjection)
        onStep("awaitEditedPeerProjection")
        val recipientEditObserved = awaitCondition("encrypted edit decrypts for recipient") {
            recipient.messages.value.any {
                it.eventId == ownMessage.eventId && it.body == editedBody && !it.isOwn
            }
        }
        val recipientProjection = recipient.messages.value.firstOrNull { it.eventId == ownMessage.eventId }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString(
                "outbox_diag_edit_projection_recipient",
                listOf(
                    recipientEditObserved,
                    recipientProjection != null,
                    recipientProjection?.isRemote == true,
                    recipientProjection?.body == originalBody,
                    recipientProjection?.body == editedBody,
                    recipientProjection?.isEdited == true,
                ).joinToString("|") { it.toString() },
            )
        })
        check(recipientEditObserved) { "The recipient timeline did not apply the encrypted edit" }

        onStep("awaitRedactionSenderProjection")
        sender.redactMessage(roomId, editedOwnMessage)
        awaitMessage(sender, "redaction updates sender timeline") {
            it.eventId == ownMessage.eventId && it.body == "Message removed" && it.isOwn
        }
        onStep("awaitRedactionPeerProjection")
        awaitMessage(recipient, "redaction syncs to recipient timeline", timeoutMillis = 90_000) {
            it.eventId == ownMessage.eventId && it.body == "Message removed" && !it.isOwn
        }
        println("OUTBOX_DIAG_MESSAGE_MUTATION edit=true redact=true peerActionsRejected=true")
    }

    private suspend fun assertNewUnverifiedDeviceCannotDecrypt(
        homeserver: String,
        recipientId: String,
        recipientPassword: String,
        marker: String,
        roomId: String,
        sender: MatrixRepository,
        recipient: MatrixRepository,
        appContext: Context,
        onStep: (String) -> Unit,
    ) {
        fun progress(step: String, state: String) {
            onStep(step)
            reportProgress(InstrumentationRegistry.getInstrumentation(), "core", step, state)
        }
        // This fresh repository logs in to the already-verified account with a new device ID
        // and an empty crypto store. It does not import the account's private cross-signing keys.
        val addedDevice = newIsolatedRepository(appContext, "recipient-added-device")
        try {
            progress("unverifiedAddedDeviceLogin", "start")
            loginFresh(addedDevice, homeserver, recipientId, recipientPassword)
            progress("unverifiedAddedDeviceLogin", "complete")
            progress("unverifiedAddedDeviceRoomSync", "start")
            await("new device sees the existing encrypted room") {
                addedDevice.refreshConversations()
                isJoinedEncrypted(addedDevice, roomId)
            }
            progress("unverifiedAddedDeviceRoomSync", "complete")
            progress("unverifiedAddedDeviceOpenRoom", "start")
            addedDevice.openConversation(roomId)
            delay(2_000)
            progress("unverifiedAddedDeviceOpenRoom", "complete")

            progress("unverifiedAddedDeviceTrust", "start")
            val localIdentityState = allowlistedIdentityState(
                addedDevice.ownVerificationIdentityStateForDiagnostic(),
            )
            val crossSignature = addedDeviceCrossSignatureStatus(appContext, recipientId)
            progress("unverifiedAddedDeviceTrust", "complete")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString(
                    "outbox_diag_added_device_trust",
                    "$localIdentityState|${crossSignature.selfSigningKeyPresent}|${crossSignature.deviceKeyCrossSigned}",
                )
            })
            check(crossSignature.selfSigningKeyPresent) {
                "The added device could not establish whether its device key is cross-signed"
            }
            check(!crossSignature.deviceKeyCrossSigned) {
                "The fresh login was cross-signed before the unverified-device check"
            }

            val body = "new-device-unverified-$marker"

            progress("unverifiedProbeSend", "start")
            sender.openConversation(roomId)
            try {
                sender.sendText(roomId, body)
            } catch (failure: IllegalStateException) {
                // Some SDK versions fail closed when an unverified device is present in a room.
                // Accept only an explicitly trust-related rejection and prove no event was sent.
                val reason = failure.message.orEmpty().lowercase()
                check(reason.contains("unverified") || reason.contains("verification") || reason.contains("trust")) {
                    "The sender rejected the unverified-device probe for an unrelated reason"
                }
                check(sender.messages.value.none { it.body == body && it.isOwn && it.isRemote }) {
                    "A remotely accepted probe was reported as trust-blocked"
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("outbox_diag_unverified_probe", "blocked-before-send")
                })
                println("OUTBOX_DIAG_UNVERIFIED_PROBE blockedBeforeSend=true")
                return
            }
            val senderEvent = awaitMessage(sender, "sender confirms the unverified-device probe") {
                it.body == body && it.isOwn && it.isRemote && it.eventId != null
            }
            val eventId = checkNotNull(senderEvent.eventId)
            progress("unverifiedProbeSend", "complete")
            progress("unverifiedProbePeerDecrypt", "start")
            awaitMessage(recipient, "existing verified device decrypts the probe") {
                it.eventId == eventId && it.body == body && !it.isOwn
            }
            progress("unverifiedProbePeerDecrypt", "complete")

            progress("unverifiedProbeUtdProjection", "start")
            val newDeviceUtdProjection = awaitMessage(
                addedDevice,
                "new device projects the same encrypted event as undecryptable",
            ) {
                it.eventId == eventId && !it.isOwn && it.body == "Unable to decrypt this message"
            }
            progress("unverifiedProbeUtdProjection", "complete")
            val projectionStates = linkedSetOf("utd")
            val observationWindowMillis = 30_000L
            val newDeviceDecrypted = withTimeoutOrNull(observationWindowMillis) {
                while (true) {
                    val eventProjection = addedDevice.messages.value.firstOrNull {
                        it.eventId == eventId && !it.isOwn
                    }
                    val projectionState = when {
                        eventProjection == null -> "missing"
                        eventProjection.body == body -> "plaintext"
                        eventProjection.body == "Unable to decrypt this message" -> "utd"
                        else -> "other"
                    }
                    projectionStates.add(projectionState)
                    if (projectionState == "plaintext") return@withTimeoutOrNull true
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: false
            val finalIdentityState = allowlistedIdentityState(
                addedDevice.ownVerificationIdentityStateForDiagnostic(),
            )
            val finalCrossSignature = addedDeviceCrossSignatureStatus(appContext, recipientId)
            val newDeviceUndecryptable = newDeviceUtdProjection.eventId == eventId &&
                newDeviceUtdProjection.body == "Unable to decrypt this message"
            val finalProjectionState = projectionStates.last()
            val projectionTransition = when {
                newDeviceDecrypted -> "utd-to-plaintext"
                "other" in projectionStates -> "utd-to-other"
                "missing" in projectionStates -> "utd-to-missing"
                else -> "utd-only"
            }
            val deviceObservation =
                "$localIdentityState|${crossSignature.selfSigningKeyPresent}|${crossSignature.deviceKeyCrossSigned}" +
                    "|true|$newDeviceUndecryptable|$newDeviceDecrypted|unavailable"
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("outbox_diag_added_device_result", deviceObservation)
                putString(
                    "outbox_diag_added_device_final_trust",
                    "$finalIdentityState|${finalCrossSignature.selfSigningKeyPresent}|${finalCrossSignature.deviceKeyCrossSigned}",
                )
                putString(
                    "outbox_diag_added_device_projection",
                    "utd|$finalProjectionState|$projectionTransition|${observationWindowMillis / 1_000}",
                )
            })
            println(
                "OUTBOX_DIAG_UNVERIFIED_DEVICE addedDevice=true localIdentity=$localIdentityState " +
                    "selfSigningKeyPresent=true deviceCrossSigned=false primaryDecrypted=true " +
                    "newDeviceUndecryptable=$newDeviceUndecryptable newDeviceDecrypted=$newDeviceDecrypted " +
                    "roomKeyRecipientList=unavailable",
            )
            println(
                "OUTBOX_DIAG_ADDED_DEVICE_PROJECTION before=utd after=$finalProjectionState " +
                    "transition=$projectionTransition windowSeconds=${observationWindowMillis / 1_000}",
            )
            println(
                "OUTBOX_DIAG_ADDED_DEVICE_FINAL_TRUST localIdentity=$finalIdentityState " +
                    "selfSigningKeyPresent=${finalCrossSignature.selfSigningKeyPresent} " +
                    "deviceCrossSigned=${finalCrossSignature.deviceKeyCrossSigned}",
            )
            check(newDeviceUndecryptable) {
                "The newly added unverified device did not surface the probe as undecryptable"
            }
            check(finalIdentityState == "unverified" && !finalCrossSignature.deviceKeyCrossSigned) {
                "The additional device did not remain unverified throughout the diagnostic window"
            }
            check(!newDeviceDecrypted) {
                "A newly added device without the account's cross-signing secrets decrypted the probe"
            }
        } finally {
            addedDevice.close()
        }
    }

    private suspend fun addedDeviceCrossSignatureStatus(
        appContext: Context,
        userId: String,
    ): DeviceCrossSignatureStatus = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val deviceContext = isolatedContext(appContext, "recipient-added-device")
        val session = checkNotNull(DeviceVault(deviceContext).loadSession()) {
            "The additional Matrix device did not persist its diagnostic session"
        }
        val connection = URL(
            "${session.homeserverUrl.trimEnd('/')}/_matrix/client/v3/keys/query",
        ).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            connection.setRequestProperty("Content-Type", "application/json")
            val request = JSONObject().put(
                "device_keys",
                JSONObject().put(userId, org.json.JSONArray().put(session.deviceId)),
            )
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(request.toString())
            }
            val status = connection.responseCode
            check(status in 200..299) {
                "The diagnostic homeserver could not return the added device's public keys (HTTP $status)"
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText())
            }
            val selfSigningKeys = response.optJSONObject("self_signing_keys")
                ?.optJSONObject(userId)
                ?.optJSONObject("keys")
            val selfSigningKeyId = selfSigningKeys?.keys()?.asSequence()?.firstOrNull()
            val deviceKeys = response.optJSONObject("device_keys")
                ?.optJSONObject(userId)
                ?.optJSONObject(session.deviceId)
            val signatures = deviceKeys?.optJSONObject("signatures")?.optJSONObject(userId)
            DeviceCrossSignatureStatus(
                selfSigningKeyPresent = selfSigningKeyId != null,
                deviceKeyCrossSigned = selfSigningKeyId != null && signatures?.has(selfSigningKeyId) == true,
            )
        } finally {
            connection.disconnect()
        }
    }

    private data class IsolatedStoreMarkerScan(
        val storeFiles: Int,
        val sqliteFiles: Int,
        val walFiles: Int,
        val cacheFiles: Int,
        val searchIndexFiles: Int,
        val markerMatchFiles: Int,
    )

    private data class DeviceCrossSignatureStatus(
        val selfSigningKeyPresent: Boolean,
        val deviceKeyCrossSigned: Boolean,
    )

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
        assertExactlyOneAttachmentAfterQuietWindow(sender, recipient, roomId, fileName)
        assertExactlyOneAttachmentAfterQuietWindow(sender, recipient, roomId, preEnqueueFileName)
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
        peerUserId: String,
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
        onVerificationStep("verificationSeedDivergentRoutes")
        seedDivergentVerificationRoutes(sender, recipient, roomId, onVerificationStep)
        onVerificationStep("verificationRequest")
        var controlInviteSeen = false
        var controlRoomHiddenAfterJoin = false
        var verificationControlRoomId: String? = null
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
            verificationControlRoomId = controlRoomInvite.roomId
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
        coroutineScope {
            val senderApproval = async {
                println("OUTBOX_DIAG_VERIFY_APPROVAL peer=sender phase=start")
                sender.approveVerification()
                println("OUTBOX_DIAG_VERIFY_APPROVAL peer=sender phase=complete")
            }
            val recipientApproval = async {
                println("OUTBOX_DIAG_VERIFY_APPROVAL peer=recipient phase=start")
                recipient.approveVerification()
                println("OUTBOX_DIAG_VERIFY_APPROVAL peer=recipient phase=complete")
            }
            senderApproval.await()
            recipientApproval.await()
        }
        val verificationEventsAfterApproval = runCatching {
            sender.verificationProtocolEventCountsForDiagnostic(
                checkNotNull(verificationControlRoomId),
                peerUserId,
            )
        }.getOrDefault("http=0")
        println("OUTBOX_DIAG_VERIFY_EVENTS phase=after-approval $verificationEventsAfterApproval")
        onVerificationStep("verificationComplete")
        val verificationCompleted = awaitCondition("verified peers", 45_000) {
            sender.verification.value?.status == DeviceVerificationStatus.VERIFIED &&
                recipient.verification.value?.status == DeviceVerificationStatus.VERIFIED
        }
        if (!verificationCompleted) {
            fun safeStatus(repository: MatrixRepository): String = when (repository.verification.value?.status) {
                DeviceVerificationStatus.REQUESTING -> "requesting"
                DeviceVerificationStatus.INCOMING_REQUEST -> "incoming-request"
                DeviceVerificationStatus.WAITING_FOR_ACCEPT -> "waiting-for-accept"
                DeviceVerificationStatus.COMPARING -> "comparing"
                DeviceVerificationStatus.CONFIRMING -> "confirming"
                DeviceVerificationStatus.VERIFIED -> "verified"
                DeviceVerificationStatus.CANCELLED -> "cancelled"
                DeviceVerificationStatus.FAILED -> "failed"
                null -> "missing"
            }
            val senderStatus = safeStatus(sender)
            val recipientStatus = safeStatus(recipient)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("outbox_diag_verification_final", "$senderStatus|$recipientStatus")
            })
            println("OUTBOX_DIAG_VERIFICATION_FINAL sender=$senderStatus recipient=$recipientStatus")
            val protocolEvents = runCatching {
                sender.verificationProtocolEventCountsForDiagnostic(
                    checkNotNull(verificationControlRoomId),
                    peerUserId,
                )
            }.getOrDefault("http=0")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("outbox_diag_verification_events", protocolEvents)
                putString(
                    "outbox_diag_verification_callbacks",
                    "${sender.verificationCallbackSummaryForDiagnostic()}|" +
                        recipient.verificationCallbackSummaryForDiagnostic(),
                )
            })
            println("OUTBOX_DIAG_VERIFY_EVENTS $protocolEvents")
            println(
                "OUTBOX_DIAG_VERIFY_CALLBACKS sender=${sender.verificationCallbackSummaryForDiagnostic()} " +
                    "recipient=${recipient.verificationCallbackSummaryForDiagnostic()}",
            )
        }
        check(verificationCompleted) { "Both fresh accounts did not complete device verification" }
        onVerificationStep("verificationPeerTrust")
        var senderTrustsRecipient = false
        var recipientTrustsSender = false
        val peerIdentitiesConverged = withTimeoutOrNull(60_000) {
            while (true) {
                senderTrustsRecipient = runCatching { sender.isPeerVerified(roomId) }.getOrDefault(false)
                recipientTrustsSender = runCatching { recipient.isPeerVerified(roomId) }.getOrDefault(false)
                if (senderTrustsRecipient && recipientTrustsSender) return@withTimeoutOrNull true
                delay(1_000)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true
        val senderOwnIdentity = allowlistedIdentityState(sender.ownVerificationIdentityStateForDiagnostic())
        val recipientOwnIdentity = allowlistedIdentityState(recipient.ownVerificationIdentityStateForDiagnostic())
        val senderPeerIdentity = allowlistedPeerIdentityState(sender.peerVerificationIdentityStateForDiagnostic(roomId))
        val recipientPeerIdentity = allowlistedPeerIdentityState(recipient.peerVerificationIdentityStateForDiagnostic(roomId))
        val trustObservation = "$senderTrustsRecipient|$recipientTrustsSender"
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("outbox_diag_peer_trust", trustObservation)
            putString("outbox_diag_identity_convergence", "$peerIdentitiesConverged|$senderOwnIdentity|$recipientOwnIdentity")
            putString("outbox_diag_peer_identity_state", "$senderPeerIdentity|$recipientPeerIdentity")
        })
        println(
            "OUTBOX_DIAG_PEER_TRUST converged=$peerIdentitiesConverged " +
                "senderTrustsRecipient=$senderTrustsRecipient recipientTrustsSender=$recipientTrustsSender " +
                "senderOwnIdentity=$senderOwnIdentity recipientOwnIdentity=$recipientOwnIdentity " +
                "senderPeerIdentity=$senderPeerIdentity recipientPeerIdentity=$recipientPeerIdentity",
        )
        if (!peerIdentitiesConverged) {
            val protocolEvents = runCatching {
                sender.verificationProtocolEventCountsForDiagnostic(
                    checkNotNull(verificationControlRoomId),
                    peerUserId,
                )
            }.getOrDefault("http=0")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("outbox_diag_verification_events", protocolEvents)
                putString(
                    "outbox_diag_verification_callbacks",
                    "${sender.verificationCallbackSummaryForDiagnostic()}|" +
                        recipient.verificationCallbackSummaryForDiagnostic(),
                )
            })
            println("OUTBOX_DIAG_VERIFY_EVENTS $protocolEvents")
            println(
                "OUTBOX_DIAG_VERIFY_CALLBACKS sender=${sender.verificationCallbackSummaryForDiagnostic()} " +
                    "recipient=${recipient.verificationCallbackSummaryForDiagnostic()}",
            )
        }
        check(peerIdentitiesConverged) {
            "The peers completed SAS, but their cross-signing trust did not converge in both directions"
        }
    }

    private suspend fun seedDivergentVerificationRoutes(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        encryptedRoomId: String,
        onVerificationStep: (String) -> Unit,
    ) {
        onVerificationStep("verificationSeedCreateSenderRoom")
        val senderCreatedRoomId = sender.createVerificationControlRoomForDiagnostic(encryptedRoomId)
        onVerificationStep("verificationSeedWaitRecipientInvite")
        await("recipient invitation to the sender's verification channel") {
            recipient.refreshConversations()
            recipient.conversations.value.any {
                it.roomId == senderCreatedRoomId && it.isVerificationControl && it.membership == "INVITED"
            }
        }
        onVerificationStep("verificationSeedJoinRecipientRoom")
        recipient.joinVerificationControlRoom(senderCreatedRoomId)

        onVerificationStep("verificationSeedCreateRecipientRoom")
        val recipientCreatedRoomId = recipient.createVerificationControlRoomForDiagnostic(encryptedRoomId)
        onVerificationStep("verificationSeedWaitSenderInvite")
        await("sender invitation to the recipient's verification channel") {
            sender.refreshConversations()
            sender.conversations.value.any {
                it.roomId == recipientCreatedRoomId && it.isVerificationControl && it.membership == "INVITED"
            }
        }
        onVerificationStep("verificationSeedJoinSenderRoom")
        sender.joinVerificationControlRoom(recipientCreatedRoomId)

        onVerificationStep("verificationSeedCheckRoutes")
        sender.setVerificationControlRoomRouteForDiagnostic(encryptedRoomId, senderCreatedRoomId)
        check(senderCreatedRoomId != recipientCreatedRoomId) {
            "The diagnostic did not create two distinct verification channels"
        }
        check(sender.verificationControlRoomRouteForDiagnostic(encryptedRoomId) == senderCreatedRoomId)
        check(recipient.verificationControlRoomRouteForDiagnostic(encryptedRoomId) == recipientCreatedRoomId)
        println("OUTBOX_DIAG_VERIFY_CONTROL seededDivergentRoutes=true")
    }

    private suspend fun verifyRapidTextBurst(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        roomId: String,
        marker: String,
    ) {
        val bodies = (1..5).map { "peer-burst-$marker-$it" }
        bodies.forEach { body ->
            check(sender.sendText(roomId, body)) { "The rapid encrypted text send was not accepted by the SDK queue" }
        }
        val reachedBothClients = awaitCondition("rapid text burst reaches both clients exactly once") {
            val senderRows = sender.messages.value.filter { it.isOwn && it.body in bodies }
            val recipientRows = recipient.messages.value.filter { !it.isOwn && it.body in bodies }
            senderRows.size == bodies.size && recipientRows.size == bodies.size &&
                senderRows.all { it.eventId != null } && recipientRows.all { it.eventId != null } &&
                senderRows.map { it.body } == bodies && recipientRows.map { it.body } == bodies &&
                senderRows.mapNotNull(ChatMessage::eventId) == recipientRows.mapNotNull(ChatMessage::eventId)
        }
        if (!reachedBothClients) {
            val senderRows = sender.messages.value.filter { it.isOwn && it.body in bodies }
            val recipientRows = recipient.messages.value.filter { !it.isOwn && it.body in bodies }
            fun sequence(rows: List<ChatMessage>) = rows.mapNotNull { row ->
                bodies.indexOf(row.body).takeIf { it >= 0 }?.plus(1)
            }.joinToString(",")
            println(
                "OUTBOX_DIAG_RAPID_BURST_FAILURE senderSequence=${sequence(senderRows)} " +
                    "senderEventIds=${senderRows.count { it.eventId != null }} " +
                    "senderStates=${senderRows.map { safeToken(it.deliveryState) }.distinct().sorted().joinToString(",")}" +
                    " senderConnection=${safeToken(sender.connection.value)} " +
                    "recipientSequence=${sequence(recipientRows)} " +
                    "recipientEventIds=${recipientRows.count { it.eventId != null }} " +
                    "recipientConnection=${safeToken(recipient.connection.value)}",
            )
            throw IllegalStateException("The rapid encrypted text burst did not reach both clients exactly once")
        }
        delay(1_500)
        val senderRows = sender.messages.value.filter { it.isOwn && it.body in bodies }
        val recipientRows = recipient.messages.value.filter { !it.isOwn && it.body in bodies }
        assertEquals("The sender timeline must show each rapid text send exactly once", bodies, senderRows.map { it.body })
        assertEquals("The recipient timeline must show each rapid text send exactly once", bodies, recipientRows.map { it.body })
        assertEquals(
            "Sender and recipient must project the same event IDs in send order",
            senderRows.mapNotNull(ChatMessage::eventId),
            recipientRows.mapNotNull(ChatMessage::eventId),
        )
    }

    private suspend fun verifyAppLifecycleRecovery(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        roomId: String,
        marker: String,
    ) {
        sender.onAppBackgrounded()
        await("sender sync and client pause in background") {
            val snapshot = sender.lifecycleDiagnosticSnapshot()
            !snapshot.appInForeground && snapshot.clientPausedForBackground &&
                snapshot.syncServiceStoppedForBackground && !snapshot.syncServiceRunning &&
                snapshot.connection == "Offline"
        }

        val offlineMessageBody = "lifecycle-offline-$marker"
        assertTrue("The peer's message must queue while the sender is backgrounded", recipient.sendText(roomId, offlineMessageBody))
        val peerMessage = awaitMessage(recipient, "peer's backgrounded-sender message") {
            it.body == offlineMessageBody && it.isOwn && it.eventId != null
        }
        check(sender.messages.value.none { it.body == offlineMessageBody }) {
            "The backgrounded sender unexpectedly synced the peer message before resume"
        }

        sender.onAppForegrounded()
        await("sender sync returns to Connected") {
            val snapshot = sender.lifecycleDiagnosticSnapshot()
            snapshot.appInForeground && !snapshot.clientPausedForBackground &&
                !snapshot.syncServiceStoppedForBackground && snapshot.syncServiceRunning &&
                snapshot.connection == "Connected"
        }
        val receivedAfterResume = awaitMessage(sender, "peer's message after sender resumes") {
            it.body == offlineMessageBody && !it.isOwn && it.eventId == peerMessage.eventId
        }
        check(receivedAfterResume.eventId != null)

        val resumedMessageBody = "lifecycle-resumed-$marker"
        assertTrue("The foregrounded sender must be able to send again", sender.sendText(roomId, resumedMessageBody))
        val senderMessage = awaitMessage(sender, "sender's post-resume message") {
            it.body == resumedMessageBody && it.isOwn && it.eventId != null
        }
        val peerReceivedAfterResume = awaitMessage(recipient, "peer receives sender's post-resume message") {
            it.body == resumedMessageBody && !it.isOwn && it.eventId == senderMessage.eventId
        }
        check(peerReceivedAfterResume.eventId != null)
    }

    private suspend fun verifyDirectConversationReuse(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        recipientId: String,
        roomId: String,
    ) {
        val repeatedRoomIds = coroutineScope {
            (1..3).map {
                async {
                    sender.createEncryptedConversation(
                        invitedUserIds = listOf(recipientId),
                        name = "Repeated direct-room probe",
                    )
                }
            }.map { it.await() }
        }
        check(repeatedRoomIds.all { it == roomId }) {
            "Repeated direct conversation requests did not reuse the existing encrypted room"
        }
        sender.refreshConversations()
        recipient.refreshConversations()
        check(sender.conversations.value.count { it.roomId == roomId } == 1 &&
            recipient.conversations.value.count { it.roomId == roomId } == 1
        ) { "Repeated direct conversation requests produced duplicate conversation rows" }
    }

    private suspend fun verifyPeerTrustStateRestore(
        sender: MatrixRepository,
        senderId: String,
        recipientId: String,
        roomId: String,
    ) {
        check(sender.isPeerVerified(roomId)) { "The verified peer trust was not available before store restore" }
        sender.close()
        assertEquals("The repository must restore the same account session", senderId, sender.restoreSession())
        awaitConnected(sender)
        sender.openConversation(roomId)
        await("verified contact state is restored for the conversation UI") {
            sender.peerTrust.value == PeerTrustStatus.VERIFIED
        }
        check(!PeerVerificationActionPolicy.shouldShowVerifyAction(
            isEncrypted = true,
            isGroup = false,
            currentPeerUserId = recipientId,
            peerTrust = sender.peerTrust.value,
            verification = sender.verification.value,
        )) { "The Verify action remained visible after restored peer trust became verified" }
    }

    private suspend fun verifyEncryptedJpegRoundTrip(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        roomId: String,
        marker: String,
        instrumentation: android.app.Instrumentation,
    ) {
        val imageBytes = createTinyJpeg()
        val source = File(
            instrumentation.targetContext.cacheDir,
            "private-messenger/media/peer-image-$marker.jpg",
        ).apply {
            parentFile?.mkdirs()
            writeBytes(imageBytes)
        }
        try {
            val sourceUri = FileProvider.getUriForFile(
                instrumentation.targetContext,
                "${instrumentation.targetContext.packageName}.files",
                source,
            )
            sender.sendAttachment(roomId, sourceUri.toString())
            val received = awaitMessage(recipient, "encrypted JPEG attachment") {
                it.attachment?.fileName == source.name && !it.isOwn && it.eventId != null
            }
            val attachment = checkNotNull(received.attachment)
            check(attachment.kind == AttachmentKind.IMAGE) { "The encrypted JPEG was not identified as an image" }
            check(attachment.mimeType.equals("image/jpeg", ignoreCase = true)) {
                "The receiver did not preserve the JPEG MIME type: ${attachment.mimeType}"
            }
            val opened = recipient.loadAttachmentForViewing(received)
            val appContext = instrumentation.targetContext
            val viewerFile = File(
                appContext.cacheDir,
                "private-messenger/media/peer-acceptance-viewer/${source.name}",
            )
            try {
                check(opened.isFile && opened.readBytes().contentEquals(imageBytes)) {
                    "The receiver did not decrypt the JPEG bytes exactly"
                }
                viewerFile.parentFile?.mkdirs()
                opened.copyTo(viewerFile, overwrite = true)
                val viewerUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.files",
                    viewerFile,
                )
                val viewerBytes = instrumentation.targetContext.contentResolver
                    .openInputStream(viewerUri)?.use { it.readBytes() }
                check(viewerBytes?.contentEquals(imageBytes) == true) {
                    "The decrypted JPEG FileProvider URI did not return the original bytes"
                }
            } finally {
                recipient.cleanupExternalViewerFiles()
                viewerFile.delete()
                viewerFile.parentFile?.delete()
            }
            check(!opened.exists()) { "The decrypted JPEG cache file was not removed after viewer cleanup" }
        } finally {
            source.delete()
        }
    }

    private fun createTinyJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(37, 105, 87))
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun assertExactlyOneAttachmentAfterQuietWindow(
        sender: MatrixRepository,
        recipient: MatrixRepository,
        roomId: String,
        fileName: String,
    ) {
        var stableSinceMillis: Long? = null
        var senderEventCount = 0
        var recipientEventCount = 0
        val settled = awaitCondition("settled attachment event counts", 20_000) {
            val senderEvents = sender.messages.value
                .filter { it.isOwn && it.attachment?.fileName == fileName }
                .mapNotNull(ChatMessage::eventId)
                .toSet()
            val recipientEvents = recipient.messages.value
                .filter { !it.isOwn && it.attachment?.fileName == fileName }
                .mapNotNull(ChatMessage::eventId)
                .toSet()
            senderEventCount = senderEvents.size
            recipientEventCount = recipientEvents.size
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
        }
        if (!settled) {
            val siblingFileName = when {
                fileName.startsWith("upload-") -> "pre-enqueue-${fileName.removePrefix("upload-")}"
                fileName.startsWith("pre-enqueue-") -> "upload-${fileName.removePrefix("pre-enqueue-")}"
                else -> ""
            }
            val senderAttachments = sender.messages.value
                .filter(ChatMessage::isOwn)
                .mapNotNull { message -> message.attachment?.let { message to it } }
            val senderExpected = senderAttachments.filter { (_, attachment) -> attachment.fileName == fileName }
            val senderSibling = senderAttachments.filter { (_, attachment) -> attachment.fileName == siblingFileName }
            val recipientCanFetchSenderExpected = senderExpected.mapNotNull { it.first.eventId }
                .any { eventId -> recipient.canFetchEventForDiagnostic(roomId, eventId) }
            val recipientCanFetchSenderSibling = senderSibling.mapNotNull { it.first.eventId }
                .any { eventId -> recipient.canFetchEventForDiagnostic(roomId, eventId) }
            val peerUtdCount = recipient.messages.value.count {
                !it.isOwn && it.body == "Unable to decrypt this message"
            }
            val peerMessageCount = recipient.messages.value.count { !it.isOwn }
            val peerAttachments = recipient.messages.value
                .filterNot(ChatMessage::isOwn)
                .mapNotNull(ChatMessage::attachment)
            val peerExpectedAttachmentCount = peerAttachments.count { it.fileName == fileName }
            val peerSiblingAttachmentCount = peerAttachments.count { it.fileName == siblingFileName }
            val peerAttachmentKinds = peerAttachments
                .groupingBy { it.kind.name }
                .eachCount()
                .toSortedMap()
                .entries
                .joinToString(",") { (kind, count) -> "$kind=${count.coerceAtMost(999)}" }
                .ifBlank { "none" }
            val peerUtdCauses = recipient.utdCauseCountsForDiagnostic()
                .toSortedMap()
                .entries
                .joinToString(",") { (cause, count) -> "$cause=${count.coerceAtMost(999)}" }
                .ifBlank { "none" }
            val peerTimelineCategories = recipient.timelineCategoriesForDiagnostic(roomId)
                .toSortedMap()
                .entries
                .joinToString(",") { (category, count) -> "$category=${count.coerceAtMost(999)}" }
                .ifBlank { "none" }
            println(
                "OUTBOX_DIAG_ATTACHMENT_COUNTS sender=$senderEventCount recipient=$recipientEventCount",
            )
            println(
                "OUTBOX_DIAG_ATTACHMENT_MATRIX senderExpected=${senderExpected.mapNotNull { it.first.eventId }.toSet().size} " +
                    "senderSibling=${senderSibling.mapNotNull { it.first.eventId }.toSet().size} " +
                    "senderExpectedRemote=${senderExpected.count { it.first.isRemote }} " +
                    "senderSiblingRemote=${senderSibling.count { it.first.isRemote }} " +
                    "recipientExpected=$peerExpectedAttachmentCount recipientSibling=$peerSiblingAttachmentCount " +
                    "recipientFetchExpected=$recipientCanFetchSenderExpected " +
                    "recipientFetchSibling=$recipientCanFetchSenderSibling",
            )
            println(
                "OUTBOX_DIAG_ATTACHMENT_PEER connected=${recipient.connection.value == "Connected"} " +
                    "peerMessages=$peerMessageCount peerAttachments=${peerAttachments.size} " +
                    "expectedNameMatches=$peerExpectedAttachmentCount siblingNameMatches=$peerSiblingAttachmentCount " +
                    "attachmentKinds=$peerAttachmentKinds " +
                    "peerUtd=$peerUtdCount utdCauses=$peerUtdCauses " +
                    "timelineCategories=$peerTimelineCategories",
            )
        }
        check(settled) {
            "The attachment event count did not settle at one for both peers " +
                "(sender=$senderEventCount, recipient=$recipientEventCount)"
        }
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
        val attachment = checkNotNull(recipientAttachment.attachment)
        val failedDownload = try {
            recipient.loadAttachmentForViewing(
                recipientAttachment.copy(
                    attachment = attachment.copy(sourceJson = sourceWithUnavailableMxcUri(attachment.sourceJson)),
                ),
            )
            null
        } catch (failure: Exception) {
            failure
        }
        check(failedDownload is MatrixAttachmentOpenFailure && failedDownload.stage == "download-and-decrypt") {
            "The unavailable peer media fixture did not fail during download and decryption"
        }
        val decrypted = recipient.loadAttachmentForViewing(checkNotNull(recipientAttachment))
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val viewerFile = File(
            appContext.cacheDir,
            "private-messenger/media/peer-acceptance-viewer/$fileName",
        )
        try {
            val actualBytes = decrypted.readBytes()
            assertEquals(expectedSize, actualBytes.size)
            assertEquals(expectedHash, sha256(actualBytes))
            viewerFile.parentFile?.mkdirs()
            decrypted.copyTo(viewerFile, overwrite = true)
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.files",
                viewerFile,
            )
            assertEquals("content", contentUri.scheme)
            val viewerBytes = appContext.contentResolver
                .openInputStream(contentUri)?.use { it.readBytes() }
            assertTrue("The Android viewer URI returned different encrypted-media plaintext", viewerBytes?.contentEquals(actualBytes) == true)
        } finally {
            recipient.cleanupExternalViewerFiles()
            viewerFile.parentFile?.deleteRecursively()
        }
        assertFalse("The decrypted peer attachment was not removed after viewer cleanup", decrypted.exists())
    }

    private fun sourceWithUnavailableMxcUri(sourceJson: String): String {
        val originalUri = Regex("""mxc://[^"\\,}]+""").find(sourceJson)?.value
            ?: error("The peer attachment MediaSource JSON did not contain an MXC URI")
        val finalSlash = originalUri.lastIndexOf('/')
        check(finalSlash > "mxc://".length) { "The peer attachment MXC URI was malformed" }
        val unreachableUri = originalUri.substring(0, finalSlash + 1) + "missing-${System.nanoTime()}"
        return sourceJson.replaceFirst(originalUri, unreachableUri)
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

    private fun allowlistedPeerIdentityState(state: String): String = when (state) {
        "missing", "verified", "changed", "previously-verified", "unverified", "unavailable" -> state
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
