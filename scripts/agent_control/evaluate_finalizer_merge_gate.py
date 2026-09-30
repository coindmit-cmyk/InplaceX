#!/usr/bin/env python3
"""Evaluate whether an Auto Integrator handoff can be auto-merged by Auto Finalizer."""

from __future__ import annotations

import argparse
import copy
import importlib
import json
from pathlib import Path
from typing import Any

import documentation_impact_checker
import project_version_gate
from validate_integration_handoff import validate as validate_handoff
from worker_result_contract_validator import DOCUMENTATION_IMPACT_VALUES, documentation_impact_from_report


INTEGRATOR_BRANCH_PREFIXES = (
    "AiStudio/Agent/integrator/",
    "integrator/",
)

DOCUMENTATION_IMPACT_BLOCKING = {"blocked_missing_docs"}


def _normalize_branch_role(base_branch: str) -> str:
    value = str(base_branch or "develop").strip()
    return value.removeprefix("origin/") if value.startswith("origin/") else value


def _normalize_task_id(value: Any) -> str:
    return str(value or "").strip()


def _to_task_set(values: Any) -> set[str]:
    return {str(value).strip() for value in values or [] if str(value).strip()}


def _task_map(project_root: Path) -> dict[str, dict[str, Any]]:
    path = project_root / "AiStudio" / "Task_manager" / "task_queue.json"
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    tasks = payload.get("tasks") if isinstance(payload, dict) else None
    if not isinstance(tasks, list):
        return {}
    rows: dict[str, dict[str, Any]] = {}
    for row in tasks:
        if not isinstance(row, dict):
            continue
        task_id = _normalize_task_id(row.get("id")).upper()
        if task_id:
            rows[task_id] = row
    return rows


def _task_requires_documentation_and_version(task: dict[str, Any] | None) -> bool:
    if not task:
        return False
    if task.get("documentation_impact_required"):
        return True
    contract = task.get("output_contract")
    if isinstance(contract, dict) and contract.get("documentation_impact_required"):
        return True
    return False


def _task_disposition(branch_dispositions: Any, task_id: str) -> dict[str, Any] | None:
    if not isinstance(branch_dispositions, list):
        return None
    normalized = _normalize_task_id(task_id).upper()
    for item in branch_dispositions:
        if not isinstance(item, dict):
            continue
        if _normalize_task_id(item.get("task_id")).upper() == normalized:
            return item
    return None


def _candidate_report_paths(task: dict[str, Any] | None, disposition: dict[str, Any] | None) -> list[str]:
    paths: list[str] = []
    for source in (disposition, task):
        if not isinstance(source, dict):
            continue
        report = _normalize_task_id(source.get("worker_report"))
        if report:
            paths.append(report)
        for imported in source.get("imported_worker_reports") or []:
            if isinstance(imported, str) and imported.strip():
                paths.append(imported.strip())
    return paths


def _documentation_version_gate_issues(
    project_root: Path,
    base_branch: str,
    task: dict[str, Any] | None,
    disposition: dict[str, Any] | None,
) -> list[str]:
    if not _task_requires_documentation_and_version(task):
        return []

    reasons: list[str] = []
    changed_paths = {
        _normalize_task_id(path)
        for path in (disposition.get("changed_paths") or [])
        if _normalize_task_id(path)
    } if isinstance(disposition, dict) else set()
    if task:
        changed_paths.update(_normalize_task_id(path) for path in (task.get("changed_paths") or []) if _normalize_task_id(path))
    changed_paths = sorted(changed_paths)
    report_payload = None
    report_text = ""
    report_path = None
    for path_value in _candidate_report_paths(task, disposition):
        candidate = project_root / path_value
        if candidate.exists():
            report_path = candidate
            try:
                report_text = candidate.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                report_text = ""
            try:
                report_payload = json.loads(report_text)
            except json.JSONDecodeError:
                report_payload = None
            break

    if report_path is None:
        reasons.append("documentation impact report missing for documentation-gated task")
    else:
        impact = documentation_impact_from_report(report_payload, report_text)
        if not impact:
            reasons.append("documentation_impact missing")
        elif impact not in DOCUMENTATION_IMPACT_VALUES:
            reasons.append(f"documentation_impact invalid: {impact}")
        elif impact in DOCUMENTATION_IMPACT_BLOCKING:
            reasons.append("documentation_impact is blocked_missing_docs")

    doc_report = documentation_impact_checker.build_report(project_root, changed_paths=changed_paths)
    if doc_report.get("release_blocking"):
        for item in doc_report.get("errors", []):
            reasons.append(f"{item.get('code')}: {item.get('message')}")

    version_report = project_version_gate.validate_version(
        project_root,
        expected_branch_role=_normalize_branch_role(base_branch),
        require=True,
    )
    for item in version_report.get("errors", []):
        reasons.append(f"{item.get('message')}")
    if version_report.get("version") and isinstance(version_report["version"], dict):
        version = version_report["version"]
        for field in ("project_index", "documentation_manifest"):
            path_value = _normalize_task_id(version.get(field))
            if not path_value:
                reasons.append(f"PROJECT_VERSION.json missing required field {field}")
                continue
            if not (project_root / path_value).is_file():
                reasons.append(f"{field} file is missing: {path_value}")

    return reasons


