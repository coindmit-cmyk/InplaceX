#!/usr/bin/env python3
"""Collect a deterministic preflight snapshot for Auto Integrator."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_file
from repository_pr_identity import validate_repository_pr_identity


DEFAULT_PREFIXES = (
    "AiStudio/Agent/worker/",
    "AiStudio/Agent/integrator/",
    "AiStudio/Agent/finalizer/",
    "AiStudio/Agent/dispatcher/",
    "AiStudio/Agent/cleanup/",
    "AiStudio/CodexDesktop/",
    "remote/",
    "local/",
    "integrator/",
    "staging/",
    "experiment/",
)
DEFAULT_MAX_PATHS_PER_CANDIDATE = 200
DEFAULT_MAX_CONFLICTS = 200
DEFAULT_MAX_OVERLAP_PATHS = 20
ACTIVE_LOCK_STATES = {"locked", "in_progress", "review"}
COMMIT_SHA_RE = re.compile(r"[0-9a-fA-F]{40}")
INTEGRATION_STATUSES = {"review", "agent_done", "integration_ready", "integration_requested"}
TASK_ID_RE = re.compile(r"\b(?:[A-Z][A-Z0-9]{0,12}-[0-9][0-9A-Z]*(?:\.[0-9A-Z]+)*|[A-Z]{1,6}[0-9][A-Z0-9]*(?:\.[0-9A-Z]+)*)\b")
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
    ".agent/",
    "AiStudio/Agent/",
    "agent-worktrees/",
    "docs/agent-updates/",
    "docs/reports/",
    "AiStudio/Task_manager/",
    "AiStudio/Task_manager/process-logs/",
    "AiStudio/Task_manager/integration_pr_snapshot_",
    "docs/plans/",
    "docs/plans/process-logs/",
    "docs/plans/integration_pr_snapshot_",
    "old/agent-updates/",
    "old/agent-runs/",
)
AGENT_CORE_PROJECT_ID = "ai-project-agent"
AGENT_CORE_AGENT_VERSION_PATH = ".agent/agent_version.json"
PROJECT_DURABLE_INTEGRATION_PATHS = {".agent/START_HERE.md"}
AGENT_CORE_DURABLE_INTEGRATION_PATHS = {
    ".agent/codebase_intelligence.json",
}
AGENT_CORE_DURABLE_INTEGRATION_PREFIXES = ("docs/plans/tasks/",)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run_git(project_root: Path, args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(project_root), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def git_stdout(project_root: Path, args: list[str]) -> str:
    return run_git(project_root, args).stdout.strip()


def load_json(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def normalize_branch(ref: str | None) -> str:
    ref = str(ref or "").strip()
    if ref.startswith("refs/heads/"):
        return ref.removeprefix("refs/heads/")
    if ref.startswith("refs/remotes/origin/"):
        return ref.removeprefix("refs/remotes/origin/")
    if ref.startswith("origin/"):
        return ref.removeprefix("origin/")
    return ref


def branch_matches(branch: str, prefixes: tuple[str, ...]) -> bool:
    normalized = normalize_branch(branch)
    return any(normalized.startswith(prefix) for prefix in prefixes)


def available_candidate_refs(project_root: Path) -> set[str]:
    refs = git_stdout(
        project_root,
        ["for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes/origin"],
    )
    return {ref for ref in refs.splitlines() if ref}


def commit_object_available(project_root: Path, ref: str) -> bool:
    if not COMMIT_SHA_RE.fullmatch(str(ref or "").strip()):
        return False
    verify = run_git(project_root, ["cat-file", "-e", f"{ref}^{{commit}}"], check=False)
    return verify.returncode == 0


def fetch_authorized_candidate_refs(project_root: Path, refs: list[str], base: str) -> dict[str, Any]:
    """Refresh only queue/hygiene-authorized refs in a narrow checkout."""
    base_branch = normalize_branch(base)
    requested: list[str] = []
    invalid: list[dict[str, str]] = []
    unavailable_commits: list[dict[str, str]] = []
    for value in refs:
        normalized = normalize_branch(value)
        if not normalized or normalized == base_branch or normalized in requested:
            continue
        if COMMIT_SHA_RE.fullmatch(normalized):
            if not commit_object_available(project_root, normalized):
                unavailable_commits.append({
                    "branch": normalized,
                    "reason": "authorized commit object is not locally available",
                })
            continue
        check = run_git(project_root, ["check-ref-format", "--branch", normalized], check=False)
        if check.returncode != 0:
            invalid.append({
                "branch": str(value),
                "reason": (check.stderr or check.stdout or "invalid branch ref").strip(),
            })
            continue
        requested.append(normalized)

    fetched: list[str] = []
    unavailable: list[dict[str, str]] = list(unavailable_commits)
    for branch in requested:
        remote_ref = f"origin/{branch}"
        refspec = f"+refs/heads/{branch}:refs/remotes/origin/{branch}"
        fetch = run_git(project_root, ["fetch", "--no-tags", "origin", refspec], check=False)
        if fetch.returncode != 0:
            unavailable.append({
                "branch": branch,
                "reason": (fetch.stderr or fetch.stdout or "candidate ref fetch failed").strip(),
            })
            continue
        verify = run_git(
            project_root,
            ["show-ref", "--verify", f"refs/remotes/{remote_ref}"],
            check=False,
        )
        if verify.returncode != 0:
            unavailable.append({"branch": branch, "reason": "fetched candidate ref is not locally resolvable"})
            continue
        fetched.append(remote_ref)

    return {
        "requested_count": len(requested),
        "fetched_count": len(fetched),
        "invalid_count": len(invalid),
        "unavailable_count": len(unavailable),
        "requested": requested,
        "fetched": fetched,
        "invalid": invalid,
        "unavailable": unavailable,
    }


def list_candidate_refs(
    project_root: Path,
    prefixes: tuple[str, ...],
    base: str,
    additional_refs: list[str] | None = None,
) -> list[str]:
    available = available_candidate_refs(project_root)
    result: list[str] = []
    seen: set[str] = set()
    for ref in sorted(available):
        if not ref or ref == f"origin/{base}" or normalize_branch(ref) == base:
            continue
        if not branch_matches(ref, prefixes):
            continue
        if ref not in seen:
            result.append(ref)
            seen.add(ref)
    for value in additional_refs or []:
        raw = str(value or "").strip()
        if not raw:
            continue
        if COMMIT_SHA_RE.fullmatch(raw) and commit_object_available(project_root, raw):
            resolved = raw.lower()
        else:
            candidates = [raw]
            if not raw.startswith("origin/"):
                candidates.append(f"origin/{raw}")
            resolved = next((candidate for candidate in candidates if candidate in available), None)
        if not resolved or normalize_branch(resolved) == normalize_branch(base) or resolved in seen:
            continue
        result.append(resolved)
        seen.add(resolved)
    return sorted(result)


def changed_paths(project_root: Path, left_ref: str, right_ref: str) -> list[str]:
    output = git_stdout(project_root, ["diff", "--name-only", left_ref, right_ref])
    return sorted(path for path in output.splitlines() if path)


def candidate_changed_paths(project_root: Path, base_ref: str, merge_base_ref: str, head_ref: str) -> list[str]:
    candidate_paths = set(changed_paths(project_root, merge_base_ref, head_ref))
    base_to_head_paths = set(changed_paths(project_root, base_ref, head_ref))
    active = sorted(candidate_paths & base_to_head_paths)
    base_only_coordination = [
        path
        for path in base_to_head_paths
        if path not in candidate_paths and is_coordination_path(path)
    ]
    return sorted(set([*active, *base_only_coordination]))


def compact_paths(paths: list[str], limit: int) -> list[str]:
    if limit <= 0:
        return paths
    return paths[:limit]


def is_coordination_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    if normalized.startswith((
        ".agent/skills/",
        ".agent/prompts/",
        "docs/plans/contracts/",
        "docs/reports/integration/",
    )):
        return False
    return normalized in COORDINATION_EXACT_PATHS or any(normalized.startswith(prefix) for prefix in COORDINATION_PREFIXES)


def canonical_project_id(project_root: Path) -> str:
    for relative_path in ("PROJECT_VERSION.json", ".agent/context.json"):
        metadata = load_json(project_root / relative_path)
        if isinstance(metadata, dict):
            value = str(metadata.get("project_id") or metadata.get("project_name") or "").strip()
            if value:
                return value
    return ""


def is_agent_core_durable_integration_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    return normalized in AGENT_CORE_DURABLE_INTEGRATION_PATHS or normalized.startswith(
        AGENT_CORE_DURABLE_INTEGRATION_PREFIXES
    )


def version_tuple(value: str) -> tuple[int, ...] | None:
    normalized = value.strip().removeprefix("v")
    parts = normalized.split(".")
    if len(parts) < 3 or any(not part.isdigit() for part in parts):
        return None
    return tuple(int(part) for part in parts)


def agent_version_parity_decision(candidate_versions: dict[str, str], target_version: str) -> dict[str, Any]:
    reasons: list[str] = []
    missing = sorted(key for key, value in candidate_versions.items() if not value)
    if missing:
        reasons.append("candidate_version_metadata_missing:" + ",".join(missing))
    populated = {value for value in candidate_versions.values() if value}
    if len(populated) > 1:
        reasons.append("candidate_version_metadata_mismatch")
    candidate_version = next(iter(populated), "") if len(populated) == 1 else ""
    candidate_tuple = version_tuple(candidate_version) if candidate_version else None
    target_tuple = version_tuple(target_version)
    if candidate_version and candidate_tuple is None:
        reasons.append("candidate_version_unparseable")
    if target_tuple is None:
        reasons.append("target_version_unparseable")
    if candidate_tuple is not None and target_tuple is not None and candidate_tuple < target_tuple:
        reasons.append("candidate_version_regression")
    return {
        "eligible": not reasons,
        "decision": "integration" if not reasons else "coordination",
        "candidate_version": candidate_version or None,
        "target_version": target_version or None,
        "candidate_versions": candidate_versions,
        "reasons": reasons,
    }


def git_file_text(project_root: Path, ref: str, path: str) -> str:
    proc = run_git(project_root, ["show", f"{ref}:{path}"], check=False)
    return proc.stdout.strip() if proc.returncode == 0 else ""


def git_json_at_ref(project_root: Path, ref: str, path: str) -> dict[str, Any]:
    text = git_file_text(project_root, ref, path)
    if not text:
        return {}
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


def agent_version_integration_decision(project_root: Path, base: str, ref: str) -> dict[str, Any]:
    candidate_agent = git_json_at_ref(project_root, ref, AGENT_CORE_AGENT_VERSION_PATH)
    candidate_template = git_json_at_ref(project_root, ref, "templates/.agent/agent_version.json")
    candidate_project = git_json_at_ref(project_root, ref, "PROJECT_VERSION.json")
    candidate_versions = {
        "agent": str(candidate_agent.get("agent_version") or "").strip(),
        "template": str(candidate_template.get("agent_version") or "").strip(),
        "version_file": git_file_text(project_root, ref, "VERSION"),
        "project_version": str(candidate_project.get("product_version") or "").strip(),
    }
    decision = agent_version_parity_decision(candidate_versions, git_file_text(project_root, base, "VERSION"))
    decision.update({"base_ref": base, "candidate_ref": ref})
    return decision


def apply_agent_version_path_gate(
    project_root: Path,
    base: str,
    ref: str,
    paths: list[str],
    coordination: list[str],
    integration: list[str],
) -> dict[str, Any] | None:
    if AGENT_CORE_AGENT_VERSION_PATH not in paths or canonical_project_id(project_root) != AGENT_CORE_PROJECT_ID:
        return None
    decision = agent_version_integration_decision(project_root, base, ref)
    if decision["eligible"]:
        coordination[:] = [path for path in coordination if path != AGENT_CORE_AGENT_VERSION_PATH]
        if AGENT_CORE_AGENT_VERSION_PATH not in integration:
            integration.append(AGENT_CORE_AGENT_VERSION_PATH)
    else:
        integration[:] = [path for path in integration if path != AGENT_CORE_AGENT_VERSION_PATH]
        if AGENT_CORE_AGENT_VERSION_PATH not in coordination:
            coordination.append(AGENT_CORE_AGENT_VERSION_PATH)
    coordination.sort()
    integration.sort()
    return decision


def split_coordination_paths(paths: list[str], *, project_root: Path | None = None) -> tuple[list[str], list[str]]:
    agent_core = project_root is not None and canonical_project_id(project_root) == AGENT_CORE_PROJECT_ID
    coordination: list[str] = []
    integration: list[str] = []
    for path in paths:
        coordination_path = is_coordination_path(path)
        durable_project_path = path.replace("\\", "/") in PROJECT_DURABLE_INTEGRATION_PATHS
        if coordination_path and not durable_project_path and not (agent_core and is_agent_core_durable_integration_path(path)):
            coordination.append(path)
        else:
            integration.append(path)
    coordination.sort()
    integration.sort()
    return coordination, integration


def canonical_task_ids(task_keys: set[str], tasks_by_id: dict[str, dict[str, Any]]) -> list[str]:
    canonical: set[str] = set()
    for key in task_keys:
        task = tasks_by_id.get(key)
        task_id = str((task or {}).get("id") or key).strip()
        if task_id:
            canonical.add(task_id)
    return sorted(canonical, key=lambda value: value.upper())


def infer_task_ids(branch: str, paths: list[str], tasks_by_id: dict[str, dict[str, Any]]) -> list[str]:
    report_paths = [
        path
        for path in paths
        if path.replace("\\", "/").startswith(("docs/plans/reports/", "docs/plans/reports/workers/", "AiStudio/Task_manager/reports/"))
    ]
    report_candidates = set(TASK_ID_RE.findall(" ".join(report_paths).upper())) & set(tasks_by_id)
    if report_candidates:
        return canonical_task_ids(report_candidates, tasks_by_id)
    text = " ".join([normalize_branch(branch), *paths])
    candidates = set(TASK_ID_RE.findall(text.upper())) & set(tasks_by_id)
    if candidates:
        return canonical_task_ids(candidates, tasks_by_id)
    normalized = normalize_branch(branch).lower()
    result = []
    for task_key, task in tasks_by_id.items():
        task_branch = normalize_branch(task.get("branch") or task.get("source_branch") or task.get("pr_branch")).lower()
        status = str(task.get("status") or "").strip()
        if task_branch and task_branch == normalized and status in INTEGRATION_STATUSES:
            result.append(str(task.get("id") or task_key).strip())
    return sorted(set(result), key=lambda value: value.upper())


def collect_tasks(queue: dict[str, Any] | None) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    tasks_by_id: dict[str, dict[str, Any]] = {}
    integration_candidates: list[dict[str, Any]] = []
    if not isinstance(queue, dict):
        return tasks_by_id, integration_candidates
    for task in queue.get("tasks", []):
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or "").strip()
        if not task_id:
            continue
        tasks_by_id[task_id.upper()] = task
        if str(task.get("status") or "") in INTEGRATION_STATUSES:
            integration_candidates.append({
                "task_id": task_id,
                "status": task.get("status"),
                "branch": task.get("branch") or task.get("source_branch") or task.get("pr_branch"),
                "pr": task.get("pr") or task.get("pull_request") or task.get("github_pr"),
                "worker_ready": task.get("worker_ready"),
                "dispatcher_decision": task.get("dispatcher_decision"),
            })
    return tasks_by_id, integration_candidates


def integration_task_refs(integration_tasks: list[dict[str, Any]]) -> list[str]:
    """Allow task-routed product branches regardless of their naming prefix."""
    result: list[str] = []
    seen: set[str] = set()
    for task in integration_tasks:
        branch = str(task.get("branch") or "").strip()
        normalized = normalize_branch(branch)
        if not branch or not normalized or normalized in seen:
            continue
        result.append(branch)
        seen.add(normalized)
    return result


def repository_hygiene_refs(path: Path) -> tuple[list[str], bool]:
    data = load_json(path)
    cleanup = data.get("branch_cleanup") if isinstance(data, dict) else None
    if not isinstance(cleanup, dict) or cleanup.get("pr_evidence_available") is not True:
        return [], False
    refs = [str(value).strip() for value in cleanup.get("salvage_branches") or [] if str(value).strip()]
    return sorted(set(refs)), True


def collect_locks(locks: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not isinstance(locks, dict):
        return []
    active = []
    for lock in locks.get("locks", []):
        if isinstance(lock, dict) and lock.get("state") in ACTIVE_LOCK_STATES:
            active.append(lock)
    return active


def collect_conflicts(candidates: list[dict[str, Any]], max_conflicts: int, max_overlap_paths: int) -> tuple[list[dict[str, Any]], int]:
    conflicts: list[dict[str, Any]] = []
    omitted = 0
    for index, left in enumerate(candidates):
        left_paths = set(left.get("integration_changed_paths", []))
        if not left_paths:
            continue
        for right in candidates[index + 1:]:
            overlap = sorted(left_paths & set(right.get("integration_changed_paths", [])))
            if overlap:
                if max_conflicts > 0 and len(conflicts) >= max_conflicts:
                    omitted += 1
                    continue
                stored_overlap = overlap[:max_overlap_paths] if max_overlap_paths > 0 else overlap
                conflicts.append({
                    "left": left["branch"],
                    "right": right["branch"],
                    "overlap_paths": stored_overlap,
                    "overlap_path_count": len(overlap),
                    "overlap_paths_truncated": len(stored_overlap) < len(overlap),
                    "omitted_overlap_path_count": max(len(overlap) - len(stored_overlap), 0),
                    "severity": "warning",
                })
    return conflicts, omitted


def collect_candidate(project_root: Path, base: str, ref: str, tasks_by_id: dict[str, dict[str, Any]], max_paths: int) -> dict[str, Any]:
    head_sha = git_stdout(project_root, ["rev-parse", ref])
    merge_base = git_stdout(project_root, ["merge-base", base, ref])
    full_paths = candidate_changed_paths(project_root, base, merge_base, ref)
    paths = compact_paths(full_paths, max_paths)
    coordination_paths, integration_paths = split_coordination_paths(paths, project_root=project_root)
    agent_version_decision = apply_agent_version_path_gate(
        project_root,
        base,
        ref,
        paths,
        coordination_paths,
        integration_paths,
    )
    ahead = git_stdout(project_root, ["rev-list", "--count", f"{base}..{ref}"])
    behind = git_stdout(project_root, ["rev-list", "--count", f"{ref}..{base}"])
    task_ids = infer_task_ids(ref, paths, tasks_by_id)
    repository_pr_identity: dict[str, Any] | None = None
    candidate_pr: int | None = None
    if len(task_ids) == 1:
        task = tasks_by_id.get(task_ids[0].upper())
        binding = validate_repository_pr_identity(
            task,
            candidate_branch=ref,
            candidate_pr=(task or {}).get("github_pr"),
            candidate_head_sha=head_sha,
            require_candidate_pr=True,
            require_candidate_head=True,
        )
        if binding.get("applicable"):
            repository_pr_identity = binding
            candidate_pr = binding.get("pr_number")
    return {
        "branch": ref,
        "normalized_branch": normalize_branch(ref),
        "head_sha": head_sha,
        "merge_base_sha": merge_base,
        "ahead_of_base": int(ahead or 0),
        "behind_base": int(behind or 0),
        "changed_paths": paths,
        "coordination_changed_paths": coordination_paths,
        "integration_changed_paths": integration_paths,
        "agent_version_integration_decision": agent_version_decision,
        "task_ids": task_ids,
        "pr": candidate_pr,
        "repository_pr_identity": repository_pr_identity,
        "path_count": len(full_paths),
        "stored_path_count": len(paths),
        "path_source": "merge_base_to_head_excluding_current_base_matches",
        "path_list_truncated": len(paths) < len(full_paths),
        "omitted_path_count": max(len(full_paths) - len(paths), 0),
        "coordination_path_count": len(coordination_paths),
        "integration_path_count": len(integration_paths),
    }


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")
    prefixes = tuple(args.prefix)

    if args.fetch:
        run_git(project_root, ["fetch", "--prune", "origin"])

    status = git_stdout(project_root, ["status", "--short", "--branch"])
    base_sha = git_stdout(project_root, ["rev-parse", args.base])
    queue = load_json(queue_path)
    locks = load_json(locks_path)
    tasks_by_id, integration_tasks = collect_tasks(queue)
    active_locks = collect_locks(locks)

    routed_task_refs = integration_task_refs(integration_tasks)
    use_hygiene = bool(getattr(args, "use_repository_hygiene", False))
    hygiene_path = (
        Path(args.repository_hygiene_state).resolve()
        if getattr(args, "repository_hygiene_state", None)
        else task_file(project_root, "repository_hygiene_state.json")
    )
    hygiene_refs, hygiene_valid = repository_hygiene_refs(hygiene_path) if use_hygiene else ([], False)
    candidate_ref_fetch = (
        fetch_authorized_candidate_refs(project_root, [*routed_task_refs, *hygiene_refs], args.base)
        if args.fetch
        else {
            "skipped": True,
            "reason": "fetch_not_requested",
            "requested_count": 0,
            "fetched_count": 0,
            "invalid_count": 0,
            "unavailable_count": 0,
            "requested": [],
            "fetched": [],
            "invalid": [],
            "unavailable": [],
        }
    )
    scan_prefixes = prefixes if not use_hygiene or not hygiene_valid else ()
    candidates = []
    errors = []
    for ref in list_candidate_refs(project_root, scan_prefixes, args.base, [*routed_task_refs, *hygiene_refs]):
        try:
            candidates.append(collect_candidate(project_root, args.base, ref, tasks_by_id, args.max_paths_per_candidate))
        except subprocess.CalledProcessError as exc:
            errors.append({
                "branch": ref,
                "error": (exc.stderr or exc.stdout or str(exc)).strip(),
            })

    conflicts, omitted_conflicts = collect_conflicts(candidates, args.max_conflicts, args.max_overlap_paths)
    dirty = any(line and not line.startswith("##") for line in status.splitlines())
    stale_candidates = [item for item in candidates if item["behind_base"] > 0]
    missing_task_trace = [item for item in candidates if item["ahead_of_base"] > 0 and not item["task_ids"]]

    blockers = []
    if dirty:
        blockers.append({"code": "dirty_worktree", "severity": "warning", "message": "local worktree has uncommitted changes"})
    for item in stale_candidates:
        blockers.append({"code": "stale_branch", "severity": "warning", "branch": item["branch"], "behind_base": item["behind_base"]})
    for item in missing_task_trace:
        blockers.append({"code": "missing_task_trace", "severity": "warning", "branch": item["branch"]})
    for conflict in conflicts:
        blockers.append({"code": "changed_path_overlap", **conflict})
    for error in errors:
        blockers.append({"code": "candidate_read_error", "severity": "error", **error})
    for item in candidate_ref_fetch.get("invalid") or []:
        blockers.append({"code": "candidate_ref_invalid", "severity": "error", **item})
    for item in candidate_ref_fetch.get("unavailable") or []:
        blockers.append({"code": "candidate_ref_unavailable", "severity": "warning", **item})

    return {
        "project_root": str(project_root),
        "checked_at": utc_now(),
        "base_branch": args.base,
        "base_sha": base_sha,
        "fetch": bool(args.fetch),
        "queue": str(queue_path),
        "locks": str(locks_path),
        "git_status": status.splitlines(),
        "dirty_worktree": dirty,
        "candidate_prefixes": list(prefixes),
        "prefix_scan_enabled": bool(scan_prefixes),
        "repository_hygiene_state": str(hygiene_path) if use_hygiene else None,
        "repository_hygiene_valid": hygiene_valid,
        "repository_hygiene_candidate_count": len(hygiene_refs),
        "candidate_ref_fetch": candidate_ref_fetch,
        "candidate_count": len(candidates),
        "candidates": candidates,
        "integration_task_count": len(integration_tasks),
        "integration_tasks": integration_tasks,
        "integration_task_refs": routed_task_refs,
        "active_lock_count": len(active_locks),
        "active_locks": active_locks,
        "conflict_count": len(conflicts),
        "stored_conflict_count": len(conflicts),
        "omitted_conflict_count": omitted_conflicts,
        "path_conflicts": conflicts,
        "blocker_count": len(blockers),
        "blockers": blockers,
    }


def print_text(report: dict[str, Any]) -> None:
    print(f"project_root: {report['project_root']}")
    print(f"checked_at: {report['checked_at']}")
    print(f"base: {report['base_branch']} {report['base_sha']}")
    print(f"dirty_worktree: {report['dirty_worktree']}")
    print(f"candidates: {report['candidate_count']}")
    print(f"integration_tasks: {report['integration_task_count']}")
    print(f"active_locks: {report['active_lock_count']}")
    print(f"path_conflicts: {report['conflict_count']}")
    print(f"blockers: {report['blocker_count']}")
    for item in report["candidates"]:
        print(f"CANDIDATE {item['branch']} ahead={item['ahead_of_base']} behind={item['behind_base']} paths={item['path_count']} tasks={','.join(item['task_ids']) or '-'}")
    for blocker in report["blockers"]:
        print(f"{blocker['severity'].upper()} {blocker['code']}: {blocker}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect Auto Integrator preflight evidence.")
    parser.add_argument("--project-root", required=True, help="Repository root to inspect.")
    parser.add_argument("--base", default="develop", help="Canonical integration base branch. Defaults to develop.")
    parser.add_argument("--queue", help="Path to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--locks", help="Path to AiStudio/Task_manager/agent_locks.json.")
    parser.add_argument("--prefix", action="append", default=list(DEFAULT_PREFIXES), help="Candidate branch prefix. Can be repeated.")
    parser.add_argument("--fetch", action="store_true", help="Run git fetch --prune origin before collecting evidence.")
    parser.add_argument("--use-repository-hygiene", action="store_true", help="Scan active integration task refs plus repository hygiene salvage refs instead of every managed prefix.")
    parser.add_argument("--repository-hygiene-state", help="Optional repository_hygiene_state.json path.")
    parser.add_argument(
        "--max-paths-per-candidate",
        type=int,
        default=DEFAULT_MAX_PATHS_PER_CANDIDATE,
        help="Maximum changed paths stored per candidate; counters keep the full path count.",
    )
    parser.add_argument("--max-conflicts", type=int, default=DEFAULT_MAX_CONFLICTS, help="Maximum path conflict records stored.")
    parser.add_argument("--max-overlap-paths", type=int, default=DEFAULT_MAX_OVERLAP_PATHS, help="Maximum overlap paths stored per conflict.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    parser.add_argument("--output", help="Optional path to write the JSON report.")
    parser.add_argument("--fail-on-error", action="store_true", help="Return non-zero when error blockers exist.")
    args = parser.parse_args()

    try:
        report = build_report(args)
    except subprocess.CalledProcessError as exc:
        print((exc.stderr or exc.stdout or str(exc)).strip(), file=sys.stderr)
        return 2

    if args.output:
        output_path = Path(args.output).resolve()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_text(report)

    if args.fail_on_error and any(item.get("severity") == "error" for item in report["blockers"]):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
