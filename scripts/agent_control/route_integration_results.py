#!/usr/bin/env python3
"""Emit downstream routing events from an Integrator handoff."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file


ROUTES = {
    "needs_dispatcher": ("dispatcher_requested", "auto-dispatcher"),
    "needs_worker_fix": ("worker_fix_requested", "worker"),
    "needs_rework": ("integration_rework_requested", "auto-integrator"),
    "needs_human": ("needs_human_created", "human"),
    "cleanup_candidates": ("cleanup_requested", "cleanup_script"),
    "ready_to_finalize": ("finalization_requested", "auto-finalizer"),
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_events(path: Path) -> set[tuple[str, str, str]]:
    seen: set[tuple[str, str, str]] = set()
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
            event_name = str(item.get("event") or "")
            task_id = str(item.get("task_id") or "")
            branch = str(item.get("branch") or "")
            seen.add((event_name, task_id, branch))
            if event_name in {"integration_invalidated", "finalization_invalidated"}:
                for key in event_target_keys(item):
                    seen.discard(("finalization_recorded", key, ""))
            elif event_name == "finalization_recorded":
                for key in event_target_keys(item):
                    seen.add(("finalization_recorded", key, ""))
    return seen


def event_target_keys(event: dict[str, Any]) -> set[str]:
    keys: set[str] = set()
    for field in ("task_id", "canonical_target_id"):
        value = str(event.get(field) or "").strip()
        if not value:
            continue
        keys.add(value)
        keys.add(value.removeprefix("task:") if value.startswith("task:") else f"task:{value}")
    return keys


def disposition_index(handoff: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in handoff.get("branch_dispositions") or []:
        if not isinstance(item, dict):
            continue
        for key in (item.get("task_id"), item.get("branch"), item.get("pr")):
            if key:
                result[str(key)] = item
    return result


def item_identity(item: Any) -> dict[str, Any]:
    if isinstance(item, dict):
        return {
            "task_id": item.get("task_id") or item.get("id"),
            "canonical_target_id": item.get("canonical_target_id"),
            "branch": item.get("branch"),
            "pr": item.get("pr"),
            "reason": item.get("reason"),
            "evidence": item.get("evidence"),
        }
    return {"task_id": item if item else None, "canonical_target_id": f"task:{item}" if item else None}


def build_events(handoff: dict[str, Any], existing: set[tuple[str, str, str]]) -> list[dict[str, Any]]:
    dispositions = disposition_index(handoff)
    events: list[dict[str, Any]] = []
    for field, (event_name, next_owner) in ROUTES.items():
        for raw in handoff.get(field) or []:
            identity = item_identity(raw)
            lookup = dispositions.get(str(identity.get("task_id") or "")) or dispositions.get(str(identity.get("branch") or "")) or {}
            task_id = identity.get("task_id") or lookup.get("task_id")
            branch = identity.get("branch") or lookup.get("branch")
            reason = identity.get("reason") or lookup.get("reason") or field
            key = (event_name, str(task_id or ""), str(branch or ""))
            if key in existing:
                continue
            if field == "ready_to_finalize":
                target_keys = event_target_keys({
                    "task_id": task_id,
                    "canonical_target_id": identity.get("canonical_target_id") or (f"task:{task_id}" if task_id else None),
                })
                if any(("finalization_recorded", target, "") in existing for target in target_keys):
                    continue
            event = {
                "schema_version": 1,
                "event_id": f"{event_name}-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S%f')}",
                "created_at": utc_now(),
                "source": "route_integration_results.py",
                "event": event_name,
                "next_owner": next_owner,
                "task_id": task_id,
                "canonical_target_id": identity.get("canonical_target_id") or (f"task:{task_id}" if task_id else None),
                "branch": branch,
                "pr": identity.get("pr") or lookup.get("pr"),
                "reason": reason,
                "evidence": identity.get("evidence") or lookup.get("evidence") or [],
                "route_field": field,
            }
            events.append(event)
            existing.add(key)
    return events


def append_events(path: Path, events: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for event in events:
            handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Emit routing events from integration_handoff.json.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--handoff")
    parser.add_argument("--events")
    parser.add_argument("--output")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    handoff_path = Path(args.handoff).resolve() if args.handoff else task_file(project_root, "integration_handoff.json")
    events_path = Path(args.events).resolve() if args.events else task_file(project_root, "agent_events.jsonl")
    output = Path(args.output).resolve() if args.output else (task_file(project_root, "route_integration_results.json") if args.apply else None)
    handoff = load_json(handoff_path)
    existing = load_events(events_path)
    events = build_events(handoff, existing)
    if args.apply:
        append_events(events_path, events)
        append_log(project_root, "integrator", "integration_results_routed", severity="info", events=len(events))
    report = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "handoff": str(handoff_path),
        "events_file": str(events_path),
        "apply": bool(args.apply),
        "event_count": len(events),
        "events": events,
    }
    if output:
        write_json(output, report)

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"events: {len(events)}")
        print(f"written: {output or '-'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
