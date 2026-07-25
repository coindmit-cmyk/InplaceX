#!/usr/bin/env python3
"""Promote auto clean-rebuild candidates into worker-ready queue tasks."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from dispatcher_packet_repair import apply_v2_packet
from process_log import append_log
from project_paths import task_manager_dir


AUTO_ROUTES = {"auto_clean_rebuild_small", "auto_clean_rebuild_medium"}
FORBIDDEN_PATHS = [".env", ".env.*", "secrets", "production config"]
MAX_PREFLIGHT_PATHS = 100
SHA_RE = re.compile(r"[0-9a-f]{40}")
RELEASE_METADATA_PATHS = frozenset(
    {
        ".agent/agent_version.json",
        "templates/.agent/agent_version.json",
        "PROJECT_VERSION.json",
        "VERSION",
        "CHANGELOG.md",
        "AiStudio/Project_state/indexes/current_summary.md",
    }
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_path(path: Any) -> str:
    return str(path or "").replace("\\", "/").strip()


def source_branch_from_item(item: dict[str, Any]) -> str:
    return str(item.get("source_branch") or item.get("branch") or "").strip()


def source_head_sha_from_item(item: dict[str, Any]) -> str:
    return str(item.get("source_head_sha") or item.get("head_sha") or "").strip().lower()


def run_git(project_root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=project_root,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )


def resolve_commit(project_root: Path, ref: str) -> tuple[str | None, str | None]:
    proc = run_git(project_root, ["rev-parse", "--verify", f"{ref}^{{commit}}"])
    if proc.returncode != 0:
        return None, proc.stderr.strip() or f"cannot resolve {ref}"
    resolved = proc.stdout.strip().lower()
    if not SHA_RE.fullmatch(resolved):
        return None, f"{ref} did not resolve to a full commit SHA"
    return resolved, None


def valid_changed_path(path: str) -> bool:
    return bool(path) and not path.startswith("/") and not re.match(r"^[A-Za-z]:/", path) and ".." not in Path(path).parts


def tree_blobs(project_root: Path, commit: str, paths: list[str]) -> tuple[dict[str, str | None], str | None]:
    proc = run_git(project_root, ["ls-tree", "-r", "-z", commit, "--", *paths])
    if proc.returncode != 0:
        return {}, proc.stderr.strip() or f"cannot inspect tree {commit}"
    blobs: dict[str, str | None] = {path: None for path in paths}
    for raw_entry in proc.stdout.split("\0"):
        if not raw_entry:
            continue
        metadata, separator, raw_path = raw_entry.partition("\t")
        parts = metadata.split()
        path = normalize_path(raw_path)
        if not separator or len(parts) != 3 or path not in blobs:
            return {}, f"unexpected git ls-tree entry for {path or '<unknown>'}"
        _mode, object_type, oid = parts
        if object_type != "blob":
            return {}, f"changed_path is not a blob: {path} ({object_type})"
        blobs[path] = oid.lower()
    return blobs, None


def git_json_at_commit(project_root: Path, commit: str, path: str) -> tuple[dict[str, Any] | None, str | None]:
    proc = run_git(project_root, ["show", f"{commit}:{path}"])
    if proc.returncode != 0:
        return None, proc.stderr.strip() or f"{path} is unavailable at {commit}"
    try:
        payload = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        return None, f"{path} is malformed JSON: {exc.msg}"
    if not isinstance(payload, dict):
        return None, f"{path} must contain a JSON object"
    return payload, None


def numeric_version(value: Any) -> tuple[int, ...] | None:
    normalized = str(value or "").strip().removeprefix("v")
    parts = normalized.split(".")
    if len(parts) < 3 or any(not part.isdigit() for part in parts):
        return None
    return tuple(int(part) for part in parts)


def compare_numeric_versions(left: tuple[int, ...], right: tuple[int, ...]) -> int:
    width = max(len(left), len(right))
    normalized_left = left + (0,) * (width - len(left))
    normalized_right = right + (0,) * (width - len(right))
    return (normalized_left > normalized_right) - (normalized_left < normalized_right)


def release_metadata_version_proof(
    project_root: Path,
    source_commit: str,
    base_commit: str,
    paths: list[str],
) -> dict[str, Any]:
    evidence: dict[str, Any] = {
        "eligible": False,
        "proof": None,
        "source_version": None,
        "base_version": None,
    }
    extra_paths = sorted(set(paths) - RELEASE_METADATA_PATHS)
    if extra_paths:
        evidence["reason"] = "changed_paths_not_release_metadata_only"
        evidence["disallowed_paths"] = extra_paths
        return evidence

    source_payload, source_error = git_json_at_commit(project_root, source_commit, "PROJECT_VERSION.json")
    if source_error:
        evidence["reason"] = "source_project_version_unavailable_or_malformed"
        evidence["git_error"] = source_error
        return evidence
    base_payload, base_error = git_json_at_commit(project_root, base_commit, "PROJECT_VERSION.json")
    if base_error:
        evidence["reason"] = "base_project_version_unavailable_or_malformed"
        evidence["git_error"] = base_error
        return evidence

    source_project_id = str(source_payload.get("project_id") or "").strip()
    base_project_id = str(base_payload.get("project_id") or "").strip()
    source_version = str(source_payload.get("product_version") or "").strip()
    base_version = str(base_payload.get("product_version") or "").strip()
    evidence.update(
        {
            "source_project_id": source_project_id or None,
            "base_project_id": base_project_id or None,
            "source_version": source_version or None,
            "base_version": base_version or None,
        }
    )
    if not source_project_id or not base_project_id or source_project_id != base_project_id:
        evidence["reason"] = "project_id_missing_or_mismatched"
        return evidence

    source_numeric = numeric_version(source_version)
    base_numeric = numeric_version(base_version)
    if source_numeric is None or base_numeric is None:
        evidence["reason"] = "project_version_missing_or_non_numeric"
        return evidence

    evidence["eligible"] = True
    if compare_numeric_versions(source_numeric, base_numeric) <= 0:
        evidence.update(
            {
                "proof": "release_metadata_version_not_newer",
                "reason": "release metadata source version is not newer than current base",
            }
        )
        return evidence
    evidence["reason"] = "release_metadata_source_version_newer_than_base"
    return evidence


def clean_rebuild_preflight(project_root: Path, item: dict[str, Any], *, base_ref: str) -> dict[str, Any]:
    source_head_sha = source_head_sha_from_item(item)
    paths = sorted(
        dict.fromkeys(normalize_path(path) for path in item.get("changed_paths") or [] if normalize_path(path))
    )
    evidence: dict[str, Any] = {
        "schema_version": 1,
        "base_ref": base_ref,
        "source_head_sha": source_head_sha,
        "changed_paths": paths,
        "outcome": "blocked",
        "proof": None,
    }
    if not SHA_RE.fullmatch(source_head_sha):
        evidence["reason"] = "source_head_sha_missing_or_invalid"
        return evidence
    if not paths:
        evidence["reason"] = "changed_paths_missing"
        return evidence
    if len(paths) > MAX_PREFLIGHT_PATHS:
        evidence["reason"] = "changed_paths_exceed_preflight_limit"
        evidence["preflight_path_limit"] = MAX_PREFLIGHT_PATHS
        return evidence
    invalid_paths = [path for path in paths if not valid_changed_path(path)]
    if invalid_paths:
        evidence["reason"] = "changed_paths_invalid"
        evidence["invalid_paths"] = invalid_paths
        return evidence

    source_commit, source_error = resolve_commit(project_root, source_head_sha)
    if source_error:
        evidence["reason"] = "source_head_sha_unavailable"
        evidence["git_error"] = source_error
        return evidence
    base_commit, base_error = resolve_commit(project_root, base_ref)
    if base_error:
        evidence["reason"] = "base_ref_unavailable"
        evidence["git_error"] = base_error
        return evidence
    evidence["resolved_source_sha"] = source_commit
    evidence["resolved_base_sha"] = base_commit

    ancestor = run_git(project_root, ["merge-base", "--is-ancestor", source_commit, base_commit])
    if ancestor.returncode == 0:
        evidence.update(
            {
                "outcome": "superseded",
                "proof": "source_head_ancestor_of_base",
                "reason": "source_head_sha is already an ancestor of current base",
            }
        )
        return evidence
    if ancestor.returncode != 1:
        evidence["reason"] = "ancestor_check_failed"
        evidence["git_error"] = ancestor.stderr.strip() or "git merge-base --is-ancestor failed"
        return evidence

    source_blobs, source_tree_error = tree_blobs(project_root, source_commit, paths)
    if source_tree_error:
        evidence["reason"] = "source_tree_unavailable"
        evidence["git_error"] = source_tree_error
        return evidence
    base_blobs, base_tree_error = tree_blobs(project_root, base_commit, paths)
    if base_tree_error:
        evidence["reason"] = "base_tree_unavailable"
        evidence["git_error"] = base_tree_error
        return evidence

    path_evidence = [
        {
            "path": path,
            "source_blob": source_blobs[path],
            "base_blob": base_blobs[path],
            "equal": source_blobs[path] == base_blobs[path],
        }
        for path in paths
    ]
    evidence["path_evidence"] = path_evidence
    if all(item["equal"] for item in path_evidence):
        evidence.update(
            {
                "outcome": "superseded",
                "proof": "changed_path_blobs_equal_base",
                "reason": "every changed_path blob already equals current base",
            }
        )
        return evidence

    release_metadata = release_metadata_version_proof(project_root, source_commit, base_commit, paths)
    evidence["release_metadata_evidence"] = release_metadata
    if release_metadata.get("proof") == "release_metadata_version_not_newer":
        evidence.update(
            {
                "outcome": "superseded",
                "proof": "release_metadata_version_not_newer",
                "source_version": release_metadata["source_version"],
                "base_version": release_metadata["base_version"],
                "reason": "release metadata source version is not newer than current base",
            }
        )
        return evidence

    evidence.update(
        {
            "outcome": "promote",
            "proof": "changed_path_blob_diff_present",
            "reason": "source payload is not represented in current base",
        }
    )
    return evidence


def existing_ids(queue: dict[str, Any]) -> set[str]:
    return {
        str(task.get("id") or task.get("task_id") or "").strip()
        for task in queue.get("tasks") or []
        if isinstance(task, dict)
    }


def existing_sources(queue: dict[str, Any]) -> set[tuple[str, str]]:
    sources: set[tuple[str, str]] = set()
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        branch = str(task.get("source_branch") or task.get("clean_rebuild_source_branch") or "").strip()
        head = str(task.get("source_head_sha") or task.get("clean_rebuild_source_head_sha") or "").strip()
        if branch or head:
            sources.add((branch, head))
    return sources


def complexity_for(route: str) -> str:
    return "S" if route == "auto_clean_rebuild_small" else "M"


def profiles_for(route: str) -> list[str]:
    if route == "auto_clean_rebuild_small":
        return ["auto-worker-5.3-mini", "auto-worker-5.3"]
    return ["auto-worker-5.3", "auto-worker-5.5"]


def make_id(source_task_id: str, used: set[str]) -> str:
    base = f"CRB-{source_task_id.upper()}"
    value = base
    suffix = 2
    while value in used:
        value = f"{base}-{suffix}"
        suffix += 1
    used.add(value)
    return value


def task_from_item(item: dict[str, Any], task_id: str, now: str) -> dict[str, Any]:
    source_task_id = str((item.get("task_ids") or [""])[0]).strip()
    route = str(item.get("rebuild_route") or "")
    paths = [normalize_path(path) for path in item.get("changed_paths") or [] if normalize_path(path)]
    allowed = [normalize_path(path) for path in item.get("allowed_paths_sample") or [] if normalize_path(path)]
    if not allowed:
        allowed = paths
    title = f"Clean rebuild {source_task_id} from worker result"
    branch = source_branch_from_item(item)
    head = source_head_sha_from_item(item)
    return {
        "id": task_id,
        "title": title,
        "status": "planned",
        "priority": "P1",
        "complexity": complexity_for(route),
        "type": "clean-rebuild",
        "worker_ready": True,
        "packet_status": "worker_ready",
        "normalization_status": "worker_ready",
        "dispatcher_decision": "worker_ready",
        "dispatcher_decision_reason": "auto clean rebuild candidate promoted from clean_rebuild_plan",
        "recommended_agent": "auto-worker-5.3" if route == "auto_clean_rebuild_medium" else "auto-worker-5.3-mini",
        "eligible_worker_profiles": profiles_for(route),
        "allowed_paths": sorted(dict.fromkeys(allowed)),
        "forbidden_paths": FORBIDDEN_PATHS,
        "changed_paths": paths,
        "checks": [
            "git diff --check",
            "run targeted tests or project checks touched by changed_paths",
        ],
        "acceptance_criteria": [
            "Rebuild only the listed changed_paths from the source worker branch on current develop",
            "Do not copy noisy nested worktree, upstream checkout, local runtime or unrelated files",
            "Record source_branch, source_head_sha and changed_paths in the worker result",
            "If the patch cannot be cleanly rebuilt, return the task with needs_worker_fix and a precise blocker",
        ],
        "context_docs": [
            "AiStudio/Task_manager/clean_rebuild_plan.json",
            "docs/plans/allowed_paths_repair_plan.json",
        ],
        "source_file": "AiStudio/Task_manager/clean_rebuild_plan.json",
        "provenance": {
            "source": "clean_rebuild_queue_bridge.py",
            "source_task_id": source_task_id,
            "source_branch": branch,
            "source_head_sha": head,
            "rebuild_route": route,
            "allowed_paths_source": item.get("allowed_paths_source"),
            "promoted_at": now,
        },
        "source_task_id": source_task_id,
        "source_branch": branch,
        "source_head_sha": head,
        "clean_rebuild_source_branch": branch,
        "clean_rebuild_source_head_sha": head,
        "clean_rebuild_route": route,
        "lock": {"state": "free", "by": None, "at": None, "expires_at": None},
        "created_at": now,
        "status_reason": "ready for clean rebuild worker",
    }


def superseded_task_from_item(
    item: dict[str, Any],
    task_id: str,
    now: str,
    preflight: dict[str, Any],
) -> dict[str, Any]:
    source_task_id = str((item.get("task_ids") or [""])[0]).strip()
    branch = source_branch_from_item(item)
    head = source_head_sha_from_item(item)
    paths = [normalize_path(path) for path in item.get("changed_paths") or [] if normalize_path(path)]
    reason = str(preflight.get("reason") or "clean rebuild source is already represented in current base")
    return {
        "id": task_id,
        "title": f"Clean rebuild {source_task_id} from worker result",
        "status": "stale_or_superseded",
        "priority": "P1",
        "complexity": complexity_for(str(item.get("rebuild_route") or "")),
        "type": "clean-rebuild",
        "worker_ready": False,
        "packet_status": "stale_or_superseded",
        "normalization_status": "stale_or_superseded",
        "dispatcher_decision": "stale_or_superseded",
        "dispatcher_decision_reason": reason,
        "recommended_agent": "deterministic-clean-rebuild-preflight",
        "requires_human_attention": False,
        "changed_paths": paths,
        "source_file": "AiStudio/Task_manager/clean_rebuild_plan.json",
        "provenance": {
            "source": "clean_rebuild_queue_bridge.py",
            "source_task_id": source_task_id,
            "source_branch": branch,
            "source_head_sha": head,
            "rebuild_route": item.get("rebuild_route"),
            "closed_at": now,
        },
        "source_task_id": source_task_id,
        "source_branch": branch,
        "source_head_sha": head,
        "clean_rebuild_source_branch": branch,
        "clean_rebuild_source_head_sha": head,
        "clean_rebuild_route": item.get("rebuild_route"),
        "integration_status": "stale_or_superseded",
        "lock": {"state": "free", "by": None, "at": None, "expires_at": None},
        "created_at": now,
        "completed_at": now,
        "status_reason": reason,
        "deterministic_preflight": preflight,
    }


def eligible_items(plan: dict[str, Any], *, include_large: bool = False) -> list[dict[str, Any]]:
    routes = set(AUTO_ROUTES)
    if include_large:
        routes.add("auto_clean_rebuild_large")
    result: list[dict[str, Any]] = []
    for item in plan.get("items") or []:
        if not isinstance(item, dict):
            continue
        if item.get("rebuild_route") not in routes:
            continue
        ids = item.get("task_ids") or []
        paths = item.get("changed_paths") or []
        if len(ids) == 1 and paths:
            result.append(item)
    return result


def promote(
    project_root: Path,
    *,
    plan_path: Path,
    queue_path: Path,
    max_items: int,
    include_large: bool,
    apply: bool,
    base_ref: str = "origin/develop",
) -> dict[str, Any]:
    plan = load_json(plan_path)
    queue = load_json(queue_path)
    queue.setdefault("schema_version", 1)
    tasks = queue.setdefault("tasks", [])
    if not isinstance(tasks, list):
        raise SystemExit("task_queue.json must contain tasks array")

    used_ids = existing_ids(queue)
    seen_sources = existing_sources(queue)
    created: list[dict[str, Any]] = []
    closed: list[dict[str, Any]] = []
    blocked: list[dict[str, Any]] = []
    additions: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    now = utc_now()

    for item in eligible_items(plan, include_large=include_large):
        branch = source_branch_from_item(item)
        head = source_head_sha_from_item(item)
        source_key = (branch, head)
        if source_key in seen_sources:
            skipped.append({"branch": branch, "head_sha": head, "reason": "already_promoted"})
            continue
        if max_items and len(created) + len(closed) + len(blocked) >= max_items:
            skipped.append({"branch": branch, "head_sha": head, "reason": "max_items_reached"})
            continue
        preflight = clean_rebuild_preflight(project_root, item, base_ref=base_ref)
        if preflight["outcome"] == "blocked":
            blocked.append(
                {
                    "branch": branch,
                    "head_sha": head,
                    "task_ids": item.get("task_ids") or [],
                    "reason": "preflight_blocked",
                    "preflight": preflight,
                }
            )
            seen_sources.add(source_key)
            continue
        source_task_id = str((item.get("task_ids") or [""])[0]).strip()
        task_id = make_id(source_task_id, used_ids)
        if preflight["outcome"] == "superseded":
            task = superseded_task_from_item(item, task_id, now, preflight)
            closed.append(task)
            additions.append(task)
            seen_sources.add(source_key)
            continue
        task = apply_v2_packet(task_from_item(item, task_id, now), now)
        task["dispatcher_decision_reason"] = "auto clean rebuild candidate promoted from clean_rebuild_plan"
        task["deterministic_preflight"] = preflight
        created.append(task)
        additions.append(task)
        seen_sources.add(source_key)

    if apply and additions:
        tasks.extend(additions)
        queue["updated_at"] = now
        write_json(queue_path, queue)

    report = {
        "schema_version": 1,
        "created_at": now,
        "project_root": str(project_root),
        "apply": apply,
        "source_plan": str(plan_path),
        "queue": str(queue_path),
        "base_ref": base_ref,
        "created_count": len(created),
        "closed_count": len(closed),
        "blocked_count": len(blocked),
        "skipped_count": len(skipped),
        "created": created,
        "closed": closed,
        "blocked": blocked,
        "skipped": skipped[:50],
    }
    append_log(
        project_root,
        "dispatcher",
        "clean_rebuild_tasks_promoted",
        severity="info",
        apply=apply,
        created_count=len(created),
        closed_count=len(closed),
        blocked_count=len(blocked),
        skipped_count=len(skipped),
    )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--plan")
    parser.add_argument("--queue")
    parser.add_argument("--output")
    parser.add_argument("--max-items", type=int, default=5)
    parser.add_argument("--include-large", action="store_true")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    report = promote(
        project_root,
        plan_path=Path(args.plan).resolve() if args.plan else plans / "clean_rebuild_plan.json",
        queue_path=Path(args.queue).resolve() if args.queue else plans / "task_queue.json",
        max_items=args.max_items,
        include_large=args.include_large,
        apply=args.apply,
        base_ref=args.base_ref,
    )
    output = Path(args.output).resolve() if args.output else plans / "clean_rebuild_queue_bridge_report.json"
    write_json(output, report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"created: {report['created_count']}")
        print(f"closed: {report['closed_count']}")
        print(f"blocked: {report['blocked_count']}")
        print(f"skipped: {report['skipped_count']}")
        print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
