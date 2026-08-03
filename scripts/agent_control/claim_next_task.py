#!/usr/bin/env python3
"""Claim the next worker-ready task from a shared queue.

The claim happens in a temporary detached git worktree, then pushes one lock
commit back to the queue branch. This keeps task selection centralized and
prevents parallel workers from choosing the same snapshot.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import validate_task_queue_readiness
from codex_model_capability import default_catalog_path, resolve_requested_model
from project_paths import task_file, task_relpath
from task_control_postgres import TaskControlPostgres


TASK_DB_DSN_ENV_NAME = "AISTUDIO_TASK_DB_DSN_ENV"


def task_control_database_from_runtime_env() -> TaskControlPostgres:
    env_name = os.environ.get(TASK_DB_DSN_ENV_NAME, "").strip() or "AISTUDIO_TASK_DB_DSN"
    return TaskControlPostgres.from_env(env_name)


REQUIRED_WORKER_PACKET_LIST_FIELDS = (
    "worker_instructions",
    "doc_refs",
    "script_actions",
    "allowed_paths",
    "forbidden_paths",
    "acceptance_criteria",
    "checks",
)
REQUIRED_WORKER_PACKET_OBJECT_FIELDS = ("traceability", "output_contract")
REQUIRED_WORKER_PACKET_VALUE_FIELDS = ("input_refs",)
TASK_COMPLEXITY_ALIASES = {"XS": "S", "S-M": "M"}
TASK_COMPLEXITIES = {"S", "M", "L", "XL"}
SCHEDULING_CLASS_RANK = {
    "integration_continuation": 0,
    "primary_delivery": 1,
    "background_remediation": 2,
}
BACKGROUND_SOURCE_MARKERS = {
    "artifact_discovery_followup",
    "artifact_discovery_routed_report",
    "artifact_discovery_normalizer.py",
    "project_rules_remediation_candidate",
}
SENSITIVE_SOURCE_MARKERS = {
    "authorization",
    "authentication",
    "billing",
    "credential",
    "payment",
    "personal_data",
    "pii",
    "secret",
    "security",
    "sensitive",
    "webhook",
}


DEFAULT_PROFILES: dict[str, dict[str, Any]] = {
    "auto-worker-5.3-mini": {
        "selection_order": ["S"],
        "allowed_types": ["docs", "tests", "automation", "small-fix", "backlog"],
    },
    "auto-worker-5.3": {
        "selection_order": ["M", "S"],
        "allowed_types": ["docs", "tests", "automation", "focused-implementation", "backlog"],
    },
    "auto-worker-5.5": {
        "selection_order": ["L"],
        "allowed_types": ["implementation", "integration", "tests", "migration", "docs", "backlog"],
    },
    "auto-worker-5.5max": {
        "selection_order": ["XL", "L"],
        "allowed_types": ["worker-ready-xl", "critical-implementation", "release-critical", "integration", "backlog"],
    },
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def compact_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip()).strip("-._").lower() or "task"


def claim_worktree_path(claim_root: Path, project_root: Path, worker_id: str, attempt: int) -> Path:
    """Return a process-unique path for concurrent claims from one worker profile."""
    return claim_root / f"{project_root.name}-{slug(worker_id)}-{compact_now()}-p{os.getpid()}-{attempt}"


def git_command(cmd: list[str]) -> list[str]:
    if cmd and cmd[0] == "git":
        return ["git", "-c", "core.longpaths=true", *cmd[1:]]
    return cmd


def run(cmd: list[str], cwd: Path, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(git_command(cmd), cwd=str(cwd), text=True, capture_output=True, check=check)


def fetch_origin(repo: Path) -> None:
    run(["git", "fetch", "--all", "--prune"], repo)


def refresh_push_ref(repo: Path, push_ref: str) -> None:
    run(["git", "fetch", "origin", f"{push_ref}:refs/remotes/origin/{push_ref}"], repo, check=False)


def is_push_rejected(stderr: str) -> bool:
    text = stderr.lower()
    return any(
        marker in text
        for marker in (
            "rejected",
            "fetch first",
            "non-fast-forward",
            "failed to push some refs",
            "tip of your current branch is behind",
        )
    )


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
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
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def exact_repository_input_refs(task: dict[str, Any]) -> list[str]:
    """Return exact repository-relative code refs that must exist before claim."""
    values: list[Any] = []
    values.extend(as_list(task.get("code_refs")))
    context_inventory = task.get("context_inventory")
    if isinstance(context_inventory, dict):
        values.extend(as_list(context_inventory.get("code_refs")))
    refs: list[str] = []
    for value in values:
        if isinstance(value, dict):
            value = value.get("path")
        ref = str(value or "").replace("\\", "/").strip()
        if (
            not ref
            or ref.startswith(("runtime-generated:", "http://", "https://", "file://", "/"))
            or re.match(r"^[A-Za-z]:/", ref)
            or any(marker in ref for marker in ("*", "?", "[", "]", "{", "}"))
            or ref.startswith(("../", "~/"))
        ):
            continue
        if ref not in refs:
            refs.append(ref)
    return refs


def missing_repository_input_refs(task: dict[str, Any], project_root: Path) -> list[str]:
    source_ref = ""
    if str(task.get("type") or "").strip().lower() == "clean-rebuild":
        provenance = task.get("provenance")
        if not isinstance(provenance, dict):
            provenance = {}
        source_ref = str(
            task.get("clean_rebuild_source_head_sha")
            or task.get("source_head_sha")
            or provenance.get("source_head_sha")
            or task.get("clean_rebuild_source_branch")
            or task.get("source_branch")
            or provenance.get("source_branch")
            or ""
        ).strip()

    return [
        ref
        for ref in exact_repository_input_refs(task)
        if not (project_root / ref).exists()
        and not (
            source_ref
            and run(
                ["git", "cat-file", "-e", f"{source_ref}:{ref}"],
                project_root,
                check=False,
            ).returncode
            == 0
        )
    ]


def branch_for(machine_id: str, worker_id: str, task_id_value: str, title: str) -> str:
    title_part = slug(title)[:48]
    return f"AiStudio/Agent/worker/{slug(machine_id)}/{slug(worker_id)}/{slug(task_id_value)}/{title_part}".rstrip("/")


def needs_fresh_retry_branch(task: dict[str, Any]) -> bool:
    if task.get("model_limit_retry_allowed") is True or task.get("model_limit_retry_promoted_at"):
        return True
    if (
        str(task.get("integration_status") or "") in {"needs_worker_fix", "returned_to_worker"}
        and str(task.get("worker_result_commit") or "").strip()
        and existing_task_branch(task)
    ):
        return True
    abandoned_claims = task.get("abandoned_claims")
    if isinstance(abandoned_claims, list) and any(
        isinstance(item, dict) and str(item.get("branch") or "").strip()
        for item in abandoned_claims
    ):
        return True
    history = task.get("worker_ready_promotion_history")
    if isinstance(history, list):
        if any(
            isinstance(item, dict)
            and str(item.get("previous_integration_status") or "") == "blocked_model_limit"
            for item in history
        ):
            return True
    status_history = task.get("status_history")
    if isinstance(status_history, list):
        retry_events = {
            "worker_finalize_failed_routed",
            "worker_finalize_failed_requeued",
            "worker_launch_failed_requeued",
            "worker_fix_requeued",
        }
        return any(
            isinstance(item, dict)
            and (
                str(item.get("event") or "") in retry_events
                or (
                    str(item.get("event") or "") == "integration_routed"
                    and str(item.get("next_owner") or "").lower() in {"worker", "worker_pool"}
                )
                or str(item.get("reason") or "").lower().startswith(("worker finalize failed", "worker launch failed"))
            )
            for item in status_history
        )
    return False


def retry_branch_for(machine_id: str, worker_id: str, task_id_value: str, title: str) -> str:
    return f"{branch_for(machine_id, worker_id, task_id_value, title)}-retry-{compact_now()}"


def existing_task_branch(task: dict[str, Any]) -> str | None:
    for key in ("branch", "github_branch"):
        value = task.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def branch_closed_or_archived(task: dict[str, Any]) -> bool:
    if str(task.get("branch_state") or "").lower() in {"closed", "archived", "deleted"}:
        return True
    if str(task.get("branch_status") or "").lower() in {"closed", "archived", "deleted"}:
        return True
    status = str(task.get("status") or "").lower()
    return status in {"done", "completed", "finalized", "released", "archived"}


def load_profile(project_root: Path, worker_id: str) -> dict[str, Any]:
    profile_path = project_root / ".agent" / "worker_profiles.json"
    profiles = load_json(profile_path)
    if isinstance(profiles, list):
        for profile in profiles:
            if isinstance(profile, dict) and profile.get("worker_id") == worker_id:
                return profile
    if isinstance(profiles, dict):
        candidates = profiles.get("profiles") if isinstance(profiles.get("profiles"), list) else []
        for profile in candidates:
            if isinstance(profile, dict) and profile.get("worker_id") == worker_id:
                return profile
    return DEFAULT_PROFILES.get(worker_id, {"selection_order": []})


def active_lock_ids(locks: dict[str, Any]) -> set[str]:
    ids: set[str] = set()
    for lock in locks.get("locks", []) if isinstance(locks.get("locks"), list) else []:
        if isinstance(lock, dict) and lock.get("state") in {"locked", "in_progress", "review"}:
            value = str(lock.get("task_id") or "").strip()
            if value:
                ids.add(value)
    return ids


def task_lock_state(task: dict[str, Any]) -> str:
    lock = task.get("lock")
    if isinstance(lock, dict):
        return str(lock.get("state") or "free").lower()
    return str(lock or "free").lower()


def task_worker_constraints(task: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for field in ("eligible_worker_profiles", "eligible_workers", "worker_profiles"):
        for item in as_list(task.get(field)):
            if item:
                values.append(str(item))
    for field in ("worker_profile", "worker"):
        value = task.get(field)
        if value:
            values.append(str(value))
    return values


def completed_task_ids(tasks: list[dict[str, Any]]) -> set[str]:
    done_statuses = {"done", "completed", "finalized", "released", "archived", "owner_approved"}
    result: set[str] = set()
    for item in tasks:
        if str(item.get("status") or "").lower() in done_statuses:
            value = task_id(item)
            if value:
                result.add(value)
    return result


def unresolved_dependencies(task: dict[str, Any], completed_ids: set[str]) -> list[str]:
    return [str(item).strip() for item in as_list(task.get("depends_on")) if str(item).strip() and str(item).strip() not in completed_ids]


def task_complexity(task: dict[str, Any]) -> str | None:
    value = task.get("complexity")
    if value is None and isinstance(task.get("next_run_recommendation"), dict):
        value = task["next_run_recommendation"].get("complexity")
    normalized = str(value or "").strip().upper()
    return TASK_COMPLEXITY_ALIASES.get(normalized, normalized) or None


def worker_packet_defects(task: dict[str, Any], project_root: Path | None = None) -> list[str]:
    defects: list[str] = []
    for field in REQUIRED_WORKER_PACKET_LIST_FIELDS:
        value = task.get(field)
        if not isinstance(value, list) or not value:
            defects.append(f"missing_or_empty:{field}")
    for field in REQUIRED_WORKER_PACKET_OBJECT_FIELDS:
        value = task.get(field)
        if not isinstance(value, dict) or not value:
            defects.append(f"missing_or_empty:{field}")
    for field in REQUIRED_WORKER_PACKET_VALUE_FIELDS:
        if not has_value(task.get(field)):
            defects.append(f"missing_or_empty:{field}")
    complexity = task_complexity(task)
    if complexity not in TASK_COMPLEXITIES:
        defects.append("missing_or_invalid:complexity")
    if validate_task_queue_readiness.unsafe_allowed_paths(task):
        defects.append("unsafe_scope:allowed_paths")
    if (
        validate_task_queue_readiness.is_migration_sensitive(task)
        and not validate_task_queue_readiness.has_compatibility_policy(task)
    ):
        defects.append("migration_without_compatibility_policy")
    if project_root is not None:
        defects.extend(
            f"missing_input_ref:{ref}"
            for ref in missing_repository_input_refs(task, project_root)
        )
    return defects


def worker_packet_v2_ready(task: dict[str, Any]) -> bool:
    return not worker_packet_defects(task)


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def decomposition_parent_allows_claim(
    task: dict[str, Any],
    tasks_by_id: dict[str, dict[str, Any]] | None,
) -> bool:
    if str(task.get("integration_repair_kind") or "") != "proactive_pr_decomposition":
        return True
    parent_id = str(task.get("integration_repair_parent_id") or "").strip()
    parent = (tasks_by_id or {}).get(parent_id)
    if not parent:
        return False
    if (
        str(parent.get("status") or "") != "blocked_by_dependency"
        or str(parent.get("integration_status") or "") != "repair_in_progress"
        or str(parent.get("dispatcher_decision") or "") != "split_into_children"
    ):
        return False
    parent_head = str(parent.get("repository_hygiene_head_sha") or parent.get("head_sha") or "").strip().lower()
    child_head = str(task.get("source_head_sha") or task.get("clean_rebuild_source_head_sha") or "").strip().lower()
    return bool(re.fullmatch(r"[0-9a-f]{40}", parent_head)) and child_head == parent_head


def eligible(task: dict[str, Any], profile: dict[str, Any], worker_id: str, locked_ids: set[str], completed_ids: set[str] | None = None, *, require_packet_v2: bool = True, tasks_by_id: dict[str, dict[str, Any]] | None = None) -> bool:
    value = task_id(task)
    if not value or value in locked_ids:
        return False
    if task.get("status") not in {"planned", "needs_stronger_agent", "worker_ready"}:
        return False
    if task.get("worker_ready") is not True or task.get("dispatcher_decision") != "worker_ready":
        return False
    canonical_task_id = str(task.get("canonical_task_id") or "").strip()
    if has_value(task.get("blocked_by")) or has_value(task.get("split_into")):
        return False
    if canonical_task_id and canonical_task_id != value:
        return False
    if unresolved_dependencies(task, completed_ids or set()):
        return False
    if not decomposition_parent_allows_claim(task, tasks_by_id):
        return False
    if task_lock_state(task) in {"locked", "in_progress", "review"}:
        return False
    complexity = task_complexity(task)
    if require_packet_v2 and complexity not in [str(item).upper() for item in profile.get("selection_order", [])]:
        return False
    eligible_profiles = task_worker_constraints(task)
    if eligible_profiles and worker_id not in eligible_profiles:
        return False
    forbidden_types = profile.get("forbidden_types")
    if isinstance(forbidden_types, list) and str(task.get("type") or "") in {str(item) for item in forbidden_types}:
        return False
    if require_packet_v2:
        for field in ("allowed_paths", "forbidden_paths", "acceptance_criteria", "checks"):
            if not has_value(task.get(field)):
                return False
    if require_packet_v2 and not worker_packet_v2_ready(task):
        return False
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        return False
    return True


def integration_repair_continuation(task: dict[str, Any]) -> bool:
    """Return true for Dispatcher-created packets that finish an in-flight PR integration."""
    return bool(str(task.get("integration_repair_parent_id") or "").strip()) and (
        str(task.get("source_lane") or "") == "dispatcher_integration_repair"
        or str(task.get("clean_rebuild_route") or "") == "auto_integrator_repair"
    )


def priority_level(task: dict[str, Any]) -> int | None:
    match = re.fullmatch(r"P(\d+)", str(task.get("priority") or "").strip().upper())
    return int(match.group(1)) if match else None


def priority_rank(task: dict[str, Any]) -> int:
    value = priority_level(task)
    return value if value is not None else 999


def scheduling_source_values(task: dict[str, Any]) -> list[str]:
    values = [
        task.get("type"),
        task.get("source_lane"),
        task.get("source_type"),
        task.get("task_origin"),
        task.get("created_by"),
        task.get("source_finding_category"),
        task.get("category"),
    ]
    for item in as_list(task.get("provenance")):
        if isinstance(item, dict):
            values.extend((item.get("source_type"), item.get("source_item_id")))
    return [str(value).strip().lower().replace("\\", "/") for value in values if has_value(value)]


def sensitive_or_security_remediation(task: dict[str, Any], source_values: list[str]) -> bool:
    boolean_guards = (
        "owner_approval_required",
        "requires_human",
        "security_review_required",
        "sensitive",
        "sensitive_data",
    )
    if any(task.get(field) is True for field in boolean_guards):
        return True
    risk_values = {
        str(task.get(field) or "").strip().lower()
        for field in ("risk", "risk_level", "security_risk", "data_sensitivity")
    }
    if risk_values & {"critical", "high", "restricted", "secret", "sensitive"}:
        return True
    structured_risk_values = [
        str(task.get("type") or "").lower(),
        str(task.get("category") or "").lower(),
        str(task.get("source_finding_category") or "").lower(),
        *source_values,
    ]
    return any(
        re.search(rf"(^|[/_.-]){re.escape(marker)}([/_.-]|$)", value)
        for marker in SENSITIVE_SOURCE_MARKERS
        for value in structured_risk_values
    )


def background_source_reason(task: dict[str, Any], source_values: list[str]) -> str | None:
    task_type = str(task.get("type") or "").strip().lower()
    if (
        task_type.startswith("automation/project_rules_remediation/")
        or task_type == "project_rules_remediation_candidate"
        or any("project_rules_remediation" in value for value in source_values)
    ):
        return "structured_project_rules_remediation"
    if (
        task_type == "artifact_discovery_followup"
        or any(
            value in BACKGROUND_SOURCE_MARKERS
            or value.endswith("/artifact_discovery_normalizer.py")
            for value in source_values
        )
    ):
        return "structured_artifact_discovery"
    return None


def scheduling_class(task: dict[str, Any]) -> tuple[str, str]:
    if integration_repair_continuation(task):
        return "integration_continuation", "in_flight_integration_repair"
    source_values = scheduling_source_values(task)
    source_reason = background_source_reason(task, source_values)
    priority = priority_level(task)
    if source_reason and priority is not None and priority >= 2:
        if sensitive_or_security_remediation(task, source_values):
            return "primary_delivery", "sensitive_or_security_remediation"
        return "background_remediation", source_reason
    if source_reason:
        return (
            ("primary_delivery", "p0_p1_remediation")
            if priority is not None
            else ("primary_delivery", "unknown_priority_remediation")
        )
    return "primary_delivery", "default_primary_work"


def task_selection_key(task: dict[str, Any], profile: dict[str, Any]) -> tuple[int, int, int, str, str]:
    order = [str(item).upper() for item in profile.get("selection_order", [])]
    complexity = task_complexity(task) or ""
    complexity_rank = order.index(complexity) if complexity in order else len(order)
    created_at = str(task.get("created_at") or task.get("discovered_at") or task.get("updated_at") or "9999")
    task_class, _ = scheduling_class(task)
    return (
        SCHEDULING_CLASS_RANK[task_class],
        priority_rank(task),
        complexity_rank,
        created_at,
        task_id(task),
    )



def choose_defective_packet_task(
    tasks: list[dict[str, Any]],
    profile: dict[str, Any],
    worker_id: str,
    locked_ids: set[str],
    exact_task_id: str | None = None,
    project_root: Path | None = None,
) -> tuple[dict[str, Any], list[str]] | None:
    completed_ids = completed_task_ids(tasks)
    tasks_by_id = {task_id(task): task for task in tasks if task_id(task)}
    candidates: list[tuple[dict[str, Any], list[str]]] = []
    for task in tasks:
        if not eligible(
            task,
            profile,
            worker_id,
            locked_ids,
            completed_ids,
            require_packet_v2=False,
            tasks_by_id=tasks_by_id,
        ):
            continue
        defects = worker_packet_defects(task, project_root)
        if defects:
            candidates.append((task, defects))
    if exact_task_id:
        requested = str(exact_task_id).strip()
        return next((item for item in candidates if task_id(item[0]) == requested), None)
    candidates.sort(key=lambda item: task_selection_key(item[0], profile))
    return candidates[0] if candidates else None


def route_task_packet_defect(
    queue: dict[str, Any],
    profile: dict[str, Any],
    worker_id: str,
    locked_ids: set[str],
    exact_task_id: str | None = None,
    project_root: Path | None = None,
) -> dict[str, Any] | None:
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        raise SystemExit("task_queue.json must contain tasks array")
    selected = choose_defective_packet_task(
        [task for task in tasks if isinstance(task, dict)],
        profile,
        worker_id,
        locked_ids,
        exact_task_id,
        project_root,
    )
    if not selected:
        return None
    task, defects = selected
    value = task_id(task)
    now = utc_now()
    before = str(task.get("status") or "")
    task["status"] = "needs_dispatcher_repair"
    task["lock"] = "free"
    task["worker_ready"] = False
    task["dispatcher_decision"] = "needs_dispatcher_repair"
    task["dispatcher_decision_reason"] = "worker packet v2 defect found before worker claim"
    task["packet_status"] = "needs_dispatcher_repair"
    task["normalization_status"] = "needs_dispatcher_repair"
    task["next_owner"] = "Dispatcher"
    task["task_packet_defects"] = defects
    task["missing_packet_fields"] = defects
    task["repair_request"] = "Repair the listed Worker Packet v2 defects before worker claim."
    task["repair_owner"] = "dispatcher"
    task["next_action"] = "Refresh stale input references or complete missing Worker Packet v2 fields, then rerun Dispatcher repair."
    task["dispatcher_next_review_at"] = now
    task["not_worker_ready_reason"] = "Worker Packet v2 is incomplete; Dispatcher must repair before worker execution"
    task["status_reason"] = task["not_worker_ready_reason"]
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "at": now,
        "by": "claim_next_task",
        "from": before,
        "to": "needs_dispatcher_repair",
        "reason": "worker_packet_v2_defect",
        "defects": defects,
        "next_owner": "Dispatcher",
    })
    task["status_history"] = history
    queue["updated_at"] = now
    return {
        "claimed": False,
        "reason": "task_packet_defect_routed",
        "task_id": value,
        "worker_id": worker_id,
        "defects": defects,
        "next_owner": "Dispatcher",
    }


def route_model_capability_defect(
    queue: dict[str, Any],
    profile: dict[str, Any],
    worker_id: str,
    locked_ids: set[str],
    requested_model: str | None,
    resolution: dict[str, Any],
    exact_task_id: str | None = None,
) -> dict[str, Any] | None:
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        raise SystemExit("task_queue.json must contain tasks array")
    chosen = choose_task([task for task in tasks if isinstance(task, dict)], profile, worker_id, locked_ids, exact_task_id)
    if not chosen:
        return None
    now = utc_now()
    before = str(chosen.get("status") or "")
    reason = str(resolution.get("reason") or "model_capability_unavailable")
    chosen.update(
        {
            "status": "needs_dispatcher_repair",
            "lock": "free",
            "worker_ready": False,
            "dispatcher_decision": "needs_dispatcher_repair",
            "dispatcher_decision_reason": "requested Codex model could not be resolved from the local capability catalog before claim",
            "packet_status": "needs_dispatcher_repair",
            "normalization_status": "needs_dispatcher_repair",
            "next_owner": "Dispatcher",
            "repair_owner": "Dispatcher",
            "dispatcher_repair_kind": "model_capability",
            "requested_model": requested_model,
            "model_capability_reason": reason,
            "model_capability_catalog": resolution.get("catalog_path"),
            "repair_request": "Dispatcher must select an exact supported Codex model id or a policy alias present in the host catalog before reissuing Worker Packet v2.",
            "missing_packet_fields": ["resolved_codex_model_capability"],
            "next_action": "Refresh the host model catalog, select a supported exact model, then rerun Dispatcher repair. Do not retry the same unresolved alias.",
            "not_worker_ready_reason": f"pre-claim model capability gate failed: {reason}",
            "status_reason": f"pre-claim model capability gate failed: {reason}",
            "dispatcher_next_review_at": now,
        }
    )
    history = chosen.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append(
        {
            "at": now,
            "by": "claim_next_task",
            "from": before,
            "to": "needs_dispatcher_repair",
            "reason": "preclaim_model_capability_failed",
            "requested_model": requested_model,
            "model_capability_reason": reason,
            "next_owner": "Dispatcher",
        }
    )
    chosen["status_history"] = history
    queue["updated_at"] = now
    return {
        "claimed": False,
        "reason": "model_capability_routed",
        "task_id": task_id(chosen),
        "worker_id": worker_id,
        "requested_model": requested_model,
        "model_capability": resolution,
        "next_owner": "Dispatcher",
    }


def append_packet_defect_event(worktree: Path, repair: dict[str, Any]) -> str:
    event_id = f"task_packet_defect-{compact_now()}-{slug(str(repair.get('task_id') or 'task'))}"
    event = {
        "event_id": event_id,
        "created_at": utc_now(),
        "project": worktree.name,
        "event": "task_packet_defect",
        "role": str(repair.get("worker_id") or "worker_pool"),
        "next_role": "dispatcher",
        "task_id": repair.get("task_id"),
        "severity": "warning",
        "consumed_by": None,
        "consumed_at": None,
        "payload": {
            "reason": "worker execution was not started because its packet or model capability requires Dispatcher repair",
            "defects": repair.get("defects") or [],
            "next_owner": "Dispatcher",
        },
    }
    path = task_file(worktree, "agent_events.jsonl")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
    return event_id

def choose_task(
    tasks: list[dict[str, Any]],
    profile: dict[str, Any],
    worker_id: str,
    locked_ids: set[str],
    exact_task_id: str | None = None,
) -> dict[str, Any] | None:
    completed_ids = completed_task_ids(tasks)
    tasks_by_id = {task_id(task): task for task in tasks if task_id(task)}
    candidates = [
        task
        for task in tasks
        if eligible(task, profile, worker_id, locked_ids, completed_ids, tasks_by_id=tasks_by_id)
    ]
    if exact_task_id:
        requested = str(exact_task_id).strip()
        return next((task for task in candidates if task_id(task) == requested), None)
    candidates.sort(key=lambda task: task_selection_key(task, profile))
    return candidates[0] if candidates else None


def claim(
    queue: dict[str, Any],
    locks: dict[str, Any],
    profile: dict[str, Any],
    worker_id: str,
    machine_id: str,
    ttl_hours: int,
    exact_task_id: str | None = None,
) -> dict[str, Any] | None:
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        raise SystemExit("task_queue.json must contain tasks array")
    chosen = choose_task([task for task in tasks if isinstance(task, dict)], profile, worker_id, active_lock_ids(locks), exact_task_id)
    if not chosen:
        return None

    chosen_id = task_id(chosen)
    now = utc_now()
    expires = (datetime.now(timezone.utc) + timedelta(hours=ttl_hours)).isoformat(timespec="seconds").replace("+00:00", "Z")
    fresh_retry_branch = needs_fresh_retry_branch(chosen)
    branch = None if fresh_retry_branch else existing_task_branch(chosen) if not branch_closed_or_archived(chosen) else None
    if not branch:
        if fresh_retry_branch:
            branch = retry_branch_for(machine_id, worker_id, chosen_id, str(chosen.get("title") or "task"))
        else:
            branch = branch_for(machine_id, worker_id, chosen_id, str(chosen.get("title") or "task"))
    for task in tasks:
        if isinstance(task, dict) and task_id(task) == chosen_id:
            task["status"] = "in_progress"
            task["lock"] = "locked"
            task["worker_id"] = worker_id
            task["machine_id"] = machine_id
            task["branch"] = branch
            task["github_branch"] = branch
            task["started_at"] = now
            task["lock_expires_at"] = expires
            task["status_reason"] = "central runner claimed task before isolated worker launch"
            break
    queue["updated_at"] = now

    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        lock_list = []
        locks["locks"] = lock_list
    lock_list.append(
        {
            "task_id": chosen_id,
            "state": "in_progress",
            "by": worker_id,
            "machine_id": machine_id,
            "branch": branch,
            "at": now,
            "expires_at": expires,
            "notes": "central runner claim",
        }
    )
    locks["updated_at"] = now
    locks.setdefault("schema_version", 1)
    locks.setdefault("default_ttl_hours", ttl_hours)

    return {
        "task_id": chosen_id,
        "title": chosen.get("title"),
        "worker_id": worker_id,
        "machine_id": machine_id,
        "branch": branch,
        "base_ref": None,
        "claimed_at": now,
        "lock_expires_at": expires,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Claim the next worker-ready task and push the lock commit.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", required=True, help="Fresh queue/base ref to claim from.")
    parser.add_argument("--push-ref", help="Branch/ref to push claim commits to. Defaults to --base-ref without origin/.")
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--requested-model", help="Requested Codex model or policy alias; resolved before a task can be claimed.")
    parser.add_argument("--model-catalog", type=Path, default=default_catalog_path(), help="Host-local Codex models_cache.json.")
    parser.add_argument("--task-id", help="Claim this exact task id if it is eligible for the selected worker profile.")
    parser.add_argument("--machine-id", default="aistudio")
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--ttl-hours", type=int, default=8)
    parser.add_argument("--push-retries", type=int, default=5, help="Retry claim from fresh GitHub state after a rejected push.")
    parser.add_argument("--push-retry-delay", type=float, default=1.0, help="Seconds to wait between rejected-push retries.")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).expanduser().resolve()
    runtime_root = Path(args.runtime_root).expanduser().resolve()
    push_ref = args.push_ref or re.sub(r"^origin/", "", args.base_ref)
    claim_root = runtime_root / "claim-worktrees"
    claim_root.mkdir(parents=True, exist_ok=True)
    postgres_authority = os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY") == "postgres"
    if postgres_authority and not os.environ.get("AISTUDIO_TASK_CONTROL_SESSION_ID"):
        raise SystemExit("PostgreSQL task claim requires a managed project session")
    attempts = 1 if postgres_authority else max(1, args.push_retries + 1)
    last_rejection: dict[str, Any] | None = None
    for attempt in range(1, attempts + 1):
        worktree = claim_worktree_path(claim_root, project_root, args.worker_id, attempt)
        if not postgres_authority and (args.fetch or attempt > 1):
            fetch_origin(project_root)
            refresh_push_ref(project_root, push_ref)
        try:
            if worktree.exists():
                shutil.rmtree(worktree)
            run(["git", "worktree", "add", "--detach", str(worktree), args.base_ref], project_root)
            profile = load_profile(worktree, args.worker_id)
            queue_path = task_file(worktree, "task_queue.json")
            locks_path = task_file(worktree, "agent_locks.json")
            queue_relpath = None if postgres_authority else task_relpath(worktree, "task_queue.json")
            locks_relpath = None if postgres_authority else task_relpath(worktree, "agent_locks.json")
            queue = load_json(queue_path)
            locks = load_json(locks_path)
            repair_result = route_task_packet_defect(
                queue,
                profile,
                args.worker_id,
                active_lock_ids(locks),
                args.task_id,
                worktree,
            )
            if repair_result:
                repair_result.update({
                    "requested_task_id": args.task_id,
                    "attempt": attempt,
                    "retry_count": attempt - 1,
                })
                if args.dry_run:
                    print(json.dumps(repair_result, ensure_ascii=False, indent=2) if args.json else repair_result["reason"])
                    return 0
                event_id = append_packet_defect_event(worktree, repair_result)
                repair_result["event_id"] = event_id
                write_json(queue_path, queue)
                if postgres_authority:
                    print(json.dumps(repair_result, ensure_ascii=False, indent=2))
                    return 0
                run(["git", "add", queue_relpath, task_relpath(worktree, "agent_events.jsonl")], worktree)
                run(["git", "commit", "-m", f"chore(dispatcher): route packet repair {repair_result['task_id']}"], worktree)
                push = run(["git", "push", "origin", f"HEAD:{push_ref}"], worktree, check=False)
                if push.returncode == 0:
                    print(json.dumps(repair_result, ensure_ascii=False, indent=2))
                    return 0
                last_rejection = {"attempt": attempt, "stderr": push.stderr, "task_id": repair_result["task_id"]}
                if not is_push_rejected(push.stderr) or attempt >= attempts:
                    result = {"claimed": False, "reason": "push_rejected", "stderr": push.stderr, "attempt": attempt, "retry_count": attempt - 1}
                    print(json.dumps(result, ensure_ascii=False, indent=2))
                    return 3
                time.sleep(max(0.0, args.push_retry_delay))
                continue

            model_resolution: dict[str, Any] | None = None
            if args.requested_model:
                model_resolution = resolve_requested_model(project_root, args.requested_model, args.model_catalog)
            if model_resolution is not None and not model_resolution.get("ok"):
                repair_result = route_model_capability_defect(
                    queue,
                    profile,
                    args.worker_id,
                    active_lock_ids(locks),
                    args.requested_model,
                    model_resolution,
                    args.task_id,
                )
                if repair_result:
                    repair_result.update({"requested_task_id": args.task_id, "attempt": attempt, "retry_count": attempt - 1})
                    if args.dry_run:
                        print(json.dumps(repair_result, ensure_ascii=False, indent=2) if args.json else repair_result["reason"])
                        return 0
                    event_id = append_packet_defect_event(worktree, repair_result)
                    repair_result["event_id"] = event_id
                    write_json(queue_path, queue)
                    if postgres_authority:
                        print(json.dumps(repair_result, ensure_ascii=False, indent=2))
                        return 0
                    run(["git", "add", queue_relpath, task_relpath(worktree, "agent_events.jsonl")], worktree)
                    run(["git", "commit", "-m", f"chore(dispatcher): route model capability repair {repair_result['task_id']}"], worktree)
                    push = run(["git", "push", "origin", f"HEAD:{push_ref}"], worktree, check=False)
                    if push.returncode == 0:
                        print(json.dumps(repair_result, ensure_ascii=False, indent=2))
                        return 0
                    last_rejection = {"attempt": attempt, "stderr": push.stderr, "task_id": repair_result["task_id"]}
                    if not is_push_rejected(push.stderr) or attempt >= attempts:
                        result = {"claimed": False, "reason": "push_rejected", "stderr": push.stderr, "attempt": attempt, "retry_count": attempt - 1}
                        print(json.dumps(result, ensure_ascii=False, indent=2))
                        return 3
                    time.sleep(max(0.0, args.push_retry_delay))
                    continue

            claim_result = claim(queue, locks, profile, args.worker_id, args.machine_id, args.ttl_hours, args.task_id)
            if not claim_result:
                result = {
                    "claimed": False,
                    "reason": "no_eligible_task",
                    "worker_id": args.worker_id,
                    "requested_task_id": args.task_id,
                    "attempt": attempt,
                    "retry_count": attempt - 1,
                }
                if last_rejection:
                    result["previous_push_rejection"] = last_rejection
                print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else result["reason"])
                return 0
            claim_result["base_ref"] = push_ref
            if model_resolution is not None:
                claim_result["requested_model"] = args.requested_model
                claim_result["resolved_model"] = model_resolution["resolved_model"]
                claim_result["model_resolution"] = model_resolution
            if postgres_authority and not args.dry_run:
                project_id = os.environ.get("AISTUDIO_TASK_CONTROL_PROJECT_ID", "").strip()
                if not project_id:
                    raise SystemExit("PostgreSQL task claim requires project identity")
                lease = task_control_database_from_runtime_env().acquire_lease(
                    project_id,
                    str(claim_result["task_id"]),
                    owner_id=f"{args.machine_id}:{args.worker_id}",
                    ttl_seconds=max(1, int(args.ttl_hours)) * 3600,
                    metadata={
                        "session_id": os.environ.get("AISTUDIO_TASK_CONTROL_SESSION_ID"),
                        "branch": claim_result.get("branch"),
                    },
                )
                if not lease.get("acquired"):
                    result = {
                        "claimed": False,
                        "reason": "sql_lease_conflict",
                        "task_id": claim_result["task_id"],
                        "holder": lease.get("holder"),
                        "lease_id": lease.get("lease_id"),
                    }
                    print(json.dumps(result, ensure_ascii=False, indent=2))
                    return 0
                claim_result["sql_lease_id"] = lease["lease_id"]
                for task in queue.get("tasks") or []:
                    if isinstance(task, dict) and task_id(task) == claim_result["task_id"]:
                        task["sql_lease_id"] = lease["lease_id"]
                        break
            result = {
                "claimed": True,
                **claim_result,
                "worktree": str(worktree),
                "requested_task_id": args.task_id,
                "attempt": attempt,
                "retry_count": attempt - 1,
            }
            if args.dry_run:
                print(json.dumps(result, ensure_ascii=False, indent=2))
                return 0

            write_json(queue_path, queue)
            write_json(locks_path, locks)
            if postgres_authority:
                result["authority"] = "postgres"
                result["session_id"] = os.environ.get("AISTUDIO_TASK_CONTROL_SESSION_ID")
                print(json.dumps(result, ensure_ascii=False, indent=2))
                return 0
            run(["git", "add", queue_relpath, locks_relpath], worktree)
            run(["git", "commit", "-m", f"chore(runner): claim {claim_result['task_id']} for {args.worker_id}"], worktree)
            push = run(["git", "push", "origin", f"HEAD:{push_ref}"], worktree, check=False)
            if push.returncode == 0:
                print(json.dumps(result, ensure_ascii=False, indent=2))
                return 0
            last_rejection = {
                "attempt": attempt,
                "stderr": push.stderr,
                "task_id": claim_result["task_id"],
            }
            if not is_push_rejected(push.stderr) or attempt >= attempts:
                result = {
                    "claimed": False,
                    "reason": "push_rejected",
                    "stderr": push.stderr,
                    "attempt": attempt,
                    "retry_count": attempt - 1,
                }
                print(json.dumps(result, ensure_ascii=False, indent=2))
                return 3
            time.sleep(max(0.0, args.push_retry_delay))
        finally:
            if worktree.exists():
                subprocess.run(["git", "worktree", "remove", "--force", str(worktree)], cwd=str(project_root), capture_output=True, text=True)

    result = {"claimed": False, "reason": "push_rejected", "last_rejection": last_rejection}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 3


if __name__ == "__main__":
    raise SystemExit(main())
