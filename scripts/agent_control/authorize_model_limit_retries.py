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
MODEL_LIMIT_RETRY_SOURCE_AUTOMATIC = "automatic_capacity_recovery"
MODEL_LIMIT_RETRY_PROFILE = "auto-worker-5.3"


def parse_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def to_int(value: Any) -> int | None:
    try:
        number = int(float(value))
    except (TypeError, ValueError):
        return None
    return max(0, min(100, number))


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


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


def is_human_routed(task: dict[str, Any]) -> bool:
    owner = str(task.get("next_owner") or "").strip().lower()
    if owner == "human":
        return True
    if str(task.get("dispatcher_decision") or "").strip().lower() in {"needs_human", "blocked"}:
        return True
    return False


def build_capacity_recovery_payload(
    runtime_root: Path,
    *,
    min_remaining_percent: int,
    max_age_minutes: int,
    now: datetime | None,
) -> tuple[bool, dict[str, Any], str]:
    limits_path = runtime_root / "codex-limits" / "latest.json"
    if not limits_path.exists():
        return False, {"remaining_percent": None, "observed_at": None, "status": "missing"}, "missing_capacity_evidence"

    snapshot = load_json(limits_path)
    limits = snapshot.get("limits") if isinstance(snapshot.get("limits"), list) else []
    if not isinstance(limits, list) or not limits:
        return False, {"remaining_percent": None, "observed_at": None, "status": "missing"}, "missing_capacity_evidence"

    candidates: list[tuple[int, datetime, dict[str, Any]]] = []
    for item in limits:
        if not isinstance(item, dict):
            continue
        remaining = to_int(item.get("remaining_percent"))
        if remaining is None:
            continue
        observed_value = item.get("observed_at") or snapshot.get("updated_at")
        observed_at = parse_datetime(str(observed_value)) if observed_value is not None else None
        if observed_at is None:
            continue
        if now is not None and max_age_minutes > 0 and (now - observed_at).total_seconds() > max_age_minutes * 60:
            continue
        candidates.append((remaining, observed_at, item))

    if not candidates:
        return False, {"remaining_percent": None, "observed_at": None, "status": "stale"}, "waiting_for_capacity_recovery"

    candidates.sort(key=lambda item: (item[1], item[0]))
    remaining, observed_at, selected = candidates[-1]
    if remaining < min_remaining_percent:
        return False, {
            "remaining_percent": remaining,
            "observed_at": observed_at.isoformat().replace("+00:00", "Z"),
            "status": "insufficient",
            "details": selected,
        }, "capacity_below_minimum"

    return True, {
        "remaining_percent": remaining,
        "observed_at": observed_at.isoformat().replace("+00:00", "Z"),
        "status": "recovered",
        "details": selected,
    }, "recovered"


def authorize_task(
    task: dict[str, Any],
    *,
    approved_at: str,
    approved_by: str,
    reason: str,
    auto: bool = False,
    recovery_evidence: dict[str, Any] | None = None,
) -> dict[str, Any]:
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
    updated["model_limit_retry_attempts"] = int(updated.get("model_limit_retry_attempts") or 0) + 1
    updated["model_limit_retry_source"] = "manual"
    updated["next_owner"] = "Dispatcher"
    updated["next_action"] = "Run promote_worker_ready_tasks.py to return this task to worker_ready."

    if auto:
        updated["model_limit_retry_source"] = MODEL_LIMIT_RETRY_SOURCE_AUTOMATIC
        updated["recommended_agent"] = MODEL_LIMIT_RETRY_PROFILE
        updated["eligible_worker_profiles"] = [MODEL_LIMIT_RETRY_PROFILE]
        recovery = dict(recovery_evidence or {})
        recovery.update({"recovered": True})
        updated["model_limit_retry_recovery_evidence"] = recovery
    return updated


def task_is_terminal(task: dict[str, Any]) -> bool:
    return str(task.get("status") or "").lower() in {"done", "completed", "finalized", "released", "archived", "owner_approved", "agent_done"}


def next_retry_blocker(task: dict[str, Any], active_locks: set[str], max_attempts: int, cooldown_seconds: int, now: datetime) -> str | None:
    tid = task_id(task)
    if tid in active_locks:
        return "active lock exists"
    if task_is_terminal(task):
        return "terminal status"
    if task.get("model_limit_retry_allowed") is True:
        return "model_limit_retry already allowed"
    attempts = int(task.get("model_limit_retry_attempts") or 0)
    if attempts >= max_attempts:
        return "attempt_limit_reached"
    approved_at = parse_datetime(task.get("model_limit_retry_allowed_at"))
    if approved_at is not None and cooldown_seconds > 0:
        age_seconds = (now - approved_at).total_seconds()
        if age_seconds < cooldown_seconds:
            return "retry_cooldown"
    if is_human_routed(task):
        return "human_routed"
    if has_value(task.get("blocked_by")) or as_list(task.get("depends_on")):
        return "dependency_blocked"
    canonical_task_id = str(task.get("canonical_task_id") or "").strip()
    if canonical_task_id and canonical_task_id != tid:
        return "canonical_task_id points to another task"
    if has_value(task.get("split_into")):
        return "split_into is set"
    return None


