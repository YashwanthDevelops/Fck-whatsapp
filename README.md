<div align="center">

# Friendline

**Private, end-to-end encrypted messaging for a small circle.**

Native Android and iOS clients · Matrix identity and sync · Self-hosted Synapse

[![Android](https://img.shields.io/badge/Android-Kotlin%20%7C%20Compose-3DDC84?logo=android&logoColor=white)](app/)
[![iOS](https://img.shields.io/badge/iOS-SwiftUI-147EFB?logo=apple&logoColor=white)](ios/)
[![Matrix](https://img.shields.io/badge/Protocol-Matrix-0DBD8B?logo=matrix&logoColor=white)](docs/ARCHITECTURE.md)
[![Status](https://img.shields.io/badge/Status-In%20development-orange)](docs/TESTING.md)

</div>

> [!WARNING]
> Friendline is an active prototype, not ready for everyday use. Recent Android changes still need a fresh peer-acceptance run. iOS builds, Android↔iOS interoperability, real push delivery, live calls, and hosted deployment are not yet verified. See [testing status](docs/TESTING.md).

## At a glance

| Android unit tests | Signed Android outputs | Earlier Android peer run | Cross-platform acceptance |
| :---: | :---: | :---: | :---: |
| **44 passed** | **4 APKs + 1 AAB** | **3 clients · API 37** | **Pending** |

Build and test results are recorded in the [testing log](docs/TESTING.md). The three-client run predates the latest changes; it is not current-source acceptance.

## What’s here

- Encrypted Matrix direct and group conversations, with device verification.
- Offline message queue and reconnect recovery, plus delivery receipts distinct from read receipts.
- Replies, reactions, edits, redactions, local search, encrypted attachments, and QR friend exchange.
- Native Android client in Kotlin/Jetpack Compose and iOS client in SwiftUI.
- Private Synapse/PostgreSQL deployment configuration, with optional push and call-service components.

Calls and push are still integration work; neither has passed live end-to-end acceptance. Android and iOS are not yet verified against each other.

## Stack

| Layer | Technology |
| --- | --- |
| Android | Kotlin · Jetpack Compose · Matrix Rust SDK |
| iOS | Swift · SwiftUI · Matrix Rust SDK |
| Messaging | Matrix · encrypted rooms · Synapse · PostgreSQL |
| Optional calls | LiveKit · self-hosted call authorization |

## Get started

Read the [development guide](docs/DEVELOPMENT.md) for local setup and homeserver instructions. Android builds use a locally generated, patched Matrix SDK AAR that is intentionally excluded from Git; see the [SDK build notes](ops/sdk-build/README.md) before building from a fresh clone.

## Project notes

[Architecture](docs/ARCHITECTURE.md) · [Security](docs/SECURITY.md) · [Threat model](docs/THREAT_MODEL.md) · [Testing](docs/TESTING.md) · [Deployment](docs/DEPLOYMENT.md) · [Calls](docs/CALLS.md)
