#!/usr/bin/env python3
"""Create an actionable Dispatcher rebuild plan from routed decisions.

This script plans owner handoff only. It may write plan/report files with
--apply, but it does not edit product project code and does not mark tasks done.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

from _rebuild_common import load_json, normalize_path, utc_now, write_json
from process_log import append_log
from project_paths import task_manager_dir


PLANNABLE_DECISIONS = {
    "crb_auto_task",
    "dispatcher_rebuild",
    "provisional_crb",
    "llm_advisory_classify",
}


def slugify(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9.]+", "-", value).strip("-")
    return (slug[:48] or "unknown").upper()


def task_id_for(item: dict[str, Any], used: set[str]) -> str:
    ids = item.get("task_ids") or []
    if ids:
        base = f"CRB-{str(ids[0]).upper()}"
    else:
        base = f"CRB-{slugify(str(item.get('branch') or 'UNTRACED'))}"
    candidate = base
    suffix = 2
    while candidate in used:
        candidate = f"{base}-{suffix}"
        suffix += 1
    used.add(candidate)
    return candidate


def operation_for(decision: str) -> str:
    if decision == "crb_auto_task":
        return "promote_clean_rebuild_queue_bridge"
    if decision == "provisional_crb":
        return "create_provisional_crb_task"
    if decision == "llm_advisory_classify":
        return "request_llm_then_create_crb_or_dispatcher_split"
    return "dispatcher_split_or_repacketize"


def build_plan(project_root: Path, decisions: dict[str, Any]) -> dict[str, Any]:
    used: set[str] = set()
    tasks: list[dict[str, Any]] = []
    for item in decisions.get("items") or []:
        if not isinstance(item, dict) or item.get("decision") not in PLANNABLE_DECISIONS:
            continue
        decision = str(item.get("decision"))
        source = item.get("source") if isinstance(item.get("source"), dict) else {}
        paths = [normalize_path(path) for path in item.get("changed_paths") or source.get("changed_paths") or [] if normalize_path(path)]
        task_id = task_id_for(item, used)
        tasks.append(
            {
                "id": task_id,
                "title": f"Clean rebuild route for {item.get('branch') or task_id}",
                "route": decision,
                "operation": operation_for(decision),
                "status": "planned",
                "next_owner": item.get("next_owner") or "auto-dispatcher",
                "next_event": item.get("next_event") or "dispatcher_rebuild_requested",
                "reason": item.get("reason"),
                "source_branch": item.get("branch") or source.get("branch") or source.get("source_branch"),
                "source_task_ids": item.get("task_ids") or [],
                "risk_class": item.get("risk_class"),
                "module_scopes": item.get("module_scopes") or [],
                "suggested_allowed_paths": paths[:25],
                "forbidden_paths": [".env", ".env.*", "secrets/**", "production credentials"],
                "acceptance_criteria": [
                    "Rebuild on current develop/release base instead of merging stale worker branch directly.",
                    "Preserve only the intended payload paths listed in suggested_allowed_paths.",
                    "Do not copy runtime, nested worktree, queue noise or unrelated coordination files.",
                    "Return precise needs_worker_fix/needs_human reason if rebuild cannot be made safe.",
                ],
                "checks": ["git diff --check", "run targeted checks for changed paths"],
                "provenance": {
                    "source": "dispatcher_rebuild_planner.py",
                    "decision": decision,
                    "created_from": "rebuild_decision_report",
                    "source_status": item.get("source_status"),
                },
            }
        )
    counts = Counter(task["route"] for task in tasks)
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "source": "dispatcher_rebuild_planner.py",
        "task_count": len(tasks),
        "counts": dict(counts),
        "tasks": tasks,
    }


def render_markdown(plan: dict[str, Any]) -> str:
    lines = [
        "# Dispatcher Rebuild Plan",
        "",
        f"- Generated: `{plan.get('created_at')}`",
        f"- Tasks: `{plan.get('task_count')}`",
        f"- Counts: `{json.dumps(plan.get('counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        "",
        "| Route | Operation | Task | Branch | Reason |",
        "| --- | --- | --- | --- | --- |",
    ]
    for task in plan.get("tasks") or []:
        reason = str(task.get("reason") or "").replace("|", "/")
        lines.append(f"| `{task.get('route')}` | `{task.get('operation')}` | `{task.get('id')}` | `{task.get('source_branch')}` | {reason} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--decisions")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--apply", action="store_true", help="Write plan/report. Default still writes --output for compatibility.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    decisions_path = Path(args.decisions).resolve() if args.decisions else plans / "rebuild_decision_report.json"
    output_path = Path(args.output).resolve() if args.output else plans / "dispatcher_rebuild_plan.json"
    report_path = Path(args.report).resolve() if args.report else plans / "reports" / f"DISPATCHER_REBUILD_PLAN_{utc_now()[:10]}.md"

    plan = build_plan(project_root, load_json(decisions_path, {"items": []}))
    write_json(output_path, plan)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(plan), encoding="utf-8")
    append_log(project_root, "dispatcher", "dispatcher_rebuild_planned", severity="info", tasks=plan["task_count"], counts=plan["counts"])

    if args.json:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
    else:
        print(f"planned_tasks: {plan['task_count']}")
        print(f"written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
