# Private Matrix homeserver deployment

This is a separate hosted deployment bundle for the private friend group. It does not share the local development stack's bind mounts, Compose project, or data. It prepares Synapse with PostgreSQL behind Caddy-managed HTTPS. An optional Sygnal profile and app configuration are included for Android/iOS push delivery; it remains inactive until real provider credentials are configured and end-to-end delivery is validated.

The stack keeps public registration and guest access disabled, blocks federation at both the Synapse listener and configuration layers, disables URL previews and anonymous statistics, and sets `push.include_content: false`. PostgreSQL is initialized with UTF-8 encoding and the `C` locale required by Synapse; this only takes effect when its data volume is first created. PostgreSQL, Synapse, and Caddy state use named Docker volumes. The database has no published port. Synapse is reachable only from Caddy and the call authorizer on an isolated Docker network, and its outbound network is isolated as well. On that private proxy network, Docker resolves the configured Matrix domain to Caddy so Synapse can reach the same HTTPS push URL without public-network egress. Since Synapse's outbound IP-range protection also applies to push gateways and blocks private addresses by default, Caddy receives a fixed address and Synapse permits only that exact `/32`. The default proxy subnet is `172.30.255.0/29`; if it overlaps a host, VPN, or Docker network, set both `PROXY_NETWORK_SUBNET` and `CADDY_PROXY_IP` in `.env` to a free RFC1918 subnet between `/24` and `/29` and a usable address inside it before first setup. Caddy publishes TCP 80/443. The optional calls profile uses UDP 443 for TURN, so Caddy's HTTP/3 listener is disabled.

`MATRIX_SERVER_NAME` is the immutable Matrix identity in user IDs and must match the existing Synapse configuration and database. Friendline's existing identity is `localhost`, so keep `MATRIX_SERVER_NAME=localhost` when preserving those accounts and E2EE device identities. `MATRIX_DOMAIN` is the separate public HTTPS hostname used by Caddy and `public_baseurl`; for production it is `matrix.friendline.run.place`. Changing the public hostname does not change Matrix IDs. The configuration helper refuses to run if the existing `server_name` differs from `MATRIX_SERVER_NAME`.

## Host requirements

- A Linux host running Docker Engine and Docker Compose V2. Keep the host patched and restrict administrative access.
- A stable public DNS name that you control, with A and, if used, AAAA records pointing to this host. Remove an AAAA record if IPv6 is not reachable from the public internet.
- Inbound TCP ports 80 and 443 allowed through both the host firewall and hosting provider firewall. If enabling calls, also allow TCP 7881 and UDP 443, 40000–40099, and 50000–50100. Caddy needs outbound access for DNS and ACME certificate issuance/renewal; LiveKit needs outbound connectivity for public-address discovery.
- Durable storage with encryption at rest and enough room for the PostgreSQL database, Synapse media store, signing/configuration keys, and backups.

The bundle does not create a host, domain, DNS records, firewall rules, or backups. The Sygnal route/configuration contract and native client lifecycle code are present, but push delivery needs Firebase and Apple Developer credentials plus end-to-end validation on signed devices. The optional call backend and standalone Android/iOS LiveKit media-session components are present, but neither client has a usable call flow or validated end-to-end media encryption.

## First-time setup

Run these commands on the Linux host from this directory (`ops/private-deployment`). Never copy a real password into the repository.

1. Create the database password and a private Compose environment file. Keep the password on the host and outside source control:

   ```sh
   install -d -m 700 secrets
   openssl rand -hex 32 > secrets/postgres_password
   chmod 600 secrets/postgres_password
   cp .env.example .env
   chmod 600 .env
   ```

