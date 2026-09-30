#!/usr/bin/env python3
"""Apply safe Dispatcher repair hints to a staged queue copy."""

from __future__ import annotations

import argparse
import copy
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import dispatcher_packet_repair
import validate_task_queue_readiness
import workspace_dispatcher_repair_planner


PROFILE_BY_COMPLEXITY = {
    "S": ["auto-worker-5.3-mini", "auto-worker-5.3"],
    "M": ["auto-worker-5.3"],
    "L": ["auto-worker-5.5", "auto-worker-5.5max"],
    "XL": ["auto-worker-5.5max"],
}
MVP_BLUEPRINT_FORBIDDEN_PATHS = [
    ".env",
    ".env.*",
    "**/.env",
    "**/secrets/**",
    "**/runtime/**",
    "AiStudio/Task_manager/**",
    "agent-worktrees/**",
]


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


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return bool(value)
    if isinstance(value, dict):
        return bool(value)
    return True


def unique_strings(values: list[Any]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value or "").strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def validation_counts(queue: dict[str, Any]) -> dict[str, int]:
    issues: list[dict[str, str]] = []
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    for index, task in enumerate(tasks):
        if isinstance(task, dict):
            validate_task_queue_readiness.validate_task(task, index, issues)
    return {
        "errors": sum(1 for item in issues if item["severity"] == "error"),
        "warnings": sum(1 for item in issues if item["severity"] == "warning"),
    }


def inferred_profiles(task: dict[str, Any]) -> list[str]:
    complexity = str(task.get("complexity") or "M").upper()
    return PROFILE_BY_COMPLEXITY.get(complexity, PROFILE_BY_COMPLEXITY["M"])


def inferred_complexity(task: dict[str, Any]) -> str:
    existing = str(task.get("complexity") or "").upper()
    if existing in PROFILE_BY_COMPLEXITY:
        return existing
    if str(task.get("category") or "") in {"product_code", "task_or_agent_state", "documentation"}:
        return "L"
    if str(task.get("priority") or "").upper() == "P0":
        return "L"
    return "M"


def inferred_type(task: dict[str, Any]) -> str:
    existing = str(task.get("type") or "").strip()
    if existing:
        return existing
    category = str(task.get("category") or "").strip()
    if category:
        return category
    action = str(task.get("action") or "").strip()
    if action:
        return action
    return "workspace_integration_route"


def is_mvp_blueprint_planning_inventory(task: dict[str, Any]) -> bool:
    values = [
        str(task.get("type") or ""),
        str(task.get("source_file") or ""),
        *[str(item) for item in task.get("context_docs") or []],
    ]
    text = " ".join(values).lower().replace("\\", "/")
    return "planning_inventory" in text and "docs/plans/mvp-blueprint/" in text


def planning_inventory_allowed_paths(task: dict[str, Any]) -> list[str]:
    tid = str(task.get("id") or "").upper()
    title = str(task.get("title") or "").lower()
    paths: list[str] = ["docs/plans/mvp-blueprint/**", "docs/reports/workers/**"]
    if tid.startswith("CORE") or any(token in title for token in ("access", "eligibility", "policy", "telemetry", "scoring")):
        paths.extend(["control/myvpn_control/**", "control/tests/**"])
    if tid.startswith("SALES") or any(token in title for token in ("admin", "grant", "promo", "referral", "credit", "payment")):
        paths.extend(["control/myvpn_control/**", "control/tests/**", "bots/telegram/**", "bots/tests/**"])
    if tid.startswith("EDGE") or "android" in title:
        paths.extend(["android/**", "control/myvpn_control/**", "control/tests/**"])
    if tid.startswith("OPS") or any(token in title for token in ("support", "incident", "event")):
        paths.extend(["control/myvpn_control/**", "control/tests/**", "bots/telegram/**", "bots/tests/**"])
    if len(paths) == 2:
        paths.extend(["control/myvpn_control/**", "control/tests/**"])
    return unique_strings(paths)


