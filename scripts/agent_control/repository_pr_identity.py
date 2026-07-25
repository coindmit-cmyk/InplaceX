#!/usr/bin/env python3
"""Validate canonical identity for Repository Hygiene PR tasks."""

from __future__ import annotations

import re
from typing import Any


DIRECT_REPOSITORY_PR_TASK_RE = re.compile(r"^REPO-PR-([0-9]+)$", re.IGNORECASE)
SHA_QUALIFIED_REPOSITORY_PR_TASK_RE = re.compile(
    r"^REPO-PR-([0-9]+)-(?:RECHECK|REV)-([0-9A-F]{12})$",
    re.IGNORECASE,
)
STACKED_REPOSITORY_PR_TASK_RE = re.compile(r"^REPO-PR-([0-9]+)-([0-9]+)$", re.IGNORECASE)
FALLBACK_REPOSITORY_PR_TASK_RE = re.compile(r"^REPO-HYGIENE-([A-Z0-9]+)$", re.IGNORECASE)
INACTIVE_REPOSITORY_PR_STATUSES = {
    "archived",
    "cancelled",
    "closed",
    "deferred",
    "done",
    "finalized",
    "stale_or_superseded",
}
INACTIVE_REPOSITORY_PR_INTEGRATION_STATUSES = {
    "closed_coordination_only",
    "finalized",
    "integrated",
}


def normalize_branch(value: Any) -> str:
    branch = str(value or "").strip()
    for prefix in ("refs/remotes/origin/", "refs/heads/", "origin/"):
        if branch.startswith(prefix):
            return branch.removeprefix(prefix)
    return branch