2. Edit `.env`: preserve `MATRIX_SERVER_NAME=localhost`, set `MATRIX_DOMAIN=matrix.friendline.run.place`, and confirm `POSTGRES_PASSWORD_FILE=./secrets/postgres_password`. Calls are an optional Compose profile; do not set up `CALLS_DOMAIN` or LiveKit keys for a messenger-only server. `MATRIX_DOMAIN` must be the stable public DNS name served by Caddy; the helper rejects reserved documentation and local-only hostnames. This syntax check cannot verify DNS ownership or reachability. The image tags are pinned to release versions. Before production, review security advisories and pin each image to a verified digest in the same change-control process.

   The default proxy subnet is `172.30.255.0/29`, with Caddy at `172.30.255.2`. Check that it does not overlap a host, VPN, or existing Docker network. If it does, add `PROXY_NETWORK_SUBNET=<free-RFC1918-subnet-from-/24-through-/29>` and `CADDY_PROXY_IP=<usable-address-in-that-subnet>` to `.env`; the Synapse helper validates the address/subnet pairing and permits only the exact Caddy address for push-gateway requests.

3. Confirm that Compose resolves the required variables and secret file, without starting containers:

   ```sh
   docker compose --env-file .env config --quiet
   ```

4. The following initialization is only for a genuinely new Synapse identity and an empty Synapse volume. It is not the migration procedure for Friendline's existing `localhost` server. Never run `synapse generate` against restored or existing Synapse data: preserve and restore the existing database and complete Synapse data (including its signing key, media, and `homeserver.yaml`), then confirm `server_name: localhost` before applying the private-server settings. For any existing database or data volume, stop here and use the separately approved migration/restore procedure.

   For a genuinely new server only, initialize PostgreSQL, generate the Synapse signing/configuration files, then apply the private-server configuration:

   ```sh
   docker compose --env-file .env up -d db
   docker compose --env-file .env run --rm synapse generate
   docker compose --env-file .env run --rm --no-deps --entrypoint python synapse /opt/private-deployment/configure.py
   ```

   The configuration helper reads the database password from the mounted Compose secret, writes the database connection into `homeserver.yaml`, stores Synapse's registration shared secret in a separate file, and restricts that configuration file to its container owner. Keep the Synapse volume protected because it contains the signing key, media, configuration, and account-provisioning secret.

5. Start the Matrix server after its DNS and firewall routing are ready:

   ```sh
   docker compose --env-file .env up -d
   docker compose --env-file .env ps
   ```

   Caddy obtains and renews the HTTPS certificate automatically. Verify the public client versions endpoint from a network outside the host:

   ```sh
   curl --fail --silent --show-error "https://matrix.friendline.run.place/_matrix/client/versions"
   ```

   This must use the same value as `MATRIX_DOMAIN`; it is independent of the preserved Matrix `server_name`.

6. Calls are outside the messenger deployment gate. To enable the existing optional calls profile later, create a protected LiveKit key file and set `CALLS_DOMAIN` and `LIVEKIT_KEYS_FILE` in `.env`. Verify that the calls domain has a reachable A/AAAA record and open the additional call ports listed under Host requirements. Start the calls profile:

   ```sh
   docker compose --env-file .env --profile calls up -d
   docker compose --env-file .env --profile calls ps
   ```

   Caddy obtains a trusted certificate for `CALLS_DOMAIN` and proxies its secure WebSocket/API traffic to LiveKit. Keep UDP 443 available for LiveKit TURN; Caddy's HTTP/3 listener is intentionally not bound there. Calls use the same random LiveKit room among Matrix room members, but are not usable end to end until both native clients implement the documented E2EE/key flow below. This profile provides ICE/TCP on 7881 and TURN/UDP on 443; restrictive networks that block UDP and nonstandard TCP may still fail because this host does not terminate TURN/TLS on TCP 443.

7. Create accounts only from the host's administrative console. The Synapse container's `register_new_matrix_user` utility uses the private shared secret, even while public registration remains disabled. Use the utility interactively so passwords are not placed in shell history or command arguments:

   ```sh
   docker compose --env-file .env exec synapse register_new_matrix_user http://127.0.0.1:8008 -c /data/homeserver.yaml
   ```

   Make only the operator account an administrator; provision friend accounts as regular users. Do not enable public registration to simplify onboarding.

## What the network exposes

