#!/usr/bin/env python3
"""Plan safe cleanup for temporary AiStudio project branches.

The default mode is dry-run. Apply modes are intentionally narrow and require
explicit flags plus ``--yes``.
"""

from __future__ import annotations

import argparse
import fnmatch
import io
import json
import re
import shutil
import subprocess
import tarfile
import time
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import legacy_task_manager_dir, task_manager_dir, task_reports_dir
import capability_preservation_check


DELTA_CONTAINMENT_SUFFIXES = {".json", ".md", ".toml", ".yaml", ".yml"}
PROTECTED_PATTERNS = (
    "main",
    "master",
    "develop",
    "release",
    "release/main",
    "release/*",
    "staging",
    "staging/*",
    "production",
    "production/*",
    "old",
    "old/**",
    "archive",
    "archive/**",
)
PROTECTION_MODES = ("default", "canonical-only")
CANONICAL_PROTECTED_BRANCHES = frozenset({"develop", "release/main"})
ACTIVE_TASK_STATUSES = {
    "planned",
    "todo",
    "ready",
    "worker_ready",
    "in_progress",
    "locked",
    "review",
    "integration",
    "integration_ready",
    "integration_requested",
    "finalization",
    "needs_human",
    "needs_worker_fix",
    "needs_dispatcher",
    "needs_architect",
    "needs_checks",
}
CLOSED_TASK_STATUSES = {"done", "completed", "finalized", "merged", "released", "archived", "closed"}
TERMINAL_TASK_STATUSES = CLOSED_TASK_STATUSES | {"stale_or_superseded", "duplicate_linked", "deprecated"}
NO_PRODUCT_PAYLOAD_INTEGRATION_STATUSES = {
    "closed_no_diff",
    "closed_coordination_only",
    "not_integrated_no_product_payload",
}
CURRENT_REPORT_MARKERS = (
    "handoff",
    "integration",
    "finalizer",
    "worker",
    "branch_cleanup",
    "pr_readiness",
)
COORDINATION_EVIDENCE_PATHS = (
    "AiStudio/Task_manager/",
    "docs/reports/worker/",
    "docs/reports/workers/",
    "docs/reports/integration/",
    "docs/plans/tasks/",
    "old/agent-runs/",
)
COORDINATION_EVIDENCE_EXACT_PATHS = {"CHANGELOG.md", "PROJECT_VERSION.json", "VERSION"}
EVIDENCE_ALGORITHM_VERSION = 1
DEFAULT_WORKER_STUCK_AFTER_SECONDS = 3600
WORKER_RUNNING_STATUSES = {"in_progress", "locked", "running", "worker_running"}
INTEGRATOR_WAITING_STATUSES = {
    "agent_done",
    "integration_handoff_ready",
    "integration_ready",
    "integration_requested",
    "review",
}
INTEGRATOR_WAITING_OWNERS = {"integrator", "auto_integrator", "auto-integrator"}
INTEGRATOR_WAITING_INTEGRATION_STATUSES = {
    "integration_handoff_ready",
    "integration_ready",
    "integration_requested",
    "needs_integrator_review",
    "pending",
    "pending_integration",
}


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def run_git(project_root: Path, args: list[str], check: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=str(project_root), text=True, capture_output=True, check=check)


def parse_git_date(value: str) -> datetime:
    raw = value.strip()
    if not raw:
        return datetime.fromtimestamp(0, timezone.utc)
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return datetime.fromtimestamp(0, timezone.utc)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def normalize_branch_name(name: str, remote: str = "origin") -> str:
    name = name.strip()
    if name.startswith(f"{remote}/"):
        return name[len(remote) + 1 :]
    if name.startswith("refs/heads/"):
        return name[len("refs/heads/") :]
    if name.startswith(f"refs/remotes/{remote}/"):
        return name[len(f"refs/remotes/{remote}/") :]
    return name


def branch_ref_for(name: str, kind: str, remote: str) -> str:
    if kind == "remote":
        return f"{remote}/{name}"
    return name


def safe_archive_name(name: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9._/-]+", "-", name.strip())
    sanitized = re.sub(r"/{2,}", "/", sanitized).strip("/._-")
    return sanitized or "branch"


def is_worker_like(name: str) -> bool:
    return any(
        name.startswith(prefix)
        for prefix in (
            "AiStudio/Agent/worker/",
            "remote/",
            "codex/auto-worker",
            "auto-worker",
        )
    )


def is_cleanup_like(name: str) -> bool:
    return any(token in name.lower() for token in ("/cleanup/", "cleanup-", "cleanup_", "post-migration"))


def contains_runtime_churn(paths: list[str]) -> bool:
    churn_markers = (
        "AiStudio/Task_manager/agent_locks.json",
        "AiStudio/Task_manager/process-logs/",
        "AiStudio/Task_manager/reports/",
        "docs/plans/agent_locks.json",
        "docs/plans/process-logs/",
        "docs/plans/reports/",
    )
    return any(any(path.startswith(marker) for marker in churn_markers) for path in paths)


@dataclass
class BranchRecord:
    name: str
    ref_kind: str
    ref: str
    sha: str
    last_commit_at: datetime
    author_date: datetime
    subject: str
    age_days: int
    merged_into_develop: bool = False
    merged_into_release_main: bool = False
    ahead_develop: int | None = None
    behind_develop: int | None = None
    ahead_release_main: int | None = None
    behind_release_main: int | None = None
    changed_paths: list[str] = field(default_factory=list)
    changed_paths_collected: bool = True


@dataclass
class Policy:
    stale_days: int
    archive_prefix: str
    protected_patterns: list[str]
    preserve_branches: list[str]
    high_value_branch_patterns: list[str] = field(default_factory=list)
    important_path_patterns: list[str] = field(default_factory=list)
    durable_evidence_path_patterns: list[str] = field(default_factory=list)
    worker_stuck_after_seconds: int = DEFAULT_WORKER_STUCK_AFTER_SECONDS
    protection_mode: str = "default"

    def is_protected(self, name: str) -> bool:
        if self.protection_mode == "canonical-only":
            return name in CANONICAL_PROTECTED_BRANCHES
        patterns = list(PROTECTED_PATTERNS) + self.protected_patterns + self.preserve_branches
        return any(fnmatch.fnmatchcase(name, pattern) for pattern in patterns)


def load_policy(
    project_root: Path,
    stale_days: int,
    archive_prefix: str,
    protection_mode: str = "default",
) -> Policy:
    if protection_mode not in PROTECTION_MODES:
        raise ValueError(f"unsupported protection mode: {protection_mode}")
    protected: list[str] = []
    preserve: list[str] = []
    high_value_branches: list[str] = []
    important_paths: list[str] = []
    durable_evidence_paths: list[str] = []
    worker_stuck_after_seconds = DEFAULT_WORKER_STUCK_AFTER_SECONDS
    for path in (
        Path(__file__).resolve().parents[2] / "templates" / "agent-control" / "branch_cleanup_policy.example.json",
        task_manager_dir(project_root) / "branch_cleanup_policy.json",
    ):
        data = load_json(path)
        if not isinstance(data, dict):
            continue
        protected.extend(str(item) for item in data.get("protected_patterns", []) if item)
        preserve.extend(str(item) for item in data.get("preserve_branches", []) if item)
        historical = data.get("historical_value") if isinstance(data.get("historical_value"), dict) else {}
        high_value_branches.extend(str(item) for item in historical.get("high_value_branch_patterns", []) if item)
        important_paths.extend(str(item) for item in historical.get("important_path_patterns", []) if item)
        durable_evidence_paths.extend(
            str(item) for item in historical.get("durable_evidence_path_patterns", []) if item
        )
        liveness = data.get("execution_liveness") if isinstance(data.get("execution_liveness"), dict) else {}
        configured_stuck_after = liveness.get("worker_stuck_after_seconds")
        if isinstance(configured_stuck_after, int) and not isinstance(configured_stuck_after, bool) and configured_stuck_after > 0:
            worker_stuck_after_seconds = configured_stuck_after
    return Policy(
        stale_days=stale_days,
        archive_prefix=archive_prefix,
        protected_patterns=list(dict.fromkeys(protected)),
        preserve_branches=list(dict.fromkeys(preserve)),
        high_value_branch_patterns=list(dict.fromkeys(high_value_branches)),
        important_path_patterns=list(dict.fromkeys(important_paths)),
        durable_evidence_path_patterns=list(dict.fromkeys(durable_evidence_paths)),
        worker_stuck_after_seconds=worker_stuck_after_seconds,
        protection_mode=protection_mode,
    )


def ref_exists(project_root: Path, ref: str) -> bool:
    return run_git(project_root, ["rev-parse", "--verify", "--quiet", ref]).returncode == 0


def merged_into(project_root: Path, branch_ref: str, base_ref: str) -> bool:
    if not ref_exists(project_root, base_ref):
        return False
    return run_git(project_root, ["merge-base", "--is-ancestor", branch_ref, base_ref]).returncode == 0


