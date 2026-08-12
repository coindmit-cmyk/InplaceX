#!/usr/bin/env python3
"""Минимальный Telegram-каталог проверенных Android-сборок Mirkori Games."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


MAX_CATALOG_BYTES = 256 * 1024
MAX_APK_BYTES = 2 * 1024 * 1024 * 1024
MAX_UPDATE_OFFSET_FILE_BYTES = 64
PLATFORM_RELEASE_CHANNELS = ("stable", "beta")


@dataclass(frozen=True)
class GameRelease:
    game_id: str
    title: str
    version: str
    apk_path: Path
    sha256: str
    notes: str
    download_url: str


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
    if catalog_path.stat().st_size > MAX_CATALOG_BYTES:
        raise ValueError("catalog is too large")
    source = json.loads(catalog_path.read_text(encoding="utf-8"))
    if set(source) != {"schemaVersion", "games"} or source["schemaVersion"] != 1:
        raise ValueError("unsupported platform catalog schema")
    games = source["games"]
    if not isinstance(games, list) or not games:
        raise ValueError("platform catalog must contain at least one game")

    root = artifact_root.resolve(strict=True)
    releases: list[GameRelease] = []
    seen_ids: set[str] = set()
    for game in games:
        if not isinstance(game, dict) or not {"id", "displayName", "releases"}.issubset(game):
            raise ValueError("platform catalog game is incomplete")
        game_id = _safe_text(game["id"], 32, "id")
        if not game_id.replace("-", "").isalnum() or game_id in seen_ids:
            raise ValueError("game id is invalid or duplicated")
        seen_ids.add(game_id)
        title = _safe_text(game["displayName"], 80, "displayName")
        candidates = game["releases"]
        if not isinstance(candidates, list):
            raise ValueError("platform releases must be a list")
        release = _select_platform_android_release(candidates)
        if release is not None:
            releases.append(_platform_release(game_id, title, release, root))
    if not releases:
        raise ValueError("platform catalog has no Android stable or beta release")
    return tuple(releases)


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
    release_id = _safe_text(item["id"], 96, "release id")
    version = _safe_text(item["versionName"], 40, "versionName")
    notes = _safe_text(item["changelog"], 800, "changelog", allow_empty=True)
    file_name = _safe_text(item["fileName"], 160, "fileName")
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
