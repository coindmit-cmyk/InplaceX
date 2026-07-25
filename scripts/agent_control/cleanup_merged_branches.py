#!/usr/bin/env python3
"""Verify and optionally delete temporary branch cleanup candidates.

The script is intentionally conservative:
- dry-run by default;
- deletion requires ``--apply`` plus ``--delete-local`` or ``--delete-remote``;
- protected branches are never deleted;
- candidates must be merged into the selected base branch;
- open PRs, active locks, active worktrees and non-final task references block cleanup;
- if GitHub PR state cannot be checked, remote deletion is blocked by default.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from project_paths import task_file


DEFAULT_PREFIXES = (
    "AiStudio/Agent/worker/",
    "AiStudio/Agent/integrator/",
    "AiStudio/Agent/finalizer/",
    "AiStudio/Agent/cleanup/",
    "AiStudio/Agent/dispatcher/",
    "AiStudio/CodexDesktop/",
    "remote/",
    "local/",
    "integrator/",
    "staging/",
    "experiment/",
    "finalizer/",
)
DEFAULT_PROTECTED = ("main", "master", "develop", "release/main", "main/release")
FINAL_TASK_STATUSES = {
    "done",
    "owner_approved",
    "finalized",
    "finalised",
    "merged",
    "superseded",
    "cancelled",
    "canceled",
    "no_op",
    "cleanup_done",
}
ACTIVE_LOCK_STATES = {"active", "claimed", "running", "in_progress", "locked"}


@dataclass(frozen=True)
class BranchCandidate:
    name: str
    ref: str
    scope: str
    source: str
    task_id: str | None = None
    summary: str | None = None


@dataclass
class CleanupDecision:
    candidate: BranchCandidate
    eligible: bool = True
    reasons: list[str] = field(default_factory=list)
    action: str = "dry_run"
    details: dict[str, Any] = field(default_factory=dict)

    def block(self, reason: str, **details: Any) -> None:
        self.eligible = False
        if reason not in self.reasons:
            self.reasons.append(reason)
        self.details.update({key: value for key, value in details.items() if value not in (None, "", [])})


def run_command(args: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=str(cwd) if cwd else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def run_git(project_root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return run_command(["git", *args], cwd=project_root)


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSON in {path}: {exc}") from exc


def rel_or_abs(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def policy_prefixes(policy: dict[str, Any]) -> tuple[str, ...]:
    cleanup = policy.get("cleanup", {}) if isinstance(policy.get("cleanup"), dict) else {}
    prefixes = cleanup.get("candidate_prefixes")
    if isinstance(prefixes, list) and prefixes:
        return tuple(str(prefix) for prefix in prefixes)

    branches = policy.get("branches", {}) if isinstance(policy.get("branches"), dict) else {}
    collected: list[str] = []
    for key in (
        "agent_worker_prefixes",
        "agent_integrator_prefixes",
        "agent_finalizer_prefixes",
        "agent_cleanup_prefixes",
        "agent_dispatcher_prefixes",
        "remote_prefixes",
        "local_prefixes",
        "integrator_prefixes",
        "staging_prefixes",
        "experiment_prefixes",
        "finalizer_prefixes",
    ):
        values = branches.get(key, [])
        if isinstance(values, list):
            collected.extend(str(value) for value in values)
    return tuple(dict.fromkeys(collected or DEFAULT_PREFIXES))


def policy_protected(policy: dict[str, Any]) -> tuple[str, ...]:
    cleanup = policy.get("cleanup", {}) if isinstance(policy.get("cleanup"), dict) else {}
    protected = cleanup.get("protected_branches")
    if isinstance(protected, list) and protected:
        return tuple(dict.fromkeys([*(str(branch) for branch in protected), *DEFAULT_PROTECTED]))

    branches = policy.get("branches", {}) if isinstance(policy.get("branches"), dict) else {}
    collected: list[str] = []
    for key in ("stable", "integration", "release"):
        values = branches.get(key, [])
        if isinstance(values, list):
            collected.extend(str(value) for value in values)
    return tuple(dict.fromkeys([*collected, *DEFAULT_PROTECTED]))


def normalize_remote_branch(name: str, remote: str) -> str:
    prefix = f"{remote}/"
    return name[len(prefix) :] if name.startswith(prefix) else name


def protected_branch(name: str, protected_branches: tuple[str, ...]) -> bool:
    return name in protected_branches or name.startswith("old/") or name.startswith("tags/")


def prefix_allowed(name: str, prefixes: tuple[str, ...]) -> bool:
    return any(name.startswith(prefix) for prefix in prefixes)


def branch_exists(project_root: Path, ref: str) -> bool:
    return run_git(project_root, ["rev-parse", "--verify", "--quiet", ref]).returncode == 0


def merged_into(project_root: Path, candidate_ref: str, base_ref: str) -> bool:
    return run_git(project_root, ["merge-base", "--is-ancestor", candidate_ref, base_ref]).returncode == 0


def commit_sha(project_root: Path, ref: str) -> str | None:
    proc = run_git(project_root, ["rev-parse", "--verify", ref])
    if proc.returncode != 0:
        return None
    return proc.stdout.strip()


def read_activity_candidates(path: Path, remote: str) -> list[BranchCandidate]:
    state = load_json(path, {}) if path.exists() else {}
    raw_candidates: list[dict[str, Any]] = []

    cleanup_candidates = state.get("cleanup_candidates", []) if isinstance(state, dict) else []
    if isinstance(cleanup_candidates, list):
        raw_candidates.extend(item for item in cleanup_candidates if isinstance(item, dict))

    pending_signals = state.get("pending_signals", []) if isinstance(state, dict) else []
    if isinstance(pending_signals, list):
        raw_candidates.extend(
            item
            for item in pending_signals
            if isinstance(item, dict) and item.get("signal") == "cleanup_candidate"
        )

    candidates: list[BranchCandidate] = []
    for item in raw_candidates:
        branch = item.get("branch")
        if not isinstance(branch, str) or not branch:
            continue
        scope = str(item.get("branch_scope") or item.get("scope") or "local")
        if scope not in {"local", "remote"}:
            scope = "remote" if branch.startswith("remote/") else "local"
        ref = f"refs/remotes/{remote}/{branch}" if scope == "remote" else branch
        task_id = item.get("task_id")
        candidates.append(
            BranchCandidate(
                name=branch,
                ref=ref,
                scope=scope,
                source="activity_state",
                task_id=str(task_id) if task_id else None,
                summary=str(item.get("summary")) if item.get("summary") else None,
            )
        )
    return candidates


def list_prefix_candidates(project_root: Path, prefixes: tuple[str, ...], remote: str) -> list[BranchCandidate]:
    candidates: list[BranchCandidate] = []
    local = run_git(project_root, ["for-each-ref", "--format=%(refname:short)", "refs/heads"])
    if local.returncode == 0:
        for line in local.stdout.splitlines():
            name = line.strip()
            if name and prefix_allowed(name, prefixes):
                candidates.append(BranchCandidate(name, name, "local", "prefix_scan"))

    remotes = run_git(project_root, ["for-each-ref", "--format=%(refname:short)", f"refs/remotes/{remote}"])
    if remotes.returncode == 0:
        for line in remotes.stdout.splitlines():
            remote_name = line.strip()
            if not remote_name or remote_name.endswith("/HEAD"):
                continue
            name = normalize_remote_branch(remote_name, remote)
            if prefix_allowed(name, prefixes):
                candidates.append(BranchCandidate(name, f"refs/remotes/{remote}/{name}", "remote", "prefix_scan"))
    return candidates


def dedupe(candidates: list[BranchCandidate]) -> list[BranchCandidate]:
    seen: set[tuple[str, str]] = set()
    result: list[BranchCandidate] = []
    for candidate in candidates:
        key = (candidate.scope, candidate.name)
        if key in seen:
            continue
        seen.add(key)
        result.append(candidate)
    return result


def load_tasks(task_queue_path: Path) -> list[dict[str, Any]]:
    queue = load_json(task_queue_path, {"tasks": []}) if task_queue_path.exists() else {"tasks": []}
    if isinstance(queue, dict) and isinstance(queue.get("tasks"), list):
        return [task for task in queue["tasks"] if isinstance(task, dict)]
    if isinstance(queue, list):
        return [task for task in queue if isinstance(task, dict)]
    return []


def load_locks(path: Path) -> list[dict[str, Any]]:
    data = load_json(path, {"locks": []}) if path.exists() else {"locks": []}
    if isinstance(data, dict):
        locks = data.get("locks", [])
        if isinstance(locks, list):
            return [lock for lock in locks if isinstance(lock, dict)]
        if isinstance(locks, dict):
            return [lock for lock in locks.values() if isinstance(lock, dict)]
    if isinstance(data, list):
        return [lock for lock in data if isinstance(lock, dict)]
    return []


def flatten_values(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        result: list[str] = []
        for nested in value.values():
            result.extend(flatten_values(nested))
        return result
    if isinstance(value, list):
        result = []
        for nested in value:
            result.extend(flatten_values(nested))
        return result
    return []


def task_references_branch(task: dict[str, Any], branch: str) -> bool:
    direct_keys = (
        "branch",
        "branch_name",
        "head_branch",
        "source_branch",
        "worker_branch",
        "integration_branch",
        "pr_branch",
    )
    for key in direct_keys:
        if task.get(key) == branch:
            return True
    return branch in flatten_values(task.get("links", {})) or branch in flatten_values(task.get("evidence", {}))


def find_blocking_tasks(tasks: list[dict[str, Any]], candidate: BranchCandidate) -> list[dict[str, str | None]]:
    blockers: list[dict[str, str | None]] = []
    for task in tasks:
        status = str(task.get("status") or "").lower()
        task_id = str(task.get("id") or task.get("task_id") or "") or None
        same_task = bool(candidate.task_id and task_id == candidate.task_id)
        same_branch = task_references_branch(task, candidate.name)
        if not same_task and not same_branch:
            continue
        if status not in FINAL_TASK_STATUSES:
            blockers.append({"task_id": task_id, "status": status or None})
    return blockers


def lock_references_candidate(lock: dict[str, Any], candidate: BranchCandidate) -> bool:
    values = flatten_values(lock)
    if candidate.name in values:
        return True
    return bool(candidate.task_id and candidate.task_id in values)


def find_active_locks(lock_sets: list[list[dict[str, Any]]], candidate: BranchCandidate) -> list[dict[str, str | None]]:
    blockers: list[dict[str, str | None]] = []
    for locks in lock_sets:
        for lock in locks:
            state = str(lock.get("state") or lock.get("status") or "").lower()
            if state and state not in ACTIVE_LOCK_STATES:
                continue
            if lock_references_candidate(lock, candidate):
                blockers.append(
                    {
                        "lock_id": str(lock.get("id") or lock.get("lock_id") or lock.get("run_id") or "") or None,
                        "state": state or None,
                    }
                )
    return blockers


def load_open_pr_heads(project_root: Path, remote: str) -> tuple[set[str], str | None]:
    gh = run_command(["gh", "pr", "list", "--state", "open", "--limit", "1000", "--json", "headRefName"], cwd=project_root)
    if gh.returncode != 0:
        return set(), gh.stderr.strip() or gh.stdout.strip() or "gh pr list failed"
    try:
        rows = json.loads(gh.stdout or "[]")
    except json.JSONDecodeError as exc:
        return set(), f"cannot parse gh pr list output: {exc}"
    if not isinstance(rows, list):
        return set(), "unexpected gh pr list output"
    heads = {str(row.get("headRefName")) for row in rows if isinstance(row, dict) and row.get("headRefName")}
    heads.update(f"{remote}/{head}" for head in list(heads))
    return heads, None


def worktree_branches(project_root: Path) -> set[str]:
    proc = run_git(project_root, ["worktree", "list", "--porcelain"])
    if proc.returncode != 0:
        return set()
    branches: set[str] = set()
    for line in proc.stdout.splitlines():
        if line.startswith("branch "):
            ref = line.removeprefix("branch ").strip()
            if ref.startswith("refs/heads/"):
                branches.add(ref.removeprefix("refs/heads/"))
    return branches


def delete_candidate(project_root: Path, candidate: BranchCandidate, remote: str) -> subprocess.CompletedProcess[str]:
    if candidate.scope == "remote":
        return run_git(project_root, ["push", remote, "--delete", candidate.name])
    return run_git(project_root, ["branch", "-d", candidate.name])


def resolve_base_ref(project_root: Path, base: str, remote: str) -> str:
    if branch_exists(project_root, base):
        return base
    remote_base = f"refs/remotes/{remote}/{base}"
    if branch_exists(project_root, remote_base):
        return remote_base
    raise SystemExit(f"Base ref not found: {base}")


def decide_candidate(
    project_root: Path,
    candidate: BranchCandidate,
    *,
    base_ref: str,
    prefixes: tuple[str, ...],
    protected_branches: tuple[str, ...],
    open_pr_heads: set[str],
    open_pr_error: str | None,
    tasks: list[dict[str, Any]],
    lock_sets: list[list[dict[str, Any]]],
    active_worktree_branches: set[str],
    require_pr_check: bool,
) -> CleanupDecision:
    decision = CleanupDecision(candidate=candidate)
    if protected_branch(candidate.name, protected_branches):
        decision.block("protected_branch")
    if not prefix_allowed(candidate.name, prefixes):
        decision.block("prefix_not_allowed")
    if not branch_exists(project_root, candidate.ref):
        decision.block("branch_not_found")
        return decision

    candidate_sha = commit_sha(project_root, candidate.ref)
    base_sha = commit_sha(project_root, base_ref)
    decision.details.update({"candidate_sha": candidate_sha, "base_sha": base_sha})

    if not merged_into(project_root, candidate.ref, base_ref):
        decision.block("not_merged_into_base")

    if candidate.name in active_worktree_branches:
        decision.block("local_worktree_active")

    task_blockers = find_blocking_tasks(tasks, candidate)
    if task_blockers:
        decision.block("non_final_task_reference", tasks=task_blockers)

    lock_blockers = find_active_locks(lock_sets, candidate)
    if lock_blockers:
        decision.block("active_lock_reference", locks=lock_blockers)

    if candidate.name in open_pr_heads or f"origin/{candidate.name}" in open_pr_heads:
        decision.block("open_pr_exists")
    elif open_pr_error and (candidate.scope == "remote" or require_pr_check):
        decision.block("open_pr_check_unavailable", error=open_pr_error)

    return decision


def render_report(decisions: list[CleanupDecision], base_ref: str, apply: bool) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    for decision in decisions:
        rows.append(
            {
                "branch": decision.candidate.name,
                "scope": decision.candidate.scope,
                "ref": decision.candidate.ref,
                "source": decision.candidate.source,
                "task_id": decision.candidate.task_id,
                "eligible": decision.eligible,
                "reasons": decision.reasons or ["eligible"],
                "action": decision.action,
                "details": decision.details,
            }
        )
    return {
        "schema_version": 1,
        "base_ref": base_ref,
        "apply": apply,
        "summary": {
            "candidates": len(rows),
            "eligible": sum(1 for row in rows if row["eligible"]),
            "blocked": sum(1 for row in rows if not row["eligible"]),
            "deleted": sum(1 for row in rows if row["action"] == "deleted"),
        },
        "candidates": rows,
    }


def print_text_report(report: dict[str, Any]) -> None:
    print(f"Base ref: {report['base_ref']}")
    summary = report["summary"]
    print(
        "Summary: "
        f"candidates={summary['candidates']} "
        f"eligible={summary['eligible']} "
        f"blocked={summary['blocked']} "
        f"deleted={summary['deleted']}"
    )
    if not report["candidates"]:
        print("No cleanup candidates found.")
    for item in report["candidates"]:
        marker = "OK" if item["eligible"] else "SKIP"
        reasons = ", ".join(item["reasons"])
        print(f"{marker} {item['scope']} {item['branch']} - {reasons} ({item['action']})")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Dry-run or delete verified temporary branch cleanup candidates.")
    parser.add_argument("--project-root", default=".", help="Repository root. Defaults to current directory.")
    parser.add_argument("--activity-state", help="Path to AiStudio/Task_manager/agent_activity_state.json.")
    parser.add_argument("--task-queue", help="Path to AiStudio/Task_manager/task_queue.json.")
    parser.add_argument("--locks", help="Path to AiStudio/Task_manager/agent_locks.json.")
    parser.add_argument("--process-locks", help="Path to AiStudio/Task_manager/process_locks.json.")
    parser.add_argument("--branch-policy", help="Path to branch_policy.json.")
    parser.add_argument("--base", default="develop", help="Base branch/ref that must contain the candidate commits.")
    parser.add_argument("--remote", default="origin", help="Git remote name.")
    parser.add_argument("--fetch", action="store_true", help="Fetch/prune the remote before checking merge state.")
    parser.add_argument("--include-prefix-candidates", action="store_true", help="Also scan all managed branch prefixes.")
    parser.add_argument("--apply", action="store_true", help="Allow deletion when paired with delete flags.")
    parser.add_argument("--delete-local", action="store_true", help="Delete eligible local branches.")
    parser.add_argument("--delete-remote", action="store_true", help="Delete eligible remote branches.")
    parser.add_argument(
        "--allow-without-pr-check",
        action="store_true",
        help="Do not block remote cleanup when gh PR state is unavailable.",
    )
    parser.add_argument("--report-file", help="Optional path to write the JSON report.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_root = Path(args.project_root).resolve()
    if not (project_root / ".git").exists():
        print(f"Not a git repository root: {project_root}", file=sys.stderr)
        return 2

    policy_path = Path(args.branch_policy).resolve() if args.branch_policy else project_root / "templates/agent-control/branch_policy.example.json"
    policy = load_json(policy_path, {}) if policy_path.exists() else {}
    if not isinstance(policy, dict):
        policy = {}
    prefixes = policy_prefixes(policy)
    protected_branches = policy_protected(policy)

    if args.fetch:
        fetch = run_git(project_root, ["fetch", "--prune", args.remote])
        if fetch.returncode != 0:
            print(fetch.stderr.strip(), file=sys.stderr)
            return fetch.returncode

    base_ref = resolve_base_ref(project_root, args.base, args.remote)
    activity_state = Path(args.activity_state).resolve() if args.activity_state else task_file(project_root, "agent_activity_state.json")
    task_queue = Path(args.task_queue).resolve() if args.task_queue else task_file(project_root, "task_queue.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")
    process_locks_path = (
        Path(args.process_locks).resolve() if args.process_locks else task_file(project_root, "process_locks.json")
    )

    candidates = read_activity_candidates(activity_state, args.remote)
    if args.include_prefix_candidates:
        candidates.extend(list_prefix_candidates(project_root, prefixes, args.remote))
    candidates = dedupe(candidates)

    tasks = load_tasks(task_queue)
    lock_sets = [load_locks(locks_path), load_locks(process_locks_path)]
    open_pr_heads, open_pr_error = load_open_pr_heads(project_root, args.remote)
    active_worktrees = worktree_branches(project_root)

    decisions = [
        decide_candidate(
            project_root,
            candidate,
            base_ref=base_ref,
            prefixes=prefixes,
            protected_branches=protected_branches,
            open_pr_heads=open_pr_heads,
            open_pr_error=None if args.allow_without_pr_check else open_pr_error,
            tasks=tasks,
            lock_sets=lock_sets,
            active_worktree_branches=active_worktrees,
            require_pr_check=not args.allow_without_pr_check,
        )
        for candidate in candidates
    ]

    for decision in decisions:
        delete_requested = args.apply and (
            (decision.candidate.scope == "local" and args.delete_local)
            or (decision.candidate.scope == "remote" and args.delete_remote)
        )
        if not decision.eligible:
            decision.action = "blocked"
            continue
        if delete_requested:
            deleted = delete_candidate(project_root, decision.candidate, args.remote)
            if deleted.returncode == 0:
                decision.action = "deleted"
            else:
                decision.block("delete_failed", error=deleted.stderr.strip() or deleted.stdout.strip())
                decision.action = "blocked"
        else:
            decision.action = "eligible_dry_run"

    report = render_report(decisions, base_ref, args.apply)
    if args.report_file:
        report_path = Path(args.report_file)
        if not report_path.is_absolute():
            report_path = project_root / report_path
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_text_report(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
