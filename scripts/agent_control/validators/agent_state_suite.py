#!/usr/bin/env python3
"""Run deterministic agent-state validators.

This validator suite is intentionally non-LLM and deterministic. It checks:

- task_queue.json schema shape and queue-task required fields
- agent locks for required shape, duplicate active locks and stale locks
- agent_version metadata shape and activation evidence
- owner directives shape and active-directive quality
- task traceability after agent_done and similar terminal-like states
- protected path violations based on agent_version metadata and task history
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

AGENT_CONTROL_DIR = Path(__file__).resolve().parents[1]
if str(AGENT_CONTROL_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_CONTROL_DIR))

import validate_task_queue_readiness


KNOWN_TASK_STATUSES = {
    "planned",
    "worker_ready",
    "claimed",
    "agent_working",
    "in_progress",
    "blocked",
    "needs_human",
    "human_answered",
    "needs_stronger_agent",
    "needs_task_packet",
    "needs_architect",
    "needs_dispatcher_split",
    "packet_defect",
    "needs_dispatcher_repair",
    "blocked_by_dependency",
    "dispatcher_review",
    "agent_done",
    "integration_ready",
    "integration_requested",
    "integrating",
    "integration_handoff_ready",
    "finalization_ready",
    "finalizing",
    "review",
    "owner_approved",
    "done",
    "postponed",
    "failed",
    "finalization_blocked",
    "integration_blocked",
    "stale_or_superseded",
    "duplicate_linked",
    "deprecated",
    "archived",
}

TRACEABLE_STATUSES = {
    "agent_done",
    "review",
    "integration_ready",
    "integration_requested",
    "integration_handoff_ready",
    "finalization_ready",
    "finalizing",
    "finalization_blocked",
    "done",
}
TERMINAL_TASK_STATUSES = {"done", "finalized", "completed"}
TERMINAL_INTEGRATION_STATUSES = {
    "finalized",
    "closed_no_diff",
    "closed_coordination_only",
    "already_integrated",
    "not_integrated_no_product_payload",
}
NONTERMINAL_DONE_INTEGRATION_STATUSES = {
    "pending",
    "pending_checks",
    "integration_requested",
    "integration_package_ready",
    "merged_to_develop",
}
PACKET_FIELD_EXEMPT_STATUSES = {
    "blocked",
    "blocked_by_dependency",
    "done",
    "failed",
    "needs_architect",
    "needs_dispatcher_repair",
    "needs_dispatcher_split",
    "needs_human",
    "needs_stronger_agent",
    "needs_task_packet",
    "packet_defect",
    "postponed",
    "archived",
    "dispatcher_review",
    "duplicate_linked",
    "stale_or_superseded",
}

KNOWN_TASK_PRIORITIES = {"P0", "P1", "P2", "P3", "P4"}
KNOWN_TASK_COMPLEXITIES = {"XS", "S", "S-M", "M", "L", "XL"}

ACTIVE_LOCK_STATES = {"locked", "in_progress", "review"}
KNOWN_LOCK_STATES = {
    "locked",
    "released",
    "stale",
    "in_progress",
    "review",
    "agent_done",
    "integration_ready",
    "integration_blocked",
    "finalized",
    "finalization_blocked",
    "blocked",
    "needs_human",
    "needs_stronger_agent",
    "needs_dispatcher_split",
    "failed",
}

KNOWN_DIRECTIVE_STATUSES = {"active", "paused", "closed"}

FALLBACK_PROTECTED_PATTERNS = [
    ".env",
    ".env.*",
    "secrets",
    "production credentials",
    "private keys",
    "tokens",
]


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def add_issue(issues: list[dict[str, str]], severity: str, code: str, path: str, message: str) -> None:
    issues.append({"severity": severity, "code": code, "path": path, "message": message})


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return len(value) > 0
    return True


def is_list_of_non_empty_str(value: Any) -> bool:
    return isinstance(value, list) and bool(value) and all(isinstance(item, str) and item.strip() for item in value)


def load_json(path: Path) -> tuple[dict[str, Any] | list[Any] | None, list[dict[str, str]]]:
    issues: list[dict[str, str]] = []
    if not path.exists():
        add_issue(issues, "error", "missing_file", str(path), "JSON file is missing")
        return None, issues

    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle), issues
    except json.JSONDecodeError as exc:
        add_issue(issues, "error", "invalid_json", str(path), f"invalid JSON: {exc}")
    except OSError as exc:
        add_issue(issues, "error", "read_error", str(path), f"cannot read file: {exc}")

    return None, issues


def validate_task_queue(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    data, issues = load_json(path)
    if data is None:
        return [], issues
    if not isinstance(data, dict):
        add_issue(issues, "error", "invalid_root_type", str(path), "task_queue.json must be an object")
        return [], issues

    if "schema_version" not in data:
        add_issue(issues, "error", "missing_schema_version", "task_queue.json", "schema_version is required")
    if "tasks" not in data:
        add_issue(issues, "error", "missing_tasks", "task_queue.json", "tasks is required")
        return [], issues

    tasks = data["tasks"]
    if not isinstance(tasks, list):
        add_issue(issues, "error", "tasks_not_array", "task_queue.json", "tasks must be an array")
        return [], issues

    required_fields = (
        "id",
        "title",
        "status",
        "priority",
        "complexity",
    )
    worker_packet_fields = (
        "allowed_paths",
        "acceptance_criteria",
    )

    task_ids: set[str] = set()
    duplicate_task_ids: list[str] = []
    for index, task in enumerate(tasks):
        task_path = f"tasks[{index}]"
        if not isinstance(task, dict):
            add_issue(issues, "error", "task_not_object", task_path, "task row must be an object")
            continue

        task_id = str(task.get("id") or "").strip()
        if not has_value(task_id):
            add_issue(issues, "error", "task_id_missing", task_path, "task id is required")
        elif task_id in task_ids:
            duplicate_task_ids.append(task_id)
        else:
            task_ids.add(task_id)

        for field in required_fields:
            if field not in task or not has_value(task[field]):
                add_issue(issues, "error", "queue_field_missing", f"{task_path}.{field}", f"task is missing required field: {field}")

        status = str(task.get("status") or "").strip()
        if status and status not in KNOWN_TASK_STATUSES:
            add_issue(issues, "warning", "unknown_status", f"{task_path}.status", f"unknown task status: {status}")

        priority = str(task.get("priority") or "").strip()
        if priority and priority not in KNOWN_TASK_PRIORITIES:
            add_issue(issues, "warning", "unknown_priority", f"{task_path}.priority", f"unexpected priority value: {priority}")

        complexity = str(task.get("complexity") or "").strip()
        if complexity and complexity not in KNOWN_TASK_COMPLEXITIES:
            add_issue(
                issues,
                "warning",
                "unknown_complexity",
                f"{task_path}.complexity",
                f"unexpected complexity value: {complexity}",
            )

        requires_worker_packet = status not in PACKET_FIELD_EXEMPT_STATUSES
        if requires_worker_packet:
            for field in worker_packet_fields:
                if field not in task or not has_value(task[field]):
                    add_issue(issues, "error", "queue_field_missing", f"{task_path}.{field}", f"task is missing required field: {field}")

        if (requires_worker_packet or "allowed_paths" in task) and not is_list_of_non_empty_str(task.get("allowed_paths", [])):
            add_issue(
                issues,
                "error",
                "invalid_allowed_paths",
                f"{task_path}.allowed_paths",
                "allowed_paths must be a non-empty list of strings",
            )

        if "forbidden_paths" in task and not isinstance(task.get("forbidden_paths"), list):
            add_issue(issues, "error", "invalid_forbidden_paths", f"{task_path}.forbidden_paths", "forbidden_paths must be a list")

    if duplicate_task_ids:
        for task_id in sorted(set(duplicate_task_ids)):
            add_issue(issues, "error", "duplicate_task_id", "task_queue.json", f"duplicate task id: {task_id}")

    return sorted(task_ids), issues


def validate_locks(path: Path, known_task_ids: set[str]) -> list[dict[str, str]]:
    data, issues = load_json(path)
    if data is None:
        return issues
    if not isinstance(data, dict):
        add_issue(issues, "error", "invalid_root_type", str(path), "agent_locks.json must be an object")
        return issues
    if "schema_version" not in data:
        add_issue(issues, "error", "missing_schema_version", f"{path}.schema_version", "schema_version is required")
    locks = data.get("locks")
    if not isinstance(locks, list):
        add_issue(issues, "error", "locks_not_array", f"{path}.locks", "locks must be an array")
        return issues

    active_by_task: dict[str, int] = {}
    active_by_worker: dict[str, int] = {}
    now = utc_now()
    for index, lock in enumerate(locks):
        lock_path = f"locks[{index}]"
        if not isinstance(lock, dict):
            add_issue(issues, "error", "lock_not_object", lock_path, "lock entry must be an object")
            continue

        state = str(lock.get("state") or "").strip()
        task_id = str(lock.get("task_id") or "").strip()
        worker = str(lock.get("by") or "").strip()
        expires_at = parse_time(lock.get("expires_at"))

        for field in ("task_id", "state", "by", "at", "expires_at"):
            if field not in lock or not has_value(lock[field]):
                add_issue(issues, "error", "lock_field_missing", f"{lock_path}.{field}", f"lock is missing required field: {field}")

        if state and state not in KNOWN_LOCK_STATES:
            add_issue(issues, "error", "unknown_lock_state", f"{lock_path}.state", f"unknown lock state: {state}")

        if state in ACTIVE_LOCK_STATES:
            if not task_id:
                add_issue(issues, "error", "active_lock_without_task", lock_path, "active lock must include task_id")
            elif known_task_ids and task_id not in known_task_ids:
                add_issue(issues, "warning", "lock_without_task", lock_path, f"active lock references missing task: {task_id}")

            if task_id:
                active_by_task[task_id] = active_by_task.get(task_id, 0) + 1
            if worker:
                active_by_worker[worker] = active_by_worker.get(worker, 0) + 1

            if not expires_at:
                add_issue(
                    issues,
                    "warning",
                    "invalid_expires_at",
                    f"{lock_path}.expires_at",
                    f"active lock for task {task_id or '<missing>'} has missing or invalid expires_at",
                )
            elif expires_at < now:
                add_issue(
                    issues,
                    "error",
                    "stale_lock",
                    lock_path,
                    f"lock for {task_id or '<missing>'} is stale (expires_at={lock.get('expires_at')})",
                )

    for task_id, count in sorted(active_by_task.items()):
        if count > 1:
            add_issue(issues, "error", "duplicate_active_lock_per_task", f"locks[*].task_id:{task_id}", f"task has {count} active locks")

    for worker, count in sorted(active_by_worker.items()):
        if count > 1:
            add_issue(issues, "warning", "duplicate_active_lock_per_worker", f"locks[*].by:{worker}", f"worker has {count} active locks")

    return issues


def validate_agent_version(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return [{"severity": "warning", "code": "agent_version_missing", "path": str(path), "message": "agent version metadata is missing"}]
    data, issues = load_json(path)
    if data is None:
        return issues
    if not isinstance(data, dict):
        add_issue(issues, "error", "invalid_root_type", str(path), "agent_version.json must be an object")
        return issues

    required = ["schema_version", "agent_repository", "agent_version"]
    for field in required:
        if field not in data:
            add_issue(issues, "error", "missing_required_field", f"{path}.{field}", f"missing {field}")

    if "schema_version" in data and not isinstance(data.get("schema_version"), int):
        add_issue(issues, "error", "invalid_schema_version", f"{path}.schema_version", "schema_version must be an integer")
    if not isinstance(data.get("agent_repository", ""), str) or not str(data.get("agent_repository") or "").strip():
        add_issue(issues, "error", "invalid_agent_repository", f"{path}.agent_repository", "agent_repository must be a non-empty string")
    if not isinstance(data.get("agent_version"), str) or not str(data.get("agent_version") or "").strip():
        add_issue(issues, "error", "invalid_agent_version", f"{path}.agent_version", "agent_version must be a non-empty string")

    if "phase2_reference" in data and not isinstance(data.get("phase2_reference"), bool):
        add_issue(issues, "error", "invalid_phase2_reference", f"{path}.phase2_reference", "phase2_reference must be boolean")
    if "phase2_active" in data and not isinstance(data.get("phase2_active"), bool):
        add_issue(issues, "error", "invalid_phase2_active", f"{path}.phase2_active", "phase2_active must be boolean")

    if bool(data.get("phase2_active")):
        approval = data.get("phase2_activation_approval")
        if not isinstance(approval, dict):
            add_issue(
                issues,
                "error",
                "phase2_activation_missing",
                f"{path}.phase2_activation_approval",
                "phase2_active=true requires phase2_activation_approval",
            )
        else:
            for field in ("approved_by", "approved_at", "approval_source"):
                if field not in approval:
                    add_issue(
                        issues,
                        "error",
                        "phase2_activation_missing_field",
                        f"{path}.phase2_activation_approval.{field}",
                        f"phase2 activation approval missing {field}",
                    )

    if "phase2_activation_requires" in data and not isinstance(data.get("phase2_activation_requires"), list):
        add_issue(issues, "error", "invalid_phase2_activation_requires", f"{path}.phase2_activation_requires", "phase2_activation_requires must be a list")

    return issues


def agent_core_uses_root_version(project_root: Path, version_path: Path) -> bool:
    if version_path.exists():
        return False
    try:
        expected = project_root.resolve() / ".agent" / "agent_version.json"
        if version_path.resolve() != expected:
            return False
    except OSError:
        return False
    return (
        (project_root / "VERSION").is_file()
        and (project_root / "templates" / ".agent" / "agent_version.json").is_file()
        and (project_root / "scripts" / "agent_control" / "agent_core_release_updater.py").is_file()
    )


def validate_owner_directives(path: Path) -> list[dict[str, str]]:
    data, issues = load_json(path)
    if data is None:
        return issues
    if not isinstance(data, dict):
        add_issue(issues, "error", "invalid_root_type", str(path), "owner_directives.json must be an object")
        return issues

    required = ["schema_version", "phase2_reference", "phase2_active", "directives"]
    for field in required:
        if field not in data:
            add_issue(issues, "error", "missing_required_field", f"{path}.{field}", f"missing {field}")

    if "schema_version" in data and not isinstance(data.get("schema_version"), int):
        add_issue(issues, "error", "invalid_schema_version", f"{path}.schema_version", "schema_version must be an integer")
    if not isinstance(data.get("phase2_reference"), bool):
        add_issue(issues, "error", "invalid_phase2_reference", f"{path}.phase2_reference", "phase2_reference must be boolean")
    if not isinstance(data.get("phase2_active"), bool):
        add_issue(issues, "error", "invalid_phase2_active", f"{path}.phase2_active", "phase2_active must be boolean")

    directives = data.get("directives")
    if not isinstance(directives, list):
        add_issue(issues, "error", "directives_not_array", f"{path}.directives", "directives must be an array")
        return issues

    for index, directive in enumerate(directives):
        item_path = f"directives[{index}]"
        if not isinstance(directive, dict):
            add_issue(issues, "error", "directive_not_object", item_path, "directive must be an object")
            continue
        for field in ("id", "status", "title"):
            if field not in directive:
                add_issue(issues, "error", "directive_missing_field", f"{item_path}.{field}", f"directive missing {field}")
        status = str(directive.get("status") or "").strip()
        if status and status not in KNOWN_DIRECTIVE_STATUSES:
            add_issue(issues, "warning", "unknown_directive_status", f"{item_path}.status", f"unknown directive status: {status}")
        if status == "active" and not has_value(directive.get("source")):
            add_issue(
                issues,
                "warning",
                "active_directive_missing_source",
                f"{item_path}.source",
                "active directive should have source",
            )

    return issues


def git_changed_files(project_root: Path) -> tuple[list[str], list[dict[str, str]]]:
    if not (project_root / ".git").exists():
        return [], [{"severity": "warning", "code": "not_a_git_repo", "path": str(project_root), "message": "git changed-file checks skipped (no .git)"}]

    commands = [
        ["git", "-C", str(project_root), "diff", "--name-only"],
        ["git", "-C", str(project_root), "diff", "--name-only", "--cached"],
        ["git", "-C", str(project_root), "ls-files", "--others", "--exclude-standard"],
    ]
    changed: list[str] = []
    issues: list[dict[str, str]] = []
    for command in commands:
        try:
            proc = subprocess.run(command, capture_output=True, text=True, check=False)
        except OSError as exc:
            issues.append({"severity": "warning", "code": "git_command_failed", "path": command[0], "message": str(exc)})
            continue
        if proc.returncode != 0:
            issues.append(
                {
                    "severity": "warning",
                    "code": "git_command_failed",
                    "path": " ".join(command),
                    "message": proc.stderr.strip() or "git command failed without details",
                },
            )
            continue
        for line in proc.stdout.splitlines():
            path = line.strip().replace("\\", "/")
            if path and path not in changed:
                changed.append(path)
    return sorted(changed), issues


def path_matches(pattern: str, path: str) -> bool:
    pattern = pattern.strip().lower()
    normalized = path.strip().replace("\\", "/").lower()

    if fnmatch.fnmatch(normalized, pattern):
        return True
    if normalized == pattern:
        return True
    if normalized.startswith(f"{pattern.rstrip('/')}/"):
        return True

    parts = normalized.split("/")
    if pattern in parts:
        return True
    return pattern in normalized


def classify_protected_match(match: str) -> str:
    value = match.lower()
    if value.startswith(".env"):
        return "no_env_commit"
    if value in {"secrets", "secret", "tokens", "private keys"} or ".secret" in value or "token" in value:
        return "no_secret_commit"
    if value in {"production", "production credentials", "production config"} or value.startswith(".github/workflows/"):
        return "no_production_touch_without_owner"
    if value in {"agent_version", "task_queue", "agent_locks", "owner_directives"}:
        return "no_production_touch_without_owner"
    return "no_production_touch_without_owner"


def validate_protected_paths(
    project_root: Path,
    owner_approved: bool,
    agent_version_path: Path,
) -> tuple[list[str], list[dict[str, str]]]:
    issues: list[dict[str, str]] = []
    changes, diff_issues = git_changed_files(project_root)
    issues.extend(diff_issues)
    if owner_approved:
        return changes, issues

    data, _ = load_json(agent_version_path)
    protected_patterns = FALLBACK_PROTECTED_PATTERNS.copy()
    if isinstance(data, dict):
        protected_patterns.extend(str(item) for item in data.get("protected_project_files", []) if item)

    protected_patterns = sorted(set(p for p in protected_patterns if p))
    for path in changes:
        for pattern in protected_patterns:
            if path_matches(pattern, path):
                issue_code = classify_protected_match(pattern)
                add_issue(
                    issues,
                    "warning",
                    issue_code,
                    path,
                    f"protected pattern matched ({pattern}); modify only with explicit owner approval",
                )
                break

    return changes, issues


def validate_traceability(queue_data: dict[str, Any]) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []
    items = queue_data.get("tasks", []) if isinstance(queue_data, dict) else []
    if not isinstance(items, list):
        return issues

    for index, task in enumerate(items):
        if not isinstance(task, dict):
            continue
        status = str(task.get("status") or "").strip()
        if not status or status not in TRACEABLE_STATUSES:
            continue
        evidence = task.get("evidence") if isinstance(task.get("evidence"), dict) else {}
        branch = (
            str(task.get("branch") or "").strip()
            or str(task.get("github_branch") or "").strip()
            or str(task.get("source_branch") or "").strip()
            or str(task.get("clean_rebuild_source_branch") or "").strip()
            or str(evidence.get("branch") or "").strip()
            or str(evidence.get("github_branch") or "").strip()
        )
        commit = (
            str(task.get("merge_commit") or "").strip()
            or str(task.get("worker_result_commit") or "").strip()
            or str(task.get("commit") or "").strip()
            or str(evidence.get("commit") or evidence.get("github_commit") or "").strip()
        )
        commits = task.get("commits")
        report = (
            str(task.get("worker_report") or "").strip()
            or str(task.get("integration_report") or "").strip()
            or str(task.get("finalization_report") or "").strip()
        )
        pr = task.get("github_pr")
        if branch or commit or has_value(pr) or is_list_of_non_empty_str(commits) or report:
            continue
        issues.append({
            "severity": "warning",
            "code": "traceability_missing_branch_or_pr",
            "path": f"tasks[{index}]",
            "message": "task in traceable status has no branch, commit, report, or github_pr",
        })

    return issues


def validate_terminal_task_integration_state(queue_data: dict[str, Any]) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []
    items = queue_data.get("tasks", []) if isinstance(queue_data, dict) else []
    if not isinstance(items, list):
        return issues

    for index, task in enumerate(items):
        if not isinstance(task, dict):
            continue

        status = str(task.get("status") or "").strip()
        if status not in TERMINAL_TASK_STATUSES:
            continue

        integration_status = str(task.get("integration_status") or "").strip()
        evidence = task.get("evidence") if isinstance(task.get("evidence"), dict) else {}
        branch = (
            str(task.get("branch") or "").strip()
            or str(task.get("github_branch") or "").strip()
            or str(task.get("source_branch") or "").strip()
            or str(evidence.get("branch") or "").strip()
        )
        if branch and not integration_status:
            add_issue(
                issues,
                "error",
                "done_task_missing_integration_status",
                f"tasks[{index}].integration_status",
                "terminal task with a source branch must declare a terminal integration disposition",
            )
        if integration_status and integration_status not in TERMINAL_INTEGRATION_STATUSES:
            suffix = ""
            if integration_status in NONTERMINAL_DONE_INTEGRATION_STATUSES:
                suffix = "; close or repair the stale integration state"
            add_issue(
                issues,
                "warning",
                "done_task_nonterminal_integration_status",
                f"tasks[{index}].integration_status",
                f"terminal task has non-terminal integration_status: {integration_status}{suffix}",
            )
        explicit_evidence = any(
            has_value(task.get(key))
            for key in (
                "merge_commit",
                "integrated_commit",
                "integration_report",
                "finalization_report",
                "repository_hygiene_integration_evidence",
            )
        ) or any(has_value(evidence.get(key)) for key in ("commit", "github_commit", "integration_proof"))
        if branch and integration_status in TERMINAL_INTEGRATION_STATUSES and not explicit_evidence:
            add_issue(
                issues,
                "warning",
                "done_task_integration_claim_needs_independent_evidence",
                f"tasks[{index}]",
                "terminal integration status is a claim; Repository Hygiene must verify ancestry, patch, capability, or no-product evidence",
            )

        lock = task.get("lock")
        lock_state = str(lock.get("state") if isinstance(lock, dict) else lock or "").strip()
        if lock_state == "review":
            add_issue(
                issues,
                "warning",
                "done_task_review_lock",
                f"tasks[{index}].lock",
                "terminal task still carries a review lock",
            )

    return issues


def validate_dispatcher_readiness_contract(queue_path: Path) -> list[dict[str, str]]:
    issues: list[dict[str, str]] = []
    if not queue_path.exists():
        return issues
    try:
        data = validate_task_queue_readiness.load_json(queue_path)
    except (OSError, json.JSONDecodeError) as exc:
        return [{
            "severity": "error",
            "code": "dispatcher_readiness_unreadable",
            "path": str(queue_path),
            "message": f"cannot validate dispatcher readiness contract: {exc}",
        }]
    tasks = data.get("tasks", []) if isinstance(data, dict) else []
    if not isinstance(tasks, list):
        return []
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            continue
        validate_task_queue_readiness.validate_task(task, index, issues)
    return issues


def build_report(project_root: Path, queue_path: Path, locks_path: Path, version_path: Path, directives_path: Path, owner_approved: bool) -> dict[str, Any]:
    queue_task_ids, queue_issues = validate_task_queue(queue_path)
    issues: list[dict[str, str]] = list(queue_issues)
    issues.extend(validate_dispatcher_readiness_contract(queue_path))
    issues.extend(validate_locks(locks_path, set(queue_task_ids)))

    version_data = None
    if version_path.exists():
        version_data, version_issues = load_json(version_path)
        issues.extend(version_issues)
    if not agent_core_uses_root_version(project_root, version_path):
        issues.extend(validate_agent_version(version_path))
    issues.extend(validate_owner_directives(directives_path))

    if isinstance(version_data, dict):
        queue_content, _ = load_json(queue_path)
        if queue_content is not None:
            issues.extend(validate_traceability(queue_content))
            issues.extend(validate_terminal_task_integration_state(queue_content))

    changed_paths, protected_issues = validate_protected_paths(
        project_root,
        owner_approved=owner_approved,
        agent_version_path=version_path,
    )
    issues.extend(protected_issues)

    errors = [item for item in issues if item["severity"] == "error"]
    warnings = [item for item in issues if item["severity"] == "warning"]

    return {
        "project_root": str(project_root),
        "queue_path": str(queue_path),
        "locks_path": str(locks_path),
        "agent_version_path": str(version_path),
        "owner_directives_path": str(directives_path),
        "errors": len(errors),
        "warnings": len(warnings),
        "issues": issues,
        "changed_files": changed_paths,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate agent state files.")
    parser.add_argument("--project-root", default=".", help="Project root path (default: .)")
    parser.add_argument("--queue", help="Override task_queue path")
    parser.add_argument("--agent-locks", help="Override agent_locks.json path")
    parser.add_argument("--agent-version", help="Override agent_version.json path")
    parser.add_argument("--owner-directives", help="Override owner_directives.json path")
    parser.add_argument("--json", action="store_true", help="Emit JSON report")
    parser.add_argument("--warnings-as-errors", action="store_true", help="Treat warnings as failures")
    parser.add_argument("--owner-approved", action="store_true", help="Skip protected-path owner warnings")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_root = Path(args.project_root).resolve()

    queue_path = Path(args.queue).resolve() if args.queue else project_root / "AiStudio" / "Task_manager" / "task_queue.json"
    locks_path = Path(args.agent_locks).resolve() if args.agent_locks else project_root / "AiStudio" / "Task_manager" / "agent_locks.json"
    version_path = Path(args.agent_version).resolve() if args.agent_version else project_root / ".agent" / "agent_version.json"
    directives_path = Path(args.owner_directives).resolve() if args.owner_directives else project_root / "AiStudio" / "Task_manager" / "owner_directives.json"

    report = build_report(
        project_root=project_root,
        queue_path=queue_path,
        locks_path=locks_path,
        version_path=version_path,
        directives_path=directives_path,
        owner_approved=bool(args.owner_approved),
    )

    errors = report.get("errors", 0)
    warnings = report.get("warnings", 0)
    exit_code = 0

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"project_root: {report['project_root']}")
        print(f"queue: {report['queue_path']}")
        print(f"agent_locks: {report['locks_path']}")
        print(f"agent_version: {report['agent_version_path']}")
        print(f"owner_directives: {report['owner_directives_path']}")
        print(f"errors: {errors}")
        print(f"warnings: {warnings}")
        if report["changed_files"]:
            print(f"changed_files: {len(report['changed_files'])}")
        for issue in report["issues"]:
            print(f"{issue['severity'].upper()} {issue['code']} {issue['path']}: {issue['message']}")

    if errors > 0 or (args.warnings_as_errors and warnings > 0):
        exit_code = 1
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
