#!/usr/bin/bash -p
set -euo pipefail

script_directory="$(builtin cd -- "${BASH_SOURCE[0]%/*}" && builtin pwd -P)"
readonly script_directory
# shellcheck source=ops/production/release-shell-bootstrap.sh
builtin source "$script_directory/release-shell-bootstrap.sh"

if [[ $# -ne 5 ]]; then
    echo "Usage: $0 <loopback|external> <base-url> <release-id> <git-sha> <image-digest>" >&2
    exit 64
fi

mode="$1"
base_url="${2%/}"
expected_release_id="$3"
expected_git_sha="$4"
expected_image_digest="$5"

[[ "$mode" == "loopback" || "$mode" == "external" ]] || exit 65
[[ "$expected_release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || exit 65
[[ "$expected_git_sha" =~ ^[0-9a-f]{40}$ ]] || exit 65
[[ "$expected_image_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || exit 65
if [[ "$mode" == "loopback" ]]; then
    [[ "$base_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}$ ]] || {
        echo "Loopback smoke accepts only an explicit http://127.0.0.1:<port> URL." >&2
        exit 65
    }
    health_path="/health"
    ready_path="/ready"
    release_path="/meta/release"
    curl_protocol=(--proto '=http')
else
    [[ "$base_url" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]] || {
        echo "External smoke requires an HTTPS origin without a path." >&2
        exit 65
    }
    health_path="/inplacex/health"
    ready_path="/inplacex/ready"
    release_path="/inplacex/meta/release"
    curl_protocol=(--proto '=https' --tlsv1.2)
fi

for command_name in curl python3; do
    command -v "$command_name" >/dev/null || {
        echo "Required command is missing: $command_name" >&2
        exit 69
    }
done

temporary_directory="$(mktemp -d)"
cleanup() { rm -rf -- "$temporary_directory"; }
trap cleanup EXIT

curl_common=(--fail --silent --show-error --connect-timeout 5 --max-time 15 "${curl_protocol[@]}")
curl "${curl_common[@]}" "$base_url$health_path" > "$temporary_directory/health.json"
curl "${curl_common[@]}" "$base_url$ready_path" > "$temporary_directory/ready.json"
curl "${curl_common[@]}" "$base_url$release_path" > "$temporary_directory/release.json"

python3 -I - "$temporary_directory" "$expected_release_id" "$expected_git_sha" "$expected_image_digest" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
expected_release = {
    "releaseId": sys.argv[2],
    "gitSha": sys.argv[3],
    "imageDigest": sys.argv[4],
}

def exact(name, expected):
    value = json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
    if value != expected:
        raise SystemExit(f"Unexpected {name} response")

exact("health", {"status": "ok"})
exact("ready", {"status": "ready"})
exact("release", expected_release)

PY

echo "InplaceX backend smoke passed for $expected_release_id ($expected_git_sha)."