def run_validate(data: dict[str, Any]) -> list[dict[str, str]]:
    return validate_handoff(data)


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return len(value) > 0
    if isinstance(value, dict):
        return len(value) > 0
    return True


def to_task_set(values: Any) -> set[str]:
    return {str(value) for value in values or [] if value}


def migration_policy_gate_issues(data: dict[str, Any]) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []
    summary = data.get("migration_policy_summary") if isinstance(data.get("migration_policy_summary"), dict) else {}
    for task_id in summary.get("missing_policy_task_ids") or []:
        issues.append({"task": str(task_id), "reason": "migration-sensitive item is missing migration_compatibility_policy"})
    for item in data.get("branch_dispositions") or []:
        if not isinstance(item, dict) or item.get("migration_sensitive") is not True:
            continue
        policy = item.get("migration_compatibility_policy")
        if not isinstance(policy, dict) or not policy:
            task_id = str(item.get("task_id") or item.get("branch") or "migration_policy")
            issues.append({"task": task_id, "reason": "migration-sensitive disposition is missing migration_compatibility_policy"})
    return issues


def _integration_contract(data: dict[str, Any]) -> tuple[dict[str, Any] | None, str | None, list[dict[str, str]]]:
    """Return the opt-in Result Integration contract without repairing it."""
    issues: list[dict[str, str]] = []
    fields = ("result_integration", "result_integration_contract")
    present = [field for field in fields if field in data]
    if not present:
        return None, None, issues
    if len(present) > 1 and data.get(present[0]) != data.get(present[1]):
        issues.append({
            "task": "result_integration",
            "reason": "result integration contract fields disagree",
        })
        return None, present[0], issues
    field = present[0]
    value = data.get(field)
    if not isinstance(value, dict):
        issues.append({
            "task": field,
            "reason": "result integration contract must be an object",
        })
        return None, field, issues
    return value, field, issues


def result_integration_gate_issues(data: dict[str, Any]) -> list[dict[str, str]]:
    """Verify an opted-in Result Integration contract exactly at the finalizer boundary."""
    contract, field, issues = _integration_contract(data)
    if contract is None:
        return issues

    try:
        execution_contract_validator = importlib.import_module("execution_contract_validator")
        validate_execution_contract = execution_contract_validator.validate
        canonical_digest = execution_contract_validator.canonical_digest
    except (ImportError, AttributeError) as exc:
        issues.append({
            "task": field or "result_integration",
            "reason": f"result integration validator unavailable: {exc}",
        })
        return issues

    semantic = validate_execution_contract(contract, kind="result_integration")
    for item in semantic.get("errors", []):
        issues.append({
            "task": field or "result_integration",
            "reason": f"{item.get('code')}: {item.get('path')}: {item.get('message')}",
        })
    if semantic.get("valid") is not True:
        return issues

    status = str(contract.get("status") or "")
    if status not in {"ready_for_finalizer", "accepted_with_residual_risk"}:
        issues.append({
            "task": field or "result_integration",
            "reason": f"result integration status {status} is not finalizer-ready",
        })

    synthesis = contract.get("synthesis")
    handoff = contract.get("finalizer_handoff")
    if not isinstance(synthesis, dict) or not isinstance(handoff, dict):
        return issues

    synthesis_seed = copy.deepcopy(synthesis)
    declared_synthesis_digest = synthesis_seed.pop("synthesis_digest", None)
    expected_synthesis_digest = canonical_digest(synthesis_seed)
    if declared_synthesis_digest != expected_synthesis_digest:
        issues.append({
            "task": f"{field}.synthesis.synthesis_digest",
            "reason": "synthesis digest does not match the exact synthesis payload",
        })

    integration_seed = {
        key: value
        for key, value in contract.items()
        if key not in {"integration_digest", "finalizer_handoff"}
    }
    if contract.get("integration_digest") != canonical_digest(integration_seed):
        issues.append({
            "task": f"{field}.integration_digest",
            "reason": "integration digest does not match the exact integration contract",
        })

    blockers = contract.get("blockers")
    if isinstance(blockers, list) and blockers:
        issues.append({
            "task": f"{field}.blockers",
            "reason": "unresolved integration blockers prevent Finalizer readiness",
        })
    for index, conflict in enumerate(contract.get("conflicts") or []):
        if not isinstance(conflict, dict):
            continue
        resolution = conflict.get("resolution")
        if conflict.get("blocking") is True and (
            not isinstance(resolution, dict) or resolution.get("status") != "resolved"
        ):
            issues.append({
                "task": f"{field}.conflicts[{index}]",
                "reason": "unresolved blocking conflict prevents Finalizer readiness",
            })

    for name in (
        "required_accounting_complete",
        "blocking_conflicts_resolved",
        "required_checks_passed",
    ):
        if handoff.get(name) is not True:
            issues.append({
                "task": f"{field}.finalizer_handoff.{name}",
                "reason": "exact Finalizer readiness evidence is incomplete",
            })
    if handoff.get("finalizer_may_modify") is not False:
        issues.append({
            "task": f"{field}.finalizer_handoff.finalizer_may_modify",
            "reason": "Finalizer must not repair or modify synthesis",
        })
    if handoff.get("merge_authority_granted") is not False:
        issues.append({
            "task": f"{field}.finalizer_handoff.merge_authority_granted",
            "reason": "Result Integration cannot grant merge authority",
        })
    return issues


