# Deployment

## Local development

Use `ops/synapse/compose.yaml`. It creates a private Synapse/PostgreSQL network, publishes only the client API port, stores server data beneath ignored `data/`, and disables federation, public registration, guest access, identity-server lookups, URL previews, and statistics.

Local HTTP on `localhost` is for development only. If testing on physical devices, bind only to a trusted private LAN and use the firewall to block public access.

## Private hosted server

A separate Synapse + PostgreSQL + Caddy Compose bundle and first-run operator guide are in [`ops/private-deployment/README.md`](../ops/private-deployment/README.md). It is ready for a real owned domain and private host; it has not been provisioned.

Before exposing the server to the internet:

1. Choose a stable server name and domain. Matrix server names become part of account IDs and cannot be changed without moving users to a new identity.
2. Use an HTTPS reverse proxy with current TLS. Publish the Matrix client API only; keep PostgreSQL, Synapse admin endpoints, SSH, and metrics private.
   Synapse's HTTP listener must trust forwarded headers (`x_forwarded: true`) behind the proxy, and the proxy must send the original client address and HTTPS scheme. Proxy `/_matrix` and `/_synapse/client`; do not expose a federation listener while federation is disabled. The [Synapse reverse-proxy guide](https://github.com/element-hq/synapse/blob/develop/docs/reverse_proxy.md) documents these requirements. Caddy can handle certificate issuance and renewal once the chosen DNS name points to the host and public ports 80/443 reach it; see [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https).
3. Replace local PostgreSQL trust authentication with a dedicated database user and strong password from a secret file/manager. Do not publish port 5432.
4. Keep registration disabled and federation whitelisted to no domains unless cross-homeserver use is an explicit future decision. Provision accounts administratively and remove temporary registration secrets after use if the chosen process permits.
5. Pin Synapse and PostgreSQL image versions (and preferably digests), monitor official security releases, and stage upgrades with database/media backups.
6. Persist Synapse configuration/signing keys, PostgreSQL data, and media data on durable encrypted storage with restrictive host permissions.
7. Back up database, media, and signing/configuration keys together. Encrypt off-host backups and conduct a restore drill before inviting the group.
8. Disable URL previews and configure Synapse with `push.include_content: false`. This removes event content from push notification pokes; the [Synapse push settings](https://element-hq.github.io/synapse/latest/usage/configuration/config_documentation.html#push) still include sender and routing metadata. Add APNs/FCM only after inspecting actual payloads end to end.
9. Record firewall rules, DNS/TLS renewal, server update, backup, restore, and account revocation steps for the operator.

Voice/video calls also require the private MatrixRTC and LiveKit deployment described in [CALLS.md](CALLS.md); the local homeserver compose stack is not a call backend.

This project has no configured host, domain, DNS, TLS certificate, or server credentials. The hosted deployment is therefore not yet provisioned. No secret is needed for local client development.

## Private client distribution

- Android: the private release signing configuration reads the keystore path, store password, alias, and key password from `PRIVATE_MESSENGER_RELEASE_*` environment variables. A local 3072-bit RSA/PKCS12 release identity has been generated under the ignored `ops/private-deployment/credentials/android-release/` directory; its password file is local-only and the directory ACL is restricted to this Windows account. Back up both files securely and retain this identity for every update before distributing the app. Set those four environment variables from the local password file and build with `:app:assembleStandardRelease :app:bundleStandardRelease -PprivateMessengerSplitApks=true`. This creates one signed APK per ABI and a signed AAB. The API 37 x86_64 emulator installed and launched the signed x86_64 APK. Its signing certificate SHA-256 is `913f042deb019a9065f4008afda6d1ca872f33616dfc993ca6ab5e07f3a79af2`.
- Android App Bundle: `:app:bundleStandardRelease` creates an AAB containing all native architectures. It must be signed with the stable upload key before a private Play track or app-bundle distribution service can use it.
- iOS: build and sign on a Mac. Xcode personal-team signing can support limited development installs; broader or longer-lived distribution may require Apple Developer Program membership, certificates, and provisioning. Decide distribution after device builds work.

## Rollback and recovery

Back up before any Synapse/PostgreSQL upgrade. Restore the whole service state into an isolated test instance before production use. A homeserver backup cannot decrypt Matrix E2EE content, but restoring server state may reveal the account, room, and activity metadata described in [THREAT_MODEL.md](THREAT_MODEL.md).
