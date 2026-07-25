#!/usr/bin/env python3
"""Validate Auto Integrator handoff JSON before Auto Finalizer consumes it."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


REQUIRED_FIELDS = (
    "integration_status",
    "package_branch",
    "base_branch",
    "base_sha",
    "ready_to_finalize",
    "needs_human",
    "blocked",
    "excluded_from_package",
    "checks",
    "finalizer_authority_required",
)
OPTIONAL_ARRAY_FIELDS = (
    "needs_rework",
    "needs_worker_fix",
    "needs_dispatcher",
    "needs_architect",
    "cleanup_candidates",
    "branch_dispositions",
)
VALID_STATUSES = {
    "integration_package_ready",
    "partial_package_ready",
    "integration_ready",
    "integration_blocked",
    "needs_rework_routed",
    "cleanup_candidates_found",
    "no_ready_items",
}
VALID_DISPOSITIONS = {
    "ready_to_finalize",
    "needs_rework",
    "needs_worker_fix",
    "needs_dispatcher",
    "needs_architect",
    "needs_human",
    "cleanup_candidate",
    "excluded",
    "duplicate",
    "stale",
    "service_report",
}
DISPOSITION_LIST_MAP = {
    "ready_to_finalize": "ready_to_finalize",
    "needs_human": "needs_human",
    "needs_rework": "needs_rework",
    "needs_worker_fix": "needs_worker_fix",
    "needs_dispatcher": "needs_dispatcher",
    "needs_architect": "needs_architect",
    "cleanup_candidate": "cleanup_candidates",
}
FINALIZER_FORBIDDEN_FIELDS = {
    "done",
    "done_recorded",
    "owner_approved_recorded",
    "locks_released",
    "cleanup_candidate_recorded",
    "release_tag",
    "production_deployed",
}
REJECTION_DISPOSITIONS = {
    "needs_rework",
    "needs_worker_fix",
    "needs_dispatcher",
    "needs_architect",
    "needs_human",
    "cleanup_candidate",
    "excluded",
    "duplicate",
    "stale",
}
REJECTION_DETAIL_REQUIRED_FIELDS = (
    "summary",
    "blocking_reasons",
    "evidence",
    "recommended_next_action",
)
INTEGRATOR_BRANCH_PREFIXES = (
    "AiStudio/Agent/integrator/",
    "integrator/",
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


def validate_rejection_detail(
    issues: list[dict[str, str]],
    *,
    path: str,
    disposition: str,
    detail: Any,
) -> None:
    if disposition not in REJECTION_DISPOSITIONS:
        return
    if not isinstance(detail, dict):
        add_issue(
            issues,
            "error",
            "disposition_missing_rejection_detail",
            path,
            f"{disposition} needs detailed rejection_detail object for later analysis",
        )
        return
    for field in REJECTION_DETAIL_REQUIRED_FIELDS:
        if not has_value(detail.get(field)):
            add_issue(
                issues,
                "error",
                "rejection_detail_missing_field",
                f"{path}.{field}",
                f"rejection_detail for {disposition} needs {field}",
            )
    for field in ("blocking_reasons", "evidence"):
        if field in detail and not isinstance(detail[field], list):
            add_issue(
                issues,
                "error",
                "rejection_detail_field_not_array",
                f"{path}.{field}",
                f"rejection_detail.{field} must be an array",
            )


def add_issue(issues: list[dict[str, str]], severity: str, code: str, path: str, message: str) -> None:
    issues.append({"severity": severity, "code": code, "path": path, "message": message})


def canonical_task_target(task_id: Any) -> str:
    value = str(task_id or "").strip()
    if value.startswith("source-artifact:"):
        return value
    return f"task:{value}" if value else ""


def looks_like_integration_batch(data: dict[str, Any]) -> bool:
    return (
        has_value(data.get("batch_id"))
        and isinstance(data.get("included"), list)
        and isinstance(data.get("excluded"), list)
        and "integration_status" not in data
    )


def validate_migration_policy(
    issues: list[dict[str, str]],
    *,
    path: str,
    item: dict[str, Any],
) -> None:
    if item.get("migration_sensitive") is not True:
        return
    policy = item.get("migration_compatibility_policy")
    if not isinstance(policy, dict) or not policy:
        add_issue(
            issues,
            "error",
            "migration_policy_missing",
            f"{path}.migration_compatibility_policy",
            "migration-sensitive ready handoff item needs migration_compatibility_policy",
        )
        return
    if str(policy.get("mode") or "").strip() != "adapt_to_current_target":
        add_issue(
            issues,
            "error",
            "migration_policy_mode_invalid",
            f"{path}.migration_compatibility_policy.mode",
            "migration_compatibility_policy.mode must be adapt_to_current_target",
        )
    for field in ("required_integrator_behavior", "required_checks"):
        if not isinstance(policy.get(field), list) or not policy.get(field):
            add_issue(
                issues,
                "error",
                "migration_policy_missing_field",
                f"{path}.migration_compatibility_policy.{field}",
                f"migration_compatibility_policy needs non-empty {field}",
            )


def validate(data: dict[str, Any]) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []

    if looks_like_integration_batch(data):
        add_issue(
            issues,
            "error",
            "wrong_artifact_type",
            "handoff",
            "integration_batch.json is Auto Integrator input; validate integration_handoff.json before Auto Finalizer consumes it",
        )
        return issues

    for field in REQUIRED_FIELDS:
        if field not in data:
            add_issue(issues, "error", "missing_required_field", field, f"handoff missing {field}")

    status = str(data.get("integration_status") or "")
    if status and status not in VALID_STATUSES:
        add_issue(issues, "error", "unknown_integration_status", "integration_status", f"unknown status {status}")

    for field in ("ready_to_finalize", "needs_human", "blocked", "excluded_from_package", "checks", *OPTIONAL_ARRAY_FIELDS):
        if field in data and not isinstance(data[field], list):
            add_issue(issues, "error", "field_not_array", field, f"{field} must be an array")

    ready = set(str(item) for item in data.get("ready_to_finalize", []) if item)
    needs_human = set(str(item) for item in data.get("needs_human", []) if item)
    blocked = set(str(item) for item in data.get("blocked", []) if item)
    needs_rework = set(str(item) for item in data.get("needs_rework", []) if item)
    needs_worker_fix = set(str(item) for item in data.get("needs_worker_fix", []) if item)
    needs_dispatcher = set(str(item) for item in data.get("needs_dispatcher", []) if item)
    needs_architect = set(str(item) for item in data.get("needs_architect", []) if item)
    cleanup_candidates = set()
    for item in data.get("cleanup_candidates", []):
        if isinstance(item, dict):
            value = item.get("branch") or item.get("pr") or item.get("task_id")
        else:
            value = item
        if value:
            cleanup_candidates.add(str(value))
    excluded = set(str(item) for item in data.get("excluded_from_package", []) if item)
    non_ready = needs_human | blocked | needs_rework | needs_worker_fix | needs_dispatcher | needs_architect

    overlap = sorted(non_ready & ready)
    if overlap:
        add_issue(issues, "error", "ready_item_also_excluded_state", "ready_to_finalize", f"items cannot be ready and routed away from finalizer: {', '.join(overlap)}")

    missing_exclusions = sorted(non_ready - excluded)
    if missing_exclusions:
        add_issue(issues, "error", "excluded_list_incomplete", "excluded_from_package", f"non-ready items must be excluded: {', '.join(missing_exclusions)}")

    extra_exclusions = sorted(excluded - non_ready - cleanup_candidates)
    if extra_exclusions:
        add_issue(issues, "warning", "extra_excluded_items", "excluded_from_package", f"excluded items are not listed in routing arrays: {', '.join(extra_exclusions)}")

    if status in {"integration_package_ready", "partial_package_ready"} and not ready:
        add_issue(issues, "error", "package_ready_without_items", "ready_to_finalize", f"{status} needs at least one ready item")

    if status == "partial_package_ready" and not non_ready and not cleanup_candidates:
        add_issue(issues, "warning", "partial_package_without_routed_items", "integration_status", "partial_package_ready should list routed non-ready items or cleanup candidates")

    if status == "integration_blocked" and not (blocked or needs_human):
        add_issue(issues, "error", "blocked_without_blockers", "blocked", "integration_blocked must list blocked or needs_human items")

    if status in {"needs_rework_routed", "cleanup_candidates_found", "no_ready_items"} and ready:
        add_issue(issues, "warning", "ready_items_with_non_package_status", "integration_status", f"{status} normally should not include ready_to_finalize items")

    branch_dispositions = data.get("branch_dispositions", [])
    if isinstance(branch_dispositions, list):
        seen_disposition_targets: set[str] = set()
        for index, item in enumerate(branch_dispositions):
            path = f"branch_dispositions[{index}]"
            if not isinstance(item, dict):
                add_issue(issues, "error", "disposition_not_object", path, "branch disposition must be an object")
                continue
            disposition = str(item.get("disposition") or "")
            if disposition not in VALID_DISPOSITIONS:
                add_issue(issues, "error", "unknown_branch_disposition", f"{path}.disposition", f"unknown disposition {disposition}")
            if not has_value(item.get("branch")) and not has_value(item.get("pr")) and not has_value(item.get("task_id")):
                add_issue(issues, "error", "disposition_missing_target", path, "branch disposition needs branch, pr or task_id")
            if not has_value(item.get("reason")):
                add_issue(issues, "error", "disposition_missing_reason", f"{path}.reason", "branch disposition needs a reason")
            if has_value(item.get("task_id")):
                expected = canonical_task_target(item.get("task_id"))
                canonical = str(item.get("canonical_target_id") or "")
                if canonical and canonical != expected:
                    add_issue(issues, "error", "canonical_identity_mismatch", f"{path}.canonical_target_id", f"canonical_target_id must be {expected}")
                if disposition == "ready_to_finalize" and not canonical:
                    add_issue(issues, "error", "ready_disposition_missing_canonical_identity", f"{path}.canonical_target_id", "ready_to_finalize disposition needs canonical_target_id")
            if disposition in {"needs_rework", "needs_worker_fix", "needs_dispatcher", "needs_architect", "needs_human", "cleanup_candidate"} and not has_value(item.get("next_owner")):
                add_issue(issues, "error", "disposition_missing_next_owner", f"{path}.next_owner", f"{disposition} needs next_owner")
            validate_rejection_detail(issues, path=f"{path}.rejection_detail", disposition=disposition, detail=item.get("rejection_detail"))
            validate_migration_policy(issues, path=path, item=item)
            target = str(item.get("task_id") or item.get("branch") or item.get("pr") or "")
            if target:
                seen_disposition_targets.add(target)
                mapped = DISPOSITION_LIST_MAP.get(disposition)
                if mapped and mapped != "cleanup_candidates":
                    values = set(str(value) for value in data.get(mapped, []) if value)
                    if target not in values:
                        add_issue(issues, "warning", "disposition_not_reflected_in_list", path, f"{target} has disposition {disposition} but is not listed in {mapped}")

        routed_targets = ready | non_ready
        missing_dispositions = sorted(target for target in routed_targets if target not in seen_disposition_targets)
        if missing_dispositions:
            severity = "error" if status in {"integration_package_ready", "partial_package_ready"} else "warning"
            add_issue(issues, severity, "missing_branch_disposition", "branch_dispositions", f"routed items should have per-branch disposition: {', '.join(missing_dispositions[:20])}")

    cleanup_items = data.get("cleanup_candidates", [])
    if isinstance(cleanup_items, list):
        for index, item in enumerate(cleanup_items):
            path = f"cleanup_candidates[{index}]"
            if not isinstance(item, dict):
                add_issue(issues, "error", "cleanup_candidate_not_object", path, "cleanup candidate must be an object with evidence")
                continue
            if not has_value(item.get("branch")) and not has_value(item.get("pr")):
                add_issue(issues, "error", "cleanup_candidate_missing_target", path, "cleanup candidate needs branch or pr")
            if not has_value(item.get("reason")):
                add_issue(issues, "error", "cleanup_candidate_missing_reason", f"{path}.reason", "cleanup candidate needs reason")
            if not has_value(item.get("evidence")):
                add_issue(issues, "error", "cleanup_candidate_missing_evidence", f"{path}.evidence", "cleanup candidate needs evidence")
            if item.get("delete_remote_branch") is True or item.get("close_pr") is True:
                add_issue(issues, "error", "cleanup_candidate_performs_deletion", path, "Integrator may only mark cleanup candidates, not approve deletion directly")

    if has_value(data.get("base_sha")) and len(str(data["base_sha"])) < 7:
        add_issue(issues, "warning", "short_base_sha", "base_sha", "base_sha should be a real commit SHA")

    if has_value(data.get("package_branch")) and not any(str(data["package_branch"]).startswith(prefix) for prefix in INTEGRATOR_BRANCH_PREFIXES):
        add_issue(issues, "warning", "non_integrator_branch", "package_branch", "package_branch should normally use AiStudio/Agent/integrator/ prefix")

    for field in FINALIZER_FORBIDDEN_FIELDS:
        if field in data and has_value(data.get(field)):
            add_issue(issues, "error", "finalizer_boundary_violation", field, f"Integrator handoff must not record finalizer field {field}")

    if not has_value(data.get("finalizer_authority_required")):
        add_issue(issues, "error", "missing_finalizer_authority", "finalizer_authority_required", "handoff must name authority required for Finalizer")

    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Auto Integrator handoff JSON.")
    parser.add_argument("--handoff", required=True, help="Path to integration handoff JSON.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    parser.add_argument("--warnings-as-errors", action="store_true", help="Return non-zero when warnings are present.")
    args = parser.parse_args()

    handoff_path = Path(args.handoff).resolve()
    if handoff_path.exists():
        data = load_json(handoff_path)
        issues = validate(data)
    else:
        issues = [{
            "severity": "error",
            "code": "handoff_missing",
            "path": str(handoff_path),
            "message": "integration handoff file is missing; Auto Finalizer has nothing to consume",
        }]
    errors = sum(1 for issue in issues if issue["severity"] == "error")
    warnings = sum(1 for issue in issues if issue["severity"] == "warning")
    report = {
        "handoff": str(handoff_path),
        "errors": errors,
        "warnings": warnings,
        "issues": issues,
    }

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"handoff: {handoff_path}")
        print(f"errors: {errors}")
        print(f"warnings: {warnings}")
        for issue in issues:
            print(f"{issue['severity'].upper()} {issue['code']} {issue['path']}: {issue['message']}")

    if errors or (args.warnings_as_errors and warnings):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