def evaluate_gate(data: dict[str, Any], base_branch: str) -> tuple[str, list[dict[str, str]]]:
    issues: list[dict[str, str]] = []
    status = str(data.get("integration_status") or "")
    ready = to_task_set(data.get("ready_to_finalize"))
    needs_human = to_task_set(data.get("needs_human"))
    blocked = to_task_set(data.get("blocked"))
    needs_rework = to_task_set(data.get("needs_rework"))
    needs_worker_fix = to_task_set(data.get("needs_worker_fix"))
    needs_dispatcher = to_task_set(data.get("needs_dispatcher"))
    needs_architect = to_task_set(data.get("needs_architect"))
    excluded = to_task_set(data.get("excluded_from_package"))
    branch_dispositions = data.get("branch_dispositions") or []

    routed = needs_human | blocked | needs_rework | needs_worker_fix | needs_dispatcher | needs_architect

    if status not in {"integration_package_ready", "partial_package_ready"}:
        issues.append({"task": "package_status", "reason": f"status {status} is not auto-finalizable"})
        return "needs_human", issues

    if data.get("package_branch") is None or not any(str(data.get("package_branch")).startswith(prefix) for prefix in INTEGRATOR_BRANCH_PREFIXES):
        issues.append({"task": "package_branch", "reason": "package_branch must be under AiStudio/Agent/integrator/ for auto merge"})

    if str(data.get("base_branch") or "") != base_branch:
        issues.append({"task": "base_branch", "reason": f"base branch must be {base_branch} for auto-merge policy"})

    if not ready:
        issues.append({"task": "ready_to_finalize", "reason": "no ready tasks in package"})

    ready_disposition_tasks: set[str] = set()
    if isinstance(branch_dispositions, list):
        for item in branch_dispositions:
            if not isinstance(item, dict):
                continue
            if str(item.get("disposition") or "") != "ready_to_finalize":
                continue
            task_id = str(item.get("task_id") or "").strip()
            if task_id:
                ready_disposition_tasks.add(task_id)
    for task_id in sorted(ready - ready_disposition_tasks):
        issues.append({"task": task_id, "reason": "ready_to_finalize item has no matching ready branch disposition"})
    for task_id in sorted(ready_disposition_tasks - ready):
        issues.append({"task": task_id, "reason": "ready branch disposition is not listed in ready_to_finalize"})

    for task_id in to_task_set(data.get("ready_to_finalize")):
        if task_id in routed:
            issues.append({"task": task_id, "reason": "ready task also routed to non-final owner route"})

    for task_id in (routed - excluded):
        if task_id and task_id not in to_task_set(data.get("excluded_from_package")):
            issues.append({"task": task_id, "reason": "non-ready tasks must be explicitly excluded"})

    if has_value(data.get("merge_conflicts")):
        for item in data.get("merge_conflicts", []):
            issues.append({"task": "merge_conflicts", "reason": str(item)})

    check_results = data.get("check_results", [])
    required_passed = data.get("required_checks_passed")
    checks = data.get("checks", [])
    if not has_value(checks):
        issues.append({"task": "checks", "reason": "checks list is empty"})
    if required_passed is False:
        issues.append({"task": "required_checks_passed", "reason": "required checks failed"})
    if isinstance(check_results, list):
        for item in check_results:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "").strip()
            state = str(item.get("state") or "").lower()
            if not name:
                continue
            if state in {"failed", "error", "cancelled"}:
                issues.append({"task": name, "reason": f"check '{name}' failed with state {state}"})

    mergeable = data.get("mergeable")
    if mergeable is False:
        issues.append({"task": "mergeable", "reason": "package is marked non-mergeable by integrator evidence"})

    if not has_value(data.get("finalizer_authority_required")):
        issues.append({"task": "finalizer_authority_required", "reason": "missing finalizer authority statement"})

    if has_value(data.get("owner_policy_block")) and str(data.get("owner_policy_block")).lower() not in {"", "false", "0", "no"}:
        issues.append({"task": "owner_policy", "reason": str(data.get("owner_policy_block"))})

    if has_value(data.get("blocked_by")):
        reasons = data.get("blocked_by")
        if isinstance(reasons, list):
            for reason in reasons:
                issues.append({"task": "blocked_by", "reason": str(reason)})
        else:
            issues.append({"task": "blocked_by", "reason": str(reasons)})

    issues.extend(migration_policy_gate_issues(data))
    issues.extend(result_integration_gate_issues(data))
    task_lookup = _task_map(Path(data.get("project_root") or ".").resolve())
    for task_id in sorted(ready):
        normalized = str(task_id or "").strip().upper()
        disposition = _task_disposition(branch_dispositions, task_id)
        task_row = task_lookup.get(normalized)
        for reason in _documentation_version_gate_issues(
                project_root=Path(data.get("project_root") or ".").resolve(),
                base_branch=base_branch,
                task=task_row,
                disposition=disposition,
            ):
                issues.append({"task": task_id, "reason": reason})

    if branch_dispositions:
        if not isinstance(branch_dispositions, list):
            issues.append({"task": "branch_dispositions", "reason": "branch_dispositions must be a list"})
        else:
            for item in branch_dispositions:
                if not isinstance(item, dict):
                    continue
                disposition = str(item.get("disposition") or "")
                if disposition in {"needs_human", "needs_rework", "needs_worker_fix", "needs_dispatcher", "needs_architect"}:
                    task_id = str(item.get("task_id") or "").strip()
                    reason = str(item.get("reason") or "").strip()
                    if task_id and task_id not in routed:
                        issues.append({"task": task_id, "reason": f"disposition {disposition} missing from routed arrays"})
                    if not reason:
                        issues.append({"task": task_id, "reason": f"branch disposition {disposition} has no reason"})

    if issues:
        return "needs_human", issues
    return "auto_merge_to_develop", []


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate auto-finalizer merge gate.")
    parser.add_argument("--handoff", required=True, help="Path to integration handoff JSON.")
    parser.add_argument("--base-branch", default="develop", help="Allowed base branch for auto-merge. Defaults to develop.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    args = parser.parse_args()

    handoff_path = Path(args.handoff).resolve()
    if not handoff_path.exists():
        report = {
            "handoff": str(handoff_path),
            "auto_finalizer_decision": "no_handoff",
            "validation_errors": 1,
            "validation_issues": [{
                "severity": "error",
                "code": "handoff_missing",
                "path": str(handoff_path),
                "message": "integration handoff file is missing; Auto Finalizer has nothing to consume",
            }],
            "gate_issues": [{"task": "handoff", "reason": "integration handoff file is missing"}],
            "needs_human": [],
            "ready_to_finalize": [],
            "excluded_from_package": [],
        }
        if args.json:
            print(json.dumps(report, ensure_ascii=False, indent=2))
        else:
            print(f"handoff file not found: {handoff_path}")
        return 2

    data = json.loads(handoff_path.read_text(encoding="utf-8"))
    validate_issues = run_validate(data)
    hard_errors = [issue for issue in validate_issues if issue.get("severity") == "error"]
    decision, issues = evaluate_gate(data, args.base_branch)

    report = {
        "handoff": str(handoff_path),
        "auto_finalizer_decision": decision,
        "validation_errors": len(hard_errors),
        "validation_issues": hard_errors,
        "gate_issues": issues,
        "needs_human": [],
        "ready_to_finalize": data.get("ready_to_finalize", []),
        "excluded_from_package": data.get("excluded_from_package", []),
    }

    if decision == "needs_human":
        report["needs_human"] = sorted(set(item["task"] for item in issues if item.get("task")))

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"handoff: {handoff_path}")
        print(f"auto_finalizer_decision: {decision}")
        if hard_errors:
            print(f"validation_errors: {len(hard_errors)}")
        if hard_errors:
            for issue in hard_errors:
                print(f"HANDOFF ERROR {issue['code']}: {issue['path']} -> {issue['message']}")
        if issues:
            for issue in issues:
                print(f"GATE {issue['task']}: {issue['reason']}")
        if decision == "auto_merge_to_develop":
            print("SAFE: Auto Finalizer may merge verified package to develop.")
        else:
            print("BLOCKED: Route affected tasks/issues via needs_human.")

    if hard_errors:
        return 3
    if decision == "needs_human":
        return 4
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
