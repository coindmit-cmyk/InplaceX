#!/usr/bin/env python3
"""Repair task identity only when audit evidence is strong."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file, task_reports_dir


HIGH_CONFIDENCE = 0.9


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def set_task_identity(task: dict[str, Any], task_id_value: str) -> None:
    task["id"] = task_id_value
    task["task_id"] = task_id_value
    task["canonical_task_id"] = task_id_value
    task["canonical_target_id"] = f"task:{task_id_value}"


def evidence_score(item: dict[str, Any]) -> tuple[float, list[str]]:
    inferred = [str(value) for value in item.get("inferred_task_ids") or [] if value]
    reports = [str(value) for value in item.get("report_paths") or [] if value]
    evidence: list[str] = []
    score = 0.0
    if len(inferred) == 1:
        score += 0.65
        evidence.append("single inferred task_id")
    if reports:
        score += 0.25
        evidence.append("worker report path")
    if item.get("branch"):
        score += 0.10
        evidence.append("branch evidence")
    return min(score, 1.0), evidence


def audit_by_queue_index(audit: dict[str, Any]) -> dict[int, dict[str, Any]]:
    result: dict[int, dict[str, Any]] = {}
    for item in audit.get("items") or []:
        if isinstance(item, dict) and item.get("kind") == "queue_task" and isinstance(item.get("index"), int):
            result[int(item["index"])] = item
    return result


def repair_queue(queue: dict[str, Any], audit: dict[str, Any], *, apply: bool) -> dict[str, Any]:
    by_index = audit_by_queue_index(audit)
    actions: list[dict[str, Any]] = []
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        item = by_index.get(index)
        if not item:
            continue
        status = str(item.get("status") or "")
        inferred = [str(value) for value in item.get("inferred_task_ids") or [] if value]
        current = task_id(task)
        if current:
            continue
        if status == "identity_recoverable" and len(inferred) == 1:
            confidence, evidence = evidence_score(item)
            if confidence >= HIGH_CONFIDENCE:
                action = {
                    "action": "attach_task_id",
                    "index": index,
                    "task_id": inferred[0],
                    "confidence": confidence,
                    "evidence": evidence,
                    "applied": bool(apply),
                }
                if apply:
                    set_task_identity(task, inferred[0])
                    task["identity_repair"] = {
                        "repaired_at": utc_now(),
                        "confidence": confidence,
                        "evidence": evidence,
                        "source": "task_identity_repair.py",
                    }
                actions.append(action)
                continue
        if status in {"identity_missing", "identity_recoverable", "identity_conflict", "duplicate_identity"}:
            action = {
                "action": "route_to_dispatcher",
                "index": index,
                "reason": "task identity is missing, ambiguous or low-confidence",
                "status": status,
                "confidence": evidence_score(item)[0],
                "applied": bool(apply),
            }
            if apply:
                task["status"] = "needs_dispatcher"
                task["dispatcher_decision"] = "needs_identity_recovery"
                task["worker_ready"] = False
                task["normalization_status"] = "needs_identity_recovery"
                task["identity_repair_reason"] = action["reason"]
            actions.append(action)
    return {"actions": actions, "action_count": len(actions)}


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Task Identity Repair",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Apply: `{str(report.get('apply')).lower()}`",
        f"- Actions: `{report.get('action_count')}`",
        "",
        "| Action | Index | Task | Applied | Reason |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in report.get("actions") or []:
        lines.append(
            f"| `{item.get('action')}` | `{item.get('index')}` | `{item.get('task_id') or '-'}` | "
            f"`{str(item.get('applied')).lower()}` | {str(item.get('reason') or ', '.join(item.get('evidence') or []) or '-').replace('|', '/')} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Repair task identity when audit evidence is strong.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue")
    parser.add_argument("--audit")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    audit_path = Path(args.audit).resolve() if args.audit else task_file(project_root, "task_identity_audit.json")
    output = Path(args.output).resolve() if args.output else task_file(project_root, "task_identity_repair.json")
    report_path = Path(args.report).resolve() if args.report else task_reports_dir(project_root) / f"TASK_IDENTITY_REPAIR_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    queue = load_json(queue_path)
    audit = load_json(audit_path)
    if not isinstance(queue, dict):
        raise SystemExit(f"invalid queue JSON: {queue_path}")
    if not isinstance(audit, dict):
        raise SystemExit(f"invalid audit JSON: {audit_path}")

    result = repair_queue(queue, audit, apply=args.apply)
    if args.apply:
        write_json(queue_path, queue)
    summary = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "queue": str(queue_path),
        "audit": str(audit_path),
        "apply": bool(args.apply),
        **result,
    }
    write_json(output, summary)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(summary), encoding="utf-8")
    append_log(project_root, "identity", "task_identity_repair", severity="info", apply=args.apply, actions=summary["action_count"])

    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print(f"actions: {summary['action_count']}")
        print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
