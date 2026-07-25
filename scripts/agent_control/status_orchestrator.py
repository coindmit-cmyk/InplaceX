#!/usr/bin/env python3
"""Status-driven automation orchestrator for one project."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import stat
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from event_driven_scheduler import consume_events, update_activity
import llm_dispatch_tagger
from process_log import append_log
from project_paths import task_manager_dir

from evaluate_finalizer_merge_gate import evaluate_gate, run_validate


PROCESS_BY_ROLE = {
    "auto_architect": "architect",
    "auto_dispatcher": "dispatcher",
    "auto_workers": "worker_pool",
    "auto_integrator": "integrator",
    "auto_finalizer": "finalizer",
    "remote_automation_host": "local_llm",
}
HANDOFF_RETRY_COUNT = int(os.environ.get("STATUS_ORCHESTRATOR_HANDOFF_RETRY_COUNT", "2") or "0")
HANDOFF_RETRY_DELAY_SECONDS = float(os.environ.get("STATUS_ORCHESTRATOR_HANDOFF_RETRY_DELAY_SECONDS", "15") or "0")
WORKER_WORKTREE_MAX_BYTES = int(os.environ.get("STATUS_ORCHESTRATOR_WORKER_WORKTREE_MAX_BYTES", str(5 * 1024 * 1024 * 1024)) or "0")
WORKER_WORKTREE_MIN_FREE_BYTES = int(os.environ.get("STATUS_ORCHESTRATOR_WORKER_WORKTREE_MIN_FREE_BYTES", str(3 * 1024 * 1024 * 1024)) or "0")
WORKER_WORKTREE_STALE_SECONDS = int(
    os.environ.get("STATUS_ORCHESTRATOR_WORKER_WORKTREE_STALE_SECONDS", str(24 * 60 * 60)) or "0"
)
ACTIVE_WORKER_LOCK_STATES = {
    "active",
    "claimed",
    "in_progress",
    "locked",
    "running",
    "worker_claimed",
}
FULL_INTAKE_RUNTIME_MAX_FILES = int(os.environ.get("STATUS_ORCHESTRATOR_FULL_INTAKE_MAX_FILES", "500") or "0")
FULL_INTAKE_RUNTIME_MAX_BYTES = int(os.environ.get("STATUS_ORCHESTRATOR_FULL_INTAKE_MAX_BYTES", str(2 * 1024 * 1024 * 1024)) or "0")
FULL_INTAKE_RUNTIME_GRACE_SECONDS = int(os.environ.get("STATUS_ORCHESTRATOR_FULL_INTAKE_GRACE_SECONDS", "900") or "0")
PROJECT_RULES_SCAN_INTERVAL_MINUTES = int(
    os.environ.get("STATUS_ORCHESTRATOR_PROJECT_RULES_SCAN_INTERVAL_MINUTES", "360") or "0"
)
STATE_SYNC_RUN_CLASSES = {
    "architect_plan",
    "dispatcher_plan",
    "finalization_run",
    "integration_run",
    "integrator_review",
    "local_llm_cycle",
    "rebuild_route",
    "worker_run",
}
STATE_SYNC_PATHS = [
    "AiStudio/Task_manager/agent_activity_state.json",
    "AiStudio/Task_manager/agent_events.jsonl",
    "AiStudio/Task_manager/agent_locks.json",
    "AiStudio/Task_manager/agent_process_state.json",
    "AiStudio/Task_manager/allowed_paths_repair_plan.json",
    "AiStudio/Task_manager/auto_finalizer_merge.json",
    "AiStudio/Task_manager/automation_status.json",
    "AiStudio/Task_manager/automation_bridge_state.json",
    "AiStudio/Task_manager/candidate_salvage_audit.json",
    "AiStudio/Task_manager/clean_rebuild_plan.json",
    "AiStudio/Task_manager/clean_rebuild_queue_bridge_report.json",
    "AiStudio/Task_manager/clean_rebuild_route_events.json",
    "AiStudio/Task_manager/dispatcher_rebuild_plan.json",
    "AiStudio/Task_manager/integration_batch.json",
    "AiStudio/Task_manager/integration_batch.routed.json",
    "AiStudio/Task_manager/integration_candidates.batch.json",
    "AiStudio/Task_manager/integration_candidates.json",
    "AiStudio/Task_manager/integration_handoff.json",
    "AiStudio/Task_manager/integration_pr_snapshot_latest.json",
    "AiStudio/Task_manager/integrator_direct_merge.json",
    "AiStudio/Task_manager/integrator_llm_advice.json",
    "AiStudio/Task_manager/integrator_llm_advice.validation.json",
    "AiStudio/Task_manager/integrator_llm_context.json",
    "AiStudio/Task_manager/integrator_preflight.json",
    "AiStudio/Task_manager/llm_parallel_debug/prompts",
    "AiStudio/Task_manager/llm_parallel_debug/responses",
    "AiStudio/Task_manager/local_llm_dispatch_policy.json",
    "AiStudio/Task_manager/loop_agent_orchestrator.json",
    "AiStudio/Task_manager/owner_directives.json",
    "AiStudio/Task_manager/pr_readiness_report.identity_filtered.json",
    "AiStudio/Task_manager/pr_readiness_report.json",
    "AiStudio/Task_manager/pre_integrator_repair.json",
    "AiStudio/Task_manager/process_locks.json",
    "AiStudio/Task_manager/provisional_crb_tasks.json",
    "AiStudio/Task_manager/rebuild_decision_report.json",
    "AiStudio/Task_manager/repository_hygiene_state.json",
    "AiStudio/Task_manager/route_rebuild_and_integration_results.json",
    "AiStudio/Task_manager/route_integration_results.json",
    "AiStudio/Task_manager/task_identity_audit.json",
    "AiStudio/Task_manager/task_queue.json",
    "AiStudio/Task_manager/worker_candidates.json",
    "AiStudio/Task_manager/worker_pool_last_plan.json",
    "AiStudio/Project_state/intake/inbox",
    "AiStudio/Project_state/indexes",
    "docs/reports/change-intake/pr-cycle",
    "docs/reports/workers",
]
WORKER_REPORT_SYNC_ROOT = "docs/reports/workers"
IGNORED_TRACKED_RUNTIME_PATHS = [
    "AiStudio/Task_manager/process-logs",
    "AiStudio/Task_manager/reports",
    "AiStudio/Task_manager/backups",
]
TRANSIENT_PROCESS_LOGS_PATH = "AiStudio/Task_manager/process-logs"
DISCARDABLE_TRACKED_RUNTIME_PATHS = [
    "AiStudio/Task_manager/reports",
    "AiStudio/Task_manager/backups",
]
NOOP_INTEGRATOR_SCAN_TRANSIENT_PATHS = [
    "AiStudio/Project_state/indexes/current_summary.md",
    "AiStudio/Task_manager/agent_activity_state.json",
    "AiStudio/Task_manager/agent_process_state.json",
    "AiStudio/Task_manager/allowed_paths_repair_plan.json",
    "AiStudio/Task_manager/auto_finalizer_merge.json",
    "AiStudio/Task_manager/automation_status.json",
    "AiStudio/Task_manager/automation_bridge_state.json",
    "AiStudio/Task_manager/candidate_salvage_audit.json",
    "AiStudio/Task_manager/clean_rebuild_plan.json",
    "AiStudio/Task_manager/clean_rebuild_queue_bridge_report.json",
    "AiStudio/Task_manager/clean_rebuild_route_events.json",
    "AiStudio/Task_manager/dispatcher_rebuild_plan.json",
    "AiStudio/Task_manager/integration_batch.json",
    "AiStudio/Task_manager/integration_batch.routed.json",
    "AiStudio/Task_manager/integration_candidates.batch.json",
    "AiStudio/Task_manager/integration_candidates.json",
    "AiStudio/Task_manager/integration_handoff.json",
    "AiStudio/Task_manager/integrator_llm_advice.json",
    "AiStudio/Task_manager/integrator_llm_advice.validation.json",
    "AiStudio/Task_manager/integrator_llm_context.json",
    "AiStudio/Task_manager/integrator_preflight.json",
    "AiStudio/Task_manager/loop_agent_orchestrator.json",
    "AiStudio/Task_manager/pr_readiness_report.identity_filtered.json",
    "AiStudio/Task_manager/pr_readiness_report.json",
    "AiStudio/Task_manager/pre_integrator_repair.json",
    "AiStudio/Task_manager/provisional_crb_tasks.json",
    "AiStudio/Task_manager/rebuild_decision_report.json",
    "AiStudio/Task_manager/route_rebuild_and_integration_results.json",
    "AiStudio/Task_manager/route_integration_results.json",
    "AiStudio/Task_manager/task_identity_audit.json",
]
PRE_APPLY_RECOVERABLE_STATE_PATHS = {
    "AiStudio/Task_manager/allowed_paths_repair_plan.json",
    "AiStudio/Task_manager/auto_finalizer_merge.json",
    "AiStudio/Task_manager/automation_status.json",
    "AiStudio/Task_manager/candidate_salvage_audit.json",
    "AiStudio/Task_manager/clean_rebuild_plan.json",
    "AiStudio/Task_manager/clean_rebuild_queue_bridge_report.json",
    "AiStudio/Task_manager/clean_rebuild_route_events.json",
    "AiStudio/Task_manager/dispatcher_rebuild_plan.json",
    "AiStudio/Task_manager/integration_batch.json",
    "AiStudio/Task_manager/integration_batch.routed.json",
    "AiStudio/Task_manager/integration_handoff.json",
    "AiStudio/Task_manager/integrator_llm_advice.json",
    "AiStudio/Task_manager/integrator_llm_advice.validation.json",
    "AiStudio/Task_manager/integrator_llm_context.json",
    "AiStudio/Task_manager/loop_agent_orchestrator.json",
    "AiStudio/Task_manager/provisional_crb_tasks.json",
    "AiStudio/Task_manager/rebuild_decision_report.json",
    "AiStudio/Task_manager/route_rebuild_and_integration_results.json",
    "AiStudio/Task_manager/route_integration_results.json",
    "AiStudio/Task_manager/worker_pool_last_plan.json",
}
PRE_APPLY_RECOVERABLE_STATE_FILE_PREFIXES = (
    "AiStudio/Project_state/intake/inbox/",
)
PRE_APPLY_QUARANTINABLE_GENERATED_PATHS = {
    "AiStudio/Task_manager/integration_pr_snapshot_latest.json",
}
PRE_APPLY_TRACKED_RECOVERABLE_STATE_PATHS = {
    "AiStudio/Task_manager/integration_pr_snapshot_latest.json",
}
PRE_APPLY_INTERRUPTED_WRITER_EXCLUDED_PATHS = {
    "AiStudio/Task_manager/owner_directives.json",
}
PRE_APPLY_WRITER_MTIME_SKEW_SECONDS = 5
GIT_REQUIRED_RUN_CLASSES = {
    "finalization_run",
    "integration_run",
    "integrator_review",
    "rebuild_route",
    "worker_run",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def referenced_worker_report_paths(project_root: Path) -> set[str]:
    queue = load_json(task_manager_dir(project_root) / "task_queue.json")
    reports: set[str] = set()
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        current_task_id = str(task.get("id") or task.get("task_id") or "").strip().upper()
        if not current_task_id:
            continue
        values: list[Any] = [task.get("worker_report")]
        imported = task.get("imported_worker_reports")
        if isinstance(imported, list):
            values.extend(imported)
        for value in values:
            normalized = str(value or "").replace("\\", "/").strip()
            if (
                normalized.startswith(f"{WORKER_REPORT_SYNC_ROOT}/")
                and current_task_id in normalized.upper()
            ):
                reports.add(normalized)
    return reports


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def pid_is_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def status_orchestrator_peer_pids(project_root: Path) -> list[int]:
    if os.name != "posix":
        return []
    proc = subprocess.run(
        ["ps", "-eo", "pid=,cmd="],
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        return []
    project_text = str(project_root)
    current_pid = os.getpid()
    pids: list[int] = []
    for raw in proc.stdout.splitlines():
        line = raw.strip()
        if not line:
            continue
        pid_text, _, cmd = line.partition(" ")
        try:
            pid = int(pid_text)
        except ValueError:
            continue
        if pid == current_pid:
            continue
        if is_status_orchestrator_process_command(cmd, project_text):
            pids.append(pid)
    return pids


def is_status_orchestrator_process_command(command: str, project_text: str) -> bool:
    if "status_orchestrator.py" not in command or project_text not in command:
        return False
    if "automation_progress_wrapper.py" in command:
        return False
    return True


def process_lock_has_live_process(lock: dict[str, Any], project_root: Path) -> bool | None:
    pid = lock.get("pid")
    if isinstance(pid, int):
        return pid_is_alive(pid)
    if isinstance(pid, str) and pid.isdigit():
        return pid_is_alive(int(pid))
    if lock.get("process") == "status_orchestrator":
        peers = status_orchestrator_peer_pids(project_root)
        return bool(peers)
    return None


def acquire_process_lock(project_root: Path, process: str, run_id: str, ttl_minutes: int) -> tuple[bool, str | None]:
    path = task_manager_dir(project_root) / "process_locks.json"
    data = load_json(path) or {"schema_version": 1, "locks": []}
    locks = data.setdefault("locks", [])
    now = datetime.now(timezone.utc)
    for lock in locks if isinstance(locks, list) else []:
        if not isinstance(lock, dict):
            continue
        if lock.get("process") != process or lock.get("state") != "active":
            continue
        expires_at = parse_time(lock.get("expires_at"))
        if expires_at and expires_at <= now:
            lock["state"] = "expired"
            lock["expired_at"] = utc_now()
            continue
        live_process = process_lock_has_live_process(lock, project_root)
        if live_process is False:
            lock["state"] = "released"
            lock["released_at"] = utc_now()
            lock["release_reason"] = "dead_process"
            lock["released_by"] = "status_orchestrator"
            continue
        return False, str(lock.get("run_id") or "unknown")
    expires = (now + timedelta(minutes=ttl_minutes)).isoformat(timespec="seconds").replace("+00:00", "Z")
    locks.append({
        "process": process,
        "state": "active",
        "by": "status_orchestrator",
        "at": utc_now(),
        "expires_at": expires,
        "run_id": run_id,
        "pid": os.getpid(),
        "host": socket.gethostname(),
        "project_root": str(project_root),
    })
    data["updated_at"] = utc_now()
    write_json(path, data)
    return True, None


def release_process_lock(project_root: Path, process: str, run_id: str) -> None:
    path = task_manager_dir(project_root) / "process_locks.json"
    data = load_json(path)
    locks = data.get("locks", []) if isinstance(data, dict) else []
    for lock in locks if isinstance(locks, list) else []:
        if isinstance(lock, dict) and lock.get("process") == process and lock.get("run_id") == run_id and lock.get("state") == "active":
            lock["state"] = "released"
            lock["released_at"] = utc_now()
    if isinstance(data, dict):
        data["updated_at"] = utc_now()
        write_json(path, data)


def run(cmd: list[str]) -> tuple[int, str, str]:
    proc = subprocess.run(cmd, text=True, capture_output=True)
    return proc.returncode, proc.stdout, proc.stderr


def dir_size_bytes(path: Path) -> int:
    total = 0
    if not path.exists():
        return 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            try:
                total += (Path(root) / name).stat().st_size
            except OSError:
                continue
    return total


def path_is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def worker_worktree_process_commands(
    worktrees_root: Path,
    *,
    process_commands: list[str] | None = None,
    proc_root: Path | None = None,
) -> list[str]:
    raw_root = worktrees_root.expanduser().absolute()
    canonical_root = raw_root.resolve(strict=False)
    needles = {str(raw_root), str(canonical_root)}

    if process_commands is None:
        if os.name != "posix":
            return []
        proc = subprocess.run(
            ["ps", "-eo", "cmd="],
            text=True,
            capture_output=True,
            check=False,
        )
        process_commands = proc.stdout.splitlines() if proc.returncode == 0 else []

    matches = {
        line.strip()
        for line in process_commands
        if line.strip() and any(needle in line for needle in needles)
    }
    if os.name != "posix" and proc_root is None:
        return sorted(matches)

    proc_root = proc_root or Path("/proc")
    try:
        pid_dirs = list(proc_root.iterdir())
    except OSError:
        pid_dirs = []
    for pid_dir in pid_dirs:
        if not pid_dir.name.isdigit():
            continue
        try:
            cwd = Path(os.readlink(pid_dir / "cwd")).resolve(strict=False)
        except OSError:
            continue
        if not path_is_within(cwd, canonical_root):
            continue
        try:
            command = (pid_dir / "cmdline").read_bytes().replace(b"\0", b" ").decode(
                "utf-8", errors="replace"
            ).strip()
        except OSError:
            command = ""
        matches.add(f"pid={pid_dir.name} cwd={cwd} command={command}".strip())
    return sorted(matches)


def active_worker_lock_evidence(runtime: Path) -> list[dict[str, str]]:
    evidence: list[dict[str, str]] = []
    managed_root = runtime.resolve(strict=False) / "managed-checkouts"
    if not managed_root.is_dir():
        return evidence
    for checkout in sorted(managed_root.iterdir(), key=lambda item: item.name):
        locks_path = checkout / "AiStudio" / "Task_manager" / "agent_locks.json"
        data = load_json(locks_path)
        locks = data.get("locks", []) if isinstance(data, dict) else []
        for lock in locks if isinstance(locks, list) else []:
            if (
                not isinstance(lock, dict)
                or str(lock.get("state", "")).strip().lower() not in ACTIVE_WORKER_LOCK_STATES
            ):
                continue
            evidence.append(
                {
                    "project": checkout.name,
                    "task_id": str(lock.get("task_id") or ""),
                    "worker": str(lock.get("by") or lock.get("worker_id") or ""),
                }
            )
    return evidence


def pid_is_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        import ctypes

        process_query_limited_information = 0x1000
        handle = ctypes.windll.kernel32.OpenProcess(  # type: ignore[attr-defined]
            process_query_limited_information,
            False,
            pid,
        )
        if not handle:
            return False
        ctypes.windll.kernel32.CloseHandle(handle)  # type: ignore[attr-defined]
        return True
    try:
        os.kill(pid, 0)
    except (OSError, ValueError):
        return False
    return True


def active_worker_launch_evidence(runtime: Path, worktrees_root: Path) -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    canonical_root = worktrees_root.resolve(strict=False)
    runs_root = runtime.resolve(strict=False) / "runs"
    if not runs_root.is_dir():
        return evidence
    for launch_path in sorted(runs_root.glob("*/*/launch.json")):
        launch = load_json(launch_path)
        if not isinstance(launch, dict):
            continue
        try:
            pid = int(launch.get("pid") or 0)
        except (TypeError, ValueError):
            continue
        worktree_value = str(launch.get("worktree") or "").strip()
        if not worktree_value or not pid_is_alive(pid):
            continue
        worktree = Path(worktree_value).expanduser().resolve(strict=False)
        if not path_is_within(worktree, canonical_root):
            continue
        evidence.append(
            {
                "task_id": str(launch.get("task_id") or ""),
                "worker_id": str(launch.get("worker_id") or ""),
                "pid": pid,
                "worktree": str(worktree),
                "launch": str(launch_path),
            }
        )
    return evidence


def worker_worktree_candidates(worktrees_root: Path) -> list[Path]:
    candidates: set[Path] = set()
    try:
        git_markers = worktrees_root.rglob(".git")
        for marker in git_markers:
            candidates.add(marker.parent)
    except OSError:
        return []
    return sorted(candidates, key=lambda item: str(item))


def worker_worktree_is_clean(path: Path) -> tuple[bool, str]:
    proc = subprocess.run(
        ["git", "-C", str(path), "status", "--porcelain"],
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        return False, "git_status_failed"
    if proc.stdout.strip():
        return False, "dirty_worktree"
    return True, "clean"


def remove_readonly_path(function: Any, path: str, _exc_info: Any) -> None:
    os.chmod(path, stat.S_IWRITE)
    function(path)


def cleanup_runtime_worker_worktrees(runtime_root: str) -> dict[str, Any]:
    runtime = Path(runtime_root).expanduser().resolve(strict=False)
    worktrees_root = (runtime / "worker-worktrees").resolve(strict=False)
    if not worktrees_root.exists():
        return {"skipped": True, "ok": True, "reason": "worker_worktrees_missing", "root": str(worktrees_root)}
    if not worktrees_root.is_dir():
        return {"skipped": True, "ok": False, "reason": "worker_worktrees_not_directory", "root": str(worktrees_root)}

    active_commands = worker_worktree_process_commands(worktrees_root)
    if active_commands:
        return {
            "skipped": True,
            "ok": True,
            "reason": "active_worker_worktree_processes",
            "root": str(worktrees_root),
            "active_process_count": len(active_commands),
        }
    active_launches = active_worker_launch_evidence(runtime, worktrees_root)
    if active_launches:
        return {
            "skipped": True,
            "ok": True,
            "reason": "active_worker_launches",
            "root": str(worktrees_root),
            "active_launch_count": len(active_launches),
            "active_launches": active_launches,
        }
    active_locks = active_worker_lock_evidence(runtime)
    if active_locks:
        return {
            "skipped": True,
            "ok": True,
            "reason": "active_worker_locks",
            "root": str(worktrees_root),
            "active_lock_count": len(active_locks),
            "active_locks": active_locks,
        }

    total_bytes = dir_size_bytes(worktrees_root)
    try:
        usage = shutil.disk_usage(runtime if runtime.exists() else worktrees_root)
        free_bytes = int(usage.free)
    except OSError:
        free_bytes = None
    disk_pressure = (
        (WORKER_WORKTREE_MAX_BYTES > 0 and total_bytes >= WORKER_WORKTREE_MAX_BYTES)
        or (free_bytes is not None and WORKER_WORKTREE_MIN_FREE_BYTES > 0 and free_bytes <= WORKER_WORKTREE_MIN_FREE_BYTES)
    )
    if not disk_pressure:
        return {
            "skipped": True,
            "ok": True,
            "reason": "no_disk_pressure",
            "root": str(worktrees_root),
            "bytes": total_bytes,
            "free_bytes": free_bytes,
        }

    removed: list[dict[str, Any]] = []
    protected: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    now = time.time()
    for child in worker_worktree_candidates(worktrees_root):
        try:
            age_seconds = max(0, int(now - child.stat().st_mtime))
        except OSError as exc:
            errors.append({"path": str(child), "error": str(exc)})
            continue
        if age_seconds < WORKER_WORKTREE_STALE_SECONDS:
            protected.append({"path": str(child), "reason": "grace_period", "age_seconds": age_seconds})
            continue
        clean, clean_reason = worker_worktree_is_clean(child)
        if not clean:
            protected.append({"path": str(child), "reason": clean_reason, "age_seconds": age_seconds})
            continue
        child_bytes = dir_size_bytes(child)
        try:
            shutil.rmtree(child, onerror=remove_readonly_path)
            removed.append({"path": str(child), "bytes": child_bytes, "age_seconds": age_seconds})
        except OSError as exc:
            errors.append({"path": str(child), "error": str(exc)})
    for parent in sorted(
        (path for path in worktrees_root.rglob("*") if path.is_dir()),
        key=lambda item: len(item.parts),
        reverse=True,
    ):
        try:
            parent.rmdir()
        except OSError:
            continue

    return {
        "skipped": False,
        "ok": not errors,
        "reason": "worker_worktrees_pruned_for_disk_pressure",
        "root": str(worktrees_root),
        "bytes_before": total_bytes,
        "free_bytes_before": free_bytes,
        "removed_count": len(removed),
        "removed": removed,
        "protected_count": len(protected),
        "protected": protected,
        "errors": errors,
    }


def cleanup_full_intake_runtime_output(
    runtime_root: str,
    *,
    project_root: Path | None = None,
    max_files: int = FULL_INTAKE_RUNTIME_MAX_FILES,
    max_bytes: int = FULL_INTAKE_RUNTIME_MAX_BYTES,
    grace_seconds: int = FULL_INTAKE_RUNTIME_GRACE_SECONDS,
) -> dict[str, Any]:
    runtime = Path(runtime_root).expanduser().resolve()
    output_dir = full_intake_runtime_output_dir(runtime_root, project_root)
    root = output_dir / "project-rules" / "discovery"
    if not root.exists():
        return {"skipped": True, "ok": True, "reason": "full_intake_discovery_missing", "root": str(root)}
    if not root.is_dir() or runtime not in root.parents:
        return {"skipped": True, "ok": False, "reason": "unsafe_full_intake_discovery_root", "root": str(root)}

    files: list[tuple[Path, int, int]] = []
    for path in root.rglob("*"):
        try:
            if path.is_file() and not path.is_symlink():
                stat = path.stat()
                files.append((path, int(stat.st_size), int(stat.st_mtime_ns)))
        except OSError:
            continue
    files.sort(key=lambda item: (item[2], str(item[0])), reverse=True)
    before_bytes = sum(item[1] for item in files)
    cutoff_ns = int((time.time() - max(0, grace_seconds)) * 1_000_000_000)
    protected = [item for item in files if item[2] >= cutoff_ns]
    older = [item for item in files if item[2] < cutoff_ns]
    kept = list(protected)
    kept_bytes = sum(item[1] for item in kept)
    removed: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    for path, size, mtime_ns in older:
        within_count = max_files <= 0 or len(kept) < max_files
        within_bytes = max_bytes <= 0 or kept_bytes + size <= max_bytes
        if within_count and within_bytes:
            kept.append((path, size, mtime_ns))
            kept_bytes += size
            continue
        try:
            path.unlink(missing_ok=True)
            removed.append({"path": str(path), "bytes": size})
        except OSError as exc:
            errors.append({"path": str(path), "error": str(exc)})
    return {
        "skipped": not removed and not errors,
        "ok": not errors,
        "reason": "full_intake_runtime_retention_applied" if removed else "full_intake_runtime_within_retention",
        "root": str(root),
        "limits": {"max_files": max_files, "max_bytes": max_bytes, "grace_seconds": grace_seconds},
        "before_files": len(files),
        "before_bytes": before_bytes,
        "protected_files": len(protected),
        "kept_files": len(kept),
        "kept_bytes": kept_bytes,
        "removed_count": len(removed),
        "removed_bytes": sum(item["bytes"] for item in removed),
        "errors": errors,
    }


def branch_name_from_ref(ref: str) -> str:
    value = str(ref or "").strip()
    for prefix in ("refs/remotes/origin/", "refs/heads/", "origin/"):
        if value.startswith(prefix):
            return value[len(prefix):]
    if value.startswith("refs/remotes/"):
        parts = value.split("/", 3)
        if len(parts) == 4:
            return parts[3]
    return value or "develop"


def finalizer_merge_command(project_root: Path, *, base_ref: str, fetch: bool, apply: bool, handoff: Path | None = None) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("auto_finalizer_merge.py")),
        "--project-root",
        str(project_root),
        "--base-branch",
        branch_name_from_ref(base_ref),
        "--json",
    ]
    if handoff is not None:
        cmd.extend(["--handoff", str(handoff)])
    if fetch:
        cmd.append("--fetch")
    if apply:
        cmd.append("--apply")
    return cmd


def git_run(project_root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=project_root, text=True, capture_output=True, check=False)


def github_repo_slug(project_root: Path, fallback: str = "") -> str:
    proc = git_run(project_root, ["config", "--get", "remote.origin.url"])
    value = str(proc.stdout or "").strip() if proc.returncode == 0 else ""
    if value.startswith("git@github.com:"):
        value = value.removeprefix("git@github.com:")
    elif "github.com/" in value:
        value = value.split("github.com/", 1)[1]
    value = value.removesuffix(".git").strip("/")
    return value if "/" in value else fallback


def is_git_worktree(project_root: Path) -> bool:
    proc = git_run(project_root, ["rev-parse", "--is-inside-work-tree"])
    return proc.returncode == 0 and proc.stdout.strip().lower() == "true"


def safe_ref_slug(ref: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", ref).strip("-") or "package"


def full_intake_runtime_output_dir(runtime_root: str, project_root: Path | None = None) -> Path:
    root = Path(runtime_root).expanduser().resolve() / "full-intake-cycle"
    if project_root is None:
        return root / "status-orchestrator"
    project = Path(project_root).resolve()
    try:
        project_id = str(load_json(project / "PROJECT_VERSION.json").get("project_id") or "").strip()
    except (OSError, json.JSONDecodeError):
        project_id = ""
    scope = safe_ref_slug(project_id or project.name)
    return root / scope / "status-orchestrator"


def project_rules_scan_gate(
    runtime_root: str,
    project_root: Path,
    *,
    interval_minutes: int = PROJECT_RULES_SCAN_INTERVAL_MINUTES,
    current_time: float | None = None,
) -> dict[str, Any]:
    rules_output = full_intake_runtime_output_dir(runtime_root, project_root) / "project-rules"
    artifacts = sorted(
        rules_output.glob("PROJECT_RULES_UPDATE_CYCLE_*.json"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    latest = artifacts[0] if artifacts else None
    now = time.time() if current_time is None else current_time
    latest_mtime = latest.stat().st_mtime if latest is not None else None
    age_seconds = max(0, int(now - latest_mtime)) if latest_mtime is not None else None
    interval_seconds = max(0, int(interval_minutes)) * 60
    due = latest is None or interval_seconds == 0 or (age_seconds is not None and age_seconds >= interval_seconds)
    return {
        "due": due,
        "reason": "no_previous_scan" if latest is None else "interval_elapsed" if due else "fresh_cached_scan",
        "interval_minutes": max(0, int(interval_minutes)),
        "latest_artifact": str(latest) if latest is not None else None,
        "age_seconds": age_seconds,
    }


def open_integrator_pr_refs(project_root: Path) -> set[str] | None:
    try:
        proc = subprocess.run(
            ["gh", "pr", "list", "--state", "open", "--json", "headRefName", "--limit", "100"],
            cwd=project_root,
            text=True,
            capture_output=True,
            check=False,
        )
    except (FileNotFoundError, OSError):
        return None
    if proc.returncode != 0:
        return None
    try:
        items = json.loads(proc.stdout or "[]")
    except json.JSONDecodeError:
        return None
    refs: set[str] = set()
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        head = str(item.get("headRefName") or "").strip()
        if head.startswith(("integrator/auto-integrator/", "AiStudio/Agent/integrator/")):
            refs.add(head)
            refs.add(f"origin/{head}")
    return refs


def handoff_auto_finalizable(data: dict[str, Any], base_branch: str) -> tuple[bool, list[dict[str, Any]]]:
    validation_issues = run_validate(data)
    hard_errors = [issue for issue in validation_issues if issue.get("severity") == "error"]
    decision, gate_issues = evaluate_gate(data, base_branch)
    issues: list[dict[str, Any]] = [*gate_issues]
    issues.extend({"task": item.get("path", "handoff"), "reason": item.get("message", "")} for item in hard_errors)
    return decision == "auto_merge_to_develop" and not hard_errors, issues


def discover_unmerged_integrator_handoff(project_root: Path, *, base_ref: str, fetch: bool) -> dict[str, Any] | None:
    if fetch:
        git_run(project_root, ["fetch", "origin", "--prune"])
    refs = git_run(
        project_root,
        [
            "for-each-ref",
            "--sort=-committerdate",
            "--format=%(refname:short)",
            "refs/remotes/origin/integrator/auto-integrator",
            "refs/remotes/origin/AiStudio/Agent/integrator",
        ],
    )
    if refs.returncode != 0:
        return None
    open_refs = open_integrator_pr_refs(project_root)
    if not open_refs:
        return None
    for ref in [line.strip() for line in refs.stdout.splitlines() if line.strip()]:
        if ref not in open_refs:
            continue
        merged = git_run(project_root, ["merge-base", "--is-ancestor", ref, base_ref])
        if merged.returncode == 0:
            continue
        show = git_run(project_root, ["show", f"{ref}:AiStudio/Task_manager/integration_handoff.json"])
        if show.returncode != 0 or not show.stdout.strip():
            continue
        try:
            data = json.loads(show.stdout)
        except json.JSONDecodeError:
            continue
        ready = data.get("ready_to_finalize")
        if not isinstance(ready, list) or not ready:
            continue
        package_branch = str(data.get("package_branch") or "")
        if not package_branch:
            continue
        base_branch = str(data.get("base_branch") or "develop")
        mergeable, _issues = handoff_auto_finalizable(data, base_branch)
        if not mergeable:
            continue
        handoff_root = Path("/tmp") / "aistudio-finalizer-handoffs"
        handoff_root.mkdir(parents=True, exist_ok=True)
        sha_proc = git_run(project_root, ["rev-parse", "--short=12", ref])
        sha = sha_proc.stdout.strip() if sha_proc.returncode == 0 else "unknown"
        handoff_path = handoff_root / f"{project_root.name}-{safe_ref_slug(ref)}-{sha}.json"
        handoff_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return {"ref": ref, "handoff": str(handoff_path), "ready_to_finalize": ready, "package_branch": package_branch}
    return None


def local_llm_pre_worker_candidate(project_root: Path) -> dict[str, Any] | None:
    queue_path = task_manager_dir(project_root) / "task_queue.json"
    policy_path = task_manager_dir(project_root) / "local_llm_dispatch_policy.json"
    queue = load_json(queue_path)
    policy = load_json(policy_path)
    settings = policy.get("pre_worker") if isinstance(policy.get("pre_worker"), dict) else {}
    if settings.get("enabled") is not True:
        return None
    allowed_kinds = {
        str(value)
        for value in settings.get("task_kinds") or []
        if str(value).strip()
    }
    if not allowed_kinds:
        return None
    try:
        tagged_queue, _report = llm_dispatch_tagger.process_queue(queue, policy, policy_path)
    except (KeyError, TypeError, ValueError):
        return None
    for task in tagged_queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        kind = str(task.get("llm_task_kind") or task.get("type") or "")
        if kind not in allowed_kinds:
            continue
        if task.get("llm_candidate") is not True or task.get("llm_triage_only") is not True:
            continue
        if task.get("llm_triage_route_on_pass") != "dispatcher_repair":
            continue
        if task.get("llm_pre_worker_decision"):
            continue
        if task.get("llm_queue_state") not in {"ready", "response_ready", "in_progress"}:
            continue
        return {
            "task_id": str(task.get("id") or task.get("task_id") or ""),
            "task_kind": kind,
            "queue_state": task.get("llm_queue_state"),
            "fallback": str(settings.get("fallback") or "codex"),
        }
    return None


def local_llm_cycle_commands(
    project_root: Path,
    *,
    apply: bool,
    pre_worker: bool = False,
    task_id: str | None = None,
) -> list[list[str]]:
    evidence = [
        sys.executable,
        str(script_path("local_llm_evidence_cycle.py")),
        "--project-root",
        str(project_root),
        "--json",
    ]
    planning = [
        sys.executable,
        str(script_path("local_llm_planning_cycle.py")),
        "--project-root",
        str(project_root),
        "--json",
    ]
    runtime = [
        sys.executable,
        str(script_path("local_llm_runtime_cycle.py")),
        "--project-root",
        str(project_root),
        "--json",
    ]
    if task_id:
        for command in (evidence, planning, runtime):
            command.extend(["--task-id", task_id])
    if pre_worker:
        runtime.extend(["--skip-idle", "--pre-worker", "--max-task-prompts", "1"])
    if apply:
        evidence.extend(["--apply", "--write-report"])
        planning.extend(["--apply", "--write-prompts", "--write-report"])
        runtime.extend(["--apply", "--execute-model", "--write-report"])
        if not pre_worker:
            runtime.append("--execute-idle")
    commands = [evidence, planning, runtime]
    if pre_worker:
        commands.append(list(evidence))
    return commands


def worker_pool_command(
    project_root: Path,
    *,
    base_ref: str,
    machine_id: str,
    runtime_root: str,
    max_total_workers: int,
    worker_base_ref: str | None = None,
    worker_context_ref: str | None = None,
    push_ref: str | None = None,
    fetch: bool = True,
    apply: bool = False,
) -> list[str]:
    command = [
        sys.executable,
        str(script_path("worker_pool_manager.py")),
        "--project-root",
        str(project_root),
        "--base-ref",
        base_ref,
        "--machine-id",
        machine_id,
        "--runtime-root",
        runtime_root,
        "--max-total-workers",
        str(max_total_workers),
        "--detach",
        "--replenish-active",
        "--json",
    ]
    if worker_base_ref:
        command.extend(["--worker-base-ref", worker_base_ref])
    if worker_context_ref:
        command.extend(["--worker-context-ref", worker_context_ref])
    if push_ref:
        command.extend(["--push-ref", push_ref])
    if fetch:
        command.append("--fetch")
    if apply:
        command.append("--apply")
    return command


def worker_pool_execution_command(
    worker_command: list[str],
    *,
    project_root: Path,
    runtime_root: str,
    systemd_unit: str | None,
    systemd_scope: str,
    systemd_after_unit: str | None,
) -> tuple[list[str], bool]:
    if not systemd_unit or not sys.platform.startswith("linux"):
        return worker_command, False
    project_version = load_json(project_root / "PROJECT_VERSION.json")
    project_id = str(project_version.get("project_id") or project_root.name).strip() or project_root.name
    progress_command = [
        sys.executable,
        str(script_path("automation_progress_wrapper.py")),
        "--runtime-root",
        runtime_root,
        "--role",
        "worker-pool",
        "--project",
        project_id,
        "--unit",
        systemd_unit,
        "--",
        *worker_command,
    ]
    command = [
        sys.executable,
        str(script_path("worker_pool_systemd_launcher.py")),
        "--unit",
        systemd_unit,
        "--scope",
        systemd_scope,
        "--working-directory",
        str(script_path("worker_pool_manager.py").resolve().parents[2]),
        "--max-concurrent-units",
        "4",
        "--json",
    ]
    if systemd_after_unit:
        command.extend(["--after-unit", systemd_after_unit])
    command.extend(["--", *progress_command])
    return command, True


def should_run_post_worker_handoff(
    run_class: str,
    *,
    no_result_handoff: bool,
    worker_pool_async: bool,
) -> bool:
    return run_class == "worker_run" and not no_result_handoff and not worker_pool_async


def execute_pre_worker_sequence(
    llm_commands: list[list[str]],
    worker_command: list[str],
    *,
    command_runner: Any = None,
) -> dict[str, Any]:
    runner = command_runner or run
    results: list[dict[str, Any]] = []
    llm_failure = None
    for command in llm_commands:
        code, stdout, stderr = runner(command)
        result = {"command": command, "exit_code": code, "stdout": stdout, "stderr": stderr}
        results.append(result)
        if code != 0:
            llm_failure = result
            break

    worker_code, worker_stdout, worker_stderr = runner(worker_command)
    worker_result = {
        "command": worker_command,
        "exit_code": worker_code,
        "stdout": worker_stdout,
        "stderr": worker_stderr,
    }
    results.append(worker_result)
    return {
        "results": results,
        "exit_code": worker_code,
        "llm_failure": llm_failure,
        "worker_result": worker_result,
        "codex_fallback_used": llm_failure is not None,
    }




def payload_has_state_commit(value: Any) -> bool:
    if isinstance(value, dict):
        if value.get("state_commit"):
            return True
        return any(payload_has_state_commit(child) for child in value.values())
    if isinstance(value, list):
        return any(payload_has_state_commit(child) for child in value)
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return False
        return payload_has_state_commit(parsed)
    return False


def nested_state_commit_recorded(action: dict[str, Any]) -> bool:
    for result in action.get("results") or []:
        if not isinstance(result, dict):
            continue
        stdout = str(result.get("stdout") or "").strip()
        if not stdout:
            continue
        try:
            payload = json.loads(stdout)
        except json.JSONDecodeError:
            continue
        if payload_has_state_commit(payload):
            return True
    return False


def pr_intake_apply_ran(action: dict[str, Any]) -> bool:
    pr_intake = action.get("pr_intake")
    return isinstance(pr_intake, dict) and isinstance(pr_intake.get("apply"), dict)


def full_intake_apply_ran(action: dict[str, Any]) -> bool:
    full_intake = action.get("full_intake")
    return isinstance(full_intake, dict) and isinstance(full_intake.get("apply"), dict)


def full_intake_state_write_ran(action: dict[str, Any]) -> bool:
    full_intake = action.get("full_intake")
    if not isinstance(full_intake, dict):
        return False
    for key in ("dry_run", "apply"):
        item = full_intake.get(key)
        if not isinstance(item, dict) or item.get("exit_code") != 0:
            continue
        command = item.get("command")
        if isinstance(command, list) and "--write-state" in command:
            return True
    return False


def result_handoff_apply_ran(action: dict[str, Any]) -> bool:
    result_handoff = action.get("result_handoff")
    return isinstance(result_handoff, dict) and isinstance(result_handoff.get("apply"), dict)


def parse_command_json_stdout(stdout: str) -> dict[str, Any] | None:
    text = str(stdout or "").strip()
    if not text:
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def finalizer_noop_payload(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    return str(payload.get("decision") or "") in {"no_handoff", "already_finalized"}


def integrator_direct_merge_noop_payload(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    if str(payload.get("status") or "") not in {"no_candidates", "no_ready_items", "routed_no_direct_merge_candidates"}:
        return False
    return (
        not payload.get("ready")
        and not payload.get("commits")
        and not payload.get("state_commit")
        and payload.get("queue_changed") in {None, False}
        and not payload.get("changed_route_ids")
    )


def is_auto_finalizer_command(command: list[str]) -> bool:
    return len(command) > 1 and str(command[1]).endswith("auto_finalizer_merge.py")


def is_integrator_direct_merge_command(command: list[str]) -> bool:
    return len(command) > 1 and str(command[1]).endswith("integrator_direct_merge.py")


def git_pathspec_has_matches(project_root: Path, pathspec: str) -> bool:
    if (project_root / pathspec).exists():
        return True
    proc = git_run(project_root, ["ls-files", "--", pathspec])
    return proc.returncode == 0 and bool(proc.stdout.strip())


def existing_git_pathspecs(project_root: Path, pathspecs: list[str]) -> list[str]:
    return [path for path in pathspecs if git_pathspec_has_matches(project_root, path)]


def porcelain_path(line: str) -> str:
    text = str(line or "").rstrip()
    if not text:
        return ""
    if " -> " in text:
        text = text.rsplit(" -> ", 1)[1]
    return text[3:].strip() if len(text) > 3 else ""


def restore_ignored_tracked_runtime_paths(project_root: Path) -> dict[str, Any]:
    existing_paths = existing_git_pathspecs(project_root, DISCARDABLE_TRACKED_RUNTIME_PATHS)
    if not existing_paths:
        return {"skipped": True, "ok": True, "reason": "no_discardable_runtime_paths"}
    status = git_run(project_root, ["status", "--porcelain", "--", *existing_paths])
    if status.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "runtime_status_failed", "stderr": status.stderr}
    paths = [
        porcelain_path(line)
        for line in status.stdout.splitlines()
        if line.strip() and not line.startswith("?? ")
    ]
    paths = [path for path in paths if path]
    if not paths:
        return {"skipped": True, "ok": True, "reason": "no_tracked_runtime_changes"}
    restore = git_run(project_root, ["restore", "--staged", "--worktree", "--", *paths])
    return {
        "skipped": False,
        "ok": restore.returncode == 0,
        "reason": "restored_ignored_tracked_runtime" if restore.returncode == 0 else "runtime_restore_failed",
        "discarded_paths": paths,
        "stderr": restore.stderr,
    }


def transient_process_log_paths_from_diff(diff_text: str) -> dict[str, Any]:
    """Accept only NUL-delimited tracked paths under the transient log prefix."""
    paths: list[str] = []
    for value in diff_text.split("\0"):
        if not value:
            continue
        path = value.replace("\\", "/")
        if path.startswith('"') or not path.startswith(f"{TRANSIENT_PROCESS_LOGS_PATH}/"):
            return {"ok": False, "reason": "transient_process_logs_ambiguous_path", "path": value}
        paths.append(path)
    return {"ok": True, "paths": sorted(set(paths))}


def preserve_tracked_transient_process_logs(project_root: Path) -> dict[str, Any]:
    """Store local process-log diffs before Git operations that require a clean tree."""
    staged = git_run(project_root, ["diff", "--cached", "--name-only", "-z", "--", TRANSIENT_PROCESS_LOGS_PATH])
    if staged.returncode != 0:
        return {"ok": False, "reason": "transient_process_logs_status_failed", "stderr": staged.stderr}
    if staged.stdout:
        return {"ok": False, "reason": "transient_process_logs_staged_changes"}
    changed = git_run(project_root, ["diff", "--name-only", "-z", "HEAD", "--", TRANSIENT_PROCESS_LOGS_PATH])
    if changed.returncode != 0:
        return {"ok": False, "reason": "transient_process_logs_status_failed", "stderr": changed.stderr}
    parsed = transient_process_log_paths_from_diff(changed.stdout)
    if parsed.get("ok") is not True:
        return parsed
    paths = list(parsed.get("paths") or [])
    if not paths:
        return {"skipped": True, "ok": True, "reason": "no_tracked_transient_process_logs"}
    diff = git_run(project_root, ["diff", "--binary", "HEAD", "--", *paths])
    if diff.returncode != 0 or not diff.stdout:
        return {
            "ok": False,
            "reason": "transient_process_logs_snapshot_failed",
            "stderr": diff.stderr,
            "paths": paths,
        }
    archive_root = (
        project_root
        / "runtime"
        / "pre-apply-recovery"
        / f"transient-process-logs-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{os.getpid()}-{time.time_ns()}"
    )
    try:
        archive_root.mkdir(parents=True, exist_ok=False)
        patch_path = archive_root / "changes.patch"
        patch_path.write_text(diff.stdout, encoding="utf-8")
        write_json(
            archive_root / "recovery.json",
            {
                "schema_version": 1,
                "created_at": utc_now(),
                "reason": "tracked_transient_process_logs_preserved_for_git_sync",
                "paths": paths,
                "patch": patch_path.relative_to(project_root).as_posix(),
            },
        )
    except OSError as exc:
        return {"ok": False, "reason": "transient_process_logs_snapshot_write_failed", "error": str(exc), "paths": paths}
    restore = git_run(project_root, ["restore", "--staged", "--worktree", "--", *paths])
    if restore.returncode != 0:
        return {
            "ok": False,
            "reason": "transient_process_logs_clean_failed",
            "stderr": restore.stderr,
            "paths": paths,
            "archive_root": archive_root.relative_to(project_root).as_posix(),
        }
    clean = git_run(project_root, ["diff", "--quiet", "HEAD", "--", *paths])
    if clean.returncode != 0:
        return {
            "ok": False,
            "reason": "transient_process_logs_not_clean_after_snapshot",
            "stderr": clean.stderr,
            "paths": paths,
            "archive_root": archive_root.relative_to(project_root).as_posix(),
        }
    return {
        "ok": True,
        "reason": "tracked_transient_process_logs_preserved",
        "paths": paths,
        "archive_root": archive_root.relative_to(project_root).as_posix(),
        "patch": patch_path.relative_to(project_root).as_posix(),
    }


def restore_preserved_transient_process_logs(project_root: Path, snapshot: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(snapshot, dict) or snapshot.get("skipped") is True:
        return {"skipped": True, "ok": True, "reason": "no_transient_process_logs_to_restore"}
    if snapshot.get("ok") is not True:
        return {"skipped": True, "ok": False, "reason": "transient_process_logs_snapshot_not_restorable"}
    paths = [str(path).replace("\\", "/") for path in snapshot.get("paths") or []]
    patch = str(snapshot.get("patch") or "")
    if (
        not paths
        or any(not path.startswith(f"{TRANSIENT_PROCESS_LOGS_PATH}/") for path in paths)
        or not patch.startswith("runtime/pre-apply-recovery/")
        or ".." in Path(patch).parts
    ):
        return {"ok": False, "reason": "transient_process_logs_restore_metadata_invalid"}
    patch_path = project_root / patch
    if patch_path.is_symlink() or not patch_path.is_file():
        return {"ok": False, "reason": "transient_process_logs_restore_patch_missing", "patch": patch}
    target_status = git_run(project_root, ["diff", "--quiet", "HEAD", "--", *paths])
    if target_status.returncode != 0:
        return {
            "ok": False,
            "reason": "transient_process_logs_restore_target_dirty",
            "paths": paths,
        }
    apply = git_run(project_root, ["apply", "--binary", "--whitespace=nowarn", str(patch_path)])
    if apply.returncode != 0:
        return {
            "ok": False,
            "reason": "transient_process_logs_restore_failed",
            "stderr": apply.stderr,
            "paths": paths,
            "patch": patch,
        }
    return {"ok": True, "reason": "tracked_transient_process_logs_restored", "paths": paths, "patch": patch}


def finalize_with_transient_process_log_restore(
    project_root: Path,
    result: dict[str, Any],
    snapshot: dict[str, Any] | None,
) -> dict[str, Any]:
    if snapshot is None:
        return result
    restoration = restore_preserved_transient_process_logs(project_root, snapshot)
    result["transient_process_logs"] = {"snapshot": snapshot, "restoration": restoration}
    if restoration.get("ok") is not True:
        result["ok"] = False
        result["reason"] = "transient_process_logs_restore_failed"
    return result


def committed_state_divergence_evidence(
    project_root: Path,
    branch: str,
    run_git: Any,
) -> dict[str, Any]:
    """Allow recovery only for local commits created by the state writer."""
    counts = run_git(["rev-list", "--left-right", "--count", f"origin/{branch}...HEAD"])
    if counts.returncode != 0:
        return {"ok": False, "reason": "committed_state_divergence_count_failed"}
    try:
        behind, ahead = (int(value) for value in counts.stdout.split())
    except (TypeError, ValueError):
        return {"ok": False, "reason": "committed_state_divergence_count_invalid"}
    if behind <= 0 or ahead <= 0:
        return {
            "ok": False,
            "reason": "committed_state_divergence_not_present",
            "ahead": ahead,
            "behind": behind,
        }

    commits = run_git(["log", "--format=%H%x00%s", f"origin/{branch}..HEAD"])
    if commits.returncode != 0:
        return {"ok": False, "reason": "committed_state_log_failed"}
    commit_rows: list[dict[str, str]] = []
    for line in commits.stdout.splitlines():
        commit_hash, separator, subject = line.partition("\0")
        if (
            not separator
            or not re.fullmatch(r"[0-9a-fA-F]{40}", commit_hash)
            or re.fullmatch(r"chore\(agent\): record [a-z0-9_-]+ state", subject) is None
        ):
            return {
                "ok": False,
                "reason": "committed_state_untrusted_commit",
                "commit": commit_hash,
                "subject": subject,
            }
        commit_rows.append({"commit": commit_hash, "subject": subject})
    if len(commit_rows) != ahead:
        return {
            "ok": False,
            "reason": "committed_state_commit_count_mismatch",
            "ahead": ahead,
            "commit_count": len(commit_rows),
        }

    changed = run_git(["diff", "--name-only", "-z", f"origin/{branch}...HEAD"])
    if changed.returncode != 0:
        return {"ok": False, "reason": "committed_state_paths_failed"}
    paths = sorted({path for path in changed.stdout.split("\0") if path})
    unsupported = [path for path in paths if not path_is_state_sync_scope(path)]
    if not paths or unsupported:
        return {
            "ok": False,
            "reason": "committed_state_paths_not_owned",
            "paths": paths,
            "unsupported_paths": unsupported,
        }
    return {
        "ok": True,
        "reason": "trusted_committed_state_divergence",
        "ahead": ahead,
        "behind": behind,
        "commits": commit_rows,
        "paths": paths,
    }


def sync_project_root_before_apply(project_root: Path, base_ref: str) -> dict[str, Any]:
    branch = branch_name_from_ref(base_ref)
    commands: list[dict[str, Any]] = []
    transient_process_logs: dict[str, Any] | None = None

    def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
        proc = git_run(project_root, args)
        commands.append({"command": ["git", *args], "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr})
        return proc

    def finish(result: dict[str, Any]) -> dict[str, Any]:
        return finalize_with_transient_process_log_restore(project_root, result, transient_process_logs)

    worktree = run_git(["rev-parse", "--is-inside-work-tree"])
    if worktree.returncode != 0 or worktree.stdout.strip().lower() != "true":
        return {"skipped": False, "ok": False, "reason": "pre_apply_non_git_project", "commands": commands}
    status = run_git(["status", "--porcelain"])
    if status.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "pre_apply_status_failed", "commands": commands}

    dirty_paths: list[str] = []
    ignored_tracked: list[str] = []
    for line in status.stdout.splitlines():
        if not line.strip():
            continue
        path = porcelain_path(line).replace("\\", "/")
        if not path:
            continue
        if line.startswith("?? ") and (path == "runtime" or path.startswith("runtime/")):
            continue
        if not line.startswith("?? ") and any(path == prefix or path.startswith(f"{prefix}/") for prefix in IGNORED_TRACKED_RUNTIME_PATHS):
            ignored_tracked.append(path)
            continue
        dirty_paths.append(path)
    if dirty_paths:
        return {
            "skipped": False,
            "ok": False,
            "reason": "pre_apply_dirty_paths",
            "dirty_paths": dirty_paths,
            "commands": commands,
        }
    if any(path == TRANSIENT_PROCESS_LOGS_PATH or path.startswith(f"{TRANSIENT_PROCESS_LOGS_PATH}/") for path in ignored_tracked):
        transient_process_logs = preserve_tracked_transient_process_logs(project_root)
        if transient_process_logs.get("ok") is not True:
            return {
                "skipped": False,
                "ok": False,
                "reason": "pre_apply_transient_process_logs_preservation_failed",
                "commands": commands,
                "transient_process_logs": transient_process_logs,
            }
        ignored_tracked = [
            path
            for path in ignored_tracked
            if path != TRANSIENT_PROCESS_LOGS_PATH and not path.startswith(f"{TRANSIENT_PROCESS_LOGS_PATH}/")
        ]
    if ignored_tracked:
        restore = run_git(["restore", "--staged", "--worktree", "--", *ignored_tracked])
        if restore.returncode != 0:
            return finish({"skipped": False, "ok": False, "reason": "pre_apply_runtime_restore_failed", "commands": commands})
    fetch = run_git(["fetch", "origin", branch])
    if fetch.returncode != 0:
        return finish({"skipped": False, "ok": False, "reason": "pre_apply_fetch_failed", "commands": commands})
    fast_forward = run_git(["merge", "--ff-only", f"origin/{branch}"])
    if fast_forward.returncode != 0:
        committed_state = committed_state_divergence_evidence(project_root, branch, run_git)
        if committed_state.get("ok") is not True:
            return finish({
                "skipped": False,
                "ok": False,
                "reason": "pre_apply_fast_forward_failed",
                "commands": commands,
                "committed_state": committed_state,
            })
        rebase = run_git(["rebase", f"origin/{branch}"])
        if rebase.returncode != 0:
            try:
                conflict_resolution = resolve_state_sync_merge_conflicts(project_root)
            except (OSError, ValueError) as exc:
                conflict_resolution = {
                    "ok": False,
                    "reason": "state_conflict_resolution_failed",
                    "error": str(exc),
                }
            if conflict_resolution.get("ok") is True and conflict_resolution.get("resolved_paths"):
                rebase = run_git(["-c", "core.editor=true", "rebase", "--continue"])
            if rebase.returncode != 0:
                rebase_head = run_git(["rev-parse", "--verify", "-q", "REBASE_HEAD"])
                abort = None
                if rebase_head.returncode == 0:
                    abort = run_git(["rebase", "--abort"])
                return finish({
                    "skipped": False,
                    "ok": False,
                    "reason": (
                        "pre_apply_committed_state_rebase_abort_failed"
                        if abort is not None and abort.returncode != 0
                        else "pre_apply_committed_state_rebase_failed"
                    ),
                    "commands": commands,
                    "committed_state": committed_state,
                    "conflict_resolution": conflict_resolution,
                })
        push = run_git(["push", "origin", f"HEAD:{branch}"])
        if push.returncode != 0:
            return finish({
                "skipped": False,
                "ok": False,
                "reason": "pre_apply_committed_state_push_failed",
                "commands": commands,
                "committed_state": committed_state,
            })
        return finish({
            "skipped": False,
            "ok": True,
            "reason": "pre_apply_committed_state_rebased",
            "commands": commands,
            "committed_state": committed_state,
        })
    return finish({
        "skipped": False,
        "ok": True,
        "reason": "pre_apply_synced",
        "discarded_ignored_tracked_paths": ignored_tracked,
        "commands": commands,
    })


def path_is_directly_recoverable_state(path: str) -> bool:
    normalized = str(path).replace("\\", "/").strip().strip("/")
    if normalized in PRE_APPLY_RECOVERABLE_STATE_PATHS:
        return True
    for prefix in PRE_APPLY_RECOVERABLE_STATE_FILE_PREFIXES:
        if normalized.startswith(prefix):
            remainder = normalized[len(prefix):]
            return bool(remainder) and "/" not in remainder
    return False


def path_is_pre_apply_recoverable_state(path: str) -> bool:
    normalized = str(path).replace("\\", "/").strip().strip("/")
    return normalized in PRE_APPLY_TRACKED_RECOVERABLE_STATE_PATHS or path_is_directly_recoverable_state(normalized)


def can_recover_pre_apply_generated_state(report: dict[str, Any] | None) -> bool:
    if not isinstance(report, dict) or report.get("reason") != "pre_apply_dirty_paths":
        return False
    dirty_paths = {
        str(path).replace("\\", "/").strip()
        for path in report.get("dirty_paths") or []
        if str(path).strip()
    }
    return bool(dirty_paths) and all(path_is_directly_recoverable_state(path) for path in dirty_paths)


def tracked_pre_apply_generated_state_recovery_evidence(
    project_root: Path,
    report: dict[str, Any] | None,
) -> dict[str, Any]:
    """Permit only the exact tracked Integrator snapshot through state sync."""
    if not isinstance(report, dict) or report.get("reason") != "pre_apply_dirty_paths":
        return {"ok": False, "reason": "pre_apply_dirty_paths_not_reported"}
    dirty_paths = {
        str(path).replace("\\", "/").strip().strip("/")
        for path in report.get("dirty_paths") or []
        if str(path).strip()
    }
    if not dirty_paths or not dirty_paths.issubset(PRE_APPLY_TRACKED_RECOVERABLE_STATE_PATHS):
        return {"ok": False, "reason": "tracked_generated_state_path_not_owned", "dirty_paths": sorted(dirty_paths)}
    status = git_run(project_root, ["status", "--porcelain", "--", *sorted(dirty_paths)])
    status_rows = [line for line in status.stdout.splitlines() if line.strip()]
    if status.returncode != 0 or len(status_rows) != len(dirty_paths):
        return {"ok": False, "reason": "tracked_generated_state_status_invalid"}
    tracked_paths = {
        porcelain_path(line).replace("\\", "/").strip()
        for line in status_rows
        if not line.startswith("?? ")
    }
    if tracked_paths != dirty_paths:
        return {"ok": False, "reason": "tracked_generated_state_not_tracked", "dirty_paths": sorted(dirty_paths)}
    validation = validate_pre_apply_generated_state(project_root, report)
    if validation.get("ok") is not True:
        return {
            "ok": False,
            "reason": "tracked_generated_state_schema_invalid",
            "validation": validation,
            "preserved": True,
        }
    return {
        "ok": True,
        "reason": "tracked_generated_state_verified",
        "source": "tracked_integrator_snapshot_contract",
        "validation": validation,
    }


def validate_pre_apply_generated_state(
    project_root: Path,
    report: dict[str, Any],
    *,
    paths: list[str] | None = None,
) -> dict[str, Any]:
    """Validate path ownership and the minimal generated-state JSON contract."""
    values = paths if paths is not None else list(report.get("dirty_paths") or [])
    requested_paths = sorted(
        {
            str(value).replace("\\", "/").strip().strip("/")
            for value in values
            if str(value).strip()
        }
    )
    checked_paths: list[str] = []
    invalid_paths: list[dict[str, str]] = []
    for rel_path in requested_paths:
        relative = Path(rel_path)
        target = project_root / relative
        if relative.is_absolute() or ".." in relative.parts:
            invalid_paths.append({"path": rel_path, "error": "unsafe_relative_path"})
            continue
        if not path_is_pre_apply_recoverable_state(rel_path):
            invalid_paths.append({"path": rel_path, "error": "generated_state_path_not_owned"})
            continue
        if target.is_symlink() or not target.is_file():
            invalid_paths.append({"path": rel_path, "error": "generated_state_file_missing_or_not_regular"})
            continue
        if target.suffix.lower() not in {".json", ".jsonl"}:
            invalid_paths.append({"path": rel_path, "error": "generated_state_extension_unsupported"})
            continue
        checked_paths.append(rel_path)
        try:
            text = target.read_text(encoding="utf-8")
            if target.suffix.lower() == ".json":
                payload = json.loads(text)
                if not isinstance(payload, dict):
                    raise ValueError("generated JSON must contain an object")
                schema_version = payload.get("schema_version")
                if (
                    isinstance(schema_version, bool)
                    or not isinstance(schema_version, (int, float, str))
                    or not str(schema_version).strip()
                ):
                    raise ValueError("generated JSON requires schema_version")
            else:
                for line_number, line in enumerate(text.splitlines(), start=1):
                    if not line.strip():
                        continue
                    payload = json.loads(line)
                    if not isinstance(payload, dict):
                        raise ValueError(f"generated JSONL line {line_number} must contain an object")
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            invalid_paths.append({"path": rel_path, "error": str(exc)})
    return {
        "ok": bool(requested_paths) and not invalid_paths,
        "reason": "generated_state_schema_valid" if requested_paths and not invalid_paths else "generated_state_schema_invalid",
        "checked_paths": checked_paths,
        "invalid_paths": invalid_paths,
    }


def path_is_state_sync_scope(path: str) -> bool:
    normalized = str(path).replace("\\", "/").strip().strip("/")
    return any(
        normalized == scope or normalized.startswith(f"{scope}/")
        for scope in STATE_SYNC_PATHS
    )


def interrupted_writer_recovery_evidence(project_root: Path, report: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(report, dict) or report.get("reason") != "pre_apply_dirty_paths":
        return {"ok": False, "reason": "pre_apply_dirty_paths_not_reported"}
    dirty_paths = sorted(
        {
            str(path).replace("\\", "/").strip().strip("/")
            for path in report.get("dirty_paths") or []
            if str(path).strip()
        }
    )
    if not dirty_paths:
        return {"ok": False, "reason": "no_dirty_paths"}
    if any(path in PRE_APPLY_INTERRUPTED_WRITER_EXCLUDED_PATHS for path in dirty_paths):
        return {"ok": False, "reason": "owner_controlled_state_dirty", "dirty_paths": dirty_paths}
    unsupported = [path for path in dirty_paths if not path_is_state_sync_scope(path)]
    if unsupported:
        return {
            "ok": False,
            "reason": "dirty_paths_outside_state_sync_scope",
            "dirty_paths": dirty_paths,
            "unsupported_paths": unsupported,
        }
    process_locks_path = "AiStudio/Task_manager/process_locks.json"
    if process_locks_path not in dirty_paths:
        return {
            "ok": False,
            "reason": "writer_lock_evidence_not_dirty",
            "dirty_paths": dirty_paths,
        }

    status = git_run(project_root, ["status", "--porcelain", "--", *dirty_paths])
    if status.returncode != 0:
        return {"ok": False, "reason": "writer_recovery_status_failed", "stderr": status.stderr}
    untracked_paths = sorted(
        {
            porcelain_path(line).replace("\\", "/")
            for line in status.stdout.splitlines()
            if line.startswith("?? ") and porcelain_path(line)
        }
    )
    unsupported_untracked = [
        path for path in untracked_paths if not path_is_pre_apply_recoverable_state(path)
    ]
    if unsupported_untracked:
        return {
            "ok": False,
            "reason": "writer_recovery_untracked_state",
            "untracked_paths": untracked_paths,
            "unsupported_paths": unsupported_untracked,
        }
    generated_validation = None
    if untracked_paths:
        generated_validation = validate_pre_apply_generated_state(
            project_root,
            report,
            paths=untracked_paths,
        )
        if generated_validation.get("ok") is not True:
            return {
                "ok": False,
                "reason": "writer_recovery_state_invalid",
                "paths": [item["path"] for item in generated_validation.get("invalid_paths") or []],
                "generated_state_validation": generated_validation,
            }

    try:
        lock_payload = load_json(project_root / process_locks_path)
    except (OSError, ValueError) as exc:
        return {"ok": False, "reason": "writer_lock_evidence_invalid", "error": str(exc)}
    if not isinstance(lock_payload, dict):
        return {"ok": False, "reason": "writer_lock_evidence_invalid"}
    candidates: list[tuple[datetime, dict[str, Any]]] = []
    for item in lock_payload.get("locks") or []:
        if (
            not isinstance(item, dict)
            or item.get("process") != "status_orchestrator"
            or item.get("by") != "status_orchestrator"
            or not str(item.get("run_id") or "").strip()
        ):
            continue
        started_at = parse_time(item.get("at"))
        if started_at is None:
            continue
        recorded_root = str(item.get("project_root") or "").strip()
        if recorded_root and Path(recorded_root).resolve() != project_root.resolve():
            continue
        recorded_host = str(item.get("host") or "").strip()
        if recorded_host and recorded_host.casefold() != socket.gethostname().casefold():
            continue
        candidates.append((started_at, item))
    if not candidates:
        return {"ok": False, "reason": "writer_lock_evidence_missing"}

    started_at, lock = max(candidates, key=lambda candidate: candidate[0])
    state = str(lock.get("state") or "")
    if state == "released":
        ended_at = parse_time(lock.get("released_at"))
    elif state == "expired":
        ended_at = parse_time(lock.get("expired_at")) or parse_time(lock.get("expires_at"))
    elif state == "active":
        if process_lock_has_live_process(lock, project_root) is not False:
            return {
                "ok": False,
                "reason": "writer_process_still_active_or_unknown",
                "run_id": lock.get("run_id"),
            }
        ended_at = datetime.now(timezone.utc)
    else:
        return {"ok": False, "reason": "writer_lock_state_not_recoverable", "state": state}
    if ended_at is None or ended_at < started_at:
        return {"ok": False, "reason": "writer_lock_interval_invalid", "run_id": lock.get("run_id")}

    skew = timedelta(seconds=PRE_APPLY_WRITER_MTIME_SKEW_SECONDS)
    outside_interval: list[str] = []
    missing_paths: list[str] = []
    invalid_paths: list[str] = []
    for rel_path in dirty_paths:
        target = project_root / rel_path
        if not target.is_file():
            missing_paths.append(rel_path)
            continue
        try:
            if target.suffix == ".json":
                json.loads(target.read_text(encoding="utf-8"))
            elif target.suffix == ".jsonl":
                for line in target.read_text(encoding="utf-8").splitlines():
                    if line.strip():
                        json.loads(line)
        except (OSError, ValueError, UnicodeError):
            invalid_paths.append(rel_path)
            continue
        modified_at = datetime.fromtimestamp(target.stat().st_mtime, timezone.utc)
        if modified_at < started_at - skew or modified_at > ended_at + skew:
            outside_interval.append(rel_path)
    if missing_paths:
        return {"ok": False, "reason": "writer_recovery_paths_missing", "paths": missing_paths}
    if invalid_paths:
        return {"ok": False, "reason": "writer_recovery_state_invalid", "paths": invalid_paths}
    if outside_interval:
        return {
            "ok": False,
            "reason": "dirty_paths_outside_writer_interval",
            "paths": outside_interval,
            "run_id": lock.get("run_id"),
        }
    return {
        "ok": True,
        "reason": "interrupted_canonical_writer_verified",
        "run_id": lock.get("run_id"),
        "writer_state": state,
        "writer_started_at": started_at.isoformat().replace("+00:00", "Z"),
        "writer_ended_at": ended_at.isoformat().replace("+00:00", "Z"),
        "dirty_paths": dirty_paths,
        "untracked_generated_paths": untracked_paths,
        "generated_state_validation": generated_validation,
    }


def pre_apply_generated_state_recovery_evidence(
    project_root: Path,
    report: dict[str, Any] | None,
) -> dict[str, Any]:
    """Classify dirty state without mutating or widening recovery ownership."""
    if can_recover_pre_apply_generated_state(report):
        validation = validate_pre_apply_generated_state(project_root, report or {})
        if validation.get("ok") is True:
            return {
                "ok": True,
                "reason": "known_generated_state_verified",
                "source": "generated_state_path_contract",
                "outcome": "recoverable",
                "next_owner": "status_orchestrator",
                "validation": validation,
            }
        return {
            "ok": False,
            "reason": "generated_state_schema_invalid",
            "source": "generated_state_path_contract",
            "outcome": "needs_repair",
            "next_owner": "Doctor",
            "preserved": True,
            "validation": validation,
        }

    writer_evidence = interrupted_writer_recovery_evidence(project_root, report)
    if writer_evidence.get("ok") is True:
        return {
            **writer_evidence,
            "source": "interrupted_writer_contract",
            "outcome": "recoverable",
            "next_owner": "status_orchestrator",
        }
    return {
        **writer_evidence,
        "source": "interrupted_writer_contract",
        "outcome": "needs_repair",
        "next_owner": "Doctor",
        "preserved": True,
    }


def can_quarantine_pre_apply_generated_state(report: dict[str, Any] | None) -> bool:
    if not isinstance(report, dict) or report.get("reason") != "pre_apply_dirty_paths":
        return False
    dirty_paths = {
        str(path).replace("\\", "/").strip()
        for path in report.get("dirty_paths") or []
        if str(path).strip()
    }
    return bool(dirty_paths & PRE_APPLY_QUARANTINABLE_GENERATED_PATHS) and dirty_paths.issubset(
        PRE_APPLY_QUARANTINABLE_GENERATED_PATHS | PRE_APPLY_RECOVERABLE_STATE_PATHS
    )


def quarantine_pre_apply_generated_state(project_root: Path, report: dict[str, Any]) -> dict[str, Any]:
    paths = sorted(
        {
            str(path).replace("\\", "/").strip()
            for path in report.get("dirty_paths") or []
            if str(path).replace("\\", "/").strip() in PRE_APPLY_QUARANTINABLE_GENERATED_PATHS
        }
    )
    if not paths:
        return {"skipped": True, "ok": True, "reason": "no_quarantinable_generated_state"}

    sources: list[tuple[str, Path]] = []
    for rel_path in paths:
        relative = Path(rel_path)
        if relative.is_absolute() or ".." in relative.parts:
            return {"skipped": False, "ok": False, "reason": "unsafe_generated_state_path", "path": rel_path}
        source = project_root / relative
        status = git_run(project_root, ["status", "--porcelain", "--untracked-files=all", "--", rel_path])
        status_rows = [line for line in status.stdout.splitlines() if line.strip()]
        if (
            status.returncode != 0
            or len(status_rows) != 1
            or not status_rows[0].startswith("?? ")
            or porcelain_path(status_rows[0]).replace("\\", "/") != rel_path
            or source.is_symlink()
            or not source.is_file()
        ):
            return {
                "skipped": False,
                "ok": False,
                "reason": "generated_state_not_untracked_file",
                "path": rel_path,
            }
        sources.append((rel_path, source))

    archive_root = (
        project_root
        / "runtime"
        / "pre-apply-recovery"
        / f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{os.getpid()}-{time.time_ns()}"
    )
    archived: list[dict[str, str]] = []
    try:
        for rel_path, source in sources:
            target = archive_root / Path(rel_path)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(target))
            archived.append({"source": rel_path, "archive": target.relative_to(project_root).as_posix()})
        write_json(
            archive_root / "recovery.json",
            {
                "schema_version": 1,
                "recovered_at": utc_now(),
                "reason": "pre_apply_untracked_generated_state_quarantined",
                "archived_paths": archived,
            },
        )
    except OSError as exc:
        rolled_back: list[str] = []
        for item in reversed(archived):
            target = project_root / Path(item["archive"])
            source = project_root / Path(item["source"])
            if target.exists() and not source.exists():
                source.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(target), str(source))
                rolled_back.append(item["source"])
        return {
            "skipped": False,
            "ok": False,
            "reason": "generated_state_quarantine_failed",
            "error": str(exc),
            "rolled_back_paths": rolled_back,
        }

    return {
        "skipped": False,
        "ok": True,
        "reason": "pre_apply_untracked_generated_state_quarantined",
        "archive_root": archive_root.relative_to(project_root).as_posix(),
        "archived_paths": archived,
    }


def git_stage_text(project_root: Path, stage: int, path: str) -> str | None:
    proc = git_run(project_root, ["show", f":{stage}:{path}"])
    if proc.returncode != 0:
        return None
    return proc.stdout


TERMINAL_TASK_STATUSES = {"done", "postponed", "failed", "stale_or_superseded", "duplicate_linked"}
TERMINAL_INTEGRATION_STATUSES = {"finalized", "closed_no_diff", "closed_coordination_only", "blocked"}
WORKER_EVIDENCE_FIELDS = {
    "branch",
    "changed_paths",
    "commits",
    "github_branch",
    "imported_worker_reports",
    "machine_id",
    "synced_from_worker_branch",
    "worker_changed_paths",
    "worker_check_evidence",
    "worker_id",
    "worker_report",
    "worker_result_commit",
    "worker_result_synced_at",
}
INTEGRATOR_ROUTE_RETRY_EVENTS = {
    "model_limit_retry_promoted",
    "owner_retry_approved",
    "worker_fix_requeued",
    "worker_ready_promoted",
}


def task_terminal_rank(task: dict[str, Any]) -> int:
    if (
        str(task.get("integration_status") or "") in TERMINAL_INTEGRATION_STATUSES
        or str(task.get("finalization_status") or "") == "recorded"
    ):
        return 2
    if str(task.get("status") or "") in TERMINAL_TASK_STATUSES:
        return 1
    return 0


def task_terminal_timestamp(task: dict[str, Any]) -> str:
    return max(
        str(task.get("finalized_at") or ""),
        str(task.get("completed_at") or ""),
        str(task.get("integrated_at") or ""),
        str(task.get("updated_at") or ""),
    )


def integrator_route_record(task: dict[str, Any]) -> dict[str, Any] | None:
    current_status = str(task.get("status") or "")
    history = task.get("status_history")
    if not isinstance(history, list):
        return None
    for item in reversed(history):
        if not isinstance(item, dict):
            continue
        if (
            str(item.get("by") or "") == "integrator_direct_merge"
            and str(item.get("event") or "") == "integration_routed"
            and str(item.get("to") or "") == current_status
        ):
            return item
    return None


def merge_integrator_route_with_worker_evidence(
    route_task: dict[str, Any],
    evidence_task: dict[str, Any],
) -> dict[str, Any]:
    merged = json.loads(json.dumps(route_task))
    route_synced_at = str(route_task.get("worker_result_synced_at") or "")
    evidence_synced_at = str(evidence_task.get("worker_result_synced_at") or "")
    if route_synced_at and evidence_synced_at and route_synced_at > evidence_synced_at:
        return merged
    for field in WORKER_EVIDENCE_FIELDS:
        value = evidence_task.get(field)
        if value not in (None, "", []):
            merged[field] = json.loads(json.dumps(value))
    return merged


def explicit_retry_after_integrator_route(
    task: dict[str, Any],
    route_record: dict[str, Any],
) -> bool:
    route_at = str(route_record.get("at") or "")
    history = task.get("status_history")
    if not isinstance(history, list):
        return False
    return any(
        isinstance(item, dict)
        and str(item.get("event") or "") in INTEGRATOR_ROUTE_RETRY_EVENTS
        and str(item.get("at") or "") > route_at
        for item in history
    )


def has_material_worker_result(task: dict[str, Any]) -> bool:
    if not str(task.get("worker_result_commit") or "").strip():
        return False
    return str(task.get("status") or "") in {
        "agent_done",
        "review",
        "integration_ready",
        "integration_requested",
        "needs_dispatcher_repair",
        "needs_task_packet",
        "needs_architect",
        "needs_human",
        "blocked",
    }


def has_active_worker_claim(task: dict[str, Any]) -> bool:
    return str(task.get("status") or "") == "in_progress" or str(task.get("lock") or "") == "locked"


def merge_task_queue_state(ours: dict[str, Any], theirs: dict[str, Any]) -> dict[str, Any]:
    merged = json.loads(json.dumps(ours))
    tasks = merged.setdefault("tasks", [])
    if not isinstance(tasks, list):
        tasks = []
        merged["tasks"] = tasks
    by_id = {task.get("id"): task for task in tasks if isinstance(task, dict)}
    theirs_tasks = theirs.get("tasks")
    if isinstance(theirs_tasks, list):
        for other in theirs_tasks:
            if not isinstance(other, dict):
                continue
            task_id = other.get("id")
            current = by_id.get(task_id)
            if current is None:
                tasks.append(other)
                by_id[task_id] = other
                continue
            current_terminal_rank = task_terminal_rank(current)
            other_terminal_rank = task_terminal_rank(other)
            if current_terminal_rank or other_terminal_rank:
                if (
                    other_terminal_rank > current_terminal_rank
                    or (
                        other_terminal_rank == current_terminal_rank
                        and task_terminal_timestamp(other) >= task_terminal_timestamp(current)
                    )
                ):
                    current.update(other)
                continue
            current_route = integrator_route_record(current)
            other_route = integrator_route_record(other)
            if current_route or other_route:
                if current_route and not other_route and explicit_retry_after_integrator_route(other, current_route):
                    current.clear()
                    current.update(json.loads(json.dumps(other)))
                    continue
                if other_route and not current_route and explicit_retry_after_integrator_route(current, other_route):
                    continue
                select_other_route = bool(
                    other_route
                    and (
                        not current_route
                        or str(other_route.get("at") or "") > str(current_route.get("at") or "")
                    )
                )
                route_task = other if select_other_route else current
                evidence_task = current if select_other_route else other
                resolved_task = merge_integrator_route_with_worker_evidence(route_task, evidence_task)
                current.clear()
                current.update(resolved_task)
                continue
            current_has_result = has_material_worker_result(current)
            other_has_result = has_material_worker_result(other)
            if current_has_result != other_has_result:
                if other_has_result:
                    current.update(other)
                continue
            if has_active_worker_claim(other):
                for key in (
                    "status",
                    "lock",
                    "started_at",
                    "worker_id",
                    "machine_id",
                    "branch",
                    "github_branch",
                    "lock_expires_at",
                    "status_reason",
                ):
                    if key in other:
                        current[key] = other[key]
    merged["updated_at"] = max(str(merged.get("updated_at") or ""), str(theirs.get("updated_at") or ""))
    return merged


def lock_identity(lock: dict[str, Any]) -> tuple[str, str, str, str]:
    return (
        str(lock.get("task_id") or ""),
        str(lock.get("by") or ""),
        str(lock.get("branch") or ""),
        str(lock.get("at") or ""),
    )


def lock_timestamp(lock: dict[str, Any]) -> str:
    return max(
        str(lock.get("released_at") or ""),
        str(lock.get("expires_at") or ""),
        str(lock.get("at") or ""),
    )


def merge_agent_locks_state(ours: dict[str, Any], theirs: dict[str, Any]) -> dict[str, Any]:
    merged = json.loads(json.dumps(ours))
    locks = merged.setdefault("locks", [])
    if not isinstance(locks, list):
        locks = []
        merged["locks"] = locks
    by_identity = {
        lock_identity(lock): lock
        for lock in locks
        if isinstance(lock, dict)
    }
    terminal_states = {"released", "expired"}
    theirs_locks = theirs.get("locks")
    if isinstance(theirs_locks, list):
        for other in theirs_locks:
            if not isinstance(other, dict):
                continue
            key = lock_identity(other)
            current = by_identity.get(key)
            if current is None:
                locks.append(other)
                by_identity[key] = other
                continue
            current_state = str(current.get("state") or "")
            other_state = str(other.get("state") or "")
            if current_state in terminal_states and other_state not in terminal_states:
                continue
            if other_state in terminal_states and current_state not in terminal_states:
                current.update(other)
                continue
            if lock_timestamp(other) >= lock_timestamp(current):
                current.update(other)
    merged["updated_at"] = max(str(merged.get("updated_at") or ""), str(theirs.get("updated_at") or ""))
    return merged


def newer_snapshot(ours: dict[str, Any], theirs: dict[str, Any], timestamp_key: str = "updated_at") -> dict[str, Any]:
    ours_timestamp = str(ours.get(timestamp_key) or "")
    theirs_timestamp = str(theirs.get(timestamp_key) or "")
    selected = theirs if theirs_timestamp > ours_timestamp else ours
    return json.loads(json.dumps(selected))


def process_lock_identity(lock: dict[str, Any]) -> tuple[str, str]:
    return (str(lock.get("process") or ""), str(lock.get("run_id") or ""))


def process_lock_timestamp(lock: dict[str, Any]) -> str:
    return max(
        str(lock.get("released_at") or ""),
        str(lock.get("expires_at") or ""),
        str(lock.get("at") or ""),
    )


def merge_process_locks_state(ours: dict[str, Any], theirs: dict[str, Any]) -> dict[str, Any]:
    merged = newer_snapshot(ours, theirs)
    locks: list[dict[str, Any]] = []
    by_identity: dict[tuple[str, str], dict[str, Any]] = {}
    terminal_states = {"released", "expired"}
    for source in (ours, theirs):
        source_locks = source.get("locks")
        if not isinstance(source_locks, list):
            continue
        for item in source_locks:
            if not isinstance(item, dict):
                continue
            other = json.loads(json.dumps(item))
            identity = process_lock_identity(other)
            current = by_identity.get(identity)
            if current is None:
                locks.append(other)
                by_identity[identity] = other
                continue
            current_terminal = str(current.get("state") or "") in terminal_states
            other_terminal = str(other.get("state") or "") in terminal_states
            if other_terminal and not current_terminal:
                current.update(other)
            elif other_terminal == current_terminal and process_lock_timestamp(other) >= process_lock_timestamp(current):
                current.update(other)
    locks.sort(key=lambda item: (str(item.get("at") or ""), str(item.get("process") or ""), str(item.get("run_id") or "")))
    merged["locks"] = locks
    merged["updated_at"] = max(str(ours.get("updated_at") or ""), str(theirs.get("updated_at") or ""))
    return merged


def merge_jsonl_state(ours: str, theirs: str) -> str:
    seen: set[str] = set()
    lines: list[str] = []
    for text in (ours, theirs):
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line in seen:
                continue
            json.loads(line)
            seen.add(line)
            lines.append(line)
    return "\n".join(lines) + ("\n" if lines else "")


def resolve_state_sync_merge_conflicts(project_root: Path) -> dict[str, Any]:
    unmerged = git_run(project_root, ["diff", "--name-only", "--diff-filter=U"])
    if unmerged.returncode != 0:
        return {"ok": False, "reason": "unmerged_status_failed", "stderr": unmerged.stderr}
    paths = [line.strip() for line in unmerged.stdout.splitlines() if line.strip()]
    supported_paths = {
        "AiStudio/Project_state/indexes/current_summary.md",
        "AiStudio/Task_manager/agent_events.jsonl",
        "AiStudio/Task_manager/agent_locks.json",
        "AiStudio/Task_manager/agent_process_state.json",
        "AiStudio/Task_manager/automation_bridge_state.json",
        "AiStudio/Task_manager/integrator_direct_merge.json",
        "AiStudio/Task_manager/process_locks.json",
        "AiStudio/Task_manager/task_queue.json",
        "AiStudio/Task_manager/worker_candidates.json",
    }
    unsupported = [path for path in paths if path not in supported_paths and not path.startswith("docs/reports/workers/")]
    if unsupported:
        return {"ok": False, "reason": "unsupported_state_conflicts", "paths": unsupported}

    resolved: list[str] = []
    for path in paths:
        if path.startswith("docs/reports/workers/"):
            checkout = git_run(project_root, ["checkout", "--theirs", "--", path])
            if checkout.returncode != 0:
                return {"ok": False, "reason": "worker_report_checkout_failed", "path": path, "stderr": checkout.stderr}
            add = git_run(project_root, ["add", "--", path])
            if add.returncode != 0:
                return {"ok": False, "reason": "conflict_add_failed", "path": path, "stderr": add.stderr}
            resolved.append(path)
            continue
        ours = git_stage_text(project_root, 2, path)
        theirs = git_stage_text(project_root, 3, path)
        if ours is None or theirs is None:
            return {"ok": False, "reason": "missing_conflict_stage", "path": path}
        target = project_root / path
        if path == "AiStudio/Task_manager/task_queue.json":
            payload = merge_task_queue_state(json.loads(ours), json.loads(theirs))
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif path == "AiStudio/Task_manager/agent_locks.json":
            payload = merge_agent_locks_state(json.loads(ours), json.loads(theirs))
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif path in {
            "AiStudio/Task_manager/agent_process_state.json",
            "AiStudio/Task_manager/automation_bridge_state.json",
        }:
            payload = newer_snapshot(json.loads(ours), json.loads(theirs))
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif path == "AiStudio/Task_manager/process_locks.json":
            payload = merge_process_locks_state(json.loads(ours), json.loads(theirs))
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif path == "AiStudio/Task_manager/worker_candidates.json":
            payload = newer_snapshot(json.loads(ours), json.loads(theirs), "generated_at")
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif path == "AiStudio/Task_manager/integrator_direct_merge.json":
            payload = newer_snapshot(json.loads(ours), json.loads(theirs), "created_at")
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        elif path == "AiStudio/Task_manager/agent_events.jsonl":
            target.write_text(merge_jsonl_state(ours, theirs), encoding="utf-8")
        elif path == "AiStudio/Project_state/indexes/current_summary.md":
            code, stdout, stderr = run([
                sys.executable,
                str(script_path("project_state_summary_builder.py")),
                "--project-root",
                str(project_root),
                "--apply",
                "--json",
            ])
            if code != 0:
                return {
                    "ok": False,
                    "reason": "current_summary_regeneration_failed",
                    "path": path,
                    "exit_code": code,
                    "stdout": stdout,
                    "stderr": stderr,
                }
        add = git_run(project_root, ["add", "--", path])
        if add.returncode != 0:
            return {"ok": False, "reason": "conflict_add_failed", "path": path, "stderr": add.stderr}
        resolved.append(path)
    return {"ok": True, "resolved_paths": resolved}


def remove_identical_untracked_ref_files(project_root: Path, ref: str) -> dict[str, Any]:
    status = git_run(project_root, ["status", "--porcelain=v1", "-z", "--untracked-files=all"])
    if status.returncode != 0:
        return {"ok": False, "reason": "untracked_status_failed", "stderr": status.stderr, "removed_paths": []}
    removed: list[str] = []
    for entry in status.stdout.split("\0"):
        if not entry.startswith("?? "):
            continue
        rel_path = entry[3:]
        target = project_root / rel_path
        if not (target.is_file() or target.is_symlink()):
            continue
        local_blob = git_run(project_root, ["hash-object", "--", rel_path])
        remote_blob = git_run(project_root, ["rev-parse", f"{ref}:{rel_path}"])
        if local_blob.returncode != 0 or remote_blob.returncode != 0:
            continue
        if local_blob.stdout.strip() != remote_blob.stdout.strip():
            continue
        target.unlink()
        removed.append(rel_path)
    return {"ok": True, "reason": "identical_untracked_files_removed" if removed else "no_identical_untracked_files", "removed_paths": removed}


def state_sync_failure_is_fatal(state_sync: Any) -> bool:
    return isinstance(state_sync, dict) and state_sync.get("ok") is False


def sync_task_manager_state(project_root: Path, run_class: str, base_ref: str, exclude_paths: list[str] | None = None) -> dict[str, Any]:
    if run_class not in STATE_SYNC_RUN_CLASSES:
        return {"skipped": True, "reason": "run_class_does_not_require_orchestrator_state_sync"}
    commands: list[dict[str, Any]] = []
    def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
        proc = git_run(project_root, args)
        commands.append({"command": ["git", *args], "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr})
        return proc
    if exclude_paths:
        existing_excludes = existing_git_pathspecs(project_root, exclude_paths)
        if existing_excludes:
            restore = run_git(["restore", "--staged", "--worktree", "--", *existing_excludes])
            if restore.returncode != 0:
                return {"skipped": False, "ok": False, "reason": "exclude_restore_failed", "commands": commands, "exclude_paths": existing_excludes}
    state_sync_paths = existing_git_pathspecs(project_root, STATE_SYNC_PATHS)
    if not state_sync_paths:
        return {"skipped": True, "reason": "no_existing_state_sync_paths", "commands": commands}
    status = git_run(project_root, ["status", "--porcelain", "--", *state_sync_paths])
    if status.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "status_failed", "stderr": status.stderr}
    if not status.stdout.strip():
        return {"skipped": True, "reason": "no_task_manager_changes", "commands": commands, "state_sync_paths": state_sync_paths}
    referenced_reports = referenced_worker_report_paths(project_root)
    unreferenced_reports = sorted(
        {
            porcelain_path(line).replace("\\", "/")
            for line in status.stdout.splitlines()
            if porcelain_path(line).replace("\\", "/").startswith(f"{WORKER_REPORT_SYNC_ROOT}/")
            and porcelain_path(line).replace("\\", "/") not in referenced_reports
        }
    )
    if unreferenced_reports:
        return {
            "skipped": False,
            "ok": False,
            "reason": "unreferenced_worker_report_paths",
            "paths": unreferenced_reports,
            "commands": commands,
        }
    branch = branch_name_from_ref(base_ref)
    add = run_git(["add", "-A", "--", *state_sync_paths])
    if add.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "add_failed", "commands": commands, "state_sync_paths": state_sync_paths}
    commit = run_git(["commit", "-m", f"chore(agent): record {run_class} state"])
    if commit.returncode != 0:
        text_out = (commit.stdout + commit.stderr).lower()
        if "nothing to commit" in text_out or "no changes" in text_out:
            return {"skipped": True, "reason": "nothing_to_commit", "commands": commands}
        return {"skipped": False, "ok": False, "reason": "commit_failed", "commands": commands}
    push = run_git(["push", "origin", f"HEAD:{branch}"])
    if push.returncode == 0:
        return {"skipped": False, "ok": True, "reason": "pushed", "commands": commands}

    push_text = f"{push.stdout}\n{push.stderr}".lower()
    if "non-fast-forward" not in push_text and "fetch first" not in push_text:
        return {"skipped": False, "ok": False, "reason": "push_failed", "commands": commands}

    fetch = run_git(["fetch", "origin", branch])
    if fetch.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "push_failed_fetch_failed", "commands": commands}
    untracked_cleanup = remove_identical_untracked_ref_files(project_root, f"origin/{branch}")
    if not untracked_cleanup.get("ok"):
        return {
            "skipped": False,
            "ok": False,
            "reason": "push_failed_untracked_cleanup_failed",
            "commands": commands,
            "untracked_cleanup": untracked_cleanup,
        }
    transient_process_logs = preserve_tracked_transient_process_logs(project_root)
    if transient_process_logs.get("ok") is not True:
        return {
            "skipped": False,
            "ok": False,
            "reason": "push_failed_transient_process_logs_preservation_failed",
            "commands": commands,
            "untracked_cleanup": untracked_cleanup,
            "transient_process_logs": transient_process_logs,
        }
    rebase = run_git(["rebase", f"origin/{branch}"])
    if rebase.returncode != 0:
        try:
            conflict_resolution = resolve_state_sync_merge_conflicts(project_root)
        except (OSError, ValueError) as exc:
            conflict_resolution = {"ok": False, "reason": "state_conflict_resolution_failed", "error": str(exc)}
        if conflict_resolution.get("ok") is True and conflict_resolution.get("resolved_paths"):
            continue_rebase = run_git(["-c", "core.editor=true", "rebase", "--continue"])
            if continue_rebase.returncode == 0:
                retry = run_git(["push", "origin", f"HEAD:{branch}"])
                return finalize_with_transient_process_log_restore(project_root, {
                    "skipped": False,
                    "ok": retry.returncode == 0,
                    "reason": (
                        "pushed_after_conflict_resolution"
                        if retry.returncode == 0
                        else "push_failed_after_conflict_resolution"
                    ),
                    "commands": commands,
                    "untracked_cleanup": untracked_cleanup,
                    "conflict_resolution": conflict_resolution,
                }, transient_process_logs)
        rebase_head = run_git(["rev-parse", "--verify", "-q", "REBASE_HEAD"])
        if rebase_head.returncode != 0:
            return finalize_with_transient_process_log_restore(project_root, {
                "skipped": False,
                "ok": False,
                "reason": "push_failed_rebase_not_started",
                "commands": commands,
                "untracked_cleanup": untracked_cleanup,
                "conflict_resolution": conflict_resolution,
            }, transient_process_logs)
        abort = run_git(["rebase", "--abort"])
        if abort.returncode != 0:
            return finalize_with_transient_process_log_restore(project_root, {
                "skipped": False,
                "ok": False,
                "reason": "push_failed_rebase_abort_failed",
                "commands": commands,
                "untracked_cleanup": untracked_cleanup,
                "conflict_resolution": conflict_resolution,
            }, transient_process_logs)
        archive_branch = (
            "archive/status-orchestrator-state-"
            f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{os.getpid()}"
        )
        archive = run_git(["branch", archive_branch, "HEAD"])
        if archive.returncode != 0:
            return finalize_with_transient_process_log_restore(project_root, {
                "skipped": False,
                "ok": False,
                "reason": "push_failed_rebase_archive_failed",
                "archive_branch": archive_branch,
                "commands": commands,
                "untracked_cleanup": untracked_cleanup,
                "conflict_resolution": conflict_resolution,
            }, transient_process_logs)
        realign = run_git(["reset", "--hard", f"origin/{branch}"])
        return finalize_with_transient_process_log_restore(project_root, {
            "skipped": False,
            "ok": realign.returncode == 0,
            "deferred": realign.returncode == 0,
            "reason": "concurrent_state_archived_and_realigned" if realign.returncode == 0 else "push_failed_rebase_realign_failed",
            "archive_branch": archive_branch,
            "commands": commands,
            "untracked_cleanup": untracked_cleanup,
            "conflict_resolution": conflict_resolution,
        }, transient_process_logs)
    retry = run_git(["push", "origin", f"HEAD:{branch}"])
    return finalize_with_transient_process_log_restore(project_root, {
        "skipped": False,
        "ok": retry.returncode == 0,
        "reason": "pushed_after_base_sync" if retry.returncode == 0 else "push_failed_after_base_sync",
        "commands": commands,
        "untracked_cleanup": untracked_cleanup,
    }, transient_process_logs)


def pr_change_intake_command(project_root: Path, *, fetch: bool, apply: bool, output_dir: Path | None = None) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("pr_change_intake_cycle.py")),
        "--project-root",
        str(project_root),
        "--repo",
        github_repo_slug(project_root, "coindmit-cmyk/ai-project-agent"),
        "--state",
        "OPEN",
        "--limit",
        "50",
        "--discovery-mode",
        "diff-only",
        "--skip-empty-report",
        "--json",
    ]
    if output_dir is not None:
        cmd.extend(["--output-dir", str(output_dir)])
    if fetch:
        cmd.append("--fetch")
    if apply:
        cmd.extend(["--apply-events", "--apply-project-state-intake"])
    return cmd


def full_intake_command(
    project_root: Path,
    *,
    runtime_root: str,
    base_ref: str,
    fetch: bool,
    apply: bool,
    skip_pr_intake: bool,
    apply_project_rules_remediation: bool,
    skip_project_rules: bool = False,
    skip_deep_intake: bool = False,
    skip_repository_hygiene: bool = False,
    write_state: bool = False,
    output_dir: Path | None = None,
) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("full_intake_automation_cycle.py")),
        "--project-root",
        str(project_root),
        "--runtime-root",
        runtime_root,
        "--repo",
        github_repo_slug(project_root, "coindmit-cmyk/ai-project-agent"),
        "--base-ref",
        base_ref,
        "--json",
    ]
    resolved_output_dir = output_dir or full_intake_runtime_output_dir(runtime_root, project_root)
    cmd.extend(["--output-dir", str(resolved_output_dir)])
    if fetch:
        cmd.append("--fetch")
    if apply:
        cmd.append("--apply")
    if write_state:
        cmd.append("--write-state")
    if skip_pr_intake:
        cmd.append("--skip-pr-intake")
    if skip_deep_intake:
        if skip_repository_hygiene:
            cmd.append("--skip-repository-hygiene")
        else:
            cmd.append("--fast-pr-registry")
        cmd.extend(
            [
                "--skip-task-docs",
                "--skip-design-handoff",
                "--skip-project-rules",
                "--skip-packet-selection",
                "--skip-owner-gap-event",
            ]
        )
    elif skip_project_rules:
        cmd.append("--skip-project-rules")
    if apply_project_rules_remediation:
        cmd.append("--apply-project-rules-remediation")
    return cmd


def design_handoff_merge_command(project_root: Path, *, base_ref: str, apply: bool) -> list[str]:
    base_branch = base_ref.removeprefix("refs/remotes/").removeprefix("origin/")
    cmd = [
        sys.executable,
        str(script_path("auto_design_handoff_merge.py")),
        "--project-root",
        str(project_root),
        "--repo",
        github_repo_slug(project_root, "coindmit-cmyk/ai-project-agent"),
        "--base-branch",
        base_branch,
        "--max-merges",
        "1",
        "--json",
    ]
    if apply:
        cmd.append("--apply")
    return cmd


def design_handoff_merge_has_action(payload: dict[str, Any] | None) -> bool:
    return isinstance(payload, dict) and int(payload.get("candidate_count") or 0) > 0


def accepted_integration_reconcile_command(
    project_root: Path,
    *,
    base_ref: str,
    fetch: bool,
    apply: bool,
) -> list[str]:
    base_branch = base_ref.removeprefix("refs/remotes/").removeprefix("origin/")
    cmd = [
        sys.executable,
        str(script_path("accepted_integration_reconciler.py")),
        "--project-root",
        str(project_root),
        "--repo",
        github_repo_slug(project_root, "coindmit-cmyk/ai-project-agent"),
        "--base-branch",
        base_branch,
        "--base-ref",
        base_ref,
        "--max-items",
        "5",
        "--json",
    ]
    if fetch:
        cmd.append("--fetch")
    if apply:
        cmd.append("--apply")
    return cmd


def accepted_integration_reconcile_has_action(payload: dict[str, Any] | None) -> bool:
    return isinstance(payload, dict) and int(payload.get("ready_count") or 0) > 0


def accepted_integration_reconcile_applied(payload: dict[str, Any] | None) -> bool:
    return isinstance(payload, dict) and int(payload.get("applied_count") or 0) > 0


def accepted_integration_state_sync_persisted(state_sync: Any) -> bool:
    return (
        isinstance(state_sync, dict)
        and state_sync.get("ok") is True
        and state_sync.get("deferred") is not True
    )


def integration_checkpoint_persisted(state_sync: Any) -> bool:
    if not isinstance(state_sync, dict) or state_sync.get("deferred") is True:
        return False
    if state_sync.get("ok") is True:
        return True
    return state_sync.get("skipped") is True and str(state_sync.get("reason") or "") in {
        "no_task_manager_changes",
        "nothing_to_commit",
    }


def full_intake_has_action(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    counters = payload.get("counters")
    if not isinstance(counters, dict):
        return False
    actionable_keys = {
        "pr_missing",
        "repository_tasks_staged",
        "repository_tasks_updated",
        "repository_tasks_superseded",
        "task_docs_imported",
        "design_imported",
        "project_rules_staged",
        "packet_selected",
        "packet_cleanup_selected",
        "owner_gap_count",
    }
    return any(int(counters.get(key) or 0) > 0 for key in actionable_keys)


def parse_full_intake_payload(stdout: str) -> dict[str, Any] | None:
    try:
        payload = json.loads(stdout)
    except (TypeError, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, dict) else None


def full_intake_dry_run_allows_apply(payload: dict[str, Any] | None, exit_code: int) -> bool:
    try:
        has_action = full_intake_has_action(payload)
    except (TypeError, ValueError):
        return False
    if not has_action:
        return False
    return full_intake_result_is_apply_safe(payload, exit_code)


def priority_execution_gate(project_root: Path) -> dict[str, Any]:
    queue_path = task_manager_dir(project_root) / "task_queue.json"
    try:
        queue = load_json(queue_path)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        return {
            "defer_deep_intake": True,
            "reason": "task_queue_unavailable",
            "error": str(exc),
            "counts": {},
        }
    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        return {
            "defer_deep_intake": True,
            "reason": "task_queue_invalid",
            "counts": {},
        }

    terminal = {"done", "finalized", "failed", "postponed", "stale_or_superseded", "duplicate_linked"}
    active_statuses = {"in_progress", "claimed", "working"}
    integration_statuses = {"pending", "pending_checks", "integration_ready"}
    counts = {"active_claims": 0, "integration_pending": 0, "worker_ready": 0}
    for task in tasks:
        if not isinstance(task, dict):
            continue
        status = str(task.get("status") or "").strip().lower()
        if status in terminal:
            continue
        integration_status = str(task.get("integration_status") or "").strip().lower()
        lock = task.get("lock")
        lock_state = str(lock.get("state") if isinstance(lock, dict) else lock or "free").strip().lower()
        if status in active_statuses or lock_state in {"active", "claimed", "working"}:
            counts["active_claims"] += 1
        if status in {"agent_done", "integration_requested", "integration_ready"} or integration_status in integration_statuses:
            counts["integration_pending"] += 1
        if (
            task.get("worker_ready") is True
            and str(task.get("dispatcher_decision") or "").strip().lower() == "worker_ready"
            and lock_state in {"", "free", "released", "unlocked"}
        ):
            counts["worker_ready"] += 1
    defer = any(counts.values())
    return {
        "defer_deep_intake": defer,
        "reason": "priority_execution_backlog" if defer else "priority_execution_idle",
        "counts": counts,
    }


def full_intake_schedule(
    rules_scan_gate: dict[str, Any],
    fast_action: bool,
    priority_gate: dict[str, Any] | None = None,
) -> dict[str, Any]:
    deep_due = rules_scan_gate.get("due") is True
    deep_deferred = bool(
        deep_due
        and isinstance(priority_gate, dict)
        and priority_gate.get("defer_deep_intake") is True
    )
    run_deep = deep_due and not deep_deferred
    return {
        "allow_apply": bool(fast_action or run_deep),
        "deep_due": deep_due,
        "deep_deferred": deep_deferred,
        "skip_deep_intake": not run_deep,
        "apply_project_rules_remediation": run_deep,
        "priority_execution_gate": priority_gate or {},
        "reason": (
            "priority_execution_backlog"
            if deep_deferred
            else
            str(rules_scan_gate.get("reason") or "cadence_due")
            if run_deep
            else "fast_intake_only"
            if fast_action
            else str(rules_scan_gate.get("reason") or "cadence_not_due")
        ),
    }


def full_intake_result_is_apply_safe(payload: dict[str, Any] | None, exit_code: int) -> bool:
    if not isinstance(payload, dict):
        return False
    if exit_code == 0:
        return True
    errors = payload.get("errors")
    return (
        isinstance(errors, list)
        and bool(errors)
        and all(
            isinstance(error, dict) and error.get("section") == "documentation_health"
            for error in errors
        )
    )


def dispatcher_queue_repair_command(project_root: Path, *, runtime_root: str, apply: bool) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("workspace_queue_migration_pipeline.py")),
        "--queue",
        str(task_manager_dir(project_root) / "task_queue.json"),
        "--locks",
        str(task_manager_dir(project_root) / "agent_locks.json"),
        "--output-dir",
        str(Path(runtime_root).expanduser() / "dispatcher-queue-repair" / project_root.name),
        "--prefix",
        "task_queue",
        "--apply-dispatcher-repairs",
        "--context-ref",
        str(task_manager_dir(project_root) / "task_queue.json"),
        "--verified-by",
        "status_orchestrator.py",
        "--json",
    ]
    if apply:
        cmd.append("--apply-to-source")
    return cmd


def dependency_reconciliation_command(project_root: Path, *, apply: bool) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("normalize_task_packets.py")),
        "--project-root",
        str(project_root),
        "--queue",
        str(task_manager_dir(project_root) / "task_queue.json"),
        "--locks",
        str(task_manager_dir(project_root) / "agent_locks.json"),
        "--dependencies-only",
        "--json",
    ]
    if apply:
        cmd.append("--apply")
    return cmd


def dependency_reconciliation_has_action(payload: dict[str, Any] | None) -> bool:
    return isinstance(payload, dict) and payload.get("changed") is True and int(payload.get("change_count") or 0) > 0


def dependency_reconciliation_applied(payload: dict[str, Any] | None) -> bool:
    return (
        isinstance(payload, dict)
        and payload.get("dry_run") is False
        and payload.get("changed") is True
        and int(payload.get("change_count") or 0) > 0
    )


def dispatcher_packet_repair_command(project_root: Path, *, apply: bool) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("dispatcher_packet_repair.py")),
        "--queue",
        str(task_manager_dir(project_root) / "task_queue.json"),
        "--json",
    ]
    if apply:
        cmd.append("--apply")
    return cmd


def dispatcher_packet_repair_has_action(payload: dict[str, Any] | None) -> bool:
    return isinstance(payload, dict) and (
        int(payload.get("repaired_count") or 0) > 0
        or int(payload.get("cleaned_count") or 0) > 0
    )


def dispatcher_packet_repair_applied(payload: dict[str, Any] | None) -> bool:
    return (
        isinstance(payload, dict)
        and payload.get("dry_run") is False
        and dispatcher_packet_repair_has_action(payload)
    )


def dispatcher_integration_repair_command(project_root: Path, *, base_ref: str, apply: bool) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("dispatcher_integration_repair.py")),
        "--project-root",
        str(project_root),
        "--base-ref",
        base_ref,
        "--json",
    ]
    if apply:
        cmd.append("--apply")
    return cmd


def dispatcher_integration_repair_has_action(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    actionable_counters = (
        "repaired_count",
        "closed_count",
        "classified_count",
        "worker_ready_repaired_count",
        "repair_packets_created_count",
        "repair_packets_linked_count",
    )
    return any(int(payload.get(key) or 0) > 0 for key in actionable_counters)


def dispatcher_queue_repair_has_action(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    normalization = payload.get("normalization") if isinstance(payload.get("normalization"), dict) else {}
    dependency_reconciliation = (
        payload.get("dependency_reconciliation")
        if isinstance(payload.get("dependency_reconciliation"), dict)
        else {}
    )
    packet_repair = payload.get("packet_repair") if isinstance(payload.get("packet_repair"), dict) else {}
    dispatcher_repair = payload.get("dispatcher_repair") if isinstance(payload.get("dispatcher_repair"), dict) else {}
    dispatcher_packet = dispatcher_repair.get("packet_repair") if isinstance(dispatcher_repair.get("packet_repair"), dict) else {}
    return (
        int(normalization.get("change_count") or 0) > 0
        or int(dependency_reconciliation.get("change_count") or 0) > 0
        or int(packet_repair.get("repaired_count") or 0) > 0
        or int(packet_repair.get("cleaned_count") or 0) > 0
        or int(dispatcher_packet.get("repaired_count") or 0) > 0
    )


def dispatcher_queue_repair_applied(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    source_apply = payload.get("source_apply")
    return isinstance(source_apply, dict) and source_apply.get("applied") is True


def dispatcher_queue_repair_apply_ran(action: dict[str, Any]) -> bool:
    repair = action.get("dispatcher_queue_repair")
    if not isinstance(repair, dict):
        return False
    dependency_apply = (
        repair.get("dependency_reconciliation", {}).get("apply")
        if isinstance(repair.get("dependency_reconciliation"), dict)
        else None
    )
    if (
        isinstance(dependency_apply, dict)
        and int(dependency_apply.get("exit_code") or 0) == 0
        and dependency_reconciliation_applied(dependency_apply.get("parsed_json"))
    ):
        return True
    packet_apply = repair.get("packet_apply")
    if (
        isinstance(packet_apply, dict)
        and int(packet_apply.get("exit_code") or 0) == 0
        and dispatcher_packet_repair_applied(packet_apply.get("parsed_json"))
    ):
        return True
    integration_apply = repair.get("integration_apply")
    if (
        isinstance(integration_apply, dict)
        and int(integration_apply.get("exit_code") or 0) == 0
        and dispatcher_integration_repair_has_action(integration_apply.get("parsed_json"))
    ):
        return True
    apply_result = repair.get("apply")
    return (
        isinstance(apply_result, dict)
        and int(apply_result.get("exit_code") or 0) == 0
        and dispatcher_queue_repair_applied(apply_result.get("parsed_json"))
    )


def worker_result_handoff_command(project_root: Path, *, base_ref: str, fetch: bool, apply: bool, output: Path | None = None) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("worker_result_handoff_gate.py")),
        "--project-root",
        str(project_root),
        "--base-ref",
        base_ref,
        "--json",
    ]
    if output is not None:
        cmd.extend(["--output", str(output)])
    if fetch:
        cmd.append("--fetch")
    if apply:
        cmd.append("--apply-events")
    return cmd


def dead_worker_claims_command(
    project_root: Path,
    *,
    apply: bool,
    runtime_root: str | None = None,
    worker_pool_systemd_unit: str | None = None,
    worker_pool_systemd_scope: str = "user",
) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("release_dead_worker_claims.py")),
        "--project-root",
        str(project_root),
        "--from-worker-pool-last-plan",
        "--allow-active-worker-pool",
        "--release-terminal-task-locks",
        "--close-terminal-task-residue",
        "--release-duplicate-active-locks",
        "--close-recorded-in-progress-claims",
        "--close-blocked-model-limit-claims",
        "--reason",
        "worker pool finished without a live worker process or worker result",
        "--json",
    ]
    if runtime_root:
        cmd.extend(
            [
                "--worker-pool-plan",
                str(
                    Path(runtime_root).expanduser()
                    / "worker-pool-plans"
                    / project_root.name
                    / "latest.json"
                ),
            ]
        )
    if worker_pool_systemd_unit:
        cmd.extend(
            [
                "--worker-pool-systemd-unit",
                worker_pool_systemd_unit,
                "--worker-pool-systemd-scope",
                worker_pool_systemd_scope,
            ]
        )
    if apply:
        cmd.append("--apply")
    return cmd


def expired_locks_command(project_root: Path, *, apply: bool) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("release_expired_locks.py")),
        "--project-root",
        str(project_root),
        "--released-by",
        "status_orchestrator.py",
    ]
    if apply:
        cmd.append("--apply")
    return cmd


def expired_locks_has_action(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    return len(payload.get("expired_task_ids") or []) > 0


def dead_worker_claims_has_action(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    keys = (
        "released_count",
        "lock_change_count",
        "terminal_lock_release_count",
        "terminal_queue_residue_count",
        "duplicate_lock_release_count",
        "recorded_claim_close_count",
        "blocked_model_limit_close_count",
    )
    return any(int(payload.get(key) or 0) > 0 for key in keys)


def dead_worker_claims_requires_cycle_restart(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return True
    material_keys = (
        "released_count",
        "terminal_queue_residue_count",
        "duplicate_lock_release_count",
        "recorded_claim_close_count",
        "blocked_model_limit_close_count",
    )
    if any(int(payload.get(key) or 0) > 0 for key in material_keys):
        return True
    lock_changes = int(payload.get("lock_change_count") or 0)
    terminal_lock_releases = int(payload.get("terminal_lock_release_count") or 0)
    return lock_changes > terminal_lock_releases


def worker_pool_systemd_is_inactive(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    evidence = payload.get("worker_pool_systemd_evidence")
    return isinstance(evidence, dict) and evidence.get("inactive") is True


def worker_result_sync_command(project_root: Path, *, base_ref: str, fetch: bool, apply: bool) -> list[str]:
    cmd = [
        sys.executable,
        str(script_path("sync_worker_results.py")),
        "--project-root",
        str(project_root),
        "--base-ref",
        base_ref,
        "--json",
    ]
    if fetch:
        cmd.append("--fetch")
    if apply:
        cmd.append("--apply")
    return cmd


def worker_result_sync_has_action(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    return any(
        int(payload.get(key) or 0) > 0
        for key in ("change_count", "lock_change_count", "queue_lock_repair_count")
    )


def run_result_handoff_cycle(
    project_root: Path,
    *,
    runtime_root: str,
    base_ref: str,
    fetch: bool,
    apply: bool,
    output_name: str = "status-orchestrator.json",
) -> dict[str, Any]:
    sync_dry_cmd = worker_result_sync_command(project_root, base_ref=base_ref, fetch=fetch, apply=False)
    sync_dry_code, sync_dry_stdout, sync_dry_stderr = run(sync_dry_cmd)
    result: dict[str, Any] = {
        "sync_dry_run": {
            "command": sync_dry_cmd,
            "exit_code": sync_dry_code,
            "stdout": sync_dry_stdout,
            "stderr": sync_dry_stderr,
        }
    }
    sync_dry_payload = None
    if sync_dry_code == 0:
        try:
            sync_dry_payload = json.loads(sync_dry_stdout)
            result["sync_dry_run"]["parsed_json"] = sync_dry_payload
        except json.JSONDecodeError:
            pass
    else:
        append_log(project_root, "orchestrator", "worker_result_sync_dry_run_failed", severity="error", stderr=sync_dry_stderr)

    if apply and worker_result_sync_has_action(sync_dry_payload):
        sync_apply_cmd = worker_result_sync_command(project_root, base_ref=base_ref, fetch=False, apply=True)
        sync_code, sync_stdout, sync_stderr = run(sync_apply_cmd)
        result["sync_apply"] = {"command": sync_apply_cmd, "exit_code": sync_code, "stdout": sync_stdout, "stderr": sync_stderr}
        if sync_code != 0:
            append_log(project_root, "orchestrator", "worker_result_sync_failed", severity="error", stderr=sync_stderr)
        else:
            try:
                result["sync_apply"]["parsed_json"] = json.loads(sync_stdout)
            except json.JSONDecodeError:
                pass

    handoff_output = Path(runtime_root).expanduser() / "worker-result-handoff" / output_name
    handoff_dry_payload = None
    for attempt in range(max(1, HANDOFF_RETRY_COUNT + 1)):
        if attempt > 0 and HANDOFF_RETRY_DELAY_SECONDS > 0:
            time.sleep(HANDOFF_RETRY_DELAY_SECONDS)
        handoff_dry_cmd = worker_result_handoff_command(project_root, base_ref=base_ref, fetch=fetch, apply=False, output=handoff_output)
        handoff_dry_code, handoff_dry_stdout, handoff_dry_stderr = run(handoff_dry_cmd)
        dry_key = "dry_run" if attempt == 0 else f"dry_run_retry_{attempt}"
        result[dry_key] = {
            "command": handoff_dry_cmd,
            "exit_code": handoff_dry_code,
            "stdout": handoff_dry_stdout,
            "stderr": handoff_dry_stderr,
        }
        handoff_dry_payload = None
        if handoff_dry_code == 0:
            try:
                handoff_dry_payload = json.loads(handoff_dry_stdout)
                result[dry_key]["parsed_json"] = handoff_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(
                project_root,
                "orchestrator",
                "worker_result_handoff_dry_run_failed",
                severity="error",
                stderr=handoff_dry_stderr,
            )
            break
        emitted_count = int(handoff_dry_payload.get("emitted_count") or 0) if isinstance(handoff_dry_payload, dict) else 0
        if not apply or emitted_count > 0:
            break
        if not fetch:
            break

    if apply and isinstance(handoff_dry_payload, dict) and int(handoff_dry_payload.get("emitted_count") or 0) > 0:
        handoff_apply_cmd = worker_result_handoff_command(project_root, base_ref=base_ref, fetch=False, apply=True)
        handoff_code, handoff_stdout, handoff_stderr = run(handoff_apply_cmd)
        result["apply"] = {"command": handoff_apply_cmd, "exit_code": handoff_code, "stdout": handoff_stdout, "stderr": handoff_stderr}
        if handoff_code != 0:
            append_log(project_root, "orchestrator", "worker_result_handoff_failed", severity="error", stderr=handoff_stderr)
        else:
            try:
                result["apply"]["parsed_json"] = json.loads(handoff_stdout)
            except json.JSONDecodeError:
                pass
    return result


def update_process_state(project_root: Path, role: str, state: str, reason: str | None, details: dict[str, Any] | None = None) -> None:
    path = task_manager_dir(project_root) / "agent_process_state.json"
    data = load_json(path)
    if not data:
        data = {"schema_version": 1, "project": project_root.name, "processes": {}}
    data["updated_at"] = utc_now()
    data["project"] = project_root.name
    processes = data.setdefault("processes", {})
    proc = processes.setdefault(role, {})
    proc["state"] = state
    proc["reason"] = reason
    if details:
        proc["last_error"] = details
    elif state in {"running", "completed", "idle"}:
        proc.pop("last_error", None)
    if state == "running":
        proc["last_started_at"] = data["updated_at"]
    if state in {"completed", "blocked", "needs_human", "failed_retryable", "failed_terminal"}:
        proc["last_finished_at"] = data["updated_at"]
    write_json(path, data)


def command_failure_details(command: list[str] | None, run_class: str, exit_code: int, stdout: str = "", stderr: str = "") -> dict[str, Any]:
    return {
        "run_class": run_class,
        "exit_code": exit_code,
        "command": command or [],
        "stdout_tail": str(stdout or "")[-4000:],
        "stderr_tail": str(stderr or "")[-4000:],
    }


def record_successful_action(project_root: Path, decision: dict[str, Any], role: str) -> None:
    consume_events(
        task_manager_dir(project_root) / "agent_events.jsonl",
        [str(item) for item in decision.get("trigger_event_ids", [])],
        role,
    )
    update_activity(task_manager_dir(project_root) / "agent_activity_state.json", decision)
    update_process_state(project_root, PROCESS_BY_ROLE.get(role, role), "completed", str(decision.get("reason")))


def main() -> int:
    parser = argparse.ArgumentParser(description="Run one status-driven orchestrator cycle.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--push-ref")
    parser.add_argument("--worker-base-ref", help="Clean product/code ref for isolated worker worktrees. Defaults to --base-ref.")
    parser.add_argument("--worker-context-ref", help="Task-manager/context ref copied into worker worktrees. Defaults to --base-ref when --worker-base-ref differs.")
    parser.add_argument("--integration-base-ref", help="Clean product/base ref for integrator preflight. Defaults to --worker-base-ref or --base-ref.")
    parser.add_argument("--machine-id", default="aistudio")
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--open-pr-stack", type=int, default=0)
    parser.add_argument("--max-total-workers", type=int, default=0, help="Maximum worker lanes for worker_pool_manager; 0 uses limit-aware dynamic capacity.")
    parser.add_argument("--worker-pool-systemd-unit", help="Fixed per-project transient systemd service used to run worker_pool_manager asynchronously on Linux.")
    parser.add_argument("--worker-pool-systemd-scope", choices=("user", "system"), default="user")
    parser.add_argument("--worker-pool-systemd-after-unit", help="Optional orchestrator service that must finish before the worker pool starts.")
    parser.add_argument("--lock-ttl-minutes", type=int, default=30)
    parser.add_argument("--no-fetch", action="store_true", help="Do not refresh remote refs before worker claims.")
    parser.add_argument("--no-pr-intake", action="store_true", help="Skip the apply-mode PR Change Intake pre-scheduler scan.")
    parser.add_argument("--no-design-pr-auto-merge", action="store_true", help="Skip safe Product Design PR auto-merge before Full Intake.")
    parser.add_argument("--no-accepted-integration-reconcile", action="store_true", help="Skip evidence-gated reconciliation of already merged Manual Integrator PRs.")
    parser.add_argument("--no-full-intake-cycle", action="store_true", help="Skip the apply-mode full intake automation bridge.")
    parser.add_argument("--no-dispatcher-queue-repair", action="store_true", help="Skip automatic queue normalization and Dispatcher repair application.")
    parser.add_argument("--no-result-handoff", action="store_true", help="Skip the apply-mode Worker Result Handoff pre-scheduler scan.")
    parser.add_argument("--no-project-rules-remediation", action="store_true", help="Do not apply approved project-rules remediation rows inside the full intake bridge.")
    parser.add_argument(
        "--project-rules-scan-interval-minutes",
        type=int,
        default=PROJECT_RULES_SCAN_INTERVAL_MINUTES,
        help="Minimum interval between deep Project Rules/Map scans; fast intake still runs every cycle.",
    )
    parser.add_argument("--local-llm-cycle", action="store_true", help="Run the local LLM evidence+planning cycle instead of scheduler-selected work.")
    parser.add_argument("--auto-local-llm-pre-worker", action="store_true", help="On the approved remote host, preempt an eligible worker run with one policy-enabled local LLM triage cycle.")
    parser.add_argument("--apply", action="store_true", help="Run selected script/worker actions. Default is decision-only.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    pre_apply_sync = sync_project_root_before_apply(project_root, args.push_ref or args.base_ref) if args.apply and not args.no_fetch else None
    pre_apply_tracked_generated_state = tracked_pre_apply_generated_state_recovery_evidence(project_root, pre_apply_sync)
    pre_apply_generated_state_validation = None
    generated_state_recovery_ready = can_recover_pre_apply_generated_state(pre_apply_sync)
    if generated_state_recovery_ready:
        pre_apply_generated_state_validation = validate_pre_apply_generated_state(project_root, pre_apply_sync or {})
        generated_state_recovery_ready = pre_apply_generated_state_validation.get("ok") is True
    elif pre_apply_tracked_generated_state.get("ok") is True:
        pre_apply_generated_state_validation = pre_apply_tracked_generated_state["validation"]
        generated_state_recovery_ready = True
    pre_apply_quarantine = None
    if not generated_state_recovery_ready and can_quarantine_pre_apply_generated_state(pre_apply_sync):
        pre_apply_quarantine = quarantine_pre_apply_generated_state(project_root, pre_apply_sync)
        if pre_apply_quarantine.get("ok") is True:
            pre_apply_sync = sync_project_root_before_apply(project_root, args.push_ref or args.base_ref)
    pre_apply_writer_evidence = interrupted_writer_recovery_evidence(project_root, pre_apply_sync)
    pre_apply_recovery = None
    if generated_state_recovery_ready or pre_apply_writer_evidence.get("ok") is True:
        pre_apply_recovery = sync_task_manager_state(
            project_root,
            "integration_run",
            args.push_ref or args.base_ref,
        )
        if pre_apply_recovery.get("ok") is True:
            pre_apply_sync = sync_project_root_before_apply(project_root, args.push_ref or args.base_ref)
    if pre_apply_sync is not None and pre_apply_sync.get("ok") is False:
        result = {
            "decision": {"should_run": False, "run_class": "scan_only", "reason": "pre_apply_sync_failed"},
            "pre_apply_sync": pre_apply_sync,
            "exit_code": 1,
        }
        if pre_apply_recovery is not None:
            result["pre_apply_recovery"] = pre_apply_recovery
        if pre_apply_generated_state_validation is not None:
            result["pre_apply_generated_state_validation"] = pre_apply_generated_state_validation
        if pre_apply_tracked_generated_state.get("reason") != "pre_apply_dirty_paths_not_reported":
            result["pre_apply_tracked_generated_state"] = pre_apply_tracked_generated_state
        if pre_apply_writer_evidence.get("reason") != "pre_apply_dirty_paths_not_reported":
            result["pre_apply_writer_evidence"] = pre_apply_writer_evidence
        if pre_apply_quarantine is not None:
            result["pre_apply_quarantine"] = pre_apply_quarantine
        print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"pre-apply sync failed: {pre_apply_sync.get('reason')}")
        return 1
    integration_base_ref = args.integration_base_ref or args.worker_base_ref or args.base_ref
    run_id = f"run-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}"
    locked = False
    lock_released = False
    holder = None
    design_handoff_merge_result = None
    accepted_integration_reconcile_result = None
    full_intake_result = None
    dispatcher_queue_repair_result = None
    result_handoff_result = None
    expired_locks_result = None
    dead_worker_claims_result = None
    runtime_worker_worktree_cleanup = None
    runtime_full_intake_cleanup = (
        cleanup_full_intake_runtime_output(args.runtime_root, project_root=project_root)
        if args.apply
        else None
    )
    rules_scan_gate = project_rules_scan_gate(
        args.runtime_root,
        project_root,
        interval_minutes=args.project_rules_scan_interval_minutes,
    )
    if args.apply and not args.no_full_intake_cycle:
        locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
        if not locked:
            result = {"decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"}, "blocked_by_run_id": holder}
            print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
            return 0
        runtime_worker_worktree_cleanup = cleanup_runtime_worker_worktrees(args.runtime_root)
    if args.apply and not args.no_full_intake_cycle and not args.no_design_pr_auto_merge:
        design_dry_cmd = design_handoff_merge_command(project_root, base_ref=args.base_ref, apply=False)
        design_dry_code, design_dry_stdout, design_dry_stderr = run(design_dry_cmd)
        design_handoff_merge_result = {
            "dry_run": {"command": design_dry_cmd, "exit_code": design_dry_code, "stdout": design_dry_stdout, "stderr": design_dry_stderr}
        }
        design_dry_payload = None
        if design_dry_code == 0:
            try:
                design_dry_payload = json.loads(design_dry_stdout)
                design_handoff_merge_result["dry_run"]["parsed_json"] = design_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(project_root, "orchestrator", "design_handoff_merge_dry_run_failed", severity="error", stderr=design_dry_stderr)
        if design_handoff_merge_has_action(design_dry_payload):
            design_apply_cmd = design_handoff_merge_command(project_root, base_ref=args.base_ref, apply=True)
            design_code, design_stdout, design_stderr = run(design_apply_cmd)
            design_handoff_merge_result["apply"] = {
                "command": design_apply_cmd,
                "exit_code": design_code,
                "stdout": design_stdout,
                "stderr": design_stderr,
            }
            if design_code != 0:
                append_log(project_root, "orchestrator", "design_handoff_merge_failed", severity="error", stderr=design_stderr)
            else:
                try:
                    design_handoff_merge_result["apply"]["parsed_json"] = json.loads(design_stdout)
                except json.JSONDecodeError:
                    pass
    if args.apply and not args.no_full_intake_cycle and not args.no_accepted_integration_reconcile:
        accepted_dry_cmd = accepted_integration_reconcile_command(
            project_root,
            base_ref=args.push_ref or args.base_ref,
            fetch=not args.no_fetch,
            apply=False,
        )
        accepted_dry_code, accepted_dry_stdout, accepted_dry_stderr = run(accepted_dry_cmd)
        accepted_integration_reconcile_result = {
            "dry_run": {
                "command": accepted_dry_cmd,
                "exit_code": accepted_dry_code,
                "stdout": accepted_dry_stdout,
                "stderr": accepted_dry_stderr,
            }
        }
        accepted_dry_payload = None
        if accepted_dry_code == 0:
            try:
                accepted_dry_payload = json.loads(accepted_dry_stdout)
                accepted_integration_reconcile_result["dry_run"]["parsed_json"] = accepted_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(
                project_root,
                "orchestrator",
                "accepted_integration_reconcile_dry_run_failed",
                severity="error",
                stderr=accepted_dry_stderr,
            )
        if accepted_integration_reconcile_has_action(accepted_dry_payload):
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {
                        "decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"},
                        "blocked_by_run_id": holder,
                        "accepted_integration_reconcile": accepted_integration_reconcile_result,
                    }
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            accepted_apply_cmd = accepted_integration_reconcile_command(
                project_root,
                base_ref=args.push_ref or args.base_ref,
                fetch=False,
                apply=True,
            )
            accepted_code, accepted_stdout, accepted_stderr = run(accepted_apply_cmd)
            accepted_integration_reconcile_result["apply"] = {
                "command": accepted_apply_cmd,
                "exit_code": accepted_code,
                "stdout": accepted_stdout,
                "stderr": accepted_stderr,
            }
            accepted_apply_payload = None
            if accepted_code != 0:
                append_log(
                    project_root,
                    "orchestrator",
                    "accepted_integration_reconcile_failed",
                    severity="error",
                    stderr=accepted_stderr,
                )
            else:
                try:
                    accepted_apply_payload = json.loads(accepted_stdout)
                    accepted_integration_reconcile_result["apply"]["parsed_json"] = accepted_apply_payload
                except json.JSONDecodeError:
                    pass
            if accepted_integration_reconcile_applied(accepted_apply_payload):
                accepted_state_sync = sync_task_manager_state(
                    project_root,
                    "finalization_run",
                    args.push_ref or args.base_ref,
                )
                accepted_integration_reconcile_result["state_sync"] = accepted_state_sync
                if not accepted_integration_state_sync_persisted(accepted_state_sync):
                    append_log(
                        project_root,
                        "orchestrator",
                        "accepted_integration_state_sync_failed",
                        severity="error",
                        state_sync=accepted_state_sync,
                    )
                    if locked:
                        release_process_lock(project_root, "status_orchestrator", run_id)
                        lock_released = True
                    result = {
                        "decision": {
                            "should_run": False,
                            "run_class": "scan_only",
                            "reason": "accepted_integration_state_sync_failed",
                        },
                        "accepted_integration_reconcile": accepted_integration_reconcile_result,
                        "lock_released": lock_released,
                        "exit_code": 1,
                        "runtime_cleanup": restore_ignored_tracked_runtime_paths(project_root),
                    }
                    print(
                        json.dumps(result, ensure_ascii=False, indent=2)
                        if args.json
                        else "accepted integration state sync failed"
                    )
                    return 1
    if args.apply and not args.no_full_intake_cycle:
        dry_output_dir = full_intake_runtime_output_dir(args.runtime_root, project_root)
        full_dry_cmd = full_intake_command(
            project_root,
            runtime_root=args.runtime_root,
            base_ref=args.base_ref,
            fetch=not args.no_fetch,
            apply=False,
            skip_pr_intake=bool(args.no_pr_intake),
            apply_project_rules_remediation=not bool(args.no_project_rules_remediation),
            skip_deep_intake=True,
            write_state=True,
            output_dir=dry_output_dir,
        )
        full_dry_code, full_dry_stdout, full_dry_stderr = run(full_dry_cmd)
        full_intake_result = {
            "project_rules_scan_gate": rules_scan_gate,
            "dry_run": {
                "command": full_dry_cmd,
                "exit_code": full_dry_code,
                "stdout": full_dry_stdout,
                "stderr": full_dry_stderr,
            },
        }
        full_dry_payload = parse_full_intake_payload(full_dry_stdout)
        if full_dry_payload is not None:
            full_intake_result["dry_run"]["parsed_json"] = full_dry_payload
        if full_dry_code != 0:
            append_log(project_root, "orchestrator", "full_intake_cycle_dry_run_failed", severity="error", stderr=full_dry_stderr)
        fast_action = full_intake_dry_run_allows_apply(full_dry_payload, full_dry_code)
        priority_gate = priority_execution_gate(project_root)
        intake_schedule = full_intake_schedule(rules_scan_gate, fast_action, priority_gate)
        allow_apply = intake_schedule["allow_apply"] and full_intake_result_is_apply_safe(
            full_dry_payload,
            full_dry_code,
        )
        full_intake_result["deep_intake"] = {
            "due": intake_schedule["deep_due"],
            "deferred": intake_schedule["deep_deferred"],
            "reason": intake_schedule["reason"],
            "fast_action": fast_action,
            "priority_execution_gate": intake_schedule["priority_execution_gate"],
        }
        if full_dry_code != 0:
            full_intake_result["dry_run"]["failure_apply_allowed"] = allow_apply
        if allow_apply:
            full_apply_cmd = full_intake_command(
                project_root,
                runtime_root=args.runtime_root,
                base_ref=args.base_ref,
                fetch=False,
                apply=True,
                skip_pr_intake=bool(args.no_pr_intake),
                apply_project_rules_remediation=(
                    not bool(args.no_project_rules_remediation)
                    and intake_schedule["apply_project_rules_remediation"]
                ),
                skip_deep_intake=intake_schedule["skip_deep_intake"],
                skip_repository_hygiene=False,
                write_state=True,
            )
            full_code, full_stdout, full_stderr = run(full_apply_cmd)
            full_intake_result["apply"] = {"command": full_apply_cmd, "exit_code": full_code, "stdout": full_stdout, "stderr": full_stderr}
            if full_code != 0:
                append_log(project_root, "orchestrator", "full_intake_cycle_failed", severity="error", stderr=full_stderr)
            else:
                try:
                    full_intake_result["apply"]["parsed_json"] = json.loads(full_stdout)
                except json.JSONDecodeError:
                    pass
    if args.apply and not args.no_dispatcher_queue_repair:
        dependency_dry_cmd = dependency_reconciliation_command(project_root, apply=False)
        dependency_dry_code, dependency_dry_stdout, dependency_dry_stderr = run(dependency_dry_cmd)
        dependency_reconciliation_result = {
            "dry_run": {
                "command": dependency_dry_cmd,
                "exit_code": dependency_dry_code,
                "stdout": dependency_dry_stdout,
                "stderr": dependency_dry_stderr,
            }
        }
        dependency_dry_payload = None
        if dependency_dry_code == 0:
            try:
                dependency_dry_payload = json.loads(dependency_dry_stdout)
                dependency_reconciliation_result["dry_run"]["parsed_json"] = dependency_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(
                project_root,
                "orchestrator",
                "dependency_reconciliation_dry_run_failed",
                severity="error",
                stderr=dependency_dry_stderr,
            )
        if dependency_reconciliation_has_action(dependency_dry_payload):
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {
                        "decision": {
                            "should_run": False,
                            "run_class": "scan_only",
                            "reason": "process_lock_active",
                        },
                        "blocked_by_run_id": holder,
                    }
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            dependency_apply_cmd = dependency_reconciliation_command(project_root, apply=True)
            dependency_code, dependency_stdout, dependency_stderr = run(dependency_apply_cmd)
            dependency_reconciliation_result["apply"] = {
                "command": dependency_apply_cmd,
                "exit_code": dependency_code,
                "stdout": dependency_stdout,
                "stderr": dependency_stderr,
            }
            if dependency_code != 0:
                append_log(
                    project_root,
                    "orchestrator",
                    "dependency_reconciliation_failed",
                    severity="error",
                    stderr=dependency_stderr,
                )
            else:
                try:
                    dependency_reconciliation_result["apply"]["parsed_json"] = json.loads(dependency_stdout)
                except json.JSONDecodeError:
                    pass
        packet_dry_cmd = dispatcher_packet_repair_command(project_root, apply=False)
        packet_dry_code, packet_dry_stdout, packet_dry_stderr = run(packet_dry_cmd)
        packet_dry_result = {
            "command": packet_dry_cmd,
            "exit_code": packet_dry_code,
            "stdout": packet_dry_stdout,
            "stderr": packet_dry_stderr,
        }
        packet_dry_payload = None
        packet_apply_result = None
        if packet_dry_code == 0:
            try:
                packet_dry_payload = json.loads(packet_dry_stdout)
                packet_dry_result["parsed_json"] = packet_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(
                project_root,
                "orchestrator",
                "dispatcher_packet_repair_dry_run_failed",
                severity="error",
                stderr=packet_dry_stderr,
            )
        if dispatcher_packet_repair_has_action(packet_dry_payload):
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {
                        "decision": {
                            "should_run": False,
                            "run_class": "scan_only",
                            "reason": "process_lock_active",
                        },
                        "blocked_by_run_id": holder,
                    }
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            packet_apply_cmd = dispatcher_packet_repair_command(project_root, apply=True)
            packet_apply_code, packet_apply_stdout, packet_apply_stderr = run(packet_apply_cmd)
            packet_apply_result = {
                "command": packet_apply_cmd,
                "exit_code": packet_apply_code,
                "stdout": packet_apply_stdout,
                "stderr": packet_apply_stderr,
            }
            if packet_apply_code != 0:
                append_log(
                    project_root,
                    "orchestrator",
                    "dispatcher_packet_repair_failed",
                    severity="error",
                    stderr=packet_apply_stderr,
                )
            else:
                try:
                    packet_apply_result["parsed_json"] = json.loads(packet_apply_stdout)
                except json.JSONDecodeError:
                    pass
        repair_dry_cmd = dispatcher_queue_repair_command(project_root, runtime_root=args.runtime_root, apply=False)
        repair_dry_code, repair_dry_stdout, repair_dry_stderr = run(repair_dry_cmd)
        integration_dry_cmd = dispatcher_integration_repair_command(
            project_root,
            base_ref=args.push_ref or args.base_ref,
            apply=False,
        )
        integration_dry_code, integration_dry_stdout, integration_dry_stderr = run(integration_dry_cmd)
        dispatcher_queue_repair_result = {
            "dependency_reconciliation": dependency_reconciliation_result,
            "packet_dry_run": packet_dry_result,
            "dry_run": {
                "command": repair_dry_cmd,
                "exit_code": repair_dry_code,
                "stdout": repair_dry_stdout,
                "stderr": repair_dry_stderr,
            },
            "integration_dry_run": {
                "command": integration_dry_cmd,
                "exit_code": integration_dry_code,
                "stdout": integration_dry_stdout,
                "stderr": integration_dry_stderr,
            },
        }
        if packet_apply_result is not None:
            dispatcher_queue_repair_result["packet_apply"] = packet_apply_result
        repair_dry_payload = None
        integration_dry_payload = None
        if repair_dry_code == 0:
            try:
                repair_dry_payload = json.loads(repair_dry_stdout)
                dispatcher_queue_repair_result["dry_run"]["parsed_json"] = repair_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(project_root, "orchestrator", "dispatcher_queue_repair_dry_run_failed", severity="error", stderr=repair_dry_stderr)
        if integration_dry_code == 0:
            try:
                integration_dry_payload = json.loads(integration_dry_stdout)
                dispatcher_queue_repair_result["integration_dry_run"]["parsed_json"] = integration_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(
                project_root,
                "orchestrator",
                "dispatcher_integration_repair_dry_run_failed",
                severity="error",
                stderr=integration_dry_stderr,
            )
        migration_action = dispatcher_queue_repair_has_action(repair_dry_payload)
        integration_action = dispatcher_integration_repair_has_action(integration_dry_payload)
        if migration_action or integration_action:
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {"decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"}, "blocked_by_run_id": holder}
                    if runtime_worker_worktree_cleanup is not None:
                        result["worker_worktree_cleanup"] = runtime_worker_worktree_cleanup
                    if runtime_full_intake_cleanup is not None:
                        result["full_intake_runtime_cleanup"] = runtime_full_intake_cleanup
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            if integration_action:
                integration_apply_cmd = dispatcher_integration_repair_command(
                    project_root,
                    base_ref=args.push_ref or args.base_ref,
                    apply=True,
                )
                integration_code, integration_stdout, integration_stderr = run(integration_apply_cmd)
                dispatcher_queue_repair_result["integration_apply"] = {
                    "command": integration_apply_cmd,
                    "exit_code": integration_code,
                    "stdout": integration_stdout,
                    "stderr": integration_stderr,
                }
                if integration_code != 0:
                    append_log(
                        project_root,
                        "orchestrator",
                        "dispatcher_integration_repair_failed",
                        severity="error",
                        stderr=integration_stderr,
                    )
                else:
                    try:
                        dispatcher_queue_repair_result["integration_apply"]["parsed_json"] = json.loads(integration_stdout)
                    except json.JSONDecodeError:
                        pass
            if migration_action:
                repair_apply_cmd = dispatcher_queue_repair_command(project_root, runtime_root=args.runtime_root, apply=True)
                repair_code, repair_stdout, repair_stderr = run(repair_apply_cmd)
                dispatcher_queue_repair_result["apply"] = {
                    "command": repair_apply_cmd,
                    "exit_code": repair_code,
                    "stdout": repair_stdout,
                    "stderr": repair_stderr,
                }
                if repair_code != 0:
                    append_log(project_root, "orchestrator", "dispatcher_queue_repair_failed", severity="error", stderr=repair_stderr)
                else:
                    try:
                        dispatcher_queue_repair_result["apply"]["parsed_json"] = json.loads(repair_stdout)
                    except json.JSONDecodeError:
                        pass
    if args.apply and not args.no_result_handoff:
        result_handoff_result = run_result_handoff_cycle(
            project_root,
            runtime_root=args.runtime_root,
            base_ref=args.base_ref,
            fetch=not args.no_fetch,
            apply=False,
        )
        sync_dry_payload = result_handoff_result.get("sync_dry_run", {}).get("parsed_json")
        handoff_dry_payload = result_handoff_result.get("dry_run", {}).get("parsed_json")
        if worker_result_sync_has_action(sync_dry_payload):
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {"decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"}, "blocked_by_run_id": holder}
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            result_handoff_result = run_result_handoff_cycle(
                project_root,
                runtime_root=args.runtime_root,
                base_ref=args.base_ref,
                fetch=False,
                apply=True,
            )
            handoff_dry_payload = result_handoff_result.get("dry_run", {}).get("parsed_json")
        if isinstance(handoff_dry_payload, dict) and int(handoff_dry_payload.get("emitted_count") or 0) > 0:
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {"decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"}, "blocked_by_run_id": holder}
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            if "apply" not in result_handoff_result:
                result_handoff_result = run_result_handoff_cycle(
                    project_root,
                    runtime_root=args.runtime_root,
                    base_ref=args.base_ref,
                    fetch=False,
                    apply=True,
                )

    if args.apply:
        expired_dry_cmd = expired_locks_command(project_root, apply=False)
        expired_dry_code, expired_dry_stdout, expired_dry_stderr = run(expired_dry_cmd)
        expired_locks_result = {
            "dry_run": {
                "command": expired_dry_cmd,
                "exit_code": expired_dry_code,
                "stdout": expired_dry_stdout,
                "stderr": expired_dry_stderr,
            }
        }
        expired_dry_payload = None
        if expired_dry_code == 0:
            try:
                expired_dry_payload = json.loads(expired_dry_stdout)
                expired_locks_result["dry_run"]["parsed_json"] = expired_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(project_root, "orchestrator", "expired_locks_dry_run_failed", severity="error", stderr=expired_dry_stderr)
        if expired_locks_has_action(expired_dry_payload):
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {"decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"}, "blocked_by_run_id": holder}
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            expired_apply_cmd = expired_locks_command(project_root, apply=True)
            expired_code, expired_stdout, expired_stderr = run(expired_apply_cmd)
            expired_locks_result["apply"] = {
                "command": expired_apply_cmd,
                "exit_code": expired_code,
                "stdout": expired_stdout,
                "stderr": expired_stderr,
            }
            if expired_code != 0:
                append_log(project_root, "orchestrator", "expired_locks_failed", severity="error", stderr=expired_stderr)
            else:
                try:
                    expired_locks_result["apply"]["parsed_json"] = json.loads(expired_stdout)
                except json.JSONDecodeError:
                    pass
            action = {
                "decision": {
                    "should_run": False,
                    "run_class": "scan_only",
                    "reason": "expired_locks_maintenance_applied",
                },
                "executed": False,
                "command": None,
                "commands": None,
                "exit_code": expired_code,
                "report": None,
                "expired_locks": expired_locks_result,
            }
            if design_handoff_merge_result is not None:
                action["design_handoff_merge"] = design_handoff_merge_result
            if accepted_integration_reconcile_result is not None:
                action["accepted_integration_reconcile"] = accepted_integration_reconcile_result
            if full_intake_result is not None:
                action["full_intake"] = full_intake_result
            if dispatcher_queue_repair_result is not None:
                action["dispatcher_queue_repair"] = dispatcher_queue_repair_result
            if result_handoff_result is not None:
                action["result_handoff"] = result_handoff_result
            if runtime_worker_worktree_cleanup is not None:
                action["worker_worktree_cleanup"] = runtime_worker_worktree_cleanup
            if runtime_full_intake_cleanup is not None:
                action["full_intake_runtime_cleanup"] = runtime_full_intake_cleanup
            if locked:
                release_process_lock(project_root, "status_orchestrator", run_id)
                lock_released = True
            action["runtime_cleanup"] = restore_ignored_tracked_runtime_paths(project_root)
            action["state_sync"] = sync_task_manager_state(project_root, "worker_run", args.push_ref or args.base_ref)
            print(json.dumps(action, ensure_ascii=False, indent=2) if args.json else "expired_locks_maintenance_applied")
            return expired_code

        dead_dry_cmd = dead_worker_claims_command(
            project_root,
            apply=False,
            runtime_root=args.runtime_root,
            worker_pool_systemd_unit=args.worker_pool_systemd_unit,
            worker_pool_systemd_scope=args.worker_pool_systemd_scope,
        )
        dead_dry_code, dead_dry_stdout, dead_dry_stderr = run(dead_dry_cmd)
        dead_worker_claims_result = {
            "dry_run": {
                "command": dead_dry_cmd,
                "exit_code": dead_dry_code,
                "stdout": dead_dry_stdout,
                "stderr": dead_dry_stderr,
            }
        }
        dead_dry_payload = None
        if dead_dry_code == 0:
            try:
                dead_dry_payload = json.loads(dead_dry_stdout)
                dead_worker_claims_result["dry_run"]["parsed_json"] = dead_dry_payload
            except json.JSONDecodeError:
                pass
        else:
            append_log(project_root, "orchestrator", "dead_worker_claims_dry_run_failed", severity="error", stderr=dead_dry_stderr)
        if dead_worker_claims_has_action(dead_dry_payload):
            if not locked:
                locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
                if not locked:
                    result = {"decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"}, "blocked_by_run_id": holder}
                    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                    return 0
            dead_apply_cmd = dead_worker_claims_command(
                project_root,
                apply=True,
                runtime_root=args.runtime_root,
                worker_pool_systemd_unit=args.worker_pool_systemd_unit,
                worker_pool_systemd_scope=args.worker_pool_systemd_scope,
            )
            dead_code, dead_stdout, dead_stderr = run(dead_apply_cmd)
            dead_worker_claims_result["apply"] = {
                "command": dead_apply_cmd,
                "exit_code": dead_code,
                "stdout": dead_stdout,
                "stderr": dead_stderr,
            }
            if dead_code != 0:
                append_log(project_root, "orchestrator", "dead_worker_claims_failed", severity="error", stderr=dead_stderr)
            else:
                try:
                    dead_apply_payload = json.loads(dead_stdout)
                    dead_worker_claims_result["apply"]["parsed_json"] = dead_apply_payload
                except json.JSONDecodeError:
                    dead_apply_payload = None
                if worker_pool_systemd_is_inactive(dead_apply_payload):
                    update_process_state(
                        project_root,
                        "worker_pool",
                        "completed",
                        "worker_pool_systemd_inactive",
                    )
            if dead_code != 0 or dead_worker_claims_requires_cycle_restart(dead_apply_payload):
                action = {
                    "decision": {
                        "should_run": False,
                        "run_class": "scan_only",
                        "reason": "dead_worker_claims_maintenance_applied",
                    },
                    "executed": False,
                    "command": None,
                    "commands": None,
                    "exit_code": dead_code,
                    "report": None,
                    "dead_worker_claims": dead_worker_claims_result,
                }
                if design_handoff_merge_result is not None:
                    action["design_handoff_merge"] = design_handoff_merge_result
                if accepted_integration_reconcile_result is not None:
                    action["accepted_integration_reconcile"] = accepted_integration_reconcile_result
                if full_intake_result is not None:
                    action["full_intake"] = full_intake_result
                if dispatcher_queue_repair_result is not None:
                    action["dispatcher_queue_repair"] = dispatcher_queue_repair_result
                if result_handoff_result is not None:
                    action["result_handoff"] = result_handoff_result
                if runtime_worker_worktree_cleanup is not None:
                    action["worker_worktree_cleanup"] = runtime_worker_worktree_cleanup
                if runtime_full_intake_cleanup is not None:
                    action["full_intake_runtime_cleanup"] = runtime_full_intake_cleanup
                if locked:
                    release_process_lock(project_root, "status_orchestrator", run_id)
                    lock_released = True
                action["runtime_cleanup"] = restore_ignored_tracked_runtime_paths(project_root)
                action["state_sync"] = sync_task_manager_state(project_root, "worker_run", args.push_ref or args.base_ref)
                print(json.dumps(action, ensure_ascii=False, indent=2) if args.json else "dead_worker_claims_maintenance_applied")
                return dead_code

    scheduler_cmd = [
        sys.executable,
        str(script_path("event_driven_scheduler.py")),
        "--project-root",
        str(project_root),
        "--open-pr-stack",
        str(args.open_pr_stack),
        "--json",
    ]
    code, stdout, stderr = run(scheduler_cmd)
    if code != 0:
        if args.apply:
            append_log(project_root, "orchestrator", "scheduler_failed", severity="error", stderr=stderr)
            if locked:
                release_process_lock(project_root, "status_orchestrator", run_id)
            accepted_apply = (
                accepted_integration_reconcile_result or {}
            ).get("apply", {}).get("parsed_json", {})
            if int(accepted_apply.get("applied_count") or 0) > 0:
                sync_task_manager_state(project_root, "finalization_run", args.push_ref or args.base_ref)
        return code
    decision = json.loads(stdout)
    package_handoff = None
    if args.local_llm_cycle:
        decision = {
            "project_root": str(project_root),
            "checked_at": utc_now(),
            "mode": "manual_local_llm_cycle",
            "should_run": True,
            "run_class": "local_llm_cycle",
            "role": "remote_automation_host",
            "reason": "explicit_local_llm_cycle_requested",
            "trigger_event_ids": [],
        }
    else:
        package_handoff = discover_unmerged_integrator_handoff(project_root, base_ref=args.base_ref, fetch=not args.no_fetch)
        if package_handoff and str(decision.get("run_class") or "") not in {"rebuild_route", "dispatcher_plan"}:
            decision.update({
                "should_run": True,
                "run_class": "finalization_run",
                "role": "auto_finalizer",
                "reason": "open_integrator_package_handoff",
                "trigger_event_ids": [],
                "package_handoff": package_handoff,
            })
        if args.auto_local_llm_pre_worker and str(decision.get("run_class") or "") == "worker_run":
            pre_worker_candidate = local_llm_pre_worker_candidate(project_root)
            if pre_worker_candidate:
                decision.update({
                    "should_run": True,
                    "run_class": "local_llm_cycle",
                    "role": "remote_automation_host",
                    "reason": "auto_local_llm_pre_worker_candidate",
                    "trigger_event_ids": [],
                    "pre_worker": True,
                    "pre_worker_candidate": pre_worker_candidate,
                })
    role = str(decision.get("role") or "scan_only")
    run_class = str(decision.get("run_class") or "scan_only")
    action = {"decision": decision, "executed": False, "command": None, "commands": None, "exit_code": None, "report": None}
    if pre_apply_recovery is not None:
        action["pre_apply_recovery"] = pre_apply_recovery
    if pre_apply_generated_state_validation is not None:
        action["pre_apply_generated_state_validation"] = pre_apply_generated_state_validation
    if pre_apply_tracked_generated_state.get("ok") is True:
        action["pre_apply_tracked_generated_state"] = pre_apply_tracked_generated_state
    if pre_apply_writer_evidence.get("ok") is True:
        action["pre_apply_writer_evidence"] = pre_apply_writer_evidence
    if design_handoff_merge_result is not None:
        action["design_handoff_merge"] = design_handoff_merge_result
    if accepted_integration_reconcile_result is not None:
        action["accepted_integration_reconcile"] = accepted_integration_reconcile_result
    if full_intake_result is not None:
        action["full_intake"] = full_intake_result
    if dispatcher_queue_repair_result is not None:
        action["dispatcher_queue_repair"] = dispatcher_queue_repair_result
    if result_handoff_result is not None:
        action["result_handoff"] = result_handoff_result
    if expired_locks_result is not None:
        action["expired_locks"] = expired_locks_result
    if dead_worker_claims_result is not None:
        action["dead_worker_claims"] = dead_worker_claims_result
    if runtime_worker_worktree_cleanup is not None:
        action["worker_worktree_cleanup"] = runtime_worker_worktree_cleanup
    if runtime_full_intake_cleanup is not None:
        action["full_intake_runtime_cleanup"] = runtime_full_intake_cleanup

    if decision.get("should_run") and run_class in GIT_REQUIRED_RUN_CLASSES and not is_git_worktree(project_root):
        reason = "project_root_not_git_worktree"
        details = command_failure_details(
            None,
            run_class,
            2,
            "",
            f"{run_class} requires a git worktree at {project_root}",
        )
        decision.update({"should_run": False, "reason": reason, "blocked_run_class": run_class})
        action.update({"blocked": True, "exit_code": 2, "failure": details})
        if args.apply:
            update_process_state(project_root, PROCESS_BY_ROLE.get(role, role), "blocked", reason, details)
            append_log(project_root, "orchestrator", "git_worktree_required", severity="error", run_class=run_class, failure=details)
            if locked:
                release_process_lock(project_root, "status_orchestrator", run_id)
        print(json.dumps(action, ensure_ascii=False, indent=2) if args.json else f"{run_class}: {reason}")
        return 2 if args.apply else 0

    if not decision.get("should_run"):
        if args.apply and locked:
            release_process_lock(project_root, "status_orchestrator", run_id)
            lock_released = True
        if args.apply and full_intake_apply_ran(action):
            action["state_sync"] = sync_task_manager_state(project_root, "dispatcher_plan", args.push_ref or args.base_ref)
        elif args.apply and full_intake_state_write_ran(action):
            action["state_sync"] = sync_task_manager_state(project_root, "dispatcher_plan", args.push_ref or args.base_ref)
        elif args.apply and dispatcher_queue_repair_apply_ran(action):
            action["state_sync"] = sync_task_manager_state(project_root, "dispatcher_plan", args.push_ref or args.base_ref)
        elif args.apply and lock_released:
            action["state_sync"] = sync_task_manager_state(project_root, "dispatcher_plan", args.push_ref or args.base_ref)
        print(json.dumps(action, ensure_ascii=False, indent=2) if args.json else f"{run_class}: {decision.get('reason')}")
        return 0

    if args.apply:
        if not locked:
            locked, holder = acquire_process_lock(project_root, "status_orchestrator", run_id, args.lock_ttl_minutes)
            if not locked:
                result = {"decision": {"should_run": False, "run_class": "scan_only", "reason": "process_lock_active"}, "blocked_by_run_id": holder}
                print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else f"process lock active: {holder}")
                return 0
        if runtime_worker_worktree_cleanup is None:
            runtime_worker_worktree_cleanup = cleanup_runtime_worker_worktrees(args.runtime_root)
            action["worker_worktree_cleanup"] = runtime_worker_worktree_cleanup
        append_log(project_root, "orchestrator", "decision", severity="info", role=role, run_class=run_class, reason=decision.get("reason"), apply=args.apply)
        if run_class in {"integration_run", "integrator_review"}:
            integration_checkpoint = sync_task_manager_state(
                project_root,
                "integration_run",
                args.push_ref or args.base_ref,
            )
            action["pre_integration_state_sync"] = integration_checkpoint
            if not integration_checkpoint_persisted(integration_checkpoint):
                reason = "pre_integration_state_sync_not_persisted"
                details = {
                    "run_class": run_class,
                    "state_sync_reason": str(integration_checkpoint.get("reason") or ""),
                    "deferred": integration_checkpoint.get("deferred") is True,
                }
                action.update(
                    {
                        "blocked": True,
                        "exit_code": 2,
                        "failure_reason": reason,
                        "failure": details,
                    }
                )
                update_process_state(project_root, "integrator", "failed_retryable", reason, details)
                append_log(
                    project_root,
                    "orchestrator",
                    "pre_integration_state_sync_failed",
                    severity="error",
                    run_class=run_class,
                    state_sync=integration_checkpoint,
                )
                if locked:
                    release_process_lock(project_root, "status_orchestrator", run_id)
                    lock_released = True
                action["runtime_cleanup"] = restore_ignored_tracked_runtime_paths(project_root)
                print(json.dumps(action, ensure_ascii=False, indent=2) if args.json else reason)
                return 2

    if run_class == "rebuild_route":
        cmd = [
            sys.executable,
            str(script_path("loop_agent_orchestrator.py")),
            "--project-root",
            str(project_root),
            "--base-ref",
            args.push_ref or args.base_ref,
            "--once",
            "--json",
        ]
        if not args.no_fetch:
            cmd.append("--fetch")
        if args.apply:
            cmd.append("--apply")
        action["command"] = cmd
        if args.apply:
            update_process_state(project_root, "dispatcher", "running", str(decision.get("reason")))
    elif run_class == "architect_plan":
        cmd = [
            sys.executable,
            str(script_path("architect_planner.py")),
            "--project-root",
            str(project_root),
            "--report",
            str(task_manager_dir(project_root) / "reports" / "architect_planner_report.json"),
            "--director-base-ref",
            args.base_ref,
            "--json",
        ]
        if args.apply:
            cmd.append("--apply")
        action["command"] = cmd
        if args.apply:
            update_process_state(project_root, "architect", "running", str(decision.get("reason")))
    elif run_class == "dispatcher_plan":
        cmd = [sys.executable, str(script_path("dispatcher_worker_bridge.py")), "--project-root", str(project_root), "--emit-events", "--json"]
        if args.apply:
            cmd.append("--apply")
        action["command"] = cmd
        if args.apply:
            update_process_state(project_root, "dispatcher", "running", str(decision.get("reason")))
    elif run_class == "worker_run":
        direct_worker_cmd = worker_pool_command(
            project_root,
            base_ref=args.base_ref,
            machine_id=args.machine_id,
            runtime_root=args.runtime_root,
            max_total_workers=args.max_total_workers,
            worker_base_ref=args.worker_base_ref,
            worker_context_ref=args.worker_context_ref,
            push_ref=args.push_ref,
            fetch=not args.no_fetch,
            apply=args.apply,
        )
        cmd, worker_pool_async = worker_pool_execution_command(
            direct_worker_cmd,
            project_root=project_root,
            runtime_root=args.runtime_root,
            systemd_unit=args.worker_pool_systemd_unit,
            systemd_scope=args.worker_pool_systemd_scope,
            systemd_after_unit=args.worker_pool_systemd_after_unit,
        )
        action["command"] = cmd
        action["worker_pool_command"] = direct_worker_cmd
        action["worker_pool_async"] = worker_pool_async
        if worker_pool_async:
            action["worker_pool_systemd_unit"] = args.worker_pool_systemd_unit
        if args.apply:
            update_process_state(project_root, "worker_pool", "running", str(decision.get("reason")))
    elif run_class == "integration_run":
        preflight_cmd = [
            sys.executable,
            str(script_path("pre_integrator_repair.py")),
            "--project-root",
            str(project_root),
            "--base-ref",
            integration_base_ref,
            "--json",
            "--emit-events",
            "--max-normal",
            "20",
            "--max-low-risk",
            "20",
            "--max-high-risk",
            "5",
            "--max-modules",
            "10",
            "--max-tasks-per-module",
            "10",
            "--allow-overlap",
        ]
        direct_cmd = [
            sys.executable,
            str(script_path("integrator_direct_merge.py")),
            "--project-root",
            str(project_root),
            "--worktree-root",
            str(Path(args.runtime_root).expanduser() / "integrator-worktrees" / project_root.name),
            "--base-ref",
            integration_base_ref,
            "--json",
            "--max-items",
            "10",
        ]
        if not args.no_fetch:
            preflight_cmd.append("--fetch")
            direct_cmd.append("--fetch")
        if args.apply:
            direct_cmd.append("--apply")
            for event_id in decision.get("trigger_event_ids") or []:
                direct_cmd.extend(["--consume-event-id", str(event_id)])
        action["commands"] = [preflight_cmd, direct_cmd]
        if args.apply:
            update_process_state(project_root, "integrator", "running", str(decision.get("reason")))
    elif run_class == "finalization_run":
        handoff_path = None
        if isinstance(package_handoff, dict) and package_handoff.get("handoff"):
            handoff_path = Path(str(package_handoff["handoff"]))
        cmd = finalizer_merge_command(project_root, base_ref=args.push_ref or args.base_ref, fetch=not args.no_fetch, apply=args.apply, handoff=handoff_path)
        cleanup_cmd = [
            sys.executable,
            str(script_path("post_finalizer_cleanup.py")),
            "--project-root",
            str(project_root),
            "--apply",
            "--json",
        ]
        if args.apply:
            action["commands"] = [cmd, cleanup_cmd]
            update_process_state(project_root, "finalizer", "running", str(decision.get("reason")))
        else:
            action["command"] = cmd
    elif run_class == "local_llm_cycle":
        pre_worker = bool(decision.get("pre_worker"))
        candidate = decision.get("pre_worker_candidate") if isinstance(decision.get("pre_worker_candidate"), dict) else {}
        commands = local_llm_cycle_commands(
            project_root,
            apply=args.apply,
            pre_worker=pre_worker,
            task_id=str(candidate.get("task_id") or "") or None,
        )
        action["commands"] = commands
        if pre_worker:
            direct_worker_cmd = worker_pool_command(
                project_root,
                base_ref=args.base_ref,
                machine_id=args.machine_id,
                runtime_root=args.runtime_root,
                max_total_workers=args.max_total_workers,
                worker_base_ref=args.worker_base_ref,
                worker_context_ref=args.worker_context_ref,
                push_ref=args.push_ref,
                fetch=not args.no_fetch,
                apply=args.apply,
            )
            worker_cmd, worker_pool_async = worker_pool_execution_command(
                direct_worker_cmd,
                project_root=project_root,
                runtime_root=args.runtime_root,
                systemd_unit=args.worker_pool_systemd_unit,
                systemd_scope=args.worker_pool_systemd_scope,
                systemd_after_unit=args.worker_pool_systemd_after_unit,
            )
            action["post_local_llm_worker_command"] = worker_cmd
            action["worker_pool_command"] = direct_worker_cmd
            action["worker_pool_async"] = worker_pool_async
            if worker_pool_async:
                action["worker_pool_systemd_unit"] = args.worker_pool_systemd_unit
        if args.apply:
            update_process_state(project_root, "local_llm", "running", str(decision.get("reason")))
    elif run_class == "integrator_review":
        context_path = task_manager_dir(project_root) / "integrator_llm_context.json"
        advice_path = task_manager_dir(project_root) / "integrator_llm_advice.json"
        preflight_cmd = [
            sys.executable,
            str(script_path("pre_integrator_repair.py")),
            "--project-root",
            str(project_root),
            "--base-ref",
            integration_base_ref,
            "--json",
            "--emit-events",
            "--max-normal",
            "20",
            "--max-low-risk",
            "20",
            "--max-high-risk",
            "5",
            "--max-modules",
            "10",
            "--max-tasks-per-module",
            "10",
            "--allow-overlap",
            "--reprocess-integrator-review",
        ]
        direct_cmd = [
            sys.executable,
            str(script_path("integrator_direct_merge.py")),
            "--project-root",
            str(project_root),
            "--worktree-root",
            str(Path(args.runtime_root).expanduser() / "integrator-worktrees" / project_root.name),
            "--base-ref",
            integration_base_ref,
            "--json",
            "--max-items",
            "10",
        ]
        if not args.no_fetch:
            preflight_cmd.append("--fetch")
            direct_cmd.append("--fetch")
        if args.apply:
            direct_cmd.append("--apply")
        action["commands"] = [
            [
                sys.executable,
                str(script_path("integrator_llm_context_builder.py")),
                "--project-root",
                str(project_root),
                "--output",
                str(context_path),
            ],
            [
                sys.executable,
                str(script_path("integrator_llm_assistant.py")),
                "--context",
                str(context_path),
                "--output",
                str(advice_path),
                "--dry-run",
                "--json",
            ],
            preflight_cmd,
            direct_cmd,
        ]
        if args.apply:
            update_process_state(project_root, "integrator", "running", str(decision.get("reason")))
    else:
        if args.apply:
            update_process_state(project_root, "orchestrator", "idle", str(decision.get("reason")))

    if args.apply and action.get("commands"):
        action["executed"] = True
        failure_details = None
        if action.get("post_local_llm_worker_command"):
            sequence = execute_pre_worker_sequence(
                action["commands"],
                action["post_local_llm_worker_command"],
            )
            results = sequence["results"]
            exit_code = sequence["exit_code"]
            action["codex_fallback_used"] = sequence["codex_fallback_used"]
            if sequence["llm_failure"] is not None:
                action["local_llm_failure"] = sequence["llm_failure"]
            if exit_code != 0:
                worker_result = sequence["worker_result"]
                failure_details = command_failure_details(
                    worker_result["command"],
                    "worker_run",
                    exit_code,
                    worker_result["stdout"],
                    worker_result["stderr"],
                )
        else:
            results = []
            exit_code = 0
            for cmd in action["commands"]:
                code_item, out, err = run(cmd)
                results.append({"command": cmd, "exit_code": code_item, "stdout": out, "stderr": err})
                if code_item != 0:
                    exit_code = code_item
                    failure_details = command_failure_details(cmd, run_class, exit_code, out, err)
                    break
                if run_class == "finalization_run" and is_auto_finalizer_command(cmd):
                    payload = parse_command_json_stdout(out)
                    if finalizer_noop_payload(payload):
                        action["no_op"] = True
                        action["no_op_reason"] = payload.get("decision")
                        break
                if run_class in {"integration_run", "integrator_review"} and is_integrator_direct_merge_command(cmd):
                    payload = parse_command_json_stdout(out)
                    if integrator_direct_merge_noop_payload(payload):
                        action["no_op_integrator_scan"] = True
                        action["no_op_reason"] = "integrator_direct_merge_noop"
        action.update({"exit_code": exit_code, "results": results})
        log_fields = {"severity": "info" if exit_code == 0 else "error", "run_class": run_class, "exit_code": exit_code}
        if failure_details:
            log_fields["failure"] = failure_details
        append_log(project_root, "orchestrator", "action_executed", **log_fields)
        if exit_code != 0:
            failed = next((item for item in results if item.get("exit_code") != 0), results[-1] if results else {})
            update_process_state(
                project_root,
                PROCESS_BY_ROLE.get(role, role),
                "failed_retryable",
                f"{run_class} exit {exit_code}",
                failure_details or command_failure_details(
                    failed.get("command") if isinstance(failed, dict) else None,
                    run_class,
                    exit_code,
                    str(failed.get("stdout") if isinstance(failed, dict) else ""),
                    str(failed.get("stderr") if isinstance(failed, dict) else ""),
                ),
            )
        else:
            if action.get("no_op") or action.get("no_op_integrator_scan"):
                update_process_state(project_root, PROCESS_BY_ROLE.get(role, role), "idle", str(action.get("no_op_reason") or decision.get("reason")))
            else:
                record_successful_action(project_root, decision, role)
                if action.get("worker_pool_async"):
                    update_process_state(
                        project_root,
                        "worker_pool",
                        "running",
                        f"scheduled in {action.get('worker_pool_systemd_unit')}",
                    )
    elif args.apply and action.get("command"):
        action["executed"] = True
        exit_code, out, err = run(action["command"])
        action.update({"exit_code": exit_code, "stdout": out, "stderr": err})
        failure_details = command_failure_details(action.get("command"), run_class, exit_code, out, err) if exit_code != 0 else None
        log_fields = {"severity": "info" if exit_code == 0 else "error", "run_class": run_class, "exit_code": exit_code}
        if failure_details:
            log_fields["failure"] = failure_details
        append_log(project_root, "orchestrator", "action_executed", **log_fields)
        if exit_code != 0:
            update_process_state(
                project_root,
                PROCESS_BY_ROLE.get(role, role),
                "failed_retryable",
                f"{run_class} exit {exit_code}",
                failure_details,
            )
        else:
            if run_class == "worker_run" and action.get("worker_pool_async"):
                consume_events(
                    task_manager_dir(project_root) / "agent_events.jsonl",
                    [str(item) for item in decision.get("trigger_event_ids", [])],
                    role,
                )
                update_activity(task_manager_dir(project_root) / "agent_activity_state.json", decision)
                update_process_state(
                    project_root,
                    "worker_pool",
                    "running",
                    f"scheduled in {action.get('worker_pool_systemd_unit')}",
                )
            else:
                record_successful_action(project_root, decision, role)
            if should_run_post_worker_handoff(
                run_class,
                no_result_handoff=bool(args.no_result_handoff),
                worker_pool_async=bool(action.get("worker_pool_async")),
            ):
                post_worker_state_sync = sync_task_manager_state(
                    project_root,
                    "worker_run",
                    args.push_ref or args.base_ref,
                )
                action["post_worker_state_sync"] = post_worker_state_sync
                action["state_sync"] = post_worker_state_sync
                if state_sync_failure_is_fatal(post_worker_state_sync):
                    action["exit_code"] = 1
                    action["failure_reason"] = str(post_worker_state_sync.get("reason") or "post_worker_state_sync_failed")
                else:
                    action["post_worker_result_handoff"] = run_result_handoff_cycle(
                        project_root,
                        runtime_root=args.runtime_root,
                        base_ref=args.base_ref,
                        fetch=not args.no_fetch,
                        apply=True,
                        output_name="status-orchestrator-post-worker.json",
                    )

    if args.apply:
        report_path = task_manager_dir(project_root) / "reports" / f"ORCHESTRATOR_{datetime.now().strftime('%Y-%m-%d')}.md"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            "# Status Orchestrator\n\n"
            f"Generated: `{utc_now()}`\n\n"
            f"- Apply: `{args.apply}`\n"
            f"- Should run: `{decision.get('should_run')}`\n"
            f"- Run class: `{run_class}`\n"
            f"- Role: `{role}`\n"
            f"- Reason: `{decision.get('reason')}`\n",
            encoding="utf-8",
        )
        action["report"] = str(report_path)
        if locked:
            release_process_lock(project_root, "status_orchestrator", run_id)
            lock_released = True
        action["runtime_cleanup"] = restore_ignored_tracked_runtime_paths(project_root)
    else:
        action["report"] = None
    if args.apply and (
        (action.get("executed") and action.get("exit_code") == 0 and not action.get("no_op"))
        or lock_released
    ):
        if (
            nested_state_commit_recorded(action)
            and not pr_intake_apply_ran(action)
            and not result_handoff_apply_ran(action)
            and not full_intake_apply_ran(action)
            and not full_intake_state_write_ran(action)
            and not dispatcher_queue_repair_apply_ran(action)
            and not lock_released
        ):
            action["state_sync"] = {"skipped": True, "ok": True, "reason": "nested_state_commit_recorded"}
        elif isinstance(action.get("state_sync"), dict):
            pass
        else:
            exclude_paths = NOOP_INTEGRATOR_SCAN_TRANSIENT_PATHS if action.get("no_op_integrator_scan") else None
            action["state_sync"] = sync_task_manager_state(project_root, run_class, args.push_ref or args.base_ref, exclude_paths=exclude_paths)
    if args.apply:
        action["post_state_runtime_cleanup"] = restore_ignored_tracked_runtime_paths(project_root)
    state_sync = action.get("state_sync")
    state_sync_reason = str(state_sync.get("reason") or "") if isinstance(state_sync, dict) else ""
    if state_sync_failure_is_fatal(state_sync):
        action["exit_code"] = 1
        action["failure_reason"] = state_sync_reason
    print(json.dumps(action, ensure_ascii=False, indent=2) if args.json else f"{run_class}: {decision.get('reason')}")
    return int(action.get("exit_code") or 0)


if __name__ == "__main__":
    raise SystemExit(main())
