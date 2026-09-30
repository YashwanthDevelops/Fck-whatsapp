# Phase 0 — Architecture and security recommendation

**Status:** architecture and product direction approved on 2026-09-28. Phase 0 implementation and validation are authorized. Once the Phase 0 gate is satisfied, proceed directly through the product phases without another approval stop.

## 1. Recommendation

The one-page topology is available as the [private messenger architecture diagram](../design/private-messenger-architecture.html).

Build a private Matrix client with native Android and iOS interfaces:

- Android: Kotlin and Jetpack Compose, using the official Matrix Rust SDK Android binding.
- iOS: Swift and SwiftUI, using the official Matrix Rust SDK Swift package.
- Messaging, E2EE, device/key state, sync, timeline, local send queue, and keyed SQLite values: Matrix Rust SDK on each platform. The pinned SDK uses value-level at-rest encryption rather than whole-file SQLite encryption; Android wrong-key and plaintext-marker checks have passed on the files inspected. Search-index, backup, diagnostics, and iOS checks remain open.
- Server: one private Synapse homeserver with PostgreSQL. No bespoke message server, broker, Redis, Kubernetes, or public discovery service.
- Accounts: manually provisioned Matrix IDs on a stable private homeserver. Friends exchange a Matrix ID and homeserver through a locally scanned QR code; devices are verified separately in the Matrix verification flow.

