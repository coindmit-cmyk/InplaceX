#!/usr/bin/env python3
"""Code-preserving Project Rebuilder scanner/planner.

This module is intentionally conservative. It creates inventory and rebuild
plans, and refuses apply unless the caller supplies the exact approved plan
hash. Initial apply mode only records validation evidence; it does not rewrite
product files.
"""

from __future__ import annotations

import argparse
import copy
import fnmatch
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import project_doctor
import project_registry
from action_report import build_report as build_action_report
from action_report import validate_report as validate_action_report


PRODUCT_EXCLUDE_PREFIXES = (
    ".git/",
    "runtime/",
    "temp/",
    "archive/",
    "backups/",
    "AiStudio/Task_manager/process-logs/",
    "AiStudio/Task_manager/reports/",
)
def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _infer_project_id(plan: dict[str, Any], project_id: str | None) -> str:
    if project_id:
        return project_id
    for item in plan.get("projects") or []:
        if isinstance(item, dict) and item.get("project_id"):
            return str(item.get("project_id") or "")
    return "project-rebuilder"


def _collect_rebuilder_action_paths(plan: dict[str, Any]) -> tuple[list[str], list[str]]:
    affected: set[str] = set()
    artifacts: set[str] = set()

    registry = plan.get("registry")
    if registry:
        affected.add(str(registry))

    for project in plan.get("projects") or []:
        if not isinstance(project, dict):
            continue
        project_id = str(project.get("project_id") or "")
        if project_id:
            affected.add(project_id)
        inventory = project.get("inventory") if isinstance(project.get("inventory"), dict) else {}
        inventory_root = inventory.get("root")
        if inventory_root:
            affected.add(str(inventory_root))
        source_checkout = inventory.get("source_checkout") if isinstance(inventory.get("source_checkout"), dict) else {}
        checkout_path = source_checkout.get("path") if isinstance(source_checkout, dict) else None
        if checkout_path:
            artifacts.add(str(checkout_path))

    return sorted(affected), sorted(artifacts)


def _validation_for_plan(plan: dict[str, Any]) -> list[str]:
    blockers: list[str] = []
    for project in plan.get("projects") or []:
        if not isinstance(project, dict):
            continue
        project_id = str(project.get("project_id") or "unknown")
        for blocker in project.get("blockers") or []:
            if blocker:
                blockers.append(f"{project_id}: {blocker}")
    return blockers


def _collect_error_blockers(errors: list[dict[str, Any]]) -> list[str]:
    blockers: list[str] = []
    for error in errors:
        if not isinstance(error, dict):
            continue
        if error.get("code"):
            blockers.append(str(error.get("code")))
        elif error.get("message"):
            blockers.append(str(error.get("message")))
    return blockers


def _next_owner_and_action(*, blockers: list[str], is_apply: bool) -> tuple[str, str]:
    if blockers:
        first = blockers[0]
        if "approved_plan_hash_missing" in first:
            return "dispatcher", "Provide --approved-plan-hash from the current plan output and rerun with --apply."
        if "approved_plan_hash_mismatch" in first:
            return "dispatcher", "Regenerate plan, then rerun with matching --approved-plan-hash."
        if "project_rebuild_blocked" in first:
            return "architect", f"Resolve rebuild blocker and rerun project_rebuilder: {first}"
        if "product_payload_unexpected_diff" in first:
            return "architect", f"Investigate payload diffs and rerun with allowed diff paths: {first}"
        return "owner", f"Resolve rebuild blocker and rerun with updated state: {first}"

    if is_apply:
        return "none", "No follow-up required for project_rebuilder apply validation."
    return "none", "No follow-up required for project_rebuilder plan generation."


