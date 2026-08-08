#!/usr/bin/bash -p
set -euo pipefail

script_directory="$(builtin cd -- "${BASH_SOURCE[0]%/*}" && builtin pwd -P)"
readonly script_directory
# shellcheck source=ops/production/release-shell-bootstrap.sh
builtin source "$script_directory/release-shell-bootstrap.sh"

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <loopback-port> <operator-network-cidr> <absolute-output-path>" >&2
    exit 64
fi

loopback_port="$1"
operator_network_cidr="$2"
output_path="$3"
template="$script_directory/nginx-inplacex-online.locations.conf.template"

if [[ ! "$loopback_port" =~ ^[0-9]+$ ]] ||
    (( 10#$loopback_port < 1 || 10#$loopback_port > 65535 )); then
    echo "Loopback port must be an integer in 1..65535." >&2
    exit 65
fi
command -v python3 >/dev/null || {
    echo "Required command is missing: python3" >&2
    exit 69
}
if ! python3 -I - "$operator_network_cidr" <<'PY'
import ipaddress
import sys

try:
    network = ipaddress.ip_network(sys.argv[1], strict=True)
except ValueError as error:
    raise SystemExit("Operator network must be one canonical IPv4 or IPv6 CIDR") from error
if str(network) != sys.argv[1].lower():
    raise SystemExit("Operator CIDR must use the canonical network address")
PY
then
    exit 65
fi
[[ "$output_path" == /* ]] || {
    echo "Output path must be absolute." >&2
    exit 65
}
[[ ! -L "$output_path" ]] || {
    echo "Refusing to replace a symlink output path." >&2
    exit 66
}
output_directory="$(dirname "$output_path")"
[[ -d "$output_directory" && ! -L "$output_directory" ]] || {
    echo "Output directory must already exist and must not be a symlink." >&2
    exit 66
}

temporary_path="$(mktemp "$output_directory/.inplacex-nginx.XXXXXX")"
cleanup() { rm -f -- "$temporary_path"; }
trap cleanup EXIT

sed \
    -e "s/@@INPLACEX_BACKEND_LOOPBACK_PORT@@/$loopback_port/g" \
    -e "s#@@INPLACEX_OPERATOR_NETWORK_CIDR@@#$operator_network_cidr#g" \
    "$template" > "$temporary_path"
if grep -q '@@INPLACEX_' "$temporary_path"; then
    echo "Rendered nginx config still contains unresolved placeholders." >&2
    exit 67
fi
chmod 0644 "$temporary_path"
mv -f -- "$temporary_path" "$output_path"
trap - EXIT

echo "Rendered $output_path for loopback port $loopback_port and the explicit operator CIDR. Run nginx -t before reload."
