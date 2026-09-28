# Development setup

## Required toolchains

| Tool | Target |
|---|---|
| Android Studio with a compatible bundled JDK, Android SDK Platform, Build Tools, and NDK | Android app and Matrix SDK Android bindings |
| Android Studio emulator or a USB-debuggable Android device | Android run/debug |
| Xcode with matching iOS SDK and Swift toolchain | iOS app and Matrix SDK Swift package |
| Docker Desktop Linux containers and Compose | Local Synapse/PostgreSQL |
| Git | Version control |

Use the Gradle wrapper checked into the repository root; Android sources are currently under `app/`. Do not rely on a globally installed Gradle. Android SDK packages and a JDK 17 are available for isolated Linux-container builds. The Windows Java selector failed to establish its loopback selector; use the project container build or Android Studio's supported bundled JDK.

## Version selection

Pinned candidate dependencies selected during feasibility inspection on 2026-09-28 (Android build-validated; remaining platform and runtime behavior still needs validation):

- Matrix Rust SDK Kotlin distribution candidate: official Matrix Rust components Kotlin `26.09.9`, Maven coordinate `org.matrix.rustcomponents:sdk-android:26.09.9`, embedding Rust SDK commit `ab673a6d71e6333934cf6cb8f87f9578cdfaed5a`.
- Matrix Rust SDK Swift package candidate: official Matrix Rust components Swift `26.08.11`, embedding Rust SDK commit `15a3ce2369a1e96fcad02cb2b1da3198ef23348a`; its package manifest declares iOS 16 minimum. The Swift wrapper API is explicitly unstable, so pin the package and isolate it behind an app adapter.
- The iOS project spec now pins Matrix Rust SDK `26.08.11` exactly. The generated Xcode workspace's `Package.resolved` lockfile still must be produced and committed on a Mac with Xcode before release builds, so transitive package resolution is not yet locked here.
- The candidate bindings embed different Rust SDK revisions. Matrix wire interoperability is expected, but Phase 0 must compare release/security changes, align revisions where practical, and prove Android→iOS and iOS→Android before accepting the pair. Do not infer compatible security-fix coverage from matching-looking package numbers. [Kotlin release](https://github.com/matrix-org/matrix-rust-components-kotlin/releases/tag/sdk-v26.09.9), [Swift release](https://github.com/matrix-org/matrix-rust-components-swift/releases/tag/26.08.11)
- Synapse: `v1.161.0` image for the current Compose environment (latest stable release verified 2026-09-28; keep image pinned and update deliberately).
- PostgreSQL: major 16 image tag for a supported, conservative line; patch images should be refreshed as part of maintenance.
- Android Compose BOM: `2026.09.00`, compile SDK 37, AGP 9.3.3, Gradle 9.5, Kotlin/Compose compiler 2.4.10. Debug and minified release APK builds have completed in the isolated Linux Gradle container.
- AGP/Gradle/JDK and Kotlin/Compose compiler versions must be locked together only after the Phase 0 Android build succeeds in the chosen Android Studio release.

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

## Secrets and local data

`.env`, `ops/synapse/data/`, Android `local.properties`, app keystores, iOS provisioning files, and device credentials must stay out of Git. Development homeserver state can be removed and recreated; never use production accounts or data in the local development container.

## Current workspace and platform constraints

The local Synapse and PostgreSQL services are available. Android SDK packages and JDK 17 are present under ignored `.tools/` directories; debug and minified release builds succeed in the isolated Linux Gradle container. An API 37 emulator has passed the encrypted offline-send, force-stop, restart, and reconnect outbox gate. Android Studio is not configured here. iOS requires macOS and Xcode for compiling SwiftUI, resolving the binary XCFramework, Simulator use, codesigning, and device validation. The current machine cannot meet that part of the definition of done. An iOS build must be performed on a Mac before claiming the app is complete.

The user approved autonomous implementation through the complete product scope on 2026-09-28. Continue implementation after Phase 0 without another approval stop. The source under `app/` and `ios/` remains in progress and must pass platform, security, and interoperability acceptance before release claims.

## Current references

- [Matrix Rust components Kotlin](https://github.com/matrix-org/matrix-rust-components-kotlin)
- [Matrix Rust components Swift](https://github.com/matrix-org/matrix-rust-components-swift)
- [Android Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Android Kotlin and AGP compatibility](https://developer.android.com/build/kotlin-support)
