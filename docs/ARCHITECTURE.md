# Architecture

**Decision status:** the architecture and product direction are approved. The isolated Android same-device offline restart gate has passed; cross-platform verification and several other security gates remain open. Phase 0 leads directly into implementation without another approval stop. See [PHASE_0_ANALYSIS.md](PHASE_0_ANALYSIS.md).

## Decision

Use native UI on each platform and the Matrix Rust SDK as the messaging, encryption, synchronization, and local-store engine. Run one private Synapse homeserver backed by PostgreSQL. Do not add a custom message protocol, custom cryptography, or a separate application backend in the MVP.

```text
Android: Kotlin + Compose ─┐
                           ├─ Matrix Rust SDK ─ HTTPS/WebSocket as used by SDK ─ Synapse ─ PostgreSQL
iOS: Swift + SwiftUI ──────┘                                            └─ encrypted media store (later)
```

The UI and OS integration are native; Matrix protocol and cryptographic behavior are shared through the SDK implementation. Each platform still needs an adapter for secure store-key persistence, lifecycle, network state, notifications, and media pickers. The [architecture diagram](../design/private-messenger-architecture.html) shows this boundary.

## Cryptographic protocol

Use Matrix room encryption through the Matrix Rust SDK. Matrix uses Olm for device-to-device encrypted communication and Megolm for room message encryption; the SDK owns session creation, key distribution, verification, rotation, and decryption. Application code must not implement primitives or fall back to unencrypted room events.

One-to-one rooms are invite-only and encrypted before a conversation becomes usable. The interface displays an actionable error if encryption cannot be enabled or a message cannot be decrypted. Device verification is exposed through the SDK's verification flow; users can compare a QR or short verification code in person.

Default trust policy: do not share room keys with new or changed unverified devices. Show a clear warning and require explicit verification/trust before key sharing. Prove the exact SDK trust-filter behavior in Phase 0; if the pinned SDK cannot enforce this, revisit the recommendation before implementation.

## Identity and friend addition

- Provision Matrix accounts manually for the small invited group; public registration stays disabled.
- Use a Matrix ID and homeserver address as the initial stable identity. Do not request phone numbers or upload contacts.
- Add a friend by scanning a QR that is encoded and decoded locally and contains only the Matrix ID and homeserver URL. Do not resolve it through a public link preview or discovery service.
- Resolve the friend directly on the configured homeserver, then create an invite-only direct room.
- Do not publish direct-room names or avatars as room state. Derive the conversation title from locally cached profile data where possible.
- Treat a newly added or changed device as unverified until the users complete device verification. Explain that device changes can indicate a reinstall, lost device, or compromise.

## Account and key storage

The SDK owns Matrix account, device, Olm, Megolm, and cross-signing state in its SQLite store. Supply a random 32-byte store key at client construction. The pinned SDK uses value-level store encryption: a wrong key is rejected, and the Phase 0 Android marker scan found no plaintext message marker in the checked SQLite, WAL, and cache files. This is not whole-file SQLite encryption; database structure and some non-sensitive metadata may remain visible. The separate search index is configured with a password derived from the same random store key, and Android peer acceptance exercised local search; its at-rest representation has not been inspected. Finish WAL/journal, index, backup, crash-log, and iOS checks before making a complete local-at-rest claim. Never put the key in a preference, source file, log, or backup.

- Android: wrap the random store key with an AES-GCM key generated in Android Keystore; keep only the wrapped value in app-private storage. Require unlock according to the chosen app-lock policy. Exclude the store and wrapped key from Android backup.
- iOS: store the random store key in Keychain with `ThisDeviceOnly` accessibility; choose `WhenUnlockedThisDeviceOnly` if foreground-only access is acceptable, since tighter locking can constrain background work. Exclude the SDK store from iCloud/device backup unless a separately designed encrypted recovery feature is later approved. [Apple Keychain accessibility](https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility)
- A reinstall or lost Keychain/Keystore key can make the encrypted store inaccessible. Do not imply that the server can restore old message history.

## Offline-first message flow

