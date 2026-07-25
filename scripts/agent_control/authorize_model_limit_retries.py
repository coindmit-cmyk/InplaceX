#!/usr/bin/env python3
"""Authorize explicit retries for tasks blocked by model capacity.

Dry-run by default. With --apply, valid blocked_model_limit rows receive
model_limit_retry_allowed=true; promote_worker_ready_tasks.py performs the
separate worker-ready promotion step.
"""

from __future__ import annotations

import argparse
import json
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


MODEL_LIMIT_INTEGRATION_STATUS = "blocked_model_limit"
MODEL_LIMIT_DISPATCHER_DECISION = "blocked_by_missing_environment"
DEFAULT_REASON = "model capacity restored or retry explicitly approved"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    return data if isinstance(data, dict) else {}


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return len(value) > 0
    return True


def active_lock_task_ids(locks: dict[str, Any] | None) -> set[str]:
    if not isinstance(locks, dict):
        return set()
    result: set[str] = set()
    for lock in locks.get("locks", []):
        if isinstance(lock, dict) and lock.get("state") in {"locked", "in_progress", "review"}:
            task_id = str(lock.get("task_id") or "").strip()
            if task_id:
                result.add(task_id)
    return result


def task_id(task: dict[str, Any], index: int = 0) -> str:
    return str(task.get("id") or task.get("task_id") or f"index-{index}").strip()


def is_model_limit_block(task: dict[str, Any]) -> bool:
    return (
        str(task.get("status") or "") == "blocked"
        and str(task.get("integration_status") or "") == MODEL_LIMIT_INTEGRATION_STATUS
        and str(task.get("dispatcher_decision") or "") == MODEL_LIMIT_DISPATCHER_DECISION
    )


def authorization_blockers(task: dict[str, Any], active_locks: set[str]) -> list[str]:
    blockers: list[str] = []
    tid = task_id(task)
    if not is_model_limit_block(task):
        blockers.append("not blocked_model_limit")
    if tid in active_locks:
        blockers.append("active lock exists")
    canonical_task_id = str(task.get("canonical_task_id") or "").strip()
    if canonical_task_id and canonical_task_id != tid:
        blockers.append("canonical_task_id points to another task")
    if has_value(task.get("split_into")):
        blockers.append("split_into is set")
    if has_value(task.get("blocked_by")):
        blockers.append("blocked_by is not empty")
    if task.get("requires_current_context_review") is True and not (
        has_value(task.get("current_context_verified_at"))
        and (has_value(task.get("current_context_verified_by")) or has_value(task.get("current_context_reviewed_by")))
    ):
        blockers.append("current code/docs/task queue review required")
    for field in ("complexity", "priority", "type"):
        if not has_value(task.get(field)):
            blockers.append(f"missing {field}")
    for field in ("allowed_paths", "forbidden_paths", "acceptance_criteria", "checks"):
        if not has_value(task.get(field)):
            blockers.append(f"missing {field}")
    if not (has_value(task.get("recommended_agent")) or has_value(task.get("eligible_worker_profiles"))):
        blockers.append("missing recommended_agent or eligible_worker_profiles")
    if not (has_value(task.get("context_docs")) or has_value(task.get("source_file")) or has_value(task.get("provenance"))):
        blockers.append("missing context_docs or source provenance")
    return blockers


def authorize_task(task: dict[str, Any], *, approved_at: str, approved_by: str, reason: str) -> dict[str, Any]:
    updated = deepcopy(task)
    history = updated.get("model_limit_retry_approval_history")
    if not isinstance(history, list):
        history = []
    history.append(
        {
            "approved_at": approved_at,
            "approved_by": approved_by,
            "reason": reason,
            "previous_next_action": updated.get("next_action"),
        }
    )
    updated["model_limit_retry_approval_history"] = history
    updated["model_limit_retry_allowed"] = True
    updated["model_limit_retry_allowed_at"] = approved_at
    updated["model_limit_retry_allowed_by"] = approved_by
    updated["model_limit_retry_reason"] = reason
    updated["next_owner"] = "Dispatcher"
    updated["next_action"] = "Run promote_worker_ready_tasks.py to return this task to worker_ready."
    return updated


def process_queue(
    data: dict[str, Any],
    active_locks: set[str],
    *,
    selected_task_ids: set[str] | None = None,
    limit: int = 0,
    approved_by: str = "scripts/agent_control/authorize_model_limit_retries.py",
    reason: str = DEFAULT_REASON,
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    result = deepcopy(data)
    tasks = result.get("tasks", [])
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    approved_at = utc_now()
    approved: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    changed = False
    remaining = max(0, int(limit or 0))
    selected = {str(item).strip() for item in selected_task_ids or set() if str(item).strip()}

    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        tid = task_id(task, index)
        if selected and tid not in selected:
            continue
        if not is_model_limit_block(task):
            if selected:
                skipped.append({"task_id": tid, "reason": "not blocked_model_limit"})
            continue
        if task.get("model_limit_retry_allowed") is True:
            skipped.append({"task_id": tid, "reason": "model_limit_retry already allowed"})
            continue
        blockers = authorization_blockers(task, active_locks)
        if blockers:
            skipped.append({"task_id": tid, "reason": "; ".join(blockers), "next_action": "dispatcher_review"})
            continue
        if remaining == 0 and limit:
            skipped.append({"task_id": tid, "reason": "limit reached", "next_action": "rerun with higher --limit"})
            continue
        tasks[index] = authorize_task(task, approved_at=approved_at, approved_by=approved_by, reason=reason)
        approved.append({"task_id": tid, "model_limit_retry_allowed": True})
        changed = True
        if limit:
            remaining -= 1

    if changed:
        result["updated_at"] = approved_at
    return result, approved, skipped


def run_once(
    queue_path: Path,
    locks_path: Path | None,
    *,
    task_ids: list[str] | None = None,
    limit: int = 0,
    approved_by: str,
    reason: str,
    apply: bool,
) -> dict[str, Any]:
    queue = load_json(queue_path)
    locks = load_json(locks_path) if locks_path and locks_path.exists() else None
    updated, approved, skipped = process_queue(
        queue,
        active_lock_task_ids(locks),
        selected_task_ids=set(task_ids or []),
        limit=limit,
        approved_by=approved_by,
        reason=reason,
    )
    if apply and updated != queue:
        write_json(queue_path, updated)
    return {
        "queue": str(queue_path),
        "checked_at": utc_now(),
        "dry_run": not apply,
        "approved_count": len(approved),
        "skipped_count": len(skipped),
        "approved": approved,
        "skipped": skipped,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--locks", type=Path)
    parser.add_argument("--task-id", action="append", default=[])
    parser.add_argument("--limit", type=int, default=0, help="Maximum rows to authorize; 0 means no limit.")
    parser.add_argument("--approved-by", default="scripts/agent_control/authorize_model_limit_retries.py")
    parser.add_argument("--reason", default=DEFAULT_REASON)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report = run_once(
        args.queue,
        args.locks,
        task_ids=args.task_id,
        limit=max(0, args.limit),
        approved_by=args.approved_by,
        reason=args.reason,
        apply=args.apply,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        mode = "dry-run" if report["dry_run"] else "apply"
        print(f"{mode}: approved={report['approved_count']} skipped={report['skipped_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
