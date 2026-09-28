# Private Matrix homeserver deployment

This is a separate hosted deployment bundle for the private friend group. It does not share the local development stack's bind mounts, Compose project, or data. It prepares Synapse with PostgreSQL behind Caddy-managed HTTPS. An optional Sygnal profile and app configuration are included for Android/iOS push delivery; it remains inactive until real provider credentials and native push registration are ready.

The stack keeps public registration and guest access disabled, blocks federation at both the Synapse listener and configuration layers, disables URL previews and anonymous statistics, and sets `push.include_content: false`. PostgreSQL, Synapse, and Caddy state use named Docker volumes. The database has no published port. Synapse is reachable only from Caddy on an isolated Docker network, and its outbound network is isolated as well. On that private proxy network, Docker resolves the configured Matrix domain to Caddy so Synapse can reach the same HTTPS push URL without public-network egress. Caddy publishes TCP 80/443 and UDP 443 for certificate validation, HTTPS, and HTTP/3.

The server domain is an identity decision: Matrix user IDs include it. Choose the stable DNS name you own before first setup. Do not use the `.invalid` placeholder from `.env.example` for a real deployment.

## Host requirements

- A Linux host running Docker Engine and Docker Compose V2. Keep the host patched and restrict administrative access.
- A stable public DNS name that you control, with A and, if used, AAAA records pointing to this host. Remove an AAAA record if IPv6 is not reachable from the public internet.
- Inbound TCP ports 80 and 443, and optionally UDP 443, allowed through both the host firewall and hosting provider firewall. Caddy needs outbound access for DNS and ACME certificate issuance/renewal.
- Durable storage with encryption at rest and enough room for the PostgreSQL database, Synapse media store, signing/configuration keys, and backups.

The bundle does not create a host, domain, DNS records, firewall rules, or backups. The Sygnal route/configuration contract is ready, but push delivery needs Firebase and Apple Developer credentials plus native client registration. Calls need a separate service and are not configured here.

## First-time setup

Run these commands on the Linux host from this directory (`ops/private-deployment`). Never copy a real password into the repository.

1. Create a password file and a private Compose environment file. The password file must stay on the host and outside source control:

   ```sh
   install -d -m 700 secrets
   openssl rand -hex 32 > secrets/postgres_password
   chmod 600 secrets/postgres_password
   cp .env.example .env
   chmod 600 .env
   ```

2. Edit `.env`: set `MATRIX_DOMAIN` to the stable DNS name you own and confirm `POSTGRES_PASSWORD_FILE=./secrets/postgres_password`. The image tags are pinned to release versions. Before production, review security advisories and pin each image to a verified digest in the same change-control process.

3. Confirm that Compose resolves the required variables and secret file, without starting containers:

   ```sh
   docker compose --env-file .env config --quiet
   ```

4. Initialize PostgreSQL, generate the Synapse signing/configuration files, then apply the private-server configuration. Run this only for a new Synapse data volume:

   ```sh
   docker compose --env-file .env up -d db
   docker compose --env-file .env run --rm synapse generate
   docker compose --env-file .env run --rm --no-deps --entrypoint python synapse /opt/private-deployment/configure.py
   ```

   The configuration helper reads the database password from the mounted Compose secret, writes the database connection into `homeserver.yaml`, stores Synapse's registration shared secret in a separate file, and restricts that configuration file to its container owner. Keep the Synapse volume protected because it contains the signing key, media, configuration, and account-provisioning secret.

5. Start the service after DNS and firewall routing are ready:

   ```sh
   docker compose --env-file .env up -d
   docker compose --env-file .env ps
   ```

   Caddy obtains and renews the HTTPS certificate automatically. Verify the public client versions endpoint from a network outside the host:

   ```sh
   curl --fail --silent --show-error "https://YOUR_OWNED_DOMAIN/_matrix/client/versions"
   ```

   Replace `YOUR_OWNED_DOMAIN` with the same value as `MATRIX_DOMAIN`.

6. Create accounts only from the host's administrative console. The Synapse container's `register_new_matrix_user` utility uses the private shared secret, even while public registration remains disabled. Use the utility interactively so passwords are not placed in shell history or command arguments:

   ```sh
   docker compose --env-file .env exec synapse register_new_matrix_user http://127.0.0.1:8008 -c /data/homeserver.yaml
   ```

   Make only the operator account an administrator; provision friend accounts as regular users. Do not enable public registration to simplify onboarding.

## What the network exposes

