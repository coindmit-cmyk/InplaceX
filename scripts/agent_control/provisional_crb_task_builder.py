#!/usr/bin/env python3
"""Create provisional CRB task packets for low-risk untraced work.

Only `provisional_crb` decisions are eligible. The created queue rows remain
`needs_task_packet`, so Dispatcher must complete the packet before workers can
claim them.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

from _rebuild_common import load_json, normalize_path, utc_now, write_json
from process_log import append_log
from project_paths import task_manager_dir


def slugify(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "-", value).strip("-")[:36].upper() or "UNTRACED"


def queue_tasks(queue: dict[str, Any]) -> list[dict[str, Any]]:
    tasks = queue.setdefault("tasks", [])
    if not isinstance(tasks, list):
        raise SystemExit("task_queue.json must contain a tasks array")
    return tasks


def existing_ids(queue: dict[str, Any]) -> set[str]:
    return {str(task.get("id") or task.get("task_id") or "") for task in queue_tasks(queue) if isinstance(task, dict)}


def existing_sources(queue: dict[str, Any]) -> set[str]:
    sources: set[str] = set()
    for task in queue_tasks(queue):
        if not isinstance(task, dict):
            continue
        source = str(task.get("source_branch") or task.get("clean_rebuild_source_branch") or "").strip()
        if source:
            sources.add(source)
    return sources


def make_id(branch: str, used: set[str]) -> str:
    base = f"CRB-PROVISIONAL-{slugify(branch)}"
    value = base
    suffix = 2
    while value in used:
        value = f"{base}-{suffix}"
        suffix += 1
    used.add(value)
    return value


def eligible_items(decisions: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for item in decisions.get("items") or []:
        if not isinstance(item, dict) or item.get("decision") != "provisional_crb":
            continue
        paths = [normalize_path(path) for path in item.get("changed_paths") or [] if normalize_path(path)]
        if item.get("risk_class") != "low":
            continue
        if not paths:
            continue
        result.append(item)
    return result


def build_task(item: dict[str, Any], task_id: str, now: str) -> dict[str, Any]:
    branch = str(item.get("branch") or "unknown").strip()
    paths = [normalize_path(path) for path in item.get("changed_paths") or [] if normalize_path(path)]
    return {
        "id": task_id,
        "task_id": task_id,
        "canonical_task_id": task_id,
        "canonical_target_id": f"task:{task_id}",
        "title": f"Provisional clean rebuild from {branch}",
        "status": "needs_task_packet",
        "priority": "P2",
        "type": "clean-rebuild-provisional",
        "worker_ready": False,
        "packet_status": "needs_task_packet",
        "normalization_status": "needs_task_packet",
        "dispatcher_decision": "needs_task_packet",
        "dispatcher_decision_reason": "provisional CRB created from low-risk no-task-id source; Dispatcher must complete packet",
        "complexity": "S",
        "allowed_paths": paths,
        "forbidden_paths": [".env", ".env.*", "secrets/**", "production credentials"],
        "checks": ["git diff --check"],
        "acceptance_criteria": [
            "Dispatcher confirms whether this provisional task is still needed.",
            "If accepted, complete allowed_paths, checks and target docs before worker claim.",
            "For this docs/backlog-only provisional item, keep it out of Integrator until Dispatcher completes the task packet.",
        ],
        "source_branch": branch,
        "changed_paths": paths,
        "source_file": "docs/plans/rebuild_decision_report.json",
        "provenance": {
            "source": "provisional_crb_task_builder.py",
            "created_at": now,
            "reason": item.get("reason"),
            "decision": item.get("decision"),
        },
        "lock": {"state": "free", "by": None, "at": None, "expires_at": None},
        "created_at": now,
        "status_reason": "awaiting Dispatcher packet completion",
    }


def build_report(project_root: Path, decisions: dict[str, Any], queue: dict[str, Any], *, max_items: int, apply: bool) -> dict[str, Any]:
    tasks = queue_tasks(queue)
    used = existing_ids(queue)
    sources = existing_sources(queue)
    now = utc_now()
    created: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for item in eligible_items(decisions):
        branch = str(item.get("branch") or "").strip()
        if branch in sources:
            skipped.append({"branch": branch, "reason": "source_branch_already_queued"})
            continue
        if max_items and len(created) >= max_items:
            skipped.append({"branch": branch, "reason": "max_items_reached"})
            continue
        task = build_task(item, make_id(branch, used), now)
        created.append(task)
        sources.add(branch)
    if apply and created:
        tasks.extend(created)
        queue["schema_version"] = queue.get("schema_version", 1)
        queue["updated_at"] = now
    return {
        "schema_version": 1,
        "created_at": now,
        "project_root": str(project_root),
        "apply": apply,
        "created_count": len(created),
        "skipped_count": len(skipped),
        "created": created,
        "skipped": skipped,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--decisions")
    parser.add_argument("--queue")
    parser.add_argument("--output")
    parser.add_argument("--max-items", type=int, default=10)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    decisions_path = Path(args.decisions).resolve() if args.decisions else plans / "rebuild_decision_report.json"
    queue_path = Path(args.queue).resolve() if args.queue else plans / "task_queue.json"
    output_path = Path(args.output).resolve() if args.output else plans / "provisional_crb_tasks.json"
    queue = load_json(queue_path, {"schema_version": 1, "tasks": []})
    report = build_report(project_root, load_json(decisions_path, {"items": []}), queue, max_items=args.max_items, apply=args.apply)
    if args.apply and report["created_count"]:
        write_json(queue_path, queue)
        append_log(project_root, "dispatcher", "provisional_crb_tasks_created", severity="info", created=report["created_count"])
    write_json(output_path, report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"created: {report['created_count']}")
        print(f"written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
