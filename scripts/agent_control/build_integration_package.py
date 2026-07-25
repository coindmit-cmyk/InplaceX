#!/usr/bin/env python3
"""Build an Integrator package branch from a prepared integration batch.

The script is deterministic glue between `integration_batch_builder.py` and the
Auto Finalizer gate. It never edits product code by hand: it creates an isolated
git worktree from the integration base, merges candidate branches one by one,
records a structured handoff, and optionally pushes the package branch.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_manager_dir


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run(cmd: list[str], cwd: Path | None = None) -> dict[str, Any]:
    proc = subprocess.run(cmd, cwd=str(cwd) if cwd else None, text=True, capture_output=True, check=False)
    return {"command": cmd, "cwd": str(cwd) if cwd else None, "exit_code": proc.returncode, "stdout": proc.stdout, "stderr": proc.stderr}


def run_check(command: str | list[str], cwd: Path) -> dict[str, Any]:
    argv = command.split() if isinstance(command, str) else list(command)
    if argv and argv[0] in {"python", "python3"}:
        argv[0] = sys.executable
    return run(argv, cwd=cwd)


def command_name(command: str | list[str]) -> str:
    if isinstance(command, str):
        return command
    return " ".join(str(part) for part in command)


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def slug(value: str) -> str:
    text = re.sub(r"[^a-zA-Z0-9._/-]+", "-", value.strip()).strip("-/").lower()
    return text or "integration-package"


def task_keys(item: dict[str, Any]) -> list[str]:
    values = [str(value) for value in item.get("task_ids") or [] if str(value or "").strip()]
    if values:
        return values
    source_id = str(item.get("source_artifact_id") or "").strip()
    return [source_id] if source_id else []


def primary_key(item: dict[str, Any]) -> str:
    keys = task_keys(item)
    if keys:
        return keys[0]
    return str(item.get("branch") or item.get("head_sha") or "unknown")


def normalize_branch(branch: Any) -> str:
    value = str(branch or "").strip()
    if value.startswith("refs/remotes/origin/"):
        return "origin/" + value.removeprefix("refs/remotes/origin/")
    if value.startswith("refs/heads/"):
        return value.removeprefix("refs/heads/")
    return value


def short_output(result: dict[str, Any]) -> str:
    text = (str(result.get("stderr") or "") + "\n" + str(result.get("stdout") or "")).strip()
    return text[-2000:]


def clean_worktree(project_root: Path, worktree: Path, results: list[dict[str, Any]]) -> None:
    if not worktree.exists():
        return
    results.append(run(["git", "worktree", "remove", "--force", str(worktree)], cwd=project_root))
    if worktree.exists():
        shutil.rmtree(worktree)
    results.append(run(["git", "worktree", "prune"], cwd=project_root))


def branch_disposition(item: dict[str, Any], disposition: str, reason: str, *, package_branch: str | None = None) -> dict[str, Any]:
    key = primary_key(item)
    canonical_target_id = item.get("canonical_target_id")
    if not canonical_target_id and key != "unknown":
        canonical_target_id = key if key.startswith("source-artifact:") else f"task:{key}"
    migration_sensitive = item_is_migration_sensitive(item)
    migration_policy = item.get("migration_compatibility_policy") if isinstance(item.get("migration_compatibility_policy"), dict) else None
    result = {
        "task_id": key,
        "task_ids": task_keys(item),
        "canonical_target_id": canonical_target_id,
        "source_artifact_id": item.get("source_artifact_id"),
        "branch": item.get("branch"),
        "head_sha": item.get("head_sha"),
        "disposition": disposition,
        "reason": reason,
        "package_branch": package_branch,
        "changed_paths": item.get("changed_paths") or [],
        "worker_report": item.get("worker_report"),
        "identity_status": item.get("identity_status"),
        "identity_provisional": item.get("identity_provisional"),
        "migration_sensitive": migration_sensitive,
        "migration_compatibility_policy": migration_policy,
        "integrator_must_adapt_migrations": bool(item.get("integrator_must_adapt_migrations") or (migration_sensitive and migration_policy)),
    }
    if disposition == "needs_rework":
        result["next_owner"] = "worker"
        result["rejection_detail"] = {
            "summary": "Integrator could not include this item in the package.",
            "blocking_reasons": [reason],
            "evidence": [f"source branch: {item.get('branch') or '<missing>'}", f"changed_paths: {', '.join(item_paths(item)) or '<missing>'}"],
            "recommended_next_action": "Rebase or repair the worker branch against the current integration base, then resubmit the task for integration.",
        }
    return result


def item_paths(item: dict[str, Any]) -> list[str]:
    paths = item.get("changed_paths") or item.get("integration_changed_paths") or []
    return sorted({str(path).replace("\\", "/") for path in paths if str(path or "").strip()})


def item_policy_code_refs(item: dict[str, Any]) -> list[str]:
    policy = item.get("migration_compatibility_policy")
    if not isinstance(policy, dict):
        return []
    refs = policy.get("code_refs") or []
    if not isinstance(refs, list):
        return []
    return sorted({str(path).replace("\\", "/") for path in refs if str(path or "").strip()})


def is_migration_path(path: str) -> bool:
    normalized = str(path or "").replace("\\", "/").lower()
    return "/migrations/" in f"/{normalized}" and normalized.endswith(".py") and not normalized.endswith("/__init__.py")


def item_is_migration_sensitive(item: dict[str, Any]) -> bool:
    if item.get("migration_sensitive") is True:
        return True
    return any(is_migration_path(path) for path in [*item_paths(item), *item_policy_code_refs(item)])


def batch_has_migrations(batch: dict[str, Any]) -> bool:
    for item in batch.get("included") or []:
        if not isinstance(item, dict):
            continue
        if item_is_migration_sensitive(item):
            return True
    return False


def migration_policy_summary(dispositions: list[dict[str, Any]]) -> dict[str, Any]:
    migration_items = [item for item in dispositions if item.get("migration_sensitive")]
    missing_policy = [
        str(item.get("task_id") or "")
        for item in migration_items
        if not isinstance(item.get("migration_compatibility_policy"), dict) or not item.get("migration_compatibility_policy")
    ]
    return {
        "migration_sensitive_count": len(migration_items),
        "missing_policy_task_ids": [item for item in missing_policy if item],
        "all_have_policy": not missing_policy,
    }


def migration_policy_check_results(batch: dict[str, Any]) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for item in batch.get("included") or []:
        if not isinstance(item, dict):
            continue
        if not item_is_migration_sensitive(item):
            continue
        key = primary_key(item)
        policy = item.get("migration_compatibility_policy")
        issues: list[str] = []
        if not isinstance(policy, dict) or not policy:
            issues.append("missing migration_compatibility_policy")
        else:
            if str(policy.get("mode") or "").strip() != "adapt_to_current_target":
                issues.append("migration_compatibility_policy.mode must be adapt_to_current_target")
            for field in ("required_integrator_behavior", "required_checks"):
                if not isinstance(policy.get(field), list) or not policy.get(field):
                    issues.append(f"migration_compatibility_policy.{field} must be non-empty")
        if issues:
            results.append({
                "name": f"migration compatibility policy for {key}",
                "state": "failed",
                "detail": "; ".join(issues),
            })
    return results


def auto_migration_check_commands(worktree: Path, batch: dict[str, Any], explicit_checks: list[str] | None) -> list[str | list[str]]:
    if not batch_has_migrations(batch):
        return []
    if explicit_checks:
        return []
    if (worktree / "manage.py").exists():
        return [
            [sys.executable, "manage.py", "makemigrations", "--check", "--dry-run"],
            [sys.executable, "manage.py", "migrate", "--plan"],
        ]
    return [[
        sys.executable,
        "-c",
        "import sys; sys.stderr.write('migration-sensitive integration package has no known migration check runner; configure --check-command\\n'); sys.exit(1)",
    ]]


def git_blob_oid(worktree: Path, ref: str, path: str) -> str | None:
    result = run(["git", "rev-parse", f"{ref}:{path}"], cwd=worktree)
    if result["exit_code"] != 0:
        return None
    oid = str(result.get("stdout") or "").strip()
    return oid or None


def migration_path_collisions(worktree: Path, branch: str, paths: list[str]) -> list[dict[str, str]]:
    collisions: list[dict[str, str]] = []
    for path in paths:
        if not is_migration_path(path):
            continue
        target_oid = git_blob_oid(worktree, "HEAD", path)
        source_oid = git_blob_oid(worktree, branch, path)
        if target_oid and source_oid and target_oid != source_oid:
            collisions.append({
                "path": path,
                "target_oid": target_oid,
                "source_oid": source_oid,
            })
    return collisions


def path_apply_collisions(
    worktree: Path,
    branch: str,
    paths: list[str],
) -> tuple[list[dict[str, str | None]], list[dict[str, Any]], str | None]:
    results: list[dict[str, Any]] = []
    merge_base = run(["git", "merge-base", "HEAD", branch], cwd=worktree)
    results.append(merge_base)
    if merge_base["exit_code"] != 0:
        return [], results, "failed to resolve source merge-base: " + short_output(merge_base)

    base_ref = str(merge_base.get("stdout") or "").strip()
    if not base_ref:
        return [], results, "failed to resolve source merge-base: git returned an empty revision"

    collisions: list[dict[str, str | None]] = []
    for path in paths:
        base_oid = git_blob_oid(worktree, base_ref, path)
        target_oid = git_blob_oid(worktree, "HEAD", path)
        source_oid = git_blob_oid(worktree, branch, path)
        if target_oid != base_oid and target_oid != source_oid:
            collisions.append({
                "path": path,
                "base_oid": base_oid,
                "target_oid": target_oid,
                "source_oid": source_oid,
            })
    return collisions, results, None


def apply_item_paths(worktree: Path, branch: str, paths: list[str]) -> tuple[bool, list[dict[str, Any]], str]:
    results: list[dict[str, Any]] = []
    if not paths:
        return False, results, "missing changed_paths for path-based package apply"
    checkout = run(["git", "checkout", branch, "--", *paths], cwd=worktree)
    results.append(checkout)
    if checkout["exit_code"] != 0:
        return False, results, "path checkout failed: " + short_output(checkout)
    add = run(["git", "add", "-A", "--", *paths], cwd=worktree)
    results.append(add)
    if add["exit_code"] != 0:
        return False, results, "git add failed: " + short_output(add)
    status = run(["git", "status", "--porcelain", "--", *paths], cwd=worktree)
    results.append(status)
    if status["exit_code"] != 0:
        return False, results, "git status failed: " + short_output(status)
    if not str(status.get("stdout") or "").strip():
        return False, results, "no package diff after applying changed_paths"
    return True, results, "applied changed_paths into package branch"


def stage_package_evidence(
    worktree: Path,
    handoff_path: Path,
    report_path: Path,
) -> tuple[bool, list[dict[str, Any]], str | None, str | None]:
    results: list[dict[str, Any]] = []
    handoff_rel = str(handoff_path.relative_to(worktree)).replace("\\", "/")
    report_rel = str(report_path.relative_to(worktree)).replace("\\", "/")

    add_handoff = run(["git", "add", "--", handoff_rel], cwd=worktree)
    results.append(add_handoff)
    if add_handoff["exit_code"] != 0:
        return False, results, "integration_handoff_stage", "failed to stage required integration handoff: " + short_output(add_handoff)

    ignored_report = run(["git", "check-ignore", "-q", "--", report_rel], cwd=worktree)
    results.append(ignored_report)
    if ignored_report["exit_code"] == 0:
        return True, results, None, None
    if ignored_report["exit_code"] != 1:
        results.append(run(["git", "reset", "--", handoff_rel], cwd=worktree))
        return False, results, "integration_report_ignore_check", "failed to inspect integration report ignore state: " + short_output(ignored_report)

    add_report = run(["git", "add", "--", report_rel], cwd=worktree)
    results.append(add_report)
    if add_report["exit_code"] != 0:
        results.append(run(["git", "reset", "--", handoff_rel, report_rel], cwd=worktree))
        return False, results, "integration_report_stage", "failed to stage integration report: " + short_output(add_report)
    return True, results, None, None


def excluded_keys(batch: dict[str, Any]) -> list[str]:
    keys: list[str] = []
    for excluded in batch.get("excluded") or []:
        if not isinstance(excluded, dict):
            continue
        item = excluded.get("item") if isinstance(excluded.get("item"), dict) else {}
        key = primary_key(item)
        if key and key != "unknown":
            keys.append(key)
    return sorted(set(keys))


def render_report(handoff: dict[str, Any], results: list[dict[str, Any]]) -> str:
    lines = [
        "# Auto Integrator Package",
        "",
        f"- Generated: `{handoff.get('created_at')}`",
        f"- Status: `{handoff.get('integration_status')}`",
        f"- Package branch: `{handoff.get('package_branch')}`",
        f"- Base: `{handoff.get('base_branch')}` `{handoff.get('base_sha')}`",
        f"- Ready: `{len(handoff.get('ready_to_finalize') or [])}`",
        f"- Needs rework: `{len(handoff.get('needs_rework') or [])}`",
        f"- Checks passed: `{handoff.get('required_checks_passed')}`",
        "",
        "## Branch Results",
        "",
        "| Disposition | Branch | Tasks | Reason |",
        "| --- | --- | --- | --- |",
    ]
    for item in handoff.get("branch_dispositions") or []:
        tasks = ", ".join(item.get("task_ids") or [str(item.get("task_id") or "-")])
        lines.append(f"| `{item.get('disposition')}` | `{item.get('branch')}` | `{tasks}` | {str(item.get('reason') or '').replace('|', '/')} |")
    lines.extend(["", "## Commands", ""])
    for result in results:
        cmd = " ".join(str(part) for part in result.get("command") or [])
        lines.append(f"- `{result.get('exit_code')}` `{cmd}`")
    lines.append("")
    return "\n".join(lines)


def block_package(handoff: dict[str, Any], reason: str, blocker: str) -> None:
    handoff["integration_status"] = "integration_blocked"
    handoff["required_checks_passed"] = False
    handoff["mergeable"] = False
    blocked = {str(item) for item in handoff.get("blocked") or [] if str(item or "").strip()}
    blocked.add(blocker)
    handoff["blocked"] = sorted(blocked)
    excluded = {str(item) for item in handoff.get("excluded_from_package") or [] if str(item or "").strip()}
    excluded.add(blocker)
    handoff["excluded_from_package"] = sorted(excluded)
    reasons = [str(item) for item in handoff.get("blocked_by") or [] if str(item or "").strip()]
    reasons.append(reason)
    handoff["blocked_by"] = reasons


def build_blocked_handoff(
    *,
    project_root: Path,
    batch: dict[str, Any],
    package_branch: str,
    base_ref: str,
    reason: str,
    results: list[dict[str, Any]],
) -> dict[str, Any]:
    included = [item for item in batch.get("included") or [] if isinstance(item, dict)]
    dispositions = [branch_disposition(item, "needs_rework", reason, package_branch=package_branch) for item in included]
    needs_rework = sorted({primary_key(item) for item in included if primary_key(item) != "unknown"})
    blocked = needs_rework or ["integration_batch"]
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "source": "build_integration_package.py",
        "integration_status": "integration_blocked",
        "package_branch": package_branch,
        "base_branch": base_ref,
        "base_sha": "unknown",
        "ready_to_finalize": [],
        "needs_rework": needs_rework,
        "needs_worker_fix": [],
        "needs_dispatcher": [],
        "needs_architect": [],
        "needs_human": [],
        "blocked": blocked,
        "excluded_from_package": sorted(set(excluded_keys(batch)) | set(needs_rework) | set(blocked)),
        "cleanup_candidates": [],
        "branch_dispositions": dispositions,
        "migration_policy_summary": migration_policy_summary(dispositions),
        "merge_conflicts": [],
        "checks": ["git worktree setup"],
        "check_results": [{"name": "git worktree setup", "state": "failed", "detail": reason}],
        "required_checks_passed": False,
        "mergeable": False,
        "finalizer_authority_required": "finalizer merge gate for ready_to_finalize only; blocked package cannot be finalized",
        "blocked_by": [reason],
        "command_results": results,
    }


def build_package(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    reports = plans / "reports"
    batch_path = Path(args.batch).resolve() if args.batch else plans / "integration_batch.json"
    handoff_path = Path(args.output).resolve() if args.output else plans / "integration_handoff.json"
    batch = load_json(batch_path)
    included = [item for item in batch.get("included") or [] if isinstance(item, dict)]
    batch_id = str(batch.get("batch_id") or datetime.now(timezone.utc).strftime("BATCH-%Y%m%d-%H%M%S"))
    package_branch = args.package_branch or f"AiStudio/Agent/integrator/{slug(batch_id)}"
    worktree_root = Path(args.worktree_root).resolve() if args.worktree_root else project_root / "AiStudio" / "Agent" / "integrator-worktrees"
    worktree = worktree_root / slug(package_branch.replace("/", "-"))
    results: list[dict[str, Any]] = []

    if not included:
        handoff = build_blocked_handoff(
            project_root=project_root,
            batch=batch,
            package_branch=package_branch,
            base_ref=args.base_ref,
            reason="integration batch has no included items",
            results=results,
        )
        write_json(handoff_path, handoff)
        return handoff

    if args.fetch:
        results.append(run(["git", "fetch", "--all", "--prune"], cwd=project_root))
        if results[-1]["exit_code"] != 0:
            handoff = build_blocked_handoff(project_root=project_root, batch=batch, package_branch=package_branch, base_ref=args.base_ref, reason="git fetch failed", results=results)
            write_json(handoff_path, handoff)
            return handoff

    if args.apply:
        clean_worktree(project_root, worktree, results)
        worktree.parent.mkdir(parents=True, exist_ok=True)
        results.append(run(["git", "worktree", "add", "-B", package_branch, str(worktree), args.base_ref], cwd=project_root))
        if results[-1]["exit_code"] != 0:
            handoff = build_blocked_handoff(project_root=project_root, batch=batch, package_branch=package_branch, base_ref=args.base_ref, reason="git worktree add failed: " + short_output(results[-1]), results=results)
            write_json(handoff_path, handoff)
            return handoff
    else:
        handoff = build_blocked_handoff(project_root=project_root, batch=batch, package_branch=package_branch, base_ref=args.base_ref, reason="dry run; package branch not created", results=results)
        write_json(handoff_path, handoff)
        return handoff

    ready: list[str] = []
    needs_rework: list[str] = []
    merge_conflicts: list[dict[str, Any]] = []
    dispositions: list[dict[str, Any]] = []

    base_sha_result = run(["git", "rev-parse", "HEAD"], cwd=worktree)
    results.append(base_sha_result)
    base_sha = base_sha_result["stdout"].strip() if base_sha_result["exit_code"] == 0 else None

    for item in included[: max(0, int(args.max_items or 0)) or len(included)]:
        branch = normalize_branch(item.get("branch"))
        key = primary_key(item)
        if not branch:
            needs_rework.append(key)
            dispositions.append(branch_disposition(item, "needs_rework", "missing source branch", package_branch=package_branch))
            continue
        verify = run(["git", "rev-parse", "--verify", branch], cwd=worktree)
        results.append(verify)
        if verify["exit_code"] != 0:
            needs_rework.append(key)
            dispositions.append(branch_disposition(item, "needs_rework", "source branch not found: " + branch, package_branch=package_branch))
            continue
        if args.apply_mode == "merge":
            merge = run(["git", "merge", "--no-ff", "--no-edit", branch], cwd=worktree)
            results.append(merge)
            if merge["exit_code"] == 0:
                ready.append(key)
                dispositions.append(branch_disposition(item, "ready_to_finalize", "merged into package branch", package_branch=package_branch))
                continue
            abort = run(["git", "merge", "--abort"], cwd=worktree)
            results.append(abort)
            needs_rework.append(key)
            detail = short_output(merge)
            merge_conflicts.append({"task": key, "branch": branch, "detail": detail})
            dispositions.append(branch_disposition(item, "needs_rework", "merge failed: " + detail, package_branch=package_branch))
            continue

        paths = item_paths(item)
        collisions, collision_results, collision_error = path_apply_collisions(worktree, branch, paths)
        results.extend(collision_results)
        if collision_error:
            needs_rework.append(key)
            dispositions.append(branch_disposition(item, "needs_rework", collision_error, package_branch=package_branch))
            continue
        if collisions:
            needs_rework.append(key)
            collision_paths = ", ".join(item["path"] for item in collisions)
            dispositions.append(branch_disposition(
                item,
                "needs_rework",
                f"path apply would overwrite current target changes: {collision_paths}; source branch must be rebased or adapted to current target before packaging",
                package_branch=package_branch,
            ))
            continue

        collisions = migration_path_collisions(worktree, branch, paths) if item_is_migration_sensitive(item) else []
        if collisions:
            needs_rework.append(key)
            collision_paths = ", ".join(item["path"] for item in collisions)
            dispositions.append(branch_disposition(
                item,
                "needs_rework",
                f"migration path collides with current target: {collision_paths}; integrator must adapt migration to current target before packaging",
                package_branch=package_branch,
            ))
            continue

        applied, apply_results, reason = apply_item_paths(worktree, branch, paths)
        results.extend(apply_results)
        if not applied:
            needs_rework.append(key)
            dispositions.append(branch_disposition(item, "needs_rework", reason, package_branch=package_branch))
            continue
        commit = run(["git", "commit", "-m", f"chore(integrator): apply {key}"], cwd=worktree)
        results.append(commit)
        if commit["exit_code"] != 0:
            needs_rework.append(key)
            dispositions.append(branch_disposition(item, "needs_rework", "path apply commit failed: " + short_output(commit), package_branch=package_branch))
            continue
        ready.append(key)
        dispositions.append(branch_disposition(item, "ready_to_finalize", reason, package_branch=package_branch))

    check_results: list[dict[str, Any]] = []
    diff_range = f"{base_sha}..HEAD" if base_sha else "HEAD"
    diff_check = run(["git", "diff", "--check", diff_range], cwd=worktree)
    results.append(diff_check)
    check_results.append({"name": f"git diff --check {diff_range}", "state": "passed" if diff_check["exit_code"] == 0 else "failed", "detail": short_output(diff_check)})
    check_results.extend(migration_policy_check_results(batch))
    for command in [*auto_migration_check_commands(worktree, batch, args.check_command), *(args.check_command or [])]:
        check = run_check(command, cwd=worktree)
        results.append(check)
        check_results.append({"name": command_name(command), "state": "passed" if check["exit_code"] == 0 else "failed", "detail": short_output(check)})

    required_passed = all(item.get("state") == "passed" for item in check_results)
    status = "integration_package_ready" if ready and not needs_rework and required_passed else "partial_package_ready" if ready and required_passed else "integration_blocked"

    ready_set = set(ready)
    needs_rework_set = set(needs_rework)
    blocked_set: set[str] = set()
    if status == "integration_blocked":
        if needs_rework_set:
            blocked_set.update(needs_rework_set)
        elif not required_passed:
            blocked_set.add("required_checks")
        else:
            blocked_set.add("integration_package")
    excluded_set = ((set(excluded_keys(batch)) | needs_rework_set | blocked_set) - ready_set) | (blocked_set - ready_set)

    handoff = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "source": "build_integration_package.py",
        "batch": str(batch_path),
        "batch_id": batch_id,
        "integration_status": status,
        "package_branch": package_branch,
        "package_worktree": str(worktree),
        "base_branch": args.finalizer_base_branch,
        "base_ref": args.base_ref,
        "base_sha": base_sha,
        "ready_to_finalize": sorted(ready_set),
        "needs_rework": sorted(needs_rework_set),
        "needs_worker_fix": [],
        "needs_dispatcher": [],
        "needs_architect": [],
        "needs_human": [],
        "blocked": sorted(blocked_set),
        "excluded_from_package": sorted(excluded_set),
        "cleanup_candidates": [],
        "branch_dispositions": dispositions,
        "migration_policy_summary": migration_policy_summary(dispositions),
        "merge_conflicts": merge_conflicts,
        "checks": [item["name"] for item in check_results],
        "check_results": check_results,
        "required_checks_passed": required_passed,
        "mergeable": bool(ready and required_passed),
        "finalizer_authority_required": "finalizer merge gate for ready_to_finalize only; owner decision only for risky/ambiguous/blocked items",
        "command_results": results,
    }

    worktree_plans = task_manager_dir(worktree)
    worktree_handoff_path = worktree_plans / "integration_handoff.json"
    write_json(worktree_handoff_path, handoff)
    report_path = worktree_plans / "reports" / f"AUTO_INTEGRATOR_PACKAGE_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_report(handoff, results), encoding="utf-8")
    evidence_staged, evidence_results, evidence_blocker, evidence_reason = stage_package_evidence(worktree, worktree_handoff_path, report_path)
    results.extend(evidence_results)
    if evidence_staged:
        commit = run(["git", "commit", "-m", f"docs(integrator): package {batch_id}"], cwd=worktree)
        results.append(commit)
        if commit["exit_code"] != 0 and "nothing to commit" not in short_output(commit).lower():
            block_package(handoff, "failed to commit integration handoff", "integration_handoff_commit")
    else:
        block_package(handoff, evidence_reason or "failed to stage package evidence", evidence_blocker or "integration_evidence_stage")
        write_json(worktree_handoff_path, handoff)

    if args.push and handoff["integration_status"] in {"integration_package_ready", "partial_package_ready"}:
        push = run(["git", "push", "-u", "origin", package_branch], cwd=worktree)
        results.append(push)
        if push["exit_code"] != 0:
            block_package(handoff, "failed to push package branch: " + short_output(push), "package_branch_push")

    handoff["command_results"] = results
    write_json(handoff_path, handoff)
    reports.mkdir(parents=True, exist_ok=True)
    (reports / f"AUTO_INTEGRATOR_PACKAGE_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md").write_text(render_report(handoff, results), encoding="utf-8")
    append_log(project_root, "integrator", "integration_package_built", severity="info", status=handoff["integration_status"], ready=len(ready), rework=len(needs_rework), package_branch=package_branch)
    return handoff


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--batch")
    parser.add_argument("--output")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--finalizer-base-branch", default="develop")
    parser.add_argument("--package-branch")
    parser.add_argument("--worktree-root")
    parser.add_argument("--max-items", type=int, default=10)
    parser.add_argument("--check-command", action="append")
    parser.add_argument("--apply-mode", choices=("paths", "merge"), default="paths")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--push", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    handoff = build_package(args)
    if args.json:
        print(json.dumps(handoff, ensure_ascii=False, indent=2))
    else:
        print(f"status: {handoff.get('integration_status')}")
        print(f"ready: {len(handoff.get('ready_to_finalize') or [])}")
        print(f"needs_rework: {len(handoff.get('needs_rework') or [])}")
        print(f"package_branch: {handoff.get('package_branch')}")
    return 0 if handoff.get("integration_status") in {"integration_package_ready", "partial_package_ready"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
