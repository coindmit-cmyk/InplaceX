#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: artifact_identity.sh --apk PATH [--output-dir PATH]

Copies an APK into an artifact directory and writes a manifest and checksum
that bind the artifact to the source version and commit.
USAGE
}

die() {
  printf 'artifact_identity: %s\n' "$1" >&2
  exit 1
}

apk_path=''
output_dir='build/ci-artifacts'

while (( $# > 0 )); do
  case "$1" in
    --apk)
      (( $# >= 2 )) || die "--apk requires a path"
      apk_path=$2
      shift 2
      ;;
    --output-dir)
      (( $# >= 2 )) || die "--output-dir requires a path"
      output_dir=$2
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

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && git rev-parse --show-toplevel)

resolve_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *)  printf '%s/%s\n' "$repo_root" "$1" ;;
  esac
}

apk_path=$(resolve_path "$apk_path")
output_dir=$(resolve_path "$output_dir")
[[ -f "$apk_path" ]] || die "APK does not exist: $apk_path"

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

find_signing_tool() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    build_tools_dir="$ANDROID_HOME/build-tools"
    if [[ -d "$build_tools_dir" ]]; then
      latest_tool=$(ls "$build_tools_dir" | sort -V | tail -n 1)
      if [[ -n "$latest_tool" && -x "$build_tools_dir/$latest_tool/apksigner" ]]; then
        printf '%s\n' "$build_tools_dir/$latest_tool/apksigner"
        return 0
      fi
    fi
  fi

  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi

  return 1
}

signing_status=""
if apksigner=$(find_signing_tool); then
  if "$apksigner" verify "$apk_path" >/dev/null 2>&1; then
    signing_status="signed"
  else
    signing_status="unsigned"
  fi
else
  die "Cannot verify APK signing: missing apksigner"
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256=$(sha256sum "$apk_path" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  sha256=$(shasum -a 256 "$apk_path" | awk '{print $1}')
else
  die 'sha256sum or shasum is required'
fi

apk_basename=$(basename "$apk_path")
apk_stem=${apk_basename%.apk}
[[ -n "$apk_stem" ]] || die "cannot derive artifact stem from: $apk_path"

artifact_name="inplacex-${apk_stem}-${version}-${short_commit}.apk"
manifest_name="${artifact_name%.apk}.json"
checksum_name="${artifact_name}.sha256"
manifest_path="$output_dir/$manifest_name"

mkdir -p "$output_dir"
cp "$apk_path" "$output_dir/$artifact_name"

cat > "$output_dir/$manifest_name" <<EOF_MANIFEST
{
  "artifact": "$artifact_name",
  "version": "$version",
  "version_code": $version_code,
  "commit": "$commit",
  "signing_status": "$signing_status",
  "sha256": "$sha256",
  "sha256_algorithm": "SHA-256"
}
EOF_MANIFEST

printf '%s  %s\n' "$sha256" "$artifact_name" > "$output_dir/$checksum_name"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'artifact_name=%s\n' "$artifact_name"
    printf 'manifest=%s\n' "$manifest_name"
    printf 'manifest_path=%s\n' "$manifest_path"
    printf 'artifact_path=%s\n' "$output_dir/$artifact_name"
    printf 'sha256=%s\n' "$sha256"
    printf 'version=%s\n' "$version"
    printf 'version_code=%s\n' "$version_code"
    printf 'commit=%s\n' "$commit"
    printf 'signing_status=%s\n' "$signing_status"
  } >> "$GITHUB_OUTPUT"
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '%s\n' '### Artifact identity'
    printf '%s\n' ''
    printf '%s\n' "- Version: \`$version\`"
    printf '%s\n' "- Commit: \`$commit\`"
    printf '%s\n' "- Signing status: \`$signing_status\`"
    printf '%s\n' "- SHA-256: \`$sha256\`"
    printf '%s\n' "- Artifact: \`$artifact_name\`"
  } >> "$GITHUB_STEP_SUMMARY"
fi

printf 'artifact=%s version=%s commit=%s signing_status=%s sha256=%s\n' \
  "$artifact_name" "$version" "$commit" "$signing_status" "$sha256"
