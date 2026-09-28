# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Stack

Approved direction (2026-09-28): Kotlin with Jetpack Compose on Android, Swift with SwiftUI on iOS, and the Matrix Rust SDK's official Kotlin/Swift bindings for messaging and encryption. Kotlin Multiplatform is deferred because the Matrix SDK already exposes the same Rust client/crypto stack to both native apps. See `docs/PHASE_0_ANALYSIS.md`.

## Users

A small group of trusted friends using different Android and iOS devices. They want to stay in touch during ordinary daily life and may have unreliable or absent network access.

## Product Purpose

Provide a dependable private messenger for the friend group: encrypted one-to-one and small-group conversations, media and voice/video calls. Sending should appear immediate, queued messages should survive offline periods and app restarts, and encryption should happen automatically without asking users to manage cryptographic details during ordinary use.

## Positioning

A private, self-hosted messenger for a known circle of friends. Accounts are provisioned for that circle, and friends connect by exchanging a QR identity or Matrix ID. There is no public directory or contact-book upload.

## Operating Context

People send short messages and media from mobile devices while connected, offline, switching networks, or returning to the app after it has been suspended. Android and iOS are equally important. The first useable milestone is encrypted Android-to-iOS and iOS-to-Android messaging.

## Capabilities and Constraints

- One-to-one and invite-only small-group messaging use end-to-end encryption. Group delivery is reported per member; a single member's acknowledgement must never imply delivery to the whole group.
- Local SDK storage encryption, optimistic message display, persistent offline sending/retry, and synchronization must survive process restarts and network changes.
- Messages support sent and recipient-delivered states, optional read receipts, typing, replies, reactions, edits/redactions, and on-device encrypted-message search.
- Photos, videos, documents, and voice messages are encrypted before upload and support interrupted-transfer recovery.
- Voice and video calls use end-to-end encrypted media; the homeserver and media relay do not receive plaintext media keys.
- Push notifications are generic and contain no message contents. Background delivery remains subject to operating-system scheduling.
- A private Matrix Synapse homeserver with PostgreSQL stores and routes Matrix events. It must not receive message plaintext or media decryption keys.
- Device enrollment and identity verification must be understandable. Losing a device or its encryption keys can mean losing access to old encrypted history; server-readable recovery is out of scope for the first release.
- Push notifications must not include message content. Mobile background execution and push delivery are best-effort operating-system services, not an always-on guarantee.
- The product name, logo, final account presentation, deployed domain, and recovery flow remain undecided.
- No public launch, mass-market discovery, analytics, or large-scale infrastructure is required.

## Completion contract

Phase numbers describe engineering order. They are validation milestones, not release cuts or requests for renewed approval. Completion requires a usable Android app, a usable iOS app, the private backend, encrypted cross-platform messaging and calling, and end-to-end acceptance on real installations. If a required Apple account, signing credential, hosting account, secret, or physical-device action is unavailable, implementation continues up to that exact external dependency and records the user action needed to unblock it.

## Brand Commitments

The experience should feel like a polished, everyday native messenger. iMessage is a quality reference for responsiveness and interaction only; Apple's branding, assets, and screen layouts are not to be copied. No product name, logo, or visual identity has been supplied.

## Evidence on Hand

The supplied product brief is the product source of truth. Partial Android/iOS scaffolding exists from the earlier project direction, but it is unapproved and unverified. No user research, logo, illustration, or brand asset was supplied.

## Product Principles

- Make the local interaction feel immediate while delivery work continues safely in the background.
- Make privacy the default behavior rather than an advanced setting.
- Keep the service private and operationally simple for a small friend group.
- Make connectivity and failure states clear, and preserve user-authored messages through failures.
- Respect Android and iOS conventions while keeping the messaging protocol interoperable.

## Accessibility & Inclusion

Use native dynamic text sizing, semantic controls, screen-reader labels, sufficient touch targets, contrast-aware light and dark appearances, and reduced-motion behavior on both platforms.
