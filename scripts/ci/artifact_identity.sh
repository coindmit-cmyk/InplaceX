#!/usr/bin/env bash

set -euo pipefail

die() {
    printf 'artifact_identity: %s\n' "$1" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: artifact_identity.sh --apk PATH --artifact-type debug|release \
  --expected-signing verified|unverified [--output-dir PATH] \
  [--expected-certificate-sha256 FINGERPRINT]
EOF
}

normalize_shell_path() {
    local value=$1
    if command -v cygpath >/dev/null 2>&1 && [[ "$value" =~ ^[A-Za-z]:[\\/].* ]]; then
        cygpath -u "$value"
    else
        printf '%s\n' "$value"
    fi
}

find_android_tool() {
    local override_name=$1
    local command_name=$2
    local override_value=${!override_name:-}
    if [[ -n "$override_value" ]]; then
        override_value=$(normalize_shell_path "$override_value")
        [[ -f "$override_value" ]] || die "$override_name does not exist"
        printf '%s\n' "$override_value"
        return
    fi
    if command -v "$command_name" >/dev/null 2>&1; then
        command -v "$command_name"
        return
    fi
    local sdk_root candidate
    for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        sdk_root=$(normalize_shell_path "$sdk_root")
        [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || continue
        candidate=$(find "$sdk_root/build-tools" -type f \
            \( -name "$command_name" -o -name "$command_name.exe" -o -name "$command_name.bat" \) \
            | sort -V | tail -n 1)
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    die "$command_name is required"
}

apk_path=''
output_dir='build/ci-artifacts'
artifact_type=''
expected_signing=''
expected_certificate_sha256=''

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
        --expected-certificate-sha256)
            (($# >= 2)) || die '--expected-certificate-sha256 requires a value'
            expected_certificate_sha256=$2
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
if [[ -n "$expected_certificate_sha256" ]]; then
    if [[ "$expected_certificate_sha256" =~ ^[0-9A-Fa-f]{64}$ ]]; then
        expected_certificate_digest=$expected_certificate_sha256
    elif [[ "$expected_certificate_sha256" =~ ^([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$ ]]; then
        expected_certificate_digest=${expected_certificate_sha256//:/}
    else
        die '--expected-certificate-sha256 must contain 64 hex digits, optionally separated by colons'
    fi
    expected_certificate_digest=$(printf '%s' "$expected_certificate_digest" | tr '[:lower:]' '[:upper:]')
else
    expected_certificate_digest=''
fi
if [[ "$artifact_type" == 'release' && "$expected_signing" == 'verified' && -z "$expected_certificate_digest" ]]; then
    die '--expected-certificate-sha256 is required for a verified release APK'
fi

while IFS= read -r inherited_variable; do
    case "${inherited_variable^^}" in
        GIT_*) unset "$inherited_variable" ;;
    esac
done < <(compgen -e)

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && git --no-replace-objects rev-parse --show-toplevel)

resolve_path() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *) printf '%s/%s\n' "$repo_root" "$1" ;;
    esac
}

apk_path=$(resolve_path "$apk_path")
output_dir=$(resolve_path "$output_dir")
[[ -f "$apk_path" ]] || die 'APK does not exist'

head_commit=$(git --no-replace-objects -C "$repo_root" rev-parse HEAD | tr '[:upper:]' '[:lower:]')
commit=${GITHUB_SHA:-$head_commit}
[[ "$commit" =~ ^[0-9a-fA-F]{40}$ ]] || die 'commit must be a full SHA-1'
commit=$(printf '%s' "$commit" | tr '[:upper:]' '[:lower:]')
[[ "$commit" == "$head_commit" ]] || die 'GITHUB_SHA does not match checked-out HEAD'

temporary_dir=$(mktemp -d)
staging_dir=''
lock_dir=''
lock_owned=false
cleanup() {
    if [[ -n "$staging_dir" && -d "$staging_dir" ]]; then
        rm -rf -- "$staging_dir"
    fi
    if [[ "$lock_owned" == true && -n "$lock_dir" && -d "$lock_dir" ]]; then
        rmdir -- "$lock_dir" 2>/dev/null || true
    fi
    rm -rf -- "$temporary_dir"
}
trap cleanup EXIT

aapt_path=$(find_android_tool AAPT aapt)
aapt_output="$temporary_dir/aapt.txt"
if ! "$aapt_path" dump badging "$apk_path" 2>&1 | tr -d '\r' >"$aapt_output"; then
    die 'aapt could not read APK metadata'
fi

package_name=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$aapt_output" | head -n 1)
version_code=$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" "$aapt_output" | head -n 1)
version_name=$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" "$aapt_output" | head -n 1)
minimum_android_sdk=$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" "$aapt_output" | head -n 1)
[[ "$package_name" == 'com.mirkori.inplacex' ]] || die 'APK package name is not com.mirkori.inplacex'
[[ "$version_code" =~ ^[0-9]+$ && "$version_code" -gt 0 ]] || die 'APK versionCode is invalid'
[[ "$version_name" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$ ]] || die 'APK versionName is invalid'
[[ "$minimum_android_sdk" =~ ^[0-9]+$ && "$minimum_android_sdk" -gt 0 ]] || die 'APK minimum Android SDK is invalid'

debuggable=false
if grep -qx 'application-debuggable' "$aapt_output"; then
    debuggable=true
fi
if [[ "$artifact_type" == 'release' && "$debuggable" != false ]]; then
    die 'release APK must not be debuggable'
fi

apksigner_path=$(find_android_tool APKSIGNER apksigner)
signing_output="$temporary_dir/apksigner.txt"
if "$apksigner_path" verify --verbose --print-certs "$apk_path" 2>&1 | tr -d '\r' >"$signing_output"; then
    signing_status='verified'
else
    signing_status='unverified'
fi
[[ "$signing_status" == "$expected_signing" ]] || die "expected signing status $expected_signing, got $signing_status"

certificate_fingerprint=''
if [[ "$signing_status" == 'verified' ]]; then
    certificate_digest=$(sed -n \
        -e 's/^Signer #1 certificate SHA-256 digest: \([0-9A-Fa-f]*\)$/\1/p' \
        -e 's/^V[0-9][0-9]* Signer: certificate SHA-256 digest: \([0-9A-Fa-f]*\)$/\1/p' \
        "$signing_output" | head -n 1)
    [[ "$certificate_digest" =~ ^[0-9A-Fa-f]{64}$ ]] || die 'verified APK is missing a valid signer certificate SHA-256 digest'
    certificate_digest=$(printf '%s' "$certificate_digest" | tr '[:lower:]' '[:upper:]')
    if [[ -n "$expected_certificate_digest" && "$certificate_digest" != "$expected_certificate_digest" ]]; then
        die 'owner certificate SHA-256 does not match expected policy'
    fi
    certificate_fingerprint=$(printf '%s' "$certificate_digest" | sed 's/../&:/g; s/:$//')
fi

if [[ "$artifact_type" == 'release' && "$signing_status" == 'verified' ]]; then
    if [[ -n "$(git --no-replace-objects -C "$repo_root" status --porcelain=v1 --untracked-files=all)" ]]; then
        die 'verified release APK requires a clean Git checkout'
    fi
fi

if command -v sha256sum >/dev/null 2>&1; then
    sha256=$(sha256sum "$apk_path" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
    sha256=$(shasum -a 256 "$apk_path" | awk '{print $1}')
else
    die 'sha256sum or shasum is required'
fi
size_bytes=$(wc -c < "$apk_path" | tr -d '[:space:]')
[[ "$size_bytes" =~ ^[0-9]+$ && "$size_bytes" -gt 0 ]] || die 'APK size is invalid'

release_version_id=$(printf '%s' "$version_name" | tr '[:upper:]' '[:lower:]')
release_id="inplacex-${release_version_id}-${version_code}"
((${#release_id} <= 64)) || die 'releaseId exceeds Mirkori catalog limit of 64 characters'
[[ "$release_id" =~ ^[a-z0-9][a-z0-9._-]{1,63}$ ]] || die 'releaseId does not match the Mirkori catalog id format'
short_commit=${commit:0:12}
if [[ "$artifact_type" == 'release' && "$signing_status" == 'verified' ]]; then
    artifact_name="InplaceX-${version_name}-${version_code}.apk"
elif [[ "$artifact_type" == 'release' ]]; then
    artifact_name="inplacex-release-unsigned-${version_name}-${version_code}-${short_commit}.apk"
else
    artifact_name="inplacex-debug-${version_name}-${short_commit}.apk"
fi
source_file_name=$(basename -- "$apk_path")
manifest_name="${artifact_name%.apk}.json"
checksum_name="${artifact_name}.sha256"
mkdir -p "$output_dir"
staging_dir=$(mktemp -d "$output_dir/.${release_id}.${artifact_type}.tmp.XXXXXX") \
    || die 'could not create a clean artifact staging directory'
cp "$apk_path" "$staging_dir/$artifact_name"

if [[ -n "$certificate_fingerprint" ]]; then
    certificate_json="\"$certificate_fingerprint\""
else
    certificate_json='null'
fi

cat > "$staging_dir/$manifest_name" <<EOF
{
  "schemaVersion": 1,
  "artifact": "$artifact_name",
  "artifact_type": "$artifact_type",
  "releaseId": "$release_id",
  "fileName": "$artifact_name",
  "sourceFileName": "$source_file_name",
  "packageName": "$package_name",
  "version": "$version_name",
  "version_code": $version_code,
  "versionName": "$version_name",
  "versionCode": $version_code,
  "minimumAndroidSdk": $minimum_android_sdk,
  "commit": "$commit",
  "sizeBytes": $size_bytes,
  "sha256": "$sha256",
  "sha256_algorithm": "SHA-256",
  "signing_status": "$signing_status",
  "signingStatus": "$signing_status",
  "certificateSha256Fingerprint": $certificate_json,
  "debuggable": $debuggable
}
EOF
printf '%s  %s\n' "$sha256" "$artifact_name" > "$staging_dir/$checksum_name"
printf 'signing_status=%s\ncertificate_sha256_fingerprint=%s\n' \
    "$signing_status" "${certificate_fingerprint:-none}" > "$staging_dir/apksigner-${artifact_type}.txt"
printf 'package_name=%s\nversion_name=%s\nversion_code=%s\nminimum_android_sdk=%s\ndebuggable=%s\n' \
    "$package_name" "$version_name" "$version_code" "$minimum_android_sdk" "$debuggable" \
    > "$staging_dir/apk-metadata-${artifact_type}.txt"

bundle_files=(
    "$artifact_name"
    "$manifest_name"
    "$checksum_name"
    "apksigner-${artifact_type}.txt"
    "apk-metadata-${artifact_type}.txt"
)
published_dir=$output_dir
if [[ "$artifact_type" == 'release' && "$signing_status" == 'verified' ]]; then
    final_dir="$output_dir/$release_id"
    lock_dir="$output_dir/.${release_id}.lock"
    mkdir -- "$lock_dir" 2>/dev/null || die 'releaseId publication is already in progress'
    lock_owned=true
    if [[ -e "$final_dir" ]]; then
        [[ -d "$final_dir" && ! -L "$final_dir" ]] \
            || die 'existing releaseId path is not a regular directory'
        for file_name in "${bundle_files[@]}"; do
            [[ -f "$final_dir/$file_name" && ! -L "$final_dir/$file_name" ]] \
                || die 'existing releaseId directory contains stale or incomplete files'
        done
        actual_entry_count=$(find "$final_dir" -mindepth 1 -maxdepth 1 | wc -l | tr -d '[:space:]')
        [[ "$actual_entry_count" == "${#bundle_files[@]}" ]] \
            || die 'existing releaseId directory contains stale or incomplete files'
        existing_sha256=$(sed -n 's/^  "sha256": "\([0-9a-fA-F]*\)",$/\1/p' \
            "$final_dir/$manifest_name" | head -n 1 | tr '[:upper:]' '[:lower:]')
        [[ "$existing_sha256" =~ ^[0-9a-f]{64}$ ]] \
            || die 'existing releaseId manifest has an invalid APK SHA-256'
        [[ "$existing_sha256" == "$sha256" ]] \
            || die 'releaseId already exists with different APK SHA-256'
        for file_name in "${bundle_files[@]}"; do
            cmp -s "$staging_dir/$file_name" "$final_dir/$file_name" \
                || die 'existing releaseId bundle differs from the generated identity bundle'
        done
    else
        mv -- "$staging_dir" "$final_dir" \
            || die 'could not atomically publish the releaseId directory'
        staging_dir=''
    fi
    rmdir -- "$lock_dir"
    lock_owned=false
    lock_dir=''
    published_dir=$final_dir
else
    for file_name in "${bundle_files[@]}"; do
        mv -f -- "$staging_dir/$file_name" "$output_dir/$file_name"
    done
    rmdir -- "$staging_dir"
    staging_dir=''
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        printf 'artifact_name=%s\n' "$artifact_name"
        printf 'artifact_path=%s\n' "$published_dir/$artifact_name"
        printf 'manifest_path=%s\n' "$published_dir/$manifest_name"
        printf 'release_id=%s\n' "$release_id"
        printf 'package_name=%s\n' "$package_name"
        printf 'version=%s\n' "$version_name"
        printf 'version_code=%s\n' "$version_code"
        printf 'minimum_android_sdk=%s\n' "$minimum_android_sdk"
        printf 'size_bytes=%s\n' "$size_bytes"
        printf 'sha256=%s\n' "$sha256"
        printf 'commit=%s\n' "$commit"
        printf 'signing_status=%s\n' "$signing_status"
        printf 'certificate_sha256_fingerprint=%s\n' "$certificate_fingerprint"
        printf 'debuggable=%s\n' "$debuggable"
    } >> "$GITHUB_OUTPUT"
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        printf '### %s artifact identity\n\n' "${artifact_type^}"
        printf '%s\n' "- Package: \`$package_name\`"
        printf '%s\n' "- Version: \`$version_name ($version_code)\`"
        printf '%s\n' "- Minimum Android SDK: \`$minimum_android_sdk\`"
        printf '%s\n' "- Commit: \`$commit\`"
        printf '%s\n' "- Size: \`$size_bytes\` bytes"
        printf '%s\n' "- SHA-256: \`$sha256\`"
        printf '%s\n' "- APK signing: \`$signing_status\` (apksigner)"
        printf '%s\n' "- Certificate SHA-256: \`${certificate_fingerprint:-none}\`"
        printf '%s\n' "- Debuggable: \`$debuggable\`"
        printf '%s\n' "- Release id: \`$release_id\`"
        printf '%s\n' "- Artifact: \`$artifact_name\`"
    } >> "$GITHUB_STEP_SUMMARY"
fi

printf 'artifact=%s release_id=%s package=%s version=%s version_code=%s size_bytes=%s signing_status=%s debuggable=%s\n' \
    "$artifact_name" "$release_id" "$package_name" "$version_name" "$version_code" "$size_bytes" \
    "$signing_status" "$debuggable"
