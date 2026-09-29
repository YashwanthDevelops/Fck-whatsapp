# Voice and video calls

## Product decision

The app will keep iOS 16 as its minimum and avoid the AGPL-licensed Element Call packages. The custom native call host will use the Apache-2.0 LiveKit client SDKs and a self-hosted LiveKit SFU. Android is pinned to `io.livekit:livekit-android:2.29.0`; iOS is pinned to LiveKit Swift `2.17.0`. LiveKit Swift declares iOS 13 as its minimum platform and supports Swift 5 language mode, so the package's stated platform and language settings fit this app's iOS 16 / Swift 5 target. It requires Swift tools 6.1 (Xcode 16.3 or newer) to resolve and build. See the [Swift package manifest](https://github.com/livekit/client-sdk-swift/blob/2.17.0/Package.swift), [Swift license](https://github.com/livekit/client-sdk-swift/blob/2.17.0/LICENSE), and [Android SDK license and release](https://github.com/livekit/client-sdk-android/tree/v2.29.0).

## Architecture

- Matrix remains the identity, encrypted conversation, and call-control transport.
- Call-control events use a namespaced `m.room.message` `msgtype` inside the existing encrypted room. The LiveKit SFU receives no Matrix access token, Matrix room ID, or media key.
- Each call creates a fresh 256-bit media key on a client. The inviter sends the key only inside the Matrix-encrypted invitation. The key is passed to LiveKit's native E2EE key provider before connecting. The LiveKit token service issues short-lived, least-privilege join tokens after validating the caller's Matrix access token and current room membership; it never receives a media key.
- A random per-call LiveKit room name and random participant identity avoid reusing a stable Matrix identifier at the SFU. Media and LiveKit data channels use the SDK's frame encryption. Signaling metadata and IP/network details remain visible to the SFU and network peers; E2EE does not make a call anonymous.
- A changed or unverified peer identity must block call setup. The current trust projection only covers one-to-one rooms, so the first implementation is limited to verified one-to-one calls. Group calls stay disabled until the app can prove every recipient device meets the selected trust policy.
- Each call uses a new key and expires independently. Hang-up destroys the in-memory key and session. Call invitations expire quickly; replayed, malformed, or stale invitations are ignored.

LiveKit documents that E2EE keys must be generated and distributed out of band, and that its signaling/control plane remains visible to the LiveKit server even when media frame encryption is enabled. This app uses the Matrix encrypted room for key distribution and limits the server role to SFU forwarding. See [LiveKit encryption](https://docs.livekit.io/transport/encryption/), [Android E2EE setup](https://docs.livekit.io/transport/encryption/start/), and the [self-hosting deployment guide](https://docs.livekit.io/transport/self-hosting/deployment/).

## Implementation status

Call UI, key distribution, native SDK integration, token authorization, and deployment are still in progress. Do not describe calls as shipped, tested end to end, or secure until the exact release builds pass Android and iOS interoperability tests and the SFU ciphertext test.

The Windows development host has no Xcode or iPhone. Android builds and simulator/emulator validation can continue here, and the Swift source can be syntax-checked with a Linux compiler, but an Xcode archive, CallKit validation, signed iOS build, physical-device testing, and Android-to-iOS call acceptance will require access to a Mac/Xcode and iPhone.

## Incoming call behavior

Foreground call invitations can be carried by encrypted Matrix room events. Background wake-up requires a separate platform implementation: Android call notifications/foreground service and iOS PushKit plus CallKit. Generic message push is not sufficient. Push payloads must remain generic and contain no call key, media, or message content. APNs/FCM credentials and signed-device acceptance remain external requirements. See [Apple's PushKit response requirements](https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit).

## Deployment requirements

The private deployment needs a publicly reachable LiveKit service with trusted TLS, an authenticated token endpoint, and firewall access for the configured ICE/TURN transports. TURN/TLS or a tested relay route is required for restrictive networks. The service's API key and secret stay server-side; no shared development token or API secret may be embedded in an app. The homeserver domain, VPS, TLS, firewall, persistent storage, and backups are not yet provisioned. See [LiveKit's port and firewall guide](https://docs.livekit.io/transport/self-hosting/ports-firewall/).

## Acceptance checks

- Verified Android and iOS users can start, answer, decline, and hang up voice and video calls in both platform directions.
- A call is refused if the room is unencrypted, the peer trust is unknown/unverified/changed, token authorization fails, or the media key is missing or invalid.
- The SFU and token service cannot decode media frames or obtain Matrix-encrypted call keys.
- Room membership changes, new/unverified devices, replayed invitations, call expiry, and hang-up/rejoin cause the expected key and authorization behavior.
- Mute, camera toggle/switch, Bluetooth/headsets, audio interruptions, app backgrounding/PiP, network handover, and TURN-only connectivity work on physical devices.
- CallKit, Android incoming-call notifications, push wake-up, and generic push payloads are validated on signed devices; payloads and logs contain no plaintext or keys.
- Deployment restart, resource limits, firewall exposure, and backup/restore behavior are verified.
