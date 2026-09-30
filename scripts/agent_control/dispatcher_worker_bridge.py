#!/usr/bin/env python3
"""Prepare worker candidates by running queue normalization gates."""

from __future__ import annotations

import argparse
from collections import Counter
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import claim_next_task
from project_paths import task_file, task_reports_dir

from process_log import append_log


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def run(cmd: list[str]) -> tuple[int, str, str]:
    proc = subprocess.run(cmd, text=True, capture_output=True)
    return proc.returncode, proc.stdout, proc.stderr


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def worker_ready(task: dict[str, Any]) -> bool:
    if task.get("worker_ready") is not True or task.get("dispatcher_decision") != "worker_ready":
        return False
    if str(task.get("status") or "") not in {"planned", "worker_ready", "needs_stronger_agent"}:
        return False
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        return False
    for field in ("complexity", "priority", "type", "allowed_paths", "forbidden_paths", "acceptance_criteria", "checks"):
        value = task.get(field)
        if value is None or value == "" or value == []:
            return False
    return True


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict, tuple, set)):
        return bool(value)
    return True


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def completed_task_ids(tasks: list[dict[str, Any]]) -> set[str]:
    done_statuses = {"done", "completed", "finalized", "released", "archived", "owner_approved"}
    result: set[str] = set()
    for item in tasks:
        value = task_id(item)
        if value and str(item.get("status") or "").lower() in done_statuses:
            result.add(value)
    return result


def unresolved_dependencies(task: dict[str, Any], completed_ids: set[str]) -> list[str]:
    raw = task.get("depends_on")
    values = raw if isinstance(raw, list) else []
    return [str(item).strip() for item in values if str(item).strip() and str(item).strip() not in completed_ids]


def build_candidates(queue: dict[str, Any]) -> list[dict[str, Any]]:
    result = []
    tasks = queue.get("tasks", []) if isinstance(queue.get("tasks"), list) else []
    completed_ids = completed_task_ids([task for task in tasks if isinstance(task, dict)])
    for task in tasks:
        if not isinstance(task, dict) or not worker_ready(task):
            continue
        blocked_by = unresolved_dependencies(task, completed_ids)
        if blocked_by:
            continue
        scheduling_class, scheduling_class_reason = claim_next_task.scheduling_class(task)
        result.append({
            "task_id": task_id(task),
            "title": task.get("title"),
            "priority": task.get("priority"),
            "complexity": task.get("complexity"),
            "eligible_worker_profiles": task.get("eligible_worker_profiles", []),
            "allowed_paths": task.get("allowed_paths", []),
            "scheduling_class": scheduling_class,
            "scheduling_class_reason": scheduling_class_reason,
        })
    return result


def candidate_summary(candidates: list[dict[str, Any]]) -> dict[str, Any]:
    class_counts = Counter(str(item.get("scheduling_class") or "unknown") for item in candidates)
    return {
        "candidate_count": len(candidates),
        "scheduling_class_counts": dict(sorted(class_counts.items())),
        "integration_continuation_count": class_counts.get("integration_continuation", 0),
        "primary_candidate_count": class_counts.get("primary_delivery", 0),
        "background_candidate_count": class_counts.get("background_remediation", 0),
        "primary_slot_reservation_required": class_counts.get("primary_delivery", 0) > 0,
    }


