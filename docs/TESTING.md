# Testing plan and results

## Required behavioral coverage

| Area | Cases |
|---|---|
| Interoperability | Android→Android, Android→iOS, iOS→Android, iOS→iOS; verify event is encrypted and decrypts on the expected devices |
| Offline send | Sender offline before Send; receiver offline; both offline; network loss during send; app killed/restarted; reconnect; duplicate prevention; retry and explicit failure |
| Delivery acknowledgement | Recipient decrypted + locally persisted before ack; ack event is encrypted; stable retry transaction ID; duplicate/restart/lost-response handling; Delivered is separate from Read |
| Sync | Cold login, initial sync, incremental sync, long offline period, pagination, Wi-Fi/mobile transition, server restart, background/foreground lifecycle |
| Security | Encrypted room state, raw event ciphertext, key/device verification, changed device warning, local store encryption/key wrapping, backups, logs, generic push payload |
| Messaging | Local echo, timestamp, delivery/read state, typing, reply, reaction, edit/redact behavior, search, draft, scrolling and pagination |
| Media | Small/large photo, video, document, voice note, interrupted upload/download, retry/resume, thumbnail and memory use |
| Reliability | Poor network, server unavailable, database restart, process death, lifecycle suspension/resume, storage exhaustion and recoverable errors |
| Accessibility/performance | Screen readers, Dynamic Type/font scale, contrast, dark appearance, reduce motion, touch targets, startup, send-to-echo time, sync, scroll and memory |

## Phase 0 proof gates and execution status

- Build the candidate official Android and iOS bindings from recorded revisions and complete Android→iOS and iOS→Android encrypted 1:1 exchange; confirm a new/unverified device cannot receive room keys until the user verifies it.
- Inspect homeserver event JSON for `m.room.encrypted` and confirm neither message nor app-ack content is visible.
- Verify keyed-store wrong-key behavior and inspect the database, WAL/journal, caches, logs, and backups for a unique plaintext marker and unwrapped key.
- Kill/restart while the sender is offline, reconnect, and prove one ordered message; separately test recipient offline and new-device-before-first-sync behavior.
- Verify app delivery acknowledgement follows recipient local persistence and deduplicates after retry or process death.

Proof status is tracked in the execution record below. A compiled app is not evidence that E2EE, at-rest protection, or offline delivery works.

## Phase 1 acceptance

- Both Android and iOS clients use the same private homeserver and can create encrypted one-to-one conversations.
- On Send, the message becomes visible from local state without waiting for server acknowledgement.
- The encrypted pending message survives offline mode, force-stop, restart, and network changes, then reaches the recipient exactly once.
- Recipient offline messages are delivered after sync resumes.
- Sender status distinguishes **Sent** (homeserver accepted) from **Delivered** (recipient decrypted and persisted, confirmed by encrypted app acknowledgement). Neither state is labeled **Read**.
- Raw homeserver event content contains no plaintext body.
- The store-at-rest encryption boundary is verified, and its key is protected by the native platform store and does not appear in backups or logs.
- Generic push payloads contain no message content; if credentials are unavailable, push remains disabled and next-foreground sync is tested.
- Critical tests run on physical Android and iPhone devices before daily-use claims.

## Execution record

