<div align="center">

# Private Messenger

**Private conversations for a small circle of friends.**

Android · iOS · Matrix

</div>

> [!WARNING]
> **Work in progress.** This project is not ready for everyday use or distribution. Android↔iOS encryption, full iOS build and device validation, and remaining security and reliability checks are still open. See the [test record](docs/TESTING.md).

An invite-only messenger built with native Android and iOS apps and a private, self-hosted Matrix homeserver. End-to-end encryption is required; the app must never fall back to plaintext rooms.

## 🏗️ Architecture

| Client | Stack |
| --- | --- |
| Android | Kotlin · Jetpack Compose · Matrix Rust SDK |
| iOS | Swift · SwiftUI · Matrix Rust SDK |
| Server | Matrix Synapse · PostgreSQL |

The homeserver routes encrypted events. Message and media content should remain encrypted on the devices.

## 🚀 Get started

See the [development guide](docs/DEVELOPMENT.md) for toolchains, local homeserver setup, and running the apps.

## 📚 Project docs

[Architecture](docs/ARCHITECTURE.md) · [Security](docs/SECURITY.md) · [Threat model](docs/THREAT_MODEL.md) · [Testing status](docs/TESTING.md) · [Deployment](docs/DEPLOYMENT.md)
