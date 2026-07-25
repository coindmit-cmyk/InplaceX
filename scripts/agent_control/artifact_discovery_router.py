#!/usr/bin/env python3
"""Route Artifact Discovery findings to owners and task candidates.

Router defaults to dry-run. With --apply it may append Dispatcher-owned task
candidates to AiStudio/Task_manager/task_queue.json when that file exists.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

ROUTE_MAP = {
    "unmapped_artifact": ("Dispatcher", "reality_map_backfill", "create ProjectMapPlanner backfill task"),
    "missing_project_map_coverage": ("Dispatcher", "reality_map_backfill", "create ProjectMapPlanner backfill task"),
    "missing_index_link": ("Integrator", "integration_repair", "add index/discovery link or exception"),
    "missing_script_catalog_entry": ("Integrator", "automation_surface_integration", "add script catalog entry or exception"),
    "missing_validator_template_pair": ("Integrator", "schema_template_integration", "add template/example or exception"),
    "missing_integration_surface": ("Integrator", "integration_repair", "repair missing integration surface"),
    "missing_ux_contract_or_waiver": ("UX Design", "ux_contract_or_waiver", "create UX contract or waiver"),
    "legacy_state_reference": ("Doctor", "policy_drift_review", "diagnose legacy state reference"),
    "cleanup_candidate": ("Integrator", "cleanup_candidate_review", "review cleanup candidate"),
    "possible_secret_pattern": ("Human", "security_review", "review sensitive-risk finding"),
    "lost_task_candidate": ("Dispatcher", "task_import_or_triage", "triage lost task candidate"),
    "lost_documentation": ("ProjectMapPlanner", "documentation_map_backfill", "map or archive lost documentation"),
    "policy_drift": ("Doctor", "policy_drift_review", "review policy drift"),
}


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def safe_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-")[:80] or "artifact"


def task_candidate(finding: dict[str, Any], owner: str, task_type: str, action: str) -> dict[str, Any]:
    path = str(finding.get("path") or "artifact")
    prefix = "MAP-BACKFILL" if task_type == "reality_map_backfill" else "DISCOVERY"
    return {
        "id": f"{prefix}-{safe_id(path)}",
        "type": task_type,
        "status": "planned",
        "title": f"{action}: {path}",
        "created_by": "Artifact Discovery Layer",
        "created_at": utc_now(),
        "source_finding_id": finding.get("id"),
        "entity_path": path,
        "artifact_type": finding.get("artifact_type"),
        "semantic_kind": finding.get("semantic_kind"),
        "implementation_status": finding.get("implementation_status"),
        "implementation_evidence": finding.get("implementation_evidence") or [],
        "integration_status": finding.get("integration_status"),
        "integration_gaps": finding.get("integration_gaps") or [],
        "integration_evidence": finding.get("integration_evidence") or [],
        "reason": finding.get("category"),
        "blocking_current_work": bool(finding.get("severity") in {"blocking", "critical"}),
        "next_owner": owner,
        "acceptance_criteria": [
            "Finding has a recorded disposition.",
            "Relevant map/index/surface/task/cleanup route is updated or explicitly deferred."
        ],
    }


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def load_task_rows(project_root: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for rel in ("AiStudio/Task_manager/task_queue.json", "AiStudio/Task_manager/task_history.json"):
        path = project_root / rel
        if not path.is_file():
            continue
        try:
            data = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError):
            continue
        for item in data.get("tasks") or []:
            if isinstance(item, dict):
                task = dict(item)
                task["_task_state_source"] = rel
                rows.append(task)
    return rows


def task_terminal(task: dict[str, Any]) -> bool:
    return str(task.get("status") or "") in {"done", "stale_or_superseded"} or str(task.get("final_status") or "") in {"done", "stale_or_superseded"}


def finding_resolution(finding: dict[str, Any], task_rows: list[dict[str, Any]]) -> dict[str, Any]:
    finding_id = str(finding.get("id") or "")
    category = str(finding.get("category") or "")
    exact = [task for task in task_rows if str(task.get("source_finding_id") or "") == finding_id]
    if exact:
        terminal = [task for task in exact if task_terminal(task)]
        source_tasks = terminal or exact
        return {
            "status": "done" if terminal else "task_exists",
            "reason": "source_finding_id matched Task Manager row",
            "task_refs": [
                {"id": task_id(task), "status": task.get("status"), "source": task.get("_task_state_source")}
                for task in source_tasks[:5]
            ],
        }
    category_rows = [task for task in task_rows if str(task.get("source_finding_category") or "") == category]
    if category_rows:
        active = [task for task in category_rows if not task_terminal(task)]
        source_tasks = active or category_rows
        return {
            "status": "active_group_pending" if active else "done_group",
            "reason": "source_finding_category matched normalized ADL group",
            "task_refs": [
                {"id": task_id(task), "status": task.get("status"), "source": task.get("_task_state_source")}
                for task in source_tasks[:5]
            ],
        }
    if finding.get("auto_task_allowed") is False:
        return {"status": "needs_human_review", "reason": "auto_task_allowed=false", "task_refs": []}
    return {"status": "uncovered", "reason": "no matching task_queue/task_history row", "task_refs": []}


def count_by(items: list[dict[str, Any]], key: str) -> dict[str, int]:
    return dict(sorted(Counter(str(item.get(key) or "") for item in items).items()))


def route_finding(finding: dict[str, Any]) -> dict[str, Any]:
    owner, task_type, action = ROUTE_MAP.get(str(finding.get("category") or ""), (str(finding.get("suggested_owner") or "Dispatcher"), str(finding.get("suggested_task_type") or "artifact_triage"), str(finding.get("suggested_action") or "triage artifact finding")))
    blocking = str(finding.get("severity") or "") in {"blocking", "critical"}
    route = {
        "source_finding_id": finding.get("id"),
        "owner": owner,
        "action": action,
        "task_type": task_type,
        "blocking": blocking,
        "reason": finding.get("category"),
    }
    if finding.get("auto_task_allowed", True):
        route["task_candidate"] = task_candidate(finding, owner, task_type, action)
    return route


def route_report(report: dict[str, Any], *, project_root: Path | None = None) -> dict[str, Any]:
    findings = [item for item in report.get("findings") or [] if isinstance(item, dict)]
    task_rows = load_task_rows(project_root) if project_root else []
    enriched: list[dict[str, Any]] = []
    for item in findings:
        finding = dict(item)
        resolution = finding_resolution(finding, task_rows)
        finding["resolution_status"] = resolution["status"]
        finding["resolution_reason"] = resolution["reason"]
        finding["resolution_task_refs"] = resolution["task_refs"]
        enriched.append(finding)
    findings = enriched
    routes = [route_finding(item) for item in findings]
    out = dict(report)
    out["findings"] = findings
    out["routes"] = routes
    out["task_candidates"] = [route["task_candidate"] for route in routes if isinstance(route.get("task_candidate"), dict)]
    summary = out.setdefault("summary", {})
    summary["finding_count"] = len(findings)
    summary["task_candidate_count"] = len(out["task_candidates"])
    summary["by_category"] = count_by(findings, "category")
    summary["by_severity"] = count_by(findings, "severity")
    summary["by_owner"] = dict(sorted(Counter(str(route.get("owner") or "") for route in routes).items()))
    summary["by_disposition"] = count_by(findings, "artifact_disposition")
    summary["by_semantic_kind"] = count_by(findings, "semantic_kind")
    summary["by_implementation_status"] = count_by(findings, "implementation_status")
    summary["by_integration_status"] = count_by(findings, "integration_status")
    summary["resolution_counts"] = count_by(findings, "resolution_status")
    out.setdefault("checks", []).append({"name": "artifact_discovery_routing", "result": "completed"})
    return out


def append_task_candidates(project_root: Path, candidates: list[dict[str, Any]]) -> dict[str, Any]:
    path = project_root / "AiStudio" / "Task_manager" / "task_queue.json"
    if not path.is_file():
        return {"ok": False, "reason": "task_queue_missing", "path": str(path)}
    data = load_json(path)
    tasks = data.setdefault("tasks", [])
    if not isinstance(tasks, list):
        return {"ok": False, "reason": "tasks_not_list", "path": str(path)}
    existing = {str(item.get("id")) for item in tasks if isinstance(item, dict)}
    added = 0
    for candidate in candidates:
        task_id = str(candidate.get("id") or "")
        if not task_id or task_id in existing:
            continue
        tasks.append(candidate)
        existing.add(task_id)
        added += 1
    data["updated_at"] = utc_now()
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"ok": True, "added": added, "path": str(path)}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    routed = route_report(load_json(Path(args.input)), project_root=Path(args.project_root).resolve())
    if args.apply:
        routed["apply_result"] = append_task_candidates(Path(args.project_root).resolve(), routed.get("task_candidates") or [])
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(routed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.json or not args.output:
        print(json.dumps(routed, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
