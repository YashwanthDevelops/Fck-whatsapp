package dev.friendline.messenger.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import dev.friendline.messenger.data.ChatMessage
import dev.friendline.messenger.data.ConversationSummary
import dev.friendline.messenger.data.DeviceVerificationUiState
import dev.friendline.messenger.data.MessageSearchHit
import dev.friendline.messenger.data.MatrixRepository
import dev.friendline.messenger.data.PendingVoiceNoteStillQueuedException
import dev.friendline.messenger.data.PeerTrustStatus
import dev.friendline.messenger.push.PushRegistrationStatus
import dev.friendline.messenger.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class AudioPlaybackStatus { IDLE, LOADING, PLAYING, PAUSED }

data class AudioPlaybackUiState(
    val messageId: String? = null,
    val status: AudioPlaybackStatus = AudioPlaybackStatus.IDLE,
    val positionMillis: Int = 0,
    val durationMillis: Int = 0,
)

private const val MAX_VOICE_NOTE_DURATION_MILLIS = 5 * 60 * 1000
private const val MIN_VOICE_NOTE_DURATION_MILLIS = 500

data class MessengerUiState(
    // Keep emulator-only HTTP out of release builds; users enter their private HTTPS host.
    val homeserver: String = if (BuildConfig.DEBUG) "http://10.0.2.2:8008" else "",
    val username: String = "",
    val password: String = "",
    val userId: String? = null,
    val isBusy: Boolean = false,
    val isSendingAttachment: Boolean = false,
    val isRecordingVoiceNote: Boolean = false,
    val voiceRecordingStartedAtMillis: Long? = null,
    val voiceNoteFilePath: String? = null,
    val voiceNoteRoomId: String? = null,
    val voiceNoteDurationMillis: Int = 0,
    val isSendingVoiceNote: Boolean = false,
    val audioPlayback: AudioPlaybackUiState = AudioPlaybackUiState(),
    val connection: String = "Offline",
    val conversations: List<ConversationSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val typingUsers: List<String> = emptyList(),
    val composerDraft: String = "",
    val replyTarget: ChatMessage? = null,
    val messageSearchQuery: String = "",
    val searchResults: List<MessageSearchHit> = emptyList(),
    val searchHasMore: Boolean = false,
    val searchLoading: Boolean = false,
    val readReceiptsEnabled: Boolean = false,
    val pushNotificationsEnabled: Boolean = false,
    val pushRegistrationStatus: PushRegistrationStatus = PushRegistrationStatus.NOT_ENABLED,
    val verification: DeviceVerificationUiState? = null,
    val peerTrust: PeerTrustStatus = PeerTrustStatus.UNKNOWN,
    val currentRoomId: String? = null,
    val currentRoomTitle: String = "",
    val currentRoomEncrypted: Boolean = false,
    val currentRoomIsGroup: Boolean = false,
    val showNewConversation: Boolean = false,
    val error: String? = null,
)

class MessengerViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val repository = MatrixRepository(appContext)
    private val debugFailureDetails =
        context.applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val _state = MutableStateFlow(MessengerUiState())
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var draftJob: Job? = null
    private var typingStopJob: Job? = null
    private var searchJob: Job? = null
    private var attachmentSendJob: Job? = null
    private var lastTypingSentAt = 0L
    private var restored = false
    @Volatile private var logoutRequested = false
    private var voiceRecorder: MediaRecorder? = null
    private var voiceRecordingFile: File? = null
    private var voiceRecordingStartedAtElapsedMillis: Long? = null
    private var audioPlayer: MediaPlayer? = null
    private var audioPlaybackFile: File? = null
    private var audioLoadJob: Job? = null
    private var audioProgressJob: Job? = null
    private var audioPlaybackGeneration = 0L

    init {
        viewModelScope.launch {
            repository.conversations.collect { rows -> _state.update { it.copy(conversations = rows) } }
        }
        viewModelScope.launch {
            repository.messages.collect { rows -> _state.update { it.copy(messages = rows) } }
        }
        viewModelScope.launch {
            repository.typingUsers.collect { users -> _state.update { it.copy(typingUsers = users) } }
        }
        viewModelScope.launch {
            repository.composerDraft.collect { draft -> _state.update { it.copy(composerDraft = draft) } }
        }
        viewModelScope.launch {
            repository.connection.collect { value -> _state.update { it.copy(connection = value) } }
        }
        viewModelScope.launch {
            repository.verification.collect { value -> _state.update { it.copy(verification = value) } }
        }
        viewModelScope.launch {
            repository.peerTrust.collect { value -> _state.update { it.copy(peerTrust = value) } }
        }
        viewModelScope.launch {
            repository.searchResults.collect { value -> _state.update { it.copy(searchResults = value) } }
        }
        viewModelScope.launch {
            repository.searchHasMore.collect { value -> _state.update { it.copy(searchHasMore = value) } }
        }
        viewModelScope.launch {
            repository.searchLoading.collect { value -> _state.update { it.copy(searchLoading = value) } }
        }
        viewModelScope.launch {
            repository.readReceiptsEnabled.collect { value -> _state.update { it.copy(readReceiptsEnabled = value) } }
        }
        viewModelScope.launch {
            repository.pushNotificationsEnabled.collect { value -> _state.update { it.copy(pushNotificationsEnabled = value) } }
        }
        viewModelScope.launch {
            repository.pushRegistrationStatus.collect { value -> _state.update { it.copy(pushRegistrationStatus = value) } }
        }
    }

    fun restoreSessionIfPresent() {
        if (restored) return
        restored = true
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching { repository.restoreSession() }
                .onSuccess { userId ->
                    if (userId != null) {
                        _state.update { it.copy(userId = userId, isBusy = false) }
                        beginRoomRefresh()
                    } else {
                        _state.update { it.copy(isBusy = false) }
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(isBusy = false, error = "Your saved session could not be opened. Sign in again to continue.")
                    }
                }
        }
    }

    fun updateHomeserver(value: String) = _state.update { it.copy(homeserver = value, error = null) }
    fun updateUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun updatePassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun showNewConversation(show: Boolean) = _state.update { it.copy(showNewConversation = show, error = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun setPushNotificationsEnabled(enabled: Boolean) {
        if (logoutRequested) return
        viewModelScope.launch {
            runCatching { repository.setPushNotificationsEnabled(enabled) }
                .onFailure {
                    _state.update { state -> state.copy(pushRegistrationStatus = PushRegistrationStatus.FAILED) }
                }
        }
    }

    fun onPushPermissionResult(granted: Boolean) {
        if (granted) {
            refreshPushNotifications()
        } else {
            _state.update { it.copy(pushRegistrationStatus = PushRegistrationStatus.PERMISSION_REQUIRED) }
        }
    }

    fun refreshPushNotifications() {
        if (logoutRequested) return
        viewModelScope.launch {
            runCatching { repository.refreshPushNotifications() }
                .onFailure {
                    _state.update { state -> state.copy(pushRegistrationStatus = PushRegistrationStatus.FAILED) }
                }
        }
    }

    fun retryPushNotifications() = refreshPushNotifications()

    fun signIn() {
        val snapshot = _state.value
        if (snapshot.username.isBlank() || snapshot.password.isBlank() || snapshot.homeserver.isBlank()) {
            _state.update { it.copy(error = "Enter your homeserver, Matrix ID, and password.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching { repository.login(snapshot.homeserver, snapshot.username, snapshot.password) }
                .onSuccess { userId ->
                    _state.update { it.copy(userId = userId, password = "", isBusy = false) }
                    beginRoomRefresh()
                }
                .onFailure { error ->
                    val safeMessage = when (error) {
                        is IllegalArgumentException -> error.message ?: "Check the homeserver address."
                        else -> if (debugFailureDetails) {
                            "Sign-in failed (${error.javaClass.simpleName}): ${error.message.orEmpty().take(220)}"
                        } else {
                            "Couldn't sign in. Check the homeserver, Matrix ID, and password, then try again."
                        }
                    }
                    _state.update { it.copy(isBusy = false, error = safeMessage) }
                }
        }
    }

    fun createConversation(invitedIdsText: String, name: String, isGroup: Boolean) {
        val invitees = invitedIdsText
            .split(',', '\n', ';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val invalid = invitees.firstOrNull { !it.matches(Regex("^@[^:\\s]+:[^\\s]+$")) }
        if (invalid != null) {
            _state.update { it.copy(error = "Enter complete Matrix IDs, such as @alex:example.org.") }
            return
        }
        if (isGroup && invitees.size < 2) {
            _state.update { it.copy(error = "Enter at least two distinct Matrix IDs to create a group.") }
            return
        }
        if (!isGroup && invitees.size != 1) {
            _state.update { it.copy(error = "Enter exactly one Matrix ID for a one-to-one conversation.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching { repository.createEncryptedConversation(invitees, name, isGroup) }
                .onSuccess { roomId ->
                    _state.update { it.copy(isBusy = false, showNewConversation = false) }
                    beginRoomRefresh()
                    openConversation(roomId)
                }
                .onFailure {
                    _state.update { it.copy(isBusy = false, error = "Couldn't create the encrypted conversation. Check the invited Matrix IDs and try again.") }
                }
        }
    }

    fun openConversation(roomId: String) {
        val beforeOpen = _state.value
        if (beforeOpen.currentRoomId != null && beforeOpen.currentRoomId != roomId &&
            !beforeOpen.isSendingVoiceNote &&
            voiceNoteDraftCanBeSentTo(beforeOpen.voiceNoteRoomId, beforeOpen.currentRoomId)
        ) {
            discardVoiceNote()
        }
        if (beforeOpen.currentRoomId != null && beforeOpen.currentRoomId != roomId) {
            stopAudioPlayback()
        }
        val conversation = repository.conversations.value.firstOrNull { it.roomId == roomId }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    error = if (it.voiceNoteFilePath != null &&
                        !voiceNoteDraftCanBeSentTo(it.voiceNoteRoomId, roomId)
                    ) {
                        "This voice recording belongs to another conversation. Return there or discard it before sending."
                    } else {
                        null
                    },
                    currentRoomId = roomId,
                    currentRoomTitle = conversation?.title ?: "Private conversation",
                    currentRoomEncrypted = conversation?.isEncrypted == true,
                    currentRoomIsGroup = conversation?.isGroup == true,
                )
            }
            runCatching {
                if (conversation?.membership == "INVITED") repository.joinConversation(roomId)
                repository.openConversation(roomId)
            }
                .onSuccess {
                    _state.update { it.copy(isBusy = false, composerDraft = repository.composerDraft.value) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            currentRoomId = null,
                            currentRoomIsGroup = false,
                            error = "Couldn't open this conversation. Try again after syncing.",
                        )
                    }
                }
        }
    }

    fun joinVerificationChannel(roomId: String) {
        if (logoutRequested || _state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                repository.joinVerificationControlRoom(roomId)
                repository.refreshConversations()
            }
                .onSuccess {
                    _state.update { it.copy(isBusy = false, error = null) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = "Couldn't join this verification channel. Confirm it's from someone in an existing encrypted conversation, then sync and try again.",
                        )
                    }
                }
        }
    }

    fun closeConversation() {
        val snapshot = _state.value
        val roomId = snapshot.currentRoomId
        val currentDraft = snapshot.composerDraft
        if (!snapshot.isSendingVoiceNote) discardVoiceNote()
        stopAudioPlayback()
        draftJob?.cancel()
        viewModelScope.launch {
            if (roomId != null) runCatching { repository.saveComposerDraft(roomId, currentDraft) }
            repository.closeConversation()
            _state.update {
                it.copy(
                    currentRoomId = null,
                    currentRoomTitle = "",
                    currentRoomEncrypted = false,
                    currentRoomIsGroup = false,
                    peerTrust = PeerTrustStatus.UNKNOWN,
                    composerDraft = "",
                    replyTarget = null,
                    messageSearchQuery = "",
                    typingUsers = emptyList(),
                    error = if (it.voiceNoteFilePath != null &&
                        !voiceNoteDraftCanBeSentTo(it.voiceNoteRoomId, roomId)
                    ) {
                        "This voice recording belongs to another conversation. Return there or discard it before sending."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun updateComposerDraft(body: String) {
        if (logoutRequested) return
        _state.update { it.copy(composerDraft = body) }
        val roomId = _state.value.currentRoomId ?: return
        typingStopJob?.cancel()
        if (body.isBlank()) {
            viewModelScope.launch { runCatching { repository.sendTypingNotice(roomId, false) } }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastTypingSentAt >= 2_500) {
                lastTypingSentAt = now
                viewModelScope.launch { runCatching { repository.sendTypingNotice(roomId, true) } }
            }
            typingStopJob = viewModelScope.launch {
                delay(4_000)
                runCatching { repository.sendTypingNotice(roomId, false) }
            }
        }
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(400)
            runCatching { repository.saveComposerDraft(roomId, body) }
        }
    }

    fun replyTo(message: ChatMessage) {
        if (!message.canReply || message.eventId == null) return
        _state.update { it.copy(replyTarget = message, error = null) }
    }

    fun cancelReply() = _state.update { it.copy(replyTarget = null) }

    fun updateMessageSearchQuery(value: String) {
        _state.update { it.copy(messageSearchQuery = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            runCatching { repository.searchMessages(value) }
                .onFailure { _state.update { it.copy(error = "Couldn't search your encrypted message index.") } }
        }
    }

    fun paginateMessageSearch() {
        viewModelScope.launch { runCatching { repository.paginateMessageSearch() } }
    }

    fun setReadReceiptsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setReadReceiptsEnabled(enabled) }
                .onFailure { _state.update { it.copy(error = "Couldn't save your privacy setting.") } }
        }
    }

    fun markMessageAsVisible(eventId: String, timestampMillis: Long) {
        val current = _state.value
        val roomId = current.currentRoomId ?: return
        if (!current.currentRoomEncrypted || !current.readReceiptsEnabled) return
        viewModelScope.launch {
            runCatching { repository.markMessageAsRead(roomId, eventId, timestampMillis) }
        }
    }

    fun openSearchHit(hit: MessageSearchHit) {
        updateMessageSearchQuery("")
        openConversation(hit.roomId)
    }

    fun verifyConversationPeer() {
        val roomId = _state.value.currentRoomId ?: return
        viewModelScope.launch {
            runCatching { repository.requestPeerVerification(roomId) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Couldn't start device verification.") }
                }
        }
    }

    fun acceptVerificationRequest() {
        viewModelScope.launch {
            runCatching { repository.acceptVerificationRequest() }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Couldn't accept verification.") } }
        }
    }

    fun declineVerificationRequest() {
        viewModelScope.launch { runCatching { repository.declineVerificationRequest() } }
    }

    fun approveVerification() {
        viewModelScope.launch {
            runCatching { repository.approveVerification() }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Couldn't approve verification.") } }
        }
    }

    fun dismissVerification() = repository.dismissVerification()

    fun toggleReaction(message: ChatMessage, key: String) {
        if (logoutRequested) return
        val roomId = _state.value.currentRoomId ?: return
        viewModelScope.launch {
            runCatching { repository.toggleReaction(roomId, message, key) }
                .onFailure { _state.update { it.copy(error = "Couldn't send that encrypted reaction. Try again after syncing.") } }
        }
    }

    fun editMessage(message: ChatMessage, newBody: String) {
        if (logoutRequested) return
        val snapshot = _state.value
        val roomId = snapshot.currentRoomId ?: return
        if (!snapshot.currentRoomEncrypted || !message.isOwn || !message.canEdit) return
        viewModelScope.launch {
            runCatching { repository.editMessage(roomId, message, newBody) }
                .onFailure { _state.update { it.copy(error = "Couldn't edit this encrypted message. Try again after syncing.") } }
        }
    }

    fun redactMessage(message: ChatMessage) {
        if (logoutRequested) return
        val snapshot = _state.value
        val roomId = snapshot.currentRoomId ?: return
        if (!snapshot.currentRoomEncrypted || !message.isOwn || !message.canRedact) return
        viewModelScope.launch {
            runCatching { repository.redactMessage(roomId, message) }
                .onFailure { _state.update { it.copy(error = "Couldn't remove this encrypted message. Try again after syncing.") } }
        }
    }

    fun sendText(body: String) {
        if (logoutRequested) return
        val roomId = _state.value.currentRoomId ?: return
        if (body.isBlank()) return
        draftJob?.cancel()
        typingStopJob?.cancel()
        _state.update { it.copy(composerDraft = "", error = null) }
        val replyTo = _state.value.replyTarget?.eventId
        _state.update { it.copy(replyTarget = null) }
        viewModelScope.launch {
            runCatching { repository.sendText(roomId, body, replyTo) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            composerDraft = it.composerDraft.ifBlank { body },
                            error = error.message ?: "Couldn't send this message.",
                        )
                    }
                }
        }
    }

    fun sendAttachment(contentUri: String) {
        if (logoutRequested) return
        val snapshot = _state.value
        val roomId = snapshot.currentRoomId ?: return
        if (!snapshot.currentRoomEncrypted || snapshot.isSendingAttachment) return
        val replyTo = snapshot.replyTarget?.eventId
        attachmentSendJob = viewModelScope.launch {
            _state.update { it.copy(isSendingAttachment = true, error = null) }
            runCatching { repository.sendAttachment(roomId, contentUri, replyTo) }
                .onSuccess { _state.update { it.copy(replyTarget = null) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.message ?: "Couldn't send this encrypted attachment. Check your connection and try again.")
                    }
                }
            _state.update { it.copy(isSendingAttachment = false) }
        }
    }

    fun startVoiceRecording() {
        if (logoutRequested) return
        val snapshot = _state.value
        if (snapshot.currentRoomId == null || !snapshot.currentRoomEncrypted || snapshot.isRecordingVoiceNote || snapshot.voiceNoteFilePath != null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(error = "Allow microphone access in Android Settings to record a voice message.") }
            return
        }

        val file = runCatching { repository.createVoiceNoteRecordingFile() }.getOrElse {
            _state.update { state -> state.copy(error = "Couldn't prepare a private voice-message recording.") }
            return
        }
        val recorder = try {
            newMediaRecorder()
        } catch (_: Exception) {
            repository.deleteTemporaryMediaFile(file)
            _state.update { it.copy(error = "Couldn't start recording. Check microphone access and try again.") }
            return
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setMaxDuration(MAX_VOICE_NOTE_DURATION_MILLIS)
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopVoiceRecording()
            }
            recorder.prepare()
            recorder.start()
            voiceRecorder = recorder
            voiceRecordingFile = file
            voiceRecordingStartedAtElapsedMillis = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    error = null,
                    isRecordingVoiceNote = true,
                    voiceRecordingStartedAtMillis = SystemClock.elapsedRealtime(),
                    voiceNoteRoomId = snapshot.currentRoomId,
                    voiceNoteDurationMillis = 0,
                )
            }
        } catch (_: Exception) {
            runCatching { recorder.release() }
            repository.deleteTemporaryMediaFile(file)
            _state.update {
                it.copy(
                    isRecordingVoiceNote = false,
                    voiceRecordingStartedAtMillis = null,
                    voiceNoteRoomId = null,
                    error = "Couldn't start recording. Check microphone access and try again.",
                )
            }
        }
    }

    fun microphonePermissionDenied() {
        _state.update { it.copy(error = "Microphone access was denied. Allow it in Android Settings to record a voice message.") }
    }

    fun stopVoiceRecording() {
        val recorder = voiceRecorder ?: return
        val file = voiceRecordingFile
        val startedAt = voiceRecordingStartedAtElapsedMillis
        voiceRecorder = null
        voiceRecordingFile = null
        voiceRecordingStartedAtElapsedMillis = null
        val stopped = runCatching { recorder.stop() }.isSuccess
        runCatching { recorder.release() }
        val duration = startedAt?.let { (SystemClock.elapsedRealtime() - it).toInt() } ?: 0
        if (!stopped || duration < MIN_VOICE_NOTE_DURATION_MILLIS || file == null || !file.isFile || file.length() == 0L) {
            file?.let(repository::deleteTemporaryMediaFile)
            _state.update {
                it.copy(
                    isRecordingVoiceNote = false,
                    voiceRecordingStartedAtMillis = null,
                    voiceNoteFilePath = null,
                    voiceNoteRoomId = null,
                    voiceNoteDurationMillis = 0,
                    error = "Hold record for a moment to make a voice message, or cancel to discard it.",
                )
            }
            return
        }
        _state.update {
            it.copy(
                isRecordingVoiceNote = false,
                voiceRecordingStartedAtMillis = null,
                voiceNoteFilePath = file.absolutePath,
                voiceNoteDurationMillis = duration,
                error = null,
            )
        }
    }

    fun discardVoiceNote() {
        val snapshot = _state.value
        val recorder = voiceRecorder
        val recordingFile = voiceRecordingFile
        voiceRecorder = null
        voiceRecordingFile = null
        voiceRecordingStartedAtElapsedMillis = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        recordingFile?.let(repository::deleteTemporaryMediaFile)
        snapshot.voiceNoteFilePath?.let { path ->
            val file = File(path)
            val roomId = snapshot.voiceNoteRoomId ?: snapshot.currentRoomId
            if (roomId != null) {
                val sizeBytes = file.length()
                val durationMillis = snapshot.voiceNoteDurationMillis.toLong()
                viewModelScope.launch {
                    runCatching {
                        repository.markVoiceNoteDraftCleared(roomId, file.name, sizeBytes, durationMillis)
                    }
                }
            }
            repository.deleteTemporaryMediaFile(file)
        }
        _state.update {
            it.copy(
                isRecordingVoiceNote = false,
                voiceRecordingStartedAtMillis = null,
                voiceNoteFilePath = null,
                voiceNoteRoomId = null,
                voiceNoteDurationMillis = 0,
            )
        }
    }

    fun sendVoiceNote() {
        if (logoutRequested) return
        val snapshot = _state.value
        val roomId = snapshot.currentRoomId ?: return
        if (!snapshot.currentRoomEncrypted || snapshot.isSendingAttachment || snapshot.isSendingVoiceNote) return
        val path = snapshot.voiceNoteFilePath ?: return
        if (!voiceNoteDraftCanBeSentTo(snapshot.voiceNoteRoomId, roomId)) {
            _state.update {
                it.copy(error = "This voice recording belongs to another conversation. Return there or discard it before sending.")
            }
            return
        }
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            repository.deleteTemporaryMediaFile(file)
            _state.update {
                it.copy(
                    voiceNoteFilePath = null,
                    voiceNoteRoomId = null,
                    voiceNoteDurationMillis = 0,
                    error = "The voice recording expired. Record it again to send.",
                )
            }
            return
        }
        val replyTo = snapshot.replyTarget?.eventId
        attachmentSendJob = viewModelScope.launch {
            _state.update { it.copy(isSendingAttachment = true, isSendingVoiceNote = true, error = null) }
            val result = runCatching {
                val contentUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
                repository.sendAttachment(roomId, contentUri.toString(), replyTo, snapshot.voiceNoteDurationMillis.toLong())
            }
            result.onSuccess {
                runCatching {
                    repository.markVoiceNoteDraftCleared(
                        roomId = roomId,
                        fileName = file.name,
                        sizeBytes = file.length(),
                        durationMillis = snapshot.voiceNoteDurationMillis.toLong(),
                    )
                }
                repository.deleteTemporaryMediaFile(file)
                _state.update {
                    it.copy(
                        voiceNoteFilePath = null,
                        voiceNoteRoomId = null,
                        voiceNoteDurationMillis = 0,
                        replyTarget = null,
                        error = null,
                    )
                }
            }.onFailure { error ->
                val message = if (error is PendingVoiceNoteStillQueuedException) {
                    "This voice message is already queued. Wait for its status to change before trying again."
                } else {
                    "Couldn't send this encrypted voice message. Check your connection and try again."
                }
                _state.update {
                    it.copy(error = message)
                }
            }
            _state.update { it.copy(isSendingAttachment = false, isSendingVoiceNote = false) }
        }
    }

    fun toggleAudioPlayback(message: ChatMessage) {
        val playback = _state.value.audioPlayback
        if (playback.messageId == message.id) {
            when (playback.status) {
                AudioPlaybackStatus.LOADING -> stopAudioPlayback()
                AudioPlaybackStatus.PLAYING -> {
                    runCatching { audioPlayer?.pause() }
                    audioProgressJob?.cancel()
                    _state.update { it.copy(audioPlayback = playback.copy(status = AudioPlaybackStatus.PAUSED)) }
                }
                AudioPlaybackStatus.PAUSED -> {
                    runCatching { audioPlayer?.start() }
                    _state.update { it.copy(audioPlayback = playback.copy(status = AudioPlaybackStatus.PLAYING)) }
                    startAudioProgress(audioPlaybackGeneration, message.id)
                }
                AudioPlaybackStatus.IDLE -> startAudioPlayback(message)
            }
        } else {
            startAudioPlayback(message)
        }
    }

    private fun startAudioPlayback(message: ChatMessage) {
        stopAudioPlayback()
        val generation = audioPlaybackGeneration
        _state.update {
            it.copy(audioPlayback = AudioPlaybackUiState(messageId = message.id, status = AudioPlaybackStatus.LOADING), error = null)
        }
        audioLoadJob = viewModelScope.launch {
            var file: File? = null
            try {
                file = repository.loadAudioForPlayback(message)
                if (generation != audioPlaybackGeneration || !isActive) {
                    repository.deleteTemporaryMediaFile(file)
                    return@launch
                }
                val player = MediaPlayer()
                audioPlayer = player
                audioPlaybackFile = file
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { readyPlayer ->
                    if (generation != audioPlaybackGeneration) {
                        runCatching { readyPlayer.release() }
                    } else {
                        readyPlayer.start()
                        _state.update {
                            it.copy(
                                audioPlayback = AudioPlaybackUiState(
                                    messageId = message.id,
                                    status = AudioPlaybackStatus.PLAYING,
                                    durationMillis = readyPlayer.duration,
                                ),
                            )
                        }
                        startAudioProgress(generation, message.id)
                    }
                }
                player.setOnCompletionListener {
                    if (generation == audioPlaybackGeneration) stopAudioPlayback()
                }
                player.setOnErrorListener { _, _, _ ->
                    if (generation == audioPlaybackGeneration) {
                        stopAudioPlayback()
                        _state.update { it.copy(error = "Couldn't play this encrypted audio message.") }
                    }
                    true
                }
                player.prepareAsync()
            } catch (_: Exception) {
                file?.let(repository::deleteTemporaryMediaFile)
                if (generation == audioPlaybackGeneration) {
                    stopAudioPlayback()
                    _state.update { it.copy(error = "Couldn't play this encrypted audio message.") }
                }
            }
        }
    }

    private fun startAudioProgress(generation: Long, messageId: String) {
        audioProgressJob?.cancel()
        audioProgressJob = viewModelScope.launch {
            while (isActive && generation == audioPlaybackGeneration) {
                val player = audioPlayer ?: break
                _state.update { state ->
                    if (state.audioPlayback.messageId == messageId && state.audioPlayback.status == AudioPlaybackStatus.PLAYING) {
                        state.copy(audioPlayback = state.audioPlayback.copy(positionMillis = runCatching { player.currentPosition }.getOrDefault(0)))
                    } else state
                }
                delay(300)
            }
        }
    }

    private fun stopAudioPlayback() {
        audioPlaybackGeneration += 1
        audioLoadJob?.cancel()
        audioLoadJob = null
        audioProgressJob?.cancel()
        audioProgressJob = null
        runCatching { audioPlayer?.release() }
        audioPlayer = null
        audioPlaybackFile?.let(repository::deleteTemporaryMediaFile)
        audioPlaybackFile = null
        _state.update { it.copy(audioPlayback = AudioPlaybackUiState()) }
    }

    fun onAppStopped() {
        if (!_state.value.isSendingVoiceNote) discardVoiceNote()
        stopAudioPlayback()
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()

    fun openAttachment(message: ChatMessage) {
        val attachment = message.attachment ?: return
        viewModelScope.launch {
            runCatching {
                val file = repository.loadAttachmentForViewing(message)
                val contentUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.files",
                    file,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, attachment.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(Intent.createChooser(intent, "Open attachment"))
            }.onFailure {
                _state.update { state ->
                    state.copy(error = "Couldn't open this encrypted attachment. Check the connection and try again.")
                }
            }
        }
    }

    fun onReturnedToAppFromExternalViewer() {
        repository.cleanupExternalViewerFiles()
        refreshPushNotifications()
    }

    fun retryFailedMessages() {
        if (logoutRequested) return
        val roomId = _state.value.currentRoomId ?: return
        viewModelScope.launch {
            runCatching { repository.retryFailedMessages(roomId) }
                .onFailure { _state.update { it.copy(error = "Couldn't retry yet. Check the connection and try again.") } }
        }
    }

    fun logout() {
        if (logoutRequested) return
        logoutRequested = true
        stopAudioPlayback()
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            attachmentSendJob?.cancelAndJoin()
            attachmentSendJob = null
            discardVoiceNote()
            runCatching { repository.logout() }
                .onSuccess {
                    refreshJob?.cancel()
                    typingStopJob?.cancel()
                    logoutRequested = false
                    _state.value = MessengerUiState(homeserver = _state.value.homeserver)
                    restored = false
                }
                .onFailure {
                    logoutRequested = false
                    _state.update {
                        val pushCleanupPending = repository.pushRegistrationStatus.value == PushRegistrationStatus.REMOVAL_PENDING
                        it.copy(
                            isBusy = false,
                            error = if (pushCleanupPending) {
                                "This device's push registration couldn't be removed. You are still signed in; retry cleanup in Settings before signing out."
                            } else {
                                "Secure sign-out couldn't finish. Your saved session is still available; try again."
                            },
                        )
                    }
                }
        }
    }

    private fun beginRoomRefresh() {
        refreshJob?.cancel()
        typingStopJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                runCatching { repository.refreshConversations() }
                delay(4_000)
            }
        }
    }

    override fun onCleared() {
        if (!_state.value.isSendingVoiceNote) discardVoiceNote()
        stopAudioPlayback()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { repository.close() }
        }
        super.onCleared()
    }
}

internal fun voiceNoteDraftCanBeSentTo(ownerRoomId: String?, selectedRoomId: String?): Boolean =
    ownerRoomId != null && ownerRoomId == selectedRoomId

class MessengerViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MessengerViewModel::class.java))
        return MessengerViewModel(context.applicationContext) as T
    }
}
