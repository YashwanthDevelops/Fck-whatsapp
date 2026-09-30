# Security

## Security goals

- Message bodies and attachments are encrypted on the sender device and decrypted only by authorized devices.
- The Synapse server stores and routes encrypted Matrix events and encrypted media, without message plaintext or media keys when clients use encrypted rooms correctly.
- Encryption/session state and local message history use the SDK's keyed store, with the random store key protected by Android Keystore or Apple Keychain. Android wrong-key rejection and a plaintext-marker scan of SQLite/WAL/cache files have passed. The SDK encrypts stored values, not the complete SQLite file. The Android search index is configured with the same random 32-byte vault key and stored under `noBackupFilesDir`; the Android manifest disables app backup. The pinned SDK source uses an encrypted Tantivy directory when a password is supplied. This is source-level evidence; device-level backup, filesystem, and log checks remain open. iOS applies `NSFileProtectionComplete` and backup exclusion to Matrix storage in source, but this needs device validation.
- Push payloads, application logs, crash diagnostics, and analytics do not contain message text, keys, tokens, or media content.
- Device identity changes are visible and users can verify devices through the Matrix verification flow.

## Security boundaries

The client device is trusted while unlocked and uncompromised. E2EE does not protect plaintext displayed on an infected/unlocked endpoint, screenshots, screen recording, recipient copying, network timing, IP addresses, room membership, or service availability. A malicious or compromised recipient can disclose what they receive.

## Cryptography decisions

