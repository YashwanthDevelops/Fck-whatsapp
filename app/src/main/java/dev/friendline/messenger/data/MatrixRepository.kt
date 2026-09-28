package dev.friendline.messenger.data

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import dev.friendline.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.ComposerDraft
import org.matrix.rustcomponents.sdk.ComposerDraftType
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaFileHandle
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.SearchService
import org.matrix.rustcomponents.sdk.SearchServicePaginationStateListener
import org.matrix.rustcomponents.sdk.SearchServiceResult
import org.matrix.rustcomponents.sdk.SearchServiceResultsListener
import org.matrix.rustcomponents.sdk.SearchServiceResultsUpdate
import org.matrix.rustcomponents.sdk.SendAttachmentJoinHandle
import org.matrix.rustcomponents.sdk.SendHandle
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

internal class MatrixLoginStageFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Sign-in could not complete during $stage", cause)

internal class MatrixSyncStartFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Sync could not start during $stage", cause)

internal class MatrixRoomCreateFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Encrypted conversation setup failed during $stage", cause)

internal class MatrixConversationOpenFailure(val stage: String, cause: Throwable) :
    IllegalStateException("Conversation setup failed during $stage", cause)

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
    private val _verification = MutableStateFlow<DeviceVerificationUiState?>(null)
    private val _peerTrust = MutableStateFlow(PeerTrustStatus.UNKNOWN)
    private val _searchResults = MutableStateFlow<List<MessageSearchHit>>(emptyList())
    private val _searchHasMore = MutableStateFlow(false)
    private val _searchLoading = MutableStateFlow(false)
    private val _readReceiptsEnabled = MutableStateFlow(vault.loadReadReceiptsEnabled())

    val conversations = _conversations.asStateFlow()
    val messages = _messages.asStateFlow()
    val composerDraft = _composerDraft.asStateFlow()
    val connection = _connection.asStateFlow()
    val typingUsers = _typingUsers.asStateFlow()
    val verification = _verification.asStateFlow()
    val peerTrust = _peerTrust.asStateFlow()
    val searchResults = _searchResults.asStateFlow()
    val searchHasMore = _searchHasMore.asStateFlow()
    val searchLoading = _searchLoading.asStateFlow()
    val readReceiptsEnabled = _readReceiptsEnabled.asStateFlow()

    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendQueueMutex = Mutex()
    private val verificationControllerMutex = Mutex()
    private val lifecycleMutex = Mutex()
    @Volatile private var sendQueuesEnabled: Boolean? = null
    @Volatile private var logoutInProgress = false
    @Volatile private var syncServiceRunning = false
    private val deliveryAckLock = Any()
    private val deliveryAckJournal = LinkedHashMap<String, DeliveryAckRecord>()
    @Volatile private var deliveryAckJournalLoaded = false
    private val failedAckSendHandles = ConcurrentHashMap<String, SendHandle>()
    private val observedAckTargetsByRoom = ConcurrentHashMap<String, MutableSet<String>>()
    private val ackReconciliationScheduled = ConcurrentHashMap.newKeySet<String>()
    private val ackRetryScheduled = ConcurrentHashMap.newKeySet<String>()
    private val expectedDeliveryMemberIdsByRoom = ConcurrentHashMap<String, Set<String>>()
    private val externalViewerFiles = ConcurrentHashMap.newKeySet<File>()
    private val pendingMediaLock = Any()
    private val pendingMediaRecords = LinkedHashMap<String, PendingMediaRecord>()
    @Volatile private var pendingMediaJournalLoaded = false
    private val pendingMediaSentWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val pendingMediaObservers = ConcurrentHashMap<String, PendingMediaObserver>()
    private val activeAttachmentSends = ConcurrentHashMap<String, TrackedAttachmentSend>()

    private var client: Client? = null
    private var searchService: SearchService? = null
    private var searchResultsHandle: TaskHandle? = null
    private var searchPaginationHandle: TaskHandle? = null
    private var searchResultsListener: SearchServiceResultsListener? = null
    private var searchPaginationListener: SearchServicePaginationStateListener? = null
    private var verificationController: SessionVerificationController? = null
    private var pendingVerificationRequest: Pair<String, String>? = null
    private var syncService: SyncService? = null
    private var syncStateHandle: TaskHandle? = null
    private var sendQueueStatusHandle: TaskHandle? = null
    private var typingListenerHandle: TaskHandle? = null
    @Volatile private var activeTimeline: Timeline? = null
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
            val session = vault.loadSession() ?: return@withLifecycleLock null
            val matrixClient = buildClient(session.homeserverUrl)
            matrixClient.restoreSession(session)
            matrixClient.encryption().waitForE2eeInitializationTasks()
            client = matrixClient
            ownUserId = session.userId
            startSync(matrixClient)
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
                val roomId = requireClient().createRoom(parameters)
                stage = "refresh-conversations"
                refreshConversations()
                stage = "verify-room-encryption"
                check(_conversations.value.firstOrNull { it.roomId == roomId }?.isEncrypted == true) {
                    "The homeserver did not return an encrypted room."
                }
                roomId
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw MatrixRoomCreateFailure(stage, failure)
            }
        }

    suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        if (logoutInProgress) return@withContext
        val matrixClient = client ?: return@withContext
        val rows = matrixClient.rooms().mapNotNull { room ->
            runCatching {
                val info = room.roomInfo()
                val latest = room.latestEvent()
                try {
                    ConversationSummary(
                        roomId = room.id(),
                        title = info.displayName?.takeIf { it.isNotBlank() }
                            ?: room.displayName()?.takeIf { it.isNotBlank() }
                            ?: "Private conversation",
                        preview = latest.previewText(),
                        lastActivityMillis = latest.timestampMillis(),
                        unreadCount = info.numUnreadMessages.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        isEncrypted = room.encryptionState().name == "ENCRYPTED",
                        isGroup = !info.isDirect,
                        membership = info.membership.name,
                    )
                } finally {
                    latest.destroy()
                    info.destroy()
                }
            }.getOrNull()
        }.sortedByDescending(ConversationSummary::lastActivityMillis)
        _conversations.value = rows
        ensurePendingMediaObserversForKnownRooms()
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
        if (enabled && !logoutInProgress) {
            activeTimeline?.let { timeline ->
                runCatching { withSendQueueGate { timeline.markAsRead(ReceiptType.READ) } }
            }
        }
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
            controller.requestUserVerification(peerUserId)
        } catch (error: Exception) {
            _verification.value = DeviceVerificationUiState(
                peerUserId = peerUserId,
                status = DeviceVerificationStatus.FAILED,
                error = error.message,
            )
            throw error
        }
    }

    suspend fun isPeerVerified(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val peerUserId = requireRoom(roomId).activeHumanMemberIds().firstOrNull { it != ownUserId }
            ?: return@withContext false
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

    suspend fun acceptVerificationRequest() = withContext(Dispatchers.IO) {
        val request = checkNotNull(pendingVerificationRequest) { "There is no pending verification request" }
        val controller = getVerificationController()
        controller.acknowledgeVerificationRequest(request.first, request.second)
        controller.acceptVerificationRequest()
        _verification.update { current -> current?.copy(status = DeviceVerificationStatus.COMPARING) }
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
        _verification.update { current -> current?.copy(status = DeviceVerificationStatus.CANCELLED) }
    }

    suspend fun approveVerification() = withContext(Dispatchers.IO) {
        getVerificationController().approveVerification()
        _verification.update { current -> current?.copy(status = DeviceVerificationStatus.CONFIRMING) }
    }

    fun dismissVerification() {
        _verification.value = null
    }

    suspend fun openConversation(roomId: String) = withContext(Dispatchers.IO) {
        var stage = "select-room"
        try {
            val room = requireClient().rooms().firstOrNull { it.id() == roomId }
                ?: throw IllegalArgumentException("This conversation is no longer available")
            stage = "read-encryption-state"
            check(room.encryptionState().name == "ENCRYPTED") {
                "This conversation is not end-to-end encrypted and cannot be opened"
            }

            stage = "release-previous-timeline"
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
            val timeline = room.timeline()
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
                            applyTimelineUpdate(update, acknowledgements)
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
                    if (!logoutInProgress && _readReceiptsEnabled.value && diff.isNotEmpty() && room.encryptionState().name == "ENCRYPTED") {
                        callbackScope.launch {
                            delay(250)
                            runCatching { withSendQueueGate { timeline.markAsRead(ReceiptType.READ) } }
                        }
                    }
                }
            })
            stage = "subscribe-typing"
            typingListenerHandle = room.subscribeToTypingNotifications(object : TypingNotificationsListener {
                override fun call(typingUserIds: List<String>) {
                    _typingUsers.value = typingUserIds.filterNot { it == ownUserId }
                }
            })
            stage = "mark-read"
            if (!logoutInProgress && _readReceiptsEnabled.value) {
                runCatching { withSendQueueGate { timeline.markAsRead(ReceiptType.READ) } }
            }
            stage = "load-draft"
            val savedDraft = room.loadComposerDraft(null)
            _composerDraft.value = savedDraft?.plainText.orEmpty()
            savedDraft?.destroy()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
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
                val sendHandle = if (replyToEventId == null) {
                    timeline.send(content)
                } else {
                    timeline.sendReply(content, replyToEventId)
                }
                sendHandle.destroy()
                runCatching { room.typingNotice(false) }
                room.clearComposerDraft(null)
                _composerDraft.value = ""
            }
        } finally {
            content.destroy()
        }
        refreshConversations()
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
            val maximumBytes = runCatching { client.getMaxMediaUploadSize().toLong() }
                .getOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_MEDIA_BYTES) ?: MAX_MEDIA_BYTES
            val staged = stageContentUri(contentUri, maximumBytes)
            var pendingMedia: PendingMediaRecord? = null
            var queueCallStarted = false
            val timeline = activeTimeline?.takeIf { activeRoomId == roomId } ?: room.timeline()
            val ownsTimeline = timeline !== activeTimeline
            try {
                pendingMedia = preparePendingMedia(roomId, staged, audioDurationMillis)
                ensurePendingMediaObserver(roomId)
                val confirmation = CompletableDeferred<Unit>()
                pendingMediaSentWaiters[pendingMedia.id] = confirmation
                markPendingMediaInFlight(pendingMedia.id)
                val uploadFile = pendingMediaSourceFile(pendingMedia)
                val parameters = UploadParameters(
                    source = UploadSource.File(uploadFile.absolutePath),
                    caption = null,
                    formattedCaption = null,
                    mentions = null,
                    inReplyTo = replyToEventId,
                )
                var joinedSuccessfully = false
                when {
                    staged.mimeType.startsWith("image/") -> {
                        val dimensions = BitmapFactory.Options().also { options ->
                            options.inJustDecodeBounds = true
                            BitmapFactory.decodeFile(uploadFile.absolutePath, options)
                        }
                        val info = ImageInfo(
                            height = dimensions.outHeight.takeIf { it > 0 }?.toULong(),
                            width = dimensions.outWidth.takeIf { it > 0 }?.toULong(),
                            mimetype = staged.mimeType,
                            size = uploadFile.length().toULong(),
                            thumbnailInfo = null,
                            thumbnailSource = null,
                            blurhash = null,
                            isAnimated = staged.mimeType == "image/gif",
                        )
                        val send = withSendQueueGate {
                            trackAttachmentSend(timeline.sendImage(parameters, null, info)).also {
                                queueCallStarted = true
                            }
                        }
                        try {
                            send.result.await()
                            joinedSuccessfully = true
                        } finally {
                            info.destroy()
                        }
                    }
                    staged.mimeType.startsWith("video/") -> {
                        val info = VideoInfo(
                            duration = null,
                            height = null,
                            width = null,
                            mimetype = staged.mimeType,
                            size = uploadFile.length().toULong(),
                            thumbnailInfo = null,
                            thumbnailSource = null,
                            blurhash = null,
                        )
                        val send = withSendQueueGate {
                            trackAttachmentSend(timeline.sendVideo(parameters, null, info)).also {
                                queueCallStarted = true
                            }
                        }
                        try {
                            send.result.await()
                            joinedSuccessfully = true
                        } finally {
                            info.destroy()
                        }
                    }
                    staged.mimeType.startsWith("audio/") -> {
                        val info = AudioInfo(
                            duration = audioDurationMillis?.takeIf { it > 0 }?.let(Duration::ofMillis),
                            size = uploadFile.length().toULong(),
                            mimetype = staged.mimeType,
                        )
                        val send = withSendQueueGate {
                            trackAttachmentSend(timeline.sendAudio(parameters, info)).also {
                                queueCallStarted = true
                            }
                        }
                        send.result.await()
                        joinedSuccessfully = true
                    }
                    else -> {
                        val info = FileInfo(
                            mimetype = staged.mimeType,
                            size = uploadFile.length().toULong(),
                            thumbnailInfo = null,
                            thumbnailSource = null,
                        )
                        val send = withSendQueueGate {
                            trackAttachmentSend(timeline.sendFile(parameters, info)).also {
                                queueCallStarted = true
                            }
                        }
                        try {
                            send.result.await()
                            joinedSuccessfully = true
                        } finally {
                            info.destroy()
                        }
                    }
                }
                if (joinedSuccessfully) {
                    // The join handle waits for the upload task, while this journal waits for
                    // the corresponding local echo to reach Sent before deleting its source.
                    withTimeoutOrNull(MEDIA_SENT_CONFIRMATION_TIMEOUT_MS) { confirmation.await() }
                }
                runCatching { withSendQueueGate { room.typingNotice(false) } }
                refreshConversations()
            } catch (error: Throwable) {
                if (!queueCallStarted) pendingMedia?.let { removePendingMediaRecord(it.id) }
                throw error
            } finally {
                pendingMedia?.let { pendingMediaSentWaiters.remove(it.id) }
                staged.file.parentFile?.deleteRecursively()
                if (ownsTimeline) timeline.close()
            }
        }

    suspend fun loadAttachmentForViewing(message: ChatMessage): File = withContext(Dispatchers.IO) {
        val attachment = checkNotNull(message.attachment) { "This message has no attachment" }
        val client = requireClient()
        val mediaSource = MediaSource.fromJson(attachment.sourceJson)
        var mediaFile: MediaFileHandle? = null
        try {
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
            viewerFile
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
        val client = requireClient()
        val mediaSource = MediaSource.fromJson(attachment.sourceJson)
        var mediaFile: MediaFileHandle? = null
        try {
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
            playbackFile
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

    suspend fun logout() = withContext(Dispatchers.IO) {
        withLifecycleLock {
            logoutInProgress = true
            val matrixClient = client
            val service = syncService
            val syncServiceWasRunning = syncServiceRunning
            val sendQueuesWereEnabled = sendQueuesEnabled
            var stopAttempted = false
            try {
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
                synchronized(vault) { vault.clearSession() }
            } catch (error: Throwable) {
                if (stopAttempted && service != null) {
                    runCatching {
                        service.start()
                        syncServiceRunning = syncServiceWasRunning
                        val restoreQueues = syncServiceWasRunning && (sendQueuesWereEnabled ?: true)
                        if (matrixClient != null) {
                            matrixClient.enableAllSendQueues(restoreQueues)
                            sendQueuesEnabled = restoreQueues
                        }
                    }.onFailure(error::addSuppressed)
                }
                logoutInProgress = false
                matrixClient?.let(::refreshSendQueueGate)
                throw error
            }

            // Queue disabling does not abort a native upload that is already in flight.
            try {
                closeSearchService()
                runCatching { client?.logout() }
                verificationController?.setDelegate(null)
                verificationController?.close()
                verificationController = null
                pendingVerificationRequest = null
                _verification.value = null
                activeRoomId = null
                val timeline = activeTimeline
                activeTimeline = null
                syncStateHandle?.cancel()
                syncStateHandle?.close()
                sendQueueStatusHandle?.cancel()
                sendQueueStatusHandle?.close()
                timelineListenerHandle?.cancel()
                timelineListenerHandle?.close()
                typingListenerHandle?.cancel()
                typingListenerHandle?.close()
                timeline?.close()
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
                synchronized(deliveryAckLock) { deliveryAckJournal.clear(); deliveryAckJournalLoaded = false }
                synchronized(pendingMediaLock) { pendingMediaRecords.clear(); pendingMediaJournalLoaded = false }
                closePendingMediaObservers()
                pendingMediaSentWaiters.values.forEach { it.cancel() }
                pendingMediaSentWaiters.clear()
                failedAckSendHandles.values.forEach { it.close() }
                failedAckSendHandles.clear()
                ackReconciliationScheduled.clear()
                ackRetryScheduled.clear()
                observedAckTargetsByRoom.clear()
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
        cleanupExternalViewerFiles()
        closeSearchService()
        verificationController?.setDelegate(null)
        verificationController?.close()
        verificationController = null
        pendingVerificationRequest = null
        _verification.value = null
        syncStateHandle?.cancel()
        syncStateHandle?.close()
        sendQueueStatusHandle?.cancel()
        sendQueueStatusHandle?.close()
        syncService?.stop()
        activeRoomId = null
        val timeline = activeTimeline
        activeTimeline = null
        timelineListenerHandle?.cancel()
        timelineListenerHandle?.close()
        typingListenerHandle?.cancel()
        typingListenerHandle?.close()
        timeline?.close()
        closePendingMediaObservers()
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
        var stage = "disable-send-queues"
        try {
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
                    if (!logoutInProgress && _readReceiptsEnabled.value && syncServiceRunning && activeRoomId != null) {
                        val timeline = activeTimeline
                        if (timeline != null) {
                            callbackScope.launch {
                                runCatching { withSendQueueGate { timeline.markAsRead(ReceiptType.READ) } }
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

    private fun applyTimelineUpdate(update: TimelineDiff, acknowledgements: MutableList<String>) {
        synchronized(timelineMessagesLock) {
            when (update) {
                is TimelineDiff.Append -> timelineMessages.addAll(update.values.map { messageFrom(it, acknowledgements) })
                TimelineDiff.Clear -> timelineMessages.clear()
                is TimelineDiff.PushFront -> timelineMessages.add(0, messageFrom(update.value, acknowledgements))
                is TimelineDiff.PushBack -> timelineMessages.add(messageFrom(update.value, acknowledgements))
                TimelineDiff.PopFront -> if (timelineMessages.isNotEmpty()) timelineMessages.removeAt(0)
                TimelineDiff.PopBack -> if (timelineMessages.isNotEmpty()) timelineMessages.removeAt(timelineMessages.lastIndex)
                is TimelineDiff.Insert -> {
                    val index = update.index.toInt().coerceIn(0, timelineMessages.size)
                    timelineMessages.add(index, messageFrom(update.value, acknowledgements))
                }
                is TimelineDiff.Set -> {
                    val index = update.index.toInt()
                    if (index in timelineMessages.indices) {
                        timelineMessages[index] = messageFrom(update.value, acknowledgements)
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
                    timelineMessages.addAll(update.values.map { messageFrom(it, acknowledgements) })
                }
            }
            publishTimelineMessagesLocked()
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

    private fun messageFrom(item: TimelineItem, acknowledgements: MutableList<String>): ChatMessage? {
        val event = item.asEvent() ?: return null
        return try {
            val msgLike = (event.content as? TimelineItemContent.MsgLike)?.content
            val messageContent = (msgLike?.kind as? MsgLikeKind.Message)?.content
            if (messageContent?.msgType is MessageType.Other) {
                val other = messageContent.msgType as MessageType.Other
                if (other.msgtype == DELIVERY_ACK_MSGTYPE) {
                    val acknowledgedId = messageContent.body.takeIf(String::isNotBlank) ?: return null
                    val roomId = activeRoomId
                    if (roomId != null) {
                        if (event.isOwn) {
                            recordOwnDeliveryAck(roomId, acknowledgedId, event.localSendState)
                            observedAckTargetsByRoom.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }
                                .add(acknowledgedId)
                            if ((event.localSendState as? EventSendState.SendingFailed)?.isRecoverable == true) {
                                rememberRecoverableAckSend(roomId, acknowledgedId, event)
                            }
                        } else {
                            recordReceivedDeliveryAck(roomId, acknowledgedId, event.sender)
                        }
                    }
                    refreshDeliveryStates()
                    return null
                }
            }
            val attachment = messageContent?.msgType?.chatAttachment()
            val body = if (attachment != null) {
                messageContent.msgType.attachmentCaption().orEmpty()
            } else {
                event.content.readableBody() ?: return null
            }
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
            val roomId = activeRoomId
            if (!event.isOwn && event.isRemote && eventId != null && roomId != null) {
                if (recordIncomingEventForAck(roomId, eventId)) acknowledgements += eventId
            }
            val sendState = event.localSendState
            val state = when (sendState) {
                is EventSendState.NotSentYet -> if (_connection.value == "Connected") "Sending" else "Queued"
                is EventSendState.SendingFailed -> if (sendState.isRecoverable) "Retry needed" else "Not sent"
                is EventSendState.Sent -> if (roomId != null && eventId != null) deliveryStateFor(roomId, eventId) else "Sent"
                null -> if (roomId != null && eventId != null) deliveryStateFor(roomId, eventId) else "Sent"
            }
            val replyTo = msgLike?.inReplyTo?.eventId()
            val reactions = msgLike?.reactions.orEmpty().map { reaction ->
                ReactionSummary(
                    key = reaction.key,
                    count = reaction.senders.size,
                    sentByMe = reaction.senders.any { sender -> sender.senderId == ownUserId },
                )
            }
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
                replyToEventId = replyTo,
                reactions = reactions,
                hasBeenRead = event.isOwn && event.readReceipts.keys.any { it != ownUserId },
                attachment = attachment,
            )
        } finally {
            event.destroy()
        }
    }

    private suspend fun sendDeliveryAcknowledgement(room: Room, eventId: String) {
        check(!logoutInProgress) { "Secure sign-out is in progress" }
        if (room.encryptionState().name != "ENCRYPTED") throw IllegalStateException("Delivery receipts require encryption")
        val timeline = room.timeline()
        val content = timeline.createMessageContent(
            MessageType.Other(DELIVERY_ACK_MSGTYPE, eventId),
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
            val expectedMembers = deliveryAckJournal[key]?.expectedMembers
                ?.takeIf { it.isNotEmpty() }
                ?: expectedDeliveryMemberIdsByRoom[roomId].orEmpty()
            if (acknowledgedBy == ownUserId || acknowledgedBy !in expectedMembers) return
            val current = deliveryAckJournal[key] ?: DeliveryAckRecord(roomId, targetId, ACK_NONE, delivered = false)
            val expected = current.expectedMembers.ifEmpty { expectedMembers }
            if (acknowledgedBy !in expected) return
            val acknowledgements = current.acknowledgedMembers + acknowledgedBy
            val isFullyDelivered = expected.isNotEmpty() && acknowledgements.containsAll(expected)
            val updated = LinkedHashMap(deliveryAckJournal).apply {
                put(
                    key,
                    current.copy(
                        delivered = current.delivered || isFullyDelivered,
                        expectedMembers = expected,
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
        val expectedMembers = record.expectedMembers.ifEmpty { expectedDeliveryMemberIdsByRoom[roomId].orEmpty() }
        return deliveryStatusLabel(
            expectedMemberIds = expectedMembers,
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
            // The FFI controller factory reads only the local crypto store. Querying the
            // homeserver first hydrates an existing cross-signing identity when available.
            val ownIdentity = matrixClient.encryption().userIdentity(currentUserId, true)
                ?: throw IllegalStateException(
                    "This account's secure verification identity is not available yet. Try again after sync completes.",
                )
            ownIdentity.close()

            val controller = matrixClient.getSessionVerificationController()
            verificationController = controller
            controller.setDelegate(object : SessionVerificationControllerDelegate {
            override fun didReceiveVerificationRequest(details: org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails) {
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
                _verification.update { current -> current?.copy(status = DeviceVerificationStatus.COMPARING) }
                callbackScope.launch {
                    runCatching { controller.startSasVerification() }
                        .onFailure { error ->
                            _verification.update { current ->
                                current?.copy(status = DeviceVerificationStatus.FAILED, error = error.message)
                            }
                        }
                }
            }

            override fun didStartSasVerification() {
                _verification.update { current -> current?.copy(status = DeviceVerificationStatus.COMPARING) }
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
                    current?.copy(status = DeviceVerificationStatus.COMPARING, sas = sas)
                }
            }

            override fun didFail() {
                _verification.update { current ->
                    current?.copy(status = DeviceVerificationStatus.FAILED, error = "Verification couldn't be completed.")
                }
            }

            override fun didCancel() {
                pendingVerificationRequest = null
                _verification.update { current -> current?.copy(status = DeviceVerificationStatus.CANCELLED) }
            }

            override fun didFinish() {
                pendingVerificationRequest = null
                _verification.update { current -> current?.copy(status = DeviceVerificationStatus.VERIFIED) }
                activeRoomId?.let { roomId ->
                    callbackScope.launch { runCatching { refreshPeerTrust(requireRoom(roomId)) } }
                }
            }
            })
            return controller
        } finally {
            verificationControllerMutex.unlock()
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
                            "This attachment exceeds the homeserver's ${maximumBytes / (1024 * 1024)} MB upload limit"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(file.length() > 0) { "The selected attachment is empty" }
            return StagedAttachment(file, safeName, mimeType)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Persist an encrypted recovery copy, plus the stable app-cache path Matrix queues reference. */
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
        )
        val destination = pendingMediaSourceFile(record)
        check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true) {
            "Couldn't prepare a private attachment recovery file"
        }
        val encryptedSource = pendingMediaArchiveFile(record.id)
        try {
            encryptFile(staged.file, encryptedSource, "pending-media:${record.id}")
            copyAtomically(staged.file, destination)
            putPendingMediaRecord(record)
            return record
        } catch (error: Throwable) {
            destination.parentFile?.deleteRecursively()
            encryptedSource.delete()
            throw error
        }
    }

    private suspend fun restorePendingMediaSources() {
        loadDeliveryAckJournal()
        loadPendingMediaJournal()
        val records = synchronized(pendingMediaLock) { pendingMediaRecords.values.toList() }
        records.forEach { record ->
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
        ensurePendingMediaObserversForKnownRooms()
        cleanupUnreferencedMediaArchives(records.mapTo(HashSet(), PendingMediaRecord::id))
    }

    private suspend fun ensurePendingMediaObserversForKnownRooms() {
        val roomIds = synchronized(pendingMediaLock) {
            pendingMediaRecords.values.map(PendingMediaRecord::roomId).distinct()
        }
        roomIds.forEach { roomId ->
            // A room may not be exposed by the SDK until its restored sync state is
            // available. Paths are restored before queue replay, so retry observer
            // setup on later conversation refreshes without blocking sync startup.
            runCatching { ensurePendingMediaObserver(roomId) }
        }
    }

    private suspend fun ensurePendingMediaObserver(roomId: String) {
        if (pendingMediaObservers.containsKey(roomId)) return
        val room = requireRoom(roomId)
        val timeline = room.timeline()
        val listener = object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                diff.forEach { update ->
                    try {
                        pendingMediaTimelineItems(update).forEach { item ->
                            reconcilePendingMediaEvent(roomId, item)
                        }
                    } finally {
                        update.destroy()
                    }
                }
            }
        }
        val handle = timeline.addListener(listener)
        val observer = PendingMediaObserver(timeline, handle, listener)
        val previous = pendingMediaObservers.putIfAbsent(roomId, observer)
        if (previous != null) {
            handle.cancel()
            handle.close()
            timeline.close()
        }
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

    private fun reconcilePendingMediaEvent(roomId: String, item: TimelineItem) {
        if (logoutInProgress) return
        val event = item.asEvent() ?: return
        try {
            if (!event.isOwn) return
            val identity = event.attachmentIdentity() ?: return
            val transactionId = (event.eventOrTransactionId as? EventOrTransactionId.TransactionId)?.transactionId
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
            val localCreatedAt = event.localCreatedAt?.toLong() ?: event.timestamp.toLong()
            val sendState = event.localSendState
            val isSent = sendState is EventSendState.Sent
            callbackScope.launch {
                if (logoutInProgress) return@launch
                val candidate = findPendingMediaRecord(
                    roomId = roomId,
                    identity = identity,
                    transactionId = transactionId,
                    eventId = eventId,
                    localCreatedAtMillis = localCreatedAt,
                ) ?: return@launch
                if (isSent) {
                    pendingMediaSentWaiters[candidate.id]?.complete(Unit)
                    removePendingMediaRecord(candidate.id)
                } else if (transactionId != null || eventId != null) {
                    updatePendingMediaRecord(candidate.copy(transactionId = transactionId, eventId = eventId))
                }
            }
        } finally {
            event.destroy()
        }
    }

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
        transactionId: String?,
        eventId: String?,
        localCreatedAtMillis: Long,
    ): PendingMediaRecord? = synchronized(pendingMediaLock) {
        val records = pendingMediaRecords.values.filter { it.roomId == roomId }
        val exactIdMatches = records.filter {
            (transactionId != null && it.transactionId == transactionId) ||
                (eventId != null && it.eventId == eventId)
        }
        if (exactIdMatches.size == 1) return@synchronized exactIdMatches.single()
        if (exactIdMatches.isNotEmpty()) return@synchronized null
        val candidates = records.filter { record ->
            record.fileName == identity.fileName &&
                record.mimeType.equals(identity.mimeType, ignoreCase = true) &&
                record.sizeBytes == identity.sizeBytes &&
                (record.audioDurationMillis == null || identity.durationMillis == null ||
                    kotlin.math.abs(record.audioDurationMillis - identity.durationMillis) <= VOICE_DURATION_MATCH_TOLERANCE_MS) &&
                localCreatedAtMillis >= record.createdAtMillis - MEDIA_EVENT_CLOCK_SKEW_MS
        }
        candidates.singleOrNull()
    }

    private fun markPendingMediaInFlight(id: String) {
        if (logoutInProgress) return
        val record = synchronized(pendingMediaLock) { pendingMediaRecords[id] } ?: return
        updatePendingMediaRecord(record.copy(inFlight = true))
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
        pendingMediaSourceFile(removed).parentFile?.deleteRecursively()
        pendingMediaArchiveFile(id).delete()
        pendingMediaSentWaiters.remove(id)?.complete(Unit)
        cleanupPendingMediaObserverIfIdle(removed.roomId)
    }

    private fun cleanupPendingMediaObserverIfIdle(roomId: String) {
        if (synchronized(pendingMediaLock) { pendingMediaRecords.values.none { it.roomId == roomId } }) {
            pendingMediaObservers.remove(roomId)?.let { observer ->
                observer.handle.cancel()
                observer.handle.close()
                observer.timeline.close()
            }
        }
    }

    private fun closePendingMediaObservers() {
        pendingMediaObservers.values.toList().forEach { observer ->
            runCatching { observer.handle.cancel() }
            runCatching { observer.handle.close() }
            runCatching { observer.timeline.close() }
        }
        pendingMediaObservers.clear()
    }

    private fun cleanupUnreferencedMediaArchives(referencedIds: Set<String>) {
        mediaOutboxRoot.listFiles()?.filter { file ->
            file.extension == "media" && file.nameWithoutExtension !in referencedIds
        }?.forEach(File::delete)
        mediaOutboxCacheRoot.listFiles()?.filter { it.name !in referencedIds }?.forEach(File::deleteRecursively)
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
                    inFlight = row.optBoolean("inFlight", false),
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
                    .put("inFlight", record.inFlight),
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
                )
                deliveryAckJournal[deliveryAckKey(record.roomId, record.targetEventId)] = record
            }
        }
        deliveryAckJournalLoaded = true
    }

    private fun saveDeliveryAckJournalLocked(updated: Map<String, DeliveryAckRecord>) {
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
                    .put("expectedMembers", JSONArray().apply { record.expectedMembers.sorted().forEach { put(it) } })
                    .put("acknowledgedMembers", JSONArray().apply { record.acknowledgedMembers.sorted().forEach { put(it) } }),
            )
        }
        writeEncryptedAtomically(deliveryAckJournalFile, "delivery-ack-journal", JSONObject().put("records", rows).toString().toByteArray(Charsets.UTF_8))
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
        is MessageType.Image -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "image/*", AttachmentKind.IMAGE)
        is MessageType.Video -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "video/*", AttachmentKind.VIDEO)
        is MessageType.Audio -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "audio/*", AttachmentKind.AUDIO)
        is MessageType.File -> ChatAttachment(content.source.toJson(), content.filename, content.info?.mimetype ?: "application/octet-stream", AttachmentKind.FILE)
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
        const val ACK_NONE = "none"
        const val ACK_PENDING = "pending"
        const val ACK_ENQUEUING = "enqueuing"
        const val ACK_QUEUED = "queued"
        const val ACK_FAILED = "failed"
        const val ACK_SENT = "sent"
        const val MAX_ACK_SEND_ATTEMPTS = 3
        const val ACK_ECHO_RECONCILIATION_DELAY_MS = 1_000L
        const val ACK_RETRY_BASE_DELAY_MS = 2_000L
        const val MAX_MEDIA_BYTES = 100L * 1024 * 1024
        const val TEMP_MEDIA_FILE_TTL_MS = 30L * 60L * 1000L
        const val MEDIA_SENT_CONFIRMATION_TIMEOUT_MS = 8_000L
        const val ATTACHMENT_SEND_DRAIN_TIMEOUT_MS = 60_000L
        const val MEDIA_EVENT_CLOCK_SKEW_MS = 30_000L
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
        val inFlight: Boolean = false,
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
    )
}
