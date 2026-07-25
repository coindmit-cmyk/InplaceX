#!/usr/bin/env python3
"""Build compact automation status JSON/Markdown from queue, events and reports."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

from _rebuild_common import load_json, utc_now, write_json
from event_driven_scheduler import finalization_recorded_task_ids, is_worker_ready, pending_finalizer_events, pending_integrator_events, read_events, tasks
from process_log import append_log
from project_paths import task_manager_dir


def count_queue(queue: Any) -> Counter[str]:
    counts: Counter[str] = Counter()
    for task in tasks(queue):
        status = str(task.get("status") or "unknown")
        counts[status] += 1
        if is_worker_ready(task):
            counts["worker_ready"] += 1
        if str(task.get("dispatcher_decision") or "") == "needs_task_packet":
            counts["needs_task_packet"] += 1
        if str(task.get("type") or "").startswith("clean-rebuild"):
            counts["clean_rebuild_tasks"] += 1
    return counts


def count_decisions(decisions: dict[str, Any]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for item in decisions.get("items") or []:
        if isinstance(item, dict):
            counts[str(item.get("decision") or "unknown")] += 1
    return counts


def pending_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [event for event in events if not event.get("consumed_by")]


def active_ready_to_finalize(handoff: dict[str, Any], events: list[dict[str, Any]]) -> list[str]:
    ready = handoff.get("ready_to_finalize") if isinstance(handoff, dict) else []
    if not isinstance(ready, list):
        return []
    recorded = finalization_recorded_task_ids(events)
    active: list[str] = []
    for item in ready:
        value = str(item or "").strip()
        if not value:
            continue
        keys = {value, value.removeprefix("task:") if value.startswith("task:") else f"task:{value}"}
        if keys & recorded:
            continue
        active.append(value)
    return active


def next_actions(project_root: Path, counts: Counter[str], pending: list[dict[str, Any]], events: list[dict[str, Any]] | None = None) -> list[str]:
    names = {str(event.get("event") or "") for event in pending}
    finalizer_pending = pending_finalizer_events(pending, events or pending)
    integrator_pending = pending_integrator_events(project_root, pending)
    actions: list[str] = []
    if {"dispatcher_rebuild_requested", "provisional_crb_requested", "llm_advisory_requested"} & names:
        actions.append("run dispatcher_rebuild_planner and provisional_crb_task_builder")
    if "crb_task_created" in names:
        actions.append("run clean_rebuild_queue_bridge")
    if counts.get("worker_ready") or "worker_ready_available" in names:
        actions.append("run worker_pool_manager")
    if counts.get("ready_candidate") or integrator_pending:
        actions.append("run pre_integrator_repair / integration_batch_builder")
    if counts.get("ready_to_finalize") or finalizer_pending:
        actions.append("run Auto Finalizer gate")
    if counts.get("needs_human") or "needs_human_created" in names:
        actions.append("review needs_human queue")
    return actions


def build_status(project_root: Path, *, queue: Any, decisions: dict[str, Any], handoff: dict[str, Any], events: list[dict[str, Any]], latest_reports: list[str]) -> dict[str, Any]:
    counts = Counter()
    counts.update(count_queue(queue))
    counts.update(count_decisions(decisions))
    counts["pending_events"] = len(pending_events(events))
    if isinstance(handoff, dict):
        counts["ready_to_finalize"] += len(active_ready_to_finalize(handoff, events))
        counts["needs_human"] += len(handoff.get("needs_human") or [])
        counts["cleanup_candidates"] += len(handoff.get("cleanup_candidates") or [])
    pending = pending_events(events)
    human_queue = [event for event in pending if str(event.get("event") or "") == "needs_human_created"][:50]
    blocked = [event for event in pending if str(event.get("severity") or "") in {"blocked", "critical"}][:50]
    return {
        "schema_version": 1,
        "project": project_root.name,
        "project_root": str(project_root),
        "updated_at": utc_now(),
        "counts": dict(counts),
        "next_actions": next_actions(project_root, counts, pending, events),
        "human_queue": human_queue,
        "blocked": blocked,
        "latest_reports": [path for path in latest_reports if path],
    }


def render_markdown(status: dict[str, Any]) -> str:
    lines = [
        "# Automation Status",
        "",
        f"- Project: `{status.get('project')}`",
        f"- Updated: `{status.get('updated_at')}`",
        "",
        "## Counts",
        "",
    ]
    for key, value in sorted((status.get("counts") or {}).items()):
        lines.append(f"- `{key}`: `{value}`")
    lines += ["", "## Next Actions", ""]
    for action in status.get("next_actions") or ["No automatic action detected"]:
        lines.append(f"- {action}")
    lines += ["", "## Human / Blocked", ""]
    lines.append(f"- Human queue: `{len(status.get('human_queue') or [])}`")
    lines.append(f"- Blocked: `{len(status.get('blocked') or [])}`")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue")
    parser.add_argument("--decisions")
    parser.add_argument("--handoff")
    parser.add_argument("--events")
    parser.add_argument("--output-json")
    parser.add_argument("--output-md")
    parser.add_argument("--apply", action="store_true", help="Write default automation_status artifacts and process log. Default --json is read-only unless explicit outputs are provided.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    queue_path = Path(args.queue).resolve() if args.queue else plans / "task_queue.json"
    decisions_path = Path(args.decisions).resolve() if args.decisions else plans / "rebuild_decision_report.json"
    handoff_path = Path(args.handoff).resolve() if args.handoff else plans / "integration_handoff.json"
    events_path = Path(args.events).resolve() if args.events else plans / "agent_events.jsonl"
    output_json = Path(args.output_json).resolve() if args.output_json else plans / "automation_status.json"
    output_md = Path(args.output_md).resolve() if args.output_md else plans / "automation_status.md"

    status = build_status(
        project_root,
        queue=load_json(queue_path, {}),
        decisions=load_json(decisions_path, {}),
        handoff=load_json(handoff_path, {}),
        events=read_events(events_path),
        latest_reports=[str(decisions_path), str(handoff_path), str(events_path)],
    )
    should_write = args.apply or bool(args.output_json) or bool(args.output_md)
    if should_write:
        write_json(output_json, status)
        output_md.parent.mkdir(parents=True, exist_ok=True)
        output_md.write_text(render_markdown(status), encoding="utf-8")
    if args.apply:
        append_log(project_root, "router", "compact_status_built", severity="info", counts=status["counts"])
    if args.json:
        print(json.dumps(status, ensure_ascii=False, indent=2))
    elif should_write:
        print(f"written: {output_json}")
    else:
        print("dry-run: compact status built")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
