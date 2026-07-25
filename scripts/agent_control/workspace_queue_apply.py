#!/usr/bin/env python3
"""Safely apply a prepared task_queue copy with backup and diff summary."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import validate_task_queue_readiness


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_queue(queue: dict[str, Any]) -> dict[str, Any]:
    issues: list[dict[str, str]] = []
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    for index, task in enumerate(tasks):
        if isinstance(task, dict):
            validate_task_queue_readiness.validate_task(task, index, issues)
    return {
        "errors": sum(1 for item in issues if item["severity"] == "error"),
        "warnings": sum(1 for item in issues if item["severity"] == "warning"),
        "issues_sample": issues[:20],
    }


def task_map(queue: dict[str, Any]) -> dict[str, dict[str, Any]]:
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    result: dict[str, dict[str, Any]] = {}
    for task in tasks:
        if isinstance(task, dict):
            tid = str(task.get("id") or task.get("task_id") or "").strip()
            if tid:
                result[tid] = task
    return result


def diff_summary(current: dict[str, Any], prepared: dict[str, Any]) -> dict[str, Any]:
    current_tasks = task_map(current)
    prepared_tasks = task_map(prepared)
    current_ids = set(current_tasks)
    prepared_ids = set(prepared_tasks)
    changed: list[str] = []
    for tid in sorted(current_ids & prepared_ids):
        if current_tasks[tid] != prepared_tasks[tid]:
            changed.append(tid)
    return {
        "current_task_count": len(current_tasks),
        "prepared_task_count": len(prepared_tasks),
        "added_task_ids": sorted(prepared_ids - current_ids),
        "removed_task_ids": sorted(current_ids - prepared_ids),
        "changed_task_ids": changed,
        "added_count": len(prepared_ids - current_ids),
        "removed_count": len(current_ids - prepared_ids),
        "changed_count": len(changed),
    }


def build_report(prepared_path: Path, target_path: Path, backup_dir: Path, *, apply: bool = False) -> dict[str, Any]:
    if not prepared_path.exists():
        raise FileNotFoundError(prepared_path)
    if not target_path.exists():
        raise FileNotFoundError(target_path)
    prepared = load_json(prepared_path)
    current = load_json(target_path)
    prepared_validation = validate_queue(prepared)
    current_validation = validate_queue(current)
    now = utc_now()
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup_path = backup_dir / f"{target_path.stem}.{now.replace(':', '').replace('-', '')}.backup{target_path.suffix}"
    diff = diff_summary(current, prepared)
    can_apply = prepared_validation["errors"] == 0
    prepared_hash = sha256_file(prepared_path)
    target_hash_before = sha256_file(target_path)

    if apply and not can_apply:
        raise ValueError("prepared queue has validation errors; refusing apply")
    if apply:
        shutil.copy2(target_path, backup_path)
        shutil.copy2(prepared_path, target_path)

    report = {
        "schema_version": "1.0",
        "mode": "workspace_queue_apply",
        "prepared": str(prepared_path),
        "target": str(target_path),
        "backup": str(backup_path) if apply else None,
        "backup_dir": str(backup_dir),
        "apply": apply,
        "mutated_target": apply,
        "can_apply": can_apply,
        "prepared_sha256": prepared_hash,
        "target_sha256_before": target_hash_before,
        "prepared_validation": prepared_validation,
        "current_validation": current_validation,
        "diff": diff,
    }
    report_path = backup_dir / f"workspace_queue_apply.{now.replace(':', '').replace('-', '')}.json"
    write_json_atomic(report_path, report)
    report["report"] = str(report_path)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--backup-dir", required=True, type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(args.prepared.expanduser(), args.target.expanduser(), args.backup_dir.expanduser(), apply=args.apply)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"apply={report['apply']}")
        print(f"can_apply={report['can_apply']}")
        print(f"added={report['diff']['added_count']} changed={report['diff']['changed_count']} removed={report['diff']['removed_count']}")
    return 0 if report["can_apply"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
