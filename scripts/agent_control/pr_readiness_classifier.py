#!/usr/bin/env python3
"""Classify branch/PR readiness before Auto Integrator sees the stack."""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file, task_reports_dir


READY = "ready_candidate"
NEEDS_REBASE = "needs_rebase"
NEEDS_CHECKS = "needs_checks"
DRAFT_ONLY = "draft_only"
COORDINATION_ONLY = "coordination_only"
DUPLICATE = "duplicate"
CLEANUP = "cleanup_candidate"
NEEDS_DISPATCHER = "needs_dispatcher"
NEEDS_WORKER_FIX = "needs_worker_fix"
NEEDS_HUMAN = "needs_human"
NEEDS_INTEGRATOR_REVIEW = "needs_integrator_review"
BLOCKED = "blocked"

HIGH_RISK_KEYWORDS = (
    "auth",
    "credential",
    "deploy",
    "migration",
    "payment",
    "permission",
    "production",
    "secret",
    "security",
)
HIGH_RISK_PREFIXES = (
    "deploy/",
    "infra/",
    "migrations/",
    "payments/",
    "security/",
)
LOW_RISK_PREFIXES = (
    ".agent/",
    "docs/",
    "scripts/agent_control/",
    "tests/",
)
FAILED_CHECK_STATES = {"failure", "failed", "error", "cancelled", "timed_out"}
PENDING_CHECK_STATES = {"pending", "queued", "in_progress", "waiting", "requested", "expected"}
TASK_PACKET_DEFECT_STATUSES = {"needs_task_packet", "needs_architect", "needs_dispatcher", "needs_dispatcher_split"}
TASK_MODULE_FIELDS = ("module", "area", "component", "subsystem", "domain")
WORKER_EVIDENCE_FIELDS = (
    "worker_report",
    "last_agent_report",
    "integration_report",
    "commits",
    "checks",
    "status_reason",
    "handoff_note",
)
MODULE_DOC_PATHS = {
    "control": ["docs/CONTROL_SERVER.md", "docs/API.md"],
    "api": ["docs/API.md", "docs/CONTROL_SERVER.md"],
    "bots": ["docs/TELEGRAM_BOTS.md", "docs/BOTS.md"],
    "telegram": ["docs/TELEGRAM_BOTS.md", "docs/BOTS.md"],
    "android": ["docs/ANDROID.md"],
    "web": ["docs/WEB.md", "docs/DASHBOARD.md"],
    "scripts": ["docs/RUNBOOK.md", "docs/OPERATIONS.md", "docs/RELEASE_CHECKLIST.md"],
    "infra": ["docs/OPERATIONS.md", "docs/RELEASE_CHECKLIST.md", "docs/BACKUP_RECOVERY.md"],
}


