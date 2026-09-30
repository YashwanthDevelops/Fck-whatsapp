import base64
import hashlib
import hmac
import io
import json
import stat
import sys
import tempfile
import unittest
import urllib.error
from email.message import Message
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from call_auth import (  # noqa: E402
    CALL_HANDLE_TTL_SECONDS,
    LIVEKIT_TOKEN_TTL_SECONDS,
    CallAuthorizer,
    MatrixClient,
    RequestError,
    _bearer_token,
    _read_request_object,
    _require_exact_keys,
    load_livekit_key,
    make_handler,
)


class Response:
    def __init__(self, value):
        self.body = io.BytesIO(json.dumps(value).encode())

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, size=-1):
        return self.body.read(size)


class FakeOpener:
    def __init__(self, replies):
        self.replies = list(replies)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return Response(reply)


def decode_jwt(token, secret):
    header, claims, signature = token.split(".")
    signing_input = header + "." + claims
    expected = base64.urlsafe_b64encode(
        hmac.new(secret, signing_input.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    if not hmac.compare_digest(signature, expected):
        raise AssertionError("JWT signature did not verify")
    decode = lambda part: json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    return decode(header), decode(claims)


class CallAuthorizationTests(unittest.TestCase):
    api_key = "0123456789abcdef"
    api_secret = bytes.fromhex("ab" * 32)
    room = "!private-room:matrix.friendline.app"
    user = "@alice:matrix.friendline.app"

    def authorizer(self, replies, now=1_800_000_000):
        opener = FakeOpener(replies)
        matrix = MatrixClient("http://synapse:8008", opener)
        authorizer = CallAuthorizer(matrix, self.api_key, self.api_secret, "wss://calls.friendline.app", lambda: now)
        return authorizer, opener

    def joined_replies(self, device="ALICEDEVICE"):
        return [
            {"user_id": self.user, "device_id": device},
            {"membership": "join"},
            {"algorithm": "m.megolm.v1.aes-sha2"},
        ]

    def test_create_issues_short_lived_room_scoped_least_privilege_token(self):
        authorizer, opener = self.authorizer(self.joined_replies())
        response = authorizer.create_call(self.room, "matrix-secret-token")
        header, claims = decode_jwt(response["token"], self.api_secret)

        self.assertEqual(header, {"alg": "HS256", "typ": "JWT"})
        self.assertEqual(claims["iss"], self.api_key)
        self.assertEqual(claims["iat"], 1_800_000_000)
        self.assertEqual(claims["exp"] - claims["iat"], LIVEKIT_TOKEN_TTL_SECONDS)
        self.assertEqual(claims["video"]["roomJoin"], True)
        self.assertTrue(claims["video"]["room"].startswith("fl-"))
        self.assertTrue(claims["video"]["canSubscribe"])
        self.assertTrue(claims["video"]["canPublish"])
        self.assertEqual(claims["video"]["canPublishSources"], ["camera", "microphone"])
        self.assertFalse(claims["video"]["canPublishData"])
        self.assertFalse(claims["video"]["canUpdateOwnMetadata"])
        self.assertNotIn("screen_share", claims["video"]["canPublishSources"])
        self.assertNotIn("screen_share_audio", claims["video"]["canPublishSources"])
        self.assertNotIn(self.user, claims["sub"])
        self.assertNotIn(self.room, claims["video"]["room"])
        self.assertEqual(response["url"], "wss://calls.friendline.app")
        self.assertEqual(response["call_expires_at"], 1_800_000_000 + CALL_HANDLE_TTL_SECONDS)
        self.assertEqual(len(opener.requests), 3)
        self.assertEqual(opener.requests[0][0].get_header("Authorization"), "Bearer matrix-secret-token")
        self.assertTrue(opener.requests[0][0].full_url.endswith("/account/whoami"))
        self.assertIn("/state/m.room.member/", opener.requests[1][0].full_url)
        self.assertTrue(opener.requests[2][0].full_url.endswith("/state/m.room.encryption"))
        self.assertNotIn("matrix-secret-token", json.dumps(response))

    def test_room_name_is_random_per_call_and_shared_by_members(self):
        authorizer, _ = self.authorizer(self.joined_replies() + self.joined_replies(device="BOBDEVICE"))
        first = authorizer.create_call(self.room, "token-one")
        second = authorizer.create_call(self.room, "token-two")
        first_claims = decode_jwt(first["token"], self.api_secret)[1]
        second_claims = decode_jwt(second["token"], self.api_secret)[1]
        self.assertNotEqual(first["call_id"], second["call_id"])
        self.assertNotEqual(first_claims["video"]["room"], second_claims["video"]["room"])

        join_authorizer, _ = self.authorizer(self.joined_replies(device="BOBDEVICE"))
        joined = join_authorizer.join_call(first["call_id"], self.room, "token-two")
        joined_claims = decode_jwt(joined["token"], self.api_secret)[1]
        self.assertEqual(joined["call_id"], first["call_id"])
        self.assertEqual(joined_claims["video"]["room"], first_claims["video"]["room"])
        self.assertNotEqual(joined_claims["sub"], first_claims["sub"])

    def test_access_token_is_rechecked_for_current_room_membership(self):
        authorizer, _ = self.authorizer([
            {"user_id": self.user, "device_id": "ALICEDEVICE"},
            {"membership": "leave"},
        ])
        with self.assertRaises(RequestError) as raised:
            authorizer.create_call(self.room, "token")
        self.assertEqual((raised.exception.status, raised.exception.code), (403, "room_membership_required"))

    def test_calls_are_refused_in_unencrypted_rooms(self):
        authorizer, _ = self.authorizer([
            {"user_id": self.user, "device_id": "ALICEDEVICE"},
            {"membership": "join"},
            {},
        ])
        with self.assertRaises(RequestError) as raised:
            authorizer.create_call(self.room, "token")
        self.assertEqual((raised.exception.status, raised.exception.code), (403, "encrypted_room_required"))

    def test_matrix_401_is_reported_as_unauthorized(self):
        error = urllib.error.HTTPError("url", 401, "unauthorized", {}, None)
        authorizer, _ = self.authorizer([error])
        with self.assertRaises(RequestError) as raised:
            authorizer.create_call(self.room, "bad-token")
        self.assertEqual((raised.exception.status, raised.exception.code), (401, "matrix_unauthorized"))

    def test_matrix_unavailable_fails_closed(self):
        error = urllib.error.URLError("upstream details must not be returned")
        authorizer, _ = self.authorizer([error])
        with self.assertRaises(RequestError) as raised:
            authorizer.create_call(self.room, "token")
        self.assertEqual((raised.exception.status, raised.exception.code), (503, "matrix_unavailable"))

    def test_call_handle_is_bound_to_room_signed_and_expires(self):
        authorizer, _ = self.authorizer(self.joined_replies())
        created = authorizer.create_call(self.room, "token")
        call_id = created["call_id"]

        with self.assertRaises(RequestError):
            authorizer._verify_call_handle(call_id, "!different:matrix.friendline.app")
        with self.assertRaises(RequestError):
            authorizer._verify_call_handle(call_id[:-1] + ("A" if call_id[-1] != "A" else "B"), self.room)

        expired_authorizer, _ = self.authorizer([], now=1_800_000_000 + CALL_HANDLE_TTL_SECONDS + 1)
        with self.assertRaises(RequestError):
            expired_authorizer._verify_call_handle(call_id, self.room)

    def test_whoami_and_membership_use_quoted_fixed_matrix_paths(self):
        unusual_room = "!room/name:matrix.friendline.app"
        authorizer, opener = self.authorizer([
            {"user_id": self.user, "device_id": "ALICEDEVICE"},
            {"membership": "join"},
            {"algorithm": "m.megolm.v1.aes-sha2"},
        ])
        authorizer.create_call(unusual_room, "token")
        self.assertIn("%21room%2Fname%3Amatrix.friendline.app", opener.requests[1][0].full_url)
        self.assertEqual(opener.requests[0][1], 5)

    def test_token_identity_does_not_reuse_matrix_user_or_device_id(self):
        authorizer, _ = self.authorizer(self.joined_replies())
        response = authorizer.create_call(self.room, "token")
        claims = decode_jwt(response["token"], self.api_secret)[1]
        self.assertRegex(claims["sub"], r"^p-[A-Za-z0-9_-]+$")
        self.assertNotIn(self.user, claims["sub"])
        self.assertNotIn("ALICEDEVICE", claims["sub"])

    def test_missing_matrix_device_id_uses_a_private_token_fingerprint(self):
        authorizer, _ = self.authorizer([
            {"user_id": self.user}, {"membership": "join"},
            {"algorithm": "m.megolm.v1.aes-sha2"},
            {"user_id": self.user}, {"membership": "join"},
            {"algorithm": "m.megolm.v1.aes-sha2"},
        ])
        first = authorizer.create_call(self.room, "token-one")
        second = authorizer.create_call(self.room, "token-two")
        first_sub = decode_jwt(first["token"], self.api_secret)[1]["sub"]
        second_sub = decode_jwt(second["token"], self.api_secret)[1]["sub"]
        self.assertNotEqual(first_sub, second_sub)
        self.assertNotIn("token-one", first_sub)
        self.assertNotIn("token-two", second_sub)


class RequestParsingTests(unittest.TestCase):
    class StubHandler:
        def __init__(self, raw=b"", headers=None):
            self.rfile = io.BytesIO(raw)
            self.headers = Message()
            for key, value in (headers or {}).items():
                self.headers[key] = value

    def test_json_body_rejects_extra_media_key_and_duplicate_keys(self):
        extra = b'{"matrix_room_id":"!r:hs","media_key":"secret"}'
        parsed = _read_request_object(self.StubHandler(extra, {"Content-Type": "application/json", "Content-Length": str(len(extra))}))
        with self.assertRaises(RequestError):
            _require_exact_keys(parsed, {"matrix_room_id"})

        duplicate = b'{"matrix_room_id":"!r:hs","matrix_room_id":"!r:other"}'
        with self.assertRaises(RequestError):
            _read_request_object(self.StubHandler(duplicate, {"Content-Type": "application/json", "Content-Length": str(len(duplicate))}))

    def test_body_requires_json_and_has_a_strict_size_limit(self):
        with self.assertRaises(RequestError):
            _read_request_object(self.StubHandler(b"{}", {"Content-Type": "text/plain", "Content-Length": "2"}))
        huge = b" " * (8 * 1024 + 1)
        with self.assertRaises(RequestError):
            _read_request_object(self.StubHandler(huge, {"Content-Type": "application/json", "Content-Length": str(len(huge))}))

    def test_bearer_token_is_required_and_bounded(self):
        with self.assertRaises(RequestError):
            _bearer_token(self.StubHandler(headers={"Authorization": "Basic token"}))
        token = "x" * 4097
        with self.assertRaises(RequestError):
            _bearer_token(self.StubHandler(headers={"Authorization": "Bearer " + token}))

    def test_handler_suppresses_default_http_request_logging(self):
        authorizer, _ = CallAuthorizationTests().authorizer([])
        handler_class = make_handler(authorizer)
        self.assertIsNone(handler_class.log_message(None, "client log test"))

    def test_livekit_key_file_requires_private_mode_and_strict_hex_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "keys.yaml"
            path.write_text("0123456789abcdef: " + "ab" * 32 + "\n", encoding="ascii")
            with mock.patch("call_auth.os.stat") as mocked_stat:
                mocked_stat.return_value.st_mode = stat.S_IFREG | 0o600
                self.assertEqual(load_livekit_key(str(path)), ("0123456789abcdef", bytes.fromhex("ab" * 32)))

                mocked_stat.return_value.st_mode = stat.S_IFREG | 0o644
                with self.assertRaises(RuntimeError):
                    load_livekit_key(str(path))

            path.write_text("0123456789abcdef: not-a-secret\n", encoding="ascii")
            with mock.patch("call_auth.os.stat") as mocked_stat:
                mocked_stat.return_value.st_mode = stat.S_IFREG | 0o600
                with self.assertRaises(RuntimeError):
                    load_livekit_key(str(path))


if __name__ == "__main__":
    unittest.main()