def ahead_behind(project_root: Path, branch_ref: str, base_ref: str) -> tuple[int | None, int | None]:
    if not ref_exists(project_root, base_ref):
        return None, None
    proc = run_git(project_root, ["rev-list", "--left-right", "--count", f"{base_ref}...{branch_ref}"])
    if proc.returncode != 0:
        return None, None
    parts = proc.stdout.strip().split()
    if len(parts) != 2:
        return None, None
    behind, ahead = int(parts[0]), int(parts[1])
    return ahead, behind


def changed_paths(project_root: Path, branch_ref: str, base_ref: str) -> list[str]:
    if not ref_exists(project_root, base_ref):
        return []
    proc = run_git(project_root, ["diff", "--name-only", f"{base_ref}...{branch_ref}"])
    if proc.returncode != 0:
        return []
    return [line.strip().replace("\\", "/") for line in proc.stdout.splitlines() if line.strip()]


def merged_branch_names(project_root: Path, refs_root: str, base_ref: str, remote: str) -> set[str]:
    """Return branches merged into base with one Git process per ref namespace."""
    if not ref_exists(project_root, base_ref):
        return set()
    proc = run_git(
        project_root,
        ["for-each-ref", refs_root, f"--merged={base_ref}", "--format=%(refname:short)"],
    )
    if proc.returncode != 0:
        return set()
    return {
        normalize_branch_name(line, remote)
        for line in proc.stdout.splitlines()
        if line.strip() and line.strip() not in {remote, f"{remote}/HEAD"}
    }


def collect_branches(
    project_root: Path,
    remote: str,
    base: str,
    release_base: str,
    include_local: bool,
    include_remote: bool,
    deep_metrics: bool = True,
) -> list[BranchRecord]:
    specs: list[tuple[str, str]] = []
    if include_local:
        specs.append(("local", "refs/heads"))
    if include_remote:
        specs.append(("remote", f"refs/remotes/{remote}"))
    now = utc_now()
    records: list[BranchRecord] = []
    seen: set[tuple[str, str]] = set()
    for kind, refs_root in specs:
        merged_develop = merged_branch_names(project_root, refs_root, base, remote)
        merged_release = merged_branch_names(project_root, refs_root, release_base, remote)
        proc = run_git(
            project_root,
            [
                "for-each-ref",
                refs_root,
                "--format=%(refname:short)|%(objectname)|%(committerdate:iso8601-strict)|%(authordate:iso8601-strict)|%(subject)",
                "--sort=-committerdate",
            ],
        )
        if proc.returncode != 0:
            continue
        for line in proc.stdout.splitlines():
            parts = line.split("|", 4)
            if len(parts) != 5:
                continue
            raw_name, sha, commit_date, author_date, subject = parts
            if raw_name in {remote, f"{remote}/HEAD"}:
                continue
            name = normalize_branch_name(raw_name, remote)
            key = (kind, name)
            if key in seen:
                continue
            seen.add(key)
            ref = branch_ref_for(name, kind, remote)
            last_at = parse_git_date(commit_date)
            rec = BranchRecord(
                name=name,
                ref_kind=kind,
                ref=ref,
                sha=sha,
                last_commit_at=last_at,
                author_date=parse_git_date(author_date),
                subject=subject,
                age_days=max(0, (now - last_at).days),
                changed_paths_collected=deep_metrics,
            )
            rec.merged_into_develop = name in merged_develop
            rec.merged_into_release_main = name in merged_release
            if deep_metrics:
                rec.ahead_develop, rec.behind_develop = ahead_behind(project_root, ref, base)
                rec.ahead_release_main, rec.behind_release_main = ahead_behind(project_root, ref, release_base)
                rec.changed_paths = changed_paths(project_root, ref, base)
            records.append(rec)
    return records


