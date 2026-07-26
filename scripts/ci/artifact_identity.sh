#!/usr/bin/env bash

set -euo pipefail

usage() {
    cat <<'EOF'
Usage: artifact_identity.sh --apk PATH [--output-dir PATH] [--artifact-type debug|release]

Copies an APK into an artifact directory and writes a manifest and checksum
that bind the artifact to the source version and commit.
EOF
}

find_apksigner() {
    if [[ -n "${APKSIGNER:-}" ]]; then
        [[ -x "$APKSIGNER" ]] || die "APKSIGNER is not executable: $APKSIGNER"
        printf '%s\n' "$APKSIGNER"
        return
    fi
    if command -v apksigner >/dev/null 2>&1; then
        command -v apksigner
        return
    fi
    local sdk_root candidate
    for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || continue
        candidate=$(find "$sdk_root/build-tools" -type f -name apksigner -perm -u+x | sort -V | tail -n 1)
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    die 'apksigner is required to derive APK signing status'
}

die() {
    printf 'artifact_identity: %s\n' "$1" >&2
    exit 1
}

apk_path=''
output_dir='build/ci-artifacts'
artifact_type='debug'

while (($# > 0)); do
    case "$1" in
        --apk)
            (($# >= 2)) || die "--apk requires a path"
            apk_path=$2
            shift 2
            ;;
        --output-dir)
            (($# >= 2)) || die "--output-dir requires a path"
            output_dir=$2
            shift 2
            ;;
        --artifact-type)
            (($# >= 2)) || die "--artifact-type requires a value"
            artifact_type=$2
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
    esac
done

[[ -n "$apk_path" ]] || die "--apk is required"
[[ "$artifact_type" == 'debug' || "$artifact_type" == 'release' ]] || die "--artifact-type must be debug or release"

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && git rev-parse --show-toplevel)

resolve_path() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *) printf '%s/%s\n' "$repo_root" "$1" ;;
    esac
}

apk_path=$(resolve_path "$apk_path")
output_dir=$(resolve_path "$output_dir")
[[ -f "$apk_path" ]] || die "APK does not exist: $apk_path"
apksigner_path=$(find_apksigner)

version_file="$repo_root/InplaceX-android/app/build.gradle.kts"
[[ -f "$version_file" ]] || die "version source does not exist: $version_file"

version=$(awk '
    /^[[:space:]]*versionName[[:space:]]*=/ {
        value = $3
        gsub(/"/, "", value)
        print value
        exit
    }
' "$version_file")
version_code=$(awk '
    /^[[:space:]]*versionCode[[:space:]]*=/ {
        print $3
        exit
    }
' "$version_file")
[[ "$version" =~ ^[[:alnum:]][[:alnum:]._-]*$ ]] || die "unsupported version value: $version"
[[ "$version_code" =~ ^[0-9]+$ ]] || die "unsupported versionCode value: $version_code"

commit=${GITHUB_SHA:-$(git -C "$repo_root" rev-parse HEAD)}
[[ "$commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "commit must be a full SHA-1: $commit"
commit=$(printf '%s' "$commit" | tr '[:upper:]' '[:lower:]')
short_commit=${commit:0:12}

if command -v sha256sum >/dev/null 2>&1; then
    sha256=$(sha256sum "$apk_path" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
    sha256=$(shasum -a 256 "$apk_path" | awk '{print $1}')
else
    die 'sha256sum or shasum is required'
fi

artifact_name="inplacex-${artifact_type}-${version}-${short_commit}.apk"
manifest_name="${artifact_name%.apk}.json"
checksum_name="${artifact_name}.sha256"
mkdir -p "$output_dir"
cp "$apk_path" "$output_dir/$artifact_name"

if "$apksigner_path" verify --verbose --print-certs "$apk_path" >"$output_dir/${artifact_name}.apksigner.txt" 2>&1; then
    signing_status='verified'
else
    signing_status='unverified'
fi

cat > "$output_dir/$manifest_name" <<EOF
{
  "artifact": "$artifact_name",
  "version": "$version",
  "version_code": $version_code,
  "commit": "$commit",
  "sha256": "$sha256",
  "sha256_algorithm": "SHA-256",
  "signing_status": "$signing_status",
  "signing_verifier": "apksigner"
}
EOF
printf '%s  %s\n' "$sha256" "$artifact_name" > "$output_dir/$checksum_name"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        printf 'artifact_name=%s\n' "$artifact_name"
        printf 'manifest=%s\n' "$manifest_name"
        printf 'sha256=%s\n' "$sha256"
        printf 'version=%s\n' "$version"
        printf 'commit=%s\n' "$commit"
        printf 'signing_status=%s\n' "$signing_status"
    } >> "$GITHUB_OUTPUT"
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        printf '%s\n' '### Debug artifact identity'
        printf '%s\n' ''
        printf '%s\n' "- Version: \`$version\`"
        printf '%s\n' "- Commit: \`$commit\`"
        printf '%s\n' "- SHA-256: \`$sha256\`"
        printf '%s\n' "- Artifact: \`$artifact_name\`"
        printf '%s\n' "- APK signing status: \`$signing_status\` (derived by apksigner)"
    } >> "$GITHUB_STEP_SUMMARY"
fi

printf 'artifact=%s version=%s commit=%s sha256=%s signing_status=%s\n' "$artifact_name" "$version" "$commit" "$sha256" "$signing_status"