def normalize_module(value: Any) -> str:
    text = str(value or "").strip().lower().replace("\\", "/")
    return re.sub(r"[^a-z0-9._/-]+", "-", text).strip("-/") or "unknown"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_branch(ref: Any) -> str:
    value = str(ref or "").strip()
    if value.startswith("refs/remotes/origin/"):
        return value.removeprefix("refs/remotes/origin/")
    if value.startswith("refs/heads/"):
        return value.removeprefix("refs/heads/")
    if value.startswith("origin/"):
        return value.removeprefix("origin/")
    return value


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def task_index(queue: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(queue, dict):
        return {}
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        return {}
    return {task_id(task).upper(): task for task in tasks if isinstance(task, dict) and task_id(task)}


def clean_rebuild_recovery_index(project_root: Path | None, queue: Any) -> dict[str, dict[str, Any]]:
    if not project_root:
        return {}
    plan = load_json(task_file(project_root, "clean_rebuild_plan.json"))
    if not isinstance(plan, dict):
        return {}
    queue_tasks = queue.get("tasks") if isinstance(queue, dict) else []
    crb_by_source: dict[tuple[str, str], str] = {}
    if isinstance(queue_tasks, list):
        for task in queue_tasks:
            if not isinstance(task, dict):
                continue
            current = task_id(task)
            if not current:
                continue
            branch = normalize_branch(task.get("clean_rebuild_source_branch") or task.get("source_branch"))
            head = str(task.get("clean_rebuild_source_head_sha") or task.get("source_head_sha") or "").strip()
            if branch or head:
                crb_by_source[(branch, head)] = current

    result: dict[str, dict[str, Any]] = {}
    for item in plan.get("items") or []:
        if not isinstance(item, dict):
            continue
        route = str(item.get("rebuild_route") or "")
        if not route.startswith("auto_clean_rebuild"):
            continue
        ids = [str(value) for value in item.get("task_ids") or [] if str(value or "").strip()]
        branch = normalize_branch(item.get("branch"))
        head = str(item.get("head_sha") or "").strip()
        paths = changed_paths(item)
        if len(ids) != 1 or not branch or not paths:
            continue
        recovered_id = crb_by_source.get((branch, head)) or crb_by_source.get((branch, "")) or ids[0]
        allowed = [str(path) for path in item.get("allowed_paths_sample") or [] if str(path or "").strip()]
        task_packet = {
            "id": recovered_id,
            "allowed_paths": allowed or paths,
            "forbidden_paths": [".env", ".env.*", "secrets", "production config"],
            "worker_report": "AiStudio/Task_manager/clean_rebuild_plan.json",
            "status": "integration_requested",
        }
        result[branch] = {
            "task_id": recovered_id,
            "source_task_id": ids[0],
            "source_branch": branch,
            "source_head_sha": head,
            "worker_report": "AiStudio/Task_manager/clean_rebuild_plan.json",
            "task_packet": task_packet,
            "reason": "identity recovered from clean_rebuild_plan",
        }
    return result


def pr_index(snapshot: Any) -> dict[str, dict[str, Any]]:
    if isinstance(snapshot, dict):
        values = snapshot.get("pull_requests") or snapshot.get("prs") or snapshot.get("items") or snapshot.get("data") or []
    else:
        values = snapshot or []
    result: dict[str, dict[str, Any]] = {}
    for item in values if isinstance(values, list) else []:
        if not isinstance(item, dict):
            continue
        branch = normalize_branch(item.get("headRefName") or item.get("head_ref") or item.get("branch"))
        if branch:
            result[branch] = item
    return result


def changed_paths(candidate: dict[str, Any]) -> list[str]:
    paths = candidate.get("integration_changed_paths")
    if isinstance(paths, list):
        return sorted(str(path) for path in paths if path)
    paths = candidate.get("changed_paths")
    return sorted(str(path) for path in paths if path) if isinstance(paths, list) else []


def all_changed_paths(candidate: dict[str, Any]) -> list[str]:
    paths = candidate.get("changed_paths")
    return sorted(str(path) for path in paths if path) if isinstance(paths, list) else []


def coordination_paths(candidate: dict[str, Any]) -> list[str]:
    paths = candidate.get("coordination_changed_paths")
    return sorted(str(path) for path in paths if path) if isinstance(paths, list) else []


def unique_values(values: list[Any]) -> list[str]:
    return sorted({str(value) for value in values if str(value or "").strip()})


def slug_token(value: Any) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "-", str(value or "").strip()).strip("-").upper()


def branch_task_id_matches(branch: str, tasks_by_id: dict[str, dict[str, Any]]) -> list[str]:
    branch_slug = slug_token(branch)
    if not branch_slug:
        return []
    matches: list[str] = []
    for task in tasks_by_id.values():
        current = str(task.get("id") or task.get("task_id") or "").strip()
        task_slug = slug_token(current)
        if task_slug and task_slug in branch_slug:
            matches.append(current)
    unique = sorted({item for item in matches if item}, key=lambda item: (-len(slug_token(item)), item))
    if len(unique) <= 1:
        return unique
    longest = slug_token(unique[0])
    if all(slug_token(item) in longest for item in unique[1:]):
        return [unique[0]]
    return sorted(unique)


def merge_mirror_candidate(preferred: dict[str, Any], other: dict[str, Any]) -> dict[str, Any]:
    merged = dict(preferred)
    for field in ("changed_paths", "integration_changed_paths", "coordination_changed_paths", "task_ids"):
        merged[field] = unique_values((preferred.get(field) or []) + (other.get(field) or []))
    for field in ("head_sha", "merge_base_sha", "ahead_of_base", "behind_base", "pr"):
        if merged.get(field) in (None, "", []):
            merged[field] = other.get(field)
    mirrors = unique_values((preferred.get("mirror_refs") or [preferred.get("branch")]) + (other.get("mirror_refs") or [other.get("branch")]))
    merged["mirror_refs"] = mirrors
    return merged


