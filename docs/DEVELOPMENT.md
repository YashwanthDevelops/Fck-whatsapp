# Development setup

## Required toolchains

| Tool | Target |
|---|---|
| Android Studio with a compatible bundled JDK, Android SDK Platform, Build Tools, and NDK | Android app and Matrix SDK Android bindings |
| Android Studio emulator or a USB-debuggable Android device | Android run/debug |
| Swift 6.4.0, xtool 1.20.1, and the Darwin Swift SDK | iOS build and signing through Linux/WSL |
| Apple ID for the Xcode 27 SDK archive | One-time Darwin SDK setup through xtool |
| Physical iPhone with USB passthrough to WSL | iOS install and device acceptance |
| Docker Desktop Linux containers and Compose | Local Synapse/PostgreSQL |
| Git | Version control |

Use the Gradle wrapper checked into the repository root; Android sources are currently under `app/`. Do not rely on a globally installed Gradle. Android SDK packages and JDK 17 are available locally. On this Windows host, create `C:\t` and set `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:/t -Djdk.net.unixdomain.tmpdir=C:/t` before running Gradle to avoid the JDK loopback-selector failure. Set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to `.tools/android-sdk` when no `local.properties` is present.

For iOS, use the SwiftPM app package and `xtool` described in [ios/README.md](../ios/README.md). From `ios/`, run `python3 configure_xtool_profile.py` before each build so xtool has the generated Info.plist and entitlements; then `xtool dev build` compiles the app and `xtool dev` also signs and installs it on a paired iPhone. The Xcode archive is needed to create Apple's iOS SDK, while the Xcode IDE and a Mac are not required by this build route. xtool builds do not replace the unit, integration, and physical-device tests below.

## Version selection

Pinned candidate dependencies selected during feasibility inspection on 2026-09-30 (the earlier Android API 37 peer acceptance used Matrix `26.09.9`; Android `26.09.28` now compiles and its current standard unit suite passes, but still needs fresh peer interoperability validation):

- Matrix Rust SDK Kotlin: official Matrix Rust components Kotlin `26.09.28`, Maven coordinate `org.matrix.rustcomponents:sdk-android:26.09.28`. Its generated API exposes Olm-encrypted custom to-device send and receive methods; the exact source JAR was inspected, and the app compiles against the upgrade. Reaction summaries moved onto `EventTimelineItem.reactions` and the projection was updated. Custom to-device call-key sending remains disabled because the binding cannot select individually verified peer devices.
- Matrix Rust SDK Swift: official Matrix Rust components Swift `26.09.07`; its package manifest declares iOS 16 minimum. The wrapper API is explicitly unstable, so keep the package pinned and isolate it behind the app adapter. The exact generated FFI has no custom encrypted to-device API; calls remain deferred.
- The iOS SwiftPM package pins Matrix Rust SDK `26.09.07`; `ios/Package.resolved` records the resolved revision. `ios/project.yml` remains available for XcodeGen-based tooling, while the WSL xtool route is the primary iOS build path.
- The candidate bindings embed different Rust SDK revisions. Matrix wire interoperability is expected, but compare their security changes and prove Android→iOS and iOS→Android before accepting the pair. Do not infer matching security-fix coverage from similar package numbers. [Kotlin release](https://github.com/matrix-org/matrix-rust-components-kotlin/releases/tag/sdk-v26.09.28), [Swift release](https://github.com/matrix-org/matrix-rust-components-swift/releases/tag/26.09.07)
- Synapse: `v1.161.0` image for the current Compose environment (latest stable release verified 2026-09-28; keep image pinned and update deliberately).
- PostgreSQL: major 16 image tag for a supported, conservative line; patch images should be refreshed as part of maintenance.
- Android Compose BOM: `2026.09.00`, compile SDK 37, AGP 9.3.3, Gradle 9.5, Kotlin/Compose compiler 2.4.10. Debug and minified release APK builds have completed in the isolated Linux Gradle container.
- The current Android Gradle wrapper and Kotlin toolchain compile the standard debug app on this Windows host using the documented Java temporary-directory workaround.

The official Matrix Swift package declares an unstable API. Treat upgrades as small migrations and run both cross-platform directions, delivery-ack, local-store, and offline-send acceptance checks after each upgrade. Element X's iOS project may track a different package line; this project stays with the official `matrix-org` package unless Phase 0 exposes a concrete incompatibility.

## First run

1. Install Android Studio and an Android SDK, plus a JDK supported by the selected Android Gradle Plugin.
2. Copy `.env.example` to `.env`, then generate and configure the local homeserver:

   ```sh
   cp .env.example .env
   docker compose -f ops/synapse/compose.yaml run --rm synapse generate
   docker compose -f ops/synapse/compose.yaml run --rm --entrypoint python synapse /opt/configure.py
   docker compose -f ops/synapse/compose.yaml up -d
   ```

3. Create one account per test device. The command prompts for a username and password; do not put passwords in shell history:

   ```sh
   docker compose -f ops/synapse/compose.yaml exec synapse register_new_matrix_user http://localhost:8008 -c /data/homeserver.yaml
   ```

4. Launch debug builds against the same development homeserver. Debug builds accept HTTP only for localhost, the Android emulator host (`http://10.0.2.2:8008`), IPv6 loopback, or a numeric RFC1918 private IPv4 address. Release builds require HTTPS. For a physical phone, use the development host's private LAN address and local firewall rules.
5. Complete device verification between the test accounts before treating a conversation as trusted.

### iOS through WSL and xtool

Follow [ios/README.md](../ios/README.md) to install the pinned tool versions, obtain the Darwin SDK through xtool, and build or install the app. Apple credentials are entered into xtool locally and must not be stored in the repository.

## Secrets and local data

`.env`, `ops/synapse/data/`, Android `local.properties`, app keystores, iOS provisioning files, and device credentials must stay out of Git. Development homeserver state can be removed and recreated; never use production accounts or data in the local development container.

## Current workspace and platform constraints

The local Synapse/PostgreSQL Compose configuration and data are present, but Docker Desktop currently returns HTTP 500 for the Linux engine and the local homeserver is not reachable; do not rely on a live development backend until Docker recovers. Android SDK packages and JDK 17 are present under ignored `.tools/` directories. Standard debug tests, the diagnostic APK/instrumentation build, and signed release APK/AAB builds pass through the Windows Gradle wrapper with the Java temp-directory workaround above; the current standard debug Kotlin compile passes as well. An API 37 emulator has passed prior encrypted offline-send, force-stop, restart, and reconnect outbox gates; current-source smoke launch also succeeds. Android Studio is not configured here. WSL now has Swift 6.4.0 and xtool 1.20.1. SwiftPM wrote the lockfile and downloaded the pinned Matrix XCFramework archive with a matching checksum, but the resolver did not finish extracting it; no Darwin Swift SDK is installed because obtaining the Xcode 27 archive requires an Apple ID. There is also no iPhone or Apple ID available in this session. The Xcode IDE and Mac are not required for the xtool build route, but the authenticated SDK archive and physical device are required to close iOS build, install, and interoperability gates.

The user approved autonomous implementation through the complete product scope on 2026-09-28. Continue implementation after Phase 0 without another approval stop. The source under `app/` and `ios/` remains in progress and must pass platform, security, and interoperability acceptance before release claims.

## Current references

- [Matrix Rust components Kotlin](https://github.com/matrix-org/matrix-rust-components-kotlin)
- [Matrix Rust components Swift](https://github.com/matrix-org/matrix-rust-components-swift)
- [Android Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Android Kotlin and AGP compatibility](https://developer.android.com/build/kotlin-support)
