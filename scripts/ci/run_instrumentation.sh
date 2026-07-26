#!/usr/bin/env bash

set -euo pipefail

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$sdk_root" ]] || { echo 'ANDROID_HOME or ANDROID_SDK_ROOT is required' >&2; exit 1; }
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo '/dev/kvm must be readable and writable' >&2; exit 1; }

export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
export PATH="$sdk_root/emulator:$sdk_root/platform-tools:$sdk_root/cmdline-tools/latest/bin:$PATH"

avd_name=ci-api-34
avd_package='system-images;android-34;google_apis;x86_64'
emulator_pid=''

cleanup() {
    if [[ -n "$emulator_pid" ]] && kill -0 "$emulator_pid" 2>/dev/null; then
        kill "$emulator_pid" || true
        wait "$emulator_pid" || true
    fi
}
trap cleanup EXIT

mkdir -p "$HOME/.android/avd"
echo no | avdmanager create avd --force --name "$avd_name" --package "$avd_package" --device pixel_2
emulator -avd "$avd_name" -no-audio -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect > emulator.log 2>&1 &
emulator_pid=$!

timeout 120 adb wait-for-device
boot_completed=''
for _ in $(seq 1 60); do
    boot_completed=$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')
    if [[ "$boot_completed" == '1' ]]; then
        break
    fi
    sleep 2
done
[[ "$boot_completed" == '1' ]] || { echo 'emulator boot timed out' >&2; exit 1; }
[[ "$(adb -s emulator-5554 get-state)" == 'device' ]] || { echo 'emulator is not ready' >&2; exit 1; }

./gradlew :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