1. The user presses Send. The UI submits the text to the room timeline/send queue and renders the SDK's local echo immediately.
2. The Matrix Rust SDK encrypts the room event locally. Its pending operation/timeline persists in the keyed SDK store. Isolated Android API 37 acceptance now covers offline send through force-stop/restart/reconnect, recipient-offline backlog delivery, and encrypted attachment recovery. Android-to-iOS interoperability, real-device coverage, and broader network/lifecycle cases remain open.
3. If the network is available, the SDK submits an `m.room.encrypted` event to Synapse with a stable transaction ID. Reuse that ID for idempotent retries of the same send operation. [Matrix transaction identifiers](https://spec.matrix.org/v1.18/client-server-api/#transaction-identifiers)
4. Synapse stores the event and routes it to the recipient's sync stream. It stores ciphertext and routing metadata, not the message body.
5. The recipient's SDK syncs the event, resolves room keys locally, decrypts it, and persists it in the local timeline.
6. Only after successful local persistence, the recipient emits a small encrypted app-level delivery acknowledgement referencing the original event ID. The sender marks **Delivered** after receiving it. This means the recipient client decrypted and persisted the message; it does not mean a person read it. Android's isolated peer acceptance verified encrypted acknowledgements, recipient-offline delivery, read receipts, and per-member group counts. Retry-after-process-death, changed/unverified-device key exclusion, and cross-platform receipt delivery still need acceptance coverage. Matrix read receipts do not represent device delivery.
7. Sender and recipient timelines converge through `/sync`; the SDK maps the local echo to the server event. The server sync token supports incremental catch-up after disconnection. [Matrix `/sync`](https://spec.matrix.org/v1.18/client-server-api/#get_matrixclientv3sync)
8. If the sender is offline, the SDK's persisted send queue must survive process death and resume when connectivity returns. If the recipient is offline, Synapse retains the encrypted event until the next sync. The isolated Android API 37 acceptance passed both sender-outbox restart and recipient-offline backlog cases; Android-to-iOS and physical-device acceptance remain open.

The app must not add a second delivery queue unless the pinned SDK demonstrably fails a required offline/restart case. Any fallback queue must store only SDK-encrypted events and use stable transaction IDs; it must never store a second plaintext copy.

## Sync, delivery, and platform lifecycle

Use the SDK sync service and room timeline APIs instead of implementing `/sync`. The client store retains the sync token and event state. Use event IDs and SDK transaction IDs for de-duplication; do not infer delivery from socket writes. In direct chats, the recipient acknowledgement marks the message delivered. In groups, record the expected joined/invited non-self members for each message and deduplicate acknowledgements by room, event, and Matrix sender; show a per-member count until every expected member has acknowledged. An acknowledgement does not mean a person read the message.

On Android, keep foreground synchronization under the SDK lifecycle while the app is active; background sync and WorkManager are opportunistic. Reliable wakeups require push. On iOS, stop the SDK sync service and pause the client before suspension, then resume it when foregrounded; this releases SQLite locks for iOS background suspension. The operating systems may delay or omit background work. Message persistence and next-foreground sync are the reliability baseline.

## Server and database

Synapse is the only application server. Use PostgreSQL for all practical deployments. Disable open registration, guest accounts, identity-server lookups, URL previews, and federation for the small private installation. Provision users through the server's local admin command. Keep media in Synapse-managed storage initially; encrypted media object storage can be considered when media lands.

Do not publish the PostgreSQL port. For deployment, put Synapse behind HTTPS and restrict inbound ports. Keep Synapse signing keys, registration secret, database credentials, and backup encryption keys outside Git and outside chat.

## Notifications and metadata

Native APNs/FCM client lifecycle wiring is in place, but push stays disabled until real provider delivery and payloads are validated. Treat push as a best-effort wake-up only; `/sync` remains the source of truth. Use generic notification text and inspect actual push-gateway payloads before enabling it. Do not include a message body, sender name, room title, attachment name, or encryption key. Routing data, device tokens, and timing may still be visible to the homeserver/gateway/provider. [Matrix event-ID-only push](https://spec.matrix.org/v1.18/client-server-api/#post_matrixclientv3pushersset), [push gateway API](https://spec.matrix.org/v1.18/push-gateway-api/)

E2EE does not hide all metadata. Synapse and the network can still observe account and device identifiers, room membership and routing, event timing/order/size, IP addresses, login/sync activity, and unencrypted profile or room state. Read receipts, typing, presence, and push registrations also reveal activity. The server operator controls the storage and availability of ciphertext.

## Complexity and proposed phases

| Phase | Scope | Complexity |
|---|---|---|
| 0 | Security and architecture analysis, then a two-platform encryption/offline proof of concept | Medium–high |
| 1 | Cross-platform encrypted 1:1 messaging, durable offline send, queued/sent/delivered status, encrypted app acknowledgement, and generic push | High |
| 2 | Opt-in read receipts, typing, replies, reactions, small-group rooms/per-member delivery semantics, search, pagination, lifecycle polish | Medium–high |
| 3 | Encrypted image/video/file/voice transfer and interrupted-transfer handling | High |
| 4 | Voice/video calls after message delivery and media reliability are stable; native MatrixRTC/LiveKit integration and a private RTC backend | Very high |

See [CALLS.md](CALLS.md) for current native RTC packages, platform constraints, the incomplete incoming-call path, server requirements, and acceptance checks.

## Feasibility notes and limitations

- Matrix Rust SDK has official FFI bindings for Kotlin/Android and Swift/Apple platforms. The Swift components package explicitly says its API is unstable, so its version must be pinned and API changes reviewed.
- SDK FFI changelog documents a 32-byte SQLite store key and Kotlin/Swift generation examples. The pinned Android binding has passed encrypted-store and live-login checks; the pinned iOS binding still needs Xcode typechecking and a live login check.
- The pinned Android and iOS bindings expose encrypted-store configuration and send-queue APIs. Android has passed isolated server-ciphertext, keyed-store, sender-offline restart, recipient-offline delivery, encrypted acknowledgement, group per-member delivery, message edit/redact, and encrypted-attachment recovery checks. The iOS binding still needs an Xcode build, and cross-platform/device acceptance remains open.
- Treat homeserver acceptance, recipient-client delivery acknowledgement, and user read receipt as different states. Android peer acceptance verified the encrypted app-level acknowledgement after recipient persistence; retry/deduplication after acknowledgement loss or process death remain open.
- Matrix's media API does not specify resumable byte-range upload. Start with retrying a complete ciphertext upload; decide on an extension/backend only if resumable media is a firm Phase 3 requirement. [Matrix media upload API](https://spec.matrix.org/v1.18/client-server-api/#post_matrixmedia-v3upload)
- Synapse is the reference Matrix homeserver. Official installation guidance recommends PostgreSQL for practical servers and HTTPS outside localhost testing.
- This Windows machine has no Xcode or Swift. iOS builds, simulator evidence, Apple signing, and iOS device testing are unavailable locally until a Mac with Xcode is configured.
- Android debug, instrumentation, and minified release APK builds succeed in the isolated Linux Gradle container; the API 37 emulator has restored sessions, synced encrypted rooms, and passed isolated peer/group/offline-attachment acceptance. Cross-platform interoperability, physical-device coverage, and iOS acceptance remain open.

## Sources

- [Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk)
- [Matrix Rust SDK Swift components and stability note](https://github.com/matrix-org/matrix-rust-components-swift)
- [Matrix Rust SDK FFI changelog](https://github.com/matrix-org/matrix-rust-sdk/blob/main/bindings/matrix-sdk-ffi/CHANGELOG.md)
- [Matrix end-to-end encryption](https://matrix.org/docs/matrix-concepts/end-to-end-encryption/)
- [Matrix Client-Server API: transaction identifiers and sync](https://spec.matrix.org/latest/client-server-api/)
- [Synapse installation and PostgreSQL](https://element-hq.github.io/synapse/latest/setup/installation.html)
- [Synapse configuration manual](https://element-hq.github.io/synapse/latest/usage/configuration/config_documentation.html)
