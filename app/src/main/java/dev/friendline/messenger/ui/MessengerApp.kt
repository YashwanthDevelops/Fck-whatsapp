package dev.friendline.messenger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun MessengerApp(viewModel: MessengerViewModel) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.restoreSessionIfPresent() }

    when {
        state.currentRoomId != null -> ChatScreen(
            title = state.currentRoomTitle,
            roomId = state.currentRoomId,
            draft = state.composerDraft,
            isEncrypted = state.currentRoomEncrypted,
            isGroup = state.currentRoomIsGroup,
            peerTrust = state.peerTrust,
            connection = state.connection,
            messages = state.messages,
            typingUsers = state.typingUsers,
            replyTarget = state.replyTarget,
            messageSearchQuery = state.messageSearchQuery,
            searchResults = state.searchResults,
            searchHasMore = state.searchHasMore,
            searchLoading = state.searchLoading,
            error = state.error,
            isSendingAttachment = state.isSendingAttachment,
            isRecordingVoiceNote = state.isRecordingVoiceNote,
            voiceRecordingStartedAtMillis = state.voiceRecordingStartedAtMillis,
            voiceNoteFilePath = state.voiceNoteFilePath,
            voiceNoteDurationMillis = state.voiceNoteDurationMillis,
            isSendingVoiceNote = state.isSendingVoiceNote,
            audioPlayback = state.audioPlayback,
            onBack = viewModel::closeConversation,
            onSend = { text -> viewModel.sendText(text) },
            onSendAttachment = viewModel::sendAttachment,
            onStartVoiceRecording = viewModel::startVoiceRecording,
            onStopVoiceRecording = viewModel::stopVoiceRecording,
            onDiscardVoiceNote = viewModel::discardVoiceNote,
            onSendVoiceNote = viewModel::sendVoiceNote,
            onMicrophonePermissionDenied = viewModel::microphonePermissionDenied,
            onPlayAudio = viewModel::toggleAudioPlayback,
            onOpenAttachment = viewModel::openAttachment,
            onRetry = viewModel::retryFailedMessages,
            onReply = viewModel::replyTo,
            onCancelReply = viewModel::cancelReply,
            onToggleReaction = viewModel::toggleReaction,
            onVerifyPeer = viewModel::verifyConversationPeer,
            onMessageSearchQueryChange = viewModel::updateMessageSearchQuery,
            onPaginateMessageSearch = viewModel::paginateMessageSearch,
            onOpenSearchHit = viewModel::openSearchHit,
            onDraftChange = viewModel::updateComposerDraft,
            onClearError = viewModel::clearError,
        )
        state.userId != null && state.showNewConversation -> NewConversationScreen(
            isBusy = state.isBusy,
            error = state.error,
            onBack = { viewModel.showNewConversation(false) },
            onCreate = viewModel::createConversation,
            onClearError = viewModel::clearError,
        )
        state.userId != null -> ConversationListScreen(
            userId = state.userId,
            connection = state.connection,
            conversations = state.conversations,
            readReceiptsEnabled = state.readReceiptsEnabled,
            error = state.error,
            onOpen = viewModel::openConversation,
            onNew = { viewModel.showNewConversation(true) },
            onLogout = viewModel::logout,
            onReadReceiptsChange = viewModel::setReadReceiptsEnabled,
            onClearError = viewModel::clearError,
        )
        else -> LoginScreen(
            homeserver = state.homeserver,
            username = state.username,
            password = state.password,
            isBusy = state.isBusy,
            error = state.error,
            onHomeserverChange = viewModel::updateHomeserver,
            onUsernameChange = viewModel::updateUsername,
            onPasswordChange = viewModel::updatePassword,
            onSignIn = viewModel::signIn,
            onClearError = viewModel::clearError,
        )
    }

    state.verification?.let { verification ->
        VerificationDialog(
            verification = verification,
            onAccept = viewModel::acceptVerificationRequest,
            onDecline = viewModel::declineVerificationRequest,
            onApprove = viewModel::approveVerification,
            onDismiss = viewModel::dismissVerification,
        )
    }
}
