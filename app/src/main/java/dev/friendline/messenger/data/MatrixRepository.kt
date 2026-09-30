package dev.friendline.messenger.data

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import dev.friendline.messenger.BuildConfig
import dev.friendline.messenger.calls.CALL_MESSAGE_TYPE
import dev.friendline.messenger.calls.CallProtocol
import dev.friendline.messenger.calls.MessengerCallAction
import dev.friendline.messenger.calls.MessengerCallKind
import dev.friendline.messenger.calls.MessengerCallSignal
import dev.friendline.messenger.push.MatrixPushClient
import dev.friendline.messenger.push.MatrixPushSession
import dev.friendline.messenger.push.PushPusherIdentity
import dev.friendline.messenger.push.PushPusherOperation
import dev.friendline.messenger.push.PushPusherPlan
import dev.friendline.messenger.push.PushPusherRecord
import dev.friendline.messenger.push.PushRegistrationStatus
import dev.friendline.messenger.push.planPushPusherSync
import dev.friendline.messenger.push.shouldDisablePushAfterRecoveredRemoval
import dev.friendline.messenger.push.shouldRecoverPushOptOutRemoval
import dev.friendline.messenger.push.shouldResumeRegistrationAfterRecoveredRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.ComposerDraft
import org.matrix.rustcomponents.sdk.ComposerDraftType
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EncryptedMessage
import org.matrix.rustcomponents.sdk.UnableToDecryptDelegate
import org.matrix.rustcomponents.sdk.UnableToDecryptInfo
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaFileHandle
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.SearchService
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.SearchServicePaginationStateListener
import org.matrix.rustcomponents.sdk.SearchServiceResult
import org.matrix.rustcomponents.sdk.SearchServiceResultsListener
import org.matrix.rustcomponents.sdk.SearchServiceResultsUpdate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SendAttachmentJoinHandle
import org.matrix.rustcomponents.sdk.SendHandle
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueRoomUpdateListener
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.SendQueueRoomErrorListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.RoomMessageEventMessageType
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import uniffi.matrix_sdk_crypto.CollectStrategy
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

internal class MatrixLoginStageFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Sign-in could not complete during $stage", cause)

internal class MatrixSyncStartFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Sync could not start during $stage", cause)

internal class MatrixRoomCreateFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Encrypted conversation setup failed during $stage", cause)

internal class MatrixConversationOpenFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Conversation setup failed during $stage", cause)

internal class PendingVoiceNoteStillQueuedException :
    IllegalStateException("This voice message is already queued and cannot be sent again yet")

internal class MatrixVerificationIdentityUnavailable :
    IllegalStateException("This account's secure verification identity is not available yet. Try again after sync completes.")

internal data class ReadReceiptDiagnosticSnapshot(
    val eventSeen: Boolean = false,
    val isOwnEvent: Boolean = false,
    val hasOtherReader: Boolean = false,
    val mappedReadState: Boolean = false,
    val appModelReadState: Boolean = false,
    val updateCount: Int = 0,
)

