#!/usr/bin/env python3
"""Rebuild a missing central task_queue.json from deterministic legacy sources."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_file, task_manager_dir, task_reports_dir

from process_log import append_log


DEFAULT_SOURCES = (
    ".agent/mvp_tasks.json",
    ".agent/next_tasks.json",
)
DONE_STATUSES = {"done", "merged", "closed"}
ACTIVE_STATUSES = {"planned", "partial", "watch", "blocked"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def unique(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value and value not in seen:
            result.append(value)
            seen.add(value)
    return result


def looks_like_path(value: str) -> bool:
    return "/" in value or "\\" in value or value.endswith((".md", ".py", ".json", ".ps1", ".toml", ".txt", ".yml", ".yaml"))


def source_items(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, dict) and isinstance(data.get("tasks"), list):
        return [item for item in data["tasks"] if isinstance(item, dict)]
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    return []


def normalize_status(status: str) -> str:
    value = status.lower().strip()
    if value in DONE_STATUSES:
        return "done"
    if value == "blocked":
        return "needs_human"
    if value in {"partial", *ACTIVE_STATUSES} or not value:
        return "needs_task_packet"
    return "needs_task_packet"


def task_type(item: dict[str, Any]) -> str:
    modules = " ".join(str(value).lower() for value in as_list(item.get("modules")))
    title = str(item.get("title") or "").lower()
    if "docs" in modules or "runbook" in title or "checklist" in title:
        return "docs"
    if "test" in title or "smoke" in title or "verify" in title:
        return "tests"
    return "implementation"


def convert_item(item: dict[str, Any], source_file: str, captured_at: str) -> dict[str, Any] | None:
    task_id = str(item.get("id") or item.get("task_id") or "").strip()
    if not task_id:
        return None
    title = str(item.get("title") or task_id).strip()
    status = normalize_status(str(item.get("status") or "planned"))
    inputs = [str(value) for value in as_list(item.get("inputs")) if isinstance(value, str)]
    outputs = [str(value) for value in as_list(item.get("outputs")) if isinstance(value, str)]
    path_hints = unique([value for value in inputs + outputs if looks_like_path(value)])
    path_hints = unique(path_hints + ["docs/plans/**", "docs/reports/**", "CHANGELOG.md"])
    acceptance = [str(value) for value in as_list(item.get("acceptance") or item.get("acceptance_criteria")) if value]
    task = {
        "id": task_id,
        "task_id": task_id,
        "canonical_task_id": task_id,
        "canonical_target_id": f"task:{task_id}",
        "title": title,
        "status": status,
        "worker_ready": False,
        "packet_status": "needs_task_packet" if status == "needs_task_packet" else status,
        "dispatcher_decision": "needs_task_packet" if status == "needs_task_packet" else status,
        "not_worker_ready_reason": "legacy backlog import requires current code, docs and task queue review before worker execution" if status != "done" else f"terminal status={status}",
        "requires_current_context_review": status != "done",
        "current_context_review_reason": "legacy JSON source is inventory only; Dispatcher must inspect current target code, documentation and task state before worker execution" if status != "done" else None,
        "dispatcher_next_review_at": captured_at if status != "done" else None,
        "priority": str(item.get("priority") or "P1"),
        "type": task_type(item),
        "base_branch": "develop",
        "source_file": source_file,
        "context_docs": unique([source_file, *inputs]),
        "allowed_paths": path_hints,
        "acceptance_criteria": acceptance,
        "provenance": [
            {
                "source_type": "legacy_backlog",
                "source_file": source_file,
                "source_item_id": task_id,
                "captured_at": captured_at,
                "summary": title,
            }
        ],
        "normalization_status": "inventory_only" if status == "done" else "needs_task_packet",
    }
    if item.get("modules"):
        task["area"] = ", ".join(str(value) for value in as_list(item.get("modules")))
    if status == "done":
        task["dispatcher_decision"] = "done"
        task.pop("current_context_review_reason", None)
        task.pop("dispatcher_next_review_at", None)
    return task


def discover_sources(project_root: Path, extra_sources: list[str]) -> list[str]:
    sources = list(DEFAULT_SOURCES)
    sources.extend(extra_sources)
    plans_dir = task_manager_dir(project_root)
    for path in sorted(plans_dir.glob("*.import.json")) if plans_dir.exists() else []:
        sources.append(path.relative_to(project_root).as_posix())
    return unique(sources)


def build_queue(project_root: Path, sources: list[str]) -> dict[str, Any]:
    captured_at = utc_now()
    tasks: list[dict[str, Any]] = []
    seen: set[str] = set()
    source_reports: list[dict[str, Any]] = []
    for source in sources:
        path = project_root / source
        data = load_json(path)
        items = source_items(data)
        if not items:
            continue
        imported = 0
        for item in items:
            task = convert_item(item, source, captured_at)
            if not task:
                continue
            task_id = str(task["id"])
            if task_id in seen:
                continue
            seen.add(task_id)
            tasks.append(task)
            imported += 1
        source_reports.append({"source_file": source, "item_count": len(items), "imported_count": imported})
    return {
        "schema_version": 1,
        "updated_at": captured_at,
        "source": "queue_rebuild_from_sources.py",
        "rebuild_policy": "inventory from deterministic legacy JSON sources; normalize/promote before worker launch",
        "sources": source_reports,
        "tasks": tasks,
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Queue Rebuild From Sources",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Apply: `{str(report.get('apply')).lower()}`",
        f"- Tasks: `{report.get('task_count')}`",
        f"- Queue: `{report.get('queue')}`",
        "",
        "| Source | Items | Imported |",
        "| --- | ---: | ---: |",
    ]
    for item in report.get("sources") or []:
        lines.append(f"| `{item.get('source_file')}` | {item.get('item_count')} | {item.get('imported_count')} |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Rebuild missing task_queue.json from legacy task sources.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue")
    parser.add_argument("--locks")
    parser.add_argument("--source", action="append", default=[])
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--replace", action="store_true", help="Allow replacing an existing queue file.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")
    output = Path(args.output).resolve() if args.output else task_file(project_root, "task_queue.rebuilt.json")
    report_path = Path(args.report).resolve() if args.report else task_reports_dir(project_root) / f"QUEUE_REBUILD_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    if queue_path.exists() and args.apply and not args.replace:
        raise SystemExit(f"queue already exists, refusing to replace without --replace: {queue_path}")
    queue = build_queue(project_root, discover_sources(project_root, args.source))
    write_json(output, queue)
    if args.apply:
        write_json(queue_path, queue)
        if not locks_path.exists():
            write_json(locks_path, {"schema_version": 1, "updated_at": utc_now(), "locks": []})
    summary = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "queue": str(queue_path),
        "output": str(output),
        "locks": str(locks_path),
        "apply": bool(args.apply),
        "task_count": len(queue.get("tasks") or []),
        "sources": queue.get("sources") or [],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(summary), encoding="utf-8")
    append_log(project_root, "dispatcher", "queue_rebuild_from_sources", severity="info", apply=args.apply, task_count=summary["task_count"])

    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print(f"tasks: {summary['task_count']}")
        print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