- Caddy and, when the `calls` profile is enabled, LiveKit are the only services with public host ports. Caddy proxies Matrix client requests to Synapse, the private call-authorization endpoints to `call-auth`, and secure LiveKit signaling to the SFU. It returns 404 for Synapse admin API paths. LiveKit's API port 7880 remains internal; only its configured ICE/TURN ports are published.
- Synapse listens on the client HTTP API only. Its federation, replication, metrics, and other generated listeners are removed. Its federation whitelist is empty, and its Docker networks are internal-only. The Matrix domain resolves to Caddy on Synapse's proxy network, so the client-registered HTTPS pusher URL works without giving Synapse general outbound access.
- PostgreSQL has no host-published port and is reachable only by Synapse on the internal database network.
- Caddy request access logging is not enabled. Synapse still handles account, room, device, and delivery metadata needed to operate Matrix; E2EE protects message content only when the client uses encrypted rooms.
- `push.include_content: false` omits event content from Matrix push notification pokes. No APNs/FCM provider is configured here, and routing metadata can still be present in push requests.
- Sygnal is an optional Compose profile. When enabled, it has no published port; Caddy routes only `/_matrix/push/v1/notify` to it over the private network.
- LiveKit and `call-auth` are an opt-in `calls` profile. The authorizer has no persistent database, forwards a Matrix access token only to this configured Synapse over the private Docker network, checks `/account/whoami` and current room membership on every issuance, and returns a five-minute JWT. It never logs request paths/headers/bodies and accepts only the Matrix room ID plus, for joins, the opaque call handle. The signed call handle is randomized per call, bound to one Matrix room, and valid for at most 12 hours. LiveKit sees pseudonymous per-call participant IDs and random room names instead of Matrix IDs. LiveKit shares only the dedicated internal RTC network with Caddy, not the Synapse/call-authorizer network.
- LiveKit JWTs can join only the derived room, subscribe, and publish camera/microphone tracks. They cannot publish data, update participant metadata, create/manage rooms, record, or publish screen-share tracks. If calls are enabled, keys live only in the protected `LIVEKIT_KEYS_FILE`; the authorizer reads the same file to sign tokens. Generate and permission the real key file before validating or starting the calls profile. The default placeholder is deliberately invalid and cannot authorize calls.
- Container JSON logs rotate at 10 MB per file and keep five files to limit disk growth on the host.

The database and Synapse networks are internal, while Caddy and the opt-in LiveKit service have the public connectivity required for their functions. Docker networks do not encrypt traffic between containers on the same host. Treat the host and its Docker administrator as trusted. The server's configuration and media volumes also contain sensitive operational data even though encrypted room bodies are not readable by Synapse.

## Calls backend contract and E2EE boundary