This keeps the user experience native while reusing one protocol/crypto implementation family. The Rust SDK describes itself as a batteries-included Matrix client SDK for encryption, sync, and room state and documents bindings for Swift and Kotlin. Matrix E2EE is optional at the protocol level, so both clients must explicitly require an encrypted room and fail closed. [Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk), [Matrix E2EE specification](https://spec.matrix.org/latest/client-server-api/#end-to-end-encryption)

**Binding compatibility check:** the official Android and Swift distributions are released separately. The candidate releases identified on 2026-09-28 are Kotlin `26.09.9` (Rust SDK commit `ab673a6d71e6333934cf6cb8f87f9578cdfaed5a`) and Swift `26.08.11` (Rust SDK commit `15a3ce2369a1e96fcad02cb2b1da3198ef23348a`). These revisions differ. Matrix wire interoperability is expected, but the clients must not be paired blindly: Phase 0 must align the underlying Rust revisions where practical, or review the differences and verify security-fix parity plus both cross-platform directions. [Kotlin release](https://github.com/matrix-org/matrix-rust-components-kotlin/releases/tag/sdk-v26.09.9), [Swift release](https://github.com/matrix-org/matrix-rust-components-swift/releases/tag/26.08.11)

**Kotlin Multiplatform recommendation:** do not introduce KMP in the first prototype. The official Matrix SDK bindings already expose the same Rust client/crypto stack separately to Kotlin and Swift. A KMP shared app layer would still need platform-specific adapters to those bindings and would add an interop boundary without sharing the security-critical implementation. Keep platform adapters thin and share protocol acceptance fixtures instead. Revisit KMP only if Phase 2 shows substantial duplicated non-UI business logic.

This is an engineering inference from the published SDK bindings and platform packaging, not a claim that KMP is impossible. The comparative rankings below are fit judgments, not independent cryptographic audits.

## 2. Cryptographic protocol and library comparison

| Option | What it provides | What we would still own | Fit for this project |
|---|---|---|---|
| **Matrix Rust SDK (recommended)** | Matrix client/server protocol, Olm/Megolm E2EE, device and room state, sync, room timelines, and persistent client storage; official Kotlin and Swift bindings exist. The pinned store provides value-level encryption; whole-file SQLite encryption is not provided. | Native UX, account provisioning, server operation, key-verification UX, lifecycle integration, and end-to-end acceptance testing. Must enforce encrypted rooms because Matrix also permits unencrypted rooms. | Best match: interoperable Android/iOS clients, private self-hosted delivery, low infrastructure count, and no app-defined crypto protocol. Swift binding API stability and offline queue semantics must be verified at pinned versions. |
| **Signal Protocol / libsignal** | Signal's cryptographic protocol implementation and Java/Swift APIs; Signal specifications cover asynchronous key agreement, ratcheting, and multi-device session management. | Nearly all messenger infrastructure and protocol behavior: identity/device directory, prekey service, encrypted mailbox, group membership/key state, sync cursors, delivery/read semantics, offline queue, attachment transport, push, and both app integrations. | Strong cryptographic work, but a poor first-project fit: Signal states that use outside Signal is unsupported, and libsignal is AGPL-3.0. This option creates a custom messaging system around a crypto library. [libsignal README](https://github.com/signalapp/libsignal), [Double Ratchet](https://signal.org/docs/specifications/doubleratchet/), [Sesame](https://signal.org/docs/specifications/sesame/) |
| **MLS / OpenMLS** | IETF RFC 9420 standardizes asynchronous group key establishment with forward secrecy and post-compromise security; OpenMLS is a Rust implementation. | Authentication and delivery services, account/device identity, persistence/sync, 1:1 product behavior, mobile FFI, attachments, notifications, and UI. | Promising protocol, but not a complete messenger backend. OpenMLS lists Android and iOS as unsupported targets that CI builds but does not test; mobile FFI and release support remain extra work. Reconsider only if Matrix interoperability is not required or Matrix adds a suitable supported MLS path. [RFC 9420](https://www.rfc-editor.org/rfc/rfc9420.html), [OpenMLS supported targets](https://openmls.tech/book/#supported-platforms) |
| **Build crypto from primitives** | Individual primitives such as AEAD, key agreement, or hashes. | Every protocol property and almost every system component. | Reject. It violates the no-custom-cryptography requirement and creates the highest review burden. |

**Decision rationale:** Matrix is the only compared option that supplies both a defined interoperable messenger protocol and maintained client SDKs with the needed native language bindings. This is a fit recommendation, not a claim that Matrix hides metadata or that an SDK removes the need for security testing. Matrix E2EE uses Olm for device-to-device key messaging and Megolm for room messages; encrypted room events are represented as `m.room.encrypted`. [Matrix Olm & Megolm](https://spec.matrix.org/latest/olm-megolm/), [Matrix Client-Server API](https://spec.matrix.org/latest/client-server-api/#mroomencrypted)

## 3. Identity, devices, and keys

### Accounts and friend addition

| Choice | Trade-off | Recommendation |
|---|---|---|
| Matrix username/ID | Human-readable and routable on the selected server; includes the homeserver name, but does not require a phone number. | Use a Matrix ID on the private homeserver; owner provisions accounts. Use a strong unique password stored in the OS password manager. |
| Phone number | Familiar lookup, but ties the account to a personal identifier and encourages address-book discovery. | Do not collect or require. |
| Bearer invite link | Easy to share, but possession may grant access and forwarded links can leak. | Defer; if added later, make consented, short-lived, and revocable. |
| QR identity card | Fast in-person exchange, but a QR address alone does not authenticate keys. | Preferred friend-add path: encode only Matrix ID + homeserver URL, then verify devices separately. |

- The project owner provisions accounts for the small invited group; public registration stays disabled.
- A Matrix ID plus a stable homeserver name is the initial account identity. Do not request phone numbers or upload address books.
- The friend-add QR contains only the Matrix ID and homeserver URL. It is an address card, not an authentication credential or a device-verification QR.
- Do not make bearer invite links the default: anyone who receives a forwarded link could use it, and links can leak through previews/history. If invite links are added later, make them short-lived, revocable, and explicitly consented to.
- The Matrix device-verification QR or short authentication string is a separate in-person/out-of-band check. Default policy: do not share room keys with a new or changed unverified device; show a clear warning and require explicit verification/trust first. Confirm the pinned SDK can enforce this policy; if it cannot, revisit the client/SDK choice before proceeding. Matrix's specification describes out-of-band fingerprint verification and key/device change behavior. [Matrix E2EE device verification](https://spec.matrix.org/latest/client-server-api/#device-verification)

### Cryptographic and local-storage keys

1. The Matrix SDK creates and manages each device's identity/signing material, Olm sessions, Megolm room sessions and their rotation, device lists, and cross-signing state. The app does not export or reimplement those keys. Verify the pinned SDK's key-rotation, verified-device-only key-sharing, and changed-device behavior rather than creating a second key-management path.
2. Each platform generates a random 32-byte store key and supplies it through the pinned SDK's documented encrypted-store API. The existence and behavior of that API, wrong-key rejection, and encryption coverage for every store file are Phase 0 gates; do not label the local store encrypted until those checks pass. [Matrix SDK persistent-session example](https://github.com/matrix-org/matrix-rust-sdk/blob/main/examples/persist_session/src/main.rs)
3. Android protects the store key with Android Keystore; iOS stores it as a device-only Keychain item. Choose the iOS accessibility class deliberately: unlocked-only access improves protection but constrains background work. Exclude the database and key envelope from platform/cloud backup unless an independently reviewed encrypted recovery feature is approved. [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Apple Keychain accessibility](https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility)
4. Access/refresh tokens and session records use native secure storage. Passwords are not retained after sign-in.
5. No server-readable key backup is included initially. If a user still has a trusted device, use SDK verification and supported key sharing to enroll a replacement. If all trusted devices and local keys are lost, create a new device identity; old history may remain undecryptable even if ciphertext is still on Synapse.

## 4. Offline-first delivery model

Prefer the SDK's room send queue, transaction IDs, timeline, and incremental sync token. Persistence through process death is a Phase 0 hypothesis to prove, not a verified property of the candidate bindings. Do not add an app-owned second message outbox unless the pinned SDK fails the Phase 0 process-death test. If that happens, stop and redesign around an SDK-supported encrypted queue; never create an unprotected second plaintext copy. Matrix transaction IDs allow an idempotent retry of the same send request, and `/sync` provides a continuation token for catching up after disconnection. [Transaction identifiers](https://spec.matrix.org/v1.18/client-server-api/#transaction-identifiers), [incremental sync](https://spec.matrix.org/v1.18/client-server-api/#get_matrixclientv3sync)

The exact persisted representation inside the SDK's database is an SDK implementation detail. The requirements are: the configured SDK store protects the local timeline/outbox and crypto state at rest; network room events are E2EE ciphertext; and the app writes no plaintext to logs or another persistent store. Phase 0 must prove the first requirement by testing a wrong key and inspecting the database, WAL/journal, cache, and backup behavior. It must separately inspect captured homeserver event content to prove the second. Plaintext may exist in process memory while composing or encrypting.

### Lifecycle

1. The composer keeps a draft in memory; if draft persistence is enabled, it must use the verified encrypted SDK store.
2. On Send, the SDK creates a local timeline echo and stable transaction ID. The UI displays it immediately with **Queued** or **Sending** state.
3. For an already-synced encrypted room with usable room/device keys, the SDK should persist the pending operation and resume it after reconnection. A first-ever send before the client has synced keys cannot be promised to encrypt offline; keep the text as a draft and explain that initial setup requires a connection.
4. The SDK encrypts the Matrix event locally and submits an `m.room.encrypted` event when transport is available. Reuse the same transaction ID for a retry of the same operation.
5. Synapse stores/routes the encrypted event. The recipient receives ciphertext through Matrix sync, decrypts it locally if that device has the necessary room key, and persists it in the local SDK timeline.
6. After successful local persistence, the recipient client emits a small **encrypted application delivery acknowledgement** in the same direct room. Its encrypted content references the original Matrix event ID; it contains no message text. Deduplicate by `(recipient account, original event ID)` and use a stable transaction ID when retrying the acknowledgement. Do not acknowledge acknowledgement events. The exact SDK callback/API and crash-safe ordering are Phase 0 proof gates.
7. The sender processes the acknowledgement and marks the message **Delivered**. This means a recipient client decrypted and persisted it; it does not mean a person opened or read it. The server still sees the acknowledgement event's sender, room routing, size, and timing.
8. A recoverable SDK queue failure may be retried. An unrecoverable failure is shown as not sent and requires a deliberate cancel/retry path; do not blindly resubmit an operation whose acknowledgement is uncertain.

### Status vocabulary

- **Queued / Sending:** local pending event; not yet accepted by Synapse.
- **Sent:** homeserver accepted the event. This does not prove the recipient downloaded or decrypted it.
- **Delivered:** an encrypted app-level acknowledgement says a recipient client decrypted and persisted the event. It does not mean the user opened it. Phase 1 status is for direct chats; group delivery semantics need a separate per-member decision because each acknowledgement exposes activity metadata.
- **Read (Phase 2):** a Matrix read receipt/fully-read marker reports reading activity. It is distinct from delivery and visible to the homeserver; public `m.read` is also visible to room members. Private receipts reduce what other users see but do not hide the activity from the homeserver. [Matrix read receipts](https://spec.matrix.org/v1.18/client-server-api/#post_matrixclientv3roomsroomidreceiptreceipttypeeventid)
- **Not sent / Retry:** SDK has reported a failure, with retry affordance only for recoverable errors.

Matrix does not define a recipient-device delivery receipt that matches this product meaning. Use an encrypted app event for that narrow signal, and do not mistake the homeserver's send response for delivery. The extra event adds traffic and activity metadata, so expose only the status the product needs. Typing indicators likewise reveal activity and belong in Phase 2. [Matrix typing events](https://spec.matrix.org/v1.18/client-server-api/#mtyping)

**Offline limitation:** durable local sending depends on a valid account session, an initialized store whose at-rest encryption has passed the Phase 0 gate, and sufficient cached room/device-key state. Test “room already established, sender goes offline” separately from “new install sends before first sync.” The latter must retain text as a draft but cannot claim guaranteed encrypted queueing until proven.

## 5. Backend and metadata

```text
Android native client ─ Matrix Rust SDK ─┐
                                          ├─ HTTPS / Matrix Client-Server API ─ Synapse ─ PostgreSQL
iOS native client ───── Matrix Rust SDK ──┘                                           └─ media storage (Phase 3)
```

Synapse is the delivery and storage service. PostgreSQL stores Matrix room/event state; Synapse's media store holds encrypted media blobs after Phase 3. A separate object store is not needed for this small prototype. Disable federation, public registration, guest access, identity lookups, URL previews, and unnecessary statistics for this private installation. With federation off, all invited accounts must use this homeserver. Keep PostgreSQL and admin endpoints private. For any non-local deployment, terminate TLS at a maintained reverse proxy.

E2EE hides message bodies and media decryption keys when clients behave correctly. Synapse and network operators can still observe Matrix user/device identifiers, room membership and routing, event IDs, size/timing/order, IP addresses, login/sync activity, and any unencrypted profile/room state. Typing, presence, read markers, and push registrations add activity metadata. Group names and avatars are Matrix room/profile state and should be treated as visible to the server. Do not claim anonymity or total metadata protection.

Basic push is a Phase 1 goal after cross-platform encrypted messaging works. Treat APNs/FCM as best-effort wake-up only; sync remains the source of truth. Use a generic notification and validate the actual push-gateway payload and rules because room/event routing identifiers may remain even when text is omitted. No message body, sender name, room title, filename, or key is allowed in the external payload. Push gateways/providers still see routing data, device tokens, and timing. [Matrix event-ID-only push](https://spec.matrix.org/v1.18/client-server-api/#post_matrixclientv3pushersset), [push gateway API](https://spec.matrix.org/v1.18/push-gateway-api/)

## 6. Local and server data model

| Store | Contents | Protection and rule |
|---|---|---|
| Matrix SDK local SQLite store on device | Sync cursor, crypto state, room/timeline cache, pending send queue, and SDK-supported composer drafts | The pinned SDK encrypts stored values, not the SQLite file as a whole. Android wrong-key rejection and marker absence from the inspected DB/WAL/cache files passed. Search-index, backup, diagnostics, and iOS coverage remain open. No direct app SQL access; SDK schema is opaque/versioned. |
| Small native preferences | Theme, accessibility choices, list/search UI state | Avoid identifiers or message text; use platform no-backup storage where possible. |
| In-memory UI state | Current composer, transient search input, typing state, open screen | Clear on logout/background policy as appropriate; never log. |
| Secure OS storage | Store key, access/refresh tokens, device-protected session envelope | Android Keystore / Apple Keychain device-only accessibility. |
| Synapse/PostgreSQL | Accounts, devices, room membership/state, event envelopes, sync data, receipts and routing metadata | Message event body should be ciphertext in encrypted rooms; database access restricted; encrypted backups. |
| Synapse media storage (Phase 3) | Uploaded encrypted attachment bytes plus routing/size metadata | SDK encrypts before upload; attachment key and hashes are carried inside encrypted room event. Never upload plaintext media or server-readable decryption keys. |

Do not make an application-owned plaintext message table or a second persistent outbox. Search should initially run over the local decrypted timeline only; keep the query transient. Add an encrypted local search index only if the selected SDK's documented API supports it and the threat model is revisited.

## 7. Android/iOS interoperability

- Both clients speak the Matrix Client-Server API and create compatible encrypted rooms; no app-specific wire format.
- Each platform has independent local storage and device identity. Do not copy a database, device private key, or local store key between platforms.
- A room key is delivered to authorized devices through SDK-managed Matrix encrypted to-device events. Added devices need verification/key-sharing before they can decrypt prior content.
- The Phase 0 cross-platform proof is Android→iOS and iOS→Android. Check `m.room.encrypted` on the server, local decryption, local echo, retry/restart behavior, encrypted delivery acknowledgement, device changes, and duplicate handling. Same-platform pairs are regression coverage after the cross-platform path works; read receipts are Phase 2.
- Pin each platform binding and treat binding upgrades as protocol/security migrations. The Kotlin release and Swift package version numbers may differ; the wire protocol and the cross-device acceptance suite are the compatibility contract.

## 8. Threat model summary and recovery

**Protected assets:** plaintext/messages/media/drafts; credentials and access tokens; device and room keys; local store key; membership/identity information; server signing/database/media state.

**Threats addressed:** network interception, a curious or compromised homeserver/database operator, offline theft of a locked device, accidental data exposure in notifications/logs/backups, and device substitution when users verify devices.

**Residual risks:** a compromised or unlocked endpoint sees plaintext; a recipient can copy it; users can skip verification; server compromise can expose metadata and availability; E2EE does not hide IP/timing/size/room membership; delivery acknowledgements, read receipts, typing, and push add activity metadata; OS background scheduling can delay push/sync; loss of all trusted device keys can make old history unrecoverable.

| Event | Expected behavior |
|---|---|
| Phone lost | Revoke the device from another trusted device or operator; assume local history may be lost. |
| App reinstalled | Sign into account on a new device, verify it, and do not imply old room history is recoverable. |
| All device/store keys lost | Start a new cryptographic device identity; old encrypted content may be unreadable. No server-readable recovery. |
| Device compromised | Stop using it, revoke it, verify replacement devices with friends, review room/device lists. |
| Friend changes phone/device | Show a clear changed-device warning and require verification before trusting new keys. |

See [THREAT_MODEL.md](THREAT_MODEL.md) and [SECURITY.md](SECURITY.md) for the fuller threat table and release controls.

## 9. Local development environment

- Local server: Docker Desktop with Linux containers and Docker Compose; Synapse plus PostgreSQL in a private Docker network. Keep DB port unpublished and use dev-only local HTTP only on loopback or a trusted LAN.
- Android: Windows or macOS with Android Studio, a supported JDK, Android SDK, emulator/device, and Gradle wrapper.
- iOS: WSL/Linux with Swift 6.4, xtool, the Darwin Swift SDK acquired from Apple's authenticated Xcode archive, and a paired physical iPhone. xtool is the primary build/sign/install route; the Xcode IDE and Mac are not required. Apple account access is needed for the SDK archive and development signing; push provisioning additionally needs paid Apple Developer access.
- Use two disposable Matrix accounts and the same Synapse instance. The Android emulator reaches a Windows-host server through `http://10.0.2.2:8008`; a physical iPhone connected through WSL USB forwarding uses the host's reachable private-LAN endpoint and the app's configured local-network policy.
- Do not use production accounts, passwords, signing keys, or device keys in test scripts or chat.

See [DEVELOPMENT.md](DEVELOPMENT.md), [DEPLOYMENT.md](DEPLOYMENT.md), and [PROJECT_ACCESS_REQUIREMENTS.md](PROJECT_ACCESS_REQUIREMENTS.md) for setup and access details.

## 10. Phase 0 proof-of-concept acceptance checklist

1. **Lock candidate versions and inspect SDK API.** Compare the Android AAR and Swift XCFramework at exact source revisions. Confirm both expose the required encrypted-store configuration, restore, room creation, timeline send, local echoes, queue state, device verification, and lifecycle controls. Record revision differences/security fixes and the Swift API stability limits; align revisions where practical.
2. **Run two-account private Synapse.** Disable registration/federation and create two test users administratively. Confirm Android and iOS clients target the same server.
3. **Validate encryption and device trust in both directions.** Create an invite-only encrypted room online; send Android→iOS and iOS→Android. Confirm recipients decrypt and raw homeserver event JSON has `m.room.encrypted` ciphertext and no message body. Add an unverified test device; prove it does not receive room keys or decrypt new messages until explicitly verified, and verify the app presents a clear warning.
4. **Validate local data protection.** Supply a random store key protected by each OS secure store; prove wrong-key rejection. Inspect the SQLite database, WAL/journal, cache, logs, exported diagnostics, and backup behavior for a unique plaintext marker and unwrapped key. Confirm all SDK-owned local message/outbox data falls within the documented encryption boundary.
5. **Validate offline queue semantics.** After initial sync and room-key setup, queue while sender offline, kill the process, restart offline, reconnect, and assert one recipient message with preserved order. Test recipient offline then reconnect. Separately test a new device before initial sync and confirm it keeps a draft instead of falsely claiming an encrypted send.
6. **Prove delivery acknowledgement feasibility.** Send an encrypted app-level acknowledgement only after recipient decryption and local persistence; inspect server events to confirm its content is encrypted. Test a crash between persist and ack, retry after lost response/process death, stable transaction ID, deduplication, and the exact meaning of **Delivered**. Keep read receipts out of this POC.
7. **Record a pass/fail evidence table.** Include exact SDK revisions, server version/configuration, device/OS versions, raw encrypted-event evidence, store-at-rest evidence, queue/restart/dedup evidence, acknowledgement evidence, and known limits. Resolve any binding/API mismatch with a supported implementation choice and continue the project; document any remaining external platform dependency.

**Exit gate:** Phase 0 is complete only when cross-platform encrypted message exchange, the local-store encryption boundary, offline send/restart behavior, and delivery-ack feasibility have evidence on both clients. Resolve failures and proceed directly to the messaging implementation. Record any host or device access needed for tests that cannot run on this Windows machine, and continue all independent implementation meanwhile.

### Execution record — 2026-09-28

**Passed on Android API 37 emulator with Matrix Kotlin binding `26.09.9` and the local Synapse/PostgreSQL stack:**

- Session restore, room creation, and sync through Synapse.
- One encrypted message sent from the Android client; direct database inspection showed `m.room.encrypted`, ciphertext present, and no plaintext body or unique marker.
- SDK store wrong-key rejection and a marker scan across 12 local SQLite/WAL/cache files.
- Android debug and instrumentation APK builds; the keyed-store instrumentation test passed twice. A runtime duplicate-timeline-key crash was fixed with event de-duplication, and the latest APK remained foregrounded without a fresh runtime exception.

**Still open:**

- Android↔iOS encrypted exchange and iOS build/runtime validation.
- User-facing device verification and the unverified-device key-sharing gate.
- Offline send across force-stop/restart, recipient-offline delivery, encrypted acknowledgement ordering/deduplication, and message feature acceptance.
- Search-index encryption/contents, complete local backup/log/cache audit, physical devices, push payload validation, and release hardening.

The local store scan is evidence for the specific Android SDK files inspected. It does not prove whole-file database encryption or complete at-rest protection. The Phase 0 exit gate remains open; implementation is continuing while platform-independent work proceeds.

## 11. Phase complexity and estimates

Sizing assumes one experienced mobile engineer, a private group under roughly ten users, no public launch, and access to both platforms. Calendar estimates exclude waiting for external accounts/hardware and are planning ranges, not commitments.

| Phase | Scope and exit gate | Complexity | Rough effort |
|---|---|---:|---:|
| **0 — Security + architecture** | Threat model, library/API feasibility, two-account server, cross-platform E2EE/offline-store proof, decision record | Medium–high | 1–2 weeks |
| **1 — Cross-platform messaging** | Login/session restore, encrypted 1:1 rooms, timeline/local echo, durable queue/reconnect, queued/sent/delivered state, encrypted app acknowledgement, generic push, Android↔iOS acceptance | High | 6–10 engineer-weeks |
| **2 — Make messaging excellent** | Read receipts (explicitly configurable), typing, replies, reactions, small-group rooms and per-member delivery semantics, pagination/search, drafts, connection recovery, accessibility and lifecycle polish | Medium–high | 3–5 engineer-weeks |
| **3 — Encrypted media** | Encrypted photos/video/files/voice notes, thumbnails, size limits, interrupted transfers and retries; true resumable upload needs an explicit design because the Matrix media upload API does not specify byte-range resume. [Matrix media upload API](https://spec.matrix.org/v1.18/client-server-api/#post_matrixmedia-v3upload) | High | 4–8 engineer-weeks |
| **4 — Calls** | Matrix/WebRTC calling, TURN, call signaling, audio/video permissions/routing, network changes, call-specific security review | Very high | 6–10+ engineer-weeks |

**Critical path:** iOS progress uses xtool in WSL; the Xcode IDE and Mac are not prerequisites. The Darwin SDK archive, Apple account access, and paired iPhone are still required for the app build/sign/install gate. Interoperability and offline restart tests cannot be replaced by an Android-only build. Phase 0 is roughly 1–2 engineer-weeks once the SDK and both devices are available; cross-platform build coordination may stretch that to 2–3 calendar weeks. Estimates assume one experienced full-time engineer; nights/weekends can take roughly 2–3 times longer. Push adds APNs/FCM setup and may be deferred behind a feature flag if credentials are not ready, but its payload privacy must pass before enablement.

## 12. Known decisions and open checks

| Topic | Recommendation | Evidence still required |
|---|---|---|
| Protocol | Matrix E2EE through Matrix Rust SDK | Exact binding version support and Android↔iOS room test |
| UI/shared code | Native Compose + SwiftUI; no KMP in first milestone | Revisit duplication after Phase 2, not before protocol proof |
| Identity | Admin-provisioned Matrix ID; QR carries ID + homeserver | Final human-readable onboarding and stable server domain |
| Recovery | Device-only keys, no cloud-readable backup | Product copy and tested replacement-device flow |
| Offline queue | SDK queue + keyed SQLite store, with full store encryption still a proof gate | Kill/restart, wrong-store-key, WAL/cache, order, and duplicate tests |
| Delivery state | Queued/sending → sent (homeserver accepted) → delivered (recipient decrypted + persisted, encrypted app ack); read is separate Phase 2 behavior | Prove callback ordering, ack dedupe/retry, and metadata trade-off |
| Push | Generic wake-up only after encrypted message flow passes | APNs/FCM credentials and captured payload review |
| Server | One Synapse + PostgreSQL, federation off | Production TLS, password auth, firewall, backup/restore when deployment is chosen |

## Primary technical sources

- [Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk)
- [Matrix Kotlin binding](https://github.com/matrix-org/matrix-rust-components-kotlin)
- [Matrix Swift binding and package manifest](https://github.com/matrix-org/matrix-rust-components-swift/blob/main/Package.swift)
- [Matrix Client-Server API and E2EE](https://spec.matrix.org/latest/client-server-api/)
- [Matrix Olm & Megolm](https://spec.matrix.org/latest/olm-megolm/)
- [Matrix encrypted attachments](https://spec.matrix.org/latest/client-server-api/#sending-encrypted-attachments)
- [Matrix media upload API](https://spec.matrix.org/v1.18/client-server-api/#post_matrixmedia-v3upload)
- [Matrix event-ID-only push and push gateway API](https://spec.matrix.org/v1.18/client-server-api/#post_matrixclientv3pushersset)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Apple Keychain accessibility](https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility)
- [Signal Protocol specifications](https://signal.org/docs/)
- [Signal libsignal support and license](https://github.com/signalapp/libsignal)
- [RFC 9420 — MLS](https://www.rfc-editor.org/rfc/rfc9420.html)
- [OpenMLS supported targets and API](https://openmls.tech/book/)
- [Synapse installation](https://element-hq.github.io/synapse/latest/setup/installation.html)
