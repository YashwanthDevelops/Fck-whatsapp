# Project access requirements

No Apple signing identity, provider secret, production host, or domain is configured. A local Android release-signing identity now exists in the ignored, account-restricted `ops/private-deployment/credentials/android-release/` directory. Never paste passwords, private keys, certificates, or API secrets into chat.

## Required now

| Requirement | Why / when | Where stored | Does the coding agent need it? | Local replacement |
|---|---|---|---|---|
| Android Studio or Android command-line SDK + JDK 17/21 | Build and run Android client; needed for Phase 1 | Installed developer machine; SDK path in ignored `local.properties` or environment | Yes, locally for builds | None for a real APK build; source can be edited without it |
| Docker Desktop Linux engine + Docker Compose | Run the private Synapse/PostgreSQL service for development | Local OS service and ignored `data/` volume | Yes, locally to exercise the service | No; another Linux Docker host can run the same Compose file |
| Android emulator or Android phone | Exercise Android login, offline queue, and UI | Physical device/emulator; test account credentials in OS password manager | Yes, for device tests | Emulator can cover some flows; physical-device verification is still required |
| Git | Track the project and review changes | Local repo | Yes | None for collaborative project history |

## Required later

| Requirement | Why / when | Where stored | Does the coding agent need it? | Local replacement |
|---|---|---|---|---|
| Mac with Xcode and Swift | Build the iOS app, use Simulator, sign development builds, and test lifecycle | A Mac owned/controlled by the project | Yes on that Mac for iOS builds | No Windows equivalent; source editing alone is not validation |
| iPhone (and Android/iOS pair) | Validate real cross-platform E2EE, notifications, gestures, and background behavior | Physical devices controlled by the project | Only for authorized hands-on test sessions | Simulator/emulator is partial; hardware behavior must be checked before relying on the app daily |
| Stable Matrix domain and private server/VPS | Host the friend-group homeserver outside local development; the domain is part of Matrix user IDs and should be chosen permanently | Domain registrar, provider account, DNS, and host secret store | Deployment operator needs access; never share account passwords in chat | Local Synapse validates code but cannot be used by friends over the internet |
| Mac with Xcode 16.3+ and Swift | Resolve/build the current Swift dependency, run iOS Simulator, sign and test iOS lifecycle and calls | A Mac owned/controlled by the project | Required locally for the iOS build; no Windows equivalent | Source editing alone cannot validate iOS binaries |
| iPhone and an Android/iOS pair | Verify actual Android↔iOS E2EE, calls, notifications, and background behavior | Physical devices controlled by the project | Required for final device acceptance | Simulator/emulator coverage is partial |
| Apple ID / signing identity, Firebase and APNs credentials | Install privately distributed iOS builds and validate background notifications | Xcode Keychain, Apple Developer portal, Firebase project and protected secret storage | Only through the user's controlled Apple/Firebase sessions; never send secrets through chat | No real push or iOS device install without provider credentials |
| Android signing-key backup | Preserve the stable signing identity for future updates | Back up `ops/private-deployment/credentials/android-release/friendline-private-release.p12` and its `signing-password.txt` into an encrypted password manager/offline backup | User should secure and retain the local generated identity before sharing builds | Replacing the key after friends install prevents seamless APK updates |
| Call service deployment and TURN credentials | Run the self-hosted SFU and support reliable calls over mobile networks | Private Linux host, DNS/TLS, protected server secrets | Deployment operator needs access; no media key is shared with the service | LiveKit media hosts exist on both platforms, but Android outbound key delivery is disabled because the Matrix binding cannot select individually verified peer devices; iOS also lacks encrypted custom to-device APIs. UI integration, trusted routing, both builds, and device interoperability remain incomplete |

## Optional

| Requirement | Why / when | Where stored | Does the coding agent need it? | Local replacement |
|---|---|---|---|---|
| Apple Developer Program membership | TestFlight or broader private iOS distribution, if desired beyond personal-device development signing | Apple account | Through the user's Apple account | Limited development signing is possible with an Apple ID, subject to Apple limits |
| Git hosting account | Remote backup, issue tracking, CI, and collaboration | Provider's interactive authentication | No credentials should be sent to the agent | Local Git repo |

## Not required

- Public app-store accounts or marketing assets for the first private release.
- Phone numbers, address-book access, contact-discovery service, analytics provider, Kubernetes, Kafka, Redis, or public user-directory service.
- Shared passwords or private encryption keys in chat.

## Machine validation (2026-09-30)

Present: Windows, Git, Docker Compose, JDK 17, Android SDK under ignored `.tools/`, Gradle wrapper, and `adb`. The Android API 37 emulator has passed prior isolated Android peer/outbox acceptance. Four signed per-ABI Android release APKs and a signed AAB were verified; the emulator installed and launched its x86_64 release APK. Current Docker Desktop Linux Engine requests fail with HTTP 500, so the local Matrix service cannot currently be started or revalidated. Android Studio, macOS/Xcode, and a configured iPhone are not present. The private Matrix host and stable domain are not provisioned. iOS compilation, Apple signing, Android↔iOS device acceptance, public backend deployment, provider push, and calls remain outstanding. Call integration is being designed for iOS 16 with a custom LiveKit media host; its E2EE key exchange and trusted-device routing are not yet implemented or validated.
