#!/usr/bin/env python3
"""Converge automation checkouts without resetting local work.

Only a clean, expected-branch checkout that is behind its configured origin
branch is fast-forwarded.  Known generated state is left in place for the
existing recovery flow.  Any product dirt, local commits, branch mismatch, or
divergence is preserved and automation is directed to a separate clean clone.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
from pathlib import Path
from typing import Any

import project_registry
import status_orchestrator


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def git(path: Path, args: list[str], timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=path,
        text=True,
        capture_output=True,
        check=False,
        timeout=timeout,
    )


def porcelain_path(line: str) -> str:
    """Extract the effective path from a porcelain-v1 status line."""
    text = str(line or "").rstrip()
    if len(text) < 4:
        return ""
    path = text[3:]
    if " -> " in path:
        path = path.rsplit(" -> ", 1)[1]
    return path.strip().replace("\\", "/")


def is_generated_state_path(path: str) -> bool:
    """Return whether a path is explicitly eligible for state recovery.

    This deliberately follows the current Status Orchestrator allowlist rather
    than treating a broad runtime directory as generated state.
    """
    normalized = str(path or "").replace("\\", "/").strip().strip("/")
    return any(
        normalized == scope or normalized.startswith(f"{scope.rstrip('/')}/")
        for scope in status_orchestrator.PRE_APPLY_RECOVERABLE_STATE_PATHS
    )


def safe_slug(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", str(value or "project")).strip("-.")
    return slug or "project"


def branch_name(project: dict[str, Any]) -> str:
    value = str(project.get("code_base_ref") or project.get("base_ref") or project.get("base_branch") or "develop")
    return value.removeprefix("refs/remotes/").removeprefix("origin/")


def project_versions(path: Path, project: dict[str, Any]) -> dict[str, Any]:
    versions: dict[str, Any] = {}
    for key, relative in (
        ("project", str(project.get("version_file") or "PROJECT_VERSION.json")),
        ("agent", str(project.get("agent_version_path") or ".agent/agent_version.json")),
    ):
        try:
            payload = json.loads((path / relative).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            payload = None
        versions[key] = {
            "version": payload.get("version") or payload.get("agent_version"),
            "commit": payload.get("commit") or payload.get("agent_commit"),
        } if isinstance(payload, dict) else None
    return versions


def inspect_checkout(path: Path, project: dict[str, Any], *, fetch: bool) -> dict[str, Any]:
    expected_branch = branch_name(project)
    report: dict[str, Any] = {
        "path": str(path),
        "exists": path.exists(),
        "is_git_worktree": False,
        "branch": None,
        "head": None,
        "upstream": f"origin/{expected_branch}",
        "ahead": None,
        "behind": None,
        "dirty_kind": "missing",
        "dirty_paths": [],
    }
    if not path.exists():
        return report

    worktree = git(path, ["rev-parse", "--is-inside-work-tree"])
    if worktree.returncode != 0 or worktree.stdout.strip().lower() != "true":
        report["dirty_kind"] = "non_git"
        return report
    report["is_git_worktree"] = True

    if fetch:
        fetched = git(path, ["fetch", "origin", "--prune"], timeout=300)
        report["fetch_ok"] = fetched.returncode == 0
        if fetched.returncode != 0:
            report["fetch_error"] = fetched.stderr.strip()[-1000:]

    head = git(path, ["rev-parse", "HEAD"])
    current_branch = git(path, ["branch", "--show-current"])
    status = git(path, ["status", "--porcelain"])
    report["head"] = head.stdout.strip() if head.returncode == 0 else None
    report["branch"] = current_branch.stdout.strip() if current_branch.returncode == 0 else None
    dirty_paths = [porcelain_path(line) for line in status.stdout.splitlines()] if status.returncode == 0 else []
    report["dirty_paths"] = [path for path in dirty_paths if path]
    if not report["dirty_paths"]:
        report["dirty_kind"] = "clean"
    elif all(is_generated_state_path(path) for path in report["dirty_paths"]):
        report["dirty_kind"] = "generated_state_only"
    else:
        report["dirty_kind"] = "product_or_unknown"

    upstream = str(report["upstream"])
    if git(path, ["rev-parse", "--verify", "--quiet", upstream]).returncode == 0:
        counts = git(path, ["rev-list", "--left-right", "--count", f"HEAD...{upstream}"])
        parts = counts.stdout.split() if counts.returncode == 0 else []
        if len(parts) == 2:
            report["ahead"], report["behind"] = (int(part) for part in parts)
    origin = git(path, ["remote", "get-url", "origin"])
    report["origin_available"] = origin.returncode == 0 and bool(origin.stdout.strip())
    report["versions"] = project_versions(path, project)
    return report


def origin_url(path: Path, project: dict[str, Any]) -> str:
    if path.exists():
        origin = git(path, ["remote", "get-url", "origin"])
        if origin.returncode == 0 and origin.stdout.strip():
            return origin.stdout.strip()
    repository = str(project.get("github_repo") or "").strip()
    return f"https://github.com/{repository}.git" if repository else ""


def checkout_is_usable(report: dict[str, Any], expected_branch: str) -> bool:
    return bool(
        report.get("is_git_worktree")
        and report.get("branch") == expected_branch
        and report.get("dirty_kind") in {"clean", "generated_state_only"}
        and report.get("ahead") is not None
        and int(report["ahead"]) == 0
    )


def available_managed_target(managed_root: Path, project_id: str) -> Path:
    """Return a vacant managed path without moving any pre-existing checkout."""
    base = managed_root / safe_slug(project_id)
    if not base.exists():
        return base
    suffix = 1
    while True:
        candidate = managed_root / f"{base.name}-managed-{suffix}"
        if not candidate.exists():
            return candidate
        suffix += 1


def fast_forward(path: Path, expected_branch: str) -> dict[str, Any]:
    merged = git(path, ["merge", "--ff-only", f"origin/{expected_branch}"], timeout=300)
    return {
        "ok": merged.returncode == 0,
        "error": merged.stderr.strip()[-1000:] if merged.returncode else "",
    }


def converge_checkout(project: dict[str, Any], managed_root: Path, *, apply: bool) -> dict[str, Any]:
    project_id = str(project.get("project_id") or "unknown")
    configured_path = str(project.get("automation_path") or project.get("local_path") or "").strip()
    current = Path(configured_path).expanduser() if configured_path else managed_root / ".missing-source" / safe_slug(project_id)
    expected_branch = branch_name(project)
    before = inspect_checkout(current, project, fetch=apply)
    result: dict[str, Any] = {
        "project_id": project_id,
        "expected_branch": expected_branch,
        "before": before,
        "selected_path": str(current),
        "registry_update_required": False,
        "action": "none",
        "ok": True,
    }

    if apply and before.get("fetch_ok") is False:
        result.update({"ok": False, "action": "blocked", "reason": "checkout_fetch_failed"})
        return result

    if checkout_is_usable(before, expected_branch):
        if before["dirty_kind"] == "generated_state_only":
            result["action"] = "recover_generated_state_in_place"
            result["recovery_required"] = True
            return result
        if int(before["behind"] or 0) > 0:
            result["action"] = "fast_forward_current"
            if apply:
                result.update(fast_forward(current, expected_branch))
                result["after"] = inspect_checkout(current, project, fetch=False)
            return result
        result["action"] = "current_checkout_ready"
        result["after"] = before
        return result

    preferred_target = managed_root / safe_slug(project_id)
    managed_before = inspect_checkout(preferred_target, project, fetch=apply) if preferred_target != current else before
    result["managed_before"] = managed_before
    if apply and managed_before.get("fetch_ok") is False:
        result.update({"ok": False, "action": "blocked", "reason": "managed_checkout_fetch_failed"})
        return result
    if checkout_is_usable(managed_before, expected_branch):
        result["selected_path"] = str(preferred_target)
        result["registry_update_required"] = preferred_target.resolve(strict=False) != current.resolve(strict=False)
        if managed_before["dirty_kind"] == "generated_state_only":
            result["action"] = "use_managed_checkout_with_state_recovery"
            result["recovery_required"] = True
            return result
        if int(managed_before["behind"] or 0) > 0:
            result["action"] = "fast_forward_managed_checkout"
            if apply:
                result.update(fast_forward(preferred_target, expected_branch))
                result["after"] = inspect_checkout(preferred_target, project, fetch=False)
            return result
        result["action"] = "use_existing_managed_checkout"
        result["after"] = managed_before
        return result

    source_url = origin_url(current, project)
    if not source_url:
        result.update({"ok": False, "action": "blocked", "reason": "repository_origin_unavailable"})
        return result
    target = available_managed_target(managed_root, project_id)
    result.update({
        "action": "create_clean_managed_checkout",
        "selected_path": str(target),
        "registry_update_required": target.resolve(strict=False) != current.resolve(strict=False),
        "preserved_unsafe_paths": [str(path) for path in (current, preferred_target) if path.exists() and path != target],
    })
    if apply:
        managed_root.mkdir(parents=True, exist_ok=True)
        clone = subprocess.run(
            ["git", "clone", "--branch", expected_branch, source_url, str(target)],
            text=True,
            capture_output=True,
            check=False,
            timeout=900,
        )
        result["ok"] = clone.returncode == 0
        if clone.returncode != 0:
            result.update({"reason": "managed_checkout_clone_failed", "clone_error": clone.stderr.strip()[-1000:]})
            return result
        result["after"] = inspect_checkout(target, project, fetch=False)
    return result


def converge_registry(registry_path: Path, managed_root: Path, *, apply: bool, project_id: str | None = None) -> dict[str, Any]:
    raw = project_registry.load_json(registry_path)
    if not isinstance(raw.get("projects"), list):
        raise ValueError("project registry must contain projects array")
    projects, warnings = project_registry.load_projects(registry_path, project_id)
    results = [converge_checkout(project, managed_root, apply=apply) for project in projects]
    updates = {
        str(result["project_id"]): str(result["selected_path"])
        for result in results
        if result.get("ok") and result.get("registry_update_required")
    }
    updated_count = 0
    if apply and updates:
        for project in raw["projects"]:
            if not isinstance(project, dict):
                continue
            identifier = str(project.get("project_id") or project.get("id") or project.get("name") or "")
            if identifier not in updates:
                continue
            project["automation_path"] = updates[identifier]
            project["automation_path_managed"] = True
            project["automation_path_updated_at"] = now_utc()
            updated_count += 1
        write_json_atomic(registry_path, raw)
    return {
        "schema_version": "1.0",
        "generated_at": now_utc(),
        "apply": apply,
        "registry": str(registry_path),
        "managed_root": str(managed_root),
        "project_count": len(projects),
        "updated_count": updated_count,
        "warnings": warnings,
        "failed_count": sum(1 for result in results if not result.get("ok")),
        "results": results,
        "ok": all(bool(result.get("ok")) for result in results),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True)
    parser.add_argument("--managed-root", default="~/agent-runtime/project-checkouts")
    parser.add_argument("--project-id")
    parser.add_argument("--output")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = converge_registry(
        Path(args.registry).expanduser().resolve(),
        Path(args.managed_root).expanduser().resolve(),
        apply=bool(args.apply),
        project_id=args.project_id,
    )
    if args.output:
        write_json_atomic(Path(args.output).expanduser().resolve(), report)
    print(json.dumps(report, ensure_ascii=False, indent=2) if args.json else f"converged: {report['updated_count']}")
    return 0 if report["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