def emit_event(project_root: Path, event: str, role: str, next_role: str, payload: dict[str, Any]) -> None:
    cmd = [
        sys.executable,
        str(script_path("emit_agent_event.py")),
        "--project-root",
        str(project_root),
        "--event",
        event,
        "--role",
        role,
        "--next-role",
        next_role,
        "--payload-json",
        json.dumps(payload, ensure_ascii=False),
    ]
    run(cmd)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run dispatcher-to-worker bridge.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--apply", action="store_true", help="Apply normalize/promote changes.")
    parser.add_argument("--emit-events", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = task_file(project_root, "task_queue.json")
    locks_path = task_file(project_root, "agent_locks.json")
    output_path = task_file(project_root, "worker_candidates.json")
    report_path = task_reports_dir(project_root) / f"DISPATCHER_WORKER_BRIDGE_{datetime.now().strftime('%Y-%m-%d')}.md"

    steps = []
    if not queue_path.exists():
        steps.append([sys.executable, str(script_path("queue_rebuild_from_sources.py")), "--project-root", str(project_root)])
    steps.extend([
        [sys.executable, str(script_path("task_docs_queue_importer.py")), "--project-root", str(project_root)],
        [sys.executable, str(script_path("dispatcher_integration_repair.py")), "--project-root", str(project_root)],
        [sys.executable, str(script_path("normalize_task_packets.py")), "--project-root", str(project_root)],
        [sys.executable, str(script_path("current_context_review.py")), "--project-root", str(project_root), "--queue", str(queue_path)],
        [sys.executable, str(script_path("dispatcher_packet_repair.py")), "--queue", str(queue_path)],
        [sys.executable, str(script_path("promote_worker_ready_tasks.py")), "--queue", str(queue_path), "--locks", str(locks_path)],
        [sys.executable, str(script_path("validate_task_queue_readiness.py")), "--queue", str(queue_path)],
        [sys.executable, str(script_path("dispatcher_decision_guard.py")), "--queue", str(queue_path)],
    ])
    if args.apply:
        for step in steps:
            if Path(step[1]).name in {"queue_rebuild_from_sources.py", "task_docs_queue_importer.py", "dispatcher_integration_repair.py", "normalize_task_packets.py", "current_context_review.py", "dispatcher_packet_repair.py", "promote_worker_ready_tasks.py"}:
                step.append("--apply")

    step_reports = []
    exit_code = 0
    for step in steps:
        code, stdout, stderr = run(step)
        step_reports.append({"step": Path(step[1]).name, "exit_code": code, "stdout": stdout, "stderr": stderr})
        if code != 0 and exit_code == 0:
            exit_code = code

    candidates = build_candidates(load_json(queue_path))
    summary = candidate_summary(candidates)
    output_written = None
    report_written = None
    if args.apply:
        output_path.write_text(
            json.dumps(
                {"generated_at": utc_now(), "summary": summary, "candidates": candidates},
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            "# Dispatcher Worker Bridge\n\n"
            f"Generated: `{utc_now()}`\n\n"
            f"- Apply: `{args.apply}`\n"
            f"- Worker candidates: `{len(candidates)}`\n"
            f"- Integration continuation: `{summary['integration_continuation_count']}`\n"
            f"- Primary delivery: `{summary['primary_candidate_count']}`\n"
            f"- Background remediation: `{summary['background_candidate_count']}`\n"
            f"- Primary slot reservation required: `{summary['primary_slot_reservation_required']}`\n"
            f"- Exit code: `{exit_code}`\n",
            encoding="utf-8",
        )
        append_log(
            project_root,
            "dispatcher",
            "dispatcher_worker_bridge",
            severity="info" if exit_code == 0 else "blocked",
            candidate_count=len(candidates),
            scheduling_class_counts=summary["scheduling_class_counts"],
            exit_code=exit_code,
        )
        output_written = str(output_path)
        report_written = str(report_path)

    if args.apply and args.emit_events:
        if candidates:
            emit_event(project_root, "worker_ready_available", "dispatcher_worker_bridge", "worker_pool", summary)
        elif exit_code != 0:
            emit_event(project_root, "dispatcher_requested", "dispatcher_worker_bridge", "auto_dispatcher", {"reason": "queue gates did not pass"})

    report = {
        "exit_code": exit_code,
        "candidate_count": len(candidates),
        "summary": summary,
        "output": output_written,
        "report": report_written,
        "dry_run": not args.apply,
        "steps": step_reports,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else f"worker candidates: {len(candidates)}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
