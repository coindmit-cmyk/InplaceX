#!/usr/bin/env bash
# shellcheck disable=SC2317

# This file is sourced before a production entrypoint performs any external
# command or durable mutation. Keep this bootstrap limited to Bash builtins.

if [[ -n "${BASH_ENV+x}" || -n "${ENV+x}" ]]; then
    builtin printf '%s\n' 'BASH_ENV and ENV are forbidden for production entrypoints.' >&2
    return 77 2>/dev/null || exit 77
fi

for hostile_environment_name in \
    GCONV_PATH LD_AUDIT LD_DEBUG LD_LIBRARY_PATH LD_PRELOAD LOCPATH NLSPATH; do
    if [[ -n "${!hostile_environment_name+x}" ]]; then
        builtin printf 'Dynamic-loader/locale override is forbidden: %s\n' \
            "$hostile_environment_name" >&2
        return 77 2>/dev/null || exit 77
    fi
done
unset hostile_environment_name

while builtin read -r _ _ imported_function; do
    [[ -z "$imported_function" ]] && continue
    builtin printf 'Imported shell functions are forbidden: %s\n' "$imported_function" >&2
    return 77 2>/dev/null || exit 77
done < <(builtin declare -F)

for environment_name in $(builtin compgen -e); do
    uppercase_environment_name="${environment_name^^}"
    case "$uppercase_environment_name" in
        BASH_FUNC_*)
            builtin printf 'Imported Bash function environment is forbidden: %s\n' \
                "$environment_name" >&2
            return 77 2>/dev/null || exit 77
            ;;
        GIT_*)
            builtin unset "$environment_name"
            ;;
    esac
done
unset environment_name uppercase_environment_name imported_function

for docker_environment_name in \
    DOCKER_HOST DOCKER_CONTEXT DOCKER_CONFIG DOCKER_CERT_PATH DOCKER_TLS_VERIFY \
    DOCKER_API_VERSION DOCKER_CLI_PLUGIN_EXTRA_DIRS DOCKER_DEFAULT_PLATFORM \
    BUILDX_BUILDER BUILDX_CONFIG BUILDKIT_HOST \
    EXPERIMENTAL_BUILDKIT_SOURCE_POLICY; do
    if [[ -n "${!docker_environment_name+x}" ]]; then
        builtin printf 'Ambient Docker selection is forbidden: %s\n' \
            "$docker_environment_name" >&2
        return 77 2>/dev/null || exit 77
    fi
done
unset docker_environment_name

PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH

readonly RELEASE_DEFAULT_DOCKER_BIN=/usr/bin/docker
readonly RELEASE_DEFAULT_DOCKER_SOCKET=/var/run/docker.sock
readonly RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci

if [[ -n "${INPLACEX_RELEASE_TEST_DOCKER_BIN:-}" ]]; then
    [[ "${INPLACEX_RELEASE_ISOLATED_CI_ACK:-}" == "$RELEASE_ISOLATED_CI_ACK" &&
        "$INPLACEX_RELEASE_TEST_DOCKER_BIN" == /* ]] || {
        builtin printf '%s\n' 'Test Docker override requires the isolated-CI acknowledgement.' >&2
        return 77 2>/dev/null || exit 77
    }
    RELEASE_DOCKER_BIN="$INPLACEX_RELEASE_TEST_DOCKER_BIN"
    RELEASE_DOCKER_SOCKET=""
else
    RELEASE_DOCKER_BIN="$RELEASE_DEFAULT_DOCKER_BIN"
    RELEASE_DOCKER_SOCKET="$RELEASE_DEFAULT_DOCKER_SOCKET"
fi
readonly RELEASE_DOCKER_BIN RELEASE_DOCKER_SOCKET
export RELEASE_DOCKER_BIN RELEASE_DOCKER_SOCKET
readonly RELEASE_DOCKER_HOST="unix://$RELEASE_DEFAULT_DOCKER_SOCKET"
export RELEASE_DOCKER_HOST
unset DOCKER_HOST
unset DOCKER_CONTEXT DOCKER_CONFIG DOCKER_CERT_PATH DOCKER_TLS_VERIFY
unset DOCKER_API_VERSION DOCKER_CLI_PLUGIN_EXTRA_DIRS DOCKER_DEFAULT_PLATFORM
unset BUILDX_BUILDER BUILDX_CONFIG BUILDKIT_HOST EXPERIMENTAL_BUILDKIT_SOURCE_POLICY
