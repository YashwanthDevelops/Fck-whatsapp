package dev.friendline.messenger.calls

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CallProtocolTest {
    @Test
    fun invitationCarriesKeyOnlyInEncryptedExtensionAndParses() {
        val callId = UUID.randomUUID().toString()
        val mediaKey = ByteArray(32) { (it + 1).toByte() }
        val encodedKey = CallProtocol.encodeMediaKey(mediaKey)
        val envelope = JSONObject(
            CallProtocol.envelope(
                action = MessengerCallAction.INVITE,
                callId = callId,
                kind = MessengerCallKind.VIDEO,
                mediaKeyBase64 = encodedKey,
                nowMillis = 10_000,
            ),
        )
        assertEquals(CALL_MESSAGE_TYPE, envelope.getString("msgtype"))
        assertFalse(envelope.getString("body").contains(encodedKey))
        assertEquals(encodedKey, envelope.getJSONObject(CALL_CONTENT_KEY).getString("media_key"))

        val event = JSONObject().put("content", envelope).toString()
        val signal = CallProtocol.parse("!room:example.org", "@alice:example.org", "\$event", event, 10_001)
        assertEquals(callId, signal?.callId)
        assertEquals(MessengerCallAction.INVITE, signal?.action)
        assertEquals(MessengerCallKind.VIDEO, signal?.kind)
        assertEquals(encodedKey, signal?.mediaKeyBase64)
        assertEquals(55_000L, signal?.expiresAtMillis)
        assertFalse(signal.toString().contains(encodedKey))
    }

    @Test
    fun staleAndMalformedInvitationsAreRejected() {
        val callId = UUID.randomUUID().toString()
        val key = CallProtocol.encodeMediaKey(ByteArray(32) { 8 })
        val valid = JSONObject(CallProtocol.envelope(
            MessengerCallAction.INVITE,
            callId,
            MessengerCallKind.VOICE,
            key,
            nowMillis = 10_000,
        ))

        assertNull(parse(valid, now = 55_000 + 1))
        assertNull(parse(JSONObject(valid.toString()).put(CALL_CONTENT_KEY,
            valid.getJSONObject(CALL_CONTENT_KEY).put("media_key", "not-a-key")), now = 10_001))
        assertNull(parse(JSONObject(valid.toString()).put(CALL_CONTENT_KEY,
            valid.getJSONObject(CALL_CONTENT_KEY).put("expires_at_ms", 90_001)), now = 10_001))
        assertNull(parse(JSONObject(valid.toString()).put(CALL_CONTENT_KEY,
            valid.getJSONObject(CALL_CONTENT_KEY).put("version", 2)), now = 10_001))
    }

    @Test
    fun callControlDoesNotCarryMediaKey() {
        val callId = UUID.randomUUID().toString()
        val content = JSONObject(CallProtocol.envelope(MessengerCallAction.HANGUP, callId, nowMillis = 1_000))
        val signal = CallProtocol.parse(
            "!room:example.org",
            "@alice:example.org",
            "\$hangup",
            JSONObject().put("content", content).toString(),
            nowMillis = 2_000,
        )
        assertEquals(MessengerCallAction.HANGUP, signal?.action)
        assertNull(signal?.mediaKeyBase64)
        assertNull(CallProtocol.decodeMediaKey("short"))
    }

    @Test
    fun futureInvitationIsRejected() {
        val content = JSONObject(CallProtocol.envelope(
            MessengerCallAction.INVITE,
            UUID.randomUUID().toString(),
            MessengerCallKind.VOICE,
            CallProtocol.encodeMediaKey(ByteArray(32)),
            nowMillis = 20_000,
        ))
        assertNull(parse(content, now = 1_000))
        assertTrue(CallProtocol.isCanonicalCallId(content.getJSONObject(CALL_CONTENT_KEY).getString("call_id")))
        assertFalse(CallProtocol.isCanonicalCallId("not-a-uuid"))
    }

    private fun parse(content: JSONObject, now: Long): MessengerCallSignal? = CallProtocol.parse(
        "!room:example.org",
        "@alice:example.org",
        "\$event",
        JSONObject().put("content", content).toString(),
        now,
    )
}