def integer(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def provenance_urls(task: dict[str, Any]) -> list[str]:
    result: list[str] = []
    for item in task.get("provenance") or []:
        if not isinstance(item, dict):
            continue
        url = str(item.get("source_url") or "").strip()
        if url:
            result.append(url)
    return sorted(set(result))


def validate_repository_pr_identity(
    task: dict[str, Any] | None,
    *,
    candidate_branch: Any = None,
    candidate_pr: Any = None,
    candidate_head_sha: Any = None,
    require_candidate_pr: bool = False,
    require_candidate_head: bool = False,
) -> dict[str, Any]:
    row = task if isinstance(task, dict) else {}
    current_id = task_id(row)
    direct_match = DIRECT_REPOSITORY_PR_TASK_RE.fullmatch(current_id)
    revision_match = SHA_QUALIFIED_REPOSITORY_PR_TASK_RE.fullmatch(current_id)
    stack_match = STACKED_REPOSITORY_PR_TASK_RE.fullmatch(current_id)
    fallback_match = FALLBACK_REPOSITORY_PR_TASK_RE.fullmatch(current_id)
    applicable = str(row.get("type") or "") == "repository_hygiene_integration" or bool(
        direct_match or revision_match or stack_match or fallback_match
    )
    if not applicable:
        return {"applicable": False, "valid": False, "issues": [], "evidence": []}

    issues: list[str] = []
    if not (direct_match or revision_match or stack_match or fallback_match):
        issues.append("repository PR task id format is invalid")
    if str(row.get("type") or "") != "repository_hygiene_integration":
        issues.append("repository PR task type mismatch")

    github_pr = integer(row.get("github_pr"))
    raw_pr_numbers = row.get("pr_numbers")
    pr_numbers: set[int] = set()
    if isinstance(raw_pr_numbers, list):
        pr_numbers = {value for value in (integer(item) for item in raw_pr_numbers) if value is not None}
    if github_pr is None:
        issues.append("repository PR queue number missing")
    elif pr_numbers and github_pr not in pr_numbers:
        issues.append("repository PR queue number missing from pr_numbers")
    if direct_match or revision_match:
        direct_pr = int((direct_match or revision_match).group(1))
        if github_pr != direct_pr:
            issues.append("repository PR queue number does not match task id")
        if pr_numbers and pr_numbers != {direct_pr}:
            issues.append("direct repository PR task has conflicting pr_numbers")
    elif stack_match:
        first, last = int(stack_match.group(1)), int(stack_match.group(2))
        if not pr_numbers or min(pr_numbers) != first or max(pr_numbers) != last:
            issues.append("repository PR stack bounds do not match pr_numbers")
    elif fallback_match:
        if not str(row.get("repository_hygiene_key") or "").strip():
            issues.append("repository PR fallback task missing hygiene key")
        if not pr_numbers:
            issues.append("repository PR fallback task missing pr_numbers")

    queue_branch = normalize_branch(row.get("branch") or row.get("source_branch") or row.get("pr_branch"))
    if not queue_branch:
        issues.append("repository PR queue branch missing")
    normalized_candidate = normalize_branch(candidate_branch)
    if normalized_candidate and queue_branch and normalized_candidate != queue_branch:
        issues.append("repository PR candidate branch does not match queue branch")

    actual_candidate_pr = integer(candidate_pr)
    if require_candidate_pr and actual_candidate_pr is None:
        issues.append("repository PR candidate number missing")
    elif actual_candidate_pr is not None and github_pr is not None and actual_candidate_pr != github_pr:
        issues.append("repository PR candidate number does not match queue leaf PR")

    expected_head_sha = str(row.get("repository_hygiene_head_sha") or row.get("expected_head_sha") or "").strip()
    if revision_match and expected_head_sha and not expected_head_sha.lower().startswith(revision_match.group(2).lower()):
        issues.append("repository PR revision task SHA does not match queue head SHA")
    actual_head_sha = str(candidate_head_sha or "").strip()
    if require_candidate_head and not actual_head_sha:
        issues.append("repository PR candidate head SHA missing")
    elif actual_head_sha and not expected_head_sha:
        issues.append("repository PR expected head SHA missing")
    elif actual_head_sha and expected_head_sha and actual_head_sha != expected_head_sha:
        issues.append("repository PR candidate head SHA does not match queue head SHA")

    urls = provenance_urls(row)
    expected_suffix = f"/pull/{github_pr}" if github_pr is not None else ""
    matching_urls = [url for url in urls if expected_suffix and url.rstrip("/").endswith(expected_suffix)]
    if urls and expected_suffix and not matching_urls:
        issues.append("repository PR provenance URL does not match queue leaf PR")

    evidence = [f"github_pr:{github_pr}"] if github_pr is not None else []
    if pr_numbers:
        evidence.append("pr_numbers:" + ",".join(str(value) for value in sorted(pr_numbers)))
    if queue_branch:
        evidence.append(f"branch:{queue_branch}")
    if expected_head_sha:
        evidence.append(f"head_sha:{expected_head_sha}")
    evidence.extend(matching_urls)
    return {
        "applicable": True,
        "valid": not issues,
        "task_id": current_id,
        "pr_number": github_pr,
        "branch": queue_branch or None,
        "expected_head_sha": expected_head_sha or None,
        "issues": issues,
        "evidence": evidence,
    }


def resolve_repository_pr_task_ids(
    task_ids: list[str],
    queue_tasks: dict[str, dict[str, Any]],
    *,
    candidate_branch: Any = None,
    candidate_pr: Any = None,
    candidate_head_sha: Any = None,
) -> dict[str, Any]:
    """Select one live revision when older identities are explicitly terminal."""
    identities = list(dict.fromkeys(str(value).strip() for value in task_ids if str(value).strip()))
    if len(identities) <= 1:
        return {"applicable": False, "valid": False, "task_ids": identities, "issues": []}

    rows: list[tuple[str, dict[str, Any], dict[str, Any]]] = []
    issues: list[str] = []
    for current_id in identities:
        row = queue_tasks.get(current_id.upper())
        if not isinstance(row, dict):
            issues.append(f"repository PR task missing from queue: {current_id}")
            continue
        binding = validate_repository_pr_identity(
            row,
            candidate_branch=candidate_branch,
            candidate_pr=candidate_pr,
            candidate_head_sha=candidate_head_sha,
            require_candidate_pr=True,
            require_candidate_head=True,
        )
        if not binding.get("applicable"):
            return {"applicable": False, "valid": False, "task_ids": identities, "issues": []}
        if not binding.get("valid"):
            issues.extend(str(value) for value in binding.get("issues") or [])
        rows.append((current_id, row, binding))

    if issues or len(rows) != len(identities):
        return {
            "applicable": True,
            "valid": False,
            "task_ids": identities,
            "issues": sorted(set(issues or ["repository PR task identity is incomplete"])),
        }

    active: list[str] = []
    inactive: list[str] = []
    for current_id, row, _binding in rows:
        status = str(row.get("status") or "").strip().lower()
        integration_status = str(row.get("integration_status") or "").strip().lower()
        if status in INACTIVE_REPOSITORY_PR_STATUSES or integration_status in INACTIVE_REPOSITORY_PR_INTEGRATION_STATUSES:
            inactive.append(current_id)
        else:
            active.append(current_id)

    if len(active) != 1:
        return {
            "applicable": True,
            "valid": False,
            "task_ids": identities,
            "issues": ["repository PR identities do not have exactly one active revision"],
        }
    return {
        "applicable": True,
        "valid": True,
        "task_ids": identities,
        "selected_task_id": active[0],
        "superseded_task_ids": inactive,
        "issues": [],
    }
