#!/usr/bin/env python3
"""Build task seeds from workspace integration route plans."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import workspace_integration_route_plan


FORBIDDEN_PATHS = [".env", ".env.*", "secrets/**", "production config", "customer exports", "runtime secrets"]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def task_id(route: dict[str, Any]) -> str:
    return str(route.get("id") or "AISTD2-P12-ROUTE-TASK")


def owner_status(route: dict[str, Any]) -> str:
    owner = str(route.get("owner") or "")
    blockers = route.get("blockers") if isinstance(route.get("blockers"), list) else []
    if "owner_secret_config_decision_required" in blockers:
        return "needs_human"
    if "unknown_paths_require_manual_classification" in blockers:
        return "needs_human"
    if owner == "dispatcher":
        return "needs_task_packet"
    if owner == "architect":
        return "needs_architect"
    if owner == "integrator":
        return "needs_human"
    return "planned"


def title(route: dict[str, Any]) -> str:
    category = str(route.get("category") or "changes").replace("_", " ")
    owner = str(route.get("owner") or "owner").title()
    return f"{owner}: route preserved {category} changes"


def task_for_route(route: dict[str, Any], created_at: str) -> dict[str, Any]:
    tid = task_id(route)
    blockers = [str(item) for item in route.get("blockers") or []]
    task = {
        "id": tid,
        "canonical_task_id": tid,
        "project_id": route.get("project_id"),
        "title": title(route),
        "type": "workspace_integration_route",
        "status": owner_status(route),
        "owner": route.get("owner"),
        "priority": "P0" if blockers else "P1",
        "complexity": "L" if int(route.get("full_category_path_count") or 0) > 100 else "M",
        "worker_ready": False,
        "packet_status": "needs_task_packet",
        "normalization_status": "needs_task_packet",
        "dispatcher_decision": "needs_task_packet",
        "requires_current_context_review": True,
        "current_context_review_reason": "Route tasks must use current preservation evidence and change classification before any apply.",
        "source_route_id": route.get("id"),
        "category": route.get("category"),
        "action": route.get("action"),
        "full_category_path_count": route.get("full_category_path_count"),
        "paths_sample": route.get("paths_sample") or [],
        "allowed_paths": route.get("allowed_paths") or [],
        "forbidden_paths": FORBIDDEN_PATHS,
        "checks": route.get("checks") or [],
        "blockers": blockers,
        "migration_sensitive": bool(route.get("migration_sensitive")),
        "preservation_captured": bool(route.get("preservation_captured")),
        "acceptance_criteria": [
            "Use preservation evidence before modifying or moving project state.",
            "Do not overwrite existing project code, docs or task state without integrating current contents.",
            "If migration files are involved, adapt migrations to current target state and run migration checks.",
            "Secret/config paths require owner decision and must not be copied into shared task packets.",
        ],
        "created_at": created_at,
        "dispatcher_next_review_at": created_at,
        "created_by": "scripts/agent_control/workspace_route_task_builder.py",
    }
    if route.get("owner") == "architect":
        task["architect_request"] = (
            f"Review preserved {route.get('category')} route with {route.get('full_category_path_count')} paths "
            "and decide integration scope before worker packetization."
        )
    if route.get("owner") == "dispatcher":
        task["repair_request"] = "Convert preserved task/agent state route into concrete dispatcher-safe packets."
        task["missing_packet_fields"] = ["worker packet v2 fields"]
        task["repair_owner"] = "dispatcher"
        task["next_action"] = "Review route seed and split into worker-ready packets only after current context verification."
    return task


def build_report(registry_path: Path, *, project_id: str | None = None, devops_root: Path | None = None) -> dict[str, Any]:
    created_at = utc_now()
    route_plan = workspace_integration_route_plan.build_report(registry_path, project_id=project_id, devops_root=devops_root)
    tasks = [
        task_for_route(route, created_at)
        for project in route_plan.get("projects") or []
        if isinstance(project, dict)
        for route in project.get("routes") or []
        if isinstance(route, dict)
    ]
    return {
        "schema_version": "1.0",
        "mode": "workspace_route_task_seeds",
        "registry": str(registry_path),
        "devops_root": str(devops_root) if devops_root else None,
        "route_plan_hash": route_plan.get("plan_hash"),
        "route_count": route_plan.get("route_count"),
        "task_count": len(tasks),
        "tasks": tasks,
        "validation_command": "python scripts/agent_control/workspace_route_task_validator.py --input <route-task-seeds.json> --json",
        "dry_run": True,
        "mutates_project_queues": False,
    }


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--devops-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = build_report(args.registry.expanduser(), project_id=args.project_id, devops_root=args.devops_root.expanduser() if args.devops_root else None)
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"{report['task_count']} route task seed(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
