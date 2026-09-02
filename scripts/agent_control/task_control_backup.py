#!/usr/bin/env python3
"""Create and verify durable pg_dump backups for Task Control PostgreSQL."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from runtime_state_io import write_json_atomic
from task_control_postgres import (
    TaskControlConfigurationError,
    TaskControlConflict,
    TaskControlPostgres,
)


def dsn_database_name(dsn: str) -> str:
    parsed = urlparse(dsn)
    name = parsed.path.lstrip("/")
    return name or "unknown"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_manifest(manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    backup_path = Path(str(manifest.get("backup_path") or ""))
    expected = str(manifest.get("sha256") or "")
    if not backup_path.is_file():
        return {"ok": False, "reason": "backup_missing", "backup_path": str(backup_path)}
    actual = sha256_file(backup_path)
    return {
        "ok": actual == expected,
        "reason": "verified" if actual == expected else "checksum_mismatch",
        "backup_path": str(backup_path),
        "sha256": actual,
        "size_bytes": backup_path.stat().st_size,
    }


def create_backup(
    *,
    dsn: str,
    output_dir: Path,
    manifest_path: Path | None,
    keep: int,
) -> dict[str, object]:
    if keep < 1:
        raise TaskControlConfigurationError("keep must be positive")
    output_dir = output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_id = f"task-control-{timestamp}-{uuid.uuid4().hex[:8]}"
    backup_path = output_dir / f"{backup_id}.dump"
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{backup_id}.",
        suffix=".dump.tmp",
        dir=output_dir,
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        database = TaskControlPostgres(dsn)
        with database.backup_snapshot_guard() as backup_guard:
            database_health_before = database.health()
            command = [
                "pg_dump",
                "--dbname",
                dsn,
                "--format=custom",
                "--no-owner",
                "--no-acl",
                "--file",
                str(temporary),
            ]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            if result.returncode != 0:
                raise RuntimeError(f"pg_dump failed: {result.stderr.strip()}")
            if temporary.stat().st_size <= 0:
                raise RuntimeError("pg_dump produced an empty backup")
            database_health_after = database.health()
            # PostgreSQL watermarks retain microseconds. The backup timestamp
            # must do the same or a dump completed in the same wall-clock
            # second can compare older than the shadow snapshot it contains.
            backup_created_at = datetime.now(timezone.utc).isoformat().replace(
                "+00:00", "Z"
            )
        identity_before = (
            database_health_before.get("database"),
            database_health_before.get("cluster_system_identifier"),
        )
        identity_after = (
            database_health_after.get("database"),
            database_health_after.get("cluster_system_identifier"),
        )
        if not identity_before[1] or identity_before != identity_after:
            raise TaskControlConflict(
                "database cluster identity changed while backup was being created"
            )
        temporary.replace(backup_path)
        digest = sha256_file(backup_path)
        manifest = {
            "schema_version": 2,
            "backup_id": backup_id,
            "created_at": backup_created_at,
            "database_name": identity_before[0],
            "cluster_system_identifier": identity_before[1],
            "last_shadow_sync": backup_guard["last_shadow_sync"],
            "schema_name": "task_control",
            "dump_format": "custom",
            "backup_path": str(backup_path),
            "sha256": digest,
            "size_bytes": backup_path.stat().st_size,
            "verified": True,
        }
        manifest_path = (
            manifest_path.expanduser().resolve()
            if manifest_path is not None
            else output_dir / "latest.manifest.json"
        )
        write_json_atomic(manifest_path, manifest)
        database.record_backup(
            backup_id=backup_id,
            created_at=str(manifest["created_at"]),
            database_name=str(manifest["database_name"]),
            sha256=digest,
            size_bytes=int(manifest["size_bytes"]),
            verified=True,
            metadata={
                "manifest_path": str(manifest_path),
                "cluster_system_identifier": manifest["cluster_system_identifier"],
            },
        )
        old_backups = sorted(
            output_dir.glob("task-control-*.dump"),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        removed: list[str] = []
        for old in old_backups[keep:]:
            old.unlink()
            removed.append(str(old))
        return {
            "ok": True,
            "backup": manifest,
            "manifest_path": str(manifest_path),
            "removed_old_backups": removed,
        }
    finally:
        temporary.unlink(missing_ok=True)


def restore_backup(
    *,
    manifest_path: Path,
    target_dsn: str,
    confirm_target_database: str,
) -> dict[str, object]:
    verification = verify_manifest(manifest_path)
    if not verification["ok"]:
        raise TaskControlConfigurationError(f"backup verification failed: {verification['reason']}")
    target_database = dsn_database_name(target_dsn)
    if target_database != confirm_target_database:
        raise TaskControlConfigurationError("target database confirmation does not match DSN")
    if not target_database.startswith("aistudio_task_control_restore_"):
        raise TaskControlConfigurationError(
            "MVP restore target must use the aistudio_task_control_restore_ prefix"
        )
    command = [
        "pg_restore",
        "--dbname",
        target_dsn,
        "--no-owner",
        "--no-acl",
        "--exit-on-error",
        str(verification["backup_path"]),
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"pg_restore failed: {result.stderr.strip()}")
    health = TaskControlPostgres(target_dsn).health()
    return {
        "ok": True,
        "target_database": target_database,
        "verification": verification,
        "health": health,
    }


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--dsn-env", default="AISTUDIO_TASK_DB_DSN")
    value.add_argument("--json", action="store_true")
    subparsers = value.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create")
    create.add_argument("--output-dir", type=Path, required=True)
    create.add_argument("--manifest", type=Path)
    create.add_argument("--keep", type=int, default=14)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--manifest", type=Path, required=True)
    restore = subparsers.add_parser("restore")
    restore.add_argument("--manifest", type=Path, required=True)
    restore.add_argument("--target-dsn-env", required=True)
    restore.add_argument("--confirm-target-database", required=True)
    restore.add_argument("--apply", action="store_true")
    return value


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "create":
            dsn = os.environ.get(args.dsn_env, "")
            if not dsn:
                raise TaskControlConfigurationError(
                    f"required DSN environment variable is missing: {args.dsn_env}"
                )
            report = create_backup(
                dsn=dsn,
                output_dir=args.output_dir,
                manifest_path=args.manifest,
                keep=args.keep,
            )
        elif args.command == "verify":
            report = verify_manifest(args.manifest.expanduser().resolve())
        else:
            if not args.apply:
                raise TaskControlConfigurationError("restore requires --apply")
            target_dsn = os.environ.get(args.target_dsn_env, "")
            if not target_dsn:
                raise TaskControlConfigurationError(
                    f"required target DSN environment variable is missing: {args.target_dsn_env}"
                )
            report = restore_backup(
                manifest_path=args.manifest.expanduser().resolve(),
                target_dsn=target_dsn,
                confirm_target_database=args.confirm_target_database,
            )
    except Exception as exc:
        report = {"ok": False, "error": {"type": type(exc).__name__, "message": str(exc)}}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main())
