"""Apply local-only Matrix settings to a Synapse-generated homeserver.yaml."""

from pathlib import Path

import yaml


config_path = Path("/data/homeserver.yaml")
if not config_path.is_file():
    raise SystemExit(
        "Synapse config is missing. Run `docker compose -f ops/synapse/compose.yaml run --rm synapse generate` first."
    )

config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
config["database"] = {
    "name": "psycopg2",
    "args": {
        "host": "db",
        "port": 5432,
        "user": "synapse",
        "database": "synapse",
        "cp_min": 5,
        "cp_max": 10,
    },
}
config["public_baseurl"] = "http://localhost:8008/"
config["enable_registration"] = False
config["enable_registration_without_verification"] = False
config.pop("registration_shared_secret", None)
config["registration_shared_secret_path"] = "/data/registration_shared_secret"
config["allow_guest_access"] = False
config["enable_3pid_lookup"] = False
config["url_preview_enabled"] = False
config["report_stats"] = False
config["federation_domain_whitelist"] = []
config["trusted_key_servers"] = []
config["suppress_key_server_warning"] = True
push = config.get("push") or {}
push["include_content"] = False
config["push"] = push

# Keep only the client API resource and bind inside the container. Federation is
# denied above and the federation listener is not published by Compose.
for listener in config.get("listeners", []):
    if listener.get("port") == 8008:
        listener["bind_addresses"] = ["0.0.0.0"]
        resources = []
        for resource in listener.get("resources", []):
            names = [name for name in resource.get("names", []) if name == "client"]
            if names:
                resource["names"] = names
                resources.append(resource)
        listener["resources"] = resources

config_path.write_text(
    yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8"
)
print("Configured private local Synapse with PostgreSQL and registration disabled.")
