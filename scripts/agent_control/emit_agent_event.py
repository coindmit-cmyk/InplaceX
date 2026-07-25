#!/usr/bin/env python3
"""Append one event to AiStudio/Task_manager/agent_events.jsonl."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from project_paths import task_file


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_payload(value: str | None) -> dict[str, Any]:
    if not value:
        return {}
    data = json.loads(value)
    if not isinstance(data, dict):
        raise SystemExit("--payload-json must be a JSON object")
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description="Append an agent automation event.")
    parser.add_argument("--project-root", default=".", help="Project root. Defaults to current directory.")
    parser.add_argument("--events", help="Event JSONL path. Defaults to AiStudio/Task_manager/agent_events.jsonl.")
    parser.add_argument("--event", required=True, help="Event name, for example integration_requested.")
    parser.add_argument("--role", required=True, help="Producer role.")
    parser.add_argument("--next-role", help="Role expected to consume the event.")
    parser.add_argument("--task-id")
    parser.add_argument("--pr", type=int)
    parser.add_argument("--branch")
    parser.add_argument("--severity", default="info", choices=("info", "warning", "blocked", "critical"))
    parser.add_argument("--payload-json", help="Optional JSON object payload.")
    parser.add_argument("--json", action="store_true", help="Print appended event as JSON.")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    events_path = Path(args.events).resolve() if args.events else task_file(project_root, "agent_events.jsonl")
    events_path.parent.mkdir(parents=True, exist_ok=True)

    event = {
        "event_id": f"evt-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}-{uuid4().hex[:8]}",
        "created_at": utc_now(),
        "project": project_root.name,
        "event": args.event,
        "role": args.role,
        "task_id": args.task_id,
        "pr": args.pr,
        "branch": args.branch,
        "severity": args.severity,
        "next_role": args.next_role,
        "consumed_by": None,
        "consumed_at": None,
        "payload": parse_payload(args.payload_json),
    }
    with events_path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")

    if args.json:
        print(json.dumps(event, ensure_ascii=False, indent=2))
    else:
        print(f"appended {event['event_id']} {event['event']} -> {event.get('next_role') or '-'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
