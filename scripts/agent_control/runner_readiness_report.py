#!/usr/bin/env python3
"""Read-only runner readiness report for AiStudio managed projects.

This checks Phase 2/2.1 gates and worker-ready task shape. It never claims
tasks, writes project files, creates branches, commits, pushes, or runs Codex.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import sys
from typing import Any

import automation_worktree_planner
import codex_host_readiness


RUNTIME = Path("/mnt/d/Codex/agent-runtime/agent-control")
REGISTRY = RUNTIME / "projects.local.json"

REQUIRED_AGENT_RUNTIME_FILES = [
    ".agent/START_HERE.md",
    ".agent/agent_version.json",
    ".agent/worker_profiles.json",
]

COORDINATION_FILE_SETS = [
    [
        ".agent/agents.md",
        ".agent/routing.md",
        ".agent/permissions.md",
    ],
    [
        ".agent/project.md",
        ".agent/modules.md",
        ".agent/workflows.md",
        ".agent/context.json",
    ],
]

REQUIRED_STATE_FILES = {
    "task_queue_path": "task_queue.json",
    "agent_locks_path": "agent_locks.json",
    "owner_directives_path": "owner_directives.json",
    "agent_runner_state_path": "agent_runner_state.json",
    "agent_activity_state_path": "agent_activity_state.json",
}

TASK_REQUIRED_FIELDS = [
    "allowed_paths",
    "forbidden_paths",
    "acceptance_criteria",
    "checks",
    "complexity",
]

RUNNABLE_STATUSES = {"planned", "needs_stronger_agent"}
ACTIVE_LOCK_STATES = {"locked", "in_progress"}
ACTIVE_CLAIM_STATUSES = {"claimed", "agent_working", "in_progress"}
TERMINAL_STATUSES = {
    "approved",
    "cancelled",
    "closed",
    "completed",
    "deprecated",
    "done",
    "finalized",
    "merged",
    "postponed",
    "rejected",
    "stale_or_superseded",
    "superseded",
}


def task_manager_path(root: Path, name: str) -> Path:
    return root / "AiStudio" / "Task_manager" / name


def relative_path(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def state_path(root: Path, cfg: dict[str, Any], key: str, default_name: str) -> Path:
    canonical = task_manager_path(root, default_name)
    configured = cfg.get(key)
    configured_path = root / str(configured) if configured else canonical
    if canonical.exists():
        return canonical
    if configured_path.exists():
        return configured_path
    if configured and str(configured).replace("\\", "/").startswith("docs/plans/") and (root / "AiStudio" / "Task_manager").exists():
        return canonical
    return configured_path


def state_paths(root: Path, cfg: dict[str, Any]) -> dict[str, Path]:
    return {key: state_path(root, cfg, key, name) for key, name in REQUIRED_STATE_FILES.items()}

def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def registry_projects() -> list[dict[str, Any]]:
    data = read_json(REGISTRY)
    if not isinstance(data, dict):
        return []
    projects = data.get("projects", [])
    return [p for p in projects if isinstance(p, dict)]


def load_registry_projects(registry_path: Path) -> list[dict[str, Any]]:
    data = read_json(registry_path)
    if not isinstance(data, dict):
        return []
    projects = data.get("projects", [])
    return [p for p in projects if isinstance(p, dict)]


def load_agent_control_status() -> dict[str, Any]:
    data = read_json(RUNTIME / "status.json")
    if not isinstance(data, dict):
        return {}
    result: dict[str, Any] = {}
    for item in data.get("projects", []):
        if isinstance(item, dict) and item.get("name"):
            result[str(item["name"])] = item
    return result


def load_agent_control_status_from(runtime_root: Path) -> dict[str, Any]:
    data = read_json(runtime_root / "status.json")
    if not isinstance(data, dict):
        return {}
    result: dict[str, Any] = {}
    for item in data.get("projects", []):
        if isinstance(item, dict) and item.get("name"):
            result[str(item["name"])] = item
    return result


def run_git(repo_path: Path, *args: str) -> str | None:
    try:
        proc = subprocess.run(
            ["git", *args],
            cwd=repo_path,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=20,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    return proc.stdout.rstrip("\r\n")


def load_json_from_git_ref(repo_path: Path, git_ref: str, rel_path: str) -> Any:
    ref = str(git_ref or "").strip()
    path = str(rel_path or "").strip()
    if not ref or not path:
        return None
    raw = run_git(repo_path, "show", f"{ref}:{path}")
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def configured_git_ref(cfg: dict[str, Any], specific_key: str = "") -> str:
    if specific_key and cfg.get(specific_key):
        return str(cfg.get(specific_key) or "").strip()
    return str(cfg.get("task_queue_git_ref") or cfg.get("task_queue_github_ref") or cfg.get("base_ref") or cfg.get("base_branch") or "").strip()


def project_command_root(cfg: dict[str, Any]) -> Path:
    return Path(str(cfg.get("automation_path") or cfg.get("local_path") or ""))


def is_git_worktree(path: Path) -> bool:
    value = run_git(path, "rev-parse", "--is-inside-work-tree")
    return str(value or "").strip().lower() == "true"


def command_root_diagnostic(cfg: dict[str, Any], root: Path | None = None) -> dict[str, Any]:
    root = root or project_command_root(cfg)
    git_expected = bool(configured_git_ref(cfg))
    automation_path = str(cfg.get("automation_path") or "").strip()
    github_repo = str(cfg.get("github_repo") or "").strip()
    exists = root.exists()
    is_git = is_git_worktree(root) if exists else False
    blockers: list[str] = []
    recommendation = ""
    if not str(root):
        blockers.append("command_root_missing")
    elif not exists:
        blockers.append("command_root_path_missing")
    elif git_expected and not is_git:
        blockers.append("command_root_not_git_worktree")
        if automation_path:
            recommendation = "Repair or replace automation_path with a valid git worktree before running write-capable automation."
        else:
            recommendation = "Configure automation_path to a git worktree for this project; artifact local_path cannot run worker/integrator/finalizer automation."
    requires_github_access = bool(blockers and git_expected)
    github_access = {"checked": False, "ok": None, "reason": "not_required"}
    if requires_github_access:
        branch = automation_worktree_planner.base_branch(cfg)
        github_access = automation_worktree_planner.remote_access(github_repo, branch)
    return {
        "local_path": str(cfg.get("local_path") or ""),
        "automation_path": automation_path or None,
        "automation_path_configured": bool(automation_path),
        "command_root": str(root),
        "exists": exists,
        "git_expected": git_expected,
        "github_repo": github_repo or None,
        "base_branch": automation_worktree_planner.base_branch(cfg),
        "is_git_worktree": is_git,
        "blockers": blockers,
        "requires_github_access": requires_github_access,
        "github_access": github_access,
        "recommendation": recommendation,
    }


def project_codex_bin(cfg: dict[str, Any]) -> str:
    return str(cfg.get("codex_bin") or cfg.get("worker_codex_bin") or "codex").strip() or "codex"


def project_in_scope(cfg: dict[str, Any]) -> bool:
    if cfg.get("enabled") is False:
        return False
    role = str(cfg.get("role") or "").strip()
    if role:
        return role == "managed_project"
    return bool(str(cfg.get("automation_path") or cfg.get("local_path") or "").strip())


def task_has_free_lock(task: dict[str, Any], active_locked_task_ids: set[str]) -> bool:
    task_id = str(task.get("id", "")).strip()
    if task_id in active_locked_task_ids:
        return False
    lock = task.get("lock")
    if isinstance(lock, dict):
        return lock.get("state") in (None, "free", "released")
    return True


def active_locks(lock_data: Any) -> set[str]:
    if not isinstance(lock_data, dict):
        return set()
    locks = lock_data.get("locks", [])
    if not isinstance(locks, list):
        return set()
    return {
        str(lock.get("task_id"))
        for lock in locks
        if isinstance(lock, dict) and lock.get("state") in {"locked", "in_progress"}
    }


def worker_profiles_enabled(data: Any) -> tuple[bool, list[str]]:
    if not isinstance(data, list):
        return False, []
    enabled = []
    for item in data:
        if isinstance(item, dict) and item.get("enabled"):
            enabled.append(str(item.get("worker_id") or item.get("id") or "<unknown>"))
    return bool(enabled), enabled


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict, tuple, set)):
        return bool(value)
    return True


def missing_static_coordination_files(root: Path) -> list[str]:
    missing = [rel for rel in REQUIRED_AGENT_RUNTIME_FILES if not (root / rel).exists()]
    if any(all((root / rel).exists() for rel in file_set) for file_set in COORDINATION_FILE_SETS):
        return missing
    variants = sorted({rel for file_set in COORDINATION_FILE_SETS for rel in file_set})
    missing.extend(rel for rel in variants if not (root / rel).exists())
    return missing


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def porcelain_path(line: str) -> str:
    text = str(line or "").rstrip()
    if not text:
        return ""
    if " -> " in text:
        text = text.rsplit(" -> ", 1)[1]
    return text[3:].strip() if len(text) > 3 else ""


def readiness_ignored_dirty_path(path: str) -> bool:
    normalized = path.replace("\\", "/").strip()
    if not normalized:
        return True
    return (
        normalized == "runtime"
        or normalized.startswith("runtime/")
        or normalized.startswith("AiStudio/Task_manager/process-logs/")
        or normalized == "AiStudio/Task_manager/reports"
        or normalized.startswith("AiStudio/Task_manager/reports/")
        or normalized.startswith("AiStudio/Task_manager/reports/workers/")
        or normalized == "docs/reports"
        or normalized.startswith("docs/reports/")
        or normalized.startswith("docs/reports/workers/")
        or normalized.startswith("docs/plans/process-logs/")
    )


def meaningful_dirty_lines(porcelain: str) -> list[str]:
    lines = [line for line in str(porcelain or "").splitlines() if line.strip()]
    return [line for line in lines if not readiness_ignored_dirty_path(porcelain_path(line))]


def ahead_behind_base(repo_path: Path, base_ref: str) -> dict[str, Any]:
    ref = str(base_ref or "").strip()
    if not ref:
        return {"checked": False, "reason": "base_ref_missing"}
    verify = run_git(repo_path, "rev-parse", "--verify", "--quiet", ref)
    if verify is None:
        return {"checked": True, "ok": False, "base_ref": ref, "reason": "base_ref_missing"}
    counts = run_git(repo_path, "rev-list", "--left-right", "--count", f"HEAD...{ref}")
    parts = str(counts or "").split()
    if len(parts) != 2:
        return {"checked": True, "ok": False, "base_ref": ref, "reason": "ahead_behind_failed"}
    try:
        ahead = int(parts[0])
        behind = int(parts[1])
    except ValueError:
        return {"checked": True, "ok": False, "base_ref": ref, "reason": "ahead_behind_failed"}
    return {
        "checked": True,
        "ok": ahead == 0 and behind == 0,
        "base_ref": ref,
        "ahead": ahead,
        "behind": behind,
        "reason": "in_sync" if ahead == 0 and behind == 0 else "diverged_from_base",
    }


def task_shape(task: dict[str, Any]) -> dict[str, Any]:
    missing = [field for field in TASK_REQUIRED_FIELDS if field not in task or task.get(field) in (None, "", [])]
    task_id = str(task.get("id", "")).strip()
    blockers = []
    if task.get("worker_ready") is not True:
        blockers.append("worker_ready is not true")
    if task.get("dispatcher_decision") != "worker_ready":
        blockers.append("dispatcher_decision is not worker_ready")
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        blockers.append("current context review is required")
    return {
        "id": task_id,
        "status": task.get("status"),
        "complexity": task.get("complexity"),
        "missing_fields": missing,
        "blockers": blockers,
        "worker_ready": not missing and not blockers,
        "title": task.get("title"),
    }


def parse_time(value: Any) -> dt.datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=dt.timezone.utc)


def expired_queue_lock_ids(queue: Any) -> set[str]:
    if not isinstance(queue, dict):
        return set()
    tasks = queue.get("tasks", [])
    if not isinstance(tasks, list):
        return set()
    now = dt.datetime.now(dt.timezone.utc)
    expired: set[str] = set()
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or "").strip()
        status = str(task.get("status") or "").strip()
        if not task_id or status in TERMINAL_STATUSES:
            continue
        lock = task.get("lock")
        lock_state = str(lock.get("state") or "").strip() if isinstance(lock, dict) else str(lock or "").strip()
        expires_at = parse_time(lock.get("expires_at") if isinstance(lock, dict) else None) or parse_time(task.get("lock_expires_at"))
        if expires_at and expires_at < now and (lock_state in ACTIVE_LOCK_STATES or status in ACTIVE_CLAIM_STATUSES):
            expired.add(task_id)
    return expired


def analyze_project(
    cfg: dict[str, Any],
    status_by_name: dict[str, Any],
    codex_readiness: dict[str, Any] | None = None,
) -> dict[str, Any]:
    project_id = str(cfg.get("project_id", ""))
    name = str(cfg.get("name", project_id))
    root = project_command_root(cfg)
    root_diagnostic = command_root_diagnostic(cfg, root)
    item: dict[str, Any] = {
        "project_id": project_id,
        "name": name,
        "path": str(root),
        "local_path": str(cfg.get("local_path", "")),
        "automation_path": str(cfg.get("automation_path", "")),
        "command_root": root_diagnostic,
        "role": cfg.get("role"),
        "registry_worker_enabled": cfg.get("worker_enabled") is not False,
        "ready": False,
        "gates": [],
        "blockers": [],
        "warnings": [],
        "candidate_tasks": [],
        "candidate_task_count": 0,
        "worker_ready_candidate_count": 0,
        "codex_host_readiness": codex_readiness or {
            "checked": False,
            "ok": None,
            "reason": "not_checked",
            "codex_bin": project_codex_bin(cfg),
        },
        "current_branch": None,
        "dirty": False,
    }

    if not project_in_scope(cfg):
        item["blockers"].append("not a managed project")
        return item
    if not root.exists():
        item["blockers"].append("automation/local path missing")
        return item
    if "command_root_not_git_worktree" in root_diagnostic["blockers"]:
        item["blockers"].append("automation/local path is not a git worktree")
    if is_git_worktree(root):
        item["current_branch"] = run_git(root, "branch", "--show-current")
        porcelain = run_git(root, "status", "--porcelain")
        dirty_lines = meaningful_dirty_lines(str(porcelain or ""))
        item["dirty"] = bool(dirty_lines)
        if str(porcelain or "").strip() and not dirty_lines:
            item["warnings"].append("local worktree has only ignored runtime/process-log/worker-report changes")
        if item["dirty"]:
            item["blockers"].append("local worktree is dirty")
        base_status = ahead_behind_base(root, configured_git_ref(cfg))
        item["base_ref_status"] = base_status
        if base_status.get("checked") and base_status.get("ok") is False:
            reason = str(base_status.get("reason") or "")
            if reason == "base_ref_missing":
                item["warnings"].append("base ref is missing locally")
            elif reason == "ahead_behind_failed":
                item["warnings"].append("base ref ahead/behind check failed")
            else:
                if int(base_status.get("behind") or 0) > 0:
                    item["blockers"].append("local checkout is behind base ref")
                if int(base_status.get("ahead") or 0) > 0:
                    item["blockers"].append("local checkout has unpushed commits")

    paths = state_paths(root, cfg)
    item["state_paths"] = {key: relative_path(root, value) for key, value in paths.items()}
    item["state_sources"] = {
        "task_queue_path": "local" if paths["task_queue_path"].exists() else "missing",
        "agent_locks_path": "local" if paths["agent_locks_path"].exists() else "missing",
    }
    missing_files = missing_static_coordination_files(root)
    missing_files.extend(
        relative_path(root, path)
        for key, path in paths.items()
        if not path.exists() and key not in {"task_queue_path", "agent_locks_path"}
    )
    if missing_files:
        item["blockers"].append("missing runner coordination files")
        item["missing_files"] = missing_files
    else:
        item["gates"].append("required runner files present")

    agent_version = read_json(root / str(cfg.get("agent_version_path", ".agent/agent_version.json")))
    if isinstance(agent_version, dict):
        item["agent_version"] = agent_version.get("agent_version")
        item["phase"] = agent_version.get("phase")
        item["phase2_active"] = bool(agent_version.get("phase2_active"))
        item["phase2_1_policy"] = bool(agent_version.get("phase2_1_policy"))
        if not agent_version.get("phase2_active"):
            item["blockers"].append("phase2_active is not true")
        if not agent_version.get("phase2_1_policy"):
            item["warnings"].append("phase2_1_policy is not true")
    else:
        item["blockers"].append("agent_version.json missing or invalid")

    owner_directives = read_json(paths["owner_directives_path"])
    if isinstance(owner_directives, dict):
        active_directives = [
            d for d in owner_directives.get("directives", [])
            if isinstance(d, dict) and d.get("status") == "active"
        ] if isinstance(owner_directives.get("directives", []), list) else []
        item["active_owner_directives"] = len(active_directives)
        if active_directives:
            item["warnings"].append("active owner directives must be reviewed before worker run")
    else:
        item["blockers"].append("owner_directives.json missing or invalid")

    runner_state = read_json(paths["agent_runner_state_path"])
    if isinstance(runner_state, dict):
        host = runner_state.get("automation_host")
        item["automation_host"] = host if isinstance(host, dict) else None
        if not isinstance(host, dict) or not host.get("enabled"):
            item["blockers"].append("remote automation host is missing or disabled")
        if isinstance(host, dict) and host.get("scheduler_enabled"):
            item["warnings"].append("scheduler_enabled is true; owner should confirm before real runs")
    elif relative_path(root, paths["agent_runner_state_path"]) not in missing_files:
        item["blockers"].append("agent_runner_state.json invalid")

    profiles = read_json(root / ".agent/worker_profiles.json")
    enabled_profiles, profile_ids = worker_profiles_enabled(profiles)
    item["enabled_worker_profiles"] = profile_ids
    if not enabled_profiles:
        item["blockers"].append("no enabled worker profiles")

    queue = read_json(paths["task_queue_path"])
    if queue is None and not paths["task_queue_path"].exists():
        queue_ref = configured_git_ref(cfg)
        queue_rel_path = str(cfg.get("task_queue_github_path") or cfg.get("task_queue_path") or "AiStudio/Task_manager/task_queue.json")
        queue = load_json_from_git_ref(root, queue_ref, queue_rel_path) if queue_ref else None
        if queue is not None:
            item["state_sources"]["task_queue_path"] = "git_ref"
            item["warnings"].append(f"task_queue loaded read-only from {queue_ref}:{queue_rel_path}")
    locks = read_json(paths["agent_locks_path"])
    if locks is None and not paths["agent_locks_path"].exists():
        locks_ref = configured_git_ref(cfg, "agent_locks_git_ref")
        locks_rel_path = str(cfg.get("agent_locks_git_path") or cfg.get("agent_locks_path") or "AiStudio/Task_manager/agent_locks.json")
        locks = load_json_from_git_ref(root, locks_ref, locks_rel_path) if locks_ref else None
        if locks is not None:
            item["state_sources"]["agent_locks_path"] = "git_ref"
            item["warnings"].append(f"agent_locks loaded read-only from {locks_ref}:{locks_rel_path}")
    locked_ids = active_locks(locks)
    item["active_locks"] = len(locked_ids)
    if locked_ids:
        item["blockers"].append("active locks present")
    expired_queue_locks = expired_queue_lock_ids(queue)
    item["expired_queue_locks"] = len(expired_queue_locks)
    if expired_queue_locks:
        item["blockers"].append("expired queue locks present")
        item["expired_queue_lock_task_ids"] = sorted(expired_queue_locks)[:20]

    if isinstance(queue, dict) and isinstance(queue.get("tasks"), list):
        for task in queue["tasks"]:
            if not isinstance(task, dict):
                continue
            if task.get("status") not in RUNNABLE_STATUSES:
                continue
            if not task_has_free_lock(task, locked_ids):
                continue
            shaped = task_shape(task)
            item["candidate_tasks"].append(shaped)
        item["candidate_task_count"] = len(item["candidate_tasks"])
        item["worker_ready_candidate_count"] = sum(1 for t in item["candidate_tasks"] if t["worker_ready"])
        if item["candidate_tasks"] and not item["worker_ready_candidate_count"]:
            item["blockers"].append("candidate tasks exist but are not worker-ready")
        if item["worker_ready_candidate_count"] and enabled_profiles and codex_readiness and codex_readiness.get("ok") is False:
            item["blockers"].append("codex host is not ready")
    else:
        item["blockers"].append("task_queue.json missing or invalid")

    control_status = status_by_name.get(name, {})
    if isinstance(control_status, dict):
        github = control_status.get("github") if isinstance(control_status.get("github"), dict) else {}
        local = control_status.get("local") if isinstance(control_status.get("local"), dict) else {}
        item["open_prs"] = github.get("open_prs")
        item["current_branch"] = local.get("current_branch") or item.get("current_branch")
        local_dirty_applies = bool(local.get("dirty")) and not is_git_worktree(root)
        item["dirty"] = local_dirty_applies or bool(item.get("dirty"))
        if local_dirty_applies and "local worktree is dirty" not in item["blockers"]:
            item["blockers"].append("local worktree is dirty")
        if github.get("open_prs"):
            item["warnings"].append("open PR stack must be considered before worker run")

    if cfg.get("worker_enabled") is False:
        item["blockers"].append("registry worker_enabled is false")

    item["ready"] = not item["blockers"]
    current_run_blockers = list(item["blockers"]) if item["worker_ready_candidate_count"] else []
    item["worker_run_applicable"] = bool(item["worker_ready_candidate_count"])
    item["worker_run_ready"] = bool(item["worker_ready_candidate_count"] and not current_run_blockers)
    item["worker_run_blocked"] = bool(current_run_blockers)
    item["current_worker_run_blockers"] = current_run_blockers
    return item


def worker_lock_preflight(cfg: dict[str, Any], status_by_name: dict[str, Any] | None = None) -> dict[str, Any]:
    item = analyze_project(cfg, status_by_name or {})
    active_locks = int(item.get("active_locks") or 0)
    expired_queue_locks = int(item.get("expired_queue_locks") or 0)
    return {
        "ok": not (active_locks or expired_queue_locks),
        "reason": "no_worker_locks" if not (active_locks or expired_queue_locks) else "worker_locks_present",
        "active_locks": active_locks,
        "expired_queue_locks": expired_queue_locks,
        "expired_queue_lock_task_ids": item.get("expired_queue_lock_task_ids") or [],
        "state_sources": item.get("state_sources") or {},
        "blockers": [
            blocker
            for blocker in item.get("blockers", [])
            if blocker in {"active locks present", "expired queue locks present"}
        ],
    }


def render_md(report: dict[str, Any]) -> str:
    lines = [
        "# Runner Readiness",
        "",
        f"Generated: `{report['generated_at']}`",
        "",
        "Read-only gate report. No agents were started and no project state was changed.",
        "",
        "## Summary",
        "",
    ]
    for key, value in report["summary"].items():
        lines.append(f"- `{key}`: {value}")
    lines.extend(["", "## Projects", ""])

    for project in report["projects"]:
        lines.append(f"### {project['name']}")
        lines.append("")
        lines.append(f"- Ready for worker run: `{project['ready']}`")
        lines.append(f"- Registry worker enabled: `{project.get('registry_worker_enabled')}`")
        lines.append(f"- Agent version: `{project.get('agent_version')}`")
        lines.append(f"- Phase: `{project.get('phase')}`")
        lines.append(f"- Current branch: `{project.get('current_branch')}`")
        lines.append(f"- Dirty: `{project.get('dirty')}`")
        lines.append(f"- Open PRs: `{project.get('open_prs')}`")
        lines.append(f"- Active locks: `{project.get('active_locks')}`")
        lines.append(f"- Candidate tasks: `{project.get('candidate_task_count', 0)}`")
        lines.append(f"- Worker-ready candidates: `{project.get('worker_ready_candidate_count', 0)}`")
        codex_ready = project.get("codex_host_readiness") if isinstance(project.get("codex_host_readiness"), dict) else {}
        lines.append(f"- Codex host: `{codex_ready.get('reason', 'unknown')}`")
        if project.get("enabled_worker_profiles"):
            lines.append(f"- Enabled worker profiles: `{', '.join(project['enabled_worker_profiles'])}`")
        if project.get("blockers"):
            lines.append("")
            lines.append("Blockers:")
            for blocker in project["blockers"]:
                lines.append(f"- `{blocker}`")
        if project.get("warnings"):
            lines.append("")
            lines.append("Warnings:")
            for warning in project["warnings"]:
                lines.append(f"- `{warning}`")
        if project.get("missing_files"):
            lines.append("")
            lines.append("Missing files:")
            for missing in project["missing_files"]:
                lines.append(f"- `{missing}`")
        not_ready = [t for t in project.get("candidate_tasks", []) if not t.get("worker_ready")]
        if not_ready:
            lines.append("")
            lines.append("Non-ready candidate preview:")
            for task in not_ready[:15]:
                missing = ", ".join(task.get("missing_fields", []))
                lines.append(f"- `{task.get('id')}` missing `{missing}`")
        lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only runner readiness report")
    parser.add_argument("--registry", default=str(REGISTRY), help="Project registry JSON")
    parser.add_argument("--runtime-root", default=str(RUNTIME), help="Runtime output directory")
    parser.add_argument("--output", default="", help="Optional JSON output path. Defaults to <runtime-root>/runner-readiness.json")
    args = parser.parse_args(argv)

    runtime_root = Path(args.runtime_root)
    registry_path = Path(args.registry)
    output_path = Path(args.output) if args.output else runtime_root / "runner-readiness.json"
    md_path = output_path.with_suffix(".md")

    status_by_name = load_agent_control_status_from(runtime_root)
    codex_readiness_cache: dict[str, dict[str, Any]] = {}

    def codex_readiness_for(cfg: dict[str, Any]) -> dict[str, Any]:
        codex_bin = project_codex_bin(cfg)
        if codex_bin not in codex_readiness_cache:
            codex_readiness_cache[codex_bin] = codex_host_readiness.codex_host_readiness(codex_bin).to_dict()
        return codex_readiness_cache[codex_bin]

    projects = [
        analyze_project(cfg, status_by_name, codex_readiness_for(cfg))
        for cfg in load_registry_projects(registry_path)
        if project_in_scope(cfg)
    ]
    report = {
        "schema_version": "1.0",
        "generated_at": utc_now(),
        "mode": "read_only",
        "projects": projects,
        "summary": {
            "project_count": len(projects),
            "ready_projects": sum(1 for p in projects if p.get("ready")),
            "blocked_projects": sum(1 for p in projects if not p.get("ready")),
            "worker_run_applicable_projects": sum(1 for p in projects if p.get("worker_run_applicable")),
            "worker_run_ready_projects": sum(1 for p in projects if p.get("worker_run_ready")),
            "worker_run_blocked_projects": sum(1 for p in projects if p.get("worker_run_blocked")),
            "no_worker_candidate_projects": sum(1 for p in projects if not p.get("worker_run_applicable")),
            "candidate_tasks": sum(int(p.get("candidate_task_count") or 0) for p in projects if p.get("ready")),
            "worker_ready_candidates": sum(int(p.get("worker_ready_candidate_count") or 0) for p in projects if p.get("ready")),
            "raw_candidate_tasks": sum(int(p.get("candidate_task_count") or 0) for p in projects),
            "raw_worker_ready_candidates": sum(int(p.get("worker_ready_candidate_count") or 0) for p in projects),
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(render_md(report), encoding="utf-8")
    print(json.dumps({"ok": True, **report["summary"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
