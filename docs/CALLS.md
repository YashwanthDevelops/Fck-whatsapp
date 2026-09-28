# Voice and video calls

## Status

Calls are deferred until encrypted messaging and media transfer pass their reliability gates. This note records the current integration path and deployment requirements so call work does not weaken the messaging security boundary.

## Recommended client stack

Use the native Element MatrixRTC packages when call work begins:

- Android: [element-call-android](https://github.com/element-hq/element-call-android)
- iOS: [element-call-ios](https://github.com/element-hq/element-call-ios)
- Shared RTC/media core: [matrix-rust-rtc](https://github.com/element-hq/matrix-rust-rtc)

These packages provide native call UI and media integration around MatrixRTC and LiveKit. Pin exact release versions and recheck their compatibility when implementation begins; the releases checked on 2026-09-28 are pre-1.0 release candidates, so APIs and requirements can change.

The currently inspected Android release candidate is compatible with the project's declared Android minimum and Matrix Rust SDK version floors, but it has not been resolved or built in this project. Android integration must preserve the SDK's trusted-device policy and handle microphone/camera permissions, foreground-call service behavior, and picture-in-picture lifecycle.

The currently inspected iOS release candidate requires iOS 18, Swift tools 6.2, and Matrix Rust SDK 26.09.01 or newer. The app currently targets iOS 16 and pins Matrix Rust SDK 26.08.11, so it cannot adopt that package without revisiting its minimum iOS version and SDK pin. The iOS host also needs CallKit/system-call and room-context adapters, camera permission text, and package-specific linker settings. Confirm the requirements against the selected release before changing the app target.

An alternative is to integrate the lower-level [matrix-rust-rtc](https://github.com/element-hq/matrix-rust-rtc) core directly. The inspected `v0.3.0-rc.2` Swift package supports iOS 16 and Swift tools 5.9, and its FFI lets the host provide Matrix operations, so it can be paired with the current `26.08.11` SDK. This is not a drop-in call UI: the app would own Matrix event and transport adapters, AVFoundation capture/rendering, SwiftUI call screens, CallKit/lifecycle handling, and incoming-call behavior. This release uses legacy `org.matrix.msc3401.call.member` room state and requires `-ObjC`; its media simulator slice is Apple Silicon only. Validate these details against the selected release and on macOS before integration.

## Distribution license gate

The inspected `0.1.0-rc.5` Android/iOS Element Call packages and `matrix-rust-rtc v0.3.0-rc.2` core are offered under `AGPL-3.0-only` or an Element commercial license. The lower-level core avoids Element Call iOS's iOS 18 floor, but it does not avoid the licensing choice. Before integrating or privately distributing calls, choose a source-availability approach that satisfies the selected license or obtain the commercial license. Recheck the exact license files for the versions ultimately pinned; do not treat this research note as legal advice.

## Media encryption and trust

The RTC core exchanges participant media keys through Olm-encrypted to-device messages and applies frame encryption before media reaches the LiveKit SFU. The inspected implementation uses HKDF and GCM for LiveKit frame encryption. Preserve the SDK's cross-signed-device trust checks; do not relax them to make a call connect. Validate this property with the exact client releases selected for the app.

MatrixRTC still exposes call membership, room association, timing, IP/network information, and transport metadata to the relevant Matrix and RTC services. Media E2EE does not make calls anonymous.

## Private server requirements

The current local Synapse/PostgreSQL Compose environment has no RTC transport advertisement, LiveKit SFU, or JWT authorization service. Internet-reachable calls between friends will need a privately operated server with valid TLS, network reachability, and restricted administration.

Synapse 1.161's inspected configuration advertises LiveKit transports in this form (replace the example host with the deployed service):

```yaml
matrix_rtc:
  transports:
    - type: livekit
      url: wss://rtc.example.org/livekit/sfu
      livekit_service_url: https://rtc.example.org/livekit/jwt
```

The current authorization path uses `lk-jwt-service` as a Matrix application service and requires room-membership authorization plus reverse-proxy routing. Keep its registration tokens and the LiveKit API secret outside the repository. Configure and test the SFU's WebRTC UDP/TCP ports and TURN/TLS fallback for restrictive networks; a localhost-only endpoint cannot serve friends on other networks.

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
