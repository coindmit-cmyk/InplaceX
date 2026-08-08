#!/usr/bin/bash -p
# shellcheck disable=SC2016,SC2034
set -euo pipefail
umask 077

script_directory="$(builtin cd -- "${BASH_SOURCE[0]%/*}" && builtin pwd -P)"
readonly script_directory
# shellcheck source=ops/production/release-shell-bootstrap.sh
builtin source "$script_directory/release-shell-bootstrap.sh"

if [[ $# -ne 3 || "$3" != "--confirm-data-restore" ]]; then
    echo "Usage: sudo $0 <absolute-env-file> <absolute-release-receipt> --confirm-data-restore" >&2
    echo "Rollback restores the receipt-bound database after a durable emergency backup." >&2
    exit 64
fi
[[ ${EUID:-$(id -u)} -eq 0 ]] || {
    echo "Production rollback must run as root." >&2
    exit 77
}

readonly env_file="$1"
readonly receipt_file="$2"
readonly compose_file="$script_directory/compose.yaml"
readonly smoke_script="$script_directory/smoke-backend.sh"
# shellcheck source=ops/production/release-common.sh
source "$script_directory/release-common.sh"

release_require_commands \
    awk cmp curl date find flock grep install mktemp nginx openssl python3 sed \
    sha256sum sleep sort stat sync
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
declare -Ar receipt_allowlist=(
    [ROLLBACK_RECEIPT_VERSION]=1
    [ROLLBACK_DEPLOYMENT_ID]=1
    [ROLLBACK_COMPOSE_PROJECT_NAME]=1
    [ROLLBACK_POSTGRES_IMAGE]=1
    [ROLLBACK_POSTGRES_DB]=1
    [ROLLBACK_POSTGRES_USER]=1
    [ROLLBACK_POSTGRES_VOLUME]=1
    [ROLLBACK_DATABASE_SYSTEM_IDENTIFIER]=1
    [ROLLBACK_BACKEND_LOOPBACK_PORT]=1
    [ROLLBACK_BACKUP_PATH]=1
    [ROLLBACK_BACKUP_SHA256]=1
    [ROLLBACK_DATABASE_PASSWORD_SHA256]=1
    [ROLLBACK_STATE_KEY_SHA256]=1
    [ROLLBACK_PUBLIC_KEY_SHA256]=1
    [ROLLBACK_GEOIP_SHA256]=1
    [ROLLBACK_RUNTIME_CONFIG_SHA256]=1
    [ROLLBACK_CANDIDATE_IMAGE]=1
    [ROLLBACK_CANDIDATE_RELEASE_ID]=1
    [ROLLBACK_CANDIDATE_GIT_SHA]=1
    [ROLLBACK_CANDIDATE_IMAGE_DIGEST]=1
    [ROLLBACK_CANDIDATE_SOURCE_ARCHIVE_SHA256]=1
    [ROLLBACK_PREVIOUS_EXISTS]=1
    [ROLLBACK_PREVIOUS_ACTIVATION_VERSION]=1
    [ROLLBACK_PREVIOUS_IMAGE]=1
    [ROLLBACK_PREVIOUS_RELEASE_ID]=1
    [ROLLBACK_PREVIOUS_GIT_SHA]=1
    [ROLLBACK_PREVIOUS_IMAGE_DIGEST]=1
    [ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256]=1
)
declare -Ar pointer_allowlist=(
    [RELEASE_POINTER_VERSION]=1
    [RELEASE_POINTER_STATE]=1
    [RELEASE_POINTER_DEPLOYMENT_ID]=1
    [RELEASE_POINTER_RECEIPT_PATH]=1
)

environment_fd=""
receipt_fd=""
release_open_root_config "$env_file" environment_fd
release_parse_allowed_kv_fd "$environment_fd" environment_allowlist
[[ -z "${INPLACEX_RUNTIME_CONFIG_SHA256+x}" ]] ||
    release_die 65 "INPLACEX_RUNTIME_CONFIG_SHA256 is calculated by rollback and must not be supplied"
release_validate_env_values
release_prepare_state_directories
if release_load_verified_activation && [[ "$VERIFIED_VERSION" == "1" ]]; then
    release_die 75 "Verified activation v1 must first be migrated by deploy-backend.sh with INPLACEX_ACTIVATION_V1_MIGRATION_ACK=$RELEASE_ACTIVATION_V1_MIGRATION_ACK"
fi
release_open_root_config "$receipt_file" receipt_fd
release_parse_allowed_kv_fd "$receipt_fd" receipt_allowlist
release_require_variables \
    ROLLBACK_RECEIPT_VERSION ROLLBACK_DEPLOYMENT_ID ROLLBACK_COMPOSE_PROJECT_NAME \
    ROLLBACK_POSTGRES_IMAGE ROLLBACK_POSTGRES_DB ROLLBACK_POSTGRES_USER \
    ROLLBACK_POSTGRES_VOLUME ROLLBACK_DATABASE_SYSTEM_IDENTIFIER \
    ROLLBACK_BACKEND_LOOPBACK_PORT ROLLBACK_BACKUP_PATH ROLLBACK_BACKUP_SHA256 \
    ROLLBACK_DATABASE_PASSWORD_SHA256 \
    ROLLBACK_STATE_KEY_SHA256 ROLLBACK_PUBLIC_KEY_SHA256 ROLLBACK_GEOIP_SHA256 \
    ROLLBACK_RUNTIME_CONFIG_SHA256 ROLLBACK_CANDIDATE_IMAGE ROLLBACK_CANDIDATE_RELEASE_ID \
    ROLLBACK_CANDIDATE_GIT_SHA ROLLBACK_CANDIDATE_IMAGE_DIGEST \
    ROLLBACK_CANDIDATE_SOURCE_ARCHIVE_SHA256 ROLLBACK_PREVIOUS_EXISTS \
    ROLLBACK_PREVIOUS_ACTIVATION_VERSION
[[ "$ROLLBACK_RECEIPT_VERSION" == "3" ]] || release_die 65 "Unsupported rollback receipt version"
[[ "$ROLLBACK_DEPLOYMENT_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
    release_die 65 "Rollback deployment ID is invalid"
[[ "$ROLLBACK_DATABASE_SYSTEM_IDENTIFIER" =~ ^[0-9]+$ ]] ||
    release_die 65 "Rollback database system identifier is invalid"
for checksum in \
    "$ROLLBACK_BACKUP_SHA256" "$ROLLBACK_DATABASE_PASSWORD_SHA256" \
    "$ROLLBACK_STATE_KEY_SHA256" "$ROLLBACK_PUBLIC_KEY_SHA256" \
    "$ROLLBACK_GEOIP_SHA256" "$ROLLBACK_RUNTIME_CONFIG_SHA256"; do
    [[ "$checksum" =~ ^[0-9a-f]{64}$ ]] || release_die 65 "Rollback checksum is invalid"
done
[[ "$ROLLBACK_PREVIOUS_EXISTS" == "true" ]] ||
    release_die 75 "This deployment has no previous release to restore"
[[ "$ROLLBACK_PREVIOUS_ACTIVATION_VERSION" == "1" || "$ROLLBACK_PREVIOUS_ACTIVATION_VERSION" == "2" ]] ||
    release_die 65 "Rollback previous activation version is invalid"
[[ "$ROLLBACK_PREVIOUS_ACTIVATION_VERSION" == "2" ]] ||
    release_die 75 "Rollback across the activation v1-to-v2 migration boundary is unsafe; keep activation v2 and deploy a v2-compatible recovery release"
release_validate_canonical_absolute_path "$receipt_file" "Rollback receipt"
release_validate_canonical_absolute_path "$ROLLBACK_BACKUP_PATH" "Rollback backup"
release_require_variables \
    ROLLBACK_PREVIOUS_IMAGE ROLLBACK_PREVIOUS_RELEASE_ID ROLLBACK_PREVIOUS_GIT_SHA \
    ROLLBACK_PREVIOUS_IMAGE_DIGEST ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256
release_validate_image_reference "$ROLLBACK_CANDIDATE_IMAGE"
release_validate_image_reference "$ROLLBACK_PREVIOUS_IMAGE"
[[ "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" == "${ROLLBACK_CANDIDATE_IMAGE##*@}" &&
    "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" == "${ROLLBACK_PREVIOUS_IMAGE##*@}" ]] ||
    release_die 65 "Rollback receipt image digest is inconsistent"
[[ "$ROLLBACK_CANDIDATE_RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ &&
    "$ROLLBACK_PREVIOUS_RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] ||
    release_die 65 "Rollback release identity is invalid"
[[ "$ROLLBACK_CANDIDATE_GIT_SHA" =~ ^[0-9a-f]{40}$ &&
    "$ROLLBACK_PREVIOUS_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] ||
    release_die 65 "Rollback Git identity is invalid"
[[ "$ROLLBACK_CANDIDATE_SOURCE_ARCHIVE_SHA256" =~ ^[0-9a-f]{64}$ &&
    "$ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
    release_die 65 "Rollback source provenance is invalid"

[[ "$COMPOSE_PROJECT_NAME" == "$ROLLBACK_COMPOSE_PROJECT_NAME" &&
    "$INPLACEX_POSTGRES_IMAGE" == "$ROLLBACK_POSTGRES_IMAGE" &&
    "$INPLACEX_POSTGRES_DB" == "$ROLLBACK_POSTGRES_DB" &&
    "$INPLACEX_POSTGRES_USER" == "$ROLLBACK_POSTGRES_USER" &&
    "$INPLACEX_POSTGRES_VOLUME" == "$ROLLBACK_POSTGRES_VOLUME" &&
    "$INPLACEX_BACKEND_LOOPBACK_PORT" == "$ROLLBACK_BACKEND_LOOPBACK_PORT" &&
    "$INPLACEX_BACKEND_IMAGE" == "$ROLLBACK_CANDIDATE_IMAGE" &&
    "$INPLACEX_RELEASE_ID" == "$ROLLBACK_CANDIDATE_RELEASE_ID" &&
    "$INPLACEX_GIT_SHA" == "$ROLLBACK_CANDIDATE_GIT_SHA" &&
    "$INPLACEX_IMAGE_DIGEST" == "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" &&
    "$INPLACEX_SOURCE_ARCHIVE_SHA256" == "$ROLLBACK_CANDIDATE_SOURCE_ARCHIVE_SHA256" ]] ||
    release_die 75 "Rollback environment differs from the receipt-bound candidate"

backup_directory="$(dirname -- "$ROLLBACK_BACKUP_PATH")"
readonly backup_directory
[[ "$receipt_file" == "$backup_directory/"* ]] ||
    release_die 75 "Receipt and backup must reside in the same protected directory"
release_validate_backup_directory "$backup_directory"
release_validate_backup_file "$ROLLBACK_BACKUP_PATH"
[[ "$(release_sha256_file "$ROLLBACK_BACKUP_PATH")" == "$ROLLBACK_BACKUP_SHA256" ]] ||
    release_die 75 "Rollback backup checksum does not match the receipt"
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
geoip_sha256="$(release_sha256_file "$INPLACEX_GEOIP_DB_PATH")"
runtime_config_sha256="$(release_runtime_config_fingerprint)"
readonly database_password_sha256 state_key_sha256 public_key_sha256 geoip_sha256 runtime_config_sha256
[[ "$database_password_sha256" == "$ROLLBACK_DATABASE_PASSWORD_SHA256" &&
    "$state_key_sha256" == "$ROLLBACK_STATE_KEY_SHA256" &&
    "$public_key_sha256" == "$ROLLBACK_PUBLIC_KEY_SHA256" &&
    "$runtime_config_sha256" == "$ROLLBACK_RUNTIME_CONFIG_SHA256" ]] ||
    release_die 75 "Secrets or runtime config differ from the rollback receipt"
export INPLACEX_RUNTIME_CONFIG_SHA256="$runtime_config_sha256"
release_assert_secret_continuity "$database_password_sha256" "$state_key_sha256" "$public_key_sha256"
[[ -z "${INPLACEX_ACTIVATION_V1_MIGRATION_ACK:-}" ]] ||
    release_die 75 "Activation v1 migration acknowledgement is deploy-only; finish migration with deploy-backend.sh or remove it after v2 activation"

readonly latest_pointer="$backup_directory/latest-inplacex-backend-release.env"
pointer_fd=""
release_open_root_config "$latest_pointer" pointer_fd
release_parse_allowed_kv_fd "$pointer_fd" pointer_allowlist
[[ "$RELEASE_POINTER_VERSION" == "2" &&
    ( "$RELEASE_POINTER_STATE" == "active" || "$RELEASE_POINTER_STATE" == "rolled_back" ) ]] ||
    release_die 75 "Rollback pointer state is invalid"
[[ "$RELEASE_POINTER_DEPLOYMENT_ID" == "$ROLLBACK_DEPLOYMENT_ID" &&
    "$RELEASE_POINTER_RECEIPT_PATH" == "$receipt_file" ]] ||
    release_die 75 "Rollback pointer does not select this exact receipt"

declare -Ar journal_allowlist=(
    [RELEASE_TRANSACTION_VERSION]=1
    [RELEASE_TRANSACTION_OPERATION]=1
    [RELEASE_TRANSACTION_PHASE]=1
    [RELEASE_TRANSACTION_DEPLOYMENT_ID]=1
    [RELEASE_TRANSACTION_RECEIPT_PATH]=1
    [RELEASE_TRANSACTION_EMERGENCY_BACKUP_PATH]=1
    [RELEASE_TRANSACTION_EMERGENCY_BACKUP_SHA256]=1
    [RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256]=1
    [RELEASE_TRANSACTION_STATE_KEY_SHA256]=1
    [RELEASE_TRANSACTION_PUBLIC_KEY_SHA256]=1
    [RELEASE_TRANSACTION_GEOIP_SHA256]=1
    [RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256]=1
)
readonly emergency_backup="$backup_directory/$ROLLBACK_DEPLOYMENT_ID.pre-manual-rollback.dump"
readonly emergency_backup_staging="$emergency_backup.partial"
emergency_backup_sha256=""
transaction_phase=""

write_journal() {
    local phase="$1"
    release_atomic_kv_file "$RELEASE_TRANSACTION_JOURNAL" \
        "RELEASE_TRANSACTION_VERSION=1" \
        "RELEASE_TRANSACTION_OPERATION=rollback" \
        "RELEASE_TRANSACTION_PHASE=$phase" \
        "RELEASE_TRANSACTION_DEPLOYMENT_ID=$ROLLBACK_DEPLOYMENT_ID" \
        "RELEASE_TRANSACTION_RECEIPT_PATH=$receipt_file" \
        "RELEASE_TRANSACTION_EMERGENCY_BACKUP_PATH=$emergency_backup" \
        "RELEASE_TRANSACTION_EMERGENCY_BACKUP_SHA256=$emergency_backup_sha256" \
        "RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256=$database_password_sha256" \
        "RELEASE_TRANSACTION_STATE_KEY_SHA256=$state_key_sha256" \
        "RELEASE_TRANSACTION_PUBLIC_KEY_SHA256=$public_key_sha256" \
        "RELEASE_TRANSACTION_GEOIP_SHA256=$geoip_sha256" \
        "RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256=$runtime_config_sha256"
    transaction_phase="$phase"
}

transaction_resumed=false
if [[ -e "$RELEASE_TRANSACTION_JOURNAL" || -L "$RELEASE_TRANSACTION_JOURNAL" ]]; then
    [[ -f "$RELEASE_TRANSACTION_JOURNAL" && ! -L "$RELEASE_TRANSACTION_JOURNAL" ]] ||
        release_die 66 "Pending release transaction path is unsafe"
    journal_fd=""
    release_open_root_config "$RELEASE_TRANSACTION_JOURNAL" journal_fd
    release_parse_allowed_kv_fd "$journal_fd" journal_allowlist
    release_require_variables \
        RELEASE_TRANSACTION_VERSION RELEASE_TRANSACTION_OPERATION RELEASE_TRANSACTION_PHASE \
        RELEASE_TRANSACTION_DEPLOYMENT_ID RELEASE_TRANSACTION_RECEIPT_PATH \
        RELEASE_TRANSACTION_EMERGENCY_BACKUP_PATH RELEASE_TRANSACTION_STATE_KEY_SHA256 \
        RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256 \
        RELEASE_TRANSACTION_PUBLIC_KEY_SHA256 RELEASE_TRANSACTION_GEOIP_SHA256 \
        RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256
    [[ "$RELEASE_TRANSACTION_VERSION" == "1" && "$RELEASE_TRANSACTION_OPERATION" == "rollback" ]] ||
        release_die 75 "A different or unsupported release transaction is pending"
    [[ "$RELEASE_TRANSACTION_PHASE" =~ ^(intent|activation_revoked|emergency_backup_ready|database_restored|previous_starting|activation_committed)$ ]] ||
        release_die 65 "Pending rollback transaction phase is invalid"
    [[ "$RELEASE_TRANSACTION_DEPLOYMENT_ID" == "$ROLLBACK_DEPLOYMENT_ID" &&
        "$RELEASE_TRANSACTION_RECEIPT_PATH" == "$receipt_file" &&
        "$RELEASE_TRANSACTION_EMERGENCY_BACKUP_PATH" == "$emergency_backup" ]] ||
        release_die 75 "Pending rollback does not match the requested receipt"
    [[ "$RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
        "$RELEASE_TRANSACTION_STATE_KEY_SHA256" == "$state_key_sha256" &&
        "$RELEASE_TRANSACTION_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
        "$RELEASE_TRANSACTION_GEOIP_SHA256" == "$geoip_sha256" &&
        "$RELEASE_TRANSACTION_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
        release_die 75 "Runtime inputs changed during the pending rollback"
    transaction_phase="$RELEASE_TRANSACTION_PHASE"
    emergency_backup_sha256="$RELEASE_TRANSACTION_EMERGENCY_BACKUP_SHA256"
    [[ -z "$emergency_backup_sha256" || "$emergency_backup_sha256" =~ ^[0-9a-f]{64}$ ]] ||
        release_die 65 "Pending emergency backup checksum is invalid"
    transaction_resumed=true
else
    if [[ "$RELEASE_POINTER_STATE" == "active" ]]; then
        release_load_verified_activation || release_die 75 "Candidate lacks a durable verified activation"
        [[ "$VERIFIED_RELEASE_ID" == "$ROLLBACK_CANDIDATE_RELEASE_ID" &&
            "$VERIFIED_GIT_SHA" == "$ROLLBACK_CANDIDATE_GIT_SHA" &&
            "$VERIFIED_IMAGE_DIGEST" == "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" &&
            "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
            "$VERIFIED_STATE_KEY_SHA256" == "$state_key_sha256" &&
            "$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
            "$VERIFIED_GEOIP_SHA256" == "$geoip_sha256" &&
            "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
            release_die 75 "Durable activation does not match the rollback candidate"
        [[ ! -e "$emergency_backup" && ! -L "$emergency_backup" &&
            ! -e "$emergency_backup_staging" && ! -L "$emergency_backup_staging" ]] ||
            release_die 73 "Emergency rollback backup already exists"
        write_journal intent
        release_fault_inject rollback_after_intent
    elif [[ "$RELEASE_POINTER_STATE" != "rolled_back" ]]; then
        release_die 75 "The one-shot rollback receipt has an unsupported state"
    fi
fi

readonly sanitized_env="$RELEASE_RUNTIME_DIRECTORY/rollback-$ROLLBACK_DEPLOYMENT_ID.env"
release_write_sanitized_env "$sanitized_env" environment_allowlist
compose=(
    release_docker compose
    --env-file "$sanitized_env"
    --project-directory "$script_directory/../.."
    -f "$compose_file"
)
compose_command() { "${compose[@]}" "$@"; }
compose_command config --quiet

release_verify_pulled_image "$ROLLBACK_POSTGRES_IMAGE"
release_verify_pulled_image \
    "$ROLLBACK_CANDIDATE_IMAGE" "$ROLLBACK_CANDIDATE_RELEASE_ID" "$ROLLBACK_CANDIDATE_GIT_SHA" \
    "$ROLLBACK_CANDIDATE_SOURCE_ARCHIVE_SHA256"
release_verify_pulled_image \
    "$ROLLBACK_PREVIOUS_IMAGE" "$ROLLBACK_PREVIOUS_RELEASE_ID" "$ROLLBACK_PREVIOUS_GIT_SHA" \
    "$ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256"

current_backend_container="$(compose_command ps --all -q backend 2>/dev/null || true)"
current_postgres_container="$(compose_command ps --all -q postgres 2>/dev/null || true)"
release_validate_container_id "$current_backend_container" backend
release_validate_container_id "$current_postgres_container" postgres
[[ -n "$current_postgres_container" ]] || release_die 75 "Rollback PostgreSQL container is missing"
release_validate_postgres_container "$current_postgres_container"
compose_command up --detach --wait \
    --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" postgres
[[ "$(release_database_system_identifier compose_command)" == "$ROLLBACK_DATABASE_SYSTEM_IDENTIFIER" ]] ||
    release_die 75 "PostgreSQL system identifier differs from the receipt"

if [[ "$transaction_resumed" == "false" && "$RELEASE_POINTER_STATE" == "rolled_back" ]]; then
    release_load_verified_activation || release_die 75 "Completed rollback activation state is missing"
    [[ "$VERIFIED_RELEASE_ID" == "$ROLLBACK_PREVIOUS_RELEASE_ID" &&
        "$VERIFIED_GIT_SHA" == "$ROLLBACK_PREVIOUS_GIT_SHA" &&
        "$VERIFIED_IMAGE_DIGEST" == "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" &&
        "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
        "$VERIFIED_STATE_KEY_SHA256" == "$state_key_sha256" &&
        "$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
        "$VERIFIED_GEOIP_SHA256" == "$geoip_sha256" &&
        "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
        release_die 75 "Completed rollback activation identity is inconsistent"
    export INPLACEX_BACKEND_IMAGE="$ROLLBACK_PREVIOUS_IMAGE"
    export INPLACEX_RELEASE_ID="$ROLLBACK_PREVIOUS_RELEASE_ID"
    export INPLACEX_GIT_SHA="$ROLLBACK_PREVIOUS_GIT_SHA"
    export INPLACEX_IMAGE_DIGEST="$ROLLBACK_PREVIOUS_IMAGE_DIGEST"
    export INPLACEX_SOURCE_ARCHIVE_SHA256="$ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256"
    export INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=""
    rm -f -- "$sanitized_env"
    release_write_sanitized_env "$sanitized_env" environment_allowlist
    compose_command up --detach --force-recreate --wait \
        --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
    current_backend_container="$(compose_command ps -q backend)"
    release_validate_container_id "$current_backend_container" backend
    [[ -n "$current_backend_container" ]] || release_die 70 "Completed rollback backend did not start"
    release_validate_backend_port "$current_backend_container"
    release_validate_runtime_secret_reads "$current_backend_container" \
        /run/secrets/inplacex_database_password \
        /run/secrets/inplacex_online_public_key \
        /run/secrets/inplacex_online_state_key
    running_release_id="$(release_inspect_environment "$current_backend_container" INPLACEX_RELEASE_ID)"
    running_git_sha="$(release_inspect_environment "$current_backend_container" INPLACEX_GIT_SHA)"
    running_image_digest="$(release_inspect_environment "$current_backend_container" INPLACEX_IMAGE_DIGEST)"
    [[ "$running_release_id" == "$ROLLBACK_PREVIOUS_RELEASE_ID" &&
        "$running_git_sha" == "$ROLLBACK_PREVIOUS_GIT_SHA" &&
        "$running_image_digest" == "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" ]] ||
        release_die 75 "Running backend differs from the completed rollback"
    "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "$ROLLBACK_PREVIOUS_RELEASE_ID" "$ROLLBACK_PREVIOUS_GIT_SHA" "$ROLLBACK_PREVIOUS_IMAGE_DIGEST"
    release_disable_drain
    release_disable_maintenance
    rm -f -- "$sanitized_env"
    echo "Rollback to $ROLLBACK_PREVIOUS_RELEASE_ID was already durably finalized."
    exit 0
fi

if [[ "$transaction_phase" == "activation_committed" ]]; then
    release_load_verified_activation || release_die 75 "Committed rollback activation state is missing"
    [[ "$VERIFIED_RELEASE_ID" == "$ROLLBACK_PREVIOUS_RELEASE_ID" &&
        "$VERIFIED_GIT_SHA" == "$ROLLBACK_PREVIOUS_GIT_SHA" &&
        "$VERIFIED_IMAGE_DIGEST" == "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" &&
        "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
        "$VERIFIED_STATE_KEY_SHA256" == "$state_key_sha256" &&
        "$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
        "$VERIFIED_GEOIP_SHA256" == "$geoip_sha256" &&
        "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] ||
        release_die 75 "Committed rollback activation identity is inconsistent"
    export INPLACEX_BACKEND_IMAGE="$ROLLBACK_PREVIOUS_IMAGE"
    export INPLACEX_RELEASE_ID="$ROLLBACK_PREVIOUS_RELEASE_ID"
    export INPLACEX_GIT_SHA="$ROLLBACK_PREVIOUS_GIT_SHA"
    export INPLACEX_IMAGE_DIGEST="$ROLLBACK_PREVIOUS_IMAGE_DIGEST"
    export INPLACEX_SOURCE_ARCHIVE_SHA256="$ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256"
    export INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=""
    rm -f -- "$sanitized_env"
    release_write_sanitized_env "$sanitized_env" environment_allowlist
    compose_command up --detach --wait \
        --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
    current_backend_container="$(compose_command ps -q backend)"
    release_validate_container_id "$current_backend_container" backend
    [[ -n "$current_backend_container" ]] || release_die 70 "Committed rollback backend did not start"
    release_validate_backend_port "$current_backend_container"
    running_release_id="$(release_inspect_environment "$current_backend_container" INPLACEX_RELEASE_ID)"
    running_git_sha="$(release_inspect_environment "$current_backend_container" INPLACEX_GIT_SHA)"
    running_image_digest="$(release_inspect_environment "$current_backend_container" INPLACEX_IMAGE_DIGEST)"
    [[ "$running_release_id" == "$ROLLBACK_PREVIOUS_RELEASE_ID" &&
        "$running_git_sha" == "$ROLLBACK_PREVIOUS_GIT_SHA" &&
        "$running_image_digest" == "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" ]] ||
        release_die 75 "Running backend differs from the committed rollback"
    "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "$ROLLBACK_PREVIOUS_RELEASE_ID" "$ROLLBACK_PREVIOUS_GIT_SHA" "$ROLLBACK_PREVIOUS_IMAGE_DIGEST"
    release_atomic_kv_file "$latest_pointer" \
        "RELEASE_POINTER_VERSION=2" \
        "RELEASE_POINTER_STATE=rolled_back" \
        "RELEASE_POINTER_DEPLOYMENT_ID=$ROLLBACK_DEPLOYMENT_ID" \
        "RELEASE_POINTER_RECEIPT_PATH=$receipt_file"
    release_disable_drain
    release_disable_maintenance
    release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
    rm -f -- "$sanitized_env"
    echo "Finalized previously verified rollback to $ROLLBACK_PREVIOUS_RELEASE_ID."
    exit 0
fi

maintenance_active=false
previous_activated=false
rollback_succeeded=false

restore_database() {
    local source_backup="$1"
    release_stop_backend_fail_closed compose_command || return 1
    compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname=postgres --variable=db="$POSTGRES_DB" --variable=owner="$POSTGRES_USER" <<'"'"'SQL'"'"'
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :'"'"'db'"'"' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS :"db";
CREATE DATABASE :"db" OWNER :"owner";
SQL'
    compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec pg_restore --exit-on-error --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
        < "$source_backup"
}

recover_candidate() {
    compose_command up --detach --wait --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" postgres || return 1
    [[ "$(release_database_system_identifier compose_command)" == "$ROLLBACK_DATABASE_SYSTEM_IDENTIFIER" ]] || return 1

    if [[ -z "$emergency_backup_sha256" ]]; then
        local running_backend running_release_id running_git_sha running_image_digest
        running_backend="$(compose_command ps -q backend 2>/dev/null || true)"
        running_release_id="$(release_inspect_environment "$running_backend" INPLACEX_RELEASE_ID)"
        running_git_sha="$(release_inspect_environment "$running_backend" INPLACEX_GIT_SHA)"
        running_image_digest="$(release_inspect_environment "$running_backend" INPLACEX_IMAGE_DIGEST)"
        if [[ -n "$running_backend" &&
            "$(release_docker inspect --format '{{.State.Running}}' "$running_backend" 2>/dev/null || true)" == "true" &&
            "$running_release_id" == "$ROLLBACK_CANDIDATE_RELEASE_ID" &&
            "$running_git_sha" == "$ROLLBACK_CANDIDATE_GIT_SHA" &&
            "$running_image_digest" == "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" ]]; then
            release_load_verified_activation || return 1
            [[ "$VERIFIED_RELEASE_ID" == "$ROLLBACK_CANDIDATE_RELEASE_ID" &&
                "$VERIFIED_GIT_SHA" == "$ROLLBACK_CANDIDATE_GIT_SHA" &&
                "$VERIFIED_IMAGE_DIGEST" == "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" &&
                "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$database_password_sha256" &&
                "$VERIFIED_STATE_KEY_SHA256" == "$state_key_sha256" &&
                "$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256" &&
                "$VERIFIED_GEOIP_SHA256" == "$geoip_sha256" &&
                "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$runtime_config_sha256" ]] || return 1
            "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
                "$ROLLBACK_CANDIDATE_RELEASE_ID" "$ROLLBACK_CANDIDATE_GIT_SHA" \
                "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" || return 1
            release_disable_drain
            release_disable_maintenance
            release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
            return 0
        fi
    fi

    release_stop_backend_fail_closed compose_command || return 1
    if [[ -n "$emergency_backup_sha256" ]]; then
        release_validate_backup_file "$emergency_backup" || return 1
        [[ "$(release_sha256_file "$emergency_backup")" == "$emergency_backup_sha256" ]] || return 1
        restore_database "$emergency_backup" || return 1
        [[ "$(release_database_system_identifier compose_command)" == "$ROLLBACK_DATABASE_SYSTEM_IDENTIFIER" ]] || return 1
    fi
    export INPLACEX_BACKEND_IMAGE="$ROLLBACK_CANDIDATE_IMAGE"
    export INPLACEX_RELEASE_ID="$ROLLBACK_CANDIDATE_RELEASE_ID"
    export INPLACEX_GIT_SHA="$ROLLBACK_CANDIDATE_GIT_SHA"
    export INPLACEX_IMAGE_DIGEST="$ROLLBACK_CANDIDATE_IMAGE_DIGEST"
    export INPLACEX_SOURCE_ARCHIVE_SHA256="$ROLLBACK_CANDIDATE_SOURCE_ARCHIVE_SHA256"
    rm -f -- "$sanitized_env"
    release_write_sanitized_env "$sanitized_env" environment_allowlist
    release_start_activation_lease \
        "$ROLLBACK_CANDIDATE_RELEASE_ID" "$ROLLBACK_CANDIDATE_GIT_SHA" "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" \
        "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
        "$geoip_sha256" "$runtime_config_sha256"
    compose_command up --detach --force-recreate --wait \
        --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend || return 1
    "$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "$ROLLBACK_CANDIDATE_RELEASE_ID" "$ROLLBACK_CANDIDATE_GIT_SHA" "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" || return 1
    release_write_activation_record \
        "$RELEASE_VERIFIED_ACTIVATION_FILE" "$ROLLBACK_CANDIDATE_RELEASE_ID" \
        "$ROLLBACK_CANDIDATE_GIT_SHA" "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" \
        "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
        "$geoip_sha256" "$runtime_config_sha256"
    release_stop_activation_lease
    if [[ -e "$emergency_backup_staging" || -L "$emergency_backup_staging" ]]; then
        release_validate_backup_file "$emergency_backup_staging" || return 1
        rm -f -- "$emergency_backup_staging"
        release_sync_directory "$backup_directory"
    fi
    release_disable_drain
    release_disable_maintenance
    release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
}

on_exit() {
    local status=$?
    trap - EXIT
    release_stop_activation_lease || true
    if [[ "$status" -ne 0 && "$maintenance_active" == "true" && "$previous_activated" != "true" ]]; then
        if [[ "$RELEASE_BACKEND_STOP_PROOF_FAILED" == "true" ]]; then
            echo "Backend stop was not proven; rollback gates and transaction journal remain without recovery mutation." >&2
        elif recover_candidate; then
            echo "Candidate database and runtime were restored from the durable rollback journal." >&2
        else
            if ! release_stop_backend_fail_closed compose_command; then
                echo "Backend stop could not be proven during failed rollback recovery." >&2
            fi
            echo "Emergency recovery failed; backend remains fail-closed with the rollback journal retained." >&2
        fi
    fi
    rm -f -- "$sanitized_env"
    [[ "$rollback_succeeded" == "true" ]] && exit 0
    exit "$status"
}
trap on_exit EXIT

release_enable_maintenance "$ROLLBACK_DEPLOYMENT_ID"
maintenance_active=true
drain_verified_backend=false
if release_running_backend_matches_verified_activation \
        "$current_backend_container" "$ROLLBACK_CANDIDATE_IMAGE" "$ROLLBACK_CANDIDATE_RELEASE_ID" \
        "$ROLLBACK_CANDIDATE_GIT_SHA" "$ROLLBACK_CANDIDATE_IMAGE_DIGEST" "$database_password_sha256" \
        "$state_key_sha256" "$public_key_sha256" "$geoip_sha256" "$runtime_config_sha256"; then
    drain_verified_backend=true
elif release_running_backend_matches_verified_activation \
    "$current_backend_container" "$ROLLBACK_PREVIOUS_IMAGE" "$ROLLBACK_PREVIOUS_RELEASE_ID" \
    "$ROLLBACK_PREVIOUS_GIT_SHA" "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" "$database_password_sha256" \
    "$state_key_sha256" "$public_key_sha256" "$geoip_sha256" "$runtime_config_sha256"; then
    drain_verified_backend=true
fi
if [[ "$drain_verified_backend" == "true" ]]; then
    release_enable_drain "$ROLLBACK_DEPLOYMENT_ID"
    release_wait_for_drain \
        "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" "$INPLACEX_DRAIN_TIMEOUT_SECONDS"
fi
release_stop_backend_fail_closed compose_command
if [[ -e "$RELEASE_VERIFIED_ACTIVATION_FILE" ]]; then
    release_remove_durable_file "$RELEASE_VERIFIED_ACTIVATION_FILE"
fi
write_journal activation_revoked
release_fault_inject rollback_after_activation_revoke

compose_command up --detach --wait \
    --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" postgres
[[ "$(release_database_system_identifier compose_command)" == "$ROLLBACK_DATABASE_SYSTEM_IDENTIFIER" ]] ||
    release_die 75 "PostgreSQL system identifier differs from the receipt"
compose_command exec -T postgres pg_restore --list < "$ROLLBACK_BACKUP_PATH" >/dev/null

if [[ -e "$emergency_backup" ]]; then
    release_validate_backup_file "$emergency_backup"
    if compose_command exec -T postgres pg_restore --list < "$emergency_backup" >/dev/null 2>&1; then
        actual_emergency_sha256="$(release_sha256_file "$emergency_backup")"
        [[ -z "$emergency_backup_sha256" || "$actual_emergency_sha256" == "$emergency_backup_sha256" ]] ||
            release_die 75 "Emergency rollback backup checksum changed"
        emergency_backup_sha256="$actual_emergency_sha256"
    elif [[ -z "$emergency_backup_sha256" ]]; then
        rm -f -- "$emergency_backup"
        release_sync_directory "$backup_directory"
    else
        release_die 75 "Journaled emergency rollback backup is not restorable"
    fi
fi
if [[ ! -e "$emergency_backup" ]]; then
    if [[ -e "$emergency_backup_staging" || -L "$emergency_backup_staging" ]]; then
        release_validate_backup_file "$emergency_backup_staging"
        rm -f -- "$emergency_backup_staging"
        release_sync_directory "$backup_directory"
    fi
    ( set -C; : > "$emergency_backup_staging" ) ||
        release_die 73 "Cannot create emergency rollback backup staging file safely"
    chmod 0600 "$emergency_backup_staging"
    release_fault_inject rollback_during_emergency_backup_staging
    release_stop_backend_fail_closed compose_command
    compose_command exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
        > "$emergency_backup_staging"
    release_validate_backup_file "$emergency_backup_staging"
    compose_command exec -T postgres pg_restore --list < "$emergency_backup_staging" >/dev/null
    release_sync_file_and_parent "$emergency_backup_staging"
    [[ ! -e "$emergency_backup" && ! -L "$emergency_backup" ]] ||
        release_die 73 "Emergency rollback backup appeared while staging"
    mv -- "$emergency_backup_staging" "$emergency_backup"
    release_sync_file_and_parent "$emergency_backup"
    emergency_backup_sha256="$(release_sha256_file "$emergency_backup")"
fi
write_journal emergency_backup_ready
release_fault_inject rollback_after_emergency_backup

restore_database "$ROLLBACK_BACKUP_PATH"
[[ "$(release_database_system_identifier compose_command)" == "$ROLLBACK_DATABASE_SYSTEM_IDENTIFIER" ]] ||
    release_die 75 "PostgreSQL system identifier changed during rollback"
write_journal database_restored
release_fault_inject rollback_after_database_restore

export INPLACEX_BACKEND_IMAGE="$ROLLBACK_PREVIOUS_IMAGE"
export INPLACEX_RELEASE_ID="$ROLLBACK_PREVIOUS_RELEASE_ID"
export INPLACEX_GIT_SHA="$ROLLBACK_PREVIOUS_GIT_SHA"
export INPLACEX_IMAGE_DIGEST="$ROLLBACK_PREVIOUS_IMAGE_DIGEST"
export INPLACEX_SOURCE_ARCHIVE_SHA256="$ROLLBACK_PREVIOUS_SOURCE_ARCHIVE_SHA256"
export INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=""
rm -f -- "$sanitized_env"
release_write_sanitized_env "$sanitized_env" environment_allowlist
release_start_activation_lease \
    "$ROLLBACK_PREVIOUS_RELEASE_ID" "$ROLLBACK_PREVIOUS_GIT_SHA" "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" \
    "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
    "$geoip_sha256" "$runtime_config_sha256"
write_journal previous_starting
compose_command up --detach --force-recreate --wait \
    --wait-timeout "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" backend
previous_container="$(compose_command ps -q backend)"
release_validate_container_id "$previous_container" backend
[[ -n "$previous_container" ]] || release_die 70 "Previous backend did not start"
release_validate_backend_port "$previous_container"
release_validate_runtime_secret_reads "$previous_container" \
    /run/secrets/inplacex_database_password \
    /run/secrets/inplacex_online_public_key \
    /run/secrets/inplacex_online_state_key
release_fault_inject rollback_after_previous_start
"$smoke_script" loopback "http://127.0.0.1:$INPLACEX_BACKEND_LOOPBACK_PORT" \
    "$ROLLBACK_PREVIOUS_RELEASE_ID" "$ROLLBACK_PREVIOUS_GIT_SHA" "$ROLLBACK_PREVIOUS_IMAGE_DIGEST"
release_write_activation_record \
    "$RELEASE_VERIFIED_ACTIVATION_FILE" "$ROLLBACK_PREVIOUS_RELEASE_ID" \
    "$ROLLBACK_PREVIOUS_GIT_SHA" "$ROLLBACK_PREVIOUS_IMAGE_DIGEST" \
    "$database_password_sha256" "$state_key_sha256" "$public_key_sha256" \
    "$geoip_sha256" "$runtime_config_sha256"
previous_activated=true
release_stop_activation_lease
release_fault_inject rollback_after_verified_activation
write_journal activation_committed
release_fault_inject rollback_after_activation

release_atomic_kv_file "$latest_pointer" \
    "RELEASE_POINTER_VERSION=2" \
    "RELEASE_POINTER_STATE=rolled_back" \
    "RELEASE_POINTER_DEPLOYMENT_ID=$ROLLBACK_DEPLOYMENT_ID" \
    "RELEASE_POINTER_RECEIPT_PATH=$receipt_file"
release_fault_inject rollback_after_pointer_committed
release_disable_drain
release_disable_maintenance
maintenance_active=false
release_fault_inject rollback_after_gates_removed
release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"
rollback_succeeded=true

echo "Rolled back and durably activated $ROLLBACK_PREVIOUS_RELEASE_ID."
echo "Emergency pre-rollback backup: $emergency_backup ($emergency_backup_sha256)"