def _build_rebuilder_action_payload(
    *,
    operation: str,
    args: argparse.Namespace,
    plan: dict[str, Any],
    started_at: str | None = None,
) -> dict[str, Any]:
    if operation not in {"plan", "apply"}:
        raise ValueError("unsupported operation")

    is_apply = operation == "apply"
    if is_apply:
        validation = dict(plan.get("apply_validation") or {})
        if not validation:
            validation = {"ok": False, "errors": [{"code": "missing_apply_validation", "message": "plan has no apply_validation"}]}
        validation_ok = bool(validation.get("ok", False))
        blockers = _collect_error_blockers(validation.get("errors") or [])
    else:
        blockers = _validation_for_plan(plan)
        validation_ok = not blockers
        validation = {
            "ok": validation_ok,
            "blocked_count": len(blockers),
            "blocked_project_count": len(plan.get("projects") or []),
        }

    next_owner, next_action = _next_owner_and_action(blockers=blockers, is_apply=is_apply)
    if is_apply:
        result = "succeeded" if validation_ok else "blocked"
    else:
        result = "no_op" if int(plan.get("project_count") or 0) == 0 else ("blocked" if blockers else "succeeded")

    affected_paths, artifacts = _collect_rebuilder_action_paths(plan)
    if getattr(args, "output", None):
        artifacts.append(str(args.output))
    if getattr(args, "compare_plan", None):
        artifacts.append(str(args.compare_plan))

    actions_planned: list[dict[str, Any]] = []
    actions_executed: list[dict[str, Any]] = []
    actions_skipped: list[dict[str, Any]] = []
    actions_failed: list[dict[str, Any]] = []

    for project in plan.get("projects") or []:
        if not isinstance(project, dict):
            continue
        project_id = str(project.get("project_id") or "")
        actions_planned.append({"action": "project_rebuilder_plan", "project_id": project_id})
        if is_apply:
            if validation_ok:
                actions_executed.append({"action": "project_rebuilder_validate", "project_id": project_id})
            else:
                actions_failed.append({"action": "project_rebuilder_validate", "project_id": project_id, "errors": validation.get("errors", [])})
        elif project.get("blockers"):
            actions_skipped.append({"action": "project_rebuilder_plan_blocked", "project_id": project_id, "blockers": project.get("blockers")})

    before_state = {
        "command": "project_rebuilder.py",
        "operation": operation,
        "registry": str(args.registry) if getattr(args, "registry", None) else None,
        "project_id_filter": getattr(args, "project_id", None),
        "level": int(args.level) if getattr(args, "level", 0) is not None else 0,
        "allowed_product_diff_paths": [str(path) for path in (getattr(args, "allowed_product_diff_paths", []) or [])],
        "compare_plan": str(args.compare_plan) if getattr(args, "compare_plan", None) else None,
        "approved_plan_hash": getattr(args, "approved_plan_hash", None),
    }
    after_state = {
        "plan_hash": str(plan.get("plan_hash") or ""),
        "project_count": int(plan.get("project_count") or 0),
        "blocked_count": int(plan.get("blocked_count") or 0),
        "product_code_changes_allowed": bool(plan.get("product_code_changes_allowed")),
        "applied": bool(plan.get("applied")),
    }

    return build_action_report(
        action_id=f"project-rebuilder.{operation}",
        action_type="project.rebuilder",
        project_id=_infer_project_id(plan, getattr(args, "project_id", None)),
        actor="project-rebuilder-cli",
        mode="apply" if is_apply else "dry_run",
        result=result,
        next_owner=next_owner,
        next_action=next_action,
        started_at=started_at or now_utc(),
        input_refs=[
            "project_rebuilder.py",
            f"operation={operation}",
            f"project_id={getattr(args, 'project_id', None) or 'all'}",
            f"level={getattr(args, 'level', 0)}",
            f"compact={getattr(args, 'compact', False)}",
        ],
        before_state=before_state,
        after_state=after_state,
        actions_planned=actions_planned,
        actions_executed=actions_executed,
        actions_skipped=actions_skipped,
        actions_failed=actions_failed,
        affected_paths=affected_paths,
        validation=validation,
        artifacts=artifacts,
        rollback={
            "plan_rebuild_required": int(plan.get("blocked_count") or 0) > 0,
            "applied": bool(plan.get("applied")),
        },
        residual_risks=blockers,
        source="project_rebuilder.py",
)


def _normalized_path(value: str) -> str:
    return value.replace("\\", "/").lstrip("/")


