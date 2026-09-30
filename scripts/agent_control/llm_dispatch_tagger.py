#!/usr/bin/env python3
"""Tag worker-ready tasks for local LLM eligibility and execution mode."""

from __future__ import annotations

import argparse
import json
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_file


EXECUTION_MODES = {"codex_only", "parallel_debug", "llm_only"}
ACTIVE_QUEUE_STATES = {"claimed", "running", "in_progress"}
TERMINAL_QUEUE_STATES = {"completed", "failed_quality_gate"}
DEFAULT_SUCCESS_THRESHOLD = {"attempts": 100, "successes": 100, "failures": 0}
DEFAULT_QUEUE = {"enabled": True, "concurrency_per_kind": 1}
DEFAULT_GRANULARITY = {"max_complexity": "M", "max_allowed_paths": 4, "max_checks": 6}
COMPLEXITY_RANK = {"XS": 0, "S": 1, "M": 2, "L": 3, "XL": 4}
HIGH_RISK_HINTS = {
    "security",
    "secret",
    "payment",
    "billing",
    "production",
    "deploy",
    "migration",
    "merge",
    "release",
    "owner",
    "credential",
}
V2_FIELDS = (
    "worker_instructions",
    "traceability",
    "doc_refs",
    "input_refs",
    "output_contract",
    "script_actions",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return bool(value)
    if isinstance(value, dict):
        return bool(value)
    return True


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def scoped_allowed_paths(task: dict[str, Any]) -> list[Any]:
    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    narrowed = as_list(input_refs.get("allowed_paths"))
    return narrowed or as_list(task.get("allowed_paths"))


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def task_kind(task: dict[str, Any]) -> str:
    for field in ("llm_task_kind", "task_kind", "type", "area"):
        value = str(task.get(field) or "").strip()
        if value:
            return value
    return "unknown"


def is_worker_ready_v2(task: dict[str, Any]) -> bool:
    if task.get("status") not in {"planned", "worker_ready", "needs_stronger_agent"}:
        return False
    if task.get("worker_ready") is not True or task.get("dispatcher_decision") != "worker_ready":
        return False
    if int(task.get("packet_schema_version") or 1) < 2:
        return False
    return all(has_value(task.get(field)) for field in V2_FIELDS)


def has_high_risk_scope(task: dict[str, Any]) -> bool:
    text_parts: list[str] = [
        str(task.get("title") or ""),
        str(task.get("summary") or ""),
        str(task.get("type") or ""),
        str(task.get("area") or ""),
        " ".join(str(item) for item in as_list(task.get("allowed_paths"))),
        " ".join(str(item) for item in as_list(task.get("checks"))),
    ]
    text = " ".join(text_parts).lower()
    return any(hint in text for hint in HIGH_RISK_HINTS)


def policy_for_kind(policy: dict[str, Any], kind: str) -> dict[str, Any]:
    kinds = policy.get("task_kinds")
    if isinstance(kinds, dict):
        item = kinds.get(kind)
        if isinstance(item, dict):
            return item
    defaults = policy.get("defaults")
    return defaults if isinstance(defaults, dict) else {}


def threshold_met(kind_policy: dict[str, Any], policy: dict[str, Any]) -> bool:
    threshold = kind_policy.get("promotion_threshold") or policy.get("promotion_threshold") or DEFAULT_SUCCESS_THRESHOLD
    evidence = kind_policy.get("evidence") if isinstance(kind_policy.get("evidence"), dict) else {}
    attempts = int(evidence.get("attempts") or 0)
    successes = int(evidence.get("successes") or 0)
    failures = int(evidence.get("failures") or 0)
    required_attempts = int(threshold.get("attempts") or DEFAULT_SUCCESS_THRESHOLD["attempts"])
    required_successes = int(threshold.get("successes") or DEFAULT_SUCCESS_THRESHOLD["successes"])
    max_failures = int(threshold.get("failures") if threshold.get("failures") is not None else DEFAULT_SUCCESS_THRESHOLD["failures"])
    return attempts >= required_attempts and successes >= required_successes and failures <= max_failures


def granularity_settings(kind_policy: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    settings = deepcopy(DEFAULT_GRANULARITY)
    policy_granularity = policy.get("granularity")
    if isinstance(policy_granularity, dict):
        settings.update(policy_granularity)
    kind_granularity = kind_policy.get("granularity")
    if isinstance(kind_granularity, dict):
        settings.update(kind_granularity)
    return settings


def complexity_rank(value: Any) -> int:
    return COMPLEXITY_RANK.get(str(value or "").strip().upper(), COMPLEXITY_RANK["M"])


def granularity_issue(task: dict[str, Any], kind_policy: dict[str, Any], policy: dict[str, Any]) -> str | None:
    settings = granularity_settings(kind_policy, policy)
    max_complexity = str(settings.get("max_complexity") or DEFAULT_GRANULARITY["max_complexity"]).strip().upper()
    if complexity_rank(task.get("complexity")) > complexity_rank(max_complexity):
        return f"task complexity {task.get('complexity')} exceeds local LLM max {max_complexity}; Dispatcher should split"

    try:
        max_allowed_paths = int(settings.get("max_allowed_paths") or DEFAULT_GRANULARITY["max_allowed_paths"])
    except (TypeError, ValueError):
        max_allowed_paths = DEFAULT_GRANULARITY["max_allowed_paths"]
    if len(scoped_allowed_paths(task)) > max_allowed_paths:
        return f"allowed_paths exceed local LLM max {max_allowed_paths}; Dispatcher should split by module/path"

    try:
        max_checks = int(settings.get("max_checks") or DEFAULT_GRANULARITY["max_checks"])
    except (TypeError, ValueError):
        max_checks = DEFAULT_GRANULARITY["max_checks"]
    if len(as_list(task.get("checks"))) > max_checks:
        return f"checks exceed local LLM max {max_checks}; Dispatcher should split"
    return None


def queue_settings(kind_policy: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    settings = deepcopy(DEFAULT_QUEUE)
    policy_queue = policy.get("queue")
    if isinstance(policy_queue, dict):
        settings.update(policy_queue)
    kind_queue = kind_policy.get("queue")
    if isinstance(kind_queue, dict):
        settings.update(kind_queue)
    if "concurrency" in settings and "concurrency_per_kind" not in settings:
        settings["concurrency_per_kind"] = settings["concurrency"]
    try:
        settings["concurrency_per_kind"] = max(1, int(settings.get("concurrency_per_kind") or 1))
    except (TypeError, ValueError):
        settings["concurrency_per_kind"] = 1
    settings["enabled"] = settings.get("enabled") is not False
    return settings


def classify(task: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    tid = task_id(task)
    kind = task_kind(task)
    item_policy = policy_for_kind(policy, kind)
    reasons: list[str] = []

    if not is_worker_ready_v2(task):
        return {
            "task_id": tid,
            "llm_candidate": False,
            "llm_execution_mode": "codex_only",
            "llm_task_kind": kind,
            "llm_granularity_status": "not_applicable",
            "reason": "not a worker-ready packet v2 task",
        }
    if has_high_risk_scope(task):
        return {
            "task_id": tid,
            "llm_candidate": False,
            "llm_execution_mode": "codex_only",
            "llm_task_kind": kind,
            "llm_granularity_status": "not_applicable",
            "reason": "high-risk scope stays Codex-only",
        }

    allowed = bool(item_policy.get("llm_candidate") or item_policy.get("candidate") or item_policy.get("allow_llm"))
    if not allowed:
        return {
            "task_id": tid,
            "llm_candidate": False,
            "llm_execution_mode": "codex_only",
            "llm_task_kind": kind,
            "llm_granularity_status": "not_applicable",
            "reason": f"task kind {kind} is not allowed by local LLM policy",
        }

    split_reason = granularity_issue(task, item_policy, policy)
    if split_reason:
        return {
            "task_id": tid,
            "llm_candidate": False,
            "llm_execution_mode": "codex_only",
            "llm_task_kind": kind,
            "llm_granularity_status": "needs_dispatcher_split",
            "reason": split_reason,
        }

    configured_mode = str(item_policy.get("execution_mode") or item_policy.get("mode") or "parallel_debug")
    if configured_mode not in EXECUTION_MODES:
        configured_mode = "parallel_debug"
        reasons.append("unknown policy mode normalized to parallel_debug")

    if configured_mode == "llm_only" and not threshold_met(item_policy, policy):
        configured_mode = "parallel_debug"
        reasons.append("llm_only threshold not met; collecting parallel evidence")
    elif configured_mode == "parallel_debug":
        reasons.append("parallel debug mode collects Codex-vs-LLM comparison evidence")
    elif configured_mode == "llm_only":
        reasons.append("llm_only threshold met")

    return {
        "task_id": tid,
        "llm_candidate": True,
        "llm_execution_mode": configured_mode,
        "llm_task_kind": kind,
        "llm_triage_only": bool(item_policy.get("triage_only", False)),
        "llm_triage_route_on_pass": str(item_policy.get("triage_route_on_pass") or "evidence_only"),
        "llm_granularity_status": "eligible",
        "reason": "; ".join(reasons) if reasons else "allowed by local LLM policy",
    }


def update_task(task: dict[str, Any], decision: dict[str, Any], policy_path: Path, checked_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    updated["llm_candidate"] = decision["llm_candidate"]
    updated["llm_execution_mode"] = decision["llm_execution_mode"]
    updated["llm_task_kind"] = decision["llm_task_kind"]
    updated["llm_triage_only"] = bool(decision.get("llm_triage_only", False))
    updated["llm_triage_route_on_pass"] = str(decision.get("llm_triage_route_on_pass") or "evidence_only")
    updated["llm_granularity_status"] = decision.get("llm_granularity_status") or "not_applicable"
    updated["llm_dispatch_reason"] = decision["reason"]
    updated["llm_dispatch_policy"] = policy_path.as_posix()
    updated["llm_dispatch_tagged_at"] = checked_at
    updated["llm_parallel_required"] = decision["llm_candidate"] and decision["llm_execution_mode"] == "parallel_debug"
    return updated


def is_active_llm_task(task: dict[str, Any]) -> bool:
    state = str(task.get("llm_queue_state") or "").strip()
    return state in ACTIVE_QUEUE_STATES or has_value(task.get("llm_claimed_by")) or has_value(task.get("llm_started_at"))


def apply_queue_states(
    tasks: list[Any],
    decisions: list[tuple[int, dict[str, Any]]],
    policy: dict[str, Any],
) -> dict[str, int]:
    counts = {
        "llm_queue_ready": 0,
        "llm_queue_waiting": 0,
        "llm_queue_active": 0,
        "llm_queue_completed": 0,
        "llm_queue_failed": 0,
    }
    by_kind: dict[str, list[tuple[int, dict[str, Any], dict[str, Any]]]] = {}

    for index, decision in decisions:
        task = tasks[index]
        if not isinstance(task, dict):
            continue
        if not decision["llm_candidate"] or decision["llm_execution_mode"] == "codex_only":
            task["llm_queue_key"] = None
            task["llm_queue_state"] = "not_applicable"
            task["llm_queue_position"] = None
            task["llm_queue_concurrency"] = 0
            task["llm_queue_reason"] = "not eligible for local LLM queue"
            continue
        state = str(task.get("llm_queue_state") or "").strip()
        if state in TERMINAL_QUEUE_STATES:
            task["llm_queue_key"] = f"task_kind:{decision['llm_task_kind']}"
            task["llm_queue_position"] = None
            task["llm_queue_concurrency"] = int(queue_settings(policy_for_kind(policy, decision["llm_task_kind"]), policy)["concurrency_per_kind"])
            if state == "completed":
                task["llm_queue_reason"] = task.get("llm_queue_reason") or "local LLM evidence already completed"
                counts["llm_queue_completed"] += 1
            else:
                task["llm_queue_reason"] = task.get("llm_queue_reason") or "local LLM quality gate failed; dispatcher review required"
                counts["llm_queue_failed"] += 1
            continue
        by_kind.setdefault(decision["llm_task_kind"], []).append((index, task, decision))

    for kind, items in by_kind.items():
        kind_policy = policy_for_kind(policy, kind)
        settings = queue_settings(kind_policy, policy)
        queue_key = f"task_kind:{kind}"
        concurrency = int(settings["concurrency_per_kind"])

        if not settings["enabled"]:
            for _, task, _ in items:
                task["llm_queue_key"] = queue_key
                task["llm_queue_state"] = "ready"
                task["llm_queue_position"] = 1
                task["llm_queue_concurrency"] = concurrency
                task["llm_queue_reason"] = "local LLM queue disabled by policy"
                counts["llm_queue_ready"] += 1
            continue

        active_count = sum(1 for _, task, _ in items if is_active_llm_task(task))
        ready_slots = max(0, concurrency - active_count)
        next_position = 1
        for _, task, _ in items:
            task["llm_queue_key"] = queue_key
            task["llm_queue_concurrency"] = concurrency
            if is_active_llm_task(task):
                task["llm_queue_state"] = str(task.get("llm_queue_state") or "in_progress")
                task["llm_queue_position"] = 0
                task["llm_queue_reason"] = "local LLM task already occupies this task-kind queue"
                counts["llm_queue_active"] += 1
            elif ready_slots > 0:
                task["llm_queue_state"] = "ready"
                task["llm_queue_position"] = next_position
                task["llm_queue_reason"] = "next local LLM task for this task kind"
                counts["llm_queue_ready"] += 1
                ready_slots -= 1
                next_position += 1
            else:
                task["llm_queue_state"] = "waiting"
                task["llm_queue_position"] = next_position
                task["llm_queue_reason"] = "waiting for earlier local LLM task of this kind"
                counts["llm_queue_waiting"] += 1
                next_position += 1

    return counts


def process_queue(queue: dict[str, Any], policy: dict[str, Any], policy_path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    result = deepcopy(queue)
    tasks = result.get("tasks")
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    checked_at = utc_now()
    decisions: list[dict[str, Any]] = []
    indexed_decisions: list[tuple[int, dict[str, Any]]] = []
    counts = {"codex_only": 0, "parallel_debug": 0, "llm_only": 0, "llm_candidate": 0}
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        decision = classify(task, policy)
        decisions.append(decision)
        indexed_decisions.append((index, decision))
        counts[decision["llm_execution_mode"]] += 1
        if decision["llm_candidate"]:
            counts["llm_candidate"] += 1
        tasks[index] = update_task(task, decision, policy_path, checked_at)
    counts.update(apply_queue_states(tasks, indexed_decisions, policy))
    if decisions:
        result["updated_at"] = checked_at
    return result, {
        "checked_at": checked_at,
        "policy": str(policy_path),
        "tasks": len(decisions),
        "counts": counts,
        "decisions": decisions,
    }


def default_policy_path(project_root: Path) -> Path:
    return task_file(project_root, "local_llm_dispatch_policy.json")


def main() -> int:
    parser = argparse.ArgumentParser(description="Tag queue tasks for local LLM dispatch eligibility and mode.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--queue", help="Defaults to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--policy", help="Defaults to AiStudio/Task_manager/local_llm_dispatch_policy.json.")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    policy_path = Path(args.policy).resolve() if args.policy else default_policy_path(project_root)
    queue = load_json(queue_path)
    policy = load_json(policy_path)
    updated, report = process_queue(queue, policy, policy_path)
    report["queue"] = str(queue_path)
    report["dry_run"] = not args.apply
    if args.apply:
        write_json(queue_path, updated)

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"queue: {queue_path}")
        print(f"policy: {policy_path}")
        print(f"mode: {'apply' if args.apply else 'dry-run'}")
        for key, value in report["counts"].items():
            print(f"{key}: {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
