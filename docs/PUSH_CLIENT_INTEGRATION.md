# Native push client scaffold

The native clients now have a privacy-safe push boundary. Push is disabled by default and remains unavailable until an operator supplies a Matrix domain plus the platform provider setup. No Firebase project file, provider credential, APNs key, or real Matrix domain is included in the repository.

## Matrix pusher contract

After an authenticated session is restored or created, the repository/store can call the native push client to register the current provider token. Before signing out, it can call the corresponding delete hook while the Matrix access token is still valid.

Both clients send an authenticated `POST /_matrix/client/v3/pushers/set` to the session homeserver. The pusher uses the platform app ID, `kind: "http"`, the provider token as `pushkey`, and:

```json
{
  "data": {
    "format": "event_id_only",
    "url": "https://<configured Matrix domain>/_matrix/push/v1/notify"
  }
}
```

The iOS pusher adds a fixed generic APNs alert through Sygnal's `default_payload`; Android omits that field and creates its own fixed generic notification from the data push. The iOS default payload contains no sender, room title, or message text:

```json
{
  "aps": {
    "alert": {
      "title": "Private Messenger",
      "body": "New message"
    },
    "sound": "default"
  }
}
```

Sygnal merges this static alert into its `event_id_only` APNs payload, which carries routing identifiers but no event content. Sign-out sends `kind: null` with the same `app_id` and `pushkey`. Requests require HTTPS, reject redirects, put the access token only in the Authorization header, and do not record response bodies or tokens in logs. The Matrix homeserver and Sygnal must continue to omit event content from push payloads.

## Android

`MatrixPushClient` in `app/src/main/java/dev/friendline/messenger/push/PushNotifications.kt` exposes:

- `notificationPermissionContract()` and `notificationPermissionToRequest(context)` for a user-initiated Android 13+ permission prompt.
- `register(context, MatrixPushSession(...))` to acquire the latest FCM token and set the pusher.
- `refreshedTokens` plus `registerToken(context, session, token)` to re-register after FCM rotates its token, with notification permission checked again.
- `unregister(context, session)` to delete the pusher before logout.

`MatrixRepository` coordinates these calls with an encrypted `DeviceVault` journal. It stores only the FCM provider token, homeserver/user/device/app identity, and a pending operation; Matrix access and refresh tokens are never copied into that journal. It atomically persists opt-out and a cleanup-needed marker before writing the detailed removal record, then persists the exact pusher token before issuing `kind: null`. This closes the process-death window between the user's opt-out and the journal write: after restore, the marker recreates a removal record and retries cleanup before registration can resume. On token rotation, it removes the old token's pusher before registering the replacement. If remote removal or local journal cleanup fails, sign-out remains blocked and the Settings panel offers **Retry cleanup**. Removal is retried after session restore and when the app returns to the foreground. A recovered logout removal also durably turns opt-in off before deletion, so a process death cannot silently register again.

`PrivateMessengerFirebaseMessagingService` ignores every incoming payload field and posts only “Private Messenger” / “New message”. Event IDs, room identifiers, sender metadata, and any other remote values are not forwarded into the notification or its launch intent.

Push is opt-in for a local build. Provide the non-secret build properties and matching Firebase app configuration only on the development machine:

```powershell
.\gradlew.bat :app:assembleStandardDebug `
  -PprivateMessengerPushEnabled=true `
  -PprivateMessengerMatrixDomain=matrix.example.org
```

Put the Firebase `google-services.json` in `app/` or `app/src/<variant>/`. The Google Services plugin is applied only when push is explicitly enabled and a configuration file exists. Firebase app entries must match the exact Android `applicationId` of every variant being built, including debug/outbox diagnostic suffixes. If configuration is absent, ordinary builds still work and token registration returns a disabled/provider-unavailable status without initializing a Firebase app.

Integration order: request notification permission from a user action; after login/session restore call `register`; collect `refreshedTokens` while the session is active and call `registerToken(context, session, token)` for rotations; before sign-out call `unregister` before invalidating the access token.

## iOS

`NativePushNotifications` in `ios/Sources/PushNotifications.swift` exposes:

- `requestPermissionAndRegister()` for a user-initiated alert permission request.
- `currentToken` and `APNSTokenStore.shared.onTokenChange` for the in-memory APNs token.
- `register(homeserverURL:accessToken:pushToken:)` after login/session restore and after token rotation.
- `unregister(homeserverURL:accessToken:pushToken:)` before logout.

The live APNs token is base64-encoded as recommended by the Matrix specification and held in memory for registration. The minimum pusher identity and token needed for reliable removal are also persisted in device-only Keychain records; access tokens are never persisted there. Pending removal survives app restart and is retried while the matching account is active. The app re-registers with APNs on launch after notification authorization, allowing Apple to provide a current token. The pusher uses Sygnal's `default_payload` with fixed title/body/sound values; keep it static so push content never depends on sender or event fields.

`ios/project.yml` defaults `MATRIX_PUSH_ENABLED` to `NO` and leaves `MATRIX_PUSH_DOMAIN` empty. A local Xcode build can opt in with non-secret build settings, for example:

```sh
cd ios
xcodegen generate -s project.yml
xcodebuild -project PrivateMessenger.xcodeproj -scheme PrivateMessenger build \
  MATRIX_PUSH_ENABLED=YES MATRIX_PUSH_DOMAIN=matrix.example.org
```

APNs token registration requires an Apple App ID with Push Notifications enabled and a matching provisioning profile/team. The project now carries the APS environment entitlement for development and distribution configurations; Apple signing and device APNs registration still need macOS/Xcode and Apple account setup.

The project selects the Matrix/Sygnal pusher app ID by build configuration: debug uses `dev.friendline.messenger.ios.dev` with APNs sandbox, and release uses `dev.friendline.messenger.ios` with APNs production. Both entries use the same APNs topic (`dev.friendline.messenger.ios`, the bundle ID); matching Sygnal entries are in `ops/private-deployment/sygnal.yaml`. This split does not provide signing or provider credentials. APNs token registration and delivery still require macOS/Xcode, Apple account setup, a matching provisioning profile, and a signed physical device.

## Integration and validation limits

Both native clients connect the push boundary to session restore/login, explicit notification settings, token refresh, foreground refresh, and sign-out. Android encrypts the minimum pusher identity and FCM token needed for rotation and reliable removal in `DeviceVault`. iOS stores the minimum pusher identity, APNs token, and pending-removal intent in device-only Keychain entries. Both platforms require successful remote pusher removal and durable local cleanup while the Matrix session is still available; on failure they keep the session active for retry.

Push is a best-effort wakeup; Matrix sync remains the source of truth. Event/room IDs, provider tokens, delivery timing, and other routing metadata can still be visible to the homeserver, push gateway, and provider. Actual APNs/FCM delivery and payload behavior remain unverified because this project has no Firebase/APNs credentials or signed iOS device build. Keep push disabled until those deliveries are tested on configured devices. This Windows host cannot create/sign an iOS app or obtain a real APNs device token.
