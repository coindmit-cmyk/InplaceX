#!/usr/bin/env python3
"""Build concrete routing plan for preserved project changes."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

import project_rebuilder
import workspace_change_classifier


NON_ACTIONABLE_CATEGORIES = {"runtime_archive", "preservation_artifact"}
ROUTE_BY_CATEGORY = {
    "product_code": ("integrator", "integrate_product_changes"),
    "task_or_agent_state": ("dispatcher", "recover_task_and_agent_state"),
    "documentation": ("architect", "integrate_documentation"),
    "automation_contract": ("architect", "review_automation_contract_updates"),
    "secret_config": ("owner", "owner_secret_config_review"),
    "unknown": ("integrator", "classify_unknown_paths"),
}


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def path_scope(path: str) -> str:
    normalized = path.replace("\\", "/").strip("/")
    parts = normalized.split("/")
    if not normalized:
        return ""
    if len(parts) == 1:
        return parts[0]
    if parts[0] in {".agent", "AiStudio", "docs", "apps", "templates", "static", "tests", "scripts", "schemas", "agent-core"}:
        return f"{parts[0]}/{parts[1]}/*" if len(parts) > 2 else f"{parts[0]}/*"
    return f"{parts[0]}/*"


def route_id(project_id: str, owner: str, action: str) -> str:
    normalized = project_id.upper().replace("_", "-").replace(" ", "-")
    return f"AISTD2-P12-{normalized}-{owner.upper()}-{action.upper().replace('_', '-')}"


def migration_sensitive(paths: list[str]) -> bool:
    return any("/migrations/" in f"/{path.replace('\\', '/')}" for path in paths)


def build_route(
    project_id: str,
    owner: str,
    action: str,
    category: str,
    paths: list[str],
    sources: list[str],
    full_count: int,
    captured: bool,
    category_migration_sensitive: bool,
) -> dict[str, Any]:
    paths = sorted({path for path in paths if path})
    route = {
        "id": route_id(project_id, owner, action),
        "project_id": project_id,
        "owner": owner,
        "action": action,
        "category": category,
        "status": "ready_for_owner_review" if captured else "blocked_until_preservation",
        "path_count": len(paths),
        "full_category_path_count": full_count,
        "sources": sources,
        "allowed_paths": sorted({path_scope(path) for path in paths if path_scope(path)}),
        "paths_sample": paths[:100],
        "requires_preservation_evidence": True,
        "preservation_captured": captured,
        "migration_sensitive": category_migration_sensitive or migration_sensitive(paths),
        "checks": [],
        "blockers": [] if captured else ["preservation_evidence_missing"],
    }
    if owner == "integrator":
        route["checks"] = [
            "python manage.py makemigrations --check --dry-run",
            "python manage.py test",
        ] if route["migration_sensitive"] else ["project-specific product tests"]
    elif owner == "dispatcher":
        route["checks"] = [
            "python scripts/agent_control/validate_task_queue_readiness.py --queue <project-root>/AiStudio/Task_manager/task_queue.json --json",
            "python scripts/agent_control/project_doctor.py --registry runtime/agent-control/projects.local.json --project-id <project-id> --json",
        ]
    elif owner == "architect":
        route["checks"] = [
            "python scripts/agent_control/documentation_impact_checker.py --project-root <project-root> --project-id <project-id> --json",
        ]
    else:
        route["checks"] = ["owner manual review"]
    if category == "secret_config":
        route["blockers"].append("owner_secret_config_decision_required")
        route["status"] = "needs_owner_decision"
    if category == "unknown":
        route["blockers"].append("unknown_paths_require_manual_classification")
    return route


def routes_for_project(project: dict[str, Any]) -> dict[str, Any]:
    project_id = str(project.get("project_id") or "unknown")
    captured = bool(project.get("preservation_captured"))
    summary = project.get("summary") if isinstance(project.get("summary"), dict) else {}
    examples = summary.get("examples") if isinstance(summary.get("examples"), dict) else {}
    counts = summary.get("by_category") if isinstance(summary.get("by_category"), dict) else {}
    source_counts = summary.get("by_category_source") if isinstance(summary.get("by_category_source"), dict) else {}
    migration_categories = set(summary.get("migration_sensitive_categories") or [])
    grouped: dict[tuple[str, str, str], list[str]] = {}
    ignored: list[dict[str, Any]] = []
    for category, full_count in sorted((counts or {}).items()):
        if category in NON_ACTIONABLE_CATEGORIES:
            continue
        owner, action = ROUTE_BY_CATEGORY.get(category, ROUTE_BY_CATEGORY["unknown"])
        grouped[(owner, action, category)] = [str(path) for path in (examples or {}).get(category, [])]
    routes = [
        build_route(
            project_id,
            owner,
            action,
            category,
            paths,
            sorted((source_counts.get(category) or {}).keys()),
            int((counts or {}).get(category, len(paths)) or 0),
            captured,
            category == "product_code" and category in migration_categories,
        )
        for (owner, action, category), paths in sorted(grouped.items())
    ]
    for route in routes:
        if route["full_category_path_count"] > route["path_count"]:
            route["sample_truncated"] = True
    return {
        "project_id": project_id,
        "preservation_captured": captured,
        "routes": routes,
        "ignored_change_count": sum(int((counts or {}).get(category, 0) or 0) for category in NON_ACTIONABLE_CATEGORIES),
        "ignored_categories": sorted(NON_ACTIONABLE_CATEGORIES),
    }


def build_report(registry_path: Path, *, project_id: str | None = None, devops_root: Path | None = None) -> dict[str, Any]:
    classification = workspace_change_classifier.build_report(registry_path, project_id=project_id, devops_root=devops_root)
    projects = [routes_for_project(project) for project in classification.get("projects") or [] if isinstance(project, dict)]
    report = {
        "schema_version": "1.0",
        "mode": "workspace_integration_route_plan",
        "registry": str(registry_path),
        "devops_root": str(devops_root) if devops_root else None,
        "project_count": len(projects),
        "route_count": sum(len(project["routes"]) for project in projects),
        "projects": projects,
        "mutates_state": False,
    }
    report["plan_hash"] = project_rebuilder.stable_hash(report)
    return report


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
        print(f"routes={report['route_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
