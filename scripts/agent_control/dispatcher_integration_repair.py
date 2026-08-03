#!/usr/bin/env python3
"""Resolve Integrator-routed queue rows that Dispatcher can repair automatically."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from dispatcher_packet_repair import (
    apply_v2_packet,
    build_code_refs,
    has_unresolved_finalize_scope_failure,
    missing_base_fields,
    missing_v2_fields,
)
from integrator_direct_merge import (
    behavior_tokens,
    detect_behavior_regression,
    is_code_behavior_path,
    is_line_preservation_path,
    line_contract_tokens,
    repository_hygiene_live_ci_gate,
)
from integrator_preflight import is_coordination_path, split_coordination_paths
from project_paths import task_file
from repository_pr_decomposition_planner import build_decomposition_plan
from task_state_invariants import normalize_terminal_task


INTEGRATION_REPAIR_MARKERS = {
    "focused_validation_failed:": "focused_validation",
    "semantic_regression_detected:": "behavior_preservation",
}
DEFAULT_REPAIR_FORBIDDEN_PATHS = [".env", ".env.*", "secrets", "production config"]
DEFAULT_BRANCH_ARCHIVE_ROOT = Path(
    os.environ.get(
        "AISTUDIO_BRANCH_ARCHIVE_ROOT",
        "/srv/aistudio-hdd/AiStudioData/archive/git-branches",
    )
)
TERMINAL_TASK_STATUSES = {"done", "finalized", "failed", "postponed", "stale_or_superseded", "duplicate_linked"}
MAX_INTEGRATION_REPAIR_WORKER_RETRIES = 2
SEMANTIC_RECHECK_POLICY_VERSION = 2
WORKER_FIX_COORDINATION_ONLY_REASON = (
    "Integrator preflight returned coordination-only worker fix; no product integration remains."
)
FINALIZE_SCOPE_COORDINATION_ONLY_REASON = (
    "Dispatcher verified prior finalize-scope failure only produced coordination paths; no product integration remains."
)
COORDINATION_ONLY_CLOSURE_REASONS = {
    WORKER_FIX_COORDINATION_ONLY_REASON,
    FINALIZE_SCOPE_COORDINATION_ONLY_REASON,
}
HIGH_RISK_REPAIR_ACTION = "create_behavior_preserving_repair_packet"
HIGH_RISK_REPAIR_KIND = "owner_execution_authorization"
HIGH_RISK_DESIGN_HANDOFF_RETRY_ACTION = "return_high_risk_design_handoff_task_to_strong_worker"
REQUIRED_HIGH_RISK_DESIGN_HANDOFF_RETRY_BOUNDARIES = {
    "task identity",
    "rejected-attempt evidence",
    "authorization and authority guard",
    "forbidden-path and secret checks",
    "capability preservation",
    "required tests and CI",
    "strong Integrator review",
    "Finalizer gate",
}
REQUIRED_HIGH_RISK_REPAIR_BOUNDARIES = {
    "secret_scan",
    "forbidden_path_check",
    "security_and_high_risk_review",
    "project_required_tests",
    "capability_preservation_check",
    "integrator_review",
    "finalizer_gate",
}
MANUAL_REPAIR_MARKERS = (
    "manual integrator review",
    "high-risk",
    "high risk",
    "needs human",
    "owner/strong review",
    "authority defect",
)
DECOMPOSITION_IMPLEMENTATION_CATEGORIES = {"automation", "code", "contract", "asset"}
COLLAPSED_REPAIR_REASON = (
    "exact-head CI-green source PR is already implemented; unstarted decomposition is superseded"
)
TERMINAL_PARENT_DECOMPOSITION_REASON = (
    "terminal parent task is no longer actionable; unstarted decomposition is superseded"
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def append_event(path: Path, event: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")


def run_git(project_root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", "-C", str(project_root), *args], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)


def normalize_branch(value: Any) -> str:
    branch = str(value or "").strip()
    if branch.startswith("refs/remotes/origin/"):
        return "origin/" + branch.removeprefix("refs/remotes/origin/")
    if branch.startswith("refs/heads/"):
        return branch.removeprefix("refs/heads/")
    return branch


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def is_repository_hygiene_integration(task: dict[str, Any]) -> bool:
    return str(task.get("type") or "").strip() == "repository_hygiene_integration"


def valid_source_head_sha(value: Any) -> str:
    candidate = str(value or "").strip().lower()
    return candidate if re.fullmatch(r"[0-9a-f]{40}", candidate) else ""


def integration_repair_source_head_sha(task: dict[str, Any]) -> str:
    values = [task.get("integration_repair_source_head_sha")]
    if is_repository_hygiene_integration(task):
        values.extend([task.get("repository_hygiene_head_sha"), task.get("head_sha")])
    values.append(task.get("worker_result_commit"))
    for value in values:
        if source_head_sha := valid_source_head_sha(value):
            return source_head_sha
    return ""


def integration_repair_retry_count(task: dict[str, Any]) -> int:
    try:
        return max(0, int(task.get("integration_repair_retry_count") or 0))
    except (TypeError, ValueError):
        return MAX_INTEGRATION_REPAIR_WORKER_RETRIES


def is_complete_worker_packet_v2(task: dict[str, Any]) -> bool:
    try:
        if int(task.get("packet_schema_version") or 1) < 2:
            return False
    except (TypeError, ValueError):
        return False
    if missing_base_fields(task) or missing_v2_fields(task):
        return False
    inventory = task.get("context_inventory")
    if not isinstance(inventory, dict) or any(not inventory.get(field) for field in ("code_refs", "doc_refs", "task_refs")):
        return False
    output_contract = task.get("output_contract")
    return (
        isinstance(output_contract, dict)
        and output_contract.get("changed_paths_must_match_allowed_paths") is True
        and output_contract.get("preserve_existing_behavior") is True
        and str(output_contract.get("task_state_on_blocker") or "") == "needs_worker_fix"
    )


def explicit_manual_or_high_risk_route(task: dict[str, Any]) -> bool:
    if task.get("requires_human_attention") is True:
        return True
    if str(task.get("next_owner") or "").strip().lower() in {"human", "owner", "manual", "integrator"}:
        return True
    if str(task.get("dispatcher_decision") or "").strip().lower() in {"needs_human", "needs_integrator_review"}:
        return True
    if str(task.get("source_risk_class") or task.get("risk_class") or "").strip().lower() in {"high", "critical"}:
        return True
    reason = " ".join(
        str(task.get(field) or "")
        for field in ("repair_request", "status_reason", "dispatcher_decision_reason", "not_worker_ready_reason")
    ).lower()
    return any(marker in reason for marker in MANUAL_REPAIR_MARKERS)


def clear_worker_packet_repair_metadata(task: dict[str, Any]) -> None:
    for field in (
        "repair_request",
        "missing_packet_fields",
        "repair_owner",
        "next_action",
        "not_worker_ready_reason",
        "task_packet_defects",
    ):
        task.pop(field, None)


def list_paths(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return sorted({str(path).replace("\\", "/") for path in value if str(path or "").strip()})


def high_risk_repair_request(task: dict[str, Any]) -> bool:
    reason = str(task.get("repair_request") or task.get("status_reason") or "").strip().lower()
    return "high-risk" in reason or "needs human" in reason


def owner_high_risk_design_handoff_authorization(
    project_root: Path,
    task: dict[str, Any],
    base_ref: str = "origin/develop",
) -> dict[str, Any] | None:
    """Return one exact owner decision for a design-handoff retry.

    Design-handoff Worker results have no PR identity. Their authorization is
    therefore bound to the original task, package item, result branch and
    result commit, plus the develop SHA observed when the owner approved it.
    """

    if str(task.get("type") or "") != "design-handoff-intake":
        return None
    tid = task_id(task)
    package_id = str(task.get("source_package_id") or "").strip()
    item_id = str(task.get("source_item_id") or "").strip()
    source_branch = normalize_branch(task.get("branch") or task.get("github_branch"))
    worker_result_commit = valid_source_head_sha(task.get("worker_result_commit"))
    if not tid or not package_id or not item_id or not source_branch or not worker_result_commit:
        return None

    current_develop_sha, _ = verified_commit(project_root, base_ref)
    if not current_develop_sha:
        return None

    decisions_dir = project_root / "AiStudio" / "Project_state" / "decisions"
    if not decisions_dir.is_dir():
        return None

    matches: list[dict[str, Any]] = []
    for path in sorted(decisions_dir.glob("*.json")):
        try:
            decision = load_json(path)
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(decision, dict):
            continue
        if str(decision.get("kind") or "") != HIGH_RISK_REPAIR_KIND:
            continue
        if str(decision.get("status") or "") != "accepted":
            continue
        if str(decision.get("access_level") or "") not in {"project", "owner_only"}:
            continue
        if not str(decision.get("id") or "").strip() or not str(decision.get("owner") or "").strip():
            continue

        authorization = decision.get("authorization")
        if not isinstance(authorization, dict):
            continue
        if str(authorization.get("scope") or "") != "exact_design_task_package_item_branch_and_worker_result":
            continue
        if str(authorization.get("action") or "") != HIGH_RISK_DESIGN_HANDOFF_RETRY_ACTION:
            continue
        approved_task_ids = [str(value).strip() for value in authorization.get("approved_task_ids") or []]
        if approved_task_ids != [tid]:
            continue
        if str(authorization.get("approved_package_id") or "").strip() != package_id:
            continue
        if str(authorization.get("approved_item_id") or "").strip() != item_id:
            continue
        if normalize_branch(authorization.get("approved_source_branch")) != source_branch:
            continue
        if valid_source_head_sha(authorization.get("approved_worker_result_commit")) != worker_result_commit:
            continue
        if str(authorization.get("observed_develop_sha") or "").strip().lower() != current_develop_sha:
            continue
        if authorization.get("require_fresh_develop_claim") is not True:
            continue
        if authorization.get("require_strong_worker") is not True:
            continue
        if authorization.get("require_strong_integrator_review") is not True:
            continue
        if authorization.get("allow_direct_merge") is not False:
            continue
        if authorization.get("one_shot") is not True:
            continue
        preserved = {str(value).strip() for value in decision.get("preserved_boundaries") or []}
        if not REQUIRED_HIGH_RISK_DESIGN_HANDOFF_RETRY_BOUNDARIES.issubset(preserved):
            continue

        matches.append({
            "decision_id": str(decision.get("id") or ""),
            "decision_path": path.relative_to(project_root).as_posix(),
            "action": HIGH_RISK_DESIGN_HANDOFF_RETRY_ACTION,
            "task_id": tid,
            "package_id": package_id,
            "item_id": item_id,
            "source_branch": source_branch,
            "worker_result_commit": worker_result_commit,
            "observed_develop_sha": current_develop_sha,
            "require_fresh_develop_claim": True,
            "require_strong_worker": True,
            "require_strong_integrator_review": True,
            "allow_direct_merge": False,
            "one_shot": True,
            "preserved_boundaries": sorted(REQUIRED_HIGH_RISK_DESIGN_HANDOFF_RETRY_BOUNDARIES),
        })

    return matches[0] if len(matches) == 1 else None


DESIGN_HANDOFF_RETRY_CLAIM_FIELDS = (
    "worker_id",
    "machine_id",
    "branch",
    "github_branch",
    "started_at",
    "claimed_at",
    "lock_expires_at",
    "worker_result_commit",
    "worker_report",
    "execution_id",
    "worktree",
    "worker_pid",
    "last_agent_report",
    "integration_report",
    "merge_commit",
    "worker_check_evidence",
    "current_context_verified_at",
    "current_context_verified_by",
    "current_context_reviewed_by",
    "current_context_review",
    "commits",
    "worker_changed_paths",
)


def task_lock_is_releasable_for_worker_retry(task: dict[str, Any]) -> bool:
    lock = task.get("lock")
    if isinstance(lock, dict):
        lock = lock.get("state")
    return str(lock or "free").strip().lower() in {"", "free", "released", "unlocked", "review"}


def consume_owner_authorized_design_handoff_retry(
    task: dict[str, Any],
    now: str,
    owner_authorization: dict[str, Any] | None,
    *,
    agent_lock_releasable: bool,
) -> dict[str, Any] | None:
    """Return one exact rejected design task to a fresh strong Worker claim."""

    if (
        not owner_authorization
        or str(task.get("type") or "") != "design-handoff-intake"
        or str(task.get("status") or "") not in {"needs_human", "needs_dispatcher_repair"}
        or str(task.get("integration_status") or "") not in {"needs_human", "needs_integrator_review", "needs_worker_fix"}
        or str(task.get("dispatcher_decision") or "") not in {"needs_human", "needs_integrator_review", "needs_dispatcher_repair"}
        or not task_lock_is_releasable_for_worker_retry(task)
        or not agent_lock_releasable
        or not is_complete_worker_packet_v2(task)
        or integration_repair_retry_count(task) != 0
        or task.get("integration_repair_retry_evidence")
    ):
        return None

    tid = task_id(task)
    evidence = task.get("integration_repair_retry_evidence")
    if not isinstance(evidence, list):
        evidence = []
    evidence.append({
        "at": now,
        "reason": str(task.get("repair_request") or task.get("status_reason") or "high-risk design-handoff result rejected by review"),
        "branch": owner_authorization["source_branch"],
        "worker_result_commit": owner_authorization["worker_result_commit"],
        "worker_report": task.get("worker_report"),
        "source_package_id": owner_authorization["package_id"],
        "source_item_id": owner_authorization["item_id"],
        "owner_decision_id": owner_authorization["decision_id"],
        "observed_develop_sha": owner_authorization["observed_develop_sha"],
    })
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "at": now,
        "by": "dispatcher_integration_repair",
        "from": str(task.get("status") or ""),
        "to": "planned",
        "event": "owner_authorized_design_handoff_retry",
        "reason": "One exact accepted owner decision authorized a fresh strong Worker attempt.",
    })
    for field in DESIGN_HANDOFF_RETRY_CLAIM_FIELDS:
        task.pop(field, None)
    task.update({
        "status": "planned",
        "integration_status": "returned_to_worker",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "exact owner authorization consumed for one fresh design-handoff retry",
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "worker_ready": True,
        "lock": "free",
        "next_owner": "worker_pool",
        "next_role": "auto_workers",
        "repair_owner": "Worker",
        "requires_human_attention": False,
        "source_risk_class": "high",
        "requires_strong_review": True,
        "requires_strong_worker": True,
        "requires_strong_integrator_review": True,
        "direct_merge_authorized": False,
        "allow_direct_merge": False,
        "fresh_worker_claim_required": True,
        "owner_authorization": deepcopy(owner_authorization),
        "integration_repair_kind": "behavior_preservation",
        "integration_repair_retry_count": 1,
        "integration_repair_retry_evidence": evidence,
        "status_history": history,
        "status_reason": "Dispatcher consumed the exact owner decision and returned the same design-handoff task to a fresh strong Worker claim.",
        "repair_request": "Fresh strong Worker must repair the rejected S02 result from current develop without widening the original packet scope.",
        "next_action": "Worker must claim a fresh branch/worktree, preserve current develop behavior, pass all required checks, and return through strong Integrator review and Finalizer.",
        "dispatcher_repaired_at": now,
        "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
    })
    return {
        "task_id": tid,
        "classification": "needs_worker_fix",
        "reason": "exact owner authorization consumed; same design-handoff task returned to fresh strong Worker",
        "owner_authorization_id": owner_authorization["decision_id"],
        "worker_result_commit": owner_authorization["worker_result_commit"],
    }


def owner_high_risk_repair_authorization(project_root: Path, task: dict[str, Any]) -> dict[str, Any] | None:
    """Return one exact, fail-closed owner authorization for a clean rebuild."""

    tid = task_id(task)
    source_head_sha = str(task.get("repository_hygiene_head_sha") or task.get("head_sha") or "").strip().lower()
    task_pr_numbers = {
        int(value)
        for value in [*(task.get("pr_numbers") or []), task.get("github_pr")]
        if isinstance(value, int) or str(value or "").isdigit()
    }
    if not tid or not re.fullmatch(r"[0-9a-f]{40}", source_head_sha) or not task_pr_numbers:
        return None

    decisions_dir = project_root / "AiStudio" / "Project_state" / "decisions"
    if not decisions_dir.is_dir():
        return None

    matches: list[dict[str, Any]] = []
    for path in sorted(decisions_dir.glob("*.json")):
        try:
            decision = load_json(path)
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(decision, dict):
            continue
        if str(decision.get("kind") or "") != HIGH_RISK_REPAIR_KIND:
            continue
        if str(decision.get("status") or "") != "accepted":
            continue
        if str(decision.get("access_level") or "") not in {"project", "owner_only"}:
            continue
        if not str(decision.get("id") or "").strip():
            continue
        if not str(decision.get("owner") or "").strip():
            continue

        authorization = decision.get("authorization")
        if not isinstance(authorization, dict):
            continue
        if str(authorization.get("action") or "") != HIGH_RISK_REPAIR_ACTION:
            continue
        approved_ids = {str(value) for value in authorization.get("approved_task_ids") or []}
        if tid not in approved_ids:
            continue
        if str(authorization.get("approved_source_head_sha") or "").strip().lower() != source_head_sha:
            continue
        approved_pr_numbers = {
            int(value)
            for value in authorization.get("approved_pr_numbers") or []
            if isinstance(value, int) or str(value or "").isdigit()
        }
        if approved_pr_numbers != task_pr_numbers:
            continue
        if authorization.get("require_current_develop_rebuild") is not True:
            continue
        if authorization.get("require_strong_worker") is not True:
            continue
        if authorization.get("allow_direct_merge") is not False:
            continue
        preserved = {str(value) for value in decision.get("preserved_boundaries") or []}
        if not REQUIRED_HIGH_RISK_REPAIR_BOUNDARIES.issubset(preserved):
            continue

        matches.append({
            "decision_id": str(decision.get("id") or ""),
            "decision_path": path.relative_to(project_root).as_posix(),
            "action": HIGH_RISK_REPAIR_ACTION,
            "task_id": tid,
            "source_head_sha": source_head_sha,
            "pr_numbers": sorted(task_pr_numbers),
            "require_current_develop_rebuild": True,
            "require_strong_worker": True,
            "allow_direct_merge": False,
            "preserved_boundaries": sorted(REQUIRED_HIGH_RISK_REPAIR_BOUNDARIES),
        })

    return matches[0] if len(matches) == 1 else None


def integration_repair_kind(task: dict[str, Any], owner_authorization: dict[str, Any] | None = None) -> str | None:
    if task.get("repository_hygiene_draft_prs"):
        return None
    if task.get("repository_hygiene_dirty_prs") and not owner_authorization:
        return None
    explicit = str(task.get("integration_repair_kind") or "").strip()
    if explicit in set(INTEGRATION_REPAIR_MARKERS.values()):
        return explicit
    reason = str(task.get("repair_request") or task.get("status_reason") or "").strip().lower()
    if "high-risk" in reason or "needs human" in reason:
        return "behavior_preservation" if owner_authorization else None
    for marker, kind in INTEGRATION_REPAIR_MARKERS.items():
        if marker in reason:
            return kind
    return None


def prefixed_integration_repair_kind(task: dict[str, Any]) -> str | None:
    reason = str(task.get("repair_request") or "").strip().lower()
    return next(
        (kind for marker, kind in INTEGRATION_REPAIR_MARKERS.items() if reason.startswith(marker)),
        None,
    )


def integration_repair_test_paths(task: dict[str, Any]) -> list[str]:
    reason = str(task.get("repair_request") or task.get("status_reason") or "")
    parsed = validation_test_paths(reason)
    return sorted({
        *list_paths(task.get("integration_repair_validation_paths")),
        *[path for path in current_task_paths(task) if path.startswith("tests/") and path.endswith(".py")],
        *parsed,
    })


def validation_test_paths(reason: str) -> list[str]:
    return sorted(set(re.findall(r"tests/[A-Za-z0-9_./-]+\.py", reason.replace("\\", "/"))))


def requeue_failed_integration_repair_child(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("type") or "") != "clean-rebuild":
        return None
    if not str(task.get("integration_repair_parent_id") or "").strip():
        return None
    if str(task.get("status") or "") != "needs_human":
        return None
    if str(task.get("integration_status") or "") != "needs_integrator_review":
        return None
    reason = str(task.get("repair_request") or "").strip()
    if not reason.lower().startswith("focused_validation_failed:"):
        return None
    retry_count = int(task.get("integration_repair_retry_count") or 0)
    if retry_count >= MAX_INTEGRATION_REPAIR_WORKER_RETRIES:
        return None

    failed_tests = validation_test_paths(reason)
    allowed_paths = list_paths([*list_paths(task.get("allowed_paths")), *failed_tests])
    checks = [str(item) for item in task.get("checks") or [] if str(item or "").strip()]
    if failed_tests:
        focused_check = "python -m pytest " + " ".join(failed_tests) + " -q"
        if focused_check not in checks:
            checks.insert(0, focused_check)

    evidence = task.get("integration_repair_retry_evidence")
    if not isinstance(evidence, list):
        evidence = []
    evidence.append({
        "at": now,
        "reason": reason,
        "branch": task.get("branch"),
        "worker_result_commit": task.get("worker_result_commit"),
        "worker_report": task.get("worker_report"),
    })
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "at": now,
        "by": "dispatcher_integration_repair",
        "from": "needs_human",
        "to": "planned",
        "event": "worker_fix_requeued",
        "reason": "Integrator focused validation failed and requires a bounded Worker repair retry.",
    })

    task.update({
        "status": "planned",
        "integration_status": "returned_to_worker",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "focused validation failure is Worker-repairable",
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "worker_ready": True,
        "lock": "free",
        "next_owner": "worker_pool",
        "next_role": "auto_workers",
        "repair_owner": "Worker",
        "requires_human_attention": False,
        "next_action": "Worker must fix the exact focused-validation failure on current develop and rerun all required checks.",
        "status_reason": "Dispatcher returned a bounded focused-validation repair to Worker.",
        "allowed_paths": allowed_paths,
        "checks": checks,
        "integration_repair_retry_count": retry_count + 1,
        "integration_repair_retry_evidence": evidence,
        "status_history": history,
        "dispatcher_repaired_at": now,
        "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
    })
    input_refs = task.get("input_refs")
    if isinstance(input_refs, dict):
        input_refs["allowed_paths"] = allowed_paths
    return {
        "task_id": task_id(task),
        "classification": "needs_worker_fix",
        "reason": "focused validation failed; Dispatcher scheduled bounded Worker retry",
        "failed_tests": failed_tests,
        "retry_count": retry_count + 1,
    }


MAX_FINALIZE_SCOPE_SAME_SCOPE_RETRIES = 1


def task_lock_is_available(task: dict[str, Any]) -> bool:
    lock = task.get("lock")
    if isinstance(lock, dict):
        lock = lock.get("state")
    return str(lock or "free").strip().lower() in {"", "free", "released", "unlocked"}


def agent_lock_is_available(locks_path: Path, tid: str) -> bool:
    if not tid or not locks_path.exists():
        return True
    try:
        locks = load_json(locks_path)
    except (OSError, json.JSONDecodeError):
        return False
    lock_rows = locks.get("locks")
    if not isinstance(lock_rows, list):
        return False
    matching_states = [
        str(row.get("state") or "free").strip().lower()
        for row in lock_rows
        if isinstance(row, dict) and str(row.get("task_id") or "") == tid
    ]
    return all(state in {"", "free", "released", "unlocked"} for state in matching_states)


def agent_lock_is_releasable_for_worker_retry(locks_path: Path, tid: str) -> bool:
    if not tid or not locks_path.exists():
        return True
    try:
        locks = load_json(locks_path)
    except (OSError, json.JSONDecodeError):
        return False
    lock_rows = locks.get("locks")
    if not isinstance(lock_rows, list):
        return False
    matching_states = [
        str(row.get("state") or "free").strip().lower()
        for row in lock_rows
        if isinstance(row, dict) and str(row.get("task_id") or "") == tid
    ]
    return all(state in {"", "free", "released", "unlocked", "review"} for state in matching_states)


def migrate_existing_design_handoff_integrator_failure(
    task: dict[str, Any],
    now: str,
    *,
    agent_lock_releasable: bool,
) -> dict[str, Any] | None:
    tid = task_id(task)
    route_state = (
        str(task.get("status") or ""),
        str(task.get("integration_status") or ""),
    )
    if (
        not tid
        or tid.startswith("SRC-")
        or str(task.get("type") or "") != "design-handoff-intake"
        or not str(task.get("source_item_id") or "").strip()
        or route_state
        not in {
            ("needs_human", "needs_integrator_review"),
            ("needs_dispatcher_repair", "needs_worker_fix"),
        }
        or not task_lock_is_available(task)
        or not agent_lock_releasable
        or not is_complete_worker_packet_v2(task)
        or not normalize_branch(task.get("branch") or task.get("github_branch"))
    ):
        return None
    reason = str(task.get("repair_request") or "").strip()
    repair_kind = prefixed_integration_repair_kind(task)
    legacy_route_probe = {
        **task,
        "requires_human_attention": False,
        "next_owner": "",
        "dispatcher_decision": "",
    }
    explicit_kind = str(task.get("integration_repair_kind") or "").strip()
    if (
        not repair_kind
        or (explicit_kind and explicit_kind != repair_kind)
        or explicit_manual_or_high_risk_route(legacy_route_probe)
    ):
        return None
    source_head_sha = integration_repair_source_head_sha(task)
    retry_count = integration_repair_retry_count(task)
    if not source_head_sha or retry_count >= MAX_INTEGRATION_REPAIR_WORKER_RETRIES:
        return None

    evidence = task.get("integration_repair_retry_evidence")
    if not isinstance(evidence, list):
        evidence = []
    evidence.append({
        "at": now,
        "reason": reason,
        "branch": task.get("branch") or task.get("github_branch"),
        "worker_result_commit": task.get("worker_result_commit"),
        "worker_report": task.get("worker_report"),
        "source_head_sha": source_head_sha,
        "from_status": task.get("status"),
        "from_integration_status": task.get("integration_status"),
    })
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "at": now,
        "by": "dispatcher_integration_repair",
        "from": str(task.get("status") or ""),
        "to": "planned",
        "event": "design_handoff_integration_retry",
        "reason": "Dispatcher returned the same complete design-handoff packet for a bounded Worker retry.",
    })
    task.update({
        "status": "planned",
        "integration_status": "returned_to_worker",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "complete design-handoff packet returned for bounded integration repair",
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "worker_ready": True,
        "lock": "free",
        "next_owner": "worker_pool",
        "next_role": "auto_workers",
        "repair_owner": "Worker",
        "requires_human_attention": False,
        "next_action": "Worker must repair the recorded Integrator failure on the pinned source attempt without widening packet scope.",
        "status_reason": "Dispatcher returned the same Worker Packet v2 for a bounded integration repair retry.",
        "integration_repair_kind": repair_kind,
        "integration_repair_source_head_sha": source_head_sha,
        "integration_repair_retry_count": retry_count + 1,
        "integration_repair_retry_evidence": evidence,
        "status_history": history,
        "dispatcher_repaired_at": now,
        "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
    })
    task.pop("missing_packet_fields", None)
    task.pop("not_worker_ready_reason", None)
    return {
        "task_id": tid,
        "classification": "needs_worker_fix",
        "reason": "complete design-handoff packet returned for bounded Worker retry",
        "repair_kind": repair_kind,
        "source_head_sha": source_head_sha,
        "retry_count": retry_count + 1,
    }


def is_generic_integration_repair_candidate(
    task: dict[str, Any],
    repair_kind: str | None,
    *,
    agent_lock_releasable: bool,
) -> bool:
    tid = task_id(task)
    explicit_kind = str(task.get("integration_repair_kind") or "").strip()
    existing_repair_child = str(task.get("type") or "") == "clean-rebuild" and bool(
        str(task.get("integration_repair_parent_id") or task.get("repair_source_task_id") or "").strip()
        or (
            str(task.get("source_lane") or "") == "dispatcher_integration_repair"
            and str(task.get("source_task_id") or "").strip()
        )
    )
    return bool(
        tid
        and not tid.startswith("SRC-")
        and not is_repository_hygiene_integration(task)
        and str(task.get("type") or "") != "design-handoff-intake"
        and not existing_repair_child
        and str(task.get("status") or "") not in TERMINAL_TASK_STATUSES
        and str(task.get("status") or "") in {"needs_dispatcher_repair", "needs_worker_fix"}
        and str(task.get("integration_status") or "") in {"needs_worker_fix", "needs_dispatcher_repair"}
        and str(task.get("dispatcher_decision") or "") == "needs_dispatcher_repair"
        and explicit_kind == repair_kind
        and explicit_kind == prefixed_integration_repair_kind(task)
        and explicit_kind in set(INTEGRATION_REPAIR_MARKERS.values())
        and not explicit_manual_or_high_risk_route(task)
        and task_lock_is_available(task)
        and agent_lock_releasable
        and is_complete_worker_packet_v2(task)
        and bool(normalize_branch(task.get("branch") or task.get("github_branch")))
        and bool(integration_repair_source_head_sha(task))
        and integration_repair_retry_count(task) < MAX_INTEGRATION_REPAIR_WORKER_RETRIES
    )


def is_normalized_finalize_scope_repair(task: dict[str, Any]) -> bool:
    status = str(task.get("status") or "")
    integration_status = str(task.get("integration_status") or "")
    missing = {
        str(item).strip()
        for item in task.get("missing_packet_fields") or []
        if str(item).strip()
    }
    current_reason = " ".join(
        str(task.get(field) or "")
        for field in (
            "repair_request",
            "not_worker_ready_reason",
            "status_reason",
            "dispatcher_decision_reason",
            "next_action",
        )
    ).lower()
    current_scope_failure = "allowed_paths" in missing or any(
        marker in current_reason
        for marker in (
            "worker_finalize_failed_outside_allowed_paths",
            "outside allowed_paths",
            "outside allowed paths",
            "outside_allowed_paths",
        )
    )
    return (
        status in {"needs_dispatcher_repair", "planned"}
        and integration_status in {"", "needs_dispatcher_repair", "returned_to_worker"}
        and str(task.get("dispatcher_decision") or "") == "needs_dispatcher_repair"
        and str(task.get("packet_status") or "") == "needs_dispatcher_repair"
        and str(task.get("normalization_status") or "") == "needs_dispatcher_repair"
        and current_scope_failure
    )


def integration_repair_complexity(kind: str, paths: list[str]) -> str:
    count = len(paths)
    if count > 80:
        return "XL"
    if count > 30:
        return "L"
    if count > 8 or kind == "behavior_preservation":
        return "M"
    return "S"


def integration_repair_profiles(complexity: str) -> tuple[str, list[str]]:
    if complexity in {"L", "XL"}:
        return "auto-worker-5.5max", ["auto-worker-5.5max", "auto-worker-5.5"]
    if complexity == "M":
        return "auto-worker-5.5", ["auto-worker-5.5", "auto-worker-5.3"]
    return "auto-worker-5.3", ["auto-worker-5.3", "auto-worker-5.3-mini"]


def repair_child_for(
    tasks: list[dict[str, Any]],
    parent_id: str,
    source_head_sha: str = "",
) -> dict[str, Any] | None:
    for task in tasks:
        if not isinstance(task, dict) or str(task.get("type") or "") != "clean-rebuild":
            continue
        source_id = str(task.get("integration_repair_parent_id") or task.get("source_task_id") or "").strip()
        if source_id != parent_id or str(task.get("status") or "") in TERMINAL_TASK_STATUSES:
            continue
        if source_head_sha:
            child_source_head = (
                integration_repair_source_head_sha(task)
                or valid_source_head_sha(task.get("source_head_sha"))
                or valid_source_head_sha(task.get("clean_rebuild_source_head_sha"))
            )
            if child_source_head != source_head_sha:
                continue
        return task
    return None


def detach_stale_repair_children(
    parent: dict[str, Any],
    tasks: list[dict[str, Any]],
    now: str,
) -> dict[str, Any] | None:
    child_ids = list_paths(parent.get("integration_repair_child_ids"))
    repair_kind = integration_repair_kind(parent)
    if not child_ids or repair_kind not in set(INTEGRATION_REPAIR_MARKERS.values()):
        return None
    children_by_id = {
        task_id(task): task
        for task in tasks
        if isinstance(task, dict) and task_id(task) in child_ids
    }
    if len(children_by_id) != len(child_ids) or any(
        str(child.get("status") or "") not in {"stale_or_superseded", "duplicate_linked"}
        for child in children_by_id.values()
    ):
        return None

    child_id_set = set(child_ids)
    remaining_blockers = [
        blocker for blocker in list_paths(parent.get("blocked_by")) if blocker not in child_id_set
    ]
    for field in (
        "integration_repair_child_ids",
        "split_into",
        "repository_pr_decomposition",
        "independent_work_units",
        "synthesis_required",
    ):
        parent.pop(field, None)
    if remaining_blockers:
        parent["blocked_by"] = remaining_blockers
    else:
        parent.pop("blocked_by", None)
    parent.update(
        {
            "status": "needs_dispatcher_repair",
            "integration_status": "needs_worker_fix",
            "dispatcher_decision": "needs_dispatcher_repair",
            "packet_status": "needs_dispatcher_repair",
            "normalization_status": "needs_dispatcher_repair",
            "worker_ready": False,
            "lock": "free",
            "next_owner": "Dispatcher",
            "next_role": "auto_dispatcher",
            "requires_human_attention": False,
            "status_reason": "stale repair children cannot satisfy the current Integrator failure",
            "dispatcher_repaired_at": now,
            "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
        }
    )
    return {
        "task_id": task_id(parent),
        "classification": "stale_repair_children_detached",
        "reason": "terminal stale repair children were detached before creating a current-head repair packet",
        "child_ids": child_ids,
        "repair_kind": repair_kind,
    }


def restore_collapsed_integration_repair(
    parent: dict[str, Any],
    tasks: list[dict[str, Any]],
    now: str,
) -> dict[str, Any] | None:
    parent_id = task_id(parent)
    repair_kind = str(parent.get("integration_repair_kind") or "").strip()
    source_head_sha = integration_repair_source_head_sha(parent)
    if (
        not parent_id
        or not is_repository_hygiene_integration(parent)
        or repair_kind not in set(INTEGRATION_REPAIR_MARKERS.values())
        or str(parent.get("status") or "") != "integration_requested"
        or str(parent.get("integration_status") or "") != "pending"
        or list_paths(parent.get("integration_repair_child_ids"))
        or not source_head_sha
    ):
        return None

    orphaned_repairs = [
        child
        for child in tasks
        if isinstance(child, dict)
        and str(child.get("type") or "") == "clean-rebuild"
        and str(child.get("clean_rebuild_route") or "") == "auto_integrator_repair"
        and str(child.get("status") or "") in TERMINAL_TASK_STATUSES
        and str(child.get("integration_repair_parent_id") or child.get("source_task_id") or "").strip()
        == parent_id
        and integration_repair_source_head_sha(child) == source_head_sha
        and str(child.get("status_reason") or "").strip() == COLLAPSED_REPAIR_REASON
    ]
    if not orphaned_repairs:
        return None

    repair_request = ""
    history = parent.get("status_history")
    if isinstance(history, list):
        for item in reversed(history):
            if not isinstance(item, dict):
                continue
            candidate = str(item.get("reason") or "").strip()
            if next(
                (
                    kind
                    for marker, kind in INTEGRATION_REPAIR_MARKERS.items()
                    if candidate.lower().startswith(marker)
                ),
                None,
            ) == repair_kind:
                repair_request = candidate
                break
    if not repair_request:
        return None

    parent.update(
        {
            "status": "needs_dispatcher_repair",
            "integration_status": "needs_worker_fix",
            "dispatcher_decision": "needs_dispatcher_repair",
            "packet_status": "needs_dispatcher_repair",
            "normalization_status": "needs_dispatcher_repair",
            "worker_ready": False,
            "lock": "free",
            "next_owner": "Dispatcher",
            "next_role": "auto_dispatcher",
            "requires_human_attention": False,
            "repair_request": repair_request,
            "status_reason": "restored focused integration repair after invalid decomposition collapse",
            "dispatcher_repaired_at": now,
            "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
        }
    )
    return {
        "task_id": parent_id,
        "classification": "collapsed_integration_repair_restored",
        "reason": "orphaned current-head repair evidence restored to Dispatcher routing",
        "source_head_sha": source_head_sha,
        "orphaned_child_ids": sorted(task_id(child) for child in orphaned_repairs),
    }


def integration_repair_child_id(parent_id: str, used_ids: set[str]) -> str:
    base = f"CRB-{parent_id.upper()}"
    value = base
    suffix = 2
    while value in used_ids:
        value = f"{base}-{suffix}"
        suffix += 1
    used_ids.add(value)
    return value


def build_integration_repair_child(
    parent: dict[str, Any],
    child_id: str,
    kind: str,
    now: str,
    owner_authorization: dict[str, Any] | None = None,
) -> dict[str, Any]:
    parent_id = task_id(parent)
    changed = current_task_paths(parent)
    tests = integration_repair_test_paths(parent)
    allowed = sorted({*changed, *tests})
    complexity = integration_repair_complexity(kind, allowed)
    recommended_agent, eligible_profiles = integration_repair_profiles(complexity)
    if owner_authorization:
        complexity = "L" if complexity in {"S", "M"} else complexity
        recommended_agent = "auto-worker-5.5max"
        eligible_profiles = ["auto-worker-5.5max", "auto-worker-5.5"]
    checks = ["git diff --check"]
    if tests:
        checks.insert(0, "python -m pytest " + " ".join(tests) + " -q")
    reason = str(parent.get("repair_request") or "Integrator requested a current-develop rebuild.")
    branch = normalize_branch(parent.get("branch") or parent.get("github_branch"))
    head_sha = integration_repair_source_head_sha(parent)
    base = {
        "id": child_id,
        "title": f"Repair integration for {parent_id}",
        "status": "planned",
        "priority": str(parent.get("priority") or "P1"),
        "complexity": complexity,
        "type": "clean-rebuild",
        "worker_ready": False,
        "recommended_agent": recommended_agent,
        "eligible_worker_profiles": eligible_profiles,
        "allowed_paths": allowed,
        "forbidden_paths": list_paths(parent.get("forbidden_paths")) or DEFAULT_REPAIR_FORBIDDEN_PATHS,
        "changed_paths": changed,
        "checks": checks,
        "acceptance_criteria": [
            "Rebuild the useful source PR intent on current develop instead of copying stale files wholesale.",
            "Resolve the exact Integrator blocker recorded in integration_repair_reason.",
            "Preserve all current develop behavior unless an explicit owner-approved replacement is recorded.",
            "Pass every required check and return a normal Worker result for Integrator review.",
        ],
        "context_docs": [
            "docs/automation/REPOSITORY_HYGIENE_CYCLE.md",
            "AiStudio/Task_manager/integrator_direct_merge.json",
            *([str(owner_authorization["decision_path"])] if owner_authorization else []),
        ],
        "source_file": "AiStudio/Task_manager/integrator_direct_merge.json",
        "source_task_id": parent_id,
        "parent_task_id": parent_id,
        "repair_source_task_id": parent_id,
        "integration_repair_parent_id": parent_id,
        "integration_repair_kind": kind,
        "integration_repair_reason": reason,
        "integration_repair_source_head_sha": head_sha,
        "source_branch": branch,
        "source_head_sha": head_sha,
        "clean_rebuild_source_branch": branch,
        "clean_rebuild_source_head_sha": head_sha,
        "clean_rebuild_route": "auto_integrator_repair",
        "source_lane": "dispatcher_integration_repair",
        "provenance": {
            "source": "dispatcher_integration_repair.py",
            "source_task_id": parent_id,
            "source_branch": branch,
            "source_head_sha": head_sha,
            "repair_kind": kind,
            "created_at": now,
        },
        "worker_instructions": [
            "Verify that source_branch still resolves to source_head_sha before using it; stop with evidence if the source moved.",
            "Read the source PR branch and compare every touched path with current develop before editing.",
            "Implement only useful source intent; do not replace newer target behavior with stale source files.",
            "Resolve integration_repair_reason and keep the repair inside allowed_paths.",
            "Run every script_action and preserve exact failure evidence if the repair remains blocked.",
        ],
        "existing_behavior": [
            "Current develop is the behavior baseline and must remain available after the repair.",
            "The source PR is input evidence, not authority to overwrite newer target behavior.",
        ],
        "preserve_contract": [
            "Preserve current commands, schemas, public functions, tests, docs contracts and automation entrypoints.",
            "Any intentional removal requires explicit owner-approved replacement evidence.",
        ],
        "lock": {"state": "free", "by": None, "at": None, "expires_at": None},
        "created_at": now,
    }
    if owner_authorization:
        base.update({
            "source_risk_class": "high",
            "requires_strong_review": True,
            "direct_merge_authorized": False,
            "owner_authorization": deepcopy(owner_authorization),
        })
        base["worker_instructions"].insert(
            0,
            "Use the owner authorization only to rebuild the exact source intent; it does not authorize direct merge or removal of current behavior.",
        )
    child = apply_v2_packet(base, now)
    child["dispatcher_decision_reason"] = "Dispatcher created Worker Packet v2 from a repairable Integrator failure."
    child["status_reason"] = "ready for current-develop integration repair"
    return child


def migrate_decomposition_child_blocked_by(
    child: dict[str, Any],
    dependency_ids: list[str],
) -> bool:
    if str(child.get("type") or "") != "clean-rebuild":
        return False
    if str(child.get("integration_repair_kind") or "") != "proactive_pr_decomposition":
        return False
    if str(child.get("status") or "") in TERMINAL_TASK_STATUSES:
        return False

    sibling_dependencies = {str(item) for item in dependency_ids}
    if not sibling_dependencies:
        return False

    before = json.dumps(child, ensure_ascii=False, sort_keys=True)
    existing_blocked_by = list_paths(child.get("blocked_by"))
    preserved_blocked_by = sorted({item for item in existing_blocked_by if item not in sibling_dependencies})
    next_depends_on = sorted(set(list_paths(child.get("depends_on"))).union(sibling_dependencies))

    child["depends_on"] = next_depends_on
    if preserved_blocked_by:
        child["blocked_by"] = preserved_blocked_by
    else:
        child.pop("blocked_by", None)
    return json.dumps(child, ensure_ascii=False, sort_keys=True) != before


def migrate_existing_decomposition_dependencies(tasks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    children_by_parent: dict[str, dict[str, dict[str, Any]]] = {}
    for task in tasks:
        if not isinstance(task, dict):
            continue
        if str(task.get("integration_repair_kind") or "") != "proactive_pr_decomposition":
            continue
        parent_id = str(task.get("integration_repair_parent_id") or "").strip()
        slice_key = str(task.get("decomposition_slice_key") or "").strip()
        if parent_id and slice_key:
            children_by_parent.setdefault(parent_id, {})[slice_key] = task

    migrations: list[dict[str, Any]] = []
    for parent in tasks:
        if not isinstance(parent, dict):
            continue
        parent_id = task_id(parent)
        plan = parent.get("repository_pr_decomposition")
        children = children_by_parent.get(parent_id)
        if not parent_id or not isinstance(plan, dict) or not children:
            continue
        child_ids = {task_id(child) for child in children.values() if task_id(child)}
        implementation_slice_keys = {
            slice_key
            for slice_key, child in children.items()
            if set(list_paths(child.get("decomposition_categories"))).intersection(
                DECOMPOSITION_IMPLEMENTATION_CATEGORIES
            )
        }
        for item in plan.get("slices") or []:
            if not isinstance(item, dict):
                continue
            slice_key = str(item.get("slice_key") or "").strip()
            child = children.get(slice_key)
            if child is None:
                continue
            categories = set(
                list_paths(child.get("decomposition_categories"))
                or list_paths(item.get("categories"))
            )
            dependency_keys = set(list_paths(item.get("depends_on")))
            if "tests" in categories and not categories.intersection(
                DECOMPOSITION_IMPLEMENTATION_CATEGORIES
            ):
                dependency_keys.update(implementation_slice_keys)
            item["depends_on"] = sorted(dependency_keys)
            dependency_ids = []
            for dependency in sorted(dependency_keys):
                sibling = children.get(dependency)
                dependency_id = task_id(sibling) if sibling is not None else dependency
                if dependency_id in child_ids:
                    dependency_ids.append(dependency_id)
            if migrate_decomposition_child_blocked_by(child, dependency_ids):
                migrations.append({
                    "task_id": task_id(child),
                    "parent_task_id": parent_id,
                    "depends_on": list_paths(child.get("depends_on")),
                    "preserved_blocked_by": list_paths(child.get("blocked_by")),
                })
    return migrations


def link_parent_to_repair_child(
    parent: dict[str, Any],
    child_id: str,
    now: str,
    owner_authorization: dict[str, Any] | None = None,
    source_head_sha: str | None = None,
) -> bool:
    before = json.dumps(parent, ensure_ascii=False, sort_keys=True)
    blocked_by = [str(item) for item in parent.get("blocked_by") or [] if str(item or "").strip()]
    split_into = [str(item) for item in parent.get("split_into") or [] if str(item or "").strip()]
    parent.update({
        "status": "blocked_by_dependency",
        "integration_status": "repair_in_progress",
        "dispatcher_decision": "split_into_children",
        "packet_status": "split_into_children",
        "normalization_status": "integration_repair_split",
        "worker_ready": False,
        "lock": "free",
        "blocked_by": sorted(set([*blocked_by, child_id])),
        "split_into": sorted(set([*split_into, child_id])),
        "integration_repair_child_ids": sorted(set([*split_into, child_id])),
        "next_owner": "worker_pool",
        "next_role": "auto_workers",
        "requires_human_attention": False,
        "repair_owner": "Worker",
        "next_action": f"Wait for {child_id}, then re-enter Integrator on current develop.",
        "status_reason": f"Dispatcher created {child_id} for the repairable Integrator failure.",
        "dispatcher_repaired_at": now,
        "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
    })
    if source_head_sha:
        parent["integration_repair_source_head_sha"] = source_head_sha
    if owner_authorization:
        parent["owner_authorization"] = deepcopy(owner_authorization)
    return json.dumps(parent, ensure_ascii=False, sort_keys=True) != before


def decomposition_child_id(parent_id: str, slice_key: str) -> str:
    return f"CRB-{parent_id.upper()}-{slice_key.upper()}"


def build_decomposition_child(
    parent: dict[str, Any],
    child_id: str,
    slice_plan: dict[str, Any],
    dependency_ids: list[str],
    now: str,
) -> dict[str, Any]:
    parent_id = task_id(parent)
    paths = list_paths(slice_plan.get("paths"))
    branch = normalize_branch(parent.get("branch") or parent.get("github_branch"))
    head_sha = str(parent.get("repository_hygiene_head_sha") or parent.get("head_sha") or "").strip()
    checks = [str(item) for item in slice_plan.get("deterministic_checks") or [] if str(item or "").strip()]
    base = {
        "id": child_id,
        "title": str(slice_plan.get("title") or f"{parent_id} decomposition slice"),
        "status": "planned",
        "priority": str(parent.get("priority") or "P1"),
        "complexity": str(slice_plan.get("complexity") or "M"),
        "type": "clean-rebuild",
        "worker_ready": False,
        "recommended_agent": slice_plan.get("recommended_agent"),
        "eligible_worker_profiles": list(slice_plan.get("eligible_worker_profiles") or []),
        "model_candidates": list(slice_plan.get("model_candidates") or []),
        "capability_profile_hint": slice_plan.get("capability_profile_hint"),
        "reasoning_effort_hint": slice_plan.get("reasoning_effort_hint"),
        "delegation_hint": "forbidden",
        "independent_work_units": 1,
        "synthesis_required": False,
        "execution_kind": slice_plan.get("execution_kind") or "llm_write",
        "execution_lane": slice_plan.get("execution_lane"),
        "allowed_paths": paths,
        "forbidden_paths": list_paths(parent.get("forbidden_paths")) or DEFAULT_REPAIR_FORBIDDEN_PATHS,
        "changed_paths": paths,
        "checks": checks or ["git diff --check"],
        "depends_on": sorted(set(dependency_ids)),
        "acceptance_criteria": [
            "Rebuild only this decomposition slice from the source PR intent on current develop.",
            "Do not copy or modify paths owned by sibling decomposition slices.",
            "Preserve current develop behavior unless the source PR explicitly adds compatible behavior.",
            "Pass every deterministic check and return a normal Worker result for Integrator review.",
        ],
        "context_docs": [
            "docs/automation/REPOSITORY_HYGIENE_CYCLE.md",
            "docs/automation/REPOSITORY_PR_DECOMPOSITION.md",
        ],
        "source_file": "AiStudio/Task_manager/task_queue.json",
        "source_task_id": parent_id,
        "parent_task_id": parent_id,
        "repair_source_task_id": parent_id,
        "integration_repair_parent_id": parent_id,
        "integration_repair_kind": "proactive_pr_decomposition",
        "source_branch": branch,
        "source_head_sha": head_sha,
        "clean_rebuild_source_branch": branch,
        "clean_rebuild_source_head_sha": head_sha,
        "clean_rebuild_route": "repository_pr_decomposition",
        "decomposition_slice_key": slice_plan.get("slice_key"),
        "decomposition_categories": list(slice_plan.get("categories") or []),
        "source_lane": "dispatcher_integration_repair",
        "provenance": {
            "source": "repository_pr_decomposition_planner.py",
            "source_task_id": parent_id,
            "source_branch": branch,
            "source_head_sha": head_sha,
            "slice_key": slice_plan.get("slice_key"),
            "created_at": now,
        },
        "worker_instructions": [
            "Verify that source_branch still resolves to source_head_sha before using it.",
            "Review the complete source PR for intent, but edit only this slice's allowed_paths.",
            "Compare every slice path with current develop and preserve newer target behavior.",
            "Run every script_action and record the actual model selected by the central Router.",
            "Stop with needs_worker_fix if the slice requires a sibling path not present in allowed_paths.",
        ],
        "existing_behavior": [
            "Current develop is the behavior baseline for every decomposition slice.",
            "Sibling slices own disjoint paths and must remain independently integrable.",
        ],
        "preserve_contract": [
            "Do not remove current commands, schemas, public functions, tests, docs contracts or automation entrypoints.",
            "Any intentional replacement requires explicit owner evidence and Integrator review.",
        ],
        "lock": {"state": "free", "by": None, "at": None, "expires_at": None},
        "created_at": now,
    }
    child = apply_v2_packet(base, now)
    child["dispatcher_decision_reason"] = "Dispatcher split a large repository PR into a model-routed Worker Packet v2 slice."
    child["status_reason"] = "ready for independent repository PR slice implementation"
    return child


DECOMPOSITION_EXECUTION_EVIDENCE_FIELDS = (
    "claimed_at",
    "started_at",
    "worker_result_commit",
    "worker_report",
    "worktree",
    "execution_id",
    "worker_pid",
)


STALE_CLEAN_REBUILD_CLAIM_FIELDS = (
    "worker_id",
    "machine_id",
    "branch",
    "github_branch",
    "started_at",
    "claimed_at",
    "lock_expires_at",
    "worker_result_commit",
    "worker_report",
    "execution_id",
    "worktree",
    "worker_pid",
    "last_agent_report",
    "integration_report",
    "not_worker_ready_reason",
    "merge_commit",
    "worker_check_evidence",
    "current_context_verified_at",
    "current_context_verified_by",
    "current_context_reviewed_by",
    "current_context_review",
    "commits",
)


def clear_clean_rebuild_claim(task: dict[str, Any]) -> None:
    for field in STALE_CLEAN_REBUILD_CLAIM_FIELDS:
        task.pop(field, None)
    lock = task.get("lock")
    if isinstance(lock, dict):
        lock["state"] = "free"
        lock["by"] = None
        lock["at"] = None
        lock["expires_at"] = None
        lock["released_at"] = utc_now()
        lock["released_by"] = "scripts/agent_control/dispatcher_integration_repair.py"
        lock["release_reason"] = "stale central claim requeued"
    else:
        task["lock"] = "free"


def requeue_stale_clean_rebuild_central_claim(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("type") or "") != "clean-rebuild":
        return None
    if str(task.get("status") or "") != "in_progress":
        return None
    if str(task.get("integration_status") or "") != "returned_to_worker":
        return None
    if str(task.get("dispatcher_decision") or "") != "worker_ready":
        return None
    if str(task.get("status_reason") or "") != "central runner claimed task before isolated worker launch":
        return None
    if str(task.get("worker_result_commit") or task.get("worker_report") or task.get("last_agent_report") or "").strip():
        return None

    reason = (
        "Dispatcher re-queued clean-rebuild after a central claim that never started executing; "
        "stale claim state was cleared for a fresh Worker retry."
    )
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "at": now,
        "by": "dispatcher_integration_repair",
        "from": "in_progress",
        "to": "planned",
        "event": "claim_requeued",
        "reason": reason,
    })
    clear_clean_rebuild_claim(task)
    task.update({
        "status": "planned",
        "integration_status": "returned_to_worker",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "central worker claim was never started",
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "worker_ready": True,
        "next_owner": "worker_pool",
        "next_role": "auto_workers",
        "requires_human_attention": False,
        "repair_owner": "Worker",
        "status_reason": reason,
        "next_action": "Worker must claim a new run and execute the required checks; stale claim metadata has been cleared.",
        "status_history": history,
        "dispatcher_repaired_at": now,
        "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
    })
    return {
        "task_id": task_id(task),
        "classification": "needs_worker_fix",
        "reason": reason,
    }


def decomposition_child_is_refreshable(child: dict[str, Any], locks_path: Path) -> bool:
    if str(child.get("status") or "") not in {"planned", "blocked_by_dependency"}:
        return False
    if not locks_path.is_file():
        return False
    if not task_lock_is_available(child) or not agent_lock_is_available(locks_path, task_id(child)):
        return False
    return not any(child.get(field) not in (None, "", [], {}) for field in DECOMPOSITION_EXECUTION_EVIDENCE_FIELDS)


def repository_pr_has_green_exact_head(task: dict[str, Any]) -> bool:
    return bool(
        is_repository_hygiene_integration(task)
        and str(task.get("repository_hygiene_ci_state") or "").strip().lower() == "green"
        and re.fullmatch(r"[0-9a-fA-F]{40}", str(task.get("repository_hygiene_head_sha") or "").strip())
        and not list_paths(task.get("repository_hygiene_draft_prs"))
        and not list_paths(task.get("repository_hygiene_dirty_prs"))
    )


def refresh_repository_pr_live_ci_state(
    project_root: Path,
    base_ref: str,
    task: dict[str, Any],
    now: str,
) -> bool:
    if repository_pr_has_green_exact_head(task):
        return True
    branch = normalize_branch(task.get("branch") or task.get("github_branch"))
    if not branch:
        return False
    issues, evidence, _commands = repository_hygiene_live_ci_gate(
        project_root,
        base_ref,
        branch,
        task,
        task,
    )
    if issues or evidence.get("ci_green") is not True:
        return False
    task["repository_hygiene_ci_state"] = "green"
    task["repository_hygiene_ci_checked_at"] = now
    return repository_pr_has_green_exact_head(task)


def collapse_unstarted_decomposition_for_green_pr(
    parent: dict[str, Any],
    tasks: list[Any],
    locks_path: Path,
    now: str,
    *,
    project_root: Path | None = None,
    base_ref: str = "develop",
) -> dict[str, Any] | None:
    child_ids = list_paths(parent.get("integration_repair_child_ids"))
    if not child_ids:
        return None
    if (
        not repository_pr_has_green_exact_head(parent)
        and (
            project_root is None
            or not refresh_repository_pr_live_ci_state(project_root, base_ref, parent, now)
        )
    ):
        return None
    children_by_id = {
        task_id(item): item
        for item in tasks
        if isinstance(item, dict) and task_id(item) in child_ids
    }
    if len(children_by_id) != len(child_ids):
        return None
    if any(
        str(child.get("clean_rebuild_route") or "").strip() == "auto_integrator_repair"
        for child in children_by_id.values()
    ):
        return None
    if any(not decomposition_child_is_refreshable(child, locks_path) for child in children_by_id.values()):
        return None

    reason = COLLAPSED_REPAIR_REASON
    for child in children_by_id.values():
        child.update(
            {
                "status": "stale_or_superseded",
                "integration_status": "superseded_by_source_pr",
                "dispatcher_decision": "stale_or_superseded",
                "packet_status": "stale_or_superseded",
                "normalization_status": "stale_or_superseded",
                "worker_ready": False,
                "lock": "free",
                "next_owner": "none",
                "next_role": "none",
                "requires_human_attention": False,
                "status_reason": reason,
                "closed_at": now,
                "closed_by": "scripts/agent_control/dispatcher_integration_repair.py",
            }
        )

    previous_plan = parent.get("repository_pr_decomposition")
    if isinstance(previous_plan, dict):
        parent["repository_pr_decomposition_superseded"] = deepcopy(previous_plan)
    remaining_blockers = [
        blocker for blocker in list_paths(parent.get("blocked_by")) if blocker not in set(child_ids)
    ]
    for field in (
        "integration_repair_child_ids",
        "split_into",
        "repository_pr_decomposition",
        "independent_work_units",
        "synthesis_required",
    ):
        parent.pop(field, None)
    if remaining_blockers:
        parent["blocked_by"] = remaining_blockers
    else:
        parent.pop("blocked_by", None)
    parent.update(
        {
            "status": "agent_done",
            "integration_status": "needs_dispatcher",
            "dispatcher_decision": "needs_dispatcher_repair",
            "packet_status": "needs_dispatcher_repair",
            "normalization_status": "repository_hygiene_routed",
            "worker_ready": False,
            "lock": "free",
            "next_owner": "dispatcher",
            "next_role": "auto_dispatcher",
            "requires_human_attention": False,
            "status_reason": reason,
            "decomposition_superseded_at": now,
        }
    )
    return {
        "task_id": task_id(parent),
        "classification": "decomposition_superseded",
        "reason": reason,
        "child_ids": child_ids,
    }


def collapse_unstarted_decomposition_for_terminal_history(
    tasks: list[Any],
    history_tasks: list[Any],
    locks_path: Path,
    now: str,
) -> list[dict[str, Any]]:
    active_ids = {
        task_id(item)
        for item in tasks
        if isinstance(item, dict) and task_id(item)
    }
    terminal_parents = {
        task_id(item): item
        for item in history_tasks
        if (
            isinstance(item, dict)
            and task_id(item)
            and str(item.get("status") or "") in TERMINAL_TASK_STATUSES
        )
    }
    children_by_parent: dict[str, list[dict[str, Any]]] = {}
    for item in tasks:
        if not isinstance(item, dict):
            continue
        parent_id = str(
            item.get("integration_repair_parent_id")
            or item.get("parent_task_id")
            or item.get("source_task_id")
            or ""
        ).strip()
        if (
            not parent_id
            or parent_id in active_ids
            or parent_id not in terminal_parents
            or str(item.get("integration_repair_kind") or "") != "proactive_pr_decomposition"
        ):
            continue
        children_by_parent.setdefault(parent_id, []).append(item)

    collapsed: list[dict[str, Any]] = []
    for parent_id, children in children_by_parent.items():
        if any(
            str(child.get("clean_rebuild_route") or "").strip() == "auto_integrator_repair"
            for child in children
        ):
            continue
        if any(not decomposition_child_is_refreshable(child, locks_path) for child in children):
            continue
        for child in children:
            terminal = normalize_terminal_task(
                {
                    **child,
                    "status": "stale_or_superseded",
                    "requires_human_attention": False,
                    "status_reason": TERMINAL_PARENT_DECOMPOSITION_REASON,
                    "closed_at": now,
                    "closed_by": "scripts/agent_control/dispatcher_integration_repair.py",
                },
                now=now,
            )
            terminal["integration_status"] = "superseded_by_terminal_parent"
            child.clear()
            child.update(terminal)
        collapsed.append(
            {
                "task_id": parent_id,
                "classification": "decomposition_superseded",
                "reason": TERMINAL_PARENT_DECOMPOSITION_REASON,
                "child_ids": [task_id(child) for child in children],
            }
        )
    return collapsed


def route_decomposition_refresh_blocked(parent: dict[str, Any], now: str, reason: str) -> dict[str, Any]:
    unchanged = (
        str(parent.get("status") or "") == "needs_dispatcher_repair"
        and str(parent.get("integration_status") or "") == "needs_dispatcher_repair"
        and str(parent.get("dispatcher_decision") or "") == "needs_dispatcher_repair"
        and str(parent.get("status_reason") or "") == reason
    )
    if unchanged:
        return {
            "task_id": task_id(parent),
            "classification": "decomposition_refresh_blocked_unchanged",
            "reason": reason,
        }
    parent.update(
        {
            "status": "needs_dispatcher_repair",
            "integration_status": "needs_dispatcher_repair",
            "dispatcher_decision": "needs_dispatcher_repair",
            "packet_status": "needs_dispatcher_repair",
            "normalization_status": "needs_dispatcher_repair",
            "worker_ready": False,
            "next_owner": "Dispatcher",
            "next_role": "auto_dispatcher",
            "requires_human_attention": False,
            "status_reason": reason,
            "repair_request": reason,
            "next_action": "Dispatcher must reconcile stale decomposition children before any Worker claim.",
            "dispatcher_repaired_at": now,
            "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
        }
    )
    return {
        "task_id": task_id(parent),
        "classification": "needs_dispatcher_repair",
        "reason": reason,
    }


def refresh_stale_decomposition_children(
    project_root: Path,
    parent: dict[str, Any],
    tasks: list[Any],
    base_ref: str,
    locks_path: Path,
    now: str,
) -> dict[str, Any] | None:
    child_ids = list_paths(parent.get("integration_repair_child_ids"))
    if not child_ids or not is_repository_hygiene_integration(parent):
        return None
    current_head = str(parent.get("repository_hygiene_head_sha") or parent.get("head_sha") or "").strip().lower()
    children_by_id = {
        task_id(item): item
        for item in tasks
        if isinstance(item, dict) and task_id(item) in child_ids
    }
    if len(children_by_id) != len(child_ids):
        return route_decomposition_refresh_blocked(parent, now, "decomposition child set is incomplete")
    child_heads = {
        str(child.get("source_head_sha") or child.get("clean_rebuild_source_head_sha") or "").strip().lower()
        for child in children_by_id.values()
    }
    if not any(re.fullmatch(r"[0-9a-f]{40}", head) for head in child_heads):
        plan = parent.get("repository_pr_decomposition")
        plan_head = str(plan.get("source_head_sha") or "").strip().lower() if isinstance(plan, dict) else ""
        if plan_head == current_head and re.fullmatch(r"[0-9a-f]{40}", plan_head):
            return None
        return route_decomposition_refresh_blocked(parent, now, "decomposition children have no valid source head evidence")
    if child_heads == {current_head}:
        return None
    if not re.fullmatch(r"[0-9a-f]{40}", current_head):
        return route_decomposition_refresh_blocked(parent, now, "updated repository PR has no valid source head SHA")
    branch = normalize_branch(parent.get("branch") or parent.get("github_branch"))
    branch_head, branch_error = verified_commit(project_root, branch)
    if branch_head != current_head:
        detail = branch_error or f"source branch resolves to {branch_head or 'no commit'}, expected {current_head}"
        return route_decomposition_refresh_blocked(parent, now, f"updated repository PR source is not exact: {detail}")
    paths, error = changed_paths(project_root, base_ref, branch)
    if error:
        return route_decomposition_refresh_blocked(parent, now, f"could not inspect updated repository PR: {error[:500]}")
    _, integration_paths = split_coordination_paths(paths, project_root=project_root)
    plan = build_decomposition_plan(parent, integration_paths)
    if plan.get("should_decompose") is not True:
        return route_decomposition_refresh_blocked(
            parent,
            now,
            str(plan.get("reason") or "updated repository PR no longer has a safe decomposition plan"),
        )
    slices = [item for item in plan.get("slices") or [] if isinstance(item, dict)]
    child_id_by_key = {
        str(item.get("slice_key") or ""): decomposition_child_id(task_id(parent), str(item.get("slice_key") or ""))
        for item in slices
    }
    if set(child_id_by_key.values()) != set(child_ids):
        return route_decomposition_refresh_blocked(parent, now, "updated repository PR changed the decomposition slice set")
    blocked_children = sorted(
        child_id for child_id, child in children_by_id.items() if not decomposition_child_is_refreshable(child, locks_path)
    )
    if blocked_children:
        return route_decomposition_refresh_blocked(
            parent,
            now,
            "stale decomposition children have active execution state: " + ", ".join(blocked_children),
        )

    refreshed_ids: list[str] = []
    previous_heads = sorted(head for head in child_heads if head)
    for item in slices:
        slice_key = str(item.get("slice_key") or "")
        child_id = child_id_by_key[slice_key]
        dependencies = [
            child_id_by_key[key]
            for key in item.get("depends_on") or []
            if key in child_id_by_key
        ]
        old_child = children_by_id[child_id]
        created_at = old_child.get("created_at")
        refreshed = build_decomposition_child(parent, child_id, item, dependencies, now)
        if created_at:
            refreshed["created_at"] = created_at
        refreshed["decomposition_refreshed_at"] = now
        refreshed["decomposition_previous_source_heads"] = previous_heads
        old_child.clear()
        old_child.update(refreshed)
        refreshed_ids.append(child_id)

    parent["changed_paths"] = paths
    parent["integration_changed_paths"] = integration_paths
    link_parent_to_decomposition(parent, refreshed_ids, plan, now)
    parent["decomposition_refreshed_at"] = now
    parent["decomposition_previous_source_heads"] = previous_heads
    return {
        "task_id": task_id(parent),
        "classification": "decomposition_children_refreshed",
        "reason": "unstarted decomposition children refreshed to the current repository PR head",
        "child_ids": refreshed_ids,
        "previous_source_heads": previous_heads,
        "source_head_sha": current_head,
    }


def link_parent_to_decomposition(
    parent: dict[str, Any],
    child_ids: list[str],
    plan: dict[str, Any],
    now: str,
) -> bool:
    before = json.dumps(parent, ensure_ascii=False, sort_keys=True)
    parent.update(
        {
            "status": "blocked_by_dependency",
            "integration_status": "repair_in_progress",
            "dispatcher_decision": "split_into_children",
            "dispatcher_decision_reason": "large repository PR decomposed into independent Worker Packet v2 slices",
            "packet_status": "split_into_children",
            "normalization_status": "repository_pr_decomposed",
            "worker_ready": False,
            "lock": "free",
            "blocked_by": sorted(set(child_ids)),
            "split_into": sorted(set(child_ids)),
            "integration_repair_child_ids": sorted(set(child_ids)),
            "repository_pr_decomposition": deepcopy(plan),
            "independent_work_units": len(child_ids),
            "synthesis_required": True,
            "capability_profile_hint": "maximum_coherent",
            "model_candidates": ["gpt-5.6-sol", "gpt-5.6-terra"],
            "reasoning_effort_hint": "max",
            "next_owner": "worker_pool",
            "next_role": "auto_workers",
            "requires_human_attention": False,
            "repair_owner": "Worker",
            "next_action": "Run every independent slice, integrate finalized child results, then close the source PR.",
            "status_reason": "Dispatcher created model-aware decomposition slices for the large source PR.",
            "dispatcher_repaired_at": now,
            "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
        }
    )
    return json.dumps(parent, ensure_ascii=False, sort_keys=True) != before


def readiness_path_for(queue_path: Path) -> Path:
    return queue_path.parent / "pr_readiness_report.identity_filtered.json"


def integrator_direct_merge_path_for(queue_path: Path) -> Path:
    return queue_path.parent / "integrator_direct_merge.json"


def locks_path_for(queue_path: Path) -> Path:
    return queue_path.parent / "agent_locks.json"


def readiness_routes(path: Path) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError):
        return {}
    priority = {
        "needs_human": 50,
        "needs_worker_fix": 40,
        "needs_integrator_review": 30,
        "coordination_only": 20,
        "cleanup_candidate": 10,
        "duplicate": 10,
    }
    routes: dict[str, dict[str, Any]] = {}
    for item in data.get("items") or []:
        if not isinstance(item, dict):
            continue
        classification = str(item.get("classification") or "")
        if classification == "needs_integrator_review" and "high-risk" in str(item.get("reason") or "").lower():
            classification = "needs_human"
        task_ids = item.get("task_ids") or [item.get("task_id")]
        for raw_tid in task_ids:
            tid = str(raw_tid or "").strip()
            if not tid:
                continue
            current = routes.get(tid)
            if current and priority.get(str(current.get("classification")), 0) >= priority.get(classification, 0):
                continue
            routed = dict(item)
            routed["classification"] = classification
            routes[tid] = routed
    return routes


def apply_readiness_route(task: dict[str, Any], route: dict[str, Any], now: str) -> dict[str, Any] | None:
    classification = str(route.get("classification") or "")
    reason = str(route.get("reason") or "readiness classification requires routing")
    tid = task_id(task)
    if classification == "coordination_only":
        task["status"] = "stale_or_superseded"
        task["integration_status"] = "closed_coordination_only"
        task["dispatcher_decision"] = "stale_or_superseded"
        task["worker_ready"] = False
        task["lock"] = "free"
        task["closed_at"] = now
        task["closed_by"] = "dispatcher_integration_repair"
        task["status_reason"] = reason
        task["next_owner"] = "none"
        return {"task_id": tid, "classification": classification, "reason": reason}
    if classification == "needs_worker_fix":
        lowered_reason = reason.lower()
        explicit_worker_fix = bool(route.get("worker_fix_required")) or any(
            marker in lowered_reason
            for marker in (
                "implementation defect",
                "worker fix required",
                "tests failed",
                "syntax error",
                "missing required output",
            )
        )
        if not explicit_worker_fix:
            task["integration_status"] = "needs_integrator_review"
            task["dispatcher_decision"] = "needs_integrator_review"
            task["worker_ready"] = False
            task["lock"] = "free"
            task["status_reason"] = reason
            task["next_owner"] = "Integrator"
            task["next_role"] = "integrator_review"
            return {"task_id": tid, "classification": "needs_integrator_review", "reason": reason}
        task["status"] = "planned"
        task["integration_status"] = "returned_to_worker"
        task["dispatcher_decision"] = "worker_ready"
        task["worker_ready"] = True
        task["lock"] = "free"
        task["status_reason"] = reason
        task["next_owner"] = "worker_pool"
        task["next_role"] = "auto_workers"
        task["dispatcher_repaired_at"] = now
        task["dispatcher_repaired_by"] = "scripts/agent_control/dispatcher_integration_repair.py"
        return {"task_id": tid, "classification": classification, "reason": reason}
    if classification == "needs_human":
        task["status"] = "needs_human"
        task["integration_status"] = "needs_human"
        task["dispatcher_decision"] = "needs_human"
        task["worker_ready"] = False
        task["lock"] = "free"
        task["status_reason"] = reason
        task["next_owner"] = "human"
        return {"task_id": tid, "classification": classification, "reason": reason}
    if classification == "needs_integrator_review":
        task["integration_status"] = "needs_integrator_review"
        task["dispatcher_decision"] = "needs_integrator_review"
        task["worker_ready"] = False
        task["lock"] = "free"
        task["status_reason"] = reason
        task["next_owner"] = "Integrator"
        task["next_role"] = "integrator_review"
        return {"task_id": tid, "classification": classification, "reason": reason}
    return None


def route_failed_finalize_scope_result(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("status") or "") != "agent_done":
        return None
    if str(task.get("integration_status") or "") not in {"pending", "pending_checks"}:
        return None
    evidence = task.get("worker_check_evidence")
    if not isinstance(evidence, dict) or evidence.get("ok") is not False:
        return None
    if not has_unresolved_finalize_scope_failure(task):
        return None
    tid = task_id(task)
    reason = str(evidence.get("reason") or task.get("status_reason") or "worker finalize failed outside allowed paths")
    task["status"] = "needs_dispatcher_repair"
    task["integration_status"] = "needs_dispatcher_repair"
    task["dispatcher_decision"] = "needs_dispatcher_repair"
    task["packet_status"] = "needs_dispatcher_repair"
    task["normalization_status"] = "needs_dispatcher_repair"
    task["worker_ready"] = False
    task["lock"] = "free"
    task["next_owner"] = "dispatcher"
    task["next_role"] = "auto_dispatcher"
    task["status_reason"] = reason
    task["not_worker_ready_reason"] = "worker_finalize_failed_outside_allowed_paths"
    task["repair_request"] = (
        "Previous worker result changed paths outside the packet allowed_paths. "
        "Dispatcher must widen allowed_paths when valid, or split/rebuild the task packet."
    )
    existing_missing = [str(item).strip() for item in task.get("missing_packet_fields") or [] if str(item).strip()]
    task["missing_packet_fields"] = sorted(set(existing_missing + ["allowed_paths"]))
    task["repair_owner"] = "dispatcher"
    task["next_action"] = "Dispatcher must inspect worker_changed_paths and rebuild the Worker Packet v2 before another claim."
    task["dispatcher_repaired_at"] = now
    task["dispatcher_repaired_by"] = "scripts/agent_control/dispatcher_integration_repair.py"
    return {"task_id": tid, "classification": "needs_dispatcher_repair", "reason": task["repair_request"]}


def integrator_report_exhausted(path: Path) -> bool:
    if not path.exists():
        return False
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError):
        return False
    return str(data.get("status") or "") in {"no_candidates", "no_ready_items", "routed_no_direct_merge_candidates"} and not data.get("ready")


def route_exhausted_integration_request(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("status") or "") != "integration_requested":
        return None
    if str(task.get("integration_status") or "") not in {"", "pending", "pending_checks"}:
        return None
    if str(task.get("dispatcher_decision") or "") != "worker_ready":
        return None
    tid = task_id(task)
    task["integration_status"] = "needs_integrator_review"
    task["dispatcher_decision"] = "needs_integrator_review"
    task["worker_ready"] = False
    task["lock"] = "free"
    task["next_owner"] = "Integrator"
    task["next_role"] = "integrator_review"
    task["dispatcher_repaired_at"] = now
    task["dispatcher_repaired_by"] = "scripts/agent_control/dispatcher_integration_repair.py"
    task["status_reason"] = "Integrator direct-merge pass exhausted candidates; Dispatcher routed this integration request to explicit Integrator review."
    return {"task_id": tid, "classification": "needs_integrator_review", "reason": task["status_reason"]}


def current_task_paths(task: dict[str, Any]) -> list[str]:
    return list_paths(
        task.get("changed_paths")
        or task.get("integration_changed_paths")
        or task.get("allowed_paths")
        or task.get("code_refs")
    )


def current_result_paths(task: dict[str, Any]) -> list[str]:
    return list_paths(
        task.get("changed_paths")
        or task.get("integration_changed_paths")
        or task.get("worker_changed_paths")
        or task.get("coordination_changed_paths")
    )


def repair_pending_integration_coordination_only(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("status") or "") != "integration_requested":
        return None
    if str(task.get("integration_status") or "") not in {"", "pending", "pending_checks"}:
        return None
    if str(task.get("dispatcher_decision") or "") != "worker_ready":
        return None
    tid = task_id(task)
    paths = current_result_paths(task)
    if not paths or not all(is_coordination_path(path) for path in paths):
        return None
    task["status"] = "stale_or_superseded"
    task["integration_status"] = "closed_coordination_only"
    task["dispatcher_decision"] = "stale_or_superseded"
    task["worker_ready"] = False
    task["lock"] = "free"
    task["closed_at"] = now
    task["closed_by"] = "dispatcher_integration_repair"
    task["status_reason"] = "Dispatcher verified the pending integration request only contains coordination paths; no product integration remains."
    task["next_owner"] = "none"
    task["next_role"] = "none"
    task["coordination_changed_paths"] = paths
    task["integration_changed_paths"] = []
    return {"task_id": tid, "classification": "coordination_only", "reason": task["status_reason"]}


def repair_existing_dispatcher_scope_failure(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if not is_normalized_finalize_scope_repair(task):
        return None
    if not has_unresolved_finalize_scope_failure(task):
        return None
    tid = task_id(task)
    paths = current_result_paths(task)
    if not paths or not all(is_coordination_path(path) for path in paths):
        return None
    product_scope_refs = [path for path in build_code_refs(task) if not is_coordination_path(path)]
    if product_scope_refs:
        return None
    task["status"] = "stale_or_superseded"
    task["integration_status"] = "closed_coordination_only"
    task["dispatcher_decision"] = "stale_or_superseded"
    task["worker_ready"] = False
    task["lock"] = "free"
    task["closed_at"] = now
    task["closed_by"] = "dispatcher_integration_repair"
    task["status_reason"] = FINALIZE_SCOPE_COORDINATION_ONLY_REASON
    task["next_owner"] = "none"
    task["coordination_changed_paths"] = paths
    for field in ("repair_request", "missing_packet_fields", "repair_owner", "next_action", "not_worker_ready_reason"):
        task.pop(field, None)
    return {"task_id": tid, "classification": "coordination_only", "reason": task["status_reason"]}


def retry_existing_dispatcher_scope_failure_same_scope(
    task: dict[str, Any],
    now: str,
    *,
    agent_lock_available: bool,
) -> dict[str, Any] | None:
    if not is_normalized_finalize_scope_repair(task):
        return None
    if not task_lock_is_available(task) or not agent_lock_available or not has_unresolved_finalize_scope_failure(task):
        return None

    result_paths = current_result_paths(task)
    product_scope_refs = [path for path in build_code_refs(task) if not is_coordination_path(path)]
    if result_paths and all(is_coordination_path(path) for path in result_paths) and not product_scope_refs:
        return None

    allowed_paths = task.get("allowed_paths")
    if not isinstance(allowed_paths, list) or not list_paths(allowed_paths):
        return None

    tid = task_id(task)
    retry_count = int(task.get("finalize_scope_same_scope_retry_count") or 0)
    evidence = task.get("finalize_scope_same_scope_retry_evidence")
    if not isinstance(evidence, list):
        evidence = []
    evidence.append({
        "at": now,
        "reason": str(task.get("status_reason") or task.get("repair_request") or "worker finalize failed outside allowed paths"),
        "worker_check_evidence": deepcopy(task.get("worker_check_evidence")),
        "worker_changed_paths": list_paths(task.get("worker_changed_paths")),
        "result_paths": result_paths,
        "abandoned_claim_count": len(task.get("abandoned_claims") or []),
    })
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []

    if retry_count >= MAX_FINALIZE_SCOPE_SAME_SCOPE_RETRIES:
        reason = (
            "Worker repeated worker_finalize_failed_outside_allowed_paths after the single permitted "
            "same-scope retry; owner review is required before any packet scope change."
        )
        history.append({
            "at": now,
            "by": "dispatcher_integration_repair",
            "from": "needs_dispatcher_repair",
            "to": "needs_human",
            "event": "same_scope_retry_exhausted",
            "reason": reason,
        })
        task.update({
            "status": "needs_human",
            "integration_status": "needs_human",
            "dispatcher_decision": "needs_human",
            "packet_status": "needs_human",
            "normalization_status": "needs_human",
            "worker_ready": False,
            "lock": "free",
            "next_owner": "human",
            "next_role": "owner_review",
            "repair_owner": "human",
            "requires_human_attention": True,
            "status_reason": reason,
            "repair_request": "Owner must decide whether to widen, split, or retire the Worker Packet after repeated scope rejection.",
            "next_action": "Do not retry automatically. Preserve the existing packet scope until owner review is recorded.",
            "finalize_scope_same_scope_retry_evidence": evidence,
            "status_history": history,
            "dispatcher_repaired_at": now,
            "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
        })
        return {"task_id": tid, "classification": "needs_human", "reason": reason, "retry_count": retry_count}

    reason = (
        "Dispatcher returned the existing Worker Packet v2 to Worker for one same-scope retry after "
        "worker_finalize_failed_outside_allowed_paths."
    )
    history.append({
        "at": now,
        "by": "dispatcher_integration_repair",
        "from": "needs_dispatcher_repair",
        "to": "planned",
        "event": "same_scope_retry_returned_to_worker",
        "reason": reason,
    })
    instructions = task.get("worker_instructions")
    if not isinstance(instructions, list):
        instructions = []
    retry_instruction = (
        "The prior attempt was rejected for paths outside this packet's existing allowed_paths. "
        "Retry only the existing Worker Packet v2 scope; do not widen or infer new paths."
    )
    if retry_instruction not in instructions:
        instructions.append(retry_instruction)
    existing_missing = [str(item).strip() for item in task.get("missing_packet_fields") or [] if str(item).strip()]
    remaining_missing = [item for item in existing_missing if item != "allowed_paths"]
    task.update({
        "status": "planned",
        "integration_status": "returned_to_worker",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "bounded same-scope retry after finalize scope rejection",
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "worker_ready": True,
        "lock": "free",
        "next_owner": "worker_pool",
        "next_role": "auto_workers",
        "repair_owner": "Worker",
        "requires_human_attention": False,
        "status_reason": reason,
        "repair_request": "Prior worker attempt was rejected for outside allowed paths; retry the unchanged Worker Packet v2 scope once.",
        "next_action": "Worker must stay inside the existing allowed_paths and preserve rejected-attempt evidence.",
        "worker_instructions": instructions,
        "finalize_scope_same_scope_retry_count": retry_count + 1,
        "finalize_scope_same_scope_retry_evidence": evidence,
        "status_history": history,
        "dispatcher_repaired_at": now,
        "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
    })
    if remaining_missing:
        task["missing_packet_fields"] = remaining_missing
    else:
        task.pop("missing_packet_fields", None)
    task.pop("not_worker_ready_reason", None)
    return {"task_id": tid, "classification": "needs_worker_fix", "reason": reason, "retry_count": retry_count + 1}


def exact_source_ref(project_root: Path, branch: str, source_head_sha: str) -> str | None:
    candidates = [branch]
    if not branch.startswith("origin/"):
        candidates.append(f"origin/{branch}")
    for candidate in candidates:
        resolved_sha, _ = verified_commit(project_root, candidate)
        if resolved_sha == source_head_sha:
            return candidate
    return None


def behavior_snapshot_at_ref(
    project_root: Path,
    ref: str,
    paths: list[str],
) -> dict[str, list[str]]:
    snapshot: dict[str, list[str]] = {}
    for rel_path in paths:
        show = run_git(project_root, ["show", f"{ref}:{rel_path}"])
        if show.returncode != 0:
            continue
        if is_line_preservation_path(rel_path):
            tokens = line_contract_tokens(show.stdout)
        elif is_code_behavior_path(rel_path):
            tokens = sorted(behavior_tokens(show.stdout, rel_path))
        else:
            tokens = []
        if tokens:
            snapshot[rel_path] = tokens
    return snapshot


def candidate_delta_three_way_check(
    project_root: Path,
    base_ref: str,
    source_ref: str,
    paths: list[str],
) -> dict[str, Any]:
    merge_base = subprocess.run(
        ["git", "merge-base", base_ref, source_ref],
        cwd=project_root,
        text=True,
        capture_output=True,
        check=False,
    )
    ancestor = merge_base.stdout.strip()
    if merge_base.returncode != 0 or not ancestor:
        return {"ok": False, "reason": "candidate_merge_base_missing"}
    diff = subprocess.run(
        ["git", "diff", "--binary", "--full-index", ancestor, source_ref, "--", *paths],
        cwd=project_root,
        capture_output=True,
        check=False,
    )
    if diff.returncode != 0:
        return {"ok": False, "reason": "candidate_diff_failed"}
    if not diff.stdout:
        return {"ok": False, "reason": "candidate_diff_empty"}
    apply_check = subprocess.run(
        ["git", "apply", "--3way", "--check", "-"],
        cwd=project_root,
        input=diff.stdout,
        capture_output=True,
        check=False,
    )
    return {
        "ok": apply_check.returncode == 0,
        "reason": (
            "candidate_delta_applies_to_current_base"
            if apply_check.returncode == 0
            else "candidate_delta_conflicts_with_current_base"
        ),
        "merge_base": ancestor,
        "patch_bytes": len(diff.stdout),
    }


def test_only_legacy_regression(
    before: dict[str, list[str]],
    after: dict[str, list[str]],
) -> dict[str, list[str]]:
    lost = {
        path: sorted(set(tokens) - set(after.get(path, [])))
        for path, tokens in before.items()
        if set(tokens) - set(after.get(path, []))
    }
    if not lost:
        return {}
    for path, tokens in lost.items():
        normalized = path.replace("\\", "/").lower()
        if not (
            normalized.startswith(("tests/", "test/"))
            or Path(normalized).name.startswith("test_")
        ):
            return {}
        if any(not token.startswith("test_") for token in tokens):
            return {}
    return lost


def requeue_stale_semantic_review_for_integrator(
    project_root: Path,
    task: dict[str, Any],
    now: str,
    *,
    base_ref: str,
    agent_lock_releasable: bool,
) -> dict[str, Any] | None:
    if (
        str(task.get("type") or "") != "clean-rebuild"
        or str(task.get("status") or "") != "needs_human"
        or str(task.get("integration_status") or "") != "needs_integrator_review"
        or str(task.get("dispatcher_decision") or "") != "needs_integrator_review"
        or not str(task.get("repair_request") or "").strip().lower().startswith("semantic_regression_detected:")
        or int(task.get("semantic_recheck_policy_version") or 0) >= SEMANTIC_RECHECK_POLICY_VERSION
        or not task_lock_is_available(task)
        or not agent_lock_releasable
    ):
        return None
    if explicit_manual_or_high_risk_route(
        {
            **task,
            "dispatcher_decision": "",
            "next_owner": "",
            "requires_human_attention": False,
        }
    ):
        return None

    branch = normalize_branch(task.get("branch") or task.get("github_branch"))
    source_head_sha = valid_source_head_sha(
        task.get("worker_result_commit")
        or task.get("integration_repair_source_head_sha")
        or task.get("head_sha")
    )
    changed = list_paths(task.get("integration_changed_paths") or task.get("changed_paths"))
    allowed = list_paths(task.get("allowed_paths"))
    forbidden = list_paths(task.get("forbidden_paths"))
    if not branch or not source_head_sha or not changed or not allowed:
        return None
    source_ref = exact_source_ref(project_root, branch, source_head_sha)
    if not source_ref:
        return None
    actual_changed, diff_error = changed_paths(project_root, base_ref, source_ref)
    if diff_error or actual_changed != changed:
        return None
    if any(
        not any(fnmatch.fnmatchcase(path, pattern) for pattern in allowed)
        or any(fnmatch.fnmatchcase(path, pattern) for pattern in forbidden)
        for path in changed
    ):
        return None
    behavior_before = behavior_snapshot_at_ref(project_root, base_ref, changed)
    behavior_after = behavior_snapshot_at_ref(project_root, source_ref, changed)
    legacy_lost_behavior = test_only_legacy_regression(behavior_before, behavior_after)
    behavior_report = detect_behavior_regression(behavior_before, behavior_after)
    delta_check = candidate_delta_three_way_check(
        project_root,
        base_ref,
        source_ref,
        changed,
    )
    if not delta_check["ok"]:
        return None
    recheck_basis = (
        "related_test_replacement"
        if legacy_lost_behavior and behavior_report["ok"]
        else "task_scoped_three_way_delta"
    )

    prior_reason = str(task.get("repair_request") or "").strip()
    evidence = task.get("semantic_recheck_evidence")
    if not isinstance(evidence, list):
        evidence = []
    evidence.append({
        "at": now,
        "policy_version": SEMANTIC_RECHECK_POLICY_VERSION,
        "prior_reason": prior_reason,
        "branch": branch,
        "worker_result_commit": source_head_sha,
        "changed_paths": changed,
        "legacy_lost_behavior": legacy_lost_behavior,
        "behavior_report": behavior_report,
        "recheck_basis": recheck_basis,
        "candidate_delta_check": delta_check,
    })
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "at": now,
        "by": "dispatcher_integration_repair",
        "from": "needs_human",
        "to": "integration_requested",
        "event": "semantic_policy_recheck_requested",
        "reason": "Integrator semantic policy changed; re-evaluate the pinned result once with required checks.",
    })

    task.update({
        "status": "integration_requested",
        "integration_status": "pending_checks",
        "dispatcher_decision": "integration_ready",
        "dispatcher_decision_reason": "stale semantic review requires one current-policy Integrator recheck",
        "packet_status": "integration_ready",
        "normalization_status": "integration_ready",
        "worker_ready": False,
        "lock": "free",
        "next_owner": "Integrator",
        "next_role": "auto_integrator",
        "requires_human_attention": False,
        "integration_changed_paths": changed,
        "semantic_recheck_policy_version": SEMANTIC_RECHECK_POLICY_VERSION,
        "semantic_recheck_evidence": evidence,
        "status_history": history,
        "dispatcher_repaired_at": now,
        "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
    })
    for field in ("repair_request", "missing_packet_fields", "repair_owner", "next_action", "not_worker_ready_reason"):
        task.pop(field, None)
    return {
        "task_id": task_id(task),
        "branch": branch,
        "integration_changed_paths": changed,
        "reason": "Dispatcher requested one current-policy semantic recheck from Integrator.",
        "semantic_recheck_policy_version": SEMANTIC_RECHECK_POLICY_VERSION,
        "recheck_basis": recheck_basis,
    }


def repair_existing_integrator_review_coordination_only(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("status") or "") != "integration_requested":
        return None
    if str(task.get("integration_status") or "") != "needs_integrator_review":
        return None
    if str(task.get("dispatcher_decision") or "") != "needs_integrator_review":
        return None
    tid = task_id(task)
    paths = current_result_paths(task)
    if not paths or not all(is_coordination_path(path) for path in paths):
        return None
    task["status"] = "stale_or_superseded"
    task["integration_status"] = "closed_coordination_only"
    task["dispatcher_decision"] = "stale_or_superseded"
    task["worker_ready"] = False
    task["lock"] = "free"
    task["closed_at"] = now
    task["closed_by"] = "dispatcher_integration_repair"
    task["status_reason"] = "Dispatcher verified Integrator review candidate only contains coordination paths; no product integration remains."
    task["next_owner"] = "none"
    task["coordination_changed_paths"] = paths
    return {"task_id": tid, "classification": "coordination_only", "reason": task["status_reason"]}


def route_existing_source_pr_close_retry(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("type") or "") != "repository_hygiene_integration":
        return None
    source_close_failed = str(task.get("source_pr_close_status") or "") == "failed"
    source_close_blocked = str(task.get("finalization_status") or "") == "blocked_source_pr_open"
    retry_state_present = str(task.get("integration_status") or "") == "needs_source_pr_close"
    if not (source_close_failed or source_close_blocked or retry_state_present):
        return None
    if str(task.get("source_pr_close_status") or "") == "closed":
        return None
    if not str(task.get("merge_commit") or "").strip():
        return None
    if int(task.get("source_pr_close_retry_count") or 0) >= 3:
        return None
    tid = task_id(task)
    before = json.dumps(task, ensure_ascii=False, sort_keys=True)
    task["status"] = "integration_requested"
    task["packet_status"] = "integration_ready"
    task["normalization_status"] = "repository_hygiene_routed"
    task["integration_status"] = "needs_source_pr_close"
    task["dispatcher_decision"] = "needs_integrator_review"
    task["worker_ready"] = False
    task["lock"] = "free"
    task["owner"] = "integrator"
    task["next_owner"] = "Integrator"
    task["next_role"] = "integrator_review"
    task["requires_human_attention"] = False
    task["source_pr_close_retry_count"] = max(1, int(task.get("source_pr_close_retry_count") or 0))
    task["next_action"] = "Auto Integrator must retry source PR closure without reapplying the integrated payload."
    task["status_reason"] = "Dispatcher routed a transient source PR closure failure back to Auto Integrator."
    for field in (
        "closed_at",
        "closed_by",
        "closed_duplicate_claim_at",
        "closed_duplicate_claim_by",
        "not_worker_ready_reason",
    ):
        task.pop(field, None)
    if json.dumps(task, ensure_ascii=False, sort_keys=True) == before:
        return None
    task["dispatcher_repaired_at"] = now
    task["dispatcher_repaired_by"] = "scripts/agent_control/dispatcher_integration_repair.py"
    return {"task_id": tid, "classification": "needs_integrator_review", "reason": task["status_reason"]}


def repair_existing_worker_fix(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if str(task.get("status") or "") != "needs_worker_fix":
        return None
    tid = task_id(task)
    paths = current_task_paths(task)
    code_refs = build_code_refs(task)
    declared_product_refs = [path for path in code_refs if not is_coordination_path(path)]
    if paths and all(is_coordination_path(path) for path in paths) and not declared_product_refs:
        task["status"] = "stale_or_superseded"
        task["integration_status"] = "closed_coordination_only"
        task["dispatcher_decision"] = "stale_or_superseded"
        task["worker_ready"] = False
        task["lock"] = "free"
        task["closed_at"] = now
        task["closed_by"] = "dispatcher_integration_repair"
        task["status_reason"] = WORKER_FIX_COORDINATION_ONLY_REASON
        task["next_owner"] = "none"
        task["coordination_changed_paths"] = paths
        return {"task_id": tid, "classification": "coordination_only", "reason": task["status_reason"]}

    if code_refs:
        repaired = apply_v2_packet(task, now)
        task.clear()
        task.update(repaired)
        return {"task_id": tid, "classification": "worker_ready_repaired", "reason": "repaired needs_worker_fix packet for worker retry"}
    return None


def reopen_false_coordination_only_closure(task: dict[str, Any], now: str) -> dict[str, Any] | None:
    if (
        str(task.get("status") or "") != "stale_or_superseded"
        or str(task.get("integration_status") or "") != "closed_coordination_only"
        or str(task.get("dispatcher_decision") or "") != "stale_or_superseded"
        or str(task.get("closed_by") or "") != "dispatcher_integration_repair"
        or str(task.get("status_reason") or "") not in COORDINATION_ONLY_CLOSURE_REASONS
        or str(task.get("lock") or "free") not in {"free", "released"}
    ):
        return None
    paths = current_result_paths(task)
    code_refs = build_code_refs(task)
    declared_product_refs = [path for path in code_refs if not is_coordination_path(path)]
    if not paths or not all(is_coordination_path(path) for path in paths) or not declared_product_refs:
        return None

    closure_evidence = {
        "closed_at": task.get("closed_at"),
        "closed_by": task.get("closed_by"),
        "changed_paths": paths,
        "declared_product_refs": declared_product_refs,
        "reopened_at": now,
        "reopened_by": "scripts/agent_control/dispatcher_integration_repair.py",
        "reason": "declared product scope disproves the previous coordination-only closure",
    }
    repaired = apply_v2_packet(task, now)
    repaired["integration_status"] = "returned_to_worker"
    repaired["lock"] = "free"
    repaired["false_coordination_closure_recovery"] = closure_evidence
    repaired.pop("closed_at", None)
    repaired.pop("closed_by", None)
    task.clear()
    task.update(repaired)
    return {
        "task_id": task_id(task),
        "classification": "worker_ready_repaired",
        "reason": closure_evidence["reason"],
    }


def release_task_locks(locks_path: Path, task_ids: set[str], now: str) -> int:
    if not task_ids or not locks_path.exists():
        return 0
    try:
        locks = load_json(locks_path)
    except (OSError, json.JSONDecodeError):
        return 0
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return 0
    changed = 0
    for lock in lock_list:
        if not isinstance(lock, dict):
            continue
        if str(lock.get("task_id") or "") not in task_ids:
            continue
        if str(lock.get("state") or "") not in {"locked", "in_progress", "review"}:
            continue
        lock["state"] = "released"
        lock["released_at"] = now
        lock["notes"] = "dispatcher readiness route assigned explicit next owner"
        changed += 1
    if changed:
        locks["updated_at"] = now
        write_json(locks_path, locks)
    return changed


def changed_paths(project_root: Path, base_ref: str, branch: str) -> tuple[list[str], str | None]:
    verify = run_git(project_root, ["rev-parse", "--verify", branch])
    if verify.returncode != 0:
        return [], (verify.stderr or verify.stdout or "branch not found").strip()
    diff = run_git(project_root, ["diff", "--name-only", f"{base_ref}...{branch}"])
    if diff.returncode != 0:
        return [], (diff.stderr or diff.stdout or "diff failed").strip()
    return sorted(path for path in diff.stdout.splitlines() if path.strip()), None


def verified_commit(project_root: Path, ref: str) -> tuple[str | None, str | None]:
    verify = run_git(project_root, ["rev-parse", "--verify", f"{ref}^{{commit}}"])
    if verify.returncode != 0:
        return None, (verify.stderr or verify.stdout or f"commit not found: {ref}").strip()
    return verify.stdout.strip().lower(), None


def preserve_recovery_commit(project_root: Path, recorded_sha: str) -> tuple[str | None, str | None]:
    """Protect an otherwise dangling recovery commit from local object pruning."""

    preservation_ref = f"refs/aistudio/recovery-evidence/{recorded_sha}"
    update = run_git(project_root, ["update-ref", preservation_ref, recorded_sha])
    if update.returncode != 0:
        return None, (update.stderr or update.stdout or "failed to preserve recovery commit").strip()
    return preservation_ref, None


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def archived_recovery_source(
    archive_root: Path | None,
    repository: str,
    recorded_sha: str,
) -> dict[str, Any] | None:
    if archive_root is None or not archive_root.is_dir() or not re.fullmatch(r"[0-9a-f]{40}", recorded_sha):
        return None
    repository_root = archive_root / repository
    if not repository_root.is_dir():
        return None
    for manifest_path in repository_root.rglob(f"*{recorded_sha[:12]}*.json"):
        try:
            manifest = load_json(manifest_path)
        except (OSError, json.JSONDecodeError, ValueError):
            continue
        if str(manifest.get("sha") or "").strip().lower() != recorded_sha:
            continue
        bundle_name = str(manifest.get("bundle") or "").strip()
        expected_checksum = str(manifest.get("bundle_sha256") or "").strip().lower()
        if not bundle_name or not expected_checksum:
            continue
        bundle_path = manifest_path.parent / bundle_name
        if not bundle_path.is_file() or file_sha256(bundle_path) != expected_checksum:
            continue
        ledger = manifest.get("recovery_ledger")
        ledger_evidence = (
            {
                key: ledger.get(key)
                for key in (
                    "ledger_id",
                    "ledger_sha256",
                    "row_id",
                    "classification",
                    "recommended_next_action",
                )
                if ledger.get(key) is not None
            }
            if isinstance(ledger, dict)
            else None
        )
        return {
            "manifest_ref": manifest_path.relative_to(archive_root).as_posix(),
            "_manifest_path": str(manifest_path),
            "_bundle_path": str(bundle_path),
            "bundle_sha256": expected_checksum,
            "branch": str(manifest.get("branch") or ""),
            "sha": recorded_sha,
            "archived_at": manifest.get("archived_at"),
            "recovery_ledger": ledger_evidence,
        }
    return None


def restore_recovery_source_from_archive(
    project_root: Path,
    archived: dict[str, Any],
) -> tuple[str | None, str | None]:
    recorded_sha = str(archived.get("sha") or "").strip().lower()
    bundle_path = str(archived.get("_bundle_path") or "").strip()
    heads = run_git(project_root, ["bundle", "list-heads", bundle_path])
    if heads.returncode != 0:
        return None, (heads.stderr or heads.stdout or "git bundle list-heads failed").strip()
    source_ref = ""
    for line in heads.stdout.splitlines():
        parts = line.strip().split(maxsplit=1)
        if len(parts) == 2 and parts[0].lower() == recorded_sha:
            source_ref = parts[1]
            break
    if not source_ref:
        return None, f"bundle does not advertise recorded SHA {recorded_sha}"
    preservation_ref = f"refs/aistudio/recovery-evidence/{recorded_sha}"
    fetch = run_git(
        project_root,
        ["fetch", "--no-tags", bundle_path, f"{source_ref}:{preservation_ref}"],
    )
    if fetch.returncode != 0:
        return None, (fetch.stderr or fetch.stdout or "git fetch from bundle failed").strip()
    restored_sha, restore_error = verified_commit(project_root, preservation_ref)
    if restored_sha != recorded_sha:
        return None, restore_error or f"restored ref does not match recorded SHA {recorded_sha}"
    return preservation_ref, None


def inspect_repository_recovery_source(
    project_root: Path,
    task: dict[str, Any],
    base_ref: str,
    branch: str,
) -> dict[str, Any]:
    """Bind branch recovery to its recorded commit and classify Git failures."""

    recorded_sha = str(task.get("repository_hygiene_head_sha") or "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", recorded_sha):
        return {
            "status": "source_unavailable",
            "recorded_sha": recorded_sha,
            "reason": "repository recovery task has no valid recorded source SHA",
            "git_error": "repository_hygiene_head_sha must be a full 40-character commit SHA",
        }

    _, base_error = verified_commit(project_root, base_ref)
    if base_error:
        return {
            "status": "retryable_error",
            "recorded_sha": recorded_sha,
            "reason": "repository recovery base ref is temporarily unavailable",
            "git_error": base_error,
        }

    branch_head_sha = None
    branch_error = "repository recovery source branch is not recorded"
    if branch:
        branch_head_sha, branch_error = verified_commit(project_root, branch)

    if branch_head_sha == recorded_sha:
        source_ref = branch
        resolution_status = "recorded_ref_exact"
    else:
        recovered_sha, recorded_error = verified_commit(project_root, recorded_sha)
        if recovered_sha != recorded_sha:
            mismatch = (
                f"source ref head {branch_head_sha} does not match recorded SHA {recorded_sha}"
                if branch_head_sha
                else branch_error
            )
            return {
                "status": "source_unavailable",
                "recorded_sha": recorded_sha,
                "branch_head_sha": branch_head_sha,
                "reason": "repository recovery source ref and recorded commit evidence are unavailable",
                "git_error": "; ".join(value for value in (mismatch, recorded_error) if value),
            }
        source_ref = recorded_sha
        resolution_status = "recovered_from_recorded_sha"

    diff = run_git(project_root, ["diff", "--name-only", f"{base_ref}...{source_ref}"])
    if diff.returncode != 0:
        return {
            "status": "retryable_error",
            "recorded_sha": recorded_sha,
            "branch_head_sha": branch_head_sha,
            "source_ref": source_ref,
            "reason": "repository recovery diff inspection failed temporarily",
            "git_error": (diff.stderr or diff.stdout or "diff failed").strip(),
        }
    return {
        "status": "resolved",
        "recorded_sha": recorded_sha,
        "branch_head_sha": branch_head_sha,
        "source_ref": source_ref,
        "resolution_status": resolution_status,
        "paths": sorted(path for path in diff.stdout.splitlines() if path.strip()),
    }


def recovery_ref_at_recorded_head(
    project_root: Path,
    task: dict[str, Any],
    base_ref: str,
) -> tuple[str | None, str]:
    """Compatibility wrapper for callers introduced by the first repair revision."""

    branch = normalize_branch(task.get("branch") or task.get("source_branch") or task.get("pr_branch"))
    source = inspect_repository_recovery_source(project_root, task, base_ref, branch)
    recorded_sha = str(source.get("recorded_sha") or "")
    if source.get("status") != "resolved":
        return None, recorded_sha
    return str(source.get("source_ref") or "") or None, recorded_sha


def build_event(project: str, tid: str, event: str, reason: str, payload: dict[str, Any]) -> dict[str, Any]:
    now = utc_now()
    suffix = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
    return {
        "schema_version": 1,
        "event_id": f"{event}-{suffix}-{tid.lower()}",
        "created_at": now,
        "project": project,
        "event": event,
        "role": "dispatcher_integration_repair",
        "task_id": tid,
        "canonical_target_id": f"task:{tid}" if tid else None,
        "severity": "info",
        "reason": reason,
        "payload": payload,
    }


def project_id_for_events(project_root: Path) -> str:
    version_path = project_root / "PROJECT_VERSION.json"
    if version_path.exists():
        try:
            version = load_json(version_path)
        except (OSError, json.JSONDecodeError):
            version = {}
        project_id = str(version.get("project_id") or "").strip()
        if project_id:
            return project_id
    return project_root.name


def repair_queue(
    project_root: Path,
    queue_path: Path,
    events_path: Path,
    base_ref: str,
    *,
    apply: bool,
    branch_archive_root: Path | None = None,
) -> dict[str, Any]:
    data = load_json(queue_path)
    result = deepcopy(data)
    tasks = result.get("tasks")
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")

    repaired: list[dict[str, Any]] = []
    closed: list[dict[str, Any]] = []
    blocked: list[dict[str, Any]] = []
    classified: list[dict[str, Any]] = []
    worker_ready_repaired: list[dict[str, Any]] = []
    repair_packets_created: list[dict[str, Any]] = []
    repair_packets_linked: list[dict[str, Any]] = []
    decomposed_parents: list[dict[str, Any]] = []
    decomposition_children_refreshed: list[dict[str, Any]] = []
    decomposition_superseded: list[dict[str, Any]] = []
    pending_repair_children: list[dict[str, Any]] = []
    skipped = 0
    now = utc_now()
    event_project = project_id_for_events(project_root)
    archive_cache: dict[str, dict[str, Any] | None] = {}
    routes = readiness_routes(readiness_path_for(queue_path))
    exhausted_integrator = integrator_report_exhausted(integrator_direct_merge_path_for(queue_path))
    lock_path = locks_path_for(queue_path)
    history_path = queue_path.parent / "task_history.json"
    history_tasks: list[Any] = []
    if history_path.is_file():
        history_data = load_json(history_path)
        raw_history_tasks = history_data.get("tasks")
        if isinstance(raw_history_tasks, list):
            history_tasks = raw_history_tasks
    used_ids = {
        task_id(item)
        for item in tasks
        if isinstance(item, dict) and task_id(item)
    }
    dependency_migrations = migrate_existing_decomposition_dependencies(tasks)
    decomposition_superseded.extend(
        collapse_unstarted_decomposition_for_terminal_history(
            tasks,
            history_tasks,
            lock_path,
            now,
        )
    )

    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        tid = task_id(task) or f"index-{index}"
        stale_repair_children = detach_stale_repair_children(task, tasks, now)
        if stale_repair_children:
            classified.append(stale_repair_children)
        collapsed_repair = restore_collapsed_integration_repair(task, tasks, now)
        if collapsed_repair:
            classified.append(collapsed_repair)
        decomposition_collapse = collapse_unstarted_decomposition_for_green_pr(
            task,
            tasks,
            lock_path,
            now,
            project_root=project_root,
            base_ref=base_ref,
        )
        if decomposition_collapse:
            decomposition_superseded.append(decomposition_collapse)
        decomposition_refresh = refresh_stale_decomposition_children(
            project_root,
            task,
            tasks,
            base_ref,
            lock_path,
            now,
        )
        if decomposition_refresh:
            if decomposition_refresh.get("classification") == "decomposition_children_refreshed":
                decomposition_children_refreshed.append(decomposition_refresh)
            elif decomposition_refresh.get("classification") == "decomposition_refresh_blocked_unchanged":
                pass
            else:
                classified.append(decomposition_refresh)
            continue
        route = routes.get(tid)
        if (
            route
            and str(task.get("status") or "") == "agent_done"
            and str(task.get("integration_status") or "") in {"pending", "pending_checks"}
        ):
            routed = apply_readiness_route(task, route, now)
            if routed:
                classified.append(routed)
                continue
        failed_scope_route = route_failed_finalize_scope_result(task, now)
        if failed_scope_route:
            classified.append(failed_scope_route)
            continue
        stale_claim_repaired = requeue_stale_clean_rebuild_central_claim(task, now)
        if stale_claim_repaired:
            worker_ready_repaired.append(stale_claim_repaired)
            continue
        source_close_retry = route_existing_source_pr_close_retry(task, now)
        if source_close_retry:
            classified.append(source_close_retry)
            continue
        pending_coordination_route = repair_pending_integration_coordination_only(task, now)
        if pending_coordination_route:
            closed.append(pending_coordination_route)
            continue
        if exhausted_integrator:
            exhausted_route = route_exhausted_integration_request(task, now)
            if exhausted_route:
                classified.append(exhausted_route)
                continue
        agent_lock_releasable = agent_lock_is_releasable_for_worker_retry(lock_path, tid)
        semantic_recheck = requeue_stale_semantic_review_for_integrator(
            project_root,
            task,
            now,
            base_ref=base_ref,
            agent_lock_releasable=agent_lock_releasable,
        )
        if semantic_recheck:
            repaired.append(semantic_recheck)
            continue
        design_handoff_owner_authorization = (
            owner_high_risk_design_handoff_authorization(project_root, task, base_ref)
            if str(task.get("type") or "") == "design-handoff-intake" and high_risk_repair_request(task)
            else None
        )
        design_handoff_owner_retry = consume_owner_authorized_design_handoff_retry(
            task,
            now,
            design_handoff_owner_authorization,
            agent_lock_releasable=agent_lock_releasable,
        )
        if design_handoff_owner_retry:
            worker_ready_repaired.append(design_handoff_owner_retry)
            continue
        design_handoff_retry = migrate_existing_design_handoff_integrator_failure(
            task,
            now,
            agent_lock_releasable=agent_lock_releasable,
        )
        if design_handoff_retry:
            worker_ready_repaired.append(design_handoff_retry)
            continue
        owner_authorization = (
            owner_high_risk_repair_authorization(project_root, task)
            if high_risk_repair_request(task)
            else None
        )
        repair_kind = integration_repair_kind(task, owner_authorization)
        legacy_hygiene_repair = bool(
            repair_kind
            and is_repository_hygiene_integration(task)
            and str(task.get("status") or "") not in TERMINAL_TASK_STATUSES
            and str(task.get("integration_status") or "") in {"needs_integrator_review", "needs_worker_fix", "needs_dispatcher_repair"}
        )
        generic_packet_repair = is_generic_integration_repair_candidate(
            task,
            repair_kind,
            agent_lock_releasable=agent_lock_releasable,
        )
        if legacy_hygiene_repair or generic_packet_repair:
            source_head_sha = integration_repair_source_head_sha(task)
            existing_child = repair_child_for(
                [*tasks, *pending_repair_children],
                tid,
                source_head_sha,
            )
            if existing_child is None:
                child_id = integration_repair_child_id(tid, used_ids)
                existing_child = build_integration_repair_child(
                    task,
                    child_id,
                    repair_kind,
                    now,
                    owner_authorization,
                )
                pending_repair_children.append(existing_child)
                repair_packets_created.append({
                    "task_id": child_id,
                    "source_task_id": tid,
                    "classification": "worker_ready_repaired",
                    "reason": str(task.get("repair_request") or "repairable Integrator failure"),
                    **({"source_head_sha": source_head_sha} if source_head_sha else {}),
                    **(
                        {"owner_authorization_id": owner_authorization["decision_id"]}
                        if owner_authorization
                        else {}
                    ),
                })
            child_id = task_id(existing_child)
            if link_parent_to_repair_child(
                task,
                child_id,
                now,
                owner_authorization,
                source_head_sha=source_head_sha or None,
            ) and not any(
                item.get("source_task_id") == tid for item in repair_packets_created
            ):
                repair_packets_linked.append({
                    "task_id": child_id,
                    "source_task_id": tid,
                    "reason": "linked existing integration repair child",
                })
            continue
        integration_repair_retry = requeue_failed_integration_repair_child(task, now)
        if integration_repair_retry:
            worker_ready_repaired.append(integration_repair_retry)
            continue
        false_coordination_recovery = reopen_false_coordination_only_closure(task, now)
        if false_coordination_recovery:
            worker_ready_repaired.append(false_coordination_recovery)
            continue
        worker_fix_repair = repair_existing_worker_fix(task, now)
        if worker_fix_repair:
            if worker_fix_repair.get("classification") == "coordination_only":
                closed.append(worker_fix_repair)
            else:
                worker_ready_repaired.append(worker_fix_repair)
            continue
        dispatcher_scope_repair = repair_existing_dispatcher_scope_failure(task, now)
        same_scope_retry = retry_existing_dispatcher_scope_failure_same_scope(
            task,
            now,
            agent_lock_available=agent_lock_is_available(lock_path, tid),
        )
        if same_scope_retry:
            if same_scope_retry.get("classification") == "needs_human":
                classified.append(same_scope_retry)
            else:
                worker_ready_repaired.append(same_scope_retry)
            continue
        if dispatcher_scope_repair:
            closed.append(dispatcher_scope_repair)
            continue
        integrator_review_repair = repair_existing_integrator_review_coordination_only(task, now)
        if integrator_review_repair:
            closed.append(integrator_review_repair)
            continue
        hygiene_integration = is_repository_hygiene_integration(task)
        recovery_task = str(task.get("type") or "") == "repository_hygiene_branch_recovery"
        integration_status = str(task.get("integration_status") or "")
        dispatcher_decision = str(task.get("dispatcher_decision") or "")
        ordinary_dispatcher_repair = (
            integration_status == "needs_dispatcher"
            and dispatcher_decision == "needs_dispatcher_repair"
        )
        recoverable_hygiene_repair = (
            hygiene_integration
            and str(task.get("status") or "") not in {"done", "finalized", "stale_or_superseded"}
            and integration_status in {"needs_dispatcher", "needs_dispatcher_repair", "pending", "pending_checks"}
            and dispatcher_decision in {"needs_dispatcher", "needs_dispatcher_repair", "integration_ready"}
        )
        recoverable_evidence_recheck = (
            recovery_task
            and integration_status in {"source_evidence_unavailable", "blocked_by_missing_environment"}
            and dispatcher_decision in {"needs_human", "blocked_by_missing_environment"}
        )
        if not ordinary_dispatcher_repair and not recoverable_hygiene_repair and not recoverable_evidence_recheck:
            skipped += 1
            continue
        branch = normalize_branch(task.get("branch") or task.get("source_branch") or task.get("pr_branch"))
        paths = list_paths(task.get("changed_paths") or task.get("integration_changed_paths"))
        error = None
        if recovery_task:
            attempted_ref = branch
            source = inspect_repository_recovery_source(project_root, task, base_ref, branch)
            source_status = str(source.get("status") or "")
            recorded_sha = str(source.get("recorded_sha") or "").strip().lower()
            archived = None
            if source_status == "source_unavailable" and recorded_sha:
                if recorded_sha not in archive_cache:
                    archive_cache[recorded_sha] = archived_recovery_source(
                        branch_archive_root,
                        event_project,
                        recorded_sha,
                    )
                archived = archive_cache[recorded_sha]
            if archived and not apply:
                classified.append({
                    "task_id": tid,
                    "classification": "archive_restore_available",
                    "reason": "verified branch bundle can restore the recorded recovery commit",
                    "recorded_sha": recorded_sha,
                    "manifest_ref": archived["manifest_ref"],
                    "bundle_sha256": archived["bundle_sha256"],
                })
                continue
            if archived and apply:
                preservation_ref, archive_error = restore_recovery_source_from_archive(
                    project_root,
                    archived,
                )
                if archive_error:
                    source_status = "retryable_error"
                    source.update({
                        "status": source_status,
                        "reason": "verified branch archive could not be restored",
                        "git_error": archive_error,
                    })
                else:
                    source = inspect_repository_recovery_source(project_root, task, base_ref, branch)
                    source_status = str(source.get("status") or "")
                    source["preservation_ref"] = preservation_ref
                    source["archive_recovery"] = {
                        key: value
                        for key, value in archived.items()
                        if not key.startswith("_")
                    }
            if (
                source_status == "resolved"
                and source.get("resolution_status") == "recovered_from_recorded_sha"
                and apply
            ):
                preservation_ref, preservation_error = preserve_recovery_commit(
                    project_root,
                    str(source.get("recorded_sha") or ""),
                )
                if preservation_error:
                    source_status = "retryable_error"
                    source.update({
                        "status": source_status,
                        "reason": "repository recovery commit could not be protected from object pruning",
                        "git_error": preservation_error,
                    })
                else:
                    source["preservation_ref"] = preservation_ref
            if source_status == "resolved":
                branch = str(source["source_ref"])
                paths = list_paths(source.get("paths"))
                task["branch"] = branch
                task["source_evidence_resolution"] = {
                    "status": source.get("resolution_status"),
                    "attempted_ref": attempted_ref or None,
                    "attempted_ref_head_sha": source.get("branch_head_sha"),
                    "resolved_ref": branch,
                    "recorded_sha": source.get("recorded_sha"),
                    "base_ref": base_ref,
                    "preservation_ref": source.get("preservation_ref"),
                    "resolved_at": now,
                    "archive_recovery": source.get("archive_recovery"),
                }
                for field in (
                    "requires_human_attention",
                    "source_evidence_failure",
                    "source_evidence_retry",
                    "human_question",
                    "status_reason",
                ):
                    task.pop(field, None)
                if str(task.get("owner") or "").lower() in {"human", "automation"}:
                    task.pop("owner", None)
                if str(task.get("next_owner") or "").lower() in {"human", "auto_recovery"}:
                    task.pop("next_owner", None)
                    task.pop("next_role", None)
                clear_worker_packet_repair_metadata(task)
            elif source_status == "retryable_error":
                if integration_status == "blocked_by_missing_environment":
                    skipped += 1
                    continue
                reason = str(source.get("reason") or "repository recovery environment is temporarily unavailable")
                task.update({
                    "status": "blocked_by_missing_environment",
                    "integration_status": "blocked_by_missing_environment",
                    "dispatcher_decision": "blocked_by_missing_environment",
                    "packet_status": "blocked_by_missing_environment",
                    "normalization_status": "blocked_by_missing_environment",
                    "worker_ready": False,
                    "lock": "free",
                    "owner": "automation",
                    "next_owner": "auto_recovery",
                    "next_role": "auto_dispatcher",
                    "requires_human_attention": False,
                    "status_reason": reason,
                    "next_action": "Retry automatically after the managed checkout and base ref are refreshed.",
                    "updated_at": now,
                    "source_evidence_retry": {
                        "status": "retryable",
                        "attempted_ref": branch or None,
                        "recorded_sha": source.get("recorded_sha") or None,
                        "base_ref": base_ref,
                        "git_error": str(source.get("git_error") or "")[:500],
                        "detected_at": now,
                    },
                })
                classified.append({
                    "task_id": tid,
                    "classification": "blocked_by_missing_environment",
                    "reason": reason,
                    "attempted_ref": branch or None,
                    "recorded_sha": source.get("recorded_sha") or None,
                })
                continue
            else:
                if integration_status == "source_evidence_unavailable":
                    skipped += 1
                    continue
                reason = str(source.get("reason") or "repository recovery source evidence is unavailable")
                for field in (
                    "missing_packet_fields",
                    "repair_owner",
                    "dispatcher_next_review_at",
                    "dispatcher_decision_reason",
                    "not_worker_ready_reason",
                    "source_evidence_retry",
                ):
                    task.pop(field, None)
                task.update({
                    "status": "needs_human",
                    "integration_status": "source_evidence_unavailable",
                    "dispatcher_decision": "needs_human",
                    "packet_status": "needs_human",
                    "normalization_status": "needs_human",
                    "worker_ready": False,
                    "lock": "free",
                    "owner": "human",
                    "next_owner": "human",
                    "next_role": "human",
                    "requires_human_attention": True,
                    "status_reason": reason,
                    "repair_request": "Restore the recorded source commit or provide an explicit preservation disposition.",
                    "next_action": "Owner must restore the exact source evidence or approve an evidence-backed disposition.",
                    "updated_at": now,
                    "dispatcher_repaired_at": now,
                    "dispatcher_repaired_by": "scripts/agent_control/dispatcher_integration_repair.py",
                    "source_evidence_failure": {
                        "status": "unavailable",
                        "attempted_ref": branch or None,
                        "branch_head_sha": source.get("branch_head_sha") or None,
                        "recorded_sha": source.get("recorded_sha") or None,
                        "base_ref": base_ref,
                        "git_error": str(source.get("git_error") or "")[:500],
                        "detected_at": now,
                    },
                })
                classified.append({
                    "task_id": tid,
                    "classification": "needs_human",
                    "reason": reason,
                    "attempted_ref": branch or None,
                    "recorded_sha": source.get("recorded_sha") or None,
                })
                continue
        elif branch and not paths:
            paths, error = changed_paths(project_root, base_ref, branch)
        if error:
            task["repair_request"] = "Dispatcher could not inspect the source branch."
            task["next_action"] = f"Human/Dispatcher must recover branch evidence before integration: {error[:500]}"
            task["next_owner"] = "dispatcher"
            task["next_role"] = "auto_dispatcher"
            task["dispatcher_next_review_at"] = now
            blocked.append({"task_id": tid, "branch": branch, "reason": error[:500]})
            continue
        if not paths:
            task["status"] = "stale_or_superseded"
            task["integration_status"] = "closed_no_diff"
            task["dispatcher_decision"] = "stale_or_superseded"
            task["worker_ready"] = False
            task["lock"] = "free"
            task["closed_at"] = now
            task["closed_by"] = "dispatcher_integration_repair"
            closed.append({"task_id": tid, "reason": "no changed paths"})
            continue

        coordination_paths, integration_paths = split_coordination_paths(paths, project_root=project_root)
        task["changed_paths"] = paths
        task["integration_changed_paths"] = integration_paths
        task["coordination_changed_paths"] = coordination_paths
        if hygiene_integration:
            task["allowed_paths"] = paths
        task["dispatcher_repaired_at"] = now
        task["dispatcher_repaired_by"] = "scripts/agent_control/dispatcher_integration_repair.py"
        if integration_paths:
            decomposition_plan = None
            if (
                hygiene_integration
                and not task.get("repository_hygiene_draft_prs")
                and not task.get("repository_hygiene_dirty_prs")
                and not task.get("integration_repair_child_ids")
                and not repository_pr_has_green_exact_head(task)
            ):
                decomposition_plan = build_decomposition_plan(task, integration_paths)
            if decomposition_plan and decomposition_plan.get("status") in {"needs_human", "needs_architect"}:
                classification = str(decomposition_plan["status"])
                reason = str(decomposition_plan.get("reason") or "repository PR decomposition blocked")
                task["status"] = classification
                task["integration_status"] = classification
                task["dispatcher_decision"] = classification
                task["packet_status"] = classification
                task["normalization_status"] = classification
                task["worker_ready"] = False
                task["lock"] = "free"
                task["next_owner"] = "human" if classification == "needs_human" else "Architect"
                task["next_role"] = "human" if classification == "needs_human" else "architect"
                task["status_reason"] = reason
                task["repository_pr_decomposition"] = decomposition_plan
                classified.append({"task_id": tid, "classification": classification, "reason": reason})
                continue
            if decomposition_plan and decomposition_plan.get("should_decompose") is True:
                slices = [item for item in decomposition_plan.get("slices") or [] if isinstance(item, dict)]
                child_id_by_key = {
                    str(item.get("slice_key") or ""): decomposition_child_id(tid, str(item.get("slice_key") or ""))
                    for item in slices
                }
                existing_by_id = {
                    task_id(item): item
                    for item in [*tasks, *pending_repair_children]
                    if isinstance(item, dict) and task_id(item)
                }
                conflicting_ids = [
                    child_id
                    for slice_key, child_id in child_id_by_key.items()
                    if child_id in existing_by_id
                    and (
                        str(existing_by_id[child_id].get("integration_repair_parent_id") or "") != tid
                        or str(existing_by_id[child_id].get("decomposition_slice_key") or "") != slice_key
                    )
                ]
                if conflicting_ids:
                    reason = "decomposition child id collision: " + ", ".join(sorted(conflicting_ids))
                    task["status"] = "needs_human"
                    task["integration_status"] = "needs_human"
                    task["dispatcher_decision"] = "needs_human"
                    task["worker_ready"] = False
                    task["lock"] = "free"
                    task["next_owner"] = "human"
                    task["status_reason"] = reason
                    classified.append({"task_id": tid, "classification": "needs_human", "reason": reason})
                    continue

                child_ids: list[str] = []
                for item in slices:
                    slice_key = str(item.get("slice_key") or "")
                    child_id = child_id_by_key[slice_key]
                    child_ids.append(child_id)
                    dependencies = [
                        child_id_by_key[key]
                        for key in item.get("depends_on") or []
                        if key in child_id_by_key
                    ]
                    if child_id in existing_by_id:
                        migrate_decomposition_child_blocked_by(
                            existing_by_id[child_id],
                            dependencies,
                        )
                        continue
                    child = build_decomposition_child(task, child_id, item, dependencies, now)
                    pending_repair_children.append(child)
                    existing_by_id[child_id] = child
                    used_ids.add(child_id)
                    repair_packets_created.append(
                        {
                            "task_id": child_id,
                            "source_task_id": tid,
                            "classification": "repository_pr_decomposition_slice",
                            "slice_key": slice_key,
                            "execution_lane": item.get("execution_lane"),
                            "model_candidates": item.get("model_candidates") or [],
                            "reason": "large repository PR decomposed before direct integration",
                        }
                    )
                link_parent_to_decomposition(task, child_ids, decomposition_plan, now)
                decomposed_parents.append(
                    {
                        "task_id": tid,
                        "child_ids": child_ids,
                        "slice_count": len(child_ids),
                        "reason": decomposition_plan.get("reason"),
                    }
                )
                continue
            task["worker_ready"] = False
            task["lock"] = "free"
            task["owner"] = "integrator"
            task["next_owner"] = "Integrator"
            clear_worker_packet_repair_metadata(task)
            if hygiene_integration and list_paths(task.get("repository_hygiene_draft_prs")):
                reason = "Draft PR requires explicit Integrator review before finalization."
                task["status"] = "agent_done"
                task["packet_status"] = "integrator_review"
                task["normalization_status"] = "repository_hygiene_routed"
                task["integration_status"] = "needs_integrator_review"
                task["dispatcher_decision"] = "needs_integrator_review"
                task["dispatcher_decision_reason"] = reason
                task["status_reason"] = reason
                task["next_role"] = "integrator_review"
                classified.append({"task_id": tid, "classification": "needs_integrator_review", "reason": reason})
            else:
                task["status"] = "integration_requested"
                task["packet_status"] = "integration_ready"
                task["normalization_status"] = "repository_hygiene_routed" if hygiene_integration else "integration_ready"
                task["integration_status"] = "pending"
                task["dispatcher_decision"] = "integration_ready"
                task["dispatcher_decision_reason"] = "changed paths restored for Integrator"
                task["next_role"] = "auto_integrator"
                task.pop("status_reason", None)
                repaired.append({"task_id": tid, "branch": branch, "integration_changed_paths": integration_paths})
        else:
            task["status"] = "stale_or_superseded"
            task["integration_status"] = "closed_coordination_only"
            task["dispatcher_decision"] = "stale_or_superseded"
            task["worker_ready"] = False
            task["lock"] = "free"
            task["closed_at"] = now
            task["closed_by"] = "dispatcher_integration_repair"
            for field in ("repair_request", "missing_packet_fields", "repair_owner", "next_action"):
                task.pop(field, None)
            closed.append({"task_id": tid, "branch": branch, "reason": "coordination-only diff", "coordination_changed_paths": task["coordination_changed_paths"]})

    if pending_repair_children:
        tasks.extend(pending_repair_children)
    changed = bool(
        repaired
        or closed
        or blocked
        or classified
        or worker_ready_repaired
        or repair_packets_created
        or repair_packets_linked
        or decomposed_parents
        or decomposition_children_refreshed
        or decomposition_superseded
        or dependency_migrations
    )
    if changed:
        result["updated_at"] = now
    if apply and changed:
        event_project = project_id_for_events(project_root)
        write_json(queue_path, result)
        released_lock_count = release_task_locks(
            lock_path,
            {
                str(item.get("task_id"))
                for item in [
                    *repaired,
                    *closed,
                    *blocked,
                    *classified,
                    *worker_ready_repaired,
                    *repair_packets_created,
                    *repair_packets_linked,
                ]
                if item.get("task_id")
            }
            | {
                str(item.get("source_task_id"))
                for item in [*repair_packets_created, *repair_packets_linked]
                if item.get("source_task_id")
            },
            now,
        )
        for item in repaired:
            append_event(events_path, build_event(event_project, item["task_id"], "integration_requested", "Dispatcher restored changed_paths for Integrator.", {"next_owner": "auto_integrator", **item}))
        for item in closed:
            append_event(events_path, build_event(event_project, item["task_id"], "integration_routed_closed", item["reason"], item))
        for item in blocked:
            append_event(events_path, build_event(event_project, item["task_id"], "dispatcher_repair_blocked", item["reason"], {"next_owner": "Dispatcher", **item}))
        for item in classified:
            classification = str(item.get("classification") or "")
            if classification == "needs_worker_fix":
                append_event(events_path, build_event(event_project, item["task_id"], "worker_ready_available", item["reason"], {"next_owner": "auto_workers", **item}))
            elif classification == "needs_human":
                append_event(events_path, build_event(event_project, item["task_id"], "needs_human_created", item["reason"], {"next_owner": "human", **item}))
            elif classification == "coordination_only":
                append_event(events_path, build_event(event_project, item["task_id"], "integration_routed_closed", item["reason"], item))
            elif classification == "needs_integrator_review":
                append_event(events_path, build_event(event_project, item["task_id"], "integrator_review_required", item["reason"], {"next_owner": "Integrator", **item}))
            elif classification == "needs_dispatcher_repair":
                append_event(events_path, build_event(event_project, item["task_id"], "dispatcher_repair_required", item["reason"], {"next_owner": "dispatcher", **item}))
            elif classification == "needs_architect":
                append_event(events_path, build_event(event_project, item["task_id"], "architect_review_required", item["reason"], {"next_owner": "Architect", **item}))
            elif classification == "blocked_by_missing_environment":
                append_event(events_path, build_event(event_project, item["task_id"], "task_blocked", item["reason"], {"next_owner": "auto_recovery", **item}))
        for item in worker_ready_repaired:
            append_event(events_path, build_event(event_project, item["task_id"], "worker_ready_available", item["reason"], {"next_owner": "auto_workers", **item}))
        for item in repair_packets_created:
            append_event(
                events_path,
                build_event(
                    event_project,
                    item["task_id"],
                    "worker_ready_available",
                    item["reason"],
                    {"next_owner": "auto_workers", **item},
                ),
            )
            append_event(
                events_path,
                build_event(
                    event_project,
                    item["source_task_id"],
                    "integration_repair_packet_created",
                    item["reason"],
                    {"next_owner": "auto_workers", **item},
                ),
            )
        for item in decomposed_parents:
            append_event(
                events_path,
                build_event(
                    event_project,
                    item["task_id"],
                    "repository_pr_decomposed",
                    str(item.get("reason") or "large repository PR decomposed"),
                    {"next_owner": "auto_workers", **item},
                ),
            )
        for item in decomposition_children_refreshed:
            append_event(
                events_path,
                build_event(
                    event_project,
                    item["task_id"],
                    "repository_pr_decomposition_refreshed",
                    item["reason"],
                    {"next_owner": "auto_workers", **item},
                ),
            )
        for item in decomposition_superseded:
            append_event(
                events_path,
                build_event(
                    event_project,
                    item["task_id"],
                    "repository_pr_decomposition_superseded",
                    item["reason"],
                    {"next_owner": "dispatcher", **item},
                ),
            )

    return {
        "schema_version": 1,
        "checked_at": now,
        "dry_run": not apply,
        "base_ref": base_ref,
        "repaired_count": len(repaired),
        "closed_count": len(closed),
        "blocked_count": len(blocked),
        "classified_count": len(classified),
        "worker_ready_repaired_count": len(worker_ready_repaired),
        "repair_packets_created_count": len(repair_packets_created),
        "repair_packets_linked_count": len(repair_packets_linked),
        "decomposed_parent_count": len(decomposed_parents),
        "decomposition_children_refreshed_count": len(decomposition_children_refreshed),
        "decomposition_superseded_count": len(decomposition_superseded),
        "dependency_migration_count": len(dependency_migrations),
        "released_lock_count": released_lock_count if apply and changed else 0,
        "skipped_count": skipped,
        "repaired": repaired,
        "closed": closed,
        "blocked": blocked,
        "classified": classified,
        "worker_ready_repaired": worker_ready_repaired,
        "repair_packets_created": repair_packets_created,
        "repair_packets_linked": repair_packets_linked,
        "decomposed_parents": decomposed_parents,
        "decomposition_children_refreshed": decomposition_children_refreshed,
        "decomposition_superseded": decomposition_superseded,
        "dependency_migrations": dependency_migrations,
        "outcome": (
            "partial_progress"
            if blocked
            and (
                repaired
                or closed
                or classified
                or worker_ready_repaired
                or repair_packets_created
                or repair_packets_linked
                or decomposed_parents
                or decomposition_children_refreshed
                or decomposition_superseded
                or dependency_migrations
            )
            else "blocked_items_recorded"
            if blocked
            else "succeeded"
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--queue")
    parser.add_argument("--events")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument(
        "--branch-archive-root",
        type=Path,
        default=DEFAULT_BRANCH_ARCHIVE_ROOT,
        help="Local archive root containing per-repository branch bundle manifests.",
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    events_path = Path(args.events).resolve() if args.events else task_file(project_root, "agent_events.jsonl")
    report = repair_queue(
        project_root,
        queue_path,
        events_path,
        args.base_ref,
        apply=args.apply,
        branch_archive_root=args.branch_archive_root.expanduser().resolve(),
    )
    print(
        json.dumps(report, ensure_ascii=False, indent=2)
        if args.json
        else (
            f"repaired: {report['repaired_count']}; "
            f"closed: {report['closed_count']}; "
            f"blocked: {report['blocked_count']}; "
            f"classified: {report['classified_count']}; "
            f"worker_ready_repaired: {report['worker_ready_repaired_count']}"
        )
    )
    # Item-level blockers are durable routing outcomes, not a failed Dispatcher
    # process. Queue and command failures still surface through exceptions or
    # the validation steps in dispatcher_worker_bridge.py.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
