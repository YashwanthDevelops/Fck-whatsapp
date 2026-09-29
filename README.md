<div align="center">

# Friendline

**Private conversations for a small circle of friends.**

End-to-end encrypted messaging built on Matrix, with native Android and iOS clients.

![Android](https://img.shields.io/badge/Android-Kotlin%20%7C%20Compose-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-SwiftUI-147EFB?logo=apple&logoColor=white)
![Protocol](https://img.shields.io/badge/protocol-Matrix-0DBD8B?logo=matrix&logoColor=white)
![Status](https://img.shields.io/badge/status-prototype-orange)

</div>

> [!WARNING]
> **Work in progress.** This prototype is not ready for everyday use or public distribution. Android↔iOS encryption, physical-device security, production hosting, and several release checks remain unverified. See the [testing record](docs/TESTING.md).

## Project snapshot

| Measure | Recorded result |
| --- | --- |
| Android unit tests | 12 passed in the recorded run |
| Peer acceptance clients | 3 Android SDK clients on one API 37 emulator |
| Group delivery | `1 of 2` recipients while one was offline; `2 of 2` after reconnect |
| Android↔iOS encrypted exchange | Not yet verified |
| Physical-phone acceptance | Not yet passed |

The peer test covered encrypted conversations, offline delivery and recovery, delivery acknowledgements, read receipts, typing, replies, reactions, local search, and attachment recovery. Emulator results do not establish physical-device or cross-platform behavior.

## Architecture

| Client | Stack |
| --- | --- |
| Android | Kotlin · Jetpack Compose · Matrix Rust SDK |
| iOS | Swift · SwiftUI · Matrix Rust SDK |
| Homeserver | Matrix Synapse · PostgreSQL |

Rooms require end-to-end encryption; the app must not fall back to plaintext. The homeserver routes encrypted events. The current server setup is for local development—friend-accessible production hosting is not configured.

## Development

Start with the [development guide](docs/DEVELOPMENT.md) for toolchains, local Synapse setup, and running the clients.

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [Security](docs/SECURITY.md) · [Threat model](docs/THREAT_MODEL.md) · [Testing status](docs/TESTING.md) · [Deployment](docs/DEPLOYMENT.md)
