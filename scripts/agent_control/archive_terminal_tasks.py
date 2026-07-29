#!/usr/bin/env python3
"""Move terminal AiStudio tasks from the active queue into task history."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


TERMINAL_STATUSES = {"done", "stale_or_superseded"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path, default: dict[str, Any] | None = None) -> dict[str, Any]:
    if not path.exists():
        return dict(default or {})
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def history_tasks(history: dict[str, Any]) -> list[dict[str, Any]]:
    tasks = history.setdefault("tasks", [])
    if not isinstance(tasks, list):
        raise SystemExit("task history field 'tasks' must be an array")
    return tasks


def archive(queue: dict[str, Any], history: dict[str, Any], *, archived_by: str) -> dict[str, Any]:
    now = utc_now()
    active: list[dict[str, Any]] = []
    archived: list[dict[str, Any]] = []
    skipped_existing_count = 0
    existing_ids = {task_id(item) for item in history_tasks(history)}

    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            active.append(task)
            continue
        status = str(task.get("status") or "")
        tid = task_id(task)
        if status not in TERMINAL_STATUSES:
            active.append(task)
            continue
        archived_task = dict(task)
        archived_task.setdefault("final_status", status)
        archived_task.setdefault("archived_at", now)
        archived_task.setdefault("archived_by", archived_by)
        if tid in existing_ids:
            skipped_existing_count += 1
            continue
        archived.append(archived_task)
        existing_ids.add(tid)

    history.setdefault("schema_version", 1)
    history["updated_at"] = now
    history_tasks(history).extend(archived)
    queue["tasks"] = active
    queue["updated_at"] = now
    return {
        "archived_count": len(archived),
        "active_count": len(active),
        "history_count": len(history_tasks(history)),
        "skipped_existing_count": skipped_existing_count,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", default="AiStudio/Task_manager/task_queue.json")
    parser.add_argument("--history", default="AiStudio/Task_manager/task_history.json")
    parser.add_argument("--archived-by", default="archive_terminal_tasks")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    queue_path = Path(args.queue)
    history_path = Path(args.history)
    queue = load_json(queue_path)
    history = load_json(history_path, {"schema_version": 1, "tasks": []})
    result = archive(queue, history, archived_by=args.archived_by)
    result.update({"queue": str(queue_path), "history": str(history_path), "applied": args.apply})
    if args.apply:
        write_json(queue_path, queue)
        write_json(history_path, history)
    print(json.dumps(result, indent=2, ensure_ascii=False) if args.json else result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