- Use Matrix E2EE through the maintained Matrix Rust SDK. Do not write a protocol, key exchange, ratchet, cipher wrapper, or signature scheme ourselves.
- Require encrypted rooms for direct conversations and refuse plaintext fallback.
- Use SDK-managed device verification, cross-signing, one-time keys, room-key distribution, and rotation. Outgoing room keys use Matrix `IdentityBasedStrategy`, which excludes devices without their owner's cross-signature; incoming room events require `TrustRequirement.CrossSigned`. Show verification state and device changes in plain language.
- New devices must be cross-signed before receiving room keys, and room events from owner-unsigned devices must not be displayed as trusted messages. A changed identity that was previously verified must fail closed. This follows the [Matrix MSC4153 guidance](https://github.com/matrix-org/matrix-spec-proposals/blob/main/proposals/4153-invisible-crypto.md); the app's device-acceptance test verifies behavior, not just configuration.
- Do not export Matrix cryptographic keys from the SDK store for application features.
- Configure the SDK's keyed SQLite store and protect its random store key with native secure storage as described in [ARCHITECTURE.md](ARCHITECTURE.md). Treat its value-level encryption separately from whole-file encryption. The Android search-index path is configured for SDK encryption and excluded from backup by location and manifest policy, but device-level search-index, log, and backup checks remain open on both platforms. Do not claim complete local at-rest coverage until wrong-key, WAL/journal, cache, search-index, log, and backup checks pass on both platforms.
- Define **Sent** as homeserver acceptance. Define **Delivered** as an encrypted app acknowledgement emitted after a recipient client decrypts and persists the event. In a group, report acknowledgements per member and never treat one member's acknowledgement as delivery to everyone. Define **Read** separately using an optional Matrix read receipt; it can disclose reading activity to the homeserver and room members depending on receipt type.
- Do not implement cloud key backup in the first release. If introduced later, it needs an independent recovery threat model and client-side encryption.

## Sensitive-data handling

Never log message bodies, decrypted event JSON, device keys, access/refresh tokens, passwords, QR invitation payloads, attachment keys, or signed media URLs. Diagnostic logs should use an event/transaction identifier only if that identifier is necessary, and should redact Matrix IDs and server URLs in any report shared outside the device.

Opening a received attachment on Android creates a decrypted temporary file and passes it to the user's chosen app through a scoped `content://` URI. The app warns before this handoff, deletes its temporary copy when the user returns to Friendline, and enforces a 30-minute expiry. Friendline cannot prevent another app from copying or retaining the file. iOS previews remain inside Quick Look presented by Friendline and the app deletes its decrypted preview when the sheet closes; startup also purges abandoned plaintext media. Sending attachments uses temporary plaintext staging files which are removed after upload or at startup. These controls limit local exposure but do not encrypt plaintext while it is actively opened or being uploaded.

Android caps each outgoing attachment at 32 MiB. Both Synapse configuration generators set `max_upload_size: 34M` (34 MiB); Synapse applies this limit to the uploaded media body, and the extra 2 MiB leaves room for Matrix encrypted-media overhead. Synapse's `M` suffix means MiB ([Synapse configuration reference](https://element-hq.github.io/synapse/latest/usage/configuration/config_documentation.html#max_upload_size)).

Android rejects an incoming attachment before calling the Matrix SDK when its advertised Matrix `info.size` is missing, zero, or above 100 MiB, then checks the decrypted file length again. This 100 MiB value is a separate client download policy, not the server upload limit. The earlier API 37 acceptance used SDK `26.09.9`, whose implementation downloads and decrypts media into complete in-memory buffers before returning a file ([tested SDK media implementation](https://github.com/matrix-org/matrix-rust-sdk/blob/ab673a6d71e6333934cf6cb8f87f9578cdfaed5a/crates/matrix-sdk/src/media.rs#L337-L486)). A source audit of the current `26.09.28` pin confirms the same behavior: `get_media_file` buffers the full response and decrypted plaintext before writing it; the FFI exposes no streaming or maximum-byte parameter ([pinned Matrix Rust SDK source](https://github.com/matrix-org/matrix-rust-sdk/tree/2a3db80e99fe322b9849325a182dc8a634fb20d4/crates/matrix-sdk/src/media.rs)). Thus the metadata check is only an early policy filter, and a false-small size can make the SDK download, decrypt, and possibly cache more than 100 MiB before the post-download check rejects use. A hard client receive limit requires bounded streaming download and decryption in the SDK/FFI. The private Synapse `max_upload_size` constrains media uploaded through this server, but it does not bound clients downloading from arbitrary homeservers.

All builds require an HTTPS homeserver. Debug builds permit cleartext HTTP only for localhost, the Android emulator host, IPv6 loopback, or a numeric RFC1918 private IPv4 address. Release builds reject HTTP at the app layer as well as in Android network security configuration. Do not use an HTTP homeserver on an untrusted network.

Native push lifecycle wiring now supports opt-in, token rotation, session restore/foreground refresh, generic notification text, and pusher removal before sign-out. The local and private-deployment Synapse configuration sets `push.include_content: false`, so message bodies are omitted from push notification pokes; sender, room, event, and timing metadata may still be present. Keep push delivery disabled until real APNs/FCM provider setup and payload capture have been validated end to end; do not register a content-bearing push rule. Push is a best-effort wake-up, while Matrix sync remains the source of truth. iOS gates sign-out when pusher removal cannot be confirmed, but this recovery path still needs Xcode/device validation.

Android application data backups must exclude the Matrix store and key envelope. iOS backups must not restore the Matrix store without its device-only Keychain key. Secure-screen behavior is a user setting and cannot prevent an external camera from photographing the screen.

## Server controls

- Keep public registration, guest access, identity lookups, URL previews, and federation disabled for this private installation.
- Restrict homeserver and administrative access; do not publish the database port.
- Require HTTPS for non-local use and keep all secret material in local secret files or an OS/CI secret store.
- Run PostgreSQL with authenticated access outside the local development Compose network; local Compose's trust mode is not suitable for deployment.
- Back up Synapse and PostgreSQL data together, encrypt backups off-device, restrict access, and test restores. Backups contain ciphertext plus account/server metadata.
- Patch Synapse and client libraries promptly, pin deployed image tags/digests, and review Matrix Rust SDK security advisories.

## Release gates

Before sharing a build with friends:

1. Confirm a test room is encrypted and inspect raw homeserver event data for ciphertext.
2. Kill/restart each client during an offline queued send and verify the message arrives once after reconnection.
3. Verify the recipient acknowledgement is encrypted, sent only after local persistence, deduplicated, and not confused with a read receipt.
4. Inspect Android/iOS data backups and local files; confirm no recoverable plaintext copy or unwrapped store key is present and verify database/WAL/cache encryption behavior.
5. Inspect logs, notification payloads, and crash reports for sensitive content.
6. Verify the account and devices on both platforms; confirm an unverified/changed device cannot receive room keys until verified and that the warning is clear.
7. Review Synapse registration, federation, TLS, database authentication, access control, backups, and image tags.

The latest isolated Android peer acceptance passed encrypted direct-room setup, recipient-offline backlog delivery, encrypted app acknowledgement, receipt-state projection, typing, replies, reactions, local search, and exactly-once encrypted attachment recovery after a force-stop and server restart. Its three-member group test reached `Delivered to 1 of 2` with one recipient offline and `Delivered to all 2` after that recipient reconnected and decrypted the message. The read-receipt repair reads the SDK's local receipt store and updates the active message projection; the homeserver still receives the standard Matrix read receipt, which may expose reading activity. SAS verification completed on both peers and the strengthened acceptance waited for both cross-signing identity views to converge to verified. This does not yet prove that an unverified extra device is excluded from room-key delivery or that changed pinned identities fail closed. Android↔iOS interoperability, APNs/FCM payload inspection, on-device backup/log verification, iOS store/backup checks, physical-device security behavior, calls, and production deployment remain unverified. See [TESTING.md](TESTING.md) for evidence and limits. The project is not currently a secure messaging product for daily use until the complete implementation and release tests demonstrate the required properties.