def prefer_candidate(candidate: dict[str, Any], existing: dict[str, Any]) -> bool:
    branch = str(candidate.get("branch") or "")
    existing_branch = str(existing.get("branch") or "")
    candidate_is_origin = branch.startswith("origin/") or branch.startswith("refs/remotes/origin/")
    existing_is_origin = existing_branch.startswith("origin/") or existing_branch.startswith("refs/remotes/origin/")
    if candidate_is_origin != existing_is_origin:
        return candidate_is_origin
    candidate_payload = len(changed_paths(candidate)) + len(coordination_paths(candidate))
    existing_payload = len(changed_paths(existing)) + len(coordination_paths(existing))
    if candidate_payload != existing_payload:
        return candidate_payload > existing_payload
    return branch < existing_branch


def dedupe_mirror_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_branch: dict[str, dict[str, Any]] = {}
    for candidate in candidates:
        key = normalize_branch(candidate.get("branch"))
        if not key:
            continue
        existing = by_branch.get(key)
        if not existing:
            by_branch[key] = dict(candidate)
            by_branch[key]["mirror_refs"] = unique_values([candidate.get("branch")])
            continue
        if prefer_candidate(candidate, existing):
            by_branch[key] = merge_mirror_candidate(dict(candidate), existing)
        else:
            by_branch[key] = merge_mirror_candidate(existing, candidate)
    return list(by_branch.values())


def risk_class(paths: list[str]) -> str:
    lowered = " ".join(paths).lower()
    if any(path.startswith(HIGH_RISK_PREFIXES) for path in paths) or any(word in lowered for word in HIGH_RISK_KEYWORDS):
        return "high"
    if paths and all(path.startswith(LOW_RISK_PREFIXES) for path in paths):
        return "low"
    if len(paths) <= 6:
        return "medium"
    return "medium"


def module_from_path(path: str) -> str:
    normalized = path.replace("\\", "/").strip("/")
    if not normalized:
        return "unknown"
    for module, doc_paths in MODULE_DOC_PATHS.items():
        if normalized in doc_paths:
            return "control" if module == "api" and normalized == "docs/CONTROL_SERVER.md" else module
    if normalized.startswith(".agent/"):
        return "agent"
    if normalized.startswith(("docs/plans/reports/", "docs/reports/", "AiStudio/Task_manager/reports/")):
        return "reports"
    first = normalized.split("/", 1)[0]
    if first in {"control", "web", "android", "bots", "scripts", "docs", "infra", "deploy", "tests", "tools"}:
        return first
    return first or "unknown"


def task_module_values(task: dict[str, Any]) -> list[str]:
    modules: list[str] = []
    for field in TASK_MODULE_FIELDS:
        value = task.get(field)
        if isinstance(value, list):
            modules.extend(normalize_module(item) for item in value if item)
        elif value:
            modules.append(normalize_module(value))
    contract = task.get("contract_scope")
    if isinstance(contract, dict):
        for field in ("module", "modules", "affected_modules", "contract_modules"):
            value = contract.get(field)
            if isinstance(value, list):
                modules.extend(normalize_module(item) for item in value if item)
            elif value:
                modules.append(normalize_module(value))
    return sorted({item for item in modules if item and item != "unknown"})


def infer_module_scope(paths: list[str], tasks: list[dict[str, Any]]) -> str:
    explicit = sorted({module for task in tasks for module in task_module_values(task)})
    if len(explicit) == 1:
        return explicit[0]
    if len(explicit) > 1:
        return "multi:" + "+".join(explicit)
    modules = sorted({module_from_path(path) for path in paths if path})
    if len(modules) == 1:
        return modules[0]
    if len(modules) > 1:
        return "multi:" + "+".join(modules)
    return "unknown"


def module_doc_patterns(paths: list[str], tasks: list[dict[str, Any]], allowed_patterns: list[str]) -> list[str]:
    modules = {module for task in tasks for module in task_module_values(task)}
    for pattern in allowed_patterns:
        normalized = pattern.replace("\\", "/")
        if normalized.startswith("control/"):
            modules.add("control")
        elif normalized.startswith("bots/"):
            modules.add("bots")
        elif normalized.startswith("android/"):
            modules.add("android")
        elif normalized.startswith("web/"):
            modules.add("web")
        elif normalized.startswith("scripts/"):
            modules.add("scripts")
        elif normalized.startswith(("infra/", "deploy/")):
            modules.add("infra")
    for path in paths:
        module = module_from_path(path)
        if module in MODULE_DOC_PATHS:
            modules.add(module)
    result: list[str] = []
    for module in sorted(modules):
        result.extend(MODULE_DOC_PATHS.get(module, []))
    return unique_values(result)


