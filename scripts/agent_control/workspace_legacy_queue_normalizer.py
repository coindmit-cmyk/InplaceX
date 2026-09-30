#!/usr/bin/env python3
"""Dry-run normalizer for legacy task_queue values before migration."""

from __future__ import annotations

import argparse
import copy
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import validate_task_queue_readiness


DECISION_ALIASES = {
    "completed_after_make_human_smoke": "done",
    "clean_rebuild_applied": "done",
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


def validation_issues(queue: dict[str, Any]) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    for index, task in enumerate(tasks):
        if isinstance(task, dict):
            validate_task_queue_readiness.validate_task(task, index, issues)
    return issues


def normalize_task(task: dict[str, Any], now: str) -> tuple[dict[str, Any], list[dict[str, str]]]:
    updated = dict(task)
    changes: list[dict[str, str]] = []
    decision = str(updated.get("dispatcher_decision") or "")
    if decision in DECISION_ALIASES:
        mapped = DECISION_ALIASES[decision]
        updated["legacy_dispatcher_decision"] = decision
        updated["dispatcher_decision"] = mapped
        updated.setdefault("dispatcher_decision_reason", f"legacy dispatcher_decision {decision} normalized to {mapped}")
        if mapped == "done":
            updated["status"] = "done"
            updated["worker_ready"] = False
        changes.append({"field": "dispatcher_decision", "from": decision, "to": mapped})
    if str(updated.get("dispatcher_decision") or "") in {"needs_task_packet", "needs_dispatcher_repair"} and not updated.get("dispatcher_next_review_at"):
        updated["dispatcher_next_review_at"] = now
        changes.append({"field": "dispatcher_next_review_at", "from": "", "to": now})
    return updated, changes


def build_report(queue_path: Path, *, output_path: Path | None = None) -> dict[str, Any]:
    queue = load_json(queue_path)
    before = validation_issues(queue)
    now = utc_now()
    staged = copy.deepcopy(queue)
    tasks = staged.get("tasks") if isinstance(staged.get("tasks"), list) else []
    changes: list[dict[str, Any]] = []
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        updated, task_changes = normalize_task(task, now)
        if task_changes:
            tasks[index] = updated
            changes.append({"task_id": task.get("id"), "index": index, "changes": task_changes})
    staged["legacy_queue_normalization"] = {
        "generated_at": now,
        "source": str(queue_path),
        "change_count": len(changes),
    }
    after = validation_issues(staged)
    if output_path:
        write_json_atomic(output_path, staged)
    return {
        "schema_version": "1.0",
        "mode": "workspace_legacy_queue_normalizer",
        "queue": str(queue_path),
        "output": str(output_path) if output_path else None,
        "change_count": len(changes),
        "changes": changes,
        "before": {
            "errors": sum(1 for item in before if item["severity"] == "error"),
            "warnings": sum(1 for item in before if item["severity"] == "warning"),
        },
        "after": {
            "errors": sum(1 for item in after if item["severity"] == "error"),
            "warnings": sum(1 for item in after if item["severity"] == "warning"),
            "issues_sample": after[:20],
        },
        "mutates_input_queue": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = build_report(args.queue.expanduser(), output_path=args.output.expanduser() if args.output else None)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"changes={report['change_count']} errors {report['before']['errors']}->{report['after']['errors']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
