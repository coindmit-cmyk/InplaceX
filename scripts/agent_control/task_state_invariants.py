#!/usr/bin/env python3
"""Canonical task-state projection and semantic invariant validation."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


TERMINAL_STATUSES = {"done", "stale_or_superseded"}
ACTIVE_LOCK_STATES = {"active", "claimed", "in_progress", "locked", "running", "worker_claimed"}
ACTIVE_PACKET_STATES = {"ready", "worker_ready"}
ACCEPTANCE_EVIDENCE_FIELDS = {
    "accepted_merge_commit",
    "completion_evidence",
    "finalization_recorded_at",
    "finalized_at",
    "merge_commit",
    "owner_accepted_at",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict)):
        return bool(value)
    return True


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "missing-id")


def has_finalization_event(task: dict[str, Any]) -> bool:
    for item in task.get("status_history") or []:
        if not isinstance(item, dict):
            continue
        if str(item.get("event") or "") in {"finalization_recorded", "integration_recorded"}:
            return True
    return False


def has_acceptance_evidence(task: dict[str, Any]) -> bool:
    return any(has_value(task.get(field)) for field in ACCEPTANCE_EVIDENCE_FIELDS) or has_finalization_event(task)


def previous_state(task: dict[str, Any]) -> str | None:
    if has_value(task.get("previous_state")):
        return str(task["previous_state"])
    history = task.get("status_history")
    if isinstance(history, list):
        for item in reversed(history):
            if isinstance(item, dict) and has_value(item.get("from")):
                return str(item["from"])
    return None


def terminal_reason(task: dict[str, Any]) -> str:
    for field in (
        "terminal_reason",
        "finalization_reason",
        "release_reason",
        "dispatcher_decision_reason",
        "not_worker_ready_reason",
    ):
        if has_value(task.get(field)):
            return str(task[field])
    return str(task.get("status") or "terminal")


def terminal_at(task: dict[str, Any], now: str) -> str:
    for field in ("terminal_at", "finalized_at", "completed_at", "updated_at"):
        if has_value(task.get(field)):
            return str(task[field])
    return now


def normalize_terminal_task(task: dict[str, Any], *, now: str | None = None) -> dict[str, Any]:
    status = str(task.get("status") or "")
    if status not in TERMINAL_STATUSES:
        return dict(task)
    now = now or utc_now()
    normalized = dict(task)
    normalized.update(
        {
            "worker_ready": False,
            "execution_ready": False,
            "lock": "free",
            "owner": None,
            "owner_lane": None,
            "next_owner": None,
            "next_role": None,
            "worker_id": None,
            "packet_status": "terminal",
            "normalization_status": "terminal",
            "dispatcher_decision": status,
            "terminal": True,
            "terminal_reason": terminal_reason(task),
            "terminal_at": terminal_at(task, now),
        }
    )
    if status == "done":
        normalized["integration_status"] = "finalized"
    elif has_value(normalized.get("integration_status")):
        normalized["integration_status"] = "not_applicable"
    normalized["canonical_state"] = {
        "state": status,
        "state_version": 1,
        "owner_lane": None,
        "execution_ready": False,
        "terminal": True,
        "terminal_reason": normalized["terminal_reason"],
        "terminal_at": normalized["terminal_at"],
        "previous_state": previous_state(task),
    }
    return normalized


def validate_task_state(
    task: dict[str, Any],
    *,
    path: str | None = None,
    strict_terminal: bool = True,
) -> list[dict[str, str]]:
    path = path or f"task({task_id(task)})"
    status = str(task.get("status") or "")
    terminal = status in TERMINAL_STATUSES
    issues: list[dict[str, str]] = []

    def error(code: str, message: str) -> None:
        issues.append({"severity": "error", "code": code, "path": path, "message": message})

    if terminal and strict_terminal:
        if task.get("worker_ready") is True or task.get("execution_ready") is True:
            error("terminal_execution_ready", "terminal task cannot be execution-ready")
        if str(task.get("lock") or "").lower() in ACTIVE_LOCK_STATES:
            error("terminal_active_lock", "terminal task cannot retain an active lock")
        active_owners = [
            field
            for field in ("owner", "owner_lane", "next_owner", "next_role", "worker_id")
            if has_value(task.get(field))
        ]
        if active_owners:
            error("terminal_active_owner", f"terminal task retains active owner fields: {', '.join(active_owners)}")
        if str(task.get("packet_status") or "").lower() in ACTIVE_PACKET_STATES:
            error("terminal_active_packet", "terminal task cannot retain a worker-ready packet state")
        if status == "done" and not has_acceptance_evidence(task):
            error("done_without_acceptance_evidence", "done task requires merge, finalization or explicit completion evidence")
    elif not terminal and task.get("terminal") is True:
        error("nonterminal_terminal_flag", "non-terminal status cannot set terminal=true")

    canonical = task.get("canonical_state")
    if isinstance(canonical, dict):
        if str(canonical.get("state") or "") != status:
            error("canonical_state_mismatch", "canonical_state.state must match status")
        if bool(canonical.get("terminal")) != terminal:
            error("canonical_terminal_mismatch", "canonical_state.terminal must match status terminality")
        if terminal and strict_terminal and (
            has_value(canonical.get("owner_lane")) or canonical.get("execution_ready") is not False
        ):
            error("canonical_terminal_route", "terminal canonical state cannot own an execution lane")

    if task.get("worker_ready") is True and str(task.get("packet_status") or "").lower() not in ACTIVE_PACKET_STATES:
        error("worker_ready_without_packet", "worker_ready=true requires packet_status ready or worker_ready")
    return issues


def validate_payload(payload: dict[str, Any], *, strict_terminal: bool = True) -> list[dict[str, str]]:
    tasks = payload.get("tasks")
    if not isinstance(tasks, list):
        return [{"severity": "error", "code": "tasks_not_array", "path": "tasks", "message": "tasks must be an array"}]
    issues: list[dict[str, str]] = []
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            issues.append(
                {
                    "severity": "error",
                    "code": "task_not_object",
                    "path": f"tasks[{index}]",
                    "message": "task must be an object",
                }
            )
            continue
        issues.extend(
            validate_task_state(
                task,
                path=f"tasks[{index}]({task_id(task)})",
                strict_terminal=strict_terminal,
            )
        )
    return issues


def validate_queue_history_disjoint(
    queue: dict[str, Any],
    history: dict[str, Any],
) -> list[dict[str, str]]:
    history_tasks = history.get("tasks")
    if not isinstance(history_tasks, list):
        return [
            {
                "severity": "error",
                "code": "history_tasks_not_array",
                "path": "history.tasks",
                "message": "task history tasks must be an array",
            }
        ]
    history_ids = {
        task_id(task)
        for task in history_tasks
        if isinstance(task, dict) and task_id(task) != "missing-id"
    }
    issues: list[dict[str, str]] = []
    for index, task in enumerate(queue.get("tasks") or []):
        if not isinstance(task, dict):
            continue
        identifier = task_id(task)
        if identifier in history_ids:
            issues.append(
                {
                    "severity": "error",
                    "code": "active_queue_history_collision",
                    "path": f"tasks[{index}]({identifier})",
                    "message": "task ID exists in both the active queue and terminal history",
                }
            )
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True)
    parser.add_argument(
        "--history",
        help="Task history path. Defaults to task_history.json next to the queue when it exists.",
    )
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    path = Path(args.queue).resolve()
    payload = json.loads(path.read_text(encoding="utf-8"))
    issues = validate_payload(payload)
    history_path = Path(args.history).resolve() if args.history else path.with_name("task_history.json")
    history_exists = history_path.exists()
    if args.history and not history_exists:
        issues.append(
            {
                "severity": "error",
                "code": "history_missing",
                "path": "history",
                "message": f"explicit task history path does not exist: {history_path}",
            }
        )
    elif history_exists:
        history = json.loads(history_path.read_text(encoding="utf-8"))
        if not isinstance(history, dict):
            issues.append(
                {
                    "severity": "error",
                    "code": "history_not_object",
                    "path": "history",
                    "message": "task history must be an object",
                }
            )
        else:
            issues.extend(validate_queue_history_disjoint(payload, history))
    report = {
        "queue": str(path),
        "history": str(history_path) if args.history or history_exists else None,
        "tasks": len(payload.get("tasks") or []),
        "errors": sum(1 for issue in issues if issue["severity"] == "error"),
        "warnings": sum(1 for issue in issues if issue["severity"] == "warning"),
        "issues": issues,
    }
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"errors: {report['errors']}")
        for issue in issues:
            print(f"{issue['severity'].upper()} {issue['code']} {issue['path']}: {issue['message']}")
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
