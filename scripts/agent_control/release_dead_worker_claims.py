#!/usr/bin/env python3
"""Release in-progress task claims that have no worker result evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_file

from process_log import append_log


TERMINAL_STATUSES = {"done", "integrated", "finalized", "postponed", "failed", "stale_or_superseded", "duplicate_linked"}
TERMINAL_QUEUE_STATUSES = {"done", "finalized", "completed"}
TERMINAL_INTEGRATION_STATUSES = {
    "finalized",
    "closed_no_diff",
    "closed_coordination_only",
    "already_integrated",
    "not_integrated_no_product_payload",
}
ACTIVE_LOCK_STATES = {"locked", "in_progress", "review"}
INACTIVE_WORKER_POOL_STATES = {"completed", "failed", "failed_retryable", "failed_terminal", "idle", "blocked", "needs_human"}
FINALIZE_SCOPE_FAILURE_MARKERS = (
    "outside_allowed_paths",
    "outside allowed_paths",
    "outside allowed paths",
    "worker changed paths outside allowed_paths",
)
RUNTIME_FAILURE_RESULTS = {"blocked"}
RUNTIME_FAILURE_CHECK_STATUSES = {"worker_runtime_error"}
WORKER_REPORT_PREFIXES = (
    "docs/reports/workers/",
    "AiStudio/Task_manager/reports/workers/",
)
RUNTIME_RECOVERY_CLEAR_FIELDS = (
    "worker_id",
    "machine_id",
    "branch",
    "github_branch",
    "started_at",
    "claimed_at",
    "lock_expires_at",
    "merge_commit",
    "finalized_at",
    "finalized_by",
    "synced_from_worker_branch",
    "worker_result_commit",
    "worker_report",
    "last_agent_report",
    "integration_report",
    "worker_check_evidence",
    "not_worker_ready_reason",
    "current_context_verified_at",
    "current_context_verified_by",
    "current_context_reviewed_by",
    "current_context_review",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    events: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            events.append(value)
    return events


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def canonical_project_id(project_root: Path) -> str:
    for relative_path in ("PROJECT_VERSION.json", ".agent/context.json"):
        metadata = load_json(project_root / relative_path)
        value = str(metadata.get("project_id") or metadata.get("project_name") or "").strip()
        if value:
            return value
    return project_root.name


def event_target_keys(event: dict[str, Any]) -> set[str]:
    keys: set[str] = set()
    for field in ("canonical_target_id", "task_id", "source_task_id"):
        value = str(event.get(field) or "").strip()
        if not value:
            continue
        keys.add(value)
        if value.startswith("task:"):
            keys.add(value.removeprefix("task:"))
        else:
            keys.add(f"task:{value}")
    return keys


def integration_recorded_task_ids(events: list[dict[str, Any]]) -> set[str]:
    integrated: set[str] = set()
    finalized: set[str] = set()
    for event in events:
        event_name = str(event.get("event") or "")
        targets = event_target_keys(event)
        if event_name == "integration_invalidated":
            integrated.difference_update(targets)
            finalized.difference_update(targets)
        elif event_name == "finalization_invalidated":
            finalized.difference_update(targets)
        elif event_name in {"integration_recorded", "direct_merge_recorded"}:
            integrated.update(targets)
        elif event_name == "finalization_recorded":
            integrated.update(targets)
            finalized.update(targets)
    return integrated | finalized


def has_result_evidence(task: dict[str, Any]) -> bool:
    for field in ("worker_result_commit", "worker_report", "last_agent_report", "integration_report"):
        if task.get(field):
            return True
    commits = task.get("commits")
    return isinstance(commits, list) and bool(commits)


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def report_field(text: str, label: str) -> str:
    match = re.search(rf"^-\s*{re.escape(label)}:\s*`([^`]+)`\s*$", text, re.IGNORECASE | re.MULTILINE)
    return match.group(1).strip().lower() if match else ""


def commit_changed_paths(project_root: Path, commit: str) -> tuple[list[str], str | None]:
    proc = subprocess.run(
        ["git", "diff-tree", "--no-commit-id", "--name-only", "-r", commit],
        cwd=str(project_root),
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        return [], proc.stderr.strip() or "git diff-tree failed"
    return sorted({line.strip().replace("\\", "/") for line in proc.stdout.splitlines() if line.strip()}), None


def append_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    if not records:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def runtime_recovery_event(
    *,
    project: str,
    task_id_value: str,
    event_name: str,
    false_merge_commit: str,
    worker_report: str,
    check_status: str,
    reason: str,
    now: str,
) -> dict[str, Any]:
    identity = f"{task_id_value}:{event_name}:{false_merge_commit}:{worker_report}"
    suffix = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:20]
    return {
        "schema_version": 1,
        "event_id": f"{event_name}-{suffix}",
        "created_at": now,
        "project": project,
        "event": event_name,
        "role": "release_dead_worker_claims",
        "next_owner": "Dispatcher",
        "next_role": "auto_dispatcher",
        "task_id": task_id_value,
        "canonical_target_id": f"task:{task_id_value}",
        "severity": "warning",
        "reason": reason,
        "consumed_by": None,
        "consumed_at": None,
        "payload": {
            "false_merge_commit": false_merge_commit,
            "worker_report": worker_report,
            "result": "blocked",
            "check_status": check_status,
            "route": "needs_dispatcher_repair",
        },
    }


def recover_runtime_failure_finalization(
    project_root: Path,
    queue: dict[str, Any],
    locks: dict[str, Any],
    events: list[dict[str, Any]],
    *,
    task_id_value: str,
    worker_report: str,
    expected_merge_commit: str,
    reason: str,
    now: str,
    apply: bool,
) -> dict[str, Any]:
    task = next(
        (
            item
            for item in queue.get("tasks") or []
            if isinstance(item, dict) and task_id(item) == task_id_value
        ),
        None,
    )
    if task is None:
        return {"eligible": False, "reason": "task_not_found", "task_id": task_id_value}

    report_rel = worker_report.strip().replace("\\", "/")
    if not report_rel.startswith(WORKER_REPORT_PREFIXES):
        return {"eligible": False, "reason": "worker_report_path_not_allowed", "worker_report": report_rel}
    report_path = (project_root / report_rel).resolve()
    try:
        report_path.relative_to(project_root)
    except ValueError:
        return {"eligible": False, "reason": "worker_report_path_escape", "worker_report": report_rel}
    if not report_path.is_file():
        return {"eligible": False, "reason": "worker_report_not_found", "worker_report": report_rel}

    report_text = report_path.read_text(encoding="utf-8", errors="ignore")
    result_route = report_field(report_text, "Result")
    check_status = report_field(report_text, "Check evidence")
    if result_route not in RUNTIME_FAILURE_RESULTS or check_status not in RUNTIME_FAILURE_CHECK_STATUSES:
        return {
            "eligible": False,
            "reason": "worker_report_not_runtime_failure",
            "result": result_route or "unknown",
            "check_status": check_status or "unknown",
        }

    expected = expected_merge_commit.strip()
    history = task.get("runtime_failure_history")
    prior_attempts = history if isinstance(history, list) else []
    already_recorded = next(
        (
            item
            for item in prior_attempts
            if isinstance(item, dict) and str(item.get("false_merge_commit") or "") == expected
        ),
        None,
    )
    task_merge = str(task.get("merge_commit") or "").strip()
    if not already_recorded and task_merge != expected:
        return {
            "eligible": False,
            "reason": "unexpected_task_merge_commit",
            "expected_merge_commit": expected,
            "task_merge_commit": task_merge or None,
        }

    changed_paths, git_error = commit_changed_paths(project_root, expected)
    if git_error:
        return {"eligible": False, "reason": "merge_commit_unreadable", "error": git_error}
    if changed_paths != [report_rel]:
        return {
            "eligible": False,
            "reason": "false_merge_contains_non_report_changes",
            "changed_paths": changed_paths,
            "expected_changed_paths": [report_rel],
        }

    now_dt = parse_time(now) or datetime.now(timezone.utc)
    active_locks: list[dict[str, Any]] = []
    expired_locks: list[dict[str, Any]] = []
    for lock in locks.get("locks") or []:
        if not isinstance(lock, dict) or str(lock.get("task_id") or "") != task_id_value:
            continue
        if str(lock.get("state") or "") not in ACTIVE_LOCK_STATES:
            continue
        active_locks.append(lock)
        expires_at = parse_time(lock.get("expires_at"))
        if expires_at is None or expires_at > now_dt:
            return {
                "eligible": False,
                "reason": "active_lock_not_expired",
                "lock_state": lock.get("state"),
                "lock_expires_at": lock.get("expires_at"),
            }
        expired_locks.append(lock)

    base_reason = reason.strip() or "worker runtime failure was incorrectly finalized"
    desired_events = [
        runtime_recovery_event(
            project=canonical_project_id(project_root),
            task_id_value=task_id_value,
            event_name=event_name,
            false_merge_commit=expected,
            worker_report=report_rel,
            check_status=check_status,
            reason=base_reason,
            now=now,
        )
        for event_name in (
            "worker_runtime_failure_recorded",
            "integration_invalidated",
            "finalization_invalidated",
            "task_packet_defect",
        )
    ]
    existing_event_ids = {str(event.get("event_id") or "") for event in events}
    events_to_append = [event for event in desired_events if event["event_id"] not in existing_event_ids]
    queue_change_required = not already_recorded or str(task.get("status") or "") != "needs_dispatcher_repair"
    lock_release_plan = [
        {
            "task_id": task_id_value,
            "previous_state": lock.get("state"),
            "expires_at": lock.get("expires_at"),
        }
        for lock in expired_locks
    ]

    if apply:
        if queue_change_required:
            previous_state = {
                key: task.get(key)
                for key in (
                    "status",
                    "worker_ready",
                    "dispatcher_decision",
                    "integration_status",
                    "finalization_status",
                    "merge_commit",
                    "branch",
                    "worker_id",
                    "machine_id",
                    "worker_result_commit",
                )
            }
            failure_record = {
                "recorded_at": now,
                "result": result_route,
                "check_status": check_status,
                "worker_report": report_rel,
                "false_merge_commit": expected,
                "worker_result_commit": task.get("worker_result_commit"),
                "branch": task.get("branch") or task.get("github_branch"),
                "worker_id": task.get("worker_id"),
                "machine_id": task.get("machine_id"),
                "started_at": task.get("started_at"),
                "lock_expires_at": task.get("lock_expires_at"),
                "previous_state": previous_state,
                "reason": base_reason,
            }
            updated_history = [*prior_attempts, failure_record]
            task["runtime_failure_history"] = updated_history
            task["last_failed_worker_report"] = report_rel
            task["last_worker_check_evidence"] = {
                "ok": False,
                "result": result_route,
                "check_status": check_status,
                "integration_status": "blocked_environment",
                "reason": base_reason,
            }
            for field in RUNTIME_RECOVERY_CLEAR_FIELDS:
                task.pop(field, None)
            task.update(
                {
                    "status": "needs_dispatcher_repair",
                    "worker_ready": False,
                    "packet_status": "needs_dispatcher_repair",
                    "normalization_status": "needs_dispatcher_repair",
                    "dispatcher_decision": "needs_dispatcher_repair",
                    "dispatcher_decision_reason": base_reason,
                    "integration_status": "invalidated_runtime_failure",
                    "finalization_status": "invalidated",
                    "next_owner": "Dispatcher",
                    "next_role": "auto_dispatcher",
                    "lock": "free",
                    "requires_current_context_review": True,
                    "repair_owner": "Dispatcher",
                    "repair_request": "Revalidate current develop and restore Worker Packet v2 after the runtime adapter correction.",
                    "next_action": "Dispatcher must refresh context, preserve the failed attempt evidence, and release one retry packet.",
                    "not_worker_ready_reason": "false finalization invalidated after worker runtime failure",
                    "updated_at": now,
                }
            )
            status_history = task.get("status_history")
            if not isinstance(status_history, list):
                status_history = []
            status_history.append(
                {
                    "at": now,
                    "by": "release_dead_worker_claims",
                    "from": previous_state.get("status"),
                    "to": "needs_dispatcher_repair",
                    "event": "worker_launch_failed_requeued",
                    "reason": f"worker launch failed: {base_reason}",
                    "next_owner": "Dispatcher",
                }
            )
            task["status_history"] = status_history
            queue["updated_at"] = now
        for lock in expired_locks:
            lock["previous_state"] = lock.get("state")
            lock["state"] = "released"
            lock["released_at"] = now
            lock["released_by"] = "release_dead_worker_claims"
            lock["release_reason"] = "expired runtime-failure claim invalidated"
        if expired_locks:
            locks["updated_at"] = now

    return {
        "eligible": True,
        "task_id": task_id_value,
        "apply": apply,
        "already_recorded": bool(already_recorded),
        "queue_change_required": queue_change_required,
        "queue_changed": bool(apply and queue_change_required),
        "worker_report": report_rel,
        "result": result_route,
        "check_status": check_status,
        "false_merge_commit": expected,
        "changed_paths": changed_paths,
        "active_lock_count": len(active_locks),
        "expired_lock_release_count": len(expired_locks),
        "lock_release_plan": lock_release_plan,
        "events_to_append": events_to_append,
        "event_append_count": len(events_to_append),
        "idempotent": not queue_change_required and not expired_locks and not events_to_append,
    }


def result_evidence_time(task: dict[str, Any]) -> datetime | None:
    for field in (
        "worker_result_synced_at",
        "integration_report_generated_at",
        "last_agent_report_at",
        "worker_report_generated_at",
    ):
        parsed = parse_time(task.get(field))
        if parsed:
            return parsed
    return None


def has_current_result_evidence(task: dict[str, Any], claim_time: datetime | None) -> bool:
    if not has_result_evidence(task):
        return False
    if claim_time is None:
        return True
    evidence_time = result_evidence_time(task)
    if evidence_time is None:
        return True
    return evidence_time >= claim_time


def pid_is_alive(pid: Any) -> bool:
    try:
        value = int(pid)
    except (TypeError, ValueError):
        return False
    if value <= 0:
        return False
    if os.name == "nt":
        try:
            import ctypes

            kernel32 = ctypes.windll.kernel32
            handle = kernel32.OpenProcess(0x1000, False, value)
            if not handle:
                return False
            exit_code = ctypes.c_ulong()
            queried = kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code))
            kernel32.CloseHandle(handle)
            return bool(queried) and exit_code.value == 259
        except (AttributeError, OSError):
            return False
    try:
        os.kill(value, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def worker_pool_is_inactive(process_state: dict[str, Any]) -> bool:
    processes = process_state.get("processes")
    if not isinstance(processes, dict):
        return False
    worker_pool = processes.get("worker_pool")
    if not isinstance(worker_pool, dict):
        return False
    state = str(worker_pool.get("state") or "").strip()
    active_workers = worker_pool.get("active_workers")
    try:
        active_count = int(active_workers or 0)
    except (TypeError, ValueError):
        active_count = 0
    return state in INACTIVE_WORKER_POOL_STATES and active_count == 0


def worker_pool_systemd_inactivity_evidence(unit: str, scope: str) -> dict[str, Any]:
    """Return fail-closed evidence that a configured worker-pool unit is idle."""
    normalized_unit = str(unit or "").strip()
    normalized_scope = str(scope or "").strip()
    evidence: dict[str, Any] = {
        "unit": normalized_unit or None,
        "scope": normalized_scope or None,
        "inactive": False,
    }
    if not normalized_unit or not re.fullmatch(r"[A-Za-z0-9_.@:-]+", normalized_unit):
        evidence["reason"] = "worker_pool_systemd_unit_invalid"
        return evidence
    if normalized_scope not in {"user", "system"}:
        evidence["reason"] = "worker_pool_systemd_scope_invalid"
        return evidence

    command = ["systemctl"]
    if normalized_scope == "user":
        command.append("--user")
    command.extend(
        [
            "show",
            normalized_unit,
            "--property=ActiveState",
            "--property=MainPID",
            "--property=Job",
        ]
    )
    evidence["command"] = command
    try:
        proc = subprocess.run(command, text=True, capture_output=True, check=False)
    except OSError as exc:
        evidence["reason"] = "worker_pool_systemd_unavailable"
        evidence["error"] = str(exc)
        return evidence
    if proc.returncode != 0:
        evidence["reason"] = "worker_pool_systemd_unavailable"
        evidence["returncode"] = proc.returncode
        evidence["stderr"] = proc.stderr.strip()[-1000:]
        return evidence

    properties: dict[str, str] = {}
    for line in proc.stdout.splitlines():
        key, separator, value = line.partition("=")
        if not separator or key not in {"ActiveState", "MainPID", "Job"}:
            continue
        if key in properties:
            evidence["reason"] = "worker_pool_systemd_evidence_ambiguous"
            return evidence
        properties[key] = value.strip()
    evidence["properties"] = properties
    if set(properties) != {"ActiveState", "MainPID", "Job"}:
        evidence["reason"] = "worker_pool_systemd_evidence_ambiguous"
        return evidence
    if properties["ActiveState"] != "inactive":
        evidence["reason"] = "worker_pool_systemd_active_or_ambiguous"
        return evidence
    try:
        main_pid = int(properties["MainPID"])
    except ValueError:
        evidence["reason"] = "worker_pool_systemd_evidence_ambiguous"
        return evidence
    if main_pid != 0 or properties["Job"] not in {"", "0"}:
        evidence["reason"] = "worker_pool_systemd_active_or_ambiguous"
        return evidence
    evidence["inactive"] = True
    evidence["reason"] = "worker_pool_systemd_inactive"
    return evidence


def dead_worker_ids_from_last_plan(
    project_root: Path,
    *,
    require_worker_pool_inactive: bool = True,
    plan_path: Path | None = None,
) -> list[str]:
    return sorted(
        dead_worker_reasons_from_last_plan(
            project_root,
            require_worker_pool_inactive=require_worker_pool_inactive,
            plan_path=plan_path,
        )
    )


def read_tail(path_value: Any, limit: int = 12000) -> str:
    if not path_value:
        return ""
    path = Path(str(path_value)).expanduser()
    if not path.exists() or not path.is_file():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")[-limit:]


def dead_launch_reason(launch: dict[str, Any], default_reason: str) -> str:
    text = f"{read_tail(launch.get('stdout_log'))}\n{read_tail(launch.get('stderr_log'))}".lower()
    if any(marker in text for marker in FINALIZE_SCOPE_FAILURE_MARKERS):
        return "worker_finalize_failed_outside_allowed_paths"
    return default_reason


def dead_worker_reasons_from_last_plan(
    project_root: Path,
    *,
    require_worker_pool_inactive: bool = True,
    default_reason: str = "worker model unavailable or interrupted before producing a worker result",
    plan_path: Path | None = None,
) -> dict[str, str]:
    last_plan = load_json(
        plan_path or task_file(project_root, "worker_pool_last_plan.json")
    )
    launches = last_plan.get("launches")
    if not isinstance(launches, list):
        return {}
    process_state = load_json(task_file(project_root, "agent_process_state.json"))
    if require_worker_pool_inactive and not worker_pool_is_inactive(process_state):
        return {}
    dead: dict[str, str] = {}
    for launch in launches:
        if not isinstance(launch, dict) or launch.get("started") is not True:
            continue
        worker_id = str(launch.get("worker_id") or "").strip()
        if not worker_id:
            continue
        if pid_is_alive(launch.get("pid")):
            continue
        dead[worker_id] = dead_launch_reason(launch, default_reason)
    return dead


def dead_task_reasons_from_last_plan(
    project_root: Path,
    *,
    require_worker_pool_inactive: bool = True,
    default_reason: str = "worker model unavailable or interrupted before producing a worker result",
    plan_path: Path | None = None,
) -> dict[str, str]:
    """Return only dead launches with an exact task identity.

    Exact task correlation makes this safe while sibling lanes are still alive.
    Older plans without planned_task_id remain eligible only through the
    worker-id fallback after the whole pool is proven inactive.
    """
    last_plan = load_json(
        plan_path or task_file(project_root, "worker_pool_last_plan.json")
    )
    launches = last_plan.get("launches")
    if not isinstance(launches, list):
        return {}
    process_state = load_json(task_file(project_root, "agent_process_state.json"))
    if require_worker_pool_inactive and not worker_pool_is_inactive(process_state):
        return {}
    dead: dict[str, str] = {}
    for launch in launches:
        if not isinstance(launch, dict) or launch.get("started") is not True:
            continue
        task_id_value = str(launch.get("planned_task_id") or launch.get("task_id") or "").strip()
        if not task_id_value or pid_is_alive(launch.get("pid")):
            continue
        dead[task_id_value] = dead_launch_reason(launch, default_reason)
    return dead


def live_launch_evidence_from_last_plan(
    project_root: Path,
    *,
    plan_path: Path | None = None,
) -> dict[str, Any]:
    last_plan = load_json(
        plan_path or task_file(project_root, "worker_pool_last_plan.json")
    )
    launches = last_plan.get("launches")
    live: list[dict[str, Any]] = []
    if isinstance(launches, list):
        for launch in launches:
            if not isinstance(launch, dict) or launch.get("started") is not True:
                continue
            pid = launch.get("pid")
            if not pid_is_alive(pid):
                continue
            live.append(
                {
                    "worker_id": str(launch.get("worker_id") or "").strip() or None,
                    "task_id": str(launch.get("planned_task_id") or launch.get("task_id") or "").strip() or None,
                    "pid": int(pid),
                    "source": "worker_pool_plan",
                }
            )
    known_pids = {item["pid"] for item in live}
    for cycle in running_worker_cycles(project_root):
        if cycle["pid"] in known_pids:
            continue
        live.append(cycle)
        known_pids.add(cycle["pid"])
    return {
        "live": bool(live),
        "live_count": len(live),
        "launches": live,
        "reason": "live_worker_launches" if live else "no_live_worker_launches",
    }


def running_worker_cycles(project_root: Path) -> list[dict[str, Any]]:
    """Discover exact live worker cycles when the persisted pool plan is stale."""
    try:
        proc = subprocess.run(
            ["pgrep", "-af", "run_worker_cycle.py"],
            text=True,
            capture_output=True,
            check=False,
        )
    except OSError:
        return []
    if proc.returncode not in (0, 1):
        return []

    current_pid = str(os.getpid())
    cycles: list[dict[str, Any]] = []
    for line in proc.stdout.splitlines():
        if not line.strip():
            continue
        pid_value, separator, command = line.partition(" ")
        if not separator or not pid_value.isdigit() or pid_value == current_pid:
            continue
        try:
            argv = shlex.split(command, posix=os.name != "nt")
        except ValueError:
            continue
        arguments: dict[str, str] = {}
        for index, value in enumerate(argv):
            if value.startswith("--") and "=" in value:
                key, parsed = value.split("=", 1)
                arguments[key] = parsed
            elif value.startswith("--") and index + 1 < len(argv):
                arguments[value] = argv[index + 1]
        process_root = arguments.get("--project-root")
        if not process_root:
            continue
        try:
            same_project = Path(process_root).resolve() == project_root.resolve()
        except OSError:
            same_project = False
        if not same_project:
            continue
        cycles.append(
            {
                "worker_id": arguments.get("--worker-id"),
                "task_id": arguments.get("--task-id"),
                "pid": int(pid_value),
                "source": "process_table",
            }
        )
    return cycles


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict, tuple, set)):
        return bool(value)
    return True


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task.get("current_context_verified_at")) and (
        has_value(task.get("current_context_verified_by"))
        or has_value(task.get("current_context_reviewed_by"))
    )


def reason_requires_dispatcher_repair(reason: str) -> bool:
    normalized = str(reason or "").lower().replace("-", "_")
    return (
        "worker_finalize_failed" in normalized
        or "worker finalize failed" in normalized
        or "outside_allowed" in normalized
        or "out_of_scope" in normalized
    )


def release_task(task: dict[str, Any], reason: str, now: str) -> dict[str, Any]:
    previous = {
        "released_at": now,
        "reason": reason,
        "status": task.get("status"),
        "worker_id": task.get("worker_id"),
        "machine_id": task.get("machine_id"),
        "branch": task.get("branch") or task.get("github_branch"),
        "claimed_at": task.get("claimed_at"),
        "lock_expires_at": task.get("lock_expires_at"),
    }
    abandoned = task.get("abandoned_claims")
    if not isinstance(abandoned, list):
        abandoned = []
    abandoned.append({k: v for k, v in previous.items() if v not in (None, "", [])})
    task["abandoned_claims"] = abandoned
    task["status"] = "planned"
    task["lock"] = "free"
    task["status_reason"] = reason
    task["claim_released_at"] = now
    if reason_requires_dispatcher_repair(reason):
        task["worker_ready"] = False
        task["dispatcher_decision"] = "needs_dispatcher_repair"
        task["packet_status"] = "needs_dispatcher_repair"
        task["normalization_status"] = "needs_dispatcher_repair"
        task["not_worker_ready_reason"] = reason
        task["repair_owner"] = "Dispatcher"
        task["repair_request"] = reason
        task["missing_packet_fields"] = ["allowed_paths"]
        task["next_action"] = "Dispatcher must widen or split the worker packet before another worker claim."
        task["next_owner"] = "dispatcher"
        task["next_role"] = "auto_dispatcher"
    elif task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        task["worker_ready"] = False
        task["dispatcher_decision"] = "needs_dispatcher_repair"
        task["not_worker_ready_reason"] = "current code/docs/task queue review required after dead worker claim release"
        task["next_owner"] = "dispatcher"
        task["next_role"] = "auto_dispatcher"
    else:
        task["worker_ready"] = True
        task["dispatcher_decision"] = task.get("dispatcher_decision") or "worker_ready"
    for field in (
        "worker_id",
        "machine_id",
        "branch",
        "github_branch",
        "claimed_at",
        "lock_expires_at",
        "synced_from_worker_branch",
    ):
        task.pop(field, None)
    return previous


def release_locks(locks: dict[str, Any], released_task_ids: set[str], reason: str, now: str) -> int:
    changed = 0
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return 0
    for lock in lock_list:
        if not isinstance(lock, dict) or str(lock.get("task_id") or "") not in released_task_ids:
            continue
        if lock.get("state") != "released":
            lock["state"] = "released"
            lock["released_at"] = now
            lock["notes"] = reason
            lock["released_by"] = "release_dead_worker_claims.py"
            lock["release_reason"] = reason
            changed += 1
    if changed:
        locks["updated_at"] = now
    return changed


def release_terminal_task_locks(queue: dict[str, Any], locks: dict[str, Any], reason: str, now: str, apply: bool) -> list[dict[str, Any]]:
    tasks = {
        task_id(task): task
        for task in queue.get("tasks", [])
        if isinstance(task, dict) and task_id(task)
    }
    released: list[dict[str, Any]] = []
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return released
    for lock in lock_list:
        if not isinstance(lock, dict):
            continue
        if str(lock.get("state") or "") not in ACTIVE_LOCK_STATES:
            continue
        current_task_id = str(lock.get("task_id") or "").strip()
        task = tasks.get(current_task_id)
        if not task:
            continue
        if str(task.get("status") or "") not in TERMINAL_STATUSES and str(task.get("lock") or "") != "free":
            continue
        released.append({
            "task_id": current_task_id,
            "status": task.get("status"),
            "task_lock": task.get("lock"),
            "lock_state": lock.get("state"),
            "branch": lock.get("branch"),
        })
        if apply:
            lock["state"] = "released"
            lock["released_at"] = now
            lock["notes"] = reason
            lock["released_by"] = "release_dead_worker_claims.py"
            lock["release_reason"] = reason
    if apply and released:
        locks["updated_at"] = now
    return released


def lock_state(value: Any) -> str:
    if isinstance(value, dict):
        return str(value.get("state") or "").strip()
    return str(value or "").strip()


def clear_task_lock(task: dict[str, Any], reason: str, now: str) -> None:
    lock = task.get("lock")
    if isinstance(lock, dict):
        previous = dict(lock)
        lock["state"] = "free"
        lock["by"] = None
        lock["expires_at"] = None
        lock["released_at"] = now
        lock["release_reason"] = reason
        if previous.get("state"):
            lock["previous_state"] = previous.get("state")
        task["lock"] = lock
    else:
        task["lock"] = "free"
    task.pop("lock_expires_at", None)


def close_terminal_task_queue_residue(queue: dict[str, Any], reason: str, now: str, apply: bool) -> list[dict[str, Any]]:
    closed: list[dict[str, Any]] = []
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        return closed
    for task in tasks:
        if not isinstance(task, dict):
            continue
        status = str(task.get("status") or "").strip()
        if status not in TERMINAL_QUEUE_STATUSES:
            continue
        current_task_id = task_id(task)
        integration_status = str(task.get("integration_status") or "").strip()
        current_lock_state = lock_state(task.get("lock"))
        should_close_integration = bool(integration_status and integration_status not in TERMINAL_INTEGRATION_STATUSES)
        should_clear_lock = current_lock_state in {"locked", "in_progress", "review"}
        if not should_close_integration and not should_clear_lock:
            continue
        closed.append({
            "task_id": current_task_id,
            "status": status,
            "integration_status": integration_status,
            "lock": current_lock_state,
            "closed_integration": should_close_integration,
            "cleared_lock": should_clear_lock,
        })
        if not apply:
            continue
        history = task.get("terminal_residue_history")
        if not isinstance(history, list):
            history = []
        history.append({
            "closed_at": now,
            "reason": reason,
            "previous_integration_status": integration_status or None,
            "previous_lock": current_lock_state or None,
        })
        task["terminal_residue_history"] = history
        task["terminal_residue_closed_at"] = now
        task["terminal_residue_closed_by"] = "release_dead_worker_claims.py"
        if should_close_integration:
            task["integration_status"] = "finalized"
            task.setdefault("finalized_at", now)
            task.setdefault("finalized_by", "automation-maintenance")
        if should_clear_lock:
            clear_task_lock(task, reason, now)
    if apply and closed:
        queue["updated_at"] = now
    return closed


def current_task_branches(queue: dict[str, Any] | None) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    if not isinstance(queue, dict):
        return result
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        current_task_id = task_id(task)
        if not current_task_id:
            continue
        branches: list[str] = []
        for field in ("synced_from_worker_branch", "github_branch", "branch"):
            branch = str(task.get(field) or "").strip()
            if branch and branch not in branches:
                branches.append(branch)
        if branches:
            result[current_task_id] = branches
    return result


def preferred_active_lock_index(active: list[tuple[int, dict[str, Any]]], branches: list[str]) -> int:
    for branch in branches:
        matches = [
            (index, lock)
            for index, lock in active
            if str(lock.get("branch") or "").strip() == branch
        ]
        if matches:
            return max(
                matches,
                key=lambda item: (
                    str(item[1].get("at") or ""),
                    str(item[1].get("review_at") or ""),
                    str(item[1].get("expires_at") or ""),
                    item[0],
                ),
            )[0]
    return max(
        active,
        key=lambda item: (
            str(item[1].get("at") or ""),
            str(item[1].get("review_at") or ""),
            str(item[1].get("expires_at") or ""),
            item[0],
        ),
    )[0]


def release_duplicate_active_locks(
    locks: dict[str, Any],
    reason: str,
    now: str,
    apply: bool,
    queue: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    released: list[dict[str, Any]] = []
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return released
    active_by_task: dict[str, list[tuple[int, dict[str, Any]]]] = {}
    for index, lock in enumerate(lock_list):
        if isinstance(lock, dict) and str(lock.get("state") or "") in ACTIVE_LOCK_STATES:
            current_task_id = str(lock.get("task_id") or "").strip()
            if current_task_id:
                active_by_task.setdefault(current_task_id, []).append((index, lock))
    task_branches = current_task_branches(queue)
    for current_task_id, active in active_by_task.items():
        if len(active) < 2:
            continue
        keep_index = preferred_active_lock_index(active, task_branches.get(current_task_id, []))
        for index, lock in active:
            if index == keep_index:
                continue
            released.append({
                "task_id": current_task_id,
                "lock_state": lock.get("state"),
                "branch": lock.get("branch"),
                "worker_id": lock.get("by") or lock.get("owner") or lock.get("worker_id"),
            })
            if apply:
                lock["state"] = "released"
                lock["released_at"] = now
                lock["notes"] = reason
                lock["released_by"] = "release_dead_worker_claims.py"
                lock["release_reason"] = reason
    if apply and released:
        locks["updated_at"] = now
    return released


def close_recorded_in_progress_claims(
    queue: dict[str, Any],
    locks: dict[str, Any],
    recorded_task_ids: set[str],
    reason: str,
    now: str,
    apply: bool,
) -> list[dict[str, Any]]:
    closed: list[dict[str, Any]] = []
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        return closed
    closed_task_ids: set[str] = set()
    for task in tasks:
        if not isinstance(task, dict):
            continue
        current_task_id = task_id(task)
        if not current_task_id:
            continue
        if current_task_id not in recorded_task_ids and f"task:{current_task_id}" not in recorded_task_ids:
            continue
        if (
            str(task.get("integration_status") or "") == "needs_source_pr_close"
            and str(task.get("source_pr_close_status") or "") != "closed"
        ):
            continue
        current_status = str(task.get("status") or "").strip()
        current_lock = lock_state(task.get("lock"))
        if current_status not in {"in_progress", "review", "integration_requested", "agent_done"} and current_lock not in ACTIVE_LOCK_STATES:
            continue
        closed.append({
            "task_id": current_task_id,
            "status": current_status,
            "lock": current_lock,
            "worker_id": task.get("worker_id"),
            "branch": task.get("branch") or task.get("github_branch"),
        })
        closed_task_ids.add(current_task_id)
        if not apply:
            continue
        history = task.get("recorded_claim_close_history")
        if not isinstance(history, list):
            history = []
        history.append({
            "closed_at": now,
            "reason": reason,
            "previous_status": current_status or None,
            "previous_lock": current_lock or None,
            "previous_worker_id": task.get("worker_id"),
            "previous_branch": task.get("branch") or task.get("github_branch"),
        })
        task["recorded_claim_close_history"] = history
        task["status"] = "stale_or_superseded"
        task["dispatcher_decision"] = "stale_or_superseded"
        task["integration_status"] = "already_integrated"
        task["worker_ready"] = False
        task["status_reason"] = reason
        task["closed_duplicate_claim_at"] = now
        task["closed_duplicate_claim_by"] = "release_dead_worker_claims.py"
        clear_task_lock(task, reason, now)
        for field in (
            "worker_id",
            "machine_id",
            "branch",
            "github_branch",
            "claimed_at",
            "lock_expires_at",
            "synced_from_worker_branch",
        ):
            task.pop(field, None)
    if apply and closed_task_ids:
        lock_list = locks.get("locks")
        if isinstance(lock_list, list):
            for lock in lock_list:
                if not isinstance(lock, dict):
                    continue
                if str(lock.get("task_id") or "").strip() not in closed_task_ids:
                    continue
                if str(lock.get("state") or "") not in ACTIVE_LOCK_STATES:
                    continue
                lock["state"] = "released"
                lock["released_at"] = now
                lock["released_by"] = "release_dead_worker_claims.py"
                lock["release_reason"] = reason
                lock["notes"] = reason
            locks["updated_at"] = now
        queue["updated_at"] = now
    return closed


def close_blocked_model_limit_claims(queue: dict[str, Any], locks: dict[str, Any], reason: str, now: str, apply: bool) -> list[dict[str, Any]]:
    closed: list[dict[str, Any]] = []
    tasks = queue.get("tasks")
    if not isinstance(tasks, list):
        return closed
    blocked_ids: set[str] = set()
    for task in tasks:
        if not isinstance(task, dict):
            continue
        current_task_id = task_id(task)
        if not current_task_id:
            continue
        if str(task.get("integration_status") or "") != "blocked_model_limit":
            continue
        if str(task.get("dispatcher_decision") or "") != "blocked_by_missing_environment":
            continue
        current_status = str(task.get("status") or "")
        current_lock = lock_state(task.get("lock"))
        if current_status not in {"in_progress", "claimed", "running"} and current_lock not in ACTIVE_LOCK_STATES:
            continue
        closed.append({
            "task_id": current_task_id,
            "status": current_status,
            "lock": current_lock,
            "worker_id": task.get("worker_id"),
            "branch": task.get("branch") or task.get("github_branch"),
        })
        blocked_ids.add(current_task_id)
        if not apply:
            continue
        history = task.get("blocked_model_limit_claim_history")
        if not isinstance(history, list):
            history = []
        history.append({
            "closed_at": now,
            "reason": reason,
            "previous_status": current_status or None,
            "previous_lock": current_lock or None,
            "previous_worker_id": task.get("worker_id"),
            "previous_branch": task.get("branch") or task.get("github_branch"),
        })
        task["blocked_model_limit_claim_history"] = history
        task["status"] = "blocked"
        task["worker_ready"] = False
        task["next_owner"] = "Dispatcher"
        task["next_action"] = "Retry when the requested worker model or model budget is available."
        task["status_reason"] = task.get("status_reason") or reason
        task["blocked_reason"] = task.get("blocked_reason") or task["status_reason"]
        clear_task_lock(task, reason, now)
    if apply and blocked_ids:
        lock_list = locks.get("locks")
        if isinstance(lock_list, list):
            for lock in lock_list:
                if not isinstance(lock, dict):
                    continue
                if str(lock.get("task_id") or "").strip() not in blocked_ids:
                    continue
                if str(lock.get("state") or "") not in ACTIVE_LOCK_STATES:
                    continue
                lock["state"] = "released"
                lock["released_at"] = now
                lock["released_by"] = "release_dead_worker_claims.py"
                lock["release_reason"] = reason
                lock["notes"] = reason
            locks["updated_at"] = now
        queue["updated_at"] = now
    return closed


def active_lock_worker_ids(locks: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return result
    for lock in lock_list:
        if not isinstance(lock, dict):
            continue
        if str(lock.get("state") or "") not in ACTIVE_LOCK_STATES:
            continue
        current_task_id = str(lock.get("task_id") or "").strip()
        worker_id = str(lock.get("by") or lock.get("worker_id") or lock.get("owner") or "").strip()
        if current_task_id and worker_id and current_task_id not in result:
            result[current_task_id] = worker_id
    return result


def dead_worker_reasons_from_active_locks(
    project_root: Path,
    locks: dict[str, Any],
    *,
    require_worker_pool_inactive: bool = True,
    default_reason: str = "worker pool finished without a live worker process or worker result",
) -> dict[str, str]:
    if require_worker_pool_inactive:
        process_state = load_json(task_file(project_root, "agent_process_state.json"))
        if not worker_pool_is_inactive(process_state):
            return {}
    return {worker_id: default_reason for worker_id in active_lock_worker_ids(locks).values()}


def active_lock_claim_times(locks: dict[str, Any]) -> dict[str, datetime]:
    result: dict[str, datetime] = {}
    lock_list = locks.get("locks")
    if not isinstance(lock_list, list):
        return result
    for lock in lock_list:
        if not isinstance(lock, dict):
            continue
        if str(lock.get("state") or "") not in ACTIVE_LOCK_STATES:
            continue
        current_task_id = str(lock.get("task_id") or "").strip()
        claimed_at = parse_time(lock.get("at") or lock.get("claimed_at") or lock.get("started_at"))
        if current_task_id and claimed_at and current_task_id not in result:
            result[current_task_id] = claimed_at
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Release dead worker claims without result evidence.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--worker-id", action="append", default=[])
    parser.add_argument("--from-worker-pool-last-plan", action="store_true", help="Release claims for worker launches from worker_pool_last_plan.json whose pids are no longer alive.")
    parser.add_argument("--worker-pool-plan", help="Explicit worker-pool plan path, including runtime-only async plans.")
    parser.add_argument("--allow-active-worker-pool", action="store_true", help="Do not require agent_process_state worker_pool to be inactive when using --from-worker-pool-last-plan.")
    parser.add_argument("--worker-pool-systemd-unit", help="Configured worker-pool unit used to prove active-lock fallback is safe when process state is stale.")
    parser.add_argument("--worker-pool-systemd-scope", choices=("user", "system"), default="user")
    parser.add_argument("--reason", default="worker model unavailable or interrupted before producing a worker result")
    parser.add_argument("--release-terminal-task-locks", action="store_true", help="Also release active lock rows for terminal/free task rows without changing task status.")
    parser.add_argument("--close-terminal-task-residue", action="store_true", help="Close stale integration_status/review lock fields on terminal task rows.")
    parser.add_argument("--release-duplicate-active-locks", action="store_true", help="Release duplicate active/review lock rows, keeping the first active row for each task.")
    parser.add_argument("--close-recorded-in-progress-claims", action="store_true", help="Close active task claims whose task id already has integration/finalization recorded events.")
    parser.add_argument("--close-blocked-model-limit-claims", action="store_true", help="Close active task claims already routed to blocked_model_limit environment handling.")
    parser.add_argument("--recover-runtime-failure-task-id", help="Invalidate one proven false finalization caused by a blocked worker runtime result.")
    parser.add_argument("--worker-report", help="Repository-relative blocked Worker report required by runtime-failure recovery.")
    parser.add_argument("--expected-merge-commit", help="Exact false terminal merge commit required by runtime-failure recovery.")
    parser.add_argument("--queue")
    parser.add_argument("--locks")
    parser.add_argument("--events")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")
    events_path = Path(args.events).resolve() if args.events else task_file(project_root, "agent_events.jsonl")
    queue = load_json(queue_path)
    locks = load_json(locks_path)
    events = read_jsonl(events_path)
    now = utc_now()
    if args.recover_runtime_failure_task_id:
        if not args.worker_report or not args.expected_merge_commit:
            report = {
                "eligible": False,
                "reason": "runtime_failure_recovery_requires_worker_report_and_expected_merge_commit",
                "task_id": args.recover_runtime_failure_task_id,
            }
            print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else report["reason"])
            return 2
        recovery = recover_runtime_failure_finalization(
            project_root,
            queue,
            locks,
            events,
            task_id_value=args.recover_runtime_failure_task_id,
            worker_report=args.worker_report,
            expected_merge_commit=args.expected_merge_commit,
            reason=args.reason,
            now=now,
            apply=bool(args.apply),
        )
        if args.apply and recovery.get("eligible"):
            if recovery.get("queue_changed"):
                write_json(queue_path, queue)
            if recovery.get("expired_lock_release_count"):
                write_json(locks_path, locks)
            new_events = recovery.get("events_to_append")
            append_jsonl(events_path, new_events if isinstance(new_events, list) else [])
            if recovery.get("queue_changed") or recovery.get("expired_lock_release_count") or recovery.get("event_append_count"):
                append_log(
                    project_root,
                    "worker-claims",
                    "runtime_failure_finalization_recovered",
                    severity="warning",
                    task_id=args.recover_runtime_failure_task_id,
                    false_merge_commit=args.expected_merge_commit,
                    worker_report=args.worker_report,
                    queue_changed=bool(recovery.get("queue_changed")),
                    released_lock_count=int(recovery.get("expired_lock_release_count") or 0),
                    appended_event_count=int(recovery.get("event_append_count") or 0),
                )
        print(json.dumps(recovery, ensure_ascii=False, indent=2) if args.json else str(recovery.get("reason") or recovery.get("task_id") or "runtime failure recovery"))
        return 0 if recovery.get("eligible") else 2
    worker_ids = set(args.worker_id or [])
    worker_reasons = {worker_id: args.reason for worker_id in worker_ids}
    task_reasons: dict[str, str] = {}
    worker_pool_systemd_evidence: dict[str, Any] | None = None
    live_launch_evidence: dict[str, Any] | None = None
    worker_pool_plan = Path(args.worker_pool_plan).expanduser().resolve() if args.worker_pool_plan else None
    if args.from_worker_pool_last_plan:
        task_reasons.update(dead_task_reasons_from_last_plan(
            project_root,
            require_worker_pool_inactive=not args.allow_active_worker_pool,
            default_reason=args.reason,
            plan_path=worker_pool_plan,
        ))
        if not args.allow_active_worker_pool:
            plan_reasons = dead_worker_reasons_from_last_plan(
                project_root,
                require_worker_pool_inactive=True,
                default_reason=args.reason,
                plan_path=worker_pool_plan,
            )
            worker_ids.update(plan_reasons)
            worker_reasons.update(plan_reasons)
        if not worker_ids and not task_reasons:
            live_launch_evidence = live_launch_evidence_from_last_plan(
                project_root,
                plan_path=worker_pool_plan,
            )
            if args.worker_pool_systemd_unit:
                worker_pool_systemd_evidence = worker_pool_systemd_inactivity_evidence(
                    args.worker_pool_systemd_unit,
                    args.worker_pool_systemd_scope,
                )
                lock_reasons = (
                    dead_worker_reasons_from_active_locks(
                        project_root,
                        locks,
                        require_worker_pool_inactive=False,
                        default_reason=args.reason,
                    )
                    if worker_pool_systemd_evidence.get("inactive")
                    and live_launch_evidence.get("live") is not True
                    else {}
                )
            else:
                lock_reasons = (
                    dead_worker_reasons_from_active_locks(
                        project_root,
                        locks,
                        require_worker_pool_inactive=not args.allow_active_worker_pool,
                        default_reason=args.reason,
                    )
                    if live_launch_evidence.get("live") is not True
                    else {}
                )
            worker_ids.update(lock_reasons)
            worker_reasons.update(lock_reasons)
    terminal_queue_residue = close_terminal_task_queue_residue(
        queue,
        "terminal task queue residue cleanup",
        now,
        args.apply,
    ) if args.close_terminal_task_residue else []
    terminal_lock_releases = release_terminal_task_locks(
        queue,
        locks,
        "terminal/free task row lock cleanup",
        now,
        args.apply,
    ) if args.release_terminal_task_locks else []
    duplicate_lock_releases = release_duplicate_active_locks(
        locks,
        "duplicate active task lock cleanup",
        now,
        args.apply,
        queue,
    ) if args.release_duplicate_active_locks else []
    recorded_claim_closes = close_recorded_in_progress_claims(
        queue,
        locks,
        integration_recorded_task_ids(events),
        "task already has integration/finalization recorded; closing duplicate active claim",
        now,
        args.apply,
    ) if args.close_recorded_in_progress_claims else []
    blocked_model_limit_closes = close_blocked_model_limit_claims(
        queue,
        locks,
        "model limit claim already routed to environment handling",
        now,
        args.apply,
    ) if args.close_blocked_model_limit_claims else []
    if not worker_ids and not task_reasons:
        changed = bool(terminal_queue_residue or terminal_lock_releases or duplicate_lock_releases or recorded_claim_closes or blocked_model_limit_closes)
        if args.apply and changed:
            write_json(queue_path, queue)
            write_json(locks_path, locks)
            append_log(
                project_root,
                "worker-claims",
                "dead_claims_released",
                severity="warning",
                released_count=0,
                terminal_lock_release_count=len(terminal_lock_releases),
                duplicate_lock_release_count=len(duplicate_lock_releases),
                terminal_queue_residue_count=len(terminal_queue_residue),
                recorded_claim_close_count=len(recorded_claim_closes),
                blocked_model_limit_close_count=len(blocked_model_limit_closes),
                worker_ids=[],
            )
        report = {
            "schema_version": 1,
            "checked_at": now,
            "project_root": str(project_root),
            "apply": bool(args.apply),
            "released_count": 0,
            "lock_change_count": (len(terminal_lock_releases) + len(duplicate_lock_releases)) if args.apply else 0,
            "released": [],
            "terminal_lock_release_count": len(terminal_lock_releases),
            "terminal_lock_releases": terminal_lock_releases,
            "terminal_queue_residue_count": len(terminal_queue_residue),
            "terminal_queue_residue": terminal_queue_residue,
            "duplicate_lock_release_count": len(duplicate_lock_releases),
            "duplicate_lock_releases": duplicate_lock_releases,
            "recorded_claim_close_count": len(recorded_claim_closes),
            "recorded_claim_closes": recorded_claim_closes,
            "blocked_model_limit_close_count": len(blocked_model_limit_closes),
            "blocked_model_limit_closes": blocked_model_limit_closes,
            "worker_pool_systemd_evidence": worker_pool_systemd_evidence,
            "live_launch_evidence": live_launch_evidence,
            "worker_ids": [],
            "task_ids": [],
            "reason": "no_worker_ids",
        }
        print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else "released: 0")
        return 0
    released: list[dict[str, Any]] = []
    lock_worker_ids = active_lock_worker_ids(locks)
    lock_claim_times = active_lock_claim_times(locks)

    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        current_task_id = task_id(task)
        worker_id = str(task.get("worker_id") or lock_worker_ids.get(current_task_id) or "").strip()
        reason = task_reasons.get(current_task_id) or worker_reasons.get(worker_id)
        if task.get("status") != "in_progress" or not reason:
            continue
        claim_time = parse_time(task.get("claimed_at")) or lock_claim_times.get(current_task_id)
        if has_current_result_evidence(task, claim_time):
            continue
        if args.apply:
            if not task.get("worker_id") and worker_id:
                task["worker_id"] = worker_id
            previous = release_task(task, reason, now)
        else:
            previous = {
                "status": task.get("status"),
                "worker_id": worker_id,
                "branch": task.get("branch") or task.get("github_branch"),
            }
            previous["reason"] = reason
        released.append({"task_id": current_task_id, **{k: v for k, v in previous.items() if v not in (None, "", [])}})

    lock_changes = release_locks(locks, {item["task_id"] for item in released}, args.reason, now) if args.apply else 0
    if args.apply and (released or terminal_queue_residue or terminal_lock_releases or duplicate_lock_releases or recorded_claim_closes or blocked_model_limit_closes):
        queue["updated_at"] = now
        write_json(queue_path, queue)
        write_json(locks_path, locks)
        append_log(
            project_root,
            "worker-claims",
            "dead_claims_released",
            severity="warning",
            released_count=len(released),
            terminal_lock_release_count=len(terminal_lock_releases),
            terminal_queue_residue_count=len(terminal_queue_residue),
            duplicate_lock_release_count=len(duplicate_lock_releases),
            recorded_claim_close_count=len(recorded_claim_closes),
            blocked_model_limit_close_count=len(blocked_model_limit_closes),
            worker_ids=sorted(worker_ids),
        )

    report = {
        "schema_version": 1,
        "checked_at": now,
        "project_root": str(project_root),
        "apply": bool(args.apply),
        "released_count": len(released),
        "lock_change_count": lock_changes
        + (len(terminal_lock_releases) if args.apply else 0)
        + (len(duplicate_lock_releases) if args.apply else 0),
        "released": released,
        "terminal_lock_release_count": len(terminal_lock_releases),
        "terminal_lock_releases": terminal_lock_releases,
        "terminal_queue_residue_count": len(terminal_queue_residue),
        "terminal_queue_residue": terminal_queue_residue,
        "duplicate_lock_release_count": len(duplicate_lock_releases),
        "duplicate_lock_releases": duplicate_lock_releases,
        "recorded_claim_close_count": len(recorded_claim_closes),
        "recorded_claim_closes": recorded_claim_closes,
        "blocked_model_limit_close_count": len(blocked_model_limit_closes),
        "blocked_model_limit_closes": blocked_model_limit_closes,
        "worker_pool_systemd_evidence": worker_pool_systemd_evidence,
        "live_launch_evidence": live_launch_evidence,
        "task_ids": sorted(task_reasons),
        "worker_ids": sorted(worker_ids),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else f"released: {len(released)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
