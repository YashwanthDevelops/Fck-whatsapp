# Project access requirements

No Apple signing identity, provider secret, production host, or domain is configured. A local Android release-signing identity now exists in the ignored, account-restricted `ops/private-deployment/credentials/android-release/` directory. Never paste passwords, private keys, certificates, or API secrets into chat.

## Required now

| Requirement | Why / when | Where stored | Does the coding agent need it? | Local replacement |
|---|---|---|---|---|
| Android Studio or Android command-line SDK + JDK 17/21 | Build and run Android client; needed for Phase 1 | Installed developer machine; SDK path in ignored `local.properties` or environment | Yes, locally for builds | None for a real APK build; source can be edited without it |
| Docker Desktop Linux engine + Docker Compose | Run the private Synapse/PostgreSQL service for development | Local OS service and ignored `data/` volume | Yes, locally to exercise the service | No; another Linux Docker host can run the same Compose file |
| WSL/Linux Swift 6.4 and xtool 1.20.1 with the Darwin Swift SDK | Build the actual iOS SwiftPM app and pinned Matrix XCFramework without the Xcode IDE | WSL developer environment; install the SDK through `xtool setup` | Yes, for the iOS build gate | Swift parsing and `ios/core` tests work without the Darwin SDK but do not validate the app binary |
| Apple account access | `xtool setup` downloads Apple's Darwin SDK material and authenticates development signing; credentials stay in the local xtool flow | User-controlled Apple account and local xtool credential store | Only through the user's controlled session; never share credentials in chat | No account-free route to the Darwin SDK or development signing |
| Android emulator or Android phone | Exercise Android login, offline queue, and UI | Physical device/emulator; test account credentials in OS password manager | Yes, for device tests | Emulator can cover some flows; physical-device verification is still required |
| Git | Track the project and review changes | Local repo | Yes | None for collaborative project history |

## Required later

| Requirement | Why / when | Where stored | Does the coding agent need it? | Local replacement |
|---|---|---|---|---|
| iPhone (and Android/iOS pair) | Validate real cross-platform E2EE, notifications, gestures, and background behavior | Physical devices controlled by the project | Only for authorized hands-on test sessions | Simulator/emulator is partial; hardware behavior must be checked before relying on the app daily |
| Stable Matrix domain and private server/VPS | Host the friend-group homeserver outside local development; the domain is part of Matrix user IDs and should be chosen permanently | Domain registrar, provider account, DNS, and host secret store | Deployment operator needs access; never share account passwords in chat | Local Synapse validates code but cannot be used by friends over the internet |
| Firebase project and APNs credentials | Validate Android/iOS push delivery and token removal after opt-in | Firebase project and protected secret storage; Apple provider key in the user's controlled Apple account | Only through controlled provider sessions; never share secrets in chat | No real provider delivery without these credentials |
| Android signing-key backup | Preserve the stable signing identity for future updates | Back up `ops/private-deployment/credentials/android-release/friendline-private-release.p12` and its `signing-password.txt` into an encrypted password manager/offline backup | User should secure and retain the local generated identity before sharing builds | Replacing the key after friends install prevents seamless APK updates |

The iOS build route is WSL/Linux + SwiftPM + xtool; a Mac and the Xcode IDE are not project prerequisites. `xtool dev build` is the app-build gate, while `xtool dev` signs, installs, and launches on a paired iPhone. xtool does not replace app-level tests or physical-device acceptance. Calls are excluded from the messenger completion gates.

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

Present: Windows/WSL, Swift 6.4.0, xtool 1.20.1, Git, JDK 17, Android SDK under ignored `.tools/`, Gradle wrapper, and `adb`. `xtool sdk status` reports `Not installed`; Apple account access and an iPhone are not available, so the actual iOS app build/sign/install gate cannot close yet. Linux Swift parsing and all 20 `ios/core` tests pass. Android standard Kotlin compilation and unit tests pass, but the connected Android device is `unauthorized` and requires local USB-debugging approval. Docker Desktop's Linux engine is unavailable at `npipe:////./pipe/dockerDesktopLinuxEngine`, so local Synapse/PostgreSQL and Android peer acceptance cannot run. The private Matrix host and stable domain are not provisioned. Android↔iOS device acceptance, public backend deployment, provider push, and release signing remain outstanding.
