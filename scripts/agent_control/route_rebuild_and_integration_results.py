#!/usr/bin/env python3
"""Emit next-owner events from rebuild decision reports.

Dry-run by default. With --apply, events are appended to
AiStudio/Task_manager/agent_events.jsonl. Event emission is idempotent for the same
event/task/branch/decision tuple.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any
from uuid import uuid4

from _rebuild_common import load_json, utc_now, write_json
from process_log import append_log
from project_paths import task_manager_dir


def event_key(event: dict[str, Any]) -> tuple[str, str, str, str]:
    payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
    return (
        str(event.get("event") or ""),
        str(event.get("task_id") or ""),
        str(event.get("branch") or ""),
        str(payload.get("decision") or ""),
    )


def read_existing(path: Path) -> set[tuple[str, str, str, str]]:
    seen: set[tuple[str, str, str, str]] = set()
    if not path.exists():
        return seen
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict):
            seen.add(event_key(item))
    return seen


def primary_task_id(item: dict[str, Any]) -> str | None:
    task_ids = item.get("task_ids") or []
    if task_ids:
        return str(task_ids[0])
    source = item.get("source") if isinstance(item.get("source"), dict) else {}
    return source.get("task_id") or source.get("id")


def build_events(decisions: dict[str, Any], existing: set[tuple[str, str, str, str]]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for item in decisions.get("items") or []:
        if not isinstance(item, dict):
            continue
        event_name = str(item.get("next_event") or "")
        next_owner = str(item.get("next_owner") or "")
        reason = str(item.get("reason") or "")
        if not event_name or not next_owner or not reason:
            continue
        source = item.get("source") if isinstance(item.get("source"), dict) else {}
        event = {
            "schema_version": 1,
            "event_id": f"evt-{utc_now().replace(':', '').replace('-', '')}-{uuid4().hex[:8]}",
            "created_at": utc_now(),
            "project": None,
            "source": "route_rebuild_and_integration_results.py",
            "event": event_name,
            "role": "router",
            "next_owner": next_owner,
            "next_role": next_owner,
            "task_id": primary_task_id(item),
            "branch": item.get("branch") or source.get("branch") or source.get("source_branch"),
            "pr": item.get("pr") or source.get("pr"),
            "severity": "blocked" if item.get("decision") == "needs_human" else "info",
            "reason": reason,
            "consumed_by": None,
            "consumed_at": None,
            "payload": {
                "decision": item.get("decision"),
                "source_status": item.get("source_status"),
                "risk_class": item.get("risk_class"),
                "changed_paths": item.get("changed_paths") or [],
                "module_scopes": item.get("module_scopes") or [],
                "reason": reason,
            },
        }
        key = event_key(event)
        if key in existing:
            continue
        existing.add(key)
        events.append(event)
    return events


def append_events(path: Path, events: list[dict[str, Any]], project: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for event in events:
            event["project"] = project
            handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--decisions")
    parser.add_argument("--events")
    parser.add_argument("--output")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    decisions_path = Path(args.decisions).resolve() if args.decisions else plans / "rebuild_decision_report.json"
    events_path = Path(args.events).resolve() if args.events else plans / "agent_events.jsonl"
    output_path = Path(args.output).resolve() if args.output else plans / "route_rebuild_and_integration_results.json"

    decisions = load_json(decisions_path, {"items": []})
    events = build_events(decisions, read_existing(events_path))
    if args.apply:
        append_events(events_path, events, project_root.name)
        append_log(project_root, "router", "rebuild_routes_emitted", severity="info", events=len(events))

    report = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "decisions": str(decisions_path),
        "events_file": str(events_path),
        "apply": bool(args.apply),
        "event_count": len(events),
        "events": events,
    }
    write_json(output_path, report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"events: {len(events)}")
        print(f"written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

