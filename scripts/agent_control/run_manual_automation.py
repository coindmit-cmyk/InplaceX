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
import re
import subprocess
import sys
import uuid
from contextvars import ContextVar
from pathlib import Path
from typing import Any, Callable

import project_state_validator
import post_finalizer_cleanup
import runner_readiness_report
import model_resource_router
from project_paths import task_manager_dir as resolved_task_manager_dir
from task_control_cutover import (
    AUTHORITY_ENV,
    COMPATIBILITY_ARTIFACTS,
    CutoverSession,
    TaskControlConfigurationError,
    configured_path,
    database_for,
    load_runtime_config,
)

ALLOWED_MODES = {"all", "architect", "dispatcher", "workers", "one_task", "integrator", "finalizer", "model_limit_retries", "release_locks", "pr_intake", "result_handoff", "full_intake"}
UNSUPPORTED_DETERMINISTIC_MODES: set[str] = set()
PROCESS_ROLE_BY_MODE = {
    "all": "orchestrator",
    "architect": "architect",
    "dispatcher": "dispatcher",
    "workers": "worker_pool",
    "one_task": "worker_pool",
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
# PostgreSQL owns queue/history in cutover, but every child mode can append to
# the Git-compatible event ledger in its session mirror. Persist that ledger
# after every successful cutover command so events are never session-local.
CUTOVER_GIT_ARTIFACT_SYNC_MODES = frozenset(ALLOWED_MODES)
SYSTEMD_UNIT_COMPONENT_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.@:-]*$")
ACTIVE_CUTOVER_SESSION: ContextVar[CutoverSession | None] = ContextVar(
    "active_cutover_session", default=None
)


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def write_bytes_atomic(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    try:
        tmp.write_bytes(content)
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--base-branch", default="develop")
    parser.add_argument("--mode", default="all", choices=sorted(ALLOWED_MODES))
    parser.add_argument("--model-limit-retry-limit", type=int, default=5, help="Maximum blocked_model_limit tasks to authorize for retry.")
    parser.add_argument("--task-id", default="", help="Exact task for one_task mode.")
    parser.add_argument("--worker-id", default="auto-worker-5.3-mini", help="Worker profile for one_task mode.")
    parser.add_argument("--max-total-workers", type=int, default=4, help="Maximum worker lanes for all/workers modes.")
    parser.add_argument("--max-tasks-per-lane", type=int, default=0, help="Override worker max tasks per lane for workers mode. 0 uses profile defaults.")
    parser.add_argument("--worker-pool-systemd-unit", default="", help="Managed worker-pool unit for all mode. On Linux the default is derived from project-id.")
    parser.add_argument("--worker-pool-systemd-scope", choices=("user", "system"), default="user")
    parser.add_argument("--worker-pool-systemd-after-unit", default="")
    parser.add_argument("--run-id", default="")
    parser.add_argument(
        "--task-control-config",
        default="",
        help="Task Control config. Cutover mode opens a managed PostgreSQL session.",
    )
    parser.add_argument("--apply", action="store_true", help="Execute write-capable automation. Default is dry-run plan.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def managed_worker_pool_unit(project_id: str) -> str:
    component = str(project_id or "").strip().lower()
    if not SYSTEMD_UNIT_COMPONENT_PATTERN.fullmatch(component):
        raise ValueError(f"project-id cannot form a managed systemd unit: {project_id}")
    return f"{component}-worker-pool.service"


def worker_pool_systemd_unit(args: argparse.Namespace) -> str:
    if getattr(args, "disable_worker_pool_systemd", False) is True:
        return ""
    explicit = str(args.worker_pool_systemd_unit or "").strip()
    if explicit:
        return explicit
    return managed_worker_pool_unit(args.project_id) if sys.platform.startswith("linux") else ""


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
        decoder = json.JSONDecoder()
        index = 0
        objects: list[dict[str, Any]] = []
        while index < len(text):
            start = text.find("{", index)
            if start < 0:
                break
            try:
                candidate, end = decoder.raw_decode(text, start)
            except json.JSONDecodeError:
                index = start + 1
                continue
            if isinstance(candidate, dict):
                objects.append(candidate)
            index = end
        return objects[-1] if objects else None
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


def sql_attempt_terminal_status(
    returncode: int,
    no_op: dict[str, Any] | None,
    child_blocker: dict[str, Any] | None,
) -> str:
    if returncode != 0:
        return "failed"
    if no_op is not None or child_blocker is not None:
        return "cancelled"
    return "succeeded"


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
        command = result.get("command") if isinstance(result.get("command"), list) else []
        worker_status = str(payload.get("status") or payload.get("state") or "").strip()
        if (
            len(command) > 1
            and str(command[1]).endswith("run_worker_cycle.py")
            and worker_status in {"no_task", "model_unavailable", "worker_host_unavailable"}
        ):
            return {
                "decision": worker_status,
                "reason": worker_status,
                "payload": payload,
                "command": command,
            }
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
    if mode in {"pr_intake", "full_intake", "all"}:
        roots.extend([
            "AiStudio/Project_state/intake/inbox",
            "docs/reports/change-intake/pr-cycle",
        ])
    if mode in {"full_intake", "all"}:
        roots.append(PROJECT_STATE_SUMMARY_PATH)
    if mode == "all":
        # post_finalizer_cleanup archives Git-owned compatibility inputs under
        # these roots even though queue/history live in the SQL session mirror.
        roots.extend(["docs/plans", "old/agent-runs/finalized"])
    return roots


def normalized_repo_path(path: Any) -> str:
    return str(path or "").replace("\\", "/").strip()


def referenced_worker_report_paths(project_root: Path) -> set[str]:
    queue = load_json(resolved_task_manager_dir(project_root) / "task_queue.json")
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
    if mode in {"pr_intake", "full_intake", "all"} and normalized.startswith("docs/reports/change-intake/pr-cycle/"):
        return True
    return not runner_readiness_report.readiness_ignored_dirty_path(path)


def merge_jsonl_state(ours: str, theirs: str) -> str:
    rows: list[dict[str, Any]] = []
    event_indexes: dict[str, int] = {}
    anonymous_rows: set[str] = set()
    for text in (ours, theirs):
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError("event ledger rows must be JSON objects")
            current_event_id = str(row.get("event_id") or "").strip()
            if not current_event_id:
                canonical = json.dumps(row, ensure_ascii=False, sort_keys=True)
                if canonical in anonymous_rows:
                    continue
                anonymous_rows.add(canonical)
                rows.append(row)
                continue
            existing_index = event_indexes.get(current_event_id)
            if existing_index is None:
                event_indexes[current_event_id] = len(rows)
                rows.append(row)
                continue
            existing = rows[existing_index]
            merged = {**existing, **row}
            consumed = row if row.get("consumed_by") else existing
            if consumed.get("consumed_by"):
                merged["consumed_by"] = consumed["consumed_by"]
                if consumed.get("consumed_at"):
                    merged["consumed_at"] = consumed["consumed_at"]
            rows[existing_index] = merged
    return "".join(
        json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n"
        for row in rows
    )


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
        and not (mode in {"pr_intake", "full_intake", "all"} and path.startswith(inbox_prefix) and path.endswith(".json"))
        and not (mode in {"pr_intake", "full_intake", "all"} and path.startswith(report_prefix))
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


def refresh_project_state_before_push(
    project_root: Path,
    run_git: Callable[[list[str]], subprocess.CompletedProcess[str]],
) -> dict[str, Any]:
    report = project_state_validator.refresh_ref_and_validate(project_root, apply=True)
    if not report.get("ok") or report.get("skipped"):
        return report
    if report.get("refreshed"):
        add_summary = run_git(["add", "--", PROJECT_STATE_SUMMARY_PATH])
        if add_summary.returncode != 0:
            return {
                **report,
                "ok": False,
                "reason": "project_state_summary_add_failed",
            }
        amend = run_git(["commit", "--amend", "--no-edit"])
        if amend.returncode != 0:
            return {
                **report,
                "ok": False,
                "reason": "project_state_summary_amend_failed",
            }
        committed = project_state_validator.refresh_ref_and_validate(project_root)
        if not committed.get("ok") or committed.get("source_was_stale"):
            return {
                **report,
                "ok": False,
                "reason": "project_state_committed_snapshot_invalid",
                "committed_validation": committed,
            }
        report["committed_validation"] = committed
    return report


def sync_task_manager_state(project_root: Path, base_branch: str, mode: str) -> dict[str, Any]:
    worktree = git_run(project_root, ["rev-parse", "--is-inside-work-tree"])
    if worktree.returncode != 0:
        if mode == "release_locks":
            return {"skipped": True, "ok": True, "reason": "non_git_release_locks_state"}
        return {"skipped": False, "ok": False, "reason": "not_git_worktree", "stderr": worktree.stderr}
    initial_project_state = project_state_validator.refresh_ref_and_validate(
        project_root,
        apply=True,
    )
    if not initial_project_state.get("ok"):
        return {
            "skipped": False,
            "ok": False,
            "reason": "project_state_refresh_failed",
            "project_state": initial_project_state,
        }
    sync_roots = state_sync_roots(mode)
    if not initial_project_state.get("skipped") and PROJECT_STATE_SUMMARY_PATH not in sync_roots:
        sync_roots.append(PROJECT_STATE_SUMMARY_PATH)
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

    def project_state_pre_push_gate() -> dict[str, Any]:
        return refresh_project_state_before_push(project_root, run_git)

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
                project_state = project_state_pre_push_gate()
                if not project_state.get("ok"):
                    return {
                        "skipped": False,
                        "ok": False,
                        "reason": "project_state_validation_failed_after_conflict_resolution",
                        "project_state": project_state,
                        "conflict_resolution": conflict_resolution,
                        "commands": commands,
                    }
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
    project_state = project_state_pre_push_gate()
    if not project_state.get("ok"):
        return {
            "skipped": False,
            "ok": False,
            "reason": "project_state_validation_failed_after_base_sync",
            "project_state": project_state,
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


def rollback_cutover_intake_artifacts(project_root: Path, mode: str) -> dict[str, Any]:
    """Discard only artifacts generated after a clean cutover preflight."""
    roots = (
        state_sync_roots(mode)
        if mode == "all"
        else [root for root in state_sync_roots(mode) if root != "AiStudio/Task_manager"]
    )
    if not roots:
        return {"ok": True, "reason": "no_cutover_git_artifact_roots", "paths": []}
    status = git_run(project_root, ["status", "--porcelain", "--", *roots])
    if status.returncode != 0:
        return {"ok": False, "reason": "cutover_git_artifact_status_failed", "stderr": status.stderr}
    lines = [line for line in status.stdout.splitlines() if line.strip()]
    tracked = [porcelain_path(line) for line in lines if not line.startswith("?? ")]
    untracked = [porcelain_path(line) for line in lines if line.startswith("?? ")]
    tracked = [path for path in tracked if path]
    untracked = [path for path in untracked if path]
    commands: list[dict[str, Any]] = []
    if tracked:
        restored = git_run(project_root, ["restore", "--staged", "--worktree", "--", *tracked])
        commands.append({"command": ["git", "restore", "--staged", "--worktree", "--", *tracked], "exit_code": restored.returncode})
        if restored.returncode != 0:
            return {"ok": False, "reason": "cutover_git_artifact_restore_failed", "paths": tracked, "commands": commands}
    if untracked:
        cleaned = git_run(project_root, ["clean", "-fd", "--", *untracked])
        commands.append({"command": ["git", "clean", "-fd", "--", *untracked], "exit_code": cleaned.returncode})
        if cleaned.returncode != 0:
            return {"ok": False, "reason": "cutover_git_artifact_clean_failed", "paths": untracked, "commands": commands}
    verify = git_run(project_root, ["status", "--porcelain", "--", *roots])
    if verify.returncode != 0 or verify.stdout.strip():
        return {
            "ok": False,
            "reason": "cutover_git_artifact_rollback_incomplete",
            "remaining": verify.stdout.splitlines(),
            "commands": commands,
        }
    return {
        "ok": True,
        "reason": "cutover_git_artifacts_rolled_back",
        "paths": sorted(set(tracked + untracked)),
        "commands": commands,
    }


def rollback_failed_compatibility_sync(
    project_root: Path,
    materialization: dict[str, Any],
) -> dict[str, Any]:
    """Clean only recoverable sidecars after their Git publication fails."""
    allowed = {
        f"AiStudio/Task_manager/{name}" for name in COMPATIBILITY_ARTIFACTS
    }
    paths = sorted(
        {
            normalized_repo_path(path)
            for path in materialization.get("paths") or []
            if normalized_repo_path(path) in allowed
        }
        | {PROJECT_STATE_SUMMARY_PATH}
    )
    commands: list[dict[str, Any]] = []

    def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
        proc = git_run(project_root, args)
        commands.append(
            {
                "command": ["git", *args],
                "exit_code": proc.returncode,
                "stdout": proc.stdout,
                "stderr": proc.stderr,
            }
        )
        return proc

    status = run_git(["status", "--porcelain", "--", *paths])
    if status.returncode != 0:
        return {
            "ok": False,
            "reason": "compatibility_sync_rollback_status_failed",
            "paths": paths,
            "commands": commands,
        }
    lines = [line for line in status.stdout.splitlines() if line.strip()]
    staged = [porcelain_path(line) for line in lines if not line.startswith("?? ") and line[0] != " "]
    staged = [path for path in staged if path]
    if staged:
        unstaged = run_git(["restore", "--staged", "--", *staged])
        if unstaged.returncode != 0:
            return {
                "ok": False,
                "reason": "compatibility_sync_rollback_unstage_failed",
                "paths": staged,
                "commands": commands,
            }
    refreshed = run_git(["status", "--porcelain", "--", *paths])
    if refreshed.returncode != 0:
        return {
            "ok": False,
            "reason": "compatibility_sync_rollback_refresh_failed",
            "paths": paths,
            "commands": commands,
        }
    refreshed_lines = [line for line in refreshed.stdout.splitlines() if line.strip()]
    tracked = [
        porcelain_path(line) for line in refreshed_lines if not line.startswith("?? ")
    ]
    untracked = [
        porcelain_path(line) for line in refreshed_lines if line.startswith("?? ")
    ]
    tracked = [path for path in tracked if path]
    untracked = [path for path in untracked if path]
    if tracked:
        restored = run_git(["restore", "--worktree", "--", *tracked])
        if restored.returncode != 0:
            return {
                "ok": False,
                "reason": "compatibility_sync_rollback_restore_failed",
                "paths": tracked,
                "commands": commands,
            }
    if untracked:
        cleaned = run_git(["clean", "-f", "--", *untracked])
        if cleaned.returncode != 0:
            return {
                "ok": False,
                "reason": "compatibility_sync_rollback_clean_failed",
                "paths": untracked,
                "commands": commands,
            }
    verify = run_git(["status", "--porcelain", "--", *paths])
    if verify.returncode != 0 or verify.stdout.strip():
        return {
            "ok": False,
            "reason": "compatibility_sync_rollback_incomplete",
            "remaining": verify.stdout.splitlines(),
            "paths": paths,
            "commands": commands,
        }
    return {
        "ok": True,
        "reason": "failed_compatibility_sync_rolled_back",
        "paths": sorted(set(tracked + untracked)),
        "commands": commands,
    }


def materialize_cutover_compatibility_artifacts(
    project_root: Path,
    session: CutoverSession,
    mode: str,
) -> dict[str, Any]:
    if mode not in CUTOVER_GIT_ARTIFACT_SYNC_MODES:
        return {"ok": True, "reason": "no_compatibility_artifacts", "paths": []}
    task_manager = getattr(session, "task_manager", None)
    if task_manager is None:
        return {
            "ok": False,
            "reason": "cutover_session_task_manager_missing",
            "paths": [],
        }
    materialized: list[str] = []
    unchanged: list[str] = []
    originals: list[tuple[Path, bytes | None]] = []
    try:
        for name in COMPATIBILITY_ARTIFACTS:
            relative = Path("AiStudio/Task_manager") / name
            source = Path(task_manager) / name
            if not source.is_file():
                continue
            target = project_root / relative
            content = source.read_bytes()
            if target.is_file() and target.read_bytes() == content:
                unchanged.append(relative.as_posix())
                continue
            previous = target.read_bytes() if target.is_file() else None
            write_bytes_atomic(target, content)
            originals.append((target, previous))
            materialized.append(relative.as_posix())
    except OSError as exc:
        rollback_errors: list[str] = []
        for target, previous in reversed(originals):
            try:
                if previous is None:
                    target.unlink(missing_ok=True)
                else:
                    write_bytes_atomic(target, previous)
            except OSError as rollback_exc:
                rollback_errors.append(f"{target}: {rollback_exc}")
        return {
            "ok": False,
            "reason": "compatibility_artifact_materialization_failed",
            "paths": materialized,
            "error": str(exc),
            "rolled_back": not rollback_errors,
            "rollback_errors": rollback_errors,
            "recovery_required": True,
        }
    if not materialized:
        return {
            "ok": True,
            "reason": (
                "compatibility_artifacts_unchanged"
                if unchanged
                else "compatibility_artifacts_absent"
            ),
            "paths": unchanged,
        }
    return {
        "ok": True,
        "reason": "compatibility_artifacts_materialized",
        "paths": materialized,
        "unchanged_paths": unchanged,
    }


def sync_project_root_before_run(
    project_root: Path,
    base_branch: str,
    *,
    defer_project_state_publication: bool = False,
) -> dict[str, Any]:
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
    pending = run_git(["rev-list", f"origin/{branch}..HEAD"])
    if pending.returncode != 0:
        return {
            "skipped": False,
            "ok": False,
            "reason": "pre_run_pending_commit_scan_failed",
            "commands": commands,
        }
    pending_commits = [line.strip() for line in pending.stdout.splitlines() if line.strip()]
    if pending_commits:
        safe_prefixes = (
            f"{WORKER_REPORT_SYNC_ROOT}/",
            "AiStudio/Project_state/intake/inbox/",
            "docs/reports/change-intake/pr-cycle/",
            "docs/plans/",
            "old/agent-runs/finalized/",
            "AiStudio/Task_manager/reports/",
            "AiStudio/Task_manager/process-logs/",
        )
        safe_exact = {
            PROJECT_STATE_SUMMARY_PATH,
            "AiStudio/Task_manager/process_locks.json",
            *(
                f"AiStudio/Task_manager/{name}"
                for name in COMPATIBILITY_ARTIFACTS
            ),
            *(
                normalized_repo_path(path)
                for path in post_finalizer_cleanup.EXACT_ARTIFACTS
                if normalized_repo_path(path).startswith("AiStudio/Task_manager/")
            ),
        }
        unsafe: list[dict[str, Any]] = []
        pending_paths: set[str] = set()
        pending_modes: set[str] = set()
        for commit_sha in pending_commits:
            subject = run_git(["show", "-s", "--format=%s", commit_sha])
            paths = run_git(
                ["diff-tree", "--no-commit-id", "--name-only", "-r", commit_sha]
            )
            changed_paths = [
                normalized_repo_path(path)
                for path in paths.stdout.splitlines()
                if normalized_repo_path(path)
            ]
            pending_paths.update(changed_paths)
            subject_text = str(subject.stdout or "").strip()
            subject_match = re.fullmatch(
                r"chore\(agent\): record manual ([a-z0-9_]+) state",
                subject_text,
            )
            if subject_match:
                pending_modes.add(subject_match.group(1))
            if (
                subject.returncode != 0
                or paths.returncode != 0
                or subject_match is None
                or not changed_paths
                or any(
                    path not in safe_exact
                    and not any(path.startswith(prefix) for prefix in safe_prefixes)
                    for path in changed_paths
                )
            ):
                unsafe.append(
                    {
                        "commit": commit_sha,
                        "subject": subject_text,
                        "paths": changed_paths,
                    }
                )
        if unsafe:
            return {
                "skipped": False,
                "ok": False,
                "reason": "pre_run_unpublished_commits_not_safe_artifacts",
                "unsafe_commits": unsafe,
                "commands": commands,
            }
        conflict_resolution = None
        rebase = run_git(["rebase", f"origin/{branch}"])
        if rebase.returncode != 0:
            conflict_mode = next(
                (
                    mode
                    for mode in ("all", "full_intake", "pr_intake")
                    if mode in pending_modes
                ),
                next(iter(sorted(pending_modes)), ""),
            )
            conflict_resolution = resolve_manual_state_rebase_conflicts(
                project_root,
                conflict_mode,
                run_git,
            )
            if conflict_resolution.get("ok"):
                rebase_continue = run_git(
                    ["-c", "core.editor=true", "rebase", "--continue"]
                )
                if rebase_continue.returncode == 0:
                    rebase = rebase_continue
                else:
                    conflict_resolution = {
                        **conflict_resolution,
                        "ok": False,
                        "reason": "rebase_continue_failed",
                    }
            if rebase.returncode != 0:
                abort = run_git(["rebase", "--abort"])
                return {
                    "skipped": False,
                    "ok": False,
                    "reason": (
                        "pre_run_pending_artifact_rebase_failed"
                        if abort.returncode == 0
                        else "pre_run_pending_artifact_rebase_abort_failed"
                    ),
                    "pending_commits": pending_commits,
                    "conflict_resolution": conflict_resolution,
                    "commands": commands,
                }
        project_state = None
        if PROJECT_STATE_SUMMARY_PATH in pending_paths:
            if defer_project_state_publication:
                return {
                    "skipped": False,
                    "ok": True,
                    "reason": "pre_run_pending_project_state_deferred",
                    "pending_commits": pending_commits,
                    "pending_paths": sorted(pending_paths),
                    "project_state_deferred": True,
                    "conflict_resolution": conflict_resolution,
                    "commands": commands,
                }
            project_state = refresh_project_state_before_push(project_root, run_git)
            if not project_state.get("ok"):
                return {
                    "skipped": False,
                    "ok": False,
                    "reason": "pre_run_pending_artifact_project_state_validation_failed",
                    "pending_commits": pending_commits,
                    "project_state": project_state,
                    "conflict_resolution": conflict_resolution,
                    "commands": commands,
                }
        publish = run_git(["push", "origin", f"HEAD:{branch}"])
        if publish.returncode != 0:
            return {
                "skipped": False,
                "ok": False,
                "reason": "pre_run_pending_artifact_push_failed",
                "pending_commits": pending_commits,
                "conflict_resolution": conflict_resolution,
                "commands": commands,
            }
        return {
            "skipped": False,
            "ok": True,
            "reason": "pre_run_pending_artifacts_published",
            "pending_commits": pending_commits,
            "project_state": project_state,
            "conflict_resolution": conflict_resolution,
            "commands": commands,
        }
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


def publish_deferred_project_state_artifacts(
    project_root: Path,
    base_branch: str,
) -> dict[str, Any]:
    branch = branch_name_from_ref(base_branch)
    commands: list[dict[str, Any]] = []

    def run_git(args: list[str]) -> subprocess.CompletedProcess[str]:
        proc = git_run(project_root, args)
        commands.append(
            {
                "command": ["git", *args],
                "exit_code": proc.returncode,
                "stdout": proc.stdout,
                "stderr": proc.stderr,
            }
        )
        return proc

    project_state = refresh_project_state_before_push(project_root, run_git)
    if not project_state.get("ok"):
        return {
            "ok": False,
            "reason": "deferred_project_state_validation_failed",
            "project_state": project_state,
            "commands": commands,
        }
    publish = run_git(["push", "origin", f"HEAD:{branch}"])
    if publish.returncode != 0:
        return {
            "ok": False,
            "reason": "deferred_project_state_push_failed",
            "project_state": project_state,
            "commands": commands,
        }
    return {
        "ok": True,
        "reason": "deferred_project_state_published",
        "project_state": project_state,
        "commands": commands,
    }


def persist_final_run_report(
    path: Path,
    payload: dict[str, Any],
    *,
    durable_task_control_commit: bool,
) -> dict[str, Any]:
    try:
        write_json(path, payload)
    except OSError as exc:
        if not durable_task_control_commit:
            raise
        payload["runtime_report"] = {
            "ok": False,
            "error_type": type(exc).__name__,
            "message": str(exc),
            "recovery_required": True,
            "durable_result_preserved": True,
        }
    return payload


def status_path(runtime_root: Path, project_id: str) -> Path:
    return runtime_root / "manual-runs" / f"{project_id}.json"


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def task_manager_dir(project_root: Path) -> Path:
    return resolved_task_manager_dir(project_root)


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
        systemd_unit = worker_pool_systemd_unit(args)
        if systemd_unit:
            command.extend(
                [
                    "--worker-pool-systemd-unit",
                    systemd_unit,
                    "--worker-pool-systemd-scope",
                    args.worker_pool_systemd_scope,
                ]
            )
            if args.worker_pool_systemd_after_unit:
                command.extend(
                    [
                        "--worker-pool-systemd-after-unit",
                        args.worker_pool_systemd_after_unit,
                    ]
                )
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
    if args.mode == "one_task":
        if not args.task_id:
            raise ValueError("one_task mode requires --task-id")
        command = [
            sys.executable,
            str(script_path("run_worker_cycle.py")),
            "--project-root", project_root,
            "--base-ref", args.base_ref,
            "--push-ref", args.base_branch,
            "--worker-base-ref", args.base_ref,
            "--worker-context-ref", args.base_ref,
            "--worker-id", args.worker_id,
            "--task-id", args.task_id,
            "--machine-id", "aistudio-manual",
            "--runtime-root", runtime_root,
            "--json",
        ]
        if not args.apply:
            command.append("--dry-run")
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
        queue_path = str(task_manager_dir(Path(project_root)) / "task_queue.json")
        locks_path = str(task_manager_dir(Path(project_root)) / "agent_locks.json")
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
            str(task_manager_dir(Path(project_root)) / "reports" / "full-intake-cycle" if args.apply else Path(runtime_root) / "full-intake-cycle" / args.project_id),
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
            str(task_manager_dir(Path(project_root)) / "reports" / "worker_result_handoff_gate.json" if args.apply else Path(runtime_root) / "worker-result-handoff" / f"{args.project_id}.json"),
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


def serialize_cutover_worker_lanes(args: argparse.Namespace) -> bool:
    """Protect the single per-project SQL mirror from concurrent worker writers."""
    if args.mode not in {"all", "workers"}:
        return False
    args.max_total_workers = 1
    # A detached systemd unit outlives the compatibility session and does not
    # inherit its authority/DSN environment. Keep the single worker in-process.
    args.disable_worker_pool_systemd = True
    if args.mode == "workers":
        args.max_tasks_per_lane = 1
    return True


def apply_cutover_exact_task_route(
    project_root: Path,
    runtime_root: Path,
    task_id: str,
    commands: list[list[str]],
) -> dict[str, Any]:
    queue = load_json(task_manager_dir(project_root) / "task_queue.json")
    task = next(
        (
            item
            for item in queue.get("tasks") or []
            if isinstance(item, dict)
            and str(item.get("id") or item.get("task_id") or "") == task_id
        ),
        None,
    )
    if task is None:
        raise ValueError(f"exact SQL task is missing from the cutover session: {task_id}")
    route = model_resource_router.route(
        project_root,
        runtime_root,
        "worker",
        task,
    )
    if route.get("status") not in {"selected", "fallback_selected"}:
        raise ValueError(
            "exact SQL task model route is blocked: "
            f"{route.get('reason_code') or route.get('reason') or route.get('status')}"
        )
    worker_command = next(
        (
            command
            for command in commands
            if len(command) > 1 and str(command[1]).endswith("run_worker_cycle.py")
        ),
        None,
    )
    if worker_command is None:
        raise ValueError("cutover exact task worker command is missing")
    worker_command.extend(
        [
            "--model",
            str(route["model"]),
            "--reasoning-effort",
            str(route["reasoning_effort"]),
        ]
    )
    return route


def worker_lock_preflight(args: argparse.Namespace, project_root: Path) -> dict[str, Any] | None:
    # Full lifecycle reconciliation is owned by status_orchestrator, which
    # consumes result handoffs and repairs recoverable locks before workers.
    # Keep the strict guard for a direct workers-only launch.
    if args.mode != "workers":
        return None
    if os.environ.get(AUTHORITY_ENV) == "postgres":
        return {
            "ok": True,
            "authority": "postgres",
            "reason": "project_session_serializes_cutover_worker_entry",
        }
    cfg = {
        "project_id": args.project_id,
        "name": args.project_id,
        "local_path": str(project_root),
        "base_ref": args.base_ref,
        "base_branch": args.base_branch,
        "task_queue_github_ref": args.base_ref,
    }
    return runner_readiness_report.worker_lock_preflight(cfg, {})

def _main_impl() -> int:
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

    # Validate the complete command contract before acquiring a SQL session or
    # lease. In particular, one_task without --task-id must leave no residue.
    commands = build_commands(args)
    cutover_session: CutoverSession | None = None
    cutover_preparation: dict[str, Any] | None = None
    sql_attempt_started = False
    task_control_config = None
    config_path = configured_path(args.task_control_config)
    if args.apply:
        try:
            if config_path is None:
                raise TaskControlConfigurationError(
                    "apply-capable manual automation requires an explicit Task Control authority config"
                )
            candidate_config = load_runtime_config(config_path)
            if candidate_config.cutover_enabled:
                task_control_config = candidate_config
                serialized_worker_lanes = serialize_cutover_worker_lanes(args)
                if serialized_worker_lanes:
                    commands = build_commands(args)
            else:
                authority = database_for(candidate_config).health()
                configured_authority = (
                    candidate_config.mode,
                    candidate_config.source_of_truth,
                    candidate_config.cutover_enabled,
                )
                database_authority = (
                    authority.get("mode"),
                    authority.get("source_of_truth"),
                    authority.get("cutover_enabled"),
                )
                if database_authority != configured_authority:
                    raise TaskControlConfigurationError(
                        "task control config authority does not match database authority: "
                        f"config={configured_authority!r}, database={database_authority!r}"
                    )
        except Exception as exc:
            finished_at = now_utc()
            payload = {
                "schema_version": "1.0",
                "run_id": run_id,
                "project_id": args.project_id,
                "project_root": str(project_root),
                "mode": args.mode,
                "apply": bool(args.apply),
                "state": "rejected",
                "started_at": started_at,
                "finished_at": finished_at,
                "error": "task_control_cutover_preparation_failed",
                "task_control": {
                    "config": str(config_path),
                    "error_type": type(exc).__name__,
                    "message": str(exc),
                },
            }
            write_json(path, payload)
            print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
            return 2
    # Synchronize the checkout before a cutover session copies Git-owned
    # compatibility inputs into its external mirror. Otherwise a fast-forward
    # after prepare can leave integration handoffs and batches stale.
    pre_run_sync = (
        sync_project_root_before_run(
            project_root,
            args.base_branch,
            defer_project_state_publication=task_control_config is not None,
        )
        if args.apply and (args.mode != "all" or task_control_config is not None)
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
            "task_control": None,
            "commands": commands,
        }
        write_json(path, payload)
        print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else payload["error"])
        return 2
    if task_control_config is not None:
        try:
            cutover_session = CutoverSession(
                database=database_for(task_control_config),
                config=task_control_config,
                project_id=args.project_id,
                project_root=project_root,
                run_id=run_id,
                owner_id=f"run_manual_automation:{os.getpid()}",
            )
            cutover_preparation = cutover_session.prepare()
            ACTIVE_CUTOVER_SESSION.set(cutover_session)
            if pre_run_sync and pre_run_sync.get("project_state_deferred"):
                deferred_publication = publish_deferred_project_state_artifacts(
                    project_root,
                    args.base_branch,
                )
                cutover_preparation["deferred_project_state_publication"] = (
                    deferred_publication
                )
                if not deferred_publication.get("ok"):
                    raise TaskControlConfigurationError(
                        str(
                            deferred_publication.get("reason")
                            or "deferred project state publication failed"
                        )
                    )
            # Several deterministic modes resolve queue/lock paths while
            # commands are built. Rebuild only after prepare exports the SQL
            # session mirror through AISTUDIO_TASK_MANAGER_DIR.
            commands = build_commands(args)
            cutover_preparation["commands_rebuilt_after_session_prepare"] = True
            cutover_preparation["serialized_worker_lanes"] = serialized_worker_lanes
            if args.mode == "one_task" and args.task_id:
                cutover_preparation["model_route"] = apply_cutover_exact_task_route(
                    project_root,
                    runtime_root,
                    args.task_id,
                    commands,
                )
                cutover_session.database.upsert_attempt(
                    args.project_id,
                    args.task_id,
                    attempt_id=run_id,
                    stage="worker",
                    status="running",
                    metadata={
                        "session_id": cutover_session.session_id,
                        "worker_id": args.worker_id,
                    },
                )
                sql_attempt_started = True
        except Exception as exc:
            if cutover_session is not None:
                cutover_session.abort("task_control_cutover_preparation_failed")
                cutover_session.restore_environment()
            finished_at = now_utc()
            payload = {
                "schema_version": "1.0",
                "run_id": run_id,
                "project_id": args.project_id,
                "project_root": str(project_root),
                "mode": args.mode,
                "apply": bool(args.apply),
                "state": "rejected",
                "started_at": started_at,
                "finished_at": finished_at,
                "error": "task_control_cutover_preparation_failed",
                "task_control": {
                    "config": str(config_path),
                    "error_type": type(exc).__name__,
                    "message": str(exc),
                },
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
            "task_control": cutover_preparation,
            "commands": commands,
        }
        if cutover_session is not None:
            if sql_attempt_started:
                cutover_session.database.upsert_attempt(
                    args.project_id,
                    args.task_id,
                    attempt_id=run_id,
                    stage="worker",
                    status="cancelled",
                    metadata={"reason": "worker_lock_preflight_failed"},
                )
            cutover_session.abort("worker_lock_preflight_failed")
            cutover_session.restore_environment()
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
        "task_control": cutover_preparation,
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
    if cutover_session is not None:
        starter = getattr(cutover_session, "start_heartbeat", None)
        if callable(starter):
            starter()
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
    task_control_commit = None
    compatibility_publication_prepared = None
    if cutover_session is not None and returncode == 0:
        compatibility_publication_prepared = (
            cutover_session.mark_compatibility_publication_pending(
                {
                    "phase": "before_postgres_commit",
                    "mode": args.mode,
                    "repository_roots": [
                        root
                        for root in state_sync_roots(args.mode)
                        if root != "AiStudio/Task_manager"
                        and root != PROJECT_STATE_SUMMARY_PATH
                    ],
                }
            )
        )
        if not compatibility_publication_prepared.get("ok"):
            returncode = 1
    if cutover_session is not None and returncode == 0:
        try:
            task_control_commit = cutover_session.commit()
            state_sync = {
                "skipped": True,
                "ok": True,
                "reason": "task_state_committed_to_postgres",
                "task_control": task_control_commit,
                "compatibility_publication_prepared": compatibility_publication_prepared,
            }
        except Exception as exc:
            returncode = 1
            task_control_commit = {
                "ok": False,
                "error_type": type(exc).__name__,
                "message": str(exc),
            }
            cutover_session.abort("task_control_commit_failed")
            if args.mode in CUTOVER_GIT_ARTIFACT_SYNC_MODES:
                rollback = rollback_cutover_intake_artifacts(project_root, args.mode)
                task_control_commit["git_artifact_rollback"] = rollback
                state_sync = {
                    "skipped": True,
                    "ok": False,
                    "reason": "task_control_commit_failed",
                    "git_artifact_rollback": rollback,
                    "recovery_required": not rollback.get("ok"),
                }
        else:
            try:
                if args.mode in CUTOVER_GIT_ARTIFACT_SYNC_MODES:
                    materialization = materialize_cutover_compatibility_artifacts(
                        project_root,
                        cutover_session,
                        args.mode,
                    )
                    state_sync["git_artifact_materialization"] = materialization
                    if not materialization.get("ok"):
                        publication_marker = (
                            cutover_session.mark_compatibility_publication_pending(
                                materialization
                            )
                        )
                        generated_artifact_rollback = (
                            rollback_cutover_intake_artifacts(
                                project_root,
                                args.mode,
                            )
                        )
                        returncode = 1
                        state_sync.update(
                            {
                                "ok": False,
                                "reason": "task_state_committed_but_git_artifact_materialization_failed",
                                "recovery_required": not (
                                    publication_marker.get("ok")
                                    and generated_artifact_rollback.get("ok")
                                ),
                                "compatibility_publication_pending": publication_marker,
                                "generated_artifact_rollback": generated_artifact_rollback,
                            }
                        )
                    else:
                        git_artifact_sync = sync_task_manager_state(
                            project_root, args.base_branch, args.mode
                        )
                        state_sync["git_artifact_sync"] = git_artifact_sync
                        if not git_artifact_sync.get("ok"):
                            publication_marker = (
                                cutover_session.mark_compatibility_publication_pending(
                                    {
                                        **materialization,
                                        "git_artifact_sync": git_artifact_sync,
                                    }
                                )
                            )
                            publication_rollback = rollback_failed_compatibility_sync(
                                project_root,
                                materialization,
                            )
                            generated_artifact_rollback = (
                                rollback_cutover_intake_artifacts(
                                    project_root,
                                    args.mode,
                                )
                            )
                            returncode = 1
                            state_sync.update(
                                {
                                    "ok": False,
                                    "reason": "task_state_committed_but_git_artifact_sync_failed",
                                    "compatibility_publication_pending": publication_marker,
                                    "git_artifact_rollback": publication_rollback,
                                    "generated_artifact_rollback": generated_artifact_rollback,
                                    "recovery_required": not (
                                        publication_rollback.get("ok")
                                        and generated_artifact_rollback.get("ok")
                                    ),
                                }
                            )
                        else:
                            publication_completion = (
                                cutover_session.complete_compatibility_publication()
                            )
                            state_sync["compatibility_publication_completion"] = (
                                publication_completion
                            )
                            if not publication_completion.get("ok"):
                                returncode = 1
                                state_sync.update(
                                    {
                                        "ok": False,
                                        "reason": "compatibility_publication_marker_resolution_failed",
                                        "recovery_required": True,
                                    }
                                )
            finally:
                cutover_session.finish_publication()
    elif cutover_session is not None:
        publication_prepare_failed = bool(
            compatibility_publication_prepared
            and not compatibility_publication_prepared.get("ok")
        )
        prepare_rollback = None
        if (
            publication_prepare_failed
            and args.mode in CUTOVER_GIT_ARTIFACT_SYNC_MODES
        ):
            prepare_rollback = rollback_cutover_intake_artifacts(
                project_root,
                args.mode,
            )
        abort_reason = (
            "compatibility_publication_prepare_failed"
            if publication_prepare_failed
            else f"child_exit_{returncode}"
        )
        task_control_commit = cutover_session.abort(abort_reason)
        state_sync = {
            "skipped": True,
            "ok": False,
            "reason": (
                "compatibility_publication_prepare_failed"
                if publication_prepare_failed
                else "task_control_session_aborted_after_child_failure"
            ),
            "compatibility_publication_prepared": compatibility_publication_prepared,
        }
        if prepare_rollback is not None:
            state_sync["generated_artifact_rollback"] = prepare_rollback
            state_sync["recovery_required"] = not prepare_rollback.get("ok")
    elif args.apply and returncode != 0 and args.mode == "dispatcher":
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
    if cutover_session is not None and sql_attempt_started:
        try:
            cutover_session.database.upsert_attempt(
                args.project_id,
                args.task_id,
                attempt_id=run_id,
                stage="worker",
                status=sql_attempt_terminal_status(returncode, no_op, child_blocker),
                accepted=None,
                result_digest=(task_control_commit or {}).get("result_digest") if isinstance(task_control_commit, dict) else None,
                metadata={
                    "session_id": cutover_session.session_id,
                    "run_state": state,
                    "returncode": returncode,
                },
            )
        except Exception as exc:
            committed = bool(
                isinstance(task_control_commit, dict)
                and task_control_commit.get("ok") is True
            )
            task_control_commit = {
                **(task_control_commit or {}),
                "attempt_record_error": {
                    "type": type(exc).__name__,
                    "message": str(exc),
                    "recovery_required": committed,
                },
            }
            if not committed:
                returncode = 1
                state = "failed"
    result_payload = {**payload, "state": state, "updated_at": finished_at, "finished_at": finished_at, "returncode": returncode, "results": results, "state_sync": state_sync, "task_control_commit": task_control_commit}
    if child_blocker is not None:
        result_payload["blocked_by_child"] = child_blocker
    if no_op is not None:
        result_payload["no_op"] = no_op
    result_payload = persist_final_run_report(
        path,
        result_payload,
        durable_task_control_commit=bool(
            cutover_session is not None
            and isinstance(task_control_commit, dict)
            and task_control_commit.get("ok") is True
        ),
    )
    if cutover_session is not None:
        cutover_session.restore_environment()
    print(json.dumps(result_payload, ensure_ascii=False, indent=2) if args.json else f"{state}: {run_id}")
    return returncode


def main() -> int:
    token = ACTIVE_CUTOVER_SESSION.set(None)
    try:
        return _main_impl()
    finally:
        session = ACTIVE_CUTOVER_SESSION.get()
        if session is not None:
            try:
                if not session.finished:
                    session.abort("unexpected_exit_cleanup")
            finally:
                session.restore_environment()
        ACTIVE_CUTOVER_SESSION.reset(token)


if __name__ == "__main__":
    raise SystemExit(main())