- Caddy is the only service with public host ports. It proxies Matrix client requests to Synapse and returns 404 for Synapse admin API paths.
- Synapse listens on the client HTTP API only. Its federation, replication, metrics, and other generated listeners are removed. Its federation whitelist is empty, and its Docker networks are internal-only. The Matrix domain resolves to Caddy on Synapse's proxy network, so the client-registered HTTPS pusher URL works without giving Synapse general outbound access.
- PostgreSQL has no host-published port and is reachable only by Synapse on the internal database network.
- Caddy request access logging is not enabled. Synapse still handles account, room, device, and delivery metadata needed to operate Matrix; E2EE protects message content only when the client uses encrypted rooms.
- `push.include_content: false` omits event content from Matrix push notification pokes. No APNs/FCM provider is configured here, and routing metadata can still be present in push requests.
- Sygnal is an optional Compose profile. When enabled, it has no published port; Caddy routes only `/_matrix/push/v1/notify` to it over the private network.

The Docker networks isolate services from the public internet, but they do not encrypt traffic between containers on the same host. Treat the host and its Docker administrator as trusted. The server's configuration and media volumes also contain sensitive operational data even though encrypted room bodies are not readable by Synapse.

## Push gateway contract and provider setup

The stable HTTPS gateway URL is:

```text
https://<MATRIX_DOMAIN>/_matrix/push/v1/notify
```

`<MATRIX_DOMAIN>` is the same real domain used for Synapse and Caddy. Caddy routes this exact path to Sygnal when its `push` profile is active. The client must register an HTTP pusher with `data.url` set to this URL and `data.format` set to `event_id_only`. `event_id_only` is a client pusher option, not a Sygnal YAML setting; `push.include_content: false` in Synapse is the second server-side protection. Clients should display generic notification text and fetch/decrypt the event after opening the app. Push still exposes event/room/device identifiers and counts needed for delivery.

The app identifiers currently in the source are:

| Platform | Native release identifier | Matrix/Sygnal `app_id` | Sygnal app config key |
| --- | --- | --- | --- |
| Android | `dev.friendline.messenger` (application ID) | `dev.friendline.messenger.android` | `dev.friendline.messenger.android` |
| iOS | `dev.friendline.messenger.ios` (bundle ID and APNs topic) | `dev.friendline.messenger.ios` | `dev.friendline.messenger.ios` |

The Android release ID is in `app/build.gradle.kts`; the iOS bundle ID and APNs topic are in `ios/project.yml`. Debug/test package suffixes are not production push identifiers.

Before enabling push, replace `REPLACE_WITH_FIREBASE_PROJECT_ID`, `REPLACE_WITH_APPLE_APNS_KEY_ID`, and `REPLACE_WITH_APPLE_TEAM_ID` in `sygnal.yaml`. Put the Firebase Admin SDK service account JSON at `credentials/firebase_service_account.json` and the Apple APNs authentication key at `credentials/apns_auth_key.p8`; keep both out of source control and restrict host permissions. The Sygnal config expects FCM HTTP v1 and an APNs token key in production mode. Its app entries disable badge counts to reduce metadata and avoid inaccurate encrypted-room counts. The `credentials/` directory is ignored by Git.

Then start Sygnal using the opt-in profile:

```sh
docker compose --env-file .env --profile push up -d sygnal
docker compose --env-file .env ps
```

The profile is intentionally opt-in: the checked-in project has no Firebase project, service-account key, Apple team, APNs key, or signed native release configuration. The iOS sandbox APNs environment is also not enabled; production APNs credentials and device tokens must match the `platform` setting. Do not register pushers until native token acquisition and client registration code have been implemented and verified.

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

The client must also supply its provider device token as `pushkey`, plus the required display-name and language fields. Keep that token out of logs and analytics. A full end-to-end test must check the actual APNs/FCM payloads to confirm that no plaintext, room title, sender name, or message preview appears.

## Backups, updates, and recovery

Back up the PostgreSQL database and the complete Synapse volume together, including `homeserver.yaml`, the signing key, media store, and registration secret. Protect backups with encryption and access controls, store them off-host, and rehearse restores into an isolated instance before inviting the group. Caddy's certificate state is in its named data volume; retain it with the host backup or expect certificate re-issuance after restore. This bundle does not schedule or verify backups.

Before upgrading, take and verify a backup, review Synapse/PostgreSQL/Caddy release notes, update the pinned image tags or digests, then run `docker compose --env-file .env pull` and `docker compose --env-file .env up -d`. Keep the previous known-good versions and a tested restore path available. Do not point a new Synapse version at the only copy of production data as an upgrade test.

To inspect service health without dumping logs or secrets:

```sh
docker compose --env-file .env ps
docker compose --env-file .env exec synapse python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8008/_matrix/client/versions', timeout=5).read()"
```

## Not yet provisioned

The repository now contains a deployment definition and operator procedure, not a running server. Provisioning still requires a real user-owned domain and DNS, a Linux host with public ports 80/443 routed to it, a strong password file, and the host's security/backup setup. Push additionally requires Firebase and Apple Developer credentials and native push implementation. These values are intentionally not present in this repository. No hosting account, payment, credentials, or real domain has been used.
