#!/usr/bin/env bash

set -euo pipefail

die() {
    printf 'artifact_identity: %s\n' "$1" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: artifact_identity.sh --apk PATH --artifact-type debug|release \
  --expected-signing verified|unverified [--output-dir PATH]
EOF
}

find_apksigner() {
    if [[ -n "${APKSIGNER:-}" ]]; then
        [[ -f "$APKSIGNER" ]] || die "APKSIGNER does not exist: $APKSIGNER"
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

apk_path=''
output_dir='build/ci-artifacts'
artifact_type=''
expected_signing=''

while (($# > 0)); do
    case "$1" in
        --apk)
            (($# >= 2)) || die '--apk requires a path'
            apk_path=$2
            shift 2
            ;;
        --output-dir)
            (($# >= 2)) || die '--output-dir requires a path'
            output_dir=$2
            shift 2
            ;;
        --artifact-type)
            (($# >= 2)) || die '--artifact-type requires a value'
            artifact_type=$2
            shift 2
            ;;
        --expected-signing)
            (($# >= 2)) || die '--expected-signing requires a value'
            expected_signing=$2
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

[[ -n "$apk_path" ]] || die '--apk is required'
[[ "$artifact_type" == 'debug' || "$artifact_type" == 'release' ]] || die '--artifact-type must be debug or release'
[[ "$expected_signing" == 'verified' || "$expected_signing" == 'unverified' ]] || die '--expected-signing must be verified or unverified'

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

head_commit=$(git -C "$repo_root" rev-parse HEAD | tr '[:upper:]' '[:lower:]')
commit=${GITHUB_SHA:-$head_commit}
[[ "$commit" =~ ^[0-9a-fA-F]{40}$ ]] || die "commit must be a full SHA-1: $commit"
commit=$(printf '%s' "$commit" | tr '[:upper:]' '[:lower:]')
[[ "$commit" == "$head_commit" ]] || die "GITHUB_SHA does not match checked-out HEAD: $commit != $head_commit"

version_file="$repo_root/InplaceX-android/app/build.gradle.kts"
[[ -f "$version_file" ]] || die "version source does not exist: $version_file"
version=$(awk '/^[[:space:]]*versionName[[:space:]]*=/ { value=$3; gsub(/"/, "", value); print value; exit }' "$version_file")
version_code=$(awk '/^[[:space:]]*versionCode[[:space:]]*=/ { print $3; exit }' "$version_file")
[[ "$version" =~ ^[[:alnum:]][[:alnum:]._-]*$ ]] || die "unsupported version value: $version"
[[ "$version_code" =~ ^[0-9]+$ ]] || die "unsupported versionCode value: $version_code"

if command -v sha256sum >/dev/null 2>&1; then
    sha256=$(sha256sum "$apk_path" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
    sha256=$(shasum -a 256 "$apk_path" | awk '{print $1}')
else
    die 'sha256sum or shasum is required'
fi

mkdir -p "$output_dir"
apksigner_path=$(find_apksigner)
signing_log="$output_dir/apksigner-${artifact_type}.txt"
if "$apksigner_path" verify --verbose --print-certs "$apk_path" >"$signing_log" 2>&1; then
    signing_status='verified'
else
    signing_status='unverified'
fi
[[ "$signing_status" == "$expected_signing" ]] || die "expected signing status $expected_signing, got $signing_status"

short_commit=${commit:0:12}
artifact_name="inplacex-${artifact_type}-${version}-${short_commit}.apk"
manifest_name="${artifact_name%.apk}.json"
checksum_name="${artifact_name}.sha256"
cp "$apk_path" "$output_dir/$artifact_name"
cat > "$output_dir/$manifest_name" <<EOF
{
  "artifact": "$artifact_name",
  "artifact_type": "$artifact_type",
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
        printf 'artifact_path=%s\n' "$output_dir/$artifact_name"
        printf 'manifest_path=%s\n' "$output_dir/$manifest_name"
        printf 'sha256=%s\n' "$sha256"
        printf 'version=%s\n' "$version"
        printf 'commit=%s\n' "$commit"
        printf 'signing_status=%s\n' "$signing_status"
    } >> "$GITHUB_OUTPUT"
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        printf '### %s artifact identity\n\n' "${artifact_type^}"
        printf '%s\n' "- Version: \`$version\`"
        printf '%s\n' "- Commit: \`$commit\`"
        printf '%s\n' "- SHA-256: \`$sha256\`"
        printf '%s\n' "- APK signing: \`$signing_status\` (apksigner)"
        printf '%s\n' "- Artifact: \`$artifact_name\`"
    } >> "$GITHUB_STEP_SUMMARY"
fi

printf 'artifact=%s version=%s commit=%s sha256=%s signing_status=%s\n' \
    "$artifact_name" "$version" "$commit" "$sha256" "$signing_status"
