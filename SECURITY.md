# Security

This project treats end-to-end encryption and privacy as release requirements. The detailed security policy, implementation status, and release gates are maintained in [docs/SECURITY.md](docs/SECURITY.md); the threat model is in [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

The application is still under development and is not ready for everyday use or distribution. An isolated Android API 37 test now proves one encrypted offline send survives force-stop/restart and reconnects as one server-backed message. iOS has not been built with Xcode, Android-to-iOS interoperability and recipient-offline delivery remain unverified, and several security gates in [docs/SECURITY.md](docs/SECURITY.md) are still open. Do not rely on a successful build as proof of complete security or reliability.

Report security concerns privately to the project owner. Do not include message contents, passwords, access tokens, private keys, recovery keys, attachment keys, or raw verification payloads in issue reports or logs.
