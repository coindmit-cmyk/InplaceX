#!/usr/bin/env python3
"""Run bounded branch-recovery queue batches without a model cycle.

The runner deliberately delegates branch classification, recovery-row creation,
and archive-first retirement to the existing lifecycle tools.  Its job is only
to provide an idempotent transaction boundary around those tools.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    import resource
except ImportError:  # pragma: no cover - Windows development host
    resource = None

ALLOWED_CHANGED_PATHS = {
    "AiStudio/Task_manager/task_queue.json",
    "AiStudio/Task_manager/agent_events.jsonl",
}
SUCCESS_CONCLUSIONS = {"SUCCESS", "NEUTRAL", "SKIPPED"}
MERGED_CLASSES = {"merged_safe_delete", "archive_candidate", "cleanup_candidate"}
PROTECTED_BRANCHES = {"develop", "release/main"}


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_json(path: Path, default: Any = None) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


class RunnerError(RuntimeError):
    pass


class OperationCounter:
    def __init__(self) -> None:
        self.git = 0
        self.github_api = 0
        self.other = 0

    def classify(self, command: list[str]) -> None:
        name = Path(command[0]).name.lower()
        if name in {"git", "git.exe"}:
            self.git += 1
        elif name in {"gh", "gh.exe"}:
            self.github_api += 1
        else:
            self.other += 1

    def payload(self) -> dict[str, int]:
        return {"git": self.git, "github_api": self.github_api, "other": self.other}


def run(
    command: list[str],
    *,
    cwd: Path,
    counter: OperationCounter,
    evidence_path: Path | None = None,
    timeout: int = 900,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    counter.classify(command)
    proc = subprocess.run(
        command,
        cwd=str(cwd),
        text=True,
        capture_output=True,
        check=False,
        timeout=timeout,
    )
    if evidence_path:
        evidence_path.parent.mkdir(parents=True, exist_ok=True)
        evidence_path.write_text(
            json.dumps(
                {
                    "command": command,
                    "cwd": str(cwd),
                    "exit_code": proc.returncode,
                    "stdout": proc.stdout,
                    "stderr": proc.stderr,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
    if check and proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "command failed").strip().splitlines()
        raise RunnerError(f"{Path(command[0]).name} exited {proc.returncode}: {'; '.join(detail[-3:])[:600]}")
    return proc


@contextmanager
def exclusive_lock(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("a+", encoding="utf-8")
    acquired = False
    try:
        if os.name == "nt":
            import msvcrt

            handle.seek(0)
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                raise RunnerError(f"runner lock is held: {path}") from exc
        else:
            import fcntl

            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                raise RunnerError(f"runner lock is held: {path}") from exc
        acquired = True
        handle.seek(0)
        handle.truncate()
        handle.write(json.dumps({"pid": os.getpid(), "host": socket.gethostname(), "acquired_at": utc_now()}))
        handle.flush()
        yield
    finally:
        try:
            if acquired and os.name == "nt":
                import msvcrt

                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            elif acquired:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()


def validator_report(
    script: Path,
    queue: Path,
    evidence_path: Path,
    *,
    source_root: Path,
    counter: OperationCounter,
) -> dict[str, Any]:
    proc = run(
        [sys.executable, str(script), "--queue", str(queue), "--json"],
        cwd=source_root,
        counter=counter,
        evidence_path=evidence_path.with_suffix(".command.json"),
        check=False,
    )
    try:
        payload = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RunnerError(f"validator emitted invalid JSON: {script.name}") from exc
    write_json(evidence_path, payload)
    return payload


def compare_validator_baseline(
    baseline: dict[str, Any], candidate: dict[str, Any], name: str
) -> None:
    for field in ("errors", "warnings"):
        before = int(baseline.get(field) or 0)
        after = int(candidate.get(field) or 0)
        if after > before:
            raise RunnerError(f"{name} regression: {field} {before}->{after}")


def recovery_index(queue: dict[str, Any]) -> dict[str, dict[str, Any]]:
    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        raise RunnerError("task queue must contain a tasks array")
    result: dict[str, dict[str, Any]] = {}
    ids: set[str] = set()
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "")
        if task_id:
            if task_id in ids:
                raise RunnerError(f"duplicate task id: {task_id}")
            ids.add(task_id)
        key = str(task.get("repository_recovery_key") or "")
        if not key:
            continue
        if key in result:
            raise RunnerError(f"duplicate repository_recovery_key: {key}")
        result[key] = task
    return result


def validate_new_rows(
    baseline_queue: dict[str, Any],
    candidate_queue: dict[str, Any],
    expected_count: int,
) -> list[str]:
    baseline = recovery_index(baseline_queue)
    candidate = recovery_index(candidate_queue)
    new_keys = sorted(set(candidate) - set(baseline))
    if len(new_keys) != expected_count:
        raise RunnerError(f"expected {expected_count} new recovery rows, found {len(new_keys)}")
    for key in new_keys:
        row = candidate[key]
        if row.get("worker_ready") is not False:
            raise RunnerError(f"new recovery row must keep worker_ready=false: {key}")
    return new_keys


def changed_paths(root: Path, counter: OperationCounter) -> list[str]:
    proc = run(["git", "status", "--porcelain"], cwd=root, counter=counter)
    paths = []
    for line in proc.stdout.splitlines():
        value = line[3:].strip()
        if " -> " in value:
            value = value.split(" -> ", 1)[1]
        paths.append(value.replace("\\", "/"))
    return sorted(paths)


def prepare_worktree(
    source_root: Path,
    worktree: Path,
    branch: str,
    counter: OperationCounter,
    evidence_dir: Path,
    dry_run: bool = False,
) -> None:
    run(["git", "fetch", "origin", "--prune"], cwd=source_root, counter=counter, evidence_path=evidence_dir / "fetch.json")
    remote_sha = run(
        ["git", "rev-parse", "origin/develop"], cwd=source_root, counter=counter
    ).stdout.strip()
    if dry_run:
        if worktree.exists():
            status = run(["git", "status", "--porcelain"], cwd=worktree, counter=counter)
            if status.stdout.strip():
                raise RunnerError(f"existing dry-run worktree is dirty: {worktree}")
            symbolic = run(
                ["git", "symbolic-ref", "-q", "HEAD"],
                cwd=worktree,
                counter=counter,
                check=False,
            )
            if symbolic.returncode == 0:
                raise RunnerError(f"dry-run refuses an existing branch worktree: {worktree}")
            return
        worktree.parent.mkdir(parents=True, exist_ok=True)
        run(
            ["git", "worktree", "add", "--detach", str(worktree), remote_sha],
            cwd=source_root,
            counter=counter,
        )
        return
    remote_branch = run(
        ["git", "ls-remote", "--heads", "origin", branch],
        cwd=source_root,
        counter=counter,
    ).stdout.strip()
    if worktree.exists():
        head = run(["git", "rev-parse", "HEAD"], cwd=worktree, counter=counter).stdout.strip()
        if remote_branch and not remote_branch.startswith(head):
            raise RunnerError(f"existing worktree head differs from remote branch: {branch}")
        return
    worktree.parent.mkdir(parents=True, exist_ok=True)
    local_exists = run(
        ["git", "show-ref", "--verify", "--quiet", f"refs/heads/{branch}"],
        cwd=source_root,
        counter=counter,
        check=False,
    ).returncode == 0
    if local_exists:
        run(["git", "worktree", "add", str(worktree), branch], cwd=source_root, counter=counter)
    elif remote_branch:
        run(
            ["git", "worktree", "add", "-b", branch, str(worktree), f"origin/{branch}"],
            cwd=source_root,
            counter=counter,
        )
    else:
        run(
            ["git", "worktree", "add", "-b", branch, str(worktree), remote_sha],
            cwd=source_root,
            counter=counter,
        )


def scan_batch(
    args: argparse.Namespace,
    worktree: Path,
    size: int,
    evidence_dir: Path,
    counter: OperationCounter,
) -> dict[str, Any]:
    output = evidence_dir / "scanner.json"
    command = [
        sys.executable,
        str(args.source_root / "scripts/agent_control/branch_lifecycle_scanner.py"),
        "--project-root",
        str(args.source_root),
        "--task-project-root",
        str(worktree),
        "--stale-days",
        str(args.stale_days),
        "--max-task-count",
        str(size),
        "--fetch",
        "--output",
        str(output),
        "--json",
    ]
    if args.codex_activity_dir:
        command += ["--codex-activity-dir", str(args.codex_activity_dir)]
    for host in args.expected_codex_host:
        command += ["--expected-codex-host", host]
    if args.evidence_cache:
        command += ["--evidence-cache", str(args.evidence_cache)]
    if args.recovery_task_id_file:
        command += ["--recovery-task-id-file", str(args.recovery_task_id_file)]
    if args.apply:
        command += ["--apply-tasks", "--yes"]
    run(
        command,
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_dir / "scanner-command.json",
        timeout=args.command_timeout_seconds,
    )
    payload = load_json(output)
    if not isinstance(payload, dict) or not payload.get("ok"):
        raise RunnerError("branch lifecycle scanner did not produce an ok report")
    return payload


def create_or_find_pr(
    args: argparse.Namespace,
    branch: str,
    head_sha: str,
    size: int,
    counter: OperationCounter,
    evidence_dir: Path,
) -> int:
    listed = run(
        [
            "gh", "pr", "list", "--repo", args.repo, "--state", "all",
            "--head", branch, "--limit", "1", "--json", "number,headRefOid",
        ],
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_dir / "pr-list.json",
    )
    rows = json.loads(listed.stdout or "[]")
    if rows:
        if str(rows[0].get("headRefOid") or "") != head_sha:
            raise RunnerError("existing PR head differs from exact candidate head")
        return int(rows[0]["number"])
    body = evidence_dir / "pr-body.md"
    body.write_text(
        "Stages one deterministic branch-recovery queue batch.\n\n"
        f"- rows: {size}\n"
        "- all new rows keep `worker_ready=false`\n"
        "- readiness and Dispatcher guard counts do not regress from baseline\n"
        "- model/API cycle: none (`model_tokens=0`)\n",
        encoding="utf-8",
    )
    created = run(
        [
            "gh", "pr", "create", "--repo", args.repo, "--base", "develop",
            "--head", branch, "--title", f"Stage deterministic branch recovery batch ({size})",
            "--body-file", str(body),
        ],
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_dir / "pr-create.json",
    )
    url = created.stdout.strip()
    try:
        return int(url.rstrip("/").rsplit("/", 1)[-1])
    except ValueError as exc:
        raise RunnerError("could not parse created PR number") from exc


def wait_merge_gate(
    args: argparse.Namespace,
    pr_number: int,
    head_sha: str,
    counter: OperationCounter,
    evidence_dir: Path,
) -> dict[str, Any]:
    deadline = time.monotonic() + args.gate_timeout_seconds
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        proc = run(
            [
                "gh", "pr", "view", str(pr_number), "--repo", args.repo,
                "--json", "state,headRefOid,mergeable,mergeStateStatus,reviewDecision,statusCheckRollup",
            ],
            cwd=args.source_root,
            counter=counter,
        )
        last = json.loads(proc.stdout)
        write_json(evidence_dir / "merge-gate-latest.json", last)
        if str(last.get("headRefOid") or "") != head_sha:
            raise RunnerError("PR head changed while waiting for merge gate")
        if str(last.get("state") or "").upper() == "MERGED":
            return last
        checks = list(last.get("statusCheckRollup") or [])
        pending = [
            item for item in checks
            if str(item.get("status") or "").upper() != "COMPLETED"
        ]
        failed = [
            item for item in checks
            if str(item.get("status") or "").upper() == "COMPLETED"
            and str(item.get("conclusion") or "").upper() not in SUCCESS_CONCLUSIONS
        ]
        review = str(last.get("reviewDecision") or "").upper()
        merge_state = str(last.get("mergeStateStatus") or "").upper()
        if failed:
            raise RunnerError("repository check gate failed")
        if (
            not pending
            and str(last.get("mergeable") or "").upper() == "MERGEABLE"
            and merge_state in {"CLEAN", "HAS_HOOKS", "UNSTABLE"}
            and review in {"", "APPROVED"}
        ):
            return last
        time.sleep(min(10, max(1, args.gate_timeout_seconds)))
    raise RunnerError(f"merge gate timeout: {json.dumps(last, ensure_ascii=False)[:500]}")


def archive_and_retire(
    args: argparse.Namespace,
    branch: str,
    evidence_dir: Path,
    counter: OperationCounter,
) -> dict[str, Any]:
    run(["git", "fetch", "origin", "--prune"], cwd=args.source_root, counter=counter)
    command = [
        sys.executable,
        str(args.source_root / "scripts/agent_control/worktree_retirement.py"),
        "--project-root", str(args.source_root),
        "--branch", branch,
        "--base-ref", "origin/develop",
        "--min-age-days", "0",
        "--archive-root", args.archive_root,
        "--max-count", "1",
        "--apply",
        "--delete-local-branch",
        "--delete-remote-branch",
        "--json",
    ]
    if args.archive_ssh_host:
        command += ["--archive-ssh-host", args.archive_ssh_host]
    proc = run(
        command,
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_dir / "retirement-command.json",
        timeout=args.command_timeout_seconds,
    )
    payload = json.loads(proc.stdout)
    write_json(evidence_dir / "retirement.json", payload)
    results = payload.get("results") or []
    if not payload.get("ok") or len(results) != 1 or results[0].get("status") != "retired":
        raise RunnerError("batch branch retirement did not complete")
    return results[0]


def verified_retirement_result(
    evidence_path: Path,
    branch: str,
    head_sha: str,
) -> dict[str, Any]:
    payload = load_json(evidence_path)
    results = payload.get("results") if isinstance(payload, dict) else None
    if (
        not isinstance(payload, dict)
        or not payload.get("ok")
        or not isinstance(results, list)
        or len(results) != 1
    ):
        raise RunnerError(f"verified retirement evidence missing for {branch}")
    result = results[0]
    archive = result.get("archive") if isinstance(result, dict) else None
    if (
        not isinstance(result, dict)
        or result.get("status") != "retired"
        or result.get("branch") != branch
        or result.get("sha") != head_sha
        or not isinstance(archive, dict)
        or archive.get("branch") != branch
        or archive.get("sha") != head_sha
        or archive.get("verified") is not True
    ):
        raise RunnerError(f"retirement evidence mismatch for {branch}")
    return result


def remote_heads(
    args: argparse.Namespace,
    counter: OperationCounter,
    evidence_path: Path,
) -> dict[str, str]:
    proc = run(
        ["git", "ls-remote", "--heads", "origin"],
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_path,
    )
    result: dict[str, str] = {}
    for line in proc.stdout.splitlines():
        sha, separator, ref = line.partition("\t")
        if separator and ref.startswith("refs/heads/"):
            result[ref.removeprefix("refs/heads/")] = sha
    return result


def full_scan(
    args: argparse.Namespace,
    counter: OperationCounter,
    evidence_dir: Path,
    label: str,
) -> dict[str, Any]:
    output = evidence_dir / f"{label}.json"
    command = [
        sys.executable,
        str(args.source_root / "scripts/agent_control/branch_lifecycle_scanner.py"),
        "--project-root", str(args.source_root),
        "--task-project-root", str(args.source_root),
        "--stale-days", "0",
        "--max-task-count", "0",
        "--fetch",
        "--output", str(output),
        "--json",
    ]
    if args.codex_activity_dir:
        command += ["--codex-activity-dir", str(args.codex_activity_dir)]
    for host in args.expected_codex_host:
        command += ["--expected-codex-host", host]
    if args.evidence_cache:
        command += ["--evidence-cache", str(args.evidence_cache)]
    run(
        command,
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_dir / f"{label}-command.json",
        timeout=args.command_timeout_seconds,
    )
    payload = load_json(output)
    if not isinstance(payload, dict) or not payload.get("ok"):
        raise RunnerError("full branch lifecycle rescan failed")
    return payload


def open_pr_heads(args: argparse.Namespace, counter: OperationCounter) -> set[str]:
    proc = run(
        [
            "gh", "pr", "list", "--repo", args.repo, "--state", "open",
            "--limit", "1000", "--json", "headRefName",
        ],
        cwd=args.source_root,
        counter=counter,
    )
    return {
        str(row.get("headRefName") or "")
        for row in json.loads(proc.stdout or "[]")
        if row.get("headRefName")
    }


def active_worktree_heads(args: argparse.Namespace, counter: OperationCounter) -> set[str]:
    proc = run(
        ["git", "worktree", "list", "--porcelain"],
        cwd=args.source_root,
        counter=counter,
    )
    return {
        line.removeprefix("branch refs/heads/").strip()
        for line in proc.stdout.splitlines()
        if line.startswith("branch refs/heads/")
    }


def retirement_cycle(
    args: argparse.Namespace,
    branches: list[str],
    *,
    counter: OperationCounter,
    evidence_dir: Path,
    label: str,
    max_count: int,
    allow_archive_namespace: bool = False,
    lifecycle_evidence: Path | None = None,
) -> dict[str, Any]:
    command = [
        sys.executable,
        str(args.source_root / "scripts/agent_control/worktree_retirement.py"),
        "--project-root", str(args.source_root),
        "--base-ref", "origin/develop",
        "--base-ref", "origin/release/main",
        "--min-age-days", "0",
        "--archive-root", args.archive_root,
        "--max-count", str(max_count),
        "--delete-local-branch",
        "--delete-remote-branch",
        "--json",
    ]
    for branch in branches:
        command += ["--branch", branch]
    if allow_archive_namespace:
        command += ["--allow-unintegrated-archive-namespace"]
    if lifecycle_evidence:
        command += ["--lifecycle-evidence", str(lifecycle_evidence)]
    planned = run(
        command,
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_dir / f"{label}-plan-command.json",
        timeout=args.command_timeout_seconds,
        check=False,
    )
    try:
        plan_payload = json.loads(planned.stdout)
    except json.JSONDecodeError as exc:
        raise RunnerError(f"invalid retirement report: {label}") from exc
    write_json(evidence_dir / f"{label}-plan.json", plan_payload)
    if not plan_payload.get("ok"):
        raise RunnerError(f"retirement cycle blocked: {label}")
    if not args.apply or int((plan_payload.get("plan") or {}).get("eligible_count") or 0) == 0:
        return plan_payload
    proc = run(
        [*command, "--apply"],
        cwd=args.source_root,
        counter=counter,
        evidence_path=evidence_dir / f"{label}-apply-command.json",
        timeout=args.command_timeout_seconds,
        check=False,
    )
    try:
        payload = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RunnerError(f"invalid applied retirement report: {label}") from exc
    write_json(evidence_dir / f"{label}.json", payload)
    if not payload.get("ok"):
        raise RunnerError(f"retirement apply blocked: {label}")
    return payload


def reconcile_full_namespace(
    args: argparse.Namespace,
    counter: OperationCounter,
) -> dict[str, Any]:
    evidence_dir = args.evidence_root / "full-reconciliation"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    initial_heads = remote_heads(args, counter, evidence_dir / "initial-heads-command.json")
    initial_scan = full_scan(args, counter, evidence_dir, "initial-scan")
    initial_decisions = {
        str(row.get("branch") or ""): row
        for row in (initial_scan.get("scanner") or {}).get("decisions") or []
    }
    initially_active = open_pr_heads(args, counter) | active_worktree_heads(args, counter)
    initially_active |= {
        name
        for name, row in initial_decisions.items()
        if row.get("source_classification") == "keep_active"
        and any(
            token in str(row.get("reason") or "").lower()
            for token in ("task", "lock", "codex", "automation", "active", "open pr", "worktree")
        )
    }
    archive_refs = sorted(name for name in initial_heads if name.startswith("archive/"))
    migrated_archive_refs = 0
    retired_merged_refs = 0

    archive_candidates = [name for name in archive_refs if name not in initially_active]
    if archive_candidates:
        archive_report = retirement_cycle(
            args,
            archive_candidates,
            counter=counter,
            evidence_dir=evidence_dir,
            label="archive-namespace-retirement",
            max_count=min(args.max_archive_ref_migrations, len(archive_candidates)),
            allow_archive_namespace=True,
        )
        migrated_archive_refs = sum(
            1 for row in archive_report.get("results") or [] if row.get("status") == "retired"
        )

    heads_after_archive = remote_heads(args, counter, evidence_dir / "post-archive-heads-command.json")
    scan_after_archive = full_scan(args, counter, evidence_dir, "post-archive-scan")
    active_after_archive = open_pr_heads(args, counter) | active_worktree_heads(args, counter)
    safe_merged = sorted({
        str(row.get("branch") or "")
        for row in (scan_after_archive.get("scanner") or {}).get("decisions") or []
        if row.get("source_classification") in MERGED_CLASSES
        and str(row.get("branch") or "") in heads_after_archive
        and str(row.get("branch") or "") not in active_after_archive
        and str(row.get("branch") or "") not in PROTECTED_BRANCHES
    })
    if safe_merged:
        merged_report = retirement_cycle(
            args,
            safe_merged,
            counter=counter,
            evidence_dir=evidence_dir,
            label="merged-safe-retirement",
            max_count=min(args.max_safe_ref_retirements, len(safe_merged)),
            lifecycle_evidence=evidence_dir / "post-archive-scan.json",
        )
        retired_merged_refs = sum(
            1 for row in merged_report.get("results") or [] if row.get("status") == "retired"
        )

    final_heads = remote_heads(args, counter, evidence_dir / "final-heads-command.json")
    final_scan = full_scan(args, counter, evidence_dir, "final-scan")
    decisions = {
        str(row.get("branch") or ""): row
        for row in (final_scan.get("scanner") or {}).get("decisions") or []
    }
    archive_remaining = {name for name in final_heads if name.startswith("archive/")}
    persistent = {
        name for name in final_heads
        if name in PROTECTED_BRANCHES
    }
    pr_active = open_pr_heads(args, counter) & set(final_heads)
    worktree_active = active_worktree_heads(args, counter) & set(final_heads)
    planner_active = {
        name
        for name, row in decisions.items()
        if name in final_heads
        and row.get("source_classification") == "keep_active"
        and any(
            token in str(row.get("reason") or "").lower()
            for token in ("task", "lock", "codex", "automation", "active", "open pr", "worktree")
        )
    }
    active = (pr_active | worktree_active | planner_active) - persistent
    protected = persistent | active
    merged = {
        name
        for name, row in decisions.items()
        if name in final_heads and row.get("source_classification") in MERGED_CLASSES
    } - protected - archive_remaining
    needs_work = set(final_heads) - archive_remaining - protected - merged
    classified = archive_remaining | protected | merged | needs_work
    inventory = {
        "generated_at": utc_now(),
        "persistent": [
            {"branch": name, "sha": final_heads[name], "reason": "canonical persistent branch"}
            for name in sorted(persistent)
        ],
        "active": [
            {
                "branch": name,
                "sha": final_heads[name],
                "reason": str((decisions.get(name) or {}).get("reason") or "live PR or worktree evidence"),
                "open_pr": name in pr_active,
                "live_worktree": name in worktree_active,
                "planner_active_evidence": name in planner_active,
            }
            for name in sorted(active)
        ],
        "retained_ambiguous": [
            {
                "branch": name,
                "sha": final_heads[name],
                "classification": str((decisions.get(name) or {}).get("source_classification") or "unavailable"),
                "reason": str((decisions.get(name) or {}).get("reason") or "requires bounded owner/recovery disposition"),
            }
            for name in sorted(needs_work | archive_remaining | merged)
        ],
    }
    inventory_path = evidence_dir / "reconciliation-inventory.json"
    write_json(inventory_path, inventory)
    report = {
        "initial_total_heads": len(initial_heads),
        "remaining_total_heads": len(final_heads),
        "initial_archive_namespace": len(archive_refs),
        "migrated_archive_refs": migrated_archive_refs,
        "remaining_remote_archive_refs": len(archive_remaining),
        "persistent": len(persistent),
        "active": len(active),
        "protected_current_open_pr_worktree": len(protected),
        "merged_safe_delete_remaining": len(merged),
        "preservation_archive_candidates_remaining": len(archive_remaining - protected),
        "needs_work_ambiguous": len(needs_work),
        "retired_merged_refs": retired_merged_refs,
        "classified_heads": len(classified),
        "unclassified_heads": len(set(final_heads) - classified),
        "continuation_required": bool(archive_remaining or merged or needs_work),
        "inventory_evidence": str(inventory_path),
    }
    write_json(evidence_dir / "reconciliation-summary.json", report)
    return report


def batch_summary(
    scanner: dict[str, Any],
    *,
    branch: str,
    head_sha: str | None = None,
    pr_number: int | None = None,
    merge_sha: str | None = None,
    archive: dict[str, Any] | None = None,
) -> dict[str, Any]:
    routing = scanner.get("task_routing") or {}
    result = {
        "branch": branch,
        "staged": int(routing.get("staged_count") or 0),
        "covered": int(routing.get("covered_count") or 0),
        "deferred": int(routing.get("deferred_count") or 0),
    }
    if head_sha:
        result["head_sha"] = head_sha
    if pr_number:
        result["pr"] = pr_number
    if merge_sha:
        result["merge_sha"] = merge_sha
    if archive:
        archive_payload = archive.get("archive") or {}
        result["archive"] = {
            "location": archive_payload.get("location"),
            "sha": archive_payload.get("sha"),
            "bundle_sha256": archive_payload.get("bundle_sha256"),
            "verified": archive_payload.get("verified"),
        }
    return result


def update_batch_state(
    state: dict[str, Any],
    state_path: Path,
    branch_name: str,
    **values: Any,
) -> dict[str, Any]:
    rows = state["batches"]
    entry = next((row for row in rows if row.get("branch") == branch_name), None)
    if entry is None:
        entry = {"branch": branch_name}
        rows.append(entry)
    entry.update(values)
    entry["updated_at"] = utc_now()
    write_json(state_path, state)
    return entry


def execute(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    usage_before = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    counter = OperationCounter()
    batches: list[dict[str, Any]] = []
    total_staged = total_covered = total_deferred = 0
    state_path = args.evidence_root / "state.json"
    state = load_json(state_path, {"schema_version": "1.0", "batches": []})
    if not isinstance(state, dict) or not isinstance(state.get("batches"), list):
        raise RunnerError("invalid runner state")
    sizes = args.batch_sizes[: args.max_batches]
    if sum(sizes) > args.max_branches:
        raise RunnerError("batch sizes exceed --max-branches")

    for ordinal, size in enumerate(sizes, start=1):
        batch_number = args.start_batch_number + ordinal - 1
        branch = f"{args.branch_prefix}-{batch_number:02d}-{args.run_date}"
        worktree = args.worktree_root / f"branch-recovery-batch-{batch_number:02d}-{args.run_date}"
        evidence_dir = args.evidence_root / f"batch-{batch_number:02d}"
        evidence_dir.mkdir(parents=True, exist_ok=True)
        state_entry = next(
            (row for row in state["batches"] if row.get("branch") == branch),
            None,
        )
        if str((state_entry or {}).get("phase") or "") == "completed":
            saved = {
                key: value
                for key, value in state_entry.items()
                if key in {
                    "branch", "staged", "covered", "deferred", "head_sha",
                    "pr", "merge_sha", "archive",
                }
            }
            batches.append(saved)
            total_staged += int(saved.get("staged") or 0)
            total_covered += int(saved.get("covered") or 0)
            total_deferred = int(saved.get("deferred") or 0)
            if total_deferred == 0:
                break
            continue
        if str((state_entry or {}).get("phase") or "") == "merged":
            scanner = load_json(evidence_dir / "scanner.json")
            if not isinstance(scanner, dict) or not scanner.get("ok"):
                raise RunnerError(f"restart evidence missing for {branch}")
            head_sha = str(state_entry.get("head_sha") or "")
            if not head_sha:
                raise RunnerError(f"restart head missing for {branch}")
            archive = verified_retirement_result(
                evidence_dir / "retirement.json",
                branch,
                head_sha,
            )
            entry = batch_summary(
                scanner,
                branch=branch,
                head_sha=head_sha,
                pr_number=int(state_entry.get("pr") or 0),
                merge_sha=str(state_entry.get("merge_sha") or ""),
                archive=archive,
            )
            batches.append(entry)
            total_staged += int(entry.get("staged") or 0)
            total_covered += int(entry.get("covered") or 0)
            total_deferred = int(entry.get("deferred") or 0)
            update_batch_state(state, state_path, branch, phase="completed", **entry)
            if total_deferred == 0:
                break
            continue
        prepare_worktree(
            args.source_root,
            worktree,
            branch,
            counter,
            evidence_dir,
            dry_run=not args.apply,
        )
        queue_path = worktree / "AiStudio/Task_manager/task_queue.json"
        phase = str((state_entry or {}).get("phase") or "")
        resume = phase in {"committed", "pushed", "pr_open", "merged"}
        if resume:
            scanner = load_json(evidence_dir / "scanner.json")
            if not isinstance(scanner, dict) or not scanner.get("ok"):
                raise RunnerError(f"restart evidence missing for {branch}")
            head_sha = str(state_entry.get("head_sha") or "")
            current_head = run(
                ["git", "rev-parse", "HEAD"], cwd=worktree, counter=counter
            ).stdout.strip()
            if current_head != head_sha:
                raise RunnerError(f"restart head mismatch for {branch}")
            pr_number = int(state_entry.get("pr") or 0)
        else:
            baseline_queue = load_json(queue_path)
            if not isinstance(baseline_queue, dict):
                raise RunnerError(f"baseline queue missing: {queue_path}")
            baseline_readiness = validator_report(
                args.source_root / "scripts/agent_control/validate_task_queue_readiness.py",
                queue_path,
                evidence_dir / "baseline-readiness.json",
                source_root=args.source_root,
                counter=counter,
            )
            baseline_guard = validator_report(
                args.source_root / "scripts/agent_control/dispatcher_decision_guard.py",
                queue_path,
                evidence_dir / "baseline-dispatcher-guard.json",
                source_root=args.source_root,
                counter=counter,
            )
            scanner = scan_batch(args, worktree, size, evidence_dir, counter)
        routing = scanner.get("task_routing") or {}
        staged = int(routing.get("staged_count") or 0)
        covered = int(routing.get("covered_count") or 0)
        deferred = int(routing.get("deferred_count") or 0)
        total_staged += staged
        total_covered += covered
        total_deferred = deferred
        if not args.apply:
            batches.append(batch_summary(scanner, branch=branch))
            run(
                ["git", "worktree", "remove", str(worktree)],
                cwd=args.source_root,
                counter=counter,
            )
            break
        if resume and phase == "merged":
            merge_sha = str(state_entry.get("merge_sha") or "")
            archive = archive_and_retire(args, branch, evidence_dir, counter)
            entry = batch_summary(
                scanner,
                branch=branch,
                head_sha=head_sha,
                pr_number=pr_number,
                merge_sha=merge_sha,
                archive=archive,
            )
            batches.append(entry)
            update_batch_state(state, state_path, branch, phase="completed", **entry)
            if deferred == 0:
                break
            continue
        if staged == 0:
            paths = changed_paths(worktree, counter)
            if paths:
                raise RunnerError("scanner reported no staged rows but changed the task worktree")
            shutil.rmtree(worktree, ignore_errors=False) if not (worktree / ".git").exists() else run(
                ["git", "worktree", "remove", str(worktree)], cwd=args.source_root, counter=counter
            )
            batches.append(batch_summary(scanner, branch=branch))
            break
        if staged != size and not (ordinal == len(sizes) and deferred == 0):
            raise RunnerError(f"unexpected staged batch size: expected {size}, found {staged}")
        if not resume:
            candidate_queue = load_json(queue_path)
            validate_new_rows(baseline_queue, candidate_queue, staged)
            paths = changed_paths(worktree, counter)
            if set(paths) - ALLOWED_CHANGED_PATHS:
                raise RunnerError(f"unexpected changed paths: {paths}")
            candidate_readiness = validator_report(
                args.source_root / "scripts/agent_control/validate_task_queue_readiness.py",
                queue_path,
                evidence_dir / "candidate-readiness.json",
                source_root=args.source_root,
                counter=counter,
            )
            candidate_guard = validator_report(
                args.source_root / "scripts/agent_control/dispatcher_decision_guard.py",
                queue_path,
                evidence_dir / "candidate-dispatcher-guard.json",
                source_root=args.source_root,
                counter=counter,
            )
            compare_validator_baseline(baseline_readiness, candidate_readiness, "queue readiness")
            compare_validator_baseline(baseline_guard, candidate_guard, "dispatcher guard")
            run(["git", "add", "--", *sorted(ALLOWED_CHANGED_PATHS)], cwd=worktree, counter=counter)
            run(
                ["git", "commit", "-m", f"chore(hygiene): stage deterministic branch recovery batch {batch_number:02d}"],
                cwd=worktree,
                counter=counter,
                evidence_path=evidence_dir / "commit.json",
            )
            head_sha = run(["git", "rev-parse", "HEAD"], cwd=worktree, counter=counter).stdout.strip()
            phase = "committed"
            update_batch_state(
                state, state_path, branch,
                phase=phase, head_sha=head_sha, staged=staged, covered=covered, deferred=deferred,
            )
        if phase == "committed":
            run(
                ["git", "push", "-u", "origin", branch],
                cwd=worktree,
                counter=counter,
                evidence_path=evidence_dir / "push.json",
            )
            phase = "pushed"
            update_batch_state(state, state_path, branch, phase=phase)
        if phase == "pushed":
            pr_number = create_or_find_pr(args, branch, head_sha, staged, counter, evidence_dir)
            phase = "pr_open"
            update_batch_state(state, state_path, branch, phase=phase, pr=pr_number)
        if phase == "pr_open":
            gate = wait_merge_gate(args, pr_number, head_sha, counter, evidence_dir)
            if gate.get("state") != "MERGED":
                run(
                    [
                        "gh", "pr", "merge", str(pr_number), "--repo", args.repo,
                        "--merge", "--match-head-commit", head_sha,
                    ],
                    cwd=args.source_root,
                    counter=counter,
                    evidence_path=evidence_dir / "merge.json",
                )
            merged = run(
                [
                    "gh", "pr", "view", str(pr_number), "--repo", args.repo,
                    "--json", "state,headRefOid,mergeCommit",
                ],
                cwd=args.source_root,
                counter=counter,
            )
            merged_payload = json.loads(merged.stdout)
            if merged_payload.get("state") != "MERGED" or merged_payload.get("headRefOid") != head_sha:
                raise RunnerError("post-merge exact-head verification failed")
            merge_sha = str((merged_payload.get("mergeCommit") or {}).get("oid") or "")
            phase = "merged"
            update_batch_state(state, state_path, branch, phase=phase, merge_sha=merge_sha)
        else:
            merge_sha = str((state_entry or {}).get("merge_sha") or "")
        archive = archive_and_retire(args, branch, evidence_dir, counter)
        entry = batch_summary(
            scanner,
            branch=branch,
            head_sha=head_sha,
            pr_number=pr_number,
            merge_sha=merge_sha,
            archive=archive,
        )
        batches.append(entry)
        update_batch_state(state, state_path, branch, phase="completed", **entry)
        if deferred == 0:
            break

    reconciliation = reconcile_full_namespace(args, counter)
    usage_after = resource.getrusage(resource.RUSAGE_SELF) if resource else None
    cpu_seconds = None
    io_blocks = None
    if usage_before is not None and usage_after is not None:
        cpu_seconds = round(
            (usage_after.ru_utime + usage_after.ru_stime)
            - (usage_before.ru_utime + usage_before.ru_stime),
            3,
        )
        io_blocks = {
            "read": max(0, usage_after.ru_inblock - usage_before.ru_inblock),
            "write": max(0, usage_after.ru_oublock - usage_before.ru_oublock),
        }
    return {
        "schema_version": "1.0",
        "status": "completed",
        "mode": "apply" if args.apply else "dry_run",
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "cpu_seconds": cpu_seconds,
        "io_blocks": io_blocks,
        "operations": counter.payload(),
        "branches": {
            "staged": total_staged,
            "covered": total_covered,
            "deferred": total_deferred,
        },
        "batches": batches,
        "reconciliation": reconciliation,
        "model_tokens": 0,
        "platform": platform.system().lower(),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--worktree-root", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--repo", default="coindmit-cmyk/ai-project-agent")
    parser.add_argument("--archive-root", default="/srv/aistudio-hdd/AiStudioData/archive/git-branches")
    parser.add_argument("--archive-ssh-host")
    parser.add_argument("--branch-prefix", default="codex/cleanup/AGENT-BRANCH-RECOVERY-BATCH")
    parser.add_argument("--start-batch-number", type=int, default=3)
    parser.add_argument("--run-date", default=datetime.now().strftime("%Y%m%d"))
    parser.add_argument("--batch-sizes", type=lambda value: [int(x) for x in value.split(",")], default=[10, 10, 6])
    parser.add_argument("--max-batches", type=int, default=3)
    parser.add_argument("--max-branches", type=int, default=26)
    parser.add_argument("--max-archive-ref-migrations", type=int, default=1000)
    parser.add_argument("--max-safe-ref-retirements", type=int, default=1000)
    parser.add_argument("--stale-days", type=int, default=14)
    parser.add_argument("--codex-activity-dir", type=Path)
    parser.add_argument("--expected-codex-host", action="append", default=[])
    parser.add_argument("--evidence-cache", type=Path)
    parser.add_argument("--recovery-task-id-file", type=Path)
    parser.add_argument("--command-timeout-seconds", type=int, default=1800)
    parser.add_argument("--gate-timeout-seconds", type=int, default=1800)
    parser.add_argument("--lock-file", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--yes", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    args.source_root = args.source_root.expanduser().resolve()
    args.worktree_root = args.worktree_root.expanduser().resolve()
    args.evidence_root = args.evidence_root.expanduser().resolve()
    args.lock_file = (args.lock_file or (args.evidence_root / "runner.lock")).expanduser().resolve()
    if args.output:
        args.output = args.output.expanduser().resolve()
    if args.recovery_task_id_file:
        args.recovery_task_id_file = args.recovery_task_id_file.expanduser().resolve()
    if args.apply and not args.yes:
        parser.error("--apply requires explicit --yes")
    if args.max_batches < 1 or args.max_batches > 10:
        parser.error("--max-batches must be between 1 and 10")
    if args.max_branches < 1 or args.max_branches > 100:
        parser.error("--max-branches must be between 1 and 100")
    if not args.batch_sizes or any(size < 1 or size > 100 for size in args.batch_sizes):
        parser.error("--batch-sizes must contain positive bounded integers")
    return args


def main() -> int:
    args = parse_args()
    started = time.monotonic()
    try:
        with exclusive_lock(args.lock_file):
            report = execute(args)
        code = 0
    except (OSError, ValueError, RunnerError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
        report = {
            "schema_version": "1.0",
            "status": "blocked",
            "mode": "apply" if args.apply else "dry_run",
            "elapsed_seconds": round(time.monotonic() - started, 3),
            "reason": str(exc)[:800],
            "model_tokens": 0,
        }
        code = 2
    output = args.output or (args.evidence_root / "resource-report.json")
    write_json(output, report)
    print(json.dumps(report, ensure_ascii=False, indent=2 if args.json else None))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