def github_pr_index(project_root: Path, remote: str) -> tuple[dict[str, dict[str, Any]], list[str], bool]:
    warnings: list[str] = []
    if shutil.which("gh") is None:
        return {}, ["gh not available; PR evidence is unknown."], False
    proc = subprocess.run(
        [
            "gh",
            "pr",
            "list",
            "--state",
            "all",
            "--limit",
            "500",
            "--json",
            "number,headRefName,state,isDraft,mergedAt,closedAt,url",
        ],
        cwd=str(project_root),
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        warnings.append(f"gh pr list failed: {proc.stderr.strip()}")
        return {}, warnings, False
    try:
        rows = json.loads(proc.stdout or "[]")
    except json.JSONDecodeError:
        return {}, ["gh pr list returned invalid JSON."], False
    index: dict[str, dict[str, Any]] = {}
    for row in rows if isinstance(rows, list) else []:
        if not isinstance(row, dict):
            continue
        head = str(row.get("headRefName") or "").strip()
        if not head:
            continue
        index[head] = {
            "number": row.get("number"),
            "state": row.get("state"),
            "is_draft": row.get("isDraft"),
            "merged_at": row.get("mergedAt"),
            "closed_at": row.get("closedAt"),
            "url": row.get("url"),
        }
    return index, warnings, True


def branch_values_from_task(task: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for key in ("branch", "github_branch", "source_branch", "pr_branch", "integration_branch"):
        value = task.get(key)
        if isinstance(value, str) and value.strip():
            values.append(value.strip())
    for key in ("branches", "source_branches"):
        value = task.get(key)
        if isinstance(value, list):
            values.extend(str(item).strip() for item in value if str(item).strip())
    return values


def add_reference(
    refs: dict[str, list[dict[str, Any]]],
    branch: str,
    kind: str,
    path: Path,
    detail: str = "",
    **metadata: Any,
) -> None:
    normalized = normalize_branch_name(branch)
    refs.setdefault(normalized, []).append(
        {"type": kind, "path": path.as_posix(), "detail": detail, **metadata}
    )


def scan_json_for_branches(path: Path, refs: dict[str, list[dict[str, Any]]]) -> None:
    data = load_json(path)
    if isinstance(data, dict) and isinstance(data.get("tasks"), list):
        for task in data["tasks"]:
            if not isinstance(task, dict):
                continue
            status = str(task.get("status") or "").lower()
            detail = str(task.get("id") or task.get("task_id") or "")
            is_recovery_task = bool(task.get("repository_recovery_key")) or task.get("type") == "repository_hygiene_branch_recovery"
            raw_lock = task.get("lock")
            lock = raw_lock if isinstance(raw_lock, dict) else {}
            lock_state = str(lock.get("state") or raw_lock or "free").lower()
            is_unclaimed_repair = bool(
                task.get("type") == "clean-rebuild"
                and status in {"planned", "todo", "ready", "worker_ready"}
                and lock_state in {"", "free", "released", "unlocked"}
            )
            if status in TERMINAL_TASK_STATUSES:
                kind = "task_queue_closed"
            elif is_recovery_task:
                kind = "task_queue_recovery"
            elif is_unclaimed_repair:
                kind = "task_queue_repair"
            else:
                kind = "task_queue_active"
            evidence_commits = [
                str(task.get(key) or "").strip()
                for key in ("merge_commit", "worker_result_commit", "commit", "head_sha", "integrated_commit")
                if str(task.get(key) or "").strip()
            ]
            merge_commits = [
                str(task.get(key) or "").strip()
                for key in ("merge_commit", "integrated_commit")
                if str(task.get(key) or "").strip()
            ]
            worker_result_commits = [
                str(task.get(key) or "").strip()
                for key in ("worker_result_commit", "commit", "head_sha")
                if str(task.get(key) or "").strip()
            ]
            worker_result_present = bool(
                worker_result_commits
                or str(task.get("worker_report") or "").strip()
                or str(task.get("last_agent_report") or "").strip()
            )
            for branch in branch_values_from_task(task):
                add_reference(
                    refs,
                    branch,
                    kind,
                    path,
                    detail,
                    task_id=detail,
                    task_status=status,
                    recovery_task=is_recovery_task,
                    unclaimed_repair_task=is_unclaimed_repair,
                    integration_status=str(task.get("integration_status") or "").strip(),
                    next_owner=str(task.get("next_owner") or task.get("next_role") or "").strip(),
                    lock_state=lock_state,
                    started_at=task.get("started_at"),
                    updated_at=task.get("updated_at"),
                    lock_expires_at=task.get("lock_expires_at"),
                    worker_result_synced_at=task.get("worker_result_synced_at"),
                    worker_result_present=worker_result_present,
                    evidence_commits=evidence_commits,
                    merge_commits=merge_commits,
                    worker_result_commits=worker_result_commits,
                )
    elif isinstance(data, dict) and isinstance(data.get("locks"), list):
        for lock in data["locks"]:
            if not isinstance(lock, dict):
                continue
            state = str(lock.get("state") or "").lower()
            branch = str(lock.get("branch") or "").strip()
            if branch:
                kind = "lock_active" if state in {"locked", "in_progress", "review", "active"} else "lock"
                add_reference(
                    refs,
                    branch,
                    kind,
                    path,
                    str(lock.get("task_id") or ""),
                    task_id=str(lock.get("task_id") or ""),
                    lock_state=state,
                    worker_id=str(lock.get("by") or ""),
                    locked_at=lock.get("at"),
                    expires_at=lock.get("expires_at"),
                    review_at=lock.get("review_at"),
                    released_at=lock.get("released_at"),
                )


def scan_jsonl_for_branches(path: Path, refs: dict[str, list[dict[str, Any]]]) -> None:
    try:
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return
    for line in lines[-5000:]:
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(item, dict):
            continue
        branch = str(item.get("branch") or item.get("source_branch") or "").strip()
        if branch:
            add_reference(refs, branch, "event", path, str(item.get("task_id") or item.get("event") or ""))


def scan_reports_for_branches(root: Path, branch_names: list[str], refs: dict[str, list[dict[str, Any]]]) -> None:
    if not root.exists():
        return
    names = sorted({branch for branch in branch_names if branch}, key=len, reverse=True)
    if not names:
        return
    matcher = re.compile("|".join(re.escape(branch) for branch in names))
    candidates: list[Path] = []
    for pattern in ("**/*.md", "**/*.json", "**/*.jsonl"):
        candidates.extend(root.glob(pattern))
    for path in candidates[-1000:]:
        lower_name = path.name.lower()
        marker = "report"
        if any(item in lower_name for item in CURRENT_REPORT_MARKERS):
            marker = "handoff" if "handoff" in lower_name else "report"
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for branch in set(matcher.findall(text)):
            add_reference(refs, branch, marker, path, "")


def active_worktree_branches(porcelain: str) -> list[str]:
    branches: list[str] = []
    for block in porcelain.split("\n\n"):
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        if any(line.startswith("prunable") for line in lines):
            continue
        branch_line = next((line for line in lines if line.startswith("branch ")), None)
        if not branch_line:
            continue
        branch = branch_line.removeprefix("branch ").strip()
        if branch.startswith("refs/heads/"):
            branch = branch.removeprefix("refs/heads/")
        if branch:
            branches.append(branch)
    return branches


def aistudio_reference_index(
    project_root: Path,
    branch_names: list[str],
    codex_activity: dict[str, Any] | None = None,
) -> dict[str, list[dict[str, Any]]]:
    refs: dict[str, list[dict[str, Any]]] = {}
    roots = [task_manager_dir(project_root), legacy_task_manager_dir(project_root)]
    for root in roots:
        if not root.exists():
            continue
        for name in ("task_queue.json", "agent_locks.json", "process_locks.json"):
            path = root / name
            if path.exists():
                scan_json_for_branches(path, refs)
        for path in (root.glob("*.jsonl")):
            scan_jsonl_for_branches(path, refs)
        scan_reports_for_branches(root / "reports", branch_names, refs)
        scan_reports_for_branches(root / "process-logs", branch_names, refs)
    worktrees = run_git(project_root, ["worktree", "list", "--porcelain"])
    if worktrees.returncode == 0:
        for branch in active_worktree_branches(worktrees.stdout):
            add_reference(refs, branch, "worktree_active", project_root, "git worktree")
    for host in (codex_activity or {}).get("hosts") or []:
        if not isinstance(host, dict) or not host.get("fresh"):
            continue
        host_id = str(host.get("host_id") or "unknown")
        for thread in host.get("active_threads") or []:
            if not isinstance(thread, dict):
                continue
            thread_id = str(thread.get("thread_id") or "")
            for branch in thread.get("branches") or []:
                if str(branch).strip():
                    add_reference(
                        refs,
                        str(branch),
                        "codex_thread_active",
                        Path(str(host.get("snapshot_path") or project_root)),
                        thread_id,
                        host_id=host_id,
                        thread_id=thread_id,
                    )
    return refs


def has_active_reference(references: list[dict[str, Any]]) -> bool:
    return any(
        ref.get("type") in {"task_queue_active", "lock_active", "handoff", "worktree_active", "codex_thread_active"}
        for ref in references
    )


def parse_iso(value: Any) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def matches_policy_pattern(value: str, pattern: str) -> bool:
    normalized_value = value.strip().replace("\\", "/")
    normalized_pattern = pattern.strip().replace("\\", "/")
    if not normalized_value or not normalized_pattern:
        return False
    return fnmatch.fnmatchcase(normalized_value, normalized_pattern) or fnmatch.fnmatchcase(
        normalized_value,
        f"*/{normalized_pattern.lstrip('/')}",
    )


def classify_historical_value(
    record: BranchRecord,
    policy: Policy,
    references: list[dict[str, Any]],
) -> dict[str, Any]:
    branch_patterns = sorted(
        pattern
        for pattern in policy.high_value_branch_patterns
        if matches_policy_pattern(record.name, pattern)
    )
    important_paths = sorted(
        path
        for path in record.changed_paths
        if any(matches_policy_pattern(path, pattern) for pattern in policy.important_path_patterns)
    )
    durable_references = [
        {
            "type": str(reference.get("type") or ""),
            "path": str(reference.get("path") or ""),
            "detail": str(reference.get("detail") or ""),
        }
        for reference in references
        if any(
            matches_policy_pattern(str(reference.get("path") or ""), pattern)
            for pattern in policy.durable_evidence_path_patterns
        )
    ]
    signals: list[str] = []
    if branch_patterns:
        signals.append("high_value_branch_pattern")
    if important_paths:
        signals.append("important_path")
    if durable_references:
        signals.append("durable_evidence_reference")
    if signals:
        classification = "high"
    elif record.changed_paths_collected:
        classification = "low"
        signals.append("no_high_value_policy_match")
    else:
        classification = "unknown"
        signals.append("changed_path_evidence_not_collected")
    return {
        "classification": classification,
        "evidence_complete": record.changed_paths_collected,
        "signals": signals,
        "matched_branch_patterns": branch_patterns,
        "important_paths": important_paths,
        "durable_references": durable_references,
    }


def reference_summary(reference: dict[str, Any]) -> dict[str, Any]:
    return {
        key: reference.get(key)
        for key in (
            "type",
            "task_id",
            "task_status",
            "lock_state",
            "next_owner",
            "integration_status",
            "started_at",
            "updated_at",
            "locked_at",
            "lock_expires_at",
            "expires_at",
            "worker_result_synced_at",
            "worker_result_present",
            "host_id",
            "thread_id",
            "path",
            "detail",
        )
        if reference.get(key) not in (None, "")
    }


def integrator_waiting_reference(reference: dict[str, Any]) -> bool:
    if reference.get("type") != "task_queue_active":
        return False
    status = str(reference.get("task_status") or "").lower()
    next_owner = str(reference.get("next_owner") or "").lower()
    integration_status = str(reference.get("integration_status") or "").lower()
    worker_result_present = bool(reference.get("worker_result_present"))
    if next_owner and next_owner not in INTEGRATOR_WAITING_OWNERS:
        return False
    if status in INTEGRATOR_WAITING_STATUSES and (worker_result_present or status != "review"):
        return True
    if worker_result_present and next_owner in INTEGRATOR_WAITING_OWNERS:
        return True
    return worker_result_present and integration_status in INTEGRATOR_WAITING_INTEGRATION_STATUSES


def worker_reference_expiry(reference: dict[str, Any]) -> datetime | None:
    return parse_iso(reference.get("lock_expires_at") or reference.get("expires_at"))


def worker_reference_activity(reference: dict[str, Any]) -> datetime | None:
    values = [
        parse_iso(reference.get(key))
        for key in (
            "worker_result_synced_at",
            "updated_at",
            "review_at",
            "started_at",
            "locked_at",
        )
    ]
    timestamps = [value for value in values if value is not None]
    return max(timestamps) if timestamps else None


def classify_execution_liveness(
    references: list[dict[str, Any]],
    policy: Policy,
) -> dict[str, Any]:
    now = utc_now()
    integrator_waiting = [reference for reference in references if integrator_waiting_reference(reference)]
    if integrator_waiting:
        return {
            "classification": "integrator_waiting",
            "signals": [reference_summary(reference) for reference in integrator_waiting],
            "stuck_after_seconds": policy.worker_stuck_after_seconds,
        }

    fresh_worker_signals: list[dict[str, Any]] = []
    worker_signals: list[dict[str, Any]] = []
    stale_worker_signals: list[dict[str, Any]] = []
    for reference in references:
        ref_type = str(reference.get("type") or "")
        status = str(reference.get("task_status") or "").lower()
        is_worker_signal = ref_type == "lock_active" or (
            ref_type == "task_queue_active" and status in WORKER_RUNNING_STATUSES
        )
        if ref_type in {"worktree_active", "codex_thread_active"}:
            fresh_worker_signals.append(reference)
            continue
        if not is_worker_signal:
            continue
        worker_signals.append(reference)
        expiry = worker_reference_expiry(reference)
        if expiry and expiry > now:
            fresh_worker_signals.append(reference)
            continue
        signal_at = expiry or worker_reference_activity(reference)
        if signal_at and (now - signal_at).total_seconds() >= policy.worker_stuck_after_seconds:
            stale_worker_signals.append(reference)

    if fresh_worker_signals:
        return {
            "classification": "worker_active",
            "signals": [reference_summary(reference) for reference in fresh_worker_signals],
            "stuck_after_seconds": policy.worker_stuck_after_seconds,
        }
    if stale_worker_signals:
        return {
            "classification": "worker_stuck",
            "signals": [reference_summary(reference) for reference in stale_worker_signals],
            "stuck_after_seconds": policy.worker_stuck_after_seconds,
        }
    if worker_signals:
        return {
            "classification": "worker_signal_stale",
            "signals": [reference_summary(reference) for reference in worker_signals],
            "stuck_after_seconds": policy.worker_stuck_after_seconds,
        }
    if has_active_reference(references):
        return {
            "classification": "referenced",
            "signals": [reference_summary(reference) for reference in references if reference_summary(reference)],
            "stuck_after_seconds": policy.worker_stuck_after_seconds,
        }
    return {
        "classification": "inactive",
        "signals": [],
        "stuck_after_seconds": policy.worker_stuck_after_seconds,
    }


def capability_state(record: BranchRecord, integration_evidence: dict[str, Any]) -> str:
    if record.merged_into_develop or record.merged_into_release_main:
        return "preserved"
    if integration_evidence.get("status") == "integrated":
        return "preserved"
    if record.ahead_develop is None:
        return "unproven"
    return "unique" if record.ahead_develop > 0 else "none"


def load_codex_activity_inventory(
    activity_dir: Path | None,
    expected_hosts: list[str],
    max_age_seconds: int,
) -> dict[str, Any]:
    now = utc_now()
    hosts: list[dict[str, Any]] = []
    warnings: list[str] = []
    if activity_dir and activity_dir.exists():
        for path in sorted(activity_dir.glob("*.json")):
            payload = load_json(path)
            if not isinstance(payload, dict) or int(payload.get("schema_version") or 0) != 1:
                warnings.append(f"invalid_codex_activity_snapshot:{path.name}")
                continue
            host_id = str(payload.get("host_id") or path.stem).strip()
            generated_at = parse_iso(payload.get("generated_at"))
            expires_at = parse_iso(payload.get("expires_at"))
            age_seconds = int((now - generated_at).total_seconds()) if generated_at else None
            fresh = bool(
                generated_at
                and expires_at
                and generated_at <= now
                and expires_at >= now
                and age_seconds is not None
                and age_seconds <= max_age_seconds
            )
            hosts.append(
                {
                    "host_id": host_id,
                    "snapshot_path": str(path),
                    "generated_at": payload.get("generated_at"),
                    "expires_at": payload.get("expires_at"),
                    "age_seconds": age_seconds,
                    "fresh": fresh,
                    "active_thread_count": int(payload.get("active_thread_count") or 0),
                    "active_threads": payload.get("active_threads") if isinstance(payload.get("active_threads"), list) else [],
                    "protected_branches": payload.get("protected_branches") if isinstance(payload.get("protected_branches"), list) else [],
                }
            )
    elif activity_dir:
        warnings.append(f"codex_activity_dir_missing:{activity_dir}")
    else:
        warnings.append("codex_activity_dir_not_configured")
    expected = sorted({str(item).strip() for item in expected_hosts if str(item).strip()})
    fresh_hosts = sorted({str(host.get("host_id")) for host in hosts if host.get("fresh")})
    missing_hosts = sorted(set(expected) - set(fresh_hosts))
    coverage_complete = bool(expected) and not missing_hosts
    if not expected:
        warnings.append("expected_codex_hosts_not_configured")
    if missing_hosts:
        warnings.append("missing_or_stale_codex_hosts:" + ",".join(missing_hosts))
    return {
        "activity_dir": str(activity_dir) if activity_dir else None,
        "expected_hosts": expected,
        "fresh_hosts": fresh_hosts,
        "missing_hosts": missing_hosts,
        "coverage_complete": coverage_complete,
        "max_age_seconds": max_age_seconds,
        "hosts": hosts,
        "warnings": warnings,
    }


def terminal_references(references: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [ref for ref in references if ref.get("type") == "task_queue_closed"]


def patch_equivalence(project_root: Path, branch_ref: str, base_ref: str) -> dict[str, Any]:
    proc = run_git(project_root, ["cherry", base_ref, branch_ref])
    if proc.returncode != 0:
        return {"checked": False, "equivalent": False, "error": proc.stderr.strip()}
    rows = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    plus = [line[2:].strip() for line in rows if line.startswith("+")]
    minus = [line[2:].strip() for line in rows if line.startswith("-")]
    return {
        "checked": True,
        "equivalent": bool(rows) and not plus,
        "unique_commit_count": len(plus),
        "equivalent_commit_count": len(minus),
        "unique_commit_sample": plus[:20],
    }


def capability_equivalence(
    project_root: Path,
    branch_ref: str,
    base_ref: str,
    *,
    max_paths: int = 30,
) -> dict[str, Any]:
    all_paths = changed_paths(project_root, branch_ref, base_ref)
    if not all_paths:
        return {"checked": False, "equivalent": False, "reason": "no_changed_paths"}
    paths = [
        path
        for path in all_paths
        if path not in COORDINATION_EVIDENCE_EXACT_PATHS
        and not any(path.startswith(prefix) for prefix in COORDINATION_EVIDENCE_PATHS)
    ]
    ignored_path_count = len(all_paths) - len(paths)
    if not paths:
        return {
            "checked": True,
            "equivalent": True,
            "status": "coordination_evidence_only",
            "path_count": 0,
            "ignored_path_count": ignored_path_count,
            "risk_path_count": 0,
            "warning_path_count": 0,
            "removed_capability_count": 0,
            "policy_findings": [],
        }
    if len(paths) > max_paths:
        return {
            "checked": False,
            "equivalent": False,
            "reason": "path_limit_exceeded",
            "path_count": len(paths),
            "ignored_path_count": ignored_path_count,
            "max_paths": max_paths,
        }
    try:
        before = git_archive_texts(project_root, branch_ref, paths)
        after = git_archive_texts(project_root, base_ref, paths)
        reports = [
            capability_preservation_check.compare_text(
                before.get(path, ""),
                after.get(path, ""),
                path_hint=path,
            )
            for path in paths
        ]
    except (OSError, subprocess.SubprocessError, tarfile.TarError) as exc:
        return {"checked": False, "equivalent": False, "reason": "checker_failed", "error": str(exc)}
    risks = [item for item in reports if item.get("status") == capability_preservation_check.STATUS_RISK]
    warnings = [item for item in reports if item.get("status") == capability_preservation_check.STATUS_WARNING]
    status = (
        capability_preservation_check.STATUS_RISK
        if risks
        else capability_preservation_check.STATUS_WARNING
        if warnings
        else capability_preservation_check.STATUS_OK
    )
    result = {
        "checked": True,
        "equivalent": status == capability_preservation_check.STATUS_OK,
        "status": status,
        "path_count": len(paths),
        "ignored_path_count": ignored_path_count,
        "risk_path_count": len(risks),
        "warning_path_count": len(warnings),
        "removed_capability_count": sum(len(item.get("removed_capabilities") or []) for item in reports),
        "policy_findings": sorted({finding for item in reports for finding in item.get("policy_findings") or []}),
    }
    if result["equivalent"]:
        return result

    delta = delta_containment_equivalence(project_root, branch_ref, base_ref, paths)
    if delta.get("equivalent"):
        return {
            **result,
            "equivalent": True,
            "status": "delta_contained_in_base",
            "exact_comparison_status": status,
            "delta_containment": delta,
        }
    result["delta_containment"] = delta
    return result


def normalized_nonblank_counts(text: str, *, json_mode: bool = False) -> Counter[str]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if json_mode:
        lines = [line[:-1].rstrip() if line.endswith(",") else line for line in lines]
    return Counter(lines)


def text_delta_containment(
    before: str,
    candidate: str,
    current: str,
    *,
    json_mode: bool = False,
) -> dict[str, Any]:
    """Prove that a candidate's normalized line delta survives in current text.

    Added lines must remain at least as numerous as in the candidate. Lines
    removed by the candidate must not be restored above the candidate count.
    This deliberately ignores unchanged context so a later document/config
    superset can preserve an older branch without requiring byte equality.
    """

    before_counts = normalized_nonblank_counts(before, json_mode=json_mode)
    candidate_counts = normalized_nonblank_counts(candidate, json_mode=json_mode)
    current_counts = normalized_nonblank_counts(current, json_mode=json_mode)
    added = candidate_counts - before_counts
    removed = before_counts - candidate_counts
    if not added and not removed:
        return {
            "equivalent": False,
            "reason": "no_normalized_line_delta",
            "added_line_count": 0,
            "removed_line_count": 0,
        }

    missing_added = {
        line: candidate_counts[line] - current_counts[line]
        for line in added
        if current_counts[line] < candidate_counts[line]
    }
    restored_removed = {
        line: current_counts[line] - candidate_counts[line]
        for line in removed
        if current_counts[line] > candidate_counts[line]
    }
    return {
        "equivalent": not missing_added and not restored_removed,
        "added_line_count": sum(added.values()),
        "removed_line_count": sum(removed.values()),
        "missing_added_line_count": sum(missing_added.values()),
        "restored_removed_line_count": sum(restored_removed.values()),
    }


def delta_containment_equivalence(
    project_root: Path,
    branch_ref: str,
    base_ref: str,
    paths: list[str],
) -> dict[str, Any]:
    """Fail-closed fallback for evolved documentation and data contracts."""

    unsupported = sorted(path for path in paths if Path(path).suffix.lower() not in DELTA_CONTAINMENT_SUFFIXES)
    if unsupported:
        return {
            "checked": False,
            "equivalent": False,
            "reason": "unsupported_path_type",
            "unsupported_paths": unsupported,
        }

    merge_base = run_git(project_root, ["merge-base", branch_ref, base_ref])
    if merge_base.returncode != 0 or not merge_base.stdout.strip():
        return {
            "checked": False,
            "equivalent": False,
            "reason": "merge_base_unavailable",
            "error": merge_base.stderr.strip(),
        }
    merge_base_ref = merge_base.stdout.strip().splitlines()[0]
    try:
        before = git_archive_texts(project_root, merge_base_ref, paths)
        candidate = git_archive_texts(project_root, branch_ref, paths)
        current = git_archive_texts(project_root, base_ref, paths)
    except (OSError, subprocess.SubprocessError, tarfile.TarError) as exc:
        return {"checked": False, "equivalent": False, "reason": "delta_checker_failed", "error": str(exc)}

    path_reports = []
    for path in paths:
        report = text_delta_containment(
            before.get(path, ""),
            candidate.get(path, ""),
            current.get(path, ""),
            json_mode=Path(path).suffix.lower() == ".json",
        )
        path_reports.append({"path": path, **report})
    equivalent = bool(path_reports) and all(report["equivalent"] for report in path_reports)
    return {
        "checked": True,
        "equivalent": equivalent,
        "status": "delta_contained_in_base" if equivalent else "delta_not_contained_in_base",
        "merge_base": merge_base_ref,
        "path_count": len(path_reports),
        "path_reports": path_reports,
    }


def git_archive_texts(project_root: Path, ref: str, paths: list[str]) -> dict[str, str]:
    tree = run_git(project_root, ["ls-tree", "-r", "--name-only", ref, "--", *paths])
    if tree.returncode != 0:
        raise subprocess.SubprocessError(tree.stderr.strip() or f"git ls-tree failed for {ref}")
    existing = [line.strip().replace("\\", "/") for line in tree.stdout.splitlines() if line.strip()]
    if not existing:
        return {}
    archive = subprocess.run(
        ["git", "archive", "--format=tar", ref, "--", *existing],
        cwd=str(project_root),
        capture_output=True,
    )
    if archive.returncode != 0:
        raise subprocess.SubprocessError(archive.stderr.decode("utf-8", errors="ignore").strip() or f"git archive failed for {ref}")
    result: dict[str, str] = {}
    with tarfile.open(fileobj=io.BytesIO(archive.stdout), mode="r:") as bundle:
        for member in bundle.getmembers():
            if not member.isfile():
                continue
            handle = bundle.extractfile(member)
            if handle is not None:
                result[member.name.replace("\\", "/")] = handle.read().decode("utf-8", errors="ignore")
    return result


def terminal_integration_evidence(
    project_root: Path,
    record: BranchRecord,
    base_ref: str,
    references: list[dict[str, Any]],
) -> dict[str, Any]:
    terminal = terminal_references(references)
    if record.merged_into_develop or record.merged_into_release_main:
        return {"status": "integrated", "proof": "branch_tip_ancestor", "terminal_task_ids": [ref.get("task_id") for ref in terminal]}
    patch = patch_equivalence(project_root, record.ref, base_ref)
    if patch.get("equivalent"):
        return {"status": "integrated", "proof": "patch_equivalent", "patch": patch, "terminal_task_ids": [ref.get("task_id") for ref in terminal]}
    no_payload_claims = [
        ref for ref in terminal
        if str(ref.get("integration_status") or "") in NO_PRODUCT_PAYLOAD_INTEGRATION_STATUSES
    ]
    evidence_commits = sorted({
        str(commit)
        for ref in terminal
        for commit in (ref.get("evidence_commits") or [])
        if str(commit)
    })
    reachable_commits = [
        commit
        for commit in evidence_commits
        if run_git(project_root, ["merge-base", "--is-ancestor", commit, base_ref]).returncode == 0
    ]
    merge_commits = sorted({
        str(commit)
        for ref in terminal
        for commit in (ref.get("merge_commits") or [])
        if str(commit)
    })
    reachable_merge_commits = [
        commit
        for commit in merge_commits
        if run_git(project_root, ["merge-base", "--is-ancestor", commit, base_ref]).returncode == 0
    ]
    if reachable_merge_commits:
        return {
            "status": "integrated",
            "proof": "recorded_merge_commit_ancestor",
            "patch": patch,
            "terminal_task_ids": sorted({str(ref.get("task_id") or "") for ref in terminal if ref.get("task_id")}),
            "reachable_merge_commits": reachable_merge_commits,
        }
    if no_payload_claims:
        return {
            "status": "integrated",
            "proof": "terminal_no_product_payload_disposition",
            "patch": patch,
            "terminal_task_ids": sorted({str(ref.get("task_id") or "") for ref in terminal if ref.get("task_id")}),
            "integration_statuses": sorted({str(ref.get("integration_status") or "") for ref in no_payload_claims}),
        }
    capability = capability_equivalence(project_root, record.ref, base_ref) if terminal else {
        "checked": False,
        "equivalent": False,
        "reason": "no_terminal_task_reference",
    }
    if terminal and capability.get("equivalent"):
        return {
            "status": "integrated",
            "proof": "capability_preserved_in_base",
            "patch": patch,
            "capability": capability,
            "terminal_task_ids": sorted({str(ref.get("task_id") or "") for ref in terminal if ref.get("task_id")}),
        }
    if terminal:
        return {
            "status": "needs_reconciliation",
            "proof": "terminal_task_without_branch_tip_equivalence",
            "patch": patch,
            "terminal_task_ids": sorted({str(ref.get("task_id") or "") for ref in terminal if ref.get("task_id")}),
            "integration_statuses": sorted({str(ref.get("integration_status") or "") for ref in terminal if ref.get("integration_status")}),
            "reachable_evidence_commits": reachable_commits,
            "no_product_payload_claim_count": len(no_payload_claims),
            "capability": capability,
        }
    return {"status": "untracked", "proof": "no_terminal_task_reference", "patch": patch, "capability": capability}


def archive_command(record: BranchRecord, remote: str, archive_prefix: str) -> str:
    target = archive_target_name(record.name, archive_prefix)
    return f"git push {remote} {record.sha}:refs/heads/{target}"


def archive_target_name(branch_name: str, archive_prefix: str) -> str:
    date = utc_now().strftime("%Y-%m-%d")
    return f"{archive_prefix.rstrip('/')}/{date}/{safe_archive_name(branch_name)}"


def delete_command(record: BranchRecord, remote: str) -> str:
    if record.ref_kind == "remote":
        lease = f"--force-with-lease=refs/heads/{record.name}:{record.sha}"
        return f"git push {lease} {remote} --delete {record.name}"
    return f"git update-ref -d refs/heads/{record.name} {record.sha}"


def classify_branch(
    record: BranchRecord,
    policy: Policy,
    pr: dict[str, Any] | None,
    pr_evidence_available: bool,
    references: list[dict[str, Any]],
    remote: str,
    integration_evidence: dict[str, Any] | None = None,
) -> dict[str, Any]:
    merged = record.merged_into_develop or record.merged_into_release_main
    protected = policy.is_protected(record.name)
    stale = record.age_days >= policy.stale_days
    open_pr = bool(pr and pr.get("state") == "OPEN")
    closed_unmerged_pr = bool(pr and pr.get("state") == "CLOSED" and not pr.get("merged_at"))
    active_ref = has_active_reference(references)
    evidence = integration_evidence or {}
    historical = classify_historical_value(record, policy, references)
    liveness = classify_execution_liveness(references, policy)
    branch_capability_state = capability_state(record, evidence)
    commands: list[str] = []

    if protected:
        classification, action, confidence, reason = "keep_active", "keep", "high", "protected branch"
    elif open_pr:
        classification, action, confidence, reason = "keep_active", "keep", "high", "open PR"
    elif liveness["classification"] == "worker_active":
        classification, action, confidence, reason = "keep_active", "keep", "high", "fresh Worker execution evidence"
    elif liveness["classification"] == "worker_stuck":
        classification, action, confidence, reason = (
            "keep_active",
            "diagnose_worker_stuck",
            "high",
            "Worker execution signal exceeded the configured stuck threshold",
        )
    elif liveness["classification"] == "integrator_waiting":
        classification, action, confidence, reason = (
            "keep_active",
            "route_integrator",
            "high",
            "completed Worker result is waiting for Integrator",
        )
    elif active_ref:
        classification, action, confidence, reason = "keep_active", "keep", "high", "active AiStudio reference"
    elif not stale and not merged:
        classification, action, confidence, reason = "keep_active", "keep", "medium", "young unmerged branch"
    elif merged and not stale:
        classification, action, confidence, reason = "cleanup_candidate", "wait_until_stale", "high", "merged branch inside stale grace window"
    elif merged and stale and not record.name.startswith("old/"):
        if historical["classification"] == "high":
            classification, action, confidence, reason = (
                "archive_candidate",
                "archive",
                "high",
                "merged and stale with high historical value evidence",
            )
            commands.append(archive_command(record, remote, policy.archive_prefix))
        elif historical["classification"] == "low":
            classification = "cleanup_candidate" if is_cleanup_like(record.name) else "merged_safe_delete"
            action, confidence, reason = "delete", "high", "merged, stale, and policy-classified low historical value"
            commands.append(delete_command(record, remote))
        else:
            classification, action, confidence, reason = (
                "unknown_needs_review",
                "review",
                "low",
                "merged branch historical value is unknown because changed-path evidence was not collected",
            )
    elif evidence.get("status") == "integrated":
        classification, action, confidence, reason = "archive_candidate", "archive", "high", str(evidence.get("proof") or "integration evidence")
        commands.append(archive_command(record, remote, policy.archive_prefix))
    elif is_worker_like(record.name) and not merged and not terminal_references(references):
        classification, action, confidence = "dirty_worker_candidate", "clean_rebuild_review", "medium"
        reason = "unmerged worker-like branch"
        if contains_runtime_churn(record.changed_paths):
            reason += " with runtime/task-manager churn"
        commands.append(archive_command(record, remote, policy.archive_prefix))
    elif not pr_evidence_available and not merged:
        classification, action, confidence, reason = "unknown_needs_review", "review", "low", "PR metadata unavailable for unmerged branch"
    elif evidence.get("status") in {"needs_reconciliation", "untracked"}:
        classification, action, confidence = "integration_recovery_candidate", "route_integration_recovery", "high"
        if evidence.get("status") == "needs_reconciliation":
            reason = "terminal task branch lacks branch-tip integration or patch-equivalence evidence"
        elif closed_unmerged_pr:
            reason = "closed unmerged PR branch still has unique commits"
        else:
            reason = "old unmerged branch has unique commits and no terminal task reference"
    else:
        classification, action, confidence, reason = "unknown_needs_review", "review", "low", "insufficient or contradictory evidence"

    return {
        "name": record.name,
        "ref_kind": record.ref_kind,
        "sha": record.sha,
        "last_commit_at": iso(record.last_commit_at),
        "age_days": record.age_days,
        "merged_into_develop": record.merged_into_develop,
        "merged_into_release_main": record.merged_into_release_main,
        "ahead_develop": record.ahead_develop,
        "behind_develop": record.behind_develop,
        "ahead_release_main": record.ahead_release_main,
        "behind_release_main": record.behind_release_main,
        "open_pr": pr if open_pr else None,
        "pr": pr,
        "referenced_by": references,
        "integration_evidence": evidence,
        "historical_value": historical["classification"],
        "historical_value_evidence": historical,
        "execution_liveness": liveness["classification"],
        "execution_liveness_evidence": liveness,
        "capability_state": branch_capability_state,
        "changed_paths_count": len(record.changed_paths),
        "changed_paths_collected": record.changed_paths_collected,
        "classification": classification,
        "confidence": confidence,
        "reason": reason,
        "recommended_action": action,
        "candidate_commands": commands,
    }


def default_output_paths(project_root: Path, timestamp: str) -> tuple[Path, Path, Path]:
    reports = task_reports_dir(project_root)
    return (
        reports / f"BRANCH_CLEANUP_PLAN_{timestamp}.json",
        reports / f"BRANCH_CLEANUP_PLAN_{timestamp}.md",
        reports / f"BRANCH_CLEANUP_COMMANDS_{timestamp}.ps1",
    )


def write_json_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_markdown(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Branch Cleanup Plan",
        "",
        f"Generated: `{report['generated_at']}`",
        f"Project: `{report['project_root']}`",
        "",
        "## Summary",
        "",
    ]
    for key, value in report["counts"].items():
        lines.append(f"- {key}: `{value}`")
    sections = [
        ("merged_safe_delete", "Safe Delete Candidates"),
        ("archive_candidate", "Archive Candidates"),
        ("integration_recovery_candidate", "Integration Recovery Candidates"),
        ("dirty_worker_candidate", "Dirty Worker Candidates"),
        ("cleanup_candidate", "Cleanup Candidates"),
        ("unknown_needs_review", "Unknown Review Items"),
        ("keep_active", "Active Branches Kept"),
    ]
    for classification, title in sections:
        items = [b for b in report["branches"] if b["classification"] == classification]
        lines.extend(["", f"## {title}", ""])
        if not items:
            lines.append("_None._")
            continue
        for item in items:
            lines.append(
                f"- `{item['name']}` `{item['sha'][:12]}` age={item['age_days']}d "
                f"history=`{item['historical_value']}` liveness=`{item['execution_liveness']}` "
                f"action=`{item['recommended_action']}` reason={item['reason']}"
            )
    if report.get("warnings"):
        lines.extend(["", "## Warnings", ""])
        lines.extend(f"- {warning}" for warning in report["warnings"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_commands(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Branch cleanup candidate commands",
        "# Review before running. The planner does not execute these in dry-run mode.",
        "",
    ]
    for item in report["branches"]:
        for command in item.get("candidate_commands", []):
            lines.append(f"# {item['classification']}: {item['name']}")
            lines.append(command)
            lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def require_fresh_activity_coverage(report: dict[str, Any], action: str) -> None:
    inventory = report.get("codex_activity") if isinstance(report.get("codex_activity"), dict) else {}
    if not bool(inventory.get("coverage_complete")):
        raise SystemExit(f"refusing {action}: Codex activity coverage is incomplete or stale")
    expected_hosts = [str(item) for item in inventory.get("expected_hosts") or [] if str(item)]
    if not expected_hosts:
        return
    now = utc_now()
    max_age_seconds = max(1, int(inventory.get("max_age_seconds") or 300))
    hosts = {
        str(host.get("host_id")): host
        for host in inventory.get("hosts") or []
        if isinstance(host, dict) and host.get("host_id")
    }
    stale_hosts: list[str] = []
    for host_id in expected_hosts:
        host = hosts.get(host_id) or {}
        generated_at = parse_iso(host.get("generated_at"))
        expires_at = parse_iso(host.get("expires_at"))
        fresh = bool(
            host.get("fresh")
            and generated_at
            and expires_at
            and generated_at <= now <= expires_at
            and (now - generated_at).total_seconds() <= max_age_seconds
        )
        if not fresh:
            stale_hosts.append(host_id)
    if stale_hosts:
        raise SystemExit(
            f"refusing {action}: Codex activity coverage became stale for hosts: {','.join(sorted(stale_hosts))}"
        )


def verify_source_sha(
    project_root: Path,
    item: dict[str, Any],
    remote: str,
) -> dict[str, Any]:
    name = str(item.get("name") or "").strip()
    expected_sha = str(item.get("sha") or "").strip().lower()
    if not name or not re.fullmatch(r"[0-9a-f]{40,64}", expected_sha):
        return {
            "verified": False,
            "expected_sha": expected_sha,
            "current_sha": None,
            "reason": "missing_or_invalid_source_identity",
        }
    if item.get("ref_kind") == "remote":
        proc = run_git(project_root, ["ls-remote", "--heads", remote, f"refs/heads/{name}"])
        current_sha = (proc.stdout.strip().split() or [""])[0].lower() if proc.returncode == 0 else ""
        return {
            "verified": proc.returncode == 0 and current_sha == expected_sha,
            "expected_sha": expected_sha,
            "current_sha": current_sha or None,
            "reason": "exact_remote_sha" if current_sha == expected_sha else "source_sha_mismatch",
            "returncode": proc.returncode,
            "stderr": proc.stderr,
        }
    proc = run_git(project_root, ["rev-parse", "--verify", f"refs/heads/{name}"])
    current_sha = proc.stdout.strip().splitlines()[0].lower() if proc.returncode == 0 and proc.stdout.strip() else ""
    return {
        "verified": proc.returncode == 0 and current_sha == expected_sha,
        "expected_sha": expected_sha,
        "current_sha": current_sha or None,
        "reason": "exact_local_sha" if current_sha == expected_sha else "source_sha_mismatch",
        "returncode": proc.returncode,
        "stderr": proc.stderr,
    }


def guarded_delete_args(item: dict[str, Any], remote: str) -> list[str]:
    name = str(item["name"])
    sha = str(item["sha"])
    if item.get("ref_kind") == "remote":
        return [
            "push",
            f"--force-with-lease=refs/heads/{name}:{sha}",
            remote,
            "--delete",
            name,
        ]
    return ["update-ref", "-d", f"refs/heads/{name}", sha]


def apply_delete(project_root: Path, report: dict[str, Any], max_delete_count: int) -> list[dict[str, Any]]:
    require_fresh_activity_coverage(report, "destructive cleanup")
    candidates = [b for b in report["branches"] if b["classification"] == "merged_safe_delete"]
    if len(candidates) > max_delete_count:
        raise SystemExit(f"refusing to delete {len(candidates)} branches; max-delete-count={max_delete_count}")
    remote = str(report.get("remote") or "origin")
    results: list[dict[str, Any]] = []
    for item in candidates:
        row: dict[str, Any] = {
            "branch": item.get("name"),
            "returncode": 1,
            "deletion_attempted": False,
        }
        if item.get("historical_value") != "low" or not (
            item.get("merged_into_develop") or item.get("merged_into_release_main")
        ):
            row["reason"] = "candidate_missing_low_value_or_merge_evidence"
            results.append(row)
            continue
        verification = verify_source_sha(project_root, item, remote)
        row["source_verification"] = verification
        if not verification["verified"]:
            row["reason"] = verification["reason"]
            results.append(row)
            continue
        args = guarded_delete_args(item, remote)
        proc = run_git(project_root, args)
        row.update(
            {
                "command": "git " + " ".join(args),
                "returncode": proc.returncode,
                "stdout": proc.stdout,
                "stderr": proc.stderr,
                "deletion_attempted": True,
                "reason": "exact_sha_guarded_delete" if proc.returncode == 0 else "delete_failed",
            }
        )
        results.append(row)
    return results


def apply_archive(project_root: Path, report: dict[str, Any]) -> list[dict[str, Any]]:
    require_fresh_activity_coverage(report, "archive mutation")
    candidates = [b for b in report["branches"] if b["classification"] == "archive_candidate"]
    remote = str(report.get("remote") or "origin")
    archive_prefix = str((report.get("policy") or {}).get("archive_prefix") or "archive/branches")
    results: list[dict[str, Any]] = []
    for item in candidates:
        source_verification = verify_source_sha(project_root, item, remote)
        target = archive_target_name(str(item.get("name") or ""), archive_prefix)
        row: dict[str, Any] = {
            "branch": item.get("name"),
            "archive_branch": target,
            "source_verification": source_verification,
            "returncode": 1,
            "verified": False,
        }
        if not source_verification["verified"]:
            row["reason"] = source_verification["reason"]
            results.append(row)
            continue
        sha = str(item["sha"])
        if item.get("ref_kind") == "remote":
            args = ["push", remote, f"{sha}:refs/heads/{target}"]
        else:
            args = ["branch", target, sha]
        proc = run_git(project_root, args)
        row.update(
            {
                "command": "git " + " ".join(args),
                "returncode": proc.returncode,
                "stdout": proc.stdout,
                "stderr": proc.stderr,
            }
        )
        if proc.returncode == 0:
            target_verification = verify_source_sha(
                project_root,
                {"name": target, "sha": sha, "ref_kind": item.get("ref_kind")},
                remote,
            )
            row["target_verification"] = target_verification
            row["verified"] = target_verification["verified"]
            if not row["verified"]:
                row["returncode"] = 1
                row["reason"] = "archive_target_sha_mismatch"
            else:
                row["reason"] = "archive_created_at_exact_sha"
        else:
            row["reason"] = "archive_create_failed"
        results.append(row)
    return results


def archived_source_delete_allowed(item: dict[str, Any]) -> bool:
    if item.get("merged_into_develop") or item.get("merged_into_release_main"):
        return True
    evidence = item.get("integration_evidence") if isinstance(item.get("integration_evidence"), dict) else {}
    return item.get("capability_state") == "preserved" and evidence.get("status") == "integrated"


def apply_archive_and_delete(
    project_root: Path,
    report: dict[str, Any],
    remote: str,
    max_archive_count: int,
) -> list[dict[str, Any]]:
    """Archive exact refs and delete only preservation-proven source tips."""
    require_fresh_activity_coverage(report, "destructive cleanup")
    candidates = [item for item in report["branches"] if item["classification"] == "archive_candidate"]
    if len(candidates) > max_archive_count:
        candidates = candidates[:max_archive_count]
    results: list[dict[str, Any]] = []
    for item in candidates:
        target = archive_target_name(str(item["name"]), str(report.get("policy", {}).get("archive_prefix") or "archive/branches"))
        sha = str(item.get("sha") or "")
        source_verification = verify_source_sha(project_root, item, remote)
        if not source_verification["verified"]:
            results.append(
                {
                    "branch": item.get("name"),
                    "scope": item.get("ref_kind"),
                    "archive_branch": target,
                    "archive_returncode": 1,
                    "delete_returncode": None,
                    "verified": False,
                    "source_verification": source_verification,
                    "reason": source_verification["reason"],
                }
            )
            continue
        if item.get("ref_kind") == "remote":
            archive_proc = run_git(project_root, ["push", remote, f"{sha}:refs/heads/{target}"])
            row: dict[str, Any] = {
                "branch": item["name"],
                "scope": "remote",
                "archive_branch": target,
                "archive_returncode": archive_proc.returncode,
                "archive_stderr": archive_proc.stderr,
                "delete_returncode": None,
                "verified": False,
                "source_verification": source_verification,
            }
            if archive_proc.returncode == 0:
                verify = None
                for attempt in range(1, 4):
                    verify = run_git(project_root, ["ls-remote", "--heads", remote, f"refs/heads/{target}"])
                    remote_sha = (verify.stdout.strip().split() or [""])[0]
                    row["verify_attempts"] = attempt
                    row["verify_stderr"] = verify.stderr
                    if verify.returncode == 0 and remote_sha == sha:
                        row["verified"] = True
                        break
                    if verify.returncode == 0 and remote_sha and remote_sha != sha:
                        break
                    if attempt < 3:
                        time.sleep(1)
                if row["verified"] and not archived_source_delete_allowed(item):
                    row["reason"] = "source_has_unique_or_unproven_capability"
                elif row["verified"]:
                    source_reverification = verify_source_sha(project_root, item, remote)
                    row["source_reverification"] = source_reverification
                    if source_reverification["verified"]:
                        delete_proc = run_git(project_root, guarded_delete_args(item, remote))
                        row["delete_returncode"] = delete_proc.returncode
                        row["delete_stderr"] = delete_proc.stderr
                        row["reason"] = "archived_then_exact_sha_guarded_delete"
                    else:
                        row["reason"] = "source_sha_changed_after_archive"
                else:
                    row["reason"] = "archive_target_sha_mismatch"
            else:
                row["reason"] = "archive_create_failed"
            results.append(row)
            continue

        archive_proc = run_git(project_root, ["branch", target, sha])
        row = {
            "branch": item["name"],
            "scope": "local",
            "archive_branch": target,
            "archive_returncode": archive_proc.returncode,
            "archive_stderr": archive_proc.stderr,
            "delete_returncode": None,
            "verified": False,
            "source_verification": source_verification,
        }
        if archive_proc.returncode == 0:
            verify = run_git(project_root, ["rev-parse", "--verify", target])
            row["verified"] = verify.returncode == 0 and verify.stdout.strip() == sha
            if row["verified"] and not archived_source_delete_allowed(item):
                row["reason"] = "source_has_unique_or_unproven_capability"
            elif row["verified"]:
                source_reverification = verify_source_sha(project_root, item, remote)
                row["source_reverification"] = source_reverification
                if source_reverification["verified"]:
                    delete_proc = run_git(project_root, guarded_delete_args(item, remote))
                    row["delete_returncode"] = delete_proc.returncode
                    row["delete_stderr"] = delete_proc.stderr
                    row["reason"] = "archived_then_exact_sha_guarded_delete"
                else:
                    row["reason"] = "source_sha_changed_after_archive"
            else:
                row["reason"] = "archive_target_sha_mismatch"
        else:
            row["reason"] = "archive_create_failed"
        results.append(row)
    return results


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).expanduser().resolve()
    if args.fetch:
        proc = run_git(project_root, ["fetch", "--all", "--prune"])
        if proc.returncode != 0:
            raise SystemExit(proc.stderr.strip() or "git fetch failed")
    protection_mode = str(getattr(args, "protection_mode", "default"))
    policy = load_policy(project_root, args.stale_days, args.archive_prefix, protection_mode)
    base = args.base if args.base.startswith("origin/") else f"{args.remote}/{args.base}"
    release_base = args.release_base if args.release_base.startswith("origin/") else f"{args.remote}/{args.release_base}"
    deep_metrics = bool(getattr(args, "deep_metrics", False))
    records = collect_branches(
        project_root,
        args.remote,
        base,
        release_base,
        args.include_local,
        args.include_remote,
        deep_metrics=deep_metrics,
    )
    pr_index, warnings, pr_evidence_available = github_pr_index(project_root, args.remote)
    activity_dir_value = getattr(args, "codex_activity_dir", None)
    expected_hosts = list(getattr(args, "expected_codex_host", None) or [])
    activity_max_age = max(1, int(getattr(args, "codex_activity_max_age_seconds", 300)))
    codex_activity = load_codex_activity_inventory(
        Path(activity_dir_value).expanduser().resolve() if activity_dir_value else None,
        expected_hosts,
        activity_max_age,
    )
    refs = aistudio_reference_index(project_root, [record.name for record in records], codex_activity)
    cache_path_value = getattr(args, "evidence_cache", None)
    cache_path = Path(cache_path_value).expanduser().resolve() if cache_path_value else None
    cached = load_json(cache_path) if cache_path and cache_path.exists() else {}
    cache_entries = (
        cached.get("entries")
        if isinstance(cached, dict)
        and int(cached.get("algorithm_version") or 0) == EVIDENCE_ALGORITHM_VERSION
        and isinstance(cached.get("entries"), dict)
        else {}
    )
    base_sha_proc = run_git(project_root, ["rev-parse", base])
    base_sha = base_sha_proc.stdout.strip() if base_sha_proc.returncode == 0 else base
    cache_hits = 0
    cache_misses = 0
    cache_changed = False
    evidence_by_ref: dict[str, dict[str, Any]] = {}
    for record in records:
        references = refs.get(record.name, [])
        pr = pr_index.get(record.name)
        merged = record.merged_into_develop or record.merged_into_release_main
        open_pr = bool(pr and pr.get("state") == "OPEN")
        active_ref = has_active_reference(references)
        stale = record.age_days >= policy.stale_days
        if not merged and stale and not open_pr and not active_ref and not policy.is_protected(record.name):
            cache_key = f"v{EVIDENCE_ALGORITHM_VERSION}:{base_sha}:{record.sha}"
            cached_evidence = cache_entries.get(cache_key)
            if isinstance(cached_evidence, dict):
                evidence_by_ref[record.ref] = cached_evidence
                cache_hits += 1
            else:
                evidence_by_ref[record.ref] = terminal_integration_evidence(project_root, record, base, references)
                cache_entries[cache_key] = evidence_by_ref[record.ref]
                cache_misses += 1
                cache_changed = True
    if cache_path and cache_changed:
        compact_entries = dict(list(cache_entries.items())[-2000:])
        write_json_report(
            cache_path,
            {
                "schema_version": 1,
                "algorithm_version": EVIDENCE_ALGORITHM_VERSION,
                "updated_at": iso(utc_now()),
                "entries": compact_entries,
            },
        )
    branches = [
        classify_branch(
            record,
            policy,
            pr_index.get(record.name),
            pr_evidence_available,
            refs.get(record.name, []),
            args.remote,
            evidence_by_ref.get(record.ref),
        )
        for record in records
    ]
    counts = {key: 0 for key in ("total", "keep_active", "merged_safe_delete", "archive_candidate", "dirty_worker_candidate", "integration_recovery_candidate", "cleanup_candidate", "unknown_needs_review")}
    counts["total"] = len(branches)
    for branch in branches:
        counts[branch["classification"]] += 1
    historical_value_counts = dict(Counter(branch["historical_value"] for branch in branches))
    execution_liveness_counts = dict(Counter(branch["execution_liveness"] for branch in branches))
    return {
        "schema_version": 1,
        "generated_at": iso(utc_now()),
        "project_root": str(project_root),
        "base": args.base,
        "release_base": args.release_base,
        "remote": args.remote,
        "policy": {
            "stale_days": args.stale_days,
            "archive_prefix": args.archive_prefix,
            "protection_mode": policy.protection_mode,
            "canonical_protected_branches": sorted(CANONICAL_PROTECTED_BRANCHES),
            "protected_patterns": policy.protected_patterns,
            "preserve_branches": policy.preserve_branches,
            "historical_value": {
                "high_value_branch_patterns": policy.high_value_branch_patterns,
                "important_path_patterns": policy.important_path_patterns,
                "durable_evidence_path_patterns": policy.durable_evidence_path_patterns,
            },
            "execution_liveness": {
                "worker_stuck_after_seconds": policy.worker_stuck_after_seconds,
            },
            "deep_metrics": deep_metrics,
        },
        "pr_evidence_available": pr_evidence_available,
        "warnings": [*warnings, *codex_activity.get("warnings", [])],
        "codex_activity": codex_activity,
        "evidence_cache": {
            "path": str(cache_path) if cache_path else None,
            "algorithm_version": EVIDENCE_ALGORITHM_VERSION,
            "base_sha": base_sha,
            "hit_count": cache_hits,
            "miss_count": cache_misses,
            "written": bool(cache_path and cache_changed),
        },
        "counts": counts,
        "historical_value_counts": historical_value_counts,
        "execution_liveness_counts": execution_liveness_counts,
        "branches": branches,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Plan safe cleanup for local and remote branches.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base", default="develop")
    parser.add_argument("--release-base", default="release/main")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--stale-days", type=int, default=14)
    parser.add_argument("--archive-prefix", default="archive/branches")
    parser.add_argument(
        "--protection-mode",
        choices=PROTECTION_MODES,
        default="default",
        help="Use canonical-only only for an explicitly audited cleanup cohort; default preserves configured patterns.",
    )
    parser.add_argument("--codex-activity-dir")
    parser.add_argument("--expected-codex-host", action="append", default=[])
    parser.add_argument("--codex-activity-max-age-seconds", type=int, default=300)
    parser.add_argument("--evidence-cache")
    parser.add_argument("--include-local", action="store_true")
    parser.add_argument("--include-remote", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument(
        "--deep-metrics",
        action="store_true",
        help="Collect per-branch ahead/behind and changed-path details. The fast default is sufficient for cleanup classification.",
    )
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--output")
    parser.add_argument("--markdown")
    parser.add_argument("--commands-output")
    parser.add_argument("--apply-delete-merged-safe", action="store_true")
    parser.add_argument("--apply-archive-candidates", action="store_true")
    parser.add_argument("--apply-archive-and-delete-safe", action="store_true")
    parser.add_argument("--max-delete-count", type=int, default=20)
    parser.add_argument("--max-archive-count", type=int, default=20)
    parser.add_argument("--yes", action="store_true")
    args = parser.parse_args()

    if (args.apply_delete_merged_safe or args.apply_archive_candidates or args.apply_archive_and_delete_safe) and not args.yes:
        raise SystemExit("apply modes require --yes")

    report = build_report(args)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    default_json, default_md, default_cmd = default_output_paths(Path(args.project_root).expanduser().resolve(), timestamp)
    json_path = Path(args.output).expanduser().resolve() if args.output else default_json
    md_path = Path(args.markdown).expanduser().resolve() if args.markdown else default_md
    commands_path = Path(args.commands_output).expanduser().resolve() if args.commands_output else default_cmd
    write_json_report(json_path, report)
    write_markdown(md_path, report)
    write_commands(commands_path, report)

    apply_results: list[dict[str, Any]] = []
    if args.apply_delete_merged_safe:
        apply_results.extend(apply_delete(Path(args.project_root).expanduser().resolve(), report, args.max_delete_count))
    if args.apply_archive_candidates:
        apply_results.extend(apply_archive(Path(args.project_root).expanduser().resolve(), report))
    if args.apply_archive_and_delete_safe:
        apply_results.extend(
            apply_archive_and_delete(
                Path(args.project_root).expanduser().resolve(),
                report,
                args.remote,
                max(0, args.max_archive_count),
            )
        )
    if args.apply_delete_merged_safe or args.apply_archive_candidates or args.apply_archive_and_delete_safe:
        report["apply_results"] = apply_results
        write_json_report(json_path, report)

    summary = {
        "ok": True,
        "dry_run": not (args.apply_delete_merged_safe or args.apply_archive_candidates or args.apply_archive_and_delete_safe),
        "json_report": str(json_path),
        "markdown_report": str(md_path),
        "commands_output": str(commands_path),
        "counts": report["counts"],
        "warnings": report["warnings"],
    }
    if args.apply_delete_merged_safe or args.apply_archive_candidates or args.apply_archive_and_delete_safe:
        summary["apply_results"] = apply_results
    print(json.dumps(summary if args.json else report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