def process_automatic_queue(
    data: dict[str, Any],
    active_locks: set[str],
    *,
    evidence_root: Path,
    batch_size: int = 0,
    max_attempts: int = 3,
    cooldown_seconds: int = 1800,
    min_remaining_percent: int = 10,
    max_age_minutes: int = 60,
    now: datetime | None = None,
    approved_by: str = "scripts/agent_control/authorize_model_limit_retries.py",
    reason: str = DEFAULT_REASON,
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]], dict[str, int]]:
    now = now or None
    runtime_root = Path(evidence_root)
    result = deepcopy(data)
    tasks = result.get("tasks", [])
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")

    approved: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    remaining_batch = max(0, int(batch_size or 0))
    counters: dict[str, int] = {
        "waiting": 0,
        "eligible": 0,
        "authorized": 0,
        "cooldown": 0,
        "exhausted": 0,
        "batch_limited": 0,
    }

    capacity_ok, evidence, capacity_reason = build_capacity_recovery_payload(
        runtime_root,
        min_remaining_percent=min_remaining_percent,
        max_age_minutes=max_age_minutes,
        now=now,
    )
    approved_at = utc_now()

    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        tid = task_id(task, index)
        if not is_model_limit_block(task):
            continue
        counters["waiting"] += 1

        blocker = next_retry_blocker(task, active_locks, max(1, max_attempts), cooldown_seconds, now)
        if blocker:
            if blocker == "retry_cooldown":
                counters["cooldown"] += 1
            if blocker == "attempt_limit_reached":
                counters["exhausted"] += 1
            skipped.append({"task_id": tid, "reason": blocker})
            continue

        if not capacity_ok:
            skipped.append({"task_id": tid, "reason": capacity_reason, "next_action": "wait_for_capacity_recovery"})
            continue

        counters["eligible"] += 1
        if remaining_batch == 0 and batch_size:
            counters["batch_limited"] += 1
            skipped.append({"task_id": tid, "reason": "automatic_batch_limit"})
            continue

        tasks[index] = authorize_task(
            task,
            approved_at=approved_at,
            approved_by=approved_by,
            reason=reason,
            auto=True,
            recovery_evidence={
                "remaining_percent": evidence.get("remaining_percent"),
                "observed_at": evidence.get("observed_at"),
                "recovered": evidence.get("status") == "recovered",
                "min_remaining_percent": min_remaining_percent,
            },
        )
        approved.append({"task_id": tid, "model_limit_retry_allowed": True})
        counters["authorized"] += 1
        if batch_size:
            remaining_batch -= 1

    if approved:
        result["updated_at"] = approved_at
        # keep only the most recent successful evidence for downstream tooling.
        result["model_limit_retry_last_recovery"] = {
            "recovered": capacity_ok,
            "evidence": evidence,
            "timestamp": approved_at,
            "runtime_root": str(runtime_root),
        }

    return result, approved, skipped, counters


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
    automatic: bool = False,
    runtime_root: Path | None = None,
    batch_size: int = 0,
    max_attempts: int = 3,
    cooldown_seconds: int = 1800,
    min_remaining_percent: int = 10,
    max_age_minutes: int = 60,
    task_ids: list[str] | None = None,
    limit: int = 0,
    approved_by: str,
    reason: str,
    apply: bool,
) -> dict[str, Any]:
    queue = load_json(queue_path)
    locks = load_json(locks_path) if locks_path and locks_path.exists() else None
    recovery = {
        "mode": "manual",
        "enabled": False,
        "evidence_root": None,
        "counters": {
            "waiting": 0,
            "eligible": 0,
            "authorized": 0,
            "cooldown": 0,
            "exhausted": 0,
            "batch_limited": 0,
        },
    }
    if automatic:
        recovery = {
            "mode": "automatic",
            "enabled": True,
            "evidence_root": str(runtime_root or (Path.home() / "agent-runtime")),
        }
        updated, approved, skipped, counters = process_automatic_queue(
            queue,
            active_lock_task_ids(locks),
            evidence_root=Path(runtime_root or (Path.home() / "agent-runtime")),
            batch_size=max(0, batch_size),
            max_attempts=max(1, max_attempts),
            cooldown_seconds=max(0, cooldown_seconds),
            min_remaining_percent=max(0, min_remaining_percent),
            max_age_minutes=max(0, max_age_minutes),
            approved_by=approved_by,
            reason=reason,
        )
        recovery["counters"] = counters
    else:
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
        "model_limit_retry": recovery,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--locks", type=Path)
    parser.add_argument("--task-id", action="append", default=[])
    parser.add_argument("--limit", type=int, default=0, help="Maximum rows to authorize; 0 means no limit.")
    parser.add_argument("--automatic", action="store_true", help="Authorize model-limit retries automatically using evidence.")
    parser.add_argument("--runtime-root", type=Path, default=None, help="Path containing codex-limits/latest.json snapshots.")
    parser.add_argument("--model-limit-retry-batch-size", type=int, default=0, help="Maximum number of automatic retry approvals per pass.")
    parser.add_argument("--model-limit-retry-max-attempts", type=int, default=3, help="Maximum automatic retry attempts per task.")
    parser.add_argument("--model-limit-retry-cooldown-seconds", type=int, default=1800, help="Minimum age in seconds before auto retry is retried again.")
    parser.add_argument("--model-limit-retry-min-remaining-percent", type=int, default=10, help="Remaining model capacity percent needed for auto retry.")
    parser.add_argument("--model-limit-retry-max-age-minutes", type=int, default=60, help="Maximum age in minutes for recovery evidence snapshots.")
    parser.add_argument("--approved-by", default="scripts/agent_control/authorize_model_limit_retries.py")
    parser.add_argument("--reason", default=DEFAULT_REASON)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report = run_once(
        args.queue,
        args.locks,
        automatic=args.automatic,
        runtime_root=args.runtime_root,
        batch_size=args.model_limit_retry_batch_size,
        max_attempts=args.model_limit_retry_max_attempts,
        cooldown_seconds=args.model_limit_retry_cooldown_seconds,
        min_remaining_percent=args.model_limit_retry_min_remaining_percent,
        max_age_minutes=args.model_limit_retry_max_age_minutes,
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
