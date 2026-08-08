#!/usr/bin/bash -p
# shellcheck disable=SC2016,SC2034
set -euo pipefail
umask 077

script_directory="$(builtin cd -- "${BASH_SOURCE[0]%/*}" && builtin pwd -P)"
readonly script_directory
# shellcheck source=ops/production/release-shell-bootstrap.sh
builtin source "$script_directory/release-shell-bootstrap.sh"

if [[ $# -lt 1 || $# -gt 3 ]]; then
    echo "Usage: sudo $0 <absolute-env-file> [YYYY-MM|--candidate-file <absolute-mmdb>]" >&2
    exit 64
fi
[[ ${EUID:-$(id -u)} -eq 0 ]] || {
    echo "Production GeoIP rotation must run as root." >&2
    exit 77
}

readonly env_file="$1"
candidate_source=""
release_month=""
if [[ $# -eq 2 ]]; then
    release_month="$2"
elif [[ $# -eq 3 && "$2" == "--candidate-file" ]]; then
    candidate_source="$3"
else
    [[ $# -eq 1 ]] || {
        echo "GeoIP rotation accepts either a release month or one candidate file." >&2
        exit 64
    }
fi
[[ -z "$release_month" || "$release_month" =~ ^[0-9]{4}-(0[1-9]|1[0-2])$ ]] || {
    echo "GeoIP release month must use YYYY-MM." >&2
    exit 65
}

readonly compose_file="$script_directory/compose.yaml"
readonly smoke_script="$script_directory/smoke-backend.sh"
readonly update_script="$script_directory/../ads/update-dbip-country-lite.sh"
# shellcheck source=ops/production/release-common.sh
source "$script_directory/release-common.sh"

release_require_commands \
    awk cmp cp curl date find flock gzip install mktemp nginx openssl python3 sed \
    sha256sum sleep sort stat sync wc
release_acquire_lock
release_prepare_runtime_directory
release_prepare_docker_control_plane compose

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
    [INPLACEX_ACTIVATION_V1_MIGRATION_ACK]=1
    [INPLACEX_RUNTIME_CONFIG_SHA256]=1
)

environment_fd=""
release_open_root_config "$env_file" environment_fd
release_parse_allowed_kv_fd "$environment_fd" environment_allowlist
[[ -z "${INPLACEX_RUNTIME_CONFIG_SHA256+x}" ]] ||
    release_die 65 "INPLACEX_RUNTIME_CONFIG_SHA256 is calculated by GeoIP rotation"
release_validate_env_values
release_prepare_state_directories
release_validate_secret_tree
release_validate_secret_payloads
release_validate_geoip_file
release_validate_geoip_payload
release_validate_release_manifest
release_validate_nginx_gate_installation
release_validate_volume

database_password_sha256="$(release_sha256_file "$INPLACEX_SECRET_DIRECTORY/database-password.txt")"
state_key_sha256="$(release_sha256_file "$INPLACEX_SECRET_DIRECTORY/online-state-key-base64.txt")"
public_key_sha256="$(release_sha256_file "$INPLACEX_SECRET_DIRECTORY/platform-public-key-x509-base64.txt")"
runtime_config_sha256="$(release_runtime_config_fingerprint)"
readonly database_password_sha256 state_key_sha256 public_key_sha256 runtime_config_sha256
export INPLACEX_RUNTIME_CONFIG_SHA256="$runtime_config_sha256"
release_assert_secret_continuity "$database_password_sha256" "$state_key_sha256" "$public_key_sha256"
[[ -z "${INPLACEX_ACTIVATION_V1_MIGRATION_ACK:-}" ]] ||
    release_die 75 "Activation v1 migration acknowledgement is deploy-only; finish migration with deploy-backend.sh or remove it after v2 activation"

validate_geoip_path() {
    local path="$1"
    local configured_path="$INPLACEX_GEOIP_DB_PATH"
    INPLACEX_GEOIP_DB_PATH="$path"
    release_validate_geoip_file
    release_validate_geoip_payload
    INPLACEX_GEOIP_DB_PATH="$configured_path"
}

remove_geoip_artifact() {
    local path="$1"
    [[ ! -e "$path" && ! -L "$path" ]] && return 0
    validate_geoip_path "$path"
    rm -f -- "$path"
    release_sync_directory "$(dirname -- "$path")"
}

remove_unjournaled_geoip_artifact() {
    local path="$1"
    [[ ! -e "$path" && ! -L "$path" ]] && return 0
    release_validate_absolute_parent_chain "$path"
    [[ -f "$path" && ! -L "$path" ]] ||
        release_die 66 "Unjournaled GeoIP staging path is unsafe"
    local metadata mode
    metadata="$(stat -Lc '%F %u %g %a %h' -- "$path")"
    [[ "$metadata" =~ ^regular\ file\ 0\ 0\ ([0-7]+)\ 1$ ]] ||
        release_die 77 "Unjournaled GeoIP staging metadata is unsafe"
    mode="${BASH_REMATCH[1]}"
    (( (8#$mode & 022) == 0 )) ||
        release_die 77 "Unjournaled GeoIP staging artifact is writable by group/world"
    rm -f -- "$path"
    release_sync_directory "$(dirname -- "$path")"
}

declare -Ar journal_allowlist=(
    [RELEASE_TRANSACTION_VERSION]=1
    [RELEASE_TRANSACTION_OPERATION]=1
    [RELEASE_TRANSACTION_PHASE]=1
    [RELEASE_TRANSACTION_DEPLOYMENT_ID]=1
    [RELEASE_TRANSACTION_RELEASE_ID]=1
    [RELEASE_TRANSACTION_GIT_SHA]=1
    [RELEASE_TRANSACTION_IMAGE_DIGEST]=1
    [RELEASE_TRANSACTION_DATABASE_SYSTEM_IDENTIFIER]=1
    [RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256]=1
    [RELEASE_TRANSACTION_STATE_KEY_SHA256]=1
    [RELEASE_TRANSACTION_PUBLIC_KEY_SHA256]=1
    [RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256]=1
    [RELEASE_TRANSACTION_OLD_GEOIP_SHA256]=1
    [RELEASE_TRANSACTION_NEW_GEOIP_SHA256]=1
    [RELEASE_TRANSACTION_BACKUP_PATH]=1
    [RELEASE_TRANSACTION_CANDIDATE_PATH]=1
)

deployment_id=""
transaction_phase=""
database_system_identifier=""
old_geoip_sha256=""
new_geoip_sha256=""
backup_path=""
candidate_path=""
backup_staging=""
pre_journal_artifacts=false

cleanup_unjournaled_artifacts() {
    local status=$?
    trap - EXIT
    if [[ "$status" -ne 0 && "$pre_journal_artifacts" == "true" &&
        ! -e "$RELEASE_TRANSACTION_JOURNAL" && ! -L "$RELEASE_TRANSACTION_JOURNAL" ]]; then
        rm -f -- "$candidate_path" "$backup_staging" "$backup_path"
        sync -f "$(dirname -- "$candidate_path")" >/dev/null 2>&1 || true
        sync -f "$(dirname -- "$backup_path")" >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap cleanup_unjournaled_artifacts EXIT

write_journal() {
    local phase="$1"
    release_atomic_kv_file "$RELEASE_TRANSACTION_JOURNAL" \
        "RELEASE_TRANSACTION_VERSION=1" \
        "RELEASE_TRANSACTION_OPERATION=geoip" \
        "RELEASE_TRANSACTION_PHASE=$phase" \
        "RELEASE_TRANSACTION_DEPLOYMENT_ID=$deployment_id" \
        "RELEASE_TRANSACTION_RELEASE_ID=$INPLACEX_RELEASE_ID" \
        "RELEASE_TRANSACTION_GIT_SHA=$INPLACEX_GIT_SHA" \
        "RELEASE_TRANSACTION_IMAGE_DIGEST=$INPLACEX_IMAGE_DIGEST" \
        "RELEASE_TRANSACTION_DATABASE_SYSTEM_IDENTIFIER=$database_system_identifier" \
        "RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256=$database_password_sha256" \
        "RELEASE_TRANSACTION_STATE_KEY_SHA256=$state_key_sha256" \
        "RELEASE_TRANSACTION_PUBLIC_KEY_SHA256=$public_key_sha256" \
        "RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256=$runtime_config_sha256" \
        "RELEASE_TRANSACTION_OLD_GEOIP_SHA256=$old_geoip_sha256" \
        "RELEASE_TRANSACTION_NEW_GEOIP_SHA256=$new_geoip_sha256" \
        "RELEASE_TRANSACTION_BACKUP_PATH=$backup_path" \
        "RELEASE_TRANSACTION_CANDIDATE_PATH=$candidate_path"
    transaction_phase="$phase"
}

load_journal() {
    local journal_fd=""
    release_open_root_config "$RELEASE_TRANSACTION_JOURNAL" journal_fd
    release_parse_allowed_kv_fd "$journal_fd" journal_allowlist
    release_require_variables \
        RELEASE_TRANSACTION_VERSION RELEASE_TRANSACTION_OPERATION RELEASE_TRANSACTION_PHASE \
        RELEASE_TRANSACTION_DEPLOYMENT_ID RELEASE_TRANSACTION_RELEASE_ID RELEASE_TRANSACTION_GIT_SHA \
        RELEASE_TRANSACTION_IMAGE_DIGEST RELEASE_TRANSACTION_DATABASE_SYSTEM_IDENTIFIER \
        RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256 \
        RELEASE_TRANSACTION_STATE_KEY_SHA256 RELEASE_TRANSACTION_PUBLIC_KEY_SHA256 \
        RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256 RELEASE_TRANSACTION_OLD_GEOIP_SHA256 \
        RELEASE_TRANSACTION_NEW_GEOIP_SHA256 RELEASE_TRANSACTION_BACKUP_PATH \
        RELEASE_TRANSACTION_CANDIDATE_PATH
    [[ "$RELEASE_TRANSACTION_VERSION" == "1" && "$RELEASE_TRANSACTION_OPERATION" == "geoip" ]] ||
        release_die 75 "A different or unsupported release transaction is pending"
    [[ "$RELEASE_TRANSACTION_PHASE" =~ ^(candidate_ready|gate_active|geoip_installed|activation_committed)$ ]] ||
        release_die 65 "Pending GeoIP transaction phase is invalid"
    [[ "$RELEASE_TRANSACTION_DEPLOYMENT_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
        release_die 65 "Pending GeoIP transaction ID is invalid"
    [[ "$RELEASE_TRANSACTION_RELEASE_ID" == "$INPLACEX_RELEASE_ID" &&
        "$RELEASE_TRANSACTION_GIT_SHA" == "$INPLACEX_GIT_SHA" &&
        "$RELEASE_TRANSACTION_IMAGE_DIGEST" == "$INPLACEX_IMAGE_DIGEST" &&
        "$RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
        "$RELEASE_TRANSACTION_STATE_KEY_SHA256" == "$state_key_sha256" &&
        "$RELEASE_TRANSACTION_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
        "$RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
        release_die 75 "Release identity, secrets, or runtime config changed during GeoIP rotation"
    deployment_id="$RELEASE_TRANSACTION_DEPLOYMENT_ID"
    transaction_phase="$RELEASE_TRANSACTION_PHASE"
    database_system_identifier="$RELEASE_TRANSACTION_DATABASE_SYSTEM_IDENTIFIER"
    old_geoip_sha256="$RELEASE_TRANSACTION_OLD_GEOIP_SHA256"
    new_geoip_sha256="$RELEASE_TRANSACTION_NEW_GEOIP_SHA256"
    backup_path="$RELEASE_TRANSACTION_BACKUP_PATH"
    candidate_path="$RELEASE_TRANSACTION_CANDIDATE_PATH"
    [[ "$database_system_identifier" =~ ^[0-9]+$ &&
        "$old_geoip_sha256" =~ ^[0-9a-f]{64}$ && "$new_geoip_sha256" =~ ^[0-9a-f]{64}$ ]] ||
        release_die 65 "Pending GeoIP fingerprints are invalid"
    [[ "$backup_path" == "$INPLACEX_RELEASE_STATE_DIRECTORY/geoip-rotation.pending.previous.mmdb" &&
        "$candidate_path" == "$(dirname -- "$INPLACEX_GEOIP_DB_PATH")/.geoip-rotation.pending.candidate.mmdb" ]] ||
        release_die 75 "Pending GeoIP paths escaped their protected directories"
    if [[ -e "$backup_path" || -L "$backup_path" ]]; then
        validate_geoip_path "$backup_path"
        [[ "$(release_sha256_file "$backup_path")" == "$old_geoip_sha256" ]] ||
            release_die 75 "Pending GeoIP backup changed"
    elif [[ "$transaction_phase" != "activation_committed" ]]; then
        release_die 75 "Pending GeoIP backup is missing"
    fi
}

transaction_resumed=false
if [[ -e "$RELEASE_TRANSACTION_JOURNAL" || -L "$RELEASE_TRANSACTION_JOURNAL" ]]; then
    [[ -f "$RELEASE_TRANSACTION_JOURNAL" && ! -L "$RELEASE_TRANSACTION_JOURNAL" ]] ||
        release_die 66 "Pending release transaction path is unsafe"
    load_journal
    transaction_resumed=true
else
    deployment_id="$(release_new_deployment_id)"
    old_geoip_sha256="$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")"
    release_load_verified_activation || release_die 75 "GeoIP rotation requires a verified active release"
    [[ "$VERIFIED_RELEASE_ID" == "$INPLACEX_RELEASE_ID" &&
        "$VERIFIED_GIT_SHA" == "$INPLACEX_GIT_SHA" &&
        "$VERIFIED_IMAGE_DIGEST" == "$INPLACEX_IMAGE_DIGEST" &&
        "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
        "$VERIFIED_STATE_KEY_SHA256" == "$state_key_sha256" &&
        "$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
        "$VERIFIED_GEOIP_SHA256" == "$old_geoip_sha256" &&
        "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
        release_die 75 "Current runtime is not the exact verified GeoIP activation"
    backup_path="$INPLACEX_RELEASE_STATE_DIRECTORY/geoip-rotation.pending.previous.mmdb"
    candidate_path="$(dirname -- "$INPLACEX_GEOIP_DB_PATH")/.geoip-rotation.pending.candidate.mmdb"
    backup_staging="$backup_path.partial"
    remove_unjournaled_geoip_artifact "$candidate_path"
    remove_unjournaled_geoip_artifact "$backup_staging"
    remove_unjournaled_geoip_artifact "$backup_path"
    pre_journal_artifacts=true
    ( set -C; : > "$backup_staging" ) || release_die 73 "Cannot create GeoIP backup safely"
    chmod 0600 "$backup_staging"
    cp -- "$INPLACEX_GEOIP_DB_PATH" "$backup_staging"
    validate_geoip_path "$backup_staging"
    release_sync_file_and_parent "$backup_staging"
    mv -- "$backup_staging" "$backup_path"
    release_sync_file_and_parent "$backup_path"

    if [[ -n "$candidate_source" ]]; then
        release_validate_absolute_parent_chain "$candidate_source"
        validate_geoip_path "$candidate_source"
        cp -- "$candidate_source" "$candidate_path"
        chmod 0644 "$candidate_path"
    else
        "$update_script" "$candidate_path" "${release_month:-$(date -u +%Y-%m)}" >/dev/null
    fi
    validate_geoip_path "$candidate_path"
    release_sync_file_and_parent "$candidate_path"
    new_geoip_sha256="$(release_sha256_file "$candidate_path")"
    if [[ "$new_geoip_sha256" == "$old_geoip_sha256" ]]; then
        remove_geoip_artifact "$candidate_path"
        remove_geoip_artifact "$backup_path"
        echo "GeoIP database already matches the requested artifact."
        exit 0
    fi
fi

readonly deployment_id old_geoip_sha256 new_geoip_sha256 backup_path candidate_path
readonly sanitized_env="$RELEASE_RUNTIME_DIRECTORY/geoip-$deployment_id.env"
release_write_sanitized_env "$sanitized_env" environment_allowlist
compose=(
    release_docker compose
    --env-file "$sanitized_env"
    --project-directory "$script_directory/../.."
    -f "$compose_file"
)
compose_command() { "${compose[@]}" "$@"; }
compose_command config --quiet
release_verify_pulled_image "$INPLACEX_POSTGRES_IMAGE"
release_verify_pulled_image \
    "$INPLACEX_BACKEND_IMAGE" "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_SOURCE_ARCHIVE_SHA256"

current_backend_container="$(compose_command ps --all -q backend 2>/dev/null || true)"
current_postgres_container="$(compose_command ps --all -q postgres 2>/dev/null || true)"
release_validate_container_id "$current_backend_container" backend
release_validate_container_id "$current_postgres_container" postgres
[[ -n "$current_backend_container" && -n "$current_postgres_container" ]] ||
    release_die 75 "GeoIP rotation requires the active backend and PostgreSQL containers"
release_validate_backend_port "$current_backend_container"
release_validate_postgres_container "$current_postgres_container"
compose_command up --detach --wait --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" postgres
actual_database_system_identifier="$(release_database_system_identifier compose_command)"
if [[ "$transaction_resumed" == "true" ]]; then
    [[ "$actual_database_system_identifier" == "$database_system_identifier" ]] ||
        release_die 75 "PostgreSQL system identifier changed during GeoIP rotation"
else
    database_system_identifier="$actual_database_system_identifier"
    write_journal candidate_ready
    pre_journal_artifacts=false
    release_fault_inject geoip_after_candidate_ready
fi

maintenance_active=false
geoip_activated=false
rotation_succeeded=false

restore_previous_geoip() {
    [[ "$(release_database_system_identifier compose_command)" == "$database_system_identifier" ]] || return 1

    local current_geoip running_backend
    current_geoip="$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")"
    running_backend="$(compose_command ps -q backend 2>/dev/null || true)"
    if [[ "$current_geoip" == "$old_geoip_sha256" && -n "$running_backend" &&
        "$(release_docker inspect --format '{{.State.Running}}' "$running_backend" 2>/dev/null || true)" == "true" &&
        "$(release_inspect_environment "$running_backend" INPLACEX_RELEASE_ID)" == "$INPLACEX_RELEASE_ID" &&
        "$(release_inspect_environment "$running_backend" INPLACEX_GIT_SHA)" == "$INPLACEX_GIT_SHA" &&
        "$(release_inspect_environment "$running_backend" INPLACEX_IMAGE_DIGEST)" == "$INPLACEX_IMAGE_DIGEST" ]]; then
        release_load_verified_activation || return 1
        [[ "$VERIFIED_RELEASE_ID" == "$INPLACEX_RELEASE_ID" &&
            "$VERIFIED_GIT_SHA" == "$INPLACEX_GIT_SHA" &&
            "$VERIFIED_IMAGE_DIGEST" == "$INPLACEX_IMAGE_DIGEST" &&
            "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
            "$VERIFIED_STATE_KEY_SHA256" == "$state_key_sha256" &&
            "$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
            "$VERIFIED_GEOIP_SHA256" == "$old_geoip_sha256" &&
            "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] || return 1
        "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
            "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" || return 1
        release_disable_drain
        release_disable_maintenance
        release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
        remove_geoip_artifact "$candidate_path"
        remove_geoip_artifact "$backup_path"
        return 0
    fi

    release_stop_backend_fail_closed compose_command || return 1
    local restore_staging
    restore_staging="$(dirname -- "$INPLACEX_GEOIP_DB_PATH")/.geoip-$deployment_id.restore.mmdb"
    rm -f -- "$restore_staging"
    cp -- "$backup_path" "$restore_staging" || return 1
    chmod 0644 "$restore_staging"
    validate_geoip_path "$restore_staging"
    mv -f -- "$restore_staging" "$INPLACEX_GEOIP_DB_PATH"
    release_sync_file_and_parent "$INPLACEX_GEOIP_DB_PATH"
    [[ "$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")" == "$old_geoip_sha256" ]] || return 1
    release_start_activation_lease \
        "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" \
        "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
        "$old_geoip_sha256" "$runtime_config_sha256"
    compose_command up --detach --force-recreate --wait \
        --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend || return 1
    "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" || return 1
    release_write_activation_record \
        "$RELEASE_VERIFIED_ACTIVATION_FILE" "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" \
        "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
        "$old_geoip_sha256" "$runtime_config_sha256"
    release_stop_activation_lease
    release_disable_drain
    release_disable_maintenance
    release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
    remove_geoip_artifact "$candidate_path"
    remove_geoip_artifact "$backup_path"
}

on_exit() {
    local status=$?
    trap - EXIT
    release_stop_activation_lease || true
    if [[ "$status" -ne 0 && "$maintenance_active" == "true" && "$geoip_activated" != "true" ]]; then
        if [[ "$RELEASE_BACKEND_STOP_PROOF_FAILED" == "true" ]]; then
            echo "Backend stop was not proven; GeoIP gates and transaction journal remain without recovery mutation." >&2
        elif ! restore_previous_geoip; then
            if ! release_stop_backend_fail_closed compose_command; then
                echo "Backend stop could not be proven during failed GeoIP recovery." >&2
            fi
            echo "GeoIP recovery failed; backend remains fail-closed with the durable journal." >&2
        fi
    fi
    rm -f -- "$sanitized_env"
    [[ "$rotation_succeeded" == "true" ]] && exit 0
    exit "$status"
}
trap on_exit EXIT

if [[ "$transaction_phase" == "activation_committed" ]]; then
    release_load_verified_activation || release_die 75 "Committed GeoIP activation is missing"
    [[ "$VERIFIED_RELEASE_ID" == "$INPLACEX_RELEASE_ID" &&
        "$VERIFIED_GIT_SHA" == "$INPLACEX_GIT_SHA" &&
        "$VERIFIED_IMAGE_DIGEST" == "$INPLACEX_IMAGE_DIGEST" &&
        "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
        "$VERIFIED_STATE_KEY_SHA256" == "$state_key_sha256" &&
        "$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
        "$VERIFIED_GEOIP_SHA256" == "$new_geoip_sha256" &&
        "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
        release_die 75 "Committed GeoIP activation fingerprint is inconsistent"
    [[ "$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")" == "$new_geoip_sha256" ]] ||
        release_die 75 "Committed GeoIP artifact fingerprint is inconsistent"
    compose_command up --detach --wait \
        --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
    current_backend_container="$(compose_command ps -q backend)"
    release_validate_container_id "$current_backend_container" backend
    [[ -n "$current_backend_container" ]] || release_die 70 "Committed GeoIP backend did not start"
    release_validate_backend_port "$current_backend_container"
    running_release_id="$(release_inspect_environment "$current_backend_container" INPLACEX_RELEASE_ID)"
    running_git_sha="$(release_inspect_environment "$current_backend_container" INPLACEX_GIT_SHA)"
    running_image_digest="$(release_inspect_environment "$current_backend_container" INPLACEX_IMAGE_DIGEST)"
    [[ "$running_release_id" == "$INPLACEX_RELEASE_ID" &&
        "$running_git_sha" == "$INPLACEX_GIT_SHA" &&
        "$running_image_digest" == "$INPLACEX_IMAGE_DIGEST" ]] ||
        release_die 75 "Running backend differs from the committed GeoIP activation"
    "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST"
    release_disable_drain
    release_disable_maintenance
    remove_geoip_artifact "$candidate_path"
    remove_geoip_artifact "$backup_path"
    release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
    rm -f -- "$sanitized_env"
    echo "Finalized previously verified GeoIP activation $new_geoip_sha256."
    exit 0
fi

release_enable_maintenance "$deployment_id"
maintenance_active=true
drain_verified_backend=false
current_geoip_before_gate="$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")"
if [[ "$current_geoip_before_gate" == "$old_geoip_sha256" ]] &&
    release_running_backend_matches_verified_activation \
        "$current_backend_container" "$INPLACEX_BACKEND_IMAGE" "$INPLACEX_RELEASE_ID" \
        "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" "$database_password_sha256" \
        "$state_key_sha256" "$public_key_sha256" "$old_geoip_sha256" "$runtime_config_sha256"; then
    drain_verified_backend=true
elif [[ "$current_geoip_before_gate" == "$new_geoip_sha256" ]] &&
    release_running_backend_matches_verified_activation \
        "$current_backend_container" "$INPLACEX_BACKEND_IMAGE" "$INPLACEX_RELEASE_ID" \
        "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" "$database_password_sha256" \
        "$state_key_sha256" "$public_key_sha256" "$new_geoip_sha256" "$runtime_config_sha256"; then
    drain_verified_backend=true
fi
if [[ "$drain_verified_backend" == "true" ]]; then
    release_enable_drain "$deployment_id"
    release_wait_for_drain \
        "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" "$INPLACEX_DRAIN_TIMEOUT_SECONDS"
fi
release_stop_backend_fail_closed compose_command
write_journal gate_active
release_fault_inject geoip_after_gate

current_geoip_sha256="$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")"
if [[ "$current_geoip_sha256" == "$old_geoip_sha256" ]]; then
    validate_geoip_path "$candidate_path"
    [[ "$(release_sha256_file "$candidate_path")" == "$new_geoip_sha256" ]] ||
        release_die 75 "GeoIP candidate fingerprint changed"
    release_stop_backend_fail_closed compose_command
    previous_staging="$(dirname -- "$INPLACEX_GEOIP_DB_PATH")/.geoip-$deployment_id.previous.mmdb"
    cp -- "$backup_path" "$previous_staging"
    chmod 0644 "$previous_staging"
    mv -f -- "$previous_staging" "${INPLACEX_GEOIP_DB_PATH}.previous"
    mv -- "$candidate_path" "$INPLACEX_GEOIP_DB_PATH"
    release_sync_file_and_parent "$INPLACEX_GEOIP_DB_PATH"
elif [[ "$current_geoip_sha256" != "$new_geoip_sha256" ]]; then
    release_die 75 "Active GeoIP artifact differs from both transaction fingerprints"
fi
validate_geoip_path "$INPLACEX_GEOIP_DB_PATH"
write_journal geoip_installed
release_fault_inject geoip_after_install

release_start_activation_lease \
    "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" \
    "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
    "$new_geoip_sha256" "$runtime_config_sha256"
compose_command up --detach --force-recreate --wait \
    --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
release_fault_inject geoip_after_start
"$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
    "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST"
[[ "$(release_database_system_identifier compose_command)" == "$database_system_identifier" ]] ||
    release_die 75 "PostgreSQL system identifier changed during GeoIP activation"
release_write_activation_record \
    "$RELEASE_VERIFIED_ACTIVATION_FILE" "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST" \
    "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
    "$new_geoip_sha256" "$runtime_config_sha256"
geoip_activated=true
release_stop_activation_lease
release_fault_inject geoip_after_verified_activation
write_journal activation_committed
release_fault_inject geoip_after_activation
release_disable_drain
release_disable_maintenance
maintenance_active=false
release_fault_inject geoip_after_gates
remove_geoip_artifact "$backup_path"
release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
rotation_succeeded=true

echo "Rotated and durably activated GeoIP artifact $new_geoip_sha256."