def pr_head_sha(pr: dict[str, Any]) -> str:
    return str(pr.get("headRefOid") or pr.get("head_sha") or "").strip()


def pr_is_draft(pr: dict[str, Any]) -> bool:
    if "isDraft" in pr:
        return bool(pr.get("isDraft"))
    return bool(pr.get("is_draft") if "is_draft" in pr else pr.get("draft"))


def check_summary(pr: dict[str, Any] | None, expected_head_sha: str = "") -> tuple[str, list[str]]:
    if not pr:
        return "unknown", ["no PR metadata snapshot"]
    expected_head_sha = str(expected_head_sha or "").strip()
    snapshot_head_sha = pr_head_sha(pr)
    if expected_head_sha and not snapshot_head_sha:
        return "unknown", ["PR metadata snapshot is missing exact head SHA"]
    if expected_head_sha and snapshot_head_sha != expected_head_sha:
        return "unknown", [f"PR metadata snapshot head mismatch: expected {expected_head_sha}, got {snapshot_head_sha}"]
    rollup = pr.get("statusCheckRollup")
    if not isinstance(rollup, list) or not rollup:
        return "missing", ["no GitHub status checks in snapshot"]
    failed: list[str] = []
    pending: list[str] = []
    unknown: list[str] = []
    for check in rollup:
        if not isinstance(check, dict):
            continue
        name = str(check.get("name") or check.get("context") or check.get("workflowName") or "check")
        state = str(check.get("state") or check.get("status") or check.get("conclusion") or "").lower()
        if state in FAILED_CHECK_STATES:
            failed.append(f"{name}:{state}")
        elif state in PENDING_CHECK_STATES:
            pending.append(f"{name}:{state}")
        elif state not in {"success", "successful", "completed", "neutral", "skipped", "passed"}:
            unknown.append(f"{name}:{state or 'unknown'}")
    if failed:
        return "failed", failed
    if pending:
        return "pending", pending
    if unknown:
        return "unknown", unknown
    return "passed", []


def has_worker_evidence(candidate: dict[str, Any], task_records: list[dict[str, Any]], check_state: str) -> bool:
    for source in [candidate, *task_records]:
        for field in WORKER_EVIDENCE_FIELDS:
            value = source.get(field)
            if isinstance(value, list) and value:
                return True
            if isinstance(value, str) and value.strip():
                return True
    paths = all_changed_paths(candidate)
    if any(str(path).replace("\\", "/").startswith(("docs/reports/", "docs/plans/reports/", "AiStudio/Task_manager/reports/")) for path in paths):
        return True
    return check_state not in {"missing", "unknown"}


