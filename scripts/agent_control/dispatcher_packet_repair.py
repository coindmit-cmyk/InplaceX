#!/usr/bin/env python3
"""Repair incomplete Dispatcher worker packets into packet_schema_version=2."""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import shlex
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


TERMINAL_STATUSES = {"done", "postponed", "failed", "stale_or_superseded", "duplicate_linked"}
REPAIRABLE_STATUSES = {
    "planned",
    "worker_ready",
    "needs_stronger_agent",
    "needs_task_packet",
    "needs_dispatcher_repair",
    "blocked_by_dependency",
}
DEPENDENCY_COMPLETE_STATUSES = {"done", "completed", "finalized", "released", "archived", "owner_approved"}
BLOCKING_DECISIONS = {"needs_architect", "needs_human", "split_into_children", "duplicate_linked", "stale_or_superseded"}
FINALIZE_SCOPE_FAILURE_MARKERS = (
    "outside_allowed",
    "outside allowed",
    "outside allowed_paths",
    "worker_finalize_failed",
    "finalize scope failure",
)
BASE_REQUIRED_FIELDS = (
    "complexity",
    "priority",
    "type",
    "allowed_paths",
    "forbidden_paths",
    "acceptance_criteria",
    "checks",
)
V2_FIELDS = (
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
INFERABLE_DECLARED_PACKET_FIELDS = {
    *BASE_REQUIRED_FIELDS,
    *V2_FIELDS,
    "recommended_agent_or_eligible_worker_profiles",
    "context_docs_or_source_provenance",
    "current_context_verification",
}
CONTROL_PLANE_RECOMMENDED_AGENTS = {"dispatcher", "auto-dispatcher", "auto_dispatcher"}
DISPATCHER_REPAIR_FIELDS = ("repair_request", "missing_packet_fields", "repair_owner", "next_action")
DEFAULT_ARTIFACT_DISCOVERY_WORKER_PROFILE = "auto-worker-5.3"
DEFAULT_FORBIDDEN_PATHS = [
    ".git/**",
    ".env",
    ".env.local",
    ".env.development",
    ".env.production",
    ".env.test",
    "**/__pycache__/**",
    "**/.pytest_cache/**",
    "node_modules/**",
    "old/**",
    "db.sqlite3",
    "*.sqlite3",
    "secrets/**",
]
ARTIFACT_DISCOVERY_WORKER_PROFILE_BY_COMPLEXITY = {
    "S": "auto-worker-5.3-mini",
    "M": "auto-worker-5.3",
    "L": "auto-worker-5.5",
    "XL": "auto-worker-5.5max",
}
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
GENERATED_PACKET_WRITER = "scripts/agent_control/dispatcher_packet_repair.py"
NO_EXISTING_CODE_REFS = "runtime-generated:no-existing-code-refs-declared"
TRUSTED_REQUESTED_SCOPE_VERIFIERS = {
    "scripts/agent_control/run_worker_cycle.py",
    "scripts/agent_control/sync_worker_results.py",
}
SAFE_REQUESTED_SCOPE_PREFIXES = (
    "scripts/agent_control/",
    "schemas/agent-control/",
    "templates/agent-control/",
    "tests/",
    "control/tests/",
    "docs/reports/workers/",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def unique_strings(values: list[Any]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def normalize_forbidden_paths(value: Any) -> list[str]:
    if isinstance(value, str):
        return unique_strings([value])
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return unique_strings(value)
    raise ValueError("forbidden_paths must be a string or a list of strings")


def has_valid_forbidden_paths_contract(value: Any) -> bool:
    try:
        return bool(normalize_forbidden_paths(value))
    except ValueError:
        return False


def correlated_test_globs(allowed_paths: list[str]) -> list[str]:
    globs: list[str] = []
    for value in allowed_paths:
        normalized = value.strip().replace("\\", "/")
        if not normalized.startswith("scripts/") or not normalized.endswith(".py"):
            continue
        module_pattern = Path(normalized).name[:-3]
        if not module_pattern:
            continue
        globs.append(f"tests/test_{module_pattern}.py")
    return unique_strings(globs)


def pytest_paths_from_packet(task: dict[str, Any]) -> list[str]:
    paths: list[str] = []
    values = [*as_list(task.get("script_actions")), *as_list(task.get("checks"))]
    for value in values:
        raw = value.get("command") if isinstance(value, dict) else value
        command = str(raw or "").strip().strip("`")
        try:
            tokens = shlex.split(command)
        except ValueError:
            continue
        if not tokens:
            continue
        executable = tokens[0].replace("\\", "/").rsplit("/", 1)[-1].lower()
        pytest_index: int | None = None
        if executable in {"pytest", "py.test"}:
            pytest_index = 0
        elif re.fullmatch(r"(?:python|python3|py)(?:\d+(?:\.\d+)*)?", executable):
            for index in range(1, len(tokens) - 1):
                if tokens[index] == "-m" and tokens[index + 1].lower() == "pytest":
                    pytest_index = index + 1
                    break
        if pytest_index is None:
            continue
        for token in tokens[pytest_index + 1 :]:
            candidate = token.split("::", 1)[0].replace("\\", "/")
            parts = candidate.split("/")
            if (
                candidate.endswith(".py")
                and candidate.startswith(("tests/", "test/"))
                and all(part not in {"", ".", ".."} for part in parts)
            ):
                paths.append(candidate)
    return unique_strings(paths)


def is_matching_test_scope_note(value: str) -> bool:
    normalized = value.strip().lower()
    return (
        "/" not in normalized
        and "\\" not in normalized
        and "matching" in normalized
        and re.search(r"\btests?\b", normalized) is not None
    )


def is_repo_relative_scope_path(value: str) -> bool:
    normalized = value.strip().replace("\\", "/")
    if (
        not normalized
        or normalized.startswith("/")
        or re.match(r"^[A-Za-z]:/", normalized)
    ):
        return False
    return all(part not in {"", ".", ".."} for part in normalized.split("/"))


def safe_verified_scope_paths(task: dict[str, Any], values: Any) -> list[str]:
    forbidden = unique_strings(as_list(task.get("forbidden_paths")))
    verified: list[str] = []
    for value in unique_strings(as_list(values)):
        normalized = value.replace("\\", "/")
        if (
            not normalized.startswith(SAFE_REQUESTED_SCOPE_PREFIXES)
            or not is_repo_relative_scope_path(normalized)
            or any(character in normalized for character in "*?[]")
            or any(fnmatch.fnmatchcase(normalized, pattern.replace("\\", "/")) for pattern in forbidden)
        ):
            continue
        verified.append(normalized)
    return unique_strings(verified)


def exact_repository_ref(value: Any) -> str | None:
    normalized = str(value or "").replace("\\", "/").strip()
    if (
        not normalized
        or normalized.startswith(("runtime-generated:", "http://", "https://", "file://", "/", "../", "~/"))
        or re.match(r"^[A-Za-z]:/", normalized)
        or any(marker in normalized for marker in ("*", "?", "[", "]", "{", "}"))
        or any(part in {"", ".", ".."} for part in normalized.split("/"))
    ):
        return None
    return normalized


def exact_repository_refs(values: Any) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in as_list(values):
        if isinstance(value, dict):
            value = value.get("path")
        ref = exact_repository_ref(value)
        if ref is None or ref in seen:
            continue
        seen.add(ref)
        result.append(ref)
    return result


def build_input_refs(task: dict[str, Any], normalized_task: dict[str, Any] | None = None) -> dict[str, Any]:
    normalized = normalized_task or task
    existing = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    declaration_source = str(existing.get("declaration_source") or "").strip().lower()
    explicit = declaration_source == "explicit"
    required_paths = list(as_list(existing.get("allowed_paths"))) if explicit else []
    return {
        **existing,
        "base_branch": existing.get("base_branch") or normalized.get("base_branch") or "develop",
        "base_ref": existing.get("base_ref") or normalized.get("base_ref") or "origin/develop",
        "allowed_paths": required_paths,
        "forbidden_paths": list(normalized.get("forbidden_paths") or existing.get("forbidden_paths") or []),
        "context_docs": list(normalized.get("context_docs") or existing.get("context_docs") or []),
        "source_file": normalized.get("source_file") or existing.get("source_file"),
        "worker_source_branch": (
            existing.get("worker_source_branch")
            or normalized.get("branch")
            or normalized.get("github_branch")
        ),
        "declaration_source": "explicit" if explicit else "none",
    }


def verified_requested_allowed_paths(task: dict[str, Any]) -> list[str]:
    evidence = task.get("worker_check_evidence")
    if not isinstance(evidence, dict):
        return []
    verifier = str(task.get("requested_allowed_paths_verified_by") or "")
    if (
        verifier not in TRUSTED_REQUESTED_SCOPE_VERIFIERS
        or str(evidence.get("requested_allowed_paths_verified_by") or "") != verifier
        or str(task.get("dispatcher_repair_kind") or "") != "allowed_paths"
        or str(evidence.get("repair_kind") or "") != "allowed_paths"
    ):
        return []
    requested = unique_strings(as_list(task.get("requested_allowed_paths")))
    if requested != unique_strings(as_list(evidence.get("requested_allowed_paths"))):
        return []
    missing = {str(value) for value in as_list(task.get("missing_packet_fields"))}
    if "allowed_paths" not in missing:
        return []
    return safe_verified_scope_paths(task, requested)


def verified_applied_scope_repair_paths(task: dict[str, Any]) -> list[str]:
    repair = task.get("dispatcher_scope_repair")
    if not isinstance(repair, dict):
        return []
    if (
        repair.get("applied_by") != "scripts/agent_control/dispatcher_packet_repair.py"
        or repair.get("source_verifier") not in TRUSTED_REQUESTED_SCOPE_VERIFIERS
        or not has_value(repair.get("applied_at"))
    ):
        return []
    return safe_verified_scope_paths(task, repair.get("requested_allowed_paths"))


def derived_allowed_paths(task: dict[str, Any], allowed_paths: list[str]) -> list[str]:
    """Keep deterministic generated artifacts writable with their source records."""
    derived: list[str] = []
    if any(
        path == "agent-core/.agent/roles/**"
        or path.startswith("agent-core/.agent/roles/")
        for path in allowed_paths
    ):
        derived.append("apps/inventory/capability_registry.json")

    forbidden = unique_strings(as_list(task.get("forbidden_paths")))
    return [
        path
        for path in derived
        if not any(fnmatch.fnmatchcase(path, pattern.replace("\\", "/")) for pattern in forbidden)
    ]


def normalize_allowed_path_contract(task: dict[str, Any]) -> tuple[list[str], list[str]]:
    raw_paths = unique_strings(as_list(task.get("allowed_paths")))
    normalized: list[str] = []
    notes: list[str] = []
    test_globs = unique_strings([*correlated_test_globs(raw_paths), *pytest_paths_from_packet(task)])
    for value in raw_paths:
        if is_matching_test_scope_note(value):
            normalized.extend(test_globs)
            notes.append(value)
            continue
        candidate = value.replace("\\", "/")
        if is_repo_relative_scope_path(candidate):
            normalized.append(candidate)
    requested_paths = verified_requested_allowed_paths(task)
    if requested_paths:
        normalized.extend(requested_paths)
        output_contract = task.get("output_contract")
        if isinstance(output_contract, dict) and output_contract.get("worker_report_required") is True:
            normalized.append("docs/reports/workers/**")
    normalized.extend(derived_allowed_paths(task, normalized))
    return unique_strings(normalized), unique_strings(notes)


def allowed_path_contract_needs_repair(task: dict[str, Any]) -> bool:
    normalized, _notes = normalize_allowed_path_contract(task)
    return normalized != unique_strings(as_list(task.get("allowed_paths")))


def script_action_command(value: Any) -> str:
    raw = value.get("command") if isinstance(value, dict) else value
    text = str(raw or "").strip()
    if len(text) >= 2 and text.startswith("`") and text.endswith("`"):
        text = text[1:-1].strip()
    if text.startswith("$ "):
        text = text[2:].strip()
    return text


def executable_check_command(value: Any) -> str | None:
    command = script_action_command(value)
    if not command or "\n" in command or "\r" in command:
        return None
    match = re.match(r"^(?P<program>[^\s]+)(?:\s|$)", command)
    if not match:
        return None
    program = match.group("program").strip("\"'").replace("\\", "/")
    basename = program.rsplit("/", 1)[-1].lower()
    if basename in EXECUTABLE_CHECK_COMMANDS:
        return command
    if re.fullmatch(r"python(?:\d+(?:\.\d+)*)?", basename):
        return command
    if Path(basename).suffix.lower() in EXECUTABLE_SCRIPT_SUFFIXES and (
        "/" in program or basename.startswith(".")
    ):
        return command
    return None


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def completed_task_ids(tasks: list[dict[str, Any]]) -> set[str]:
    return {
        value
        for task in tasks
        if str(task.get("status") or "").strip().lower() in DEPENDENCY_COMPLETE_STATUSES
        for value in [task_id(task)]
        if value
    }


def unresolved_dependencies(task: dict[str, Any], completed_ids: set[str]) -> list[str]:
    return [
        dependency
        for dependency in unique_strings(as_list(task.get("depends_on")))
        if dependency not in completed_ids
    ]


def is_dependency_repair_candidate(task: dict[str, Any]) -> bool:
    status = str(task.get("status") or "").strip()
    decision = str(task.get("dispatcher_decision") or "").strip()
    return status in REPAIRABLE_STATUSES and decision not in BLOCKING_DECISIONS


def mark_blocked_by_dependency(
    task: dict[str, Any],
    unresolved: list[str],
    repaired_at: str,
) -> dict[str, Any]:
    updated = deepcopy(task)
    dependencies = unique_strings(as_list(updated.get("depends_on")))
    resolved = [dependency for dependency in dependencies if dependency not in unresolved]
    reason = "worker packet waits for unresolved dependencies: " + ", ".join(unresolved)
    updated.update({
        "status": "blocked_by_dependency",
        "packet_status": "blocked_by_dependency",
        "normalization_status": "blocked_by_dependency",
        "dispatcher_decision": "blocked_by_dependency",
        "dispatcher_decision_reason": reason,
        "worker_ready": False,
        "next_owner": "Dispatcher",
        "next_role": "auto_dispatcher",
        "next_action": "Wait for the listed dependencies to reach an accepted terminal state, then rerun dispatcher_packet_repair.py.",
        "not_worker_ready_reason": reason,
        "blocked_by": unresolved,
        "resolved_dependencies": resolved,
        "dependency_state_checked_at": repaired_at,
        "dependency_state_checked_by": GENERATED_PACKET_WRITER,
    })
    return updated


def release_resolved_dependency_block(task: dict[str, Any], repaired_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    updated["status"] = "needs_dispatcher_repair"
    updated["packet_status"] = "needs_dispatcher_repair"
    updated["normalization_status"] = "needs_dispatcher_repair"
    updated["dispatcher_decision"] = "needs_dispatcher_repair"
    updated["dispatcher_decision_reason"] = "dependencies are resolved; Dispatcher must rebuild or validate Worker Packet v2"
    updated["worker_ready"] = False
    updated["next_owner"] = "Dispatcher"
    updated["next_role"] = "auto_dispatcher"
    updated["resolved_dependencies"] = unique_strings(as_list(updated.get("depends_on")))
    updated["dependency_state_checked_at"] = repaired_at
    updated["dependency_state_checked_by"] = GENERATED_PACKET_WRITER
    updated.pop("blocked_by", None)
    updated.pop("next_action", None)
    updated.pop("not_worker_ready_reason", None)
    return updated


def is_integration_only_task(task: dict[str, Any]) -> bool:
    return str(task.get("type") or "").strip() == "repository_hygiene_integration"


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


def worker_report_path(task: dict[str, Any]) -> str:
    return f"docs/reports/workers/WORKER_RESULT_{task_id(task)}_<timestamp>.md"


def legacy_task_manager_worker_report_path(value: Any) -> bool:
    normalized = str(value or "").replace("\\", "/").strip().lower()
    return normalized.startswith("aistudio/task_manager/reports/")


def missing_base_fields(task: dict[str, Any]) -> list[str]:
    missing = [field for field in BASE_REQUIRED_FIELDS if not has_value(task.get(field))]
    if has_value(task.get("forbidden_paths")) and not has_valid_forbidden_paths_contract(task.get("forbidden_paths")):
        missing.append("forbidden_paths_contract")
    if not has_worker_execution_route(task) and not can_infer_worker_profile(task):
        missing.append("recommended_agent_or_eligible_worker_profiles")
    if not (has_value(task.get("context_docs")) or has_value(task.get("source_file")) or has_value(task.get("provenance"))):
        missing.append("context_docs_or_source_provenance")
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        missing.append("current_context_verification")
    return missing


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def missing_v2_fields(task: dict[str, Any]) -> list[str]:
    return [field for field in V2_FIELDS if not has_value(task.get(field))]


def is_missing_base_branch_migration_candidate(task: dict[str, Any]) -> bool:
    return (
        int(task.get("packet_schema_version") or 1) >= 2
        and str(task.get("status") or "") in REPAIRABLE_STATUSES
        and task.get("worker_ready") is True
        and missing_v2_fields(task) == ["base_branch"]
    )


def repair_missing_base_branch(task: dict[str, Any]) -> dict[str, Any]:
    updated = deepcopy(task)
    input_refs = updated.get("input_refs") if isinstance(updated.get("input_refs"), dict) else {}
    updated["base_branch"] = updated.get("base_branch") or input_refs.get("base_branch") or "develop"
    return updated


def can_infer_worker_profile(task: dict[str, Any]) -> bool:
    task_type = str(task.get("type") or "").strip()
    return (
        task_type == "artifact_discovery_followup"
        or (task_type == "design-handoff-intake" and has_value(task.get("source_item_id")))
    )


def inferred_worker_profile(task: dict[str, Any]) -> str:
    complexity = str(task.get("complexity") or "").strip().upper()
    return ARTIFACT_DISCOVERY_WORKER_PROFILE_BY_COMPLEXITY.get(complexity, DEFAULT_ARTIFACT_DISCOVERY_WORKER_PROFILE)


def has_unresolved_finalize_scope_failure(task: dict[str, Any]) -> bool:
    missing = {str(item).strip() for item in as_list(task.get("missing_packet_fields")) if str(item).strip()}
    values = [
        task.get("repair_request"),
        task.get("not_worker_ready_reason"),
        task.get("status_reason"),
        task.get("dispatcher_decision_reason"),
        task.get("next_action"),
    ]
    for claim in as_list(task.get("abandoned_claims")):
        if isinstance(claim, dict):
            values.extend([claim.get("reason"), claim.get("status_reason"), claim.get("release_reason")])
    text = " ".join(str(value or "") for value in values).lower()
    if "allowed_paths" not in missing and "allowed paths" not in text and "outside_allowed" not in text:
        return False
    return any(marker in text for marker in FINALIZE_SCOPE_FAILURE_MARKERS)


def is_project_rules_review_only(task: dict[str, Any]) -> bool:
    task_type = str(task.get("type") or "").strip().lower()
    category = str(task.get("category") or "").strip().lower()
    import_source = str(task.get("import_source") or "").strip()
    provenance = task.get("provenance") if isinstance(task.get("provenance"), dict) else {}
    provenance_source = str(provenance.get("import_source") or "").strip()
    return (
        task_type.startswith("automation/project_rules_review/")
        and category in {"source_truth", "project_memory", "sensitive_risk"}
        and (
            import_source == "project_rules_remediation_review_import_gate"
            or provenance_source == "project_rules_remediation_review_import_gate"
        )
    )


def is_aistd2_intake_row(task: dict[str, Any]) -> bool:
    tid = str(task.get("id") or task.get("task_id") or "").strip()
    labels = {str(item) for item in as_list(task.get("labels"))}
    return (
        tid.startswith("INTAKE-AISTD2-")
        or str(task.get("type") or "") == "normalization/aistd2-intake"
        or "aistd2" in labels
    )


def is_scoped_dispatcher_packet(task: dict[str, Any]) -> bool:
    reason = str(task.get("dispatcher_decision_reason") or "")
    return reason.startswith("Dispatcher scoped ") or has_value(task.get("dispatcher_scope_report"))


def is_unscoped_aistd2_intake(task: dict[str, Any]) -> bool:
    return is_aistd2_intake_row(task) and not is_scoped_dispatcher_packet(task)


def is_design_handoff_intake_parent(task: dict[str, Any]) -> bool:
    return (
        str(task.get("type") or "").strip().lower() == "design-handoff-intake"
        and str(task.get("recommended_agent") or "").strip().lower() == "dispatcher"
        and not has_value(task.get("source_item_id"))
    )


def is_design_handoff_dependency_blocked(task: dict[str, Any]) -> bool:
    return (
        str(task.get("type") or "").strip().lower() == "design-handoff-intake"
        and has_value(task.get("source_item_id"))
        and has_value(task.get("blocked_by"))
    )


def is_sensitive_risk_review_only(task: dict[str, Any]) -> bool:
    task_type = str(task.get("type") or "")
    values = [
        str(task.get("category") or ""),
        str(task.get("source_finding_category") or ""),
        str(task.get("source_summary") or ""),
        *[str(item) for item in as_list(task.get("labels"))],
    ]
    if task_type.strip().lower() != "clean-rebuild":
        values.extend([task_type, str(task.get("title") or "")])
    text = " ".join(values).lower().replace("-", "_")
    return (
        ("possible_secret_pattern" in text or "sensitive_risk" in text or "sensitive_risk" in text)
        and not is_scoped_dispatcher_packet(task)
    )


def can_repair_to_v2(task: dict[str, Any]) -> bool:
    status = str(task.get("status") or "")
    decision = str(task.get("dispatcher_decision") or "")
    if status not in REPAIRABLE_STATUSES:
        return False
    if status in TERMINAL_STATUSES or decision in BLOCKING_DECISIONS:
        return False
    if has_value(task.get("blocked_by")) or has_value(task.get("split_into")):
        return False
    if has_unresolved_finalize_scope_failure(task):
        return False
    if is_project_rules_review_only(task):
        return False
    if is_unscoped_aistd2_intake(task):
        return False
    if is_design_handoff_intake_parent(task):
        return False
    if is_sensitive_risk_review_only(task):
        return False
    actual_missing_fields = {
        *missing_base_fields(task),
        *missing_v2_fields(task),
    }
    declared_missing_fields = {
        str(field).strip()
        for field in as_list(task.get("missing_packet_fields"))
        if str(field).strip()
    }
    unresolved_declared_fields = declared_missing_fields - actual_missing_fields
    unsafe_unresolved_fields = unresolved_declared_fields - INFERABLE_DECLARED_PACKET_FIELDS
    if unsafe_unresolved_fields:
        completed_scope_repair = (
            unsafe_unresolved_fields == {"dispatcher_blocker_resolution"}
            and bool(verified_applied_scope_repair_paths(task))
        )
        if not completed_scope_repair:
            return False
    non_inferable_base_fields = [
        field
        for field in missing_base_fields(task)
        if field != "forbidden_paths"
    ]
    return not non_inferable_base_fields


def build_doc_refs(task: dict[str, Any]) -> list[dict[str, str]]:
    refs: list[dict[str, str]] = []
    for path in unique_strings([
        *as_list(task.get("context_docs")),
        *as_list(task.get("input_docs")),
        *as_list(task.get("source_refs")),
        task.get("source_file"),
    ]):
        refs.append({"path": path, "purpose": "required context"})
    provenance = task.get("provenance")
    provenance_items = provenance if isinstance(provenance, list) else [provenance]
    for item in provenance_items:
        if not isinstance(item, dict):
            continue
        for key in ("source_file", "architecture_doc", "decision_doc"):
            value = item.get(key)
            if value:
                refs.append({"path": str(value), "purpose": f"provenance:{key}"})
    unique: list[dict[str, str]] = []
    seen: set[str] = set()
    for ref in refs:
        if ref["path"] in seen:
            continue
        seen.add(ref["path"])
        unique.append(ref)
    return unique


def build_task_refs(task: dict[str, Any]) -> list[dict[str, str]]:
    refs: list[dict[str, str]] = []
    for value in unique_strings([
        task.get("id"),
        task.get("task_id"),
        task.get("canonical_task_id"),
        task.get("canonical_target_id"),
        task.get("derived_from"),
        *as_list(task.get("related_tasks")),
        *as_list(task.get("blocked_by")),
        *as_list(task.get("depends_on")),
    ]):
        refs.append({"id": value, "purpose": "task context"})
    return refs


def build_code_refs(task: dict[str, Any]) -> list[str]:
    input_refs = task.get("input_refs") if isinstance(task.get("input_refs"), dict) else {}
    inventory = task.get("context_inventory") if isinstance(task.get("context_inventory"), dict) else {}
    prior_worker_outputs = (
        [
            *exact_repository_refs(task.get("changed_paths")),
            *exact_repository_refs(task.get("integration_changed_paths")),
        ]
        if str(task.get("status") or "") == "needs_worker_fix"
        else []
    )
    return unique_strings([
        *exact_repository_refs(task.get("code_refs")),
        *exact_repository_refs(input_refs.get("allowed_paths")),
        *exact_repository_refs(inventory.get("code_refs")),
        *exact_repository_refs(task.get("allowed_paths")),
        *prior_worker_outputs,
    ])


def is_migration_sensitive(task: dict[str, Any], code_refs: list[str] | None = None) -> bool:
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
        *[str(path) for path in (code_refs or [])],
    ]
    text = " ".join(values).lower().replace("\\", "/")
    return "migration" in text or "migrations/" in text or "migrate" in text or "makemigrations" in text


def build_migration_compatibility_policy(task: dict[str, Any], code_refs: list[str]) -> dict[str, Any]:
    return {
        "base_ref": task.get("base_ref") or "origin/develop",
        "mode": "adapt_to_current_target",
        "required_integrator_behavior": [
            "Inspect current target-branch model/schema code and existing migrations before applying candidate migration changes.",
            "Integrate compatible migration intent into current code instead of rejecting only because nearby code or migration numbering changed.",
            "Preserve already existing target-branch behavior and data compatibility unless the task explicitly authorizes a replacement.",
            "If migration numbering, dependencies, model state, or data safety conflicts cannot be resolved mechanically, route to needs_integrator_review with concrete blockers.",
        ],
        "required_checks": [
            "git diff --check",
            "framework migration graph/check command when available, for example python manage.py makemigrations --check --dry-run or python manage.py migrate --plan",
            "targeted tests for touched models/services when available",
        ],
        "code_refs": code_refs,
    }


def build_context_inventory(task: dict[str, Any], doc_refs: list[dict[str, str]], code_refs: list[str]) -> dict[str, Any]:
    return {
        "base_ref": task.get("base_ref") or "origin/develop",
        "code_refs": code_refs,
        "doc_refs": doc_refs,
        "task_refs": build_task_refs(task),
        "task_queue_ref": task.get("task_queue_ref") or "AiStudio/Task_manager/task_queue.json",
        "review_policy": "Use current target-branch code, task queue state, and docs as context; integrate compatible additions instead of treating drift as an automatic blocker.",
    }


def build_script_actions(task: dict[str, Any]) -> list[dict[str, Any]]:
    actions: list[dict[str, Any]] = []
    checks = unique_strings([
        command
        for value in as_list(task.get("checks"))
        if (command := executable_check_command(value)) is not None
    ])
    if "git diff --check" not in checks:
        checks.append("git diff --check")
    for command in checks:
        actions.append({
            "command": command,
            "required": True,
            "purpose": "worker validation evidence",
            "on_failure": "stop and return needs_worker_fix with command output",
        })
    return actions


def generated_script_actions_need_repair(task: dict[str, Any]) -> bool:
    if str(task.get("repaired_packet_by") or "") != GENERATED_PACKET_WRITER:
        return False
    if str(task.get("status") or "") not in {"planned", "worker_ready", "needs_stronger_agent"}:
        return False
    expected = [item["command"] for item in build_script_actions(task)]
    actual = [script_action_command(item) for item in as_list(task.get("script_actions"))]
    return actual != expected


def apply_v2_packet(task: dict[str, Any], repaired_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    tid = task_id(updated)
    if not has_value(updated.get("forbidden_paths")):
        updated["forbidden_paths"] = list(DEFAULT_FORBIDDEN_PATHS)
    else:
        updated["forbidden_paths"] = normalize_forbidden_paths(updated.get("forbidden_paths"))
    input_refs = updated.get("input_refs") if isinstance(updated.get("input_refs"), dict) else {}
    updated["base_branch"] = updated.get("base_branch") or input_refs.get("base_branch") or "develop"
    requested_scope_paths = verified_requested_allowed_paths(updated)
    applied_scope_repair_paths = verified_applied_scope_repair_paths(updated)
    normalized_allowed_paths, allowed_path_notes = normalize_allowed_path_contract(updated)
    updated["allowed_paths"] = normalized_allowed_paths
    if allowed_path_notes:
        updated["allowed_path_notes"] = unique_strings([
            *as_list(updated.get("allowed_path_notes")),
            *allowed_path_notes,
        ])
    if can_infer_worker_profile(updated):
        inferred_profile = inferred_worker_profile(updated)
        profiles = unique_strings(as_list(updated.get("eligible_worker_profiles")))
        if (
            inferred_profile not in profiles
            and (
                not has_value(updated.get("recommended_agent"))
                or is_orchestrator_recommended_agent(updated.get("recommended_agent"))
            )
        ):
            updated["eligible_worker_profiles"] = [inferred_profile]
    updated["packet_schema_version"] = 2
    updated["worker_instructions"] = updated.get("worker_instructions") or [
        "Read doc_refs and input_refs before editing.",
        "Inspect code_refs and current target-branch code before changing behavior.",
        "Preserve existing_behavior and preserve_contract unless the task explicitly authorizes a replacement.",
        "Modify only allowed_paths and never touch forbidden_paths without explicit dispatcher repair.",
        "Run every required script_action and capture evidence.",
        "If a required check cannot run or fails, stop and return needs_worker_fix with the exact blocker.",
        "Write/update the worker report and emit integration_requested only when output_contract is satisfied.",
    ]
    updated["traceability"] = updated.get("traceability") or {
        "task_id": tid,
        "canonical_task_id": updated.get("canonical_task_id") or tid,
        "canonical_target_id": updated.get("canonical_target_id"),
        "source_lane": updated.get("source_lane"),
        "source_file": updated.get("source_file"),
        "provenance": updated.get("provenance"),
        "derived_from": updated.get("derived_from"),
        "repaired_at": repaired_at,
        "repaired_by": "scripts/agent_control/dispatcher_packet_repair.py",
    }
    updated["doc_refs"] = updated.get("doc_refs") or build_doc_refs(updated)
    updated["input_refs"] = build_input_refs(task, updated)
    updated["code_refs"] = build_code_refs(updated) or [NO_EXISTING_CODE_REFS]
    code_refs = updated["code_refs"]
    updated["context_inventory"] = updated.get("context_inventory") or build_context_inventory(updated, updated["doc_refs"], code_refs)
    if isinstance(updated.get("context_inventory"), dict):
        updated["context_inventory"]["code_refs"] = unique_strings([
            *as_list(updated["context_inventory"].get("code_refs")),
            *as_list(code_refs),
        ])
    if isinstance(updated.get("input_refs"), dict):
        updated["input_refs"]["forbidden_paths"] = list(updated.get("forbidden_paths") or [])
    output_contract = updated.get("output_contract")
    if not isinstance(output_contract, dict):
        output_contract = {
            "changed_paths_must_match_allowed_paths": True,
            "required_checks": updated.get("checks") or [],
            "worker_report_required": True,
            "worker_report_path": worker_report_path(updated),
            "event_required": "integration_requested",
            "task_state_on_success": "agent_done",
            "task_state_on_blocker": "needs_worker_fix",
            "preserve_existing_behavior": True,
        }
        updated["output_contract"] = output_contract
    elif legacy_task_manager_worker_report_path(output_contract.get("worker_report_path")):
        output_contract["worker_report_path"] = worker_report_path(updated)
    updated["script_actions"] = updated.get("script_actions") or build_script_actions(updated)
    updated["existing_behavior"] = updated.get("existing_behavior") or [
        "Dispatcher must inspect current target-branch implementation for the touched code_refs before worker claim.",
        "Worker must treat current develop behavior as the baseline to preserve, not as disposable scaffolding.",
    ]
    updated["preserve_contract"] = updated.get("preserve_contract") or [
        "Do not remove existing commands, callbacks, routes, handlers, public functions, config fields, diagnostics, links, or UI controls unless explicitly requested.",
        "If current target code is more complete than the task draft, extend the current code instead of replacing it with a simpler implementation.",
        "Minor target-branch drift is not a blocker by itself; integrate the requested compatible change into the current implementation.",
    ]
    updated["regression_guards"] = updated.get("regression_guards") or [
        "Compare changed code against current develop before finalizing.",
        "Document any intentional behavior removal in the worker report and integration_notes.",
    ]
    updated["integration_notes"] = updated.get("integration_notes") or [
        "Integrator must preserve current develop behavior and cherry-pick only useful candidate additions.",
        "If candidate code was built on stale context, adapt it to current code instead of rejecting the migration only because surrounding code changed.",
        "If preserving behavior requires non-trivial design work, route to needs_integrator_review, needs_dispatcher_repair, needs_architect, or needs_worker_fix with a concrete next owner.",
    ]
    if is_migration_sensitive(updated, code_refs) and not has_value(updated.get("migration_compatibility_policy")):
        updated["migration_compatibility_policy"] = build_migration_compatibility_policy(updated, code_refs)
    updated["packet_status"] = "worker_ready"
    updated["normalization_status"] = "worker_ready"
    updated["dispatcher_decision"] = "worker_ready"
    updated["dispatcher_decision_reason"] = "worker packet v2 repaired and eligible for worker claim"
    updated["status"] = "planned"
    updated["worker_ready"] = True
    updated["next_owner"] = "worker_pool"
    updated["next_role"] = "auto_workers"
    updated["repaired_packet_at"] = repaired_at
    updated["repaired_packet_by"] = "scripts/agent_control/dispatcher_packet_repair.py"
    if requested_scope_paths:
        updated["dispatcher_scope_repair"] = {
            "applied_at": repaired_at,
            "applied_by": "scripts/agent_control/dispatcher_packet_repair.py",
            "requested_allowed_paths": requested_scope_paths,
            "source_verifier": task.get("requested_allowed_paths_verified_by"),
        }
    if (
        (requested_scope_paths or applied_scope_repair_paths)
        and updated.get("integration_status") == "needs_dispatcher_repair"
    ):
        updated.pop("integration_status", None)
    for field in (
        "repair_request",
        "missing_packet_fields",
        "repair_owner",
        "next_action",
        "not_worker_ready_reason",
        "status_reason",
        "requested_allowed_paths",
        "requested_allowed_paths_verified_by",
        "dispatcher_repair_kind",
    ):
        updated.pop(field, None)
    return updated


def clean_worker_ready_metadata(task: dict[str, Any], repaired_at: str) -> dict[str, Any] | None:
    if task.get("worker_ready") is not True or task.get("dispatcher_decision") != "worker_ready":
        return None
    updated = deepcopy(task)
    cleaned_fields = [field for field in ("not_worker_ready_reason", "status_reason") if field in task]
    for field in cleaned_fields:
        updated.pop(field, None)
    normalized_allowed_paths, allowed_path_notes = normalize_allowed_path_contract(updated)
    if normalized_allowed_paths != unique_strings(as_list(updated.get("allowed_paths"))):
        updated["allowed_paths"] = normalized_allowed_paths
        cleaned_fields.append("allowed_paths")
        code_refs = build_code_refs(updated) or [NO_EXISTING_CODE_REFS]
        if code_refs != unique_strings(as_list(updated.get("code_refs"))):
            updated["code_refs"] = code_refs
            cleaned_fields.append("code_refs")
        inventory = updated.get("context_inventory")
        if isinstance(inventory, dict):
            inventory_code_refs = unique_strings([*as_list(inventory.get("code_refs")), *as_list(code_refs)])
            if inventory_code_refs != unique_strings(as_list(inventory.get("code_refs"))):
                inventory["code_refs"] = inventory_code_refs
                cleaned_fields.append("context_inventory.code_refs")
    if isinstance(updated.get("input_refs"), dict):
        repaired_input_refs = build_input_refs(updated)
        if repaired_input_refs != updated["input_refs"]:
            updated["input_refs"] = repaired_input_refs
            cleaned_fields.append("input_refs")
    if allowed_path_notes:
        notes = unique_strings([*as_list(updated.get("allowed_path_notes")), *allowed_path_notes])
        if notes != unique_strings(as_list(updated.get("allowed_path_notes"))):
            updated["allowed_path_notes"] = notes
            cleaned_fields.append("allowed_path_notes")
    if updated.get("next_owner") != "worker_pool":
        updated["next_owner"] = "worker_pool"
        cleaned_fields.append("next_owner")
    if updated.get("next_role") != "auto_workers":
        updated["next_role"] = "auto_workers"
        cleaned_fields.append("next_role")
    if can_infer_worker_profile(updated):
        inferred_profile = inferred_worker_profile(updated)
        profiles = unique_strings(as_list(updated.get("eligible_worker_profiles")))
        if inferred_profile not in profiles:
            if (
                not has_value(updated.get("recommended_agent"))
                or is_orchestrator_recommended_agent(updated.get("recommended_agent"))
            ):
                updated["eligible_worker_profiles"] = [inferred_profile]
                cleaned_fields.append("eligible_worker_profiles")
    if generated_script_actions_need_repair(updated):
        updated["script_actions"] = build_script_actions(updated)
        cleaned_fields.append("script_actions")
    output_contract = updated.get("output_contract")
    if (
        str(updated.get("repaired_packet_by") or "") == GENERATED_PACKET_WRITER
        and isinstance(output_contract, dict)
    ):
        expected_checks = unique_strings(as_list(updated.get("checks")))
        if unique_strings(as_list(output_contract.get("required_checks"))) != expected_checks:
            output_contract["required_checks"] = expected_checks
            cleaned_fields.append("output_contract.required_checks")
    if isinstance(output_contract, dict) and legacy_task_manager_worker_report_path(output_contract.get("worker_report_path")):
        output_contract["worker_report_path"] = worker_report_path(updated)
        cleaned_fields.append("output_contract.worker_report_path")
    if not cleaned_fields:
        return None
    updated["repaired_packet_metadata_at"] = repaired_at
    updated["repaired_packet_metadata_by"] = GENERATED_PACKET_WRITER
    updated["_cleaned_worker_ready_metadata_fields"] = cleaned_fields
    return updated


def mark_needs_repair(task: dict[str, Any], missing: list[str], repaired_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    updated["status"] = "needs_dispatcher_repair"
    updated["worker_ready"] = False
    updated["packet_status"] = "needs_dispatcher_repair"
    updated["normalization_status"] = "needs_dispatcher_repair"
    updated["dispatcher_decision"] = "needs_dispatcher_repair"
    updated["dispatcher_decision_reason"] = "worker packet is incomplete and requires dispatcher repair"
    updated["repair_request"] = "Complete Worker Task Packet v2 fields before worker claim."
    updated["missing_packet_fields"] = missing
    updated["repair_owner"] = "dispatcher"
    updated["next_action"] = "run dispatcher_packet_repair.py after adding missing base packet fields, or route to needs_architect/needs_human with a concrete question"
    updated["dispatcher_next_review_at"] = task.get("dispatcher_next_review_at") or repaired_at
    return updated


def route_design_handoff_parent(task: dict[str, Any], repaired_at: str) -> dict[str, Any]:
    updated = deepcopy(task)
    updated["status"] = "planned"
    updated["worker_ready"] = False
    updated["packet_status"] = "dispatcher_intake"
    updated["normalization_status"] = "dispatcher_intake"
    updated["dispatcher_decision"] = "needs_dispatcher_review"
    updated["dispatcher_decision_reason"] = "design handoff parent must be split or import child Worker Packet v2 tasks"
    updated["owner"] = "dispatcher"
    updated["next_owner"] = "Dispatcher"
    updated["next_role"] = "auto_dispatcher"
    updated["not_worker_ready_reason"] = "parent intake row is coordination scope, not executable worker scope"
    updated["dispatcher_next_review_at"] = task.get("dispatcher_next_review_at") or repaired_at
    return updated


def needs_dispatcher_repair_contract(task: dict[str, Any]) -> bool:
    routes = {
        str(task.get("status") or ""),
        str(task.get("dispatcher_decision") or ""),
        str(task.get("integration_status") or ""),
    }
    return "needs_dispatcher_repair" in routes and any(not has_value(task.get(field)) for field in DISPATCHER_REPAIR_FIELDS)


def infer_dispatcher_repair_fields(task: dict[str, Any]) -> list[str]:
    existing = unique_strings(as_list(task.get("missing_packet_fields")))
    if has_unresolved_finalize_scope_failure(task):
        existing = unique_strings([*existing, "allowed_paths"])
    if existing:
        return existing
    blocker = " ".join(
        str(task.get(field) or "")
        for field in ("blocked_reason", "status_reason", "dispatcher_decision_reason", "not_worker_ready_reason")
    ).lower()
    if "source_report_missing" in blocker or "source report missing" in blocker:
        return ["source_report"]
    return ["dispatcher_blocker_resolution"]


def complete_dispatcher_repair_contract(task: dict[str, Any], repaired_at: str) -> dict[str, Any]:
    missing = unique_strings([
        *infer_dispatcher_repair_fields(task),
        *missing_base_fields(task),
        *missing_v2_fields(task),
    ])
    updated = mark_needs_repair(task, missing, repaired_at)
    blocker = str(task.get("blocked_reason") or task.get("status_reason") or "dispatcher repair is required").strip()
    updated["owner"] = "dispatcher"
    updated["next_owner"] = "Dispatcher"
    updated["next_role"] = "auto_dispatcher"
    updated["dispatcher_decision_reason"] = task.get("dispatcher_decision_reason") or blocker
    updated["repair_request"] = task.get("repair_request") or (
        f"Resolve the recorded Dispatcher blocker before worker claim: {blocker}"
    )
    updated["next_action"] = task.get("next_action") or (
        "resolve the recorded blocker, rerun dispatcher_packet_repair.py, then rerun task queue readiness validation"
    )
    updated["not_worker_ready_reason"] = task.get("not_worker_ready_reason") or blocker
    return updated


def process_queue(
    data: dict[str, Any],
    *,
    dependency_context_tasks: list[dict[str, Any]] | None = None,
    selected_task_ids: set[str] | None = None,
    missing_base_branch_only: bool = False,
) -> tuple[dict[str, Any], dict[str, Any]]:
    result = deepcopy(data)
    tasks = result.get("tasks")
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    repaired_at = utc_now()
    repaired: list[dict[str, Any]] = []
    cleaned: list[dict[str, Any]] = []
    marked: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    task_rows = [task for task in tasks if isinstance(task, dict)]
    context_rows = dependency_context_tasks if dependency_context_tasks is not None else task_rows
    completed_ids = completed_task_ids(context_rows)
    requested_ids = set(selected_task_ids or ())
    found_ids: set[str] = set()

    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        tid = task_id(task) or f"index-{index}"
        if requested_ids and tid not in requested_ids:
            continue
        found_ids.add(tid)
        if missing_base_branch_only and not is_missing_base_branch_migration_candidate(task):
            continue
        if str(task.get("status") or "") in TERMINAL_STATUSES:
            skipped.append({"task_id": tid, "reason": "terminal status"})
            continue
        if is_integration_only_task(task):
            skipped.append({"task_id": tid, "reason": "integration-only task is not a Worker Packet v2 candidate"})
            continue
        if is_design_handoff_intake_parent(task):
            routed = route_design_handoff_parent(task, repaired_at)
            if routed != task:
                tasks[index] = routed
                cleaned.append({"task_id": tid, "fields": ["design_handoff_parent_route"]})
            else:
                skipped.append({"task_id": tid, "reason": "design handoff parent already routed to Dispatcher"})
            continue
        if is_design_handoff_dependency_blocked(task):
            skipped.append({"task_id": tid, "reason": "design handoff dependencies are not finalized"})
            continue
        unresolved = unresolved_dependencies(task, completed_ids)
        if unresolved and is_dependency_repair_candidate(task):
            blocked = mark_blocked_by_dependency(task, unresolved, repaired_at)
            if blocked != task:
                tasks[index] = blocked
                marked.append({"task_id": tid, "blocked_by": unresolved, "resolved_dependencies": blocked["resolved_dependencies"]})
            else:
                skipped.append({"task_id": tid, "reason": "dependencies remain unresolved"})
            continue
        if str(task.get("dispatcher_decision") or "") == "blocked_by_dependency" and is_dependency_repair_candidate(task):
            task = release_resolved_dependency_block(task, repaired_at)
        if (
            not missing_v2_fields(task)
            and int(task.get("packet_schema_version") or 1) >= 2
            and str(task.get("status") or "") in REPAIRABLE_STATUSES
            and str(task.get("dispatcher_decision") or "") == "needs_dispatcher_repair"
        ):
            if has_unresolved_finalize_scope_failure(task):
                if allowed_path_contract_needs_repair(task):
                    tasks[index] = apply_v2_packet(task, repaired_at)
                    repaired.append({
                        "task_id": tid,
                        "status": "planned",
                        "packet_schema_version": 2,
                        "fields": ["allowed_paths"],
                        "reason": "scope failure repaired from explicit test commands",
                    })
                    continue
                completed = complete_dispatcher_repair_contract(task, repaired_at)
                if completed != task:
                    tasks[index] = completed
                    marked.append({"task_id": tid, "missing_packet_fields": completed["missing_packet_fields"]})
                    continue
                skipped.append({"task_id": tid, "reason": "scope-failed v2 packet remains needs_dispatcher_repair"})
                continue
            if can_repair_to_v2(task):
                tasks[index] = apply_v2_packet(task, repaired_at)
                repaired.append({"task_id": tid, "status": "planned", "packet_schema_version": 2})
                continue
        if needs_dispatcher_repair_contract(task):
            completed = complete_dispatcher_repair_contract(task, repaired_at)
            if completed != task:
                tasks[index] = completed
                marked.append({"task_id": tid, "missing_packet_fields": completed["missing_packet_fields"]})
            else:
                skipped.append({"task_id": tid, "reason": "dispatcher repair contract already complete"})
            continue
        missing_base = missing_base_fields(task)
        missing_v2 = missing_v2_fields(task)
        if missing_v2 == ["base_branch"] and int(task.get("packet_schema_version") or 1) >= 2:
            tasks[index] = repair_missing_base_branch(task)
            repaired.append({
                "task_id": tid,
                "status": str(task.get("status") or ""),
                "packet_schema_version": 2,
                "fields": ["base_branch"],
            })
            continue
        if not missing_v2 and int(task.get("packet_schema_version") or 1) >= 2:
            if (
                str(task.get("status") or "") in REPAIRABLE_STATUSES
                and allowed_path_contract_needs_repair(task)
            ):
                tasks[index] = apply_v2_packet(task, repaired_at)
                repaired.append({
                    "task_id": tid,
                    "status": "planned",
                    "packet_schema_version": 2,
                    "fields": ["allowed_paths"],
                })
                continue
            cleaned_task = clean_worker_ready_metadata(task, repaired_at)
            if cleaned_task is not None:
                cleaned_fields = cleaned_task.pop("_cleaned_worker_ready_metadata_fields", [])
                tasks[index] = cleaned_task
                cleaned.append({"task_id": tid, "fields": cleaned_fields})
                continue
            skipped.append({"task_id": tid, "reason": "already packet v2"})
            continue
        if can_repair_to_v2(task):
            tasks[index] = apply_v2_packet(task, repaired_at)
            repaired.append({"task_id": tid, "status": "planned", "packet_schema_version": 2})
            continue
        if str(task.get("status") or "") in REPAIRABLE_STATUSES:
            missing = unique_strings([*missing_base, *missing_v2, *as_list(task.get("missing_packet_fields"))])
            marked_task = mark_needs_repair(task, missing, repaired_at)
            if marked_task != task:
                tasks[index] = marked_task
                marked.append({"task_id": tid, "missing_packet_fields": missing})
            else:
                skipped.append({"task_id": tid, "reason": "dispatcher repair state already current"})
        else:
            skipped.append({"task_id": tid, "reason": "not a dispatcher repair candidate"})

    if repaired or cleaned or marked:
        result["updated_at"] = repaired_at

    return result, {
        "checked_at": repaired_at,
        "repaired_count": len(repaired),
        "cleaned_count": len(cleaned),
        "needs_dispatcher_repair_count": len(marked),
        "skipped_count": len(skipped),
        "repaired": repaired,
        "cleaned": cleaned,
        "needs_dispatcher_repair": marked,
        "skipped": skipped,
        "selected_task_ids": sorted(requested_ids),
        "missing_task_ids": sorted(requested_ids - found_ids),
        "missing_base_branch_only": missing_base_branch_only,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Repair Dispatcher worker packets to schema v2.")
    parser.add_argument("--queue", required=True, help="Path to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--apply", action="store_true", help="Write repaired queue. Default is dry-run.")
    parser.add_argument("--output", help="Write repaired queue copy without mutating --queue. Cannot be combined with --apply.")
    parser.add_argument("--task-id", action="append", default=[], help="Repair only this exact task ID. Repeat for multiple tasks.")
    parser.add_argument("--missing-base-branch-only", action="store_true", help="Migrate only existing Worker Packet v2 rows missing top-level base_branch.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    if args.apply and args.output:
        parser.error("--output cannot be combined with --apply")

    queue_path = Path(args.queue).resolve()
    output_path = Path(args.output).resolve() if args.output else None
    data = load_json(queue_path)
    updated, report = process_queue(
        data,
        selected_task_ids=set(args.task_id),
        missing_base_branch_only=bool(args.missing_base_branch_only),
    )
    report["queue"] = str(queue_path)
    report["output"] = str(output_path) if output_path else None
    report["dry_run"] = not args.apply
    if args.apply and (report["repaired_count"] or report["cleaned_count"] or report["needs_dispatcher_repair_count"]):
        write_json(queue_path, updated)
    if output_path:
        write_json(output_path, updated)

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"queue: {queue_path}")
        print(f"mode: {'dry-run' if not args.apply else 'apply'}")
        print(f"repaired: {report['repaired_count']}")
        print(f"cleaned: {report['cleaned_count']}")
        print(f"needs_dispatcher_repair: {report['needs_dispatcher_repair_count']}")
        print(f"skipped: {report['skipped_count']}")
    return 1 if report["missing_task_ids"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
