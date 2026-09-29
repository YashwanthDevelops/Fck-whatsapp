package dev.friendline.messenger.calls

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

internal const val CALL_MESSAGE_TYPE = "org.friendline.call.v1"
internal const val CALL_CONTENT_KEY = "org.friendline.messenger.call.v1"
internal const val MAX_CALL_INVITE_AGE_MILLIS = 60_000L
internal const val MAX_CALL_CONTROL_AGE_MILLIS = 86_400_000L
internal const val CALL_INVITE_TTL_MILLIS = 45_000L

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
    val mediaKeyBase64: String?,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "MessengerCallSignal(roomId=$roomId, senderId=$senderId, eventId=$eventId, " +
            "callId=$callId, action=$action, kind=$kind, mediaKey=[redacted])"
}

object CallProtocol {
    fun envelope(
        action: MessengerCallAction,
        callId: String,
        kind: MessengerCallKind? = null,
        mediaKeyBase64: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        require(isCanonicalCallId(callId)) { "Invalid call identifier" }
        if (action == MessengerCallAction.INVITE) {
            require(kind != null) { "An invitation must include a call type" }
            require(mediaKeyBase64 != null && decodeMediaKey(mediaKeyBase64) != null) {
                "An invitation must include a valid media key"
            }
        } else {
            require(kind == null && mediaKeyBase64 == null) {
                "Only invitations may carry media parameters"
            }
        }

        val extension = JSONObject()
            .put("version", 1)
            .put("action", action.wireValue)
            .put("call_id", callId)
            .put("issued_at_ms", nowMillis)
        if (action == MessengerCallAction.INVITE) {
            extension.put("kind", kind!!.wireValue)
            extension.put("media_key", mediaKeyBase64)
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
        val mediaKey: String?
        if (action == MessengerCallAction.INVITE) {
            kind = MessengerCallKind.entries.firstOrNull { it.wireValue == extension.optString("kind") }
                ?: return null
            mediaKey = extension.optString("media_key").takeIf(String::isNotBlank)
            if (mediaKey == null || decodeMediaKey(mediaKey) == null) return null
        } else {
            kind = null
            mediaKey = null
        }
        MessengerCallSignal(roomId, senderId, eventId, callId, action, kind, mediaKey, expiresAt)
    }.getOrNull()

    fun decodeMediaKey(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value)
            .takeIf { it.size == 32 }
    }.getOrNull()

    fun encodeMediaKey(key: ByteArray): String {
        require(key.size == 32) { "Call media keys must contain 256 bits" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    }

    fun isCanonicalCallId(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)
}
