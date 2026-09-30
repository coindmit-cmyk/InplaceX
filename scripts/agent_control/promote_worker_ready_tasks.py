#!/usr/bin/env python3
"""Promote complete needs_task_packet rows to explicit worker-ready planned work."""

from __future__ import annotations

import argparse
import json
import time
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from dispatcher_packet_repair import apply_v2_packet


SOURCE_STATUSES = {"needs_task_packet"}
MODEL_LIMIT_INTEGRATION_STATUS = "blocked_model_limit"
MODEL_LIMIT_DISPATCHER_DECISION = "blocked_by_missing_environment"
BLOCKING_DECISIONS = {
    "needs_architect",
    "needs_human",
    "split_into_children",
    "duplicate_linked",
    "stale_or_superseded",
    "blocked_by_dependency",
    "blocked_by_missing_environment",
    "blocked_by_pr_stack",
}
REQUIRED_VALUE_FIELDS = (
    "complexity",
    "priority",
    "type",
)
REQUIRED_LIST_FIELDS = (
    "allowed_paths",
    "forbidden_paths",
    "acceptance_criteria",
    "checks",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


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


def is_retriable_model_limit_block(task: dict[str, Any]) -> bool:
    return (
        str(task.get("status") or "") == "blocked"
        and str(task.get("integration_status") or "") == MODEL_LIMIT_INTEGRATION_STATUS
        and str(task.get("dispatcher_decision") or "") == MODEL_LIMIT_DISPATCHER_DECISION
        and task.get("model_limit_retry_allowed") is True
    )


def was_promoted_from_model_limit(task: dict[str, Any]) -> bool:
    if task.get("model_limit_retry_promoted_at"):
        return True
    history = task.get("worker_ready_promotion_history")
    if not isinstance(history, list):
        return False
    return any(
        isinstance(item, dict) and str(item.get("previous_integration_status") or "") == MODEL_LIMIT_INTEGRATION_STATUS
        for item in history
    )


def repair_unapproved_model_limit_retry(task: dict[str, Any], repaired_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    updated["status"] = "blocked"
    updated["worker_ready"] = False
    updated["dispatcher_decision"] = MODEL_LIMIT_DISPATCHER_DECISION
    updated["integration_status"] = MODEL_LIMIT_INTEGRATION_STATUS
    updated["blocked_reason"] = updated.get("blocked_reason") or "worker could not start because the requested model was unavailable or usage-limited"
    updated["status_reason"] = "worker could not start because the requested model was unavailable or usage-limited"
    updated["next_owner"] = "Dispatcher"
    updated["next_action"] = "Retry only after model capacity or budget is explicitly available."
    updated["model_limit_retry_reverted_at"] = repaired_at
    updated["model_limit_retry_reverted_by"] = "scripts/agent_control/promote_worker_ready_tasks.py"
    updated.pop("model_limit_retry_promoted_at", None)
    return updated


def repair_stale_model_limit_owner(task: dict[str, Any], repaired_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    updated["next_owner"] = "Dispatcher"
    updated["model_limit_next_owner_repaired_at"] = repaired_at
    updated["model_limit_next_owner_repaired_by"] = "scripts/agent_control/promote_worker_ready_tasks.py"
    updated["next_action"] = updated.get("next_action") or "Retry when the requested worker model or model budget is available."
    return updated


def packet_blockers(task: dict[str, Any], active_locks: set[str]) -> list[str]:
    blockers: list[str] = []
    task_id = str(task.get("id") or "").strip()
    decision = str(task.get("dispatcher_decision") or "")
    normalization_status = str(task.get("normalization_status") or "")

    retriable_model_limit = is_retriable_model_limit_block(task)
    if str(task.get("status") or "") not in SOURCE_STATUSES and not retriable_model_limit:
        blockers.append("status is not needs_task_packet or retriable blocked_model_limit")
    if decision in BLOCKING_DECISIONS and not retriable_model_limit:
        blockers.append(f"dispatcher_decision={decision}")
    if normalization_status in {"inventory_only", "duplicate_linked", "stale_or_superseded"}:
        blockers.append(f"normalization_status={normalization_status}")
    if task_id in active_locks:
        blockers.append("active lock exists")
    canonical_task_id = str(task.get("canonical_task_id") or "").strip()
    if canonical_task_id and canonical_task_id != task_id:
        blockers.append("canonical_task_id points to another task")
    if has_value(task.get("split_into")):
        blockers.append("split_into is set")
    if has_value(task.get("blocked_by")):
        blockers.append("blocked_by is not empty")
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        blockers.append("current code/docs/task queue review required")

    for field in REQUIRED_VALUE_FIELDS:
        if not has_value(task.get(field)):
            blockers.append(f"missing {field}")
    for field in REQUIRED_LIST_FIELDS:
        if not has_value(task.get(field)):
            blockers.append(f"missing {field}")

    if not (has_value(task.get("recommended_agent")) or has_value(task.get("eligible_worker_profiles"))):
        blockers.append("missing recommended_agent or eligible_worker_profiles")
    if not (has_value(task.get("context_docs")) or has_value(task.get("source_file")) or has_value(task.get("provenance"))):
        blockers.append("missing context_docs or source provenance")

    return blockers


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def promote_task(task: dict[str, Any], promoted_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    previous_status = updated.get("status")
    previous_decision = updated.get("dispatcher_decision")
    previous_packet_status = updated.get("packet_status")
    previous_normalization = updated.get("normalization_status")
    previous_integration_status = updated.get("integration_status")
    retried_model_limit = is_retriable_model_limit_block(updated)

    updated["status"] = "planned"
    updated["worker_ready"] = True
    updated["packet_status"] = "worker_ready"
    updated["normalization_status"] = "worker_ready"
    updated["dispatcher_decision"] = "worker_ready"
    updated["dispatcher_decision_reason"] = (
        "model-limit blocker cleared for worker retry"
        if retried_model_limit
        else "complete packet auto-promoted from needs_task_packet"
    )
    updated["integration_status"] = "worker_ready"
    updated["worker_ready_promoted_at"] = promoted_at
    updated["worker_ready_promoted_by"] = "scripts/agent_control/promote_worker_ready_tasks.py"

    history = updated.get("worker_ready_promotion_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "promoted_at": promoted_at,
        "previous_status": previous_status,
        "previous_dispatcher_decision": previous_decision,
        "previous_packet_status": previous_packet_status,
        "previous_normalization_status": previous_normalization,
        "previous_integration_status": previous_integration_status,
    })
    updated["worker_ready_promotion_history"] = history

    if updated.get("not_worker_ready_reason"):
        updated["not_worker_ready_reason"] = None
    if retried_model_limit:
        updated["blocked_reason"] = None
        updated["status_reason"] = "model-limit blocker cleared for worker retry"
        updated["model_limit_retry_promoted_at"] = promoted_at

    return apply_v2_packet(updated, promoted_at)


def process_queue(
    data: dict[str, Any],
    active_locks: set[str],
    task_delay_seconds: float = 0,
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    result = deepcopy(data)
    tasks = result.get("tasks", [])
    promoted: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    promoted_at = utc_now()
    changed = False

    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")

    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            if task_delay_seconds > 0:
                time.sleep(task_delay_seconds)
            continue
        task_id = str(task.get("id") or f"index-{index}")
        try:
            if (
                str(task.get("status") or "") in {"planned", "worker_ready"}
                and task.get("worker_ready") is True
                and was_promoted_from_model_limit(task)
                and task.get("model_limit_retry_allowed") is not True
            ):
                tasks[index] = repair_unapproved_model_limit_retry(task, promoted_at)
                skipped.append({
                    "task_id": task_id,
                    "reason": "reverted unapproved blocked_model_limit retry promotion",
                    "next_action": "wait_for_model_capacity",
                })
                changed = True
                continue
            if (
                str(task.get("status") or "") == "blocked"
                and str(task.get("integration_status") or "") == MODEL_LIMIT_INTEGRATION_STATUS
                and str(task.get("dispatcher_decision") or "") == MODEL_LIMIT_DISPATCHER_DECISION
                and task.get("model_limit_retry_allowed") is not True
                and str(task.get("next_owner") or "") not in {"Dispatcher", "dispatcher"}
            ):
                tasks[index] = repair_stale_model_limit_owner(task, promoted_at)
                skipped.append({
                    "task_id": task_id,
                    "reason": "repaired blocked_model_limit next_owner",
                    "next_action": "wait_for_model_capacity",
                })
                changed = True
                continue
            if str(task.get("status") or "") not in SOURCE_STATUSES and not is_retriable_model_limit_block(task):
                continue
            blockers = packet_blockers(task, active_locks)
            if blockers:
                skipped.append({
                    "task_id": task_id,
                    "reason": "; ".join(blockers),
                    "next_action": "dispatcher_review",
                })
                continue
            tasks[index] = promote_task(task, promoted_at)
            promoted.append({"task_id": task_id, "status": "planned", "worker_ready": True})
            changed = True
        finally:
            if task_delay_seconds > 0:
                time.sleep(task_delay_seconds)

    if changed:
        result["updated_at"] = promoted_at

    return result, promoted, skipped


def run_once(
    queue_path: Path,
    locks_path: Path | None,
    apply: bool,
    task_delay_seconds: float = 0,
) -> dict[str, Any]:
    queue = load_json(queue_path)
    locks = load_json(locks_path) if locks_path and locks_path.exists() else None
    updated, promoted, skipped = process_queue(queue, active_lock_task_ids(locks), task_delay_seconds)

    if apply and updated != queue:
        write_json(queue_path, updated)

    return {
        "queue": str(queue_path),
        "checked_at": utc_now(),
        "dry_run": not apply,
        "promoted_count": len(promoted),
        "skipped_count": len(skipped),
        "promoted": promoted,
        "skipped": skipped,
    }


def print_report(report: dict[str, Any], json_output: bool) -> None:
    if json_output:
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
        return

    mode = "dry-run" if report.get("dry_run") else "apply"
    print(f"queue: {report['queue']}", flush=True)
    print(f"checked_at: {report['checked_at']}", flush=True)
    print(f"mode: {mode}", flush=True)
    print(f"promoted: {report['promoted_count']}", flush=True)
    print(f"skipped: {report['skipped_count']}", flush=True)
    for item in report["promoted"]:
        print(f"PROMOTE {item['task_id']}: planned worker_ready=true", flush=True)
    for item in report["skipped"]:
        print(f"SKIP {item['task_id']}: {item['reason']} -> dispatcher_review", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Promote complete needs_task_packet rows to worker-ready planned tasks.")
    parser.add_argument("--queue", required=True, help="Path to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--locks", help="Optional path to AiStudio/Task_manager/agent_locks.json.")
    parser.add_argument("--apply", action="store_true", help="Write changes. Default is dry-run.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    parser.add_argument("--watch", action="store_true", help="Keep checking the queue until stopped.")
    parser.add_argument("--task-delay", type=float, default=None, help="Seconds to sleep after each inspected task in watch mode. Defaults to 1.")
    parser.add_argument("--cycle-delay", type=float, default=1800, help="Seconds to sleep after a full watch cycle. Defaults to 1800.")
    parser.add_argument("--max-cycles", type=int, help="Optional watch cycle limit for tests or scheduled wrappers.")
    args = parser.parse_args()

    queue_path = Path(args.queue).resolve()
    locks_path = Path(args.locks).resolve() if args.locks else None

    if args.watch:
        task_delay = 1.0 if args.task_delay is None else max(0.0, args.task_delay)
        cycle_delay = max(0.0, args.cycle_delay)
        cycles = 0
        while True:
            cycles += 1
            report = run_once(queue_path, locks_path, args.apply, task_delay)
            report["cycle"] = cycles
            report["watch"] = True
            report["next_cycle_delay_seconds"] = cycle_delay
            print_report(report, args.json)
            if args.max_cycles is not None and cycles >= args.max_cycles:
                break
            time.sleep(cycle_delay)
        return 0

    report = run_once(queue_path, locks_path, args.apply)
    print_report(report, args.json)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