- Synapse and PostgreSQL are running locally. Android SDK binding `26.09.9` signed in on the API 37 emulator, restored its session, discovered native sliding sync, and stayed connected to the local Synapse through `/sync`.
- Android created an encrypted direct room and sent a unique test message. A read-only PostgreSQL inspection found a raw `m.room.encrypted` event; its content had ciphertext and no `body` or unique plaintext marker.
- The Android SDK-store instrumentation check passed: the correct store key opened the stored event and a random key was refused during store-cipher initialization. A scan of 12 SQLite, WAL, and cache files found no unique plaintext marker. This proves the checked values are protected; it does not establish full-file encryption or audit search-index, backup, log, and every cache path.
- Android debug APK and instrumentation APK assembly succeeded in the isolated Linux Gradle container. The keyed-store check passed again against the latest build. A timeline duplication crash was found during emulator launch; timeline models now deduplicate IDs, and the updated app remained resumed without a fresh Android runtime exception.
- Android homeserver URL policy unit tests passed, and both debug and minified release APKs built successfully. Production builds reject HTTP at sign-in and session restore; debug builds allow only loopback or numeric private IPv4 HTTP endpoints.
- An earlier standard-account offline probe restored and synced an existing encrypted room but did not reach Synapse with a room-send request. That probe prompted a separate, isolated app package and Synapse project so outbox validation would not touch the saved app account or room. Its result is recorded below.
- A follow-up read-only audit covered three instrumentation windows (2026-09-28 15:28:31–15:28:37, 15:29:02–15:29:07, and 15:29:23–15:29:29 UTC). Synapse logged 7, 9, and 7 requests; none targeted room-send or send-to-device endpoints. PostgreSQL showed zero events received in the saved room during each window, and no diagnostic marker matched event content. This is strong evidence those probes did not send or alter room events; it cannot rule out a request rejected before reaching Synapse. The existing room pointer and queued messages remain intact.
- SDK source review confirmed that send queues start enabled and `subscribeToSendQueueStatus()` respawns persisted unsent requests. Queue startup is now gated before subscription and is enabled only after sync/media state is ready. Per-room queue initialization was removed from conversation opening; the SDK's global queue gate controls connectivity and startup.
- An earlier isolated run localized sign-in failure to eager verification-controller construction. The SDK FFI factory looks up the current user's cross-signing identity in the local crypto store and returns an error when it is unavailable; the repository was incorrectly making this optional UI setup a prerequisite for starting sync. The app now requests the own identity from the homeserver before controller construction, retries setup after sync becomes live, and keeps messaging startup independent of verification availability. See the [SDK controller factory](https://matrix-org.github.io/matrix-rust-sdk/src/matrix_sdk_ffi/client.rs.html#1576) and [identity lookup behavior](https://matrix-org.github.io/matrix-rust-sdk/matrix_sdk/encryption/struct.Encryption.html#method.get_user_identity).
- The isolated outbox acceptance run now passes end to end on the API 37 Android emulator: a fresh account created an encrypted room, sent while Synapse was stopped, retained one local echo without a server event, survived force-stop, restored its session, reconnected, and settled to exactly one message with a server event ID. The timeline projection now preserves non-message slots when applying indexed SDK diffs; before that fix it could leave a stale local echo beside its replacement. The diagnostic app ID, Synapse project, credentials, and volumes were isolated; only allowlisted endpoint counts and sanitized stage results were reported. This validates Android same-device restart and reconnect, not receiver-offline behavior or physical Android↔iOS exchange.
- iOS peer/account SAS verification, incoming/outgoing requests, identity-change indicators, attachment lifecycle controls, and HTTPS URL policy are implemented in source. Swift syntax parsing passed in a Linux Swift container. Xcode typechecking, iOS SDK linkage, Simulator execution, codesigning, and device tests have not run.
- Android live sign-in, encrypted room creation, server-side ciphertext, keyed-store checks, and the isolated same-device offline restart/outbox test have passed. Recipient-offline delivery, delivery/read acknowledgement behavior, typing, replies, reactions, complete message search, media recovery, and Android↔iOS exchange remain unverified.
- The Synapse configuration generator now sets `push.include_content: false` so message bodies are omitted from push notification pokes. APNs/FCM transport is not configured, and no real push payload has been captured or validated yet.
- Xcode is unavailable on this Windows host. Full iOS build and Simulator/device checks require a Mac with Xcode; the iOS source has only been syntax-parsed here.

A successful build alone will not prove cross-platform E2EE or reliable offline behavior. Record exact client/server versions, OS/device versions, commands, and evidence for every future acceptance run.
