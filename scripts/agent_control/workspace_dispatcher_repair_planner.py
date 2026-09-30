#!/usr/bin/env python3
"""Build a dry-run worklist for tasks marked needs_dispatcher_repair."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


WORKER_PACKET_V2_FIELDS = {
    "worker_instructions",
    "traceability",
    "context_inventory",
    "doc_refs",
    "input_refs",
    "output_contract",
    "script_actions",
    "existing_behavior",
    "preserve_contract",
    "regression_guards",
    "code_refs",
    "integration_notes",
}

BASE_CONTEXT_FIELDS = {
    "recommended_agent_or_eligible_worker_profiles",
    "context_docs_or_source_provenance",
    "current_context_verification",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def task_id(task: dict[str, Any], index: int) -> str:
    return str(task.get("id") or task.get("task_id") or f"index-{index}")


def as_string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value if str(item).strip()]


def classify_actions(missing_fields: list[str]) -> list[str]:
    actions: list[str] = []
    missing = set(missing_fields)
    if "recommended_agent_or_eligible_worker_profiles" in missing:
        actions.append("assign_worker_profile")
    if "context_docs_or_source_provenance" in missing:
        actions.append("add_source_context_refs")
    if "current_context_verification" in missing:
        actions.append("verify_current_context")
    if missing & WORKER_PACKET_V2_FIELDS:
        actions.append("complete_worker_packet_v2")
    if not actions:
        actions.append("inspect_dispatcher_repair_marker")
    return actions


def build_item(task: dict[str, Any], index: int) -> dict[str, Any]:
    missing_fields = as_string_list(task.get("missing_packet_fields"))
    base_missing = [field for field in missing_fields if field in BASE_CONTEXT_FIELDS]
    v2_missing = [field for field in missing_fields if field in WORKER_PACKET_V2_FIELDS]
    return {
        "task_id": task_id(task, index),
        "title": task.get("title"),
        "index": index,
        "status": task.get("status"),
        "dispatcher_decision": task.get("dispatcher_decision"),
        "repair_owner": task.get("repair_owner") or "dispatcher",
        "repair_request": task.get("repair_request"),
        "next_action": task.get("next_action"),
        "missing_packet_fields": missing_fields,
        "base_context_missing": base_missing,
        "worker_packet_v2_missing": v2_missing,
        "recommended_actions": classify_actions(missing_fields),
        "source_hints": {
            "allowed_paths": task.get("allowed_paths") or [],
            "context_docs": task.get("context_docs") or [],
            "source_file": task.get("source_file"),
            "provenance": task.get("provenance") or {},
            "route_kind": task.get("route_kind"),
        },
    }


def build_plan_from_queue(queue: dict[str, Any], queue_ref: str) -> dict[str, Any]:
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    items: list[dict[str, Any]] = []
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        if task.get("status") == "needs_dispatcher_repair" or task.get("dispatcher_decision") == "needs_dispatcher_repair":
            items.append(build_item(task, index))

    action_counts: dict[str, int] = {}
    for item in items:
        for action in item["recommended_actions"]:
            action_counts[action] = action_counts.get(action, 0) + 1

    return {
        "schema_version": "1.0",
        "mode": "workspace_dispatcher_repair_planner",
        "generated_at": utc_now(),
        "queue": queue_ref,
        "mutates_queue": False,
        "repair_count": len(items),
        "action_counts": action_counts,
        "items": items,
    }


def build_plan(queue_path: Path) -> dict[str, Any]:
    return build_plan_from_queue(load_json(queue_path), str(queue_path))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    plan = build_plan(args.queue.expanduser())
    if args.output:
        write_json_atomic(args.output.expanduser(), plan)
    if args.json:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
    else:
        print(f"repair_count={plan['repair_count']}")
        for action, count in sorted(plan["action_counts"].items()):
            print(f"{action}={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
