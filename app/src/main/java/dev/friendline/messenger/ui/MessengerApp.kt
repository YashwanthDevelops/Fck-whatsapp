package dev.friendline.messenger.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.friendline.messenger.push.MatrixPushClient

@Composable
fun MessengerApp(viewModel: MessengerViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val myFriendAddressQr = remember(state.userId, state.homeserver) { viewModel.friendAddressQrPayload() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = MatrixPushClient.notificationPermissionContract(),
    ) { granted -> viewModel.onPushPermissionResult(granted) }

    LaunchedEffect(Unit) { viewModel.restoreSessionIfPresent() }

    when {
        state.currentRoomId != null -> ChatScreen(
            title = state.currentRoomTitle,
            roomId = state.currentRoomId,
            draft = state.composerDraft,
            isEncrypted = state.currentRoomEncrypted,
            isGroup = state.currentRoomIsGroup,
            canCall = state.currentRoomEncrypted && !state.currentRoomIsGroup,
            callBusy = state.callBusy,
            callConnected = state.callConnected,
            callPeerAccepted = state.callPeerAccepted,
            callKind = state.activeCallKind,
            microphoneEnabled = state.callMicrophoneEnabled,
            cameraEnabled = state.callCameraEnabled,
            remoteVideoTrack = state.remoteVideoTrack,
            onAttachVideoRenderer = viewModel::attachVideoRenderer,
            onDetachVideoRenderer = viewModel::detachVideoRenderer,
            incomingCallKind = state.incomingCall?.takeIf { it.roomId == state.currentRoomId }?.kind,
            incomingCallId = state.incomingCall?.takeIf { it.roomId == state.currentRoomId }?.callId,
            incomingCallFrom = state.incomingCall?.takeIf { it.roomId == state.currentRoomId }?.senderId,
            onStartCall = viewModel::startSecureCall,
            onAcceptCall = { expectedRoomId, expectedCallId ->
                viewModel.acceptIncomingSecureCall(expectedRoomId, expectedCallId)
            },
            onDeclineCall = viewModel::declineIncomingSecureCall,
            onEndCall = viewModel::endSecureCall,
            onToggleCallMicrophone = viewModel::toggleCallMicrophone,
            onToggleCallCamera = viewModel::toggleCallCamera,
            peerTrust = state.peerTrust,
            connection = state.connection,
            messages = state.messages,
            typingUsers = state.typingUsers,
            replyTarget = state.replyTarget,
            messageSearchQuery = state.messageSearchQuery,
            searchResults = state.searchResults,
            searchHasMore = state.searchHasMore,
            searchLoading = state.searchLoading,
            navigationTargetEventId = state.navigationTargetEventId,
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
            onMessageVisible = viewModel::markMessageAsVisible,
            onStartVoiceRecording = viewModel::startVoiceRecording,
            onStopVoiceRecording = viewModel::stopVoiceRecording,
            onDiscardVoiceNote = viewModel::discardVoiceNote,
            onSendVoiceNote = viewModel::sendVoiceNote,
            onMicrophonePermissionDenied = viewModel::microphonePermissionDenied,
            onPlayAudio = viewModel::toggleAudioPlayback,
            onOpenAttachment = viewModel::openAttachment,
            onRetry = viewModel::retryFailedMessages,
            onReply = viewModel::replyTo,
            onOpenReferencedMessage = viewModel::openReferencedMessage,
            onCancelReply = viewModel::cancelReply,
            onToggleReaction = viewModel::toggleReaction,
            onEditMessage = viewModel::editMessage,
            onRedactMessage = viewModel::redactMessage,
            onVerifyPeer = viewModel::verifyConversationPeer,
            onMessageSearchQueryChange = viewModel::updateMessageSearchQuery,
            onPaginateMessageSearch = viewModel::paginateMessageSearch,
            onOpenSearchHit = viewModel::openSearchHit,
            onMessageNavigationCompleted = viewModel::completeMessageNavigation,
            onDraftChange = viewModel::updateComposerDraft,
            onClearError = viewModel::clearError,
        )
        state.userId != null && state.showNewConversation -> NewConversationScreen(
            isBusy = state.isBusy,
            error = state.error,
            friendMatrixId = state.userId,
            friendAddressQrPayload = myFriendAddressQr,
            onBack = { viewModel.showNewConversation(false) },
            onCreate = viewModel::createConversation,
            onResolveFriendAddressQr = viewModel::resolveFriendAddressQr,
            onClearError = viewModel::clearError,
        )
        state.userId != null -> ConversationListScreen(
            userId = state.userId,
            connection = state.connection,
            conversations = state.conversations,
            isBusy = state.isBusy,
            readReceiptsEnabled = state.readReceiptsEnabled,
            pushNotificationsEnabled = state.pushNotificationsEnabled,
            pushRegistrationStatus = state.pushRegistrationStatus,
            pushNotificationsConfigured = MatrixPushClient.isConfigured,
            error = state.error,
            onOpen = viewModel::openConversation,
            onAcceptInvitation = viewModel::acceptRoomInvitation,
            onDeclineInvitation = viewModel::declineRoomInvitation,
            onLeaveConversation = viewModel::leaveConversation,
            onNew = { viewModel.showNewConversation(true) },
            onLogout = viewModel::logout,
            onReadReceiptsChange = viewModel::setReadReceiptsEnabled,
            onPushNotificationsChange = { enabled ->
                viewModel.setPushNotificationsEnabled(enabled)
                if (enabled) {
                    MatrixPushClient.notificationPermissionToRequest(context)?.let(notificationPermissionLauncher::launch)
                }
            },
            onRetryPushNotifications = viewModel::retryPushNotifications,
            onOpenNotificationSettings = {
                val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                runCatching { context.startActivity(settingsIntent) }
            },
            onClearError = viewModel::clearError,
            onJoinVerification = viewModel::joinVerificationChannel,
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
