package dev.friendline.messenger.calls

import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.regex.Pattern

internal const val CALL_MESSAGE_TYPE = "org.friendline.call.v1"
internal const val CALL_CONTENT_KEY = "org.friendline.messenger.call.v1"
internal const val MAX_CALL_INVITE_AGE_MILLIS = 60_000L
internal const val MAX_CALL_CONTROL_AGE_MILLIS = 86_400_000L
internal const val CALL_INVITE_TTL_MILLIS = 45_000L
internal const val CALL_KEY_EVENT_TYPE = "org.friendline.call-key.v1"
internal const val CALL_KEY_BYTES = 32

enum class MessengerCallKind(val wireValue: String) {
    VOICE("voice"),
    VIDEO("video"),
}

enum class MessengerCallAction(val wireValue: String) {
    INVITE("invite"),
    ACCEPT("accept"),
    DECLINE("decline"),
    HANGUP("hangup"),
}

/** Kept in memory only. Its diagnostic representation deliberately redacts the call key. */
class MessengerCallSignal(
    val roomId: String,
    val senderId: String,
    val eventId: String,
    val callId: String,
    val action: MessengerCallAction,
    val kind: MessengerCallKind?,
    val expiresAtMillis: Long,
    private val callKey: ByteArray? = null,
) {
    fun copyCallKey(): ByteArray? = callKey?.copyOf()

    fun destroy() {
        callKey?.fill(0)
    }

    override fun toString(): String =
        "MessengerCallSignal(roomId=$roomId, senderId=$senderId, eventId=$eventId, " +
            "callId=$callId, action=$action, kind=$kind)"
}

/** Ephemeral outgoing call material; callers must destroy it after handing the key to LiveKit. */
class OutgoingCallKeyMaterial(
    val roomId: String,
    val callId: String,
    val kind: MessengerCallKind,
    val expiresAtMillis: Long,
    val liveKitUrl: String,
    val liveKitToken: String,
    private val callKey: ByteArray,
) {
    fun copyCallKey(): ByteArray = callKey.copyOf()

    fun destroy() {
        callKey.fill(0)
    }

    override fun toString(): String =
        "OutgoingCallKeyMaterial(roomId=$roomId, callId=$callId, kind=$kind, expiresAtMillis=$expiresAtMillis)"
}

/** Incoming invite material is released only after sender, room, and device checks pass. */
class IncomingCallMediaMaterial internal constructor(
    val roomId: String,
    val callId: String,
    val kind: MessengerCallKind,
    val expiresAtMillis: Long,
    val liveKitUrl: String,
    val liveKitToken: String,
    private val callKey: ByteArray,
) {
    fun copyCallKey(): ByteArray = callKey.copyOf()

    fun destroy() {
        callKey.fill(0)
    }

    override fun toString(): String =
        "IncomingCallMediaMaterial(roomId=$roomId, callId=$callId, kind=$kind)"
}

internal data class CallKeyRecipients(val userId: String, val deviceIds: List<String>)

/** Resolve a safe per-device target for a verified one-to-one encrypted room. */
internal fun verifiedCallKeyRecipients(
    roomIsEncrypted: Boolean,
    roomIsDirect: Boolean,
    roomMemberIds: Collection<String>,
    ownUserId: String,
    verifiedDeviceIds: List<String>,
): CallKeyRecipients? {
    if (!roomIsEncrypted || !roomIsDirect || ownUserId.isBlank()) return null
    val members = roomMemberIds.toSet()
    if (members.size != 2 || ownUserId !in members) return null
    val peerUserId = members.singleOrNull { it != ownUserId } ?: return null
    if (verifiedDeviceIds.isEmpty() || verifiedDeviceIds.any { it.isBlank() || it == "*" }) return null
    if (verifiedDeviceIds.size != verifiedDeviceIds.distinct().size) return null
    return CallKeyRecipients(peerUserId, verifiedDeviceIds.toList())
}

object CallProtocol {
    fun encryptedInviteContent(
        roomId: String,
        callId: String,
        kind: MessengerCallKind,
        callKey: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        require(roomId.isNotBlank() && roomId.length <= 255) { "Invalid room identifier" }
        require(isCanonicalCallId(callId)) { "Invalid call identifier" }
        require(callKey.size == CALL_KEY_BYTES) { "Call key must be 256 bits" }
        require(nowMillis > 0 && nowMillis <= Long.MAX_VALUE - CALL_INVITE_TTL_MILLIS) {
            "Invalid call invitation timestamp"
        }
        return JSONObject()
            .put("version", 1)
            .put("action", MessengerCallAction.INVITE.wireValue)
            .put("room_id", roomId)
            .put("call_id", callId)
            .put("kind", kind.wireValue)
            .put("issued_at_ms", nowMillis)
            .put("expires_at_ms", nowMillis + CALL_INVITE_TTL_MILLIS)
            .put("media_key", Base64.getEncoder().withoutPadding().encodeToString(callKey))
            .toString()
    }

