package dev.friendline.messenger.data

import android.app.Instrumentation
import android.os.Bundle
import android.util.JsonReader
import android.util.JsonToken
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.friendline.messenger.ui.MessengerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Isolated outbox validation. This test refuses to run in the regular app flavor so it cannot
 * restore or mutate the existing application's Matrix account or send queue.
 *
 * Stages are invoked by ops/outbox-diagnostic/Invoke-OutboxDiagnostic.ps1 against a fresh,
 * loopback-only Synapse instance and fresh test accounts.
 */
@RunWith(AndroidJUnit4::class)
class OfflineOutboxIntegrationTest {
    @Test
    fun encryptedMessageOutboxSurvivesOfflineRestartAndReconnects() {
        var instrumentation: Instrumentation? = null
        var repository: MatrixRepository? = null
        var viewModelStore: ViewModelStore? = null
        var minimalClient: Client? = null
        var failureStage = "test-method-entry"
        var failureStep = "test-method-entry"
        var failureState = "start"
        var activeMarker: String? = null

        try {
            val activeInstrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation = activeInstrumentation
            activeInstrumentation.sendStatus(0, Bundle().apply {
                putString("outbox_diag_boundary", "test-method-entered")
            })
            println("OUTBOX_DIAG_BOUNDARY reached=test-method")

            runBlocking {
                failureStage = "argument-validation"
                val context = activeInstrumentation.targetContext
                val args = InstrumentationRegistry.getArguments()

                assertTrue(
                    "Outbox diagnostics must run only in the isolated app flavor",
                    dev.friendline.messenger.BuildConfig.APPLICATION_ID.endsWith(".outboxdiag.debug"),
                )

                val stage = checkNotNull(args.getString("stage"))
                failureStage = stage
                val marker = checkNotNull(args.getString("marker"))
                activeMarker = marker
                val stateFile = File(context.noBackupFilesDir, "outbox-diagnostic/fresh-room.txt")
                failureStep = "repository-construction"
                failureState = "start"
                reportProgress(activeInstrumentation, failureStage, failureStep, failureState)
                val activeRepository = if (stage == "seed-offline") {
                    null
                } else {
                    MatrixRepository(context).also { repository = it }
                }
                failureState = "complete"
                reportProgress(activeInstrumentation, failureStage, failureStep, failureState)

                when (stage) {
                    "prepare" -> {
                        val activeRepository = checkNotNull(activeRepository)
                        val homeserverUrl = checkNotNull(args.getString("homeserver_url"))
                        val username = checkNotNull(args.getString("username"))
                        val password = checkNotNull(args.getString("password"))
                        val recipientUserId = checkNotNull(args.getString("recipient_user_id"))

                        failureStep = "versionsProbe"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val probeResult = probeVersions(homeserverUrl)
                        reportSafeStatus(activeInstrumentation, "outbox_diag_versions_probe", probeResult)
                        println("OUTBOX_DIAG_VERSIONS_PROBE result=$probeResult")
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        val accountLoginProbe = probePasswordLogin(homeserverUrl, username, password)
                        reportSafeStatus(
                            activeInstrumentation,
                            "outbox_diag_account_login",
                            "${accountLoginProbe.httpStatus}|${accountLoginProbe.errcode}",
                        )
                        println(
                            "OUTBOX_DIAG_ACCOUNT_LOGIN " +
                                "httpStatus=${accountLoginProbe.httpStatus} errcode=${accountLoginProbe.errcode}",
                        )
                        if (accountLoginProbe.httpStatus == "200") {
                            var probeAccessToken = checkNotNull(accountLoginProbe.accessToken) {
                                "The account login probe returned no access token"
                            }
                            try {
                                val roomCreateProbe = probeEncryptedRoomCreate(
                                    homeserverUrl,
                                    probeAccessToken,
                                    recipientUserId,
                                )
                                reportSafeStatus(
                                    activeInstrumentation,
                                    "outbox_diag_room_create_probe",
                                    "${roomCreateProbe.httpStatus}|${roomCreateProbe.errcode}",
                                )
                                println(
                                    "OUTBOX_DIAG_CONTROL_ROOM_CREATE " +
                                        "httpStatus=${roomCreateProbe.httpStatus} " +
                                        "errcode=${roomCreateProbe.errcode}",
                                )
                                val logoutStatus = probeLogout(homeserverUrl, probeAccessToken)
                                reportSafeStatus(
                                    activeInstrumentation,
                                    "outbox_diag_account_logout",
                                    logoutStatus,
                                )
                                println("OUTBOX_DIAG_ACCOUNT_LOGOUT httpStatus=$logoutStatus")
                            } finally {
                                probeAccessToken = ""
                                accountLoginProbe.accessToken = null
                            }
                        }

                        failureStep = "minimalClientBuilder"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val diagnosticClient = try {
                            ClientBuilder()
                                .homeserverUrl(homeserverUrl)
                                .disableWellKnownLookup(true)
                                .build()
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-builder", failure)
                            throw failure
                        }
                        minimalClient = diagnosticClient
                        reportBoundarySuccess(activeInstrumentation, "minimal-builder")
                        println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-builder result=success")
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "minimalSdkLogin"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        try {
                            diagnosticClient.login(username.trim(), password, "Outbox diagnostic", null)
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-login", failure)
                            throw failure
                        }
                        reportBoundarySuccess(activeInstrumentation, "minimal-sdk-login")
                        println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-sdk-login result=success")
                        val diagnosticSession = try {
                            diagnosticClient.session().also {
                                reportBoundarySuccess(activeInstrumentation, "minimal-sdk-session-read")
                            }
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-session-read", failure)
                            null
                        }
                        diagnosticSession?.let { session ->
                            reportHomeserverUrlShape(activeInstrumentation, "session", session.homeserverUrl, homeserverUrl)
                            try {
                                check(session.userId == username.trim())
                                reportBoundarySuccess(activeInstrumentation, "minimal-session-user-match")
                            } catch (failure: Throwable) {
                                reportBoundaryFailure(activeInstrumentation, "minimal-session-user-match", failure)
                            }
                            try {
                                check(session.homeserverUrl.trimEnd('/') == homeserverUrl.trimEnd('/'))
                                reportBoundarySuccess(activeInstrumentation, "minimal-session-url-match")
                            } catch (failure: Throwable) {
                                reportBoundaryFailure(activeInstrumentation, "minimal-session-url-match", failure)
                            }
                        }
                        try {
                            val resolvedHomeserver = diagnosticClient.homeserver()
                            reportHomeserverUrlShape(activeInstrumentation, "client", resolvedHomeserver, homeserverUrl)
                            check(resolvedHomeserver.trimEnd('/') == homeserverUrl.trimEnd('/'))
                            reportBoundarySuccess(activeInstrumentation, "minimal-client-url-match")
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-client-url-match", failure)
                        }
                        val minimalUserId = try {
                            diagnosticClient.userId().also { actualUserId ->
                                check(actualUserId == username.trim())
                                reportBoundarySuccess(activeInstrumentation, "minimal-sdk-user-id")
                                println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-sdk-user-id result=success")
                            }
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-user-id", failure)
                            null
                        }
                        try {
                            diagnosticClient.getProfile(checkNotNull(minimalUserId))
                            reportBoundarySuccess(activeInstrumentation, "minimal-sdk-profile-fetch")
                            println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-sdk-profile-fetch result=success")
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-profile-fetch", failure)
                        }
                        try {
                            diagnosticClient.createRoom(
                                CreateRoomParameters(
                                    name = "Outbox diagnostic plain SDK",
                                    isEncrypted = false,
                                    isDirect = false,
                                    visibility = org.matrix.rustcomponents.sdk.RoomVisibility.Private,
                                    preset = org.matrix.rustcomponents.sdk.RoomPreset.PRIVATE_CHAT,
                                ),
                            )
                            reportBoundarySuccess(activeInstrumentation, "minimal-sdk-plain-room-create")
                            println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-sdk-plain-room-create result=success")
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-plain-room-create", failure)
                        }
                        try {
                            diagnosticClient.encryption().waitForE2eeInitializationTasks()
                            reportBoundarySuccess(activeInstrumentation, "minimal-sdk-e2ee-init")
                            println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-sdk-e2ee-init result=success")
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-e2ee-init", failure)
                        }
                        try {
                            diagnosticClient.createRoom(
                                CreateRoomParameters(
                                    name = "Outbox diagnostic encrypted SDK",
                                    isEncrypted = true,
                                    isDirect = true,
                                    visibility = org.matrix.rustcomponents.sdk.RoomVisibility.Private,
                                    preset = org.matrix.rustcomponents.sdk.RoomPreset.PRIVATE_CHAT,
                                    invite = listOf(recipientUserId),
                                ),
                            )
                            reportBoundarySuccess(activeInstrumentation, "minimal-sdk-room-create")
                            println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-sdk-room-create result=success")
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-room-create", failure)
                        }
                        try {
                            diagnosticClient.logout()
                            reportBoundarySuccess(activeInstrumentation, "minimal-sdk-logout")
                            println("OUTBOX_DIAG_SDK_BOUNDARY boundary=minimal-sdk-logout result=success")
                        } catch (failure: Throwable) {
                            reportBoundaryFailure(activeInstrumentation, "minimal-sdk-logout", failure)
                        } finally {
                            diagnosticClient.close()
                            minimalClient = null
                        }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "repositoryLogin"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        try {
                            activeRepository.login(homeserverUrl, username, password)
                        } catch (loginFailure: Throwable) {
                            val loginStage = (loginFailure as? MatrixLoginStageFailure)?.stage
                            if (loginStage != null) {
                                val syncStage = (loginFailure.cause as? MatrixSyncStartFailure)?.stage
                                failureStep = if (syncStage != null) "sync-$syncStage" else loginStage
                                failureState = "complete"
                            }
                            reportBoundaryFailure(activeInstrumentation, "repository-login", loginFailure)
                            throw loginFailure
                        }
                        reportBoundarySuccess(activeInstrumentation, "repository-login")
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        if (args.getString("login_only") == "true") {
                            failureStep = "awaitConnected"
                            failureState = "start"
                            reportProgress(activeInstrumentation, stage, failureStep, failureState)
                            awaitConnected(activeRepository)
                            failureState = "complete"
                            reportProgress(activeInstrumentation, stage, failureStep, failureState)
                            reportSafeStatus(
                                activeInstrumentation,
                                "outbox_diag_result",
                                "prepare|repositoryLogin=success|syncConnected=true",
                            )
                            println("OUTBOX_DIAG_RESULT stage=prepare repositoryLogin=success syncConnected=true")
                            return@runBlocking
                        }

                        failureStep = "awaitConnected"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        awaitConnected(activeRepository)
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "createEncryptedConversation"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val roomId = activeRepository.createEncryptedConversation(
                            invitedUserIds = listOf(recipientUserId),
                            name = "Outbox diagnostic",
                        )
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "awaitRoomReady"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val roomReady = awaitRoomReady(activeRepository, roomId)
                        check(roomReady) {
                            "The diagnostic direct room did not become joined and encrypted"
                        }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "openConversation"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        activeRepository.openConversation(roomId)
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "persistDiagnosticRoom"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        stateFile.parentFile?.mkdirs()
                        stateFile.writeText(roomId)
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        reportSafeStatus(activeInstrumentation, "outbox_diag_result", "prepare|encrypted=true|joined=true")
                        println("OUTBOX_DIAG_RESULT stage=prepare encrypted=true joined=true")
                    }

                    "seed-offline" -> {
                        failureStep = "verifyDiagnosticRoom"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        check(stateFile.isFile) { "The isolated prepare stage has not completed" }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        failureStep = "restoreSession"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val store = ViewModelStore().also { viewModelStore = it }
                        val viewModel = MessengerViewModel(context)
                        store.put("offline-outbox-regression", viewModel)
                        viewModel.restoreSessionIfPresent()
                        val restored = withTimeoutOrNull(60_000) {
                            while (viewModel.state.value.userId == null) delay(100)
                            true
                        }
                        check(restored == true) { "The view model did not restore the isolated diagnostic account" }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val roomId = stateFile.readText().trim()
                        failureStep = "openConversation"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        viewModel.openConversation(roomId)
                        val opened = withTimeoutOrNull(45_000) {
                            while (viewModel.state.value.currentRoomId != roomId ||
                                !viewModel.state.value.currentRoomEncrypted || viewModel.state.value.isBusy
                            ) delay(100)
                            true
                        }
                        check(opened == true) { "The view model did not open the encrypted diagnostic room" }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        failureStep = "sendText"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val markers = (1..3).map { "$marker-$it" }
                        markers.forEach(viewModel::sendText)
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "awaitLocalEcho"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val echoAppeared = withTimeoutOrNull(20_000) {
                            while (markers.any { body ->
                                    viewModel.state.value.messages.none { it.body == body && it.isOwn }
                                }
                            ) {
                                delay(100)
                            }
                            true
                        }
                        check(echoAppeared == true) { "The view-model outbox did not create all local echoes" }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        failureStep = "selectLocalEcho"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val queuedMessages = viewModel.state.value.messages
                        val localEchoes = markers.map { body ->
                            queuedMessages.single { it.body == body && it.isOwn }
                        }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        failureStep = "assertOfflineEcho"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        assertEquals("Each submitted message must have one local echo", 3, localEchoes.size)
                        assertTrue("The view-model queue must preserve tap order", localEchoes.zipWithNext().all {
                            pair -> queuedMessages.indexOf(pair.first) < queuedMessages.indexOf(pair.second)
                        })
                        assertTrue("Offline local echoes must not have server event IDs", localEchoes.all {
                            it.eventId == null && it.deliveryState in setOf("Queued", "Sending")
                        })
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        reportSafeStatus(activeInstrumentation, "outbox_diag_result", "seed-offline|echoCount=3|ordered=true|serverEvent=false")
                        println(
                            "OUTBOX_DIAG_RESULT stage=seed-offline echoCount=3 ordered=true " +
                                "serverEvent=false connection=${viewModel.state.value.connection}",
                        )
                    }

                    "resume" -> {
                        val activeRepository = checkNotNull(activeRepository)
                        check(stateFile.isFile) { "The isolated offline seed stage has not completed" }
                        failureStep = "restoreSession"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        checkNotNull(activeRepository.restoreSession()) {
                            "The isolated diagnostic account has no saved session"
                        }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val roomId = stateFile.readText().trim()
                        failureStep = "awaitConnected"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        awaitConnected(activeRepository)
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        failureStep = "openConversation"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        activeRepository.openConversation(roomId)
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)

                        failureStep = "awaitDelivery"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        val markers = (1..3).map { "$marker-$it" }
                        val delivered = withTimeoutOrNull(60_000) {
                            while (markers.any { body ->
                                    activeRepository.messages.value.none {
                                        it.body == body && it.isOwn && it.eventId != null &&
                                            it.deliveryState in setOf("Sent", "Delivered")
                                    }
                                }
                            ) {
                                delay(250)
                            }
                            true
                        }
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        check(delivered == true) {
                            "The queued message did not reach the sent state after reconnect"
                        }
                        failureStep = "verifyExactlyOnce"
                        failureState = "start"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        delay(1_500)
                        val markerMessages = activeRepository.messages.value.filter { it.body in markers && it.isOwn }
                        val messagesByMarker = markers.map { body -> markerMessages.single { it.body == body } }
                        val serverEventCount = messagesByMarker.mapNotNull { it.eventId }.distinct().size
                        assertEquals("The restarted outbox should create one event per submission", 3, markerMessages.size)
                        assertEquals("Each restarted submission should have one server event ID", 3, serverEventCount)
                        assertTrue("The restarted SDK queue must preserve tap order", messagesByMarker.zipWithNext().all {
                            pair -> markerMessages.indexOf(pair.first) < markerMessages.indexOf(pair.second)
                        })
                        stateFile.delete()
                        failureState = "complete"
                        reportProgress(activeInstrumentation, stage, failureStep, failureState)
                        reportSafeStatus(activeInstrumentation, "outbox_diag_result", "resume|sentCount=3|ordered=true|connection=Connected")
                        println("OUTBOX_DIAG_RESULT stage=resume sentCount=3 ordered=true connection=Connected visibleMessages=${markerMessages.size} serverEventIds=$serverEventCount")
                    }

                    else -> error("Unsupported isolated diagnostic stage")
                }
            }
        } catch (failure: Throwable) {
            val roomFailureStage = (failure as? MatrixRoomCreateFailure)?.stage
            if (roomFailureStage != null) {
                failureStep = "room-$roomFailureStage"
                failureState = "complete"
            }
            val conversationOpenStage = (failure as? MatrixConversationOpenFailure)?.stage
            if (conversationOpenStage != null) {
                failureStep = "open-$conversationOpenStage"
                failureState = "complete"
            }
            safeMatrixFailureToken(failure)?.let { errorCode ->
                failureStep = "$failureStep-$errorCode".take(60)
            }
            val classNames = generateSequence(failure) { it.cause }
                .take(8)
                .map { cause -> cause.javaClass.name }
                .toList()
            val classChain = safeClassChain(classNames)
            val category = classifyFailure(classNames)
            val safeFailureStage = failureStage
                .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                .take(40)
                .ifBlank { "unknown" }
            val safeFailureStep = failureStep
                .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                .take(60)
                .ifBlank { "unknown" }
            if (safeFailureStage == "resume") {
                val markerForDiagnostics = activeMarker
                val matchingMessages = if (markerForDiagnostics == null) emptyList() else {
                    repository?.messages?.value.orEmpty().filter { it.body == markerForDiagnostics && it.isOwn }
                }
                val stateTokens = matchingMessages.map { message ->
                    when (message.deliveryState) {
                        "Queued" -> "Queued"
                        "Sending" -> "Sending"
                        "Sent" -> "Sent"
                        "Delivered" -> "Delivered"
                        "Failed" -> "Failed"
                        else -> "Other"
                    }
                }.distinct().sorted().joinToString("-").ifBlank { "none" }
                val connectionToken = when (repository?.connection?.value) {
                    "Connected" -> "Connected"
                    "Reconnecting" -> "Reconnecting"
                    "Syncing" -> "Syncing"
                    "Offline" -> "Offline"
                    else -> "Other"
                }
                val matchingCount = matchingMessages.size.coerceIn(0, 99)
                val eventIdCount = matchingMessages.count { it.eventId != null }.coerceIn(0, 99)
                reportSafeStatus(
                    checkNotNull(instrumentation),
                    "outbox_diag_reconnect_state",
                    "$connectionToken|$matchingCount|$stateTokens|$eventIdCount",
                )
                println("OUTBOX_DIAG_RECONNECT_STATE connection=$connectionToken matchingMessages=$matchingCount states=$stateTokens eventIds=$eventIdCount")
            }
            instrumentation?.sendStatus(0, Bundle().apply {
                putString(
                    "outbox_diag_failure",
                    "$safeFailureStage|$safeFailureStep|$failureState|$category|$classChain",
                )
            })
            println(
                "OUTBOX_DIAG_EXCEPTION stage=$safeFailureStage step=$safeFailureStep " +
                    "state=$failureState category=$category classChain=$classChain",
            )
            throw failure
        } finally {
            viewModelStore?.clear()
            minimalClient?.close()
            repository?.let { activeRepository ->
                try {
                    runBlocking { activeRepository.close() }
                    instrumentation?.let { reportBoundarySuccess(it, "repository-close") }
                } catch (failure: Throwable) {
                    instrumentation?.let { reportBoundaryFailure(it, "repository-close", failure) }
                    throw failure
                }
            }
        }
    }

    private fun reportProgress(
        instrumentation: Instrumentation,
        stage: String,
        step: String,
        state: String,
    ) {
        val safeStage = diagnosticToken(stage)
        val safeStep = diagnosticToken(step)
        val safeState = if (state == "complete") "complete" else "start"
        instrumentation.sendStatus(0, Bundle().apply {
            putString("outbox_diag_progress", "$safeStage|$safeStep|$safeState")
        })
        println("OUTBOX_DIAG_PROGRESS stage=$safeStage step=$safeStep state=$safeState")
    }

    private fun reportSafeStatus(instrumentation: Instrumentation, key: String, value: String) {
        val safeKey = when (key) {
            "outbox_diag_versions_probe",
            "outbox_diag_account_login",
            "outbox_diag_room_create_probe",
            "outbox_diag_account_logout",
            "outbox_diag_result",
            "outbox_diag_reconnect_state" -> key
            else -> "outbox_diag_status"
        }
        val safeValue = value.take(120).filter { it.isLetterOrDigit() || it in "_-.=|" }
        instrumentation.sendStatus(0, Bundle().apply { putString(safeKey, safeValue) })
    }

    private fun reportBoundarySuccess(instrumentation: Instrumentation, boundary: String) {
        val safeBoundary = diagnosticBoundary(boundary)
        val value = "$safeBoundary|success|none|none"
        instrumentation.sendStatus(0, Bundle().apply { putString("outbox_diag_sdk_boundary", value) })
        println("OUTBOX_DIAG_SDK_BOUNDARY boundary=$safeBoundary result=success")
    }

    private fun reportHomeserverUrlShape(
        instrumentation: Instrumentation,
        boundary: String,
        actual: String,
        expected: String,
    ) {
        val allowedBoundary = when (boundary) {
            "session" -> "session"
            "client" -> "client"
            else -> "unknown"
        }
        val actualUri = runCatching { java.net.URI(actual) }.getOrNull()
        val expectedUri = runCatching { java.net.URI(expected) }.getOrNull()
        val scheme = when (actualUri?.scheme?.lowercase()) {
            "http" -> "http"
            "https" -> "https"
            null -> "missing"
            else -> "other"
        }
        val host = when {
            actualUri?.host == null -> "missing"
            actualUri.host.equals(expectedUri?.host, ignoreCase = true) -> "expected"
            actualUri.host == "localhost" || actualUri.host == "127.0.0.1" || actualUri.host == "::1" -> "loopback"
            else -> "other"
        }
        val port = when {
            actualUri == null -> "invalid"
            actualUri.port == expectedUri?.port -> "expected"
            actualUri.port == -1 -> "none"
            else -> "other"
        }
        val path = when (actualUri?.path) {
            null -> "missing"
            "", "/" -> "root"
            else -> "other"
        }
        val userInfo = if (actualUri?.rawUserInfo == null) "none" else "present"
        val query = if (actualUri?.rawQuery == null) "none" else "present"
        val fragment = if (actualUri?.rawFragment == null) "none" else "present"
        val matches = actualUri != null && expectedUri != null &&
            actual.trimEnd('/') == expected.trimEnd('/')
        val safeShape = listOf(
            allowedBoundary,
            matches.toString(),
            scheme,
            host,
            port,
            path,
            userInfo,
            query,
            fragment,
        ).joinToString("|")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("outbox_diag_url_shape", safeShape)
        })
        println(
            "OUTBOX_DIAG_URL_SHAPE boundary=$allowedBoundary match=$matches scheme=$scheme host=$host " +
                "port=$port path=$path userinfo=$userInfo query=$query fragment=$fragment",
        )
    }

    private fun classifyFailure(classNames: List<String>): String {
        return classNames.firstNotNullOfOrNull { SAFE_EXCEPTION_CATEGORIES[it] } ?: "other"
    }

    private fun safeClassChain(classNames: List<String>): String =
        classNames.joinToString(">") { className ->
            SAFE_EXCEPTION_CLASS_LABELS[className] ?: className.substringAfterLast('.')
                .replace('$', '_')
                .takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }
                ?: "OtherThrowable"
        }

    private fun safeMatrixFailureToken(failure: Throwable): String? {
        val internalError = generateSequence(failure) { it.cause }
            .filterIsInstance<org.matrix.rustcomponents.sdk.InternalException>()
            .firstOrNull()
        if (internalError != null) {
            val message = internalError.message.orEmpty().lowercase()
            return when {
                "invalid handle" in message -> "SDK_INVALID_HANDLE"
                "not on a tokio runtime" in message -> "SDK_NO_RUNTIME"
                "option::unwrap" in message -> "SDK_PANIC_OPTION_UNWRAP"
                "result::unwrap" in message -> "SDK_PANIC_RESULT_UNWRAP"
                "rust panic" in message || "panic" in message -> "SDK_RUST_PANIC"
                "unexpected call_error" in message -> "SDK_UNEXPECTED_CALL"
                "unknown rust call status" in message -> "SDK_RUST_CALL_STATUS"
                else -> "SDK_INTERNAL"
            }
        }
        val matrixError = generateSequence(failure) { it.cause }
            .filterIsInstance<ClientException.Generic>()
            .firstOrNull() ?: return null
        val detailsCode = matrixError.details?.let { details ->
            runCatching { JSONObject(details).optString("errcode") }.getOrNull()
        }
        detailsCode?.takeIf { it.matches(Regex("M_[A-Z0-9_]{1,64}")) }?.let { return it }

        // Generic SDK failures sometimes preserve the HTTP response status only in `msg`.
        // Inspect it in memory and emit only a constrained Matrix error token or status code.
        val safeTexts = listOfNotNull(matrixError.details, matrixError.msg)
        val safeCode = Regex("\\bM_[A-Z0-9_]{1,64}\\b")
            .find(safeTexts.joinToString("\n"))
            ?.value
        if (safeCode != null) return safeCode

        val statusPatterns = listOf(
            Regex("(?i)\\bHTTP(?:/[0-9.]+)?\\s+(?<status>[1-5][0-9]{2})\\b"),
            Regex("(?i)\\b(?:HTTP\\s+)?(?:response\\s+)?status(?:\\s+code)?\\s*[:=]?\\s*(?<status>[1-5][0-9]{2})\\b"),
        )
        val status = statusPatterns.firstNotNullOfOrNull { pattern ->
            safeTexts.firstNotNullOfOrNull { text -> pattern.find(text)?.groups?.get("status")?.value }
        }
        if (status != null) return "HTTP_$status"

        val combinedText = safeTexts.joinToString(" ")
        val knownUrlErrors = listOf(
            "RelativeUrlWithoutBase",
            "RelativeUrlWithCannotBeABaseBase",
            "SetHostOnCannotBeABaseUrl",
            "InvalidDomainCharacter",
            "InvalidIpv4Address",
            "InvalidIpv6Address",
            "InvalidPort",
            "EmptyHost",
            "IdnaError",
            "InvalidUriChar",
            "InvalidUri",
        )
        knownUrlErrors.firstOrNull { marker ->
            Regex("\\b${Regex.escape(marker)}\\b").containsMatchIn(combinedText)
        }?.let { return "URL_$it" }

        val normalized = safeTexts.joinToString(" ").lowercase()
        return when {
            "not implemented" in normalized -> "SDK_UNIMPLEMENTED"
            "no session" in normalized || "missing session" in normalized || "not logged in" in normalized -> "SDK_NO_SESSION"
            "session" in normalized -> "SDK_SESSION"
            "user_id" in normalized || "user id" in normalized -> "SDK_USER_ID"
            "authentication" in normalized || "unauthorized" in normalized -> "SDK_AUTH"
            "sqlite" in normalized || "database" in normalized || "store" in normalized -> "SDK_STORE"
            "security" in normalized -> "SDK_SECURITY"
            Regex("\\burl\\b").containsMatchIn(normalized) -> "SDK_URL"
            Regex("\\buri\\b").containsMatchIn(normalized) -> "SDK_URI"
            Regex("\\b(?:parse|parser)\\b").containsMatchIn(normalized) -> "SDK_PARSE"
            Regex("\\bserialization\\b").containsMatchIn(normalized) -> "SDK_SERIALIZE"
            Regex("\\bdeserialization\\b").containsMatchIn(normalized) -> "SDK_DESERIALIZE"
            "crypto" in normalized || "encrypt" in normalized -> "SDK_CRYPTO"
            "room" in normalized -> "SDK_ROOM"
            "network" in normalized || "connect" in normalized || "transport" in normalized -> "SDK_TRANSPORT"
            else -> "SDK_GENERIC"
        }
    }

    private fun probeEncryptedRoomCreate(
        homeserverUrl: String,
        accessToken: String,
        invitedUserId: String,
    ): RoomCreateProbeResult {
        var connection: HttpURLConnection? = null
        var requestBody: JSONObject? = null
        var requestBytes: ByteArray? = null
        var httpStatus = "unknown"
        var errcode = "unknown"
        try {
            val base = URL(homeserverUrl)
            check(base.protocol == "http" && base.host == "127.0.0.1" && base.port == 18009) {
                "Unexpected diagnostic homeserver origin"
            }
            val encryptionState = JSONObject()
                .put("type", "m.room.encryption")
                .put("state_key", "")
                .put(
                    "content",
                    JSONObject().put("algorithm", "m.megolm.v1.aes-sha2"),
                )
            val requestJson = JSONObject()
                .put("name", "Outbox diagnostic control")
                .put("visibility", "private")
                .put("preset", "private_chat")
                .put("is_direct", true)
                .put("invite", org.json.JSONArray().put(invitedUserId))
                .put("initial_state", org.json.JSONArray().put(encryptionState))
            requestBody = requestJson
            val encodedBody = requestJson.toString().toByteArray(Charsets.UTF_8)
            requestBytes = encodedBody

            val activeConnection = URL("${homeserverUrl.trimEnd('/')}/_matrix/client/v3/createRoom")
                .openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = 5_000
            activeConnection.readTimeout = 5_000
            activeConnection.instanceFollowRedirects = false
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "application/json")
            activeConnection.setRequestProperty("Authorization", "Bearer $accessToken")
            activeConnection.outputStream.use { output ->
                output.write(encodedBody)
                output.flush()
            }

            val statusCode = activeConnection.responseCode
            httpStatus = safeHttpStatus(statusCode)
            val responseStream = try {
                if (statusCode in 200..299) activeConnection.inputStream else activeConnection.errorStream
            } catch (_: IOException) {
                activeConnection.errorStream
            }
            if (responseStream != null) {
                val parsed = runCatching {
                    JSONObject(responseStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        .optString("errcode")
                }.getOrNull()
                errcode = validatedMatrixErrcode(parsed)
            }
        } catch (_: IOException) {
            // Keep transport details, response bodies and request data out of diagnostic output.
        } finally {
            requestBytes?.fill(0)
            requestBody?.remove("invite")
            requestBody?.remove("initial_state")
            requestBody?.remove("name")
            connection?.disconnect()
        }
        return RoomCreateProbeResult(httpStatus, errcode)
    }

    private fun reportBoundaryFailure(
        instrumentation: Instrumentation,
        boundary: String,
        failure: Throwable,
    ) {
        val safeBoundary = diagnosticBoundary(boundary)
        val classNames = generateSequence(failure) { it.cause }
            .take(8)
            .map { cause -> cause.javaClass.name }
            .toList()
        val category = classifyFailure(classNames)
        val classChain = safeClassChain(classNames)
        val errorToken = safeMatrixFailureToken(failure) ?: "unknown"
        val value = "$safeBoundary|failure|$category|$classChain|$errorToken"
        instrumentation.sendStatus(0, Bundle().apply { putString("outbox_diag_sdk_boundary", value) })
        println(
            "OUTBOX_DIAG_SDK_BOUNDARY boundary=$safeBoundary result=failure " +
                "category=$category classChain=$classChain token=$errorToken",
        )
    }

    private fun diagnosticBoundary(boundary: String): String = when (boundary) {
        "minimal-builder" -> "minimal-builder"
        "minimal-sdk-login" -> "minimal-sdk-login"
        "minimal-sdk-session" -> "minimal-sdk-session"
        "minimal-sdk-session-read" -> "minimal-sdk-session-read"
        "minimal-session-user-match" -> "minimal-session-user-match"
        "minimal-session-url-match" -> "minimal-session-url-match"
        "minimal-client-url-match" -> "minimal-client-url-match"
        "minimal-sdk-user-id" -> "minimal-sdk-user-id"
        "minimal-sdk-profile-fetch" -> "minimal-sdk-profile-fetch"
        "minimal-sdk-plain-room-create" -> "minimal-sdk-plain-room-create"
        "minimal-sdk-e2ee-init" -> "minimal-sdk-e2ee-init"
        "minimal-sdk-room-create" -> "minimal-sdk-room-create"
        "minimal-sdk-logout" -> "minimal-sdk-logout"
        "repository-login" -> "repository-login"
        "repository-close" -> "repository-close"
        else -> "unknown"
    }

    private fun diagnosticToken(value: String): String = value
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(60)
        .ifBlank { "unknown" }

    private fun probeVersions(homeserverUrl: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val base = URL(homeserverUrl)
            check(base.protocol == "http" && base.host == "127.0.0.1" && base.port == 18009) {
                "Unexpected diagnostic homeserver origin"
            }
            connection = URL("${homeserverUrl.trimEnd('/')}/_matrix/client/versions")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status in 100..599) "http-$status" else "transport-unclassified"
        } catch (failure: IOException) {
            safeTransportFailure(failure)
        } finally {
            connection?.disconnect()
        }
    }

    private fun probePasswordLogin(
        homeserverUrl: String,
        username: String,
        password: String,
    ): PasswordLoginProbeResult {
        var connection: HttpURLConnection? = null
        var requestBody: JSONObject? = null
        var identifier: JSONObject? = null
        var requestBytes: ByteArray? = null
        var httpStatus = "unknown"
        var errcode = "unknown"
        var accessToken: String? = null

        try {
            val base = URL(homeserverUrl)
            check(base.protocol == "http" && base.host == "127.0.0.1" && base.port == 18009) {
                "Unexpected diagnostic homeserver origin"
            }
            val activeConnection = URL("${homeserverUrl.trimEnd('/')}/_matrix/client/v3/login")
                .openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = 5_000
            activeConnection.readTimeout = 5_000
            activeConnection.instanceFollowRedirects = false
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "application/json")

            val requestIdentifier = JSONObject()
                .put("type", "m.id.user")
                .put("user", username)
            identifier = requestIdentifier
            val requestJson = JSONObject()
                .put("type", "m.login.password")
                .put("identifier", requestIdentifier)
                .put("password", password)
            requestBody = requestJson
            val encodedBody = requestJson.toString().toByteArray(Charsets.UTF_8)
            requestBytes = encodedBody
            activeConnection.outputStream.use { output ->
                output.write(encodedBody)
                output.flush()
            }

            val statusCode = activeConnection.responseCode
            httpStatus = safeHttpStatus(statusCode)
            val response = readPasswordLoginResponse(activeConnection, statusCode)
            errcode = validatedMatrixErrcode(response.errcode)
            accessToken = response.accessToken
        } catch (_: IOException) {
            // Keep transport details and request data out of diagnostic output.
        } finally {
            requestBytes?.fill(0)
            requestBody?.remove("password")
            requestBody?.remove("identifier")
            identifier?.remove("user")
            connection?.disconnect()
        }

        return PasswordLoginProbeResult(httpStatus, errcode, accessToken)
    }

    private fun readPasswordLoginResponse(
        connection: HttpURLConnection,
        statusCode: Int,
    ): PasswordLoginResponse {
        val responseStream = try {
            if (statusCode in 200..299) connection.inputStream else connection.errorStream
        } catch (_: IOException) {
            connection.errorStream
        } ?: return PasswordLoginResponse()

        return try {
            InputStreamReader(responseStream, Charsets.UTF_8).use { streamReader ->
                JsonReader(streamReader).use { reader ->
                    var accessToken: String? = null
                    var errcode: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "access_token" -> accessToken = readNullableJsonString(reader)
                            "errcode" -> errcode = readNullableJsonString(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    PasswordLoginResponse(accessToken, errcode)
                }
            }
        } catch (_: IOException) {
            PasswordLoginResponse()
        } catch (_: IllegalStateException) {
            PasswordLoginResponse()
        }
    }

    private fun readNullableJsonString(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun probeLogout(homeserverUrl: String, accessToken: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val base = URL(homeserverUrl)
            check(base.protocol == "http" && base.host == "127.0.0.1" && base.port == 18009) {
                "Unexpected diagnostic homeserver origin"
            }
            val activeConnection = URL("${homeserverUrl.trimEnd('/')}/_matrix/client/v3/logout")
                .openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = 5_000
            activeConnection.readTimeout = 5_000
            activeConnection.instanceFollowRedirects = false
            activeConnection.setRequestProperty("Authorization", "Bearer $accessToken")
            safeHttpStatus(activeConnection.responseCode)
        } catch (_: IOException) {
            "unknown"
        } finally {
            connection?.disconnect()
        }
    }

    private fun safeHttpStatus(statusCode: Int): String =
        if (statusCode in 100..599) statusCode.toString() else "unknown"

    private fun validatedMatrixErrcode(value: String?): String =
        value?.takeIf { MATRIX_ERRCODE_REGEX.matches(it) } ?: "unknown"

    private fun safeTransportFailure(failure: Throwable): String {
        val allowedNames = setOf(
            "UnknownHostException",
            "ConnectException",
            "NoRouteToHostException",
            "SocketTimeoutException",
            "SocketException",
            "SSLException",
            "SSLHandshakeException",
            "ProtocolException",
            "UnknownServiceException",
            "InterruptedIOException",
        )
        val knownName = generateSequence(failure) { it.cause }
            .take(8)
            .map { it.javaClass.simpleName }
            .firstOrNull { it in allowedNames }
        return if (knownName == null) "transport-unclassified" else "transport-$knownName"
    }

    private companion object {
        val MATRIX_ERRCODE_REGEX = Regex("M_[A-Z0-9_]{1,64}")
        val SAFE_EXCEPTION_CLASS_LABELS = mapOf(
            "org.matrix.rustcomponents.sdk.ClientException\$Generic" to "MatrixClientGeneric",
            "org.matrix.rustcomponents.sdk.InternalException" to "MatrixSdkInternalException",
            "dev.friendline.messenger.data.MatrixLoginStageFailure" to "MatrixLoginStageFailure",
            "dev.friendline.messenger.data.MatrixSyncStartFailure" to "MatrixSyncStartFailure",
            "dev.friendline.messenger.data.MatrixRoomCreateFailure" to "MatrixRoomCreateFailure",
            "dev.friendline.messenger.data.MatrixConversationOpenFailure" to "MatrixConversationOpenFailure",
            "org.matrix.rustcomponents.sdk.ClientException" to "MatrixClientException",
            "java.net.UnknownHostException" to "UnknownHostException",
            "java.net.ConnectException" to "ConnectException",
            "java.net.NoRouteToHostException" to "NoRouteToHostException",
            "java.net.SocketTimeoutException" to "SocketTimeoutException",
            "java.net.SocketException" to "SocketException",
            "javax.net.ssl.SSLException" to "SSLException",
            "javax.net.ssl.SSLHandshakeException" to "SSLHandshakeException",
            "java.net.ProtocolException" to "ProtocolException",
            "java.io.InterruptedIOException" to "InterruptedIOException",
            "java.io.IOException" to "IOException",
            "java.lang.IllegalStateException" to "IllegalStateException",
            "java.lang.IllegalArgumentException" to "IllegalArgumentException",
            "java.lang.NullPointerException" to "NullPointerException",
            "java.lang.SecurityException" to "SecurityException",
            "java.util.concurrent.TimeoutException" to "TimeoutException",
            "kotlinx.coroutines.TimeoutCancellationException" to "TimeoutCancellationException",
            "org.json.JSONException" to "JsonException",
            "org.matrix.rustcomponents.sdk.AuthenticationException" to "MatrixAuthenticationException",
            "org.matrix.rustcomponents.sdk.UnauthorizedException" to "MatrixUnauthorizedException",
            "org.matrix.rustcomponents.sdk.ForbiddenException" to "MatrixForbiddenException",
            "org.matrix.rustcomponents.sdk.StoreException" to "MatrixStoreException",
            "org.matrix.rustcomponents.sdk.StorageException" to "MatrixStorageException",
            "org.matrix.rustcomponents.sdk.CryptoException" to "MatrixCryptoException",
            "org.matrix.rustcomponents.sdk.EncryptionException" to "MatrixEncryptionException",
            "org.matrix.rustcomponents.sdk.RoomException" to "MatrixRoomException",
            "org.matrix.rustcomponents.sdk.MembershipException" to "MatrixMembershipException",
        )
        val SAFE_EXCEPTION_CATEGORIES = mapOf(
            "java.util.concurrent.TimeoutException" to "timeout",
            "kotlinx.coroutines.TimeoutCancellationException" to "timeout",
            "java.net.SocketTimeoutException" to "timeout",
            "java.net.UnknownHostException" to "network",
            "java.net.ConnectException" to "network",
            "java.net.NoRouteToHostException" to "network",
            "java.net.SocketException" to "network",
            "javax.net.ssl.SSLException" to "network",
            "javax.net.ssl.SSLHandshakeException" to "network",
            "org.matrix.rustcomponents.sdk.AuthenticationException" to "auth",
            "org.matrix.rustcomponents.sdk.UnauthorizedException" to "auth",
            "org.matrix.rustcomponents.sdk.ForbiddenException" to "auth",
            "org.matrix.rustcomponents.sdk.StoreException" to "store",
            "org.matrix.rustcomponents.sdk.StorageException" to "store",
            "org.matrix.rustcomponents.sdk.CryptoException" to "encryption",
            "org.matrix.rustcomponents.sdk.EncryptionException" to "encryption",
            "org.matrix.rustcomponents.sdk.RoomException" to "room",
            "org.matrix.rustcomponents.sdk.MembershipException" to "room",
        )
    }

    private data class PasswordLoginProbeResult(
        val httpStatus: String,
        val errcode: String,
        var accessToken: String?,
    )

    private data class RoomCreateProbeResult(
        val httpStatus: String,
        val errcode: String,
    )

    private data class PasswordLoginResponse(
        val accessToken: String? = null,
        val errcode: String? = null,
    )

    private suspend fun awaitConnected(repository: MatrixRepository) {
        val connected = withTimeoutOrNull(60_000) {
            while (repository.connection.value != "Connected") delay(250)
            true
        }
        check(connected == true) { "The isolated diagnostic client did not connect" }
    }

    private suspend fun awaitRoomReady(repository: MatrixRepository, roomId: String): Boolean =
        withTimeoutOrNull(45_000) {
            while (true) {
                repository.refreshConversations()
                if (repository.conversations.value.any {
                        it.roomId == roomId && it.isEncrypted && it.membership == "JOINED"
                    }
                ) {
                    return@withTimeoutOrNull true
                }
                delay(250)
            }
            false
        } == true
}
