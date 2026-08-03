#!/usr/bin/env python3
"""Inventory PR topology, route integration work, and run guarded branch cleanup.

The cycle turns linear stacked PRs into one integration candidate represented
in Task Manager. Cleanup is opt-in and archives unmerged branches with exact-SHA
verification before the source ref can be deleted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import branch_cleanup_planner
import branch_lifecycle_scanner
import worktree_retirement
from project_paths import task_file, task_manager_dir


TERMINAL_STATUSES = {
    "done",
    "completed",
    "finalized",
    "released",
    "archived",
    "closed",
    "stale_or_superseded",
    "duplicate_linked",
}
PROTECTED_BASES = {"develop", "main", "master", "release", "release/main", "staging", "production"}
PRE_EXECUTION_STATUSES = {"planned", "todo", "ready", "worker_ready"}
FREE_LOCK_STATES = {"", "free", "released", "unlocked"}
ACTIVE_LOCK_STATES = {"locked", "in_progress", "review"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def git(project_root: Path, args: list[str], timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=str(project_root),
        text=True,
        capture_output=True,
        check=False,
        timeout=timeout,
    )


def fetch_refs(project_root: Path, remote: str) -> dict[str, Any]:
    proc = git(project_root, ["fetch", remote, "--prune"])
    return {
        "command": ["git", "fetch", remote, "--prune"],
        "exit_code": proc.returncode,
        "stderr": proc.stderr.strip(),
    }


def load_open_prs(repo: str, limit: int = 1000) -> list[dict[str, Any]]:
    proc = subprocess.run(
        [
            "gh",
            "pr",
            "list",
            "--repo",
            repo,
            "--state",
            "open",
            "--limit",
            str(limit),
            "--json",
            "number,title,state,isDraft,baseRefName,headRefName,headRefOid,mergeStateStatus,updatedAt,url,reviewDecision,statusCheckRollup",
        ],
        text=True,
        capture_output=True,
        check=False,
        timeout=60,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "gh pr list failed")
    rows = json.loads(proc.stdout or "[]")
    if not isinstance(rows, list):
        raise ValueError("gh pr list returned a non-list payload")
    return [normalize_pr(row) for row in rows if isinstance(row, dict)]


def load_merged_prs(repo: str, limit: int = 1000) -> list[dict[str, Any]]:
    proc = subprocess.run(
        [
            "gh",
            "pr",
            "list",
            "--repo",
            repo,
            "--state",
            "merged",
            "--limit",
            str(limit),
            "--json",
            "number,headRefOid,mergeCommit,mergedAt",
        ],
        text=True,
        capture_output=True,
        check=False,
        timeout=60,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "gh merged pr list failed")
    rows = json.loads(proc.stdout or "[]")
    if not isinstance(rows, list):
        raise ValueError("gh merged pr list returned a non-list payload")
    return [row for row in rows if isinstance(row, dict)]


def normalize_pr(row: dict[str, Any]) -> dict[str, Any]:
    checks = (
        row.get("statusCheckRollup")
        or row.get("status_check_rollup")
        or row.get("ci_checks")
        or []
    )
    normalized_checks = [
        {
            "name": str(check.get("name") or check.get("context") or ""),
            "status": str(check.get("status") or ("COMPLETED" if check.get("state") else "")).upper(),
            "conclusion": str(check.get("conclusion") or check.get("state") or "").upper(),
        }
        for check in checks
        if isinstance(check, dict)
    ]
    ci_state = "missing"
    if normalized_checks:
        if all(
            check["status"] == "COMPLETED" and check["conclusion"] == "SUCCESS"
            for check in normalized_checks
        ):
            ci_state = "green"
        elif any(
            check["status"] == "COMPLETED"
            and check["conclusion"] not in {"", "SUCCESS"}
            for check in normalized_checks
        ):
            ci_state = "failed"
        else:
            ci_state = "pending"
    return {
        "number": int(row.get("number") or 0),
        "title": str(row.get("title") or ""),
        "state": str(row.get("state") or "OPEN").upper(),
        "is_draft": bool(row.get("isDraft") if "isDraft" in row else row.get("is_draft")),
        "base_ref": str(row.get("baseRefName") or row.get("base_ref") or ""),
        "head_ref": str(row.get("headRefName") or row.get("head_ref") or ""),
        "head_sha": str(row.get("headRefOid") or row.get("head_sha") or ""),
        "merge_state": str(row.get("mergeStateStatus") or row.get("merge_state") or "UNKNOWN").upper(),
        "updated_at": row.get("updatedAt") or row.get("updated_at"),
        "url": str(row.get("url") or ""),
        "review_decision": row.get("reviewDecision") or row.get("review_decision"),
        "ci_state": ci_state,
        "ci_checks": normalized_checks,
    }


def integration_base_from_ref(base_ref: str, remote: str = "origin") -> str:
    value = str(base_ref or "").strip()
    for prefix in (f"refs/remotes/{remote}/", f"{remote}/", "refs/heads/"):
        if value.startswith(prefix):
            return value[len(prefix):]
    return value


def stable_key(repo: str, numbers: list[int]) -> str:
    source = f"{repo.lower()}:{','.join(str(number) for number in sorted(numbers))}"
    return "repository-pr-group:" + hashlib.sha256(source.encode("utf-8")).hexdigest()[:20]


def task_id_for(numbers: list[int]) -> str:
    ordered = sorted(numbers)
    if len(ordered) == 1:
        return f"REPO-PR-{ordered[0]}"
    return f"REPO-PR-{ordered[0]}-{ordered[-1]}"


def available_task_id(group: dict[str, Any], used_ids: set[str]) -> str:
    preferred = str(group["task_id"])
    if preferred not in used_ids:
        return preferred
    suffix = str(group["group_key"]).rsplit(":", 1)[-1].upper()
    candidate = f"REPO-HYGIENE-{suffix}"
    if candidate not in used_ids:
        return candidate
    index = 2
    while f"{candidate}-{index}" in used_ids:
        index += 1
    return f"{candidate}-{index}"


def revision_task_id(group: dict[str, Any]) -> str:
    head_sha = str(group.get("candidate_head_sha") or "").strip().upper()
    suffix = head_sha[:12] if len(head_sha) >= 12 else hashlib.sha256(head_sha.encode("utf-8")).hexdigest()[:12].upper()
    return f"{group['task_id']}-REV-{suffix}"


def recheck_task_id(group: dict[str, Any]) -> str:
    head_sha = str(group.get("candidate_head_sha") or "").strip().upper()
    suffix = head_sha[:12] if len(head_sha) >= 12 else hashlib.sha256(head_sha.encode("utf-8")).hexdigest()[:12].upper()
    return f"{group['task_id']}-RECHECK-{suffix}"


def select_group_task(candidates: list[dict[str, Any]], current_head: str) -> dict[str, Any] | None:
    if not candidates:
        return None
    exact = [
        task
        for task in candidates
        if str(task.get("repository_hygiene_head_sha") or "").strip().lower() == current_head
    ]
    pool = exact
    if not pool:
        superseded_ids = {str(task.get("supersedes_task_id") or "") for task in candidates}
        pool = [task for task in candidates if str(task.get("id") or "") not in superseded_ids] or candidates
    return max(pool, key=lambda task: (str(task.get("updated_at") or task.get("discovered_at") or ""), str(task.get("id") or "")))


def task_allows_head_refresh(task: dict[str, Any]) -> bool:
    status = str(task.get("status") or "").lower()
    integration_status = str(task.get("integration_status") or "").lower()
    lock = task.get("lock")
    lock_state = str(lock.get("state") or "") if isinstance(lock, dict) else str(lock or "")
    unlocked = lock_state.lower() in {
        "",
        "free",
        "released",
        "unlocked",
    }
    if not unlocked:
        return False
    if status in {"agent_done", "needs_dispatcher_repair", "needs_human", "planned", "todo"}:
        return True
    if status != "integration_requested" or integration_status not in {"pending", "pending_required_ci"}:
        return False
    irreversible_fields = (
        "merge_commit",
        "integrator_commit",
        "accepted_worker_result_commit",
        "integration_completed_at",
        "finalized_at",
    )
    return not any(task.get(field) not in (None, "", [], {}) for field in irreversible_fields)


def task_dependencies_are_terminal(task: dict[str, Any], by_id: dict[str, dict[str, Any]]) -> bool:
    dependency_ids = [str(value).strip() for value in task.get("depends_on") or [] if str(value).strip()]
    return bool(dependency_ids) and all(
        dependency_id in by_id
        and str(by_id[dependency_id].get("status") or "").lower() in TERMINAL_STATUSES
        for dependency_id in dependency_ids
    )


def ordered_component(
    component: set[int],
    by_number: dict[int, dict[str, Any]],
    parent: dict[int, int | None],
    children: dict[int, list[int]],
) -> tuple[list[int], list[int], list[int], bool]:
    roots = sorted(number for number in component if parent.get(number) not in component)
    leaves = sorted(number for number in component if not [child for child in children.get(number, []) if child in component])
    linear = len(roots) == 1 and len(leaves) == 1 and all(
        len([child for child in children.get(number, []) if child in component]) <= 1 for number in component
    )
    if not linear:
        return sorted(component), roots, leaves, False
    ordered: list[int] = []
    current = roots[0]
    while current not in ordered:
        ordered.append(current)
        next_rows = [child for child in children.get(current, []) if child in component]
        if not next_rows:
            break
        current = next_rows[0]
    return ordered, roots, leaves, len(ordered) == len(component)


def build_pr_groups(
    prs: list[dict[str, Any]],
    repo: str,
    protected_bases: set[str] | None = None,
    *,
    integration_base: str = "develop",
) -> list[dict[str, Any]]:
    protected = protected_bases or PROTECTED_BASES
    open_prs = [normalize_pr(row) for row in prs if str(row.get("state") or "OPEN").upper() == "OPEN"]
    by_number = {int(row["number"]): row for row in open_prs if int(row.get("number") or 0) > 0}
    by_head = {row["head_ref"]: number for number, row in by_number.items() if row.get("head_ref")}
    parent: dict[int, int | None] = {}
    children: dict[int, list[int]] = defaultdict(list)
    adjacency: dict[int, set[int]] = defaultdict(set)
    for number, row in by_number.items():
        parent_number = by_head.get(str(row.get("base_ref") or ""))
        parent[number] = parent_number
        if parent_number is not None:
            children[parent_number].append(number)
            adjacency[number].add(parent_number)
            adjacency[parent_number].add(number)

    components: list[set[int]] = []
    remaining = set(by_number)
    while remaining:
        seed = min(remaining)
        stack = [seed]
        component: set[int] = set()
        while stack:
            current = stack.pop()
            if current in component:
                continue
            component.add(current)
            stack.extend(adjacency.get(current, set()) - component)
        remaining -= component
        components.append(component)

    groups: list[dict[str, Any]] = []
    for component in sorted(components, key=lambda values: min(values)):
        ordered, roots, leaves, linear = ordered_component(component, by_number, parent, children)
        root = by_number[roots[0]] if roots else by_number[ordered[0]]
        leaf = by_number[leaves[0]] if len(leaves) == 1 else None
        target_base = str(root.get("base_ref") or "")
        protected_target = target_base in protected
        automatic_target = target_base == integration_base
        route = "dispatcher_integration" if linear and leaf is not None and automatic_target else "needs_human"
        rows = [by_number[number] for number in ordered]
        dirty = [row["number"] for row in rows if row.get("merge_state") in {"DIRTY", "CONFLICTING"}]
        drafts = [row["number"] for row in rows if row.get("is_draft")]
        groups.append(
            {
                "group_key": stable_key(repo, ordered),
                "task_id": task_id_for(ordered),
                "classification": "stacked_pr_bundle" if len(ordered) > 1 else "direct_pr_candidate",
                "topology": "linear" if linear else "branched_or_ambiguous",
                "route": route,
                "target_base": target_base,
                "protected_target": protected_target,
                "integration_base": integration_base,
                "automatic_target": automatic_target,
                "pr_numbers": ordered,
                "pr_urls": [row.get("url") for row in rows if row.get("url")],
                "root_pr": roots[0] if len(roots) == 1 else None,
                "leaf_pr": leaves[0] if len(leaves) == 1 else None,
                "candidate_branch": f"origin/{leaf['head_ref']}" if leaf else None,
                "candidate_head_sha": leaf.get("head_sha") if leaf else None,
                "roots": roots,
                "leaves": leaves,
                "draft_prs": drafts,
                "dirty_prs": dirty,
                "candidate_ci_state": leaf.get("ci_state") if leaf else "missing",
                "requires_reconstruction": bool(len(ordered) > 1 or drafts or dirty),
                "reason": (
                    "linear PR stack can be reviewed as one cumulative leaf candidate"
                    if route == "dispatcher_integration" and len(ordered) > 1
                    else "direct PR can be routed through Dispatcher and Integrator"
                    if route == "dispatcher_integration"
                    else "PR topology or target base does not match this integration lane"
                ),
            }
        )
    return groups


def values_as_pr_numbers(task: dict[str, Any]) -> set[int]:
    values: list[Any] = []
    for key in ("github_pr", "pr_number", "integration_pr"):
        values.append(task.get(key))
    for key in ("pr_numbers", "pull_requests"):
        value = task.get(key)
        if isinstance(value, list):
            values.extend(value)
    result: set[int] = set()
    for value in values:
        try:
            if value is not None and str(value).strip():
                result.add(int(value))
        except (TypeError, ValueError):
            continue
    return result


def task_for_group(group: dict[str, Any], now: str, *, task_id: str | None = None) -> dict[str, Any]:
    numbers = [int(value) for value in group.get("pr_numbers") or []]
    first, last = min(numbers), max(numbers)
    title = f"Integrate open PR #{first}" if len(numbers) == 1 else f"Integrate open PR stack #{first}-#{last}"
    automatic = group.get("route") == "dispatcher_integration"
    return {
        "id": task_id or group["task_id"],
        "title": title,
        "status": "agent_done" if automatic else "needs_human",
        "priority": "P1",
        "complexity": "L" if len(numbers) > 1 or group.get("requires_reconstruction") else "M",
        "type": "repository_hygiene_integration",
        "recommended_agent": "Dispatcher" if automatic else "human",
        "requires_human_attention": not automatic,
        "worker_ready": False,
        "packet_status": "needs_dispatcher_repair" if automatic else "needs_human",
        "normalization_status": "repository_hygiene_routed" if automatic else "needs_human",
        "dispatcher_decision": "needs_dispatcher_repair" if automatic else "needs_human",
        "integration_status": "needs_dispatcher" if automatic else "needs_human",
        "branch": group.get("candidate_branch"),
        "base_branch": group.get("target_base") or "develop",
        "github_pr": group.get("leaf_pr"),
        "pr_numbers": numbers,
        "repository_hygiene_key": group["group_key"],
        "repository_hygiene_topology": group.get("topology"),
        "repository_hygiene_route": group.get("route"),
        "repository_hygiene_head_sha": group.get("candidate_head_sha"),
        "repository_hygiene_dirty_prs": group.get("dirty_prs") or [],
        "repository_hygiene_draft_prs": group.get("draft_prs") or [],
        "repository_hygiene_ci_state": group.get("candidate_ci_state") or "missing",
        "source_lane": "repository_hygiene_cycle",
        "source_summary": group.get("reason"),
        "provenance": [
            {
                "source_type": "github_pull_request_topology",
                "source_item_id": group["group_key"],
                "source_url": (group.get("pr_urls") or [None])[-1],
                "captured_at": now,
                "summary": group.get("reason"),
            }
        ],
        "acceptance_criteria": [
            "Preserve existing behavior unless the PR explicitly replaces it.",
            "Integrate stacked commits in recorded PR order and retain migration compatibility.",
            "Run project-required tests and git diff --check before finalization.",
            "Close or delete source PR branches only after merge evidence exists.",
        ],
        "checks": ["project-required tests", "git diff --check", "capability preservation comparison"],
        "lock": "free",
        "next_owner": "dispatcher" if automatic else "human",
        "next_role": "auto_dispatcher" if automatic else "human",
        "discovered_at": now,
        "updated_at": now,
    }


def apply_group_tasks(queue: dict[str, Any], groups: list[dict[str, Any]], *, apply: bool, now: str) -> dict[str, Any]:
    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    by_key: dict[str, list[dict[str, Any]]] = defaultdict(list)
    by_id: dict[str, dict[str, Any]] = {}
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        if task_id:
            by_id[task_id] = task
        if task.get("repository_hygiene_key"):
            by_key[str(task["repository_hygiene_key"])].append(task)
    covered_by_other: set[int] = set()
    used_ids = {
        str(task.get("id"))
        for task in tasks
        if isinstance(task, dict) and str(task.get("id") or "").strip()
    }
    for task in tasks:
        if not isinstance(task, dict) or task.get("repository_hygiene_key"):
            continue
        covered_by_other.update(values_as_pr_numbers(task))

    staged: list[str] = []
    updated: list[str] = []
    covered: list[str] = []
    superseded: list[str] = []
    active_numbers = {number for group in groups for number in group.get("pr_numbers") or []}
    for task in tasks:
        if not isinstance(task, dict) or not task.get("repository_hygiene_key"):
            continue
        task_numbers = values_as_pr_numbers(task)
        if not task_numbers:
            continue
        source_pr_closed = not bool(task_numbers & active_numbers)
        if not source_pr_closed and any(str(group.get("group_key")) == str(task.get("repository_hygiene_key")) for group in groups):
            continue
        if str(task.get("status") or "").lower() in TERMINAL_STATUSES:
            continue
        superseded.append(str(task.get("id") or ""))
        if apply:
            task.update(
                {
                    "status": "stale_or_superseded",
                    "integration_status": "stale_or_superseded",
                    "dispatcher_decision": "stale_or_superseded",
                    "packet_status": "stale_or_superseded",
                    "worker_ready": False,
                    "lock": "free",
                    "next_owner": "none",
                    "closed_at": now,
                    "closed_by": "repository_hygiene_cycle",
                    "status_reason": (
                        "source pull request is no longer open"
                        if source_pr_closed
                        else "open PR topology changed and a replacement hygiene group was created"
                    ),
                }
            )

    for group in groups:
        numbers = {int(value) for value in group.get("pr_numbers") or []}
        current_head = str(group.get("candidate_head_sha") or "").strip().lower()
        existing = select_group_task(by_key.get(str(group["group_key"]), []), current_head)
        if existing is not None:
            covered.append(str(existing.get("id") or group["task_id"]))
            if (
                str(existing.get("status") or "").lower() == "blocked_by_dependency"
                and task_dependencies_are_terminal(existing, by_id)
            ):
                replacement = task_for_group(group, now)
                if str(existing.get("id") or group["task_id"]) not in updated:
                    updated.append(str(existing.get("id") or group["task_id"]))
                if apply:
                    for key in (
                        "status",
                        "recommended_agent",
                        "requires_human_attention",
                        "worker_ready",
                        "packet_status",
                        "normalization_status",
                        "dispatcher_decision",
                        "integration_status",
                        "next_owner",
                        "next_role",
                        "updated_at",
                    ):
                        existing[key] = replacement[key]
                    existing["next_action"] = "Dispatcher must process the newer PR head after its predecessor finalized."
                    existing.pop("blocked_by_active_superseded_task", None)
            existing_terminal = str(existing.get("status") or "").lower() in TERMINAL_STATUSES
            previous_head = str(existing.get("repository_hygiene_head_sha") or "").strip().lower()
            coordination_close_ready = bool(
                existing_terminal
                and str(existing.get("integration_status") or "").lower() == "closed_coordination_only"
                and existing.get("classification_recheck") is True
                and current_head
                and current_head == previous_head
                and not existing.get("integration_changed_paths")
                and bool(existing.get("coordination_changed_paths"))
                and task_lock_is_free(existing)
            )
            if coordination_close_ready:
                task_id = str(existing.get("id") or group["task_id"])
                if task_id not in updated:
                    updated.append(task_id)
                if apply:
                    existing.update(
                        {
                            "status": "integration_requested",
                            "integration_status": "needs_coordination_source_pr_close",
                            "finalization_status": "blocked_source_pr_open",
                            "dispatcher_decision": "needs_integrator_review",
                            "packet_status": "integration_ready",
                            "worker_ready": False,
                            "lock": "free",
                            "next_owner": "Integrator",
                            "next_role": "integrator_review",
                            "source_pr_close_status": "pending",
                            "source_pr_close_mode": "coordination_only",
                            "source_pr_close_retry_count": int(existing.get("source_pr_close_retry_count") or 0),
                            "next_action": "Auto Integrator must close the exact-head source PR without applying coordination-only payload.",
                            "updated_at": now,
                        }
                    )
                continue
            must_preserve_existing = existing_terminal or not task_allows_head_refresh(existing)
            needs_coordination_recheck = bool(
                existing_terminal
                and str(existing.get("integration_status") or "").lower() == "closed_coordination_only"
                and group.get("route") == "dispatcher_integration"
                and not existing.get("classification_recheck")
            )
            if must_preserve_existing and current_head and (current_head != previous_head or needs_coordination_recheck):
                followup_id = recheck_task_id(group) if needs_coordination_recheck else revision_task_id(group)
                revision_group = {**group, "task_id": followup_id}
                allocated_id = available_task_id(revision_group, used_ids)
                used_ids.add(allocated_id)
                staged.append(allocated_id)
                if apply:
                    revision = task_for_group(group, now, task_id=allocated_id)
                    revision["supersedes_task_id"] = existing.get("id") or group["task_id"]
                    revision["late_head_detected"] = True
                    if needs_coordination_recheck:
                        revision["classification_recheck"] = True
                        revision["late_head_detected"] = False
                    if not existing_terminal:
                        revision["depends_on"] = [existing.get("id") or group["task_id"]]
                        revision["blocked_by_active_superseded_task"] = True
                        revision.update({
                            "status": "blocked_by_dependency",
                            "packet_status": "blocked_by_dependency",
                            "normalization_status": "blocked_by_dependency",
                            "dispatcher_decision": "blocked_by_dependency",
                            "integration_status": "blocked_by_dependency",
                            "worker_ready": False,
                            "next_action": "Wait for the predecessor integration task before processing the newer PR head.",
                        })
                    revision["source_summary"] = (
                        "open PR requires reclassification after an earlier coordination-only terminal result"
                        if needs_coordination_recheck
                        else "open PR head changed after the previous integration task reached a terminal state"
                        if existing_terminal
                        else "open PR head changed while the previous integration task remained active"
                    )
                    tasks.append(revision)
            elif not existing_terminal:
                replacement = task_for_group(group, now)
                route_changed = any(
                    bool(str(existing.get(key) or "").strip())
                    and existing.get(key) != replacement.get(key)
                    for key in ("base_branch", "repository_hygiene_route")
                )
                changed = any(
                    existing.get(key) != replacement.get(key)
                    for key in (
                        "branch",
                        "base_branch",
                        "repository_hygiene_route",
                        "repository_hygiene_head_sha",
                        "repository_hygiene_dirty_prs",
                        "repository_hygiene_draft_prs",
                        "repository_hygiene_ci_state",
                        "pr_numbers",
                    )
                )
                if changed:
                    updated.append(str(existing.get("id") or group["task_id"]))
                    if apply:
                        for key in (
                            "branch",
                            "base_branch",
                            "repository_hygiene_route",
                            "repository_hygiene_head_sha",
                            "repository_hygiene_dirty_prs",
                            "repository_hygiene_draft_prs",
                            "repository_hygiene_ci_state",
                            "pr_numbers",
                            "source_summary",
                            "updated_at",
                        ):
                            existing[key] = replacement[key]
                        if route_changed:
                            for key in (
                                "status",
                                "recommended_agent",
                                "requires_human_attention",
                                "worker_ready",
                                "packet_status",
                                "normalization_status",
                                "dispatcher_decision",
                                "integration_status",
                                "next_owner",
                                "next_role",
                            ):
                                existing[key] = replacement[key]
                            if replacement["requires_human_attention"]:
                                existing["status_reason"] = replacement["source_summary"]
                            else:
                                existing.pop("status_reason", None)
            continue
        if numbers and numbers <= covered_by_other:
            covered.append(group["task_id"])
            continue
        allocated_id = available_task_id(group, used_ids)
        used_ids.add(allocated_id)
        staged.append(allocated_id)
        if apply:
            tasks.append(task_for_group(group, now, task_id=allocated_id))

    changed = bool(staged or updated or superseded)
    if apply and changed:
        queue["updated_at"] = now
    return {
        "staged_count": len(staged),
        "updated_count": len(updated),
        "covered_count": len(covered),
        "superseded_count": len(superseded),
        "staged_task_ids": staged,
        "updated_task_ids": updated,
        "covered_task_ids": covered,
        "superseded_task_ids": superseded,
        "changed": changed,
    }


def exact_commit_sha(value: Any) -> str | None:
    candidate = str(value or "").strip().lower()
    if len(candidate) != 40 or any(char not in "0123456789abcdef" for char in candidate):
        return None
    return candidate


def resolve_commit(project_root: Path, ref: str) -> str | None:
    proc = git(project_root, ["rev-parse", "--verify", f"{ref}^{{commit}}"])
    if proc.returncode != 0:
        return None
    return exact_commit_sha(proc.stdout)


def git_is_ancestor(project_root: Path, ancestor_sha: str, descendant_ref: str) -> bool:
    return git(project_root, ["merge-base", "--is-ancestor", ancestor_sha, descendant_ref]).returncode == 0


def active_lock_task_ids(locks: dict[str, Any]) -> set[str]:
    return {
        str(lock.get("task_id") or "").strip()
        for lock in locks.get("locks", []) if isinstance(locks.get("locks"), list)
        if isinstance(lock, dict)
        and str(lock.get("state") or "").lower() in ACTIVE_LOCK_STATES
        and str(lock.get("task_id") or "").strip()
    }


def release_active_task_locks(locks: dict[str, Any] | None, task_id: str, now: str) -> bool:
    if not isinstance(locks, dict) or not isinstance(locks.get("locks"), list):
        return False
    changed = False
    for lock in locks["locks"]:
        if not isinstance(lock, dict) or str(lock.get("task_id") or "").strip() != task_id:
            continue
        if str(lock.get("state") or "").lower() not in ACTIVE_LOCK_STATES:
            continue
        lock.update(
            {
                "state": "released",
                "released_at": now,
                "released_by": "scripts/agent_control/repository_hygiene_cycle.py",
                "release_reason": "active task had no unique payload and was already represented in base",
            }
        )
        changed = True
    if changed:
        locks["updated_at"] = now
    return changed


def task_lock_is_free(task: dict[str, Any]) -> bool:
    lock = task.get("lock")
    state = lock.get("state") if isinstance(lock, dict) else lock
    return str(state or "free").lower() in FREE_LOCK_STATES


def task_has_execution_evidence(task: dict[str, Any]) -> bool:
    return any(
        bool(task.get(field))
        for field in (
            "started_at",
            "worker_id",
            "machine_id",
            "worker_result",
            "worker_result_report",
            "worker_result_commit",
        )
    )


def child_source_head_shas(task: dict[str, Any]) -> set[str]:
    values = {
        exact_commit_sha(task.get("source_head_sha")),
        exact_commit_sha(task.get("clean_rebuild_source_head_sha")),
    }
    provenance = task.get("provenance")
    if isinstance(provenance, dict):
        values.add(exact_commit_sha(provenance.get("source_head_sha")))
    traceability = task.get("traceability")
    if isinstance(traceability, dict) and isinstance(traceability.get("provenance"), dict):
        values.add(exact_commit_sha(traceability["provenance"].get("source_head_sha")))
    return {value for value in values if value}


def reconcile_merged_repository_tasks(
    queue: dict[str, Any],
    merged_prs: list[dict[str, Any]],
    project_root: Path,
    base_ref: str,
    *,
    active_lock_ids: set[str],
    apply: bool,
    now: str,
) -> dict[str, Any]:
    """Finalize stale single-PR parents only from exact GitHub merge evidence."""

    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    base_sha = resolve_commit(project_root, base_ref)
    if base_sha is None:
        return {
            "reconciled_count": 0,
            "reconciled_task_ids": [],
            "protected_task_ids": [],
            "base_ref": base_ref,
            "base_sha": None,
            "changed": False,
        }
    by_number = {
        int(row.get("number") or 0): row
        for row in merged_prs
        if int(row.get("number") or 0) > 0
    }
    reconciled: list[str] = []
    protected: list[str] = []
    ancestry: dict[str, bool] = {}
    for task in tasks:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        if str(task.get("type") or "") != "repository_hygiene_integration":
            continue
        if (
            str(task.get("status") or "").lower() != "stale_or_superseded"
            or str(task.get("status_reason") or "") != "source pull request is no longer open"
        ):
            continue
        pr_numbers = sorted(values_as_pr_numbers(task))
        if len(pr_numbers) != 1:
            continue
        row = by_number.get(pr_numbers[0])
        if not isinstance(row, dict):
            continue
        source_sha = exact_commit_sha(task.get("repository_hygiene_head_sha"))
        merged_head_sha = exact_commit_sha(row.get("headRefOid"))
        raw_merge_commit = row.get("mergeCommit")
        merge_sha = exact_commit_sha(
            raw_merge_commit.get("oid")
            if isinstance(raw_merge_commit, dict)
            else raw_merge_commit
        )
        if source_sha is None or source_sha != merged_head_sha or merge_sha is None:
            protected.append(task_id)
            continue
        if merge_sha not in ancestry:
            ancestry[merge_sha] = git_is_ancestor(project_root, merge_sha, base_sha)
        if not ancestry[merge_sha]:
            protected.append(task_id)
            continue
        if task_id in active_lock_ids or not task_lock_is_free(task):
            protected.append(task_id)
            continue
        reconciled.append(task_id)
        if not apply:
            continue
        task.update(
            {
                "status": "done",
                "integration_status": "finalized",
                "finalization_status": "finalized",
                "dispatcher_decision": "done",
                "packet_status": "done",
                "normalization_status": "finalized",
                "worker_ready": False,
                "lock": "free",
                "next_owner": "none",
                "next_role": "none",
                "merge_commit": merge_sha,
                "source_pr_close_status": "closed",
                "source_pr_closed_numbers": pr_numbers,
                "finalized_at": now,
                "finalized_by": "repository_hygiene_cycle",
                "updated_at": now,
                "status_reason": "source pull request merge commit is an ancestor of the integration base",
                "repository_hygiene_integration_evidence": {
                    "status": "integrated",
                    "proof": "github_merge_commit_ancestor",
                    "source_head_sha": source_sha,
                    "merge_commit": merge_sha,
                    "merged_at": row.get("mergedAt"),
                    "base_ref": base_ref,
                    "base_sha": base_sha,
                    "checked_at": now,
                },
            }
        )
    changed = bool(reconciled)
    if apply and changed:
        queue["updated_at"] = now
    return {
        "reconciled_count": len(reconciled),
        "reconciled_task_ids": sorted(reconciled),
        "protected_task_ids": sorted(set(protected)),
        "base_ref": base_ref,
        "base_sha": base_sha,
        "changed": changed,
    }


def reconcile_integrated_superseded_children(
    queue: dict[str, Any],
    project_root: Path,
    base_ref: str,
    *,
    candidate_parent_ids: set[str],
    active_pr_numbers: set[int],
    active_lock_ids: set[str],
    locks: dict[str, Any] | None = None,
    apply: bool,
    now: str,
) -> dict[str, Any]:
    """Close decomposition children whose source payload is already on base.

    Active children without unique payload can be safely reconciled so stale active
    claims do not block cleanup. Active children with unique worker evidence are
    preserved for Integrator review.
    """

    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    base_sha = resolve_commit(project_root, base_ref)
    if base_sha is None:
        return {
            "resolved_count": 0,
            "resolved_task_ids": [],
            "protected_task_ids": [],
            "unproven_parent_ids": sorted(candidate_parent_ids),
            "base_ref": base_ref,
            "base_sha": None,
            "changed": False,
        }

    by_id = {
        str(task.get("id") or task.get("task_id") or "").strip(): task
        for task in tasks
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "").strip()
    }
    parents: dict[str, tuple[dict[str, Any], str, dict[str, Any]]] = {}
    unproven_parents: list[str] = []
    ancestry: dict[str, bool] = {}
    for parent_id, parent in by_id.items():
        if str(parent.get("type") or "") != "repository_hygiene_integration":
            continue
        pr_numbers = values_as_pr_numbers(parent)
        if not pr_numbers or pr_numbers & active_pr_numbers:
            continue
        source_sha = exact_commit_sha(parent.get("repository_hygiene_head_sha"))
        merge_sha = exact_commit_sha(parent.get("merge_commit"))
        parent_finalized = (
            str(parent.get("status") or "").lower() in TERMINAL_STATUSES
            and str(parent.get("integration_status") or "").lower()
            in {"finalized", "integrated", "already_integrated"}
            and merge_sha is not None
        )
        if parent_finalized:
            if source_sha is None:
                unproven_parents.append(parent_id)
                continue
            if merge_sha not in ancestry:
                ancestry[merge_sha] = git_is_ancestor(project_root, merge_sha, base_sha)
            if not ancestry[merge_sha]:
                unproven_parents.append(parent_id)
                continue
            parents[parent_id] = (
                parent,
                source_sha,
                {
                    "status": "integrated",
                    "proof": "parent_merge_commit_ancestor",
                    "source_head_sha": source_sha,
                    "parent_task_id": parent_id,
                    "parent_merge_commit": merge_sha,
                },
            )
            continue
        is_closed_candidate = (
            parent_id in candidate_parent_ids
            or (
                str(parent.get("status") or "").lower() == "stale_or_superseded"
                and str(parent.get("status_reason") or "") == "source pull request is no longer open"
            )
        )
        if not is_closed_candidate:
            continue
        if source_sha is None:
            unproven_parents.append(parent_id)
            continue
        if source_sha not in ancestry:
            ancestry[source_sha] = git_is_ancestor(project_root, source_sha, base_sha)
        if not ancestry[source_sha]:
            unproven_parents.append(parent_id)
            continue
        parents[parent_id] = (
            parent,
            source_sha,
            {
                "status": "integrated",
                "proof": "exact_source_head_ancestor",
                "source_head_sha": source_sha,
            },
        )

    resolved: list[str] = []
    released_lock_ids: list[str] = []
    protected: list[str] = []
    for task_id, task in by_id.items():
        if str(task.get("type") or "") != "clean-rebuild":
            continue
        linked_parent_ids = {
            str(task.get(field) or "").strip()
            for field in (
                "parent_task_id",
                "source_task_id",
                "repair_source_task_id",
                "integration_repair_parent_id",
            )
            if str(task.get(field) or "").strip()
        }
        parent_ids = sorted(linked_parent_ids & set(parents))
        if len(parent_ids) != 1:
            continue
        parent_id = parent_ids[0]
        _, source_sha, proof = parents[parent_id]
        if child_source_head_shas(task) != {source_sha}:
            protected.append(task_id)
            continue
        status = str(task.get("status") or "").lower()
        integration_status = str(task.get("integration_status") or "").lower()
        parent_merge_proven = proof["proof"] == "parent_merge_commit_ancestor"
        handoff_reference_payload = any(
            task.get(field)
            for field in (
                "branch", "github_branch", "worker_branch", "synced_from_worker_branch",
                "pr", "github_pr", "pull_request",
            )
        ) and branch_cleanup_planner.integrator_waiting_reference(
            {
                "type": "task_queue_active",
                "task_status": status,
                "next_owner": task.get("next_owner") or task.get("next_role"),
                "integration_status": integration_status,
                "worker_result_present": bool(
                    task.get("worker_result_commit")
                    or task.get("head_sha")
                    or task.get("worker_report")
                    or task.get("last_agent_report")
                ),
            }
        )
        has_unique_payload = bool(
            task.get("worker_result")
            or task.get("worker_report")
            or task.get("worker_result_report")
            or task.get("last_agent_report")
            or task.get("integration_report")
            or task.get("worker_result_commit")
            or task.get("head_sha")
            or (isinstance(task.get("commits"), list) and bool(task.get("commits")))
            or task.get("worker_result_evidence")
            or task.get("worker_result_payload")
            or handoff_reference_payload
        )
        child_is_actively_executing = bool(
            status in {"claimed", "in_progress", "running", "worker_claimed", "agent_working"}
            or task.get("started_at")
            or task.get("claimed_at")
            or task.get("worker_id")
            or task.get("machine_id")
        )
        if has_unique_payload:
            protected.append(task_id)
            continue
        # A canonical active claim may still have a live Worker which has not
        # published its result metadata yet.  Never terminalize the task or
        # release that lease here; a later cycle can reconcile it after the
        # normal worker/recovery path has ended the claim.
        if task_id in active_lock_ids:
            protected.append(task_id)
            continue
        if not child_is_actively_executing:
            if not task_lock_is_free(task) or task_id in active_lock_ids:
                protected.append(task_id)
                continue
            if not parent_merge_proven and (
                status not in PRE_EXECUTION_STATUSES or task_has_execution_evidence(task)
            ):
                protected.append(task_id)
                continue
            if status in TERMINAL_STATUSES:
                protected.append(task_id)
                continue
        resolved.append(task_id)
        if not apply:
            continue
        if release_active_task_locks(locks, task_id, now):
            released_lock_ids.append(task_id)
        evidence = {
            **proof,
            "base_ref": base_ref,
            "base_sha": base_sha,
            "checked_at": now,
        }
        lock = task.get("lock")
        if isinstance(lock, dict):
            lock.update(
                {
                    "state": "free",
                    "by": None,
                    "at": None,
                    "expires_at": None,
                    "released_at": now,
                    "released_by": "scripts/agent_control/repository_hygiene_cycle.py",
                    "release_reason": "active task had no unique payload and was already represented in base",
                }
            )
        else:
            task["lock"] = "free"

        task.update(
            {
                "status": "stale_or_superseded",
                "integration_status": "already_integrated",
                "dispatcher_decision": "stale_or_superseded",
                "packet_status": "stale_or_superseded",
                "normalization_status": "stale_or_superseded",
                "worker_ready": False,
                "lock": "free",
                "next_owner": "none",
                "next_role": "none",
                "repository_hygiene_integration_evidence": evidence,
                "status_reason": (
                    "source task was already finalized into develop"
                    if parent_merge_proven
                    else "source PR payload is already integrated into develop"
                ),
                "not_worker_ready_reason": (
                    "source parent merge commit is already an ancestor of develop"
                    if parent_merge_proven
                    else "exact source head is already an ancestor of develop"
                ),
                "closed_by": "repository_hygiene_cycle",
                "closed_at": now,
                "updated_at": now,
            }
        )
        if parent_merge_proven:
            task["superseded_by_task_id"] = parent_id
            task["superseded_by_merge_commit"] = proof["parent_merge_commit"]

    changed = bool(resolved)
    if apply and changed:
        queue["updated_at"] = now
    return {
        "resolved_count": len(resolved),
        "resolved_task_ids": sorted(resolved),
        "protected_task_ids": sorted(set(protected)),
        "unproven_parent_ids": sorted(set(unproven_parents)),
        "base_ref": base_ref,
        "base_sha": base_sha,
        "changed": changed,
        "released_lock_ids": sorted(released_lock_ids),
        "locks_changed": bool(released_lock_ids),
    }


def reconcile_finalized_repair_parents(
    queue: dict[str, Any],
    *,
    apply: bool,
    now: str,
) -> dict[str, Any]:
    """Route a repaired hygiene parent to source-PR closure.

    Dispatcher leaves the parent blocked while its clean-rebuild children run.
    Once every linked child is finalized, the payload is already on develop and
    only verified source-PR closure remains.  Keep that transition explicit so
    Scheduler can re-enter Auto Integrator without rebuilding the payload again.
    """

    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    by_id = {
        str(task.get("id") or task.get("task_id") or "").strip(): task
        for task in tasks
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "").strip()
    }
    reconciled: list[str] = []
    waiting: list[str] = []
    for parent_id, parent in by_id.items():
        if str(parent.get("type") or "") != "repository_hygiene_integration":
            continue
        if str(parent.get("status") or "").lower() != "blocked_by_dependency":
            continue
        if str(parent.get("integration_status") or "").lower() != "repair_in_progress":
            continue
        child_ids = sorted(
            {
                str(value).strip()
                for value in (
                    parent.get("integration_repair_child_ids")
                    or parent.get("split_into")
                    or []
                )
                if str(value or "").strip()
            }
        )
        if not child_ids:
            continue
        blocked_by = {
            str(value).strip()
            for value in parent.get("blocked_by") or []
            if str(value or "").strip()
        }
        if blocked_by - set(child_ids):
            waiting.append(parent_id)
            continue
        children = [by_id.get(child_id) for child_id in child_ids]
        if any(not isinstance(child, dict) for child in children):
            waiting.append(parent_id)
            continue
        linked = all(
            parent_id
            in {
                str(child.get("parent_task_id") or ""),
                str(child.get("source_task_id") or ""),
                str(child.get("repair_source_task_id") or ""),
                str(child.get("integration_repair_parent_id") or ""),
            }
            for child in children
            if isinstance(child, dict)
        )
        def is_coordination_only(child: dict[str, Any]) -> bool:
            return (
                str(child.get("status") or "").lower() == "stale_or_superseded"
                and str(child.get("integration_status") or "").lower()
                == "closed_coordination_only"
            )

        finalized = all(
            is_coordination_only(child)
            or (
                str(child.get("status") or "").lower() in TERMINAL_STATUSES
                and str(child.get("integration_status") or "").lower()
                in {"finalized", "integrated", "already_integrated"}
                and bool(str(child.get("merge_commit") or "").strip())
            )
            for child in children
            if isinstance(child, dict)
        )
        if not linked or not finalized:
            waiting.append(parent_id)
            continue
        merge_commits = sorted(
            {
                str(child.get("merge_commit") or "").strip()
                for child in children
                if isinstance(child, dict)
                and not is_coordination_only(child)
                and str(child.get("merge_commit") or "").strip()
            }
        )
        if not merge_commits or not values_as_pr_numbers(parent):
            waiting.append(parent_id)
            continue
        reconciled.append(parent_id)
        if not apply:
            continue
        resolved_dependencies = {
            str(value).strip()
            for value in parent.get("resolved_dependencies") or []
            if str(value or "").strip()
        }
        parent.update(
            {
                "status": "integration_requested",
                "integration_status": "needs_source_pr_close",
                "finalization_status": "blocked_source_pr_open",
                "dispatcher_decision": "needs_integrator_review",
                "packet_status": "integration_requested",
                "worker_ready": False,
                "lock": "free",
                "blocked_by": [],
                "resolved_dependencies": sorted(resolved_dependencies | set(child_ids)),
                "merge_commit": merge_commits[-1],
                "integration_repair_merge_commits": merge_commits,
                "source_pr_close_status": "pending",
                "source_pr_close_retry_count": int(parent.get("source_pr_close_retry_count") or 0),
                "next_owner": "Integrator",
                "next_role": "integrator_review",
                "requires_human_attention": False,
                "next_action": "Auto Integrator must close and verify the source PRs without reapplying the finalized repair payload.",
                "status_reason": "all integration repair children finalized; source PR closure pending",
                "repair_completed_at": now,
                "updated_at": now,
            }
        )
    changed = bool(reconciled)
    if apply and changed:
        queue["updated_at"] = now
    return {
        "reconciled_count": len(reconciled),
        "waiting_count": len(waiting),
        "reconciled_task_ids": reconciled,
        "waiting_task_ids": waiting,
        "changed": changed,
    }


def recovery_key(repo: str, branch: str) -> str:
    raw = f"{repo}|{branch}"
    return "repository-branch-recovery:" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def recovery_task_id(key: str) -> str:
    return "REPO-RECOVERY-" + hashlib.sha256(key.encode("utf-8")).hexdigest()[:20].upper()


def recovery_task_for(repo: str, row: dict[str, Any], now: str) -> dict[str, Any]:
    branch = str(row.get("name") or "")
    key = recovery_key(repo, branch)
    evidence = row.get("integration_evidence") if isinstance(row.get("integration_evidence"), dict) else {}
    task_ids = sorted({str(value) for value in evidence.get("terminal_task_ids") or [] if str(value)})
    return {
        "id": recovery_task_id(key),
        "title": f"Reconcile unintegrated branch {branch}",
        "status": "needs_dispatcher_repair",
        "priority": "P1",
        "complexity": "M",
        "type": "repository_hygiene_branch_recovery",
        "recommended_agent": "Dispatcher",
        "worker_ready": False,
        "packet_status": "needs_dispatcher_repair",
        "normalization_status": "repository_hygiene_recovery",
        "dispatcher_decision": "needs_dispatcher_repair",
        "integration_status": "needs_dispatcher",
        "repair_request": (
            "Reconcile the source branch against current develop and record an "
            "integration or archive disposition before any worker claim."
        ),
        "missing_packet_fields": [
            "branch_reconciliation_evidence",
            "integration_disposition",
        ],
        "repair_owner": "dispatcher",
        "next_action": (
            "Dispatcher must compare the exact source tip with current develop "
            "and route it to Integrator, clean rebuild, archive, or explicit review."
        ),
        "branch": f"origin/{branch}",
        "source_branch": branch,
        "base_branch": "develop",
        "repository_recovery_key": key,
        "repository_hygiene_classification": row.get("classification"),
        "repository_hygiene_head_sha": row.get("sha"),
        "repository_hygiene_integration_evidence": evidence,
        "linked_task_ids": task_ids,
        "source_lane": "repository_hygiene_cycle",
        "source_summary": row.get("reason"),
        "provenance": [
            {
                "source_type": "git_branch_reconciliation",
                "source_item_id": key,
                "captured_at": now,
                "summary": row.get("reason"),
            }
        ],
        "acceptance_criteria": [
            "Protect the branch while any Codex task, worktree, PR, lock, or nonterminal task still references it.",
            "Prove direct ancestry, exact patch equivalence, or capability equivalence against current develop.",
            "Integrate every unique functional change or record an evidence-backed no-product-payload disposition.",
            "Only then mark the source branch eligible for archive-first cleanup.",
        ],
        "checks": [
            "git ancestry and git cherry comparison",
            "capability preservation comparison for unique functional paths",
            "current PR, queue, lock, worktree, and Codex activity refresh",
        ],
        "lock": "free",
        "next_owner": "dispatcher",
        "next_role": "auto_dispatcher",
        "discovered_at": now,
        "updated_at": now,
    }


def apply_recovery_tasks(
    queue: dict[str, Any],
    repo: str,
    branch_rows: list[dict[str, Any]],
    *,
    apply: bool,
    now: str,
    max_stage_count: int | None = None,
    allowed_task_ids: set[str] | None = None,
) -> dict[str, Any]:
    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        raise ValueError("task queue must contain a tasks array")
    all_logical_rows: dict[str, dict[str, Any]] = {}
    for row in branch_rows:
        if not isinstance(row, dict):
            continue
        branch = str(row.get("name") or "")
        if not branch:
            continue
        current = all_logical_rows.get(branch)
        if current is None or row.get("ref_kind") == "remote":
            all_logical_rows[branch] = row
    logical_rows = {
        branch: row
        for branch, row in all_logical_rows.items()
        if row.get("classification") == "integration_recovery_candidate"
    }
    existing_by_key = {
        str(task.get("repository_recovery_key")): task
        for task in tasks
        if isinstance(task, dict) and task.get("repository_recovery_key")
    }
    staged: list[str] = []
    deferred: list[str] = []
    updated: list[str] = []
    covered: list[str] = []
    resolved: list[str] = []
    resolved_branches: dict[str, tuple[dict[str, Any], dict[str, Any]]] = {}
    for branch, row in sorted(logical_rows.items()):
        desired = recovery_task_for(repo, row, now)
        if allowed_task_ids is not None and str(desired["id"]) not in allowed_task_ids:
            continue
        key = str(desired["repository_recovery_key"])
        existing = existing_by_key.get(key)
        if existing is None:
            if max_stage_count is not None and len(staged) >= max(0, max_stage_count):
                deferred.append(str(desired["id"]))
                continue
            staged.append(str(desired["id"]))
            if apply:
                tasks.append(desired)
            continue
        covered.append(str(existing.get("id") or desired["id"]))
        if str(existing.get("status") or "").lower() in TERMINAL_STATUSES:
            continue
        mutable = (
            "branch",
            "source_branch",
            "repository_hygiene_classification",
            "repository_hygiene_head_sha",
            "repository_hygiene_integration_evidence",
            "linked_task_ids",
            "source_summary",
            "repair_request",
            "missing_packet_fields",
            "repair_owner",
            "next_action",
            "updated_at",
        )
        if any(existing.get(key_name) != desired.get(key_name) for key_name in mutable[:-1]):
            updated.append(str(existing.get("id") or desired["id"]))
            if apply:
                for key_name in mutable:
                    existing[key_name] = desired[key_name]

    for task in tasks:
        if not isinstance(task, dict) or not task.get("repository_recovery_key"):
            continue
        if str(task.get("status") or "").lower() in TERMINAL_STATUSES:
            continue
        branch = str(task.get("source_branch") or "")
        row = all_logical_rows.get(branch)
        if row is None or row.get("classification") == "integration_recovery_candidate":
            continue
        evidence = row.get("integration_evidence") if isinstance(row.get("integration_evidence"), dict) else {}
        proven_integrated = bool(
            row.get("merged_into_develop")
            or row.get("merged_into_release_main")
            or (
                row.get("classification") == "archive_candidate"
                and evidence.get("status") == "integrated"
            )
        )
        if not proven_integrated:
            continue
        task_id = str(task.get("id") or recovery_task_id(str(task["repository_recovery_key"])))
        resolved.append(task_id)
        resolved_branches[branch] = (row, evidence)
        if apply:
            task.update({
                "status": "stale_or_superseded",
                "integration_status": "already_integrated",
                "dispatcher_decision": "stale_or_superseded",
                "packet_status": "stale_or_superseded",
                "worker_ready": False,
                "repository_hygiene_classification": row.get("classification"),
                "repository_hygiene_head_sha": row.get("sha"),
                "repository_hygiene_integration_evidence": evidence,
                "status_reason": "repository hygiene independently proved the source branch integrated",
                "closed_by": "repository_hygiene_cycle",
                "closed_at": now,
                "updated_at": now,
            })

    resolved_repair_tasks: list[str] = []
    for task in tasks:
        if not isinstance(task, dict) or task.get("type") != "clean-rebuild":
            continue
        if str(task.get("status") or "").lower() in TERMINAL_STATUSES:
            continue
        branch = str(task.get("source_branch") or "").removeprefix("origin/")
        resolved_branch = resolved_branches.get(branch)
        if resolved_branch is None:
            continue
        lock = task.get("lock") if isinstance(task.get("lock"), dict) else {}
        lock_state = str(lock.get("state") or "free").lower()
        status = str(task.get("status") or "").lower()
        if status not in {"planned", "todo", "ready", "worker_ready"} or lock_state not in {"", "free", "released", "unlocked"}:
            continue
        row, evidence = resolved_branch
        task_id = str(task.get("id") or task.get("task_id") or "")
        if not task_id:
            continue
        resolved.append(task_id)
        resolved_repair_tasks.append(task_id)
        if apply:
            task.update({
                "status": "stale_or_superseded",
                "integration_status": "already_integrated",
                "dispatcher_decision": "stale_or_superseded",
                "packet_status": "stale_or_superseded",
                "worker_ready": False,
                "repository_hygiene_classification": row.get("classification"),
                "repository_hygiene_integration_evidence": evidence,
                "status_reason": "repository hygiene independently proved the clean-rebuild source branch integrated",
                "closed_by": "repository_hygiene_cycle",
                "closed_at": now,
                "updated_at": now,
            })
    changed = bool(staged or updated or resolved)
    return {
        "staged_count": len(staged),
        "deferred_count": len(deferred),
        "updated_count": len(updated),
        "covered_count": len(covered),
        "resolved_count": len(resolved),
        "resolved_repair_count": len(resolved_repair_tasks),
        "staged_task_ids": staged,
        "deferred_task_ids": deferred,
        "updated_task_ids": updated,
        "covered_task_ids": covered,
        "resolved_task_ids": resolved,
        "resolved_repair_task_ids": resolved_repair_tasks,
        "changed": changed,
    }


def append_queue_event(project_root: Path, task_report: dict[str, Any], groups: list[dict[str, Any]], now: str) -> dict[str, Any]:
    changed_ids = sorted(
        {
            *task_report.get("staged_task_ids", []),
            *task_report.get("updated_task_ids", []),
            *task_report.get("superseded_task_ids", []),
        }
    )
    if not changed_ids:
        return {"event_appended": False, "reason": "no_task_changes"}
    fingerprint = "|".join(
        [*changed_ids, *(str(group.get("candidate_head_sha") or "") for group in groups)]
    )
    event_id = "repository-hygiene-" + hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()[:20]
    path = task_file(project_root, "agent_events.jsonl")
    if path.exists() and any(f'"event_id": "{event_id}"' in line for line in path.read_text(encoding="utf-8").splitlines()):
        return {"event_appended": False, "reason": "event_already_exists", "event_id": event_id}
    event = {
        "schema_version": 1,
        "event_id": event_id,
        "event": "queue_changed",
        "event_type": "queue_changed",
        "created_at": now,
        "source": "repository_hygiene_cycle",
        "role": "repository_hygiene",
        "next_owner": "Dispatcher",
        "reason": "repository PR or branch reconciliation produced or refreshed integration tasks",
        "payload": {"task_ids": changed_ids, "group_count": len(groups), "route": "dispatcher_repair"},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
    return {"event_appended": True, "event_id": event_id, "path": str(path)}


def append_repair_parent_events(
    project_root: Path,
    queue: dict[str, Any],
    task_ids: list[str],
    now: str,
) -> dict[str, Any]:
    if not task_ids:
        return {"event_count": 0, "event_ids": [], "reason": "no_reconciled_repair_parents"}
    tasks = queue.get("tasks") if isinstance(queue, dict) else []
    by_id = {
        str(task.get("id") or task.get("task_id") or "").strip(): task
        for task in tasks or []
        if isinstance(task, dict)
    }
    path = task_file(project_root, "agent_events.jsonl")
    existing = set()
    if path.exists():
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict) and row.get("event_id"):
                existing.add(str(row["event_id"]))
    event_ids: list[str] = []
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for task_id in sorted(set(task_ids)):
            task = by_id.get(task_id) or {}
            merge_commit = str(task.get("merge_commit") or "").strip()
            fingerprint = f"{task_id}|{merge_commit}|source-pr-close"
            event_id = "integrator-review-required-" + hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()[:20]
            if event_id in existing:
                continue
            event = {
                "schema_version": 1,
                "event_id": event_id,
                "event": "integrator_review_required",
                "created_at": now,
                "canonical_target_id": f"task:{task_id}",
                "project": project_root.name,
                "task_id": task_id,
                "role": "repository_hygiene",
                "next_owner": "Integrator",
                "severity": "info",
                "reason": "finalized repair payload is on develop; source PR closure and verification remain",
                "payload": {
                    "classification": "needs_source_pr_close",
                    "next_owner": "Integrator",
                    "merge_commit": merge_commit,
                    "pr_numbers": sorted(values_as_pr_numbers(task)),
                    "repair_child_ids": list(task.get("integration_repair_child_ids") or []),
                },
            }
            handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
            existing.add(event_id)
            event_ids.append(event_id)
    return {"event_count": len(event_ids), "event_ids": event_ids, "path": str(path)}


def release_base(project_root: Path, remote: str) -> str:
    for name in ("release", "release/main"):
        if git(project_root, ["rev-parse", "--verify", "--quiet", f"{remote}/{name}"]).returncode == 0:
            return name
    return "release/main"


def build_branch_cleanup_report(
    project_root: Path,
    remote: str,
    base: str,
    codex_activity_dir: Path | None = None,
    expected_codex_hosts: list[str] | None = None,
    codex_activity_max_age_seconds: int = 300,
    evidence_cache: Path | None = None,
) -> dict[str, Any]:
    args = SimpleNamespace(
        project_root=str(project_root),
        base=base.removeprefix(f"{remote}/"),
        release_base=release_base(project_root, remote),
        remote=remote,
        stale_days=14,
        archive_prefix="archive/branches",
        include_local=True,
        include_remote=True,
        fetch=False,
        deep_metrics=False,
        codex_activity_dir=str(codex_activity_dir) if codex_activity_dir else None,
        expected_codex_host=list(expected_codex_hosts or []),
        codex_activity_max_age_seconds=codex_activity_max_age_seconds,
        evidence_cache=str(evidence_cache) if evidence_cache else None,
    )
    return branch_cleanup_planner.build_report(args)


def compact_cleanup(report: dict[str, Any]) -> dict[str, Any]:
    actionable = {
        "merged_safe_delete",
        "archive_candidate",
        "dirty_worker_candidate",
        "cleanup_candidate",
        "unknown_needs_review",
        "integration_recovery_candidate",
    }
    candidates: list[dict[str, Any]] = []
    candidate_keys: set[tuple[str, str]] = set()
    for row in report.get("branches") or []:
        classification = str(row.get("classification") or "")
        name = str(row.get("name") or "")
        key = (classification, name)
        if classification not in actionable or not name or key in candidate_keys:
            continue
        candidate_keys.add(key)
        candidates.append(
            {
                "name": name,
                "classification": classification,
                "recommended_action": row.get("recommended_action"),
                "reason": row.get("reason"),
                "open_pr": (row.get("open_pr") or {}).get("number") if isinstance(row.get("open_pr"), dict) else None,
            }
        )
    counts = report.get("counts") or {}
    by_classification = {
        classification: sorted({
            str(row.get("name"))
            for row in report.get("branches") or []
            if row.get("classification") == classification and row.get("name")
        })
        for classification in (
            "merged_safe_delete",
            "archive_candidate",
            "dirty_worker_candidate",
            "cleanup_candidate",
            "unknown_needs_review",
            "integration_recovery_candidate",
        )
    }
    return {
        "counts": counts,
        "logical_counts": {
            classification: len(values)
            for classification, values in by_classification.items()
        },
        "pr_evidence_available": report.get("pr_evidence_available"),
        "warnings": report.get("warnings") or [],
        "actionable_count": sum(len(by_classification.get(key) or []) for key in actionable),
        "actionable_ref_count": sum(int(counts.get(key) or 0) for key in actionable),
        "candidate_sample": candidates[:100],
        "candidate_sample_truncated": len(candidates) > 100,
        "merged_safe_delete_branches": by_classification["merged_safe_delete"],
        "archive_branches": by_classification["archive_candidate"],
        "grace_cleanup_branches": by_classification["cleanup_candidate"],
        "unknown_review_branches": by_classification["unknown_needs_review"],
        "integration_recovery_branches": by_classification["integration_recovery_candidate"],
        "salvage_branches": sorted(
            set(by_classification["dirty_worker_candidate"])
            | set(by_classification["integration_recovery_candidate"])
        ),
        "destructive_apply_performed": False,
        "apply_gate": "cleanup_merged_branches.py deterministic protection checks and project policy",
    }


def state_signature(report: dict[str, Any]) -> str:
    stable = {
        "repo": report.get("repo"),
        "base_ref": report.get("base_ref"),
        "open_pr_count": report.get("open_pr_count"),
        "groups": report.get("groups"),
        "task_routing": {
            key: (report.get("task_routing") or {}).get(key)
            for key in (
                "staged_task_ids",
                "updated_task_ids",
                "covered_task_ids",
                "superseded_task_ids",
            )
        },
        "branch_cleanup": {
            "counts": (report.get("branch_cleanup") or {}).get("counts"),
            "pr_evidence_available": (report.get("branch_cleanup") or {}).get("pr_evidence_available"),
            "candidate_sample": (report.get("branch_cleanup") or {}).get("candidate_sample"),
            "merged_safe_delete_branches": (report.get("branch_cleanup") or {}).get("merged_safe_delete_branches"),
            "archive_branches": (report.get("branch_cleanup") or {}).get("archive_branches"),
            "salvage_branches": (report.get("branch_cleanup") or {}).get("salvage_branches"),
            "grace_cleanup_branches": (report.get("branch_cleanup") or {}).get("grace_cleanup_branches"),
            "unknown_review_branches": (report.get("branch_cleanup") or {}).get("unknown_review_branches"),
            "integration_recovery_branches": (report.get("branch_cleanup") or {}).get("integration_recovery_branches"),
            "codex_activity": report.get("codex_activity"),
        },
    }
    raw = json.dumps(stable, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def run_cycle(
    *,
    project_root: Path,
    repo: str,
    remote: str = "origin",
    base_ref: str = "origin/develop",
    prs: list[dict[str, Any]] | None = None,
    merged_prs: list[dict[str, Any]] | None = None,
    apply: bool = False,
    apply_safe_cleanup: bool = False,
    max_safe_delete_count: int = 20,
    max_safe_archive_count: int = 20,
    fetch: bool = False,
    output: Path | None = None,
    codex_activity_dir: Path | None = None,
    expected_codex_hosts: list[str] | None = None,
    codex_activity_max_age_seconds: int = 300,
    evidence_cache: Path | None = None,
    skip_branch_cleanup: bool = False,
    worktree_retirement_stale_days: int = 14,
    apply_worktree_retirement: bool = False,
    worktree_archive_root: str | None = None,
    worktree_archive_ssh_host: str | None = None,
    max_worktree_retire_count: int = 10,
    max_branch_recovery_task_count: int = 10,
) -> dict[str, Any]:
    project_root = project_root.resolve()
    now = utc_now()
    fetch_report = fetch_refs(project_root, remote) if fetch else {"skipped": True}
    if fetch and int(fetch_report.get("exit_code") or 0) != 0:
        raise RuntimeError(str(fetch_report.get("stderr") or "git fetch failed"))
    open_prs = [normalize_pr(row) for row in (prs if prs is not None else load_open_prs(repo))]
    merged_pr_rows = (
        merged_prs
        if merged_prs is not None
        else load_merged_prs(repo)
        if prs is None
        else []
    )
    groups = build_pr_groups(
        open_prs,
        repo,
        integration_base=integration_base_from_ref(base_ref, remote),
    )
    queue_path = task_file(project_root, "task_queue.json")
    queue = load_json(queue_path, {"schema_version": 1, "tasks": []})
    task_report = apply_group_tasks(queue, groups, apply=apply, now=now)
    locks_path = task_file(project_root, "agent_locks.json")
    locks = load_json(locks_path, {"schema_version": 1, "locks": []})
    merged_parent_report = reconcile_merged_repository_tasks(
        queue,
        merged_pr_rows,
        project_root,
        base_ref,
        active_lock_ids=active_lock_task_ids(locks),
        apply=apply,
        now=now,
    )
    task_report["merged_parent_reconciliation"] = merged_parent_report
    task_report["updated_task_ids"] = sorted({
        *(task_report.get("updated_task_ids") or []),
        *(merged_parent_report.get("reconciled_task_ids") or []),
    })
    task_report["updated_count"] = len(task_report["updated_task_ids"])
    task_report["changed"] = bool(task_report.get("changed") or merged_parent_report.get("changed"))
    descendant_report = reconcile_integrated_superseded_children(
        queue,
        project_root,
        base_ref,
        candidate_parent_ids=set(task_report.get("superseded_task_ids") or []),
        active_pr_numbers={number for group in groups for number in group.get("pr_numbers") or []},
        active_lock_ids=active_lock_task_ids(locks),
        locks=locks,
        apply=apply,
        now=now,
    )
    task_report["integrated_descendant_reconciliation"] = descendant_report
    task_report["superseded_task_ids"] = sorted(
        {
            *(task_report.get("superseded_task_ids") or []),
            *(descendant_report.get("resolved_task_ids") or []),
        }
    )
    task_report["superseded_count"] = len(task_report["superseded_task_ids"])
    task_report["changed"] = bool(task_report.get("changed") or descendant_report.get("changed"))
    repair_parent_report = reconcile_finalized_repair_parents(queue, apply=apply, now=now)
    task_report["repair_parent_reconciliation"] = repair_parent_report
    task_report["updated_task_ids"] = sorted({
        *(task_report.get("updated_task_ids") or []),
        *(repair_parent_report.get("reconciled_task_ids") or []),
    })
    task_report["updated_count"] = len(task_report["updated_task_ids"])
    task_report["changed"] = bool(task_report.get("changed") or repair_parent_report.get("changed"))
    default_activity_dir = project_root / "runtime" / "agent-control" / "codex-active-work" / "hosts"
    resolved_activity_dir = codex_activity_dir or Path(
        os.environ.get("AISTUDIO_CODEX_ACTIVITY_DIR") or default_activity_dir
    )
    configured_hosts = list(expected_codex_hosts or [])
    if not configured_hosts:
        configured_hosts = [
            item.strip()
            for item in os.environ.get("AISTUDIO_CODEX_ACTIVITY_EXPECTED_HOSTS", "").split(",")
            if item.strip()
        ]
    cleanup_report = (
        {
            "branches": [],
            "counts": {},
            "pr_evidence_available": True,
            "warnings": [],
            "codex_activity": {},
            "skipped": True,
            "reason": "fast_pr_registry",
        }
        if skip_branch_cleanup
        else build_branch_cleanup_report(
            project_root,
            remote,
            base_ref,
            resolved_activity_dir,
            configured_hosts,
            codex_activity_max_age_seconds,
            evidence_cache or project_root / "runtime" / "agent-control" / "repository-hygiene-evidence-cache.json",
        )
    )
    cleanup_branches = list(cleanup_report.get("branches") or [])
    archive_evaluation_names = (
        set()
        if skip_branch_cleanup
        else branch_lifecycle_scanner.archive_evaluation_names(
            cleanup_report,
            stale_days=max(0, worktree_retirement_stale_days),
        )
    )
    worktree_retirement_report = (
        {
            "schema_version": "1.0",
            "skipped": True,
            "reason": "fast_pr_registry",
            "candidate_count": 0,
            "eligible_count": 0,
            "blocked_count": 0,
            "worktrees": [],
        }
        if skip_branch_cleanup
        else worktree_retirement.build_plan(
            project_root,
            branches=archive_evaluation_names,
            open_pr_heads={str(pr.get("head") or "") for pr in open_prs if pr.get("head")},
            protected_branches={
                str(item.get("name") or "")
                for item in cleanup_branches
                if item.get("classification") == "keep_active"
            },
            min_age_days=max(0, worktree_retirement_stale_days),
            remote=remote,
            include_selected_without_worktree=True,
        )
        if archive_evaluation_names
        else {
            "schema_version": "1.0",
            "generated_at": now,
            "min_age_days": max(0, worktree_retirement_stale_days),
            "base_refs": ["origin/develop", "origin/release/main"],
            "selected_branches": [],
            "candidate_count": 0,
            "eligible_count": 0,
            "blocked_count": 0,
            "worktrees": [],
        }
    )
    branch_lifecycle_scan = branch_lifecycle_scanner.scan(
        cleanup_report,
        worktree_retirement_report,
        stale_days=max(0, worktree_retirement_stale_days),
    )
    worktree_retirement_apply: dict[str, Any] = {
        "skipped": True,
        "reason": "disabled_or_dry_run",
        "retired_count": 0,
        "failed_count": 0,
    }
    if apply and apply_safe_cleanup and apply_worktree_retirement:
        eligible_names = [
            str(item.get("branch") or "")
            for item in worktree_retirement_report.get("worktrees") or []
            if item.get("eligible") and item.get("branch")
        ]
        if not eligible_names:
            worktree_retirement_apply["reason"] = "no_eligible_worktrees"
        elif not (cleanup_report.get("codex_activity") or {}).get("coverage_complete"):
            worktree_retirement_apply["reason"] = "codex_activity_coverage_incomplete"
        elif not worktree_archive_root:
            worktree_retirement_apply["reason"] = "archive_root_required"
        else:
            apply_plan = {**worktree_retirement_report, "selected_branches": sorted(eligible_names)}
            retirement_results = worktree_retirement.apply_plan(
                project_root,
                apply_plan,
                archive_root=worktree_archive_root,
                archive_ssh_host=worktree_archive_ssh_host,
                remote=remote,
                max_count=max(0, max_worktree_retire_count),
                delete_local_branch=True,
                delete_remote_branch=False,
            )
            failed_retirements = [row for row in retirement_results if row.get("status") != "retired"]
            worktree_retirement_apply = {
                "skipped": False,
                "reason": "retirement_applied",
                "candidate_count": len(retirement_results),
                "retired_count": len(retirement_results) - len(failed_retirements),
                "failed_count": len(failed_retirements),
                "results": retirement_results,
            }
    recovery_report = apply_recovery_tasks(
        queue,
        repo,
        branch_lifecycle_scanner.recovery_rows(cleanup_report, branch_lifecycle_scan),
        apply=apply,
        now=now,
        max_stage_count=max(0, max_branch_recovery_task_count),
    )
    task_report["recovery"] = recovery_report
    for key in ("staged_task_ids", "updated_task_ids", "covered_task_ids"):
        task_report[key] = sorted({*(task_report.get(key) or []), *(recovery_report.get(key) or [])})
    task_report["superseded_task_ids"] = sorted({
        *(task_report.get("superseded_task_ids") or []),
        *(recovery_report.get("resolved_task_ids") or []),
    })
    for key in ("staged_count", "updated_count", "covered_count"):
        task_report[key] = len(task_report.get(key.replace("_count", "_task_ids")) or [])
    task_report["changed"] = bool(task_report.get("changed") or recovery_report.get("changed"))
    if apply and task_report["changed"]:
        if descendant_report.get("locks_changed"):
            write_json(locks_path, locks)
        queue["updated_at"] = now
        write_json(queue_path, queue)
        event_report = append_queue_event(project_root, task_report, groups, now)
        repair_parent_event_report = append_repair_parent_events(
            project_root,
            queue,
            list(repair_parent_report.get("reconciled_task_ids") or []),
            now,
        )
    else:
        event_report = {"event_appended": False, "reason": "dry_run" if not apply else "no_task_changes"}
        repair_parent_event_report = {
            "event_count": 0,
            "event_ids": [],
            "reason": "dry_run" if not apply else "no_reconciled_repair_parents",
        }
    compact = compact_cleanup(cleanup_report)
    if skip_branch_cleanup:
        compact.update({"skipped": True, "reason": "fast_pr_registry"})
    cleanup_apply: dict[str, Any] = {"skipped": True, "reason": "disabled_or_dry_run", "deleted_count": 0}
    if apply and apply_safe_cleanup:
        try:
            delete_results = branch_cleanup_planner.apply_delete(project_root, cleanup_report, max_safe_delete_count)
            archive_results = branch_cleanup_planner.apply_archive_and_delete(
                project_root,
                cleanup_report,
                remote,
                max_safe_archive_count,
            )
            failed_deletes = [row for row in delete_results if int(row.get("returncode") or 0) != 0]
            failed_archives = [
                row
                for row in archive_results
                if int(row.get("archive_returncode") or 0) != 0
                or row.get("verified") is not True
                or int(row.get("delete_returncode") if row.get("delete_returncode") is not None else 1) != 0
            ]
            failed = [*failed_deletes, *failed_archives]
            cleanup_apply = {
                "skipped": not delete_results and not archive_results,
                "reason": "no_eligible_cleanup_branches" if not delete_results and not archive_results else "safe_cleanup_applied",
                "merged_candidate_count": len(delete_results),
                "merged_deleted_count": len(delete_results) - len(failed_deletes),
                "archive_candidate_count": len(archive_results),
                "archived_and_deleted_count": len(archive_results) - len(failed_archives),
                "deleted_count": len(delete_results) + len(archive_results) - len(failed),
                "failed_count": len(failed),
                "failed": failed[:20],
            }
            compact["destructive_apply_performed"] = bool(delete_results or archive_results)
        except SystemExit as exc:
            cleanup_apply = {
                "skipped": True,
                "reason": "safe_delete_guard_blocked",
                "deleted_count": 0,
                "error": str(exc),
            }
    report = {
        "schema_version": 1,
        "generated_at": now,
        "project_root": str(project_root),
        "repo": repo,
        "remote": remote,
        "base_ref": base_ref,
        "apply": apply,
        "fetch": fetch_report,
        "open_pr_count": len(open_prs),
        "group_count": len(groups),
        "stack_group_count": sum(1 for group in groups if len(group.get("pr_numbers") or []) > 1),
        "direct_group_count": sum(1 for group in groups if len(group.get("pr_numbers") or []) == 1),
        "dispatcher_group_count": sum(1 for group in groups if group.get("route") == "dispatcher_integration"),
        "needs_human_group_count": sum(1 for group in groups if group.get("route") == "needs_human"),
        "groups": groups,
        "task_routing": task_report,
        "event": event_report,
        "repair_parent_events": repair_parent_event_report,
        "branch_cleanup": compact,
        "worktree_retirement": worktree_retirement_report,
        "worktree_retirement_apply": worktree_retirement_apply,
        "branch_lifecycle_scan": branch_lifecycle_scan,
        "codex_activity": cleanup_report.get("codex_activity") or {},
        "cleanup_apply": cleanup_apply,
        "ok": (
            bool(cleanup_report.get("pr_evidence_available", False))
            and not int(cleanup_apply.get("failed_count") or 0)
            and not int(worktree_retirement_apply.get("failed_count") or 0)
        ),
    }
    output_path = output or task_manager_dir(project_root) / "repository_hygiene_state.json"
    if apply:
        report["state_signature"] = state_signature(report)
        previous = load_json(output_path, {})
        if previous.get("state_signature") != report["state_signature"]:
            report["state_written"] = True
            write_json(output_path, report)
        else:
            report["state_written"] = False
    elif output is not None:
        report["state_written"] = True
        write_json(output_path, report)
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--repo", required=True)
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--pr-list-json")
    parser.add_argument("--output")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--apply-safe-cleanup", action="store_true")
    parser.add_argument("--max-safe-delete-count", type=int, default=20)
    parser.add_argument("--max-safe-archive-count", type=int, default=20)
    parser.add_argument("--codex-activity-dir")
    parser.add_argument("--expected-codex-host", action="append", default=[])
    parser.add_argument("--codex-activity-max-age-seconds", type=int, default=300)
    parser.add_argument("--evidence-cache")
    parser.add_argument("--skip-branch-cleanup", action="store_true")
    parser.add_argument("--worktree-retirement-stale-days", type=int, default=14)
    parser.add_argument("--apply-worktree-retirement", action="store_true")
    parser.add_argument("--worktree-archive-root")
    parser.add_argument("--worktree-archive-ssh-host")
    parser.add_argument("--max-worktree-retire-count", type=int, default=10)
    parser.add_argument("--max-branch-recovery-task-count", type=int, default=10)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    prs = None
    if args.pr_list_json:
        rows = load_json(Path(args.pr_list_json).resolve(), [])
        if not isinstance(rows, list):
            raise SystemExit("--pr-list-json must contain a JSON array")
        prs = rows
    report = run_cycle(
        project_root=Path(args.project_root),
        repo=args.repo,
        remote=args.remote,
        base_ref=args.base_ref,
        prs=prs,
        apply=bool(args.apply),
        apply_safe_cleanup=bool(args.apply_safe_cleanup),
        max_safe_delete_count=max(0, int(args.max_safe_delete_count)),
        max_safe_archive_count=max(0, int(args.max_safe_archive_count)),
        fetch=bool(args.fetch),
        output=Path(args.output).resolve() if args.output else None,
        codex_activity_dir=Path(args.codex_activity_dir).resolve() if args.codex_activity_dir else None,
        expected_codex_hosts=list(args.expected_codex_host or []),
        codex_activity_max_age_seconds=max(1, int(args.codex_activity_max_age_seconds)),
        evidence_cache=Path(args.evidence_cache).resolve() if args.evidence_cache else None,
        skip_branch_cleanup=bool(args.skip_branch_cleanup),
        worktree_retirement_stale_days=max(0, int(args.worktree_retirement_stale_days)),
        apply_worktree_retirement=bool(args.apply_worktree_retirement),
        worktree_archive_root=args.worktree_archive_root,
        worktree_archive_ssh_host=args.worktree_archive_ssh_host,
        max_worktree_retire_count=max(0, int(args.max_worktree_retire_count)),
        max_branch_recovery_task_count=max(0, int(args.max_branch_recovery_task_count)),
    )
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else json.dumps(report["task_routing"], ensure_ascii=False))
    return 0 if report.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main())