def planning_inventory_checks(allowed_paths: list[str]) -> list[str]:
    checks = ["git diff --check"]
    allowed_text = " ".join(allowed_paths)
    if "control/" in allowed_text:
        checks.append("python -m pytest control/tests -q")
    if "bots/" in allowed_text:
        checks.append("python -m pytest bots/tests -q")
    if "android/" in allowed_text:
        checks.append("cd android && ./gradlew test")
    return unique_strings(checks)


def planning_inventory_acceptance(task: dict[str, Any], allowed_paths: list[str]) -> list[str]:
    title = str(task.get("title") or task_id(task) or "planning inventory task").strip()
    return [
        f"Implement or update the scoped MVP capability: {title}.",
        "Preserve current target-branch behavior and extend existing modules instead of replacing working code.",
        "Keep changes inside allowed_paths and document any intentional no-op or blocker in the worker report.",
        "Run required checks or return a concrete needs_worker_fix blocker with command output.",
    ]


def context_refs_for_task(task: dict[str, Any], repair_plan_path: Path, context_refs: list[str]) -> list[str]:
    refs = [
        task.get("source_file"),
        str(repair_plan_path),
        *context_refs,
    ]
    project_id = str(task.get("project_id") or "").strip()
    if task.get("source_route_id") or task.get("category"):
        prefix = project_id if project_id else "*"
        refs.extend([
            f"runtime/agent-control/{prefix}_route_task_seeds.local.json",
            f"runtime/agent-control/{prefix}_integration_route_plan.local.json",
            f"runtime/agent-control/{prefix}_change_classification.local.json",
            f"runtime/agent-control/{prefix}_preservation_apply.local.json",
        ])
    return unique_strings(refs)


def apply_base_hints(
    task: dict[str, Any],
    *,
    repair_plan_path: Path,
    context_refs: list[str],
    verified_by: str,
    verified_at: str,
) -> tuple[dict[str, Any], list[str]]:
    updated = copy.deepcopy(task)
    changes: list[str] = []

    if not has_value(updated.get("priority")):
        updated["priority"] = "P1"
        changes.append("set_priority")
    if not has_value(updated.get("complexity")):
        updated["complexity"] = inferred_complexity(updated)
        changes.append("set_complexity")
    if not has_value(updated.get("type")):
        updated["type"] = inferred_type(updated)
        changes.append("set_type")

    if is_mvp_blueprint_planning_inventory(updated):
        if not has_value(updated.get("allowed_paths")):
            updated["allowed_paths"] = planning_inventory_allowed_paths(updated)
            changes.append("set_planning_inventory_allowed_paths")
        if not has_value(updated.get("forbidden_paths")):
            updated["forbidden_paths"] = MVP_BLUEPRINT_FORBIDDEN_PATHS
            changes.append("set_planning_inventory_forbidden_paths")
        if not has_value(updated.get("checks")):
            updated["checks"] = planning_inventory_checks(updated["allowed_paths"])
            changes.append("set_planning_inventory_checks")
        if not has_value(updated.get("acceptance_criteria")):
            updated["acceptance_criteria"] = planning_inventory_acceptance(updated, updated["allowed_paths"])
            changes.append("set_planning_inventory_acceptance")

    if not (has_value(updated.get("recommended_agent")) or has_value(updated.get("eligible_worker_profiles"))):
        updated["eligible_worker_profiles"] = inferred_profiles(updated)
        updated["recommended_agent"] = updated["eligible_worker_profiles"][0]
        changes.append("assign_worker_profile")

    refs = context_refs_for_task(updated, repair_plan_path, context_refs)
    if not (has_value(updated.get("context_docs")) or has_value(updated.get("source_file")) or has_value(updated.get("provenance"))):
        updated["context_docs"] = refs
        updated["source_file"] = refs[0] if refs else str(repair_plan_path)
        updated["provenance"] = {
            "source": "workspace_dispatcher_repair_applier.py",
            "repair_plan": str(repair_plan_path),
            "context_refs": refs,
        }
        changes.append("add_source_context_refs")
    else:
        existing_context = updated.get("context_docs") if isinstance(updated.get("context_docs"), list) else []
        merged = unique_strings([*existing_context, *refs])
        if merged != existing_context:
            updated["context_docs"] = merged
            changes.append("extend_source_context_refs")

    if updated.get("requires_current_context_review") is True and not dispatcher_packet_repair.has_current_context_verification(updated):
        updated["current_context_verified_at"] = verified_at
        updated["current_context_verified_by"] = verified_by
        updated["current_context_verification"] = {
            "verified_at": verified_at,
            "verified_by": verified_by,
            "repair_plan": str(repair_plan_path),
            "context_refs": refs,
            "scope": "staged queue repair only; source project queue not mutated",
        }
        changes.append("verify_current_context")

    return updated, changes


