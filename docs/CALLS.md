# Voice and video calls

## Status

Calls are not implemented yet. This note records the current integration path and the product gates so call work can proceed without weakening the messaging security boundary.

## Recommended client stack

Use the native Element MatrixRTC packages when call work begins:

- Android: [element-call-android](https://github.com/element-hq/element-call-android)
- iOS: [element-call-ios](https://github.com/element-hq/element-call-ios)
- Shared RTC/media core: [matrix-rust-rtc](https://github.com/element-hq/matrix-rust-rtc)

These packages provide native call UI and media integration around MatrixRTC and LiveKit. The current releases checked on 2026-09-29 are pre-1.0 release candidates, so APIs and requirements can change.

Element Call Android `0.1.0-rc.5` is compatible with the project's declared Android minimum (26) and Matrix Rust SDK version (26.09.9; the package floor is 26.09.08). It is fetched from GitHub release artifacts and has not been resolved or built in this project. Integration still needs microphone/camera permissions, foreground-call service and notification wiring, and picture-in-picture lifecycle. [Release](https://github.com/element-hq/element-call-android/releases/tag/v0.1.0-rc.5) · [Host requirements](https://github.com/element-hq/element-call-android/blob/v0.1.0-rc.5/README.md)

Element Call iOS `0.1.0-rc.8` requires iOS 18, Swift tools 6.2, and Matrix Rust SDK 26.09.01 or newer. The app targets iOS 16, uses Swift 5, and pins Matrix Rust SDK 26.08.11, so adopting it requires raising the iOS floor and updating the SDK/toolchain. The host also needs CallKit/audio-session integration and room context. [Release](https://github.com/element-hq/element-call-ios/releases/tag/0.1.0-rc.8) · [Package manifest](https://raw.githubusercontent.com/element-hq/element-call-ios/0.1.0-rc.8/Package.swift) · [Integration requirements](https://github.com/element-hq/element-call-ios/blob/0.1.0-rc.8/README.md)

An alternative is to integrate the lower-level [matrix-rust-rtc](https://github.com/element-hq/matrix-rust-rtc) core directly. Version `0.3.0-rc.2` supports iOS 16 and Swift tools 5.9, but provides no ready call UI. The app would own Matrix event and transport adapters, AVFoundation capture/rendering, SwiftUI call screens, CallKit/lifecycle handling, and incoming-call behavior. This preserves the current iOS floor at substantially greater engineering cost. [RTC core package](https://github.com/element-hq/matrix-rust-rtc/blob/v0.3.0-rc.2/Package.swift)

## Distribution license gate

The Element Call Android/iOS packages and RTC core are dual-licensed under AGPL-3.0-or-later or an Element commercial license. The lower-level core avoids Element Call iOS's iOS 18 floor, but it does not avoid the license choice. Before integrating or privately distributing calls, choose a source-availability approach that satisfies the selected license or obtain a commercial license. Recheck the exact license for versions ultimately pinned; this note is not legal advice. [Android project and license](https://github.com/element-hq/element-call-android) · [iOS project and license](https://github.com/element-hq/element-call-ios) · [RTC core](https://github.com/element-hq/matrix-rust-rtc/blob/v0.4.0-rc.1/README.md)

## Media encryption and trust

The RTC core exchanges participant media keys through Olm-encrypted to-device messages and applies frame encryption before media reaches the LiveKit SFU. The inspected implementation uses HKDF and GCM for LiveKit frame encryption. Preserve the SDK's cross-signed-device trust checks; do not relax them to make a call connect. Validate this property with the exact client releases selected for the app.

MatrixRTC still exposes call membership, room association, timing, IP/network information, and transport metadata to the relevant Matrix and RTC services. Media E2EE does not make calls anonymous.

## Private server requirements

The current local Synapse/PostgreSQL Compose environment has no RTC transport advertisement, LiveKit SFU, or JWT authorization service. Internet-reachable calls between friends will need privately operated RTC services with valid TLS, network reachability, and restricted administration.

Synapse 1.161's inspected configuration advertises LiveKit transports in this form (replace the example host with the deployed service):

```yaml
matrix_rtc:
  transports:
    - type: livekit
      url: wss://rtc.example.org/livekit/sfu
      livekit_service_url: https://rtc.example.org/livekit/jwt
```

Synapse 1.161 adds `url` for the SFU WebSocket and retains `livekit_service_url` for compatibility with older clients. RTC authorization uses `lk-jwt-service` as a Matrix application service and requires room-membership authorization plus reverse-proxy routing. This private deployment disables federation; Element's self-hosting guide requires either federation or OpenID resources for RTC authorization, so add an OpenID listener while keeping federation disabled. Keep appservice registration tokens and the LiveKit API secret outside the repository. Configure and test the SFU's WebRTC UDP/TCP ports or a tested relay path; a localhost-only endpoint cannot serve friends on other networks. [Synapse 1.161 upgrade notes](https://github.com/element-hq/synapse/blob/develop/docs/upgrade.md) · [Element Call self-hosting](https://github.com/element-hq/element-call/blob/main/docs/self_hosting.md) · [LiveKit firewall requirements](https://docs.livekit.io/transport/self-hosting/ports-firewall/)

## Incoming-call limitation

The inspected RTC release candidate documents incomplete incoming ringing: receiving notifications, ring expiry/acknowledgements, and push-to-app delivery are not implemented in the RTC core. Therefore an in-app call flow alone is not a complete incoming-call experience. Background incoming calls require a separate APNs PushKit and CallKit path on iOS and an appropriate Android call-notification path. Keep incoming-call claims disabled until those flows exist and pass device tests.

## Acceptance checks

- Android and iOS can start and join a verified encrypted 1:1 call over the private homeserver.
- Voice and video both connect in both platform directions; the SFU cannot decode media frames.
- Tests cover denied and restored permissions, mute, hang-up, Bluetooth/headsets, audio interruptions, app backgrounding/PiP, network switching, and TURN/TLS-only connectivity.
- Matrix and LiveKit traffic, server logs, and push payloads contain no media plaintext, call content, or media keys.
- Incoming ringing, CallKit, Android call notifications, and wake-up behavior are tested separately on physical devices before calls are treated as complete.

## Official references

- [Element Call Android releases and host requirements](https://github.com/element-hq/element-call-android)
- [Element Call iOS package and integration notes](https://github.com/element-hq/element-call-ios)
- [Matrix RTC core architecture](https://github.com/element-hq/matrix-rust-rtc)
- [RTC core iOS 16 package manifest](https://github.com/element-hq/matrix-rust-rtc/blob/v0.3.0-rc.2/Package.swift)
- [RTC core packaging notes](https://github.com/element-hq/matrix-rust-rtc/blob/v0.3.0-rc.2/mobile/PACKAGING.md)
- [Synapse configuration](https://github.com/element-hq/synapse/blob/release-v1.161/docs/usage/configuration/config_documentation.md)
- [LiveKit JWT authorization service](https://github.com/element-hq/lk-jwt-service)
- [LiveKit self-hosted deployment](https://docs.livekit.io/transport/self-hosting/deployment/)
- [Apple PushKit incoming VoIP calls](https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit)
