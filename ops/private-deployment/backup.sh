#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="${PRIVATE_MESSENGER_ENV_FILE:-$script_dir/.env}"
compose=(docker compose --project-directory "$script_dir" --env-file "$env_file" -f "$script_dir/compose.yaml")

if [[ ! -f "$env_file" ]]; then
    echo "Private deployment .env file is missing." >&2
    exit 2
fi

# Compose parses quoting and interpolation for its env file. Capture the resolved
# environment privately, then read only the two backup settings from it.
compose_environment="$("${compose[@]}" config --environment)"
compose_setting() {
    local setting="$1"
    printf '%s\n' "$compose_environment" | awk -F= -v key="$setting" '$1 == key { sub(/^[^=]*=/, ""); print; exit }'
}
backup_dir="${BACKUP_DIR:-$(compose_setting BACKUP_DIR)}"
age_recipient="${BACKUP_AGE_RECIPIENT:-$(compose_setting BACKUP_AGE_RECIPIENT)}"
unset compose_environment
if [[ -z "$backup_dir" ]]; then
    echo "Set BACKUP_DIR to a mounted off-host backup destination." >&2
    exit 2
fi
if [[ -z "$age_recipient" ]]; then
    echo "Set BACKUP_AGE_RECIPIENT to an age public recipient whose private identity is kept off this host." >&2
    exit 2
fi
for command in docker age tar; do
    command -v "$command" >/dev/null 2>&1 || {
        echo "Required command is unavailable: $command" >&2
        exit 2
    }
done

mkdir -p -- "$backup_dir"
chmod 700 -- "$backup_dir"
backup_dir="$(cd -- "$backup_dir" && pwd)"

synapse_id="$("${compose[@]}" ps -q synapse)"
caddy_id="$("${compose[@]}" ps -q caddy)"
db_id="$("${compose[@]}" ps -q db)"
if [[ -z "$synapse_id" || -z "$caddy_id" || -z "$db_id" ]]; then
    echo "The db, synapse, and caddy services must be running before backup." >&2
    exit 2
fi

was_synapse_running="$(docker inspect --format '{{.State.Running}}' "$synapse_id")"
was_caddy_running="$(docker inspect --format '{{.State.Running}}' "$caddy_id")"
was_db_running="$(docker inspect --format '{{.State.Running}}' "$db_id")"
if [[ "$was_synapse_running" != true || "$was_caddy_running" != true || "$was_db_running" != true ]]; then
    echo "The db, synapse, and caddy services must all be running before backup." >&2
    exit 2
fi
restart_synapse=0
restart_caddy=0
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/friendline-backup.XXXXXXXX")"
chmod 700 -- "$work_dir"
cleanup() {
    local status=$?
    if [[ "$restart_synapse" == 1 && "$was_synapse_running" == true ]]; then
        if ! "${compose[@]}" start synapse >/dev/null; then
            echo "Backup cleanup could not restart Synapse; start it manually." >&2
            status=1
        fi
    fi
    if [[ "$restart_caddy" == 1 && "$was_caddy_running" == true ]]; then
        if ! "${compose[@]}" start caddy >/dev/null; then
            echo "Backup cleanup could not restart Caddy; start it manually." >&2
            status=1
        fi
    fi
    rm -rf -- "$work_dir"
    exit "$status"
}
trap cleanup EXIT

volume_name() {
    local container_id="$1"
    local destination="$2"
    docker inspect --format "{{range .Mounts}}{{if eq .Destination \"$destination\"}}{{.Name}}{{end}}{{end}}" "$container_id"
}

archive_volume() {
    local volume="$1"
    local archive_name="$2"
    if [[ -z "$volume" ]]; then
        echo "Could not identify the persistent volume for $archive_name." >&2
        return 1
    fi
    docker run --rm --network none \
        --mount "type=volume,src=$volume,dst=/source,readonly" \
        --mount "type=bind,src=$work_dir,dst=/backup" \
        busybox:1.37.0 sh -c "tar -C /source -czf /backup/$archive_name ."
}

synapse_volume="$(volume_name "$synapse_id" /data)"
caddy_data_volume="$(volume_name "$caddy_id" /data)"
caddy_config_volume="$(volume_name "$caddy_id" /config)"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
printf 'Friendline private backend backup\nCreated UTC: %s\n' "$timestamp" >"$work_dir/manifest.txt"

# Stop event writes while the database and encrypted media/config volumes are captured.
restart_synapse=1
restart_caddy=1
"${compose[@]}" stop synapse >/dev/null
"${compose[@]}" stop caddy >/dev/null
"${compose[@]}" exec -T db sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$work_dir/postgres.dump"
archive_volume "$synapse_volume" synapse-volume.tar.gz
archive_volume "$caddy_data_volume" caddy-data-volume.tar.gz
archive_volume "$caddy_config_volume" caddy-config-volume.tar.gz

# The deployment config and provider/signing secrets are sensitive; include them only
# inside the age-encrypted archive so a restore can reproduce the deployed services.
# Keep every build context used by Compose inside the encrypted recovery archive.
# In particular, call-auth is built locally rather than pulled from a registry.
host_paths=(compose.yaml Caddyfile configure.py sygnal.yaml call-auth/Dockerfile call-auth/call_auth.py)
for optional_path in .env secrets credentials; do
    if [[ -e "$script_dir/$optional_path" ]]; then
        host_paths+=("$optional_path")
    fi
done
tar -czf "$work_dir/host-config.tar.gz" -C "$script_dir" "${host_paths[@]}"

archive_name="friendline-backup-$timestamp.tar.gz.age"
output_file="$backup_dir/$archive_name"
tar -C "$work_dir" -czf - \
    manifest.txt postgres.dump synapse-volume.tar.gz \
    caddy-data-volume.tar.gz caddy-config-volume.tar.gz host-config.tar.gz \
    | age --recipient "$age_recipient" --output "$output_file"
chmod 600 -- "$output_file"
test -s "$output_file"

"${compose[@]}" start synapse >/dev/null
restart_synapse=0
"${compose[@]}" start caddy >/dev/null
restart_caddy=0
printf 'Encrypted backup written: %s\n' "$output_file"
printf 'Verify decryption and rehearse restore on an isolated host before relying on this backup.\n'
