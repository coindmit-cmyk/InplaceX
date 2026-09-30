#!/usr/bin/env python3
"""Check whether remote automation can continue without the local laptop."""

from __future__ import annotations

import argparse
import json
import socket
import subprocess
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import automation_worktree_planner


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run_git(cwd: Path, args: list[str], timeout: int = 30) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(
            ["git", *args],
            cwd=str(cwd),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired):
        return None


def git_stdout(cwd: Path, args: list[str]) -> str:
    proc = run_git(cwd, args)
    if not proc or proc.returncode != 0:
        return ""
    return proc.stdout.strip()


def parse_porcelain(text: str) -> dict[str, Any]:
    tracked: list[str] = []
    untracked: list[str] = []
    for line in text.splitlines():
        if not line:
            continue
        path = line[3:] if len(line) > 3 else line
        if line.startswith("??"):
            untracked.append(path)
        else:
            tracked.append(path)
    return {
        "tracked_dirty_count": len(tracked),
        "untracked_count": len(untracked),
        "tracked_dirty_sample": tracked[:20],
        "untracked_sample": untracked[:20],
    }


def ahead_behind(path: Path) -> dict[str, int | None]:
    upstream = git_stdout(path, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"])
    if not upstream:
        return {"ahead": None, "behind": None}
    counts = git_stdout(path, ["rev-list", "--left-right", "--count", f"HEAD...{upstream}"]).split()
    if len(counts) != 2:
        return {"ahead": None, "behind": None}
    try:
        return {"ahead": int(counts[0]), "behind": int(counts[1])}
    except ValueError:
        return {"ahead": None, "behind": None}


def worktree_state(project: dict[str, Any], *, fetch: bool) -> dict[str, Any]:
    path = Path(str(project.get("command_root") or project.get("automation_path") or ""))
    item: dict[str, Any] = {
        "project_id": project.get("project_id"),
        "path": str(path),
        "exists": path.exists(),
        "is_git_worktree": False,
        "branch": "",
        "head": "",
        "ahead": None,
        "behind": None,
        "tracked_dirty_count": None,
        "untracked_count": None,
        "blockers": [],
        "warnings": [],
    }
    if not path.exists():
        item["blockers"].append("automation_path_missing")
        return item
    inside = git_stdout(path, ["rev-parse", "--is-inside-work-tree"])
    item["is_git_worktree"] = inside.lower() == "true"
    if not item["is_git_worktree"]:
        item["blockers"].append("automation_path_not_git_worktree")
        return item
    if fetch:
        fetch_proc = run_git(path, ["fetch", "origin", "--prune"], timeout=60)
        if not fetch_proc or fetch_proc.returncode != 0:
            item["warnings"].append("fetch_failed")
    item["branch"] = git_stdout(path, ["branch", "--show-current"])
    item["head"] = git_stdout(path, ["rev-parse", "--short", "HEAD"])
    item.update(ahead_behind(path))
    dirty = parse_porcelain(git_stdout(path, ["status", "--porcelain"]))
    item.update(dirty)
    if item["tracked_dirty_count"]:
        item["blockers"].append("tracked_dirty_worktree")
    if item["untracked_count"]:
        item["warnings"].append("untracked_files_present")
    if item["behind"]:
        item["warnings"].append("worktree_behind_upstream")
    if item["ahead"]:
        item["warnings"].append("worktree_ahead_upstream")
    return item


def url_check(url: str) -> dict[str, Any]:
    if not url:
        return {"checked": False, "ok": None, "url": ""}
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            return {"checked": True, "ok": 200 <= response.status < 400, "url": url, "status": response.status}
    except Exception as exc:
        return {"checked": True, "ok": False, "url": url, "error": str(exc)}


def service_check(names: list[str]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for name in names:
        try:
            proc = subprocess.run(
                ["systemctl", "is-active", name],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=10,
            )
        except (OSError, subprocess.SubprocessError, subprocess.TimeoutExpired) as exc:
            result.append({"name": name, "checked": False, "ok": None, "error": str(exc)})
            continue
        state = proc.stdout.strip()
        result.append({"name": name, "checked": True, "ok": state == "active", "state": state})
    return result


def build_report(
    registry: Path,
    worktree_root: Path,
    *,
    fetch: bool = False,
    dashboard_url: str = "",
    services: list[str] | None = None,
) -> dict[str, Any]:
    planner = automation_worktree_planner.build_report(registry, worktree_root, check_remote=True)
    worktrees = [worktree_state(project, fetch=fetch) for project in planner.get("projects") or [] if isinstance(project, dict)]
    dashboard = url_check(dashboard_url)
    service_states = service_check(services or [])
    blockers: list[str] = []
    warnings: list[str] = []
    if not planner.get("credential_readiness", {}).get("ok"):
        blockers.append("github_credentials_not_ready")
    if planner.get("blocked_count"):
        blockers.append("automation_worktree_planner_blocked")
    for item in worktrees:
        blockers.extend(f"{item.get('project_id')}:{blocker}" for blocker in item.get("blockers") or [])
        warnings.extend(f"{item.get('project_id')}:{warning}" for warning in item.get("warnings") or [])
    if dashboard.get("checked") and dashboard.get("ok") is False:
        warnings.append("dashboard_url_unavailable")
    for service in service_states:
        if service.get("checked") and service.get("ok") is False:
            warnings.append(f"service_inactive:{service.get('name')}")
    return {
        "schema_version": "1.0",
        "mode": "remote_continuity_check",
        "generated_at": utc_now(),
        "host": socket.gethostname(),
        "registry": str(registry),
        "worktree_root": str(worktree_root),
        "fetch": fetch,
        "ok": not blockers,
        "blockers": sorted(set(blockers)),
        "warnings": sorted(set(warnings)),
        "planner": {
            "project_count": planner.get("project_count"),
            "ready_count": planner.get("ready_count"),
            "blocked_count": planner.get("blocked_count"),
            "credential_readiness": planner.get("credential_readiness"),
        },
        "worktrees": worktrees,
        "dashboard": dashboard,
        "services": service_states,
        "secret_values_reported": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--worktree-root", required=True, type=Path)
    parser.add_argument("--fetch", action="store_true", help="Fetch origin before ahead/behind checks.")
    parser.add_argument("--dashboard-url", default="")
    parser.add_argument("--service", action="append", default=[])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(
        args.registry.expanduser(),
        args.worktree_root.expanduser(),
        fetch=args.fetch,
        dashboard_url=args.dashboard_url,
        services=args.service,
    )
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"ok={report['ok']} blockers={len(report['blockers'])} warnings={len(report['warnings'])}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
