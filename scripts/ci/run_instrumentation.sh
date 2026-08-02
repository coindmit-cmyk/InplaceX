#!/usr/bin/env bash

set -euo pipefail

die() {
    printf 'run_instrumentation: %s\n' "$1" >&2
    exit 1
}

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && git rev-parse --show-toplevel)
cd "$repo_root"

[[ -n "${GITHUB_SHA:-}" ]] || die 'GITHUB_SHA is required'
[[ "$GITHUB_SHA" =~ ^[0-9a-fA-F]{40}$ ]] || die 'GITHUB_SHA must be a full SHA-1'
head_commit=$(git rev-parse HEAD)
[[ "${GITHUB_SHA,,}" == "${head_commit,,}" ]] || die "GITHUB_SHA does not match checked-out HEAD"
[[ -r /dev/kvm && -w /dev/kvm ]] || die '/dev/kvm must be readable and writable'

mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
[[ ${#devices[@]} -eq 1 ]] || die "expected exactly one ready Android device, got ${#devices[@]}"
serial=${devices[0]}
[[ "$serial" == emulator-* ]] || die 'instrumentation target must be an emulator'
[[ "$(adb -s "$serial" get-state)" == 'device' ]] || die 'emulator is not ready'
[[ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == '1' ]] || die 'emulator boot is incomplete'

./gradlew :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest --no-configuration-cache
