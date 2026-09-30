package dev.friendline.messenger.data

data class ConversationSummary(
    val roomId: String,
    val title: String,
    val preview: String,
    val lastActivityMillis: Long,
    val unreadCount: Int,
    val isEncrypted: Boolean,
    val isGroup: Boolean = false,
    val membership: String = "JOINED",
    /** True only for the protocol-only room used to coordinate peer device verification. */
    val isVerificationControl: Boolean = false,
    /** The peer this control room verifies; null when the room is not a control room. */
    val verificationPeerUserId: String? = null,
)

data class ChatMessage(
    val id: String,
    val eventId: String?,
    val isRemote: Boolean,
    val sender: String,
    val body: String,
    val timestampMillis: Long,
    val isOwn: Boolean,
    val deliveryState: String,
    val canRetry: Boolean = false,
    val canReply: Boolean = false,
    val canEdit: Boolean = false,
    val isEdited: Boolean = false,
    val canRedact: Boolean = false,
    val replyToEventId: String? = null,
    val reactions: List<ReactionSummary> = emptyList(),
    val hasBeenRead: Boolean = false,
    val attachment: ChatAttachment? = null,
    val deliveryMemberDetails: List<DeliveryMemberStatus> = emptyList(),
)

data class DeliveryMemberStatus(
    val userId: String,
    val delivered: Boolean,
)

data class ChatAttachment(
    val sourceJson: String,
    val fileName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val sizeBytes: Long? = null,
)

enum class AttachmentKind {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
}

enum class PeerTrustStatus {
    UNKNOWN,
    UNVERIFIED,
    VERIFIED,
    CHANGED,
}

data class ReactionSummary(
    val key: String,
    val count: Int,
    val sentByMe: Boolean,
)

enum class DeviceVerificationStatus {
    REQUESTING,
    INCOMING_REQUEST,
    WAITING_FOR_ACCEPT,
    COMPARING,
    CONFIRMING,
    VERIFIED,
    CANCELLED,
    FAILED,
}

data class DeviceVerificationUiState(
    val peerUserId: String,
    val peerDeviceName: String? = null,
    val status: DeviceVerificationStatus,
    val sas: String? = null,
    val error: String? = null,
)

data class MessageSearchHit(
    val roomId: String,
    val roomTitle: String,
    val eventId: String,
    val sender: String,
    val body: String,
    val timestampMillis: Long,
)