    fun parseEncryptedInviteContent(
        senderId: String,
        messageContent: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MessengerCallSignal? = runCatching {
        val content = JSONObject(messageContent)
        val expectedKeys = setOf(
            "version", "action", "room_id", "call_id", "kind", "issued_at_ms", "expires_at_ms", "media_key",
        )
        if (content.keys().asSequence().toSet() != expectedKeys) return null
        if (content.optInt("version", -1) != 1 ||
            content.optString("action") != MessengerCallAction.INVITE.wireValue
        ) return null
        val roomId = content.optString("room_id")
        val callId = content.optString("call_id")
        if (roomId.isBlank() || roomId.length > 255 || !isCanonicalCallId(callId)) return null
        val kind = MessengerCallKind.entries.firstOrNull {
            it.wireValue == content.optString("kind")
        } ?: return null
        val issuedAt = content.optLong("issued_at_ms", -1L)
        val expiresAt = content.optLong("expires_at_ms", -1L)
        if (issuedAt <= 0 || issuedAt > nowMillis + 5_000L ||
            expiresAt <= nowMillis || expiresAt <= issuedAt ||
            expiresAt - issuedAt > CALL_INVITE_TTL_MILLIS
        ) return null
        val encodedKey = content.optString("media_key")
        if (encodedKey.length != 43) return null
        val key = Base64.getDecoder().decode(encodedKey)
        if (key.size != CALL_KEY_BYTES) {
            key.fill(0)
            return null
        }
        MessengerCallSignal(
            roomId = roomId,
            senderId = senderId,
            eventId = callId,
            callId = callId,
            action = MessengerCallAction.INVITE,
            kind = kind,
            expiresAtMillis = expiresAt,
            callKey = key,
        )
    }.getOrNull()

    fun envelope(
        action: MessengerCallAction,
        callId: String,
        kind: MessengerCallKind? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        require(isCanonicalCallId(callId)) { "Invalid call identifier" }
        if (action == MessengerCallAction.INVITE) {
            require(kind != null) { "An invitation must include a call type" }
        } else {
            require(kind == null) {
                "Only invitations may carry call type metadata"
            }
        }

        val extension = JSONObject()
            .put("version", 1)
            .put("action", action.wireValue)
            .put("call_id", callId)
            .put("issued_at_ms", nowMillis)
        if (action == MessengerCallAction.INVITE) {
            extension.put("kind", kind!!.wireValue)
            extension.put("expires_at_ms", nowMillis + CALL_INVITE_TTL_MILLIS)
        }

        // The key is in an encrypted extension field, not in the body used for previews,
        // notification text, accessibility labels, or timeline search.
        val body = when (action) {
            MessengerCallAction.INVITE -> if (kind == MessengerCallKind.VIDEO) "Video call invitation" else "Voice call invitation"
            MessengerCallAction.ACCEPT -> "Call accepted"
            MessengerCallAction.DECLINE -> "Call declined"
            MessengerCallAction.HANGUP -> "Call ended"
        }
        return JSONObject()
            .put("msgtype", CALL_MESSAGE_TYPE)
            .put("body", body)
            .put(CALL_CONTENT_KEY, extension)
            .toString()
    }

    fun parse(
        roomId: String,
        senderId: String,
        eventId: String,
        originalEventJson: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MessengerCallSignal? = runCatching {
        val content = JSONObject(originalEventJson).optJSONObject("content") ?: return null
        if (content.optString("msgtype") != CALL_MESSAGE_TYPE) return null
        val extension = content.optJSONObject(CALL_CONTENT_KEY) ?: return null
        if (extension.optInt("version", -1) != 1) return null
        val action = MessengerCallAction.entries.firstOrNull {
            it.wireValue == extension.optString("action")
        } ?: return null
        val callId = extension.optString("call_id")
        if (!isCanonicalCallId(callId)) return null
        val issuedAt = extension.optLong("issued_at_ms", -1L)
        if (issuedAt <= 0 || issuedAt > nowMillis + 5_000L) return null
        val expiresAt = when (action) {
            MessengerCallAction.INVITE -> extension.optLong("expires_at_ms", -1L)
            else -> issuedAt + MAX_CALL_CONTROL_AGE_MILLIS
        }
        val maxAge = if (action == MessengerCallAction.INVITE) {
            MAX_CALL_INVITE_AGE_MILLIS
        } else {
            MAX_CALL_CONTROL_AGE_MILLIS
        }
        if (expiresAt <= nowMillis || expiresAt - issuedAt > maxAge) return null

        val kind: MessengerCallKind?
        if (action == MessengerCallAction.INVITE) {
            kind = MessengerCallKind.entries.firstOrNull { it.wireValue == extension.optString("kind") }
                ?: return null
            // Reject legacy key-bearing room events. A persistent encrypted room event is
            // not a safe transport for call keys because unverified devices can receive it.
            if (extension.has("media_key")) return null
        } else {
            kind = null
        }
        MessengerCallSignal(roomId, senderId, eventId, callId, action, kind, expiresAt)
    }.getOrNull()

    fun isCanonicalCallId(value: String): Boolean = runCatching {
        CALL_AUTH_HANDLE.matcher(value).matches() || UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private val CALL_AUTH_HANDLE = Pattern.compile("v1\\.[A-Za-z0-9_-]{86}\\.[A-Za-z0-9_-]{43}")
}