The backend uses the self-hosted LiveKit server, which is licensed under the [Apache License 2.0](https://github.com/livekit/livekit/blob/master/LICENSE). It does not install or package Element Call. LiveKit deployment needs a public calls domain with trusted TLS termination; its documented ports and self-hosting configuration are linked in the [LiveKit deployment guide](https://docs.livekit.io/transport/self-hosting/deployment/) and [firewall reference](https://docs.livekit.io/transport/self-hosting/ports-firewall/).

The clients call the Matrix-domain API with a normal Matrix bearer token:

| Request | Body | Result |
| --- | --- | --- |
| `POST /_friendline/calls/v1/calls` | `{"matrix_room_id":"!…:…"}` | Creates a random call handle and returns the caller's LiveKit URL/JWT. |
| `POST /_friendline/calls/v1/calls/{call_id}/join` | `{"matrix_room_id":"!…:…"}` | Rechecks membership and returns a short-lived JWT for that call. |

The backend API returns an opaque `call_id` bound to one encrypted room. The current clients do not implement invitation transport, so this contract does not imply that users can start or join calls. A future client may share the handle as nonsecret call-control metadata in that room only after its retention, replay, and expiry behavior is specified. The backend rejects unknown body fields, including message text and media keys. It validates the bearer token with Synapse's `whoami`, checks the authenticated user's current membership state, and requires the room's `m.room.encryption` algorithm to be `m.megolm.v1.aes-sha2` before creating or joining a call. The handle is not a substitute for Matrix authentication or membership.

This is an authorization and media-relay backend, not a complete call implementation. Android now exposes the Matrix SDK's Olm-encrypted custom to-device primitives and has an initial one-to-one invitation/key transport adapter. The current iOS Matrix wrapper does not expose those APIs. [LiveKit E2EE must be enabled by each client](https://docs.livekit.io/transport/encryption/), and LiveKit explicitly leaves secure key generation, storage, and distribution to the application. Both platforms must generate and rotate media keys locally and distribute them only through trust-checked, Olm-encrypted to-device delivery. Do not put media keys in timeline room events: those events are retained in room history and are not an approved call-key transport. This API has no media-key field and the issued grants disable data publishing. Until both clients are wired, validated between real Android/iOS devices, and shown to exchange the same secret key securely, calls must not be represented as end-to-end encrypted. LiveKit signaling/control remains TLS-protected rather than E2EE; membership is checked when a token is issued, while already-connected participants are not immediately disconnected if later removed from the Matrix room.

## Push gateway contract and provider setup

The stable HTTPS gateway URL is:

```text
https://<MATRIX_DOMAIN>/_matrix/push/v1/notify
```

`<MATRIX_DOMAIN>` is the same real domain used for Synapse and Caddy. Caddy routes this exact path to Sygnal when its `push` profile is active. The client must register an HTTP pusher with `data.url` set to this URL and `data.format` set to `event_id_only`. `event_id_only` is a client pusher option, not a Sygnal YAML setting; `push.include_content: false` in Synapse is the second server-side protection. Clients should display generic notification text and fetch/decrypt the event after opening the app. Push still exposes event/room/device identifiers and counts needed for delivery.

The native app identifiers and Sygnal pusher identities are:

| Build | Native application ID / bundle ID and APNs topic | Matrix/Sygnal `app_id` | APNs environment |
| --- | --- | --- | --- |
| Android release | `dev.friendline.messenger` | `dev.friendline.messenger.android` | n/a |
| iOS debug | `dev.friendline.messenger.ios` | `dev.friendline.messenger.ios.dev` | Sandbox |
| iOS release | `dev.friendline.messenger.ios` | `dev.friendline.messenger.ios` | Production |

The Android release ID is in `app/build.gradle.kts`; the iOS bundle ID, APNs topic, APS environment, and build-specific pusher app IDs are in `ios/project.yml`. Android's Matrix/Sygnal `app_id` stays `dev.friendline.messenger.android` across variants; the Firebase client app must match the built package ID. Current IDs are `dev.friendline.messenger` (standard release), `dev.friendline.messenger.debug` (standard debug), `dev.friendline.messenger.outboxdiag` (outbox diagnostic release), and `dev.friendline.messenger.outboxdiag.debug` (outbox diagnostic debug).

Before enabling push, replace `REPLACE_WITH_FIREBASE_PROJECT_ID`, `REPLACE_WITH_APPLE_APNS_KEY_ID`, and `REPLACE_WITH_APPLE_TEAM_ID` in `sygnal.yaml`. Put the Firebase Admin SDK service account JSON at `credentials/firebase_service_account.json` and the Apple APNs authentication key at `credentials/apns_auth_key.p8`; keep both out of source control and restrict host permissions. The Sygnal config expects FCM HTTP v1 and an APNs token key, with separate sandbox and production app entries sharing the APNs topic/key. Its app entries disable badge counts to reduce metadata and avoid inaccurate encrypted-room counts. The `credentials/` directory is ignored by Git.

Then start Sygnal using the opt-in profile:

```sh
docker compose --env-file .env --profile push up -d sygnal
docker compose --env-file .env ps
```

The profile is intentionally opt-in: the checked-in project has no Firebase project, service-account key, Apple team, APNs key, or signed native build. The iOS project pairs the debug entitlement and `dev.friendline.messenger.ios.dev` pusher with Sygnal sandbox, and the release entitlement and `dev.friendline.messenger.ios` pusher with Sygnal production. The signed provisioning profile and provider credentials must still match. Do not enable push delivery until native token acquisition and client registration have been validated on signed devices and the actual provider payloads have been inspected.

Each client pusher must use the matching `app_id` above and include fields equivalent to:

```json
{
  "kind": "http",
  "data": {
    "url": "https://<MATRIX_DOMAIN>/_matrix/push/v1/notify",
    "format": "event_id_only"
  }
}
```

The iOS pusher additionally supplies a fixed `data.default_payload.aps.alert` with title “Private Messenger” and body “New message”; Android omits `default_payload` and posts generic notification text locally. Keep the iOS payload static. The client must also supply its provider device token as `pushkey`, plus the required display-name and language fields. Keep that token out of logs and analytics. A full end-to-end test must check the actual APNs/FCM payloads to confirm that no plaintext, room title, sender name, or message preview appears.

## Backups, updates, and recovery

The `backup.sh` operator script makes a point-in-time, age-encrypted archive containing a PostgreSQL custom-format dump, the complete Synapse and Caddy named volumes, deployment configuration/secrets, and the local `call-auth` Docker build inputs needed to reconstruct the stack. Synapse and Caddy are stopped briefly while database and file state are captured; expect a short service interruption. The temporary working directory may contain plaintext data, so run it on a host with encrypted storage and enough free space, and do not direct `TMPDIR` to shared or untrusted storage. The final archive is written only to the configured backup destination and encrypted to the configured age public recipient. Keep the age private identity offline and separately backed up; it is required for recovery.

Set `BACKUP_DIR` to a mounted off-host destination and `BACKUP_AGE_RECIPIENT` to a real age public recipient in `.env`, then run:

```sh
chmod 700 backup.sh
./backup.sh
```

The script never sources `.env` as shell code and does not print resolved environment values. Verify each archive by decrypting it on a protected recovery machine and rehearse a restore into an isolated instance before inviting the group. The repository does not yet automate retention, alerting, restore, or recovery-point monitoring; a successful script run alone is not a tested recovery plan. Caddy's certificate state is included, though Caddy may re-issue certificates after a restore.

Before upgrading, take and verify a backup, review Synapse/PostgreSQL/Caddy release notes, update the pinned image tags or digests, then run `docker compose --env-file .env pull` and `docker compose --env-file .env up -d`. Keep the previous known-good versions and a tested restore path available. Do not point a new Synapse version at the only copy of production data as an upgrade test.

To inspect service health without dumping logs or secrets:

```sh
docker compose --env-file .env ps
docker compose --env-file .env exec synapse python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8008/_matrix/client/versions', timeout=5).read()"
```

## Not yet provisioned

The repository contains a deployment definition and operator procedure, not a running server. The following operator-owned inputs and actions remain before private use:

- Provision a Linux host with Docker Compose V2. Point the public hostname at that host, configure inbound TCP 80/443 (and optional UDP 443), and arrange encrypted storage, firewall policy, and tested backups. The existing Matrix identity remains `localhost`; the public hostname does not become part of user IDs.
- On that host, create `.env` from `.env.example`, retain `MATRIX_SERVER_NAME=localhost`, set `MATRIX_DOMAIN=matrix.friendline.run.place`, and create `secrets/postgres_password` with a random value of at least 32 characters and restrictive file permissions. Do not run first-time Synapse generation when restoring Friendline's existing data.
- For Android push, create a Firebase project, enable FCM HTTP v1, register each application ID that will be built (the four current IDs are listed above), add matching client `google-services.json` files on the Android build machine, and place the Firebase service-account JSON on the host at `credentials/firebase_service_account.json`. Set the Firebase project ID in `sygnal.yaml`.
- For iOS push, use an Apple Developer team to enable Push Notifications for bundle ID `dev.friendline.messenger.ios`, create an APNs authentication key, and install matching signed provisioning profiles on macOS/Xcode. Place the `.p8` key on the host at `credentials/apns_auth_key.p8`; set its Key ID and Team ID in `sygnal.yaml`. The debug build uses APNs sandbox and the release build uses production.
- Enable the Sygnal `push` profile only after both provider configurations and client builds are ready. Validate registration and actual APNs/FCM payloads with signed physical devices before giving builds to friends. Push acceptance cannot be completed from this Windows host without the Apple signing setup and physical iOS device.

Provider credentials and host secrets belong only in their protected local files; do not commit or share them. The repository does not provision the Azure host, DNS, firewall, storage, or provider credentials.
