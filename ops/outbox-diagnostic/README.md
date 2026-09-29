# Isolated Android outbox diagnostic

Run `./ops/outbox-diagnostic/Invoke-OutboxDiagnostic.ps1` from PowerShell 7 with Docker, `adb`, and exactly one ready Android emulator. The script selects the emulator by its `emulator-<port>` serial and ignores attached physical devices. It builds and installs only the `outboxDiagDebug` app/test APKs, then runs the fresh-account offline-send/restart sequence.

To run the broader fresh-account peer acceptance lane, use `./ops/outbox-diagnostic/Invoke-OutboxDiagnostic.ps1 -PeerAcceptance`. It provisions three new Synapse users and runs three independent `MatrixRepository` clients in separate diagnostic storage directories. The online stage checks encrypted 1:1 membership, SAS verification and cross-signing trust in both directions, recipient-offline backlog/reconnect, encrypted delivery acknowledgements, read receipts, typing, replies, reactions, and local search. It also tests group delivery accounting: one of two recipients acknowledges while the other is offline, then the second recipient reconnects and the sender reaches the explicit all-members-delivered state. Finally, the harness stops only the diagnostic Synapse service while queueing an encrypted attachment, force-stops and restores the sender, restarts Synapse, and checks that exactly one event arrives and the recipient decrypts the original bytes. This runs three Android SDK clients on one emulator; it does not replace physical-device or Android-to-iOS interoperability testing.

The diagnostic app ID is `dev.friendline.messenger.outboxdiag.debug`; its Android app data, Matrix SQLite stores, and test marker file are separate from the regular app ID. The default instrumentation runner is filtered to `OfflineOutboxIntegrationTest`; `-PeerAcceptance` selects `AndroidPeerAcceptanceIntegrationTest`. Both paths clear data only for the diagnostic app and its instrumentation package.

The Compose project is `friendline-outbox-diagnostic`. It publishes Synapse only on host loopback port `8009`, keeps Postgres un-published on a separate Docker network, and uses named volumes separate from `ops/synapse`. The app reaches host port `8009` through an `adb reverse` mapping on device loopback port `18009`; the diagnostic Synapse `public_baseurl` matches that device-visible URL because the Matrix SDK honors the homeserver URL advertised after login. Test usernames, passwords, and the message marker are generated at runtime and are not written to source or emitted in logs. The script removes the diagnostic containers/network when finished and retains the diagnostic-only volumes for inspection.

To remove only the generated diagnostic server data after reviewing it:

```powershell
docker compose --project-name friendline-outbox-diagnostic --file ops/outbox-diagnostic/compose.yaml --env-file ops/outbox-diagnostic/empty.env down --volumes
```
