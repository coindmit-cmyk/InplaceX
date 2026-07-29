#!/usr/bin/env python3
"""Атомарно публикует проверенный APK в каталог Mirkori Games."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import urllib.parse
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--game-id", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--download-url", required=True)
    args = parser.parse_args()

    source = args.apk.resolve(strict=True)
    if source.suffix.lower() != ".apk":
        raise ValueError("source must be an APK")
    if not args.game_id.replace("-", "").isalnum():
        raise ValueError("game id has an invalid format")
    if not args.version or len(args.version) > 40 or any(
        char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
        for char in args.version
    ):
        raise ValueError("version has an invalid format")
    parsed_download_url = urllib.parse.urlsplit(args.download_url)
    if (
        parsed_download_url.scheme != "https"
        or parsed_download_url.hostname not in {"inplacex.dmit.life", "games.dmit.life"}
        or parsed_download_url.port not in {None, 443}
        or parsed_download_url.username is not None
        or parsed_download_url.password is not None
        or parsed_download_url.query
        or parsed_download_url.fragment
        or not parsed_download_url.path.startswith("/downloads/")
        or not parsed_download_url.path.lower().endswith(".apk")
    ):
        raise ValueError("download URL is not an approved HTTPS APK URL")

    artifact_root = args.artifact_root.resolve()
    game_directory = artifact_root / args.game_id
    game_directory.mkdir(parents=True, exist_ok=True)
    filename = f"{args.game_id}-{args.version}.apk"
    target = game_directory / filename
    source_hash = sha256_file(source)
    if target.exists() and sha256_file(target) != source_hash:
        raise FileExistsError("release path already contains a different APK")
    if not target.exists():
        temporary_apk = target.with_suffix(".apk.tmp")
        shutil.copyfile(source, temporary_apk)
        if sha256_file(temporary_apk) != source_hash:
            temporary_apk.unlink(missing_ok=True)
            raise OSError("copied APK hash does not match source")
        temporary_apk.replace(target)

    catalog = {"schemaVersion": 1, "games": []}
    if args.catalog.exists():
        catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    games = [game for game in catalog.get("games", []) if game.get("id") != args.game_id]
    games.append(
        {
            "id": args.game_id,
            "title": args.title,
            "version": args.version,
            "apk": target.relative_to(artifact_root).as_posix(),
            "sha256": source_hash,
            "notes": args.notes,
            "downloadUrl": args.download_url,
        },
    )
    catalog = {"schemaVersion": 1, "games": sorted(games, key=lambda game: game["id"])}
    args.catalog.parent.mkdir(parents=True, exist_ok=True)
    temporary_catalog = args.catalog.with_suffix(".json.tmp")
    temporary_catalog.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary_catalog.replace(args.catalog)
    print(f"published={args.game_id} version={args.version} sha256={source_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
