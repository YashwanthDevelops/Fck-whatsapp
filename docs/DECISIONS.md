# Decision record

The decisions below are approved project direction as of 2026-09-28. Implementation proceeds through the product phases after Phase 0 validation; platform or account access constraints are recorded separately. See [PHASE_0_ANALYSIS.md](PHASE_0_ANALYSIS.md).

## ADR-001 — Native UI with Matrix Rust SDK on both platforms

**Status:** approved.

**Decision:** Kotlin/Jetpack Compose on Android, Swift/SwiftUI on iOS, and Matrix Rust SDK FFI bindings on both.

**Reason:** platform-specific UI/lifecycle/security APIs stay native while Matrix protocol, encryption, sync, and store logic come from one established implementation. This avoids a custom cryptographic or sync layer. The Swift component API is unstable, so a version pin and adapter boundary are required.

## ADR-002 — Private Synapse with PostgreSQL

**Status:** approved.

**Decision:** one Synapse process and PostgreSQL for the private friend group; no broker, cache, Kubernetes, or bespoke message server.

**Reason:** Matrix supplies event transport, encrypted rooms, device sessions, and sync. Synapse is a maintained server implementation; PostgreSQL is recommended for practical installs. Local Compose will use an un-published development-only database network.

## ADR-003 — Manually provisioned Matrix identities

**Status:** approved for initial release.

**Decision:** use private Matrix IDs and manually provision accounts. Friend addition uses locally parsed QR/Matrix ID. Keep registration and contact discovery disabled.

**Reason:** the audience is a small known group; public discovery and phone-number lookup add privacy and abuse surface without helping this use case.

## ADR-004 — SDK-managed E2EE and encrypted local store

**Status:** approved; API and lifecycle behavior are being verified in Phase 0.

**Decision:** Matrix Rust SDK owns cryptography, encryption state, send queue, sync state, and the candidate keyed SQLite store. Generate a random store key and protect it with Android Keystore / iOS Keychain.

**Reason:** no custom protocol or key lifecycle. The SDK exposes store-key configuration, but the selected binding must prove that it encrypts all local message/outbox and crypto data, rejects a wrong key, and covers WAL/cache files. Offline queue restart semantics are also a release gate.

## ADR-005 — Push notifications after message-flow proof

**Status:** approved; generic push remains a Phase 1 goal after the message flow passes.

**Decision:** build foreground and next-foreground sync first. Add APNs/FCM wakeup only after encrypted delivery, with generic content-free payloads.

**Reason:** push needs external credentials and operating systems do not guarantee background execution. Payload privacy must be inspected rather than assumed.

## ADR-006 — Encrypted app acknowledgement for recipient delivery

**Status:** approved; API, persistence ordering, and metadata trade-off are being proven in Phase 0.

**Decision:** distinguish **Sent** (homeserver accepted) from **Delivered** (recipient client decrypted and persisted locally) using a small encrypted application event in the direct room. The event references the original event ID, is deduplicated by recipient account/event ID, and is never acknowledged in turn. Keep **Read** as a separate Phase 2 signal.

**Reason:** Matrix does not define a device-delivery receipt with the requested meaning. A homeserver send response is not proof the recipient client received the message. An app-level encrypted receipt supplies the product status without exposing its content to Synapse, but its sender/timing/size add metadata and its callback/retry behavior needs an explicit proof.

## ADR-007 — Calls deferred until chat/media are stable

**Status:** approved for sequencing; implementation follows stable messaging and media.

**Decision:** voice/video calling follows media and security review, using an established WebRTC/Matrix calling implementation and a TURN service where needed.

**Reason:** call signaling, NAT traversal, permissions, audio routing, and reconnect behavior create a separate lifecycle/security project and should not delay reliable messaging.

## ADR-008 — Windows host and Apple-platform validation

**Status:** supersedes the original macOS/Xcode assumption; current access requirements are tracked in `PROJECT_ACCESS_REQUIREMENTS.md`.

**Decision:** build the iOS SwiftPM app with xtool from WSL/Linux. Use `xtool dev build` for the app build gate and `xtool dev` for signing, installation, and launch on a paired physical iPhone. Keep platform-neutral Swift tests on Linux. Do not treat those tests as a substitute for the iOS binary or device gates.

**Reason:** the Xcode IDE and a Mac are not prerequisites for the xtool route, so independent iOS work can proceed on this host. Apple's Darwin SDK archive and Apple account access are still required to install the SDK and sign a development build; an iPhone is required for native behavior validation. Simulator checks are not part of the current WSL validation path.
