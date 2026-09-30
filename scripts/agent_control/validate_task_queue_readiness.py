#!/usr/bin/env python3
"""Validate worker-ready and Dispatcher decision state in task_queue.json."""

from __future__ import annotations

import argparse
import json
import fnmatch
import re
import sys
from pathlib import Path
from typing import Any


CLAIMABLE_STATUSES = {"planned", "needs_stronger_agent", "worker_ready"}
NON_WORKER_STATUSES = {
    "needs_task_packet",
    "needs_dispatcher_repair",
    "needs_architect",
    "needs_human",
    "human_answered",
    "needs_dispatcher_split",
    "packet_defect",
    "needs_worker_fix",
    "dispatcher_review",
    "blocked",
    "claimed",
    "agent_working",
    "agent_done",
    "integration_ready",
    "integration_requested",
    "integrating",
    "integration_handoff_ready",
    "finalization_ready",
    "finalizing",
    "integration_blocked",
    "finalization_blocked",
    "review",
    "owner_approved",
    "done",
    "postponed",
    "failed",
    "archived",
    "stale_or_superseded",
    "duplicate_linked",
}
INTEGRATION_READY_STATUSES = {"agent_done", "review", "integration_ready", "integration_requested"}
NON_WORKER_DECISIONS = {
    "needs_task_packet",
    "needs_dispatcher_repair",
    "needs_dispatcher_review",
    "needs_architect",
    "needs_human",
    "needs_worker_fix",
    "needs_integrator_review",
    "split_into_children",
    "duplicate_linked",
    "stale_or_superseded",
    "blocked_by_dependency",
    "blocked_by_missing_environment",
    "blocked_by_pr_stack",
    "blocked",
    "archived",
    "done",
}
DECISIONS = {
    "worker_ready",
    "integration_ready",
    "needs_dispatcher_review",
    "needs_task_packet",
    "needs_dispatcher_repair",
    "needs_architect",
    "needs_human",
    "needs_worker_fix",
    "needs_integrator_review",
    "split_into_children",
    "duplicate_linked",
    "stale_or_superseded",
    "blocked_by_dependency",
    "blocked_by_missing_environment",
    "blocked_by_pr_stack",
    "blocked",
    "archived",
    "done",
}
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
    "base_branch",
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
REPAIR_DETAIL_FIELDS = (
    "repair_request",
    "missing_packet_fields",
    "repair_owner",
    "next_action",
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
EXECUTABLE_CHECK_COMMANDS = {
    "ansible",
    "ansible-playbook",
    "bash",
    "cargo",
    "cmake",
    "curl",
    "docker",
    "docker-compose",
    "dotnet",
    "false",
    "gh",
    "git",
    "go",
    "helm",
    "jq",
    "kubectl",
    "make",
    "mypy",
    "node",
    "npm",
    "npx",
    "pip",
    "pip3",
    "pnpm",
    "poetry",
    "powershell",
    "pwsh",
    "py",
    "pytest",
    "ruff",
    "sh",
    "systemd-analyze",
    "terraform",
    "tox",
    "true",
    "uv",
    "wget",
    "yarn",
    "yq",
}
EXECUTABLE_SCRIPT_SUFFIXES = {".bat", ".cmd", ".ps1", ".py", ".sh"}
CONTROL_PLANE_RECOMMENDED_AGENTS = {"dispatcher", "auto-dispatcher", "auto_dispatcher"}

def normalize_path(value: Any) -> str:
    return str(value or "").replace("\\", "/").strip().lower()


def path_list(value: Any) -> list[str]:
    return [normalize_path(item) for item in as_list(value) if normalize_path(item)]


def is_repository_relative_scope_path(value: Any) -> bool:
    path = str(value or "").replace("\\", "/").strip()
    if (
        not path
        or path.startswith(("/", "~"))
        or re.match(r"^[A-Za-z]:", path)
        or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*://", path)
    ):
        return False
    return ".." not in [segment for segment in path.split("/") if segment]


def unsafe_allowed_paths(task: dict[str, Any]) -> list[str]:
    return [
        str(path)
        for path in as_list(task.get("allowed_paths"))
        if not is_repository_relative_scope_path(path)
    ]


def pattern_covers_path(pattern: str, candidate: str) -> bool:
    if not pattern or not candidate:
        return False
    if pattern == candidate:
        return True
    if pattern.endswith("/**"):
        base = pattern[:-3].rstrip("/")
        return candidate == base or candidate.startswith(base + "/")
    if candidate.endswith("/**"):
        base = candidate[:-3].rstrip("/")
        return pattern == base or pattern.startswith(base + "/")
    return fnmatch.fnmatch(candidate, pattern) or fnmatch.fnmatch(pattern, candidate)


def paths_overlap(allowed: list[str], forbidden: list[str]) -> bool:
    for allowed_item in allowed:
        for forbidden_item in forbidden:
            if pattern_covers_path(allowed_item, forbidden_item) or pattern_covers_path(forbidden_item, allowed_item):
                return True
    return False


def is_orchestrator_recommended_agent(value: Any) -> bool:
    if not has_value(value):
        return False
    return str(value).strip().replace("-", "_").lower() in CONTROL_PLANE_RECOMMENDED_AGENTS


def has_worker_execution_route(task: dict[str, Any]) -> bool:
    if has_value(task.get("recommended_agent")) and not is_orchestrator_recommended_agent(task.get("recommended_agent")):
        return True
    for profile in as_list(task.get("eligible_worker_profiles")):
        if has_value(profile) and not is_orchestrator_recommended_agent(profile):
            return True
    return False


def is_forbidden_worker_report_path(path: Any) -> bool:
    normalized = normalize_path(path)
    return normalized.startswith("aistudio/task_manager/reports/")


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


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def script_action_command(value: Any) -> str:
    raw = value.get("command") if isinstance(value, dict) else value
    text = str(raw or "").strip()
    if len(text) >= 2 and text.startswith("`") and text.endswith("`"):
        text = text[1:-1].strip()
    if text.startswith("$ "):
        text = text[2:].strip()
    return text


def is_executable_script_action(value: Any) -> bool:
    command = script_action_command(value)
    if not command or "\n" in command or "\r" in command:
        return False
    match = re.match(r"^(?P<program>[^\s]+)(?:\s|$)", command)
    if not match:
        return False
    program = match.group("program").strip("\"'").replace("\\", "/")
    basename = program.rsplit("/", 1)[-1].lower()
    if basename in EXECUTABLE_CHECK_COMMANDS:
        return True
    if re.fullmatch(r"python(?:\d+(?:\.\d+)*)?", basename):
        return True
    return Path(basename).suffix.lower() in EXECUTABLE_SCRIPT_SUFFIXES and (
        "/" in program or basename.startswith(".")
    )


def packet_schema_version(task: dict[str, Any]) -> int:
    try:
        return int(task.get("packet_schema_version") or 1)
    except (TypeError, ValueError):
        return 1


def task_path(index: int, task_id: Any) -> str:
    return f"tasks[{index}]({task_id or 'missing-id'})"


def add_issue(issues: list[dict[str, str]], severity: str, code: str, path: str, message: str) -> None:
    issues.append({"severity": severity, "code": code, "path": path, "message": message})


def has_needs_architect_detail(task: dict[str, Any]) -> bool:
    return any(has_value(task.get(field)) for field in NEEDS_ARCHITECT_DETAIL_FIELDS)


def has_generic_needs_architect_reason(task: dict[str, Any]) -> bool:
    reason = str(task.get("not_worker_ready_reason") or task.get("dispatcher_decision_reason") or "").lower()
    return any(fragment in reason for fragment in GENERIC_NEEDS_ARCHITECT_REASONS)


def context_inventory_has_refs(task: dict[str, Any]) -> bool:
    inventory = task.get("context_inventory")
    if not isinstance(inventory, dict):
        return False
    return all(has_value(inventory.get(field)) for field in ("code_refs", "doc_refs", "task_refs"))


def is_migration_sensitive(task: dict[str, Any]) -> bool:
    inventory = task.get("context_inventory") if isinstance(task.get("context_inventory"), dict) else {}
    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    values = [
        str(task.get("type") or ""),
        str(task.get("title") or ""),
        *[str(path) for path in task.get("allowed_paths") or []],
        *[str(path) for path in task.get("code_refs") or []],
        *[str(path) for path in task.get("changed_paths") or []],
        *[str(path) for path in task.get("integration_changed_paths") or []],
        *[str(item) for item in task.get("checks") or []],
        *[str(item) for item in task.get("acceptance_criteria") or []],
        *[str(item) for item in task.get("worker_instructions") or []],
        *[str(item) for item in task.get("output_contract") or []],
        *[str(item) for item in task.get("script_actions") or []],
        *[str(item) for item in task.get("regression_guards") or []],
        *[str(path) for path in inventory.get("code_refs") or []],
        *[str(path) for path in input_refs.get("allowed_paths") or []],
        *[str(path) for path in input_refs.get("changed_paths") or []],
    ]
    text = " ".join(values).lower().replace("\\", "/")
    return "migration" in text or "migrations/" in text or "migrate" in text or "makemigrations" in text


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


def is_sensitive_risk_review_only(task: dict[str, Any]) -> bool:
    task_type = str(task.get("type") or "")
    values = [
        str(task.get("category") or ""),
        str(task.get("source_finding_category") or ""),
        str(task.get("source_summary") or ""),
        *[str(item) for item in task.get("labels") or []],
    ]
    if task_type.strip().lower() != "clean-rebuild":
        values.extend([task_type, str(task.get("title") or "")])
    text = " ".join(values).lower().replace("-", "_")
    return "possible_secret_pattern" in text or "sensitive_risk" in text


def validate_task(task: dict[str, Any], index: int, issues: list[dict[str, str]]) -> None:
    path = task_path(index, task.get("id"))
    status = str(task.get("status") or "")
    worker_ready = task.get("worker_ready") is True
    packet_status = str(task.get("packet_status") or "")
    normalization_status = str(task.get("normalization_status") or "")
    decision = str(task.get("dispatcher_decision") or "")
    claimable_packet = status in CLAIMABLE_STATUSES

    if decision and decision not in DECISIONS:
        add_issue(issues, "error", "unknown_dispatcher_decision", path, f"unknown dispatcher_decision: {decision}")

    if status in CLAIMABLE_STATUSES and not worker_ready and decision not in NON_WORKER_DECISIONS:
        add_issue(
            issues,
            "error",
            "claimable_without_worker_ready",
            path,
            "claimable status requires worker_ready=true",
        )

    if status in CLAIMABLE_STATUSES and worker_ready and decision != "worker_ready":
        add_issue(
            issues,
            "error",
            "claimable_non_worker_decision",
            path,
            f"claimable status cannot use dispatcher_decision={decision}",
        )

    if decision == "integration_ready" and status not in INTEGRATION_READY_STATUSES:
        add_issue(
            issues,
            "error",
            "integration_ready_without_integration_status",
            path,
            f"dispatcher_decision=integration_ready requires status in {sorted(INTEGRATION_READY_STATUSES)}",
        )

    if worker_ready:
        if is_sensitive_risk_review_only(task):
            add_issue(issues, "error", "sensitive_risk_marked_worker_ready", path, "sensitive-risk findings require Human/security review before worker-ready")
        if decision and decision != "worker_ready":
            add_issue(issues, "error", "worker_ready_wrong_decision", path, "worker_ready=true requires dispatcher_decision=worker_ready")
        if packet_status and packet_status not in {"worker_ready", "ready"}:
            add_issue(issues, "error", "worker_ready_wrong_packet_status", path, f"worker_ready=true conflicts with packet_status={packet_status}")
        for field in REQUIRED_PACKET_FIELDS:
            if not has_value(task.get(field)):
                add_issue(issues, "error", "missing_worker_packet_field", path, f"worker-ready task missing {field}")
        if not has_worker_execution_route(task):
            add_issue(issues, "error", "missing_worker_routing", path, "worker-ready task needs recommended_agent or eligible_worker_profiles")
        if has_value(task.get("recommended_agent")) and not has_worker_execution_route(task):
            add_issue(
                issues,
                "error",
                "worker_ready_orchestrator_only_route",
                path,
                "worker-ready task routing must include a concrete worker profile; Dispatcher is orchestration-only",
            )
        if not (has_value(task.get("context_docs")) or has_value(task.get("source_file")) or has_value(task.get("provenance"))):
            add_issue(issues, "error", "missing_context_or_provenance", path, "worker-ready task needs context docs or source provenance")
        allowed = path_list(task.get("allowed_paths"))
        forbidden = path_list(task.get("forbidden_paths"))
        unsafe_scope = unsafe_allowed_paths(task)
        if unsafe_scope:
            add_issue(
                issues,
                "error",
                "unsafe_allowed_path",
                path,
                f"allowed_paths contains {len(unsafe_scope)} non-repository-relative scope entries",
            )
        if paths_overlap(allowed, forbidden):
            add_issue(
                issues,
                "error",
                "paths_overlap",
                path,
                "allowed_paths must not overlap forbidden_paths",
            )
        if claimable_packet and packet_schema_version(task) >= 2:
            for field in WORKER_PACKET_V2_FIELDS:
                if not has_value(task.get(field)):
                    add_issue(issues, "error", "missing_worker_packet_v2_field", path, f"worker packet v2 missing {field}")
            output_contract = task.get("output_contract")
            if isinstance(output_contract, dict):
                if is_forbidden_worker_report_path(output_contract.get("worker_report_path")):
                    add_issue(
                        issues,
                        "error",
                        "forbidden_worker_report_path",
                        f"{path}.output_contract.worker_report_path",
                        "worker_report_path must not target AiStudio/Task_manager/reports/**",
                    )
            if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
                add_issue(issues, "error", "current_context_review_required", path, "worker-ready generated/imported task requires explicit current_context_verified_at and reviewer before worker claim")
            if has_value(task.get("context_inventory")) and not context_inventory_has_refs(task):
                add_issue(issues, "error", "incomplete_context_inventory", path, "context_inventory must include code_refs, doc_refs and task_refs")
            for action_index, action in enumerate(task.get("script_actions") or []):
                if not is_executable_script_action(action):
                    add_issue(
                        issues,
                        "error",
                        "non_executable_script_action",
                        f"{path}.script_actions[{action_index}]",
                        "script_actions must contain executable commands; keep conceptual validation requirements in checks or acceptance_criteria",
                    )
            if is_migration_sensitive(task) and not has_compatibility_policy(task):
                add_issue(issues, "error", "migration_without_compatibility_policy", path, "migration-sensitive packets must say how to integrate compatible changes into current target code")
        elif claimable_packet:
            add_issue(
                issues,
                "warning",
                "legacy_worker_packet_v1",
                path,
                "worker-ready task uses legacy packet schema; run dispatcher_packet_repair.py to add worker packet v2 fields",
            )

    if normalization_status in {"inventory_only", "needs_task_packet", "needs_dispatcher_repair"}:
        if not decision:
            add_issue(issues, "error", "inventory_without_decision", path, "inventory row needs dispatcher_decision")
        if worker_ready:
            add_issue(issues, "error", "inventory_marked_worker_ready", path, "inventory-only rows must keep worker_ready=false")
        if not has_value(task.get("dispatcher_next_review_at")) and decision in {"needs_task_packet", "needs_dispatcher_repair"}:
            add_issue(issues, "warning", "missing_next_review", path, f"{decision} should record dispatcher_next_review_at")

    if normalization_status == "duplicate_linked" or decision == "duplicate_linked":
        if not has_value(task.get("canonical_task_id")):
            add_issue(issues, "error", "duplicate_without_canonical", path, "duplicate_linked rows need canonical_task_id")

    if decision == "split_into_children" and not has_value(task.get("split_into")):
        add_issue(issues, "error", "split_without_children", path, "split_into_children requires split_into child ids")

    if decision == "needs_architect" or status == "needs_architect":
        if not has_needs_architect_detail(task):
            add_issue(
                issues,
                "error",
                "needs_architect_without_request",
                path,
                "needs_architect requires architect_request, architecture_question or split_reason",
            )
        if has_generic_needs_architect_reason(task) and not has_needs_architect_detail(task):
            add_issue(
                issues,
                "error",
                "generic_needs_architect_reason",
                path,
                "needs_architect cannot use only a generic broad/container reason",
            )

    if decision == "needs_dispatcher_repair" or status == "needs_dispatcher_repair":
        for field in REPAIR_DETAIL_FIELDS:
            if not has_value(task.get(field)):
                add_issue(issues, "error", "missing_dispatcher_repair_field", path, f"needs_dispatcher_repair requires {field}")
        if worker_ready:
            add_issue(issues, "error", "dispatcher_repair_marked_worker_ready", path, "needs_dispatcher_repair must keep worker_ready=false")

    if decision in {"needs_task_packet", "needs_dispatcher_repair", "needs_dispatcher_review", "needs_architect", "needs_human"}:
        if status not in NON_WORKER_STATUSES and not has_value(task.get("not_worker_ready_reason")):
            add_issue(issues, "warning", "missing_not_worker_ready_reason", path, "non-worker decision should explain why")

    if not decision and (worker_ready or status not in NON_WORKER_STATUSES or normalization_status or packet_status):
        add_issue(issues, "error", "missing_dispatcher_decision", path, "visible normalized row needs dispatcher_decision")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate task queue worker-readiness and Dispatcher decisions.")
    parser.add_argument("--queue", required=True, help="Path to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    parser.add_argument("--warnings-as-errors", action="store_true", help="Return non-zero when warnings are present.")
    args = parser.parse_args()

    queue_path = Path(args.queue).resolve()
    data = load_json(queue_path)
    tasks = data.get("tasks", [])
    issues: list[dict[str, str]] = []

    if not isinstance(tasks, list):
        add_issue(issues, "error", "tasks_not_array", "tasks", "tasks must be an array")
    else:
        for index, task in enumerate(tasks):
            if not isinstance(task, dict):
                add_issue(issues, "error", "task_not_object", f"tasks[{index}]", "task must be an object")
                continue
            validate_task(task, index, issues)

    error_count = sum(1 for issue in issues if issue["severity"] == "error")
    warning_count = sum(1 for issue in issues if issue["severity"] == "warning")
    report = {
        "queue": str(queue_path),
        "tasks": len(tasks) if isinstance(tasks, list) else 0,
        "errors": error_count,
        "warnings": warning_count,
        "issues": issues,
    }

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"queue: {queue_path}")
        print(f"tasks: {report['tasks']}")
        print(f"errors: {error_count}")
        print(f"warnings: {warning_count}")
        for issue in issues:
            print(f"{issue['severity'].upper()} {issue['code']} {issue['path']}: {issue['message']}")

    if error_count or (args.warnings_as_errors and warning_count):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
