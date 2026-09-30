#!/usr/bin/env python3
"""Fail-closed eligibility gate for the bounded S-task Fast Track."""

from __future__ import annotations

from typing import Any

import claim_next_task
import validate_task_queue_readiness

MAX_ALLOWED_PATHS = 3
MAX_CHECKS = 3
HIGH_RISK_PATH_MARKERS = (
    ".env",
    ".github/workflows/",
    "aistudio/task_manager/",
    "credential",
    "deploy/",
    "infra/",
    "migration",
    "release/",
    "secret",
    "systemd/",
    "terraform/",
)


def high_risk_paths(task: dict[str, Any]) -> list[str]:
    paths = [
        str(path).replace("\\", "/").strip().lower()
        for field in ("allowed_paths", "changed_paths")
        for path in (task.get(field) if isinstance(task.get(field), list) else [])
    ]
    return [path for path in paths if any(marker in path for marker in HIGH_RISK_PATH_MARKERS)]


def evaluate(
    task_id: str,
    queue: dict[str, Any],
    locks: dict[str, Any],
    worker_id: str,
    profile: dict[str, Any],
) -> dict[str, Any]:
    tasks = [item for item in queue.get("tasks", []) if isinstance(item, dict)]
    task = next((item for item in tasks if claim_next_task.task_id(item) == task_id), None)
    reasons: list[str] = []

    if task is None:
        reasons.append("task_not_found")
    else:
        if validate_task_queue_readiness.packet_schema_version(task) != 2:
            reasons.append("worker_packet_v2_required")
        if claim_next_task.task_complexity(task) != "S":
            reasons.append("complexity_s_required")

        allowed_paths = task.get("allowed_paths")
        checks = task.get("checks")
        if not isinstance(allowed_paths, list) or not (1 <= len(allowed_paths) <= MAX_ALLOWED_PATHS):
            reasons.append("allowed_paths_limit_exceeded")
        if not isinstance(checks, list) or not (1 <= len(checks) <= MAX_CHECKS):
            reasons.append("checks_limit_exceeded")

        source_values = claim_next_task.scheduling_source_values(task)
        if claim_next_task.sensitive_or_security_remediation(task, source_values):
            reasons.append("sensitive_or_security_task")
        if high_risk_paths(task):
            reasons.append("high_risk_paths")

        reasons.extend(claim_next_task.worker_packet_defects(task))
        completed_ids = claim_next_task.completed_task_ids(tasks)
        locked_ids = claim_next_task.active_lock_ids(locks)
        tasks_by_id = {
            claim_next_task.task_id(item): item
            for item in tasks
            if claim_next_task.task_id(item)
        }
        if not claim_next_task.eligible(
            task,
            profile,
            worker_id,
            locked_ids,
            completed_ids,
            require_packet_v2=True,
            tasks_by_id=tasks_by_id,
        ):
            reasons.append("worker_claim_gate_rejected")

    reasons = list(dict.fromkeys(reasons))
    eligible = not reasons
    return {
        "schema_version": "1.0",
        "task_id": task_id,
        "eligible": eligible,
        "route": "fast_track" if eligible else "standard_lifecycle",
        "reasons": reasons,
        "limits": {
            "complexity": "S",
            "max_allowed_paths": MAX_ALLOWED_PATHS,
            "max_checks": MAX_CHECKS,
        },
    }
