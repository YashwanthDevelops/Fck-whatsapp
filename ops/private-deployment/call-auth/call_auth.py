#!/usr/bin/env python3
"""Small Matrix-membership-gated LiveKit token issuer for private calls.

This process deliberately has no persistence and does not accept media keys,
message content, notification payloads, or arbitrary LiveKit grants.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import stat
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable


BASE_PATH = "/_friendline/calls/v1/calls"
CALL_HANDLE_TTL_SECONDS = 12 * 60 * 60
LIVEKIT_TOKEN_TTL_SECONDS = 5 * 60
MAX_BODY_BYTES = 8 * 1024
MAX_ACCESS_TOKEN_BYTES = 4 * 1024
MAX_MATRIX_ID_BYTES = 255

_SAFE_TOKEN = re.compile(r"^[\x21-\x7e]{1,4096}$")
_SAFE_HANDLE = re.compile(r"^v1\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)$")
_SAFE_LIVEKIT_KEY = re.compile(r"^[a-f0-9]{16,64}$")


class RequestError(Exception):
    """An expected request rejection safe to return to a client."""

    def __init__(self, status: int, code: str):
        super().__init__(code)
        self.status = status
        self.code = code


class MatrixUnavailable(Exception):
    pass


class MatrixUnauthorized(Exception):
    pass


class DuplicateJsonKey(ValueError):
    pass


def _json_object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey("duplicate JSON key")
        result[key] = value
    return result


def _reject_json_constant(_: str) -> None:
    raise ValueError("non-standard JSON constant")


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _decode_b64url(value: str) -> bytes:
    if not value or "=" in value:
        raise ValueError("invalid base64url")
    decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    if _b64url(decoded) != value:
        raise ValueError("non-canonical base64url")
    return decoded


def _canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _validate_matrix_id(value: Any, sigil: str) -> str:
    if not isinstance(value, str):
        raise RequestError(400, "invalid_matrix_id")
    try:
        encoded = value.encode("utf-8", "strict")
    except UnicodeEncodeError:
        raise RequestError(400, "invalid_matrix_id") from None
    if (
        len(encoded) > MAX_MATRIX_ID_BYTES
        or not value.startswith(sigil)
        or ":" not in value[1:]
        or any(ord(char) < 0x21 or ord(char) == 0x7F for char in value)
    ):
        raise RequestError(400, "invalid_matrix_id")
    return value


def load_livekit_key(path: str) -> tuple[str, bytes]:
    """Read the single API key mapping without exposing it in errors/logs."""
    try:
        if stat.S_IMODE(os.stat(path).st_mode) & 0o077:
            raise RuntimeError("LiveKit key file permissions must be 0600 or stricter")
        with open(path, "rt", encoding="ascii") as key_file:
            lines = key_file.read(1024).splitlines()
    except RuntimeError:
        raise
    except (OSError, UnicodeError):
        raise RuntimeError("LiveKit key file is unavailable") from None

    if len(lines) != 1 or ":" not in lines[0]:
        raise RuntimeError("LiveKit key file must contain one API key mapping")
    api_key, api_secret = (part.strip() for part in lines[0].split(":", 1))
    if (
        not _SAFE_LIVEKIT_KEY.fullmatch(api_key)
        or not re.fullmatch(r"[a-f0-9]{64}", api_secret)
    ):
        raise RuntimeError("LiveKit key file has an invalid format")
    return api_key, bytes.fromhex(api_secret)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: urllib.request.Request, fp: Any, code: int,
                         msg: str, headers: Any, newurl: str) -> None:
        return None


class MatrixClient:
    """Read-only Matrix calls; the configured homeserver is the only target."""

    def __init__(self, base_url: str, opener: Any | None = None):
        parsed = urllib.parse.urlsplit(base_url)
        if (
            parsed.scheme != "http"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.path not in ("", "/")
            or parsed.query
            or parsed.fragment
        ):
            raise RuntimeError("MATRIX_BASE_URL must be an internal HTTP origin")
        self.base_url = base_url.rstrip("/")
        self.opener = opener or urllib.request.build_opener(_NoRedirect())

    def _get(self, path: str, access_token: str) -> dict[str, Any]:
        request = urllib.request.Request(
            self.base_url + path,
            headers={
                "Authorization": "Bearer " + access_token,
                "Accept": "application/json",
                "User-Agent": "Friendline-Call-Auth/1",
            },
            method="GET",
        )
        try:
            response = self.opener.open(request, timeout=5)
            with response:
                raw = response.read(64 * 1024 + 1)
        except urllib.error.HTTPError as error:
            if error.code == 401:
                raise MatrixUnauthorized() from None
            if error.code in (403, 404) and "/state/m.room.member/" in path:
                return {"membership": "not_joined"}
            if error.code == 404 and path.endswith("/state/m.room.encryption"):
                return {}
            raise MatrixUnavailable() from None
        except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
            raise MatrixUnavailable() from None
        if len(raw) > 64 * 1024:
            raise MatrixUnavailable()
        try:
            parsed = json.loads(raw, parse_constant=_reject_json_constant)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            raise MatrixUnavailable() from None
        if not isinstance(parsed, dict):
            raise MatrixUnavailable()
        return parsed

    def whoami(self, access_token: str) -> tuple[str, str | None]:
        result = self._get("/_matrix/client/v3/account/whoami", access_token)
        try:
            user_id = _validate_matrix_id(result.get("user_id"), "@")
        except RequestError:
            raise MatrixUnavailable() from None
        device_id = result.get("device_id")
        if device_id is not None:
            try:
                encoded_device_id = device_id.encode("utf-8", "strict") if isinstance(device_id, str) else b""
            except UnicodeEncodeError:
                raise MatrixUnavailable() from None
            if (
                not isinstance(device_id, str)
                or not device_id
                or len(encoded_device_id) > 255
                or any(ord(char) < 0x21 or ord(char) == 0x7F for char in device_id)
            ):
                raise MatrixUnavailable()
        return user_id, device_id

    def is_joined(self, room_id: str, user_id: str, access_token: str) -> bool:
        path = "/_matrix/client/v3/rooms/{}/state/m.room.member/{}".format(
            urllib.parse.quote(room_id, safe=""), urllib.parse.quote(user_id, safe="")
        )
        try:
            result = self._get(path, access_token)
        except MatrixUnauthorized:
            raise
        return result.get("membership") == "join"

    def is_encrypted_room(self, room_id: str, access_token: str) -> bool:
        path = "/_matrix/client/v3/rooms/{}/state/m.room.encryption".format(
            urllib.parse.quote(room_id, safe="")
        )
        result = self._get(path, access_token)
        return result.get("algorithm") == "m.megolm.v1.aes-sha2"


class CallAuthorizer:
    def __init__(
        self,
        matrix: MatrixClient,
        api_key: str,
        api_secret: bytes,
        livekit_url: str,
        clock: Callable[[], int] = lambda: int(time.time()),
    ):
        parsed = urllib.parse.urlsplit(livekit_url)
        if (
            parsed.scheme != "wss"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.path not in ("", "/")
            or parsed.query
            or parsed.fragment
        ):
            raise RuntimeError("LIVEKIT_URL must be a secure websocket origin")
        self.matrix = matrix
        self.api_key = api_key
        self.api_secret = api_secret
        self.livekit_url = livekit_url.rstrip("/")
        self.clock = clock
        self.handle_key = hmac.new(api_secret, b"friendline-call-handle-v1", hashlib.sha256).digest()

    def _authenticate_member(self, room_id: Any, access_token: str) -> tuple[str, str | None, str]:
        room_id = _validate_matrix_id(room_id, "!")
        try:
            user_id, device_id = self.matrix.whoami(access_token)
            joined = self.matrix.is_joined(room_id, user_id, access_token)
            encrypted = joined and self.matrix.is_encrypted_room(room_id, access_token)
        except MatrixUnauthorized:
            raise RequestError(401, "matrix_unauthorized") from None
        except MatrixUnavailable:
            raise RequestError(503, "matrix_unavailable") from None
        if not joined:
            raise RequestError(403, "room_membership_required")
        if not encrypted:
            raise RequestError(403, "encrypted_room_required")
        return user_id, device_id, room_id

    def _room_digest(self, room_id: str) -> bytes:
        return hmac.new(self.handle_key, b"matrix-room-v1\0" + room_id.encode("utf-8"), hashlib.sha256).digest()

    def create_call(self, room_id: Any, access_token: str) -> dict[str, Any]:
        user_id, device_id, room_id = self._authenticate_member(room_id, access_token)
        issued_at = self.clock()
        nonce = secrets.token_bytes(24)
        payload = issued_at.to_bytes(8, "big") + nonce + self._room_digest(room_id)
        encoded = _b64url(payload)
        signature = _b64url(hmac.new(self.handle_key, b"handle-v1\0" + payload, hashlib.sha256).digest())
        call_id = "v1." + encoded + "." + signature
        return self._response(call_id, payload, user_id, device_id, issued_at, access_token)

    def join_call(self, call_id: str, room_id: Any, access_token: str) -> dict[str, Any]:
        user_id, device_id, room_id = self._authenticate_member(room_id, access_token)
        payload = self._verify_call_handle(call_id, room_id)
        issued_at = int.from_bytes(payload[:8], "big")
        return self._response(call_id, payload, user_id, device_id, issued_at, access_token)

    def _verify_call_handle(self, call_id: str, room_id: str) -> bytes:
        match = _SAFE_HANDLE.fullmatch(call_id)
        if not match or len(call_id) > 256:
            raise RequestError(404, "call_not_found")
        try:
            payload = _decode_b64url(match.group(1))
            supplied_signature = _decode_b64url(match.group(2))
        except (ValueError, base64.binascii.Error):
            raise RequestError(404, "call_not_found") from None
        expected_signature = hmac.new(self.handle_key, b"handle-v1\0" + payload, hashlib.sha256).digest()
        if len(payload) != 64 or not hmac.compare_digest(supplied_signature, expected_signature):
            raise RequestError(404, "call_not_found")
        issued_at = int.from_bytes(payload[:8], "big")
        now = self.clock()
        if issued_at > now + 30 or now - issued_at > CALL_HANDLE_TTL_SECONDS:
            raise RequestError(404, "call_not_found")
        if not hmac.compare_digest(payload[32:], self._room_digest(room_id)):
            raise RequestError(404, "call_not_found")
        return payload

    def _response(
        self,
        call_id: str,
        payload: bytes,
        user_id: str,
        device_id: str | None,
        issued_at: int,
        access_token: str,
    ) -> dict[str, Any]:
        nonce = payload[8:32]
        room_digest = payload[32:64]
        room_name = "fl-" + _b64url(
            hmac.new(self.handle_key, b"livekit-room-v1\0" + room_digest + nonce, hashlib.sha256).digest()
        )
        device_identity = device_id
        if device_identity is None:
            # whoami may omit device_id for unusual token types; keep distinct
            # Matrix sessions distinct without revealing the bearer token.
            device_identity = _b64url(
                hmac.new(self.handle_key, b"matrix-token-fingerprint-v1\0" + access_token.encode("ascii"), hashlib.sha256).digest()
            )
        identity_material = (user_id + "\0" + device_identity).encode("utf-8")
        identity = "p-" + _b64url(
            hmac.new(self.handle_key, b"livekit-participant-v1\0" + nonce + identity_material, hashlib.sha256).digest()
        )
        now = self.clock()
        token = self._livekit_jwt(room_name, identity, now)
        return {
            "call_id": call_id,
            "url": self.livekit_url,
            "token": token,
            "expires_at": now + LIVEKIT_TOKEN_TTL_SECONDS,
            "call_expires_at": issued_at + CALL_HANDLE_TTL_SECONDS,
        }

    def _livekit_jwt(self, room_name: str, identity: str, now: int) -> str:
        header = {"alg": "HS256", "typ": "JWT"}
        claims = {
            "iss": self.api_key,
            "sub": identity,
            "iat": now,
            "nbf": now - 5,
            "exp": now + LIVEKIT_TOKEN_TTL_SECONDS,
            "jti": secrets.token_urlsafe(18),
            "video": {
                "roomJoin": True,
                "room": room_name,
                "canSubscribe": True,
                # Both pinned native client SDKs check canPublish before they
                # inspect canPublishSources. Keep this true and constrain the
                # actual allowed track sources to camera and microphone.
                "canPublish": True,
                "canPublishData": False,
                "canPublishSources": ["camera", "microphone"],
                "canUpdateOwnMetadata": False,
            },
        }
        signing_input = _b64url(_canonical_json(header)) + "." + _b64url(_canonical_json(claims))
        signature = hmac.new(self.api_secret, signing_input.encode("ascii"), hashlib.sha256).digest()
        return signing_input + "." + _b64url(signature)


def _read_request_object(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    content_types = handler.headers.get_all("Content-Type", [])
    if len(content_types) != 1:
        raise RequestError(415, "application_json_required")
    content_type = content_types[0].split(";", 1)[0].strip().lower()
    if content_type != "application/json":
        raise RequestError(415, "application_json_required")
    if handler.headers.get_all("Transfer-Encoding", []):
        raise RequestError(400, "invalid_request")
    try:
        lengths = handler.headers.get_all("Content-Length", [])
        if len(lengths) != 1:
            raise RequestError(400, "invalid_request")
        raw_length = lengths[0]
        if raw_length is None or not raw_length.isascii() or not raw_length.isdecimal():
            raise RequestError(400, "invalid_request")
        length = int(raw_length)
    except (ValueError, OverflowError):
        raise RequestError(400, "invalid_request") from None
    if length < 1 or length > MAX_BODY_BYTES:
        raise RequestError(413 if length > MAX_BODY_BYTES else 400, "invalid_request")
    raw = handler.rfile.read(length)
    if len(raw) != length:
        raise RequestError(400, "invalid_request")
    try:
        result = json.loads(
            raw,
            object_pairs_hook=_json_object_no_duplicates,
            parse_constant=_reject_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, DuplicateJsonKey, ValueError):
        raise RequestError(400, "invalid_request") from None
    if not isinstance(result, dict):
        raise RequestError(400, "invalid_request")
    return result


def _require_exact_keys(body: dict[str, Any], allowed: set[str]) -> None:
    if set(body) != allowed:
        raise RequestError(400, "invalid_request")


def _bearer_token(handler: BaseHTTPRequestHandler) -> str:
    values = handler.headers.get_all("Authorization", [])
    if len(values) != 1:
        raise RequestError(401, "matrix_unauthorized")
    value = values[0]
    if not value.startswith("Bearer "):
        raise RequestError(401, "matrix_unauthorized")
    token = value[7:]
    if not _SAFE_TOKEN.fullmatch(token) or len(token.encode("ascii")) > MAX_ACCESS_TOKEN_BYTES:
        raise RequestError(401, "matrix_unauthorized")
    return token


def make_handler(authorizer: CallAuthorizer) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "FriendlineCallAuth"
        sys_version = ""

        def setup(self) -> None:
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, format: str, *args: Any) -> None:
            # Do not let default access/error logs capture URLs, headers, tokens, or body.
            return

        def _write_json(self, status: int, body: dict[str, Any]) -> None:
            encoded = _canonical_json(body)
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Pragma", "no-cache")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)
            self.close_connection = True

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/healthz":
                self._write_json(200, {"status": "ok"})
                return
            self._write_json(404, {"error": "not_found"})

        def do_POST(self) -> None:  # noqa: N802
            try:
                access_token = _bearer_token(self)
                body = _read_request_object(self)
                if self.path == BASE_PATH:
                    _require_exact_keys(body, {"matrix_room_id"})
                    result = authorizer.create_call(body["matrix_room_id"], access_token)
                else:
                    match = re.fullmatch(re.escape(BASE_PATH) + r"/([^/]+)/join", self.path)
                    if not match:
                        raise RequestError(404, "not_found")
                    _require_exact_keys(body, {"matrix_room_id"})
                    try:
                        call_id = urllib.parse.unquote(match.group(1), errors="strict")
                    except (UnicodeDecodeError, ValueError):
                        raise RequestError(404, "call_not_found") from None
                    result = authorizer.join_call(call_id, body["matrix_room_id"], access_token)
                self._write_json(200, result)
            except RequestError as error:
                self._write_json(error.status, {"error": error.code})
            except Exception:
                self._write_json(500, {"error": "internal_error"})

        def do_PUT(self) -> None:  # noqa: N802
            self._write_json(405, {"error": "method_not_allowed"})

        def do_DELETE(self) -> None:  # noqa: N802
            self._write_json(405, {"error": "method_not_allowed"})

    return Handler


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """Bound concurrent public requests so slow clients cannot grow threads forever."""

    request_queue_size = 32
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address: tuple[str, int], handler_class: type[BaseHTTPRequestHandler]):
        self._request_slots = threading.BoundedSemaphore(32)
        super().__init__(server_address, handler_class)

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._request_slots.release()
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


def _required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(name + " is required")
    return value


def main() -> None:
    api_key, api_secret = load_livekit_key(os.environ.get("LIVEKIT_KEYS_FILE", "/run/secrets/livekit_keys"))
    matrix = MatrixClient(os.environ.get("MATRIX_BASE_URL", "http://synapse:8008"))
    authorizer = CallAuthorizer(
        matrix,
        api_key,
        api_secret,
        _required_env("LIVEKIT_URL"),
    )
    server = BoundedThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), make_handler(authorizer))
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
