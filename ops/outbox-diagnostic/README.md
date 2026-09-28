# Isolated Android outbox diagnostic

Run `./ops/outbox-diagnostic/Invoke-OutboxDiagnostic.ps1` from PowerShell 7 with Docker, `adb`, and exactly one Android emulator available. The script builds and installs only the `outboxDiagDebug` app/test APKs, then runs the fresh-account offline-send/restart sequence.

The diagnostic app ID is `dev.friendline.messenger.outboxdiag.debug`; its Android app data, Matrix SQLite store, and test marker file are separate from the regular app ID. The instrumentation runner is filtered to `OfflineOutboxIntegrationTest`. The script clears data only for the diagnostic app and its instrumentation package.

The Compose project is `friendline-outbox-diagnostic`. It publishes Synapse only on host loopback port `8009`, keeps Postgres un-published on a separate Docker network, and uses named volumes separate from `ops/synapse`. The app reaches host port `8009` through an `adb reverse` mapping on device loopback port `18009`; the diagnostic Synapse `public_baseurl` matches that device-visible URL because the Matrix SDK honors the homeserver URL advertised after login. Test usernames, passwords, and the message marker are generated at runtime and are not written to source or emitted in logs. The script removes the diagnostic containers/network when finished and retains the diagnostic-only volumes for inspection.

To remove only the generated diagnostic server data after reviewing it:

```powershell
docker compose --project-name friendline-outbox-diagnostic --file ops/outbox-diagnostic/compose.yaml --env-file ops/outbox-diagnostic/empty.env down --volumes
```
