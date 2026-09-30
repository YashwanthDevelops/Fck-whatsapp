package dev.friendline.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.friendline.messenger.data.ChatMessage
import dev.friendline.messenger.data.AttachmentKind
import dev.friendline.messenger.data.MessageSearchHit
import dev.friendline.messenger.data.PeerTrustStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    title: String,
    roomId: String?,
    draft: String,
    isEncrypted: Boolean,
    isGroup: Boolean,
    connection: String,
    peerTrust: PeerTrustStatus,
    messages: List<ChatMessage>,
    typingUsers: List<String>,
    replyTarget: ChatMessage?,
    messageSearchQuery: String,
    searchResults: List<MessageSearchHit>,
    searchHasMore: Boolean,
    searchLoading: Boolean,
    error: String?,
    isSendingAttachment: Boolean,
    isRecordingVoiceNote: Boolean,
    voiceRecordingStartedAtMillis: Long?,
    voiceNoteFilePath: String?,
    voiceNoteDurationMillis: Int,
    isSendingVoiceNote: Boolean,
    audioPlayback: AudioPlaybackUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendAttachment: (String) -> Unit,
    onMessageVisible: (String, Long) -> Unit,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: () -> Unit,
    onDiscardVoiceNote: () -> Unit,
    onSendVoiceNote: () -> Unit,
    onMicrophonePermissionDenied: () -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    onRetry: () -> Unit,
    onReply: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
    onToggleReaction: (ChatMessage, String) -> Unit,
    onEditMessage: (ChatMessage, String) -> Unit,
    navigationTargetEventId: String?,
    onRedactMessage: (ChatMessage) -> Unit,
    onVerifyPeer: () -> Unit,
    onMessageSearchQueryChange: (String) -> Unit,
    onPaginateMessageSearch: () -> Unit,
    onOpenSearchHit: (MessageSearchHit) -> Unit,
    onDraftChange: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val listState = rememberLazyListState()
    var scrollToLatestOnLoad by remember(roomId) { mutableStateOf(true) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    var attachmentToOpenExternally by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember(roomId) { mutableStateOf<ChatMessage?>(null) }
    var editingBody by remember(editingMessage?.id) { mutableStateOf(editingMessage?.body.orEmpty()) }
    var messagePendingRedaction by remember(roomId) { mutableStateOf<ChatMessage?>(null) }
    var elapsedRecordingMillis by remember(voiceRecordingStartedAtMillis) { mutableStateOf(0) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val voiceNoteComposerLocked = isRecordingVoiceNote || voiceNoteFilePath != null || isSendingVoiceNote
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartVoiceRecording() else onMicrophonePermissionDenied()
    }
    val visualMediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onSendAttachment(it.toString()) }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onSendAttachment(it.toString()) }
    }
    onMessageNavigationCompleted: (String) -> Unit,
    LaunchedEffect(roomId, messages.size, searchVisible, navigationTargetEventId) {
        if (!searchVisible && messages.isNotEmpty()) {
            val layout = listState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index
            val wasNearBottom = layout.totalItemsCount == 0 || lastVisibleIndex == null || lastVisibleIndex >= messages.lastIndex - 1
            if (scrollToLatestOnLoad || wasNearBottom) listState.scrollToItem(messages.lastIndex)
            scrollToLatestOnLoad = false
        }
    }
    LaunchedEffect(voiceRecordingStartedAtMillis) {
        val startedAt = voiceRecordingStartedAtMillis ?: return@LaunchedEffect
        while (true) {
            elapsedRecordingMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0).toInt()
            kotlinx.coroutines.delay(250)
        }
    }
    LaunchedEffect(roomId, messages, searchVisible) {
        snapshotFlow {
            if (searchVisible) {
                null
            } else {
                listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { visible -> messages.getOrNull(visible.index) }
                    .filter { !it.isOwn && it.isRemote && it.eventId != null }
                    .maxByOrNull(ChatMessage::timestampMillis)
                    ?.let { message -> message.eventId?.let { it to message.timestampMillis } }
            }
        }.distinctUntilChanged().collect { target ->
            target?.let { (eventId, timestampMillis) -> onMessageVisible(eventId, timestampMillis) }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                title,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (isGroup) {
                                Text(
                                    "GROUP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
            if (navigationTargetEventId != null) {
                val targetIndex = messages.indexOfFirst { it.eventId == navigationTargetEventId }
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                    scrollToLatestOnLoad = false
                    onMessageNavigationCompleted(navigationTargetEventId)
                }
                return@LaunchedEffect
            }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(
                                if (isEncrypted) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (isEncrypted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                if (isEncrypted) "End-to-end encrypted · $connection" else "Not encrypted · sending disabled",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    !isEncrypted -> MaterialTheme.colorScheme.error
                                    connection.equals("Connected", ignoreCase = true) -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.error
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        if (isEncrypted && peerTrust != PeerTrustStatus.UNKNOWN) {
                            Text(
                                when (peerTrust) {
                                    PeerTrustStatus.UNKNOWN -> ""
                                    PeerTrustStatus.UNVERIFIED -> "Contact not verified"
                                    PeerTrustStatus.VERIFIED -> "Contact verified"
                                    PeerTrustStatus.CHANGED -> "Contact identity changed · verify again"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (peerTrust) {
                                    PeerTrustStatus.CHANGED -> MaterialTheme.colorScheme.error
                                    PeerTrustStatus.VERIFIED -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to conversations" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = onVerifyPeer) { Text("Verify") }
                    IconButton(
                        onClick = {
                            searchVisible = !searchVisible
                            onMessageSearchQueryChange("")
                        },
                        modifier = Modifier.semantics { contentDescription = if (searchVisible) "Close message search" else "Search messages" },
                    ) {
                        Icon(if (searchVisible) Icons.Filled.Close else Icons.Filled.Search, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            if (!searchVisible) Column(Modifier.imePadding()) {
                if (isSendingAttachment) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (replyTarget != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(start = 18.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Replying to ${if (replyTarget.isOwn) "yourself" else replyTarget.sender}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(replyTarget.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onCancelReply, modifier = Modifier.semantics { contentDescription = "Cancel reply" }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
                if (isRecordingVoiceNote) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                            .padding(start = 16.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Recording voice message · ${formatVoiceDuration(elapsedRecordingMillis)}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onDiscardVoiceNote) { Text("Cancel") }
                        IconButton(
                            onClick = onStopVoiceRecording,
                            modifier = Modifier.semantics { contentDescription = "Stop voice-message recording" },
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (voiceNoteFilePath != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(start = 16.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Voice message · ${formatVoiceDuration(voiceNoteDurationMillis)}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = onDiscardVoiceNote, enabled = !isSendingVoiceNote) { Text("Discard") }
                        Button(
                            onClick = onSendVoiceNote,
                            enabled = isEncrypted && !isSendingVoiceNote,
                            modifier = Modifier.semantics { contentDescription = "Send encrypted voice message" },
                        ) {
                            if (isSendingVoiceNote) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box {
                        IconButton(
                            onClick = { attachmentMenuExpanded = true },
                            enabled = isEncrypted && !isSendingAttachment && !voiceNoteComposerLocked,
                            modifier = Modifier.semantics { contentDescription = "Attach encrypted media or file" },
                        ) {
                            if (isSendingAttachment) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Add, contentDescription = null)
                            }
                        }
                        DropdownMenu(
                            expanded = attachmentMenuExpanded,
                            onDismissRequest = { attachmentMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photo or video") },
                                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                onClick = {
                                    attachmentMenuExpanded = false
                                    visualMediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("File or audio") },
                                leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                                onClick = {
                                    attachmentMenuExpanded = false
                                    documentPicker.launch(arrayOf("*/*"))
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (isEncrypted) "Write a message" else "Sending is disabled") },
                        enabled = isEncrypted && !voiceNoteComposerLocked,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (draft.isNotBlank() && isEncrypted && !voiceNoteComposerLocked) {
                                onSend(draft)
                            }
                        }),
                    )
                    IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                onStartVoiceRecording()
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = isEncrypted && !isSendingAttachment && !voiceNoteComposerLocked,
                        modifier = Modifier.padding(bottom = 4.dp).semantics { contentDescription = "Record encrypted voice message" },
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                    }
                    Button(
                        onClick = {
                            if (draft.isNotBlank() && isEncrypted && !voiceNoteComposerLocked) {
                                onSend(draft)
                            }
                        },
                        enabled = draft.isNotBlank() && isEncrypted && !voiceNoteComposerLocked,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .semantics { contentDescription = "Send encrypted message" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!isEncrypted) {
                Text(
                    "This room has no encryption. Messages are disabled here to protect your privacy.",
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (searchVisible) {
                OutlinedTextField(
                    value = messageSearchQuery,
                    onValueChange = onMessageSearchQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    label = { Text("Search messages on this device") },
                    placeholder = { Text("Search messages on this device") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }
            if (error != null) {
                ChatErrorBanner(error, onClearError)
            }
            if (searchVisible) {
                when {
                    messageSearchQuery.isBlank() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Search encrypted messages across your conversations on this device.",
                            modifier = Modifier.padding(28.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    searchResults.isEmpty() && searchLoading -> Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text("Searching your encrypted message index…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    searchResults.isEmpty() -> Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No matching messages in your local index.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (searchHasMore) {
                            TextButton(onClick = onPaginateMessageSearch, enabled = !searchLoading) {
                                Text(if (searchLoading) "Loading…" else "Search older results")
                            }
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        items(searchResults, key = { "${it.roomId}:${it.eventId}" }) { hit ->
                            SearchResultLine(hit) {
                                searchVisible = false
                                onOpenSearchHit(hit)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                        if (searchHasMore) item {
                            TextButton(onClick = onPaginateMessageSearch, enabled = !searchLoading) {
                                Text(if (searchLoading) "Loading…" else "Load more results")
                            }
                        }
                    }
                }
            } else if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !isEncrypted -> "No messages shown in this unencrypted room."
                    connection.equals("Connected", ignoreCase = true) -> "This line is open. Send the first message when you're ready."
                    else -> "No messages are available on this device yet. New messages will sync when your connection returns."
                        },
                        modifier = Modifier.padding(28.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = ChatMessage::id) { message ->
                        MessageLine(
                            message = message,
                            isGroup = isGroup,
                            isEncrypted = isEncrypted,
                            onRetry = onRetry,
                            onReply = onReply,
                            onToggleReaction = onToggleReaction,
                            onEdit = { selected ->
                                editingBody = selected.body
                                editingMessage = selected
                            },
                            onRedact = { messagePendingRedaction = it },
                            onOpenAttachment = { attachmentToOpenExternally = it },
                            audioPlayback = audioPlayback,
                            onPlayAudio = onPlayAudio,
                        )
                    }
                }
            }
            if (typingUsers.isNotEmpty()) {
                Text(
                    "${typingUsers.take(2).joinToString()} ${if (typingUsers.size == 1) "is" else "are"} typing…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    attachmentToOpenExternally?.let { message ->
        AlertDialog(
            onDismissRequest = { attachmentToOpenExternally = null },
            title = { Text("Open decrypted attachment?") },
            text = {
                Text(
                    "${message.attachment?.fileName ?: "This file"} will be decrypted on this device and opened by another app. That app may keep a copy. Private Messenger removes its temporary copy when you return or within 30 minutes.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    attachmentToOpenExternally = null
                    onOpenAttachment(message)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { attachmentToOpenExternally = null }) { Text("Cancel") }
            },
        )
    }

    editingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("Edit message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your edit is sent as an encrypted message update.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = editingBody,
                        onValueChange = { editingBody = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("Message") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editingBody.isNotBlank() && editingBody.trim() != message.body,
                    onClick = {
                        editingMessage = null
                        onEditMessage(message, editingBody)
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("Cancel") }
            },
        )
    }

    messagePendingRedaction?.let { message ->
        AlertDialog(
            onDismissRequest = { messagePendingRedaction = null },
            title = { Text("Remove this message?") },
            text = { Text("This removes the message for everyone in this encrypted conversation.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        messagePendingRedaction = null
                        onRedactMessage(message)
                    },
                ) { Text("Remove message", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { messagePendingRedaction = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChatErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
            Text(
                message,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss error" },
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}

@Composable
private fun SearchResultLine(hit: MessageSearchHit, onClick: () -> Unit) {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
        .format(Date(hit.timestampMillis))
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(hit.roomTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${hit.sender}: ${hit.body}", style = MaterialTheme.typography.bodyMedium, maxLines = 3)
    }
}

@Composable
private fun MessageLine(
    message: ChatMessage,
    isGroup: Boolean,
    isEncrypted: Boolean,
    onRetry: () -> Unit,
    onReply: (ChatMessage) -> Unit,
    onToggleReaction: (ChatMessage, String) -> Unit,
    onEdit: (ChatMessage) -> Unit,
    onRedact: (ChatMessage) -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    audioPlayback: AudioPlaybackUiState,
    onPlayAudio: (ChatMessage) -> Unit,
) {
    val alignment = if (message.isOwn) Alignment.End else Alignment.Start
    val surface = if (message.isOwn) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val author = if (message.isOwn) "You" else message.sender
    val time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(message.timestampMillis))
    val deliveryLabel = messageDeliveryLabel(
        deliveryState = message.deliveryState,
        hasBeenRead = message.hasBeenRead,
        isGroup = isGroup,
    )
    var menuExpanded by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$author, $time. ${message.attachment?.fileName.orEmpty()} ${message.body}. $deliveryLabel"
        },
        horizontalAlignment = alignment,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Surface(
                color = surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menuExpanded = true }),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!message.isOwn) Text(author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    if (message.replyToEventId != null) Text("↪ Reply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    message.attachment?.let { attachment ->
                        if (attachment.kind == AttachmentKind.AUDIO || attachment.mimeType.startsWith("audio/")) {
                            AudioMessageCard(
                                state = audioPlayback.takeIf { it.messageId == message.id } ?: AudioPlaybackUiState(),
                                onClick = { onPlayAudio(message) },
                            )
                        } else {
                            AttachmentCard(attachment.fileName, attachment.kind.name) { onOpenAttachment(message) }
                        }
                    }
                    if (message.body.isNotBlank()) Text(message.body, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.semantics { contentDescription = "Message options" },
                ) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (message.canReply) {
                        DropdownMenuItem(text = { Text("Reply") }, onClick = { menuExpanded = false; onReply(message) })
                    }
                    if (isEncrypted && message.canEdit) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuExpanded = false; onEdit(message) })
                    }
                    if (isEncrypted && message.canRedact) {
                        DropdownMenuItem(text = { Text("Remove message") }, onClick = { menuExpanded = false; onRedact(message) })
                    }
                    listOf("👍", "❤️", "😂", "😮").forEach { emoji ->
                        DropdownMenuItem(text = { Text("React $emoji") }, onClick = { menuExpanded = false; onToggleReaction(message, emoji) })
                    }
                }
            }
        }
        if (message.reactions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
                message.reactions.forEach { reaction ->
                    AssistChip(
                        onClick = { onToggleReaction(message, reaction.key) },
                        label = { Text("${reaction.key} ${reaction.count}") },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = if (reaction.sentByMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.isOwn) {
                Text(deliveryLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (message.isOwn && message.canRetry) {
            TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text("Retry encrypted send", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun messageDeliveryLabel(
    deliveryState: String,
    hasBeenRead: Boolean,
    isGroup: Boolean,
): String = when {
    !hasBeenRead -> deliveryState
    isGroup && deliveryState.startsWith("Delivered to ") -> "$deliveryState · Seen"
    isGroup -> "Seen"
    else -> "Read"
}

@Composable
private fun AudioMessageCard(state: AudioPlaybackUiState, onClick: () -> Unit) {
    val isLoading = state.status == AudioPlaybackStatus.LOADING
    val isPlaying = state.status == AudioPlaybackStatus.PLAYING
    val accessibilityLabel = when {
        isLoading -> "Cancel loading voice message"
        isPlaying -> "Pause voice message"
        else -> "Play voice message"
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.semantics { contentDescription = accessibilityLabel },
            ) {
                when {
                    isLoading -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    isPlaying -> Icon(Icons.Filled.Pause, contentDescription = null)
                    else -> Icon(Icons.Filled.PlayArrow, contentDescription = null)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Voice message", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    when (state.status) {
                        AudioPlaybackStatus.LOADING -> "Decrypting on this device…"
                        AudioPlaybackStatus.PLAYING -> "${formatVoiceDuration(state.positionMillis)} / ${formatVoiceDuration(state.durationMillis)}"
                        AudioPlaybackStatus.PAUSED -> "Paused · ${formatVoiceDuration(state.positionMillis)} / ${formatVoiceDuration(state.durationMillis)}"
                        AudioPlaybackStatus.IDLE -> "Tap to play privately on this device"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun formatVoiceDuration(durationMillis: Int): String {
    val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun AttachmentCard(fileName: String, kind: String, onClick: () -> Unit) {
    val label = when (kind) {
        "IMAGE" -> "Photo"
        "VIDEO" -> "Video"
        "AUDIO" -> "Audio"
        else -> "File"
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text("Tap to decrypt and choose an app", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
