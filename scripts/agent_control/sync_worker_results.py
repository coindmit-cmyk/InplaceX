#!/usr/bin/env python3
"""Sync worker branch task status back into the central task queue.

This script does not merge product code. It reads task metadata from worker
branches and copies only queue/lock status evidence into the current checkout.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file, task_relpath


DEFAULT_PREFIXES = (
    "refs/remotes/origin/AiStudio/Agent/worker/",
    "refs/heads/AiStudio/Agent/worker/",
    "refs/remotes/origin/remote/",
    "refs/heads/remote/",
)
TASK_ID_RE = re.compile(r"\b(?:[A-Z][A-Z0-9]{0,12}-[0-9][0-9A-Z]*(?:\.[0-9A-Z]+)*|[A-Z]{1,6}[0-9][A-Z0-9]*(?:\.[0-9A-Z]+)*)\b")
SYNCABLE_STATUSES = {
    "agent_done",
    "done",
    "review",
    "integration_ready",
    "integration_requested",
    "needs_task_packet",
    "needs_architect",
    "needs_human",
    "needs_stronger_agent",
    "needs_dispatcher_repair",
    "needs_worker_fix",
    "blocked",
}
FINAL_STATUSES = {
    "done",
    "integrated",
    "finalized",
    "postponed",
    "failed",
    "stale_or_superseded",
    "duplicate_linked",
    "split_into_children",
}
INTEGRATION_RECORDED_EVENTS = {"integration_recorded", "direct_merge_recorded", "finalization_recorded"}
REVIEW_LOCK_STATUSES = {"agent_done", "review", "integration_ready", "integration_requested"}
RELEASE_LOCK_STATUSES = {
    "needs_task_packet",
    "needs_architect",
    "needs_human",
    "needs_stronger_agent",
    "needs_dispatcher_repair",
    "needs_worker_fix",
    "blocked",
}
ACTIVE_LOCK_STATES = {"locked", "in_progress", "review"}
REVIEW_REACTIVATABLE_LOCK_STATES = ACTIVE_LOCK_STATES | {"released", "agent_done", "integration_ready"}
CLAIM_STATUSES = {"in_progress", "claimed", "running"}
REVIEW_LOCK_REPAIR_REQUEST = "reconcile_review_lock_evidence"
REVIEW_LOCK_REPAIR_FIELDS = (
    "status_reason",
    "blocked_reason",
    "not_worker_ready_reason",
    "repair_request",
    "repair_owner",
    "missing_packet_fields",
    "next_action",
    "review_lock_repair_requested_at",
    "review_lock_repair_requested_by",
)
BLOCKED_DISPATCHER_DECISIONS = {
    "blocked_by_dependency",
    "blocked_by_missing_environment",
    "blocked_by_pr_stack",
    "needs_dispatcher_repair",
}
MODEL_LIMIT_MARKERS = (
    "you've hit your usage limit",
    "you have hit your usage limit",
    "hit your usage limit",
    "usage limit",
    "rate limit",
    "quota exceeded",
    "model currently unavailable",
    "model unavailable",
)
EXPLICIT_CHECK_STATUS_RE = re.compile(
    r"\bcheck_status\b[^\S\r\n]*(?:[`*_]*(?:=|:)[^\S\r\n]*|[`*_]*\r?\n[^\S\r\n]*)"
    r"[`\"'*_]*(passed|partial|not[_ -]?run(?:_or_missing)?|blocked|failed(?:_or_skipped)?)\b",
    re.IGNORECASE,
)
EXPLICIT_RESULT_ROUTE_RE = re.compile(
    r"(?im)^\s*(?:[-*]\s*)?(?:result|route|status)\s*(?:=|:)\s*"
    r"[`\"'*_]*(agent_done|needs_dispatcher_repair|needs_worker_fix|blocked|failed|unknown)\b"
)
STRONG_DISPATCHER_REPAIR_MARKERS = (
    "blocked - needs_dispatcher_repair",
    "blocked -- needs_dispatcher_repair",
    "blocked \u2014 needs_dispatcher_repair",
    "dispatcher should repair the packet",
    "required dispatcher repair",
    "dispatcher should reissue the packet",
    "dispatcher должен переиздать пакет",
)
SCOPE_REPAIR_MARKERS = (
    "outside allowed_paths",
    "outside `allowed_paths`",
    "outside allowed paths",
    "cannot satisfy its acceptance criteria within scope",
)
REQUESTED_PATH_HINT_RE = re.compile(r"`([^`\r\n]+\.(?:py|json|md|yaml|yml|toml))`", re.IGNORECASE)
SAFE_REQUESTED_SCOPE_PREFIXES = (
    "scripts/agent_control/",
    "schemas/agent-control/",
    "templates/agent-control/",
    "tests/",
    "control/tests/",
    "docs/reports/workers/",
)
COPY_FIELDS = (
    "status_reason",
    "worker_id",
    "machine_id",
    "pr",
    "github_pr",
    "pull_request",
    "changed_paths",
    "worker_report",
    "last_agent_report",
    "integration_report",
    "integrator_must_run_checks",
    "migration_sensitive",
    "migration_compatibility_policy",
    "integrator_must_adapt_migrations",
    "worker_check_evidence",
    "failed_worker",
    "escalation_reason",
    "handoff_note",
    "blocked_reason",
    "not_worker_ready_reason",
    "dispatcher_decision_reason",
    "packet_status",
    "normalization_status",
    "repair_request",
    "repair_owner",
    "missing_packet_fields",
    "next_action",
    "requested_allowed_paths",
    "requested_allowed_paths_verified_by",
    "dispatcher_repair_kind",
)
ACTIVE_RESULT_BRANCH_FIELDS = ("branch", "github_branch", "worker_branch", "pr_branch")
RETRY_EVENTS = {
    "worker_finalize_failed_routed",
    "worker_finalize_failed_requeued",
    "worker_launch_failed_requeued",
    "worker_fix_requeued",
    "integration_routed",
}
RETRY_BRANCH_RE = re.compile(r"^(?P<root>.+)-retry-(?P<claimed_at>\d{8}T\d{6}Z)$")
COORDINATION_EXACT_PATHS = {
    "AiStudio/Task_manager/agent_activity_state.json",
    "AiStudio/Task_manager/agent_events.jsonl",
    "AiStudio/Task_manager/agent_locks.json",
    "AiStudio/Task_manager/agent_process_state.json",
    "AiStudio/Task_manager/agent_runner_state.json",
    "AiStudio/Task_manager/integration_candidates.json",
    "AiStudio/Task_manager/integrator_preflight.json",
    "AiStudio/Task_manager/model_budget_state.json",
    "AiStudio/Task_manager/process_locks.json",
    "AiStudio/Task_manager/task_queue.json",
    "docs/plans/agent_activity_state.json",
    "docs/plans/agent_events.jsonl",
    "docs/plans/agent_locks.json",
    "docs/plans/agent_process_state.json",
    "docs/plans/agent_runner_state.json",
    "docs/plans/integration_candidates.json",
    "docs/plans/integrator_preflight.json",
    "docs/plans/model_budget_state.json",
    "docs/plans/process_locks.json",
    "docs/plans/task_queue.json",
    ".agent/mvp_tasks.json",
    ".agent/next_tasks.json",
}
COORDINATION_PREFIXES = (
    "AiStudio/Task_manager/",
    "AiStudio/Task_manager/process-logs/",
    "AiStudio/Task_manager/integration_pr_snapshot_",
    "docs/plans/",
    "docs/plans/process-logs/",
    "docs/plans/integration_pr_snapshot_",
    "old/agent-runs/",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run_git(project_root: Path, args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-c", "core.longpaths=true", "-C", str(project_root), *args],
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def git_stdout(project_root: Path, args: list[str]) -> str:
    return (run_git(project_root, args).stdout or "").strip()


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_branch(ref: str) -> str:
    if ref.startswith("refs/remotes/origin/"):
        return ref.removeprefix("refs/remotes/origin/")
    if ref.startswith("refs/heads/"):
        return ref.removeprefix("refs/heads/")
    if ref.startswith("origin/"):
        return ref.removeprefix("origin/")
    return ref


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def event_target_keys(event: dict[str, Any]) -> set[str]:
    keys: set[str] = set()
    for field in ("task_id", "canonical_target_id", "source_task_id"):
        value = str(event.get(field) or "").strip()
        if not value:
            continue
        keys.add(value)
        if value.startswith("task:"):
            keys.add(value.removeprefix("task:"))
        else:
            keys.add(f"task:{value}")
    return keys


def read_event_records(events_path: Path) -> tuple[set[str], set[str]]:
    integrated: set[str] = set()
    finalized: set[str] = set()
    if not events_path.exists():
        return integrated, finalized
    for line in events_path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        event_name = str(event.get("event") or "")
        keys = event_target_keys(event)
        if event_name == "integration_invalidated":
            integrated.difference_update(keys)
            finalized.difference_update(keys)
        elif event_name == "finalization_invalidated":
            finalized.difference_update(keys)
        elif event_name in {"integration_recorded", "direct_merge_recorded"}:
            integrated.update(keys)
        elif event_name == "finalization_recorded":
            integrated.update(keys)
            finalized.update(keys)
    return integrated, finalized


def is_recorded_key(task_key: str, recorded: set[str]) -> bool:
    if not task_key:
        return False
    return task_key in recorded or f"task:{task_key}" in recorded


def task_list(queue: dict[str, Any]) -> list[dict[str, Any]]:
    tasks = queue.get("tasks")
    return [task for task in tasks if isinstance(task, dict)] if isinstance(tasks, list) else []


def tasks_by_id(queue: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {task_id(task): task for task in task_list(queue) if task_id(task)}


def repair_finalized_worker_evidence(project_root: Path | None, task: dict[str, Any]) -> bool:
    before = json.loads(json.dumps(task))
    exact_review_lock_repair = str(task.get("repair_request") or "") == REVIEW_LOCK_REPAIR_REQUEST
    if (
        not exact_review_lock_repair
        or project_root is None
        or immutable_worker_report_check_status(project_root, task) != "passed"
    ):
        return False
    task["worker_check_evidence"] = {
        "ok": True,
        "route": "agent_done",
        "check_status": "passed",
        "integration_status": "finalized",
        "reason": "immutable worker report explicitly records required checks as passed",
    }
    for field in REVIEW_LOCK_REPAIR_FIELDS:
        task.pop(field, None)
    if str(task.get("packet_status") or "") == "needs_dispatcher_repair":
        task["packet_status"] = "done"
    if str(task.get("normalization_status") or "") == "needs_dispatcher_repair":
        task["normalization_status"] = "done"
    return task != before


def reconcile_finalized_tasks_from_events(
    queue: dict[str, Any],
    finalized: set[str],
    project_root: Path | None = None,
) -> list[dict[str, Any]]:
    changes: list[dict[str, Any]] = []
    now = utc_now()
    for task in task_list(queue):
        current_id = task_id(task)
        if not is_recorded_key(current_id, finalized):
            continue
        before = str(task.get("status") or "")
        before_integration = str(task.get("integration_status") or "")
        evidence_repaired = repair_finalized_worker_evidence(project_root, task)
        if (
            not evidence_repaired
            and before == "done"
            and before_integration == "finalized"
            and task.get("lock") == "free"
            and task.get("worker_ready") is False
        ):
            continue
        task["status"] = "done"
        task["integration_status"] = "finalized"
        task["finalization_status"] = "recorded"
        task["lock"] = "free"
        task["worker_ready"] = False
        task["dispatcher_decision"] = "done"
        task.setdefault("finalized_by", "auto-integrator")
        task.setdefault("finalized_at", now)
        history = task.get("status_history")
        if not isinstance(history, list):
            history = []
        history.append(
            {
                "at": now,
                "by": "sync_worker_results",
                "from": before,
                "to": "done",
                "reason": "event log contains finalization_recorded; preserving integrated task state",
                "event": "finalization_recorded",
                "next_owner": "none",
            }
        )
        task["status_history"] = history
        changes.append(
            {
                "task_id": current_id,
                "from": before,
                "to": "done",
                "source_status": "finalization_recorded",
                "integration_status_from": before_integration,
                "integration_status_to": "finalized",
                "source": "event_log_reconciliation",
            }
        )
    return changes


def infer_task_ids_from_branch(branch: str, known_task_ids: set[str]) -> set[str]:
    found = set(TASK_ID_RE.findall(branch.upper()))
    known = {task_id.upper() for task_id in known_task_ids}
    expanded = set(found)
    for item in found:
        expanded.add(f"CRB-{item}")
    return expanded & known


def task_branch_matches(task: dict[str, Any], branch: str) -> bool:
    normalized = normalize_branch(branch)
    for field in ("branch", "github_branch", "worker_branch", "pr_branch", "synced_from_worker_branch"):
        value = task.get(field)
        if isinstance(value, str) and normalize_branch(value) == normalized:
            return True
    return False


def active_result_branches(task: dict[str, Any]) -> list[str]:
    result: list[str] = []
    for field in ACTIVE_RESULT_BRANCH_FIELDS:
        value = task.get(field)
        if not isinstance(value, str) or not value.strip():
            continue
        normalized = normalize_branch(value.strip())
        if normalized not in result:
            result.append(normalized)
    return result


def retry_marker_at(task: dict[str, Any]) -> datetime | None:
    values: list[str] = []
    for collection_name in ("status_history", "integration_repair_retry_evidence", "worker_ready_promotion_history"):
        collection = task.get(collection_name)
        if not isinstance(collection, list):
            continue
        for item in collection:
            if not isinstance(item, dict):
                continue
            event = str(item.get("event") or "")
            if event == "integration_routed" and str(item.get("next_owner") or "").lower() not in {"worker", "worker_pool"}:
                continue
            previous_status = str(item.get("previous_integration_status") or "")
            if event not in RETRY_EVENTS and previous_status != "blocked_model_limit":
                continue
            value = str(item.get("at") or item.get("promoted_at") or "").strip()
            if value:
                values.append(value)
    promoted_at = str(task.get("model_limit_retry_promoted_at") or "").strip()
    if promoted_at:
        values.append(promoted_at)

    parsed: list[datetime] = []
    for value in values:
        try:
            item = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            continue
        if item.tzinfo is None:
            item = item.replace(tzinfo=timezone.utc)
        parsed.append(item)
    return max(parsed) if parsed else None


def parse_timestamp(value: Any) -> datetime | None:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=timezone.utc)


def dispatcher_scope_repair_supersedes_result(task: dict[str, Any], commit: str) -> bool:
    repair = task.get("dispatcher_scope_repair")
    if not isinstance(repair, dict):
        return False
    if str(task.get("worker_result_commit") or "") != commit:
        return False
    if str(repair.get("applied_by") or "") != "scripts/agent_control/dispatcher_packet_repair.py":
        return False
    if str(repair.get("source_verifier") or "") != "scripts/agent_control/sync_worker_results.py":
        return False
    if not isinstance(repair.get("requested_allowed_paths"), list) or not repair["requested_allowed_paths"]:
        return False
    applied_at = parse_timestamp(repair.get("applied_at"))
    synced_at = parse_timestamp(task.get("worker_result_synced_at"))
    return applied_at is not None and synced_at is not None and applied_at >= synced_at


def retry_result_branch_matches(task: dict[str, Any], branch: str) -> bool:
    retry_match = RETRY_BRANCH_RE.fullmatch(normalize_branch(branch))
    marker_at = retry_marker_at(task)
    if not retry_match or marker_at is None:
        return False

    roots = {
        match.group("root") if (match := RETRY_BRANCH_RE.fullmatch(value)) else value
        for value in active_result_branches(task)
    }
    if retry_match.group("root") not in roots:
        return False
    try:
        claimed_at = datetime.strptime(retry_match.group("claimed_at"), "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        return False
    return claimed_at >= marker_at


def expected_result_branch_matches(task: dict[str, Any], branch: str) -> bool:
    normalized = normalize_branch(branch)
    active = active_result_branches(task)
    if active:
        return normalized in active or retry_result_branch_matches(task, normalized)
    historical = task.get("synced_from_worker_branch")
    return isinstance(historical, str) and normalize_branch(historical) == normalized


def abandoned_result_branch_matches(task: dict[str, Any], branch: str) -> bool:
    if (
        str(task.get("status") or "") != "planned"
        or task.get("worker_ready") is not True
        or not str(task.get("worker_result_commit") or "").strip()
        or not str(task.get("worker_report") or "").strip()
    ):
        return False
    claims = task.get("abandoned_claims")
    if not isinstance(claims, list):
        return False
    normalized = normalize_branch(branch)
    for claim in reversed(claims):
        if not isinstance(claim, dict):
            continue
        claim_branch = normalize_branch(str(claim.get("branch") or ""))
        reason = str(claim.get("reason") or "").lower()
        return (
            claim_branch == normalized
            and "worker pool finished without a live worker process or worker result" in reason
        )
    return False


def has_expected_result_branch(task: dict[str, Any]) -> bool:
    return bool(active_result_branches(task) or str(task.get("synced_from_worker_branch") or "").strip())


def is_clean_rebuild_source_branch(task: dict[str, Any], branch: str) -> bool:
    normalized = normalize_branch(branch)
    for field in ("source_branch", "clean_rebuild_source_branch"):
        value = task.get(field)
        if isinstance(value, str) and normalize_branch(value) == normalized:
            return True
    return False


def list_worker_refs(project_root: Path, prefixes: list[str]) -> list[str]:
    refs = git_stdout(project_root, ["for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes/origin"])
    result: list[str] = []
    seen: set[str] = set()
    for ref in refs.splitlines():
        if not any(ref.startswith(prefix) for prefix in prefixes):
            continue
        if ref not in seen:
            result.append(ref)
            seen.add(ref)
    return sorted(result)


def resolve_branch_prefixes(values: list[str] | None) -> list[str]:
    return list(values) if values else list(DEFAULT_PREFIXES)


def read_branch_queue(project_root: Path, ref: str, queue_relpath: str) -> dict[str, Any] | None:
    candidates = [queue_relpath]
    if queue_relpath == "AiStudio/Task_manager/task_queue.json":
        candidates.append("docs/plans/task_queue.json")
    elif queue_relpath == "docs/plans/task_queue.json":
        candidates.append("AiStudio/Task_manager/task_queue.json")
    for relpath in candidates:
        proc = run_git(project_root, ["show", f"{ref}:{relpath}"], check=False)
        stdout = proc.stdout or ""
        if proc.returncode != 0 or not stdout.strip():
            continue
        try:
            data = json.loads(stdout)
        except json.JSONDecodeError:
            continue
        if isinstance(data, dict):
            return data
    return None


def head_sha(project_root: Path, ref: str) -> str:
    return git_stdout(project_root, ["rev-parse", ref])


def commit_time(project_root: Path, ref: str) -> int:
    value = git_stdout(project_root, ["show", "-s", "--format=%ct", ref])
    try:
        return int(value)
    except ValueError:
        return 0


def changed_paths(project_root: Path, base_ref: str | None, head_ref: str) -> list[str]:
    if not base_ref:
        return []
    merge_base = run_git(project_root, ["merge-base", base_ref, head_ref], check=False)
    if merge_base.returncode != 0:
        return []
    merge_base_sha = (merge_base.stdout or "").strip()
    if not merge_base_sha:
        return []
    diff = run_git(project_root, ["diff", "--name-only", merge_base_sha, head_ref], check=False)
    if diff.returncode != 0:
        return []
    return sorted(path for path in (diff.stdout or "").splitlines() if path)


def is_worker_report_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    if not normalized.startswith(("docs/reports/", "docs/plans/reports/", "AiStudio/Task_manager/reports/")):
        return False
    name = Path(normalized).name.upper()
    return name.startswith("WORKER_RESULT_") or Path(name).stem.endswith("_WORKER_REPORT")


def safe_report_paths(paths: list[str], current_task_id: str) -> list[str]:
    normalized_task = current_task_id.upper()
    result: list[str] = []
    for path in paths:
        normalized = path.replace("\\", "/")
        if not is_worker_report_path(normalized):
            continue
        if normalized_task not in normalized.upper():
            continue
        result.append(normalized)
    return sorted(result)


def normalize_imported_report(target: Path, text: str | None = None) -> None:
    if target.suffix.lower() not in {".json", ".jsonl", ".md", ".txt"}:
        if text is not None:
            target.write_text(text, encoding="utf-8")
        return
    exists = target.exists()
    source = target.read_text(encoding="utf-8") if text is None else text
    normalized = source.rstrip() + "\n"
    if not exists or source != normalized:
        target.write_text(normalized, encoding="utf-8")


def import_worker_reports(project_root: Path, ref: str, report_paths: list[str]) -> list[str]:
    imported: list[str] = []
    for report_path in report_paths:
        target = project_root / report_path
        if target.exists():
            normalize_imported_report(target)
            imported.append(report_path)
            continue
        blob = run_git(project_root, ["show", f"{ref}:{report_path}"], check=False)
        if blob.returncode != 0:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        normalize_imported_report(target, blob.stdout or "")
        imported.append(report_path)
    return imported


def normalize_canonical_worker_reports(
    project_root: Path,
    queue: dict[str, Any],
    *,
    apply: bool,
) -> list[str]:
    candidates: set[str] = set()
    for task in queue.get("tasks", []):
        if not isinstance(task, dict):
            continue
        values = [task.get("worker_report")]
        imported = task.get("imported_worker_reports")
        if isinstance(imported, list):
            values.extend(imported)
        for value in values:
            path = str(value or "").strip().replace("\\", "/")
            if is_worker_report_path(path):
                candidates.add(path)

    root = project_root.resolve()
    repaired: list[str] = []
    for report_path in sorted(candidates):
        target = (project_root / report_path).resolve()
        if root not in target.parents or not target.is_file():
            continue
        if target.suffix.lower() not in {".json", ".jsonl", ".md", ".txt"}:
            continue
        source = target.read_text(encoding="utf-8")
        normalized = source.rstrip() + "\n"
        if source == normalized:
            continue
        repaired.append(report_path)
        if apply:
            target.write_text(normalized, encoding="utf-8")
    return repaired


def cleanup_misclassified_imported_reports(
    project_root: Path,
    queue: dict[str, Any],
    *,
    apply: bool,
) -> dict[str, Any]:
    root = project_root.resolve()
    candidates: list[dict[str, str]] = []
    queue_changed = 0
    for task in queue.get("tasks", []) if isinstance(queue.get("tasks"), list) else []:
        if not isinstance(task, dict):
            continue
        imported = [
            str(value).strip().replace("\\", "/")
            for value in task.get("imported_worker_reports") or []
            if str(value or "").strip()
        ]
        if not imported:
            continue
        changed = {
            str(value).strip().replace("\\", "/")
            for value in [*(task.get("worker_changed_paths") or []), *(task.get("changed_paths") or [])]
            if str(value or "").strip()
        }
        source_sha = str(task.get("worker_result_commit") or "").strip().lower()
        if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
            continue
        canonical = safe_report_paths(
            [*imported, *changed, str(task.get("worker_report") or "")],
            task_id(task),
        )
        removable: list[str] = []
        for report_path in imported:
            if report_path in canonical or report_path not in changed:
                continue
            if not report_path.startswith(("docs/reports/", "docs/plans/reports/", "AiStudio/Task_manager/reports/")):
                continue
            target = (project_root / report_path).resolve()
            if root not in target.parents or not target.is_file():
                continue
            tracked = run_git(project_root, ["ls-files", "--error-unmatch", "--", report_path], check=False)
            if tracked.returncode == 0:
                continue
            blob = run_git(project_root, ["show", f"{source_sha}:{report_path}"], check=False)
            if blob.returncode != 0:
                continue
            local_text = target.read_text(encoding="utf-8")
            source_text = blob.stdout or ""
            if local_text.rstrip() != source_text.rstrip():
                continue
            removable.append(report_path)
            candidates.append({
                "task_id": task_id(task),
                "path": report_path,
                "worker_result_commit": source_sha,
            })
        if not apply or not removable:
            continue
        for report_path in removable:
            (project_root / report_path).unlink()
        remaining = [path for path in imported if path not in removable]
        if remaining:
            task["imported_worker_reports"] = remaining
        else:
            task.pop("imported_worker_reports", None)
        current_report = str(task.get("worker_report") or "").strip().replace("\\", "/")
        if current_report in removable and canonical:
            task["worker_report"] = canonical[-1]
        queue_changed += 1
    return {
        "candidate_count": len(candidates),
        "cleaned_count": len(candidates) if apply else 0,
        "queue_changed_count": queue_changed,
        "candidates": candidates,
    }


def immutable_report_text(project_root: Path, ref: str, report_path: str) -> str:
    blob = run_git(project_root, ["show", f"{ref}:{report_path}"], check=False)
    if blob.returncode == 0:
        return blob.stdout or ""
    return ""


def report_stdout_section(text: str) -> str:
    match = re.search(r"(?ms)^## stdout\s*\n+```text\s*\n(.*?)\n```(?:\n|\Z)", text)
    return match.group(1) if match else ""


def report_control_text(text: str) -> str:
    """Return report-owned status text without embedded execution transcripts."""
    section_match = re.search(r"(?m)^## (?:stdout|stderr)\s*$", text)
    if section_match is None:
        return text
    header = text[: section_match.start()]
    stdout = report_stdout_section(text)
    return f"{header}\n{stdout}" if stdout else header


def requested_scope_path_hints(text: str) -> list[str]:
    hints: list[str] = []
    for match in REQUESTED_PATH_HINT_RE.finditer(text):
        value = match.group(1).strip().replace("\\", "/")
        if value and value not in hints:
            hints.append(value)
    return hints


def resolve_requested_allowed_paths(project_root: Path, ref: str, hints: list[str]) -> list[str]:
    tree = run_git(project_root, ["ls-tree", "-r", "--name-only", ref], check=False)
    if tree.returncode != 0:
        return []
    tracked = [
        path.strip().replace("\\", "/")
        for path in (tree.stdout or "").splitlines()
        if path.strip().replace("\\", "/").startswith(SAFE_REQUESTED_SCOPE_PREFIXES)
    ]
    resolved: list[str] = []
    for raw_hint in hints:
        hint = raw_hint.strip().replace("\\", "/")
        if not hint or hint.startswith("/") or ".." in Path(hint).parts:
            continue
        matches = [path for path in tracked if path == hint] if "/" in hint else [
            path for path in tracked if Path(path).name == hint
        ]
        if (
            not matches
            and "/" in hint
            and hint.startswith(SAFE_REQUESTED_SCOPE_PREFIXES)
            and Path(hint).suffix.lower() in {".py", ".json", ".md", ".yaml", ".yml", ".toml"}
            and any(path.startswith(f"{Path(hint).parent.as_posix()}/") for path in tracked)
        ):
            matches = [hint]
        if len(matches) == 1 and matches[0] not in resolved:
            resolved.append(matches[0])
    return resolved


def report_dispatcher_scope_evidence(
    project_root: Path,
    ref: str,
    report_paths: list[str],
) -> dict[str, Any]:
    for report_path in reversed(report_paths):
        text = immutable_report_text(project_root, ref, report_path)
        stdout = report_stdout_section(text)
        lowered = f"{stdout}\n{text}".lower()
        normalized = re.sub(r"\s+", " ", lowered)
        if (
            not any(re.sub(r"\s+", " ", marker.lower()) in normalized for marker in STRONG_DISPATCHER_REPAIR_MARKERS)
            or not any(re.sub(r"\s+", " ", marker.lower()) in normalized for marker in SCOPE_REPAIR_MARKERS)
        ):
            continue
        requested_paths = resolve_requested_allowed_paths(
            project_root,
            ref,
            requested_scope_path_hints(f"{stdout}\n{text}"),
        )
        return {
            "repair_kind": "allowed_paths",
            "repair_fields": ["allowed_paths"],
            "requested_allowed_paths": requested_paths,
            "requested_allowed_paths_verified_by": "scripts/agent_control/sync_worker_results.py",
        }
    return {}


def report_has_model_limit(project_root: Path, ref: str, report_paths: list[str]) -> bool:
    for report_path in report_paths:
        text = ""
        local_path = project_root / report_path
        if local_path.exists():
            text = local_path.read_text(encoding="utf-8", errors="ignore")
        else:
            blob = run_git(project_root, ["show", f"{ref}:{report_path}"], check=False)
            if blob.returncode == 0:
                text = blob.stdout or ""
        lowered = text.lower()
        if any(marker in lowered for marker in MODEL_LIMIT_MARKERS):
            return True
    return False


def report_has_task_packet_blocker(project_root: Path, ref: str, report_paths: list[str]) -> bool:
    for report_path in report_paths:
        text = ""
        local_path = project_root / report_path
        if local_path.exists():
            text = local_path.read_text(encoding="utf-8", errors="ignore")
        else:
            blob = run_git(project_root, ["show", f"{ref}:{report_path}"], check=False)
            if blob.returncode == 0:
                text = blob.stdout or ""
        lowered = text.lower()
        if "needs_task_packet" in lowered and ("check_status=blocked" in lowered or '"check_status": "blocked"' in lowered):
            return True
    return False


def report_explicit_check_status(
    project_root: Path,
    ref: str,
    report_paths: list[str],
    *,
    allow_local_fallback: bool = True,
) -> str | None:
    latest_status = None
    for report_path in report_paths:
        blob = run_git(project_root, ["show", f"{ref}:{report_path}"], check=False)
        if blob.returncode == 0:
            text = blob.stdout or ""
        else:
            if not allow_local_fallback:
                continue
            local_path = project_root / report_path
            text = local_path.read_text(encoding="utf-8", errors="ignore") if local_path.exists() else ""
        statuses = [
            match.group(1).lower().replace(" ", "_").replace("-", "_")
            for match in EXPLICIT_CHECK_STATUS_RE.finditer(report_control_text(text))
        ]
        if statuses:
            latest_status = statuses[-1]
    return latest_status


def report_explicit_result_route(project_root: Path, ref: str, report_paths: list[str]) -> str | None:
    latest_route = None
    for report_path in report_paths:
        text = immutable_report_text(project_root, ref, report_path)
        if not text:
            continue
        stdout = report_stdout_section(text)
        lowered_stdout = stdout.lower()
        if (
            "needs_dispatcher_repair" in lowered_stdout
            and any(marker in lowered_stdout for marker in STRONG_DISPATCHER_REPAIR_MARKERS)
        ):
            latest_route = "needs_dispatcher_repair"
            continue
        routes = [
            match.group(1).lower()
            for match in EXPLICIT_RESULT_ROUTE_RE.finditer(report_control_text(text))
        ]
        if routes:
            latest_route = routes[-1]
    return latest_route


def passed_check_source(source: dict[str, Any]) -> dict[str, Any]:
    updated = dict(source)
    updated["worker_check_evidence"] = {
        "ok": True,
        "route": "agent_done",
        "check_status": "passed",
        "integration_status": "pending",
        "reason": "immutable worker report explicitly records required checks as passed",
    }
    if str(updated.get("status") or "") in REVIEW_LOCK_STATUSES:
        updated["integration_status"] = "pending"
    return updated


def needs_worker_fix_source(source: dict[str, Any], check_status: str) -> dict[str, Any]:
    updated = dict(source)
    reason = f"worker explicitly reported required checks as {check_status}"
    updated["status"] = "needs_worker_fix"
    updated["integration_status"] = "needs_worker_fix"
    updated["worker_ready"] = False
    updated["dispatcher_decision"] = "needs_worker_fix"
    updated["next_owner"] = "Dispatcher"
    updated["next_role"] = "auto_dispatcher"
    updated["status_reason"] = reason
    updated["not_worker_ready_reason"] = reason
    updated["worker_check_evidence"] = {
        "ok": False,
        "route": "needs_worker_fix",
        "check_status": check_status,
        "integration_status": "needs_worker_fix",
        "reason": reason,
    }
    return updated


def source_has_model_limit(project_root: Path, source: dict[str, Any], branch_ref: str, report_paths: list[str]) -> bool:
    evidence = source.get("worker_check_evidence")
    if isinstance(evidence, dict):
        if evidence.get("ok") is True and str(evidence.get("check_status") or "") == "passed":
            return False
        if str(evidence.get("check_status") or "") == "model_limit":
            return True
        for key in ("reason", "stderr", "stdout"):
            lowered = str(evidence.get(key) or "").lower()
            if any(marker in lowered for marker in MODEL_LIMIT_MARKERS):
                return True
    return report_has_model_limit(project_root, branch_ref, report_paths)


def model_limit_source(source: dict[str, Any]) -> dict[str, Any]:
    updated = dict(source)
    updated["status"] = "blocked"
    updated["integration_status"] = "blocked_model_limit"
    updated["status_reason"] = "worker could not start because the requested model was unavailable or usage-limited"
    updated["blocked_reason"] = updated["status_reason"]
    updated["worker_ready"] = False
    updated["dispatcher_decision"] = "blocked_by_missing_environment"
    updated["next_owner"] = "Dispatcher"
    updated["next_action"] = "Retry when the requested worker model or model budget is available."
    updated["worker_check_evidence"] = {
        "ok": False,
        "route": "blocked",
        "check_status": "model_limit",
        "integration_status": "blocked_model_limit",
        "reason": updated["status_reason"],
    }
    return updated


def is_dispatcher_repair_source(source: dict[str, Any]) -> bool:
    if str(source.get("status") or "") != "blocked":
        return False
    repair_markers = {
        str(source.get("dispatcher_decision") or ""),
        str(source.get("packet_status") or ""),
        str(source.get("normalization_status") or ""),
        str(source.get("integration_status") or ""),
    }
    if "needs_dispatcher_repair" in repair_markers:
        return True
    blocked_by = source.get("blocked_by")
    if isinstance(blocked_by, list):
        return any(str(item or "").strip() == "missing_target_checkout_for_task_paths" for item in blocked_by)
    return False


def dispatcher_repair_source(source: dict[str, Any]) -> dict[str, Any]:
    updated = dict(source)
    updated["status"] = "blocked"
    updated["worker_ready"] = False
    updated["dispatcher_decision"] = "needs_dispatcher_repair"
    updated["packet_status"] = "needs_dispatcher_repair"
    updated["normalization_status"] = "needs_dispatcher_repair"
    updated["integration_status"] = "needs_dispatcher_repair"
    updated["next_owner"] = "Dispatcher"
    updated["status_reason"] = str(
        source.get("dispatcher_decision_reason") or source.get("blocked_reason") or "worker result requires Dispatcher packet repair"
    )
    updated["not_worker_ready_reason"] = updated["status_reason"]
    updated["repair_request"] = "Route this task to the correct target checkout or rebuild the Worker Packet v2 for the current project workspace."
    updated["missing_packet_fields"] = ["target_checkout"]
    updated["repair_owner"] = "dispatcher"
    updated["next_action"] = "Dispatcher must repair project routing before the task can be claimed by a worker."
    updated["worker_check_evidence"] = {
        "ok": False,
        "route": "blocked",
        "check_status": "blocked",
        "integration_status": "needs_dispatcher_repair",
        "reason": updated["status_reason"],
    }
    return updated


def requested_dispatcher_repair_source(source: dict[str, Any]) -> dict[str, Any]:
    updated = dict(source)
    updated["status"] = "needs_dispatcher_repair"
    updated["worker_ready"] = False
    updated["dispatcher_decision"] = "needs_dispatcher_repair"
    updated["packet_status"] = "needs_dispatcher_repair"
    updated["normalization_status"] = "needs_dispatcher_repair"
    updated["integration_status"] = "needs_dispatcher_repair"
    updated["next_owner"] = "Dispatcher"
    updated["repair_request"] = str(updated.get("repair_request") or "rebuild_worker_packet_v2")
    missing_fields = updated.get("missing_packet_fields")
    if not isinstance(missing_fields, list) or not missing_fields:
        updated["missing_packet_fields"] = ["worker_packet_v2"]
    updated["repair_owner"] = str(updated.get("repair_owner") or "dispatcher")
    updated["next_action"] = str(
        updated.get("next_action") or "Dispatcher must rebuild the Worker Packet v2 before this task can return to worker execution."
    )
    return updated


def promoted_blocked_integration_status(source: dict[str, Any]) -> str:
    evidence = source.get("worker_check_evidence")
    evidence_status = str(evidence.get("integration_status") or "") if isinstance(evidence, dict) else ""
    if str(source.get("integration_status") or "") == "pending_checks" or evidence_status == "pending_checks":
        return "pending_checks"
    if isinstance(evidence, dict) and evidence.get("ok") is True and str(evidence.get("check_status") or "") == "passed":
        return "pending"
    return ""


def should_promote_blocked_pending_checks(source: dict[str, Any], paths: list[str]) -> bool:
    if str(source.get("status") or "") != "blocked":
        return False
    if not integration_paths(paths):
        return False
    integration_status = promoted_blocked_integration_status(source)
    if not integration_status:
        return False
    evidence = source.get("worker_check_evidence")
    if isinstance(evidence, dict) and (str(evidence.get("integration_status") or "") == "pending_checks" or evidence.get("ok") is True):
        return True
    return bool(source.get("worker_report"))


def promote_blocked_pending_checks_source(source: dict[str, Any]) -> dict[str, Any]:
    updated = dict(source)
    integration_status = promoted_blocked_integration_status(source) or "pending_checks"
    updated["status"] = "agent_done"
    updated["integration_status"] = integration_status
    updated["status_reason"] = str(source.get("status_reason") or source.get("blocked_reason") or "worker completed implementation but Integrator must run pending checks")
    if integration_status == "pending_checks":
        updated["integrator_must_run_checks"] = True
    else:
        updated.pop("integrator_must_run_checks", None)
    return updated


def needs_sync_metadata_repair(target: dict[str, Any], synced_status: str, report_paths: list[str] | None = None) -> bool:
    report_paths = report_paths or []
    missing_worker_report = bool(report_paths) and not target.get("worker_report")
    missing_imported_report = bool(report_paths) and not set(report_paths).issubset(set(target.get("imported_worker_reports") or []))
    if synced_status in REVIEW_LOCK_STATUSES:
        return (
            target.get("lock") != "review"
            or target.get("worker_ready") is True
            or str(target.get("next_owner") or "") not in {"Integrator", "integrator"}
            or missing_worker_report
            or missing_imported_report
        )
    if synced_status == "needs_human":
        return str(target.get("next_owner") or "") not in {"human", "Human"}
    if synced_status == "needs_dispatcher_repair":
        return (
            target.get("worker_ready") is True
            or str(target.get("next_owner") or "") not in {"Dispatcher", "dispatcher"}
            or missing_worker_report
            or missing_imported_report
        )
    if synced_status in {"needs_task_packet", "needs_architect", "needs_stronger_agent", "blocked"}:
        if str(target.get("integration_status") or "") == "blocked_model_limit":
            return str(target.get("next_owner") or "") not in {"Dispatcher", "dispatcher"}
        return target.get("worker_ready") is True or str(target.get("next_owner") or "") in {"worker_pool", "worker"}
    return False


def repair_contract_matches(target: dict[str, Any], source: dict[str, Any]) -> bool:
    fields = (
        "packet_status",
        "normalization_status",
        "repair_request",
        "repair_owner",
        "missing_packet_fields",
        "next_action",
        "not_worker_ready_reason",
    )
    return all(source.get(field) in (None, "", []) or target.get(field) == source.get(field) for field in fields)


def is_coordination_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    if normalized.startswith("docs/plans/contracts/"):
        return False
    return normalized in COORDINATION_EXACT_PATHS or any(normalized.startswith(prefix) for prefix in COORDINATION_PREFIXES)


def integration_paths(paths: list[str]) -> list[str]:
    return sorted(path for path in paths if not is_coordination_path(path))


def worker_id_from_branch(branch: str) -> str:
    parts = normalize_branch(branch).split("/")
    for part in parts:
        if part.startswith("auto-worker-"):
            return part
    return ""


def pr_for_branch(project_root: Path, branch: str) -> dict[str, Any]:
    try:
        proc = subprocess.run(
            [
                "gh",
                "pr",
                "list",
                "--head",
                branch,
                "--state",
                "open",
                "--json",
                "number,url,state,isDraft",
                "--limit",
                "1",
            ],
            cwd=str(project_root),
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except (FileNotFoundError, OSError):
        return {}
    if proc.returncode != 0 or not (proc.stdout or "").strip():
        return {}
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {}
    if not isinstance(data, list) or not data or not isinstance(data[0], dict):
        return {}
    return data[0]


def supplemental_check_evidence_paths(
    project_root: Path,
    target: dict[str, Any],
    branch: str,
    branch_ref: str,
    expected_head_commit: str | None = None,
) -> list[str]:
    """Accept append-only check evidence for an already-synced Worker result."""
    if (
        str(target.get("status") or "") != "planned"
        or target.get("worker_ready") is not True
        or str(target.get("lock") or "") != "free"
        or str(target.get("integration_status") or "") != "needs_worker_fix"
        or str(target.get("repaired_packet_by") or "")
        != "scripts/agent_control/dispatcher_packet_repair.py"
        or not str(target.get("repaired_packet_at") or "").strip()
        or not expected_result_branch_matches(target, branch)
    ):
        return []

    evidence = target.get("worker_check_evidence")
    if (
        not isinstance(evidence, dict)
        or str(evidence.get("check_status") or "") == "passed"
        or str(evidence.get("integration_status") or "") != "needs_worker_fix"
    ):
        return []

    previous_commit = str(target.get("worker_result_commit") or "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", previous_commit):
        return []
    current_commit = head_sha(project_root, branch_ref).lower()
    if expected_head_commit and current_commit != expected_head_commit.lower():
        return []
    if current_commit == previous_commit:
        return []
    ancestry = run_git(
        project_root,
        ["merge-base", "--is-ancestor", previous_commit, current_commit],
        check=False,
    )
    if ancestry.returncode != 0:
        return []

    diff = run_git(
        project_root,
        ["diff", "--name-status", "--no-renames", previous_commit, current_commit],
        check=False,
    )
    if diff.returncode != 0:
        return []
    added_paths: list[str] = []
    for line in (diff.stdout or "").splitlines():
        status, separator, path = line.partition("\t")
        normalized = path.strip().replace("\\", "/")
        if separator != "\t" or status != "A" or not normalized:
            return []
        added_paths.append(normalized)
    report_paths = safe_report_paths(added_paths, task_id(target))
    if not added_paths or report_paths != sorted(added_paths):
        return []
    if (
        report_explicit_check_status(
            project_root,
            branch_ref,
            report_paths,
            allow_local_fallback=False,
        )
        != "passed"
    ):
        return []
    if report_explicit_result_route(project_root, branch_ref, report_paths) in {
        "needs_dispatcher_repair",
        "needs_worker_fix",
        "blocked",
        "failed",
        "unknown",
    }:
        return []
    return report_paths


def is_verified_same_commit_dispatcher_replay(
    target: dict[str, Any],
    *,
    branch: str,
    commit: str,
    result_route: str | None,
    check_status: str | None,
    report_paths: list[str],
    paths: list[str],
) -> bool:
    return (
        str(target.get("status") or "") == "planned"
        and target.get("worker_ready") is True
        and str(target.get("integration_status") or "") == "needs_worker_fix"
        and str(target.get("repaired_packet_by") or "")
        == "scripts/agent_control/dispatcher_packet_repair.py"
        and bool(str(target.get("repaired_packet_at") or "").strip())
        and expected_result_branch_matches(target, branch)
        and str(target.get("worker_result_commit") or "") == commit
        and result_route == "agent_done"
        and check_status == "passed"
        and bool(report_paths)
        and bool(integration_paths(paths))
    )


def source_from_claimed_branch(
    project_root: Path,
    target: dict[str, Any],
    branch: str,
    paths: list[str],
    *,
    branch_ref: str | None = None,
    active_lease_match: bool = False,
) -> dict[str, Any] | None:
    status = str(target.get("status") or "")
    report_paths = safe_report_paths(paths, task_id(target))
    evidence_ref = branch_ref or branch
    supplemental_evidence = supplemental_check_evidence_paths(
        project_root,
        target,
        branch,
        evidence_ref,
    )
    verification_report_paths = supplemental_evidence or report_paths
    result_route = report_explicit_result_route(
        project_root,
        evidence_ref,
        verification_report_paths,
    )
    scope_evidence = report_dispatcher_scope_evidence(
        project_root,
        evidence_ref,
        verification_report_paths,
    )
    if (
        result_route == "needs_worker_fix"
        and scope_evidence.get("repair_fields") == ["allowed_paths"]
        and scope_evidence.get("requested_allowed_paths")
    ):
        result_route = "needs_dispatcher_repair"
    retry_match = retry_result_branch_matches(target, branch)
    retry_resync = status in REVIEW_LOCK_STATUSES and retry_match
    verified_same_commit_replay = (
        status == "planned"
        and target.get("worker_ready") is True
        and str(target.get("integration_status") or "") == "needs_worker_fix"
        and is_verified_same_commit_dispatcher_replay(
            target,
            branch=branch,
            commit=head_sha(project_root, evidence_ref),
            result_route=result_route,
            check_status=report_explicit_check_status(
                project_root,
                evidence_ref,
                verification_report_paths,
                allow_local_fallback=False,
            ),
            report_paths=report_paths,
            paths=paths,
        )
    )
    planned_retry_recovery = (
        status == "planned"
        and target.get("worker_ready") is True
        and (retry_match or bool(supplemental_evidence) or verified_same_commit_replay)
        and bool(report_paths)
        and bool(integration_paths(paths))
    )
    lease_result_recovery = active_lease_match and bool(report_paths)
    recorded_result_matches = (
        result_route == "needs_dispatcher_repair"
        and bool(report_paths)
        and (
            expected_result_branch_matches(target, branch)
            or abandoned_result_branch_matches(target, branch)
        )
        and str(target.get("worker_result_commit") or "") == head_sha(project_root, evidence_ref)
    )
    immutable_dispatcher_reclassification = (
        (
            status == "needs_worker_fix"
            or (status == "planned" and target.get("worker_ready") is True)
        )
        and result_route == "needs_dispatcher_repair"
        and bool(report_paths)
        and recorded_result_matches
    )
    if (
        status not in CLAIM_STATUSES
        and not retry_resync
        and not planned_retry_recovery
        and not lease_result_recovery
        and not immutable_dispatcher_reclassification
    ):
        return None
    payload_paths = integration_paths(paths)
    if not payload_paths and result_route != "needs_dispatcher_repair":
        return None
    if result_route == "needs_dispatcher_repair":
        report_path = verification_report_paths[-1]
        reason = "worker explicitly reported that the claimed packet is stale or incomplete"
        missing_fields = scope_evidence.get("repair_fields") or ["current_context_or_source_freshness"]
        worker_evidence = {
            "ok": False,
            "route": "needs_dispatcher_repair",
            "check_status": report_explicit_check_status(
                project_root,
                evidence_ref,
                verification_report_paths,
            )
            or "blocked",
            "integration_status": "needs_dispatcher_repair",
            "reason": reason,
        }
        worker_evidence.update(scope_evidence)
        source = {
            "id": task_id(target),
            "task_id": task_id(target),
            "status": "needs_dispatcher_repair",
            "status_reason": reason,
            "not_worker_ready_reason": reason,
            "dispatcher_decision": "needs_dispatcher_repair",
            "packet_status": "needs_dispatcher_repair",
            "normalization_status": "needs_dispatcher_repair",
            "integration_status": "needs_dispatcher_repair",
            "repair_request": "rebuild_worker_packet_v2",
            "repair_owner": "dispatcher",
            "missing_packet_fields": missing_fields,
            "next_owner": "Dispatcher",
            "next_role": "auto_dispatcher",
            "branch": branch,
            "github_branch": branch,
            "worker_id": target.get("worker_id") or worker_id_from_branch(branch),
            "machine_id": target.get("machine_id"),
            "changed_paths": payload_paths,
            "worker_report": report_path,
            "worker_check_evidence": worker_evidence,
        }
        if scope_evidence.get("requested_allowed_paths"):
            source["requested_allowed_paths"] = scope_evidence["requested_allowed_paths"]
            source["requested_allowed_paths_verified_by"] = scope_evidence[
                "requested_allowed_paths_verified_by"
            ]
            source["dispatcher_repair_kind"] = scope_evidence["repair_kind"]
        return source
    if report_has_task_packet_blocker(
        project_root,
        evidence_ref,
        verification_report_paths,
    ):
        return {
            "id": task_id(target),
            "task_id": task_id(target),
            "status": "needs_task_packet",
            "status_reason": "worker blocked because the assigned task packet was missing or stale",
            "blocked_reason": "needs_task_packet",
            "not_worker_ready_reason": "worker blocked because the assigned task packet was missing or stale",
            "dispatcher_decision": "needs_task_packet",
            "packet_status": "needs_task_packet",
            "normalization_status": "needs_dispatcher_repair",
            "next_owner": "Dispatcher",
            "branch": branch,
            "github_branch": branch,
            "worker_id": target.get("worker_id") or worker_id_from_branch(branch),
            "machine_id": target.get("machine_id"),
            "changed_paths": payload_paths,
            "worker_check_evidence": {
                "ok": False,
                "route": "blocked",
                "check_status": "blocked",
                "integration_status": "needs_task_packet",
                "reason": "worker report contains blocked needs_task_packet evidence",
            },
        }
    if result_route in {"needs_worker_fix", "failed", "unknown"}:
        check_status = (
            report_explicit_check_status(
                project_root,
                evidence_ref,
                verification_report_paths,
                allow_local_fallback=True,
            )
            or "not_run_or_missing"
        )
        return needs_worker_fix_source(
            {
                "id": task_id(target),
                "task_id": task_id(target),
                "status": "agent_done",
                "status_reason": f"worker result route is {result_route}",
                "branch": branch,
                "github_branch": branch,
                "worker_id": target.get("worker_id") or worker_id_from_branch(branch),
                "machine_id": target.get("machine_id"),
                "changed_paths": payload_paths,
                "worker_report": verification_report_paths[-1],
            },
            check_status,
        )
    if result_route == "blocked":
        reason = "worker result route is blocked"
        return {
            "id": task_id(target),
            "task_id": task_id(target),
            "status": "blocked",
            "status_reason": reason,
            "not_worker_ready_reason": reason,
            "dispatcher_decision": "blocked_by_missing_environment",
            "integration_status": "blocked",
            "worker_report": verification_report_paths[-1],
            "changed_paths": payload_paths,
            "worker_id": target.get("worker_id") or worker_id_from_branch(branch),
            "machine_id": target.get("machine_id"),
            "worker_check_evidence": {
                "ok": False,
                "route": "blocked",
                "check_status": report_explicit_check_status(
                    project_root,
                    evidence_ref,
                    verification_report_paths,
                )
                or "blocked",
                "integration_status": "blocked",
                "reason": reason,
            },
        }

    source: dict[str, Any] = {
        "id": task_id(target),
        "task_id": task_id(target),
        "status": "integration_requested",
        "status_reason": "worker branch contains product changes; central sync derived integration request",
        "branch": branch,
        "github_branch": branch,
        "worker_id": target.get("worker_id") or worker_id_from_branch(branch),
        "machine_id": target.get("machine_id"),
        "changed_paths": payload_paths,
    }
    pr = pr_for_branch(project_root, branch)
    if pr.get("number"):
        source["github_pr"] = pr["number"]
        source["pr"] = pr["number"]
    if pr.get("url"):
        source["worker_report"] = pr["url"]
    return source


def should_replace_selected_candidate(
    previous: dict[str, Any] | None,
    *,
    branch: str,
    commit: str,
    committed_at: int,
    derived_result: bool = False,
) -> bool:
    if not previous:
        return True
    if (committed_at, branch) > (
        int(previous["committed_at"]),
        str(previous["branch"]),
    ):
        return True
    return (
        derived_result
        and str(previous.get("branch") or "") == branch
        and str(previous.get("commit") or "") == commit
    )


def branch_task_candidates(project_root: Path, ref: str, queue_relpath: str, allowed_task_ids: set[str]) -> list[dict[str, Any]]:
    queue = read_branch_queue(project_root, ref, queue_relpath)
    if not queue:
        return []
    result = []
    branch = normalize_branch(ref)
    for task in task_list(queue):
        status = str(task.get("status") or "")
        current_id = task_id(task)
        current_id_upper = current_id.upper()
        if status not in SYNCABLE_STATUSES:
            continue
        if allowed_task_ids:
            if current_id_upper in allowed_task_ids:
                result.append(task)
            continue
        if task_branch_matches(task, branch):
            result.append(task)
    return result


def is_promoted_model_limit_retry(target: dict[str, Any]) -> bool:
    if str(target.get("status") or "") != "planned":
        return False
    if target.get("worker_ready") is not True:
        return False
    if str(target.get("integration_status") or "") != "worker_ready":
        return False
    if target.get("model_limit_retry_allowed") is not True:
        return False
    history = target.get("worker_ready_promotion_history")
    if not isinstance(history, list):
        return False
    return any(
        isinstance(item, dict) and str(item.get("previous_integration_status") or "") == "blocked_model_limit"
        for item in history
    )


def is_model_limit_block_source(source: dict[str, Any], synced_status: str) -> bool:
    return (
        synced_status == "blocked"
        and str(source.get("integration_status") or "") == "blocked_model_limit"
        and str(source.get("dispatcher_decision") or "") == "blocked_by_missing_environment"
    )


def is_exact_review_lock_reentry(
    target: dict[str, Any],
    synced_status: str,
    result_identity_matches: bool,
) -> bool:
    return (
        result_identity_matches
        and str(target.get("status") or "") == "needs_dispatcher_repair"
        and str(target.get("repair_request") or "") == REVIEW_LOCK_REPAIR_REQUEST
        and synced_status in REVIEW_LOCK_STATUSES
    )


def clear_review_lock_repair_state(target: dict[str, Any]) -> None:
    for field in REVIEW_LOCK_REPAIR_FIELDS:
        target.pop(field, None)
    target["dispatcher_decision"] = "worker_ready"
    target["packet_status"] = "worker_ready"
    target["normalization_status"] = "worker_ready"


def copy_task_result(
    project_root: Path,
    target: dict[str, Any],
    source: dict[str, Any],
    branch_ref: str,
    branch: str,
    commit: str,
    paths: list[str],
    import_reports: bool = True,
) -> dict[str, Any] | None:
    source_status = str(source.get("status") or "")
    report_paths = safe_report_paths(paths, task_id(target)) if paths else []
    supplemental_evidence = supplemental_check_evidence_paths(
        project_root,
        target,
        branch,
        branch_ref,
        commit,
    )
    verification_report_paths = supplemental_evidence or report_paths
    explicit_result_route = report_explicit_result_route(
        project_root,
        branch_ref,
        verification_report_paths,
    )
    scope_evidence = report_dispatcher_scope_evidence(
        project_root,
        branch_ref,
        verification_report_paths,
    )
    if (
        explicit_result_route == "needs_worker_fix"
        and scope_evidence.get("repair_fields") == ["allowed_paths"]
        and scope_evidence.get("requested_allowed_paths")
    ):
        explicit_result_route = "needs_dispatcher_repair"
    if (
        explicit_result_route == "needs_dispatcher_repair"
        and dispatcher_scope_repair_supersedes_result(target, commit)
    ):
        return None
    if explicit_result_route == "needs_dispatcher_repair":
        source = requested_dispatcher_repair_source(source)
        source["status_reason"] = "immutable worker report requests Dispatcher packet repair"
        source["not_worker_ready_reason"] = source["status_reason"]
        source["worker_check_evidence"] = {
            "ok": False,
            "route": "needs_dispatcher_repair",
            "check_status": report_explicit_check_status(
                project_root,
                branch_ref,
                verification_report_paths,
            )
            or "blocked",
            "integration_status": "needs_dispatcher_repair",
            "reason": source["status_reason"],
            **scope_evidence,
        }
        if scope_evidence.get("repair_fields"):
            source["missing_packet_fields"] = scope_evidence["repair_fields"]
        if scope_evidence.get("requested_allowed_paths"):
            source["requested_allowed_paths"] = scope_evidence["requested_allowed_paths"]
            source["requested_allowed_paths_verified_by"] = scope_evidence[
                "requested_allowed_paths_verified_by"
            ]
            source["dispatcher_repair_kind"] = scope_evidence["repair_kind"]
        source_status = str(source.get("status") or "")
    route_specific_statuses = {
        "needs_architect",
        "needs_dispatcher_repair",
        "needs_human",
        "needs_stronger_agent",
        "needs_task_packet",
    }
    explicit_check_status = (
        None
        if source_status in route_specific_statuses
        else report_explicit_check_status(
            project_root,
            branch_ref,
            verification_report_paths,
        )
    )
    nonpassed_check_status = (
        explicit_check_status
        if explicit_check_status and explicit_check_status != "passed"
        else None
    )
    if nonpassed_check_status:
        source = needs_worker_fix_source(source, nonpassed_check_status)
        source_status = str(source.get("status") or "")
    elif explicit_check_status == "passed":
        source = passed_check_source(source)
    elif source_has_model_limit(
        project_root,
        source,
        branch_ref,
        verification_report_paths,
    ):
        source = model_limit_source(source)
        source_status = str(source.get("status") or "")
    elif is_dispatcher_repair_source(source):
        source = dispatcher_repair_source(source)
        source_status = str(source.get("status") or "")
    elif source_status == "needs_dispatcher_repair":
        source = requested_dispatcher_repair_source(source)
        source_status = str(source.get("status") or "")
    elif should_promote_blocked_pending_checks(source, paths):
        source = promote_blocked_pending_checks_source(source)
        source_status = str(source.get("status") or "")
    synced_status = "agent_done" if source_status == "done" else source_status
    target_status = str(target.get("status") or "")
    if source_status not in SYNCABLE_STATUSES or target_status in FINAL_STATUSES:
        return None
    if is_promoted_model_limit_retry(target) and is_model_limit_block_source(source, synced_status):
        return None
    result_identity_matches = (
        (
            expected_result_branch_matches(target, branch)
            or abandoned_result_branch_matches(target, branch)
        )
        and str(target.get("worker_result_commit") or "") == commit
    )
    verified_same_commit_replay = is_verified_same_commit_dispatcher_replay(
        target,
        branch=branch,
        commit=commit,
        result_route=explicit_result_route,
        check_status=explicit_check_status,
        report_paths=report_paths,
        paths=paths,
    )
    immutable_dispatcher_reclassification = (
        result_identity_matches
        and target_status in {"needs_worker_fix", "planned"}
        and synced_status == "needs_dispatcher_repair"
        and explicit_result_route == "needs_dispatcher_repair"
        and bool(report_paths)
    )
    review_lock_reentry = is_exact_review_lock_reentry(target, synced_status, result_identity_matches)
    target_integration_status = str(target.get("integration_status") or "")
    if (
        target_status in REVIEW_LOCK_STATUSES
        and target_integration_status in {"pending", "pending_checks"}
        and synced_status in RELEASE_LOCK_STATUSES
    ):
        return None
    if (
        target_status in RELEASE_LOCK_STATUSES
        and synced_status in REVIEW_LOCK_STATUSES
        and not review_lock_reentry
    ):
        return None
    if (
        result_identity_matches
        and target.get("worker_result_synced_at")
        and target_status != synced_status
        and not review_lock_reentry
        and not immutable_dispatcher_reclassification
        and not verified_same_commit_replay
    ):
        return None
    if (
        target_status == synced_status
        and (target.get("github_branch") or target.get("branch"))
        and result_identity_matches
        and not needs_sync_metadata_repair(target, synced_status, report_paths)
        and repair_contract_matches(target, source)
    ):
        return None

    before = target_status
    if review_lock_reentry:
        clear_review_lock_repair_state(target)
    target["status"] = synced_status
    target["worker_result_commit"] = commit
    target["worker_result_synced_at"] = utc_now()
    if paths:
        target["worker_changed_paths"] = paths
        filtered_paths = integration_paths(paths)
        if filtered_paths:
            target["changed_paths"] = filtered_paths
        else:
            target.pop("changed_paths", None)
        if report_paths and not target.get("worker_report"):
            target["worker_report"] = report_paths[0]
        imported_reports = import_worker_reports(project_root, branch_ref, report_paths) if import_reports else []
        if imported_reports:
            existing_reports = target.get("imported_worker_reports")
            if not isinstance(existing_reports, list):
                existing_reports = []
            target["imported_worker_reports"] = sorted(set(existing_reports + imported_reports))
    for field in COPY_FIELDS:
        value = source.get(field)
        if value not in (None, "", []):
            target[field] = value

    target["branch"] = branch
    target["github_branch"] = branch
    target["synced_from_worker_branch"] = branch
    commits = target.get("commits")
    if not isinstance(commits, list):
        commits = []
    source_commits = source.get("commits")
    if not isinstance(source_commits, list):
        source_commits = []
    for value in [*source_commits, commit]:
        if isinstance(value, str) and value and value not in commits:
            commits.append(value)
    target["commits"] = commits
    if paths:
        target["worker_changed_paths"] = paths
        filtered_paths = integration_paths(paths)
        if filtered_paths:
            target["changed_paths"] = filtered_paths
        else:
            target.pop("changed_paths", None)
        if report_paths:
            source_report = str(source.get("worker_report") or "").strip().replace("\\", "/")
            target["worker_report"] = source_report if source_report in report_paths else report_paths[-1]

    if synced_status in REVIEW_LOCK_STATUSES:
        target["lock"] = "review"
        target["worker_ready"] = False
        target["next_owner"] = "Integrator"
        source_integration_status = str(source.get("integration_status") or "")
        evidence = source.get("worker_check_evidence")
        needs_integrator_checks = source_integration_status == "pending_checks" or (
            isinstance(evidence, dict) and evidence.get("ok") is False
        )
        target["integration_status"] = "pending_checks" if needs_integrator_checks else "pending"
    elif synced_status in RELEASE_LOCK_STATUSES:
        target["lock"] = "free"
        source_integration_status = str(source.get("integration_status") or "")
        if source_integration_status:
            target["integration_status"] = source_integration_status
        if synced_status in {
            "needs_task_packet",
            "needs_architect",
            "needs_human",
            "needs_dispatcher_repair",
            "needs_worker_fix",
            "blocked",
        }:
            target["worker_ready"] = False
        if synced_status == "needs_task_packet":
            target["dispatcher_decision"] = "needs_task_packet"
            target["packet_status"] = "needs_task_packet"
            target["next_owner"] = "Dispatcher"
        elif synced_status == "needs_architect":
            target["dispatcher_decision"] = "needs_architect"
        elif synced_status == "needs_human":
            target["dispatcher_decision"] = "needs_human"
            target["next_owner"] = "human"
        elif synced_status == "needs_dispatcher_repair":
            target["dispatcher_decision"] = "needs_dispatcher_repair"
            target["packet_status"] = "needs_dispatcher_repair"
            target["normalization_status"] = "needs_dispatcher_repair"
            target["integration_status"] = "needs_dispatcher_repair"
            target["next_owner"] = "Dispatcher"
        elif synced_status == "needs_worker_fix":
            target["dispatcher_decision"] = "needs_worker_fix"
            target["integration_status"] = "needs_worker_fix"
            target["next_owner"] = "Dispatcher"
            target["next_role"] = "auto_dispatcher"
        elif synced_status == "blocked":
            decision = str(source.get("dispatcher_decision") or "")
            target["dispatcher_decision"] = decision if decision in BLOCKED_DISPATCHER_DECISIONS else "blocked_by_missing_environment"
            if target["dispatcher_decision"] == "needs_dispatcher_repair":
                target["packet_status"] = "needs_dispatcher_repair"
                target["normalization_status"] = "needs_dispatcher_repair"
            if str(target.get("integration_status") or "") == "blocked_model_limit":
                target["next_owner"] = "Dispatcher"
                target["next_action"] = "Retry when the requested worker model or model budget is available."
            else:
                target["next_owner"] = "Dispatcher"

    change = {"task_id": task_id(target), "from": before, "to": synced_status, "source_status": source_status, "branch": branch, "commit": commit}
    if review_lock_reentry:
        change["review_lock_reentry"] = True
    if target.get("worker_report"):
        change["worker_report"] = target.get("worker_report")
    if target.get("imported_worker_reports"):
        change["imported_worker_reports"] = target.get("imported_worker_reports")
    return change


def review_branch_candidates(task: dict[str, Any]) -> list[str]:
    branches: list[str] = []
    for field in ("synced_from_worker_branch", "github_branch", "branch"):
        branch = str(task.get(field) or "").strip()
        if branch and branch not in branches:
            branches.append(branch)
    return branches


def lock_recency_key(lock: dict[str, Any], index: int) -> tuple[str, str, str, int]:
    return (
        str(lock.get("at") or ""),
        str(lock.get("review_at") or ""),
        str(lock.get("expires_at") or ""),
        index,
    )


def canonical_review_lock(task: dict[str, Any], task_locks: list[dict[str, Any]]) -> dict[str, Any] | None:
    all_indexed = list(enumerate(task_locks))
    indexed = [
        (index, lock)
        for index, lock in all_indexed
        if str(lock.get("state") or "") in REVIEW_REACTIVATABLE_LOCK_STATES
    ]
    branches = review_branch_candidates(task)
    for branch in branches:
        matches = [(index, lock) for index, lock in indexed if str(lock.get("branch") or "").strip() == branch]
        if matches:
            return max(matches, key=lambda item: lock_recency_key(item[1], item[0]))[1]
        if any(str(lock.get("branch") or "").strip() == branch for _, lock in all_indexed):
            return None
    if branches:
        branchless = [(index, lock) for index, lock in indexed if not str(lock.get("branch") or "").strip()]
        if branchless:
            return max(branchless, key=lambda item: lock_recency_key(item[1], item[0]))[1]
        return None
    active = [(index, lock) for index, lock in indexed if str(lock.get("state") or "") == "review"]
    if active:
        return max(active, key=lambda item: lock_recency_key(item[1], item[0]))[1]
    if indexed:
        return max(indexed, key=lambda item: lock_recency_key(item[1], item[0]))[1]
    return None


def set_review_lock(lock: dict[str, Any], now: str, notes: str) -> bool:
    if str(lock.get("state") or "") not in REVIEW_REACTIVATABLE_LOCK_STATES:
        return False
    changed = str(lock.get("state") or "") != "review"
    stale_release_fields = any(field in lock for field in ("released_at", "released_by", "release_reason"))
    if not changed and not stale_release_fields:
        return False
    lock["state"] = "review"
    lock["review_at"] = now
    lock["notes"] = notes
    lock.pop("released_at", None)
    lock.pop("released_by", None)
    lock.pop("release_reason", None)
    return True


def release_superseded_review_lock(lock: dict[str, Any], now: str) -> bool:
    if str(lock.get("state") or "") not in ACTIVE_LOCK_STATES:
        return False
    lock["state"] = "released"
    lock["released_at"] = now
    lock["notes"] = "superseded by current worker result branch"
    lock["released_by"] = "sync_worker_results.py"
    lock["release_reason"] = "superseded by current worker result branch"
    return True


def route_missing_review_lock_to_dispatcher(task: dict[str, Any], now: str) -> bool:
    reason = "current Worker result has no reactivatable canonical review lock"
    before = dict(task)
    task["status"] = "needs_dispatcher_repair"
    task["worker_ready"] = False
    task["lock"] = "free"
    task["dispatcher_decision"] = "needs_dispatcher_repair"
    task["packet_status"] = "needs_dispatcher_repair"
    task["normalization_status"] = "needs_dispatcher_repair"
    task["integration_status"] = "needs_dispatcher_repair"
    task["next_owner"] = "Dispatcher"
    task["status_reason"] = reason
    task["repair_request"] = "reconcile_review_lock_evidence"
    task["missing_packet_fields"] = ["canonical_review_lock"]
    task["repair_owner"] = "Dispatcher"
    task["next_action"] = "Dispatcher must reconcile terminal or missing lock evidence before integration resumes."
    task["review_lock_repair_requested_at"] = now
    task["review_lock_repair_requested_by"] = "sync_worker_results.py"
    return task != before


def immutable_worker_report_check_status(project_root: Path, task: dict[str, Any]) -> str | None:
    commit = str(task.get("worker_result_commit") or "").strip()
    if not re.fullmatch(r"[0-9a-fA-F]{7,40}", commit):
        return None
    changed_paths = task.get("worker_changed_paths")
    report_paths = safe_report_paths(
        changed_paths if isinstance(changed_paths, list) else [],
        task_id(task),
    )
    if len(report_paths) != 1:
        report_paths = safe_report_paths(
            [str(task.get("worker_report") or "")],
            task_id(task),
        )
    if len(report_paths) != 1:
        return None
    return report_explicit_check_status(
        project_root,
        commit,
        report_paths,
        allow_local_fallback=False,
    )


def restore_verified_review_lock_reentry(task: dict[str, Any], now: str) -> bool:
    before = dict(task)
    for field in REVIEW_LOCK_REPAIR_FIELDS:
        task.pop(field, None)
    task["status"] = "integration_requested"
    task["integration_status"] = "pending"
    task["dispatcher_decision"] = "integration_ready"
    task["packet_status"] = "worker_ready"
    task["normalization_status"] = "worker_ready"
    task["worker_ready"] = False
    task["next_owner"] = "Integrator"
    task["next_role"] = "auto_integrator"
    task["lock"] = "review"
    task["worker_check_evidence"] = {
        "ok": True,
        "route": "agent_done",
        "check_status": "passed",
        "integration_status": "pending",
        "reason": "immutable worker report explicitly records required checks as passed",
    }
    history = task.get("status_history")
    if not isinstance(history, list):
        history = []
    history.append({
        "at": now,
        "by": "sync_worker_results.py",
        "from": "needs_dispatcher_repair",
        "to": "integration_requested",
        "event": "review_lock_reentry",
        "reason": "exact worker result commit, report and released branch lock were verified",
    })
    task["status_history"] = history
    return task != before


def has_worker_review_evidence(task: dict[str, Any], task_locks: list[dict[str, Any]]) -> bool:
    if task_locks:
        return True
    return any(
        str(task.get(field) or "").strip()
        for field in (
            "worker_result_commit",
            "worker_report",
            "synced_from_worker_branch",
            "worker_id",
        )
    )


def update_locks(locks: dict[str, Any], changes: list[dict[str, Any]]) -> int:
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return 0
    changed = 0
    now = utc_now()
    by_task = {str(change["task_id"]): change for change in changes}
    locks_by_task: dict[str, list[dict[str, Any]]] = {}
    for lock in lock_list:
        if isinstance(lock, dict):
            locks_by_task.setdefault(str(lock.get("task_id") or ""), []).append(lock)
    for current_task_id, change in by_task.items():
        task_locks = locks_by_task.get(current_task_id, [])
        status = str(change.get("to") or "")
        if status in REVIEW_LOCK_STATUSES:
            canonical = canonical_review_lock(
                {"synced_from_worker_branch": change.get("branch")},
                task_locks,
            )
            for lock in task_locks:
                if lock is canonical:
                    changed += int(set_review_lock(lock, now, "worker result synced for integrator review"))
                else:
                    changed += int(release_superseded_review_lock(lock, now))
        elif status in FINAL_STATUSES:
            for lock in task_locks:
                if lock.get("state") != "released":
                    lock["state"] = "released"
                    lock["released_at"] = now
                    lock["notes"] = "task already finalized in event log"
                    lock["released_by"] = "sync_worker_results.py"
                    lock["release_reason"] = "task already finalized in event log"
                    changed += 1
        elif status in RELEASE_LOCK_STATUSES:
            for lock in task_locks:
                if lock.get("state") != "released":
                    lock["state"] = "released"
                    lock["released_at"] = now
                    lock["notes"] = "worker result synced and returned from worker lane"
                    lock["released_by"] = "sync_worker_results.py"
                    lock["release_reason"] = "worker result synced and returned from worker lane"
                    changed += 1
    if changed:
        locks["updated_at"] = now
    return changed


def repair_existing_review_locks(
    queue: dict[str, Any],
    locks: dict[str, Any],
    project_root: Path | None = None,
) -> tuple[int, int]:
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return 0, 0
    central = {
        task_id(task): task
        for task in queue.get("tasks", [])
        if isinstance(task, dict) and task_id(task)
    }
    locks_by_task: dict[str, list[dict[str, Any]]] = {}
    for lock in lock_list:
        if isinstance(lock, dict):
            locks_by_task.setdefault(str(lock.get("task_id") or ""), []).append(lock)
    lock_changed = 0
    queue_changed = 0
    now = utc_now()
    for current_task_id, target in central.items():
        status = str(target.get("status") or "")
        exact_reentry_requested = (
            status == "needs_dispatcher_repair"
            and str(target.get("repair_request") or "") == REVIEW_LOCK_REPAIR_REQUEST
        )
        if status not in REVIEW_LOCK_STATUSES and not exact_reentry_requested:
            continue
        task_locks = locks_by_task.get(current_task_id, [])
        if not has_worker_review_evidence(target, task_locks):
            continue
        canonical = canonical_review_lock(target, task_locks)
        if canonical is None:
            for lock in task_locks:
                lock_changed += int(release_superseded_review_lock(lock, now))
            if not exact_reentry_requested:
                queue_changed += int(route_missing_review_lock_to_dispatcher(target, now))
            continue
        if exact_reentry_requested:
            if (
                project_root is None
                or immutable_worker_report_check_status(project_root, target) != "passed"
            ):
                continue
            queue_changed += int(restore_verified_review_lock_reentry(target, now))
        if target.get("lock") != "review":
            target["lock"] = "review"
            queue_changed += 1
        for lock in task_locks:
            if lock is canonical:
                lock_changed += int(set_review_lock(lock, now, "worker result already synced for integrator review"))
            else:
                lock_changed += int(release_superseded_review_lock(lock, now))
    if lock_changed:
        locks["updated_at"] = now
    return lock_changed, queue_changed


def queue_git_relpath(project_root: Path, queue_path: Path) -> str:
    try:
        return queue_path.relative_to(project_root).as_posix()
    except ValueError:
        return task_relpath(project_root, "task_queue.json")


def sync(project_root: Path, args: argparse.Namespace) -> dict[str, Any]:
    if args.fetch:
        run_git(project_root, ["fetch", "--prune", "origin"])

    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")
    queue_relpath = queue_git_relpath(project_root, queue_path)
    queue = load_json(queue_path)
    locks = load_json(locks_path)
    misclassified_report_cleanup = cleanup_misclassified_imported_reports(
        project_root,
        queue,
        apply=bool(args.apply),
    )
    normalized_report_paths = normalize_canonical_worker_reports(
        project_root,
        queue,
        apply=bool(args.apply),
    )
    central = tasks_by_id(queue)
    lock_rows = locks.get("locks") if isinstance(locks, dict) else []
    if not isinstance(lock_rows, list):
        lock_rows = []
    active_lease_branches = {
        str(lock.get("task_id") or ""): normalize_branch(str(lock.get("branch") or ""))
        for lock in lock_rows
        if isinstance(lock, dict)
        if str(lock.get("state") or "") in ACTIVE_LOCK_STATES
        and str(lock.get("task_id") or "").strip()
        and str(lock.get("branch") or "").strip()
    }
    integrated_records, finalized_records = read_event_records(task_file(project_root, "agent_events.jsonl"))
    reconciliation_changes = reconcile_finalized_tasks_from_events(queue, finalized_records, project_root)
    selected: dict[str, dict[str, Any]] = {}
    scanned = 0
    skipped_missing_task = 0
    skipped_already_integrated = 0

    for ref in list_worker_refs(project_root, resolve_branch_prefixes(args.branch_prefix)):
        branch = normalize_branch(ref)
        commit = head_sha(project_root, ref)
        committed_at = commit_time(project_root, ref)
        paths = changed_paths(project_root, args.base_ref, ref)
        branch_task_ids = infer_task_ids_from_branch(branch, set(central))
        for source_task in branch_task_candidates(project_root, ref, queue_relpath, branch_task_ids):
            scanned += 1
            current_task_id = task_id(source_task)
            target = central.get(current_task_id)
            if not target:
                skipped_missing_task += 1
                continue
            active_lease_branch = active_lease_branches.get(current_task_id)
            if active_lease_branch and active_lease_branch != branch:
                continue
            if is_recorded_key(current_task_id, integrated_records):
                skipped_already_integrated += 1
                continue
            if is_clean_rebuild_source_branch(target, branch):
                continue
            if has_expected_result_branch(target) and not expected_result_branch_matches(target, branch):
                continue
            previous = selected.get(current_task_id)
            if should_replace_selected_candidate(
                previous,
                branch=branch,
                commit=commit,
                committed_at=committed_at,
            ):
                selected[current_task_id] = {
                    "source_task": source_task,
                    "branch_ref": ref,
                    "branch": branch,
                    "commit": commit,
                    "committed_at": committed_at,
                    "paths": paths,
                }
        for current_task_id, target in central.items():
            if is_recorded_key(current_task_id, integrated_records):
                continue
            active_lease_branch = active_lease_branches.get(current_task_id)
            if active_lease_branch and active_lease_branch != branch:
                continue
            active_lease_match = active_lease_branches.get(current_task_id) == branch
            if (
                not expected_result_branch_matches(target, branch)
                and not abandoned_result_branch_matches(target, branch)
                and not active_lease_match
            ):
                continue
            derived_source = source_from_claimed_branch(
                project_root,
                target,
                branch,
                paths,
                branch_ref=ref,
                active_lease_match=active_lease_match,
            )
            if not derived_source:
                continue
            previous = selected.get(current_task_id)
            if not should_replace_selected_candidate(
                previous,
                branch=branch,
                commit=commit,
                committed_at=committed_at,
                derived_result=True,
            ):
                continue
            selected[current_task_id] = {
                "source_task": derived_source,
                "branch_ref": ref,
                "branch": branch,
                "commit": commit,
                "committed_at": committed_at,
                "paths": paths,
            }

    changes: list[dict[str, Any]] = list(reconciliation_changes)
    for current_task_id in sorted(selected):
        item = selected[current_task_id]
        target = central.get(current_task_id)
        if not target:
            continue
        change = copy_task_result(
            project_root,
            target,
            item["source_task"],
            str(item["branch_ref"]),
            str(item["branch"]),
            str(item["commit"]),
            list(item["paths"]),
            import_reports=bool(args.apply),
        )
        if change:
            changes.append(change)

    lock_changes = update_locks(locks, changes)
    repair_lock_changes, repair_queue_changes = repair_existing_review_locks(
        queue,
        locks,
        project_root,
    )
    lock_changes += repair_lock_changes
    if changes or repair_queue_changes or misclassified_report_cleanup["queue_changed_count"]:
        queue["updated_at"] = utc_now()
    if args.apply:
        if changes or repair_queue_changes or misclassified_report_cleanup["queue_changed_count"]:
            write_json(queue_path, queue)
        if lock_changes:
            write_json(locks_path, locks)
        append_log(project_root, "worker-sync", "worker_results_synced", severity="info", change_count=len(changes), lock_change_count=lock_changes)

    return {
        "project_root": str(project_root),
        "checked_at": utc_now(),
        "apply": bool(args.apply),
        "scanned_worker_tasks": scanned,
        "change_count": len(changes),
        "reconciled_finalized_count": len(reconciliation_changes),
        "skipped_already_integrated": skipped_already_integrated,
        "lock_change_count": lock_changes,
        "queue_lock_repair_count": repair_queue_changes,
        "normalized_worker_report_count": len(normalized_report_paths),
        "normalized_worker_report_paths": normalized_report_paths,
        "misclassified_report_cleanup": misclassified_report_cleanup,
        "skipped_missing_task": skipped_missing_task,
        "changes": changes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync worker branch statuses into the central task queue.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--queue")
    parser.add_argument("--locks")
    parser.add_argument("--base-ref", help="Base ref used to collect changed paths.")
    parser.add_argument("--branch-prefix", action="append", default=None, help="Worker branch ref prefix. Can be repeated. Overrides the default prefix set.")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    report = sync(project_root, args)
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else f"changes: {report['change_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
