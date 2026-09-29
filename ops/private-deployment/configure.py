"""Apply private hosted-server settings to a generated Synapse config."""

from __future__ import annotations

import ipaddress
import os
import re
import secrets
import tempfile
from pathlib import Path


CONFIG_PATH = Path("/data/homeserver.yaml")
REGISTRATION_SECRET_PATH = Path("/data/registration_shared_secret")
DOMAIN_PATTERN = re.compile(
    r"(?=.{1,253}\Z)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)"
    r"(?:\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))+\Z"
)
RESERVED_DOMAIN_SUFFIXES = (".example", ".invalid", ".localhost", ".local", ".test")
RESERVED_EXAMPLE_DOMAINS = (
    "example.com",
    "example.net",
    "example.org",
)


def is_deployable_domain(domain: str) -> bool:
    """Reject malformed and documentation-only names before fixing a Matrix ID domain."""
    normalized = domain.lower()
    if not DOMAIN_PATTERN.fullmatch(domain):
        return False
    try:
        ipaddress.ip_address(normalized)
    except ValueError:
        pass
    else:
        return False
    if normalized.endswith(RESERVED_DOMAIN_SUFFIXES):
        return False
    return not any(
        normalized == reserved or normalized.endswith(f".{reserved}")
        for reserved in RESERVED_EXAMPLE_DOMAINS
    )


def read_database_password() -> str:
    secret_path = Path(os.environ["POSTGRES_PASSWORD_FILE"])
    password = secret_path.read_text(encoding="utf-8").strip()
    if len(password) < 32:
        raise SystemExit("The PostgreSQL password file must contain at least 32 characters.")
    return password


def persist_registration_secret(existing_secret: str | None) -> None:
    if REGISTRATION_SECRET_PATH.exists():
        return
    value = existing_secret or secrets.token_urlsafe(48)
    descriptor = os.open(
        REGISTRATION_SECRET_PATH,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL,
        0o600,
    )
    with os.fdopen(descriptor, "w", encoding="utf-8") as secret_file:
        secret_file.write(value)


def configure() -> None:
    if not CONFIG_PATH.is_file():
        raise SystemExit("Generate /data/homeserver.yaml with the Synapse image first.")

    domain = os.environ.get("MATRIX_DOMAIN", "")
    if not is_deployable_domain(domain):
        raise SystemExit(
            "MATRIX_DOMAIN must be a stable public DNS name you control; "
            "documentation and local-only names are not deployable."
        )

    import yaml

    config = yaml.safe_load(CONFIG_PATH.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise SystemExit("Generated Synapse configuration is not a YAML mapping.")

    registration_secret = config.pop("registration_shared_secret", None)
    persist_registration_secret(registration_secret)
    config["registration_shared_secret_path"] = str(REGISTRATION_SECRET_PATH)
    config["server_name"] = domain
    config["public_baseurl"] = f"https://{domain}/"
    config["database"] = {
        "name": "psycopg2",
        "args": {
            "host": "db",
            "port": 5432,
            "user": "synapse",
            "password": read_database_password(),
            "database": "synapse",
            "cp_min": 2,
            "cp_max": 10,
        },
    }

    config["enable_registration"] = False
    config["enable_registration_without_verification"] = False
    config["allow_guest_access"] = False
    config["enable_3pid_lookup"] = False
    config["url_preview_enabled"] = False
    config["report_stats"] = False
    config["federation_domain_whitelist"] = []
    config["trusted_key_servers"] = []
    config["suppress_key_server_warning"] = True
    config["max_upload_size"] = "34M"

    push = config.get("push") or {}
    push["include_content"] = False
    config["push"] = push

    # Keep one reverse-proxy-facing HTTP listener and remove federation,
    # replication, metrics, and other generated listeners entirely.
    client_listener = None
    for listener in config.get("listeners", []):
        if (
            listener.get("port") == 8008
            and listener.get("type") == "http"
            and not listener.get("tls", False)
        ):
            client_listener = listener
            break
    if client_listener is None:
        raise SystemExit("Generated config has no plain HTTP client listener on port 8008.")

    resources = []
    has_client_resource = False
    for resource in client_listener.get("resources", []):
        names = resource.get("names", [])
        kept_names = [
            name for name in names if name not in {"federation", "replication", "metrics"}
        ]
        if kept_names:
            resource["names"] = kept_names
            resources.append(resource)
            has_client_resource = has_client_resource or "client" in kept_names
    if not has_client_resource:
        raise SystemExit("Generated config has no client API resource on port 8008.")

    client_listener["bind_addresses"] = ["0.0.0.0"]
    client_listener["x_forwarded"] = True
    client_listener["resources"] = resources
    config["listeners"] = [client_listener]

    fd, temporary_path = tempfile.mkstemp(
        prefix="homeserver.yaml.", dir=str(CONFIG_PATH.parent)
    )
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as config_file:
            yaml.safe_dump(config, config_file, sort_keys=False, allow_unicode=True)
        os.replace(temporary_path, CONFIG_PATH)
    finally:
        if os.path.exists(temporary_path):
            os.unlink(temporary_path)

    print("Configured Synapse for private HTTPS access, local accounts, and PostgreSQL.")


if __name__ == "__main__":
    configure()
