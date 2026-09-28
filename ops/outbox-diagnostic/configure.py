"""Configure a generated Synapse homeserver for the private outbox test lane."""

from pathlib import Path

import yaml


config_path = Path("/data/homeserver.yaml")
if not config_path.is_file():
    raise SystemExit("Generated Synapse config is missing")

config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
config["database"] = {
    "name": "psycopg2",
    "args": {
        "host": "db",
        "port": 5432,
        "user": "synapse",
        "database": "synapse",
        "cp_min": 2,
        "cp_max": 5,
    },
}
# Matrix SDK clients honor the homeserver URL advertised after login. The app-side URL is
# reached through an adb reverse mapping on 18009, which forwards to host port 8009.
config["public_baseurl"] = "http://127.0.0.1:18009/"
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
print("Configured the private loopback Synapse lane.")
