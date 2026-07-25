#!/usr/bin/env python3
"""Apply approved automation worktree plans.

The provisioner is guarded:
- dry-run is the default;
- registry writes require --apply;
- clone actions require a completed remote check and no planner blockers;
- secret values are never printed.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path
from typing import Any

import automation_worktree_planner


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def project_key(project: dict[str, Any]) -> str:
    return str(project.get("project_id") or project.get("id") or project.get("name") or "").strip()


def update_registry_automation_paths(registry_path: Path, patches: list[dict[str, Any]], apply: bool) -> dict[str, Any]:
    if not patches:
        return {"updated_count": 0, "updates": []}
    registry = load_json(registry_path)
    projects = registry.get("projects")
    if not isinstance(projects, list):
        raise ValueError("project registry must contain projects array")
    patch_by_project = {
        str(patch.get("project_id") or "").strip(): str(patch.get("automation_path") or "").strip()
        for patch in patches
        if str(patch.get("project_id") or "").strip() and str(patch.get("automation_path") or "").strip()
    }
    updates: list[dict[str, Any]] = []
    for project in projects:
        if not isinstance(project, dict):
            continue
        pid = project_key(project)
        if pid not in patch_by_project:
            continue
        before = str(project.get("automation_path") or "")
        after = patch_by_project[pid]
        updates.append({"project_id": pid, "before": before or None, "after": after})
        if apply:
            project["automation_path"] = after
    if apply and updates:
        write_json_atomic(registry_path, registry)
    return {"updated_count": len(updates), "updates": updates}


def run_clone(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    return subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        text=True,
        capture_output=True,
        check=False,
        timeout=300,
        env=env,
    )


def run_git_store(git_store: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    return subprocess.run(
        ["git", f"--git-dir={git_store}", *args],
        text=True,
        capture_output=True,
        check=False,
        timeout=300,
        env=env,
    )


def validate_apply_item(item: dict[str, Any]) -> list[str]:
    blockers = [str(value) for value in item.get("blockers") or []]
    action = str(item.get("action") or "")
    remote_access = item.get("remote_access") if isinstance(item.get("remote_access"), dict) else {}
    if action == "clone_and_set_automation_path" and not remote_access.get("checked"):
        blockers.append("remote_check_required_for_apply")
    if action == "clone_and_set_automation_path" and remote_access.get("ok") is False:
        reason = str(remote_access.get("reason") or "remote_access_failed")
        if reason not in blockers:
            blockers.append(reason)
    return blockers


def apply_plan_item(item: dict[str, Any], apply: bool) -> dict[str, Any]:
    action = str(item.get("action") or "")
    project_id = str(item.get("project_id") or "")
    proposed_path = Path(str(item.get("proposed_automation_path") or "")).expanduser()
    result: dict[str, Any] = {
        "project_id": project_id,
        "action": action,
        "applied": False,
        "registry_patch": item.get("registry_patch") or {},
        "proposed_automation_path": str(proposed_path) if str(proposed_path) else None,
    }
    if action == "none":
        result["state"] = "ready"
        return result
    blockers = validate_apply_item(item) if apply else [str(value) for value in item.get("blockers") or []]
    if blockers:
        result.update({"state": "blocked", "blockers": blockers})
        return result
    if action not in {"set_automation_path", "clone_and_set_automation_path"}:
        result.update({"state": "skipped", "reason": f"unsupported action: {action}"})
        return result
    if not apply:
        result["state"] = "planned"
        return result
    if action == "clone_and_set_automation_path" and not automation_worktree_planner.is_git_worktree(proposed_path):
        if proposed_path.exists():
            result.update({"state": "failed", "error": "proposed_path_exists_but_is_not_git_worktree"})
            return result
        proposed_path.parent.mkdir(parents=True, exist_ok=True)
        command = [str(part) for part in item.get("clone_command") or []]
        if not command:
            result.update({"state": "failed", "error": "clone_command_missing"})
            return result
        proc = run_clone(command)
        result["clone"] = {"returncode": proc.returncode, "stdout": proc.stdout[-2000:], "stderr": proc.stderr[-2000:]}
        if proc.returncode != 0:
            result.update({"state": "failed", "error": "git_clone_failed"})
            return result
    if not automation_worktree_planner.is_git_worktree(proposed_path):
        result.update({"state": "failed", "error": "proposed_path_is_not_git_worktree"})
        return result
    result.update({"state": "ready_to_patch_registry", "applied": True})
    return result


def provision(
    registry_path: Path,
    worktree_root: Path,
    *,
    apply: bool = False,
    check_remote: bool = True,
    project_id: str | None = None,
) -> dict[str, Any]:
    report = automation_worktree_planner.build_report(registry_path, worktree_root, check_remote=check_remote)
    items = [
        item
        for item in report.get("projects") or []
        if isinstance(item, dict) and (not project_id or str(item.get("project_id") or "") == project_id)
    ]
    results = [apply_plan_item(item, apply=apply) for item in items]
    patches = [
        result.get("registry_patch")
        for result in results
        if result.get("applied") and isinstance(result.get("registry_patch"), dict)
    ]
    registry_update = update_registry_automation_paths(registry_path, patches, apply=apply)
    failed_count = sum(1 for result in results if result.get("state") in {"blocked", "failed"})
    return {
        "schema_version": "1.0",
        "apply": bool(apply),
        "remote_checked": bool(report.get("remote_checked")),
        "registry": str(registry_path),
        "worktree_root": str(worktree_root),
        "project_filter": project_id,
        "credential_readiness": report.get("credential_readiness"),
        "project_count": len(items),
        "failed_count": failed_count,
        "registry_update": registry_update,
        "results": results,
    }


def apply_layout_action(action: dict[str, Any], apply: bool) -> dict[str, Any]:
    name = str(action.get("action") or "")
    path = Path(str(action.get("path") or "")).expanduser()
    result: dict[str, Any] = {"action": name, "path": str(path), "applied": False}
    if not apply:
        result["state"] = "planned"
        return result
    if name == "create_directory":
        path.mkdir(parents=True, exist_ok=True)
        result.update({"state": "done", "applied": True})
        return result
    if name == "clone_bare_store":
        if automation_worktree_planner.is_bare_git_store(path):
            result.update({"state": "already_ready", "applied": False})
            return result
        if path.exists():
            result.update({"state": "failed", "error": "git_store_exists_but_not_bare"})
            return result
        remote = str(action.get("remote") or "")
        if not remote:
            result.update({"state": "failed", "error": "remote_missing"})
            return result
        path.parent.mkdir(parents=True, exist_ok=True)
        proc = run_clone(["git", "clone", "--bare", remote, str(path)])
        result["clone"] = {"returncode": proc.returncode, "stdout": proc.stdout[-2000:], "stderr": proc.stderr[-2000:]}
        if proc.returncode != 0:
            result.update({"state": "failed", "error": "git_clone_bare_failed"})
            return result
        result.update({"state": "done", "applied": True})
        return result
    if name in {"reuse_bare_store", "write_workspace_manifest", "create_worktree"}:
        result["state"] = "deferred"
        return result
    result.update({"state": "skipped", "reason": f"unsupported action: {name}"})
    return result


def create_layout_worktree(git_store: Path, action: dict[str, Any], apply: bool) -> dict[str, Any]:
    path = Path(str(action.get("path") or "")).expanduser()
    branch = str(action.get("branch") or "").strip()
    role = str(action.get("role") or "")
    result = {"action": "create_worktree", "role": role, "path": str(path), "branch": branch, "applied": False}
    if not apply:
        result["state"] = "planned"
        return result
    if automation_worktree_planner.is_git_worktree(path):
        result["state"] = "already_ready"
        return result
    if path.exists() and any(path.iterdir()):
        result.update({"state": "failed", "error": "checkout_exists_non_empty"})
        return result
    if not automation_worktree_planner.branch_exists_in_store(git_store, branch):
        result.update({"state": "failed", "error": "branch_missing_in_store"})
        return result
    path.parent.mkdir(parents=True, exist_ok=True)
    proc = run_git_store(git_store, ["worktree", "add", str(path), branch])
    result["git"] = {"returncode": proc.returncode, "stdout": proc.stdout[-2000:], "stderr": proc.stderr[-2000:]}
    if proc.returncode != 0:
        result.update({"state": "failed", "error": "git_worktree_add_failed"})
        return result
    result.update({"state": "done", "applied": True})
    return result


def write_workspace_manifest(project_plan: dict[str, Any], apply: bool) -> dict[str, Any]:
    path = Path(str(project_plan.get("manifest_path") or "")).expanduser()
    result = {"action": "write_workspace_manifest", "path": str(path), "applied": False}
    if not apply:
        result["state"] = "planned"
        return result
    path.parent.mkdir(parents=True, exist_ok=True)
    write_json_atomic(path, project_plan["manifest"])
    result.update({"state": "done", "applied": True})
    return result


def provision_layout(
    registry_path: Path,
    *,
    apply: bool = False,
    check_remote: bool = True,
    project_id: str | None = None,
) -> dict[str, Any]:
    report = automation_worktree_planner.build_layout_report(registry_path, check_remote=check_remote, project_id_filter=project_id)
    results: list[dict[str, Any]] = []
    for item in report.get("projects") or []:
        if not isinstance(item, dict):
            continue
        project_result: dict[str, Any] = {
            "project_id": item.get("project_id"),
            "workspace_root": item.get("workspace_root"),
            "apply": bool(apply),
            "state": "planned",
            "blockers": item.get("blockers") or [],
            "actions": [],
        }
        if item.get("blockers"):
            project_result["state"] = "blocked"
            results.append(project_result)
            continue
        git_store = Path(str(item.get("git_store") or "")).expanduser()
        action_results: list[dict[str, Any]] = []
        for action in item.get("actions") or []:
            if not isinstance(action, dict):
                continue
            name = str(action.get("action") or "")
            if name in {"create_directory", "clone_bare_store", "reuse_bare_store"}:
                action_results.append(apply_layout_action(action, apply))
        if apply and not automation_worktree_planner.is_bare_git_store(git_store):
            project_result.update({"state": "failed", "error": "git_store_not_ready_after_prepare", "actions": action_results})
            results.append(project_result)
            continue
        for action in item.get("actions") or []:
            if isinstance(action, dict) and str(action.get("action") or "") == "create_worktree":
                action_results.append(create_layout_worktree(git_store, action, apply))
        for action in item.get("actions") or []:
            if isinstance(action, dict) and str(action.get("action") or "") == "write_workspace_manifest":
                action_results.append(write_workspace_manifest(item, apply))
        failed = [action for action in action_results if action.get("state") == "failed"]
        project_result["actions"] = action_results
        if failed:
            project_result["state"] = "failed"
        elif apply:
            project_result["state"] = "applied"
        results.append(project_result)
    failed_count = sum(1 for result in results if result.get("state") in {"blocked", "failed"})
    return {
        "schema_version": "1.0",
        "mode": "workspace_layout",
        "apply": bool(apply),
        "remote_checked": bool(report.get("remote_checked")),
        "registry": str(registry_path),
        "project_filter": project_id,
        "project_count": len(results),
        "failed_count": failed_count,
        "registry_warnings": report.get("registry_warnings") or [],
        "results": results,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--worktree-root", default="runtime/agent-control/automation-worktrees", type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--layout", action="store_true", help="Provision full Project Standard v2 workspace layout")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--no-remote-check", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.layout:
        payload = provision_layout(
            args.registry.expanduser(),
            apply=args.apply,
            check_remote=not args.no_remote_check,
            project_id=args.project_id,
        )
    else:
        payload = provision(
            args.registry.expanduser(),
            args.worktree_root.expanduser(),
            apply=args.apply,
            check_remote=not args.no_remote_check,
            project_id=args.project_id,
        )
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        for result in payload["results"]:
            action = result.get("action") or payload.get("mode") or "provision"
            print(f"{result['project_id']}: {action} -> {result.get('state')}")
    return 0 if payload["failed_count"] == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
