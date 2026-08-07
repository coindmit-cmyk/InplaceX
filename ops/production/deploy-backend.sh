#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2034
set -euo pipefail
umask 077

if [[ $# -ne 2 ]]; then
    echo "Usage: sudo $0 <absolute-env-file> <absolute-backup-directory>" >&2
    exit 64
fi
[[ ${EUID:-$(id -u)} -eq 0 ]] || {
    echo "Production deploy must run as root." >&2
    exit 77
}

readonly env_file="$1"
readonly backup_directory="$2"
script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly script_directory
readonly compose_file="$script_directory/compose.yaml"
readonly smoke_script="$script_directory/smoke-backend.sh"
# shellcheck source=ops/production/release-common.sh
source "$script_directory/release-common.sh"

release_require_commands \
    awk cmp curl date docker flock grep install mktemp nginx openssl python3 sed \
    sha256sum sleep sort ss stat sync
release_acquire_lock
release_prepare_runtime_directory

declare -Ar environment_allowlist=(
    [COMPOSE_PROJECT_NAME]=1
    [INPLACEX_BACKEND_IMAGE]=1
    [INPLACEX_POSTGRES_IMAGE]=1
    [INPLACEX_BACKEND_LOOPBACK_PORT]=1
    [INPLACEX_POSTGRES_DB]=1
    [INPLACEX_POSTGRES_USER]=1
    [INPLACEX_POSTGRES_VOLUME]=1
    [INPLACEX_SECRET_DIRECTORY]=1
    [INPLACEX_RUNTIME_SECRET_GID]=1
    [INPLACEX_GEOIP_DB_PATH]=1
    [INPLACEX_RELEASE_STATE_DIRECTORY]=1
    [INPLACEX_PUBLIC_HOSTNAME]=1
    [INPLACEX_OPERATOR_NETWORK_CIDR]=1
    [INPLACEX_DRAIN_TIMEOUT_SECONDS]=1
    [INPLACEX_ONLINE_TOKEN_ISSUER]=1
    [INPLACEX_ONLINE_TOKEN_AUDIENCE]=1
    [INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS]=1
    [INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS]=1
    [INPLACEX_AD_MARKET_COUNTRY_HEADER]=1
    [INPLACEX_AD_MARKET_CONTAINER_DB_PATH]=1
    [INPLACEX_RELEASE_ID]=1
    [INPLACEX_GIT_SHA]=1
    [INPLACEX_IMAGE_DIGEST]=1
    [INPLACEX_SOURCE_ARCHIVE_SHA256]=1
    [INPLACEX_RELEASE_MANIFEST_PATH]=1
    [INPLACEX_INITIAL_DEPLOY]=1
    [INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS]=1
    [INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK]=1
    [INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256]=1
    [INPLACEX_RUNTIME_CONFIG_SHA256]=1
)

environment_fd=""
release_open_root_config "$env_file" environment_fd
release_parse_allowed_kv_fd "$environment_fd" environment_allowlist
[[ -z "${INPLACEX_RUNTIME_CONFIG_SHA256+x}" ]] ||
    release_die 65 "INPLACEX_RUNTIME_CONFIG_SHA256 is calculated by deploy and must not be supplied"
release_validate_env_values
[[ "$backup_directory" =~ ^/[A-Za-z0-9_./-]+$ ]] ||
    release_die 65 "Backup directory path contains unsupported characters"
release_validate_backup_directory "$backup_directory"
release_prepare_state_directories
release_validate_secret_tree
release_validate_secret_payloads
release_validate_geoip_file
release_validate_geoip_payload
release_validate_release_manifest
release_validate_nginx_gate_installation
release_validate_volume

state_key_sha256="$(release_sha256_file "$INPLACEX_SECRET_DIRECTORY/online-state-key-base64.txt")"
public_key_sha256="$(release_sha256_file "$INPLACEX_SECRET_DIRECTORY/platform-public-key-x509-base64.txt")"
geoip_sha256="$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")"
runtime_config_sha256="$(release_runtime_config_fingerprint)"
readonly state_key_sha256 public_key_sha256 geoip_sha256 runtime_config_sha256
export INPLACEX_RUNTIME_CONFIG_SHA256="$runtime_config_sha256"
release_assert_secret_continuity "$state_key_sha256" "$public_key_sha256"
readonly requested_candidate_image="$INPLACEX_BACKEND_IMAGE"
readonly requested_candidate_release_id="$INPLACEX_RELEASE_ID"
readonly requested_candidate_git_sha="$INPLACEX_GIT_SHA"
readonly requested_candidate_image_digest="$INPLACEX_IMAGE_DIGEST"
readonly requested_candidate_source_archive_sha256="$INPLACEX_SOURCE_ARCHIVE_SHA256"

declare -Ar journal_allowlist=(
    [RELEASE_TRANSACTION_VERSION]=1
    [RELEASE_TRANSACTION_OPERATION]=1
    [RELEASE_TRANSACTION_PHASE]=1
    [RELEASE_TRANSACTION_DEPLOYMENT_ID]=1
    [RELEASE_TRANSACTION_COMPOSE_PROJECT_NAME]=1
    [RELEASE_TRANSACTION_POSTGRES_IMAGE]=1
    [RELEASE_TRANSACTION_POSTGRES_DB]=1
    [RELEASE_TRANSACTION_POSTGRES_USER]=1
    [RELEASE_TRANSACTION_POSTGRES_VOLUME]=1
    [RELEASE_TRANSACTION_DATABASE_SYSTEM_IDENTIFIER]=1
    [RELEASE_TRANSACTION_BACKEND_LOOPBACK_PORT]=1
    [RELEASE_TRANSACTION_BACKUP_PATH]=1
    [RELEASE_TRANSACTION_BACKUP_SHA256]=1
    [RELEASE_TRANSACTION_RECEIPT_PATH]=1
    [RELEASE_TRANSACTION_STATE_KEY_SHA256]=1
    [RELEASE_TRANSACTION_PUBLIC_KEY_SHA256]=1
    [RELEASE_TRANSACTION_GEOIP_SHA256]=1
    [RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256]=1
    [RELEASE_TRANSACTION_CANDIDATE_IMAGE]=1
    [RELEASE_TRANSACTION_CANDIDATE_RELEASE_ID]=1
    [RELEASE_TRANSACTION_CANDIDATE_GIT_SHA]=1
    [RELEASE_TRANSACTION_CANDIDATE_IMAGE_DIGEST]=1
    [RELEASE_TRANSACTION_CANDIDATE_SOURCE_ARCHIVE_SHA256]=1
    [RELEASE_TRANSACTION_PREVIOUS_EXISTS]=1
    [RELEASE_TRANSACTION_PREVIOUS_IMAGE]=1
    [RELEASE_TRANSACTION_PREVIOUS_RELEASE_ID]=1
    [RELEASE_TRANSACTION_PREVIOUS_GIT_SHA]=1
    [RELEASE_TRANSACTION_PREVIOUS_IMAGE_DIGEST]=1
    [RELEASE_TRANSACTION_PREVIOUS_SOURCE_ARCHIVE_SHA256]=1
)

write_journal() {
    local phase="$1"
    release_atomic_kv_file "$RELEASE_TRANSACTION_JOURNAL" \
        "RELEASE_TRANSACTION_VERSION=1" \
        "RELEASE_TRANSACTION_OPERATION=deploy" \
        "RELEASE_TRANSACTION_PHASE=$phase" \
        "RELEASE_TRANSACTION_DEPLOYMENT_ID=$deployment_id" \
        "RELEASE_TRANSACTION_COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME" \
        "RELEASE_TRANSACTION_POSTGRES_IMAGE=$INPLACEX_POSTGRES_IMAGE" \
        "RELEASE_TRANSACTION_POSTGRES_DB=$INPLACEX_POSTGRES_DB" \
        "RELEASE_TRANSACTION_POSTGRES_USER=$INPLACEX_POSTGRES_USER" \
        "RELEASE_TRANSACTION_POSTGRES_VOLUME=$INPLACEX_POSTGRES_VOLUME" \
        "RELEASE_TRANSACTION_DATABASE_SYSTEM_IDENTIFIER=${database_system_identifier:-}" \
        "RELEASE_TRANSACTION_BACKEND_LOOPBACK_PORT=$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "RELEASE_TRANSACTION_BACKUP_PATH=$backup_path" \
        "RELEASE_TRANSACTION_BACKUP_SHA256=${backup_sha256:-}" \
        "RELEASE_TRANSACTION_RECEIPT_PATH=$receipt_path" \
        "RELEASE_TRANSACTION_STATE_KEY_SHA256=$state_key_sha256" \
        "RELEASE_TRANSACTION_PUBLIC_KEY_SHA256=$public_key_sha256" \
        "RELEASE_TRANSACTION_GEOIP_SHA256=$geoip_sha256" \
        "RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256=$runtime_config_sha256" \
        "RELEASE_TRANSACTION_CANDIDATE_IMAGE=$requested_candidate_image" \
        "RELEASE_TRANSACTION_CANDIDATE_RELEASE_ID=$requested_candidate_release_id" \
        "RELEASE_TRANSACTION_CANDIDATE_GIT_SHA=$requested_candidate_git_sha" \
        "RELEASE_TRANSACTION_CANDIDATE_IMAGE_DIGEST=$requested_candidate_image_digest" \
        "RELEASE_TRANSACTION_CANDIDATE_SOURCE_ARCHIVE_SHA256=$requested_candidate_source_archive_sha256" \
        "RELEASE_TRANSACTION_PREVIOUS_EXISTS=$previous_exists" \
        "RELEASE_TRANSACTION_PREVIOUS_IMAGE=$previous_image" \
        "RELEASE_TRANSACTION_PREVIOUS_RELEASE_ID=$previous_release_id" \
        "RELEASE_TRANSACTION_PREVIOUS_GIT_SHA=$previous_git_sha" \
        "RELEASE_TRANSACTION_PREVIOUS_IMAGE_DIGEST=$previous_image_digest" \
        "RELEASE_TRANSACTION_PREVIOUS_SOURCE_ARCHIVE_SHA256=$previous_source_archive_sha256"
    transaction_phase="$phase"
}

load_journal() {
    local journal_fd=""
    release_open_root_config "$RELEASE_TRANSACTION_JOURNAL" journal_fd
    release_parse_allowed_kv_fd "$journal_fd" journal_allowlist
    release_require_variables \
        RELEASE_TRANSACTION_VERSION RELEASE_TRANSACTION_OPERATION RELEASE_TRANSACTION_PHASE \
        RELEASE_TRANSACTION_DEPLOYMENT_ID RELEASE_TRANSACTION_COMPOSE_PROJECT_NAME \
        RELEASE_TRANSACTION_POSTGRES_IMAGE RELEASE_TRANSACTION_POSTGRES_DB \
        RELEASE_TRANSACTION_POSTGRES_USER RELEASE_TRANSACTION_POSTGRES_VOLUME \
        RELEASE_TRANSACTION_BACKEND_LOOPBACK_PORT RELEASE_TRANSACTION_BACKUP_PATH \
        RELEASE_TRANSACTION_RECEIPT_PATH RELEASE_TRANSACTION_STATE_KEY_SHA256 \
        RELEASE_TRANSACTION_PUBLIC_KEY_SHA256 RELEASE_TRANSACTION_GEOIP_SHA256 \
        RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256 RELEASE_TRANSACTION_CANDIDATE_IMAGE \
        RELEASE_TRANSACTION_CANDIDATE_RELEASE_ID RELEASE_TRANSACTION_CANDIDATE_GIT_SHA \
        RELEASE_TRANSACTION_CANDIDATE_IMAGE_DIGEST RELEASE_TRANSACTION_CANDIDATE_SOURCE_ARCHIVE_SHA256 \
        RELEASE_TRANSACTION_PREVIOUS_EXISTS
    [[ "$RELEASE_TRANSACTION_VERSION" == "1" && "$RELEASE_TRANSACTION_OPERATION" == "deploy" ]] ||
        release_die 75 "A different or unsupported release transaction is pending"
    [[ "$RELEASE_TRANSACTION_PHASE" =~ ^(intent|postgres_ready|backup_ready|candidate_starting|candidate_verified|activation_committed)$ ]] ||
        release_die 65 "Pending deploy transaction phase is invalid"
    [[ "$RELEASE_TRANSACTION_DEPLOYMENT_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
        release_die 65 "Pending deploy transaction ID is invalid"
    [[ "$RELEASE_TRANSACTION_COMPOSE_PROJECT_NAME" == "$COMPOSE_PROJECT_NAME" &&
        "$RELEASE_TRANSACTION_POSTGRES_IMAGE" == "$INPLACEX_POSTGRES_IMAGE" &&
        "$RELEASE_TRANSACTION_POSTGRES_DB" == "$INPLACEX_POSTGRES_DB" &&
        "$RELEASE_TRANSACTION_POSTGRES_USER" == "$INPLACEX_POSTGRES_USER" &&
        "$RELEASE_TRANSACTION_POSTGRES_VOLUME" == "$INPLACEX_POSTGRES_VOLUME" &&
        "$RELEASE_TRANSACTION_BACKEND_LOOPBACK_PORT" == "$INPLACEX_BACKEND_LOOPBACK_PORT" ]] ||
        release_die 75 "Pending deploy infrastructure differs from the requested environment"
    [[ "$RELEASE_TRANSACTION_CANDIDATE_IMAGE" == "$requested_candidate_image" &&
        "$RELEASE_TRANSACTION_CANDIDATE_RELEASE_ID" == "$requested_candidate_release_id" &&
        "$RELEASE_TRANSACTION_CANDIDATE_GIT_SHA" == "$requested_candidate_git_sha" &&
        "$RELEASE_TRANSACTION_CANDIDATE_IMAGE_DIGEST" == "$requested_candidate_image_digest" &&
        "$RELEASE_TRANSACTION_CANDIDATE_SOURCE_ARCHIVE_SHA256" == "$requested_candidate_source_archive_sha256" ]] ||
        release_die 75 "Finish the pending exact candidate before requesting a different release"
    [[ "$RELEASE_TRANSACTION_STATE_KEY_SHA256" == "$state_key_sha256" &&
        "$RELEASE_TRANSACTION_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
        "$RELEASE_TRANSACTION_GEOIP_SHA256" == "$geoip_sha256" &&
        "$RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
        release_die 75 "Secrets, GeoIP, or runtime config changed during the pending deploy"

    deployment_id="$RELEASE_TRANSACTION_DEPLOYMENT_ID"
    transaction_phase="$RELEASE_TRANSACTION_PHASE"
    database_system_identifier="${RELEASE_TRANSACTION_DATABASE_SYSTEM_IDENTIFIER-}"
    backup_path="$RELEASE_TRANSACTION_BACKUP_PATH"
    backup_sha256="${RELEASE_TRANSACTION_BACKUP_SHA256-}"
    receipt_path="$RELEASE_TRANSACTION_RECEIPT_PATH"
    previous_exists="$RELEASE_TRANSACTION_PREVIOUS_EXISTS"
    previous_image="${RELEASE_TRANSACTION_PREVIOUS_IMAGE-}"
    previous_release_id="${RELEASE_TRANSACTION_PREVIOUS_RELEASE_ID-}"
    previous_git_sha="${RELEASE_TRANSACTION_PREVIOUS_GIT_SHA-}"
    previous_image_digest="${RELEASE_TRANSACTION_PREVIOUS_IMAGE_DIGEST-}"
    previous_source_archive_sha256="${RELEASE_TRANSACTION_PREVIOUS_SOURCE_ARCHIVE_SHA256-}"
    [[ "$backup_path" == "$backup_directory/$deployment_id.pre-deploy.dump" &&
        "$receipt_path" == "$backup_directory/$deployment_id.release.env" ]] ||
        release_die 75 "Pending deploy artifact paths escaped the protected backup directory"
    [[ "$previous_exists" == "true" || "$previous_exists" == "false" ]] ||
        release_die 65 "Pending deploy previous-release flag is invalid"
    if [[ "$previous_exists" == "true" ]]; then
        release_require_variables previous_image previous_release_id previous_git_sha previous_image_digest previous_source_archive_sha256
        release_validate_image_reference "$previous_image"
        [[ "$previous_image_digest" == "${previous_image##*@}" ]] ||
            release_die 65 "Pending previous image digest is inconsistent"
    else
        [[ -z "$previous_image$previous_release_id$previous_git_sha$previous_image_digest$previous_source_archive_sha256" ]] ||
            release_die 65 "Initial pending deploy contains unexpected previous identity"
    fi
    if [[ -n "$database_system_identifier" ]]; then
        [[ "$database_system_identifier" =~ ^[0-9]+$ ]] || release_die 65 "Pending database identity is invalid"
    fi
    if [[ -n "$backup_sha256" ]]; then
        [[ "$backup_sha256" =~ ^[0-9a-f]{64}$ ]] || release_die 65 "Pending backup checksum is invalid"
    fi
}

transaction_resumed=false
deployment_id=""
transaction_phase=""
database_system_identifier=""
backup_sha256=""
previous_exists=false
previous_image=""
previous_release_id=""
previous_git_sha=""
previous_image_digest=""
previous_source_archive_sha256=""

if [[ -e "$RELEASE_TRANSACTION_JOURNAL" || -L "$RELEASE_TRANSACTION_JOURNAL" ]]; then
    [[ -f "$RELEASE_TRANSACTION_JOURNAL" && ! -L "$RELEASE_TRANSACTION_JOURNAL" ]] ||
        release_die 66 "Pending release transaction path is unsafe"
    load_journal
    transaction_resumed=true
else
    deployment_id="$(release_new_deployment_id)"
fi
readonly deployment_id
backup_path="${backup_path:-$backup_directory/$deployment_id.pre-deploy.dump}"
receipt_path="${receipt_path:-$backup_directory/$deployment_id.release.env}"
readonly backup_path receipt_path
readonly sanitized_env="$RELEASE_RUNTIME_DIRECTORY/compose-$deployment_id.env"
release_write_sanitized_env "$sanitized_env" environment_allowlist
compose=(
    docker compose
    --env-file "$sanitized_env"
    --project-directory "$script_directory/../.."
    -f "$compose_file"
)
compose_command() { "${compose[@]}" "$@"; }
compose_command config --quiet

release_verify_pulled_image "$INPLACEX_POSTGRES_IMAGE"
release_verify_pulled_image "$INPLACEX_BACKEND_IMAGE" "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_SOURCE_ARCHIVE_SHA256"
if [[ "$previous_exists" == "true" ]]; then
    release_verify_pulled_image "$previous_image" "$previous_release_id" "$previous_git_sha" "$previous_source_archive_sha256"
fi

current_backend_container="$(compose_command ps --all -q backend 2>/dev/null || true)"
current_postgres_container="$(compose_command ps --all -q postgres 2>/dev/null || true)"
release_validate_container_id "$current_backend_container" backend
release_validate_container_id "$current_postgres_container" postgres

if [[ "$transaction_resumed" == "false" ]]; then
    if release_load_verified_activation; then
        [[ "$runtime_config_sha256" == "$VERIFIED_RUNTIME_CONFIG_SHA256" ]] ||
            release_die 75 "Runtime config changes require a separately rollbackable configuration package"
        if [[ -n "$current_backend_container" &&
            "$(release_inspect_environment "$current_backend_container" INPLACEX_RELEASE_ID)" == "$INPLACEX_RELEASE_ID" &&
            "$(release_inspect_environment "$current_backend_container" INPLACEX_GIT_SHA)" == "$INPLACEX_GIT_SHA" &&
            "$(release_inspect_environment "$current_backend_container" INPLACEX_IMAGE_DIGEST)" == "$INPLACEX_IMAGE_DIGEST" &&
            "$VERIFIED_RELEASE_ID" == "$INPLACEX_RELEASE_ID" && "$VERIFIED_GIT_SHA" == "$INPLACEX_GIT_SHA" &&
            "$VERIFIED_IMAGE_DIGEST" == "$INPLACEX_IMAGE_DIGEST" ]]; then
            [[ "$(docker inspect --format '{{.State.Running}}' "$current_backend_container")" == "true" ]] ||
                compose_command up --detach --wait --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
            "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
                "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST"
            release_disable_drain
            release_disable_maintenance
            rm -f -- "$sanitized_env"
            echo "InplaceX backend $INPLACEX_RELEASE_ID is already durably verified and active."
            exit 0
        fi
        [[ "$INPLACEX_INITIAL_DEPLOY" == "false" ]] ||
            release_die 75 "Initial deployment conflicts with an existing verified activation"
        [[ -n "$current_backend_container" && -n "$current_postgres_container" ]] ||
            release_die 75 "Existing deployment requires both backend and PostgreSQL containers"
        release_validate_backend_port "$current_backend_container"
        release_validate_postgres_container "$current_postgres_container"
        [[ "$(docker inspect --format '{{.State.Running}}' "$current_backend_container")" == "true" ]] ||
            release_die 75 "The previous verified backend must be running before a new deployment"
        previous_image="$(docker inspect --format '{{.Config.Image}}' "$current_backend_container")"
        previous_release_id="$(release_inspect_environment "$current_backend_container" INPLACEX_RELEASE_ID)"
        previous_git_sha="$(release_inspect_environment "$current_backend_container" INPLACEX_GIT_SHA)"
        previous_image_digest="$(release_inspect_environment "$current_backend_container" INPLACEX_IMAGE_DIGEST)"
        previous_source_archive_sha256="$(release_inspect_environment "$current_backend_container" INPLACEX_SOURCE_ARCHIVE_SHA256)"
        release_validate_image_reference "$previous_image"
        [[ "$previous_image_digest" == "${previous_image##*@}" &&
            "$previous_release_id" == "$VERIFIED_RELEASE_ID" &&
            "$previous_git_sha" == "$VERIFIED_GIT_SHA" &&
            "$previous_image_digest" == "$VERIFIED_IMAGE_DIGEST" ]] ||
            release_die 75 "Running backend is not the exact durable verified activation"
        [[ "$previous_source_archive_sha256" =~ ^[0-9a-f]{64}$ ]] ||
            release_die 75 "Running backend source provenance is invalid"
        release_verify_pulled_image "$previous_image" "$previous_release_id" "$previous_git_sha" "$previous_source_archive_sha256"
        previous_exists=true
    else
        [[ "$INPLACEX_INITIAL_DEPLOY" == "true" ]] ||
            release_die 75 "Existing deployment requires a durable verified activation state"
        [[ -z "$current_backend_container" && -z "$current_postgres_container" ]] ||
            release_die 75 "Initial deployment refuses existing project containers"
        release_assert_volume_empty
        previous_exists=false
    fi
    [[ ! -e "$backup_path" && ! -e "$receipt_path" ]] ||
        release_die 73 "Deployment artifact already exists"
    write_journal intent
    release_fault_inject after_intent
else
    echo "Resuming durable InplaceX deploy transaction $deployment_id at phase $transaction_phase." >&2
fi

if ss -H -ltn "sport = :$INPLACEX_BACKEND_LOOPBACK_PORT" | grep -q . &&
    [[ -z "$current_backend_container" ]]; then
    release_die 75 "Configured loopback port is owned by another process"
fi

maintenance_active=false
candidate_activated=false
deployment_succeeded=false

restore_database_backup() {
    release_validate_backup_file "$backup_path"
    [[ "$(release_sha256_file "$backup_path")" == "$backup_sha256" ]] || return 1
    compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname=postgres --variable=db="$POSTGRES_DB" --variable=owner="$POSTGRES_USER" <<'"'"'SQL'"'"'
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :'"'"'db'"'"' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS :"db";
CREATE DATABASE :"db" OWNER :"owner";
SQL'
    compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec pg_restore --exit-on-error --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
        < "$backup_path"
}

recover_previous_release() {
    [[ "$previous_exists" == "true" && -n "$backup_sha256" ]] || return 1
    compose_command stop --timeout 30 backend >/dev/null 2>&1 || true
    compose_command up --detach --wait --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" postgres || return 1
    restore_database_backup || return 1
    export INPLACEX_BACKEND_IMAGE="$previous_image"
    export INPLACEX_RELEASE_ID="$previous_release_id"
    export INPLACEX_GIT_SHA="$previous_git_sha"
    export INPLACEX_IMAGE_DIGEST="$previous_image_digest"
    export INPLACEX_SOURCE_ARCHIVE_SHA256="$previous_source_archive_sha256"
    export INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=""
    rm -f -- "$sanitized_env"
    release_write_sanitized_env "$sanitized_env" environment_allowlist
    release_start_activation_lease \
        "$previous_release_id" "$previous_git_sha" "$previous_image_digest" \
        "$state_key_sha256" "$public_key_sha256" "$geoip_sha256" "$runtime_config_sha256"
    compose_command up --detach --force-recreate --wait \
        --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend || return 1
    "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "$previous_release_id" "$previous_git_sha" "$previous_image_digest" || return 1
    release_write_activation_record \
        "$RELEASE_VERIFIED_ACTIVATION_FILE" "$previous_release_id" "$previous_git_sha" "$previous_image_digest" \
        "$state_key_sha256" "$public_key_sha256" "$geoip_sha256" "$runtime_config_sha256"
    release_stop_activation_lease
    release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
    release_disable_drain
    release_disable_maintenance
}

on_exit() {
    local status=$?
    trap - EXIT
    release_stop_activation_lease || true
    if [[ "$status" -ne 0 && "$maintenance_active" == "true" && "$candidate_activated" != "true" ]]; then
        echo "Deployment failed before durable activation; attempting journal-bound recovery." >&2
        if recover_previous_release; then
            echo "Previous verified backend and pre-deploy database were restored." >&2
        else
            compose_command stop --timeout 30 backend >/dev/null 2>&1 || true
            echo "Automatic recovery could not be verified; maintenance and transaction journal remain." >&2
        fi
    fi
    rm -f -- "$sanitized_env"
    [[ "$deployment_succeeded" == "true" ]] && exit 0
    exit "$status"
}
trap on_exit EXIT

release_enable_maintenance "$deployment_id"
maintenance_active=true
if [[ -n "$current_backend_container" &&
    "$(docker inspect --format '{{.State.Running}}' "$current_backend_container" 2>/dev/null || true)" == "true" ]]; then
    release_enable_drain "$deployment_id"
    release_wait_for_drain \
        "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" "$INPLACEX_DRAIN_TIMEOUT_SECONDS"
fi
release_fault_inject after_gate
if [[ -n "$current_backend_container" ]]; then
    compose_command stop --timeout 30 backend
fi
compose_command up --detach --wait \
    --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" postgres
current_postgres_container="$(compose_command ps -q postgres)"
release_validate_container_id "$current_postgres_container" postgres
[[ -n "$current_postgres_container" ]] || release_die 70 "PostgreSQL did not start"
release_validate_postgres_container "$current_postgres_container"
release_validate_runtime_secret_reads "$current_postgres_container" /run/secrets/inplacex_database_password
actual_database_system_identifier="$(release_database_system_identifier compose_command)"
[[ "$actual_database_system_identifier" =~ ^[0-9]+$ ]] ||
    release_die 70 "PostgreSQL returned an invalid system identifier"
if [[ -n "$database_system_identifier" ]]; then
    [[ "$actual_database_system_identifier" == "$database_system_identifier" ]] ||
        release_die 75 "PostgreSQL system identifier changed during the pending deployment"
else
    database_system_identifier="$actual_database_system_identifier"
    write_journal postgres_ready
fi

if [[ -n "${INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK:-}" ]]; then
    legacy_history="$(compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec psql --tuples-only --no-align --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="SELECT string_agg(version::text, chr(44) ORDER BY version), count(*) FILTER (WHERE checksum IS NULL) FROM inplacex_schema_history;"' |
        tr -d '[:space:]')"
    release_validate_legacy_checksum_history "$legacy_history"
fi

if [[ -e "$backup_path" ]]; then
    release_validate_backup_file "$backup_path"
    compose_command exec -T postgres pg_restore --list < "$backup_path" >/dev/null
    actual_backup_sha256="$(release_sha256_file "$backup_path")"
    [[ -z "$backup_sha256" || "$actual_backup_sha256" == "$backup_sha256" ]] ||
        release_die 75 "Pending pre-deploy backup checksum changed"
    backup_sha256="$actual_backup_sha256"
else
    compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
        > "$backup_path"
    chmod 0600 "$backup_path"
    release_validate_backup_file "$backup_path"
    compose_command exec -T postgres pg_restore --list < "$backup_path" >/dev/null
    release_sync_file_and_parent "$backup_path"
    backup_sha256="$(release_sha256_file "$backup_path")"
fi
write_journal backup_ready
release_fault_inject after_backup

export INPLACEX_BACKEND_IMAGE="$requested_candidate_image"
export INPLACEX_RELEASE_ID="$requested_candidate_release_id"
export INPLACEX_GIT_SHA="$requested_candidate_git_sha"
export INPLACEX_IMAGE_DIGEST="$requested_candidate_image_digest"
export INPLACEX_SOURCE_ARCHIVE_SHA256="$requested_candidate_source_archive_sha256"
rm -f -- "$sanitized_env"
release_write_sanitized_env "$sanitized_env" environment_allowlist
release_start_activation_lease \
    "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" \
    "$state_key_sha256" "$public_key_sha256" "$geoip_sha256" "$runtime_config_sha256"
write_journal candidate_starting
compose_command up --detach --force-recreate --wait \
    --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
candidate_container="$(compose_command ps -q backend)"
release_validate_container_id "$candidate_container" backend
[[ -n "$candidate_container" ]] || release_die 70 "Candidate backend did not start"
release_validate_backend_port "$candidate_container"
release_validate_runtime_secret_reads "$candidate_container" \
    /run/secrets/inplacex_database_password \
    /run/secrets/inplacex_online_public_key \
    /run/secrets/inplacex_online_state_key
release_fault_inject after_candidate_start
"$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
    "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST"
[[ "$(release_database_system_identifier compose_command)" == "$database_system_identifier" ]] ||
    release_die 75 "PostgreSQL system identifier changed during deployment"

if [[ -n "${INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK:-}" ]]; then
    remaining_missing_checksums="$(compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec psql --tuples-only --no-align --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="SELECT count(*) FROM inplacex_schema_history WHERE checksum IS NULL;"' |
        tr -d '[:space:]')"
    [[ "$remaining_missing_checksums" == "0" ]] ||
        release_die 75 "Candidate did not complete the acknowledged checksum baseline"
    export INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=""
    rm -f -- "$sanitized_env"
    release_write_sanitized_env "$sanitized_env" environment_allowlist
    compose_command up --detach --force-recreate --wait \
        --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
    "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST"
fi

write_journal candidate_verified
release_write_activation_record \
    "$RELEASE_VERIFIED_ACTIVATION_FILE" "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" \
    "$state_key_sha256" "$public_key_sha256" "$geoip_sha256" "$runtime_config_sha256"
candidate_activated=true
release_stop_activation_lease
release_fault_inject after_activation

receipt_lines=(
    "ROLLBACK_RECEIPT_VERSION=2"
    "ROLLBACK_DEPLOYMENT_ID=$deployment_id"
    "ROLLBACK_COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME"
    "ROLLBACK_POSTGRES_IMAGE=$INPLACEX_POSTGRES_IMAGE"
    "ROLLBACK_POSTGRES_DB=$INPLACEX_POSTGRES_DB"
    "ROLLBACK_POSTGRES_USER=$INPLACEX_POSTGRES_USER"
    "ROLLBACK_POSTGRES_VOLUME=$INPLACEX_POSTGRES_VOLUME"
    "ROLLBACK_DATABASE_SYSTEM_IDENTIFIER=$database_system_identifier"
    "ROLLBACK_BACKEND_LOOPBACK_PORT=$INPLACEX_BACKEND_LOOPBACK_PORT"
    "ROLLBACK_BACKUP_PATH=$backup_path"
    "ROLLBACK_BACKUP_SHA256=$backup_sha256"
    "ROLLBACK_STATE_KEY_SHA256=$state_key_sha256"
    "ROLLBACK_PUBLIC_KEY_SHA256=$public_key_sha256"
    "ROLLBACK_GEOIP_SHA256=$geoip_sha256"
    "ROLLBACK_RUNTIME_CONFIG_SHA256=$runtime_config_sha256"
    "ROLLBACK_CANDIDATE_IMAGE=$INPLACEX_BACKEND_IMAGE"
    "ROLLBACK_CANDIDATE_RELEASE_ID=$INPLACEX_RELEASE_ID"
    "ROLLBACK_CANDIDATE_GIT_SHA=$INPLACEX_GIT_SHA"
    "ROLLBACK_CANDIDATE_IMAGE_DIGEST=$INPLACEX_IMAGE_DIGEST"
    "ROLLBACK_CANDIDATE_SOURCE_ARCHIVE_SHA256=$INPLACEX_SOURCE_ARCHIVE_SHA256"
)
if [[ "$previous_exists" == "true" ]]; then
    receipt_lines+=(
        "ROLLBACK_PREVIOUS_EXISTS=true"
        "ROLLBACK_PREVIOUS_IMAGE=$previous_image"
        "ROLLBACK_PREVIOUS_RELEASE_ID=$previous_release_id"
        "ROLLBACK_PREVIOUS_GIT_SHA=$previous_git_sha"
        "ROLLBACK_PREVIOUS_IMAGE_DIGEST=$previous_image_digest"
        "ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256=$previous_source_archive_sha256"
    )
else
    receipt_lines+=("ROLLBACK_PREVIOUS_EXISTS=false")
fi
release_atomic_kv_file "$receipt_path" "${receipt_lines[@]}"
latest_pointer="$backup_directory/latest-inplacex-backend-release.env"
release_atomic_kv_file "$latest_pointer" \
    "RELEASE_POINTER_VERSION=2" \
    "RELEASE_POINTER_STATE=active" \
    "RELEASE_POINTER_DEPLOYMENT_ID=$deployment_id" \
    "RELEASE_POINTER_RECEIPT_PATH=$receipt_path"
write_journal activation_committed
release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
release_disable_drain
release_disable_maintenance
maintenance_active=false
deployment_succeeded=true

echo "Deployed and durably activated InplaceX backend $INPLACEX_RELEASE_ID."
echo "Deployment ID: $deployment_id"
echo "Pre-deploy backup: $backup_path ($backup_sha256)"
echo "Rollback receipt: $receipt_path"