def _normalized_glob_pattern(value: str) -> str:
    return value.replace("\\", "/").lstrip("/")


def _path_allowed(path: str, *, allowed_diff_paths: list[str]) -> bool:
    normalized_path = _normalized_path(path)
    for raw_pattern in allowed_diff_paths:
        pattern = _normalized_glob_pattern(raw_pattern)
        if not pattern:
            continue
        if normalized_path == pattern:
            return True
        normalized_pattern = pattern.rstrip("/")
        if normalized_pattern and normalized_path.startswith(normalized_pattern + "/"):
            return True
        if fnmatch.fnmatch(normalized_path, pattern):
            return True
    return False


def run_git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(["git", *args], cwd=repo, text=True, capture_output=True, check=False, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def stable_hash(payload: dict[str, Any]) -> str:
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256_bytes(raw)


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def tracked_files(repo: Path) -> list[str]:
    proc = run_git(repo, ["ls-files"])
    if not proc or proc.returncode != 0:
        return []
    return sorted(line.strip().replace("\\", "/") for line in proc.stdout.splitlines() if line.strip())


def product_file(path: str) -> bool:
    normalized = path.replace("\\", "/").strip("/")
    return bool(normalized) and not any(normalized == prefix.rstrip("/") or normalized.startswith(prefix) for prefix in PRODUCT_EXCLUDE_PREFIXES)


def file_hash(repo: Path, rel: str) -> dict[str, Any]:
    path = repo / rel
    if not path.is_file():
        return {"path": rel, "exists": False}
    data = path.read_bytes()
    return {"path": rel, "size": len(data), "sha256": sha256_bytes(data)}


def _index_files_by_path(file_payloads: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for item in file_payloads:
        if not isinstance(item, dict):
            continue
        path = str(item.get("path") or "").replace("\\", "/")
        if not path:
            continue
        indexed[path] = item
    return indexed


def compare_product_payload(
    *,
    before: dict[str, Any],
    after: dict[str, Any],
    allowed_product_diff_paths: list[str],
) -> dict[str, Any]:
    before_files = _index_files_by_path(before.get("files", []) if isinstance(before.get("files"), list) else [])
    after_files = _index_files_by_path(after.get("files", []) if isinstance(after.get("files"), list) else [])
    all_paths = sorted(set(before_files) | set(after_files))

    unchanged = True
    changed_count = 0
    allowed_diffs: list[dict[str, Any]] = []
    unexpected_diffs: list[dict[str, Any]] = []

    for path in all_paths:
        before_entry = before_files.get(path)
        after_entry = after_files.get(path)
        if before_entry == after_entry:
            continue

        changed_count += 1
        difference = {
            "path": path,
            "before_exists": bool(before_entry and before_entry.get("exists", True)),
            "after_exists": bool(after_entry and after_entry.get("exists", True)),
            "before_sha256": before_entry.get("sha256") if isinstance(before_entry, dict) else None,
            "after_sha256": after_entry.get("sha256") if isinstance(after_entry, dict) else None,
            "before_size": before_entry.get("size") if isinstance(before_entry, dict) else None,
            "after_size": after_entry.get("size") if isinstance(after_entry, dict) else None,
        }
        if _path_allowed(path, allowed_diff_paths=allowed_product_diff_paths):
            allowed_diffs.append(difference)
        else:
            unexpected_diffs.append(difference)

    if unexpected_diffs or allowed_diffs:
        unchanged = False

    return {
        "product_payload_unchanged": unchanged,
        "product_payload_changed": changed_count > 0,
        "product_payload_before_hash": str(before.get("content_manifest_hash", "")),
        "product_payload_after_hash": str(after.get("content_manifest_hash", "")),
        "allowed_product_payload_diffs": allowed_diffs,
        "unexpected_product_payload_diffs": unexpected_diffs,
        "unexpected_product_payload_diff_count": len(unexpected_diffs),
        "allowed_product_payload_diff_count": len(allowed_diffs),
    }


def git_branch(repo: Path) -> str:
    proc = run_git(repo, ["branch", "--show-current"])
    return proc.stdout.strip() if proc and proc.returncode == 0 else ""


def git_worktree_list(repo: Path) -> list[str]:
    proc = run_git(repo, ["worktree", "list"])
    if not proc or proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def git_is_inside_work_tree(repo: Path) -> bool:
    proc = run_git(repo, ["rev-parse", "--is-inside-work-tree"])
    return bool(proc and proc.returncode == 0 and proc.stdout.strip().lower() == "true")


def git_resolve_ref(repo: Path, ref_name: str) -> tuple[bool, str, str]:
    if not ref_name:
        return False, "", "base_ref_missing"
    proc = run_git(repo, ["rev-parse", "--verify", f"{ref_name}^{{commit}}"])
    if proc and proc.returncode == 0:
        commit = proc.stdout.strip()
        if commit:
            return True, commit, ""
    error = proc.stderr.strip() if proc else "git_invocation_failed"
    return False, "", error or "base_ref_unresolvable"


def git_merge_base_and_ahead_behind(repo: Path, *, base_ref: str, head: str) -> dict[str, Any]:
    if not base_ref or not head:
        return {}

    result: dict[str, Any] = {}
    merge_base_proc = run_git(repo, ["merge-base", base_ref, head])
    if merge_base_proc and merge_base_proc.returncode == 0:
        merge_base = merge_base_proc.stdout.strip()
        if merge_base:
            result["merge_base"] = merge_base

    revlist_proc = run_git(repo, ["rev-list", "--left-right", "--count", f"{base_ref}...{head}"])
    if not (revlist_proc and revlist_proc.returncode == 0):
        return result

    parts = revlist_proc.stdout.strip().split()
    if len(parts) != 2:
        return result

    try:
        behind = int(parts[0])
        ahead = int(parts[1])
    except ValueError:
        return result

    result["behind"] = behind
    result["ahead"] = ahead
    return result


def collect_source_checkout_inventory(repo: Path, base_ref: str) -> dict[str, Any]:
    if not git_is_inside_work_tree(repo):
        return {
            "is_git_checkout": False,
            "path": str(repo),
            "base_ref": base_ref,
            "base_ref_resolved": False,
            "base_ref_error": "repo_is_not_a_git_checkout",
            "base_ref_head": "",
        }

    base_ref_ok, base_ref_head, base_ref_error = git_resolve_ref(repo, base_ref)
    head = git_head(repo)
    source_inventory: dict[str, Any] = {
        "is_git_checkout": True,
        "path": str(repo),
        "branch": git_branch(repo),
        "head": head,
        "base_ref": base_ref,
        "base_ref_resolved": base_ref_ok,
        "base_ref_head": base_ref_head,
        "base_ref_error": base_ref_error,
        "git_worktree_list": git_worktree_list(repo),
    }
    if base_ref_ok:
        source_inventory.update(git_merge_base_and_ahead_behind(repo, base_ref=base_ref_head, head=head))
    return source_inventory


def git_head(repo: Path) -> str:
    proc = run_git(repo, ["rev-parse", "HEAD"])
    return proc.stdout.strip() if proc and proc.returncode == 0 else ""


def dirty_entries(repo: Path) -> list[str]:
    proc = run_git(repo, ["status", "--porcelain"])
    result: list[str] = []
    for line in (proc.stdout if proc and proc.returncode == 0 else "").splitlines():
        if not line.strip():
            continue
        if line.startswith("??"):
            continue
        path = line[3:].strip()
        if " -> " in path:
            path = path.rsplit(" -> ", 1)[1]
        if product_file(path):
            result.append(line)
    return result


def unpushed_commits(repo: Path, base_ref: str) -> list[str]:
    if not base_ref:
        return []
    proc = run_git(repo, ["log", "--format=%H", f"{base_ref}..HEAD"])
    return [line.strip() for line in (proc.stdout if proc and proc.returncode == 0 else "").splitlines() if line.strip()]


def _porcelain_path(entry: str) -> str:
    path = entry[3:].strip()
    if " -> " in path:
        path = path.rsplit(" -> ", 1)[1]
    return path.replace("\\", "/")


def _status_porcelain_lines(repo: Path) -> list[str]:
    proc = run_git(repo, ["status", "--porcelain", "--untracked-files=all"])
    if not proc or proc.returncode != 0:
        return []
    return [line for line in proc.stdout.splitlines() if line.strip()]


def _safe_project_id(value: str | None) -> str:
    normalized = "".join(ch if ch.isalnum() or ch in "._-" else "-" for ch in str(value or "project")).strip("-")
    return normalized or "project"


def untracked_product_files(repo: Path) -> list[str]:
    result: list[str] = []
    for line in _status_porcelain_lines(repo):
        if not line.startswith("??"):
            continue
        path = _porcelain_path(line)
        if product_file(path):
            result.append(path)
    return result


def build_preservation_actions(
    project_id: str | None,
    *,
    inventory: dict[str, Any],
    source_checkout: dict[str, Any],
    level: int,
) -> list[dict[str, Any]]:
    actions: list[dict[str, Any]] = []
    dirty = [entry for entry in inventory.get("dirty_entries") or [] if isinstance(entry, str)]
    untracked = [entry for entry in inventory.get("untracked_product_files") or [] if isinstance(entry, str)]
    unpushed = [entry for entry in inventory.get("unpushed_commits") or [] if isinstance(entry, str)]
    safe_project_id = _safe_project_id(project_id)
    head = str(inventory.get("head") or "")
    rescue_ref = f"refs/heads/rescue/{safe_project_id}/pre-rebuild-{head[:12] or 'pending'}"

    if dirty:
        actions.append({
            "level": level,
            "action": "archive_dirty_patch",
            "mutates_product": False,
            "required": True,
            "dirty_entry_count": len(dirty),
            "reason": "dirty tracked changes should be archived before workspace replacement",
            "suggested_outputs": [
                f"preservation/{safe_project_id}/dirty-status.txt",
                f"preservation/{safe_project_id}/dirty-worktree.patch",
                f"preservation/{safe_project_id}/dirty-index.patch",
            ],
            "suggested_commands": [
                ["git", "status", "--porcelain"],
                ["git", "diff", "--binary"],
                ["git", "diff", "--cached", "--binary"],
            ],
        })

    if untracked:
        actions.append({
            "level": level,
            "action": "untracked_copy_manifest",
            "mutates_product": False,
            "required": True,
            "untracked_product_file_count": len(untracked),
            "untracked_product_file_sample": untracked[:20],
            "reason": "untracked product files should be listed for manual migration import",
            "suggested_outputs": [
                f"preservation/{safe_project_id}/untracked-manifest.txt",
            ],
            "suggested_commands": [
                ["git", "status", "--porcelain", "--untracked-files=all"],
            ],
        })

    if unpushed:
        actions.append({
            "level": level,
            "action": "rescue_ref_or_push_recommendation",
            "mutates_product": False,
            "required": True,
            "unpushed_commit_count": len(unpushed),
            "reason": "unpushed commits should remain reachable before rebuild",
            "suggested_ref": rescue_ref,
            "suggested_bundle": f"preservation/{safe_project_id}/unpushed-commits.tar.gz",
            "suggested_commands": [
                ["git", "log", "--oneline", f"{inventory.get('base_ref') or 'origin/develop'}..HEAD"],
            ],
        })

    if not source_checkout.get("is_git_checkout"):
        actions.append({
            "level": level,
            "action": "create_git_checkout_from_inventory_source",
            "mutates_product": False,
            "required": True,
            "reason": "rebuilder source checkout must be a valid git repository",
            "suggested_commands": [
                ["git", "status"],
            ],
        })

    if not source_checkout.get("base_ref_resolved"):
        actions.append({
            "level": level,
            "action": "resolve_base_ref_before_rebuild",
            "mutates_product": False,
            "required": True,
            "source_ref": source_checkout.get("base_ref"),
            "source_ref_error": source_checkout.get("base_ref_error"),
            "reason": "configured source base_ref must resolve before rebuild planning",
            "suggested_commands": [
                ["git", "fetch", "--prune"],
                ["git", "show-ref", "--verify", str(source_checkout.get("base_ref") or "")],
            ],
        })

    return actions


def inventory_project(project: dict[str, Any]) -> dict[str, Any]:
    root = Path(str(project.get("automation_path") or project.get("local_path") or "")).expanduser()
    base_ref = str(project.get("code_base_ref") or project.get("base_ref") or "origin/develop")
    source_checkout = collect_source_checkout_inventory(root, base_ref)
    files = [path for path in tracked_files(root) if product_file(path)]
    file_hashes = [file_hash(root, path) for path in files]
    content_manifest_hash = stable_hash({"files": file_hashes})
    return {
        "project_id": project.get("project_id"),
        "root": str(root),
        "head": source_checkout.get("head", "") if source_checkout.get("is_git_checkout") else "",
        "source_checkout": source_checkout,
        "base_ref": base_ref,
        "base_ref_resolved": bool(source_checkout.get("base_ref_resolved")),
        "tracked_product_file_count": len(files),
        "content_manifest_hash": content_manifest_hash,
        "files": file_hashes,
        "dirty_entries": dirty_entries(root),
        "untracked_product_files": untracked_product_files(root),
        "unpushed_commits": unpushed_commits(root, base_ref),
    }


def level_actions(level: int, doctor_report: dict[str, Any]) -> list[dict[str, Any]]:
    actions: list[dict[str, Any]] = [{"level": 0, "action": "inventory_only", "mutates_product": False}]
    if level >= 1:
        actions.append({"level": 1, "action": "repair_version_index_manifest_metadata", "mutates_product": False})
    if level >= 2:
        actions.append({"level": 2, "action": "repair_agent_task_state_metadata", "mutates_product": False})
    if level >= 3:
        actions.append({"level": 3, "action": "provision_workspace_layout", "mutates_product": False})
    if level >= 4:
        actions.append({"level": 4, "action": "normalize_branch_roles", "mutates_product": False})
    if level >= 5:
        actions.append({"level": 5, "action": "combined_rebuild_validation", "mutates_product": False})
    if doctor_report.get("deductions"):
        actions.append({"level": level, "action": "address_doctor_deductions", "mutates_product": False, "deduction_count": len(doctor_report.get("deductions") or [])})
    return actions


def sample_list(items: list[Any], limit: int) -> dict[str, Any]:
    return {
        "count": len(items),
        "sample": items[:limit],
        "omitted_count": max(0, len(items) - limit),
    }


def compact_plan(plan: dict[str, Any], *, sample_limit: int = 20) -> dict[str, Any]:
    compact = copy.deepcopy(plan)
    compact["compact"] = True
    compact["compact_sample_limit"] = sample_limit
    for item in compact.get("projects") or []:
        if not isinstance(item, dict):
            continue
        inventory = item.get("inventory") if isinstance(item.get("inventory"), dict) else {}
        files = inventory.get("files") if isinstance(inventory.get("files"), list) else []
        dirty = inventory.get("dirty_entries") if isinstance(inventory.get("dirty_entries"), list) else []
        untracked = inventory.get("untracked_product_files") if isinstance(inventory.get("untracked_product_files"), list) else []
        unpushed = inventory.get("unpushed_commits") if isinstance(inventory.get("unpushed_commits"), list) else []
        inventory["files_summary"] = sample_list(files, sample_limit)
        inventory["dirty_entries_summary"] = sample_list(dirty, sample_limit)
        inventory["untracked_product_files_summary"] = sample_list(untracked, sample_limit)
        inventory["unpushed_commits_summary"] = sample_list(unpushed, sample_limit)
        inventory.pop("files", None)
        inventory.pop("dirty_entries", None)
        inventory.pop("untracked_product_files", None)
        inventory.pop("unpushed_commits", None)
        doctor = item.get("doctor") if isinstance(item.get("doctor"), dict) else {}
        deductions = doctor.get("deductions") if isinstance(doctor.get("deductions"), list) else []
        doctor["deductions_summary"] = sample_list(deductions, sample_limit)
        doctor.pop("deductions", None)
    return compact


def build_plan(registry_path: Path, *, project_id: str | None = None, level: int = 0) -> dict[str, Any]:
    projects, registry_warnings = project_registry.load_projects(registry_path, project_id=project_id)
    items: list[dict[str, Any]] = []
    for project in projects:
        inventory = inventory_project(project)
        source_checkout = inventory.get("source_checkout", {})
        doctor = project_doctor.scan_project(project)
        blockers: list[str] = []
        if inventory["dirty_entries"]:
            blockers.append("dirty_state_requires_archive_or_manual_classification")
        if inventory["untracked_product_files"]:
            blockers.append("untracked_product_files_require_archive_manifest_before_rebuild")
        if inventory["unpushed_commits"]:
            blockers.append("unpushed_commits_require_rescue_ref_or_push_before_rebuild")
        if not source_checkout.get("is_git_checkout"):
            blockers.append("non_git_checkout_cannot_rebuild_without_git_source")
            blockers.append("base_ref_unresolved_or_missing_source_ref")
        elif not source_checkout.get("base_ref_resolved"):
            blockers.append("base_ref_unresolved_or_missing_source_ref")

        preservation_actions = build_preservation_actions(
            project.get("project_id"),
            inventory=inventory,
            source_checkout=source_checkout,
            level=level,
        )
        item = {
            "project_id": project.get("project_id"),
            "level": level,
            "inventory": inventory,
            "preservation_summary": {
                "dirty_entry_count": len(inventory.get("dirty_entries") or []),
                "untracked_product_file_count": len(inventory.get("untracked_product_files") or []),
                "unpushed_commit_count": len(inventory.get("unpushed_commits") or []),
                "action_count": len(preservation_actions),
            },
            "preservation_actions": preservation_actions,
            "doctor": {
                "health_score": doctor.get("health_score"),
                "status": doctor.get("status"),
                "deductions": doctor.get("deductions") or [],
            },
            "actions": level_actions(level, doctor),
            "blockers": blockers,
            "rollback": {
                "old_workspace_kept": True,
                "new_workspace_inactive_until_validated": True,
                "requires_archive_manifest_before_move": True,
            },
        }
        items.append(item)
    plan = {
        "schema_version": "1.0",
        "mode": "project_rebuild_plan",
        "registry": str(registry_path),
        "level": level,
        "project_count": len(items),
        "blocked_count": sum(1 for item in items if item["blockers"]),
        "registry_warnings": registry_warnings,
        "projects": items,
        "product_code_changes_allowed": False,
    }
    plan["plan_hash"] = stable_hash(plan)
    return plan


def validate_apply(
    plan: dict[str, Any],
    approved_plan_hash: str | None,
    *,
    prior_plan: dict[str, Any] | None = None,
    allowed_product_diff_paths: list[str] | None = None,
) -> dict[str, Any]:
    allowed = [path for path in (allowed_product_diff_paths or []) if str(path).strip()]
    actual = str(plan.get("plan_hash") or "")
    errors: list[dict[str, str]] = []
    if not approved_plan_hash:
        errors.append({"code": "approved_plan_hash_missing", "message": "apply requires --approved-plan-hash"})
    elif approved_plan_hash != actual:
        errors.append({"code": "approved_plan_hash_mismatch", "message": "approved plan hash does not match current plan"})

    prior_projects_by_id = {
        str(project.get("project_id") or ""): project
        for project in (prior_plan.get("projects", []) if isinstance(prior_plan, dict) else [])
        if isinstance(project, dict)
    }

    payload_gate_failed = False
    for project in plan.get("projects") or []:
        if not isinstance(project, dict):
            continue

        if project.get("blockers"):
            errors.append({"code": "project_rebuild_blocked", "message": f"{project.get('project_id')} has blockers"})
            continue

        prior_project = prior_projects_by_id.get(str(project.get("project_id") or ""))
        prior_inventory = prior_project.get("inventory") if isinstance(prior_project, dict) else None
        current_inventory = project.get("inventory", {})
        comparison = compare_product_payload(
            before=prior_inventory if isinstance(prior_inventory, dict) else current_inventory,
            after=current_inventory if isinstance(current_inventory, dict) else {},
            allowed_product_diff_paths=allowed,
        )
        project["product_payload_validation"] = comparison

        if comparison["unexpected_product_payload_diff_count"] > 0:
            payload_gate_failed = True
            project.setdefault("preservation_actions", []).append({
                "level": int(project.get("level", 0)),
                "action": "block_unexpected_product_payload_diff",
                "mutates_product": False,
                "required": True,
                "unexpected_product_payload_diff_count": comparison["unexpected_product_payload_diff_count"],
                "allowed_product_payload_diff_count": comparison["allowed_product_payload_diff_count"],
                "allowed_product_diff_paths": allowed,
                "unexpected_diffs_sample": comparison["unexpected_product_payload_diffs"][:20],
                "recommended_recovery": "provide an updated baseline plan or allow only approved docs/service diff paths",
            })
            errors.append({
                "code": "product_payload_unexpected_diff",
                "message": f"{project.get('project_id')} has unexpected product payload diff",
                "unexpected_product_payload_diff_count": comparison["unexpected_product_payload_diff_count"],
            })
    return {
        "ok": not errors,
        "plan_hash": actual,
        "product_payload_gate_enabled": bool(prior_plan),
        "payload_gate_errors_detected": payload_gate_failed,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--level", type=int, default=0, choices=range(0, 6))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--compare-plan", type=Path)
    parser.add_argument("--approved-plan-hash")
    parser.add_argument("--allowed-product-diff-path", action="append", default=[], dest="allowed_product_diff_paths")
    parser.add_argument("--action-report-output", type=Path, help="Path to write Universal Action Report JSON.")
    parser.add_argument("--compact", action="store_true", help="Omit large file/deduction lists from JSON output while preserving plan_hash.")
    parser.add_argument("--compact-sample-limit", type=int, default=20)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    started_at = now_utc()

    plan = build_plan(args.registry.expanduser(), project_id=args.project_id, level=args.level)
    action_report_payload: dict[str, Any] | None = None
    apply_failed = False

    if args.apply:
        prior_plan: dict[str, Any] | None = None
        if args.compare_plan:
            try:
                prior_plan = json.loads(args.compare_plan.read_text(encoding="utf-8"))
                if not isinstance(prior_plan, dict):
                    raise ValueError("compare plan must be a JSON object")
            except (OSError, ValueError, json.JSONDecodeError):
                validation = {
                    "ok": False,
                    "plan_hash": str(plan.get("plan_hash") or ""),
                    "errors": [{"code": "invalid_compare_plan", "message": "compare plan is missing or invalid JSON object"}],
                }
            else:
                validation = validate_apply(
                    plan,
                    args.approved_plan_hash,
                    prior_plan=prior_plan,
                    allowed_product_diff_paths=args.allowed_product_diff_paths,
                )
        else:
            validation = validate_apply(plan, args.approved_plan_hash)

        plan["apply_validation"] = validation
        plan["applied"] = False
        action_report_payload = _build_rebuilder_action_payload(operation="apply", args=args, plan=plan, started_at=started_at)
        apply_failed = not bool(validation.get("ok"))
    else:
        action_report_payload = _build_rebuilder_action_payload(operation="plan", args=args, plan=plan, started_at=started_at)

    output_plan = compact_plan(plan, sample_limit=args.compact_sample_limit) if args.compact else plan
    if args.output:
        write_json_atomic(args.output.expanduser(), output_plan)
    if args.json:
        print(json.dumps(output_plan, ensure_ascii=False, indent=2))
    else:
        print(f"plan {plan['plan_hash']} projects={plan['project_count']} blocked={plan['blocked_count']}")

    if args.action_report_output:
        if action_report_payload is None:
            raise SystemExit("unable to build project rebuilder action report")
        report_validation = validate_action_report(action_report_payload)
        if not report_validation["ok"]:
            raise SystemExit(f"action report validation failed: {report_validation['errors']}")
        write_json_atomic(args.action_report_output.expanduser(), action_report_payload)

    return 2 if apply_failed else 0
if __name__ == "__main__":
    raise SystemExit(main())
