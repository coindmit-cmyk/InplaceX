#!/usr/bin/env python3
"""Guard Auto Dispatcher output against over-routing work away from workers."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


ARCHITECT_RATIO_DEFAULT = 0.25
MIN_WORKER_READY_DEFAULT = 1
CONCRETE_COMPLEXITIES = {"S", "M", "L"}
CONCRETE_HINTS = (
    "model",
    "service",
    "view",
    "api",
    "admin",
    "dashboard",
    "command",
    "flow",
    "checklist",
    "smoke",
    "test",
    "validator",
    "registry",
    "dto",
    "adapter",
    "import",
    "export",
    "sync",
    "notification",
)
ARCHITECT_HINTS = (
    "architecture",
    "decision",
    "future",
    "designed and split",
    "split into implementation tasks",
    "product line",
    "multi-project",
    "owner decision",
)
NEEDS_ARCHITECT_DETAIL_FIELDS = (
    "architect_request",
    "architecture_question",
    "split_reason",
)
GENERIC_NEEDS_ARCHITECT_REASONS = (
    "too broad or container-like",
    "architect/dispatcher must split it before worker execution",
)
WORKER_READY_STATUSES = {"planned", "needs_stronger_agent", "worker_ready"}
REQUIRED_PACKET_FIELDS = (
    "complexity",
    "priority",
    "type",
    "allowed_paths",
    "forbidden_paths",
    "acceptance_criteria",
    "checks",
)
WORKER_PACKET_V2_FIELDS = (
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
)
WORKER_ASSIGNMENT_FIELDS = (
    "recommended_agent",
    "eligible_worker_profiles",
    "worker",
)
CONTEXT_FIELDS = (
    "context_docs",
    "source_file",
    "provenance",
)
PROJECT_RULES_REVIEW_ONLY_CATEGORIES = {"source_truth", "project_memory", "sensitive_risk"}
REPAIR_DETAIL_FIELDS = (
    "repair_request",
    "missing_packet_fields",
    "repair_owner",
    "next_action",
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return len(value) > 0
    return True


def is_worker_ready(task: dict[str, Any]) -> bool:
    return (
        str(task.get("status") or "") in WORKER_READY_STATUSES
        and task.get("worker_ready") is True
        and task.get("dispatcher_decision") == "worker_ready"
    )


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def completed_task_ids(tasks: list[dict[str, Any]]) -> set[str]:
    done_statuses = {"done", "completed", "finalized", "released", "archived", "owner_approved"}
    result: set[str] = set()
    for task in tasks:
        if str(task.get("status") or "").lower() in done_statuses:
            value = task_id(task)
            if value:
                result.add(value)
    return result


def unresolved_dependencies(task: dict[str, Any], completed_ids: set[str]) -> list[str]:
    value = task.get("depends_on")
    if not isinstance(value, list):
        return []
    return [
        str(item).strip()
        for item in value
        if str(item).strip() and str(item).strip() not in completed_ids
    ]


def is_claimable_worker_ready(task: dict[str, Any], completed_ids: set[str]) -> bool:
    return is_worker_ready(task) and not unresolved_dependencies(task, completed_ids)


def has_complete_worker_packet(task: dict[str, Any]) -> bool:
    if not all(has_value(task.get(field)) for field in REQUIRED_PACKET_FIELDS):
        return False
    if not any(has_value(task.get(field)) for field in WORKER_ASSIGNMENT_FIELDS):
        return False
    if not any(has_value(task.get(field)) for field in CONTEXT_FIELDS):
        return False
    return True


def has_full_worker_packet_v2(task: dict[str, Any]) -> bool:
    return has_complete_worker_packet(task) and all(has_value(task.get(field)) for field in WORKER_PACKET_V2_FIELDS)


def context_inventory_has_refs(task: dict[str, Any]) -> bool:
    inventory = task.get("context_inventory")
    if not isinstance(inventory, dict):
        return False
    return all(has_value(inventory.get(field)) for field in ("code_refs", "doc_refs", "task_refs"))


def is_migration_sensitive(task: dict[str, Any]) -> bool:
    values = [
        str(task.get("type") or ""),
        str(task.get("title") or ""),
        *[str(path) for path in task.get("allowed_paths") or []],
        *[str(path) for path in task.get("code_refs") or []],
    ]
    text = " ".join(values).lower().replace("\\", "/")
    return "migration" in text or "migrations/" in text


def has_compatibility_policy(task: dict[str, Any]) -> bool:
    migration_policy = task.get("migration_compatibility_policy")
    if isinstance(migration_policy, dict):
        policy_text = " ".join([
            str(migration_policy.get("mode") or ""),
            *[str(item) for item in migration_policy.get("required_integrator_behavior") or []],
            *[str(item) for item in migration_policy.get("required_checks") or []],
        ]).lower()
        if "current" in policy_text and ("integrate" in policy_text or "adapt" in policy_text):
            return True
    inventory = task.get("context_inventory")
    inventory_policy = ""
    if isinstance(inventory, dict):
        inventory_policy = str(inventory.get("review_policy") or "")
    combined = " ".join([
        inventory_policy,
        *[str(item) for item in task.get("preserve_contract") or []],
        *[str(item) for item in task.get("integration_notes") or []],
    ]).lower()
    return "integrate" in combined and ("current" in combined or "target" in combined or "develop" in combined)


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def is_project_rules_review_only(task: dict[str, Any]) -> bool:
    task_type = str(task.get("type") or "").lower()
    category = str(task.get("category") or "").lower()
    value = task_id(task).upper()
    return (
        task_type.startswith("automation/project_rules_review/")
        and category in PROJECT_RULES_REVIEW_ONLY_CATEGORIES
        and value.startswith("PRU-")
    )


def is_promotable_needs_task_packet(task: dict[str, Any]) -> bool:
    if task.get("status") != "needs_task_packet" and task.get("dispatcher_decision") != "needs_task_packet":
        return False
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        return False
    return has_complete_worker_packet(task)


def is_needs_architect(task: dict[str, Any]) -> bool:
    return task.get("status") == "needs_architect" or task.get("dispatcher_decision") == "needs_architect"


def has_needs_architect_detail(task: dict[str, Any]) -> bool:
    return any(has_value(task.get(field)) for field in NEEDS_ARCHITECT_DETAIL_FIELDS)


def has_generic_needs_architect_reason(task: dict[str, Any]) -> bool:
    reason = str(task.get("not_worker_ready_reason") or task.get("dispatcher_decision_reason") or "").lower()
    return any(fragment in reason for fragment in GENERIC_NEEDS_ARCHITECT_REASONS)


def is_concrete_overroute_candidate(task: dict[str, Any]) -> bool:
    if not is_needs_architect(task):
        return False
    if has_value(task.get("split_into")):
        return False
    title = str(task.get("title") or "").lower()
    reason = str(task.get("not_worker_ready_reason") or task.get("dispatcher_decision_reason") or "").lower()
    complexity = str(task.get("complexity") or "").upper()
    if complexity == "XL":
        return False
    if any(hint in title or hint in reason for hint in ARCHITECT_HINTS):
        return False
    if complexity in CONCRETE_COMPLEXITIES and any(hint in title for hint in CONCRETE_HINTS):
        return True
    return "too broad or container-like" in reason and complexity in CONCRETE_COMPLEXITIES


def add_issue(issues: list[dict[str, Any]], severity: str, code: str, message: str, **extra: Any) -> None:
    item = {"severity": severity, "code": code, "message": message}
    item.update(extra)
    issues.append(item)


def build_report(queue_path: Path, max_architect_ratio: float, min_worker_ready: int) -> dict[str, Any]:
    data = load_json(queue_path)
    tasks = [task for task in data.get("tasks", []) if isinstance(task, dict)]
    done_ids = completed_task_ids(tasks)
    raw_worker_ready = [task for task in tasks if is_worker_ready(task)]
    worker_ready = [task for task in raw_worker_ready if is_claimable_worker_ready(task, done_ids)]
    blocked_by_dependency = [task for task in raw_worker_ready if unresolved_dependencies(task, done_ids)]
    needs_architect = [task for task in tasks if is_needs_architect(task)]
    claimable_or_dispatcher = [
        task for task in tasks
        if task.get("status") in {"planned", "needs_task_packet", "needs_architect"}
        or task.get("dispatcher_decision") in {"worker_ready", "needs_task_packet", "needs_architect"}
    ]
    concrete_overroutes = [task for task in needs_architect if is_concrete_overroute_candidate(task)]
    missing_architect_detail = [task for task in needs_architect if not has_needs_architect_detail(task)]
    generic_architect_reason = [
        task for task in needs_architect
        if has_generic_needs_architect_reason(task) and not has_needs_architect_detail(task)
    ]
    promotable_needs_task_packet = [task for task in claimable_or_dispatcher if is_promotable_needs_task_packet(task)]
    incomplete_v2_worker_ready = [
        task for task in worker_ready
        if int(task.get("packet_schema_version") or 1) >= 2 and not has_full_worker_packet_v2(task)
    ]
    incomplete_context_inventory = [
        task for task in worker_ready
        if int(task.get("packet_schema_version") or 1) >= 2
        and has_value(task.get("context_inventory"))
        and not context_inventory_has_refs(task)
    ]
    migration_without_policy = [
        task for task in worker_ready
        if int(task.get("packet_schema_version") or 1) >= 2
        and is_migration_sensitive(task)
        and not has_compatibility_policy(task)
    ]
    project_rules_review_worker_ready = [
        task for task in raw_worker_ready
        if is_project_rules_review_only(task)
    ]
    missing_current_context_review = [
        task for task in worker_ready
        if int(task.get("packet_schema_version") or 1) >= 2
        and task.get("requires_current_context_review") is True
        and not has_current_context_verification(task)
    ]
    legacy_worker_ready = [
        task for task in worker_ready
        if int(task.get("packet_schema_version") or 1) < 2
    ]
    dispatcher_repair = [
        task for task in tasks
        if task.get("status") == "needs_dispatcher_repair" or task.get("dispatcher_decision") == "needs_dispatcher_repair"
    ]
    incomplete_repair = [
        task for task in dispatcher_repair
        if not all(has_value(task.get(field)) for field in REPAIR_DETAIL_FIELDS)
    ]
    ratio = (len(needs_architect) / len(claimable_or_dispatcher)) if claimable_or_dispatcher else 0.0

    issues: list[dict[str, Any]] = []
    if promotable_needs_task_packet:
        add_issue(
            issues,
            "error",
            "complete_packet_left_needs_task_packet",
            "Complete worker packets cannot remain needs_task_packet; normalize them to planned + worker_ready before committing Dispatcher output.",
            count=len(promotable_needs_task_packet),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in promotable_needs_task_packet[:20]],
        )
    if incomplete_v2_worker_ready:
        add_issue(
            issues,
            "error",
            "worker_packet_v2_incomplete",
            "packet_schema_version=2 worker-ready tasks must include worker_instructions, traceability, context_inventory, doc_refs, input_refs, output_contract, script_actions, existing_behavior, preserve_contract, regression_guards, code_refs and integration_notes.",
            count=len(incomplete_v2_worker_ready),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in incomplete_v2_worker_ready[:20]],
        )
    if incomplete_context_inventory:
        add_issue(
            issues,
            "error",
            "incomplete_context_inventory",
            "packet_schema_version=2 context_inventory must include code_refs, doc_refs and task_refs so generated work is grounded in current code, docs and task state.",
            count=len(incomplete_context_inventory),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in incomplete_context_inventory[:20]],
        )
    if migration_without_policy:
        add_issue(
            issues,
            "error",
            "migration_without_compatibility_policy",
            "Migration-sensitive worker packets must say how compatible changes are integrated into current target code instead of being rejected only because target code drifted.",
            count=len(migration_without_policy),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in migration_without_policy[:20]],
        )
    if project_rules_review_worker_ready:
        add_issue(
            issues,
            "error",
            "project_rules_review_only_worker_ready",
            "Project Rules review-only rows must stay Dispatcher-owned; source_truth/project_memory/sensitive_risk findings are not durable worker packets.",
            count=len(project_rules_review_worker_ready),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in project_rules_review_worker_ready[:20]],
        )
    if missing_current_context_review:
        add_issue(
            issues,
            "error",
            "current_context_review_required",
            "Generated or imported worker-ready packets must record explicit current code, docs and task queue review before worker claim.",
            count=len(missing_current_context_review),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in missing_current_context_review[:20]],
        )
    if incomplete_repair:
        add_issue(
            issues,
            "error",
            "dispatcher_repair_without_contract",
            "needs_dispatcher_repair rows must include repair_request, missing_packet_fields, repair_owner and next_action.",
            count=len(incomplete_repair),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in incomplete_repair[:20]],
        )
    if legacy_worker_ready:
        add_issue(
            issues,
            "warning",
            "legacy_worker_packet_v1",
            "worker-ready legacy packets should be upgraded to packet_schema_version=2 by dispatcher_packet_repair.py.",
            count=len(legacy_worker_ready),
            sample_task_ids=[str(task.get("id") or task.get("task_id")) for task in legacy_worker_ready[:20]],
        )
    if missing_architect_detail:
        add_issue(
            issues,
            "error",
            "needs_architect_without_request",
            "needs_architect rows require architect_request, architecture_question or split_reason.",
            count=len(missing_architect_detail),
            sample_task_ids=[str(task.get("id")) for task in missing_architect_detail[:20]],
        )
    if generic_architect_reason:
        add_issue(
            issues,
            "error",
            "generic_needs_architect_reason",
            "needs_architect rows cannot use only a generic broad/container reason.",
            count=len(generic_architect_reason),
            sample_task_ids=[str(task.get("id")) for task in generic_architect_reason[:20]],
        )
    if blocked_by_dependency:
        add_issue(
            issues,
            "warning",
            "worker_ready_blocked_by_dependency",
            "Some worker_ready rows have unresolved depends_on entries and cannot be claimed by worker pool yet.",
            count=len(blocked_by_dependency),
            sample_task_ids=[str(task.get("id")) for task in blocked_by_dependency[:20]],
        )
    if len(worker_ready) < min_worker_ready and needs_architect:
        add_issue(
            issues,
            "error",
            "no_worker_ready_after_dispatcher",
            "Dispatcher output left too few worker-ready planned tasks while routing work to needs_architect.",
            worker_ready_count=len(worker_ready),
            min_worker_ready=min_worker_ready,
            needs_architect_count=len(needs_architect),
        )
    if ratio > max_architect_ratio and concrete_overroutes:
        add_issue(
            issues,
            "error",
            "needs_architect_spike",
            "Dispatcher routed a high share of concrete work to needs_architect.",
            needs_architect_ratio=round(ratio, 4),
            max_architect_ratio=max_architect_ratio,
            concrete_overroute_count=len(concrete_overroutes),
            sample_task_ids=[str(task.get("id")) for task in concrete_overroutes[:20]],
        )
    elif concrete_overroutes:
        add_issue(
            issues,
            "warning",
            "concrete_needs_architect_candidates",
            "Some concrete-looking tasks were routed to needs_architect; Dispatcher should split or packetize them when possible.",
            concrete_overroute_count=len(concrete_overroutes),
            sample_task_ids=[str(task.get("id")) for task in concrete_overroutes[:20]],
        )

    errors = sum(1 for issue in issues if issue["severity"] == "error")
    warnings = sum(1 for issue in issues if issue["severity"] == "warning")
    return {
        "queue": str(queue_path),
        "tasks": len(tasks),
        "worker_ready_count": len(worker_ready),
        "raw_worker_ready_count": len(raw_worker_ready),
        "worker_ready_blocked_by_dependency_count": len(blocked_by_dependency),
        "needs_architect_count": len(needs_architect),
        "claimable_or_dispatcher_count": len(claimable_or_dispatcher),
        "needs_architect_ratio": round(ratio, 4),
        "concrete_overroute_count": len(concrete_overroutes),
        "concrete_overroute_task_ids": [str(task.get("id")) for task in concrete_overroutes],
        "needs_architect_without_request_count": len(missing_architect_detail),
        "generic_needs_architect_reason_count": len(generic_architect_reason),
        "complete_packet_left_needs_task_packet_count": len(promotable_needs_task_packet),
        "legacy_worker_packet_v1_count": len(legacy_worker_ready),
        "worker_packet_v2_incomplete_count": len(incomplete_v2_worker_ready),
        "incomplete_context_inventory_count": len(incomplete_context_inventory),
        "migration_without_compatibility_policy_count": len(migration_without_policy),
        "project_rules_review_only_worker_ready_count": len(project_rules_review_worker_ready),
        "current_context_review_required_count": len(missing_current_context_review),
        "dispatcher_repair_without_contract_count": len(incomplete_repair),
        "errors": errors,
        "warnings": warnings,
        "issues": issues,
    }


def print_text(report: dict[str, Any]) -> None:
    print(f"queue: {report['queue']}")
    print(f"tasks: {report['tasks']}")
    print(f"worker_ready: {report['worker_ready_count']}")
    print(f"needs_architect: {report['needs_architect_count']}")
    print(f"needs_architect_ratio: {report['needs_architect_ratio']}")
    print(f"concrete_overroutes: {report['concrete_overroute_count']}")
    print(f"complete_packet_left_needs_task_packet: {report['complete_packet_left_needs_task_packet_count']}")
    print(f"errors: {report['errors']}")
    print(f"warnings: {report['warnings']}")
    for issue in report["issues"]:
        print(f"{issue['severity'].upper()} {issue['code']}: {issue['message']}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Guard Auto Dispatcher queue decisions against needs_architect over-routing.")
    parser.add_argument("--queue", required=True, help="Path to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--max-architect-ratio", type=float, default=ARCHITECT_RATIO_DEFAULT, help="Maximum allowed needs_architect share among dispatcher-visible work.")
    parser.add_argument("--min-worker-ready", type=int, default=MIN_WORKER_READY_DEFAULT, help="Minimum planned worker-ready tasks expected after Dispatcher.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    parser.add_argument("--warnings-as-errors", action="store_true", help="Return non-zero when warnings are present.")
    args = parser.parse_args()

    report = build_report(Path(args.queue).resolve(), args.max_architect_ratio, args.min_worker_ready)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_text(report)

    if report["errors"] or (args.warnings_as_errors and report["warnings"]):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
