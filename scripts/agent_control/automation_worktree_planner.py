#!/usr/bin/env python3
"""Plan git automation worktrees for project registry entries.

The planner is read-only: it does not clone, edit registry files, or write
credentials. It identifies registry entries whose effective command root is not
a git worktree and emits the exact automation_path and clone command an operator
or approved provisioning step should use.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import project_registry

def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def slug(value: str) -> str:
    text = re.sub(r"[^A-Za-z0-9_.-]+", "-", value.strip()).strip("-")
    return text.lower() or "project"


def github_repo_url(repo: str) -> str:
    value = str(repo or "").strip()
    if not value:
        return ""
    if value.startswith(("http://", "https://", "git@")):
        return value
    return f"https://github.com/{value}.git"


def base_branch(project: dict[str, Any]) -> str:
    value = str(project.get("base_branch") or project.get("base_ref") or "develop").strip()
    return value.removeprefix("origin/") or "develop"


def project_id(project: dict[str, Any]) -> str:
    return str(project.get("project_id") or project.get("id") or project.get("name") or "").strip()


def project_command_root(project: dict[str, Any]) -> Path:
    return Path(str(project.get("automation_path") or project.get("local_path") or "")).expanduser()


def run_git(cwd: Path, args: list[str]) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(
            ["git", *args],
            cwd=str(cwd),
            text=True,
            capture_output=True,
            check=False,
            timeout=20,
        )
    except (OSError, subprocess.SubprocessError):
        return None


def is_git_worktree(path: Path) -> bool:
    if not path.exists():
        return False
    proc = run_git(path, ["rev-parse", "--is-inside-work-tree"])
    return bool(proc and proc.returncode == 0 and proc.stdout.strip().lower() == "true")


def run_git_dir(git_dir: Path, args: list[str]) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(
            ["git", f"--git-dir={git_dir}", *args],
            text=True,
            capture_output=True,
            check=False,
            timeout=20,
        )
    except (OSError, subprocess.SubprocessError):
        return None


def is_bare_git_store(path: Path) -> bool:
    if not path.exists():
        return False
    proc = run_git_dir(path, ["rev-parse", "--is-bare-repository"])
    return bool(proc and proc.returncode == 0 and proc.stdout.strip().lower() == "true")


def branch_exists_in_store(git_store: Path, branch: str) -> bool:
    if not branch:
        return False
    proc = run_git_dir(git_store, ["show-ref", "--verify", f"refs/heads/{branch}"])
    return bool(proc and proc.returncode == 0)


def git_branch(path: Path) -> str:
    proc = run_git(path, ["branch", "--show-current"])
    return proc.stdout.strip() if proc and proc.returncode == 0 else ""


def git_origin_url(path: Path) -> str:
    proc = run_git(path, ["remote", "get-url", "origin"])
    return proc.stdout.strip() if proc and proc.returncode == 0 else ""


def host_command_env() -> dict[str, str]:
    env = dict(os.environ)
    if not env.get("HOME"):
        env["HOME"] = str(Path.home())
    return env


def run_host_command(args: list[str], timeout: int = 10) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(args, text=True, capture_output=True, check=False, timeout=timeout, env=host_command_env())
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        return None


def credential_helper_configured() -> bool:
    proc = run_host_command(["git", "config", "--global", "--get", "credential.helper"])
    return bool(proc and proc.returncode == 0 and proc.stdout.strip())


def gh_auth_status() -> dict[str, Any]:
    gh_path = shutil.which("gh")
    if not gh_path:
        return {"present": False, "authenticated": False, "reason": "gh_missing"}
    proc = run_host_command(["gh", "--version"])
    if proc is None or proc.returncode != 0:
        return {"present": True, "authenticated": False, "reason": "gh_version_failed"}
    status = run_host_command(["gh", "auth", "status", "--hostname", "github.com"], timeout=15)
    if status and status.returncode == 0:
        return {"present": True, "authenticated": True, "reason": "gh_auth_ok"}
    detail = ((status.stderr if status else "") or (status.stdout if status else "") or "").strip()[-500:]
    return {"present": True, "authenticated": False, "reason": "gh_auth_missing", "detail": detail}


def host_auth_probe() -> dict[str, Any]:
    env_token_present = bool(os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN"))
    ssh_auth_sock_present = bool(os.environ.get("SSH_AUTH_SOCK"))
    gh = gh_auth_status()
    helper = credential_helper_configured()
    return {
        "env_token_present": env_token_present,
        "ssh_auth_sock_present": ssh_auth_sock_present,
        "git_credential_helper_configured": helper,
        "gh_cli": gh,
        "non_interactive_credentials_detected": bool(env_token_present or helper or gh.get("authenticated") or ssh_auth_sock_present),
    }


def credential_readiness(probe: dict[str, Any], plans: list[dict[str, Any]]) -> dict[str, Any]:
    credential_blocked_projects = [
        str(item.get("project_id") or "")
        for item in plans
        if "github_credentials_missing" in (item.get("blockers") or [])
    ]
    allowed_mechanisms = [
        "GITHUB_TOKEN or GH_TOKEN environment variable supplied by local secret provider",
        "Git credential helper configured on the automation host",
        "gh CLI authenticated non-interactively for github.com",
        "SSH agent with deploy key or machine user key allowed by policy",
    ]
    next_actions: list[str] = []
    if credential_blocked_projects and not probe.get("non_interactive_credentials_detected"):
        next_actions.extend([
            "Install one approved non-interactive GitHub credential mechanism on the automation host.",
            "Re-run /api/automation-worktree-plan.json and confirm credential_readiness.ok is true.",
            "Only then clone proposed automation worktrees and set registry automation_path values.",
        ])
    elif credential_blocked_projects:
        next_actions.append("Credentials are detected but git ls-remote still fails; verify repo permissions and branch access.")
    return {
        "ok": not credential_blocked_projects,
        "reason": "github_credentials_missing" if credential_blocked_projects else "credentials_not_required_or_accessible",
        "credential_blocked_projects": credential_blocked_projects,
        "allowed_mechanisms": allowed_mechanisms,
        "next_actions": next_actions,
        "secret_values_reported": False,
    }


def remote_access(repo: str, branch: str) -> dict[str, Any]:
    url = github_repo_url(repo)
    if not url:
        return {"checked": False, "ok": None, "reason": "github_repo_missing"}
    env = host_command_env()
    env["GIT_TERMINAL_PROMPT"] = "0"
    try:
        proc = subprocess.run(
            ["git", "ls-remote", "--heads", url, branch],
            text=True,
            capture_output=True,
            check=False,
            timeout=20,
            env=env,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired) as exc:
        return {"checked": True, "ok": False, "repo": repo, "branch": branch, "reason": "git_remote_check_failed", "detail": str(exc)}
    detail = (proc.stderr or proc.stdout or "").strip()[-1000:]
    if proc.returncode == 0 and proc.stdout.strip():
        return {"checked": True, "ok": True, "repo": repo, "branch": branch, "reason": "remote_branch_accessible"}
    if proc.returncode == 0:
        return {"checked": True, "ok": False, "repo": repo, "branch": branch, "reason": "remote_branch_missing_or_empty", "detail": detail}
    classified = classify_remote_access_failure(detail)
    return {"checked": True, "ok": False, "repo": repo, "branch": branch, "detail": detail, **classified}


def classify_remote_access_failure(detail: str) -> dict[str, Any]:
    text = str(detail or "").lower()
    if any(token in text for token in (
        "could not read username",
        "authentication failed",
        "permission denied (publickey)",
        "terminal prompts disabled",
    )):
        return {
            "reason": "github_credentials_missing",
            "credential_required": True,
            "recommendation": "Install non-interactive GitHub credentials for this host before cloning automation worktrees.",
        }
    if any(token in text for token in ("could not resolve host", "failed to connect", "connection timed out", "network is unreachable")):
        return {
            "reason": "network_unreachable",
            "credential_required": False,
            "recommendation": "Restore outbound network access to GitHub before cloning automation worktrees.",
        }
    if any(token in text for token in ("repository not found", "not found")):
        return {
            "reason": "github_repo_inaccessible",
            "credential_required": True,
            "recommendation": "Verify the repository name and credentials for this host.",
        }
    return {
        "reason": "remote_access_failed",
        "credential_required": None,
        "recommendation": "Inspect git ls-remote detail and fix host access before cloning automation worktrees.",
    }


def plan_project(project: dict[str, Any], worktree_root: Path, check_remote: bool) -> dict[str, Any]:
    pid = project_id(project)
    command_root = project_command_root(project)
    automation_path = str(project.get("automation_path") or "").strip()
    repo = str(project.get("github_repo") or "").strip()
    branch = base_branch(project)
    command_root_is_git = is_git_worktree(command_root)
    proposed_path = worktree_root / slug(pid)
    proposed_is_git = is_git_worktree(proposed_path)
    access = remote_access(repo, branch) if check_remote else {"checked": False, "ok": None, "reason": "not_checked"}
    action = "ready"
    blockers: list[str] = []
    if command_root_is_git:
        action = "none"
    elif proposed_is_git:
        action = "set_automation_path"
    else:
        action = "clone_and_set_automation_path"
        if not repo:
            blockers.append("github_repo_missing")
        if access.get("ok") is False:
            blockers.append(str(access.get("reason") or "remote_access_failed"))
    return {
        "project_id": pid,
        "name": project.get("name") or pid,
        "local_path": project.get("local_path"),
        "automation_path": automation_path or None,
        "command_root": str(command_root),
        "command_root_is_git_worktree": command_root_is_git,
        "github_repo": repo or None,
        "base_branch": branch,
        "proposed_automation_path": str(proposed_path),
        "proposed_path_exists": proposed_path.exists(),
        "proposed_path_is_git_worktree": proposed_is_git,
        "remote_access": access,
        "action": action,
        "blockers": blockers,
        "clone_command": ["git", "clone", "--branch", branch, github_repo_url(repo), str(proposed_path)] if repo else [],
        "registry_patch": {"project_id": pid, "automation_path": str(proposed_path)} if action in {"set_automation_path", "clone_and_set_automation_path"} else {},
    }


def checkout_path(project: dict[str, Any], role: str) -> str:
    checkouts = project.get("checkouts") if isinstance(project.get("checkouts"), dict) else {}
    return str(checkouts.get(role) or "").strip()


def branch_for_role(project: dict[str, Any], role: str) -> str:
    branches = project.get("branches") if isinstance(project.get("branches"), dict) else {}
    if role == "develop":
        return str(branches.get("develop") or project.get("base_branch") or "develop").strip().removeprefix("origin/") or "develop"
    return str(branches.get(role) or role).strip().removeprefix("origin/") or role


def workspace_manifest(project: dict[str, Any]) -> dict[str, Any]:
    workspace_root = str(project.get("workspace_root") or "").strip()
    git_store = str(project.get("git_store_path") or "").strip()
    temporary = [
        str(Path(workspace_root) / "temp" / "builds"),
        str(Path(workspace_root) / "temp" / "worktrees"),
        str(Path(workspace_root) / "temp" / "imports"),
        str(Path(workspace_root) / "temp" / "exports"),
        str(Path(workspace_root) / "temp" / "scratch"),
        str(Path(workspace_root) / "temp" / "rebuild"),
    ] if workspace_root else []
    return {
        "schema_version": 2,
        "project_id": project_id(project),
        "workspace_root": workspace_root,
        "git_store": git_store or (str(Path(workspace_root) / ".git-store") if workspace_root else ""),
        "checkouts": {
            "develop": checkout_path(project, "develop"),
            "codex": checkout_path(project, "codex"),
            "release": checkout_path(project, "release"),
        },
        "temporary": temporary,
        "runtime": str(Path(workspace_root) / "runtime") if workspace_root else "",
        "archive": str(Path(workspace_root) / "archive") if workspace_root else "",
        "backups": str(Path(workspace_root) / "backups") if workspace_root else "",
        "archive_before_delete": True,
        "automatic_delete_enabled": False,
    }


def expected_workspace_names(manifest: dict[str, Any]) -> set[str]:
    workspace_root = Path(str(manifest["workspace_root"]))
    names = {"PROJECT_WORKSPACE.json", ".git-store", "temp", "runtime", "archive", "backups"}
    for value in (manifest.get("checkouts") or {}).values():
        try:
            names.add(Path(str(value)).resolve(strict=False).relative_to(workspace_root.resolve(strict=False)).parts[0])
        except (ValueError, IndexError):
            pass
    return names


def unsafe_workspace_entries(workspace_root: Path, manifest: dict[str, Any]) -> list[str]:
    if not workspace_root.exists():
        return []
    expected = expected_workspace_names(manifest)
    return sorted(item.name for item in workspace_root.iterdir() if item.name not in expected)


def plan_workspace_layout(project: dict[str, Any], *, check_remote: bool = True) -> dict[str, Any]:
    pid = project_id(project)
    manifest = workspace_manifest(project)
    workspace_root_text = str(manifest["workspace_root"] or "").strip()
    git_store_text = str(manifest["git_store"] or "").strip()
    workspace_root = Path(workspace_root_text).expanduser() if workspace_root_text else None
    git_store = Path(git_store_text).expanduser() if git_store_text else None
    repo = str(project.get("github_repo") or "").strip()
    blockers: list[str] = []
    actions: list[dict[str, Any]] = []
    warnings: list[str] = []
    if workspace_root is None:
        blockers.append("workspace_root_missing")
    if not repo:
        blockers.append("github_repo_missing")
    for key, value in (manifest.get("checkouts") or {}).items():
        if not str(value or "").strip():
            blockers.append(f"{key}_checkout_missing_path")
        elif workspace_root is not None and not project_registry.path_is_under(str(value), str(workspace_root)):
            blockers.append(f"{key}_checkout_escapes_workspace")
    if git_store is not None and workspace_root is not None and not project_registry.path_is_under(str(git_store), str(workspace_root)):
        blockers.append("git_store_escapes_workspace")

    if workspace_root is not None and not blockers:
        unsafe_entries = unsafe_workspace_entries(workspace_root, manifest)
        if unsafe_entries:
            blockers.append("workspace_root_non_empty_unsafe")
            warnings.append(f"unsafe workspace entries: {', '.join(unsafe_entries)}")
    if workspace_root is not None and not workspace_root.exists():
        actions.append({"action": "create_directory", "path": str(workspace_root)})
    if workspace_root is not None:
        for value in [*manifest["temporary"], manifest["runtime"], manifest["archive"], manifest["backups"]]:
            path = Path(str(value)).expanduser()
            if not path.exists():
                actions.append({"action": "create_directory", "path": str(path)})

    store_is_bare = is_bare_git_store(git_store) if git_store is not None and workspace_root is not None else False
    if workspace_root is None:
        blockers.append("git_store_requires_workspace_root")
    elif git_store is None:
        blockers.append("git_store_missing_path")
    elif git_store.exists() and not store_is_bare:
        blockers.append("git_store_exists_but_not_bare")
    elif not git_store.exists():
        access = remote_access(repo, branch_for_role(project, "develop")) if check_remote else {"checked": False, "ok": None, "reason": "not_checked"}
        actions.append({"action": "clone_bare_store", "path": str(git_store), "remote": github_repo_url(repo), "remote_access": access})
        if check_remote and access.get("ok") is False:
            blockers.append(str(access.get("reason") or "remote_access_failed"))
    else:
        actions.append({"action": "reuse_bare_store", "path": str(git_store)})

    checkout_reports: list[dict[str, Any]] = []
    for role in ("develop", "codex", "release"):
        raw_checkout = checkout_path(project, role)
        branch = branch_for_role(project, role)
        if not raw_checkout:
            checkout_reports.append({"role": role, "path": "", "branch": branch, "exists": False, "is_git_worktree": False})
            continue
        checkout = Path(raw_checkout).expanduser()
        item: dict[str, Any] = {"role": role, "path": str(checkout), "branch": branch, "exists": checkout.exists(), "is_git_worktree": is_git_worktree(checkout) if checkout.exists() else False}
        if checkout.exists() and not item["is_git_worktree"]:
            try:
                non_empty = any(checkout.iterdir())
            except OSError:
                non_empty = True
            if non_empty:
                blockers.append(f"{role}_checkout_exists_non_git")
            else:
                actions.append({"action": "create_worktree", "role": role, "path": str(checkout), "branch": branch})
        elif item["is_git_worktree"]:
            current_branch = git_branch(checkout)
            item["current_branch"] = current_branch
            item["origin_url"] = git_origin_url(checkout)
            if current_branch and current_branch != branch:
                blockers.append(f"{role}_checkout_branch_mismatch")
        else:
            actions.append({"action": "create_worktree", "role": role, "path": str(checkout), "branch": branch})
            if store_is_bare and not branch_exists_in_store(git_store, branch):
                blockers.append(f"{role}_branch_missing_in_store")
        checkout_reports.append(item)

    manifest_path = workspace_root / "PROJECT_WORKSPACE.json" if workspace_root is not None else Path("PROJECT_WORKSPACE.json")
    if workspace_root is not None:
        actions.append({"action": "write_workspace_manifest", "path": str(manifest_path)})
    return {
        "project_id": pid,
        "workspace_root": str(workspace_root) if workspace_root is not None else "",
        "git_store": str(git_store) if git_store is not None else "",
        "github_repo": repo or None,
        "manifest_path": str(manifest_path),
        "manifest": manifest,
        "checkouts": checkout_reports,
        "actions": actions,
        "blockers": sorted(set(blockers)),
        "warnings": warnings,
        "ready": not blockers,
    }


def build_layout_report(registry_path: Path, *, check_remote: bool = True, project_id_filter: str | None = None) -> dict[str, Any]:
    projects, registry_warnings = project_registry.load_projects(registry_path)
    items = [
        plan_workspace_layout(project, check_remote=check_remote)
        for project in projects
        if not project_id_filter or project_id(project) == project_id_filter
    ]
    return {
        "schema_version": "1.0",
        "mode": "workspace_layout",
        "registry": str(registry_path),
        "remote_checked": bool(check_remote),
        "registry_warnings": registry_warnings,
        "project_count": len(items),
        "ready_count": sum(1 for item in items if item["ready"]),
        "blocked_count": sum(1 for item in items if item["blockers"]),
        "projects": items,
    }


def build_report(registry_path: Path, worktree_root: Path, check_remote: bool = True) -> dict[str, Any]:
    projects, registry_warnings = project_registry.load_projects(registry_path)
    plans = [plan_project(project, worktree_root, check_remote=check_remote) for project in projects]
    auth_probe = host_auth_probe() if check_remote else {"checked": False}
    readiness = credential_readiness(auth_probe, plans) if check_remote else {"ok": None, "reason": "remote_not_checked", "secret_values_reported": False}
    return {
        "schema_version": "1.0",
        "registry": str(registry_path),
        "worktree_root": str(worktree_root),
        "remote_checked": bool(check_remote),
        "host_auth_probe": auth_probe,
        "credential_readiness": readiness,
        "registry_warnings": registry_warnings,
        "project_count": len(plans),
        "ready_count": sum(1 for item in plans if item["action"] == "none"),
        "needs_action_count": sum(1 for item in plans if item["action"] != "none"),
        "blocked_count": sum(1 for item in plans if item["blockers"]),
        "projects": plans,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--worktree-root", default="runtime/agent-control/automation-worktrees", type=Path)
    parser.add_argument("--no-remote-check", action="store_true")
    parser.add_argument("--layout", action="store_true", help="Plan full Project Standard v2 workspace layout")
    parser.add_argument("--project-id")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.layout:
        report = build_layout_report(args.registry.expanduser(), check_remote=not args.no_remote_check, project_id_filter=args.project_id)
    else:
        report = build_report(args.registry.expanduser(), args.worktree_root.expanduser(), check_remote=not args.no_remote_check)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        for item in report["projects"]:
            if args.layout:
                print(f"{item['project_id']}: workspace_layout -> {item.get('workspace_root')}")
            else:
                print(f"{item['project_id']}: {item['action']} -> {item.get('proposed_automation_path')}")
            for blocker in item["blockers"]:
                print(f"  blocker: {blocker}")
    return 0 if not report["blocked_count"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
