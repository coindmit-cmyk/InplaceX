#!/usr/bin/bash -p
set -euo pipefail
umask 077

script_directory="$(builtin cd -- "${BASH_SOURCE[0]%/*}" && builtin pwd -P)"
readonly script_directory
repository_root="$(builtin cd -- "$script_directory/../.." && builtin pwd -P)"
readonly repository_root
invoked_builder_path="$script_directory/${BASH_SOURCE[0]##*/}"
readonly invoked_builder_path

for bootstrap_environment_name in \
    GCONV_PATH LD_AUDIT LD_DEBUG LD_LIBRARY_PATH LD_PRELOAD LOCPATH NLSPATH; do
    if [[ -n "${!bootstrap_environment_name+x}" ]]; then
        builtin printf 'Dynamic-loader/locale override is forbidden: %s\n' \
            "$bootstrap_environment_name" >&2
        exit 77
    fi
done
unset bootstrap_environment_name

[[ ${EUID:-$(id -u)} -eq 0 ]] || {
    echo "Release image build requires root for a protected source and Docker control plane." >&2
    exit 77
}

for bootstrap_path in \
    "$repository_root/.git/HEAD" \
    "$invoked_builder_path" \
    "$script_directory/release-shell-bootstrap.sh" \
    "$script_directory/release-common.sh"; do
    bootstrap_current="${bootstrap_path%/*}"
    [[ -n "$bootstrap_current" ]] || bootstrap_current=/
    while :; do
        [[ -d "$bootstrap_current" && ! -L "$bootstrap_current" ]] || {
            echo "Release source parent must be a real directory: $bootstrap_current" >&2
            exit 66
        }
        [[ "$(/usr/bin/stat -c '%u' -- "$bootstrap_current")" == "0" ]] || {
            echo "Release source parent must be owned by root: $bootstrap_current" >&2
            exit 77
        }
        bootstrap_mode="$(/usr/bin/stat -c '%a' -- "$bootstrap_current")"
        (( (8#$bootstrap_mode & 022) == 0 )) || {
            echo "Release source parent must not be group/world writable: $bootstrap_current" >&2
            exit 77
        }
        [[ "$bootstrap_current" == "/" ]] && break
        bootstrap_current="${bootstrap_current%/*}"
        [[ -n "$bootstrap_current" ]] || bootstrap_current=/
    done
done

[[ "$invoked_builder_path" == "$script_directory/build-backend-release.sh" ]] || {
    echo "Release builder must use its canonical file name." >&2
    exit 66
}
[[ -d "$repository_root/.git" && ! -L "$repository_root/.git" ]] || {
    echo "Release builder requires an ordinary embedded .git directory." >&2
    exit 77
}
for bootstrap_specification in \
    "755|$invoked_builder_path" \
    "644|$script_directory/release-shell-bootstrap.sh" \
    "755|$script_directory/release-common.sh"; do
    IFS='|' read -r bootstrap_mode bootstrap_path <<< "$bootstrap_specification"
    [[ -f "$bootstrap_path" && ! -L "$bootstrap_path" &&
        "$(/usr/bin/stat -Lc '%F|%u|%g|%a|%h' -- "$bootstrap_path")" == \
            "regular file|0|0|$bootstrap_mode|1" ]] || {
        echo "Release bootstrap file must be root-owned, protected, regular, and single-link: $bootstrap_path" >&2
        exit 77
    }
done
unset bootstrap_current bootstrap_mode bootstrap_path bootstrap_specification
# shellcheck source=ops/production/release-shell-bootstrap.sh
builtin source "$script_directory/release-shell-bootstrap.sh"
# shellcheck source=ops/production/release-common.sh
builtin source "$script_directory/release-common.sh"

registry_auth_mode=""
registry_auth_config=""
if [[ $# -eq 6 && "$4" == "--push" && "$5" == "--registry-auth-config" ]]; then
    registry_auth_mode=config
    registry_auth_config="$6"
elif [[ $# -eq 5 && "$4" == "--push" && "$5" == "--anonymous-loopback" ]]; then
    registry_auth_mode=anonymous-loopback
else
    echo "Usage: $0 <registry/repository:tag> <release-id> <new-absolute-manifest-path> --push (--registry-auth-config <absolute-json> | --anonymous-loopback)" >&2
    echo "The command builds one filter-free exact HEAD archive and pushes BuildKit provenance/SBOM attestations." >&2
    exit 64
fi
image_tag="$1"
release_id="$2"
manifest_path="$3"
readonly archive_dockerfile="ops/Dockerfile"
readonly source_archive_helper="ops/production/create-source-archive.py"

for command_name in awk chmod chown env find grep id install mkdir mktemp python3 rm sha256sum stat sync tar; do
    command -v "$command_name" >/dev/null || {
        echo "Required release-build command is missing: $command_name" >&2
        exit 69
    }
done
[[ -f /usr/bin/git && -x /usr/bin/git && ! -L /usr/bin/git &&
    "$(stat -c '%u' -- /usr/bin/git)" == "0" ]] || {
    echo "Release builder requires a protected root-owned /usr/bin/git." >&2
    exit 77
}
git_mode="$(stat -c '%a' -- /usr/bin/git)"
(( (8#$git_mode & 022) == 0 )) || {
    echo "Release Git executable must not be group/world writable." >&2
    exit 77
}
[[ "$image_tag" =~ ^[a-z0-9][a-z0-9._:/-]*:[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ &&
    "$image_tag" == */* && "$image_tag" != *:latest ]] || {
    echo "Image target must be one explicit registry tag and must not be latest." >&2
    exit 65
}
registry_authority="${image_tag%%/*}"
[[ "$registry_authority" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[1-9][0-9]{0,4})?$ ]] || {
    echo "Image target must contain one canonical registry authority." >&2
    exit 65
}
if [[ "$registry_authority" == *:* ]]; then
    registry_port="${registry_authority##*:}"
    (( 10#$registry_port <= 65535 )) || {
        echo "Registry port is outside the valid range." >&2
        exit 65
    }
fi
if [[ "$registry_auth_mode" == "anonymous-loopback" ]]; then
    [[ "$registry_authority" =~ ^127\.0\.0\.1:([1-9][0-9]{0,4})$ &&
        "${INPLACEX_RELEASE_ISOLATED_CI_ACK:-}" == "$RELEASE_ISOLATED_CI_ACK" ]] || {
        echo "Anonymous registry access is restricted to an acknowledged isolated-CI loopback registry." >&2
        exit 77
    }
fi
[[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || exit 65
[[ "$manifest_path" == /* && ! -e "$manifest_path" && ! -L "$manifest_path" ]] || {
    echo "Manifest path must be a new absolute path and must not already exist or be a symlink." >&2
    exit 66
}
release_validate_absolute_parent_chain "$manifest_path"
manifest_directory="${manifest_path%/*}"
[[ -n "$manifest_directory" ]] || manifest_directory=/
[[ -d "$manifest_directory" && ! -L "$manifest_directory" ]] || {
    echo "Manifest parent must already be a real directory." >&2
    exit 66
}

temporary_directory="$(mktemp -d /run/inplacex-release-build.XXXXXX)"
chown root:root "$temporary_directory"
chmod 0700 "$temporary_directory"
temporary_manifest=""
cleanup() {
    [[ -z "$temporary_manifest" || ( ! -e "$temporary_manifest" && ! -L "$temporary_manifest" ) ]] ||
        rm -f -- "$temporary_manifest"
    rm -rf -- "$temporary_directory"
}
trap cleanup EXIT
readonly source_archive="$temporary_directory/source.tar"
readonly metadata_path="$temporary_directory/build-metadata.json"
readonly normalized_registry_auth_preflight="$temporary_directory/registry-auth.preflight.json"

if [[ "$registry_auth_mode" == "config" ]]; then
    release_normalize_registry_auth_config \
        "$registry_auth_config" "$registry_authority" "$normalized_registry_auth_preflight"
fi

trusted_git=(
    /usr/bin/env -i
    PATH=/usr/sbin:/usr/bin:/sbin:/bin
    HOME="$temporary_directory/git-home"
    LANG=C
    LC_ALL=C
    GIT_CONFIG_GLOBAL=/dev/null
    GIT_CONFIG_NOSYSTEM=1
    /usr/bin/git --no-replace-objects -c core.fsmonitor=false -c core.hooksPath=/dev/null \
    -C "$repository_root"
)
mkdir -m 0700 "$temporary_directory/git-home"

verify_repository_filesystem_trust() {
    local unsafe_git_path
    release_validate_absolute_parent_chain "$repository_root/.git/HEAD"
    [[ -d "$repository_root/.git" && ! -L "$repository_root/.git" ]] || {
        echo "Release builder requires an ordinary embedded .git directory." >&2
        exit 77
    }
    unsafe_git_path="$(find "$repository_root/.git" -xdev \
        \( -type l -o ! -user root -o -perm /022 -o \
        \( ! -type d -a ! -type f \) \) -print -quit)"
    [[ -z "$unsafe_git_path" ]] || {
        echo "Release Git metadata is not root-owned and protected: $unsafe_git_path" >&2
        exit 77
    }
}

verify_repository_filesystem_trust
[[ "$("${trusted_git[@]}" rev-parse --is-inside-work-tree)" == "true" ]]
git_directory="$("${trusted_git[@]}" rev-parse --path-format=absolute --git-dir)"
git_common_directory="$("${trusted_git[@]}" rev-parse --path-format=absolute --git-common-dir)"
[[ "$git_directory" == "$repository_root/.git" &&
    "$git_common_directory" == "$repository_root/.git" &&
    "$("${trusted_git[@]}" rev-parse --show-toplevel)" == "$repository_root" ]] || {
    echo "Release builder requires one ordinary embedded Git repository at the exact source path." >&2
    exit 75
}
git_sha="$("${trusted_git[@]}" rev-parse --verify 'HEAD^{commit}')"
[[ "$git_sha" =~ ^[0-9a-f]{40}$ ]] || exit 65

verify_repository_trust() {
    verify_repository_filesystem_trust
    [[ "$("${trusted_git[@]}" rev-parse --path-format=absolute --git-dir)" == \
        "$repository_root/.git" &&
        "$("${trusted_git[@]}" rev-parse --path-format=absolute --git-common-dir)" == \
        "$repository_root/.git" &&
        "$("${trusted_git[@]}" rev-parse --show-toplevel)" == "$repository_root" &&
        "$("${trusted_git[@]}" rev-parse --verify 'HEAD^{commit}')" == "$git_sha" ]] || {
        echo "Release Git repository identity changed during the build." >&2
        exit 75
    }
}

verify_release_toolset() {
    local specification tree_mode working_mode tool_path full_path
    local tree_entry tree_metadata object_hash working_hash
    local opened_fd metadata_before metadata_after path_identity descriptor_identity
    verify_repository_trust
    for specification in \
        '100755|755|ops/production/build-backend-release.sh' \
        '100644|644|ops/production/release-shell-bootstrap.sh' \
        '100755|755|ops/production/release-common.sh' \
        "100644|644|$source_archive_helper" \
        "100644|644|$archive_dockerfile"; do
        IFS='|' read -r tree_mode working_mode tool_path <<< "$specification"
        full_path="$repository_root/$tool_path"
        release_validate_absolute_parent_chain "$full_path"
        tree_entry="$("${trusted_git[@]}" ls-tree "$git_sha" -- "$tool_path")"
        tree_metadata="${tree_entry%%$'\t'*}"
        [[ "$tree_metadata" =~ ^${tree_mode}\ blob\ [0-9a-f]{40}$ &&
            "$tree_entry" == *$'\t'"$tool_path" ]] || {
            echo "Release tool mode/blob differs from the exact HEAD tree: $tool_path" >&2
            exit 75
        }
        [[ -f "$full_path" && ! -L "$full_path" ]] || {
            echo "Release tool must be a protected regular non-symlink file: $tool_path" >&2
            exit 77
        }
        exec {opened_fd}<"$full_path" || exit 66
        metadata_before="$(stat -Lc '%F|%u|%g|%a|%h|%d:%i|%s|%y|%z' -- \
            "/proc/self/fd/$opened_fd")"
        [[ "$metadata_before" == "regular file|0|0|$working_mode|1|"* ]] || {
            echo "Release tool must be root-owned, protected, exact-mode, and single-link: $tool_path" >&2
            exit 77
        }
        path_identity="$(stat -Lc '%d:%i' -- "$full_path")"
        descriptor_identity="$(stat -Lc '%d:%i' -- "/proc/self/fd/$opened_fd")"
        [[ "$path_identity" == "$descriptor_identity" ]] || {
            echo "Release tool path changed while it was opened: $tool_path" >&2
            exit 75
        }
        object_hash="$("${trusted_git[@]}" cat-file blob "$git_sha:$tool_path" | sha256sum | awk '{print $1}')"
        working_hash="$(sha256sum "/proc/self/fd/$opened_fd" | awk '{print $1}')"
        metadata_after="$(stat -Lc '%F|%u|%g|%a|%h|%d:%i|%s|%y|%z' -- \
            "/proc/self/fd/$opened_fd")"
        [[ "$metadata_after" == "$metadata_before" &&
            "$(stat -Lc '%d:%i' -- "$full_path")" == "$descriptor_identity" ]] || {
            echo "Release tool metadata changed while it was verified: $tool_path" >&2
            exit 75
        }
        exec {opened_fd}<&-
        [[ "$object_hash" =~ ^[0-9a-f]{64}$ && "$working_hash" == "$object_hash" ]] || {
            echo "Release tool bytes differ from the exact HEAD blob: $tool_path" >&2
            exit 75
        }
    done
    verify_repository_trust
}
verify_release_toolset

python3 -I "$repository_root/$source_archive_helper" \
    "$repository_root" "$git_sha" "$source_archive" "$temporary_directory/git-home"
[[ "$("${trusted_git[@]}" rev-parse --verify 'HEAD^{commit}')" == "$git_sha" ]] || {
    echo "Release HEAD changed while its immutable archive was created." >&2
    exit 75
}
verify_release_toolset
tar -tf "$source_archive" > "$temporary_directory/archive.list"
grep -Fxq "$archive_dockerfile" "$temporary_directory/archive.list" || {
    echo "The immutable source archive does not contain $archive_dockerfile." >&2
    exit 70
}
source_archive_sha256="$(sha256sum "$source_archive" | awk '{print $1}')"
[[ "$source_archive_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 70

release_prepare_docker_control_plane buildx "$temporary_directory/docker-cli"
if [[ "$registry_auth_mode" == "config" ]]; then
    release_normalize_registry_auth_config \
        "$registry_auth_config" "$registry_authority" "$RELEASE_DOCKER_CONFIG/config.json"
else
    [[ -z "$(find "$RELEASE_DOCKER_CONFIG" -mindepth 1 -print -quit)" ]] || {
        echo "Anonymous isolated-CI Docker config must remain empty." >&2
        exit 77
    }
fi

verify_release_toolset
release_docker buildx build \
    --file "$archive_dockerfile" \
    --build-arg "INPLACEX_BUILD_VERSION=$release_id" \
    --build-arg "INPLACEX_BUILD_REVISION=$git_sha" \
    --build-arg "INPLACEX_SOURCE_ARCHIVE_SHA256=$source_archive_sha256" \
    --tag "$image_tag" \
    --provenance=mode=max \
    --sbom=true \
    --metadata-file "$metadata_path" \
    --push - < "$source_archive"

image_digest="$(python3 -I - "$metadata_path" <<'PY'
import json
import pathlib
import re
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")).get("containerimage.digest")
if not isinstance(value, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", value) is None:
    raise SystemExit("BuildKit metadata is missing an immutable image digest")
print(value)
PY
)"
image_repository="${image_tag%:*}"
provenance_path="$temporary_directory/provenance.json"
sbom_path="$temporary_directory/sbom.json"
image_index_path="$temporary_directory/image-index.json"
attestation_evidence_path="$temporary_directory/attestation-evidence.json"
verify_release_toolset
release_docker buildx imagetools inspect "$image_repository@$image_digest" \
    --format '{{json .Provenance}}' > "$provenance_path"
verify_release_toolset
release_docker buildx imagetools inspect "$image_repository@$image_digest" \
    --format '{{json .SBOM}}' > "$sbom_path"
verify_release_toolset
release_docker buildx imagetools inspect "$image_repository@$image_digest" \
    --raw > "$image_index_path"
python3 -I - "$provenance_path" "$sbom_path" "$image_index_path" \
    "$attestation_evidence_path" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

provenance_path, sbom_path, index_path, output_path = map(pathlib.Path, sys.argv[1:])
provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
index = json.loads(index_path.read_text(encoding="utf-8"))
if set(provenance) != {"SLSA"} or not isinstance(provenance["SLSA"], dict):
    raise SystemExit("Published image provenance predicate is not one exact SLSA object")
build_type = provenance["SLSA"].get("buildType")
if not isinstance(build_type, str) or not build_type.startswith("https://"):
    raise SystemExit("Published provenance buildType identity is missing")
if set(sbom) != {"SPDX"} or not isinstance(sbom["SPDX"], dict):
    raise SystemExit("Published image SBOM predicate is not one exact SPDX object")
spdx_version = sbom["SPDX"].get("spdxVersion")
if not isinstance(spdx_version, str) or re.fullmatch(r"SPDX-[0-9]+\.[0-9]+", spdx_version) is None:
    raise SystemExit("Published SBOM SPDX identity is missing")
descriptors = index.get("manifests")
if index.get("schemaVersion") != 2 or not isinstance(descriptors, list):
    raise SystemExit("Published image digest is not an OCI/Docker image index")
attestation_digests = sorted({
    descriptor.get("digest")
    for descriptor in descriptors
    if isinstance(descriptor, dict)
    and isinstance(descriptor.get("annotations"), dict)
    and descriptor["annotations"].get("vnd.docker.reference.type") == "attestation-manifest"
    and isinstance(descriptor.get("digest"), str)
})
if not attestation_digests or any(
    not isinstance(value, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", value) is None
    for value in attestation_digests
):
    raise SystemExit("Published image index has no valid attestation manifest digest")
evidence = {
    "attestationManifestDigests": attestation_digests,
    "provenancePredicate": "SLSA",
    "provenanceBuildType": build_type,
    "provenanceSha256": hashlib.sha256(provenance_path.read_bytes()).hexdigest(),
    "sbomPredicate": "SPDX",
    "sbomSpdxVersion": spdx_version,
    "sbomSha256": hashlib.sha256(sbom_path.read_bytes()).hexdigest(),
}
output_path.write_text(json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8")
PY
sha256sum "$provenance_path" "$sbom_path" "$image_index_path" "$attestation_evidence_path" >/dev/null

verify_release_toolset
temporary_manifest="$(mktemp "$manifest_directory/.inplacex-backend-manifest.XXXXXX")"
python3 -I - "$metadata_path" "$temporary_manifest" "$image_tag" "$release_id" "$git_sha" \
    "$source_archive_sha256" "$attestation_evidence_path" <<'PY'
import json
import pathlib
import re
import sys

metadata = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
attestation_evidence = json.loads(pathlib.Path(sys.argv[7]).read_text(encoding="utf-8"))
digest = metadata.get("containerimage.digest")
if not isinstance(digest, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None:
    raise SystemExit("BuildKit metadata is missing an immutable image digest")
manifest = {
    "schemaVersion": 2,
    "component": "inplacex-online-backend",
    "releaseId": sys.argv[4],
    "gitSha": sys.argv[5],
    "sourceArchiveSha256": sys.argv[6],
    "image": f"{sys.argv[3].rsplit(':', 1)[0]}@{digest}",
    "imageDigest": digest,
    "builderBase": "gradle:9.3.1-jdk21@sha256:f3784cc59d7fbab1e0ddb09c4cd082f13e16d3fb8c50b7922b7aeae8e9507da5",
    "runtimeBase": "eclipse-temurin:11-jre-jammy@sha256:e8acde9cc75b96765f005857cfeb7f826409177482c3f70400d5a94328689d56",
    "attestations": ["slsa-provenance-mode-max", "spdx-sbom"],
    "attestationEvidence": attestation_evidence,
}
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
chmod 0600 "$temporary_manifest"
[[ "$(sha256sum "$source_archive" | awk '{print $1}')" == "$source_archive_sha256" ]] || {
    echo "Immutable source archive changed before release manifest publication." >&2
    exit 75
}
verify_release_toolset
[[ ! -e "$manifest_path" && ! -L "$manifest_path" ]] || {
    echo "Manifest destination appeared during the release build." >&2
    exit 75
}
release_publish_new_file_no_replace "$temporary_manifest" "$manifest_path"
temporary_manifest=""
[[ "$(stat -Lc '%F %u %g %a %h' -- "$manifest_path")" == "regular file 0 0 600 1" ]] || {
    echo "Published manifest identity is unsafe." >&2
    exit 75
}

echo "Published exact InplaceX backend image and manifest for $release_id ($git_sha)."
