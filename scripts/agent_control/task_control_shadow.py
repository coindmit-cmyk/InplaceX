#!/usr/bin/env python3
"""Shadow-import JSON/Git Task Manager state into PostgreSQL without cutover."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

from runtime_state_io import write_json_atomic
from task_control_postgres import (
    TaskControlConfigurationError,
    TaskControlPostgres,
    TaskSnapshot,
    load_migrations,
    normalized_task_id,
    payload_digest,
    require_project_id,
    snapshot_source_records,
    utc_now,
)


DEFAULT_CONFIG = Path("~/.config/aistudio/task-control.json").expanduser()


@dataclass(frozen=True)
class ShadowConfig:
    mode: str
    source_of_truth: str
    cutover_enabled: bool
    dsn_env: str
    registry_path: Path
    required_project_ids: tuple[str, ...]
    include_disabled_projects: bool
    max_shadow_age_seconds: int
    backup_manifest: Path | None


def load_json(path: Path, *, default: dict[str, Any] | None = None) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        if default is not None:
            return default
        raise TaskControlConfigurationError(f"required JSON file is missing: {path}") from None
    except json.JSONDecodeError as exc:
        raise TaskControlConfigurationError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise TaskControlConfigurationError(f"JSON root must be an object: {path}")
    return value


def load_config(path: Path) -> ShadowConfig:
    raw = load_json(path)
    mode = str(raw.get("mode") or "")
    source_of_truth = str(raw.get("source_of_truth") or "")
    cutover_enabled = raw.get("cutover_enabled")
    if mode != "shadow" or source_of_truth != "json_git" or cutover_enabled is not False:
        raise TaskControlConfigurationError(
            "MVP config must remain mode=shadow, source_of_truth=json_git, cutover_enabled=false"
        )
    dsn_env = str(raw.get("dsn_env") or "")
    if not dsn_env or not dsn_env.replace("_", "").isalnum():
        raise TaskControlConfigurationError("dsn_env must name an environment variable")
    registry_value = str(raw.get("registry_path") or "")
    if not registry_value:
        raise TaskControlConfigurationError("registry_path is required")
    required = raw.get("required_project_ids") or []
    if not isinstance(required, list):
        raise TaskControlConfigurationError("required_project_ids must be an array")
    required_ids = tuple(require_project_id(str(project_id)) for project_id in required)
    max_age = int(raw.get("max_shadow_age_seconds") or 900)
    if max_age < 60:
        raise TaskControlConfigurationError("max_shadow_age_seconds must be at least 60")
    manifest_value = str(raw.get("backup_manifest") or "").strip()
    return ShadowConfig(
        mode=mode,
        source_of_truth=source_of_truth,
        cutover_enabled=False,
        dsn_env=dsn_env,
        registry_path=Path(registry_value).expanduser(),
        required_project_ids=required_ids,
        include_disabled_projects=raw.get("include_disabled_projects") is True,
        max_shadow_age_seconds=max_age,
        backup_manifest=Path(manifest_value).expanduser() if manifest_value else None,
    )


def git_revision(project_root: Path) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(project_root), "rev-parse", "HEAD"],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def load_task_document(
    path: Path,
    *,
    required: bool,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    payload = load_json(path, default=None if required else {"schema_version": 1, "tasks": []})
    tasks = payload.get("tasks")
    if not isinstance(tasks, list):
        raise TaskControlConfigurationError(f"tasks must be an array: {path}")
    rows: list[dict[str, Any]] = []
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            raise TaskControlConfigurationError(f"{path}: tasks[{index}] must be an object")
        normalized_task_id(task)
        rows.append(task)
    return payload, rows


def build_snapshot(
    *,
    project_id: str,
    project_root: Path,
    repository: str | None,
    base_branch: str,
) -> TaskSnapshot:
    require_project_id(project_id)
    project_root = project_root.expanduser().resolve()
    queue_path = project_root / "AiStudio" / "Task_manager" / "task_queue.json"
    history_path = queue_path.with_name("task_history.json")
    queue_document, queue_rows = load_task_document(queue_path, required=True)
    history_document, history_rows = load_task_document(history_path, required=False)
    source_documents = {
        "queue": {key: value for key, value in queue_document.items() if key != "tasks"},
        "history": {key: value for key, value in history_document.items() if key != "tasks"},
    }
    records: dict[str, dict[str, Any]] = {}
    source_records: dict[tuple[str, str], dict[str, Any]] = {}
    duplicates: set[str] = set()
    for source_kind, rows in (("history", history_rows), ("queue", queue_rows)):
        for source_ordinal, task in enumerate(rows):
            task_id = normalized_task_id(task)
            source_key = (task_id, source_kind)
            if source_key in source_records:
                raise TaskControlConfigurationError(
                    f"duplicate task id {task_id!r} inside {source_kind}: {project_root}"
                )
            record = {
                "source_kind": source_kind,
                "source_ordinal": source_ordinal,
                "source_digest": payload_digest(task),
                "payload": task,
            }
            source_records[source_key] = record
            if task_id in records:
                duplicates.add(task_id)
            records[task_id] = record
    revision = git_revision(project_root)
    source_digest = payload_digest(
        {
            "project_id": project_id,
            "revision": revision,
            "source_documents": source_documents,
            "source_records": [
                (
                    task_id,
                    source_kind,
                    record["source_ordinal"],
                    record["source_digest"],
                )
                for (task_id, source_kind), record in sorted(source_records.items())
            ],
        }
    )
    return TaskSnapshot(
        project_id=project_id,
        repository=repository,
        base_branch=base_branch,
        source_revision=revision,
        source_digest=source_digest,
        tasks=records,
        source_records=source_records,
        source_documents=source_documents,
        duplicate_task_ids=tuple(sorted(duplicates)),
    )


def registry_projects(config: ShadowConfig) -> list[dict[str, Any]]:
    registry = load_json(config.registry_path)
    projects = registry.get("projects")
    if not isinstance(projects, list):
        raise TaskControlConfigurationError("registry projects must be an array")
    selected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in projects:
        if not isinstance(item, dict):
            raise TaskControlConfigurationError("registry project must be an object")
        project_id = require_project_id(str(item.get("project_id") or item.get("id") or ""))
        if project_id in seen:
            raise TaskControlConfigurationError(f"duplicate registry project_id: {project_id}")
        seen.add(project_id)
        if item.get("enabled") is False and not config.include_disabled_projects:
            continue
        local_path = (
            item.get("automation_path")
            if item.get("automation_path_managed") is True
            else None
        ) or item.get("local_path") or item.get("project_root")
        if not isinstance(local_path, str) or not local_path:
            raise TaskControlConfigurationError(f"project {project_id} has no local_path")
        selected.append(
            {
                "project_id": project_id,
                "project_root": Path(local_path),
                "repository": item.get("repository") or item.get("github_repo"),
                "base_branch": str(item.get("base_branch") or "develop"),
            }
        )
    missing_required = sorted(set(config.required_project_ids) - {item["project_id"] for item in selected})
    if missing_required:
        raise TaskControlConfigurationError(
            f"required projects are absent or disabled in registry: {missing_required}"
        )
    return selected


def build_fleet_snapshots(config: ShadowConfig) -> list[TaskSnapshot]:
    return [
        build_snapshot(
            project_id=item["project_id"],
            project_root=item["project_root"],
            repository=item["repository"],
            base_branch=item["base_branch"],
        )
        for item in registry_projects(config)
    ]


def snapshot_summary(snapshot: TaskSnapshot) -> dict[str, Any]:
    source_records = snapshot_source_records(snapshot)
    return {
        "project_id": snapshot.project_id,
        "source_revision": snapshot.source_revision,
        "source_digest": snapshot.source_digest,
        "queue_tasks": sum(1 for row in source_records.values() if row["source_kind"] == "queue"),
        "history_tasks": sum(1 for row in source_records.values() if row["source_kind"] == "history"),
        "duplicate_task_ids": list(snapshot.duplicate_task_ids),
    }


def verify_backup_manifest(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {"ok": False, "reason": "backup_manifest_not_configured"}
    try:
        manifest = load_json(path)
        backup_path = Path(str(manifest.get("backup_path") or "")).expanduser()
        expected = str(manifest.get("sha256") or "")
        if not backup_path.is_file() or len(expected) != 64:
            return {"ok": False, "reason": "backup_or_checksum_missing", "manifest": str(path)}
        digest = hashlib.sha256(backup_path.read_bytes()).hexdigest()
        return {
            "ok": digest == expected,
            "reason": "verified" if digest == expected else "checksum_mismatch",
            "manifest": str(path),
            "backup": str(backup_path),
            "sha256": digest,
        }
    except (OSError, TaskControlConfigurationError) as exc:
        return {"ok": False, "reason": str(exc), "manifest": str(path)}


def parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed.astimezone(timezone.utc)


def cutover_readiness(
    database: TaskControlPostgres,
    config: ShadowConfig,
    snapshots: Sequence[TaskSnapshot],
) -> dict[str, Any]:
    health = database.health()
    reconciliations = [database.reconcile_snapshot(snapshot) for snapshot in snapshots]
    backup = verify_backup_manifest(config.backup_manifest)
    last_sync = parse_time(health.get("last_shadow_sync"))
    age_seconds = (
        (datetime.now(timezone.utc) - last_sync).total_seconds()
        if last_sync is not None
        else None
    )
    migration_version = load_migrations()[-1].version
    blockers: list[str] = []
    if health.get("mode") != "shadow" or health.get("source_of_truth") != "json_git":
        blockers.append("database is not in the expected shadow/json_git mode")
    if health.get("cutover_enabled") is not False:
        blockers.append("cutover_enabled must remain false before owner-approved cutover")
    if health.get("migration_version") != migration_version:
        blockers.append("database migration version is not current")
    if age_seconds is None or age_seconds > config.max_shadow_age_seconds:
        blockers.append("latest successful shadow import is stale")
    if health.get("active_leases") != 0:
        blockers.append("active task leases exist")
    if any(not result["ok"] for result in reconciliations):
        blockers.append("PostgreSQL shadow differs from JSON/Git Task Manager")
    if not backup["ok"]:
        blockers.append("verified PostgreSQL backup is missing")
    return {
        "ok": not blockers,
        "ready_for_owner_cutover_decision": not blockers,
        "cutover_performed": False,
        "checked_at": utc_now(),
        "health": health,
        "shadow_age_seconds": age_seconds,
        "reconciliations": reconciliations,
        "backup": backup,
        "blockers": blockers,
    }


def write_export(output_dir: Path, payload: dict[str, Any]) -> dict[str, str]:
    output_dir = output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    queue_path = output_dir / "task_queue.json"
    history_path = output_dir / "task_history.json"
    write_json_atomic(queue_path, payload["queue"])
    write_json_atomic(history_path, payload["history"])
    return {"queue": str(queue_path), "history": str(history_path)}


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    value.add_argument("--json", action="store_true")
    subparsers = value.add_subparsers(dest="command", required=True)
    subparsers.add_parser("migrate")
    subparsers.add_parser("health")
    sync = subparsers.add_parser("sync-fleet")
    sync.add_argument("--apply", action="store_true")
    subparsers.add_parser("reconcile-fleet")
    export = subparsers.add_parser("export-project")
    export.add_argument("--project-id", required=True)
    export.add_argument("--output-dir", type=Path, required=True)
    subparsers.add_parser("cutover-check")
    return value


def main() -> int:
    args = parser().parse_args()
    try:
        config = load_config(args.config.expanduser())
        database = TaskControlPostgres.from_env(config.dsn_env)
        if args.command == "migrate":
            report = database.migrate()
        elif args.command == "health":
            report = database.health()
        elif args.command == "sync-fleet":
            snapshots = build_fleet_snapshots(config)
            if args.apply:
                result = database.import_shadow_snapshots(
                    snapshots,
                    metadata={
                        "registry": str(config.registry_path),
                        "mode": config.mode,
                        "source_of_truth": config.source_of_truth,
                    },
                )
                reconciliations = [database.reconcile_snapshot(snapshot) for snapshot in snapshots]
                report = {
                    **result,
                    "applied": True,
                    "snapshots": [snapshot_summary(snapshot) for snapshot in snapshots],
                    "reconciliations": reconciliations,
                }
                report["ok"] = result["ok"] and all(row["ok"] for row in reconciliations)
            else:
                report = {
                    "ok": True,
                    "applied": False,
                    "mode": "dry_run",
                    "duplicate_task_ids_preserved": sum(
                        len(snapshot.duplicate_task_ids) for snapshot in snapshots
                    ),
                    "snapshots": [snapshot_summary(snapshot) for snapshot in snapshots],
                }
        elif args.command == "reconcile-fleet":
            snapshots = build_fleet_snapshots(config)
            reconciliations = [database.reconcile_snapshot(snapshot) for snapshot in snapshots]
            report = {
                "ok": all(row["ok"] for row in reconciliations),
                "reconciliations": reconciliations,
            }
        elif args.command == "export-project":
            payload = database.export_project(args.project_id)
            report = {
                "ok": True,
                "project_id": args.project_id,
                "paths": write_export(args.output_dir, payload),
                "queue_count": len(payload["queue"]["tasks"]),
                "history_count": len(payload["history"]["tasks"]),
            }
        elif args.command == "cutover-check":
            snapshots = build_fleet_snapshots(config)
            report = cutover_readiness(database, config, snapshots)
        else:
            raise AssertionError(args.command)
    except Exception as exc:
        report = {
            "ok": False,
            "error": {"type": type(exc).__name__, "message": str(exc)},
        }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main())
