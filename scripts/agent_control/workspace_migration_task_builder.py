#!/usr/bin/env python3
"""Build Architect/Dispatcher task seeds from workspace health evidence."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import project_doctor
import project_rebuilder
import workspace_preservation_plan


FORBIDDEN_PATHS = [".env", ".env.*", "secrets/**", "production config", "customer exports", "runtime secrets"]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def task_id(project_id: str, kind: str) -> str:
    normalized_project = project_id.upper().replace("_", "-").replace(" ", "-")
    normalized_kind = kind.upper().replace("_", "-")
    return f"AISTD2-P12-{normalized_project}-{normalized_kind}"


def owner_status(owner: str) -> str:
    if owner == "architect":
        return "needs_architect"
    if owner == "dispatcher":
        return "needs_task_packet"
    if owner in {"integrator", "workspace-doctor"}:
        return "needs_human"
    return "planned"


def compact_deductions(deductions: list[dict[str, Any]], prefixes: tuple[str, ...]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for item in deductions:
        code = str(item.get("code") or "")
        if any(code == prefix or code.startswith(prefix) or prefix in code for prefix in prefixes):
            result.append({
                "code": code,
                "points": item.get("points"),
                "next_owner": item.get("next_owner"),
                "next_action": item.get("next_action"),
            })
    return result


def build_task(
    *,
    project_id: str,
    kind: str,
    owner: str,
    title: str,
    reason: str,
    source_codes: list[str],
    allowed_paths: list[str],
    checks: list[str],
    created_at: str,
    priority: str = "P1",
    complexity: str = "M",
) -> dict[str, Any]:
    tid = task_id(project_id, kind)
    return {
        "id": tid,
        "canonical_task_id": tid,
        "project_id": project_id,
        "title": title,
        "type": "workspace_migration",
        "status": owner_status(owner),
        "owner": owner,
        "priority": priority,
        "complexity": complexity,
        "worker_ready": False,
        "packet_status": "needs_task_packet",
        "normalization_status": "needs_task_packet",
        "dispatcher_decision": "needs_task_packet",
        "requires_current_context_review": True,
        "current_context_review_reason": "Workspace migration tasks must use fresh Doctor/Rebuilder evidence before any apply.",
        "reason": reason,
        "source_codes": sorted(set(source_codes)),
        "allowed_paths": sorted(set(allowed_paths)),
        "forbidden_paths": FORBIDDEN_PATHS,
        "checks": checks,
        "acceptance_criteria": [
            "No product code is rewritten by this task unless a separate product task explicitly allows it.",
            "Dirty, unpushed or unique local state is preserved before any layout change.",
            "Every apply action has a reviewed plan hash and rollback or restore evidence.",
        ],
        "created_at": created_at,
        "created_by": "scripts/agent_control/workspace_migration_task_builder.py",
    }


def task_seeds_for_project(
    project: dict[str, Any],
    rebuild_item: dict[str, Any],
    preservation_item: dict[str, Any],
    created_at: str,
) -> list[dict[str, Any]]:
    project_id = str(project.get("project_id") or "unknown")
    deductions = project.get("deductions") if isinstance(project.get("deductions"), list) else []
    seeds: list[dict[str, Any]] = []

    registry_codes = compact_deductions(deductions, ("registry_path_warning", "checkout_missing_path", "checkout_missing", "checkout_escapes"))
    if registry_codes:
        seeds.append(build_task(
            project_id=project_id,
            kind="registry-adoption",
            owner="architect",
            title="Adopt Project Standard v2 registry and checkout roles",
            reason="Registry/checkouts are not ready for safe workspace migration.",
            source_codes=[str(item["code"]) for item in registry_codes],
            allowed_paths=["runtime/agent-control/projects.local.json", "PROJECT_WORKSPACE.json", "PROJECT_VERSION.json"],
            checks=[
                "python scripts/agent_control/project_registry.py --registry runtime/agent-control/projects.local.json --migrate",
                "python scripts/agent_control/project_doctor.py --registry runtime/agent-control/projects.local.json --json",
            ],
            created_at=created_at,
            priority="P0",
        ))

    dirty_codes = compact_deductions(deductions, ("branch_mismatch", "checkout_dirty"))
    blockers = [str(item) for item in rebuild_item.get("blockers") or []]
    if dirty_codes or blockers:
        task = build_task(
            project_id=project_id,
            kind="preserve-local-state",
            owner="integrator",
            title="Preserve dirty or branch-diverged local state before migration",
            reason="Migration cannot apply while local state is dirty, diverged or not on the expected branch.",
            source_codes=[str(item["code"]) for item in dirty_codes] + blockers,
            allowed_paths=["rescue refs", "archive/rebuild/**", "docs/reports/**"],
            checks=[
                f"python scripts/agent_control/workspace_preservation_plan.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
                f"python scripts/agent_control/workspace_change_classifier.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
                f"python scripts/agent_control/workspace_integration_route_plan.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
                f"python scripts/agent_control/project_rebuilder.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --level 0 --compact --json",
            ],
            created_at=created_at,
            priority="P0",
            complexity="L",
        )
        if preservation_item.get("preservation_evidence"):
            task["preservation_evidence"] = preservation_item["preservation_evidence"]
        seeds.append(task)

    docs_codes = compact_deductions(deductions, ("project_version_missing", "project_index_missing", "documentation_manifest_missing"))
    if docs_codes:
        seeds.append(build_task(
            project_id=project_id,
            kind="docs-version-gate",
            owner="architect",
            title="Create Project Standard v2 navigation and version files",
            reason="Project navigation/version gates are missing and must be integrated without overwriting local documentation.",
            source_codes=[str(item["code"]) for item in docs_codes],
            allowed_paths=["PROJECT_VERSION.json", "PROJECT_INDEX.md", "DOCUMENTATION_MANIFEST.json", "docs/**"],
            checks=[
                f"python scripts/agent_control/documentation_impact_checker.py --project-root <project-root> --project-id {project_id} --json",
            ],
            created_at=created_at,
        ))

    task_state_codes = compact_deductions(deductions, ("task_queue_missing", "agent_locks_missing", "owner_directives_missing", "task_manager_missing"))
    if task_state_codes:
        seeds.append(build_task(
            project_id=project_id,
            kind="task-state-recovery",
            owner="dispatcher",
            title="Recover or initialize Task Manager state for migration",
            reason="Dispatcher state files are missing or incomplete.",
            source_codes=[str(item["code"]) for item in task_state_codes],
            allowed_paths=["AiStudio/Task_manager/**", "docs/reports/**"],
            checks=[
                f"python scripts/agent_control/project_doctor.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
            ],
            created_at=created_at,
        ))

    cleanup_codes = compact_deductions(deductions, ("legacy_sibling_folders",))
    if cleanup_codes:
        seeds.append(build_task(
            project_id=project_id,
            kind="cleanup-plan",
            owner="workspace-doctor",
            title="Build archive-only cleanup plan for sibling workspace candidates",
            reason="Sibling folders require classification and archive/restore planning before any cleanup.",
            source_codes=[str(item["code"]) for item in cleanup_codes],
            allowed_paths=["archive/cleanup/**", "docs/reports/**"],
            checks=[
                f"python scripts/agent_control/workspace_cleanup.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
            ],
            created_at=created_at,
        ))

    if seeds:
        owner_task = build_task(
            project_id=project_id,
            kind="owner-cutover-review",
            owner="owner",
            title="Review exact migration plan before any apply",
            reason="Project has migration work that needs owner approval of plan hash and rollback evidence.",
            source_codes=sorted({code for seed in seeds for code in seed.get("source_codes", [])}),
            allowed_paths=["docs/reports/**", "runtime/agent-control/**"],
            checks=[
                f"python scripts/agent_control/workspace_preservation_plan.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
                f"python scripts/agent_control/workspace_change_classifier.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
                f"python scripts/agent_control/workspace_integration_route_plan.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
                f"python scripts/agent_control/workspace_route_task_builder.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --json",
                f"python scripts/agent_control/project_rebuilder.py --registry runtime/agent-control/projects.local.json --project-id {project_id} --level 0 --compact --json",
                f"python scripts/agent_control/quarantine_policy.py --doctor-report <doctor-report> --project-id {project_id} --json",
            ],
            created_at=created_at,
            priority="P0",
            complexity="S",
        )
        if preservation_item.get("preservation_evidence"):
            owner_task["preservation_evidence"] = preservation_item["preservation_evidence"]
        seeds.append(owner_task)
    return seeds


def build_report(registry_path: Path, *, devops_root: Path | None = None, project_id: str | None = None) -> dict[str, Any]:
    created_at = utc_now()
    doctor = project_doctor.build_report(registry_path, devops_root=devops_root, project_id=project_id)
    rebuild = project_rebuilder.build_plan(registry_path, project_id=project_id, level=0)
    preservation = workspace_preservation_plan.build_report(registry_path, devops_root=devops_root, project_id=project_id)
    rebuild_by_project = {
        str(item.get("project_id") or ""): item
        for item in rebuild.get("projects") or []
        if isinstance(item, dict)
    }
    preservation_by_project = {
        str(item.get("project_id") or ""): item
        for item in preservation.get("projects") or []
        if isinstance(item, dict)
    }
    tasks: list[dict[str, Any]] = []
    for project in doctor.get("projects") or []:
        if not isinstance(project, dict):
            continue
        pid = str(project.get("project_id") or "")
        tasks.extend(task_seeds_for_project(project, rebuild_by_project.get(pid, {}), preservation_by_project.get(pid, {}), created_at))
    return {
        "schema_version": "1.0",
        "mode": "workspace_migration_task_seeds",
        "registry": str(registry_path),
        "devops_root": str(devops_root) if devops_root else None,
        "project_filter": project_id,
        "doctor_summary": {
            "project_count": doctor.get("project_count"),
            "attention_count": doctor.get("attention_count"),
            "average_health_score": doctor.get("average_health_score"),
        },
        "rebuild_summary": {
            "project_count": rebuild.get("project_count"),
            "blocked_count": rebuild.get("blocked_count"),
        },
        "preservation_summary": {
            "project_count": preservation.get("project_count"),
            "preservation_required_count": preservation.get("preservation_required_count"),
            "captured_count": sum(
                1
                for item in preservation.get("projects") or []
                if isinstance(item, dict) and (item.get("preservation_evidence") or {}).get("captured")
            ),
        },
        "task_count": len(tasks),
        "tasks": tasks,
        "dry_run": True,
        "mutates_project_queues": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--devops-root", type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = build_report(args.registry.expanduser(), devops_root=args.devops_root.expanduser() if args.devops_root else None, project_id=args.project_id)
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"{report['task_count']} task seed(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
