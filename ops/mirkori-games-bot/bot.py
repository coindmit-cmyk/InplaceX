#!/usr/bin/env python3
"""Минимальный Telegram-каталог проверенных Android-сборок Mirkori Games."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


MAX_CATALOG_BYTES = 256 * 1024
MAX_PLATFORM_CATALOG_BYTES = 1024 * 1024
MAX_APK_BYTES = 4 * 1024 * 1024 * 1024
MAX_UPDATE_OFFSET_FILE_BYTES = 64
PLATFORM_RELEASE_CHANNELS = ("stable", "beta")
PLATFORM_SCHEMA_VERSIONS = {1, 2, 3}
PLATFORM_DISTRIBUTION_FIELDS = {
    "id", "platform", "marketScope", "packageName", "signingIdentityRef",
    "certificateSha256Fingerprints", "paymentChannel", "deliveryChannel",
    "releaseChannels", "status", "effectiveConfigurationVersion",
}
PLATFORM_DISTRIBUTION_RELEASE_FIELDS = {
    "id", "distributionId", "channel", "versionName", "versionCode",
    "minimumSupportedVersionCode", "minimumAndroidSdk", "publishedAt",
    "changelogs", "fileName", "relativePath", "sizeBytes", "sha256",
}
PLATFORM_LIFECYCLE_FIELDS = {"releaseId", "status", "effectiveAt", "policyVersion"}
PLATFORM_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{1,63}\Z")
PLATFORM_PACKAGE_PATTERN = re.compile(r"[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+\Z")
PLATFORM_FINGERPRINT_PATTERN = re.compile(r"(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}\Z")
PLATFORM_FILE_NAME_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
PLATFORM_REASON_CODE_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{2,63}\Z")
PLATFORM_SUPPORT_PATH_PATTERN = re.compile(r"/[A-Za-z0-9._~/-]{1,254}\Z")


@dataclass(frozen=True)
class GameRelease:
    game_id: str
    title: str
    version: str
    apk_path: Path
    sha256: str
    notes: str
    download_url: str


@dataclass(frozen=True)
class _PlatformReleaseCandidate:
    release_id: str
    distribution_id: str
    channel: str
    version: str
    version_code: int
    published_at: datetime
    apk_path: Path
    file_name: str
    sha256: str
    notes: str


def load_catalog(catalog_path: Path, artifact_root: Path) -> tuple[GameRelease, ...]:
    if catalog_path.stat().st_size > MAX_CATALOG_BYTES:
        raise ValueError("catalog is too large")
    source = json.loads(catalog_path.read_text(encoding="utf-8"))
    if set(source) != {"schemaVersion", "games"} or source["schemaVersion"] != 1:
        raise ValueError("unsupported catalog schema")
    games = source["games"]
    if not isinstance(games, list) or not games:
        raise ValueError("catalog must contain at least one game")

    root = artifact_root.resolve(strict=True)
    releases: list[GameRelease] = []
    seen_ids: set[str] = set()
    for item in games:
        if not isinstance(item, dict) or set(item) != {
            "id",
            "title",
            "version",
            "apk",
            "sha256",
            "notes",
            "downloadUrl",
        }:
            raise ValueError("catalog game has unexpected fields")
        game_id = _safe_text(item["id"], 32, "id")
        if not game_id.replace("-", "").isalnum() or game_id in seen_ids:
            raise ValueError("game id is invalid or duplicated")
        seen_ids.add(game_id)
        title = _safe_text(item["title"], 80, "title")
        version = _safe_text(item["version"], 40, "version")
        notes = _safe_text(item["notes"], 800, "notes", allow_empty=True)
        download_url = _safe_download_url(item["downloadUrl"])
        expected_hash = str(item["sha256"]).lower()
        if len(expected_hash) != 64 or any(char not in "0123456789abcdef" for char in expected_hash):
            raise ValueError("sha256 has an invalid format")

        relative_apk = Path(str(item["apk"]))
        if relative_apk.is_absolute() or relative_apk.suffix.lower() != ".apk":
            raise ValueError("apk must be a relative .apk path")
        apk_path = (root / relative_apk).resolve(strict=True)
        if root not in apk_path.parents or not apk_path.is_file():
            raise ValueError("apk path escapes artifact root")
        if any(not char.isprintable() or char in {'"', "\r", "\n"} for char in apk_path.name):
            raise ValueError("apk filename has an invalid format")
        if apk_path.stat().st_size > MAX_APK_BYTES:
            raise ValueError("apk exceeds configured Telegram delivery limit")
        actual_hash = sha256_file(apk_path)
        if not secrets.compare_digest(actual_hash, expected_hash):
            raise ValueError("apk sha256 does not match catalog")
        releases.append(
            GameRelease(
                game_id=game_id,
                title=title,
                version=version,
                apk_path=apk_path,
                sha256=actual_hash,
                notes=notes,
                download_url=download_url,
            ),
        )
    return tuple(releases)


def load_platform_catalog(catalog_path: Path, artifact_root: Path) -> tuple[GameRelease, ...]:
    """Loads the same validated release catalog consumed by Mirkori Platform."""
    if catalog_path.stat().st_size > MAX_PLATFORM_CATALOG_BYTES:
        raise ValueError("catalog is too large")
    source = json.loads(catalog_path.read_text(encoding="utf-8"))
    if (
        not isinstance(source, dict)
        or set(source) != {"schemaVersion", "games"}
        or not isinstance(source["schemaVersion"], int)
        or isinstance(source["schemaVersion"], bool)
        or source["schemaVersion"] not in PLATFORM_SCHEMA_VERSIONS
    ):
        raise ValueError("unsupported platform catalog schema")
    schema_version = source["schemaVersion"]
    games = source["games"]
    if not isinstance(games, list) or not games:
        raise ValueError("platform catalog must contain at least one game")

    root = artifact_root.resolve(strict=True)
    if not root.is_dir():
        raise ValueError("platform artifact root must be a directory")
    if schema_version == 1:
        return _load_legacy_platform_catalog(games, root)
    return _load_distribution_platform_catalog(games, root, schema_version)


def _load_legacy_platform_catalog(games: list[Any], root: Path) -> tuple[GameRelease, ...]:
    releases: list[GameRelease] = []
    seen_ids: set[str] = set()
    for game in games:
        if not isinstance(game, dict) or not {"id", "displayName", "releases"}.issubset(game):
            raise ValueError("platform catalog game is incomplete")
        game_id = _safe_text(game["id"], 64, "id")
        if not game_id.replace("-", "").isalnum() or game_id in seen_ids:
            raise ValueError("game id is invalid or duplicated")
        seen_ids.add(game_id)
        title = _safe_text(game["displayName"], 120, "displayName")
        candidates = game["releases"]
        if not isinstance(candidates, list):
            raise ValueError("platform releases must be a list")
        release = _select_platform_android_release(candidates)
        if release is not None:
            releases.append(_platform_release(game_id, title, release, root))
    if not releases:
        raise ValueError("platform catalog has no Android stable or beta release")
    return tuple(releases)


def _load_distribution_platform_catalog(
    games: list[Any],
    root: Path,
    schema_version: int,
) -> tuple[GameRelease, ...]:
    selected: list[GameRelease] = []
    seen_game_ids: set[str] = set()
    seen_release_ids: set[str] = set()
    seen_paths: set[Path] = set()
    seen_versions: set[tuple[str, str, str, int]] = set()
    expected_game_fields = {
        "id", "slug", "displayName", "description", "distributionVariants", "releases",
    } | ({"releasePolicies"} if schema_version == 3 else set())

    for game in games:
        if not isinstance(game, dict) or set(game) != expected_game_fields:
            raise ValueError("platform catalog game has unexpected fields")
        game_id = _platform_id(game["id"], "game id")
        if game_id in seen_game_ids:
            raise ValueError("game id is duplicated")
        seen_game_ids.add(game_id)
        _platform_id(game["slug"], "game slug")
        title = _platform_text(game["displayName"], 120, "displayName")
        _platform_text(game["description"], 1000, "description", allow_line_breaks=True)

        distributions = _platform_distributions(game["distributionVariants"])
        distributions_by_id = {item["id"]: item for item in distributions}
        direct_distribution = next(
            item for item in distributions
            if item["marketScope"] == "rf"
            and item["paymentChannel"] == "mirkori"
            and item["deliveryChannel"] == "direct_apk"
        )

        raw_releases = game["releases"]
        if not isinstance(raw_releases, list):
            raise ValueError("platform releases must be a list")
        candidates: list[_PlatformReleaseCandidate] = []
        for value in raw_releases:
            candidate = _platform_distribution_release(value, distributions_by_id, root)
            if candidate.release_id in seen_release_ids:
                raise ValueError("platform release id is duplicated")
            seen_release_ids.add(candidate.release_id)
            if candidate.apk_path in seen_paths:
                raise ValueError("platform artifact path is duplicated")
            seen_paths.add(candidate.apk_path)
            version_key = (game_id, candidate.distribution_id, candidate.channel, candidate.version_code)
            if version_key in seen_versions:
                raise ValueError("platform release version code is duplicated")
            seen_versions.add(version_key)
            candidates.append(candidate)

        lifecycle = (
            _platform_lifecycle_policies(game["releasePolicies"], candidates)
            if schema_version == 3
            else {candidate.release_id: "active" for candidate in candidates}
        )
        deliverable = [
            item for item in candidates
            if item.distribution_id == direct_distribution["id"]
            and item.channel in direct_distribution["releaseChannels"]
            and lifecycle[item.release_id] == "active"
        ]
        release = _select_distribution_release(deliverable)
        if release is not None:
            selected.append(_game_release_from_platform_candidate(game_id, title, release))

    if not selected:
        raise ValueError("platform catalog has no active RF direct APK stable or beta release")
    return tuple(selected)


def _platform_distributions(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError("platform catalog requires exactly two distribution variants")
    distributions: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict) or set(item) != PLATFORM_DISTRIBUTION_FIELDS:
            raise ValueError("platform distribution has unexpected fields")
        distribution_id = _platform_id(item["id"], "distribution id")
        if item["platform"] != "android":
            raise ValueError("platform distribution must target Android")
        combination = (item["marketScope"], item["paymentChannel"], item["deliveryChannel"])
        if combination not in {
            ("rf", "mirkori", "direct_apk"),
            ("global", "google_play", "google_play"),
        }:
            raise ValueError("platform distribution channels are incompatible")
        if item["status"] != "active":
            raise ValueError("platform distribution is not active")
        _platform_text(item["packageName"], 255, "packageName", PLATFORM_PACKAGE_PATTERN)
        _platform_id(item["signingIdentityRef"], "signingIdentityRef")
        fingerprints = item["certificateSha256Fingerprints"]
        if (
            not isinstance(fingerprints, list)
            or not fingerprints
            or len(set(fingerprints)) != len(fingerprints)
            or any(not isinstance(entry, str) or PLATFORM_FINGERPRINT_PATTERN.fullmatch(entry) is None for entry in fingerprints)
        ):
            raise ValueError("platform certificate fingerprints are invalid")
        channels = item["releaseChannels"]
        if (
            not isinstance(channels, list)
            or not channels
            or len(set(channels)) != len(channels)
            or any(channel not in PLATFORM_RELEASE_CHANNELS for channel in channels)
        ):
            raise ValueError("platform release channels are invalid")
        configuration_version = item["effectiveConfigurationVersion"]
        if not _positive_integer(configuration_version):
            raise ValueError("platform distribution configuration version is invalid")
        distributions.append({**item, "id": distribution_id, "releaseChannels": tuple(channels)})
    if len({item["id"] for item in distributions}) != 2 or {item["marketScope"] for item in distributions} != {"rf", "global"}:
        raise ValueError("platform catalog requires distinct RF and global distributions")
    return distributions


def _platform_distribution_release(
    value: Any,
    distributions: dict[str, dict[str, Any]],
    artifact_root: Path,
) -> _PlatformReleaseCandidate:
    if not isinstance(value, dict) or set(value) != PLATFORM_DISTRIBUTION_RELEASE_FIELDS:
        raise ValueError("platform release has unexpected fields")
    release_id = _platform_id(value["id"], "release id")
    distribution_id = _platform_id(value["distributionId"], "distribution id")
    distribution = distributions.get(distribution_id)
    if distribution is None:
        raise ValueError("platform release references an unknown distribution")
    channel = value["channel"]
    if channel not in distribution["releaseChannels"]:
        raise ValueError("platform release channel is not enabled")
    version_code = value["versionCode"]
    minimum_supported = value["minimumSupportedVersionCode"]
    minimum_android_sdk = value["minimumAndroidSdk"]
    if not _positive_integer(version_code) or not _positive_integer(minimum_supported) or minimum_supported > version_code:
        raise ValueError("platform release version codes are invalid")
    if not isinstance(minimum_android_sdk, int) or isinstance(minimum_android_sdk, bool) or minimum_android_sdk not in range(21, 101):
        raise ValueError("platform release minimum Android SDK is invalid")
    changelogs = value["changelogs"]
    if not isinstance(changelogs, dict) or set(changelogs) != {"ru", "en"}:
        raise ValueError("platform release requires RU and EN changelogs")
    notes = _platform_text(changelogs["ru"], 4000, "Russian changelog", allow_line_breaks=True)
    _platform_text(changelogs["en"], 4000, "English changelog", allow_line_breaks=True)
    file_name = _platform_text(value["fileName"], 128, "fileName", PLATFORM_FILE_NAME_PATTERN)
    if not file_name.lower().endswith(".apk"):
        raise ValueError("platform Android artifact must be an APK")
    apk_path = _platform_artifact_path(artifact_root, value["relativePath"], file_name)
    declared_size = value["sizeBytes"]
    if not _positive_integer(declared_size) or declared_size > MAX_APK_BYTES or apk_path.stat().st_size != declared_size:
        raise ValueError("platform APK size does not match catalog")
    expected_hash = str(value["sha256"]).lower()
    if len(expected_hash) != 64 or any(char not in "0123456789abcdef" for char in expected_hash):
        raise ValueError("sha256 has an invalid format")
    actual_hash = sha256_file(apk_path)
    if not secrets.compare_digest(actual_hash, expected_hash):
        raise ValueError("platform APK sha256 does not match catalog")
    return _PlatformReleaseCandidate(
        release_id=release_id,
        distribution_id=distribution_id,
        channel=channel,
        version=_platform_text(value["versionName"], 64, "versionName"),
        version_code=version_code,
        published_at=_platform_instant(value["publishedAt"], "publishedAt"),
        apk_path=apk_path,
        file_name=file_name,
        sha256=actual_hash,
        notes=notes,
    )


def _platform_lifecycle_policies(
    value: Any,
    releases: list[_PlatformReleaseCandidate],
) -> dict[str, str]:
    if not isinstance(value, list):
        raise ValueError("platform release policies must be a list")
    releases_by_id = {release.release_id: release for release in releases}
    policies: dict[str, str] = {}
    for item in value:
        if not isinstance(item, dict):
            raise ValueError("platform release policy must be an object")
        status = item.get("status")
        expected_fields = PLATFORM_LIFECYCLE_FIELDS | ({"reasonCode", "supportPath"} if status == "recalled" else set())
        if set(item) != expected_fields or status not in {"active", "delisted", "recalled"}:
            raise ValueError("platform release policy has unexpected fields")
        release_id = _platform_id(item["releaseId"], "release policy id")
        release = releases_by_id.get(release_id)
        if release is None or release_id in policies:
            raise ValueError("platform release policy coverage is invalid")
        effective_at = _platform_instant(item["effectiveAt"], "release policy effectiveAt")
        if effective_at < release.published_at or not _positive_integer(item["policyVersion"]):
            raise ValueError("platform release policy version or time is invalid")
        if status == "recalled":
            _platform_text(item["reasonCode"], 64, "reasonCode", PLATFORM_REASON_CODE_PATTERN)
            support_path = _platform_text(item["supportPath"], 255, "supportPath", PLATFORM_SUPPORT_PATH_PATTERN)
            if support_path.startswith("//") or "?" in support_path or "#" in support_path or any(
                segment in {"", ".", ".."} for segment in support_path.split("/")[1:]
            ):
                raise ValueError("platform release recall support path is invalid")
        policies[release_id] = status
    if policies.keys() != releases_by_id.keys():
        raise ValueError("platform release policies must cover every release exactly once")
    return policies


def _select_distribution_release(
    candidates: list[_PlatformReleaseCandidate],
) -> _PlatformReleaseCandidate | None:
    for channel in PLATFORM_RELEASE_CHANNELS:
        matching = [item for item in candidates if item.channel == channel]
        if matching:
            return max(matching, key=lambda item: (item.version_code, item.release_id))
    return None


def _game_release_from_platform_candidate(
    game_id: str,
    title: str,
    item: _PlatformReleaseCandidate,
) -> GameRelease:
    return GameRelease(
        game_id=game_id,
        title=title,
        version=item.version,
        apk_path=item.apk_path,
        sha256=item.sha256,
        notes=item.notes,
        download_url=_safe_download_url(
            f"https://games.dmit.life/downloads/{urllib.parse.quote(item.release_id, safe='')}/"
            f"{urllib.parse.quote(item.file_name, safe='')}",
        ),
    )


def _platform_artifact_path(artifact_root: Path, value: Any, file_name: str) -> Path:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("platform artifact path is invalid")
    segments = value.split("/")
    if any(segment in {"", ".", ".."} for segment in segments) or segments[-1] != file_name:
        raise ValueError("platform artifact path is invalid")
    relative = Path(*segments)
    if relative.is_absolute():
        raise ValueError("platform artifact path is invalid")
    apk_path = (artifact_root / relative).resolve(strict=True)
    if artifact_root not in apk_path.parents or not apk_path.is_file():
        raise ValueError("platform artifact path escapes artifact root")
    return apk_path


def _platform_id(value: Any, name: str) -> str:
    return _platform_text(value, 64, name, PLATFORM_ID_PATTERN)


def _platform_text(
    value: Any,
    maximum: int,
    name: str,
    pattern: re.Pattern[str] | None = None,
    allow_line_breaks: bool = False,
) -> str:
    invalid_character = lambda char: not char.isprintable() and not (allow_line_breaks and char in {"\r", "\n", "\t"})
    if (
        not isinstance(value, str)
        or not value
        or len(value) > maximum
        or value != value.strip()
        or any(invalid_character(char) for char in value)
        or pattern is not None and pattern.fullmatch(value) is None
    ):
        raise ValueError(f"{name} has an invalid format")
    return value


def _platform_instant(value: Any, name: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{name} has an invalid format")
    try:
        instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{name} has an invalid format") from error
    if instant.tzinfo is None:
        raise ValueError(f"{name} has an invalid format")
    return instant


def _positive_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _select_platform_android_release(candidates: list[Any]) -> dict[str, Any] | None:
    android = [
        item for item in candidates
        if isinstance(item, dict)
        and item.get("platform") == "android"
        and item.get("channel") in PLATFORM_RELEASE_CHANNELS
        and isinstance(item.get("versionCode"), int)
        and not isinstance(item.get("versionCode"), bool)
        and item["versionCode"] > 0
    ]
    for channel in PLATFORM_RELEASE_CHANNELS:
        matching = [item for item in android if item["channel"] == channel]
        if matching:
            return max(matching, key=lambda item: (item["versionCode"], str(item.get("id", ""))))
    return None


def _platform_release(
    game_id: str,
    title: str,
    item: dict[str, Any],
    artifact_root: Path,
) -> GameRelease:
    required = {
        "id", "versionName", "fileName", "relativePath", "sizeBytes",
        "sha256", "changelog",
    }
    if not required.issubset(item):
        raise ValueError("platform release is incomplete")
    release_id = _safe_text(item["id"], 64, "release id")
    version = _safe_text(item["versionName"], 64, "versionName")
    notes = _safe_text(item["changelog"], 4000, "changelog")
    file_name = _safe_text(item["fileName"], 128, "fileName")
    if not file_name.lower().endswith(".apk"):
        raise ValueError("platform Android artifact must be an APK")
    expected_hash = str(item["sha256"]).lower()
    if len(expected_hash) != 64 or any(char not in "0123456789abcdef" for char in expected_hash):
        raise ValueError("sha256 has an invalid format")
    relative_apk = Path(str(item["relativePath"]))
    if relative_apk.is_absolute() or relative_apk.name != file_name:
        raise ValueError("platform artifact path is invalid")
    apk_path = (artifact_root / relative_apk).resolve(strict=True)
    if artifact_root not in apk_path.parents or not apk_path.is_file():
        raise ValueError("platform artifact path escapes artifact root")
    declared_size = item["sizeBytes"]
    if (
        not isinstance(declared_size, int)
        or isinstance(declared_size, bool)
        or declared_size < 1
        or declared_size > MAX_APK_BYTES
        or apk_path.stat().st_size != declared_size
    ):
        raise ValueError("platform APK size does not match catalog")
    actual_hash = sha256_file(apk_path)
    if not secrets.compare_digest(actual_hash, expected_hash):
        raise ValueError("platform APK sha256 does not match catalog")
    return GameRelease(
        game_id=game_id,
        title=title,
        version=version,
        apk_path=apk_path,
        sha256=actual_hash,
        notes=notes,
        download_url=_safe_download_url(
            f"https://games.dmit.life/downloads/{urllib.parse.quote(release_id, safe='')}/"
            f"{urllib.parse.quote(file_name, safe='')}",
        ),
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_release_file(release: GameRelease) -> None:
    if not release.apk_path.is_file() or release.apk_path.stat().st_size > MAX_APK_BYTES:
        raise ValueError("release APK is unavailable")
    if not secrets.compare_digest(sha256_file(release.apk_path), release.sha256):
        raise ValueError("release APK changed after catalog validation")


def _safe_download_url(value: Any) -> str:
    source = _safe_text(value, 512, "downloadUrl")
    parsed = urllib.parse.urlsplit(source)
    if (
        parsed.scheme != "https"
        or parsed.hostname not in {"inplacex.dmit.life", "games.dmit.life"}
        or parsed.port not in {None, 443}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or not parsed.path.startswith("/downloads/")
        or not parsed.path.lower().endswith(".apk")
    ):
        raise ValueError("downloadUrl is not an approved HTTPS APK URL")
    return source


def parse_allowed_chat_ids(source: str) -> frozenset[int]:
    values = [value.strip() for value in source.split(",") if value.strip()]
    return frozenset(int(value) for value in values)


def is_chat_allowed(chat_id: int, allowed_chat_ids: frozenset[int], public_downloads: bool) -> bool:
    return public_downloads or chat_id in allowed_chat_ids


class TelegramApi:
    def __init__(self, token: str, timeout_seconds: int = 45) -> None:
        if not token or any(char.isspace() for char in token):
            raise ValueError("Telegram token has an invalid format")
        self._base_url = f"https://api.telegram.org/bot{token}"
        self._timeout_seconds = timeout_seconds

    def call(self, method: str, payload: dict[str, Any]) -> dict[str, Any]:
        data = urllib.parse.urlencode(
            {
                key: json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else str(value)
                for key, value in payload.items()
            },
        ).encode("utf-8")
        request = urllib.request.Request(
            f"{self._base_url}/{method}",
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        return self._read_response(request)

    def send_download_link(self, chat_id: int, release: GameRelease) -> dict[str, Any]:
        verify_release_file(release)
        return self.call(
            "sendMessage",
            {
                "chat_id": chat_id,
                "text": (
                    f"{release.title} {release.version}\n"
                    f"{release.notes}\n"
                    f"SHA-256: {release.sha256}"
                ).strip(),
                "reply_markup": {
                    "inline_keyboard": [
                        [{"text": "Скачать APK", "url": release.download_url}],
                    ],
                },
            },
        )

    def _read_response(self, request: urllib.request.Request) -> dict[str, Any]:
        with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if payload.get("ok") is not True:
            raise RuntimeError("Telegram API rejected request")
        return payload


class MirkoriGamesBot:
    def __init__(
        self,
        api: TelegramApi,
        releases: tuple[GameRelease, ...],
        allowed_chat_ids: frozenset[int],
        public_downloads: bool,
        offset_file: Path,
    ) -> None:
        self._api = api
        self._releases = {release.game_id: release for release in releases}
        self._allowed_chat_ids = allowed_chat_ids
        self._public_downloads = public_downloads
        self._offset_file = offset_file

    def run_forever(self) -> None:
        offset = self._read_offset()
        while True:
            try:
                response = self._api.call(
                    "getUpdates",
                    {
                        "offset": offset,
                        "timeout": 30,
                        "allowed_updates": ["message", "callback_query"],
                    },
                )
                for update in response.get("result", []):
                    update_id = int(update["update_id"])
                    self._handle_update(update)
                    offset = max(offset, update_id + 1)
                    self._write_offset(offset)
            except (OSError, urllib.error.URLError, RuntimeError, ValueError, json.JSONDecodeError):
                time.sleep(3)

    def _handle_update(self, update: dict[str, Any]) -> None:
        if "message" in update:
            message = update["message"]
            chat_id = int(message["chat"]["id"])
            if not self._authorized(chat_id):
                return
            text = str(message.get("text", "")).strip()
            if text in {"/start", "/games"}:
                self._send_catalog(chat_id)
            elif text.startswith("/"):
                game_id = text[1:].split("@", 1)[0].lower()
                release = self._releases.get(game_id)
                if release is not None:
                    self._api.send_download_link(chat_id, release)
        elif "callback_query" in update:
            query = update["callback_query"]
            chat_id = int(query["message"]["chat"]["id"])
            if not self._authorized(chat_id):
                return
            data = str(query.get("data", ""))
            if data.startswith("download:"):
                release = self._releases.get(data.removeprefix("download:"))
                if release is not None:
                    self._api.call("answerCallbackQuery", {"callback_query_id": query["id"]})
                    self._api.send_download_link(chat_id, release)

    def _send_catalog(self, chat_id: int) -> None:
        buttons = [
            [{"text": f"Скачать {release.title} {release.version}", "url": release.download_url}]
            for release in self._releases.values()
        ]
        self._api.call(
            "sendMessage",
            {
                "chat_id": chat_id,
                "text": "Mirkori Games\nВыберите проверенную сборку:",
                "reply_markup": {"inline_keyboard": buttons},
            },
        )

    def _authorized(self, chat_id: int) -> bool:
        return is_chat_allowed(chat_id, self._allowed_chat_ids, self._public_downloads)

    def _read_offset(self) -> int:
        if not self._offset_file.exists():
            return 0
        if self._offset_file.stat().st_size > MAX_UPDATE_OFFSET_FILE_BYTES:
            raise ValueError("offset file is invalid")
        return max(0, int(self._offset_file.read_text(encoding="ascii").strip()))

    def _write_offset(self, offset: int) -> None:
        self._offset_file.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._offset_file.with_suffix(".tmp")
        temporary.write_text(str(offset), encoding="ascii")
        temporary.replace(self._offset_file)


def _safe_text(value: Any, maximum: int, name: str, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{name} must be a string")
    if (not allow_empty and not value) or len(value) > maximum or any(not char.isprintable() for char in value):
        raise ValueError(f"{name} has an invalid format")
    return value


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    catalog = parser.add_mutually_exclusive_group(required=True)
    catalog.add_argument("--catalog", type=Path)
    catalog.add_argument("--platform-catalog", type=Path)
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument("--offset-file", type=Path, default=Path("state/update-offset"))
    parser.add_argument("--validate-catalog", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    releases = (
        load_platform_catalog(args.platform_catalog, args.artifact_root)
        if args.platform_catalog is not None
        else load_catalog(args.catalog, args.artifact_root)
    )
    if args.validate_catalog:
        print(f"catalog=valid games={len(releases)}")
        return 0

    token = os.environ.get("MIRKORI_GAMES_TELEGRAM_BOT_TOKEN", "")
    allowed_chat_ids = parse_allowed_chat_ids(os.environ.get("MIRKORI_GAMES_ALLOWED_CHAT_IDS", ""))
    public_downloads = os.environ.get("MIRKORI_GAMES_PUBLIC_DOWNLOADS", "false").lower() == "true"
    if not public_downloads and not allowed_chat_ids:
        raise RuntimeError("configure allowed chat ids or explicitly enable public downloads")
    MirkoriGamesBot(
        api=TelegramApi(token),
        releases=releases,
        allowed_chat_ids=allowed_chat_ids,
        public_downloads=public_downloads,
        offset_file=args.offset_file,
    ).run_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
