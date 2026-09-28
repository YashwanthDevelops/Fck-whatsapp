# Project access requirements

No credentials, Apple membership, deployment host, or signing material were found in the workspace. Never paste passwords, private keys, certificates, or API secrets into chat.

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
| Private server/VPS and stable domain with HTTPS | Host the friend-group homeserver outside local development | Provider account, DNS, and OS secret store | Deployment operator needs access; an agent does not need account password | Local Synapse works for development; LAN testing works on a trusted network |
| Apple signing identity / Apple ID | Install a development build on an iPhone; later private distribution | Xcode Keychain and Apple's developer portal | Agent only via a controlled Mac workflow; never send certificate/private key through chat | iOS Simulator needs no paid Developer Program membership; device installs require Apple signing |
| Android signing key | Produce a stable privately shared APK | Offline encrypted password manager / secure backup | Not for debug builds; release signer only through local secure process | Debug APK for initial testing; it is not a stable release identity |

## Optional

| Requirement | Why / when | Where stored | Does the coding agent need it? | Local replacement |
|---|---|---|---|---|
| Apple Developer Program membership | TestFlight or broader private iOS distribution, if later desired | Apple account | No by default | Xcode development signing supports limited personal-device testing, subject to Apple limits |
| Firebase/FCM project and Apple APNs credentials | Generic message wakeups after messaging behavior is stable | Android/iOS secret managers; APNs key in protected CI/Keychain | Only when configuring push; never in source or chat | Foreground/next-launch sync remains available; background wakeups are best effort |
| TURN service credentials | Reliable WebRTC calls in Phase 4 | Server secret store / environment file | Only when implementing calls | Local host candidates can test some same-network calls; not a reliable internet replacement |
| Git hosting account | Remote backup, issue tracking, CI, and collaboration | Provider's interactive authentication | No credentials should be sent to the agent | Local Git repo |

## Not required

- Public app-store accounts or marketing assets for the first private release.
- Phone numbers, address-book access, contact-discovery service, analytics provider, Kubernetes, Kafka, Redis, or public user-directory service.
- Shared passwords or private encryption keys in chat.

## Machine validation (2026-09-28)

Present: Windows, Git, Docker Desktop Linux engine/Compose, Java 26, a downloaded JDK 17, Android SDK packages under ignored `.tools/`, Gradle wrapper, and `adb`. An API 37 Android emulator and isolated Linux-container Gradle builds are available; debug, minified release, and outbox integration builds have succeeded. Android Studio, macOS/Xcode, and a configured iPhone are not present. Physical Android↔iPhone testing and iOS build/signing still require access to a Mac and devices.
