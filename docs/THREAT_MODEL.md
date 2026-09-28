# Threat model

## Assets

- Message plaintext, attachments, voice recordings, and drafts.
- Matrix account credentials, access/refresh tokens, device identity, cross-signing keys, Olm/Megolm sessions, and the SDK store key.
- Room membership, friend identifiers, contact QR data, and device verification state.
- Synapse signing key, PostgreSQL records, registration secret, media store, and encrypted backups.

## Adversaries

- Network observer between clients and the homeserver.
- Homeserver operator or database/media storage reader.
- Someone who obtains a lost or temporarily unlocked phone.
- Malware, malicious accessibility service, rooted/jailbroken device, or compromised OS.
- A recipient who copies or forwards received information.
- An attacker who substitutes or adds an unverified device, guesses credentials, or obtains an administrative secret.
- Accidental leak through push, logs, crash reporting, backups, or QR handling.

## Security properties and limits

| Scenario | Expected protection | Limit |
|---|---|---|
| Passive network observation | TLS hides transport; Matrix E2EE hides message/media content | IPs, endpoints, timing, sizes, and traffic patterns remain observable |
| Server/database reader | Encrypted room events and encrypted media contents | Membership, account/device IDs, event timing/order/size, profile/room state, and IP/log metadata may remain visible |
| Delivery acknowledgement | Message content stays encrypted; sender gets a delivery signal after the recipient client decrypts and persists locally | Ack event sender, room, timing, and size are visible to the service; it does not prove the person read the message |
| Lost locked device | If the Phase 0 store checks pass, OS key protection plus SDK store encryption raise the cost of offline extraction | Until proven, store-at-rest protection is an assumption; an unlocked, rooted, or exploited device can expose decrypted content and keys |
| New/unverified device | Verification and device-change UI make substitution detectable when users check | Users can dismiss warnings or skip verification; account takeover remains dangerous |
| Recipient disclosure | None after content is displayed to the recipient | E2EE cannot stop screenshots, copying, recording, or onward sharing |
| Homeserver outage or deletion | Local timeline and send queue preserve data already on-device | Offline users cannot exchange messages; no automatic recovery of lost local keys/history |
| App lifecycle/background restriction | Persistent local SDK state and next-foreground sync preserve queued work | APNs, FCM, and OS background scheduling can delay or omit wakeups |

## Metadata visible to the service

Synapse must process authenticated Matrix IDs/device IDs, room membership and event routing, event IDs and arrival times, event sizes, sync/presence/read/typing activity, app-level delivery acknowledgement event sender/timing/size, push registrations if enabled, IP addresses, and unencrypted profile/room state. Encrypted message and acknowledgement content is opaque to Synapse when clients use encrypted rooms correctly. E2EE is not anonymity. Matrix private read receipts hide the receipt from other room members, not from the homeserver.

## Trust assumptions

- Users install authentic client builds and keep their devices/OS reasonably maintained.
- The Matrix Rust SDK release is the security-critical implementation and is updated when its advisories require it.
- The server operator protects Synapse signing keys, admin credentials, database/media data, and backups.
- Users verify new or changed devices for conversations where device substitution matters.
- The client withholds room keys from new or changed unverified devices until verification/trust is explicitly completed; Phase 0 must verify that the SDK enforces this policy.
- No external identity server, URL previewer, analytics service, or content-bearing push provider is enabled by default.
- The Phase 1 delivery signal means a recipient client decrypted and persisted the message; it is not a claim that the human recipient opened or read it.

## Incident and recovery expectations

- Lost phone: revoke the device/session from another trusted device or through the server operator; history on that phone may be unrecoverable.
- Reinstall: create a new device and verify it. Old room keys/history may not return because recovery is intentionally not enabled.
- Lost encryption/store key: create a new device; do not claim the server can reconstruct old messages.
- Suspected compromise: stop using the affected device, revoke it, verify replacement devices with contacts, and review room/device membership.
- Friend replaces a device: treat the new device as unverified until the friend completes an out-of-band verification.

The implementation must provide a concise recovery and device-verification explanation in the app before release.
