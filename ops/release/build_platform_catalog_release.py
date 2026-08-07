#!/usr/bin/env python3
"""Build an immutable Mirkori Platform catalog snapshot from a signed APK bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


PACKAGE_NAME = "com.mirkori.inplacex"
GAME_ID = "inplacex"
GAME_SLUG = "inplacex"
CATALOG_SCHEMA_VERSION = 1
IDENTITY_SCHEMA_VERSION = 1
MAX_JSON_BYTES = 1024 * 1024
MAX_APK_BYTES = 4 * 1024 * 1024 * 1024
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
FINGERPRINT_PATTERN = re.compile(r"(?:[0-9A-F]{2}:){31}[0-9A-F]{2}\Z")
RELEASE_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{1,63}\Z")
FILE_NAME_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
EXPECTED_SOURCE_FILE_NAME = "app-signedReleaseCandidate.apk"
EXPECTED_IDENTITY_FIELDS = {
    "schemaVersion",
    "artifact",
    "artifact_type",
    "releaseId",
    "fileName",
    "sourceFileName",
    "packageName",
    "version",
    "version_code",
    "versionName",
    "versionCode",
    "minimumAndroidSdk",
    "commit",
    "sizeBytes",
    "sha256",
    "sha256_algorithm",
    "signing_status",
    "signingStatus",
    "certificateSha256Fingerprint",
    "debuggable",
}
EXPECTED_RELEASE_FIELDS = {
    "id",
    "platform",
    "channel",
    "versionName",
    "versionCode",
    "minimumSupportedVersionCode",
    "minimumAndroidSdk",
    "publishedAt",
    "changelog",
    "fileName",
    "relativePath",
    "sizeBytes",
    "sha256",
}
EXPECTED_GAME_FIELDS = {"id", "slug", "displayName", "description", "releases"}
EXPECTED_APP_LINK_FIELDS = {"packageName", "certificateSha256Fingerprints"}


class ReleaseBuildError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseBuildError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path, label: str) -> Any:
    metadata = regular_file(path, label)
    require(0 < metadata.st_size <= MAX_JSON_BYTES, f"{label} has an invalid size")
    try:
        with path.open("r", encoding="utf-8") as source:
            return json.load(source)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReleaseBuildError(f"{label} is not valid UTF-8 JSON") from error


def is_windows_reparse_point(metadata: os.stat_result) -> bool:
    reparse_attribute = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return os.name == "nt" and bool(getattr(metadata, "st_file_attributes", 0) & reparse_attribute)


def resolved_without_links(path: Path, label: str) -> Path:
    absolute = path.absolute()
    resolved = path.resolve(strict=True)
    require(
        os.path.normcase(os.path.normpath(str(absolute)))
        == os.path.normcase(os.path.normpath(str(resolved))),
        f"{label} path must not traverse links or reparse points",
    )
    return resolved


def regular_file(path: Path, label: str) -> os.stat_result:
    try:
        metadata = path.lstat()
    except FileNotFoundError as error:
        raise ReleaseBuildError(f"{label} does not exist") from error
    require(stat.S_ISREG(metadata.st_mode), f"{label} must be a regular file")
    require(not stat.S_ISLNK(metadata.st_mode), f"{label} must not be a symlink")
    require(not is_windows_reparse_point(metadata), f"{label} must not be a Windows reparse point")
    require(metadata.st_nlink == 1, f"{label} must not be hard-linked")
    resolved_without_links(path, label)
    return metadata


def real_directory(path: Path, label: str) -> Path:
    try:
        metadata = path.lstat()
    except FileNotFoundError as error:
        raise ReleaseBuildError(f"{label} does not exist") from error
    require(stat.S_ISDIR(metadata.st_mode), f"{label} must be a directory")
    require(not stat.S_ISLNK(metadata.st_mode), f"{label} must not be a symlink")
    require(not is_windows_reparse_point(metadata), f"{label} must not be a Windows reparse point")
    return resolved_without_links(path, label)


def normalize_fingerprint(value: Any) -> str:
    require(isinstance(value, str), "certificate fingerprint must be a string")
    compact = value.replace(":", "").upper()
    require(re.fullmatch(r"[0-9A-F]{64}", compact) is not None, "certificate fingerprint is invalid")
    return ":".join(compact[index : index + 2] for index in range(0, 64, 2))


def safe_text(value: Any, label: str, minimum: int, maximum: int) -> str:
    require(isinstance(value, str), f"{label} must be a string")
    require(value == value.strip(), f"{label} must not have surrounding whitespace")
    require(minimum <= len(value) <= maximum, f"{label} has an invalid length")
    require(all(character.isprintable() for character in value), f"{label} contains control characters")
    return value


def canonical_utc_instant(value: Any) -> str:
    require(isinstance(value, str), "publishedAt must be a string")
    require(value.endswith("Z"), "publishedAt must be a UTC instant ending in Z")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ReleaseBuildError("publishedAt is not a valid ISO-8601 instant") from error
    require(parsed.utcoffset() == timezone.utc.utcoffset(parsed), "publishedAt must use UTC")
    canonical = parsed.isoformat().replace("+00:00", "Z")
    require(value == canonical, "publishedAt must use canonical ISO-8601 form")
    return canonical


def parse_key_value_file(path: Path, expected_keys: set[str], label: str) -> dict[str, str]:
    regular_file(path, label)
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ReleaseBuildError(f"{label} must be UTF-8 text") from error
    for line in lines:
        require(line and "=" in line, f"{label} contains a malformed line")
        key, value = line.split("=", 1)
        require(key in expected_keys, f"{label} contains an unknown key")
        require(key not in values, f"{label} contains a duplicate key")
        require(value == value.strip() and value != "", f"{label} contains an invalid value")
        values[key] = value
    require(set(values) == expected_keys, f"{label} is incomplete")
    return values


def candidate_manifest(candidate_directory: Path) -> tuple[dict[str, Any], Path]:
    candidate = real_directory(candidate_directory, "release candidate directory")
    entries = list(candidate.iterdir())
    for entry in entries:
        regular_file(entry, "release candidate entry")
    manifests = [entry for entry in entries if entry.suffix.lower() == ".json"]
    require(len(manifests) == 1, "candidate must contain exactly one identity manifest")
    identity = load_json(manifests[0], "release identity manifest")
    require(isinstance(identity, dict), "release identity manifest must be an object")
    require(set(identity) == EXPECTED_IDENTITY_FIELDS, "release identity manifest fields are invalid")
    require(
        isinstance(identity["schemaVersion"], int)
        and not isinstance(identity["schemaVersion"], bool)
        and identity["schemaVersion"] == IDENTITY_SCHEMA_VERSION,
        "unsupported release identity schema",
    )
    require(identity["artifact_type"] == "release", "candidate is not a release artifact")
    require(identity["signing_status"] == "verified", "candidate signature is not verified")
    require(identity["signingStatus"] == "verified", "candidate signing status is inconsistent")
    require(identity["debuggable"] is False, "debuggable candidate is forbidden")
    require(identity["packageName"] == PACKAGE_NAME, "candidate package name is invalid")
    require(identity["version"] == identity["versionName"], "candidate version fields disagree")
    require(identity["version_code"] == identity["versionCode"], "candidate version code fields disagree")
    require(
        isinstance(identity["versionCode"], int)
        and not isinstance(identity["versionCode"], bool)
        and identity["versionCode"] > 0,
        "versionCode is invalid",
    )
    require(
        isinstance(identity["minimumAndroidSdk"], int)
        and not isinstance(identity["minimumAndroidSdk"], bool)
        and 21 <= identity["minimumAndroidSdk"] <= 100,
        "minimumAndroidSdk is invalid",
    )
    safe_text(identity["versionName"], "versionName", 1, 64)
    release_id = safe_text(identity["releaseId"], "releaseId", 2, 64)
    require(RELEASE_ID_PATTERN.fullmatch(release_id) is not None, "releaseId is invalid")
    commit = safe_text(identity["commit"], "commit", 40, 40)
    require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None, "candidate commit is invalid")
    file_name = safe_text(identity["fileName"], "fileName", 1, 128)
    require(FILE_NAME_PATTERN.fullmatch(file_name) is not None and file_name.lower().endswith(".apk"), "APK fileName is invalid")
    require(identity["artifact"] == file_name, "candidate artifact fields disagree")
    require(manifests[0].name == f"{file_name[:-4]}.json", "candidate identity manifest name is invalid")
    source_file_name = safe_text(identity["sourceFileName"], "sourceFileName", 1, 128)
    require(
        source_file_name == EXPECTED_SOURCE_FILE_NAME,
        "sourceFileName does not identify the exact :app:releaseCandidate APK",
    )
    apk = candidate / file_name
    metadata = regular_file(apk, "candidate APK")
    require(0 < metadata.st_size <= MAX_APK_BYTES, "candidate APK size is invalid")
    require(
        isinstance(identity["sizeBytes"], int) and not isinstance(identity["sizeBytes"], bool),
        "candidate APK size identity is invalid",
    )
    require(metadata.st_size == identity["sizeBytes"], "candidate APK size does not match identity")
    digest = sha256_file(apk)
    require(SHA256_PATTERN.fullmatch(str(identity["sha256"])) is not None, "candidate APK SHA-256 is invalid")
    require(digest == identity["sha256"], "candidate APK SHA-256 does not match identity")
    require(identity["sha256_algorithm"] == "SHA-256", "candidate hash algorithm is invalid")
    fingerprint = normalize_fingerprint(identity["certificateSha256Fingerprint"])

    checksum_path = candidate / f"{file_name}.sha256"
    checksum = regular_file(checksum_path, "candidate checksum").st_size
    require(1 <= checksum <= 512, "candidate checksum file is invalid")
    try:
        checksum_text = checksum_path.read_text(encoding="ascii")
    except UnicodeDecodeError as error:
        raise ReleaseBuildError("candidate checksum must be ASCII text") from error
    require(checksum_text == f"{digest}  {file_name}\n", "candidate checksum file does not match identity")
    signer = parse_key_value_file(
        candidate / "apksigner-release.txt",
        {"signing_status", "certificate_sha256_fingerprint"},
        "candidate signer report",
    )
    require(signer["signing_status"] == "verified", "candidate signer report is not verified")
    require(normalize_fingerprint(signer["certificate_sha256_fingerprint"]) == fingerprint, "signer fingerprint differs")
    metadata_values = parse_key_value_file(
        candidate / "apk-metadata-release.txt",
        {"package_name", "version_name", "version_code", "minimum_android_sdk", "debuggable"},
        "candidate APK metadata",
    )
    require(metadata_values["package_name"] == PACKAGE_NAME, "candidate metadata package name differs")
    require(metadata_values["version_name"] == identity["versionName"], "candidate metadata versionName differs")
    require(metadata_values["version_code"] == str(identity["versionCode"]), "candidate metadata versionCode differs")
    require(
        metadata_values["minimum_android_sdk"] == str(identity["minimumAndroidSdk"]),
        "candidate metadata minimum SDK differs",
    )
    require(metadata_values["debuggable"] == "false", "candidate metadata is debuggable")
    expected_entries = {
        manifests[0].name,
        file_name,
        checksum_path.name,
        "apksigner-release.txt",
        "apk-metadata-release.txt",
    }
    require({entry.name for entry in entries} == expected_entries, "candidate directory contains unexpected files")
    require(candidate.name == release_id, "release candidate directory name must match releaseId")
    identity["certificateSha256Fingerprint"] = fingerprint
    return identity, apk


def validate_relative_path(value: Any) -> PurePosixPath:
    require(isinstance(value, str) and value == value.strip() and "\\" not in value, "artifact path is invalid")
    path = PurePosixPath(value)
    require(not path.is_absolute() and all(part not in {"", ".", ".."} for part in path.parts), "artifact path is invalid")
    require(path.as_posix() == value, "artifact path must use canonical forward-slash form")
    return path


def validate_base_catalog(base_directory: Path) -> dict[str, Any]:
    base = real_directory(base_directory, "base catalog directory")
    require({entry.name for entry in base.iterdir()} == {"catalog.json", "artifacts"}, "base catalog layout is invalid")
    artifacts = real_directory(base / "artifacts", "base artifacts directory")
    manifest = load_json(base / "catalog.json", "base catalog manifest")
    require(isinstance(manifest, dict) and set(manifest) == {"schemaVersion", "games"}, "base catalog fields are invalid")
    require(
        isinstance(manifest["schemaVersion"], int)
        and not isinstance(manifest["schemaVersion"], bool)
        and manifest["schemaVersion"] == CATALOG_SCHEMA_VERSION,
        "unsupported base catalog schema",
    )
    require(isinstance(manifest["games"], list) and manifest["games"], "base catalog must contain games")
    declared: set[PurePosixPath] = set()
    game_ids: set[str] = set()
    game_slugs: set[str] = set()
    release_ids: set[str] = set()
    version_keys: set[tuple[str, str, str, int]] = set()
    for game in manifest["games"]:
        require(
            isinstance(game, dict)
            and set(game) in (EXPECTED_GAME_FIELDS, EXPECTED_GAME_FIELDS | {"androidAppLink"}),
            "base game fields are invalid",
        )
        game_id = safe_text(game["id"], "base game id", 2, 64)
        require(RELEASE_ID_PATTERN.fullmatch(game_id) is not None and game_id not in game_ids, "base game id is invalid")
        game_ids.add(game_id)
        slug = safe_text(game["slug"], "base game slug", 2, 64)
        require(
            RELEASE_ID_PATTERN.fullmatch(slug) is not None and slug not in game_slugs,
            "base game slug is invalid or duplicated",
        )
        game_slugs.add(slug)
        safe_text(game["displayName"], "base game displayName", 1, 120)
        safe_text(game["description"], "base game description", 1, 1000)
        app_link = game.get("androidAppLink")
        if app_link is not None:
            require(isinstance(app_link, dict) and set(app_link) == EXPECTED_APP_LINK_FIELDS, "base androidAppLink is invalid")
            package_name = safe_text(app_link["packageName"], "base Android package name", 3, 255)
            require(
                re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package_name) is not None,
                "base Android package name is invalid",
            )
            fingerprints = app_link["certificateSha256Fingerprints"]
            require(isinstance(fingerprints, list) and fingerprints, "base certificate list is invalid")
            require(len({normalize_fingerprint(value) for value in fingerprints}) == len(fingerprints), "base certificate list is duplicated")
        releases = game["releases"]
        require(isinstance(releases, list), "base releases must be an array")
        for release in releases:
            require(isinstance(release, dict) and set(release) == EXPECTED_RELEASE_FIELDS, "base release fields are invalid")
            release_id = safe_text(release["id"], "base release id", 2, 64)
            require(RELEASE_ID_PATTERN.fullmatch(release_id) is not None and release_id not in release_ids, "base release id is invalid")
            release_ids.add(release_id)
            require(
                isinstance(release["platform"], str) and release["platform"] in {"android", "windows"},
                "base platform is invalid",
            )
            require(
                isinstance(release["channel"], str) and release["channel"] in {"stable", "beta"},
                "base channel is invalid",
            )
            safe_text(release["versionName"], "base versionName", 1, 64)
            require(
                isinstance(release["versionCode"], int)
                and not isinstance(release["versionCode"], bool)
                and release["versionCode"] > 0,
                "base versionCode is invalid",
            )
            require(
                isinstance(release["minimumSupportedVersionCode"], int)
                and not isinstance(release["minimumSupportedVersionCode"], bool)
                and 1 <= release["minimumSupportedVersionCode"] <= release["versionCode"],
                "base minimum supported versionCode is invalid",
            )
            if release["platform"] == "android":
                require(app_link is not None, "base Android release requires androidAppLink")
                require(
                    isinstance(release["minimumAndroidSdk"], int)
                    and not isinstance(release["minimumAndroidSdk"], bool)
                    and 21 <= release["minimumAndroidSdk"] <= 100,
                    "base minimum Android SDK is invalid",
                )
            else:
                require(release["minimumAndroidSdk"] is None, "base Windows release has an Android minimum SDK")
            key = (game_id, release["platform"], release["channel"], release["versionCode"])
            require(key not in version_keys, "base catalog has duplicate versionCode")
            version_keys.add(key)
            relative = validate_relative_path(release["relativePath"])
            require(relative not in declared, "base catalog has duplicate artifact paths")
            declared.add(relative)
            artifact = artifacts.joinpath(*relative.parts)
            metadata = regular_file(artifact, "base artifact")
            file_name = safe_text(release["fileName"], "base artifact fileName", 1, 128)
            require(FILE_NAME_PATTERN.fullmatch(file_name) is not None, "base artifact fileName is invalid")
            require(relative.name == file_name, "base artifact fileName differs from its path")
            require(
                release["platform"] != "android" or file_name.lower().endswith(".apk"),
                "base Android artifact must be an APK",
            )
            require(
                isinstance(release["sizeBytes"], int)
                and not isinstance(release["sizeBytes"], bool)
                and 1 <= release["sizeBytes"] <= MAX_APK_BYTES,
                "base artifact size is invalid",
            )
            require(metadata.st_size == release["sizeBytes"], "base artifact size differs")
            require(
                isinstance(release["sha256"], str) and SHA256_PATTERN.fullmatch(release["sha256"]) is not None,
                "base artifact hash is invalid",
            )
            require(sha256_file(artifact) == release["sha256"], "base artifact hash differs")
            canonical_utc_instant(release["publishedAt"])
            safe_text(release["changelog"], "base changelog", 1, 4000)
    actual: set[PurePosixPath] = set()
    for root, directories, files in os.walk(artifacts, followlinks=False):
        root_path = Path(root)
        for name in directories:
            real_directory(root_path / name, "base artifact directory")
        for name in files:
            file_path = root_path / name
            regular_file(file_path, "base artifact")
            actual.add(PurePosixPath(file_path.relative_to(artifacts).as_posix()))
    require(actual == declared, "base artifacts do not exactly match the base manifest")
    return manifest


def copy_tree_without_links(source: Path, target: Path) -> None:
    source = real_directory(source, "source directory")
    target.mkdir(parents=True, exist_ok=False)
    for entry in source.iterdir():
        metadata = entry.lstat()
        require(not stat.S_ISLNK(metadata.st_mode), "source tree must not contain symlinks")
        require(not is_windows_reparse_point(metadata), "source tree must not contain Windows reparse points")
        destination = target / entry.name
        if stat.S_ISDIR(metadata.st_mode):
            real_directory(entry, "source subdirectory")
            copy_tree_without_links(entry, destination)
        else:
            regular_file(entry, "source file")
            shutil.copyfile(entry, destination)


def tree_identity(directory: Path) -> tuple[tuple[str, str, str], ...]:
    root = real_directory(directory, "release directory")
    identity: list[tuple[str, str, str]] = []
    for current, directories, files in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in directories:
            directory_path = current_path / name
            real_directory(directory_path, "release subdirectory")
            identity.append(("directory", directory_path.relative_to(root).as_posix(), ""))
        for name in files:
            file_path = current_path / name
            regular_file(file_path, "release file")
            identity.append(("file", file_path.relative_to(root).as_posix(), sha256_file(file_path)))
    return tuple(sorted(identity))


def fsync_tree(directory: Path) -> None:
    root = real_directory(directory, "staging directory")
    for current, directories, files in os.walk(root, topdown=False, followlinks=False):
        current_path = Path(current)
        for name in directories:
            real_directory(current_path / name, "staging subdirectory")
        for name in files:
            # Windows rejects fsync on a read-only descriptor. Every staging
            # file is private to this builder, so a writable binary descriptor
            # is safe and preserves the same durability contract on both OSes.
            file_path = current_path / name
            regular_file(file_path, "staging file")
            with file_path.open("r+b") as handle:
                os.fsync(handle.fileno())
        if hasattr(os, "O_DIRECTORY"):
            descriptor = os.open(current_path, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)


def build_catalog(
    identity: dict[str, Any],
    base_manifest: dict[str, Any] | None,
    channel: str,
    minimum_supported_version_code: int,
    published_at: str,
    changelog: str,
    description: str,
) -> dict[str, Any]:
    games = [] if base_manifest is None else json.loads(json.dumps(base_manifest["games"]))
    game = next((item for item in games if item["id"] == GAME_ID), None)
    fingerprint = identity["certificateSha256Fingerprint"]
    if game is None:
        game = {
            "id": GAME_ID,
            "slug": GAME_SLUG,
            "displayName": "InplaceX",
            "description": description,
            "androidAppLink": {
                "packageName": PACKAGE_NAME,
                "certificateSha256Fingerprints": [fingerprint],
            },
            "releases": [],
        }
        games.append(game)
    else:
        require(game["slug"] == GAME_SLUG, "existing InplaceX slug is incompatible")
        app_link = game.get("androidAppLink")
        if app_link is None:
            app_link = {
                "packageName": PACKAGE_NAME,
                "certificateSha256Fingerprints": [],
            }
            game["androidAppLink"] = app_link
        require(app_link["packageName"] == PACKAGE_NAME, "existing InplaceX app link is incompatible")
        fingerprints = [normalize_fingerprint(value) for value in app_link["certificateSha256Fingerprints"]]
        if fingerprint not in fingerprints:
            fingerprints.append(fingerprint)
        app_link["certificateSha256Fingerprints"] = fingerprints

    version_code = identity["versionCode"]
    release_id = identity["releaseId"]
    artifact_path = f"{GAME_ID}/android/{channel}/{release_id}/{identity['fileName']}"
    new_release = {
        "id": release_id,
        "platform": "android",
        "channel": channel,
        "versionName": identity["versionName"],
        "versionCode": version_code,
        "minimumSupportedVersionCode": minimum_supported_version_code,
        "minimumAndroidSdk": identity["minimumAndroidSdk"],
        "publishedAt": published_at,
        "changelog": changelog,
        "fileName": identity["fileName"],
        "relativePath": artifact_path,
        "sizeBytes": identity["sizeBytes"],
        "sha256": identity["sha256"],
    }
    existing_by_id = {release["id"]: release for release in game["releases"]}
    existing_by_version = {
        (release["platform"], release["channel"], release["versionCode"]): release
        for release in game["releases"]
    }
    same_id = existing_by_id.get(release_id)
    same_version = existing_by_version.get(("android", channel, version_code))
    if same_id is not None or same_version is not None:
        existing = same_id or same_version
        require(existing == new_release, "release id or versionCode already exists with different metadata")
    else:
        game["releases"].append(new_release)
    game["releases"] = sorted(
        game["releases"],
        key=lambda item: (item["platform"], item["channel"], item["versionCode"], item["id"]),
    )
    games.sort(key=lambda item: item["id"])
    return {"schemaVersion": CATALOG_SCHEMA_VERSION, "games": games}


def publish_directory(staging: Path, output: Path) -> None:
    try:
        output.lstat()
    except FileNotFoundError:
        pass
    else:
        real_directory(output, "existing output directory")
        require(tree_identity(output) == tree_identity(staging), "output path already contains a different release")
        shutil.rmtree(staging)
        return
    staging.replace(output)
    if hasattr(os, "O_DIRECTORY"):
        descriptor = os.open(output.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def main(arguments: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-dir", type=Path, required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--base-release-dir", type=Path)
    parser.add_argument("--allow-empty-base", action="store_true")
    parser.add_argument("--channel", choices=("stable", "beta"), default="stable")
    parser.add_argument("--minimum-supported-version-code", type=int, required=True)
    parser.add_argument("--published-at", required=True)
    parser.add_argument("--changelog", required=True)
    parser.add_argument(
        "--description",
        default="Логическая игра: размещайте числа по правилам и проходите кампанию.",
    )
    args = parser.parse_args(arguments)
    require(args.base_release_dir is not None or args.allow_empty_base, "base catalog is required unless --allow-empty-base is explicit")
    require(not (args.base_release_dir is not None and args.allow_empty_base), "choose a base catalog or --allow-empty-base")
    require(args.minimum_supported_version_code > 0, "minimum supported versionCode must be positive")
    expected_commit = safe_text(args.expected_commit, "expected commit", 40, 40)
    require(re.fullmatch(r"[0-9a-f]{40}", expected_commit) is not None, "expected commit is invalid")
    published_at = canonical_utc_instant(args.published_at)
    changelog = safe_text(args.changelog, "changelog", 1, 4000)
    description = safe_text(args.description, "description", 1, 1000)
    identity, apk = candidate_manifest(args.candidate_dir)
    require(identity["commit"] == expected_commit, "release candidate commit differs from expected commit")
    require(
        args.minimum_supported_version_code <= identity["versionCode"],
        "minimum supported versionCode exceeds candidate versionCode",
    )
    output = args.output_dir.absolute()
    require(output.name not in {"", ".", ".."}, "output directory is invalid")
    output.parent.mkdir(parents=True, exist_ok=True)
    output_parent = real_directory(output.parent, "output parent directory")
    output = output_parent / output.name
    source_roots = [apk.parent.resolve()]
    if args.base_release_dir:
        source_roots.append(args.base_release_dir.resolve())
    for source_root in source_roots:
        require(
            output_parent != source_root and source_root not in output_parent.parents,
            "output parent must not be inside an input directory",
        )
    base_manifest = validate_base_catalog(args.base_release_dir) if args.base_release_dir else None
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.tmp.", dir=output.parent))
    try:
        artifacts = staging / "artifacts"
        if args.base_release_dir:
            copy_tree_without_links(args.base_release_dir.resolve() / "artifacts", artifacts)
        else:
            artifacts.mkdir()
        catalog = build_catalog(
            identity,
            base_manifest,
            args.channel,
            args.minimum_supported_version_code,
            published_at,
            changelog,
            description,
        )
        release = next(
            release
            for game in catalog["games"]
            if game["id"] == GAME_ID
            for release in game["releases"]
            if release["id"] == identity["releaseId"]
        )
        artifact_target = artifacts.joinpath(*PurePosixPath(release["relativePath"]).parts)
        artifact_target.parent.mkdir(parents=True, exist_ok=True)
        if artifact_target.exists():
            require(sha256_file(artifact_target) == identity["sha256"], "base catalog has a different artifact at target path")
        else:
            shutil.copyfile(apk, artifact_target)
        manifest_path = staging / "catalog.json"
        manifest_path.write_text(
            json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        validate_base_catalog(staging)
        fsync_tree(staging)
        publish_directory(staging, output)
    except BaseException:
        if staging.exists():
            shutil.rmtree(staging)
        raise
    print(
        "catalog_release="
        f"{output} release_id={identity['releaseId']} version_code={identity['versionCode']} "
        f"sha256={identity['sha256']} certificate_sha256={identity['certificateSha256Fingerprint']}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ReleaseBuildError) as error:
        print(f"release catalog build failed: {error}", file=os.sys.stderr)
        raise SystemExit(65) from error