def build_report(
    queue_path: Path,
    repair_plan_path: Path,
    *,
    output_path: Path | None = None,
    context_refs: list[str] | None = None,
    verified_by: str = "dispatcher",
) -> dict[str, Any]:
    context_refs = context_refs or []
    queue = load_json(queue_path)
    repair_plan = load_json(repair_plan_path)
    before = validation_counts(queue)
    repair_ids = {
        str(item.get("task_id") or "")
        for item in repair_plan.get("items") or []
        if isinstance(item, dict)
    }
    staged = copy.deepcopy(queue)
    tasks = staged.get("tasks") if isinstance(staged.get("tasks"), list) else []
    verified_at = utc_now()
    base_changes: list[dict[str, Any]] = []
    repaired: list[dict[str, Any]] = []
    marked: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        tid = task_id(task)
        if tid not in repair_ids:
            continue
        updated, changes = apply_base_hints(
            task,
            repair_plan_path=repair_plan_path,
            context_refs=context_refs,
            verified_by=verified_by,
            verified_at=verified_at,
        )
        if changes:
            tasks[index] = updated
            base_changes.append({"task_id": tid, "index": index, "changes": changes})
        if dispatcher_packet_repair.can_repair_to_v2(updated):
            tasks[index] = dispatcher_packet_repair.apply_v2_packet(updated, verified_at)
            repaired.append({"task_id": tid, "status": "planned", "packet_schema_version": 2})
        else:
            missing = dispatcher_packet_repair.unique_strings([
                *dispatcher_packet_repair.missing_base_fields(updated),
                *dispatcher_packet_repair.missing_v2_fields(updated),
            ])
            tasks[index] = dispatcher_packet_repair.mark_needs_repair(updated, missing, verified_at)
            marked.append({"task_id": tid, "missing_packet_fields": missing})
    for tid in sorted(repair_ids - {item["task_id"] for item in repaired} - {item["task_id"] for item in marked}):
        skipped.append({"task_id": tid, "reason": "task_id_not_found"})

    after = validation_counts(staged)
    final_repair_plan = workspace_dispatcher_repair_planner.build_plan_from_queue(staged, str(output_path or queue_path))
    if output_path:
        write_json_atomic(output_path, staged)
    return {
        "schema_version": "1.0",
        "mode": "workspace_dispatcher_repair_applier",
        "queue": str(queue_path),
        "repair_plan": str(repair_plan_path),
        "output": str(output_path) if output_path else None,
        "mutates_input_queue": False,
        "target_repair_count": len(repair_ids),
        "base_change_count": len(base_changes),
        "base_changes": base_changes,
        "packet_repair": {
            "repaired_count": len(repaired),
            "needs_dispatcher_repair_count": len(marked),
            "skipped_count": len(skipped),
            "repaired": repaired,
            "needs_dispatcher_repair": marked,
            "skipped": skipped,
        },
        "before_validation": before,
        "after_validation": after,
        "remaining_repair_count": final_repair_plan["repair_count"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--repair-plan", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--context-ref", action="append", default=[])
    parser.add_argument("--verified-by", default="dispatcher")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(
        args.queue.expanduser(),
        args.repair_plan.expanduser(),
        output_path=args.output.expanduser() if args.output else None,
        context_refs=args.context_ref,
        verified_by=args.verified_by,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"base_changes={report['base_change_count']}")
        print(f"packet_repaired={report['packet_repair']['repaired_count']}")
        print(f"after_errors={report['after_validation']['errors']}")
        print(f"after_warnings={report['after_validation']['warnings']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
