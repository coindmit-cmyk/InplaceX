#!/usr/bin/env python3
"""Merge a verified Auto Integrator package into the integration branch."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_manager_dir
from task_control_postgres import TaskControlConfigurationError, TaskControlPostgres

from evaluate_finalizer_merge_gate import evaluate_gate, run_validate


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def run_git(project_root: Path, args: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=str(cwd or project_root),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def git_text(project_root: Path, args: list[str], cwd: Path | None = None) -> str:
    proc = run_git(project_root, args, cwd=cwd)
    if proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout.strip()


def resolve_ref(project_root: Path, ref: str) -> str | None:
    proc = run_git(project_root, ["rev-parse", "--verify", ref])
    if proc.returncode == 0:
        return proc.stdout.strip()
    return None


def package_ref(
    project_root: Path,
    branch: str,
    remote: str,
    *,
    require_remote: bool = False,
) -> str:
    remote_prefix = f"{remote}/"
    full_remote_prefix = f"refs/remotes/{remote}/"
    normalized = branch
    if normalized.startswith(full_remote_prefix):
        normalized = normalized.removeprefix(full_remote_prefix)
    elif normalized.startswith(remote_prefix):
        normalized = normalized.removeprefix(remote_prefix)
    candidates = [f"{remote}/{normalized}", f"refs/remotes/{remote}/{normalized}"]
    if not require_remote:
        candidates.append(branch)
    for candidate in candidates:
        if resolve_ref(project_root, candidate):
            return candidate
    raise RuntimeError(f"package branch not found: {branch}")


def clean_worktree_path(project_root: Path, worktree: Path) -> None:
    if not worktree.exists():
        return
    run_git(project_root, ["worktree", "remove", "--force", str(worktree)])
    if worktree.exists():
        shutil.rmtree(worktree)
    run_git(project_root, ["worktree", "prune"])


def write_report(worktree: Path, report: dict[str, Any]) -> Path:
    report_dir = worktree / "docs" / "plans" / "reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    path = report_dir / f"FINALIZER_{utc_stamp()}.md"
    lines = [
        "# Auto Finalizer Report",
        "",
        f"- Decision: `{report.get('decision')}`",
        f"- Target branch: `{report.get('target_branch')}`",
        f"- Package branch: `{report.get('package_branch')}`",
        f"- Ready items: `{len(report.get('ready_to_finalize') or [])}`",
        f"- Pushed: `{bool(report.get('pushed'))}`",
    ]
    if report.get("target_sha_before"):
        lines.append(f"- Target SHA before: `{report.get('target_sha_before')}`")
    if report.get("target_sha_after"):
        lines.append(f"- Target SHA after: `{report.get('target_sha_after')}`")
    if report.get("issues"):
        lines.extend(["", "## Issues"])
        for item in report.get("issues") or []:
            lines.append(f"- `{item.get('task')}`: {item.get('reason')}")
    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    return path


def append_event(path: Path, event: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False) + "\n")


def build_event(project: str, task_id: str, package_branch: str, merge_commit: str) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    safe_task = "".join(ch if ch.isalnum() else "-" for ch in task_id.lower()).strip("-") or "task"
    return {
        "schema_version": 1,
        "event_id": f"finalization_recorded-{utc_stamp()}-{safe_task}",
        "created_at": now,
        "project": project,
        "event": "finalization_recorded",
        "role": "auto-finalizer",
        "task_id": task_id,
        "canonical_target_id": f"task:{task_id}" if task_id and not task_id.startswith("SRC-") else task_id,
        "severity": "info",
        "reason": "auto finalizer merged verified integration package",
        "payload": {
            "package_branch": package_branch,
            "merge_commit": merge_commit,
            "mode": "package_to_target",
        },
    }


def event_target_keys(event: dict[str, Any]) -> set[str]:
    keys: set[str] = set()
    for field in ("task_id", "canonical_target_id"):
        value = str(event.get(field) or "").strip()
        if not value:
            continue
        keys.add(value)
        keys.add(value.removeprefix("task:") if value.startswith("task:") else f"task:{value}")
    return keys


def finalization_recorded_task_ids_from_text(text: str, *, strict: bool = False) -> set[str]:
    recorded: set[str] = set()
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            if strict:
                raise ValueError(f"malformed event ledger JSON at line {line_number}: {exc.msg}") from exc
            continue
        if not isinstance(event, dict):
            if strict:
                raise ValueError(f"event ledger entry at line {line_number} is not an object")
            continue
        event_name = str(event.get("event") or "")
        targets = event_target_keys(event)
        if event_name in {"integration_invalidated", "finalization_invalidated"}:
            recorded.difference_update(targets)
        elif event_name == "finalization_recorded":
            recorded.update(targets)
    return recorded


def finalization_recorded_task_ids(events_path: Path) -> set[str]:
    if not events_path.exists():
        return set()
    return finalization_recorded_task_ids_from_text(
        events_path.read_text(encoding="utf-8", errors="ignore")
    )


def finalization_recorded_task_ids_at_ref(
    project_root: Path,
    target_sha: str,
) -> tuple[set[str], str | None]:
    try:
        events_rel = (
            task_manager_dir(project_root) / "agent_events.jsonl"
        ).relative_to(project_root).as_posix()
    except ValueError:
        return set(), "task manager path is outside the project root"

    target_commit = run_git(project_root, ["cat-file", "-e", f"{target_sha}^{{commit}}"])
    if target_commit.returncode != 0:
        return set(), f"target SHA is not a readable commit: {target_sha}"

    events_tree = run_git(project_root, ["ls-tree", "--name-only", target_sha, "--", events_rel])
    if events_tree.returncode != 0:
        detail = events_tree.stderr.strip() or events_tree.stdout.strip() or "git ls-tree failed"
        return set(), f"failed to inspect finalization events at {target_sha}: {detail}"
    if events_rel not in {line.strip() for line in events_tree.stdout.splitlines()}:
        return set(), None

    events = run_git(project_root, ["show", f"{target_sha}:{events_rel}"])
    if events.returncode != 0:
        detail = events.stderr.strip() or events.stdout.strip() or "git show failed"
        return set(), f"failed to read finalization events from {target_sha}: {detail}"
    try:
        return finalization_recorded_task_ids_from_text(events.stdout, strict=True), None
    except ValueError as exc:
        return set(), f"invalid finalization event ledger at {target_sha}: {exc}"


def filter_recorded_ready_items(data: dict[str, Any], recorded: set[str]) -> tuple[dict[str, Any], list[str]]:
    ready = data.get("ready_to_finalize")
    if not isinstance(ready, list):
        return data, []
    active: list[Any] = []
    skipped: list[str] = []
    for item in ready:
        value = str(item or "").strip()
        if not value:
            continue
        keys = {value, value.removeprefix("task:") if value.startswith("task:") else f"task:{value}"}
        if keys & recorded:
            skipped.append(value)
            continue
        active.append(item)
    if not skipped:
        return data, []
    updated = dict(data)
    updated["ready_to_finalize"] = active
    return updated, skipped


def update_task_queue(plans: Path, ready_task_ids: list[str], merge_commit: str) -> bool:
    if not ready_task_ids:
        return False
    queue_path = plans / "task_queue.json"
    if not queue_path.exists():
        return False
    data = json.loads(queue_path.read_text(encoding="utf-8"))
    tasks = data.get("tasks") if isinstance(data, dict) else None
    if not isinstance(tasks, list):
        return False
    ready = set(str(task_id) for task_id in ready_task_ids if task_id)
    now = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    changed = False
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "")
        if task_id not in ready:
            continue
        before = {
            "status": task.get("status"),
            "integration_status": task.get("integration_status"),
            "finalization_status": task.get("finalization_status"),
        }
        task["status"] = "done"
        task["integration_status"] = "finalized"
        task["finalization_status"] = "done_recorded"
        task["dispatcher_decision"] = "done"
        task["packet_status"] = "done"
        task["normalization_status"] = "finalized"
        task["worker_ready"] = False
        task["lock"] = "free"
        task["next_owner"] = "none"
        task["next_role"] = "none"
        task["not_worker_ready_reason"] = "terminal status=done"
        task["finalized_at"] = now
        task["finalized_by"] = "auto-finalizer"
        task["finalizer_merge_commit"] = merge_commit
        history = task.get("status_history")
        if not isinstance(history, list):
            history = []
        history.append({
            "at": now,
            "by": "auto_finalizer_merge",
            "from": before,
            "to": {
                "status": task.get("status"),
                "integration_status": task.get("integration_status"),
                "finalization_status": task.get("finalization_status"),
            },
            "event": "finalization_recorded",
            "reason": "verified integration package merged to target branch",
        })
        task["status_history"] = history
        changed = True
    if changed:
        queue_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return changed


def release_task_locks(plans: Path, ready_task_ids: list[str]) -> tuple[bool, list[dict[str, str]]]:
    if not ready_task_ids:
        return False, []
    ready = {str(task_id) for task_id in ready_task_ids if task_id}
    queue_path = plans / "task_queue.json"
    try:
        queue_data = json.loads(queue_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return False, [{"task": "task_queue", "reason": f"cannot read task queue for lock ownership: {exc}"}]
    tasks = queue_data.get("tasks") if isinstance(queue_data, dict) else None
    if not isinstance(tasks, list):
        return False, [{"task": "task_queue", "reason": "task queue does not contain a tasks list"}]
    task_by_id = {
        str(task.get("id") or task.get("task_id") or ""): task
        for task in tasks
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "") in ready
    }
    missing_tasks = sorted(ready - set(task_by_id))
    if missing_tasks:
        return False, [
            {"task": task_id, "reason": "ready task is missing from the canonical queue"}
            for task_id in missing_tasks
        ]

    locks_path = plans / "agent_locks.json"
    if not locks_path.exists():
        active_queue_locks = [
            task_id
            for task_id, task in task_by_id.items()
            if str(task.get("lock") or "") not in {"", "free", "released"}
        ]
        if active_queue_locks:
            return False, [
                {"task": task_id, "reason": "queue declares an active lock but agent_locks.json is missing"}
                for task_id in active_queue_locks
            ]
        return False, []
    try:
        data = json.loads(locks_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return False, [{"task": "agent_locks", "reason": f"cannot read lock ledger: {exc}"}]
    locks = data.get("locks") if isinstance(data, dict) else None
    if not isinstance(locks, list):
        return False, [{"task": "agent_locks", "reason": "lock ledger does not contain a locks list"}]

    errors: list[dict[str, str]] = []
    releasable: list[dict[str, Any]] = []
    for task_id in sorted(ready):
        task = task_by_id.get(task_id)
        if task is None:
            continue
        active_locks = [
            lock
            for lock in locks
            if isinstance(lock, dict)
            and str(lock.get("task_id") or "") == task_id
            and str(lock.get("state") or "") != "released"
        ]
        released_locks = [
            lock
            for lock in locks
            if isinstance(lock, dict)
            and str(lock.get("task_id") or "") == task_id
            and str(lock.get("state") or "") == "released"
        ]
        queue_lock_state = str(task.get("lock") or "")
        if not active_locks:
            if released_locks and queue_lock_state in {"", "free", "released"}:
                continue
            if queue_lock_state not in {"", "free", "released"}:
                errors.append({
                    "task": task_id,
                    "reason": f"queue expects lock state {queue_lock_state!r}, but no active lock exists",
                })
            continue
        if len(active_locks) != 1:
            errors.append({"task": task_id, "reason": "multiple active lock records prevent exact release"})
            continue
        lock = active_locks[0]
        if queue_lock_state != "review" or str(lock.get("state") or "") != "review":
            errors.append({
                "task": task_id,
                "reason": (
                    "finalizer may release only the exact review lease "
                    f"(queue={queue_lock_state!r}, ledger={lock.get('state')!r})"
                ),
            })
            continue
        comparisons = (
            ("by", "worker_id"),
            ("machine_id", "machine_id"),
            ("branch", "branch"),
            ("expires_at", "lock_expires_at"),
        )
        mismatch = next(
            (
                f"{lock_field}={lock.get(lock_field)!r} does not match "
                f"{task_field}={task.get(task_field)!r}"
                for lock_field, task_field in comparisons
                if str(lock.get(lock_field) or "") != str(task.get(task_field) or "")
            ),
            None,
        )
        if mismatch:
            errors.append({"task": task_id, "reason": f"lock ownership mismatch: {mismatch}"})
            continue
        releasable.append(lock)
    if errors:
        return False, errors

    now = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    for lock in releasable:
        lock["previous_state"] = lock.get("state")
        lock["state"] = "released"
        lock["released_at"] = now
        lock["released_by"] = "auto-finalizer"
        lock["release_reason"] = "verified integration package finalized"
    changed = bool(releasable)
    if changed:
        data["updated_at"] = now
        locks_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return changed, []


def record_finalizer_state_commit(
    *,
    project_root: Path,
    worktree: Path,
    data: dict[str, Any],
    report: dict[str, Any],
    remote: str,
    target_branch: str,
    expected_head: str,
) -> tuple[str | None, list[dict[str, str]]]:
    plans = task_manager_dir(worktree)
    ready = [str(task_id) for task_id in data.get("ready_to_finalize") or [] if task_id]
    _, lock_errors = release_task_locks(plans, ready)
    if lock_errors:
        return None, lock_errors
    events_path = plans / "agent_events.jsonl"
    for task_id in ready:
        append_event(events_path, build_event(project_root.name, task_id, str(data.get("package_branch") or ""), expected_head))
    update_task_queue(plans, ready, expected_head)
    report_path = plans / "auto_finalizer_merge.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY", "").strip() == "postgres":
        # The enclosing managed session commits queue/history to PostgreSQL
        # after this child returns. Product integration remains a Git push,
        # but a second Git Task Manager state commit is neither needed nor safe.
        return None, []

    state_paths = [
        "AiStudio/Task_manager/agent_events.jsonl",
        "AiStudio/Task_manager/agent_locks.json",
        "AiStudio/Task_manager/task_queue.json",
        "AiStudio/Task_manager/auto_finalizer_merge.json",
    ]
    existing_state_paths = [path for path in state_paths if (worktree / path).exists()]
    if not existing_state_paths:
        return None, []
    run_git(project_root, ["add", "-A", "--", *existing_state_paths], cwd=worktree)
    status = git_text(project_root, ["status", "--porcelain", "--", *existing_state_paths], cwd=worktree)
    if not status:
        return None, []
    git_text(project_root, ["commit", "-m", "chore(finalizer): record finalization state"], cwd=worktree)
    state_sha = git_text(project_root, ["rev-parse", "HEAD"], cwd=worktree)
    return state_sha, []


def record_sql_finalizer_candidates(
    *,
    data: dict[str, Any],
    project_id: str,
    target_branch: str,
    target_sha_before: str,
    integration_sha: str,
    state: str,
) -> list[dict[str, Any]]:
    dsn_env = os.environ.get("AISTUDIO_TASK_DB_DSN_ENV", "").strip()
    dsn = os.environ.get(dsn_env, "") if dsn_env else ""
    if not project_id or not dsn:
        raise TaskControlConfigurationError(
            "SQL finalizer requires project identity and configured DSN"
        )
    package_branch = str(data.get("package_branch") or "").strip()
    session_id = os.environ.get("AISTUDIO_TASK_CONTROL_SESSION_ID", "").strip()
    database = TaskControlPostgres(dsn)
    rows: list[dict[str, Any]] = []
    for raw_task_id in data.get("ready_to_finalize") or []:
        task_id = str(raw_task_id or "").strip()
        if not task_id:
            continue
        identity = f"{project_id}\n{package_branch}\n{task_id}".encode("utf-8")
        candidate_id = f"finalizer-{hashlib.sha256(identity).hexdigest()[:24]}"
        rows.append(
            database.upsert_integration_candidate(
                project_id,
                task_id,
                candidate_id=candidate_id,
                state=state,
                base_branch=target_branch,
                base_sha=target_sha_before,
                work_branch=package_branch,
                head_sha=integration_sha,
                session_id=session_id,
                evidence={
                    "source": "auto_finalizer_merge",
                    "session_id": session_id,
                    "package_branch": package_branch,
                    "target_branch": target_branch,
                    "integration_sha": integration_sha,
                },
            )
        )
    return rows


def build_blocked_report(
    *,
    project_root: Path,
    handoff_path: Path,
    data: dict[str, Any],
    decision: str,
    issues: list[dict[str, Any]],
    validation_issues: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "project_root": str(project_root),
        "handoff": str(handoff_path),
        "decision": decision,
        "applied": False,
        "pushed": False,
        "package_branch": data.get("package_branch"),
        "target_branch": data.get("base_branch"),
        "ready_to_finalize": data.get("ready_to_finalize", []),
        "issues": issues,
        "validation_issues": validation_issues,
    }


def finalize(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    handoff_path = Path(args.handoff).resolve()

    if not handoff_path.exists():
        return {
            "schema_version": 1,
            "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "project_root": str(project_root),
            "handoff": str(handoff_path),
            "decision": "no_handoff",
            "applied": False,
            "pushed": False,
            "package_branch": None,
            "target_branch": args.base_branch,
            "ready_to_finalize": [],
            "issues": [],
            "validation_issues": [],
        }

    data = json.loads(handoff_path.read_text(encoding="utf-8"))
    is_git_checkout = bool(resolve_ref(project_root, "HEAD"))
    if is_git_checkout and args.apply and not args.fetch:
        return build_blocked_report(
            project_root=project_root,
            handoff_path=handoff_path,
            data=data,
            decision="needs_human",
            issues=[{"task": "target_state", "reason": "apply requires --fetch to prove fresh target state"}],
            validation_issues=[],
        )
    if is_git_checkout and args.fetch:
        fetch = run_git(project_root, ["fetch", args.remote, "--prune"])
        if fetch.returncode != 0:
            detail = fetch.stderr.strip() or fetch.stdout.strip() or "git fetch failed"
            return build_blocked_report(
                project_root=project_root,
                handoff_path=handoff_path,
                data=data,
                decision="needs_human",
                issues=[{"task": "target_state", "reason": f"cannot refresh target state: {detail}"}],
                validation_issues=[],
            )

    target_branch = args.base_branch
    target_remote_ref = f"{args.remote}/{target_branch}"
    remote_target_sha = resolve_ref(project_root, target_remote_ref)
    if is_git_checkout and args.apply and not remote_target_sha:
        return build_blocked_report(
            project_root=project_root,
            handoff_path=handoff_path,
            data=data,
            decision="needs_human",
            issues=[{"task": "target_branch", "reason": f"remote target branch not found: {target_remote_ref}"}],
            validation_issues=[],
        )
    target_ref = target_remote_ref if remote_target_sha else target_branch
    target_sha_before = resolve_ref(project_root, target_ref)
    if is_git_checkout:
        if not target_sha_before:
            return build_blocked_report(
                project_root=project_root,
                handoff_path=handoff_path,
                data=data,
                decision="needs_human",
                issues=[{"task": "target_branch", "reason": f"target branch not found: {target_ref}"}],
                validation_issues=[],
            )
        recorded, target_state_error = finalization_recorded_task_ids_at_ref(project_root, target_sha_before)
        if target_state_error:
            return build_blocked_report(
                project_root=project_root,
                handoff_path=handoff_path,
                data=data,
                decision="needs_human",
                issues=[{"task": "target_state", "reason": target_state_error}],
                validation_issues=[],
            )
    else:
        # Compatibility for isolated validator/unit fixtures that intentionally
        # have no Git repository. Apply-capable checkouts use the target ref.
        recorded = finalization_recorded_task_ids(task_manager_dir(project_root) / "agent_events.jsonl")
    data, already_recorded = filter_recorded_ready_items(data, recorded)
    if already_recorded and not data.get("ready_to_finalize"):
        return {
            "schema_version": 1,
            "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "project_root": str(project_root),
            "handoff": str(handoff_path),
            "decision": "already_finalized",
            "applied": False,
            "pushed": False,
            "package_branch": data.get("package_branch"),
            "target_branch": args.base_branch,
            "ready_to_finalize": [],
            "skipped_already_finalized": already_recorded,
            "issues": [],
            "validation_issues": [],
        }
    validation_issues = run_validate(data)
    hard_errors = [issue for issue in validation_issues if issue.get("severity") == "error"]
    decision, issues = evaluate_gate(data, args.base_branch)
    if hard_errors:
        issues = [*issues, *({"task": item.get("path", "handoff"), "reason": item.get("message", "")} for item in hard_errors)]
    if decision != "auto_merge_to_develop" or hard_errors:
        return build_blocked_report(
            project_root=project_root,
            handoff_path=handoff_path,
            data=data,
            decision="needs_human",
            issues=issues,
            validation_issues=hard_errors,
        )
    if not target_sha_before:
        return build_blocked_report(
            project_root=project_root,
            handoff_path=handoff_path,
            data=data,
            decision="needs_human",
            issues=[{"task": "target_branch", "reason": f"target branch not found: {target_ref}"}],
            validation_issues=[],
        )

    package_branch = str(data["package_branch"])
    try:
        package = package_ref(
            project_root,
            package_branch,
            args.remote,
            require_remote=is_git_checkout and args.apply,
        )
    except RuntimeError as exc:
        return build_blocked_report(
            project_root=project_root,
            handoff_path=handoff_path,
            data=data,
            decision="needs_human",
            issues=[{"task": "package_branch", "reason": str(exc)}],
            validation_issues=[],
        )
    package_sha = resolve_ref(project_root, package)
    if not package_sha:
        raise RuntimeError(f"package branch not resolvable: {package_branch}")
    ancestry = run_git(project_root, ["merge-base", "--is-ancestor", package_sha, target_sha_before])
    if ancestry.returncode not in {0, 1}:
        detail = ancestry.stderr.strip() or ancestry.stdout.strip() or "git merge-base failed"
        return build_blocked_report(
            project_root=project_root,
            handoff_path=handoff_path,
            data=data,
            decision="needs_human",
            issues=[{"task": "package_ancestry", "reason": detail}],
            validation_issues=[],
        )
    package_already_integrated = ancestry.returncode == 0

    stamp = utc_stamp()
    finalizer_branch = args.finalizer_branch or f"finalizer/{stamp}-{package_branch.split('/')[-1]}"
    worktree = Path(args.worktree_root).expanduser().resolve() / finalizer_branch.replace("/", "-")

    report: dict[str, Any] = {
        "schema_version": 1,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "project_root": str(project_root),
        "handoff": str(handoff_path),
        "decision": decision,
        "applied": bool(args.apply),
        "pushed": False,
        "package_branch": package_branch,
        "package_ref": package,
        "package_sha": package_sha,
        "package_already_integrated": package_already_integrated,
        "target_branch": target_branch,
        "target_ref": target_ref,
        "target_sha_before": target_sha_before,
        "ready_to_finalize": data.get("ready_to_finalize", []),
        "skipped_already_finalized": already_recorded,
        "finalizer_branch": finalizer_branch,
        "worktree": str(worktree),
        "issues": [],
        "commands": [],
    }

    if not args.apply:
        report["action"] = "dry_run"
        return report

    clean_worktree_path(project_root, worktree)
    worktree.parent.mkdir(parents=True, exist_ok=True)
    git_text(project_root, ["worktree", "add", "-B", finalizer_branch, str(worktree), target_sha_before])
    if package_already_integrated:
        integration_sha = target_sha_before
        report["decision"] = "integration_package_state_recovery"
    else:
        merge = run_git(project_root, ["merge", "--no-ff", "--no-edit", package], cwd=worktree)
        report["commands"].append({"command": ["git", "merge", "--no-ff", "--no-edit", package], "exit_code": merge.returncode})
        if merge.returncode != 0:
            report["decision"] = "finalization_blocked"
            report["issues"].append({"task": "merge", "reason": merge.stderr.strip() or merge.stdout.strip()})
            return report

        check = run_git(project_root, ["diff", "--check", f"{target_sha_before}..HEAD"], cwd=worktree)
        report["commands"].append({"command": ["git", "diff", "--check", f"{target_sha_before}..HEAD"], "exit_code": check.returncode})
        if check.returncode != 0:
            report["decision"] = "finalization_blocked"
            report["issues"].append({"task": "diff_check", "reason": check.stderr.strip() or check.stdout.strip()})
            return report

        report_path = write_report(worktree, report)
        run_git(project_root, ["add", report_path.relative_to(worktree).as_posix()], cwd=worktree)
        status = git_text(project_root, ["status", "--porcelain"], cwd=worktree)
        if status:
            git_text(project_root, ["commit", "-m", f"chore(finalizer): finalize {package_branch}"], cwd=worktree)
        integration_sha = git_text(project_root, ["rev-parse", "HEAD"], cwd=worktree)
        report["decision"] = "integration_package_returned"

    report["integration_sha"] = integration_sha
    state_commit, lock_errors = record_finalizer_state_commit(
        project_root=project_root,
        worktree=worktree,
        data=data,
        report=report,
        remote=args.remote,
        target_branch=target_branch,
        expected_head=integration_sha,
    )
    if lock_errors:
        report["decision"] = "finalization_blocked"
        report["issues"].extend(lock_errors)
        report["state_sync"] = {"ok": False, "reason": "exact_lock_release_blocked"}
        return report
    sql_state_pending = (
        os.environ.get("AISTUDIO_TASK_CONTROL_AUTHORITY", "").strip() == "postgres"
    )
    if not state_commit and not sql_state_pending:
        report["decision"] = "finalization_blocked"
        report["issues"].append({"task": "state_commit", "reason": "finalizer produced no canonical state commit"})
        report["state_sync"] = {"ok": False, "reason": "state_commit_missing"}
        return report

    if sql_state_pending:
        try:
            report["sql_integration_candidates"] = record_sql_finalizer_candidates(
                data=data,
                project_id=os.environ.get("AISTUDIO_TASK_CONTROL_PROJECT_ID", "").strip(),
                target_branch=target_branch,
                target_sha_before=target_sha_before,
                integration_sha=integration_sha,
                state="integrating",
            )
        except Exception as exc:
            report["decision"] = "finalization_blocked"
            report["issues"].append(
                {"task": "sql_integration_candidate", "reason": str(exc)}
            )
            report["state_sync"] = {
                "ok": False,
                "reason": "sql_pending_integration_record_failed",
            }
            return report

    push = run_git(project_root, ["push", args.remote, f"HEAD:{target_branch}"], cwd=worktree)
    report["commands"].append({"command": ["git", "push", args.remote, f"HEAD:{target_branch}"], "exit_code": push.returncode})
    if push.returncode != 0:
        report["decision"] = "finalization_blocked"
        report["issues"].append({"task": "push", "reason": push.stderr.strip() or push.stdout.strip()})
        report["state_sync"] = {"ok": False, "reason": "atomic_finalizer_push_failed"}
        return report

    report["pushed"] = True
    if sql_state_pending:
        try:
            report["sql_integration_candidates"] = record_sql_finalizer_candidates(
                data=data,
                project_id=os.environ.get("AISTUDIO_TASK_CONTROL_PROJECT_ID", "").strip(),
                target_branch=target_branch,
                target_sha_before=target_sha_before,
                integration_sha=integration_sha,
                state="merged",
            )
        except Exception as exc:
            report["decision"] = "finalization_blocked"
            report["issues"].append(
                {"task": "sql_integration_candidate", "reason": str(exc)}
            )
            report["state_sync"] = {
                "ok": False,
                "reason": "sql_merged_integration_record_failed",
                "recovery_required": True,
            }
            return report
    report["state_commit"] = state_commit
    report["target_sha_after"] = state_commit or integration_sha
    report["state_sync"] = (
        {
            "ok": True,
            "reason": "task_state_staged_for_postgres_session_commit",
            "pending_session_commit": True,
        }
        if sql_state_pending
        else {"ok": True, "reason": "integration_and_finalizer_state_pushed_once"}
    )
    return report


def write_cli_report(
    *,
    project_root: Path,
    report: dict[str, Any],
    apply: bool,
    output: str | None,
) -> Path | None:
    if output:
        output_path = Path(output).expanduser().resolve()
    elif apply:
        output_path = task_manager_dir(project_root) / "auto_finalizer_merge.json"
    else:
        return None
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--handoff", default=None, help="Defaults to docs/plans/integration_handoff.json.")
    parser.add_argument("--base-branch", default="develop")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--worktree-root", default=None, help="Defaults to <project>/AiStudio/Agent/finalizer-worktrees.")
    parser.add_argument("--finalizer-branch", default=None)
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--output", default=None, help="Optional report path. Dry-run does not write project state unless this is set.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    args.project_root = str(project_root)
    if args.handoff is None:
        args.handoff = str(task_manager_dir(project_root) / "integration_handoff.json")
    if args.worktree_root is None:
        args.worktree_root = str(project_root / "AiStudio" / "Agent" / "finalizer-worktrees")

    report = finalize(args)
    write_cli_report(
        project_root=project_root,
        report=report,
        apply=bool(args.apply),
        output=args.output,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"decision: {report.get('decision')}")
        print(f"package_branch: {report.get('package_branch')}")
        print(f"target_branch: {report.get('target_branch')}")
    if report.get("decision") in {"needs_human", "finalization_blocked"}:
        return 4
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
