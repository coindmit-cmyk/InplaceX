#!/usr/bin/env python3
"""Run one approved manual automation command for a project.

This is intentionally a narrow dashboard/CLI wrapper. It does not accept raw
shell commands; every mode maps to an Agent Core script with fixed arguments.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any

import runner_readiness_report

ALLOWED_MODES = {"all", "architect", "dispatcher", "workers", "integrator", "finalizer", "model_limit_retries", "release_locks", "pr_intake", "result_handoff", "full_intake"}
UNSUPPORTED_DETERMINISTIC_MODES: set[str] = set()
PROCESS_ROLE_BY_MODE = {
    "all": "orchestrator",
    "architect": "architect",
    "dispatcher": "dispatcher",
    "workers": "worker_pool",
    "integrator": "integrator",
    "finalizer": "finalizer",
    "model_limit_retries": "model_limit_retries",
    "release_locks": "lock_maintenance",
    "pr_intake": "dispatcher",
    "result_handoff": "integrator",
    "full_intake": "dispatcher",
}
PROJECT_STATE_SUMMARY_PATH = "AiStudio/Project_state/indexes/current_summary.md"
WORKER_REPORT_SYNC_ROOT = "docs/reports/workers"
WORKER_REPORT_SYNC_MODES = {"all", "result_handoff", "integrator"}


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--base-branch", default="develop")
    parser.add_argument("--mode", default="all", choices=sorted(ALLOWED_MODES))
    parser.add_argument("--model-limit-retry-limit", type=int, default=5, help="Maximum blocked_model_limit tasks to authorize for retry.")
    parser.add_argument("--max-total-workers", type=int, default=4, help="Maximum worker lanes for all/workers modes.")
    parser.add_argument("--max-tasks-per-lane", type=int, default=0, help="Override worker max tasks per lane for workers mode. 0 uses profile defaults.")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--apply", action="store_true", help="Execute write-capable automation. Default is dry-run plan.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()



def branch_name_from_ref(ref: str) -> str:
    value = str(ref or "develop").strip()
    return value.removeprefix("origin/") or "develop"


def git_run(project_root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=str(project_root), text=True, capture_output=True, check=False)


def github_repo_slug(project_root: Path, fallback: str = "") -> str:
    proc = git_run(project_root, ["config", "--get", "remote.origin.url"])
    value = str(proc.stdout or "").strip() if proc.returncode == 0 else ""
    if value.startswith("git@github.com:"):
        value = value.removeprefix("git@github.com:")
    elif "github.com/" in value:
        value = value.split("github.com/", 1)[1]
    value = value.removesuffix(".git").strip("/")
    return value if "/" in value else fallback


def payload_has_state_commit(value: Any) -> bool:
    if isinstance(value, dict):
        if value.get("state_commit"):
            return True
        state_sync = value.get("state_sync")
        if isinstance(state_sync, dict) and state_sync.get("reason") == "nested_state_commit_recorded":
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


def nested_state_commit_recorded(results: list[dict[str, Any]]) -> bool:
    for result in results:
        if not isinstance(result, dict):
            continue
        parsed = result.get("parsed_json")
        if isinstance(parsed, dict) and payload_has_state_commit(parsed):
            return True
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


def parse_stdout_json(stdout: str) -> dict[str, Any] | None:
    text = str(stdout or "").strip()
    if not text:
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def first_child_blocker(results: list[dict[str, Any]]) -> dict[str, Any] | None:
    for result in results:
        if not isinstance(result, dict):
            continue
        payload = result.get("parsed_json")
        if not isinstance(payload, dict):
            payload = parse_stdout_json(str(result.get("stdout") or ""))
        if not isinstance(payload, dict):
            continue
        state = str(payload.get("state") or payload.get("status") or "").strip()
        if state != "blocked":
            continue
        return {
            "state": state,
            "error": payload.get("error") or payload.get("reason") or "child_blocked",
            "command": result.get("command") or [],
            "payload": payload,
        }
    return None


def finalizer_noop_payload(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    return str(payload.get("decision") or "") in {"no_handoff", "already_finalized"}


def finalizer_noop_result(results: list[dict[str, Any]]) -> dict[str, Any] | None:
    for result in results:
        if not isinstance(result, dict):
            continue
        command = result.get("command") if isinstance(result.get("command"), list) else []
        if len(command) <= 1 or not str(command[1]).endswith("auto_finalizer_merge.py"):
            continue
        payload = result.get("parsed_json")
        if not isinstance(payload, dict):
            payload = parse_stdout_json(str(result.get("stdout") or ""))
        if finalizer_noop_payload(payload):
            return {"decision": payload.get("decision"), "payload": payload, "command": command}
    return None


def model_limit_retry_noop_result(results: list[dict[str, Any]]) -> dict[str, Any] | None:
    authorizer: dict[str, Any] | None = None
    promoter: dict[str, Any] | None = None
    for result in results:
        if not isinstance(result, dict):
            continue
        command = result.get("command") if isinstance(result.get("command"), list) else []
        if len(command) <= 1:
            continue
        payload = result.get("parsed_json")
        if not isinstance(payload, dict):
            payload = parse_stdout_json(str(result.get("stdout") or ""))
        if not isinstance(payload, dict):
            continue
        script = str(command[1])
        if script.endswith("authorize_model_limit_retries.py"):
            authorizer = payload
        elif script.endswith("promote_worker_ready_tasks.py"):
            promoter = payload
    if not isinstance(authorizer, dict) or not isinstance(promoter, dict):
        return None
    if (
        int(authorizer.get("approved_count") or 0) == 0
        and int(authorizer.get("skipped_count") or 0) == 0
        and int(promoter.get("promoted_count") or 0) == 0
        and int(promoter.get("skipped_count") or 0) == 0
    ):
        return {
            "decision": "no_model_limit_retry_candidates",
            "authorizer": authorizer,
            "promoter": promoter,
        }
    return None


def child_scan_only_noop_result(results: list[dict[str, Any]]) -> dict[str, Any] | None:
    for result in results:
        if not isinstance(result, dict):
            continue
        payload = result.get("parsed_json")
        if not isinstance(payload, dict):
            payload = parse_stdout_json(str(result.get("stdout") or ""))
        if not isinstance(payload, dict):
            continue
        decision = payload.get("decision")
        if not isinstance(decision, dict):
            continue
        if payload.get("executed") is False and decision.get("should_run") is False and str(decision.get("run_class") or "") == "scan_only":
            return {
                "decision": "scan_only",
                "reason": decision.get("reason") or "no_unconsumed_event_or_ready_state",
                "skip_process_state": True,
                "payload": {
                    "run_class": decision.get("run_class"),
                    "counts": decision.get("counts") or {},
                },
            }
    return None


def state_sync_roots(mode: str) -> list[str]:
    roots = ["AiStudio/Task_manager"]
    if mode in WORKER_REPORT_SYNC_MODES:
        roots.append(WORKER_REPORT_SYNC_ROOT)
    if mode in {"pr_intake", "full_intake"}:
        roots.extend([
            "AiStudio/Project_state/intake/inbox",
            "docs/reports/change-intake/pr-cycle",
        ])
    if mode == "full_intake":
        roots.append(PROJECT_STATE_SUMMARY_PATH)
    return roots


def normalized_repo_path(path: Any) -> str:
    return str(path or "").replace("\\", "/").strip()


def referenced_worker_report_paths(project_root: Path) -> set[str]:
    queue = load_json(project_root / "AiStudio" / "Task_manager" / "task_queue.json")
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
            normalized = normalized_repo_path(value)
            if (
                normalized.startswith(f"{WORKER_REPORT_SYNC_ROOT}/")
                and current_task_id in normalized.upper()
            ):
                reports.add(normalized)
    return reports


def state_sync_meaningful_path(path: str, mode: str, referenced_reports: set[str] | None = None) -> bool:
    normalized = str(path or "").replace("\\", "/")
    if normalized.startswith(f"{WORKER_REPORT_SYNC_ROOT}/"):
        return mode in WORKER_REPORT_SYNC_MODES and normalized in (referenced_reports or set())
    if mode in {"pr_intake", "full_intake"} and normalized.startswith("docs/reports/change-intake/pr-cycle/"):
        return True
    return not runner_readiness_report.readiness_ignored_dirty_path(path)


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


def resolve_manual_state_rebase_conflicts(
    project_root: Path,
    mode: str,
    run_git: Any,
) -> dict[str, Any]:
    unmerged = run_git(["diff", "--name-only", "--diff-filter=U"])
    if unmerged.returncode != 0:
        return {"ok": False, "reason": "unmerged_status_failed"}
    paths = [line.strip().replace("\\", "/") for line in unmerged.stdout.splitlines() if line.strip()]
    if not paths:
        return {"ok": False, "reason": "no_supported_rebase_conflicts"}

    event_path = "AiStudio/Task_manager/agent_events.jsonl"
    inbox_prefix = "AiStudio/Project_state/intake/inbox/"
    report_prefix = "docs/reports/change-intake/pr-cycle/"
    unsupported = [
        path
        for path in paths
        if path != event_path
        and not (mode in {"pr_intake", "full_intake"} and path.startswith(inbox_prefix) and path.endswith(".json"))
        and not (mode in {"pr_intake", "full_intake"} and path.startswith(report_prefix))
    ]
    if unsupported:
        return {"ok": False, "reason": "unsupported_state_rebase_conflicts", "paths": unsupported}

    resolved: list[str] = []
    for path in paths:
        ours_proc = run_git(["show", f":2:{path}"])
        theirs_proc = run_git(["show", f":3:{path}"])
        if ours_proc.returncode != 0 or theirs_proc.returncode != 0:
            return {"ok": False, "reason": "missing_conflict_stage", "path": path}
        ours = ours_proc.stdout
        theirs = theirs_proc.stdout
        target = project_root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            if path == event_path:
                target.write_text(merge_jsonl_state(ours, theirs), encoding="utf-8")
            elif path.startswith(inbox_prefix):
                ours_payload = json.loads(ours)
                theirs_payload = json.loads(theirs)
                if str(ours_payload.get("event_id") or "") != str(theirs_payload.get("event_id") or ""):
                    return {"ok": False, "reason": "intake_event_identity_mismatch", "path": path}
                # Stage 2 is the fetched canonical branch during rebase. Preserve that
                # immutable intake record; a refreshed PR intake uses a new event id.
                target.write_text(json.dumps(ours_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            elif ours == theirs:
                target.write_text(ours, encoding="utf-8")
            else:
                return {"ok": False, "reason": "change_intake_report_conflict", "path": path}
        except (json.JSONDecodeError, OSError) as exc:
            return {"ok": False, "reason": "malformed_state_rebase_conflict", "path": path, "error": str(exc)}
        add = run_git(["add", "--", path])
        if add.returncode != 0:
            return {"ok": False, "reason": "conflict_add_failed", "path": path}
        resolved.append(path)
    return {"ok": True, "resolved_paths": resolved}


def sync_task_manager_state(project_root: Path, base_branch: str, mode: str) -> dict[str, Any]:
    worktree = git_run(project_root, ["rev-parse", "--is-inside-work-tree"])
    if worktree.returncode != 0:
        if mode == "release_locks":
            return {"skipped": True, "ok": True, "reason": "non_git_release_locks_state"}
        return {"skipped": False, "ok": False, "reason": "not_git_worktree", "stderr": worktree.stderr}
    sync_roots = state_sync_roots(mode)
    status = git_run(project_root, ["status", "--porcelain", "--", *sync_roots])
    if status.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "status_failed", "stderr": status.stderr}
    status_lines = [line for line in status.stdout.splitlines() if line.strip()]
    dirty_paths = [porcelain_path(line) for line in status_lines]
    dirty_paths = [path for path in dirty_paths if path]
    referenced_reports = referenced_worker_report_paths(project_root) if mode in WORKER_REPORT_SYNC_MODES else set()
    unreferenced_reports = sorted(
        path
        for path in dirty_paths
        if normalized_repo_path(path).startswith(f"{WORKER_REPORT_SYNC_ROOT}/")
        and normalized_repo_path(path) not in referenced_reports
    )
    if unreferenced_reports:
        return {
            "skipped": False,
            "ok": False,
            "reason": "unreferenced_worker_report_paths",
            "paths": unreferenced_reports,
        }
    meaningful_paths = [
        path for path in dirty_paths if state_sync_meaningful_path(path, mode, referenced_reports)
    ]
    ignored_tracked_paths = [
        porcelain_path(line)
        for line in status_lines
        if not line.startswith("?? ")
        and not state_sync_meaningful_path(porcelain_path(line), mode, referenced_reports)
    ]
    ignored_tracked_paths = [path for path in ignored_tracked_paths if path]
    if not dirty_paths:
        return {"skipped": True, "ok": True, "reason": "no_task_manager_changes"}
    if not meaningful_paths:
        return {
            "skipped": True,
            "ok": True,
            "reason": "only_ignored_task_manager_changes",
            "ignored_paths": dirty_paths,
        }
    branch = branch_name_from_ref(base_branch)
    commands: list[dict[str, Any]] = []

    def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
        proc = git_run(project_root, args)
        commands.append({"command": ["git", *args], "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr})
        return proc

    if ignored_tracked_paths:
        restore = run_git(["restore", "--staged", "--worktree", "--", *ignored_tracked_paths])
        if restore.returncode != 0:
            return {"skipped": False, "ok": False, "reason": "restore_ignored_task_manager_changes_failed", "commands": commands}
    add = run_git(["add", "-A", "--", *meaningful_paths])
    if add.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "add_failed", "commands": commands}
    commit = run_git(["commit", "-m", f"chore(agent): record manual {mode} state"])
    if commit.returncode != 0:
        text_out = (commit.stdout + commit.stderr).lower()
        if "nothing to commit" in text_out or "no changes" in text_out:
            return {"skipped": True, "ok": True, "reason": "nothing_to_commit", "commands": commands}
        return {"skipped": False, "ok": False, "reason": "commit_failed", "commands": commands}
    fetch = run_git(["fetch", "origin", branch])
    if fetch.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "fetch_failed", "commands": commands}
    rebase = run_git(["rebase", f"origin/{branch}"])
    if rebase.returncode != 0:
        conflict_resolution = resolve_manual_state_rebase_conflicts(project_root, mode, run_git)
        if conflict_resolution.get("ok"):
            rebase_continue = run_git(["-c", "core.editor=true", "rebase", "--continue"])
            if rebase_continue.returncode == 0:
                push = run_git(["push", "origin", f"HEAD:{branch}"])
                return {
                    "skipped": False,
                    "ok": push.returncode == 0,
                    "reason": "pushed_after_state_conflict_merge" if push.returncode == 0 else "push_failed_after_state_conflict_merge",
                    "conflict_resolution": conflict_resolution,
                    "commands": commands,
                }
            conflict_resolution = {
                **conflict_resolution,
                "ok": False,
                "reason": "rebase_continue_failed",
            }
        abort = run_git(["rebase", "--abort"])
        return {
            "skipped": False,
            "ok": False,
            "reason": "rebase_failed" if abort.returncode == 0 else "rebase_abort_failed",
            "conflict_resolution": conflict_resolution,
            "rebase_aborted": abort.returncode == 0,
            "commands": commands,
        }
    push = run_git(["push", "origin", f"HEAD:{branch}"])
    if push.returncode == 0:
        return {"skipped": False, "ok": True, "reason": "pushed", "commands": commands}
    return {"skipped": False, "ok": False, "reason": "push_failed", "commands": commands}


def porcelain_path(line: str) -> str:
    text = str(line or "").rstrip()
    if not text:
        return ""
    if " -> " in text:
        text = text.rsplit(" -> ", 1)[1]
    return text[3:].strip() if len(text) > 3 else ""


def sync_after_nested_state_commit(project_root: Path, base_branch: str, mode: str = "") -> dict[str, Any]:
    branch = branch_name_from_ref(base_branch)
    commands: list[dict[str, Any]] = []

    def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
        proc = git_run(project_root, args)
        commands.append({"command": ["git", *args], "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr})
        return proc

    worktree = run_git(["rev-parse", "--is-inside-work-tree"])
    if worktree.returncode != 0:
        return {"skipped": True, "ok": True, "reason": "nested_state_commit_recorded_non_git", "commands": commands}
    status = run_git(["status", "--porcelain"])
    if status.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "status_failed_after_nested_state_commit", "commands": commands}
    tracked_dirty = [
        porcelain_path(line)
        for line in status.stdout.splitlines()
        if line.strip() and not line.startswith("?? ")
    ]
    tracked_dirty = [path for path in tracked_dirty if path]
    unsafe = [path for path in tracked_dirty if not path.replace("\\", "/").startswith("AiStudio/Task_manager/")]
    if unsafe:
        return {
            "skipped": False,
            "ok": False,
            "reason": "unsafe_dirty_paths_after_nested_state_commit",
            "unsafe_paths": unsafe,
            "commands": commands,
        }
    if tracked_dirty:
        restore = run_git(["restore", "--staged", "--worktree", "--", *tracked_dirty])
        if restore.returncode != 0:
            return {"skipped": False, "ok": False, "reason": "restore_failed_after_nested_state_commit", "commands": commands}
    fetch = run_git(["fetch", "origin", branch])
    if fetch.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "fetch_failed_after_nested_state_commit", "commands": commands}
    merge = run_git(["merge", "--ff-only", f"origin/{branch}"])
    if merge.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "ff_failed_after_nested_state_commit", "commands": commands}
    evidence_sync = None
    if mode in WORKER_REPORT_SYNC_MODES and referenced_worker_report_paths(project_root):
        report_status = git_run(project_root, ["status", "--porcelain", "--", WORKER_REPORT_SYNC_ROOT])
        if report_status.returncode != 0:
            return {
                "skipped": False,
                "ok": False,
                "reason": "worker_report_status_failed_after_nested_state_commit",
                "commands": commands,
            }
        if report_status.stdout.strip():
            evidence_sync = sync_task_manager_state(project_root, base_branch, mode)
            if not evidence_sync.get("ok"):
                return {
                    "skipped": False,
                    "ok": False,
                    "reason": "worker_report_sync_failed_after_nested_state_commit",
                    "worker_report_sync": evidence_sync,
                    "commands": commands,
                }
    return {
        "skipped": False,
        "ok": True,
        "reason": "nested_state_commit_synced",
        "discarded_tracked_task_manager_paths": tracked_dirty,
        "worker_report_sync": evidence_sync,
        "commands": commands,
    }


def rollback_failed_dispatcher_state(project_root: Path, base_branch: str) -> dict[str, Any]:
    result = sync_after_nested_state_commit(project_root, base_branch)
    if not result.get("ok"):
        return result
    return {
        **result,
        "reason": "failed_dispatcher_state_rolled_back",
        "rollback_sync_reason": result.get("reason"),
    }


def sync_project_root_before_run(project_root: Path, base_branch: str) -> dict[str, Any]:
    branch = branch_name_from_ref(base_branch)
    commands: list[dict[str, Any]] = []

    def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
        proc = git_run(project_root, args)
        commands.append({"command": ["git", *args], "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr})
        return proc

    worktree = run_git(["rev-parse", "--is-inside-work-tree"])
    if worktree.returncode != 0 or str(worktree.stdout or "").strip().lower() != "true":
        return {"skipped": True, "ok": True, "reason": "pre_run_non_git_project", "commands": commands}
    status = run_git(["status", "--porcelain"])
    if status.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "pre_run_status_failed", "commands": commands}
    status_lines = [line for line in status.stdout.splitlines() if line.strip()]
    meaningful_dirty = [
        porcelain_path(line)
        for line in status_lines
        if not runner_readiness_report.readiness_ignored_dirty_path(porcelain_path(line))
    ]
    meaningful_dirty = [path for path in meaningful_dirty if path]
    if meaningful_dirty:
        return {
            "skipped": False,
            "ok": False,
            "reason": "pre_run_dirty_paths",
            "dirty_paths": meaningful_dirty,
            "commands": commands,
        }
    ignored_tracked = [
        porcelain_path(line)
        for line in status_lines
        if not line.startswith("?? ") and runner_readiness_report.readiness_ignored_dirty_path(porcelain_path(line))
    ]
    ignored_tracked = [path for path in ignored_tracked if path]
    if ignored_tracked:
        restore = run_git(["restore", "--staged", "--worktree", "--", *ignored_tracked])
        if restore.returncode != 0:
            return {"skipped": False, "ok": False, "reason": "pre_run_restore_ignored_failed", "commands": commands}
    fetch = run_git(["fetch", "origin", branch])
    if fetch.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "pre_run_fetch_failed", "commands": commands}
    merge = run_git(["merge", "--ff-only", f"origin/{branch}"])
    if merge.returncode != 0:
        return {"skipped": False, "ok": False, "reason": "pre_run_ff_failed", "commands": commands}
    return {
        "skipped": False,
        "ok": True,
        "reason": "pre_run_synced",
        "discarded_ignored_tracked_paths": ignored_tracked,
        "commands": commands,
    }

def status_path(runtime_root: Path, project_id: str) -> Path:
    return runtime_root / "manual-runs" / f"{project_id}.json"


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def task_manager_dir(project_root: Path) -> Path:
    return project_root / "AiStudio" / "Task_manager"


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def update_process_state(
    project_root: Path,
    mode: str,
    state: str,
    reason: str | None,
    *,
    details: dict[str, Any] | None = None,
) -> None:
    role = PROCESS_ROLE_BY_MODE.get(mode)
    if not role:
        return
    path = task_manager_dir(project_root) / "agent_process_state.json"
    data = load_json(path) or {"schema_version": 1, "project": project_root.name, "processes": {}}
    data["updated_at"] = now_utc()
    data["project"] = data.get("project") or project_root.name
    processes = data.setdefault("processes", {})
    if not isinstance(processes, dict):
        processes = {}
        data["processes"] = processes
    item = processes.setdefault(role, {})
    if not isinstance(item, dict):
        item = {}
        processes[role] = item
    item["state"] = state
    item["reason"] = reason
    if state in {"completed", "blocked", "needs_human", "failed_retryable", "failed_terminal"}:
        item["last_finished_at"] = data["updated_at"]
    if state == "running":
        item["last_started_at"] = data["updated_at"]
    if details:
        item["last_error"] = details
    elif state in {"completed", "idle"}:
        item.pop("last_error", None)
    write_json(path, data)


def build_commands(args: argparse.Namespace) -> list[list[str]]:
    project_root = str(Path(args.project_root).expanduser())
    runtime_root = str(Path(args.runtime_root).expanduser())
    if args.mode == "all":
        command = [
            sys.executable,
            str(script_path("status_orchestrator.py")),
            "--project-root",
            project_root,
            "--base-ref",
            args.base_ref,
            "--push-ref",
            args.base_branch,
            "--worker-base-ref",
            args.base_ref,
            "--worker-context-ref",
            args.base_ref,
            "--machine-id",
            "aistudio-manual",
            "--runtime-root",
            runtime_root,
            "--max-total-workers",
            str(max(0, int(args.max_total_workers))),
            "--json",
        ]
        if args.apply:
            command.append("--apply")
        return [command]
    if args.mode == "dispatcher":
        command = [sys.executable, str(script_path("dispatcher_worker_bridge.py")), "--project-root", project_root, "--emit-events", "--json"]
        if args.apply:
            command.append("--apply")
        return [command]
    if args.mode == "architect":
        command = [
            sys.executable,
            str(script_path("architect_planner.py")),
            "--project-root",
            project_root,
            "--report",
            str(Path(runtime_root) / "architect" / f"{args.project_id}.json"),
            "--director-base-ref",
            args.base_ref,
            "--json",
        ]
        if args.apply:
            command.append("--apply")
        return [command]
    if args.mode == "workers":
        command = [
            sys.executable,
            str(script_path("worker_pool_manager.py")),
            "--project-root",
            project_root,
            "--base-ref",
            args.base_ref,
            "--push-ref",
            args.base_branch,
            "--worker-base-ref",
            args.base_ref,
            "--worker-context-ref",
            args.base_ref,
            "--machine-id",
            "aistudio-manual",
            "--runtime-root",
            runtime_root,
            "--max-total-workers",
            str(max(0, int(args.max_total_workers))),
            "--max-tasks-per-lane",
            str(max(0, int(args.max_tasks_per_lane))),
            "--json",
            "--fetch",
        ]
        if args.apply:
            command.append("--apply")
        return [command]
    if args.mode == "release_locks":
        expired = [
            sys.executable,
            str(script_path("release_expired_locks.py")),
            "--project-root",
            project_root,
            "--released-by",
            "run_manual_automation.py",
            "--task-queue-ref",
            args.base_ref,
            "--agent-locks-ref",
            args.base_ref,
            "--output",
            str(Path(runtime_root) / "lock-maintenance" / f"{args.project_id}.json"),
        ]
        dead_claims = [
            sys.executable,
            str(script_path("release_dead_worker_claims.py")),
            "--project-root",
            project_root,
            "--from-worker-pool-last-plan",
            "--release-terminal-task-locks",
            "--close-terminal-task-residue",
            "--release-duplicate-active-locks",
            "--close-recorded-in-progress-claims",
            "--reason",
            "worker pool finished without a live worker process or worker result",
            "--json",
        ]
        events = [
            sys.executable,
            str(script_path("event_maintenance.py")),
            "--project-root",
            project_root,
            "--open-pr-stack",
            "0",
            "--json",
        ]
        if args.apply:
            expired.append("--apply")
            dead_claims.append("--apply")
            events.append("--apply")
        return [expired, dead_claims, events]
    if args.mode == "model_limit_retries":
        queue_path = str(Path(project_root) / "AiStudio" / "Task_manager" / "task_queue.json")
        locks_path = str(Path(project_root) / "AiStudio" / "Task_manager" / "agent_locks.json")
        authorize = [
            sys.executable,
            str(script_path("authorize_model_limit_retries.py")),
            "--queue",
            queue_path,
            "--locks",
            locks_path,
            "--limit",
            str(max(0, int(args.model_limit_retry_limit))),
            "--approved-by",
            "run_manual_automation.py",
            "--reason",
            "model capacity retry authorized by manual automation",
            "--json",
        ]
        promote = [
            sys.executable,
            str(script_path("promote_worker_ready_tasks.py")),
            "--queue",
            queue_path,
            "--locks",
            locks_path,
            "--json",
        ]
        if args.apply:
            authorize.append("--apply")
            promote.append("--apply")
        return [authorize, promote]
    if args.mode == "pr_intake":
        repo_slug = github_repo_slug(Path(project_root), "coindmit-cmyk/ai-project-agent")
        command = [
            sys.executable,
            str(script_path("pr_change_intake_cycle.py")),
            "--project-root",
            project_root,
            "--repo",
            repo_slug,
            "--state",
            "OPEN",
            "--limit",
            "50",
            "--discovery-mode",
            "diff-only",
            "--output-dir",
            str(Path(project_root) / "docs" / "reports" / "change-intake" / "pr-cycle" if args.apply else Path(runtime_root) / "pr-change-intake" / args.project_id),
            "--skip-empty-report",
            "--json",
        ]
        if args.apply:
            command.extend(["--fetch", "--apply-events", "--apply-project-state-intake"])
        return [command]
    if args.mode == "full_intake":
        repo_slug = github_repo_slug(Path(project_root), "coindmit-cmyk/ai-project-agent")
        command = [
            sys.executable,
            str(script_path("full_intake_automation_cycle.py")),
            "--project-root",
            project_root,
            "--runtime-root",
            runtime_root,
            "--repo",
            repo_slug,
            "--base-ref",
            args.base_ref,
            "--output-dir",
            str(Path(project_root) / "AiStudio" / "Task_manager" / "reports" / "full-intake-cycle" if args.apply else Path(runtime_root) / "full-intake-cycle" / args.project_id),
            "--json",
        ]
        if args.apply:
            command.extend(["--fetch", "--apply", "--apply-project-rules-remediation"])
        return [command]
    if args.mode == "result_handoff":
        sync = [
            sys.executable,
            str(script_path("sync_worker_results.py")),
            "--project-root",
            project_root,
            "--base-ref",
            args.base_ref,
            "--json",
        ]
        command = [
            sys.executable,
            str(script_path("worker_result_handoff_gate.py")),
            "--project-root",
            project_root,
            "--base-ref",
            args.base_ref,
            "--output",
            str(Path(project_root) / "AiStudio" / "Task_manager" / "reports" / "worker_result_handoff_gate.json" if args.apply else Path(runtime_root) / "worker-result-handoff" / f"{args.project_id}.json"),
            "--json",
        ]
        if args.apply:
            sync.extend(["--fetch", "--apply"])
            command.extend(["--fetch", "--apply-events"])
        return [sync, command]
    if args.mode == "integrator":
        preflight = [
            sys.executable,
            str(script_path("pre_integrator_repair.py")),
            "--project-root",
            project_root,
            "--base-ref",
            args.base_ref,
            "--json",
            "--emit-events",
        ]
        direct = [
            sys.executable,
            str(script_path("integrator_direct_merge.py")),
            "--project-root",
            project_root,
            "--base-ref",
            args.base_ref,
            "--json",
        ]
        if args.apply:
            direct.append("--apply")
        return [preflight, direct]
    if args.mode == "finalizer":
        command = [
            sys.executable,
            str(script_path("auto_finalizer_merge.py")),
            "--project-root",
            project_root,
            "--base-branch",
            args.base_branch,
            "--fetch",
            "--json",
        ]
        if args.apply:
            command.append("--apply")
        return [command]
    raise ValueError(f"unsupported deterministic mode: {args.mode}")


def worker_lock_preflight(args: argparse.Namespace, project_root: Path) -> dict[str, Any] | None:
    if args.mode not in {"all", "workers"}:
        return None
    cfg = {
        "project_id": args.project_id,
        "name": args.project_id,
        "local_path": str(project_root),
        "base_ref": args.base_ref,
        "base_branch": args.base_branch,
        "task_queue_github_ref": args.base_ref,
    }
    return runner_readiness_report.worker_lock_preflight(cfg, {})

def main() -> int:
    args = parse_args()
    runtime_root = Path(args.runtime_root).expanduser()
    project_root = Path(args.project_root).expanduser()
    run_id = args.run_id or f"manual-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    path = status_path(runtime_root, args.project_id)
    started_at = now_utc()

    if not project_root.exists():
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "project_id": args.project_id,
            "project_root": str(project_root),
            "mode": args.mode,
            "state": "rejected",
            "started_at": started_at,
            "finished_at": started_at,
            "error": "project_root_missing",
        }
        write_json(path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2

    if args.mode in UNSUPPORTED_DETERMINISTIC_MODES:
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "project_id": args.project_id,
            "project_root": str(project_root),
            "mode": args.mode,
            "state": "rejected",
            "started_at": started_at,
            "finished_at": started_at,
            "error": "unsupported_deterministic_mode",
        }
        write_json(path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2

    commands = build_commands(args)
    # Full-cycle generated-state recovery belongs to status_orchestrator. Do
    # not reject its recoverable dirty state in this wrapper before the child
    # can validate and persist it.
    pre_run_sync = (
        sync_project_root_before_run(project_root, args.base_branch)
        if args.apply and args.mode != "all"
        else None
    )
    if args.apply and pre_run_sync is not None and pre_run_sync.get("ok") is False:
        finished_at = now_utc()
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "project_id": args.project_id,
            "project_root": str(project_root),
            "base_ref": args.base_ref,
            "base_branch": args.base_branch,
            "mode": args.mode,
            "apply": bool(args.apply),
            "state": "rejected",
            "started_at": started_at,
            "finished_at": finished_at,
            "updated_at": finished_at,
            "error": "pre_run_sync_failed",
            "pre_run_sync": pre_run_sync,
            "commands": commands,
        }
        write_json(path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    preflight = worker_lock_preflight(args, project_root) if args.apply else None
    if args.apply and preflight is not None and not preflight.get("ok"):
        finished_at = now_utc()
        payload = {
            "schema_version": "1.0",
            "run_id": run_id,
            "project_id": args.project_id,
            "project_root": str(project_root),
            "base_ref": args.base_ref,
            "base_branch": args.base_branch,
            "mode": args.mode,
            "apply": bool(args.apply),
            "state": "rejected",
            "started_at": started_at,
            "finished_at": finished_at,
            "updated_at": finished_at,
            "error": "worker_lock_preflight_failed",
            "preflight": preflight,
            "commands": commands,
        }
        write_json(path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    payload = {
        "schema_version": "1.0",
        "run_id": run_id,
        "project_id": args.project_id,
        "project_root": str(project_root),
        "base_ref": args.base_ref,
        "base_branch": args.base_branch,
        "mode": args.mode,
        "apply": bool(args.apply),
        "state": "running",
        "created_at": started_at,
        "started_at": started_at,
        "updated_at": started_at,
        "expires_at": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=4)).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "pid": os.getpid(),
        "commands": commands,
        "pre_run_sync": pre_run_sync,
        "preflight": preflight,
    }
    write_json(path, payload)

    if not args.apply:
        finished_at = now_utc()
        result_payload = {**payload, "state": "succeeded", "updated_at": finished_at, "finished_at": finished_at, "returncode": 0, "planned_commands": commands, "results": []}
        write_json(path, result_payload)
        print(json.dumps(result_payload, ensure_ascii=False, indent=2) if args.json else f"succeeded: {run_id}")
        return 0

    results = []
    returncode = 0
    for command in commands:
        proc = subprocess.run(command, text=True, capture_output=True, check=False)
        result = {"command": command, "returncode": proc.returncode, "stdout": proc.stdout[-12000:], "stderr": proc.stderr[-12000:]}
        parsed_json = parse_stdout_json(proc.stdout)
        if parsed_json is not None:
            result["parsed_json"] = parsed_json
        results.append(result)
        if proc.returncode != 0:
            returncode = proc.returncode
            break
    child_blocker = first_child_blocker(results) if returncode == 0 else None
    no_op = None
    if returncode == 0:
        no_op = child_scan_only_noop_result(results)
    if returncode == 0 and no_op is None and args.mode == "finalizer":
        no_op = finalizer_noop_result(results)
    elif returncode == 0 and no_op is None and args.mode == "model_limit_retries":
        no_op = model_limit_retry_noop_result(results)
    if args.apply:
        if no_op is not None:
            if not no_op.get("skip_process_state"):
                update_process_state(project_root, args.mode, "idle", str(no_op.get("decision") or f"manual_{args.mode}_noop"))
        elif child_blocker is not None:
            update_process_state(
                project_root,
                args.mode,
                "blocked",
                f"manual_{args.mode}_blocked",
                details=child_blocker,
            )
        elif returncode == 0:
            update_process_state(project_root, args.mode, "completed", f"manual_{args.mode}_succeeded")
        else:
            failed = results[-1] if results else {}
            update_process_state(
                project_root,
                args.mode,
                "failed_retryable",
                f"manual_{args.mode}_exit_{returncode}",
                details={
                    "returncode": returncode,
                    "command": failed.get("command") or [],
                    "stdout_tail": str(failed.get("stdout") or "")[-4000:],
                    "stderr_tail": str(failed.get("stderr") or "")[-4000:],
                },
            )
    state_sync = None
    if args.apply and returncode != 0 and args.mode == "dispatcher":
        state_sync = rollback_failed_dispatcher_state(project_root, args.base_branch)
    elif args.apply and returncode == 0 and no_op is not None and not no_op.get("skip_process_state"):
        state_sync = sync_task_manager_state(project_root, args.base_branch, args.mode)
        if state_sync and state_sync.get("ok") is False:
            returncode = 1
    elif args.apply and returncode == 0 and no_op is None:
        if nested_state_commit_recorded(results):
            state_sync = sync_after_nested_state_commit(project_root, args.base_branch, args.mode)
            if state_sync and state_sync.get("ok") is False:
                returncode = 1
        else:
            state_sync = sync_task_manager_state(project_root, args.base_branch, args.mode)
            if state_sync and state_sync.get("ok") is False:
                returncode = 1
    finished_at = now_utc()
    state = "no_op" if returncode == 0 and no_op is not None else "blocked" if returncode == 0 and child_blocker is not None else "succeeded" if returncode == 0 else "failed"
    result_payload = {**payload, "state": state, "updated_at": finished_at, "finished_at": finished_at, "returncode": returncode, "results": results, "state_sync": state_sync}
    if child_blocker is not None:
        result_payload["blocked_by_child"] = child_blocker
    if no_op is not None:
        result_payload["no_op"] = no_op
    write_json(path, result_payload)
    print(json.dumps(result_payload, ensure_ascii=False, indent=2) if args.json else f"{state}: {run_id}")
    return returncode


if __name__ == "__main__":
    raise SystemExit(main())
