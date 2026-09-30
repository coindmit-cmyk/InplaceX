#!/usr/bin/env python3
"""Stage route task seeds into a reviewable task_queue copy."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import workspace_route_task_validator
import workspace_queue_migration_pipeline
import validate_task_queue_readiness


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


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def queue_tasks(queue: dict[str, Any]) -> list[dict[str, Any]]:
    tasks = queue.get("tasks")
    return tasks if isinstance(tasks, list) else []


def importable_task(seed: dict[str, Any], now: str) -> dict[str, Any]:
    task = dict(seed)
    task.setdefault("created_at", now)
    task["imported_at"] = now
    task["import_source"] = "workspace_route_task_importer"
    task["worker_ready"] = False
    task["requires_current_context_review"] = True
    task.setdefault("not_worker_ready_reason", "Route task seed requires dispatcher review before worker claim.")
    return task


def build_report(
    seeds_path: Path,
    queue_path: Path | None = None,
    output_path: Path | None = None,
    pipeline_output_dir: Path | None = None,
    pipeline_apply_dispatcher_repairs: bool = False,
    pipeline_context_refs: list[str] | None = None,
    verified_by: str = "dispatcher",
) -> dict[str, Any]:
    if pipeline_output_dir and not output_path:
        raise ValueError("pipeline_output_dir requires output_path")
    pipeline_context_refs = pipeline_context_refs or []
    validation = workspace_route_task_validator.build_report(seeds_path)
    seeds = load_json(seeds_path)
    seed_tasks = [task for task in seeds.get("tasks") or [] if isinstance(task, dict)]
    if queue_path and queue_path.exists():
        queue = load_json(queue_path)
    else:
        queue = {"schema_version": 1, "tasks": []}
    existing = queue_tasks(queue)
    existing_ids = {str(task.get("id") or "") for task in existing if isinstance(task, dict)}
    now = utc_now()
    additions: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for seed in seed_tasks:
        tid = str(seed.get("id") or "")
        if not tid:
            skipped.append({"id": "", "reason": "missing_id"})
        elif tid in existing_ids:
            skipped.append({"id": tid, "reason": "already_exists"})
        else:
            additions.append(importable_task(seed, now))
    staged_queue = dict(queue)
    staged_queue["tasks"] = [*existing, *additions]
    staged_queue.setdefault("schema_version", 1)
    staged_queue["route_task_import"] = {
        "source": str(seeds_path),
        "generated_at": now,
        "added_count": len(additions),
        "skipped_count": len(skipped),
        "validation_ok": validation.get("ok"),
    }
    if output_path:
        write_json_atomic(output_path, staged_queue)
    staged_issues: list[dict[str, str]] = []
    for index, task in enumerate(staged_queue.get("tasks") or []):
        if isinstance(task, dict):
            validate_task_queue_readiness.validate_task(task, index, staged_issues)
    added_ids = {str(task.get("id") or "") for task in additions}
    added_issues = [
        issue for issue in staged_issues
        if any(f"({task_id})" in str(issue.get("path") or "") for task_id in added_ids)
    ]
    inherited_issues = [issue for issue in staged_issues if issue not in added_issues]
    pipeline_summary = None
    if pipeline_output_dir and output_path:
        pipeline_summary = workspace_queue_migration_pipeline.build_pipeline(
            output_path,
            pipeline_output_dir,
            output_path.stem,
            apply_dispatcher_repairs=pipeline_apply_dispatcher_repairs,
            context_refs=pipeline_context_refs,
            verified_by=verified_by,
        )
    return {
        "schema_version": "1.0",
        "mode": "workspace_route_task_import",
        "seeds": str(seeds_path),
        "queue": str(queue_path) if queue_path else None,
        "output": str(output_path) if output_path else None,
        "validation": validation,
        "existing_count": len(existing),
        "seed_count": len(seed_tasks),
        "added_count": len(additions),
        "skipped": skipped,
        "staged_queue_validation": {
            "errors": sum(1 for item in staged_issues if item["severity"] == "error"),
            "warnings": sum(1 for item in staged_issues if item["severity"] == "warning"),
            "added_task_errors": sum(1 for item in added_issues if item["severity"] == "error"),
            "added_task_warnings": sum(1 for item in added_issues if item["severity"] == "warning"),
            "inherited_errors": sum(1 for item in inherited_issues if item["severity"] == "error"),
            "inherited_warnings": sum(1 for item in inherited_issues if item["severity"] == "warning"),
            "inherited_issue_sample": inherited_issues[:20],
            "added_issue_sample": added_issues[:20],
        },
        "mutates_input_queue": False,
        "queue_migration_pipeline": pipeline_summary,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seeds", required=True, type=Path)
    parser.add_argument("--queue", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--pipeline-output-dir", type=Path, help="Run queue migration preparation pipeline on the staged output copy.")
    parser.add_argument("--pipeline-apply-dispatcher-repairs", action="store_true", help="Apply targeted dispatcher repairs inside the optional pipeline.")
    parser.add_argument("--pipeline-context-ref", action="append", default=[], help="Context evidence ref passed to the optional pipeline dispatcher repair stage.")
    parser.add_argument("--verified-by", default="dispatcher")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if args.pipeline_output_dir and not args.output:
        parser.error("--pipeline-output-dir requires --output")
    report = build_report(
        args.seeds.expanduser(),
        queue_path=args.queue.expanduser() if args.queue else None,
        output_path=args.output.expanduser() if args.output else None,
        pipeline_output_dir=args.pipeline_output_dir.expanduser() if args.pipeline_output_dir else None,
        pipeline_apply_dispatcher_repairs=args.pipeline_apply_dispatcher_repairs,
        pipeline_context_refs=args.pipeline_context_ref,
        verified_by=args.verified_by,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"added={report['added_count']} skipped={len(report['skipped'])}")
    return 0 if report["validation"].get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