class MatrixRepository(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val vault = DeviceVault(appContext)
    private val matrixRoot = File(appContext.noBackupFilesDir, "private-messenger/matrix").apply { mkdirs() }
    private val mediaTransferRoot = File(appContext.cacheDir, "private-messenger/media")
    private val mediaTransferDir = File(mediaTransferRoot, UUID.randomUUID().toString()).apply { mkdirs() }
    private val mediaOutboxRoot = File(appContext.noBackupFilesDir, "private-messenger/media-outbox")
    private val mediaOutboxCacheRoot = File(appContext.cacheDir, "private-messenger/media-outbox")
    private val pendingMediaJournalFile = File(mediaOutboxRoot, "pending-media.bin")
    private val deliveryAckJournalFile = File(matrixRoot, "delivery-ack-journal.bin")
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val timelineMessagesLock = Any()
    // Keep null slots for timeline items that are not chat messages. SDK diff indices refer to
    // the full timeline, so applying them to the filtered UI list can leave stale local echoes.
    private val timelineMessages = mutableListOf<ChatMessage?>()
    private val _composerDraft = MutableStateFlow("")
    private val _connection = MutableStateFlow("Offline")
    private val _typingUsers = MutableStateFlow<List<String>>(emptyList())
    private val _callSignals = MutableSharedFlow<MessengerCallSignal>(extraBufferCapacity = 32)
    private val _verification = MutableStateFlow<DeviceVerificationUiState?>(null)
    private val _peerTrust = MutableStateFlow(PeerTrustStatus.UNKNOWN)
    private val _searchResults = MutableStateFlow<List<MessageSearchHit>>(emptyList())
    private val _searchHasMore = MutableStateFlow(false)
    private val _searchLoading = MutableStateFlow(false)
    private val _readReceiptsEnabled = MutableStateFlow(vault.loadReadReceiptsEnabled())
    private val _pushNotificationsEnabled = MutableStateFlow(vault.loadPushNotificationsEnabled())
    private val _pushRegistrationStatus = MutableStateFlow(
        when {
            vault.loadPushRemovalPending() || vault.hasPushPusherRecord() -> PushRegistrationStatus.REMOVAL_PENDING
            !MatrixPushClient.isConfigured -> PushRegistrationStatus.DISABLED
            _pushNotificationsEnabled.value -> PushRegistrationStatus.REGISTERING
            else -> PushRegistrationStatus.NOT_ENABLED
        },
    )

    val conversations = _conversations.asStateFlow()
    val messages = _messages.asStateFlow()
    val composerDraft = _composerDraft.asStateFlow()
    val connection = _connection.asStateFlow()
    val typingUsers = _typingUsers.asStateFlow()
    val callSignals = _callSignals.asSharedFlow()
    val verification = _verification.asStateFlow()
    val peerTrust = _peerTrust.asStateFlow()
    val searchResults = _searchResults.asStateFlow()
    val searchHasMore = _searchHasMore.asStateFlow()
    val searchLoading = _searchLoading.asStateFlow()
    val readReceiptsEnabled = _readReceiptsEnabled.asStateFlow()
    val pushNotificationsEnabled = _pushNotificationsEnabled.asStateFlow()
    val pushRegistrationStatus = _pushRegistrationStatus.asStateFlow()

    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendQueueMutex = Mutex()
    // UploadSource.Data crosses the FFI as one byte array. Keep only one upload payload
    // resident at a time so two large attachments cannot multiply the memory ceiling.
    private val mediaDataEnqueueMutex = Mutex()
    private val mediaDataRecoveryMutex = Mutex()
    private val verificationControllerMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private var pushTokenObserver: Job? = null
    @Volatile private var sendQueuesEnabled: Boolean? = null
    @Volatile private var logoutInProgress = false
    @Volatile private var syncServiceRunning = false
    private val deliveryAckLock = Any()
    private val deliveryAckJournal = LinkedHashMap<String, DeliveryAckRecord>()
    private val pendingDeliverySnapshotsByTransaction = LinkedHashMap<String, PendingDeliverySnapshot>()
    @Volatile private var deliveryAckJournalLoaded = false
    private val failedAckSendHandles = ConcurrentHashMap<String, SendHandle>()
    private val pendingMediaSendHandles = ConcurrentHashMap<String, SendHandle>()
    private val observedAckTargetsByRoom = ConcurrentHashMap<String, MutableSet<String>>()
    private val ackReconciliationScheduled = ConcurrentHashMap.newKeySet<String>()
    private val ackRetryScheduled = ConcurrentHashMap.newKeySet<String>()
    private val expectedDeliveryMemberIdsByRoom = ConcurrentHashMap<String, Set<String>>()
    private val deliveryAckObservers = ConcurrentHashMap<String, DeliveryAckObserver>()
    private val remoteAckMessageCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val diagnosticUtdCauseCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val diagnosticUtdEventIds = ConcurrentHashMap.newKeySet<String>()
    private val diagnosticTimelineEventIdsByRoom = ConcurrentHashMap<String, MutableSet<String>>()
    private val diagnosticTimelineCategoriesByRoom = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>>()
    private val deliverySnapshotReservationLock = Any()
    private var activeDeliverySnapshotReservation: DeliverySnapshotReservation? = null
    private val readReceiptLocks = ConcurrentHashMap<String, Mutex>()
    private val verificationPeerPrewarmScheduled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var verificationDiagnosticStage = "idle"
    private val lastVisibleReadReceiptTimestamps = ConcurrentHashMap<String, Long>()
    private val lastVisibleReadReceiptEventIds = ConcurrentHashMap<String, String>()
    private val readReceiptDiagnosticLock = Any()
    private var readReceiptDiagnosticEventId: String? = null
    private var readReceiptDiagnosticSnapshot = ReadReceiptDiagnosticSnapshot()
    private val externalViewerFiles = ConcurrentHashMap.newKeySet<File>()
    private val pendingMediaLock = Any()
    private val pendingMediaRecords = LinkedHashMap<String, PendingMediaRecord>()
    @Volatile private var pendingMediaJournalLoaded = false
    private val pendingMediaObservers = ConcurrentHashMap<String, PendingMediaObserver>()
    private val pendingMediaRecoveryAmbiguousRecordIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingMediaReadyRoomIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingMediaReplaySchedulerLock = Any()
    private var pendingMediaReplayJob: Job? = null
    private var pendingMediaReplayRetryCount = 0
    private var pendingMediaReplayExecuting = false
    private val activeAttachmentSends = ConcurrentHashMap<String, TrackedAttachmentSend>()

    private var client: Client? = null
    private var searchService: SearchService? = null
    private var searchResultsHandle: TaskHandle? = null
    private var searchPaginationHandle: TaskHandle? = null
    private var searchResultsListener: SearchServiceResultsListener? = null
    private var searchPaginationListener: SearchServicePaginationStateListener? = null
    private var verificationController: SessionVerificationController? = null
    private var pendingVerificationRequest: Pair<String, String>? = null
    @Volatile private var verificationWasInitiatedHere = false
    private var syncService: SyncService? = null
    private var syncStateHandle: TaskHandle? = null
    private var sendQueueStatusHandle: TaskHandle? = null
    private var sendQueueUpdatesHandle: TaskHandle? = null
    private var sendQueueUpdatesListener: SendQueueRoomUpdateListener? = null
    private var typingListenerHandle: TaskHandle? = null
    @Volatile private var activeTimeline: Timeline? = null
    @Volatile private var readReceiptRefreshJob: Job? = null
    private var timelineListenerHandle: TaskHandle? = null
    @Volatile private var activeRoomId: String? = null
    private var ownUserId: String? = null

    init {
        mediaTransferRoot.mkdirs()
        mediaOutboxRoot.mkdirs()
        mediaOutboxCacheRoot.mkdirs()
        synchronized(mediaCleanupLock) {
            if (!mediaTempCleanedForProcess) {
                mediaTransferRoot.listFiles()?.filter { it != mediaTransferDir }?.forEach(File::deleteRecursively)
                mediaTempCleanedForProcess = true
            }
        }
    }

    suspend fun restoreSession(): String? = withContext(Dispatchers.IO) {
        withLifecycleLock {
            val session = vault.loadSession() ?: run {
                cleanupPlaintextMediaCaches()
                return@withLifecycleLock null
            }
            val matrixClient = buildClient(session.homeserverUrl)
            matrixClient.restoreSession(session)
            matrixClient.encryption().waitForE2eeInitializationTasks()
            client = matrixClient
            ownUserId = session.userId
            startSync(matrixClient)
            schedulePushRegistration()
            session.userId
        }
    }

    suspend fun login(homeserverUrl: String, username: String, password: String): String =
        withContext(Dispatchers.IO) {
            withLifecycleLock {
                val normalizedUrl = validateHomeserverUrl(homeserverUrl)
                var stage = "client-build"
                val matrixClient = try {
                    buildClient(normalizedUrl)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    throw MatrixLoginStageFailure(stage, failure)
                }
                try {
                    stage = "password-login"
                    matrixClient.login(username.trim(), password, "Private Messenger", null)
                    stage = "e2ee-initialization"
                    matrixClient.encryption().waitForE2eeInitializationTasks()
                    stage = "session-save"
                    vault.saveSession(matrixClient.session())
                    client = matrixClient
                    ownUserId = matrixClient.userId()
                    stage = "sync-start"
                    startSync(matrixClient)
                    schedulePushRegistration()
                    matrixClient.userId()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    throw MatrixLoginStageFailure(stage, failure)
                }
            }
        }

    suspend fun createEncryptedConversation(
        invitedUserIds: List<String>,
        name: String?,
        isGroup: Boolean = invitedUserIds.map(String::trim).filter(String::isNotEmpty).distinct().size > 1,
    ): String =
        withContext(Dispatchers.IO) {
            val invitees = invitedUserIds.map(String::trim).filter(String::isNotEmpty).distinct()
            require(if (isGroup) invitees.size >= 2 else invitees.size == 1) {
                if (isGroup) "A group needs at least two invitees." else "A one-to-one conversation needs one invitee."
            }
            require(invitees.all { it.matches(Regex("^@[^:\\s]+:[^\\s]+$")) }) {
                "Every invitee must be a complete Matrix user ID."
            }
            val accountUserId = ownUserId
            require(accountUserId == null || accountUserId !in invitees) {
                "Do not include your own Matrix ID in the invitees."
            }
            val roomName = name?.trim()?.takeIf(String::isNotEmpty)
            var stage = "build-parameters"
            try {
                val parameters = CreateRoomParameters(
                    name = roomName,
                    isEncrypted = true,
                    isDirect = !isGroup,
                    visibility = org.matrix.rustcomponents.sdk.RoomVisibility.Private,
                    preset = org.matrix.rustcomponents.sdk.RoomPreset.PRIVATE_CHAT,
                    invite = invitees,
                    joinRuleOverride = org.matrix.rustcomponents.sdk.JoinRule.Invite,
                )
                stage = "homeserver-create-room"
                val matrixClient = requireClient()
                val roomId = matrixClient.createRoom(parameters)
                stage = "verify-room-encryption"
                val isEncrypted = awaitEncryptedRoom(matrixClient, roomId)
                check(isEncrypted == true) {
                    "The homeserver did not return an encrypted room."
                }
                stage = "refresh-conversations"
                refreshConversations()
                roomId
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw MatrixRoomCreateFailure(stage, failure)
            }
        }

    private suspend fun awaitEncryptedRoom(matrixClient: Client, roomId: String): Boolean? =
        withTimeoutOrNull(ROOM_CREATE_ENCRYPTION_TIMEOUT_MS) {
            while (true) {
                // A newly-created room handle may only be partially synced. Ask
                // the SDK for the latest encryption state before accepting it.
                val room = matrixClient.getRoom(roomId) ?: matrixClient.awaitRoomRemoteEcho(roomId)
                val encryptionState = try {
                    room.latestEncryptionState()
                } finally {
                    room.destroy()
                }
                if (encryptionState.name == "ENCRYPTED") return@withTimeoutOrNull true
                delay(ROOM_CREATE_ENCRYPTION_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }

    suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        if (logoutInProgress) return@withContext
        val matrixClient = client ?: return@withContext
        val directRooms = runCatching {
            JSONObject(matrixClient.accountData("m.direct") ?: "{}")
        }.getOrDefault(JSONObject())
        val rows = matrixClient.rooms().mapNotNull { room ->
            runCatching {
                val info = room.roomInfo()
                val latest = room.latestEvent()
                try {
                    if (info.topic == VERIFICATION_CONTROL_ROOM_TOPIC) {
                        val peerUserId = info.inviter?.userId
                            ?: directPeerForRoom(directRooms, room.id())
                        if (info.membership == org.matrix.rustcomponents.sdk.Membership.INVITED && peerUserId != null) {
                            runCatching { prewarmVerificationPeer(matrixClient, peerUserId) }
                            return@runCatching ConversationSummary(
                                roomId = room.id(),
                                title = "Device verification",
                                preview = "Private verification channel invitation",
                                lastActivityMillis = latest.timestampMillis(),
                                unreadCount = 0,
                                isEncrypted = false,
                                isGroup = false,
                                membership = info.membership.name,
                                isVerificationControl = true,
                                verificationPeerUserId = peerUserId,
                            )
                        }
                        // Joined control rooms carry Matrix's verification handshake only. They
                        // are deliberately absent from the conversation list and normal messaging UI.
                        return@runCatching null
                    }
                    ConversationSummary(
                        roomId = room.id(),
                        title = info.displayName?.takeIf { it.isNotBlank() }
                            ?: room.displayName()?.takeIf { it.isNotBlank() }
                            ?: "Private conversation",
                        preview = latest.previewText(),
                        lastActivityMillis = latest.timestampMillis(),
                        unreadCount = info.numUnreadMessages.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        isEncrypted = room.encryptionState().name == "ENCRYPTED",
                        // m.direct is reserved for the verification control channel after the
                        // first trust ceremony. Participant counts preserve the user's 1:1/group
                        // presentation even though the SDK's DM helper must select the control room.
                        isGroup = info.joinedMembersCount + info.invitedMembersCount > 2uL,
                        membership = info.membership.name,
                    )
                } finally {
                    latest.destroy()
                    info.destroy()
                }
            }.getOrNull()
        }.sortedByDescending(ConversationSummary::lastActivityMillis)
        _conversations.value = rows
        val mediaReadiness = ensurePendingMediaObserversForKnownRooms()
        if (mediaReadiness.readyRoomIds.isNotEmpty() && hasPendingDataMediaReplayWork()) {
            requestPendingDataMediaReplay(resetRetryBudget = mediaReadiness.newlyReadyRoomIds.isNotEmpty())
        }
        matrixClient.rooms().forEach { room ->
            if (runCatching { room.encryptionState().name == "ENCRYPTED" }.getOrDefault(false)) {
                runCatching { ensureDeliveryAckObserver(room) }
            }
        }
    }

    suspend fun searchMessages(query: String) = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            _searchResults.value = emptyList()
            _searchHasMore.value = false
            _searchLoading.value = false
            searchService?.setQuery("")
            return@withContext
        }
        val service = getSearchService()
        _searchResults.value = emptyList()
        service.setQuery(normalized)
    }

    suspend fun paginateMessageSearch() = withContext(Dispatchers.IO) {
        if (_searchHasMore.value && !_searchLoading.value) getSearchService().paginate()
    }

    suspend fun setReadReceiptsEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(vault) {
            check(!logoutInProgress) { "Secure sign-out is in progress" }
            vault.saveReadReceiptsEnabled(enabled)
        }
        _readReceiptsEnabled.value = enabled
    }

    suspend fun setPushNotificationsEnabled(enabled: Boolean): PushRegistrationStatus =
        withContext(Dispatchers.IO) {
            withLifecycleLock {
                check(!logoutInProgress) { "Secure sign-out is in progress" }
                if (enabled && !MatrixPushClient.isConfigured) {
                    _pushRegistrationStatus.value = PushRegistrationStatus.DISABLED
                    return@withLifecycleLock PushRegistrationStatus.DISABLED
                }

                if (!enabled) {
                    val wasEnabled = _pushNotificationsEnabled.value
                    val hasPusherRecord = runCatching { vault.hasPushPusherRecord() }.getOrDefault(true)
                    val removalWasPending = runCatching { vault.loadPushRemovalPending() }.getOrDefault(true)
                    val needsRemoval = wasEnabled || hasPusherRecord || removalWasPending
                    if (needsRemoval) {
                        try {
                            synchronized(vault) { vault.savePushOptOutWithRemovalPending() }
                        } catch (_: Exception) {
                            _pushRegistrationStatus.value = PushRegistrationStatus.REMOVAL_PENDING
                            return@withLifecycleLock PushRegistrationStatus.REMOVAL_PENDING
                        }
                    } else {
                        synchronized(vault) { vault.savePushNotificationsEnabled(false) }
                    }
                    _pushNotificationsEnabled.value = false
                    pushTokenObserver?.cancel()
                    pushTokenObserver = null
                    MatrixPushClient.clearDisplayedNotifications(appContext)
                    val staged = persistPushRemovalIntent(
                        client,
                        createMissing = needsRemoval,
                        operation = PushPusherOperation.REMOVE_PENDING,
                    )
                    if (staged != PushRegistrationStatus.REMOVED) {
                        _pushRegistrationStatus.value = PushRegistrationStatus.REMOVAL_PENDING
                        return@withLifecycleLock PushRegistrationStatus.REMOVAL_PENDING
                    }
                    val result = removePushPusherForSession(client, createMissing = needsRemoval)
                    _pushRegistrationStatus.value = if (result == PushRegistrationStatus.REMOVED) {
                        disabledPushStatus()
                    } else {
                        PushRegistrationStatus.REMOVAL_PENDING
                    }
                    return@withLifecycleLock result
                }

                val activeClient = client
                val pendingRemoval = runCatching {
                    shouldRecoverPushOptOutRemoval(
                        durableRemovalMarker = vault.loadPushRemovalPending(),
                        record = vault.loadPushPusherRecord(),
                    )
                }.getOrDefault(true)
                if (pendingRemoval) {
                    if (activeClient == null) {
                        _pushRegistrationStatus.value = PushRegistrationStatus.REMOVAL_PENDING
                        return@withLifecycleLock PushRegistrationStatus.REMOVAL_PENDING
                    }
                    val removed = removePushPusherForSession(
                        activeClient,
                        createMissing = runCatching { vault.loadPushRemovalPending() }.getOrDefault(true),
                    )
                    if (removed != PushRegistrationStatus.REMOVED) {
                        _pushRegistrationStatus.value = PushRegistrationStatus.REMOVAL_PENDING
                        return@withLifecycleLock PushRegistrationStatus.REMOVAL_PENDING
                    }
                }
                synchronized(vault) { vault.savePushNotificationsEnabled(true) }
                _pushNotificationsEnabled.value = true

                if (activeClient == null) {
                    _pushRegistrationStatus.value = PushRegistrationStatus.PROVIDER_UNAVAILABLE
                    return@withLifecycleLock PushRegistrationStatus.PROVIDER_UNAVAILABLE
                }
                _pushRegistrationStatus.value = PushRegistrationStatus.REGISTERING
                val result = reconcilePushRegistration(activeClient)
                _pushRegistrationStatus.value = result
                startPushTokenObserver()
                result
            }
        }

    suspend fun refreshPushNotifications(): PushRegistrationStatus = withContext(Dispatchers.IO) {
        withLifecycleLock {
            val activeClient = client
            if (activeClient == null) {
                val status = if (vault.loadPushRemovalPending() || vault.hasPushPusherRecord()) {
                    PushRegistrationStatus.REMOVAL_PENDING
                } else if (_pushNotificationsEnabled.value) {
                    PushRegistrationStatus.PROVIDER_UNAVAILABLE
                } else {
                    disabledPushStatus()
                }
                _pushRegistrationStatus.value = status
                return@withLifecycleLock status
            }

            _pushRegistrationStatus.value = if (
                (vault.loadPushRemovalPending() || vault.hasPushPusherRecord()) && !_pushNotificationsEnabled.value
            ) {
                PushRegistrationStatus.REMOVING
            } else {
                PushRegistrationStatus.REGISTERING
            }
            val result = reconcilePushRegistration(activeClient)
            if (client === activeClient && !logoutInProgress) {
                _pushRegistrationStatus.value = result
                if (_pushNotificationsEnabled.value) startPushTokenObserver()
            }
            result
        }
    }

    private fun schedulePushRegistration() {
        val hasDurablePusherState = runCatching { vault.hasPushPusherRecord() }.getOrDefault(true)
        val removalPending = runCatching { vault.loadPushRemovalPending() }.getOrDefault(true)
        if (!_pushNotificationsEnabled.value && !hasDurablePusherState && !removalPending) {
            _pushRegistrationStatus.value = disabledPushStatus()
            return
        }
        if (_pushNotificationsEnabled.value && !MatrixPushClient.isConfigured && !hasDurablePusherState && !removalPending) {
            _pushRegistrationStatus.value = PushRegistrationStatus.DISABLED
            return
        }
        val activeClient = client ?: return
        _pushRegistrationStatus.value = if ((hasDurablePusherState || removalPending) && !_pushNotificationsEnabled.value) {
            PushRegistrationStatus.REMOVING
        } else {
            PushRegistrationStatus.REGISTERING
        }
        callbackScope.launch {
            withLifecycleLock {
                if (client !== activeClient || logoutInProgress) return@withLifecycleLock
                if (!_pushNotificationsEnabled.value &&
                    !runCatching { vault.hasPushPusherRecord() }.getOrDefault(true) &&
                    !runCatching { vault.loadPushRemovalPending() }.getOrDefault(true)
                ) {
                    _pushRegistrationStatus.value = disabledPushStatus()
                    return@withLifecycleLock
                }
                val result = reconcilePushRegistration(activeClient)
                if (client === activeClient && !logoutInProgress) {
                    _pushRegistrationStatus.value = result
                    if (_pushNotificationsEnabled.value) startPushTokenObserver()
                }
            }
        }
    }

    private fun startPushTokenObserver() {
        if (pushTokenObserver?.isActive == true || !_pushNotificationsEnabled.value ||
            !MatrixPushClient.isConfigured || logoutInProgress
        ) return

        pushTokenObserver = callbackScope.launch {
            MatrixPushClient.refreshedTokens.filterNotNull().distinctUntilChanged().collect { token ->
                withLifecycleLock {
                    val activeClient = client
                    if (activeClient == null || logoutInProgress || !_pushNotificationsEnabled.value) {
                        return@withLifecycleLock
                    }
                    _pushRegistrationStatus.value = PushRegistrationStatus.REGISTERING
                    val result = reconcilePushRegistration(activeClient, token)
                    if (client === activeClient && !logoutInProgress && _pushNotificationsEnabled.value) {
                        _pushRegistrationStatus.value = result
                    }
                }
            }
        }
    }

    private suspend fun reconcilePushRegistration(
        activeClient: Client,
        tokenOverride: String? = null,
    ): PushRegistrationStatus {
        val pushSession = matrixPushSession(activeClient) ?: return PushRegistrationStatus.FAILED
        val identity = pushPusherIdentity(pushSession) ?: return PushRegistrationStatus.FAILED
        var existing = try {
            synchronized(vault) { vault.loadPushPusherRecord() }
        } catch (_: Exception) {
            return PushRegistrationStatus.REMOVAL_PENDING
        }
        val removalPending = runCatching { synchronized(vault) { vault.loadPushRemovalPending() } }
            .getOrDefault(true)
        if (removalPending) {
            try {
                synchronized(vault) { vault.savePushOptOutWithRemovalPending() }
            } catch (_: Exception) {
                return PushRegistrationStatus.REMOVAL_PENDING
            }
            _pushNotificationsEnabled.value = false
            pushTokenObserver?.cancel()
            pushTokenObserver = null
            MatrixPushClient.clearDisplayedNotifications(appContext)
            val staged = persistPushRemovalIntent(
                activeClient,
                createMissing = true,
                operation = PushPusherOperation.REMOVE_PENDING,
            )
            if (staged != PushRegistrationStatus.REMOVED) return PushRegistrationStatus.REMOVAL_PENDING
            existing = try {
                synchronized(vault) { vault.loadPushPusherRecord() }
            } catch (_: Exception) {
                return PushRegistrationStatus.REMOVAL_PENDING
            }
        }
        if (existing != null && existing.identity != identity) return PushRegistrationStatus.REMOVAL_PENDING

        if (existing?.operation in setOf(PushPusherOperation.REMOVE_PENDING, PushPusherOperation.ROTATION_REMOVE_PENDING)) {
            val resumeRegistration = shouldResumeRegistrationAfterRecoveredRotation(
                checkNotNull(existing),
                optedIn = _pushNotificationsEnabled.value,
            )
            if (shouldDisablePushAfterRecoveredRemoval(checkNotNull(existing))) {
                try {
                    synchronized(vault) { vault.savePushOptOutWithRemovalPending() }
                } catch (_: Exception) {
                    return PushRegistrationStatus.REMOVAL_PENDING
                }
                _pushNotificationsEnabled.value = false
                pushTokenObserver?.cancel()
                pushTokenObserver = null
                MatrixPushClient.clearDisplayedNotifications(appContext)
            }
            _pushRegistrationStatus.value = PushRegistrationStatus.REMOVING
            val removed = removePushPusherForSession(
                activeClient,
                createMissing = false,
                operation = checkNotNull(existing).operation,
            )
            if (removed != PushRegistrationStatus.REMOVED) return PushRegistrationStatus.REMOVAL_PENDING
            existing = null
            if (!resumeRegistration) return disabledPushStatus()
        }

        if (!_pushNotificationsEnabled.value) {
            if (existing != null) {
                _pushRegistrationStatus.value = PushRegistrationStatus.REMOVING
                val removed = removePushPusherForSession(activeClient, createMissing = false)
                if (removed != PushRegistrationStatus.REMOVED) return PushRegistrationStatus.REMOVAL_PENDING
            }
            return disabledPushStatus()
        }
        if (!MatrixPushClient.isConfigured) return PushRegistrationStatus.DISABLED

        val token = tokenOverride?.takeIf(String::isNotBlank)
            ?: MatrixPushClient.currentToken()
            ?: return PushRegistrationStatus.PROVIDER_UNAVAILABLE
        val plan = planPushPusherSync(existing, identity, token, optedIn = true)
        when (plan) {
            PushPusherPlan.IDENTITY_MISMATCH -> return PushRegistrationStatus.REMOVAL_PENDING
            PushPusherPlan.REMOVE_EXISTING -> {
                _pushRegistrationStatus.value = PushRegistrationStatus.REMOVING
                val removed = removePushPusherForSession(
                    activeClient,
                    createMissing = false,
                    operation = PushPusherOperation.ROTATION_REMOVE_PENDING,
                )
                if (removed != PushRegistrationStatus.REMOVED) return PushRegistrationStatus.REMOVAL_PENDING
                existing = null
            }
            PushPusherPlan.NO_CHANGE -> return if (existing?.operation == PushPusherOperation.REGISTERED) {
                PushRegistrationStatus.REGISTERED
            } else {
                PushRegistrationStatus.FAILED
            }
            PushPusherPlan.REGISTER_DESIRED -> Unit
        }

        val pendingRegistration = PushPusherRecord(identity, token, PushPusherOperation.REGISTER_PENDING)
        try {
            synchronized(vault) { vault.savePushPusherRecord(pendingRegistration) }
        } catch (_: Exception) {
            return PushRegistrationStatus.FAILED
        }
        val registration = MatrixPushClient.registerToken(appContext, pushSession, token)
        if (registration == PushRegistrationStatus.REGISTERED) {
            try {
                synchronized(vault) {
                    vault.savePushPusherRecord(pendingRegistration.copy(operation = PushPusherOperation.REGISTERED))
                }
            } catch (_: Exception) {
                // The durable intent remains REGISTER_PENDING, so foreground recovery repeats
                // this idempotent set request instead of losing the provider token.
                return PushRegistrationStatus.FAILED
            }
        }
        return registration
    }

    private suspend fun persistPushRemovalIntent(
        activeClient: Client?,
        createMissing: Boolean,
        operation: PushPusherOperation = PushPusherOperation.REMOVE_PENDING,
    ): PushRegistrationStatus {
        val existing = try {
            synchronized(vault) { vault.loadPushPusherRecord() }
        } catch (_: Exception) {
            return PushRegistrationStatus.REMOVAL_PENDING
        }
        if (existing == null && !createMissing) return PushRegistrationStatus.REMOVED
        val pushSession = matrixPushSession(activeClient) ?: return PushRegistrationStatus.REMOVAL_PENDING
        val identity = pushPusherIdentity(pushSession) ?: return PushRegistrationStatus.REMOVAL_PENDING
        if (existing != null && existing.identity != identity) return PushRegistrationStatus.REMOVAL_PENDING

        val pendingRemoval = (existing ?: PushPusherRecord(identity, null, operation)).copy(operation = operation)
        try {
            synchronized(vault) { vault.savePushPusherRecord(pendingRemoval) }
        } catch (_: Exception) {
            return PushRegistrationStatus.REMOVAL_PENDING
        }
        return PushRegistrationStatus.REMOVED
    }

    private suspend fun removePushPusherForSession(
        activeClient: Client?,
        createMissing: Boolean,
        operation: PushPusherOperation = PushPusherOperation.REMOVE_PENDING,
    ): PushRegistrationStatus {
        val staged = persistPushRemovalIntent(activeClient, createMissing, operation)
        if (staged != PushRegistrationStatus.REMOVED) return PushRegistrationStatus.REMOVAL_PENDING
        val pendingRemoval = try {
            synchronized(vault) { vault.loadPushPusherRecord() }
        } catch (_: Exception) {
            return PushRegistrationStatus.REMOVAL_PENDING
        } ?: return PushRegistrationStatus.REMOVED
        val pushSession = matrixPushSession(activeClient) ?: return PushRegistrationStatus.REMOVAL_PENDING
        val identity = pushPusherIdentity(pushSession) ?: return PushRegistrationStatus.REMOVAL_PENDING
        if (pendingRemoval.identity != identity || pendingRemoval.operation != operation) {
            return PushRegistrationStatus.REMOVAL_PENDING
        }
        _pushRegistrationStatus.value = PushRegistrationStatus.REMOVING

        val token = pendingRemoval.pushToken ?: MatrixPushClient.currentToken()
            ?: return PushRegistrationStatus.REMOVAL_PENDING
        if (pendingRemoval.pushToken == null) {
            val tokenizedRemoval = pendingRemoval.copy(pushToken = token)
            try {
                synchronized(vault) { vault.savePushPusherRecord(tokenizedRemoval) }
            } catch (_: Exception) {
                return PushRegistrationStatus.REMOVAL_PENDING
            }
        }
        val result = MatrixPushClient.unregister(appContext, pushSession, pushToken = token)
        if (result != PushRegistrationStatus.REMOVED) return PushRegistrationStatus.REMOVAL_PENDING
        return try {
            synchronized(vault) {
                vault.clearPushPusherRecord()
                if (operation == PushPusherOperation.REMOVE_PENDING) {
                    // If this settings update fails, recovery safely repeats an idempotent DELETE.
                    vault.savePushRemovalPending(false)
                }
            }
            PushRegistrationStatus.REMOVED
        } catch (_: Exception) {
            // Retrying DELETE is idempotent and is safer than dropping the durable intent.
            PushRegistrationStatus.REMOVAL_PENDING
        }
    }

    private suspend fun matrixPushSession(activeClient: Client?): MatrixPushSession? {
        val sdkSession = activeClient?.let { runCatching { it.session() }.getOrNull() }
            ?: runCatching { synchronized(vault) { vault.loadSession() } }.getOrNull()
            ?: return null
        return MatrixPushSession(
            homeserverUrl = sdkSession.homeserverUrl,
            userId = sdkSession.userId,
            deviceId = sdkSession.deviceId,
            accessToken = sdkSession.accessToken,
            deviceDisplayName = "Android device",
        )
    }

    private fun pushPusherIdentity(pushSession: MatrixPushSession): PushPusherIdentity? =
        runCatching {
            PushPusherIdentity(
                homeserverUrl = HomeserverUrlPolicy.normalize(pushSession.homeserverUrl, allowPrivateHttp = false),
                userId = pushSession.userId,
                deviceId = pushSession.deviceId,
                appId = MatrixPushClient.APP_ID,
            )
        }.getOrNull()

    private fun disabledPushStatus(): PushRegistrationStatus = if (MatrixPushClient.isConfigured) {
        PushRegistrationStatus.NOT_ENABLED
    } else {
        PushRegistrationStatus.DISABLED
    }

    suspend fun joinConversation(roomId: String) = withContext(Dispatchers.IO) {
        val room = requireClient().rooms().firstOrNull { it.id() == roomId }
            ?: throw IllegalArgumentException("This invitation is no longer available")
        val info = room.roomInfo()
        try {
            check(room.encryptionState().name == "ENCRYPTED") {
                "This invitation is not end-to-end encrypted and cannot be joined"
            }
            if (info.membership == org.matrix.rustcomponents.sdk.Membership.INVITED) room.join()
        } finally {
            info.destroy()
        }
        refreshConversations()
    }

    suspend fun requestPeerVerification(roomId: String) = withContext(Dispatchers.IO) {
        val room = requireRoom(roomId)
        check(room.encryptionState().name == "ENCRYPTED") {
            "Device verification is available for encrypted conversations"
        }
        val expectedMembers = room.activeHumanMemberIds().filterNot { it == ownUserId }.toSet()
        expectedDeliveryMemberIdsByRoom[room.id()] = expectedMembers
        if (expectedMembers.size != 1) {
            _peerTrust.value = PeerTrustStatus.UNKNOWN
            return@withContext
        }
        val peerUserId = expectedMembers.firstOrNull()
            ?: throw IllegalStateException("No other conversation member is available to verify")
        val controller = getVerificationController()
        _verification.value = DeviceVerificationUiState(
            peerUserId = peerUserId,
            status = DeviceVerificationStatus.WAITING_FOR_ACCEPT,
        )
        try {
            val matrixClient = requireClient()
            val controlRoomId = withVerificationStage("control-room-create") {
                ensureVerificationControlRoom(matrixClient, peerUserId)
            }
            withVerificationStage("control-room-route") {
                selectVerificationControlRoom(matrixClient, peerUserId, controlRoomId)
            }
            withVerificationStage("control-room-join") {
                check(awaitVerificationPeerJoin(matrixClient, controlRoomId)) {
                    "Your friend has not joined the verification channel yet. Ask them to join the invitation, then try again."
                }
            }
            // This asks the SDK for the peer identity when none is tracked yet, before the
            // unencrypted control-room request is processed on the other device.
            withVerificationStage("peer-identity") {
                prewarmVerificationPeer(matrixClient, peerUserId, retryIfAlreadyAttempted = true)
            }
            withVerificationStage("sdk-room-selection") {
                check(matrixClient.getDmRoom(peerUserId)?.id() == controlRoomId) {
                    "The Matrix SDK did not select the private verification channel. Try again after syncing."
                }
            }
            withVerificationStage("sdk-request") {
                verificationWasInitiatedHere = true
                controller.requestUserVerification(peerUserId)
            }
        } catch (error: Exception) {
            verificationWasInitiatedHere = false
            _verification.value = DeviceVerificationUiState(
                peerUserId = peerUserId,
                status = DeviceVerificationStatus.FAILED,
                error = verificationErrorMessage(error),
            )
            throw error
        }
    }

    private suspend fun <T> withVerificationStage(
        stage: String,
        operation: suspend () -> T,
    ): T = try {
        verificationDiagnosticStage = stage
        operation()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw IllegalStateException("verification-stage-$stage", failure)
    }

    internal fun verificationStageForDiagnostic(): String = verificationDiagnosticStage

    internal fun watchReadReceiptForDiagnostic(eventId: String) {
        check(BuildConfig.DEBUG) { "Read-receipt diagnostics are unavailable in release builds" }
        synchronized(readReceiptDiagnosticLock) {
            readReceiptDiagnosticEventId = eventId
            val existing = _messages.value.firstOrNull { it.eventId == eventId }
            readReceiptDiagnosticSnapshot = ReadReceiptDiagnosticSnapshot(
                eventSeen = existing != null,
                isOwnEvent = existing?.isOwn == true,
                hasOtherReader = existing?.hasBeenRead == true,
                mappedReadState = existing?.hasBeenRead == true,
                appModelReadState = existing?.hasBeenRead == true,
            )
        }
    }

    internal fun readReceiptDiagnosticSnapshot(): ReadReceiptDiagnosticSnapshot {
        check(BuildConfig.DEBUG) { "Read-receipt diagnostics are unavailable in release builds" }
        return synchronized(readReceiptDiagnosticLock) { readReceiptDiagnosticSnapshot }
    }

    internal suspend fun peerReadReceiptCacheStateForDiagnostic(
        roomId: String,
        peerUserId: String,
        eventId: String,
    ): String = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Read-receipt diagnostics are unavailable in release builds" }
        val room = requireRoom(roomId)
        try {
            when (room.loadUserReceipt(ReceiptType.READ, ReceiptThread.Unthreaded, peerUserId)?.eventId) {
                null -> "missing"
                eventId -> "target"
                else -> "other"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "unavailable"
        } finally {
            room.close()
        }
    }

    /** Return allowlisted aggregate undecryptable-event causes for isolated debug acceptance runs. */
    internal fun utdCauseCountsForDiagnostic(): Map<String, Int> {
        check(BuildConfig.DEBUG) { "Encryption diagnostics are unavailable in release builds" }
        return diagnosticUtdCauseCounts.entries.associate { (cause, count) -> cause to count.get() }
    }

    /** Return only allowlisted event-kind counts for a room in isolated debug acceptance runs. */
    internal fun timelineCategoriesForDiagnostic(roomId: String): Map<String, Int> {
        check(BuildConfig.DEBUG) { "Encryption diagnostics are unavailable in release builds" }
        return diagnosticTimelineCategoriesByRoom[roomId]?.entries
            ?.associate { (category, count) -> category to count.get() }
            .orEmpty()
    }

    /** Query only aggregate encrypted-event counts from a room history response during debug acceptance. */
    internal suspend fun recentEncryptedEventCountsForDiagnostic(roomId: String, peerUserId: String): String =
        withContext(Dispatchers.IO) {
            check(BuildConfig.DEBUG) { "Encryption diagnostics are unavailable in release builds" }
            val session = requireClient().session()
            val url = URL(
                "${session.homeserverUrl.trimEnd('/')}/_matrix/client/v3/rooms/${Uri.encode(roomId)}/messages?dir=b&limit=50",
            )
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                val status = connection.responseCode
                if (status !in 200..299) return@withContext "$status|0|0|0"

                val response = connection.inputStream.bufferedReader().use { reader ->
                    JSONObject(reader.readText())
                }
                val events = response.optJSONArray("chunk")
                var peerEncrypted = 0
                var ownEncrypted = 0
                var otherEncrypted = 0
                if (events != null) {
                    for (index in 0 until events.length()) {
                        val event = events.optJSONObject(index) ?: continue
                        if (event.optString("type") != "m.room.encrypted") continue
                        when (event.optString("sender")) {
                            peerUserId -> peerEncrypted++
                            session.userId -> ownEncrypted++
                            else -> otherEncrypted++
                        }
                    }
                }
                "$status|${peerEncrypted.coerceAtMost(999)}|${ownEncrypted.coerceAtMost(999)}|${otherEncrypted.coerceAtMost(999)}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "0|0|0|0"
            } finally {
                connection.disconnect()
            }
        }

    /** Return only allowlisted verification event counts and sender roles during debug acceptance. */
    internal suspend fun verificationProtocolEventCountsForDiagnostic(
        roomId: String,
        peerUserId: String,
    ): String = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Verification diagnostics are unavailable in release builds" }
        val session = requireClient().session()
        val eventNames = listOf("request", "ready", "start", "accept", "key", "mac", "done", "cancel")
        val counts = eventNames.associateWith { name ->
            mutableMapOf("own" to 0, "peer" to 0, "other" to 0)
        }
        val url = URL(
            "${session.homeserverUrl.trimEnd('/')}/_matrix/client/v3/rooms/${Uri.encode(roomId)}/messages?dir=b&limit=50",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            val status = connection.responseCode
            if (status !in 200..299) return@withContext "http=$status"

            val response = connection.inputStream.bufferedReader().use { reader -> JSONObject(reader.readText()) }
            val events = response.optJSONArray("chunk")
            if (events != null) {
                for (index in 0 until events.length()) {
                    val event = events.optJSONObject(index) ?: continue
                    val type = event.optString("type")
                    val name = when (type) {
                        "m.key.verification.request" -> "request"
                        "m.key.verification.ready" -> "ready"
                        "m.key.verification.start" -> "start"
                        "m.key.verification.accept" -> "accept"
                        "m.key.verification.key" -> "key"
                        "m.key.verification.mac" -> "mac"
                        "m.key.verification.done" -> "done"
                        "m.key.verification.cancel" -> "cancel"
                        else -> continue
                    }
                    val role = when (event.optString("sender")) {
                        session.userId -> "own"
                        peerUserId -> "peer"
                        else -> "other"
                    }
                    val roleCounts = counts.getValue(name)
                    roleCounts[role] = (roleCounts[role] ?: 0) + 1
                }
            }
            "http=200|" + eventNames.joinToString("|") { name ->
                val roleCounts = counts.getValue(name)
                "$name=${roleCounts.getValue("own")},${roleCounts.getValue("peer")},${roleCounts.getValue("other")}"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "http=0"
        } finally {
            connection.disconnect()
        }
    }

    private fun recordDiagnosticUtdCause(eventId: String, cause: String) {
        if (!BuildConfig.DEBUG || !diagnosticUtdEventIds.add(eventId)) return
        diagnosticUtdCauseCounts.computeIfAbsent(cause) { AtomicInteger() }.incrementAndGet()
    }

    private fun recordDiagnosticTimelineCategory(roomId: String, eventId: String, category: String) {
        if (!BuildConfig.DEBUG) return
        val seenEvents = diagnosticTimelineEventIdsByRoom.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }
        if (!seenEvents.add(eventId)) return
        val categoryCounts = diagnosticTimelineCategoriesByRoom.computeIfAbsent(roomId) { ConcurrentHashMap() }
        categoryCounts.computeIfAbsent(category) { AtomicInteger() }.incrementAndGet()
    }

    private fun verificationErrorMessage(failure: Throwable): String? {
        var current = failure
        while (current.message.orEmpty().startsWith("verification-stage-")) {
            current = current.cause ?: break
        }
        return current.message
    }

    /** Join a protocol-only invitation after confirming it came from an existing encrypted peer. */
    suspend fun joinVerificationControlRoom(roomId: String) = withContext(Dispatchers.IO) {
        val matrixClient = requireClient()
        val room = matrixClient.rooms().firstOrNull { it.id() == roomId }
            ?: throw IllegalArgumentException("This verification invitation is no longer available")
        val info = room.roomInfo()
        val peerUserId = try {
            check(info.topic == VERIFICATION_CONTROL_ROOM_TOPIC) {
                "This is not a Friendline verification channel."
            }
            check(info.membership == org.matrix.rustcomponents.sdk.Membership.INVITED) {
                "This verification invitation has already been accepted."
            }
            check(info.joinedMembersCount + info.invitedMembersCount <= 2uL) {
                "The verification channel includes an unexpected participant."
            }
            info.inviter?.userId
                ?: throw IllegalStateException("The verification invitation does not identify its sender")
        } finally {
            info.destroy()
        }

        val knownEncryptedPeer = matrixClient.rooms().any { candidate ->
            if (candidate.id() == roomId || candidate.encryptionState().name != "ENCRYPTED") return@any false
            val candidateInfo = candidate.roomInfo()
            try {
                candidateInfo.membership == org.matrix.rustcomponents.sdk.Membership.JOINED &&
                    peerUserId in candidate.activeHumanMemberIds()
            } finally {
                candidateInfo.destroy()
            }
        }
        check(knownEncryptedPeer) {
            "Only join a verification channel sent by someone in an existing encrypted conversation."
        }

        prewarmVerificationPeer(matrixClient, peerUserId, retryIfAlreadyAttempted = true)
        room.join()
        selectVerificationControlRoom(matrixClient, peerUserId, roomId)
        refreshConversations()
    }

    private suspend fun ensureVerificationControlRoom(matrixClient: Client, peerUserId: String): String {
        val existing = withVerificationStage("control-room-search") {
            matrixClient.rooms().firstOrNull { candidate ->
                if (candidate.encryptionState().name == "ENCRYPTED") return@firstOrNull false
                val info = candidate.roomInfo()
                try {
                    info.topic == VERIFICATION_CONTROL_ROOM_TOPIC &&
                        info.membership == org.matrix.rustcomponents.sdk.Membership.JOINED &&
                        peerUserId in candidate.activeHumanMemberIds()
                } finally {
                    info.destroy()
                }
            }
        }
        val roomId = existing?.id() ?: withVerificationStage("control-room-create-request") {
            matrixClient.createRoom(
                CreateRoomParameters(
                    name = VERIFICATION_CONTROL_ROOM_NAME,
                    topic = VERIFICATION_CONTROL_ROOM_TOPIC,
                    isEncrypted = false,
                    isDirect = true,
                    visibility = org.matrix.rustcomponents.sdk.RoomVisibility.Private,
                    preset = org.matrix.rustcomponents.sdk.RoomPreset.PRIVATE_CHAT,
                    invite = listOf(peerUserId),
                    historyVisibilityOverride = RoomHistoryVisibility.Joined,
                ),
            )
        }
        val stateReady = withVerificationStage("control-room-topic-state") {
            withTimeoutOrNull(VERIFICATION_CONTROL_ROOM_STATE_TIMEOUT_MS) {
                while (true) {
                    val room = matrixClient.getRoom(roomId)
                    if (room != null) {
                        val info = room.roomInfo()
                        try {
                            // A create-room response can precede its state echo in the local
                            // sync store. Wait for the protocol marker before validating and
                            // routing any user-verification event through this room.
                            if (info.topic == VERIFICATION_CONTROL_ROOM_TOPIC) {
                                check(room.encryptionState().name != "ENCRYPTED") {
                                    "The verification protocol room unexpectedly changed encryption state"
                                }
                                val participantCount = info.joinedMembersCount + info.invitedMembersCount
                                check(participantCount <= 2uL) {
                                    "The verification channel includes an unexpected participant"
                                }
                                check(peerUserId in room.activeHumanMemberIds() ||
                                    info.membership == org.matrix.rustcomponents.sdk.Membership.INVITED
                                ) {
                                    "The verification channel does not include the intended peer"
                                }
                                return@withTimeoutOrNull true
                            }
                        } finally {
                            info.destroy()
                            room.close()
                        }
                    }
                    delay(VERIFICATION_CONTROL_ROOM_STATE_POLL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == true
        }
        check(stateReady) {
            "The Matrix verification channel did not finish syncing its private protocol marker"
        }
        return roomId
    }

    private suspend fun selectVerificationControlRoom(
        matrixClient: Client,
        peerUserId: String,
        controlRoomId: String,
    ) {
        val mapping = JSONObject(matrixClient.accountData("m.direct") ?: "{}")
        mapping.put(peerUserId, JSONArray().put(controlRoomId))
        matrixClient.setAccountData("m.direct", mapping.toString())

        // The account-data PUT completes before the sync loop updates the SDK's local store.
        // Wait for both the stored m.direct mapping and the SDK's DM lookup to converge before
        // requesting verification; otherwise the SDK may send the request into the encrypted DM.
        val selected = withTimeoutOrNull(VERIFICATION_ROOM_ROUTE_SYNC_TIMEOUT_MS) {
            while (true) {
                val persisted = JSONObject(matrixClient.accountData("m.direct") ?: "{}")
                    .optJSONArray(peerUserId)
                val mappingMatches = persisted != null && persisted.length() == 1 &&
                    persisted.optString(0) == controlRoomId
                val routedRoomMatches = matrixClient.getDmRoom(peerUserId)?.id() == controlRoomId
                if (mappingMatches && routedRoomMatches) return@withTimeoutOrNull true
                delay(VERIFICATION_ROOM_ROUTE_SYNC_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true
        check(selected) {
            "The Matrix SDK did not sync the private verification-channel selection"
        }
    }

    private fun directPeerForRoom(mapping: JSONObject, roomId: String): String? =
        mapping.keys().asSequence().firstOrNull { peerUserId ->
            val roomIds = mapping.optJSONArray(peerUserId) ?: return@firstOrNull false
            (0 until roomIds.length()).any { index -> roomIds.optString(index) == roomId }
        }

    private suspend fun prewarmVerificationPeer(
        matrixClient: Client,
        peerUserId: String,
        retryIfAlreadyAttempted: Boolean = false,
    ) {
        if (!retryIfAlreadyAttempted && !verificationPeerPrewarmScheduled.add(peerUserId)) return
        try {
            val identity = matrixClient.encryption().userIdentity(peerUserId, true)
            identity?.close()
        } catch (failure: Exception) {
            verificationPeerPrewarmScheduled.remove(peerUserId)
            throw failure
        }
    }

    private suspend fun awaitVerificationPeerJoin(matrixClient: Client, roomId: String): Boolean =
        withTimeoutOrNull(VERIFICATION_ROOM_JOIN_TIMEOUT_MS) {
            while (true) {
                val room = matrixClient.getRoom(roomId)
                if (room != null) {
                    val info = room.roomInfo()
                    try {
                        if (info.joinedMembersCount >= 2uL) return@withTimeoutOrNull true
                    } finally {
                        info.destroy()
                    }
                }
                delay(VERIFICATION_ROOM_JOIN_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true

    /** Prewarm the SDK verification event listener for isolated acceptance diagnostics. */
    internal suspend fun prepareVerificationListenerForDiagnostic() = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Verification diagnostics are unavailable in release builds" }
        getVerificationController()
    }

    /** Return only the allowlisted state of this account's own SDK identity for isolated diagnostics. */
    internal suspend fun ownVerificationIdentityStateForDiagnostic(): String = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Verification diagnostics are unavailable in release builds" }
        val identity = try {
            val matrixClient = requireClient()
            val currentUserId = ownUserId ?: matrixClient.userId()
            // The controller prewarm above has already allowed a server fallback; inspect the
            // resulting local identity without generating another diagnostic network request.
            matrixClient.encryption().userIdentity(currentUserId, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext "unavailable"
        } ?: return@withContext "missing"

        try {
            if (identity.isVerified()) "verified" else "unverified"
        } catch (_: Exception) {
            "unavailable"
        } finally {
            identity.close()
        }
    }

    /** Return only the peer identity trust state for isolated debug acceptance runs. */
    internal suspend fun peerVerificationIdentityStateForDiagnostic(roomId: String): String =
        withContext(Dispatchers.IO) {
            check(BuildConfig.DEBUG) { "Verification diagnostics are unavailable in release builds" }
            val room = try {
                requireRoom(roomId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext "unavailable"
            }
            val peerUserId = try {
                room.activeHumanMemberIds().firstOrNull { it != ownUserId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext "unavailable"
            } finally {
                room.close()
            } ?: return@withContext "missing"

            val identity = try {
                requireClient().encryption().userIdentity(peerUserId, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext "unavailable"
            } ?: return@withContext "missing"

            try {
                when {
                    identity.hasVerificationViolation() -> "changed"
                    identity.isVerified() -> "verified"
                    identity.wasPreviouslyVerified() -> "previously-verified"
                    else -> "unverified"
                }
            } catch (_: Exception) {
                "unavailable"
            } finally {
                identity.close()
            }
        }

    suspend fun isPeerVerified(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val room = requireRoom(roomId)
        val peerUserId = try {
            room.activeHumanMemberIds().firstOrNull { it != ownUserId }
        } finally {
            room.close()
        } ?: return@withContext false
        val identity = requireClient().encryption().userIdentity(peerUserId, true)
            ?: return@withContext false
        try {
            identity.isVerified()
        } finally {
            identity.close()
        }
    }

    private suspend fun refreshPeerTrust(room: Room) {
        if (room.encryptionState().name != "ENCRYPTED") {
            expectedDeliveryMemberIdsByRoom.remove(room.id())
            _peerTrust.value = PeerTrustStatus.UNKNOWN
            return
        }
        val expectedMembers = room.activeHumanMemberIds().filterNot { it == ownUserId }.toSet()
        expectedDeliveryMemberIdsByRoom[room.id()] = expectedMembers
        if (expectedMembers.size != 1) {
            _peerTrust.value = PeerTrustStatus.UNKNOWN
            return
        }
        val peerUserId = expectedMembers.single()
        val identity = runCatching { requireClient().encryption().userIdentity(peerUserId, true) }.getOrNull()
        if (identity == null) {
            _peerTrust.value = PeerTrustStatus.UNVERIFIED
            return
        }
        try {
            _peerTrust.value = when {
                identity.hasVerificationViolation() -> PeerTrustStatus.CHANGED
                identity.isVerified() -> PeerTrustStatus.VERIFIED
                else -> PeerTrustStatus.UNVERIFIED
            }
        } finally {
            identity.close()
        }
    }

    /** Cross-signing signatures can arrive after the SAS flow's terminal callback. */
    private suspend fun refreshPeerTrustAfterVerification(roomId: String) {
        withTimeoutOrNull(PEER_TRUST_REFRESH_TIMEOUT_MS) {
            while (!logoutInProgress && activeRoomId == roomId) {
                val room = try {
                    requireRoom(roomId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withTimeoutOrNull
                }
                try {
                    refreshPeerTrust(room)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep waiting for the next sync update; trust remains unverified until
                    // the SDK exposes a verified identity.
                } finally {
                    room.close()
                }
                if (_peerTrust.value == PeerTrustStatus.VERIFIED || _peerTrust.value == PeerTrustStatus.CHANGED) {
                    return@withTimeoutOrNull
                }
                delay(PEER_TRUST_REFRESH_POLL_MS)
            }
        }
    }

    suspend fun acceptVerificationRequest() = withContext(Dispatchers.IO) {
        val request = checkNotNull(pendingVerificationRequest) { "There is no pending verification request" }
        val controller = getVerificationController()
        updateVerificationProgress()
        try {
            controller.acknowledgeVerificationRequest(request.first, request.second)
            controller.acceptVerificationRequest()
        } catch (error: Exception) {
            updateVerificationFailure(error)
            throw error
        }
    }

    suspend fun declineVerificationRequest() = withContext(Dispatchers.IO) {
        val controller = getVerificationController()
        val request = pendingVerificationRequest
        if (request != null) {
            controller.acknowledgeVerificationRequest(request.first, request.second)
            controller.declineVerification()
        } else {
            controller.cancelVerification()
        }
        pendingVerificationRequest = null
        verificationWasInitiatedHere = false
        _verification.update { current -> current?.copy(status = DeviceVerificationStatus.CANCELLED) }
    }

    suspend fun approveVerification() = withContext(Dispatchers.IO) {
        val controller = getVerificationController()
        _verification.update { current ->
            if (current == null || current.status in VERIFICATION_TERMINAL_STATUSES) current
            else current.copy(status = DeviceVerificationStatus.CONFIRMING)
        }
        try {
            controller.approveVerification()
        } catch (error: Exception) {
            updateVerificationFailure(error)
            throw error
        }
    }

    fun dismissVerification() {
        _verification.value = null
    }

    suspend fun openConversation(roomId: String) = openConversation(roomId, focusEventId = null)

    suspend fun openConversation(roomId: String, focusEventId: String?) = withContext(Dispatchers.IO) {
        var stage = "select-room"
        try {
            val room = requireClient().rooms().firstOrNull { it.id() == roomId }
                ?: throw IllegalArgumentException("This conversation is no longer available")
            stage = "read-encryption-state"
            check(room.encryptionState().name == "ENCRYPTED") {
                "This conversation is not end-to-end encrypted and cannot be opened"
            }

            stage = "release-previous-timeline"
            stopReadReceiptRefresh()
            activeRoomId = null
            val previousTimeline = activeTimeline
            activeTimeline = null
            timelineListenerHandle?.cancel()
            timelineListenerHandle?.close()
            typingListenerHandle?.cancel()
            typingListenerHandle?.close()
            typingListenerHandle = null
            previousTimeline?.close()
            clearTimelineMessages()
            activeRoomId = roomId
            stage = "refresh-peer-trust"
            refreshPeerTrust(room)
            stage = "create-timeline"
            val timelineConfiguration = TimelineConfiguration(
                focusEventId?.let { eventId ->
                    TimelineFocus.Event(
                        eventId = eventId,
                        numContextEvents = 40u.toUShort(),
                        threadMode = uniffi.matrix_sdk_ui.TimelineEventFocusThreadMode.Automatic(
                            hideThreadedEvents = false,
                        ),
                    )
                } ?: TimelineFocus.Live(false),
                TimelineFilter.All,
                "conversation-${UUID.randomUUID()}",
                DateDividerMode.DAILY,
                TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS,
                true,
            )
            val timeline = room.timelineWithConfiguration(timelineConfiguration)
            activeTimeline = timeline
            stage = "subscribe-timeline"
            timelineListenerHandle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    if (activeRoomId != roomId || activeTimeline !== timeline) {
                        diff.forEach { update -> runCatching { update.destroy() } }
                        return
                    }
                    val isReset = diff.any { it is TimelineDiff.Reset }
                    val observedAckTargets = observedAckTargetsByRoom.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }
                    if (isReset) observedAckTargets.clear()
                    val acknowledgements = mutableListOf<String>()
                    diff.forEach { update ->
                        try {
                            applyTimelineUpdate(roomId, update, acknowledgements)
                        } finally {
                            update.destroy()
                        }
                    }
                    acknowledgements.distinct().forEach { eventId ->
                        val account = ownUserId
                        callbackScope.launch {
                            if (!logoutInProgress && account != null && account == ownUserId && claimDeliveryAckEnqueue(roomId, eventId)) {
                                enqueueClaimedDeliveryAck(room, roomId, eventId)
                            }
                        }
                    }
                    if (!logoutInProgress && isReset) reconcilePendingDeliveryAcksAfterReset(room, roomId, observedAckTargets.toSet())
                }
            })
            stage = "subscribe-typing"
            typingListenerHandle = room.subscribeToTypingNotifications(object : TypingNotificationsListener {
                override fun call(typingUserIds: List<String>) {
                    _typingUsers.value = typingUserIds.filterNot { it == ownUserId }
                }
            })
            startReadReceiptRefresh(roomId, timeline)
            stage = "mark-read"
            stage = "load-draft"
            val savedDraft = room.loadComposerDraft(null)
            _composerDraft.value = savedDraft?.plainText.orEmpty()
            savedDraft?.destroy()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            stopReadReceiptRefresh()
            runCatching { timelineListenerHandle?.cancel() }
            runCatching { timelineListenerHandle?.close() }
            runCatching { typingListenerHandle?.cancel() }
            runCatching { typingListenerHandle?.close() }
            runCatching { activeTimeline?.close() }
            timelineListenerHandle = null
            typingListenerHandle = null
            activeTimeline = null
            activeRoomId = null
            clearTimelineMessages()
            throw MatrixConversationOpenFailure(stage, failure)
        }
    }

    suspend fun saveComposerDraft(roomId: String, body: String) = withContext(Dispatchers.IO) {
        val room = requireClient().rooms().firstOrNull { it.id() == roomId } ?: return@withContext
        if (body.isBlank()) {
            room.clearComposerDraft(null)
            _composerDraft.value = ""
        } else {
            val draft = ComposerDraft(
                plainText = body,
                htmlText = null,
                draftType = ComposerDraftType.NewMessage,
                attachments = emptyList(),
            )
            try {
                room.saveComposerDraft(draft, null)
                _composerDraft.value = body
            } finally {
                draft.destroy()
            }
        }
    }

    suspend fun closeConversation() = withContext(Dispatchers.IO) {
        val roomId = activeRoomId
        if (roomId != null) {
            runCatching { withSendQueueGate { requireRoom(roomId).typingNotice(false) } }
        }
        stopReadReceiptRefresh()
        activeRoomId = null
        val timeline = activeTimeline
        activeTimeline = null
        timelineListenerHandle?.cancel()
        timelineListenerHandle?.close()
        typingListenerHandle?.cancel()
        typingListenerHandle?.close()
        timeline?.close()
        timelineListenerHandle = null
        typingListenerHandle = null
        _typingUsers.value = emptyList()
        clearTimelineMessages()
        _composerDraft.value = ""
        _peerTrust.value = PeerTrustStatus.UNKNOWN
    }

    suspend fun sendText(roomId: String, body: String, replyToEventId: String? = null) = withContext(Dispatchers.IO) {
        val room = requireRoom(roomId)
        check(room.encryptionState().name == "ENCRYPTED") {
            "Messages are disabled because this conversation is not encrypted"
        }
        val timeline = activeTimeline?.takeIf { activeRoomId == roomId } ?: room.timeline().also {
            activeRoomId = roomId
            activeTimeline = it
        }
        val content: RoomMessageEventContentWithoutRelation = timeline.createMessageContent(
            MessageType.Text(TextMessageContent(body.trim(), null)),
        ) ?: throw IllegalStateException("This message could not be prepared")
        try {
            val handle = withSendQueueGate {
                val sendHandle = withDeliverySnapshotReservation(roomId, room) {
                    if (replyToEventId == null) {
                        timeline.send(content)
                    } else {
                        timeline.sendReply(content, replyToEventId)
                    }
                }
                sendHandle.destroy()
                runCatching { room.typingNotice(false) }
                runCatching { room.clearComposerDraft(null) }
                _composerDraft.value = ""
            }
        } finally {
            content.destroy()
        }
        refreshConversations()
    }

    /**
     * Send encrypted call control over the existing Matrix room. The 256-bit media key is
     * included only on an invitation and remains inside Matrix's encrypted event payload.
     */
    suspend fun sendCallSignal(
        roomId: String,
        action: MessengerCallAction,
        callId: String,
        kind: MessengerCallKind? = null,
        mediaKeyBase64: String? = null,
    ) = withContext(Dispatchers.IO) {
        check(!logoutInProgress && activeRoomId == roomId) { "Open the conversation before placing a call" }
        val room = requireRoom(roomId)
        check(room.encryptionState().name == "ENCRYPTED") {
            "Calls are disabled because this conversation is not end-to-end encrypted"
        }
        check(_peerTrust.value == PeerTrustStatus.VERIFIED) {
            "Verify this conversation before starting or joining a call"
        }
        check(expectedDeliveryMemberIdsByRoom[roomId]?.size == 1) {
            "Calls are currently available only in verified one-to-one conversations"
        }
        val content = CallProtocol.envelope(action, callId, kind, mediaKeyBase64)
        withSendQueueGate {
            check(activeRoomId == roomId && !logoutInProgress) { "The conversation changed before the call signal was sent" }
            room.sendRaw("m.room.message", content)
        }
    }

    suspend fun sendAttachment(
        roomId: String,
        contentUri: String,
        replyToEventId: String? = null,
        audioDurationMillis: Long? = null,
    ) =
        withContext(Dispatchers.IO) {
            check(!logoutInProgress) { "Secure sign-out is in progress" }
            val room = requireRoom(roomId)
            check(room.encryptionState().name == "ENCRYPTED") {
                "Attachments are disabled because this conversation is not encrypted"
            }
            val client = requireClient()
            val serverLimitBytes = runCatching { client.getMaxMediaUploadSize().toLong() }.getOrNull()
            val maximumBytes = effectiveMediaUploadLimit(serverLimitBytes, MAX_MEDIA_UPLOAD_BYTES)
            val staged = stageContentUri(contentUri, maximumBytes)
            var pendingMedia: PendingMediaRecord? = null
            var queueAttemptStarted = false
            val timeline = activeTimeline?.takeIf { activeRoomId == roomId } ?: room.timeline()
            val ownsTimeline = timeline !== activeTimeline
            try {
                val existingVoiceDraft = audioDurationMillis?.takeIf { it > 0 }?.let { duration ->
                    findPendingVoiceMediaRecord(roomId, staged, duration)
                }
                if (existingVoiceDraft != null) {
                    // This recording already has a durable outbox identity. Retry its local echo
                    // through SendHandle; never create a second m.audio event for the same draft.
                    retryPendingVoiceMedia(existingVoiceDraft)
                    runCatching { withSendQueueGate { room.typingNotice(false) } }
                    refreshConversations()
                    return@withContext
                }
                ensurePendingMediaObserver(roomId)
                mediaDataEnqueueMutex.lock()
                try {
                    // Create the durable record immediately before the FFI enqueue. This makes
                    // its local-echo timestamp window narrow even when another large upload is
                    // holding the one-payload memory guard.
                    val mediaRecord = preparePendingMedia(roomId, staged, audioDurationMillis)
                    pendingMedia = mediaRecord
                    markPendingMediaInFlight(mediaRecord.id)
                    enqueuePendingAttachment(room, mediaRecord, staged, timeline, replyToEventId) {
                        queueAttemptStarted = true
                    }
                } finally {
                    mediaDataEnqueueMutex.unlock()
                }
                // No Matrix queue item refers to this cache path; the SDK owns an encrypted
                // copy, and the app archive is encrypted independently for recovery.
                staged.file.parentFile?.deleteRecursively()
                pendingMedia?.let { updatePendingMediaRecord(it.id) { current -> current.copy(inFlight = false) } }
                runCatching { withSendQueueGate { room.typingNotice(false) } }
                refreshConversations()
            } catch (error: Throwable) {
                if (!queueAttemptStarted) pendingMedia?.let { removePendingMediaRecord(it.id) }
                throw error
            } finally {
                staged.file.parentFile?.deleteRecursively()
                if (ownsTimeline) timeline.close()
            }
        }

    /**
     * Diagnostic-only crash point: persist the same encrypted v2 archive and in-flight
     * journal state as a media send, but return before calling the Matrix SDK. The isolated
     * outbox instrumentation force-stops the process after this method returns.
     */
    internal suspend fun preparePendingMediaForDiagnosticCrashWindow(roomId: String, contentUri: String) =
        withContext(Dispatchers.IO) {
            check(BuildConfig.DEBUG) { "Outbox crash diagnostics are unavailable in release builds" }
            check(!logoutInProgress) { "Secure sign-out is in progress" }
            val room = requireRoom(roomId)
            check(room.encryptionState().name == "ENCRYPTED") {
                "Attachments are disabled because this conversation is not encrypted"
            }
            val client = requireClient()
            val serverLimitBytes = runCatching { client.getMaxMediaUploadSize().toLong() }.getOrNull()
            val maximumBytes = effectiveMediaUploadLimit(serverLimitBytes, MAX_MEDIA_UPLOAD_BYTES)
            val staged = stageContentUri(contentUri, maximumBytes)
            try {
                ensurePendingMediaObserver(roomId)
                mediaDataEnqueueMutex.withLock {
                    val record = preparePendingMedia(roomId, staged, null)
                    markPendingMediaInFlight(record.id)
                }
            } finally {
                staged.file.parentFile?.deleteRecursively()
            }
        }

    private suspend fun enqueuePendingAttachment(
        room: Room,
        record: PendingMediaRecord,
        staged: StagedAttachment,
        timeline: Timeline,
        replyToEventId: String? = null,
        onQueueAttempt: () -> Unit = {},
    ) {
        val uploadBytes = staged.file.readBytes()
        try {
            val parameters = UploadParameters(
                source = UploadSource.Data(uploadBytes, record.fileName),
                caption = null,
                formattedCaption = null,
                mentions = null,
                inReplyTo = replyToEventId,
                // An encrypted custom event-content field makes remote echoes exactly
                // correlatable after a crash. Typed message rendering ignores unknown fields.
                extraContentJson = pendingMediaContentExtra(record),
            )
            when {
                record.mimeType.startsWith("image/") -> {
                    val dimensions = BitmapFactory.Options().also { options ->
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(staged.file.absolutePath, options)
                    }
                    val info = ImageInfo(
                        height = dimensions.outHeight.takeIf { it > 0 }?.toULong(),
                        width = dimensions.outWidth.takeIf { it > 0 }?.toULong(),
                        mimetype = record.mimeType,
                        size = uploadBytes.size.toULong(),
                        thumbnailInfo = null,
                        thumbnailSource = null,
                        blurhash = null,
                        isAnimated = record.mimeType == "image/gif",
                    )
                    try {
                        val send = withSendQueueGate {
                            onQueueAttempt()
                            withDeliverySnapshotReservation(record.roomId, room) {
                                trackAttachmentSend(timeline.sendImage(parameters, null, info))
                            }
                        }
                        withContext(NonCancellable) { send.result.await() }
                    } finally {
                        info.destroy()
                    }
                }
                record.mimeType.startsWith("video/") -> {
                    val info = VideoInfo(
                        duration = null,
                        height = null,
                        width = null,
                        mimetype = record.mimeType,
                        size = uploadBytes.size.toULong(),
                        thumbnailInfo = null,
                        thumbnailSource = null,
                        blurhash = null,
                    )
                    try {
                        val send = withSendQueueGate {
                            onQueueAttempt()
                            withDeliverySnapshotReservation(record.roomId, room) {
                                trackAttachmentSend(timeline.sendVideo(parameters, null, info))
                            }
                        }
                        withContext(NonCancellable) { send.result.await() }
                    } finally {
                        info.destroy()
                    }
                }
                record.mimeType.startsWith("audio/") -> {
                    val info = AudioInfo(
                        duration = record.audioDurationMillis?.takeIf { it > 0 }?.let(Duration::ofMillis),
                        size = uploadBytes.size.toULong(),
                        mimetype = record.mimeType,
                    )
                    val send = withSendQueueGate {
                        onQueueAttempt()
                        withDeliverySnapshotReservation(record.roomId, room) {
                            trackAttachmentSend(timeline.sendAudio(parameters, info))
                        }
                    }
                    withContext(NonCancellable) { send.result.await() }
                }
                else -> {
                    val info = FileInfo(
                        mimetype = record.mimeType,
                        size = uploadBytes.size.toULong(),
                        thumbnailInfo = null,
                        thumbnailSource = null,
                    )
                    try {
                        val send = withSendQueueGate {
                            onQueueAttempt()
                            withDeliverySnapshotReservation(record.roomId, room) {
                                trackAttachmentSend(timeline.sendFile(parameters, info))
                            }
                        }
                        withContext(NonCancellable) { send.result.await() }
                    } finally {
                        info.destroy()
                    }
                }
            }
        } finally {
            uploadBytes.fill(0)
        }
    }

    suspend fun loadAttachmentForViewing(message: ChatMessage): File = withContext(Dispatchers.IO) {
        val attachment = checkNotNull(message.attachment) { "This message has no attachment" }
        check(advertisedMediaSizeIsAllowed(attachment.sizeBytes, MAX_MEDIA_BYTES)) {
            "This attachment has a missing or unsupported advertised size"
        }
        val client = requireClient()
        val mediaSource = MediaSource.fromJson(attachment.sourceJson)
        var mediaFile: MediaFileHandle? = null
        try {
            mediaTransferDir.mkdirs()
            mediaFile = client.getMediaFile(
                mediaSource,
                attachment.fileName,
                attachment.mimeType,
                false,
                mediaTransferDir.absolutePath,
            )
            val decryptedFile = File(mediaFile.path())
            check(decryptedFile.isFile && decryptedFile.length() <= MAX_MEDIA_BYTES) {
                "This attachment is larger than the supported download limit"
            }
            val safeName = safeFileName(attachment.fileName)
            val viewerFile = File(mediaTransferDir, "view-${UUID.randomUUID()}-$safeName")
            var copyComplete = false
            try {
                decryptedFile.inputStream().use { input ->
                    FileOutputStream(viewerFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                viewerFile.setReadable(true, true)
                externalViewerFiles.add(viewerFile)
                callbackScope.launch {
                    delay(TEMP_MEDIA_FILE_TTL_MS)
                    viewerFile.delete()
                    externalViewerFiles.remove(viewerFile)
                }
                copyComplete = true
                viewerFile
            } finally {
                if (!copyComplete) {
                    externalViewerFiles.remove(viewerFile)
                    viewerFile.delete()
                }
            }
        } finally {
            mediaFile?.destroy()
            mediaSource.destroy()
        }
    }

    /** Create a private cache file for an in-app voice-note recording. */
    fun createVoiceNoteRecordingFile(): File {
        val voiceDirectory = File(mediaTransferDir, "voice-drafts").apply { mkdirs() }
        return File(voiceDirectory, "voice-${UUID.randomUUID()}.m4a")
    }

    /** Decrypt audio into the private transfer cache so it can be played without another app. */
    suspend fun loadAudioForPlayback(message: ChatMessage): File = withContext(Dispatchers.IO) {
        val attachment = checkNotNull(message.attachment) { "This message has no audio attachment" }
        require(attachment.kind == AttachmentKind.AUDIO || attachment.mimeType.startsWith("audio/")) {
            "This message is not an audio attachment"
        }
        check(advertisedMediaSizeIsAllowed(attachment.sizeBytes, MAX_MEDIA_BYTES)) {
            "This audio message has a missing or unsupported advertised size"
        }
        val client = requireClient()
        val mediaSource = MediaSource.fromJson(attachment.sourceJson)
        var mediaFile: MediaFileHandle? = null
        try {
            mediaTransferDir.mkdirs()
            mediaFile = client.getMediaFile(
                mediaSource,
                attachment.fileName,
                attachment.mimeType,
                false,
                mediaTransferDir.absolutePath,
            )
            val decryptedFile = File(mediaFile.path())
            check(decryptedFile.isFile && decryptedFile.length() <= MAX_MEDIA_BYTES) {
                "This audio message is larger than the supported download limit"
            }
            val safeName = safeFileName(attachment.fileName)
            val playbackFile = File(mediaTransferDir, "play-${UUID.randomUUID()}-$safeName")
            var copyComplete = false
            try {
                decryptedFile.inputStream().use { input ->
                    FileOutputStream(playbackFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                callbackScope.launch {
                    delay(TEMP_MEDIA_FILE_TTL_MS)
                    playbackFile.delete()
                }
                copyComplete = true
                playbackFile
            } finally {
                if (!copyComplete) playbackFile.delete()
            }
        } finally {
            mediaFile?.destroy()
            mediaSource.destroy()
        }
    }

    /** Delete one of this repository's temporary plaintext media files. */
    fun deleteTemporaryMediaFile(file: File) {
        runCatching {
            val root = mediaTransferDir.canonicalFile
            val candidate = file.canonicalFile
            if (candidate.path.startsWith(root.path + File.separator)) candidate.delete()
        }
    }

    /** Remove decrypted copies after returning from another app that opened one. */
    fun cleanupExternalViewerFiles() {
        externalViewerFiles.toList().forEach { file ->
            file.delete()
            externalViewerFiles.remove(file)
        }
    }

    private fun cleanupPlaintextMediaCaches() {
        synchronized(mediaCleanupLock) {
            check(mediaTransferRoot.deleteRecursively()) {
                "Temporary media could not be removed from this device"
            }
            mediaTransferRoot.mkdirs()
            mediaTransferDir.mkdirs()
        }
        check(mediaOutboxCacheRoot.deleteRecursively()) {
            "A legacy attachment recovery file could not be removed from this device"
        }
        mediaOutboxCacheRoot.mkdirs()
    }

    suspend fun retryFailedMessages(roomId: String) = withContext(Dispatchers.IO) {
        val room = requireRoom(roomId)
        check(room.encryptionState().name == "ENCRYPTED") {
            "Retry is disabled because this conversation is not encrypted"
        }
        withSendQueueGate {
            check(sendQueuesEnabled == true && syncServiceRunning && _connection.value == "Connected") {
                "Reconnect before retrying this message"
            }
            room.enableSendQueue(true)
        }
    }

    suspend fun sendTypingNotice(roomId: String, isTyping: Boolean) = withContext(Dispatchers.IO) {
        val room = requireRoom(roomId)
        if (room.encryptionState().name == "ENCRYPTED") withSendQueueGate { room.typingNotice(isTyping) }
    }

    /** Send a Matrix read receipt only after Compose reports this remote message as visible. */
    suspend fun markMessageAsRead(roomId: String, eventId: String, timestampMillis: Long) =
        withContext(Dispatchers.IO) {
            if (!_readReceiptsEnabled.value || logoutInProgress) return@withContext
            val room = requireRoom(roomId)
            check(room.encryptionState().name == "ENCRYPTED") {
                "Read receipts are disabled for unencrypted conversations"
            }
            val roomMutex = readReceiptLocks.computeIfAbsent(roomId) { Mutex() }
            roomMutex.withLock {
                val previousTimestamp = lastVisibleReadReceiptTimestamps[roomId] ?: Long.MIN_VALUE
                if (timestampMillis < previousTimestamp ||
                    (timestampMillis == previousTimestamp && lastVisibleReadReceiptEventIds[roomId] == eventId)
                ) {
                    return@withLock
                }
                val timeline = activeTimeline?.takeIf { activeRoomId == roomId } ?: room.timeline()
                val ownsTimeline = timeline !== activeTimeline
                try {
                    withSendQueueGate { timeline.sendReadReceipt(ReceiptType.READ, eventId) }
                    lastVisibleReadReceiptTimestamps[roomId] = timestampMillis
                    lastVisibleReadReceiptEventIds[roomId] = eventId
                } finally {
                    if (ownsTimeline) timeline.close()
                }
            }
        }

    suspend fun toggleReaction(roomId: String, message: ChatMessage, key: String) = withContext(Dispatchers.IO) {
        val room = requireRoom(roomId)
        check(room.encryptionState().name == "ENCRYPTED") {
            "Reactions are disabled because this conversation is not encrypted"
        }
        val timeline = activeTimeline?.takeIf { activeRoomId == roomId } ?: room.timeline()
        val itemId = if (message.isRemote) {
            EventOrTransactionId.EventId(message.eventId ?: message.id)
        } else {
            EventOrTransactionId.TransactionId(message.id)
        }
        try {
            withSendQueueGate { timeline.toggleReaction(itemId, key) }
        } finally {
            if (activeTimeline !== timeline) timeline.close()
        }
    }

    suspend fun editMessage(roomId: String, message: ChatMessage, newBody: String) = withContext(Dispatchers.IO) {
        val body = newBody.trim()
        check(body.isNotEmpty()) { "An edited message cannot be empty" }
        check(body != message.body) { "The edited message is unchanged" }
        check(message.isOwn && message.isRemote && message.canEdit && message.eventId != null) {
            "Only your sent text messages can be edited"
        }
        val room = requireRoom(roomId)
        check(room.encryptionState().name == "ENCRYPTED") {
            "Editing is disabled because this conversation is not encrypted"
        }
        val timeline = activeTimeline?.takeIf { activeRoomId == roomId } ?: room.timeline()
        try {
            val content = timeline.createMessageContent(
                MessageType.Text(TextMessageContent(body, null)),
            ) ?: throw IllegalStateException("This edit could not be prepared")
            val editedContent = EditedContent.RoomMessage(content)
            try {
                withSendQueueGate {
                    timeline.edit(EventOrTransactionId.EventId(message.eventId), editedContent)
                }
            } finally {
                editedContent.destroy()
            }
        } finally {
            if (activeTimeline !== timeline) timeline.close()
        }
    }

    suspend fun redactMessage(roomId: String, message: ChatMessage) = withContext(Dispatchers.IO) {
        check(message.isOwn && message.isRemote && message.canRedact && message.eventId != null) {
            "Only your sent messages can be removed"
        }
        val room = requireRoom(roomId)
        check(room.encryptionState().name == "ENCRYPTED") {
            "Removing messages is disabled because this conversation is not encrypted"
        }
        val timeline = activeTimeline?.takeIf { activeRoomId == roomId } ?: room.timeline()
        try {
            withSendQueueGate {
                timeline.redactEvent(EventOrTransactionId.EventId(message.eventId), null)
            }
        } finally {
            if (activeTimeline !== timeline) timeline.close()
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        withLifecycleLock {
            logoutInProgress = true
            stopReadReceiptRefresh()
            cancelPendingDataMediaReplay(resetRetryBudget = true)
            pendingMediaReadyRoomIds.clear()
            pushTokenObserver?.cancel()
            pushTokenObserver = null
            val matrixClient = client
            val service = syncService
            val syncServiceWasRunning = syncServiceRunning
            val sendQueuesWereEnabled = sendQueuesEnabled
            var stopAttempted = false
            try {
                val pushWasEnabled = _pushNotificationsEnabled.value
                val hasPusherRecord = runCatching { vault.hasPushPusherRecord() }.getOrDefault(true)
                val removalWasPending = runCatching { vault.loadPushRemovalPending() }.getOrDefault(true)
                val needsPushRemoval = pushWasEnabled || hasPusherRecord || removalWasPending
                if (needsPushRemoval) {
                    synchronized(vault) { vault.savePushOptOutWithRemovalPending() }
                    _pushNotificationsEnabled.value = false
                }
                val stagedPushRemoval = persistPushRemovalIntent(
                    matrixClient,
                    createMissing = needsPushRemoval,
                    operation = PushPusherOperation.REMOVE_PENDING,
                )
                if (stagedPushRemoval != PushRegistrationStatus.REMOVED) {
                    _pushRegistrationStatus.value = PushRegistrationStatus.REMOVAL_PENDING
                    throw IOException("Push registration removal could not be saved; keep this session available to retry it")
                }
                MatrixPushClient.clearDisplayedNotifications(appContext)
                val pushRemoval = removePushPusherForSession(
                    matrixClient,
                    createMissing = needsPushRemoval,
                )
                if (pushRemoval != PushRegistrationStatus.REMOVED) {
                    _pushRegistrationStatus.value = PushRegistrationStatus.REMOVAL_PENDING
                    throw IOException("Push registration removal is pending; keep this session available to retry it")
                }
                _pushRegistrationStatus.value = disabledPushStatus()

                if (matrixClient != null) {
                    // This barrier waits for any in-progress enqueue to register its native
                    // handle. logoutInProgress rejects every enqueue that reaches the gate
                    // after this point.
                    sendQueueMutex.lock()
                    sendQueueMutex.unlock()
                }
                // Keep the existing send queues and sync service alive while uploads drain;
                // stopping either can strand an SDK task waiting for its queue completion.
                // A timeout aborts logout before the vault key or durable recovery files are
                // removed. The repository-owned joiners outlive the cancelled UI send job.
                awaitActiveAttachmentSends()
                if (matrixClient != null) {
                    sendQueueMutex.lock()
                    try {
                        sendQueuesEnabled = false
                        matrixClient.enableAllSendQueues(false)
                    } finally {
                        sendQueueMutex.unlock()
                    }
                }
                if (service != null) {
                    stopAttempted = true
                    service.stop()
                    syncServiceRunning = false
                }
                // Drain observers that can persist encrypted ACK/media journals before
                // deleting the wrapping key. The logout gate keeps new mutations out.
                synchronized(deliveryAckLock) { Unit }
                synchronized(pendingMediaLock) { Unit }
                // Remove app-owned plaintext media before destroying the key needed for the
                // encrypted outbox archive. If deletion fails, abort sign-out and retain the
                // recoverable session.
                check(mediaTransferRoot.deleteRecursively()) {
                    "Temporary media could not be removed; sign-out was not completed"
                }
                check(mediaOutboxCacheRoot.deleteRecursively()) {
                    "A legacy attachment recovery file could not be removed; sign-out was not completed"
                }
                synchronized(vault) { vault.clearSession() }
            } catch (error: Throwable) {
                logoutInProgress = false
                if (stopAttempted && service != null) {
                    runCatching {
                        // Recreate any legacy file-backed queue sources from the encrypted
                        // archive before resuming workers after an aborted sign-out.
                        restorePendingMediaSources()
                        service.start()
                        syncServiceRunning = syncServiceWasRunning
                        val restoreQueues = syncServiceWasRunning && (sendQueuesWereEnabled ?: true)
                        if (matrixClient != null) {
                            matrixClient.enableAllSendQueues(restoreQueues)
                            sendQueuesEnabled = restoreQueues
                        }
                    }.onFailure(error::addSuppressed)
                }
                matrixClient?.let(::refreshSendQueueGate)
                schedulePushRegistration()
                throw error
            }

            _pushNotificationsEnabled.value = false
            _pushRegistrationStatus.value = if (MatrixPushClient.isConfigured) {
                PushRegistrationStatus.NOT_ENABLED
            } else {
                PushRegistrationStatus.DISABLED
            }

            // Queue disabling does not abort a native upload that is already in flight.
            try {
                closeSearchService()
                runCatching { client?.logout() }
                verificationController?.setDelegate(null)
                verificationController?.close()
                verificationController = null
                pendingVerificationRequest = null
                verificationWasInitiatedHere = false
                _verification.value = null
                stopReadReceiptRefresh()
                activeRoomId = null
                val timeline = activeTimeline
                activeTimeline = null
                syncStateHandle?.cancel()
                syncStateHandle?.close()
                sendQueueStatusHandle?.cancel()
                sendQueueStatusHandle?.close()
                closeSendQueueUpdates()
                timelineListenerHandle?.cancel()
                timelineListenerHandle?.close()
                typingListenerHandle?.cancel()
                typingListenerHandle?.close()
                timeline?.close()
                closeDeliveryAckObservers()
                syncService?.close()
                client?.close()
                syncStateHandle = null
                sendQueueStatusHandle = null
                timelineListenerHandle = null
                syncService = null
                syncServiceRunning = false
                client = null
                ownUserId = null
                _peerTrust.value = PeerTrustStatus.UNKNOWN
                _typingUsers.value = emptyList()
                expectedDeliveryMemberIdsByRoom.clear()
                synchronized(deliveryAckLock) {
                    deliveryAckJournal.clear()
                    pendingDeliverySnapshotsByTransaction.clear()
                    deliveryAckJournalLoaded = false
                }
                synchronized(pendingMediaLock) { pendingMediaRecords.clear(); pendingMediaJournalLoaded = false }
                pendingMediaSendHandles.values.forEach { it.close() }
                pendingMediaSendHandles.clear()
                closePendingMediaObservers()
                failedAckSendHandles.values.forEach { it.close() }
                failedAckSendHandles.clear()
                ackReconciliationScheduled.clear()
                ackRetryScheduled.clear()
                observedAckTargetsByRoom.clear()
                verificationPeerPrewarmScheduled.clear()
                readReceiptLocks.clear()
                lastVisibleReadReceiptTimestamps.clear()
                lastVisibleReadReceiptEventIds.clear()
                _conversations.value = emptyList()
                clearTimelineMessages()
                _composerDraft.value = ""
                _connection.value = "Offline"

                matrixRoot.deleteRecursively()
                mediaOutboxRoot.deleteRecursively()
                mediaOutboxCacheRoot.deleteRecursively()
                mediaTransferDir.deleteRecursively()
                cleanupExternalViewerFiles()
            } finally {
                logoutInProgress = false
            }
        }
    }

    /** Stop background Matrix work without signing out or deleting the encrypted local store. */
    suspend fun close() = withContext(Dispatchers.IO) {
        withLifecycleLock {
        syncServiceRunning = false
        stopReadReceiptRefresh()
        cancelPendingDataMediaReplay(resetRetryBudget = true)
        pendingMediaReadyRoomIds.clear()
        pushTokenObserver?.cancel()
        pushTokenObserver = null
        cleanupExternalViewerFiles()
        closeSearchService()
        verificationController?.setDelegate(null)
        verificationController?.close()
        verificationController = null
        pendingVerificationRequest = null
        verificationWasInitiatedHere = false
        _verification.value = null
        syncStateHandle?.cancel()
        syncStateHandle?.close()
        sendQueueStatusHandle?.cancel()
        sendQueueStatusHandle?.close()
        closeSendQueueUpdates()
        syncService?.stop()
        activeRoomId = null
        val timeline = activeTimeline
        activeTimeline = null
        timelineListenerHandle?.cancel()
        timelineListenerHandle?.close()
        typingListenerHandle?.cancel()
        typingListenerHandle?.close()
        timeline?.close()
        closeDeliveryAckObservers()
        closePendingMediaObservers()
        pendingMediaSendHandles.values.forEach { it.close() }
        pendingMediaSendHandles.clear()
        failedAckSendHandles.values.forEach { it.close() }
        failedAckSendHandles.clear()
        syncService?.close()
        client?.close()
        syncStateHandle = null
        sendQueueStatusHandle = null
        timelineListenerHandle = null
        typingListenerHandle = null
        syncService = null
        client = null
        ownUserId = null
        mediaTransferDir.deleteRecursively()
        _typingUsers.value = emptyList()
        clearTimelineMessages()
        _connection.value = "Offline"
        _peerTrust.value = PeerTrustStatus.UNKNOWN
        syncServiceRunning = false
        }
    }

    private suspend fun buildClient(homeserverUrl: String): Client {
        val normalizedHomeserverUrl = validateHomeserverUrl(homeserverUrl)
        val cacheDirectory = File(matrixRoot, "cache").apply { mkdirs() }
        val dataPath = File(matrixRoot, "matrix.db").absolutePath
        val cachePath = File(cacheDirectory, "matrix-cache.db").absolutePath
        val store = SqliteStoreBuilder(dataPath, cachePath).key(vault.loadOrCreateStoreKey())
        return ClientBuilder()
            .homeserverUrl(normalizedHomeserverUrl)
            .disableWellKnownLookup(true)
            .enableAutomaticBackPagination(true)
            .autoEnableCrossSigning(true)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .roomKeyRecipientStrategy(CollectStrategy.ONLY_TRUSTED_DEVICES)
            .withSearchIndexStore(
                File(matrixRoot, "search-index").absolutePath,
                vault.loadOrCreateStoreKey().joinToString("") { byte -> "%02x".format(byte) },
            )
            .sqliteStore(store)
            .build()
    }

    private fun validateHomeserverUrl(value: String): String {
        return HomeserverUrlPolicy.normalize(value, allowPrivateHttp = BuildConfig.DEBUG)
    }

    private suspend fun startSync(matrixClient: Client) {
        var stage = "install-udt-diagnostic-listener"
        try {
            if (BuildConfig.DEBUG) {
                matrixClient.setUtdDelegate(object : UnableToDecryptDelegate {
                    override fun onUtd(info: UnableToDecryptInfo) {
                        recordDiagnosticUtdCause(info.eventId, info.cause.name)
                    }
                })
            }
            stage = "disable-send-queues"
            // The send-queue subscription below respawns persisted tasks immediately. Keep it
            // disabled until durable attachment paths have been restored into private cache.
            matrixClient.enableAllSendQueues(false)
            stage = "restore-pending-media"
            restorePendingMediaSources()
            sendQueuesEnabled = null
            sendQueueStatusHandle?.cancel()
            sendQueueStatusHandle?.close()
            // This subscription respawns send tasks persisted before process death.
            stage = "subscribe-send-queue-status"
            sendQueueStatusHandle = matrixClient.subscribeToSendQueueStatus(object : SendQueueRoomErrorListener {
                override fun onError(roomId: String, error: org.matrix.rustcomponents.sdk.ClientException) = Unit
            })
            stage = "subscribe-send-queue-updates"
            loadDeliveryAckJournal()
            closeSendQueueUpdates()
            val updateListener = object : SendQueueRoomUpdateListener {
                override fun onUpdate(roomId: String, update: RoomSendQueueUpdate) {
                    observeSendQueueUpdate(roomId, update)
                }
            }
            sendQueueUpdatesListener = updateListener
            // This supplies exact transaction-to-event promotion for durable recipient
            // snapshots. If the SDK subscription fails, messaging remains available but
            // delivery acknowledgements stay fail-closed because no snapshot is trusted.
            sendQueueUpdatesHandle = runCatching {
                matrixClient.subscribeToSendQueueUpdates(updateListener)
            }.getOrNull()
            // Subscribing restores the SDK-owned queue entries. Reconcile their local echoes
            // before the sync-state callback is allowed to enable the workers and before the
            // app considers replaying a durable DATA archive.
            stage = "reconcile-pending-media-queue"
            ensurePendingMediaObserversForKnownRooms()
            stage = "build-sync-service"
            val service = matrixClient.syncService().finish()
            syncService = service
            syncServiceRunning = false
            stage = "subscribe-sync-state"
            syncStateHandle = service.state(object : SyncServiceStateObserver {
                override fun onUpdate(state: org.matrix.rustcomponents.sdk.SyncServiceState) {
                    val wasRunning = syncServiceRunning
                    syncServiceRunning = state == org.matrix.rustcomponents.sdk.SyncServiceState.RUNNING
                    refreshSendQueueGate(matrixClient)
                    _connection.value = when {
                        state == org.matrix.rustcomponents.sdk.SyncServiceState.RUNNING -> "Connected"
                        state == org.matrix.rustcomponents.sdk.SyncServiceState.ERROR ||
                            state == org.matrix.rustcomponents.sdk.SyncServiceState.TERMINATED ||
                            state == org.matrix.rustcomponents.sdk.SyncServiceState.OFFLINE -> "Reconnecting"
                        else -> "Syncing"
                    }
                    if (syncServiceRunning && !logoutInProgress) {
                        retryRecoverableDeliveryAcks()
                        if (!wasRunning) {
                            requestPendingDataMediaReplay(resetRetryBudget = true)
                            // The verification controller requires a cross-signing identity.
                            // Let first sync populate it; verification setup must not block
                            // regular encrypted messaging when that identity is not ready.
                            callbackScope.launch {
                                withLifecycleLock {
                                    if (client === matrixClient && !logoutInProgress) {
                                        runCatching { getVerificationController() }
                                    }
                                }
                            }
                        }
                    }
                }
            })
            _connection.value = "Syncing"
            stage = "start-sync-service"
            service.start()
            stage = "refresh-conversations"
            refreshConversations()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw MatrixSyncStartFailure(stage, failure)
        }
    }

    private fun applyTimelineUpdate(roomId: String, update: TimelineDiff, acknowledgements: MutableList<String>) {
        synchronized(timelineMessagesLock) {
            when (update) {
                is TimelineDiff.Append -> timelineMessages.addAll(update.values.map { messageFrom(it, roomId, acknowledgements) })
                TimelineDiff.Clear -> timelineMessages.clear()
                is TimelineDiff.PushFront -> timelineMessages.add(0, messageFrom(update.value, roomId, acknowledgements))
                is TimelineDiff.PushBack -> timelineMessages.add(messageFrom(update.value, roomId, acknowledgements))
                TimelineDiff.PopFront -> if (timelineMessages.isNotEmpty()) timelineMessages.removeAt(0)
                TimelineDiff.PopBack -> if (timelineMessages.isNotEmpty()) timelineMessages.removeAt(timelineMessages.lastIndex)
                is TimelineDiff.Insert -> {
                    val index = update.index.toInt().coerceIn(0, timelineMessages.size)
                    timelineMessages.add(index, messageFrom(update.value, roomId, acknowledgements))
                }
                is TimelineDiff.Set -> {
                    val index = update.index.toInt()
                    if (index in timelineMessages.indices) {
                        timelineMessages[index] = messageFrom(update.value, roomId, acknowledgements)
                    }
                }
                is TimelineDiff.Remove -> {
                    val index = update.index.toInt()
                    if (index in timelineMessages.indices) timelineMessages.removeAt(index)
                }
                is TimelineDiff.Truncate -> {
                    val length = update.length.toInt().coerceIn(0, timelineMessages.size)
                    while (timelineMessages.size > length) timelineMessages.removeAt(timelineMessages.lastIndex)
                }
                is TimelineDiff.Reset -> {
                    timelineMessages.clear()
                    timelineMessages.addAll(update.values.map { messageFrom(it, roomId, acknowledgements) })
                }
            }
            publishTimelineMessagesLocked()
        }
    }

    private fun startReadReceiptRefresh(roomId: String, timeline: Timeline) {
        stopReadReceiptRefresh()
        readReceiptRefreshJob = callbackScope.launch {
            while (!logoutInProgress && activeRoomId == roomId && activeTimeline === timeline) {
                delay(1_000)
                if (logoutInProgress || activeRoomId != roomId || activeTimeline !== timeline) break
                try {
                    refreshReadReceiptProjection(roomId, timeline)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Receipt reads are a local-store projection repair. A transient store
                    // error must not interrupt sync or the conversation timeline.
                }
            }
        }
    }

    private fun stopReadReceiptRefresh() {
        readReceiptRefreshJob?.cancel()
        readReceiptRefreshJob = null
    }

    private suspend fun refreshReadReceiptProjection(roomId: String, timeline: Timeline) {
        val pendingOwnEventIds = synchronized(timelineMessagesLock) {
            if (activeRoomId != roomId || activeTimeline !== timeline || logoutInProgress) return
            timelineMessages.asSequence()
                .filterNotNull()
                .filter { it.isOwn && !it.hasBeenRead }
                .mapNotNull(ChatMessage::eventId)
                .toSet()
        }
        if (pendingOwnEventIds.isEmpty()) return

        val peerUserIds = expectedDeliveryMemberIdsByRoom[roomId].orEmpty()
        if (peerUserIds.isEmpty()) return

        val room = requireRoom(roomId)
        val receiptEventIds = try {
            peerUserIds.mapNotNull { peerUserId ->
                room.loadUserReceipt(ReceiptType.READ, ReceiptThread.Unthreaded, peerUserId)?.eventId
            }.toSet()
        } finally {
            room.close()
        }
        if (receiptEventIds.isEmpty()) return

        val changedEventIds = mutableListOf<String>()
        synchronized(timelineMessagesLock) {
            if (activeRoomId != roomId || activeTimeline !== timeline || logoutInProgress) return
            val latestReceiptIndex = timelineMessages.indices
                .filter { index -> timelineMessages[index]?.eventId?.let(receiptEventIds::contains) == true }
                .maxOrNull()
                ?: return
            for (index in 0..latestReceiptIndex) {
                val message = timelineMessages[index] ?: continue
                val eventId = message.eventId ?: continue
                if (message.isOwn && !message.hasBeenRead) {
                    timelineMessages[index] = message.copy(hasBeenRead = true)
                    changedEventIds += eventId
                }
            }
            if (changedEventIds.isNotEmpty()) publishTimelineMessagesLocked()
        }
        changedEventIds.forEach(::recordReadReceiptProjectionDiagnostic)
    }

    private fun recordReadReceiptProjectionDiagnostic(eventId: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(readReceiptDiagnosticLock) {
            if (readReceiptDiagnosticEventId != eventId) return
            readReceiptDiagnosticSnapshot = readReceiptDiagnosticSnapshot.copy(appModelReadState = true)
        }
    }

    private fun clearTimelineMessages() {
        synchronized(timelineMessagesLock) {
            timelineMessages.clear()
            _messages.value = emptyList()
        }
    }

    private fun publishTimelineMessagesLocked() {
        val current = timelineMessages.filterNotNull()
        val latestById = current.associateBy(ChatMessage::id)
        val seen = HashSet<String>(current.size)
        _messages.value = current.mapNotNull { message ->
            if (seen.add(message.id)) latestById[message.id] else null
        }.takeLast(300)
    }

    private fun messageFrom(item: TimelineItem, roomId: String, acknowledgements: MutableList<String>): ChatMessage? {
        val event = item.asEvent() ?: return null
        return try {
            val msgLike = (event.content as? TimelineItemContent.MsgLike)?.content
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
            if (BuildConfig.DEBUG && event.isRemote && eventId != null) {
                val itemKind = when (val content = event.content) {
                    is TimelineItemContent.MsgLike -> when (val kind = content.content.kind) {
                        is MsgLikeKind.Message -> if (kind.content.msgType is MessageType.Other) {
                            val messageType = kind.content.msgType as MessageType.Other
                            if (messageType.msgtype == DELIVERY_ACK_MSGTYPE) "ACK" else "OTHER_MESSAGE"
                        } else {
                            "MESSAGE"
                        }
                        is MsgLikeKind.UnableToDecrypt -> "UTD"
                        MsgLikeKind.Redacted -> "REDACTED"
                        else -> "OTHER_MESSAGE_KIND"
                    }
                    TimelineItemContent.CallInvite -> "CALL_INVITE"
                    is TimelineItemContent.RtcNotification -> "RTC_NOTIFICATION"
                    is TimelineItemContent.RoomMembership -> "ROOM_MEMBERSHIP"
                    is TimelineItemContent.ProfileChange -> "PROFILE_CHANGE"
                    is TimelineItemContent.State -> "STATE"
                    is TimelineItemContent.FailedToParseMessageLike -> "FAILED_MESSAGE"
                    is TimelineItemContent.FailedToParseState -> "FAILED_STATE"
                }
                val direction = if (event.isOwn) "OWN_REMOTE" else "REMOTE"
                recordDiagnosticTimelineCategory(roomId, eventId, "${direction}_$itemKind")
            }
            if (BuildConfig.DEBUG && !event.isOwn && event.isRemote) {
                val unableToDecrypt = msgLike?.kind as? MsgLikeKind.UnableToDecrypt
                if (unableToDecrypt != null) {
                    val cause = when (val encrypted = unableToDecrypt.msg) {
                        is EncryptedMessage.MegolmV1AesSha2 -> encrypted.cause.name
                        is EncryptedMessage.OlmV1Curve25519AesSha2 -> "OLM_ENCRYPTED"
                        EncryptedMessage.Unknown -> "UNKNOWN_ENCRYPTED"
                    }
                    if (eventId != null) recordDiagnosticUtdCause(eventId, cause)
                }
            }
            val messageContent = (msgLike?.kind as? MsgLikeKind.Message)?.content
            val customMessage = messageContent?.msgType as? MessageType.Other
            if (customMessage?.msgtype == CALL_MESSAGE_TYPE) {
                val senderId = event.sender
                val incomingEventId = eventId
                if (!event.isOwn && event.isRemote && incomingEventId != null &&
                    activeRoomId == roomId && _peerTrust.value == PeerTrustStatus.VERIFIED &&
                    expectedDeliveryMemberIdsByRoom[roomId]?.contains(senderId) == true
                ) {
                    val rawEvent = runCatching { event.lazyProvider.debugInfo().originalJson }.getOrNull()
                    if (rawEvent != null) {
                        CallProtocol.parse(
                            roomId = roomId,
                            senderId = senderId,
                            eventId = incomingEventId,
                            originalEventJson = rawEvent,
                        )?.let(_callSignals::tryEmit)
                    }
                }
                return null
            }
            val acknowledgedId = messageContent?.msgType?.deliveryAckTarget()
            if (acknowledgedId != null) {
                if (event.isOwn) {
                    recordOwnDeliveryAck(roomId, acknowledgedId, event.localSendState)
                    observedAckTargetsByRoom.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }
                        .add(acknowledgedId)
                    if ((event.localSendState as? EventSendState.SendingFailed)?.isRecoverable == true) {
                        rememberRecoverableAckSend(roomId, acknowledgedId, event)
                    }
                } else if (event.isRemote) {
                    recordRemoteAckMessageObserved(roomId)
                    recordReceivedDeliveryAck(roomId, acknowledgedId, event.sender)
                }
                refreshDeliveryStates()
                return null
            }
            val attachment = messageContent?.msgType?.chatAttachment()
            val body = if (attachment != null) {
                messageContent.msgType.attachmentCaption().orEmpty()
            } else {
                event.content.readableBody() ?: return null
            }
            if (!event.isOwn && event.isRemote && eventId != null &&
                messageContent?.msgType?.isDeliveryAckEligible() == true
            ) {
                if (recordIncomingEventForAck(roomId, eventId)) acknowledgements += eventId
            }
            val sendState = event.localSendState
            val state = when (sendState) {
                is EventSendState.NotSentYet -> if (_connection.value == "Connected") "Sending" else "Queued"
                is EventSendState.SendingFailed -> if (sendState.isRecoverable) "Retry needed" else "Not sent"
                is EventSendState.Sent -> if (eventId != null) deliveryStateFor(roomId, eventId) else "Sent"
                null -> if (eventId != null) deliveryStateFor(roomId, eventId) else "Sent"
            }
            val replyTo = msgLike?.inReplyTo?.eventId()
            val reactions = msgLike?.reactions.orEmpty().map { reaction ->
                ReactionSummary(
                    key = reaction.key,
                    count = reaction.senders.size,
                    sentByMe = reaction.senders.any { sender -> sender.senderId == ownUserId },
                )
            }
            val hasBeenRead = event.isOwn && event.readReceipts.keys.any { it != ownUserId }
            recordReadReceiptDiagnostic(eventId, event.isOwn, event.readReceipts.keys.any { it != ownUserId }, hasBeenRead)
            ChatMessage(
                id = eventId ?: (event.eventOrTransactionId as? EventOrTransactionId.TransactionId)?.transactionId.orEmpty(),
                eventId = eventId,
                isRemote = event.isRemote,
                sender = event.sender,
                body = body,
                timestampMillis = event.timestamp.toLong(),
                isOwn = event.isOwn,
                deliveryState = state,
                canRetry = (sendState as? EventSendState.SendingFailed)?.isRecoverable == true,
                canReply = event.canBeRepliedTo && eventId != null,
                canEdit = event.isOwn && event.isRemote && event.isEditable &&
                    messageContent?.msgType is MessageType.Text,
                canRedact = event.isOwn && event.isRemote && eventId != null && messageContent != null,
                replyToEventId = replyTo,
                reactions = reactions,
                hasBeenRead = hasBeenRead,
                attachment = attachment,
            )
        } finally {
            event.destroy()
        }
    }

    private fun recordReadReceiptDiagnostic(
        eventId: String?,
        isOwnEvent: Boolean,
        hasOtherReader: Boolean,
        mappedReadState: Boolean,
    ) {
        if (!BuildConfig.DEBUG || eventId == null) return
        synchronized(readReceiptDiagnosticLock) {
            if (readReceiptDiagnosticEventId != eventId) return
            readReceiptDiagnosticSnapshot = ReadReceiptDiagnosticSnapshot(
                eventSeen = true,
                isOwnEvent = isOwnEvent,
                hasOtherReader = hasOtherReader,
                mappedReadState = mappedReadState,
                appModelReadState = mappedReadState,
                updateCount = (readReceiptDiagnosticSnapshot.updateCount + 1).coerceAtMost(999),
            )
        }
    }

    private fun MessageType.isDeliveryAckEligible(): Boolean = when (this) {
        is MessageType.Text,
        is MessageType.Notice,
        is MessageType.Emote,
        is MessageType.Image,
        is MessageType.Video,
        is MessageType.Audio,
        is MessageType.File,
        -> true
        else -> false
    }

    private suspend fun ensureDeliveryAckObserver(room: Room) {
        val roomId = room.id()
        if (deliveryAckObservers.containsKey(roomId)) {
            room.close()
            return
        }
        if (runCatching { room.encryptionState().name != "ENCRYPTED" }.getOrDefault(true)) {
            room.close()
            return
        }
        val configuration = TimelineConfiguration(
            TimelineFocus.Live(false),
            TimelineFilter.OnlyMessage(
                listOf(
                    RoomMessageEventMessageType.TEXT,
                    RoomMessageEventMessageType.NOTICE,
                    RoomMessageEventMessageType.EMOTE,
                    RoomMessageEventMessageType.IMAGE,
                    RoomMessageEventMessageType.VIDEO,
                    RoomMessageEventMessageType.AUDIO,
                    RoomMessageEventMessageType.FILE,
                    RoomMessageEventMessageType.OTHER,
                ),
            ),
            "delivery-ack-${roomId.hashCode()}",
            DateDividerMode.DAILY,
            TimelineReadReceiptTracking.DISABLED,
            false,
        )
        val timeline = try {
            room.timelineWithConfiguration(configuration)
        } catch (error: Throwable) {
            room.close()
            throw error
        }
        val listener = object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                diff.forEach { update ->
                    try {
                        processDeliveryAckTimelineUpdate(room, roomId, update)
                    } finally {
                        update.destroy()
                    }
                }
            }
        }
        val handle = try {
            timeline.addListener(listener)
        } catch (error: Throwable) {
            timeline.close()
            room.close()
            throw error
        }
        val observer = DeliveryAckObserver(room, timeline, handle, listener)
        val previous = deliveryAckObservers.putIfAbsent(roomId, observer)
        if (previous != null) {
            handle.cancel()
            handle.close()
            timeline.close()
            room.close()
        }
    }

    private fun processDeliveryAckTimelineUpdate(room: Room, roomId: String, update: TimelineDiff) {
        if (logoutInProgress) return
        val isReset = update is TimelineDiff.Reset
        val observedTargets = observedAckTargetsByRoom.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }
        if (isReset) observedTargets.clear()
        val pendingAcknowledgements = mutableListOf<String>()
        pendingMediaTimelineItems(update).forEach { item ->
            observeDeliveryAckItem(roomId, item, pendingAcknowledgements, observedTargets)
        }
        pendingAcknowledgements.distinct().forEach { eventId ->
            val account = ownUserId
            callbackScope.launch {
                if (!logoutInProgress && account != null && account == ownUserId &&
                    claimDeliveryAckEnqueue(roomId, eventId)
                ) {
                    enqueueClaimedDeliveryAck(room, roomId, eventId)
                }
            }
        }
        if (isReset && !logoutInProgress) {
            reconcilePendingDeliveryAcksAfterReset(room, roomId, observedTargets.toSet())
        }
    }

    private fun observeDeliveryAckItem(
        roomId: String,
        item: TimelineItem,
        pendingAcknowledgements: MutableList<String>,
        observedTargets: MutableSet<String>,
    ) {
        val event = item.asEvent() ?: return
        try {
            val message = ((event.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message)
                ?.content ?: return
            val acknowledgedEventId = message.msgType.deliveryAckTarget()
            if (acknowledgedEventId != null) {
                if (event.isOwn) {
                    recordOwnDeliveryAck(roomId, acknowledgedEventId, event.localSendState)
                    observedTargets.add(acknowledgedEventId)
                    if ((event.localSendState as? EventSendState.SendingFailed)?.isRecoverable == true) {
                        rememberRecoverableAckSend(roomId, acknowledgedEventId, event)
                    }
                } else if (event.isRemote) {
                    recordRemoteAckMessageObserved(roomId)
                    recordReceivedDeliveryAck(roomId, acknowledgedEventId, event.sender)
                }
                refreshDeliveryStates()
                return
            }

            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return
            if (!event.isOwn && event.isRemote && message.msgType.isDeliveryAckEligible() &&
                recordIncomingEventForAck(roomId, eventId)
            ) {
                pendingAcknowledgements += eventId
            }
        } finally {
            event.destroy()
        }
    }

    private fun MessageType.deliveryAckTarget(): String? = when (this) {
        is MessageType.Other -> if (msgtype == DELIVERY_ACK_MSGTYPE) body.takeIf(String::isNotBlank) else null
        is MessageType.Text -> DeliveryAckProtocol.targetEventId(content.body)
        else -> null
    }

    private fun closeDeliveryAckObservers() {
        deliveryAckObservers.values.toList().forEach { observer ->
            runCatching { observer.handle.cancel() }
            runCatching { observer.handle.close() }
            runCatching { observer.timeline.close() }
            runCatching { observer.room.close() }
        }
        deliveryAckObservers.clear()
    }

    private suspend fun sendDeliveryAcknowledgement(room: Room, eventId: String) {
        check(!logoutInProgress) { "Secure sign-out is in progress" }
        if (room.encryptionState().name != "ENCRYPTED") throw IllegalStateException("Delivery receipts require encryption")
        val timeline = room.timeline()
        val content = timeline.createMessageContent(
            MessageType.Text(TextMessageContent(DeliveryAckProtocol.encode(eventId), null)),
        ) ?: run {
            timeline.close()
            throw IllegalStateException("Couldn't prepare an encrypted delivery receipt")
        }
        try {
            withSendQueueGate { timeline.send(content).destroy() }
        } finally {
            content.destroy()
            timeline.close()
        }
    }

    private fun deliveryAckKey(roomId: String, eventId: String): String = "$roomId\u0000$eventId"

    private fun observeSendQueueUpdate(roomId: String, update: RoomSendQueueUpdate) {
        when (update) {
            is RoomSendQueueUpdate.NewLocalEvent -> bindDeliverySnapshotReservation(roomId, update.transactionId)
            is RoomSendQueueUpdate.SentEvent -> promoteDeliverySnapshot(roomId, update.transactionId, update.eventId)
            is RoomSendQueueUpdate.CancelledLocalEvent -> discardPendingDeliverySnapshot(roomId, update.transactionId)
            is RoomSendQueueUpdate.ReplacedLocalEvent -> discardPendingDeliverySnapshot(roomId, update.transactionId)
            else -> Unit
        }
    }

    private fun bindDeliverySnapshotReservation(roomId: String, transactionId: String) {
        synchronized(deliverySnapshotReservationLock) {
            val reservation = activeDeliverySnapshotReservation ?: return
            if (reservation.roomId != roomId) return
            if (!reservation.abandoned && reservation.expectedMembers != null) {
                persistPendingDeliverySnapshot(roomId, transactionId, reservation.expectedMembers)
            }
            activeDeliverySnapshotReservation = null
        }
    }

    private fun persistPendingDeliverySnapshot(roomId: String, transactionId: String, expectedMembers: Set<String>) {
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val key = deliveryAckKey(roomId, transactionId)
            val current = pendingDeliverySnapshotsByTransaction[key]
            if (current?.snapshotKnown == true) return
            val snapshot = PendingDeliverySnapshot(
                roomId = roomId,
                transactionId = transactionId,
                expectedMembers = expectedMembers.toSet(),
                snapshotKnown = true,
                eventId = current?.eventId,
            )
            val pending = LinkedHashMap(pendingDeliverySnapshotsByTransaction).apply { put(key, snapshot) }
            if (snapshot.eventId == null) {
                saveDeliveryAckJournalLocked(deliveryAckJournal, pending)
                pendingDeliverySnapshotsByTransaction.clear()
                pendingDeliverySnapshotsByTransaction.putAll(pending)
            } else {
                val records = LinkedHashMap(deliveryAckJournal)
                promoteDeliverySnapshotLocked(records, pending, snapshot)
                saveDeliveryAckJournalLocked(records, pending)
                deliveryAckJournal.clear()
                deliveryAckJournal.putAll(records)
                pendingDeliverySnapshotsByTransaction.clear()
                pendingDeliverySnapshotsByTransaction.putAll(pending)
            }
        }
        refreshDeliveryStates()
    }

    private fun promoteDeliverySnapshot(roomId: String, transactionId: String, eventId: String) {
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val transactionKey = deliveryAckKey(roomId, transactionId)
            val snapshot = pendingDeliverySnapshotsByTransaction[transactionKey] ?: return
            val pending = LinkedHashMap(pendingDeliverySnapshotsByTransaction)
            val records = LinkedHashMap(deliveryAckJournal)
            if (snapshot.snapshotKnown) {
                promoteDeliverySnapshotLocked(records, pending, snapshot.copy(eventId = eventId))
            } else {
                pending[transactionKey] = snapshot.copy(eventId = eventId)
            }
            saveDeliveryAckJournalLocked(records, pending)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(records)
            pendingDeliverySnapshotsByTransaction.clear()
            pendingDeliverySnapshotsByTransaction.putAll(pending)
        }
        refreshDeliveryStates()
    }

    private fun promoteDeliverySnapshotLocked(
        records: LinkedHashMap<String, DeliveryAckRecord>,
        pending: LinkedHashMap<String, PendingDeliverySnapshot>,
        snapshot: PendingDeliverySnapshot,
    ) {
        val eventId = snapshot.eventId ?: return
        val eventKey = deliveryAckKey(snapshot.roomId, eventId)
        val current = records[eventKey]
        val promoted = if (current?.snapshotKnown == true) {
            current
        } else {
            val provisionalAcknowledgements = current
                ?.takeIf {
                    it.provisionalAckUpdatedAtMillis > 0L &&
                        System.currentTimeMillis() - it.provisionalAckUpdatedAtMillis <= PROVISIONAL_ACK_TTL_MS
                }
                ?.provisionalAcknowledgedMembers
                .orEmpty()
                .let { validateProvisionalDeliveryAcknowledgements(snapshot.expectedMembers, it) }
            DeliveryAckRecord(
                roomId = snapshot.roomId,
                targetEventId = eventId,
                state = ACK_NONE,
                delivered = snapshot.expectedMembers.isNotEmpty() &&
                    provisionalAcknowledgements.containsAll(snapshot.expectedMembers),
                retryAttempts = 0,
                nextRetryAtMillis = 0L,
                expectedMembers = snapshot.expectedMembers,
                acknowledgedMembers = provisionalAcknowledgements,
                snapshotKnown = snapshot.snapshotKnown,
                transactionId = snapshot.transactionId,
            )
        }
        records[eventKey] = promoted
        pending.remove(deliveryAckKey(snapshot.roomId, snapshot.transactionId))
    }

    private fun discardPendingDeliverySnapshot(roomId: String, transactionId: String) {
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val key = deliveryAckKey(roomId, transactionId)
            if (key !in pendingDeliverySnapshotsByTransaction) return
            val pending = LinkedHashMap(pendingDeliverySnapshotsByTransaction).apply { remove(key) }
            saveDeliveryAckJournalLocked(deliveryAckJournal, pending)
            pendingDeliverySnapshotsByTransaction.clear()
            pendingDeliverySnapshotsByTransaction.putAll(pending)
        }
    }

    /** Return true only for a newly seen event or a safely retryable pre-enqueue failure. */
    private fun recordIncomingEventForAck(roomId: String, eventId: String): Boolean = synchronized(deliveryAckLock) {
        if (logoutInProgress) return@synchronized false
        loadDeliveryAckJournalLocked()
        val key = deliveryAckKey(roomId, eventId)
        val current = deliveryAckJournal[key]
        if (current == null) {
            val updated = LinkedHashMap(deliveryAckJournal).apply {
                put(key, DeliveryAckRecord(roomId, eventId, ACK_PENDING, delivered = false))
            }
            saveDeliveryAckJournalLocked(updated)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(updated)
            true
        } else if (
            isProvisionalOnlyDeliveryAckRecord(
                state = current.state,
                snapshotKnown = current.snapshotKnown,
                provisionalMemberIds = current.provisionalAcknowledgedMembers,
            )
        ) {
            // An untrusted early ACK may have reserved this key before the actual
            // incoming message arrived. Restore the receipt outbox row and discard
            // those candidates; this event is not an outgoing event we snapshot.
            val updated = LinkedHashMap(deliveryAckJournal).apply {
                put(
                    key,
                    current.copy(
                        state = ACK_PENDING,
                        delivered = false,
                        retryAttempts = 0,
                        nextRetryAtMillis = 0L,
                        expectedMembers = emptySet(),
                        acknowledgedMembers = emptySet(),
                        snapshotKnown = false,
                        transactionId = null,
                        provisionalAcknowledgedMembers = emptySet(),
                        provisionalAckUpdatedAtMillis = 0L,
                    ),
                )
            }
            saveDeliveryAckJournalLocked(updated)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(updated)
            true
        } else {
            current.state == ACK_PENDING
        }
    }

    private fun claimDeliveryAckEnqueue(roomId: String, eventId: String): Boolean = synchronized(deliveryAckLock) {
        if (logoutInProgress) return@synchronized false
        loadDeliveryAckJournalLocked()
        val key = deliveryAckKey(roomId, eventId)
        val current = deliveryAckJournal[key] ?: return@synchronized false
        if (current.state != ACK_PENDING) return@synchronized false
        val updated = LinkedHashMap(deliveryAckJournal).apply { put(key, current.copy(state = ACK_ENQUEUING)) }
        saveDeliveryAckJournalLocked(updated)
        deliveryAckJournal.clear()
        deliveryAckJournal.putAll(updated)
        true
    }

    private fun enqueueClaimedDeliveryAck(room: Room, roomId: String, eventId: String) {
        callbackScope.launch {
            if (logoutInProgress) return@launch
            runCatching { sendDeliveryAcknowledgement(room, eventId) }
                .onSuccess { setDeliveryAckState(roomId, eventId, ACK_QUEUED) }
                .onFailure {
                    recordAckEnqueueFailure(roomId, eventId)
                    scheduleMissingAckRetry(room, roomId, eventId)
                }
        }
    }

    private fun reconcilePendingDeliveryAcksAfterReset(room: Room, roomId: String, observedTargets: Set<String>) {
        val entries = synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            deliveryAckJournal.values.filter { it.roomId == roomId }
        }
        entries.filter { it.state == ACK_PENDING }.forEach { record ->
            callbackScope.launch {
                if (claimDeliveryAckEnqueue(roomId, record.targetEventId)) {
                    enqueueClaimedDeliveryAck(room, roomId, record.targetEventId)
                }
            }
        }
        entries.filter {
            (it.state == ACK_ENQUEUING || it.state == ACK_FAILED) && it.targetEventId !in observedTargets
        }.forEach { scheduleMissingAckRetry(room, roomId, it.targetEventId) }
    }

    private fun scheduleMissingAckRetry(room: Room, roomId: String, eventId: String) {
        val key = deliveryAckKey(roomId, eventId)
        if (!ackReconciliationScheduled.add(key)) return
        callbackScope.launch {
            try {
                val record = getDeliveryAckRecord(roomId, eventId) ?: return@launch
                val retryWait = (record.nextRetryAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
                delay(maxOf(ACK_ECHO_RECONCILIATION_DELAY_MS, retryWait))
                if (observedAckTargetsByRoom[roomId]?.contains(eventId) == true) return@launch
                if (reopenAckForRetry(record) && claimDeliveryAckEnqueue(roomId, eventId)) {
                    enqueueClaimedDeliveryAck(room, roomId, eventId)
                }
            } finally {
                ackReconciliationScheduled.remove(key)
            }
        }
    }

    private fun reopenAckForRetry(expected: DeliveryAckRecord): Boolean = synchronized(deliveryAckLock) {
        if (logoutInProgress) return@synchronized false
        loadDeliveryAckJournalLocked()
        val key = deliveryAckKey(expected.roomId, expected.targetEventId)
        val current = deliveryAckJournal[key] ?: return@synchronized false
        if (current.state !in setOf(ACK_ENQUEUING, ACK_FAILED) || current.retryAttempts >= MAX_ACK_SEND_ATTEMPTS) {
            return@synchronized false
        }
        if (current.nextRetryAtMillis > System.currentTimeMillis()) return@synchronized false
        val updated = LinkedHashMap(deliveryAckJournal).apply {
            put(key, current.copy(state = ACK_PENDING, retryAttempts = current.retryAttempts + 1))
        }
        saveDeliveryAckJournalLocked(updated)
        deliveryAckJournal.clear()
        deliveryAckJournal.putAll(updated)
        true
    }

    private fun recordAckEnqueueFailure(roomId: String, eventId: String) {
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val key = deliveryAckKey(roomId, eventId)
            val current = deliveryAckJournal[key] ?: DeliveryAckRecord(roomId, eventId, ACK_FAILED, delivered = false)
            val attempt = if (current.retryAttempts == 0) 1 else current.retryAttempts
            val nextRetryAt = System.currentTimeMillis() + ackRetryDelayMillis(attempt)
            val updated = LinkedHashMap(deliveryAckJournal).apply {
                put(key, current.copy(state = ACK_FAILED, retryAttempts = attempt, nextRetryAtMillis = nextRetryAt))
            }
            saveDeliveryAckJournalLocked(updated)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(updated)
        }
    }

    private fun ackRetryDelayMillis(attempt: Int): Long =
        when (attempt.coerceIn(1, MAX_ACK_SEND_ATTEMPTS)) {
            1 -> ACK_RETRY_BASE_DELAY_MS
            2 -> ACK_RETRY_BASE_DELAY_MS * 2
            else -> ACK_RETRY_BASE_DELAY_MS * 4
        }

    private fun setDeliveryAckState(roomId: String, eventId: String, state: String) {
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val key = deliveryAckKey(roomId, eventId)
            val current = deliveryAckJournal[key] ?: DeliveryAckRecord(roomId, eventId, ACK_PENDING, delivered = false)
            val updated = LinkedHashMap(deliveryAckJournal).apply { put(key, current.copy(state = state)) }
            saveDeliveryAckJournalLocked(updated)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(updated)
        }
    }

    private fun recordOwnDeliveryAck(roomId: String, targetId: String, sendState: EventSendState?) {
        val state = when (sendState) {
            is EventSendState.NotSentYet -> ACK_QUEUED
            is EventSendState.SendingFailed -> ACK_FAILED
            is EventSendState.Sent, null -> ACK_SENT
        }
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val key = deliveryAckKey(roomId, targetId)
            val current = deliveryAckJournal[key]
            val updated = LinkedHashMap(deliveryAckJournal).apply {
                put(
                    key,
                    DeliveryAckRecord(
                        roomId = roomId,
                        targetEventId = targetId,
                        state = state,
                        delivered = current?.delivered ?: false,
                        retryAttempts = current?.retryAttempts ?: 0,
                        nextRetryAtMillis = current?.nextRetryAtMillis ?: 0L,
                        expectedMembers = current?.expectedMembers.orEmpty(),
                        acknowledgedMembers = current?.acknowledgedMembers.orEmpty(),
                        snapshotKnown = current?.snapshotKnown ?: false,
                        transactionId = current?.transactionId,
                        provisionalAcknowledgedMembers = current?.provisionalAcknowledgedMembers.orEmpty(),
                        provisionalAckUpdatedAtMillis = current?.provisionalAckUpdatedAtMillis ?: 0L,
                    ),
                )
            }
            saveDeliveryAckJournalLocked(updated)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(updated)
        }
    }

    private fun recordReceivedDeliveryAck(roomId: String, targetId: String, acknowledgedBy: String) {
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val key = deliveryAckKey(roomId, targetId)
            if (acknowledgedBy == ownUserId) return
            val current = deliveryAckJournal[key] ?: DeliveryAckRecord(roomId, targetId, ACK_NONE, delivered = false)
            // Only a snapshot durably captured for this exact outgoing event can
            // authorize an ACK. Never infer recipients from current room membership.
            if (!current.snapshotKnown) {
                val now = System.currentTimeMillis()
                val priorCandidates = if (
                    current.provisionalAckUpdatedAtMillis > 0L &&
                    now - current.provisionalAckUpdatedAtMillis <= PROVISIONAL_ACK_TTL_MS
                ) current.provisionalAcknowledgedMembers else emptySet()
                val candidates = priorCandidates + acknowledgedBy
                if (candidates.size > MAX_PROVISIONAL_ACK_SENDERS_PER_TARGET) return
                if (current.provisionalAcknowledgedMembers.isEmpty() &&
                    deliveryAckJournal.values.count {
                        !it.snapshotKnown && it.provisionalAcknowledgedMembers.isNotEmpty() &&
                            now - it.provisionalAckUpdatedAtMillis <= PROVISIONAL_ACK_TTL_MS
                    } >= MAX_PROVISIONAL_ACK_TARGETS
                ) return
                val updated = LinkedHashMap(deliveryAckJournal).apply {
                    put(
                        key,
                        current.copy(
                            provisionalAcknowledgedMembers = candidates,
                            provisionalAckUpdatedAtMillis = now,
                        ),
                    )
                }
                saveDeliveryAckJournalLocked(updated)
                deliveryAckJournal.clear()
                deliveryAckJournal.putAll(updated)
                return
            }
            val expected = current.expectedMembers
            val acknowledgements = addDeliveryAcknowledgement(
                expectedMemberIds = expected,
                acknowledgedMemberIds = current.acknowledgedMembers,
                acknowledgingMemberId = acknowledgedBy,
            ) ?: return
            if (acknowledgements == current.acknowledgedMembers) return
            val isFullyDelivered = expected.isNotEmpty() && acknowledgements.containsAll(expected)
            val updated = LinkedHashMap(deliveryAckJournal).apply {
                put(
                    key,
                    current.copy(
                        delivered = current.delivered || isFullyDelivered,
                        acknowledgedMembers = acknowledgements,
                    ),
                )
            }
            saveDeliveryAckJournalLocked(updated)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(updated)
        }
    }

    private fun rememberRecoverableAckSend(roomId: String, targetId: String, event: EventTimelineItem) {
        if (logoutInProgress) return
        val sendHandle = runCatching { event.lazyProvider.getSendHandle() }.getOrNull() ?: return
        val key = deliveryAckKey(roomId, targetId)
        val previous = failedAckSendHandles.putIfAbsent(key, sendHandle)
        if (previous != null) sendHandle.close()
        if (_connection.value == "Connected") retryRecoverableAckSend(key, roomId, targetId)
    }

    private fun retryRecoverableDeliveryAcks() {
        failedAckSendHandles.keys.toList().forEach { key ->
            val parts = key.split('\u0000', limit = 2)
            if (parts.size == 2) retryRecoverableAckSend(key, parts[0], parts[1])
        }
    }

    private fun retryRecoverableAckSend(key: String, roomId: String, targetId: String) {
        if (logoutInProgress) return
        if (!ackRetryScheduled.add(key)) return
        val handle = failedAckSendHandles.remove(key) ?: run {
            ackRetryScheduled.remove(key)
            return
        }
        callbackScope.launch {
            var returnedToQueue = false
            try {
                if (logoutInProgress) {
                    returnedToQueue = failedAckSendHandles.putIfAbsent(key, handle) == null
                    return@launch
                }
                val record = getDeliveryAckRecord(roomId, targetId) ?: return@launch
                val retryWait = (record.nextRetryAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
                if (retryWait > 0) delay(retryWait)
                if (_connection.value != "Connected" || client == null) {
                    val previous = failedAckSendHandles.putIfAbsent(key, handle)
                    returnedToQueue = previous == null
                    if (previous != null) previous.close()
                    return@launch
                }
                if (!beginRecoverableAckRetry(roomId, targetId)) return@launch
                withSendQueueGate { handle.tryResend() }
                setDeliveryAckState(roomId, targetId, ACK_QUEUED)
            } catch (_: Exception) {
                if (logoutInProgress) {
                    returnedToQueue = failedAckSendHandles.putIfAbsent(key, handle) == null
                } else {
                    recordAckRetryFailure(roomId, targetId)
                }
            } finally {
                ackRetryScheduled.remove(key)
                if (!returnedToQueue) handle.close()
            }
        }
    }

    private fun getDeliveryAckRecord(roomId: String, targetId: String): DeliveryAckRecord? = synchronized(deliveryAckLock) {
        if (logoutInProgress) return@synchronized null
        loadDeliveryAckJournalLocked()
        deliveryAckJournal[deliveryAckKey(roomId, targetId)]
    }

    internal fun deliveryAckDiagnosticSnapshot(
        roomId: String,
        eventId: String,
    ): DeliveryAckDiagnosticSnapshot {
        val record = synchronized(deliveryAckLock) {
            if (logoutInProgress) null else {
                loadDeliveryAckJournalLocked()
                deliveryAckJournal[deliveryAckKey(roomId, eventId)]
            }
        }
        val state = when (record?.state) {
            ACK_PENDING -> "pending"
            ACK_ENQUEUING -> "enqueuing"
            ACK_QUEUED -> "queued"
            ACK_FAILED -> "failed"
            ACK_SENT -> "sent"
            ACK_NONE -> "none"
            null -> "missing"
            else -> "other"
        }
        return DeliveryAckDiagnosticSnapshot(
            observerInstalled = deliveryAckObservers.containsKey(roomId),
            recordPresent = record != null,
            state = state,
            snapshotKnown = record?.snapshotKnown == true,
            expectedRecipientCount = record?.expectedMembers?.size ?: 0,
            acknowledgedRecipientCount = record?.acknowledgedMembers?.size ?: 0,
            provisionalAcknowledgementCount = record?.provisionalAcknowledgedMembers?.size ?: 0,
            remoteAckMessageCount = remoteAckMessageCounts[roomId]?.get() ?: 0,
        )
    }

    private fun recordRemoteAckMessageObserved(roomId: String) {
        remoteAckMessageCounts.computeIfAbsent(roomId) { AtomicInteger() }.incrementAndGet()
    }

    private fun beginRecoverableAckRetry(roomId: String, targetId: String): Boolean = synchronized(deliveryAckLock) {
        if (logoutInProgress) return@synchronized false
        loadDeliveryAckJournalLocked()
        val key = deliveryAckKey(roomId, targetId)
        val current = deliveryAckJournal[key] ?: return@synchronized false
        if (current.state != ACK_FAILED || current.retryAttempts >= MAX_ACK_SEND_ATTEMPTS) return@synchronized false
        val updated = LinkedHashMap(deliveryAckJournal).apply {
            put(key, current.copy(state = ACK_ENQUEUING, retryAttempts = current.retryAttempts + 1))
        }
        saveDeliveryAckJournalLocked(updated)
        deliveryAckJournal.clear()
        deliveryAckJournal.putAll(updated)
        true
    }

    private fun recordAckRetryFailure(roomId: String, targetId: String) {
        synchronized(deliveryAckLock) {
            if (logoutInProgress) return
            loadDeliveryAckJournalLocked()
            val key = deliveryAckKey(roomId, targetId)
            val current = deliveryAckJournal[key] ?: return
            val attempt = current.retryAttempts.coerceAtLeast(1)
            val updated = LinkedHashMap(deliveryAckJournal).apply {
                put(
                    key,
                    current.copy(
                        state = ACK_FAILED,
                        nextRetryAtMillis = System.currentTimeMillis() + ackRetryDelayMillis(attempt),
                    ),
                )
            }
            saveDeliveryAckJournalLocked(updated)
            deliveryAckJournal.clear()
            deliveryAckJournal.putAll(updated)
        }
    }

    private fun refreshDeliveryStates() {
        val roomId = activeRoomId ?: return
        synchronized(timelineMessagesLock) {
            timelineMessages.indices.forEach { index ->
                val message = timelineMessages[index] ?: return@forEach
                if (message.isOwn && message.eventId != null) {
                    val status = deliveryStateFor(roomId, message.eventId, message.deliveryState)
                    if (status != message.deliveryState) timelineMessages[index] = message.copy(deliveryState = status)
                }
            }
            publishTimelineMessagesLocked()
        }
    }

    private fun deliveryStateFor(roomId: String, eventId: String, fallback: String = "Sent"): String {
        val record = getDeliveryAckRecord(roomId, eventId) ?: return fallback
        if (!record.snapshotKnown) return fallback
        return deliveryStatusLabel(
            expectedMemberIds = record.expectedMembers,
            acknowledgedMemberIds = record.acknowledgedMembers,
            fallback = fallback,
            legacyDelivered = record.delivered,
        )
    }

    private suspend fun getSearchService(): SearchService {
        searchService?.let { return it }
        val service = requireClient().searchService()
        searchService = service
        val resultsListener = object : SearchServiceResultsListener {
            override fun onUpdate(updates: List<SearchServiceResultsUpdate>) {
                updates.forEach(::applySearchResultsUpdate)
            }
        }
        searchResultsListener = resultsListener
        searchResultsHandle = service.subscribeToResults(resultsListener)
        val paginationListener = object : SearchServicePaginationStateListener {
            override fun onUpdate(paginationState: uniffi.matrix_sdk_ui.SearchServicePaginationState) {
                when (paginationState) {
                    is uniffi.matrix_sdk_ui.SearchServicePaginationState.Idle -> {
                        _searchHasMore.value = !paginationState.endReached
                        _searchLoading.value = false
                    }
                    uniffi.matrix_sdk_ui.SearchServicePaginationState.Loading -> _searchLoading.value = true
                }
            }
        }
        searchPaginationListener = paginationListener
        searchPaginationHandle = service.subscribeToPaginationStateUpdates(paginationListener)
        return service
    }

    private fun applySearchResultsUpdate(update: SearchServiceResultsUpdate) {
        val rows = _searchResults.value.toMutableList()
        when (update) {
            is SearchServiceResultsUpdate.Append -> rows += update.values.mapNotNull(::messageSearchHit)
            SearchServiceResultsUpdate.Clear -> rows.clear()
            is SearchServiceResultsUpdate.PushFront -> messageSearchHit(update.value)?.let { rows.add(0, it) }
            is SearchServiceResultsUpdate.PushBack -> messageSearchHit(update.value)?.let(rows::add)
            SearchServiceResultsUpdate.PopFront -> if (rows.isNotEmpty()) rows.removeAt(0)
            SearchServiceResultsUpdate.PopBack -> if (rows.isNotEmpty()) rows.removeAt(rows.lastIndex)
            is SearchServiceResultsUpdate.Insert -> messageSearchHit(update.value)?.let { hit ->
                rows.add(update.index.toInt().coerceIn(0, rows.size), hit)
            }
            is SearchServiceResultsUpdate.Set -> messageSearchHit(update.value)?.let { hit ->
                val index = update.index.toInt()
                if (index in rows.indices) rows[index] = hit
            }
            is SearchServiceResultsUpdate.Remove -> {
                val index = update.index.toInt()
                if (index in rows.indices) rows.removeAt(index)
            }
            is SearchServiceResultsUpdate.Truncate -> if (update.length.toInt() < rows.size) {
                rows.subList(update.length.toInt().coerceAtLeast(0), rows.size).clear()
            }
            is SearchServiceResultsUpdate.Reset -> {
                rows.clear()
                rows += update.values.mapNotNull(::messageSearchHit)
            }
        }
        val seen = HashSet<String>(rows.size)
        _searchResults.value = rows.filter { seen.add("${it.roomId}:${it.eventId}") }
        update.destroy()
    }

    private fun messageSearchHit(result: SearchServiceResult): MessageSearchHit? {
        return when (result) {
            is SearchServiceResult.Message -> {
                val body = result.result.content.readableBody()?.takeIf(String::isNotBlank) ?: return null
                val roomTitle = _conversations.value.firstOrNull { it.roomId == result.roomId }?.title
                    ?: "Private conversation"
                MessageSearchHit(
                    roomId = result.roomId,
                    roomTitle = roomTitle,
                    eventId = result.result.eventId,
                    sender = result.result.sender,
                    body = body,
                    timestampMillis = result.result.timestamp.toLong(),
                )
            }
        }
    }

    private fun closeSearchService() {
        searchResultsHandle?.cancel()
        searchResultsHandle?.close()
        searchPaginationHandle?.cancel()
        searchPaginationHandle?.close()
        searchService?.close()
        searchResultsHandle = null
        searchPaginationHandle = null
        searchResultsListener = null
        searchPaginationListener = null
        searchService = null
        _searchResults.value = emptyList()
        _searchHasMore.value = false
        _searchLoading.value = false
    }

    private suspend fun getVerificationController(): SessionVerificationController {
        verificationControllerMutex.lock()
        try {
            verificationController?.let { return it }
            val matrixClient = requireClient()
            val currentUserId = ownUserId ?: matrixClient.userId()
            // The login-time wait may run before initial sync schedules cross-signing setup.
            // Wait again from this post-sync verification path so the SDK's incoming-request
            // prefilter sees a complete identity; do not surface requests that cannot be
            // completed by this session.
            var identityAvailable = false
            withTimeoutOrNull(VERIFICATION_IDENTITY_WAIT_TIMEOUT_MS) {
                matrixClient.encryption().waitForE2eeInitializationTasks()
                while (!identityAvailable) {
                    val ownIdentity = matrixClient.encryption().userIdentity(currentUserId, true)
                    if (ownIdentity != null) {
                        ownIdentity.close()
                        identityAvailable = true
                    } else {
                        delay(VERIFICATION_IDENTITY_POLL_MS)
                    }
                }
            }
            if (!identityAvailable) throw MatrixVerificationIdentityUnavailable()

            val controller = matrixClient.getSessionVerificationController()
            verificationController = controller
            controller.setDelegate(object : SessionVerificationControllerDelegate {
            override fun didReceiveVerificationRequest(details: org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails) {
                verificationWasInitiatedHere = false
                val userId = details.senderProfile.userId
                val deviceName = details.deviceDisplayName ?: details.deviceId
                pendingVerificationRequest = userId to details.flowId
                _verification.value = DeviceVerificationUiState(
                    peerUserId = userId,
                    peerDeviceName = deviceName,
                    status = DeviceVerificationStatus.INCOMING_REQUEST,
                )
            }

            override fun didAcceptVerificationRequest() {
                updateVerificationProgress()
                // Both the requester and accepter reach Ready. Only the requester starts
                // SAS; the accepter waits for the SDK to receive and accept the Start event.
                if (!verificationWasInitiatedHere) return
                callbackScope.launch {
                    runCatching { controller.startSasVerification() }
                        .onFailure { error ->
                            updateVerificationFailure(error)
                        }
                }
            }

            override fun didStartSasVerification() {
                updateVerificationProgress()
            }

            override fun didReceiveVerificationData(data: SessionVerificationData) {
                val sas = try {
                    when (data) {
                        is SessionVerificationData.Emojis -> data.emojis.joinToString("  ·  ") { emoji ->
                            "${emoji.symbol()} ${emoji.description()}"
                        }
                        is SessionVerificationData.Decimals -> data.values.joinToString("   ") { value ->
                            value.toString().padStart(4, '0')
                        }
                    }
                } finally {
                    data.destroy()
                }
                _verification.update { current ->
                    when {
                        current == null -> null
                        current.status in VERIFICATION_TERMINAL_STATUSES -> current
                        current.status == DeviceVerificationStatus.CONFIRMING -> current.copy(sas = sas)
                        else -> current.copy(status = DeviceVerificationStatus.COMPARING, sas = sas)
                    }
                }
            }

            override fun didFail() {
                updateVerificationTerminal(DeviceVerificationStatus.FAILED, "Verification couldn't be completed.")
            }

            override fun didCancel() {
                pendingVerificationRequest = null
                verificationWasInitiatedHere = false
                updateVerificationTerminal(DeviceVerificationStatus.CANCELLED)
            }

            override fun didFinish() {
                pendingVerificationRequest = null
                verificationWasInitiatedHere = false
                updateVerificationTerminal(DeviceVerificationStatus.VERIFIED)
                activeRoomId?.let { roomId ->
                    callbackScope.launch { runCatching { refreshPeerTrustAfterVerification(roomId) } }
                }
            }
            })
            return controller
        } finally {
            verificationControllerMutex.unlock()
        }
    }

    private fun updateVerificationProgress() {
        _verification.update { current ->
            if (current == null || current.status in VERIFICATION_TERMINAL_STATUSES ||
                current.status == DeviceVerificationStatus.CONFIRMING
            ) {
                current
            } else {
                current.copy(status = DeviceVerificationStatus.COMPARING)
            }
        }
    }

    private fun updateVerificationFailure(error: Throwable) {
        val safeError = verificationErrorMessage(error) ?: "Verification couldn't be completed."
        _verification.update { current ->
            if (current == null || current.status in VERIFICATION_TERMINAL_STATUSES) current
            else current.copy(status = DeviceVerificationStatus.FAILED, error = safeError)
        }
    }

    private fun updateVerificationTerminal(status: DeviceVerificationStatus, error: String? = null) {
        _verification.update { current ->
            if (current == null || current.status in VERIFICATION_TERMINAL_STATUSES) current
            else current.copy(status = status, error = error)
        }
    }

    private fun requireRoom(roomId: String): Room = requireClient().rooms().firstOrNull { it.id() == roomId }
        ?: throw IllegalArgumentException("This conversation is no longer available")

    private suspend fun <T> withLifecycleLock(action: suspend () -> T): T {
        lifecycleMutex.lock()
        try {
            return action()
        } finally {
            lifecycleMutex.unlock()
        }
    }

    /** Serialize SDK enqueue calls with queue shutdown and reject new sends during logout. */
    private suspend fun <T> withSendQueueGate(action: suspend () -> T): T {
        sendQueueMutex.lock()
        try {
            check(!logoutInProgress) { "Secure sign-out is in progress" }
            return action()
        } finally {
            sendQueueMutex.unlock()
        }
    }

    /**
     * Capture recipient membership while enqueue calls are serialized, then bind that exact
     * set to the new Matrix transaction ID reported by the SDK send-queue listener. An
     * unresolved reservation becomes discard-only; a late callback is never attributed to
     * the next message.
     */
    private suspend fun <T> withDeliverySnapshotReservation(
        roomId: String,
        room: Room,
        enqueue: suspend () -> T,
    ): T {
        if (sendQueueUpdatesHandle == null) return enqueue()

        val expectedMembers = runCatching {
            val senderId = checkNotNull(ownUserId)
            snapshotJoinedDeliveryRecipients(joinedHumanDeliveryCandidates(room), senderId)
        }.getOrNull()
        val reservation = DeliverySnapshotReservation(roomId, expectedMembers)
        val installed = synchronized(deliverySnapshotReservationLock) {
            if (activeDeliverySnapshotReservation != null) {
                // Do not let a later send steal an unresolved transaction callback.
                activeDeliverySnapshotReservation?.abandoned = true
                false
            } else {
                activeDeliverySnapshotReservation = reservation
                true
            }
        }
        if (!installed) return enqueue()

        try {
            val result = enqueue()
            // Give the SDK callback a short chance to arrive without delaying the
            // composer. If it does not, mark this reservation discard-only.
            callbackScope.launch {
                delay(DELIVERY_SNAPSHOT_BIND_GRACE_MS)
                synchronized(deliverySnapshotReservationLock) {
                    if (activeDeliverySnapshotReservation === reservation) reservation.abandoned = true
                }
            }
            return result
        } catch (failure: Throwable) {
            synchronized(deliverySnapshotReservationLock) {
                if (activeDeliverySnapshotReservation === reservation) reservation.abandoned = true
            }
            throw failure
        }
    }

    private fun closeSendQueueUpdates() {
        sendQueueUpdatesHandle?.cancel()
        sendQueueUpdatesHandle?.close()
        sendQueueUpdatesHandle = null
        sendQueueUpdatesListener = null
        synchronized(deliverySnapshotReservationLock) {
            activeDeliverySnapshotReservation = null
        }
    }

    /**
     * Retain each native attachment task independently of its UI coroutine. The SDK's
     * SendAttachmentJoinHandle.cancel() aborts the Rust task, so this watcher never cancels
     * the handle; it only releases it once join() returns.
     */
    private fun trackAttachmentSend(handle: SendAttachmentJoinHandle): TrackedAttachmentSend {
        val tracked = TrackedAttachmentSend(
            id = UUID.randomUUID().toString(),
            handle = handle,
            result = CompletableDeferred(),
            finished = CompletableDeferred(),
        )
        activeAttachmentSends[tracked.id] = tracked
        callbackScope.launch {
            try {
                withContext(NonCancellable) { handle.join() }
                tracked.result.complete(Unit)
            } catch (error: Throwable) {
                tracked.result.completeExceptionally(error)
            } finally {
                runCatching { handle.destroy() }
                activeAttachmentSends.remove(tracked.id, tracked)
                tracked.finished.complete(Unit)
            }
        }
        return tracked
    }

    private suspend fun awaitActiveAttachmentSends() {
        val pending = activeAttachmentSends.values.toList()
        if (pending.isEmpty()) return
        val drained = withTimeoutOrNull(ATTACHMENT_SEND_DRAIN_TIMEOUT_MS) {
            pending.forEach { it.finished.await() }
            true
        } ?: false
        if (!drained || activeAttachmentSends.isNotEmpty()) {
            throw AttachmentDrainTimeoutException()
        }
    }

    private fun refreshSendQueueGate(matrixClient: Client) {
        // While logout is draining a registered native media send, keep its existing
        // queue worker alive so join() can observe a terminal result. The logout flag still
        // rejects new application sends at withSendQueueGate().
        val queuesShouldRun = syncServiceRunning &&
            (!logoutInProgress || activeAttachmentSends.isNotEmpty())
        if (sendQueuesEnabled == queuesShouldRun) return
        sendQueuesEnabled = queuesShouldRun
        callbackScope.launch {
            sendQueueMutex.lock()
            try {
                if (sendQueuesEnabled == queuesShouldRun && client === matrixClient &&
                    (!queuesShouldRun || (syncServiceRunning &&
                        (!logoutInProgress || activeAttachmentSends.isNotEmpty())))
                ) {
                    matrixClient.enableAllSendQueues(queuesShouldRun)
                }
            } catch (_: Exception) {
                // A later sync-state transition or logout recovery retries this update.
            } finally {
                sendQueueMutex.unlock()
            }
        }
    }

    private fun requireClient(): Client {
        check(!logoutInProgress) { "Secure sign-out is in progress" }
        return checkNotNull(client) { "Connect to your homeserver first" }
    }

    private fun stageContentUri(contentUri: String, maximumBytes: Long): StagedAttachment {
        val uri = Uri.parse(contentUri)
        val resolver = appContext.contentResolver
        val suppliedName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val mimeType = resolver.getType(uri)?.substringBefore(';')?.lowercase()
            ?: "application/octet-stream"
        val safeName = safeFileName(suppliedName ?: "attachment")
        val stagingDirectory = File(mediaTransferDir, "upload-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(stagingDirectory, safeName)
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("The selected attachment could not be opened")
            input.use { source ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= maximumBytes) {
                            "This attachment exceeds the supported ${maximumBytes / (1024 * 1024)} MB upload limit"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(file.length() > 0) { "The selected attachment is empty" }
            return StagedAttachment(file, safeName, mimeType)
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            throw error
        }
    }

    /** Persist an independently encrypted recovery copy before passing bytes to the SDK queue. */
    private fun preparePendingMedia(
        roomId: String,
        staged: StagedAttachment,
        audioDurationMillis: Long?,
    ): PendingMediaRecord {
        val record = PendingMediaRecord(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            fileName = staged.displayName,
            mimeType = staged.mimeType,
            sizeBytes = staged.file.length(),
            audioDurationMillis = audioDurationMillis?.takeIf { it > 0 },
            createdAtMillis = System.currentTimeMillis(),
            transactionId = null,
            eventId = null,
            sourceVersion = DATA_UPLOAD_SOURCE_VERSION,
        )
        val encryptedSource = pendingMediaArchiveFile(record.id)
        try {
            encryptFile(staged.file, encryptedSource, "pending-media:${record.id}")
            putPendingMediaRecord(record)
            return record
        } catch (error: Throwable) {
            encryptedSource.delete()
            throw error
        }
    }

    private suspend fun restorePendingMediaSources() {
        loadDeliveryAckJournal()
        loadPendingMediaJournal()
        val records = synchronized(pendingMediaLock) { pendingMediaRecords.values.toList() }
        // Voice drafts live only in the process-private transfer directory. After a process
        // restart, a sent tombstone cannot match a live composer draft and can be discarded.
        records.filter(PendingMediaRecord::sent).forEach { removePendingMediaRecord(it.id) }
        val activeRecords = records.filterNot(PendingMediaRecord::sent)
        val legacyRecords = activeRecords.filter { it.sourceVersion < DATA_UPLOAD_SOURCE_VERSION }
        legacyRecords.forEach { record ->
            val source = pendingMediaSourceFile(record)
            val archive = pendingMediaArchiveFile(record.id)
            if (archive.isFile) {
                decryptFile(archive, source, "pending-media:${record.id}")
            } else if (source.isFile) {
                // A cache purge does not usually remove this copy. If only the encrypted
                // journal file was lost, seal the remaining app-private source before replay.
                encryptFile(source, archive, "pending-media:${record.id}")
            } else {
                throw IllegalStateException("A pending encrypted attachment could not be recovered")
            }
        }
        cleanupUnreferencedMediaArchives(
            referencedArchiveIds = activeRecords.mapTo(HashSet(), PendingMediaRecord::id),
            referencedLegacySourceIds = legacyRecords.mapTo(HashSet(), PendingMediaRecord::id),
        )
    }

    /**
     * Re-enqueue v2 archives only after restored send-queue entries have been reconciled from
     * the timeline's initial Reset. Match local echoes by transaction ID or a bounded
     * device-local creation time, and remote echoes by the encrypted record marker. Ambiguous
     * matches are deliberately left pending.
     */
    private fun hasPendingDataMediaReplayWork(): Boolean = synchronized(pendingMediaLock) {
        pendingMediaRecords.values.any { record ->
            !record.sent &&
                record.sourceVersion >= DATA_UPLOAD_SOURCE_VERSION &&
                record.transactionId == null &&
                record.eventId == null &&
                record.id !in pendingMediaRecoveryAmbiguousRecordIds
        }
    }

    /**
     * Coalesce recovery requests, retry transient failures with a finite backoff, and let a
     * newly-ready room or a fresh sync-running transition accelerate a pending retry.
     * The replay routine still rechecks transaction/event IDs and ambiguity before enqueue.
     */
    private fun requestPendingDataMediaReplay(
        delayMillis: Long = 0L,
        resetRetryBudget: Boolean = false,
        retryWithinBudget: Boolean = false,
    ) {
        if (logoutInProgress || !syncServiceRunning || client == null || !hasPendingDataMediaReplayWork()) return
        synchronized(pendingMediaReplaySchedulerLock) {
            if (resetRetryBudget) pendingMediaReplayRetryCount = 0
            if (!resetRetryBudget && !retryWithinBudget &&
                pendingMediaReplayRetryCount >= MAX_PENDING_MEDIA_REPLAY_RETRIES
            ) return
            val existing = pendingMediaReplayJob
            if (existing?.isActive == true) {
                // A newly-ready room should not wait out an already scheduled backoff. Never
                // cancel an executing replay, where the SDK may already own a local echo.
                if (delayMillis == 0L && !pendingMediaReplayExecuting) {
                    existing.cancel()
                    pendingMediaReplayJob = null
                } else {
                    return
                }
            }
            lateinit var scheduledJob: Job
            scheduledJob = callbackScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (delayMillis > 0L) delay(delayMillis)
                    if (logoutInProgress || !syncServiceRunning || client == null) return@launch
                    val stillOwnsReplaySlot = synchronized(pendingMediaReplaySchedulerLock) {
                        if (pendingMediaReplayJob === scheduledJob && scheduledJob.isActive) {
                            pendingMediaReplayExecuting = true
                            true
                        } else {
                            false
                        }
                    }
                    if (!stillOwnsReplaySlot) return@launch
                    val retryNeeded = try {
                        resumePendingDataMediaUploads()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        true
                    }
                    val nextDelay = synchronized(pendingMediaReplaySchedulerLock) {
                        if (pendingMediaReplayJob === scheduledJob) pendingMediaReplayExecuting = false
                        if (retryNeeded) {
                            pendingMediaReplayRetryCount += 1
                            pendingMediaReplayRetryDelayMillis(pendingMediaReplayRetryCount)
                        } else {
                            pendingMediaReplayRetryCount = 0
                            null
                        }
                    }
                    if (nextDelay != null) {
                        synchronized(pendingMediaReplaySchedulerLock) {
                            if (pendingMediaReplayJob === scheduledJob) pendingMediaReplayJob = null
                        }
                        requestPendingDataMediaReplay(delayMillis = nextDelay, retryWithinBudget = true)
                    }
                } finally {
                    synchronized(pendingMediaReplaySchedulerLock) {
                        if (pendingMediaReplayJob === scheduledJob) {
                            pendingMediaReplayJob = null
                            pendingMediaReplayExecuting = false
                        }
                    }
                }
            }
            pendingMediaReplayJob = scheduledJob
            scheduledJob.start()
        }
    }

    private fun cancelPendingDataMediaReplay(resetRetryBudget: Boolean) {
        synchronized(pendingMediaReplaySchedulerLock) {
            pendingMediaReplayJob?.cancel()
            pendingMediaReplayJob = null
            pendingMediaReplayExecuting = false
            if (resetRetryBudget) pendingMediaReplayRetryCount = 0
        }
    }

    private suspend fun resumePendingDataMediaUploads(): Boolean = mediaDataRecoveryMutex.withLock recovery@{
        if (logoutInProgress || !syncServiceRunning) return@recovery false
        val activeClient = client ?: return@recovery false
        // Wait for the restored timeline Reset first. An SDK-owned queue entry must be
        // discovered before any archive without an SDK echo is considered for replay.
        val readiness = ensurePendingMediaObserversForKnownRooms()
        val readyRooms = readiness.readyRoomIds
        sendQueueMutex.withLock {
            if (client === activeClient && syncServiceRunning && !logoutInProgress && sendQueuesEnabled != true) {
                activeClient.enableAllSendQueues(true)
                sendQueuesEnabled = true
            }
        }
        if (logoutInProgress || client !== activeClient || !syncServiceRunning) return@recovery false

        val records = synchronized(pendingMediaLock) {
            pendingMediaRecords.values.filter { !it.sent && it.sourceVersion >= DATA_UPLOAD_SOURCE_VERSION }
        }
        var retryNeeded = false
        for (savedRecord in records) {
            if (savedRecord.roomId !in readyRooms) {
                retryNeeded = true
                continue
            }
            if (savedRecord.id in pendingMediaRecoveryAmbiguousRecordIds) continue
            val record = synchronized(pendingMediaLock) { pendingMediaRecords[savedRecord.id] } ?: continue
            if (record.sent || record.transactionId != null || record.eventId != null ||
                record.id in pendingMediaRecoveryAmbiguousRecordIds
            ) continue

            val recoveryDirectory = File(mediaTransferDir, "recovery-${record.id}").apply { mkdirs() }
            val recoveryFile = File(recoveryDirectory, safeFileName(record.fileName))
            try {
                val archive = pendingMediaArchiveFile(record.id)
                check(archive.isFile && archive.length() > 0L) {
                    "A pending encrypted attachment could not be recovered"
                }
                decryptFile(archive, recoveryFile, "pending-media:${record.id}")
                val retryRecord = record.copy(
                    // Anchor the next SDK local echo to this replay attempt before enqueue.
                    createdAtMillis = System.currentTimeMillis(),
                    inFlight = true,
                )
                updatePendingMediaRecord(retryRecord.id) { current ->
                    current.copy(createdAtMillis = retryRecord.createdAtMillis, inFlight = true)
                }
                mediaDataEnqueueMutex.withLock enqueue@{
                    if (logoutInProgress || client !== activeClient || !syncServiceRunning) return@enqueue
                    val room = requireRoom(record.roomId)
                    val timeline = activeTimeline?.takeIf { activeRoomId == record.roomId } ?: room.timeline()
                    val ownsTimeline = timeline !== activeTimeline
                    try {
                        enqueuePendingAttachment(room, retryRecord, StagedAttachment(recoveryFile, record.fileName, record.mimeType), timeline)
                    } finally {
                        if (ownsTimeline) timeline.close()
                    }
                }
                updatePendingMediaRecord(record.id) { current -> current.copy(inFlight = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the encrypted archive and record. The bounded scheduler retries after
                // checking the SDK timeline again; a later sync transition/app start also
                // starts a fresh retry budget. Never discard ambiguous send identities.
                updatePendingMediaRecord(record.id) { current -> current.copy(inFlight = false) }
                retryNeeded = true
            } finally {
                recoveryDirectory.deleteRecursively()
            }
        }
        retryNeeded
    }

    private suspend fun ensurePendingMediaObserversForKnownRooms(): PendingMediaObserverReadiness {
        val roomIds = synchronized(pendingMediaLock) {
            pendingMediaRecords.values.filterNot(PendingMediaRecord::sent).map(PendingMediaRecord::roomId).distinct()
        }
        val readyRooms = mutableSetOf<String>()
        val newlyReadyRooms = mutableSetOf<String>()
        roomIds.forEach { roomId ->
            // A room may not be exposed by the SDK until its restored sync state is
            // available. Paths are restored before queue replay, so retry observer
            // setup on later conversation refreshes without blocking sync startup.
            val observer = runCatching { ensurePendingMediaObserver(roomId) }.getOrNull() ?: return@forEach
            val snapshotReady = withTimeoutOrNull(PENDING_MEDIA_SNAPSHOT_TIMEOUT_MS) {
                observer.initialSnapshotReady.await()
                true
            } == true
            if (snapshotReady) {
                readyRooms += roomId
                if (pendingMediaReadyRoomIds.add(roomId)) newlyReadyRooms += roomId
            }
        }
        return PendingMediaObserverReadiness(readyRooms, newlyReadyRooms)
    }

    private suspend fun ensurePendingMediaObserver(roomId: String): PendingMediaObserver {
        pendingMediaObservers[roomId]?.let { return it }
        val room = requireRoom(roomId)
        val timeline = room.timeline()
        val initialSnapshotReady = CompletableDeferred<Unit>()
        val initialResetObserved = AtomicBoolean(false)
        val listener = object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                val resetJobs = mutableListOf<Job>()
                var completesInitialReset = false
                diff.forEach { update ->
                    try {
                        val isInitialReset = update is TimelineDiff.Reset &&
                            initialResetObserved.compareAndSet(false, true)
                        if (isInitialReset) completesInitialReset = true
                        pendingMediaTimelineItems(update).forEach { item ->
                            runCatching { reconcilePendingMediaEvent(roomId, item) }
                                .getOrNull()
                                ?.let { job -> if (isInitialReset) resetJobs += job }
                        }
                    } finally {
                        update.destroy()
                    }
                }
                if (completesInitialReset) {
                    callbackScope.launch {
                        resetJobs.joinAll()
                        initialSnapshotReady.complete(Unit)
                    }
                }
            }
        }
        val handle = timeline.addListener(listener)
        val observer = PendingMediaObserver(timeline, handle, listener, initialSnapshotReady)
        val previous = pendingMediaObservers.putIfAbsent(roomId, observer)
        if (previous != null) {
            handle.cancel()
            handle.close()
            timeline.close()
            return previous
        }
        return observer
    }

    private fun pendingMediaTimelineItems(update: TimelineDiff): List<TimelineItem> = when (update) {
        is TimelineDiff.Append -> update.values
        is TimelineDiff.Reset -> update.values
        is TimelineDiff.PushFront -> listOf(update.value)
        is TimelineDiff.PushBack -> listOf(update.value)
        is TimelineDiff.Insert -> listOf(update.value)
        is TimelineDiff.Set -> listOf(update.value)
        else -> emptyList()
    }

    private fun reconcilePendingMediaEvent(roomId: String, item: TimelineItem): Job? {
        if (logoutInProgress) return null
        val event = item.asEvent() ?: return null
        try {
            if (!event.isOwn) return null
            val identity = event.attachmentIdentity() ?: return null
            val transactionId = (event.eventOrTransactionId as? EventOrTransactionId.TransactionId)?.transactionId
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
            val localCreatedAt = event.localCreatedAt?.toLong()
            val pendingMediaRecordId = if (event.isRemote) event.pendingMediaRecordId() else null
            val sendState = event.localSendState
            val isSent = sendState is EventSendState.Sent
            val recoverableSendHandle = if ((sendState as? EventSendState.SendingFailed)?.isRecoverable == true) {
                runCatching { event.lazyProvider.getSendHandle() }.getOrNull()
            } else {
                null
            }
            return callbackScope.launch {
                var retainedSendHandle = false
                try {
                    if (logoutInProgress) return@launch
                    val candidate = findPendingMediaRecord(
                        roomId = roomId,
                        identity = identity,
                        pendingMediaRecordId = pendingMediaRecordId,
                        transactionId = transactionId,
                        eventId = eventId,
                        localCreatedAtMillis = localCreatedAt,
                    ) ?: return@launch
                    if (isSent) {
                        markPendingMediaSent(candidate.id, transactionId, eventId)
                    } else if (transactionId != null || eventId != null) {
                        updatePendingMediaRecord(candidate.copy(transactionId = transactionId, eventId = eventId))
                        if (recoverableSendHandle != null) {
                            pendingMediaSendHandles.put(candidate.id, recoverableSendHandle)?.close()
                            retainedSendHandle = true
                        } else {
                            pendingMediaSendHandles.remove(candidate.id)?.close()
                        }
                    }
                } finally {
                    if (!retainedSendHandle) recoverableSendHandle?.close()
                }
            }
        } finally {
            event.destroy()
        }
    }

    /** Read only the app's random correlation token from a remote event's decrypted content. */
    private fun EventTimelineItem.pendingMediaRecordId(): String? {
        val rawEvent = runCatching { lazyProvider.debugInfo().originalJson }.getOrNull() ?: return null
        return runCatching {
            val content = JSONObject(rawEvent).optJSONObject("content") ?: return@runCatching null
            val id = content.optString(PENDING_MEDIA_RECORD_ID_CONTENT_KEY).takeIf(String::isNotBlank)
                ?: return@runCatching null
            UUID.fromString(id).toString().takeIf { it == id }
        }.getOrNull()
    }

    private fun pendingMediaContentExtra(record: PendingMediaRecord): String =
        JSONObject().put(PENDING_MEDIA_RECORD_ID_CONTENT_KEY, record.id).toString()

    private fun EventTimelineItem.attachmentIdentity(): PendingMediaIdentity? {
        val msgLike = (content as? TimelineItemContent.MsgLike)?.content ?: return null
        val message = (msgLike.kind as? MsgLikeKind.Message)?.content ?: return null
        return when (val type = message.msgType) {
            is MessageType.Image -> PendingMediaIdentity(
                type.content.filename,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                null,
            )
            is MessageType.Video -> PendingMediaIdentity(
                type.content.filename,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                type.content.info?.duration?.toMillis(),
            )
            is MessageType.Audio -> PendingMediaIdentity(
                type.content.filename,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                type.content.info?.duration?.toMillis(),
            )
            is MessageType.File -> PendingMediaIdentity(
                type.content.filename,
                type.content.info?.mimetype,
                type.content.info?.size?.toLong(),
                null,
            )
            else -> null
        }
    }

    private fun findPendingMediaRecord(
        roomId: String,
        identity: PendingMediaIdentity,
        pendingMediaRecordId: String?,
        transactionId: String?,
        eventId: String?,
        localCreatedAtMillis: Long?,
    ): PendingMediaRecord? = synchronized(pendingMediaLock) {
        val records = pendingMediaRecords.values.filter { it.roomId == roomId }
        val exactIdMatches = records.filter {
            (pendingMediaRecordId != null && it.id == pendingMediaRecordId) ||
                (transactionId != null && it.transactionId == transactionId) ||
                (eventId != null && it.eventId == eventId)
        }
        if (exactIdMatches.size == 1) return@synchronized exactIdMatches.single()
        if (exactIdMatches.isNotEmpty()) {
            pendingMediaRecoveryAmbiguousRecordIds.addAll(exactIdMatches.map(PendingMediaRecord::id))
            return@synchronized null
        }
        val matchingIdentity = records.filter { record ->
            record.fileName == identity.fileName &&
                record.mimeType.equals(identity.mimeType, ignoreCase = true) &&
                record.sizeBytes == identity.sizeBytes &&
                (record.audioDurationMillis == null || identity.durationMillis == null ||
                    kotlin.math.abs(record.audioDurationMillis - identity.durationMillis) <= VOICE_DURATION_MATCH_TOLERANCE_MS)
        }
        if (localCreatedAtMillis == null) {
            // A remote timestamp cannot disambiguate a historic same-name attachment from the
            // record being recovered. Preserve every plausible archive and fail closed.
            pendingMediaRecoveryAmbiguousRecordIds.addAll(matchingIdentity.map(PendingMediaRecord::id))
            return@synchronized null
        }
        val candidates = matchingIdentity.filter { record ->
            isPendingMediaLocalEchoInRecoveryWindow(
                recordCreatedAtMillis = record.createdAtMillis,
                localCreatedAtMillis = localCreatedAtMillis,
                maximumDelayMillis = MEDIA_EVENT_MATCH_WINDOW_MS,
            )
        }
        if (candidates.size > 1) {
            pendingMediaRecoveryAmbiguousRecordIds.addAll(candidates.map(PendingMediaRecord::id))
            return@synchronized null
        }
        candidates.singleOrNull()
    }

    private fun findPendingVoiceMediaRecord(
        roomId: String,
        staged: StagedAttachment,
        durationMillis: Long,
    ): PendingMediaRecord? {
        val retryIdentity = VoiceMediaDraftIdentity(
            roomId = roomId,
            fileName = staged.displayName,
            mimeType = staged.mimeType,
            sizeBytes = staged.file.length(),
            durationMillis = durationMillis,
        )
        val matches = synchronized(pendingMediaLock) {
            loadPendingMediaJournalLocked()
            pendingMediaRecords.values.filter { record ->
                val duration = record.audioDurationMillis ?: return@filter false
                VoiceMediaDraftIdentity(
                    roomId = record.roomId,
                    fileName = record.fileName,
                    mimeType = record.mimeType,
                    sizeBytes = record.sizeBytes,
                    durationMillis = duration,
                ).matchesRetry(retryIdentity)
            }
        }
        if (matches.size > 1) throw PendingVoiceNoteStillQueuedException()
        return matches.singleOrNull()
    }

    private suspend fun retryPendingVoiceMedia(record: PendingMediaRecord) {
        val action = voiceMediaSendAction(
            hasOutboxRecord = true,
            isSent = record.sent,
            transactionId = record.transactionId,
            hasRecoverableSendHandle = pendingMediaSendHandles.containsKey(record.id),
        )
        if (action == VoiceMediaSendAction.ALREADY_SENT) return
        if (action != VoiceMediaSendAction.RETRY_EXISTING) throw PendingVoiceNoteStillQueuedException()
        val handle = pendingMediaSendHandles.remove(record.id) ?: throw PendingVoiceNoteStillQueuedException()
        var returnedToPending = false
        try {
            withSendQueueGate {
                check(sendQueuesEnabled == true && syncServiceRunning && _connection.value == "Connected") {
                    "Reconnect before retrying this voice message"
                }
                handle.tryResend()
            }
            updatePendingMediaRecord(record.copy(inFlight = true))
        } catch (error: Throwable) {
            returnedToPending = pendingMediaSendHandles.putIfAbsent(record.id, handle) == null
            throw error
        } finally {
            if (!returnedToPending) handle.close()
        }
    }

    private fun markPendingMediaInFlight(id: String) {
        if (logoutInProgress) return
        updatePendingMediaRecord(id) { record -> record.copy(inFlight = true) }
    }

    private fun pendingMediaSourceFile(record: PendingMediaRecord): File {
        val safeId = UUID.fromString(record.id).toString()
        val safeName = safeFileName(record.fileName)
        val root = mediaOutboxCacheRoot.canonicalFile
        val result = File(File(root, safeId), safeName).canonicalFile
        check(result.path.startsWith(root.path + File.separator)) { "Invalid private media recovery path" }
        return result
    }

    private fun pendingMediaArchiveFile(id: String): File {
        val safeId = UUID.fromString(id).toString()
        val root = mediaOutboxRoot.canonicalFile
        return File(root, "$safeId.media").canonicalFile.also {
            check(it.path.startsWith(root.path + File.separator)) { "Invalid encrypted media recovery path" }
        }
    }

    private fun putPendingMediaRecord(record: PendingMediaRecord) = synchronized(pendingMediaLock) {
        check(!logoutInProgress) { "Secure sign-out is in progress" }
        loadPendingMediaJournalLocked()
        val updated = LinkedHashMap(pendingMediaRecords).apply { put(record.id, record) }
        savePendingMediaJournalLocked(updated)
        pendingMediaRecords.clear()
        pendingMediaRecords.putAll(updated)
    }

    private fun updatePendingMediaRecord(record: PendingMediaRecord) = synchronized(pendingMediaLock) {
        if (logoutInProgress) return@synchronized
        loadPendingMediaJournalLocked()
        if (record.id !in pendingMediaRecords) return@synchronized
        val updated = LinkedHashMap(pendingMediaRecords).apply { put(record.id, record) }
        savePendingMediaJournalLocked(updated)
        pendingMediaRecords.clear()
        pendingMediaRecords.putAll(updated)
    }

    private fun updatePendingMediaRecord(id: String, transform: (PendingMediaRecord) -> PendingMediaRecord) =
        synchronized(pendingMediaLock) {
            if (logoutInProgress) return@synchronized
            loadPendingMediaJournalLocked()
            val current = pendingMediaRecords[id] ?: return@synchronized
            val updatedRecord = transform(current)
            val updated = LinkedHashMap(pendingMediaRecords).apply { put(id, updatedRecord) }
            savePendingMediaJournalLocked(updated)
            pendingMediaRecords.clear()
            pendingMediaRecords.putAll(updated)
        }

    private fun removePendingMediaRecord(id: String) {
        val removed = synchronized(pendingMediaLock) {
            if (logoutInProgress) return@synchronized null
            loadPendingMediaJournalLocked()
            val record = pendingMediaRecords[id] ?: return@synchronized null
            val updated = LinkedHashMap(pendingMediaRecords).apply { remove(id) }
            savePendingMediaJournalLocked(updated)
            pendingMediaRecords.clear()
            pendingMediaRecords.putAll(updated)
            record
        } ?: return
        pendingMediaRecoveryAmbiguousRecordIds.remove(removed.id)
        cleanupRemovedPendingMediaRecord(removed)
    }

    private fun markPendingMediaSent(id: String, transactionId: String?, eventId: String?) {
        val result = synchronized(pendingMediaLock) {
            if (logoutInProgress) return@synchronized null
            loadPendingMediaJournalLocked()
            val record = pendingMediaRecords[id] ?: return@synchronized null
            val sentRecord = record.copy(
                transactionId = transactionId ?: record.transactionId,
                eventId = eventId ?: record.eventId,
                inFlight = false,
                sent = record.audioDurationMillis != null && !record.draftCleared,
            )
            val updated = LinkedHashMap(pendingMediaRecords)
            if (sentRecord.sent) updated[id] = sentRecord else updated.remove(id)
            savePendingMediaJournalLocked(updated)
            pendingMediaRecords.clear()
            pendingMediaRecords.putAll(updated)
            sentRecord
        } ?: return
        pendingMediaRecoveryAmbiguousRecordIds.remove(id)
        pendingMediaSendHandles.remove(id)?.close()
        deletePendingMediaFiles(result)
        cleanupPendingMediaObserverIfIdle(result.roomId)
    }

    private fun cleanupRemovedPendingMediaRecord(removed: PendingMediaRecord) {
        pendingMediaSendHandles.remove(removed.id)?.close()
        deletePendingMediaFiles(removed)
        cleanupPendingMediaObserverIfIdle(removed.roomId)
    }

    private fun deletePendingMediaFiles(record: PendingMediaRecord) {
        if (record.sourceVersion < DATA_UPLOAD_SOURCE_VERSION) {
            pendingMediaSourceFile(record).parentFile?.deleteRecursively()
        }
        pendingMediaArchiveFile(record.id).delete()
    }

    suspend fun markVoiceNoteDraftCleared(
        roomId: String,
        fileName: String,
        sizeBytes: Long,
        durationMillis: Long,
    ) = withContext(Dispatchers.IO) {
        val removed = synchronized(pendingMediaLock) {
            if (logoutInProgress) return@withContext
            loadPendingMediaJournalLocked()
            val matches = pendingMediaRecords.values.filter { record ->
                record.roomId == roomId &&
                    record.fileName == fileName &&
                    record.sizeBytes == sizeBytes &&
                    record.audioDurationMillis == durationMillis
            }
            if (matches.isEmpty()) return@withContext
            val updated = LinkedHashMap(pendingMediaRecords)
            val removedRecords = mutableListOf<PendingMediaRecord>()
            matches.forEach { record ->
                val current = pendingMediaRecords[record.id] ?: return@forEach
                if (current.sent) {
                    updated.remove(current.id)
                    removedRecords += current
                } else {
                    updated[current.id] = current.copy(draftCleared = true)
                }
            }
            savePendingMediaJournalLocked(updated)
            pendingMediaRecords.clear()
            pendingMediaRecords.putAll(updated)
            removedRecords
        }
        removed.forEach(::cleanupRemovedPendingMediaRecord)
    }

    private fun cleanupPendingMediaObserverIfIdle(roomId: String) {
        if (synchronized(pendingMediaLock) { pendingMediaRecords.values.none { it.roomId == roomId && !it.sent } }) {
            pendingMediaObservers.remove(roomId)?.let { observer ->
                observer.handle.cancel()
                observer.handle.close()
                observer.timeline.close()
            }
            pendingMediaReadyRoomIds.remove(roomId)
        }
    }

    private fun closePendingMediaObservers() {
        pendingMediaObservers.values.toList().forEach { observer ->
            runCatching { observer.handle.cancel() }
            runCatching { observer.handle.close() }
            runCatching { observer.timeline.close() }
        }
    private suspend fun joinedHumanDeliveryCandidates(room: Room): List<DeliveryRecipientCandidate> {
        val iterator = room.members()
        return try {
            buildList {
                while (true) {
                    val chunk = iterator.nextChunk(64u) ?: break
                    addAll(chunk.map { member ->
                        DeliveryRecipientCandidate(
                            userId = member.userId,
                            isJoined = member.membership is MembershipState.Join,
                            isServiceMember = member.isServiceMember,
                        )
                    })
                }
            }
        } finally {
            iterator.close()
        }
    }

        pendingMediaObservers.clear()
    }

    private fun cleanupUnreferencedMediaArchives(
        referencedArchiveIds: Set<String>,
        referencedLegacySourceIds: Set<String>,
    ) {
        mediaOutboxRoot.listFiles()?.filter { file ->
            file.extension == "media" && file.nameWithoutExtension !in referencedArchiveIds
        }?.forEach(File::delete)
        mediaOutboxCacheRoot.listFiles()?.filter { it.name !in referencedLegacySourceIds }?.forEach(File::deleteRecursively)
    }

    private fun loadPendingMediaJournal() = synchronized(pendingMediaLock) {
        loadPendingMediaJournalLocked()
    }

    private fun loadPendingMediaJournalLocked() {
        if (pendingMediaJournalLoaded) return
        pendingMediaRecords.clear()
        if (pendingMediaJournalFile.isFile) {
            val json = JSONObject(String(openLocalPayload("pending-media-journal", pendingMediaJournalFile.readBytes()), Charsets.UTF_8))
            val rows = json.optJSONArray("records") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val record = PendingMediaRecord(
                    id = UUID.fromString(row.getString("id")).toString(),
                    roomId = row.getString("roomId"),
                    fileName = safeFileName(row.getString("fileName")),
                    mimeType = row.getString("mimeType").take(120),
                    sizeBytes = row.getLong("sizeBytes"),
                    audioDurationMillis = row.optLong("audioDurationMillis").takeIf { it > 0 },
                    createdAtMillis = row.getLong("createdAtMillis"),
                    transactionId = row.optString("transactionId").takeIf(String::isNotBlank),
                    eventId = row.optString("eventId").takeIf(String::isNotBlank),
                    // Records written before UploadSource.Data depended on a stable plaintext
                    // path for SDK queue recovery. Treat an absent field as that legacy format.
                    sourceVersion = row.optInt("sourceVersion", LEGACY_FILE_UPLOAD_SOURCE_VERSION)
                        .coerceIn(LEGACY_FILE_UPLOAD_SOURCE_VERSION, DATA_UPLOAD_SOURCE_VERSION),
                    inFlight = row.optBoolean("inFlight", false),
                    sent = row.optBoolean("sent", false),
                    draftCleared = row.optBoolean("draftCleared", false),
                )
                pendingMediaRecords[record.id] = record
            }
        }
        pendingMediaJournalLoaded = true
    }

    private fun savePendingMediaJournalLocked(records: Map<String, PendingMediaRecord>) {
        mediaOutboxRoot.mkdirs()
        val rows = JSONArray()
        records.values.forEach { record ->
            rows.put(
                JSONObject()
                    .put("id", record.id)
                    .put("roomId", record.roomId)
                    .put("fileName", record.fileName)
                    .put("mimeType", record.mimeType)
                    .put("sizeBytes", record.sizeBytes)
                    .put("audioDurationMillis", record.audioDurationMillis ?: 0)
                    .put("createdAtMillis", record.createdAtMillis)
                    .put("transactionId", record.transactionId)
                    .put("eventId", record.eventId)
                    .put("sourceVersion", record.sourceVersion)
                    .put("inFlight", record.inFlight)
                    .put("sent", record.sent)
                    .put("draftCleared", record.draftCleared),
            )
        }
        writeEncryptedAtomically(pendingMediaJournalFile, "pending-media-journal", JSONObject().put("records", rows).toString().toByteArray(Charsets.UTF_8))
    }

    private fun loadDeliveryAckJournal() = synchronized(deliveryAckLock) {
        loadDeliveryAckJournalLocked()
    }

    private fun loadDeliveryAckJournalLocked() {
        if (deliveryAckJournalLoaded) return
        deliveryAckJournal.clear()
        pendingDeliverySnapshotsByTransaction.clear()
        if (deliveryAckJournalFile.isFile) {
            val json = JSONObject(String(openLocalPayload("delivery-ack-journal", deliveryAckJournalFile.readBytes()), Charsets.UTF_8))
            val rows = json.optJSONArray("records") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val record = DeliveryAckRecord(
                    roomId = row.getString("roomId"),
                    targetEventId = row.getString("targetEventId"),
                    state = row.getString("state"),
                    delivered = row.optBoolean("delivered", false),
                    retryAttempts = row.optInt("retryAttempts", 0).coerceIn(0, MAX_ACK_SEND_ATTEMPTS),
                    nextRetryAtMillis = row.optLong("nextRetryAtMillis", 0L).coerceAtLeast(0L),
                    // Records written before immutable recipient snapshots were introduced
                    // cannot safely be reconstructed from current room membership.
                    snapshotKnown = row.optBoolean("snapshotKnown", false),
                    transactionId = row.optString("transactionId").takeIf(String::isNotBlank),
                    provisionalAckUpdatedAtMillis = row.optLong("provisionalAckUpdatedAtMillis", 0L).coerceAtLeast(0L),
                    expectedMembers = row.optJSONArray("expectedMembers")?.let { array ->
                        buildSet {
                            for (memberIndex in 0 until array.length()) {
                                array.optString(memberIndex).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                            }
                        }
                    }.orEmpty(),
                    acknowledgedMembers = row.optJSONArray("acknowledgedMembers")?.let { array ->
                        buildSet {
                            for (memberIndex in 0 until array.length()) {
                                array.optString(memberIndex).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                            }
                        }
                    }.orEmpty(),
                    provisionalAcknowledgedMembers = row.optJSONArray("provisionalAcknowledgedMembers")?.let { array ->
                        buildSet {
                            for (memberIndex in 0 until array.length()) {
                                array.optString(memberIndex).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                            }
                        }
                    }.orEmpty(),
                )
                deliveryAckJournal[deliveryAckKey(record.roomId, record.targetEventId)] = record
            }
            val pendingRows = json.optJSONArray("pendingSnapshots") ?: JSONArray()
            for (index in 0 until pendingRows.length()) {
                val row = pendingRows.optJSONObject(index) ?: continue
                val record = PendingDeliverySnapshot(
                    roomId = row.getString("roomId"),
                    transactionId = row.getString("transactionId"),
                    expectedMembers = row.optJSONArray("expectedMembers")?.let { array ->
                        buildSet {
                            for (memberIndex in 0 until array.length()) {
                                array.optString(memberIndex).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                            }
                        }
                    }.orEmpty(),
                    snapshotKnown = row.optBoolean("snapshotKnown", false),
                    eventId = row.optString("eventId").takeIf(String::isNotBlank),
                )
                pendingDeliverySnapshotsByTransaction[deliveryAckKey(record.roomId, record.transactionId)] = record
            }
        }
        deliveryAckJournalLoaded = true
    }

    private fun saveDeliveryAckJournalLocked(
        updated: Map<String, DeliveryAckRecord>,
        updatedPending: Map<String, PendingDeliverySnapshot> = pendingDeliverySnapshotsByTransaction,
    ) {
        val rows = JSONArray()
        updated.values.forEach { record ->
            rows.put(
                JSONObject()
                    .put("roomId", record.roomId)
                    .put("targetEventId", record.targetEventId)
                    .put("state", record.state)
                    .put("delivered", record.delivered)
                    .put("retryAttempts", record.retryAttempts)
                    .put("nextRetryAtMillis", record.nextRetryAtMillis)
                    .put("snapshotKnown", record.snapshotKnown)
                    .put("transactionId", record.transactionId)
                    .put("provisionalAckUpdatedAtMillis", record.provisionalAckUpdatedAtMillis)
                    .put("expectedMembers", JSONArray().apply { record.expectedMembers.sorted().forEach { put(it) } })
                    .put("acknowledgedMembers", JSONArray().apply { record.acknowledgedMembers.sorted().forEach { put(it) } })
                    .put("provisionalAcknowledgedMembers", JSONArray().apply {
                        record.provisionalAcknowledgedMembers.sorted().forEach { put(it) }
                    }),
            )
        }
        val pendingRows = JSONArray()
        updatedPending.values.forEach { record ->
            pendingRows.put(
                JSONObject()
                    .put("roomId", record.roomId)
                    .put("transactionId", record.transactionId)
                    .put("eventId", record.eventId)
                    .put("snapshotKnown", record.snapshotKnown)
                    .put("expectedMembers", JSONArray().apply { record.expectedMembers.sorted().forEach { put(it) } }),
            )
        }
        writeEncryptedAtomically(
            deliveryAckJournalFile,
            "delivery-ack-journal",
            JSONObject().put("records", rows).put("pendingSnapshots", pendingRows).toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun writeEncryptedAtomically(destination: File, purpose: String, plaintext: ByteArray) {
        synchronized(vault) {
            check(!logoutInProgress) { "Secure sign-out is in progress" }
            destination.parentFile?.mkdirs()
            val encrypted = vault.encryptLocalData(purpose, plaintext)
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(encrypted)
                output.fd.sync()
            }
            moveAtomically(temporary, destination)
        }
    }

    private fun openLocalPayload(purpose: String, payload: ByteArray): ByteArray {
        return synchronized(vault) {
            check(!logoutInProgress) { "Secure sign-out is in progress" }
            vault.decryptLocalData(purpose, payload)
        }
    }

    private fun copyAtomically(source: File, destination: File) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileInputStream(source).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        moveAtomically(temporary, destination)
    }

    private fun moveAtomically(temporary: File, destination: File) {
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encryptFile(source: File, destination: File, purpose: String) = synchronized(vault) {
        check(!logoutInProgress) { "Secure sign-out is in progress" }
        vault.encryptLocalFile(purpose, source, destination)
    }

    private fun decryptFile(source: File, destination: File, purpose: String) = synchronized(vault) {
        check(!logoutInProgress) { "Secure sign-out is in progress" }
        vault.decryptLocalFile(purpose, source, destination)
    }

    private fun safeFileName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .take(100)
        .ifBlank { "attachment" }

    private fun MessageType.chatAttachment(): ChatAttachment? = when (this) {
        is MessageType.Image -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "image/*", AttachmentKind.IMAGE, content.info?.size?.toLong())
        is MessageType.Video -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "video/*", AttachmentKind.VIDEO, content.info?.size?.toLong())
        is MessageType.Audio -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "audio/*", AttachmentKind.AUDIO, content.info?.size?.toLong())
        is MessageType.File -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "application/octet-stream", AttachmentKind.FILE, content.info?.size?.toLong())
        else -> null
    }

    private fun MessageType.attachmentCaption(): String? = when (this) {
        is MessageType.Image -> content.caption
        is MessageType.Video -> content.caption
        is MessageType.Audio -> content.caption
        is MessageType.File -> content.caption
        else -> null
    }

    private fun MessageType.attachmentLabel(): String = when (this) {
        is MessageType.Image -> "Photo"
        is MessageType.Video -> "Video"
        is MessageType.Audio -> "Audio"
        is MessageType.File -> "File"
        else -> "Attachment"
    }

    private fun TimelineItemContent.readableBody(): String? = when (this) {
        is TimelineItemContent.MsgLike -> when (val kind = content.kind) {
            is MsgLikeKind.Message -> when (kind.content.msgType) {
                is MessageType.Image -> "Photo"
                is MessageType.Video -> "Video"
                is MessageType.Audio -> "Audio"
                is MessageType.File -> "File"
                else -> kind.content.body
            }
            MsgLikeKind.Redacted -> "Message removed"
            is MsgLikeKind.UnableToDecrypt -> "Unable to decrypt this message"
            else -> null
        }
        is TimelineItemContent.RoomMembership -> null
        is TimelineItemContent.ProfileChange -> null
        is TimelineItemContent.State -> null
        is TimelineItemContent.FailedToParseMessageLike -> "Unsupported message"
        is TimelineItemContent.FailedToParseState -> null
        is TimelineItemContent.CallInvite -> "Call invitation"
        is TimelineItemContent.RtcNotification -> "Call update"
    }

    private fun LatestEventValue.previewText(): String = when (this) {
        LatestEventValue.None -> "No messages yet"
        is LatestEventValue.Remote -> content.readableBody() ?: "Encrypted conversation"
        is LatestEventValue.Local -> content.readableBody() ?: "Encrypted conversation"
        is LatestEventValue.RemoteInvite -> "Invitation received"
    }

    private fun LatestEventValue.timestampMillis(): Long = when (this) {
        LatestEventValue.None -> 0L
        is LatestEventValue.Remote -> timestamp.toLong()
        is LatestEventValue.Local -> timestamp.toLong()
        is LatestEventValue.RemoteInvite -> timestamp.toLong()
    }

    private companion object {
        const val DELIVERY_ACK_MSGTYPE = "org.friendline.delivery"
        val VERIFICATION_TERMINAL_STATUSES = setOf(
            DeviceVerificationStatus.VERIFIED,
            DeviceVerificationStatus.CANCELLED,
            DeviceVerificationStatus.FAILED,
        )
        const val VERIFICATION_CONTROL_ROOM_NAME = "Device verification"
        const val VERIFICATION_CONTROL_ROOM_TOPIC = "org.friendline.verification-control.v1"
        const val VERIFICATION_ROOM_JOIN_TIMEOUT_MS = 120_000L
        const val VERIFICATION_ROOM_JOIN_POLL_MS = 500L
        const val VERIFICATION_ROOM_ROUTE_SYNC_TIMEOUT_MS = 30_000L
        const val VERIFICATION_ROOM_ROUTE_SYNC_POLL_MS = 500L
        const val VERIFICATION_CONTROL_ROOM_STATE_TIMEOUT_MS = 15_000L
        const val VERIFICATION_CONTROL_ROOM_STATE_POLL_MS = 250L
        const val ROOM_CREATE_ENCRYPTION_TIMEOUT_MS = 15_000L
        const val ROOM_CREATE_ENCRYPTION_POLL_MS = 250L
        const val ACK_NONE = "none"
        const val ACK_PENDING = "pending"
        const val ACK_ENQUEUING = "enqueuing"
        const val ACK_QUEUED = "queued"
        const val ACK_FAILED = "failed"
        const val ACK_SENT = "sent"
        const val MAX_ACK_SEND_ATTEMPTS = 3
        const val ACK_ECHO_RECONCILIATION_DELAY_MS = 1_000L
        const val ACK_RETRY_BASE_DELAY_MS = 2_000L
        const val MAX_MEDIA_BYTES = MAX_MEDIA_DOWNLOAD_BYTES
        const val LEGACY_FILE_UPLOAD_SOURCE_VERSION = 1
        const val DATA_UPLOAD_SOURCE_VERSION = 2
        const val PENDING_MEDIA_RECORD_ID_CONTENT_KEY = "org.friendline.pending_media_record_id"
        const val TEMP_MEDIA_FILE_TTL_MS = 30L * 60L * 1000L
        const val ATTACHMENT_SEND_DRAIN_TIMEOUT_MS = 60_000L
        const val MEDIA_EVENT_MATCH_WINDOW_MS = 5L * 60L * 1000L
        const val PENDING_MEDIA_SNAPSHOT_TIMEOUT_MS = 10_000L
        const val DELIVERY_SNAPSHOT_BIND_GRACE_MS = 100L
        const val PROVISIONAL_ACK_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_PROVISIONAL_ACK_TARGETS = 256
        const val MAX_PROVISIONAL_ACK_SENDERS_PER_TARGET = 32
        const val VERIFICATION_IDENTITY_WAIT_TIMEOUT_MS = 20_000L
        const val VERIFICATION_IDENTITY_POLL_MS = 1_000L
        const val PEER_TRUST_REFRESH_TIMEOUT_MS = 60_000L
        const val PEER_TRUST_REFRESH_POLL_MS = 1_000L
        const val VOICE_DURATION_MATCH_TOLERANCE_MS = 1_000L
        val mediaCleanupLock = Any()
        var mediaTempCleanedForProcess = false
    }

    private data class StagedAttachment(val file: File, val displayName: String, val mimeType: String)
    private data class DeliveryAckRecord(
        val roomId: String,
        val targetEventId: String,
        val state: String,
        val delivered: Boolean,
        val retryAttempts: Int = 0,
        val nextRetryAtMillis: Long = 0L,
        val expectedMembers: Set<String> = emptySet(),
        val acknowledgedMembers: Set<String> = emptySet(),
        val snapshotKnown: Boolean = false,
        val transactionId: String? = null,
        val provisionalAcknowledgedMembers: Set<String> = emptySet(),
        val provisionalAckUpdatedAtMillis: Long = 0L,
    )
    private data class PendingDeliverySnapshot(
        val roomId: String,
        val transactionId: String,
        val expectedMembers: Set<String>,
        val snapshotKnown: Boolean,
        val eventId: String? = null,
    )
    private data class DeliverySnapshotReservation(
        val roomId: String,
        val expectedMembers: Set<String>?,
        var abandoned: Boolean = false,
    )
    private data class PendingMediaRecord(
        val id: String,
        val roomId: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val audioDurationMillis: Long?,
        val createdAtMillis: Long,
        val transactionId: String?,
        val eventId: String?,
        val sourceVersion: Int = LEGACY_FILE_UPLOAD_SOURCE_VERSION,
        val inFlight: Boolean = false,
        val sent: Boolean = false,
        val draftCleared: Boolean = false,
    )
    private data class TrackedAttachmentSend(
        val id: String,
        val handle: SendAttachmentJoinHandle,
        val result: CompletableDeferred<Unit>,
        val finished: CompletableDeferred<Unit>,
    )
    private class AttachmentDrainTimeoutException : IllegalStateException(
        "An encrypted media send is still finishing. Sign-out was not completed.",
    )
    private data class PendingMediaIdentity(
        val fileName: String,
        val mimeType: String?,
        val sizeBytes: Long?,
        val durationMillis: Long?,
    )
    private data class PendingMediaObserver(
        val timeline: Timeline,
        val handle: TaskHandle,
        val listener: TimelineListener,
        val initialSnapshotReady: CompletableDeferred<Unit>,
    )
    private data class PendingMediaObserverReadiness(
        val readyRoomIds: Set<String>,
        val newlyReadyRoomIds: Set<String>,
    )
    private data class DeliveryAckObserver(
        val room: Room,
        val timeline: Timeline,
        val handle: TaskHandle,
        val listener: TimelineListener,
    )
}
