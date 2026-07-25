#!/usr/bin/env python3
"""Fill safe default worker packet fields in task_queue.json."""

from __future__ import annotations

import argparse
import json
import re
import time
from copy import deepcopy
from datetime import datetime, timezone
from fnmatch import fnmatch
from pathlib import Path
from typing import Any

import dispatcher_packet_repair
from project_paths import task_file, task_relpath


ELIGIBLE_STATUSES = {"planned", "needs_task_packet"}
TERMINAL_STATUSES = {"done", "stale_or_superseded", "blocked", "cancelled", "duplicate"}
COMPLETED_DEPENDENCY_STATUSES = {
    "done",
    "completed",
    "finalized",
    "released",
    "archived",
    "owner_approved",
}
BLOCKING_DECISIONS = {
    "needs_architect",
    "needs_dispatcher_review",
    "needs_dispatcher_repair",
    "needs_integrator_review",
    "needs_human",
    "split_into_children",
    "duplicate_linked",
    "stale_or_superseded",
    "blocked_by_dependency",
    "blocked_by_missing_environment",
    "blocked_by_pr_stack",
}
PRESERVE_NON_WORKER_DECISIONS = {
    "needs_dispatcher_review",
    "needs_dispatcher_repair",
    "needs_integrator_review",
    "needs_architect",
    "needs_human",
    "split_into_children",
    "duplicate_linked",
    "stale_or_superseded",
    "blocked_by_dependency",
    "blocked_by_missing_environment",
    "blocked_by_pr_stack",
}
PROJECT_RULES_REVIEW_ONLY_CATEGORIES = {"source_truth", "project_memory", "sensitive_risk"}
REQUIRED_VALUE_FIELDS = ("complexity", "priority", "type")
REQUIRED_LIST_FIELDS = ("allowed_paths", "forbidden_paths", "acceptance_criteria", "checks")
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
GENERIC_ALLOWED_PATHS = {
    "docs/plans/**",
    "docs/reports/**",
    "CHANGELOG.md",
    "README.md",
    "manage.py",
    "requirements*.txt",
    "pyproject.toml",
}
MODULE_DOC_PATHS = {
    "control": ["docs/CONTROL_SERVER.md", "docs/API.md"],
    "api": ["docs/API.md", "docs/CONTROL_SERVER.md"],
    "bots": ["docs/TELEGRAM_BOTS.md", "docs/BOTS.md"],
    "telegram": ["docs/TELEGRAM_BOTS.md", "docs/BOTS.md"],
    "android": [
        "docs/ANDROID.md",
        "docs/CLIENT_APP_PLAN.md",
        "docs/plans/android/ANDROID_LIVE_ENV.md",
        "docs/plans/android/ANDROID_OWNER_DIRECT_UPDATE_FLOW.md",
        "docs/plans/android/CONTROL_CENTER_LIVE_ANDROID_CONTRACT.md",
        "docs/plans/android/LIVE_VERIFICATION_RUNBOOK.md",
    ],
    "web": ["docs/WEB.md", "docs/DASHBOARD.md"],
    "scripts": ["docs/RUNBOOK.md", "docs/OPERATIONS.md", "docs/RELEASE_CHECKLIST.md"],
    "infra": ["docs/OPERATIONS.md", "docs/RELEASE_CHECKLIST.md", "docs/BACKUP_RECOVERY.md"],
}
PROFILE_BY_COMPLEXITY = {
    "S": ["auto-worker-5.3-mini", "auto-worker-5.3"],
    "M": ["auto-worker-5.3"],
    "L": ["auto-worker-5.5", "auto-worker-5.5max"],
    "XL": ["auto-worker-5.5max"],
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return len(value) > 0
    return True


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def unique(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value and value not in seen:
            result.append(value)
            seen.add(value)
    return result


def normalize_scope_path(value: Any) -> str:
    return str(value or "").replace("\\", "/").strip().lower()


def scope_patterns_overlap(left: str, right: str) -> bool:
    left = normalize_scope_path(left)
    right = normalize_scope_path(right)
    if not left or not right:
        return False
    if left == right:
        return True
    if left.endswith("/**"):
        base = left[:-3].rstrip("/")
        return right == base or right.startswith(base + "/")
    if right.endswith("/**"):
        base = right[:-3].rstrip("/")
        return left == base or left.startswith(base + "/")
    return fnmatch(right, left) or fnmatch(left, right)


def is_repo_relative_scope_path(value: str) -> bool:
    normalized = normalize_scope_path(value)
    if (
        not normalized
        or normalized.startswith("/")
        or re.match(r"^[A-Za-z]:/", normalized)
    ):
        return False
    return all(part not in {"", ".", ".."} for part in normalized.split("/"))


def safe_inferred_allowed_paths(paths: list[str], forbidden_paths: list[str]) -> list[str]:
    return [
        path
        for path in unique(paths)
        if is_repo_relative_scope_path(path)
        and not any(scope_patterns_overlap(path, forbidden) for forbidden in forbidden_paths)
    ]


def remove_forbidden_scope_overlaps(paths: list[str], forbidden_paths: list[str]) -> list[str]:
    return [
        path
        for path in unique(paths)
        if not any(scope_patterns_overlap(path, forbidden) for forbidden in forbidden_paths)
    ]


def active_lock_task_ids(locks: dict[str, Any] | None) -> set[str]:
    if not isinstance(locks, dict):
        return set()
    result: set[str] = set()
    for lock in locks.get("locks", []):
        if isinstance(lock, dict) and lock.get("state") in {"locked", "in_progress", "review"}:
            task_id = str(lock.get("task_id") or "").strip()
            if task_id:
                result.add(task_id)
    return result


def task_identity(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def completed_dependency_ids(tasks: list[dict[str, Any]]) -> set[str]:
    return {
        task_identity(task)
        for task in tasks
        if task_identity(task)
        and str(task.get("status") or "").strip().lower() in COMPLETED_DEPENDENCY_STATUSES
    }


def reconcile_dependency_block(
    task: dict[str, Any],
    completed_ids: set[str],
    now: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    task_id = task_identity(task) or "unknown"
    if str(task.get("status") or "") != "blocked_by_dependency":
        return task, {"task_id": task_id, "action": "ignored", "reason": "not dependency-blocked"}

    dependencies = unique([str(value).strip() for value in as_list(task.get("depends_on")) if str(value).strip()])
    if not dependencies:
        return task, {
            "task_id": task_id,
            "action": "ignored",
            "reason": "dependency-blocked task has no declared dependencies",
        }

    resolved = [dependency for dependency in dependencies if dependency in completed_ids]
    unresolved = [dependency for dependency in dependencies if dependency not in completed_ids]
    current_resolved = unique(
        [str(value).strip() for value in as_list(task.get("resolved_dependencies")) if str(value).strip()]
    )
    current_blocked = unique([str(value).strip() for value in as_list(task.get("blocked_by")) if str(value).strip()])
    evidence_changed = current_resolved != resolved or current_blocked != unresolved
    if unresolved and not evidence_changed:
        return task, {
            "task_id": task_id,
            "action": "ignored",
            "reason": "dependency blockers are current",
        }

    updated = deepcopy(task)
    updated["resolved_dependencies"] = resolved
    updated["blocked_by"] = unresolved
    updated["dependency_reconciled_at"] = now
    updated["dependency_reconciled_by"] = "scripts/agent_control/normalize_task_packets.py"

    if unresolved:
        return updated, {
            "task_id": task_id,
            "action": "dependency_block_updated",
            "resolved_dependencies": resolved,
            "blocked_by": unresolved,
        }

    updated.update({
        "status": "needs_task_packet",
        "worker_ready": False,
        "packet_status": "needs_task_packet",
        "normalization_status": "needs_task_packet",
        "dispatcher_decision": "needs_task_packet",
        "dispatcher_decision_reason": "all declared dependencies are finalized; packet normalization required",
        "not_worker_ready_reason": "all declared dependencies are finalized; packet normalization required",
        "next_owner": "Dispatcher",
    })
    if str(updated.get("integration_status") or "") == "blocked_by_dependency":
        updated.pop("integration_status", None)
    return updated, {
        "task_id": task_id,
        "action": "dependency_unblocked",
        "resolved_dependencies": resolved,
        "blocked_by": [],
    }


def reconcile_queue_dependencies(
    data: dict[str, Any],
    active_locks: set[str],
    now: str,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    result = deepcopy(data)
    tasks = result.get("tasks", [])
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    completed_ids = completed_dependency_ids([task for task in tasks if isinstance(task, dict)])
    actions: list[dict[str, Any]] = []
    changed = False
    for index, task in enumerate(tasks):
        if not isinstance(task, dict) or task_identity(task) in active_locks:
            continue
        updated, action = reconcile_dependency_block(task, completed_ids, now)
        if action["action"] == "ignored":
            continue
        actions.append(action)
        if updated != task:
            tasks[index] = updated
            changed = True
    if changed:
        result["updated_at"] = now
    return result, actions


def repair_reconciled_dependency_packets(
    data: dict[str, Any],
    dependency_actions: list[dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    target_ids = {
        str(action.get("task_id") or "")
        for action in dependency_actions
        if action.get("action") == "dependency_unblocked"
    }
    if not target_ids:
        return data, {
            "repaired_count": 0,
            "cleaned_count": 0,
            "needs_dispatcher_repair_count": 0,
            "skipped_count": 0,
            "repaired": [],
            "cleaned": [],
            "needs_dispatcher_repair": [],
            "skipped": [],
        }

    tasks = data.get("tasks")
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    subset = {
        "tasks": [
            deepcopy(task)
            for task in tasks
            if isinstance(task, dict) and task_identity(task) in target_ids
        ]
    }
    repaired_subset, report = dispatcher_packet_repair.process_queue(
        subset,
        dependency_context_tasks=[task for task in tasks if isinstance(task, dict)],
    )
    repaired_by_id = {
        task_identity(task): task
        for task in repaired_subset.get("tasks", [])
        if isinstance(task, dict)
    }
    result = deepcopy(data)
    result_tasks = result.get("tasks", [])
    changed = False
    for index, task in enumerate(result_tasks):
        if not isinstance(task, dict):
            continue
        repaired = repaired_by_id.get(task_identity(task))
        if repaired is not None and repaired != task:
            result_tasks[index] = repaired
            changed = True
    if changed:
        result["updated_at"] = repaired_subset.get("updated_at") or utc_now()
    return result, report


def project_has(project_root: Path, relative: str) -> bool:
    return (project_root / relative).exists()


def infer_priority(task: dict[str, Any]) -> str:
    task_id = str(task.get("id") or "").upper()
    title = str(task.get("title") or "").lower()
    if task_id.startswith("P0") or "critical" in title or "release" in title:
        return "P0"
    if task_id.startswith("P2") or "nice to have" in title:
        return "P2"
    return "P1"


def infer_complexity(task: dict[str, Any]) -> str:
    existing = str(task.get("complexity") or "").upper()
    existing = {"XS": "S", "S-M": "M"}.get(existing, existing)
    if existing in PROFILE_BY_COMPLEXITY:
        return existing
    title = str(task.get("title") or "").lower()
    task_type = str(task.get("type") or "").lower()
    broad_words = ("migration", "payment", "checkout", "router", "tun", "production", "release")
    medium_words = ("integrate", "adapter", "telemetry", "subscription", "authorization", "contract", "flow")
    small_words = ("docs", "runbook", "copy", "typo", "note", "decision", "checklist", "define")
    if "xl" in task_type:
        return "XL"
    if any(word in title for word in broad_words):
        return "L"
    if any(word in title for word in small_words):
        return "M" if "smoke" in title or "verify" in title else "S"
    if any(word in title for word in medium_words):
        return "M"
    return "M"


def infer_type(task: dict[str, Any]) -> str:
    title = str(task.get("title") or "").lower()
    task_id = str(task.get("id") or "").upper()
    if "docs" in title or "runbook" in title or "checklist" in title:
        return "docs"
    if "test" in title or "smoke" in title or "verify" in title:
        return "tests"
    if task_id.startswith(("PAY", "BUY", "CX", "COM", "PROD", "WHE", "XCOM")):
        return "implementation"
    if task_id.startswith(("NW", "FAM", "FRI", "R4S", "MVP")):
        return "backlog"
    return "implementation"


def normalized_module_values(task: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for field in ("module", "modules", "area", "component", "subsystem", "domain"):
        value = task.get(field)
        if isinstance(value, list):
            values.extend(str(item).strip().lower() for item in value if str(item or "").strip())
        elif str(value or "").strip():
            values.append(str(value).strip().lower())
    contract = task.get("contract_scope")
    if isinstance(contract, dict):
        for field in ("module", "modules", "affected_modules", "contract_modules"):
            value = contract.get(field)
            if isinstance(value, list):
                values.extend(str(item).strip().lower() for item in value if str(item or "").strip())
            elif str(value or "").strip():
                values.append(str(value).strip().lower())
    return unique([value.replace("\\", "/") for value in values])


def infer_modules_from_paths(paths: list[str]) -> list[str]:
    modules: list[str] = []
    for path in paths:
        normalized = path.replace("\\", "/").strip("/")
        if normalized.startswith("control/"):
            modules.append("control")
        elif normalized.startswith("bots/"):
            modules.append("bots")
        elif normalized.startswith("android/"):
            modules.append("android")
        elif normalized.startswith("web/"):
            modules.append("web")
        elif normalized.startswith("scripts/"):
            modules.append("scripts")
        elif normalized.startswith(("infra/", "deploy/")):
            modules.append("infra")
    return unique(modules)


def module_documentation_paths(task: dict[str, Any], project_root: Path, current_paths: list[str] | None = None) -> list[str]:
    modules = normalized_module_values(task)
    if current_paths:
        modules = unique(modules + infer_modules_from_paths(current_paths))
    title = str(task.get("title") or "").lower()
    if "telegram" in title or "bot" in title:
        modules.append("telegram")
    if "android" in title:
        modules.append("android")
    if "dashboard" in title or "web" in title:
        modules.append("web")
    if "control" in title or "internal api" in title or "api contract" in title:
        modules.append("control")

    docs: list[str] = []
    for module in unique(modules):
        for candidate in MODULE_DOC_PATHS.get(module, []):
            if project_has(project_root, candidate):
                docs.append(candidate)
    return unique(docs)


def infer_allowed_paths(task: dict[str, Any], project_root: Path) -> list[str]:
    task_id = str(task.get("id") or "").upper()
    title = str(task.get("title") or "").lower()
    source_file = task.get("source_file")
    paths: list[str] = []

    if isinstance(source_file, str) and source_file.strip():
        paths.append(source_file.strip())
    for doc in as_list(task.get("context_docs")):
        if isinstance(doc, str) and doc.strip():
            paths.append(doc.strip())

    if project_has(project_root, "docs/plans"):
        paths.append("docs/plans/**")
    if project_has(project_root, "docs"):
        paths.append("docs/reports/**")
    if project_has(project_root, "CHANGELOG.md"):
        paths.append("CHANGELOG.md")

    # E-SHOP/Django domain hints.
    domain_map = {
        "PAY": ["apps/payments/**", "tests/**"],
        "BUY": ["apps/orders/**", "apps/payments/**", "apps/customers/**", "templates/**", "tests/**"],
        "CX": ["apps/customers/**", "apps/orders/**", "apps/support/**", "templates/**", "tests/**"],
        "CIE": ["apps/integrations/**", "apps/catalog/**", "apps/inventory/**", "tests/**"],
        "PROD": ["apps/catalog/**", "apps/inventory/**", "apps/pricing/**", "templates/**", "tests/**"],
        "WHE": ["apps/wholesale/**", "apps/customers/**", "apps/orders/**", "tests/**"],
        "XCOM": ["apps/catalog/**", "apps/orders/**", "apps/payments/**", "apps/integrations/**", "tests/**"],
        "COM": ["apps/catalog/**", "apps/orders/**", "apps/payments/**", "apps/customers/**", "tests/**"],
    }
    prefix = task_id.split("-")[0]
    paths.extend(domain_map.get(prefix, []))

    keyword_paths = [
        (("smoke", "release", "fresh setup"), ["docs/releases/**", "docs/RELEASE_CHECKLIST.md", "docs/PRODUCTION_SMOKE_CHECKLIST.md", "scripts/run-local-smoke.ps1"]),
        (("payment", "checkout", "invoice"), ["apps/payments/**"]),
        (("order", "cart", "buy"), ["apps/orders/**"]),
        (("catalog", "product", "sku"), ["apps/catalog/**"]),
        (("inventory", "stock", "warehouse"), ["apps/inventory/**"]),
        (("customer", "family", "friend"), ["apps/customers/**"]),
        (("delivery", "shipping"), ["apps/delivery/**"]),
        (("notification", "telegram", "email"), ["apps/notifications/**"]),
        (("pricing", "discount"), ["apps/pricing/**"]),
        (("wholesale",), ["apps/wholesale/**"]),
        (("support", "reporting"), ["apps/support/**", "apps/reporting/**"]),
        (("android",), ["android/**", ".env.example"]),
        (("windows", "desktop"), ["desktop/windows/**"]),
        (("telemetry", "admin", "dashboard", "api", "server", "endpoint", "live", "control"), ["control/**", "control/tests/**", ".env.example", "docs/CLIENT_APP_PLAN.md"]),
        (("r4s",), ["control/**", "docs/**"]),
    ]
    for keywords, candidates in keyword_paths:
        if any(keyword in title for keyword in keywords):
            paths.extend(candidates)
    paths.extend(module_documentation_paths(task, project_root, paths))

    if project_has(project_root, "manage.py"):
        paths.extend(["manage.py", "requirements*.txt", "pyproject.toml"])
    if project_has(project_root, "README.md"):
        paths.append("README.md")

    return unique(paths)


def baseline_allowed_paths(project_root: Path) -> list[str]:
    paths: list[str] = []
    if project_has(project_root, "docs/plans"):
        paths.append("docs/plans/**")
    if project_has(project_root, "docs"):
        paths.append("docs/reports/**")
    if project_has(project_root, "CHANGELOG.md"):
        paths.append("CHANGELOG.md")
    return paths


def supplemental_allowed_paths(task: dict[str, Any], project_root: Path, current_paths: list[str]) -> list[str]:
    return unique(baseline_allowed_paths(project_root) + module_documentation_paths(task, project_root, current_paths))


def has_actionable_allowed_paths(task: dict[str, Any]) -> bool:
    allowed_paths = [str(path) for path in as_list(task.get("allowed_paths"))]
    return bool(allowed_paths) and any(path not in GENERIC_ALLOWED_PATHS for path in allowed_paths)


def is_smoke_or_release_task(task: dict[str, Any]) -> bool:
    title = str(task.get("title") or "").lower()
    area = str(task.get("area") or "").lower()
    return any(word in title for word in ("smoke", "release", "fresh setup")) or area == "release"


def infer_checks(task: dict[str, Any], project_root: Path) -> list[str]:
    checks: list[str] = []
    queue_relpath = task_relpath(project_root, "task_queue.json")
    if is_smoke_or_release_task(task) and project_has(project_root, "scripts/run-local-smoke.ps1"):
        checks.append("powershell -ExecutionPolicy Bypass -File scripts/run-local-smoke.ps1")
    if project_has(project_root, "manage.py"):
        checks.extend(["python manage.py check", "python -m pytest"])
    elif project_has(project_root, "pyproject.toml"):
        checks.append("python -m pytest")
    if project_has(project_root, "package.json"):
        checks.append("npm test")
    if task_file(project_root, "task_queue.json").exists():
        checks.append(f"python -m json.tool {queue_relpath}")
    checks.append("git diff --check")
    return unique(checks)


def has_task_specific_checks(task: dict[str, Any]) -> bool:
    checks = [str(check) for check in as_list(task.get("checks"))]
    if is_smoke_or_release_task(task):
        return any("run-local-smoke.ps1" in check for check in checks)
    return True


def infer_context_docs(task: dict[str, Any], project_root: Path) -> list[str]:
    docs: list[str] = []
    queue_relpath = task_relpath(project_root, "task_queue.json")
    for field in ("source_file",):
        value = task.get(field)
        if isinstance(value, str) and value.strip():
            docs.append(value.strip())
    for value in as_list(task.get("context_docs")):
        if isinstance(value, str) and value.strip():
            docs.append(value.strip())
    for candidate in (
        "README.md",
        "docs/plans/MVP_TASK_QUEUE.md",
        "docs/plans/NEXT_WORK.md",
        "docs/plans/MVP_DISTRIBUTION.md",
        "docs/plans/DEVELOPMENT_MAP.md",
        queue_relpath,
    ):
        if project_has(project_root, candidate):
            docs.append(candidate)
    return unique(docs)


def build_provenance(task: dict[str, Any], captured_at: str, source_file: str) -> list[dict[str, Any]]:
    provenance = as_list(task.get("provenance"))
    if provenance:
        return provenance
    return [{
        "source_type": "task_queue",
        "source_file": source_file,
        "source_item_id": task.get("id"),
        "captured_at": captured_at,
        "summary": task.get("source_summary") or task.get("title"),
    }]


def missing_packet_fields(task: dict[str, Any]) -> list[str]:
    missing: list[str] = []
    for field in REQUIRED_VALUE_FIELDS:
        if not has_value(task.get(field)):
            missing.append(field)
    for field in REQUIRED_LIST_FIELDS:
        if not has_value(task.get(field)):
            missing.append(field)
    if not (has_value(task.get("recommended_agent")) or has_value(task.get("eligible_worker_profiles"))):
        missing.append("recommended_agent_or_eligible_worker_profiles")
    if not (has_value(task.get("context_docs")) or has_value(task.get("source_file")) or has_value(task.get("provenance"))):
        missing.append("context_or_provenance")
    task_type = str(task.get("type") or "").lower()
    if task_type not in {"docs", "tests", "docs/automation"} and not has_actionable_allowed_paths(task):
        missing.append("actionable_allowed_paths")
    return missing


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def is_project_rules_review_only(task: dict[str, Any]) -> bool:
    task_type = str(task.get("type") or "").lower()
    category = str(task.get("category") or "").lower()
    task_id = str(task.get("id") or "").upper()
    return (
        task_type.startswith("automation/project_rules_review/")
        and category in PROJECT_RULES_REVIEW_ONLY_CATEGORIES
        and task_id.startswith("PRU-")
    )


def is_integration_only_task(task: dict[str, Any]) -> bool:
    return str(task.get("type") or "").strip() == "repository_hygiene_integration"


def normalize_task(task: dict[str, Any], project_root: Path, active_locks: set[str], now: str) -> tuple[dict[str, Any], dict[str, Any]]:
    updated = deepcopy(task)
    task_id = str(updated.get("id") or "unknown")
    changes: list[str] = []
    blockers: list[str] = []
    status = str(updated.get("status") or "")
    decision = str(updated.get("dispatcher_decision") or "")

    def set_if_changed(targets: dict[str, Any]) -> list[str]:
        changed_fields: list[str] = []
        for key, value in targets.items():
            if updated.get(key) != value:
                updated[key] = value
                changed_fields.append(key)
        return changed_fields

    if status in TERMINAL_STATUSES:
        targets: dict[str, Any] = {
            "worker_ready": False,
            "packet_status": status,
            "normalization_status": status,
            "not_worker_ready_reason": f"terminal status={status}",
        }
        if decision == "worker_ready":
            targets["dispatcher_decision"] = status
            targets["dispatcher_decision_reason"] = f"terminal status={status}"
        changed_fields = set_if_changed(targets)
        if changed_fields:
            updated["packet_normalized_at"] = now
            updated["packet_normalized_by"] = "scripts/agent_control/normalize_task_packets.py"
            return updated, {"task_id": task_id, "action": "terminal_cleanup", "changed_fields": changed_fields, "reason": f"status={status}"}
        return updated, {"task_id": task_id, "action": "ignored", "reason": f"status={status}"}

    if task_id in active_locks:
        return updated, {
            "task_id": task_id,
            "action": "ignored",
            "reason": "active lock exists; preserve the claimed task state until result reconciliation",
        }
    if status not in ELIGIBLE_STATUSES:
        return updated, {"task_id": task_id, "action": "ignored", "reason": f"status={status}"}

    forbidden_paths = [str(path) for path in as_list(updated.get("forbidden_paths"))]
    if not forbidden_paths:
        forbidden_paths = list(DEFAULT_FORBIDDEN_PATHS)
    current_allowed_paths = [str(path) for path in as_list(updated.get("allowed_paths"))]
    safe_allowed_paths = remove_forbidden_scope_overlaps(current_allowed_paths, forbidden_paths)
    if safe_allowed_paths != current_allowed_paths:
        updated["allowed_paths"] = safe_allowed_paths
        changes.append("allowed_paths_forbidden_overlap")

    if is_integration_only_task(updated):
        human_route = (
            updated.get("requires_human_attention") is True
            or str(updated.get("recommended_agent") or "").strip().lower() == "human"
            or decision == "needs_human"
        )
        targets = {
            "status": "needs_human" if human_route else "agent_done",
            "worker_ready": False,
            "packet_status": "needs_human" if human_route else "needs_dispatcher_repair",
            "normalization_status": "needs_human" if human_route else "repository_hygiene_routed",
            "dispatcher_decision": "needs_human" if human_route else "needs_dispatcher_repair",
            "dispatcher_decision_reason": (
                "repository hygiene integration requires human review"
                if human_route
                else "repository hygiene integration is routed to Dispatcher and Integrator, not Worker"
            ),
            "integration_status": "needs_human" if human_route else "needs_dispatcher",
            "next_owner": "human" if human_route else "dispatcher",
            "next_role": "human" if human_route else "auto_dispatcher",
            "not_worker_ready_reason": "repository hygiene integration is not a Worker Packet v2 candidate",
        }
        changed_fields = unique([*changes, *set_if_changed(targets)])
        if changed_fields:
            updated["packet_normalized_at"] = now
            updated["packet_normalized_by"] = "scripts/agent_control/normalize_task_packets.py"
            return updated, {
                "task_id": task_id,
                "action": "integration_only_repair",
                "changed_fields": changed_fields,
                "reason": "repository_hygiene_integration",
            }
        return updated, {
            "task_id": task_id,
            "action": "ignored",
            "reason": "repository_hygiene_integration",
        }
    if is_project_rules_review_only(updated):
        changed_fields = unique([*changes, *set_if_changed({
            "status": "planned",
            "worker_ready": False,
            "packet_status": "needs_dispatcher_review",
            "normalization_status": "needs_dispatcher_review",
            "dispatcher_decision": "needs_dispatcher_review",
            "dispatcher_decision_reason": "Project Rules review-only candidate requires Dispatcher review before worker packet normalization",
            "next_owner": "Dispatcher",
            "not_worker_ready_reason": "Project Rules review-only candidate is not a durable worker packet",
        })])
        if changed_fields:
            updated["packet_normalized_at"] = now
            updated["packet_normalized_by"] = "scripts/agent_control/normalize_task_packets.py"
            return updated, {
                "task_id": task_id,
                "action": "needs_dispatcher_review",
                "changed_fields": changed_fields,
                "reason": "project_rules_review_only",
            }
        return updated, {"task_id": task_id, "action": "ignored", "reason": "project_rules_review_only"}
    if decision in PRESERVE_NON_WORKER_DECISIONS:
        if changes:
            updated["packet_normalized_at"] = now
            updated["packet_normalized_by"] = "scripts/agent_control/normalize_task_packets.py"
            return updated, {
                "task_id": task_id,
                "action": "scope_overlap_repaired",
                "changed_fields": changes,
                "reason": f"dispatcher_decision={decision}",
            }
        return updated, {"task_id": task_id, "action": "ignored", "reason": f"dispatcher_decision={decision}"}
    if decision in BLOCKING_DECISIONS:
        blockers.append(f"dispatcher_decision={decision}")
    if updated.get("requires_current_context_review") is True and not has_current_context_verification(updated):
        blockers.append("current code/docs/task queue review required")
    canonical_task_id = str(updated.get("canonical_task_id") or "").strip()
    if canonical_task_id and canonical_task_id != task_id:
        blockers.append("canonical_task_id points to another task")
    if has_value(updated.get("split_into")):
        blockers.append("split_into is set")
    if has_value(updated.get("blocked_by")):
        blockers.append("blocked_by is not empty")

    if not has_value(updated.get("priority")):
        updated["priority"] = infer_priority(updated)
        changes.append("priority")
    raw_complexity = updated.get("complexity")
    if not has_value(raw_complexity) and isinstance(updated.get("next_run_recommendation"), dict):
        raw_complexity = updated["next_run_recommendation"].get("complexity")
    normalized_complexity = str(raw_complexity or "").strip().upper()
    normalized_complexity = {"XS": "S", "S-M": "M"}.get(normalized_complexity, normalized_complexity)
    if not normalized_complexity:
        normalized_complexity = infer_complexity(updated)
    if normalized_complexity in PROFILE_BY_COMPLEXITY:
        if updated.get("complexity") != normalized_complexity:
            updated["complexity"] = normalized_complexity
            changes.append("complexity")
    else:
        blockers.append(f"invalid complexity={normalized_complexity}")
    if not has_value(updated.get("type")):
        updated["type"] = infer_type(updated)
        changes.append("type")
    if not has_value(updated.get("allowed_paths")) or not has_actionable_allowed_paths(updated):
        current_paths = [str(path) for path in as_list(updated.get("allowed_paths"))]
        allowed_paths = safe_inferred_allowed_paths(infer_allowed_paths(updated, project_root), forbidden_paths)
        merged_paths = unique(current_paths + allowed_paths)
        if merged_paths and updated.get("allowed_paths") != merged_paths:
            updated["allowed_paths"] = merged_paths
            changes.append("allowed_paths")
    else:
        current_paths = [str(path) for path in as_list(updated.get("allowed_paths"))]
        inferred_paths = safe_inferred_allowed_paths(
            supplemental_allowed_paths(updated, project_root, current_paths)
            + infer_allowed_paths(updated, project_root),
            forbidden_paths,
        )
        merged_paths = unique(current_paths + inferred_paths)
        if merged_paths != current_paths:
            updated["allowed_paths"] = merged_paths
            changes.append("allowed_paths")
    if not has_value(updated.get("forbidden_paths")):
        updated["forbidden_paths"] = forbidden_paths
        changes.append("forbidden_paths")
    if not has_value(updated.get("checks")) or not has_task_specific_checks(updated):
        current_checks = [str(check) for check in as_list(updated.get("checks"))]
        merged_checks = unique(current_checks + infer_checks(updated, project_root))
        if updated.get("checks") != merged_checks:
            updated["checks"] = merged_checks
            changes.append("checks")
    if not has_value(updated.get("acceptance_criteria")):
        title = str(updated.get("title") or task_id)
        updated["acceptance_criteria"] = [
            f"Implement or verify: {title}.",
            "Keep changes within allowed_paths and do not touch forbidden_paths.",
            "Run the listed checks and record results in the task report.",
        ]
        changes.append("acceptance_criteria")
    if not (has_value(updated.get("recommended_agent")) or has_value(updated.get("eligible_worker_profiles"))):
        complexity = str(updated.get("complexity") or "").upper()
        if complexity in PROFILE_BY_COMPLEXITY:
            updated["eligible_worker_profiles"] = PROFILE_BY_COMPLEXITY[complexity]
            changes.append("eligible_worker_profiles")
    if not has_value(updated.get("context_docs")):
        context_docs = infer_context_docs(updated, project_root)
        if context_docs:
            updated["context_docs"] = context_docs
            changes.append("context_docs")
    if not has_value(updated.get("provenance")):
        updated["provenance"] = build_provenance(updated, now, task_relpath(project_root, "task_queue.json"))
        changes.append("provenance")

    def apply_targets(targets: dict[str, Any]) -> bool:
        changed = False
        for key, value in targets.items():
            if updated.get(key) != value:
                updated[key] = value
                changed = True
        return changed

    missing = missing_packet_fields(updated)
    if blockers or missing:
        reason_parts = blockers + [f"missing {field}" for field in missing]
        not_ready_reason = "; ".join(reason_parts) if reason_parts else "packet normalization incomplete"
        status_changed = apply_targets({
            "status": "needs_task_packet",
            "worker_ready": False,
            "packet_status": "needs_task_packet",
            "normalization_status": "needs_task_packet",
            "dispatcher_decision": "needs_task_packet",
            "not_worker_ready_reason": not_ready_reason,
            "dispatcher_decision_reason": not_ready_reason,
        })
        if changes or status_changed:
            updated["packet_normalized_at"] = now
            updated["packet_normalized_by"] = "scripts/agent_control/normalize_task_packets.py"
        return updated, {
            "task_id": task_id,
            "action": "needs_dispatcher",
            "changed_fields": changes,
            "reason": not_ready_reason,
        }

    status_changed = apply_targets({
        "status": "planned",
        "worker_ready": True,
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "complete packet auto-normalized from queue metadata",
        "not_worker_ready_reason": None,
    })
    if changes or status_changed:
        updated["packet_normalized_at"] = now
        updated["packet_normalized_by"] = "scripts/agent_control/normalize_task_packets.py"
    return updated, {
        "task_id": task_id,
        "action": "worker_ready",
        "changed_fields": changes,
    }


def normalize_queue(
    data: dict[str, Any],
    project_root: Path,
    active_locks: set[str],
    task_delay_seconds: float = 0,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    now = utc_now()
    result, actions = reconcile_queue_dependencies(data, active_locks, now)
    tasks = result.get("tasks", [])
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    changed = result != data
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            if task_delay_seconds > 0:
                time.sleep(task_delay_seconds)
            continue
        try:
            updated, action = normalize_task(task, project_root, active_locks, now)
            if action["action"] == "ignored":
                continue
            actions.append(action)
            if updated != task:
                tasks[index] = updated
                changed = True
        finally:
            if task_delay_seconds > 0:
                time.sleep(task_delay_seconds)
    if changed:
        result["updated_at"] = now
    return result, actions


def run_once(
    project_root: Path,
    queue_path: Path,
    locks_path: Path,
    apply: bool,
    task_delay_seconds: float = 0,
    dependencies_only: bool = False,
) -> dict[str, Any]:
    queue = load_json(queue_path)
    locks = load_json(locks_path) if locks_path.exists() else None
    active_locks = active_lock_task_ids(locks)
    dependency_packet_repair = None
    if dependencies_only:
        updated, actions = reconcile_queue_dependencies(queue, active_locks, utc_now())
        updated, dependency_packet_repair = repair_reconciled_dependency_packets(updated, actions)
    else:
        updated, actions = normalize_queue(queue, project_root, active_locks, task_delay_seconds)
    worker_ready = [action for action in actions if action["action"] == "worker_ready"]
    needs_dispatcher = [action for action in actions if action["action"] == "needs_dispatcher"]

    if apply and updated != queue:
        write_json(queue_path, updated)

    return {
        "project_root": str(project_root),
        "queue": str(queue_path),
        "checked_at": utc_now(),
        "dry_run": not apply,
        "mode": "dependency_reconciliation" if dependencies_only else "packet_normalization",
        "changed": updated != queue,
        "change_count": len(actions),
        "actions": actions,
        "dependency_packet_repair": dependency_packet_repair,
        "worker_ready_count": len(worker_ready),
        "needs_dispatcher_count": len(needs_dispatcher),
        "worker_ready": worker_ready,
        "needs_dispatcher": needs_dispatcher,
    }


def print_report(report: dict[str, Any], json_output: bool) -> None:
    if json_output:
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
        return

    print(f"project_root: {report['project_root']}", flush=True)
    print(f"queue: {report['queue']}", flush=True)
    print(f"mode: {'dry-run' if report['dry_run'] else 'apply'}", flush=True)
    print(f"changed: {report['changed']}", flush=True)
    print(f"worker_ready: {report['worker_ready_count']}", flush=True)
    print(f"needs_dispatcher: {report['needs_dispatcher_count']}", flush=True)
    for action in report["worker_ready"]:
        changed_fields = ", ".join(action.get("changed_fields") or [])
        print(f"READY {action['task_id']}: {changed_fields}", flush=True)
    for action in report["needs_dispatcher"]:
        print(f"DISPATCHER {action['task_id']}: {action.get('reason')}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Normalize incomplete task queue packets using safe project heuristics.")
    parser.add_argument("--project-root", required=True, help="Project root used for stack/path inference.")
    parser.add_argument("--queue", help="Path to task_queue.json. Defaults to <project-root>/AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--locks", help="Optional path to agent_locks.json. Defaults to <project-root>/AiStudio/Task_manager/agent_locks.json.")
    parser.add_argument("--apply", action="store_true", help="Write changes. Default is dry-run.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    parser.add_argument(
        "--dependencies-only",
        action="store_true",
        help="Reconcile dependency evidence only; do not normalize or repair packet fields.",
    )
    parser.add_argument("--watch", action="store_true", help="Keep checking the queue until stopped.")
    parser.add_argument("--task-delay", type=float, default=None, help="Seconds to sleep after each inspected task in watch mode. Defaults to 1.")
    parser.add_argument("--cycle-delay", type=float, default=1800, help="Seconds to sleep after a full watch cycle. Defaults to 1800.")
    parser.add_argument("--max-cycles", type=int, help="Optional watch cycle limit for tests or scheduled wrappers.")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")

    if args.watch:
        task_delay = 1.0 if args.task_delay is None else max(0.0, args.task_delay)
        cycle_delay = max(0.0, args.cycle_delay)
        cycles = 0
        while True:
            cycles += 1
            report = run_once(
                project_root,
                queue_path,
                locks_path,
                args.apply,
                task_delay,
                dependencies_only=args.dependencies_only,
            )
            report["cycle"] = cycles
            report["watch"] = True
            report["next_cycle_delay_seconds"] = cycle_delay
            print_report(report, args.json)
            if args.max_cycles is not None and cycles >= args.max_cycles:
                break
            time.sleep(cycle_delay)
        return 0

    report = run_once(
        project_root,
        queue_path,
        locks_path,
        args.apply,
        dependencies_only=args.dependencies_only,
    )
    print_report(report, args.json)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
