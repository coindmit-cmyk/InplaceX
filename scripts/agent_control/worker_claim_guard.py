#!/usr/bin/env python3
"""Validate a task row before it can be claimed by a worker."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from project_paths import task_file
from validate_task_queue_readiness import unsafe_allowed_paths


REQUIRED_PACKET_FIELDS = ("allowed_paths", "forbidden_paths", "acceptance_criteria", "checks")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return bool(value)
    return True


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def lock_state(task: dict[str, Any], locks: dict[str, Any]) -> str:
    tid = task_id(task)
    for lock in locks.get("locks") or []:
        if isinstance(lock, dict) and str(lock.get("task_id") or "") == tid and lock.get("state") in {"locked", "in_progress", "review"}:
            return str(lock.get("state"))
    lock = task.get("lock")
    if isinstance(lock, dict):
        return str(lock.get("state") or "free")
    return str(lock or "free")


def validate_task(task: dict[str, Any], locks: dict[str, Any]) -> dict[str, Any]:
    errors: list[str] = []
    tid = task_id(task)
    if not tid:
        errors.append("missing task_id")
    if task.get("worker_ready") is not True:
        errors.append("worker_ready must be true")
    if task.get("dispatcher_decision") != "worker_ready":
        errors.append("dispatcher_decision must be worker_ready")
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        errors.append("current context review required")
    if not has_value(task.get("base_branch")):
        errors.append("missing base_branch")
    for field in REQUIRED_PACKET_FIELDS:
        if not has_value(task.get(field)):
            errors.append(f"missing {field}")
    if unsafe_allowed_paths(task):
        errors.append("allowed_paths must contain repository-relative scope only")
    if lock_state(task, locks) in {"locked", "in_progress", "review"}:
        errors.append("lock not free")
    next_state = "planned" if not errors else ("needs_task_packet" if any(error.startswith("missing") for error in errors) else "blocked")
    next_owner = None if not errors else "dispatcher"
    return {"ok": not errors, "task_id": tid, "errors": errors, "next_state": next_state, "next_owner": next_owner}


def find_task(queue: dict[str, Any], task_id_value: str) -> dict[str, Any] | None:
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and task_id(task) == task_id_value:
            return task
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate task claim readiness.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--queue")
    parser.add_argument("--locks")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    root = Path(args.project_root).resolve()
    queue = load_json(Path(args.queue).resolve() if args.queue else task_file(root, "task_queue.json"))
    locks = load_json(Path(args.locks).resolve() if args.locks else task_file(root, "agent_locks.json"))
    task = find_task(queue, args.task_id)
    result = {"ok": False, "task_id": args.task_id, "errors": ["task not found"], "next_state": "needs_dispatcher", "next_owner": "dispatcher"} if not task else validate_task(task, locks)
    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else ("ok" if result["ok"] else "; ".join(result["errors"])))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