def match_any(path: str, patterns: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    for pattern in patterns:
        pattern = pattern.replace("\\", "/")
        if fnmatch.fnmatch(normalized, pattern) or normalized == pattern.rstrip("/"):
            return True
        if pattern.endswith("/**") and normalized.startswith(pattern[:-3]):
            return True
    return False


def scope_issues(paths: list[str], tasks: list[dict[str, Any]]) -> list[str]:
    if not paths or not tasks:
        return []
    forbidden: list[str] = []
    allowed_patterns: list[str] = []
    forbidden_patterns: list[str] = []
    for task in tasks:
        allowed_patterns.extend(str(item) for item in task.get("allowed_paths") or [] if item)
        forbidden_patterns.extend(str(item) for item in task.get("forbidden_paths") or [] if item)
    allowed_patterns = unique_values(allowed_patterns + module_doc_patterns(paths, tasks, allowed_patterns))
    for path in paths:
        if forbidden_patterns and match_any(path, forbidden_patterns):
            forbidden.append(f"forbidden path: {path}")
    if forbidden:
        return forbidden
    if allowed_patterns:
        outside = [path for path in paths if not match_any(path, allowed_patterns)]
        if outside:
            return [f"outside allowed paths: {', '.join(outside[:8])}"]
    return []


def issue_kind(issue: str) -> str:
    lowered = str(issue or "").lower()
    if lowered.startswith("forbidden path:"):
        return "forbidden_path"
    if lowered.startswith("outside allowed paths:"):
        return "allowed_path_metadata"
    return "scope"


def blocker_type(classification: str, blockers: list[str], check_state: str) -> str | None:
    if classification == READY:
        return None
    if classification in {CLEANUP, COORDINATION_ONLY, DUPLICATE}:
        return "not_code_payload"
    if classification == NEEDS_HUMAN:
        return "human_risk_review"
    if classification == NEEDS_INTEGRATOR_REVIEW:
        return "integrator_review"
    if classification == NEEDS_DISPATCHER:
        return "task_metadata"
    if classification == NEEDS_WORKER_FIX:
        return "code_scope_violation"
    if classification == NEEDS_REBASE:
        return "integration_rebase"
    if classification == NEEDS_CHECKS:
        if check_state in {"missing", "unknown", "pending", "failed"}:
            return "verification_required"
        return "source_evidence_required"
    if blockers:
        return "blocked"
    return "unknown"


def code_payload_status(classification: str, blocker: str | None) -> str:
    if classification == READY:
        return "candidate"
    if blocker in {"task_metadata", "integration_rebase", "verification_required", "source_evidence_required", "integrator_review"}:
        return "unverified_candidate"
    if blocker == "human_risk_review":
        return "risk_review_required"
    if blocker == "code_scope_violation":
        return "needs_code_fix"
    if blocker == "not_code_payload":
        return "not_code_payload"
    return "blocked"


def build_conflict_map(preflight: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    result: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for conflict in preflight.get("path_conflicts") or []:
        if not isinstance(conflict, dict):
            continue
        left = str(conflict.get("left") or "")
        right = str(conflict.get("right") or "")
        if left:
            result[left].append(conflict)
        if right:
            result[right].append(conflict)
    return result


def build_conflict_map_from_candidates(candidates: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    paths_by_branch = {str(candidate.get("branch") or ""): changed_paths(candidate) for candidate in candidates}
    result: dict[str, list[dict[str, Any]]] = defaultdict(list)
    branches = sorted(paths_by_branch)
    for index, left in enumerate(branches):
        left_paths = set(paths_by_branch[left])
        if not left_paths:
            continue
        for right in branches[index + 1 :]:
            overlap = sorted(left_paths.intersection(paths_by_branch[right]))
            if not overlap:
                continue
            conflict = {
                "left": left,
                "right": right,
                "paths": overlap,
                "reason": "product changed-path overlap",
            }
            result[left].append(conflict)
            result[right].append(conflict)
    return result


def duplicate_losers(candidates: list[dict[str, Any]], max_task_ids: int) -> set[str]:
    groups: dict[tuple[str, tuple[str, ...]], list[dict[str, Any]]] = defaultdict(list)
    for candidate in candidates:
        ids = tuple(str(item) for item in candidate.get("task_ids") or [])
        paths = tuple(changed_paths(candidate))
        if ids and len(ids) <= max_task_ids and paths:
            groups[("|".join(ids), paths)].append(candidate)
    losers: set[str] = set()
    for items in groups.values():
        if len(items) < 2:
            continue
        ordered = sorted(
            items,
            key=lambda item: (
                int(item.get("behind_base") or 0),
                -int(item.get("ahead_of_base") or 0),
                str(item.get("branch") or ""),
            ),
        )
        for item in ordered[1:]:
            losers.add(normalize_branch(item.get("branch")))
    return losers


def classify_candidate(
    candidate: dict[str, Any],
    *,
    tasks_by_id: dict[str, dict[str, Any]],
    conflicts_by_branch: dict[str, list[dict[str, Any]]],
    duplicate_branches: set[str],
    prs_by_branch: dict[str, dict[str, Any]],
    require_checks: bool,
    block_drafts: bool,
    strict_fresh_base: bool,
    max_task_ids: int,
    strict_product_conflicts: bool,
    recovery_by_branch: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    branch = str(candidate.get("branch") or "")
    normalized = normalize_branch(branch)
    task_ids = [str(item) for item in candidate.get("task_ids") or [] if item]
    recovery = recovery_by_branch.get(normalized)
    identity_recovered_from = None
    if not task_ids and recovery:
        task_ids = [str(recovery["task_id"])]
        identity_recovered_from = "clean_rebuild_plan"
    if not task_ids:
        branch_matches = branch_task_id_matches(normalized, tasks_by_id)
        if len(branch_matches) == 1:
            task_ids = branch_matches
            identity_recovered_from = "branch"
    product_paths = changed_paths(candidate)
    raw_paths = all_changed_paths(candidate)
    service_paths = coordination_paths(candidate)
    ahead = int(candidate.get("ahead_of_base") or 0)
    behind = int(candidate.get("behind_base") or 0)
    state_only_behind = behind > 0 and candidate.get("state_only_base_advance") is True
    pr = prs_by_branch.get(normalized)
    task_records = [tasks_by_id[item.upper()] for item in task_ids if item.upper() in tasks_by_id]
    if recovery and not task_records:
        task_records = [dict(recovery.get("task_packet") or {})]
    check_state, check_reasons = check_summary(pr, str(candidate.get("head_sha") or ""))
    branch_conflicts = conflicts_by_branch.get(branch) or conflicts_by_branch.get(normalized) or []
    item_risk = risk_class(product_paths)
    module_scope = infer_module_scope(product_paths, task_records)
    evidence: list[str] = []
    warnings: list[str] = []
    blockers: list[str] = []
    classification = READY
    next_owner = "integration_batch_builder"
    reason = "traceable candidate with no blocking product overlap"

    if ahead <= 0 or not raw_paths:
        classification = CLEANUP
        next_owner = "cleanup_script"
        reason = "no diff/ahead payload against base"
    elif not task_ids:
        classification = NEEDS_INTEGRATOR_REVIEW
        next_owner = "auto-integrator"
        reason = "missing task trace"
    elif len(task_ids) > max_task_ids:
        classification = NEEDS_INTEGRATOR_REVIEW
        next_owner = "auto-integrator"
        reason = f"ambiguous task trace: {len(task_ids)} task ids"
    elif any(str(task.get("status") or "") in TASK_PACKET_DEFECT_STATUSES for task in task_records):
        classification = NEEDS_INTEGRATOR_REVIEW
        next_owner = "auto-integrator"
        reason = "task is routed as packet/architecture defect"
    elif normalized in duplicate_branches:
        classification = DUPLICATE
        next_owner = "auto-integrator"
        reason = "duplicate task/path evidence superseded by another branch"
    elif not product_paths:
        classification = COORDINATION_ONLY
        next_owner = "auto-integrator"
        reason = "coordination-only candidate; central sync state is authoritative"
    else:
        scope = scope_issues(product_paths, task_records)
        forbidden_scope = [issue for issue in scope if issue_kind(issue) == "forbidden_path"]
        metadata_scope = [issue for issue in scope if issue_kind(issue) == "allowed_path_metadata"]
        if scope:
            blockers.extend(scope)
        if item_risk == "high":
            blockers.append("high-risk paths require owner/strong review")
        if behind > 0 and not state_only_behind:
            blockers.append(f"behind base by {behind} commits")
        if strict_product_conflicts and branch_conflicts:
            blockers.append("product changed-path overlap with another candidate")
        if pr and pr_is_draft(pr) and not has_worker_evidence(candidate, task_records, check_state):
            blockers.append("draft worker PR lacks worker report, checks or equivalent evidence")
        if require_checks and check_state in {"missing", "unknown", "pending", "failed"}:
            blockers.append(f"required checks are {check_state}")

        if forbidden_scope:
            classification = NEEDS_WORKER_FIX
            next_owner = "worker"
            reason = "; ".join(forbidden_scope)
        elif item_risk == "high":
            classification = NEEDS_INTEGRATOR_REVIEW
            next_owner = "auto-integrator"
            reason = "; ".join(blockers) if blockers else "high-risk paths require owner/strong review"
        elif metadata_scope:
            classification = NEEDS_INTEGRATOR_REVIEW
            next_owner = "auto-integrator"
            reason = "; ".join(metadata_scope)
        elif behind > 0 and not state_only_behind:
            classification = NEEDS_REBASE
            next_owner = "worker"
            reason = "; ".join(blockers)
        elif strict_product_conflicts and branch_conflicts:
            classification = NEEDS_INTEGRATOR_REVIEW
            next_owner = "auto-integrator"
            reason = "; ".join(blockers)
        elif pr and pr_is_draft(pr) and not has_worker_evidence(candidate, task_records, check_state):
            classification = NEEDS_CHECKS
            next_owner = "auto-integrator"
            reason = "; ".join(blockers)
        elif require_checks and check_state in {"missing", "unknown", "pending", "failed"}:
            classification = NEEDS_CHECKS
            next_owner = "auto-integrator"
            reason = "; ".join(blockers)

    is_draft = pr_is_draft(pr) if pr else False
    if is_draft and classification == READY:
        warnings.append("draft worker PR accepted as source artifact; package branch/PR is the Finalizer merge target")
    if check_state in {"missing", "unknown"} and classification == READY:
        warnings.append(f"GitHub checks {check_state}; local/task evidence required")
    if state_only_behind and classification == READY:
        evidence.append(f"state_only_base_advance={behind}")
    if branch_conflicts:
        evidence.append(f"product_conflicts={len(branch_conflicts)}")
        if classification == READY:
            warnings.append("product path overlaps another candidate; batch builder must isolate by module/path")
    if candidate.get("path_list_truncated"):
        evidence.append(f"changed_paths_truncated={candidate.get('stored_path_count')}/{candidate.get('path_count')}")
        warnings.append(f"changed paths truncated in preflight by {candidate.get('omitted_path_count')} paths")
    evidence.extend(check_reasons[:10])
    readiness_blocker_type = blocker_type(classification, blockers, check_state)
    payload_status = code_payload_status(classification, readiness_blocker_type)

    return {
        "branch": branch,
        "normalized_branch": normalized,
        "pr": pr.get("number") if pr else candidate.get("pr"),
        "pr_url": pr.get("url") or pr.get("html_url") if pr else None,
        "head_sha": candidate.get("head_sha"),
        "merge_base_sha": candidate.get("merge_base_sha"),
        "task_ids": task_ids,
        "identity_recovered_from": identity_recovered_from,
        "classification": classification,
        "readiness_blocker_type": readiness_blocker_type,
        "code_payload_status": payload_status,
        "risk_class": item_risk,
        "module_scope": module_scope,
        "changed_paths": product_paths,
        "coordination_changed_paths": service_paths,
        "raw_changed_paths": raw_paths,
        "path_count": len(product_paths),
        "full_path_count": candidate.get("path_count"),
        "stored_path_count": candidate.get("stored_path_count"),
        "path_list_truncated": bool(candidate.get("path_list_truncated")),
        "omitted_path_count": candidate.get("omitted_path_count"),
        "raw_path_count": len(raw_paths),
        "mirror_refs": candidate.get("mirror_refs") or [branch],
        "ahead_of_base": ahead,
        "behind_base": behind,
        "state_only_base_advance": state_only_behind,
        "check_state": check_state,
        "merge_state": (pr.get("mergeStateStatus") or pr.get("merge_state")) if pr else None,
        "is_draft": is_draft if pr else None,
        "source_artifact": is_draft if pr else False,
        "merge_target_allowed": False if is_draft else True,
        "reason": reason,
        "blocking_reasons": blockers,
        "next_owner": next_owner,
        "evidence": evidence,
        "warnings": warnings,
    }


def build_report(preflight: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve() if args.project_root else None
    queue = load_json(Path(args.queue).resolve()) if args.queue else load_json(task_file(project_root, "task_queue.json")) if project_root else None
    pr_snapshot = load_json(Path(args.pr_snapshot).resolve()) if args.pr_snapshot else None
    tasks_by_id = task_index(queue)
    recovery_by_branch = clean_rebuild_recovery_index(project_root, queue)
    raw_candidates = [item for item in preflight.get("candidates") or [] if isinstance(item, dict)]
    candidates = dedupe_mirror_candidates(raw_candidates)
    max_task_ids = int(getattr(args, "max_task_ids", 5) or 5)
    strict_product_conflicts = bool(getattr(args, "strict_product_conflicts", False))
    conflicts_by_branch = build_conflict_map_from_candidates(candidates)
    duplicates = duplicate_losers(candidates, max_task_ids)
    prs_by_branch = pr_index(pr_snapshot)

    items = [
        classify_candidate(
            candidate,
            tasks_by_id=tasks_by_id,
            conflicts_by_branch=conflicts_by_branch,
            duplicate_branches=duplicates,
            prs_by_branch=prs_by_branch,
            require_checks=args.require_checks,
            block_drafts=args.block_drafts,
            strict_fresh_base=args.strict_fresh_base,
            max_task_ids=max_task_ids,
            strict_product_conflicts=strict_product_conflicts,
            recovery_by_branch=recovery_by_branch,
        )
        for candidate in candidates
    ]
    counts = Counter(str(item["classification"]) for item in items)
    risk_counts = Counter(str(item["risk_class"]) for item in items)
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root) if project_root else preflight.get("project_root"),
        "base_branch": args.base or preflight.get("base_branch") or "develop",
        "base_sha": preflight.get("base_sha"),
        "preflight_checked_at": preflight.get("checked_at"),
        "policy": {
            "require_checks": bool(args.require_checks),
            "block_drafts": bool(args.block_drafts),
            "strict_fresh_base": True,
            "max_task_ids": max_task_ids,
            "strict_product_conflicts": strict_product_conflicts,
            "clean_rebuild_recovery": bool(recovery_by_branch),
        },
        "raw_candidate_count": len(raw_candidates),
        "deduped_candidate_count": len(candidates),
        "mirror_candidate_count": len(raw_candidates) - len(candidates),
        "items": items,
        "counts": dict(counts),
        "risk_counts": dict(risk_counts),
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# PR Readiness Report",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Base: `{report.get('base_branch')}` `{report.get('base_sha')}`",
        f"- Items: `{len(report.get('items') or [])}`",
        f"- Counts: `{json.dumps(report.get('counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        "",
        "| Classification | Risk | Branch | Tasks | Reason |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in report.get("items") or []:
        tasks = ", ".join(item.get("task_ids") or []) or "-"
        lines.append(
            f"| `{item.get('classification')}` | `{item.get('risk_class')}` | "
            f"`{item.get('branch')}` | `{tasks}` | {str(item.get('reason') or '').replace('|', '/')} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Classify PR/branch readiness before Auto Integrator.")
    parser.add_argument("--preflight", required=True, help="Path to integrator_preflight.json.")
    parser.add_argument("--project-root", help="Project root, used to read task_queue.json and write logs.")
    parser.add_argument("--queue", help="Optional task_queue.json path.")
    parser.add_argument("--pr-snapshot", help="Optional GitHub PR snapshot JSON.")
    parser.add_argument("--output", help="Output JSON path. Defaults to <project-root>/AiStudio/Task_manager/pr_readiness_report.json.")
    parser.add_argument("--report", help="Optional Markdown report path.")
    parser.add_argument("--base", help="Override base branch label in report.")
    parser.add_argument("--require-checks", action="store_true", help="Classify missing/unknown checks as needs_checks.")
    parser.add_argument("--block-drafts", action="store_true", help="Classify draft PRs as draft_only.")
    parser.add_argument(
        "--strict-fresh-base",
        action="store_true",
        default=True,
        help="Compatibility flag; behind-base candidates are always classified as needs_rebase.",
    )
    parser.add_argument("--strict-product-conflicts", action="store_true", help="Classify product path overlaps as needs_rebase instead of leaving them for module-aware batching.")
    parser.add_argument("--max-task-ids", type=int, default=5, help="Route candidates with more inferred task ids to Dispatcher.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    preflight_path = Path(args.preflight).resolve()
    preflight = load_json(preflight_path)
    if not isinstance(preflight, dict):
        raise SystemExit(f"invalid preflight JSON: {preflight_path}")
    report = build_report(preflight, args)
    project_root = Path(args.project_root).resolve() if args.project_root else None
    output = Path(args.output).resolve() if args.output else (task_file(project_root, "pr_readiness_report.json") if project_root else None)
    if output:
        write_json(output, report)
    if args.report:
        report_path = Path(args.report).resolve()
    elif project_root:
        report_path = task_reports_dir(project_root) / f"PR_READINESS_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"
    else:
        report_path = None
    if report_path:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(render_markdown(report), encoding="utf-8")
    if project_root:
        append_log(project_root, "pre-integrator", "pr_readiness_classified", severity="info", counts=report["counts"])

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"items: {len(report['items'])}")
        print(f"counts: {report['counts']}")
        if output:
            print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
