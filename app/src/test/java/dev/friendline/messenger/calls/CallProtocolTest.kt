package dev.friendline.messenger.calls

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CallProtocolTest {
    @Test
    fun encryptedToDeviceInvitationCarriesFreshKeyOnlyInMemoryAndDestroysIt() {
        val key = ByteArray(CALL_KEY_BYTES) { it.toByte() }
        val callId = UUID.randomUUID().toString()
        val encoded = CallProtocol.encryptedInviteContent(
            roomId = "!room:example.org",
            callId = callId,
            kind = MessengerCallKind.VOICE,
            callKey = key,
            nowMillis = 10_000,
        )
        val signal = CallProtocol.parseEncryptedInviteContent(
            senderId = "@alice:example.org",
            messageContent = encoded,
            nowMillis = 10_001,
        )
        val received = checkNotNull(signal)

        assertEquals(callId, received.callId)
        assertEquals("!room:example.org", received.roomId)
        assertArrayEquals(key, received.copyCallKey())
        assertFalse(received.toString().contains("media_key"))
        received.destroy()
        assertArrayEquals(ByteArray(CALL_KEY_BYTES), received.copyCallKey())
    }

    @Test
    fun encryptedToDeviceInviteParserRejectsExpiredOrInvalidKeys() {
        val key = ByteArray(CALL_KEY_BYTES) { 9 }
        val valid = JSONObject(CallProtocol.encryptedInviteContent(
            roomId = "!room:example.org",
            callId = UUID.randomUUID().toString(),
            kind = MessengerCallKind.VIDEO,
            callKey = key,
            nowMillis = 10_000,
        ))
        assertNull(CallProtocol.parseEncryptedInviteContent("@alice:example.org", valid.toString(), 55_001))
        assertNull(CallProtocol.parseEncryptedInviteContent(
            "@alice:example.org",
            JSONObject(valid.toString()).put("media_key", "not-a-key").toString(),
            10_001,
        ))
        assertNull(CallProtocol.parseEncryptedInviteContent(
            "@alice:example.org",
            JSONObject(valid.toString()).put("action", "hangup").toString(),
            10_001,
        ))
    }

    @Test
    fun invitationContainsNoMediaKeyAndParses() {
        val callId = UUID.randomUUID().toString()
        val envelope = JSONObject(
            CallProtocol.envelope(
                action = MessengerCallAction.INVITE,
                callId = callId,
                kind = MessengerCallKind.VIDEO,
                nowMillis = 10_000,
            ),
        )
        assertEquals(CALL_MESSAGE_TYPE, envelope.getString("msgtype"))
        assertFalse(envelope.getJSONObject(CALL_CONTENT_KEY).has("media_key"))

        val event = JSONObject().put("content", envelope).toString()
        val signal = CallProtocol.parse("!room:example.org", "@alice:example.org", "\$event", event, 10_001)
        assertEquals(callId, signal?.callId)
        assertEquals(MessengerCallAction.INVITE, signal?.action)
        assertEquals(MessengerCallKind.VIDEO, signal?.kind)
        assertEquals(55_000L, signal?.expiresAtMillis)
    }

    @Test
    fun staleAndMalformedInvitationsAreRejected() {
        val callId = UUID.randomUUID().toString()
        val valid = JSONObject(CallProtocol.envelope(
            MessengerCallAction.INVITE,
            callId,
            MessengerCallKind.VOICE,
            nowMillis = 10_000,
        ))

        assertNull(parse(valid, now = 55_000 + 1))
        assertNull(parse(JSONObject(valid.toString()).put(CALL_CONTENT_KEY,
            valid.getJSONObject(CALL_CONTENT_KEY).put("media_key", "legacy-key")), now = 10_001))
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
    }

    @Test
    fun futureInvitationIsRejected() {
        val content = JSONObject(CallProtocol.envelope(
            MessengerCallAction.INVITE,
            UUID.randomUUID().toString(),
            MessengerCallKind.VOICE,
            nowMillis = 20_000,
        ))
        assertNull(parse(content, now = 1_000))
        val canonicalId = content.getJSONObject(CALL_CONTENT_KEY).getString("call_id")
        assertTrue(CallProtocol.isCanonicalCallId(canonicalId))
        assertTrue(CallProtocol.isCanonicalCallId("abcdefab-cdef-abcd-efab-cdefabcdefab"))
        assertTrue(CallProtocol.isCanonicalCallId("v1." + "A".repeat(86) + "." + "B".repeat(43)))
        assertFalse(CallProtocol.isCanonicalCallId("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB"))
        assertFalse(CallProtocol.isCanonicalCallId("v1." + "A".repeat(85) + "." + "B".repeat(43)))
        assertFalse(CallProtocol.isCanonicalCallId("not-a-uuid"))
    }

    @Test
    fun verifiedCallRecipientsRequireEncryptedDirectRoomAndTwoMembers() {
        val members = listOf("@alice:example.org", "@bob:example.org")
        val devices = listOf("BOB_PHONE", "BOB_TABLET")

        assertEquals(
            CallKeyRecipients("@bob:example.org", devices),
            verifiedCallKeyRecipients(true, true, members, "@alice:example.org", devices),
        )
        assertNull(verifiedCallKeyRecipients(false, true, members, "@alice:example.org", devices))
        assertNull(verifiedCallKeyRecipients(true, false, members, "@alice:example.org", devices))
        assertNull(
            verifiedCallKeyRecipients(
                true,
                true,
                members + "@mallory:example.org",
                "@alice:example.org",
                devices,
            ),
        )
    }

    @Test
    fun verifiedCallRecipientsRejectEmptyWildcardOrDuplicateDeviceTargets() {
        val members = listOf("@alice:example.org", "@bob:example.org")

        assertNull(verifiedCallKeyRecipients(true, true, members, "@alice:example.org", emptyList()))
        assertNull(verifiedCallKeyRecipients(true, true, members, "@alice:example.org", listOf("*")))
        assertNull(
            verifiedCallKeyRecipients(
                true,
                true,
                members,
                "@alice:example.org",
                listOf("BOB_PHONE", "BOB_PHONE"),
            ),
        )
    }

    private fun parse(content: JSONObject, now: Long): MessengerCallSignal? = CallProtocol.parse(
        "!room:example.org",
        "@alice:example.org",
        "\$event",
        JSONObject().put("content", content).toString(),
        now,
    )
}
