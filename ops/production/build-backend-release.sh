#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 4 || "$4" != "--push" ]]; then
    echo "Usage: $0 <registry/repository:tag> <release-id> <absolute-manifest-path> --push" >&2
    echo "The command builds only an exact clean HEAD archive and pushes BuildKit provenance/SBOM attestations." >&2
    exit 64
fi

image_tag="$1"
release_id="$2"
manifest_path="$3"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dockerfile="$repository_root/ops/Dockerfile"

for command_name in docker git mktemp python3 sha256sum sync; do
    command -v "$command_name" >/dev/null || {
        echo "Required release-build command is missing: $command_name" >&2
        exit 69
    }
done
[[ "$image_tag" =~ ^[a-z0-9][a-z0-9._:/-]*:[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ &&
    "$image_tag" != *:latest ]] || {
    echo "Image target must be one explicit registry tag and must not be latest." >&2
    exit 65
}
[[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || exit 65
[[ "$manifest_path" == /* && ! -L "$manifest_path" ]] || {
    echo "Manifest path must be absolute and must not be a symlink." >&2
    exit 66
}
manifest_directory="$(dirname -- "$manifest_path")"
[[ -d "$manifest_directory" && ! -L "$manifest_directory" ]] || {
    echo "Manifest parent must already be a real directory." >&2
    exit 66
}

cd "$repository_root"
git rev-parse --is-inside-work-tree >/dev/null
git_sha="$(git rev-parse --verify HEAD)"
[[ "$git_sha" =~ ^[0-9a-f]{40}$ ]] || exit 65
[[ -z "$(git status --porcelain=v1 --untracked-files=all)" ]] || {
    echo "Release image build requires an exact clean source tree." >&2
    exit 75
}
git fsck --no-dangling >/dev/null

source_archive_sha256="$(git archive --format=tar "$git_sha" | sha256sum | awk '{print $1}')"
[[ "$source_archive_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 70
temporary_directory="$(mktemp -d)"
cleanup() { rm -rf -- "$temporary_directory"; }
trap cleanup EXIT
metadata_path="$temporary_directory/build-metadata.json"

git archive --format=tar "$git_sha" | docker buildx build \
    --file "$dockerfile" \
    --build-arg "INPLACEX_BUILD_VERSION=$release_id" \
    --build-arg "INPLACEX_BUILD_REVISION=$git_sha" \
    --build-arg "INPLACEX_SOURCE_ARCHIVE_SHA256=$source_archive_sha256" \
    --tag "$image_tag" \
    --provenance=mode=max \
    --sbom=true \
    --metadata-file "$metadata_path" \
    --push \
    -

temporary_manifest="$(mktemp "$manifest_directory/.inplacex-backend-manifest.XXXXXX")"
python3 - "$metadata_path" "$temporary_manifest" "$image_tag" "$release_id" "$git_sha" \
    "$source_archive_sha256" <<'PY'
import json
import pathlib
import re
import sys

metadata = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
digest = metadata.get("containerimage.digest")
if not isinstance(digest, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None:
    raise SystemExit("BuildKit metadata is missing an immutable image digest")
manifest = {
    "schemaVersion": 1,
    "component": "inplacex-online-backend",
    "releaseId": sys.argv[4],
    "gitSha": sys.argv[5],
    "sourceArchiveSha256": sys.argv[6],
    "image": f"{sys.argv[3].rsplit(':', 1)[0]}@{digest}",
    "imageDigest": digest,
    "builderBase": "gradle:9.3.1-jdk21@sha256:f3784cc59d7fbab1e0ddb09c4cd082f13e16d3fb8c50b7922b7aeae8e9507da5",
    "runtimeBase": "eclipse-temurin:11-jre-jammy@sha256:e8acde9cc75b96765f005857cfeb7f826409177482c3f70400d5a94328689d56",
    "attestations": ["slsa-provenance-mode-max", "spdx-sbom"],
}
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
chmod 0600 "$temporary_manifest"
mv -f -- "$temporary_manifest" "$manifest_path"
sync -f "$manifest_path"
sync -f "$manifest_directory"

echo "Published exact InplaceX backend image and manifest for $release_id ($git_sha)."
