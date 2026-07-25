#!/usr/bin/env python3
"""Release expired AiStudio task locks safely.

Dry-run by default. With --apply, expired locked/in_progress locks are marked
released, matching in-progress task packet locks are reset to planned/free, and
a worker_retry_requested event is appended so the next owner is explicit.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
from typing import Any

from project_paths import task_file

DEFAULT_LOCK_STATES = {"active", "claimed", "in_progress", "locked", "running", "worker_claimed"}
TASK_LOCKED_STATES = {
    "agent_working",
    "claimed",
    "finalization_requested",
    "finalizer_auto_merge_ready",
    "in_progress",
    "integration_requested",
    "locked",
    "running",
    "worker_claimed",
}
TASK_RESET_TO_PLANNED_STATES = {"agent_working", "claimed", "in_progress", "locked", "running", "worker_claimed"}
TASK_TERMINAL_STATES = {
    "approved",
    "cancelled",
    "closed",
    "completed",
    "deprecated",
    "done",
    "finalized",
    "merged",
    "postponed",
    "rejected",
    "stale_or_superseded",
    "superseded",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def load_json(path: Path, fallback: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return fallback


def load_json_from_git_ref(project_root: Path, git_ref: str, rel_path: str) -> tuple[Any | None, str | None]:
    ref = str(git_ref or "").strip()
    path = str(rel_path or "").strip()
    if not ref or not path:
        return None, "git ref and relative path are required"
    try:
        proc = subprocess.run(
            ["git", "show", f"{ref}:{path}"],
            cwd=project_root,
            text=True,
            capture_output=True,
            check=False,
            timeout=20,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return None, f"Cannot read {ref}:{path}: {exc}"
    if proc.returncode != 0:
        return None, f"Cannot read {ref}:{path}: {proc.stderr.strip() or proc.stdout.strip()}"
    try:
        return json.loads(proc.stdout), None
    except json.JSONDecodeError as exc:
        return None, f"Invalid JSON in {ref}:{path}: {exc}"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def expired_lock_ids(locks: list[Any], now: datetime, states: set[str]) -> set[str]:
    result: set[str] = set()
    for lock in locks:
        if not isinstance(lock, dict):
            continue
        task_id = str(lock.get("task_id") or "").strip()
        expires_at = parse_time(lock.get("expires_at"))
        if task_id and lock.get("state") in states and expires_at and expires_at < now:
            result.add(task_id)
    return result


def task_lock_state(task: dict[str, Any]) -> str:
    lock = task.get("lock")
    if isinstance(lock, dict):
        return str(lock.get("state") or "").strip()
    return str(lock or "").strip()


def task_lock_expires_at(task: dict[str, Any]) -> datetime | None:
    lock = task.get("lock")
    if isinstance(lock, dict):
        parsed = parse_time(lock.get("expires_at"))
        if parsed is not None:
            return parsed
    for key in ("lock_expires_at", "expires_at"):
        parsed = parse_time(task.get(key))
        if parsed is not None:
            return parsed
    return None


def expired_embedded_lock_ids(queue_data: dict[str, Any], now: datetime, states: set[str]) -> set[str]:
    result: set[str] = set()
    tasks = queue_data.get("tasks", [])
    if not isinstance(tasks, list):
        return result
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        expires_at = task_lock_expires_at(task)
        state = task_lock_state(task)
        status = str(task.get("status") or "").strip()
        if status in TASK_TERMINAL_STATES:
            continue
        has_active_worker_claim = state in states or status in TASK_LOCKED_STATES
        if task_id and has_active_worker_claim and expires_at and expires_at < now:
            result.add(task_id)
    return result


def release_locks(lock_data: dict[str, Any], task_ids: set[str], now_text: str, released_by: str, states: set[str]) -> list[dict[str, Any]]:
    changed: list[dict[str, Any]] = []
    locks = lock_data.get("locks", [])
    if not isinstance(locks, list):
        return changed
    for lock in locks:
        if not isinstance(lock, dict):
            continue
        task_id = str(lock.get("task_id") or "").strip()
        if task_id not in task_ids or lock.get("state") not in states:
            continue
        previous = dict(lock)
        lock["state"] = "released"
        lock["released_at"] = now_text
        lock["released_by"] = released_by
        lock["release_reason"] = "expired_lock"
        lock["previous_state"] = previous.get("state")
        changed.append({"task_id": task_id, "previous": previous, "current": dict(lock)})
    return changed


def reset_tasks(queue_data: dict[str, Any], task_ids: set[str], now_text: str, released_by: str, states: set[str]) -> list[dict[str, Any]]:
    changed: list[dict[str, Any]] = []
    tasks = queue_data.get("tasks", [])
    if not isinstance(tasks, list):
        return changed
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        if task_id not in task_ids:
            continue
        previous_status = task.get("status")
        previous_lock = task.get("lock")
        previous_lock_snapshot = dict(previous_lock) if isinstance(previous_lock, dict) else previous_lock
        if previous_status in TASK_RESET_TO_PLANNED_STATES:
            task["status"] = "planned"
        if isinstance(previous_lock, dict) and str(previous_lock.get("state") or "").strip() in states:
            previous_lock["previous_state"] = previous_lock.get("state")
            previous_lock["state"] = "free"
            previous_lock["by"] = None
            previous_lock["released_at"] = now_text
            previous_lock["released_by"] = released_by
            previous_lock["release_reason"] = "expired_lock"
            previous_lock["expires_at"] = None
        elif previous_lock in states or previous_lock == "locked" or previous_lock == "in_progress":
            task["lock"] = "free"
        task.pop("lock_expires_at", None)
        task["stale_lock_released_at"] = now_text
        task["stale_lock_released_by"] = released_by
        task["stale_lock_previous_status"] = previous_status
        changed.append({
            "task_id": task_id,
            "previous_status": previous_status,
            "new_status": task.get("status"),
            "previous_lock": previous_lock_snapshot,
        })
    return changed


def append_events(events_path: Path, task_ids: set[str], now_text: str, released_by: str) -> None:
    events_path.parent.mkdir(parents=True, exist_ok=True)
    with events_path.open("a", encoding="utf-8") as fh:
        for task_id in sorted(task_ids):
            event = {
                "ts": now_text,
                "event": "worker_retry_requested",
                "task_id": task_id,
                "reason": "expired_lock_released",
                "owner": released_by,
                "next_owner": "auto-workers",
            }
            fh.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def build_report(
    project_root: Path,
    apply: bool,
    released_by: str,
    states: set[str] | None = None,
    task_queue_ref: str = "",
    task_queue_rel_path: str = "AiStudio/Task_manager/task_queue.json",
    agent_locks_ref: str = "",
    agent_locks_rel_path: str = "AiStudio/Task_manager/agent_locks.json",
) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    now_text = now.replace(microsecond=0).isoformat().replace("+00:00", "Z")
    queue_path = task_file(project_root, "task_queue.json")
    locks_path = task_file(project_root, "agent_locks.json")
    events_path = task_file(project_root, "agent_events.jsonl")
    warnings: list[str] = []
    queue_exists = queue_path.exists()
    locks_exists = locks_path.exists()
    queue_source = "local" if queue_exists else "missing"
    locks_source = "local" if locks_exists else "missing"
    if not queue_exists:
        warnings.append(f"Task queue not found: {queue_path}")
    queue_data = load_json(queue_path, {"tasks": []})
    lock_data = load_json(locks_path, {"locks": []})
    if not queue_exists and task_queue_ref:
        fallback_queue, fallback_error = load_json_from_git_ref(project_root, task_queue_ref, task_queue_rel_path)
        if fallback_error:
            warnings.append(f"Task queue git fallback failed: {fallback_error}")
        else:
            queue_data = fallback_queue
            queue_source = "git_ref"
            warnings.append(f"Task queue loaded read-only from {task_queue_ref}:{task_queue_rel_path}")
    if not locks_exists and agent_locks_ref:
        fallback_locks, fallback_error = load_json_from_git_ref(project_root, agent_locks_ref, agent_locks_rel_path)
        if fallback_error:
            warnings.append(f"Agent locks git fallback failed: {fallback_error}")
        else:
            lock_data = fallback_locks
            locks_source = "git_ref"
            warnings.append(f"Agent locks loaded read-only from {agent_locks_ref}:{agent_locks_rel_path}")
    locks = lock_data.get("locks", []) if isinstance(lock_data, dict) else []
    release_states = states or set(DEFAULT_LOCK_STATES)
    task_ids = expired_lock_ids(locks if isinstance(locks, list) else [], now, release_states)
    if isinstance(queue_data, dict):
        task_ids.update(expired_embedded_lock_ids(queue_data, now, release_states))
    report = {
        "schema_version": "1.0",
        "project_root": str(project_root),
        "applied": apply,
        "expired_task_ids": sorted(task_ids),
        "release_states": sorted(release_states),
        "sources": {
            "task_queue_path": str(queue_path),
            "task_queue_exists": queue_exists,
            "task_queue_source": queue_source,
            "task_queue_ref": task_queue_ref,
            "task_queue_rel_path": task_queue_rel_path,
            "agent_locks_path": str(locks_path),
            "agent_locks_exists": locks_exists,
            "agent_locks_source": locks_source,
            "agent_locks_ref": agent_locks_ref,
            "agent_locks_rel_path": agent_locks_rel_path,
        },
        "warnings": warnings,
        "released_locks": [],
        "reset_tasks": [],
    }
    if task_ids and apply and (queue_source != "local" or locks_source == "git_ref"):
        report["applied"] = False
        report["apply_blocked"] = True
        report["warnings"].append("Apply blocked because at least one state source is read-only git_ref.")
        return report
    if not task_ids or not apply:
        return report
    report["released_locks"] = release_locks(lock_data, task_ids, now_text, released_by, release_states)
    report["reset_tasks"] = reset_tasks(queue_data, task_ids, now_text, released_by, release_states)
    write_json(locks_path, lock_data)
    write_json(queue_path, queue_data)
    append_events(events_path, task_ids, now_text, released_by)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Release expired AiStudio task locks.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--released-by", default="release_expired_locks.py")
    parser.add_argument("--states", nargs="+", default=sorted(DEFAULT_LOCK_STATES), help="Expired lock states to release. Use review explicitly for review locks.")
    parser.add_argument("--task-queue-ref", default="", help="Read task_queue.json from this git ref when the local queue is missing. Read-only; --apply is blocked.")
    parser.add_argument("--task-queue-rel-path", default="AiStudio/Task_manager/task_queue.json")
    parser.add_argument("--agent-locks-ref", default="", help="Read agent_locks.json from this git ref when the local lock file is missing. Read-only; --apply is blocked.")
    parser.add_argument("--agent-locks-rel-path", default="AiStudio/Task_manager/agent_locks.json")
    parser.add_argument("--output")
    args = parser.parse_args()
    report = build_report(
        Path(args.project_root).resolve(),
        bool(args.apply),
        args.released_by,
        set(args.states),
        task_queue_ref=args.task_queue_ref,
        task_queue_rel_path=args.task_queue_rel_path,
        agent_locks_ref=args.agent_locks_ref,
        agent_locks_rel_path=args.agent_locks_rel_path,
    )
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(text, encoding="utf-8")
    print(text, end="")
    if report.get("apply_blocked"):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
