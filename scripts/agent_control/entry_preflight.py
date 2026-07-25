#!/usr/bin/env python3
"""Mandatory entry preflight for write-capable AiStudio automation."""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
from typing import Any

import github_freshness_guard
import project_version_gate
import start_here_gate


DEFAULT_CHECKLIST = {
    "schema_version": 1,
    "steps": [
        {"id": "git_freshness", "required": True, "recovery_owner": "dispatcher"},
        {"id": "start_here", "required": True, "recovery_owner": "architect"},
        {"id": "project_version", "required": False, "recovery_owner": "architect"},
    ],
}


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def load_checklist(project_root: Path, checklist_path: str | None = None) -> dict[str, Any]:
    path = Path(checklist_path).expanduser() if checklist_path else project_root / ".agent" / "entry_checklist.json"
    if path.is_file():
        return load_json(path)
    return DEFAULT_CHECKLIST


def step_required(checklist: dict[str, Any], step_id: str, default: bool) -> bool:
    for step in checklist.get("steps") or []:
        if isinstance(step, dict) and step.get("id") == step_id:
            return bool(step.get("required", default))
    return default


def utc_timestamp(value: dt.datetime | None = None) -> str:
    current = value or dt.datetime.now(dt.timezone.utc)
    return current.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_optional_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    try:
        return load_json(path)
    except (OSError, json.JSONDecodeError, ValueError):
        return {}


def session_evidence(
    project_root: Path,
    *,
    freshness: dict[str, Any],
    version: dict[str, Any],
    version_file: str,
    agent_version_path: str,
    authority: dict[str, Any] | None,
    mode: str,
    ttl_seconds: int,
    issued_at: dt.datetime | None = None,
) -> dict[str, Any]:
    """Build a portable, short-lived record without writing runtime state."""
    if ttl_seconds <= 0:
        raise ValueError("session_ttl_seconds must be positive")
    issued = issued_at or dt.datetime.now(dt.timezone.utc)
    expires = issued + dt.timedelta(seconds=ttl_seconds)
    project_version = version.get("version") if isinstance(version.get("version"), dict) else {}
    agent_version = load_optional_json(project_root / agent_version_path)
    authority = authority or {}
    return {
        "schema_version": "1.0",
        "issued_at": utc_timestamp(issued),
        "expires_at": utc_timestamp(expires),
        "repository": {
            "project_root": str(project_root),
            "branch": freshness.get("current_branch"),
            "local_ref": str(freshness.get("local_ref") or "HEAD"),
            "local_sha": freshness.get("local_sha"),
            "base_ref": str(freshness.get("base_ref") or "origin/develop"),
            "base_sha": freshness.get("base_sha"),
            "ahead_commits": freshness.get("local_only_commits"),
            "behind_commits": freshness.get("base_only_commits"),
        },
        "version": {
            "project_version_path": version_file,
            "project_version": project_version.get("product_version"),
            "branch_role": project_version.get("branch_role"),
            "agent_core_version": agent_version.get("agent_version"),
        },
        "authority": {
            "checked": bool(authority),
            "mode": mode,
            "host_id": authority.get("host_id"),
            "host_role": authority.get("host_role") or "unknown",
            "canonical_writer_host": authority.get("canonical_writer_host") or None,
            "apply_allowed": bool(authority.get("apply_allowed", False)),
        },
    }


def run_entry_preflight(
    project_root: Path,
    *,
    base_ref: str = "origin/develop",
    local_ref: str = "HEAD",
    remote: str = "origin",
    fetch: bool = True,
    auto_ff: bool = True,
    start_here_path: str = ".agent/START_HERE.md",
    version_file: str = "PROJECT_VERSION.json",
    branch_role: str | None = None,
    project_id: str | None = None,
    github_repo: str | None = None,
    release_ref: str | None = None,
    checklist_path: str | None = None,
    registry_path: str | None = None,
    host_id: str | None = None,
    mode: str = "dry_run",
    agent_version_path: str = ".agent/agent_version.json",
    session_ttl_seconds: int = 3600,
    issued_at: dt.datetime | None = None,
) -> dict[str, Any]:
    if mode not in {"dry_run", "apply"}:
        raise ValueError("mode must be 'dry_run' or 'apply'")
    root = project_root.expanduser()
    checklist = load_checklist(root, checklist_path)
    freshness = github_freshness_guard.check_freshness(root, base_ref=base_ref, local_ref=local_ref, remote=remote, fetch=fetch, auto_ff=auto_ff)
    start_here_required = step_required(checklist, "start_here", True)
    start_here = start_here_gate.validate_start_here(
        root,
        start_here_path=start_here_path,
        version_file=version_file,
        expected_project_id=project_id,
        expected_repo=github_repo,
        expected_base_ref=base_ref,
        expected_release_ref=release_ref,
        require=start_here_required,
    )
    version_required = step_required(checklist, "project_version", False)
    version = project_version_gate.validate_version(root, version_file=version_file, expected_branch_role=branch_role, require=version_required)
    errors: list[dict[str, Any]] = []
    if not freshness.get("ok"):
        errors.append({"step": "git_freshness", "owner": "dispatcher", "report": freshness})
    if not start_here.get("ok"):
        errors.append({"step": "start_here", "owner": "architect", "status": "orientation_blocked", "report": start_here})
    if not version.get("ok"):
        errors.append({"step": "project_version", "owner": "architect", "report": version})
    authority: dict[str, Any] | None = None
    if registry_path or host_id or mode == "apply":
        if not registry_path or not host_id:
            authority = {
                "ok": False,
                "host_id": host_id,
                "host_role": "unknown",
                "canonical_writer_host": "",
                "apply_allowed": False,
                "errors": [{"code": "authority_inputs_required", "message": "registry_path and host_id are required for authority preflight"}],
            }
        else:
            import automation_authority_guard

            authority = automation_authority_guard.evaluate_registry_authority(
                Path(registry_path).expanduser(), host_id=host_id, mode=mode
            )
        if not authority.get("ok"):
            errors.append({"step": "automation_authority", "owner": "dispatcher", "report": authority})
    evidence = session_evidence(
        root,
        freshness=freshness,
        version=version,
        version_file=version_file,
        agent_version_path=agent_version_path,
        authority=authority,
        mode=mode,
        ttl_seconds=session_ttl_seconds,
        issued_at=issued_at,
    )
    return {
        "schema_version": "1.0",
        "ok": not errors,
        "project_root": str(root),
        "base_ref": base_ref,
        "local_ref": local_ref,
        "checklist": checklist,
        "freshness": freshness,
        "start_here": start_here,
        "project_version": version,
        "automation_authority": authority,
        "session_evidence": evidence,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--local-ref", default="HEAD")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--no-fetch", action="store_true")
    parser.add_argument("--no-auto-ff", action="store_true")
    parser.add_argument("--start-here-path", default=".agent/START_HERE.md")
    parser.add_argument("--version-file", default="PROJECT_VERSION.json")
    parser.add_argument("--branch-role")
    parser.add_argument("--project-id")
    parser.add_argument("--github-repo")
    parser.add_argument("--release-ref")
    parser.add_argument("--checklist")
    parser.add_argument("--registry")
    parser.add_argument("--host-id")
    parser.add_argument("--mode", choices=["dry_run", "apply"], default="dry_run")
    parser.add_argument("--agent-version-path", default=".agent/agent_version.json")
    parser.add_argument("--session-ttl-seconds", type=int, default=3600)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = run_entry_preflight(
        Path(args.project_root),
        base_ref=args.base_ref,
        local_ref=args.local_ref,
        remote=args.remote,
        fetch=not args.no_fetch,
        auto_ff=not args.no_auto_ff,
        start_here_path=args.start_here_path,
        version_file=args.version_file,
        branch_role=args.branch_role,
        project_id=args.project_id,
        github_repo=args.github_repo,
        release_ref=args.release_ref,
        checklist_path=args.checklist,
        registry_path=args.registry,
        host_id=args.host_id,
        mode=args.mode,
        agent_version_path=args.agent_version_path,
        session_ttl_seconds=args.session_ttl_seconds,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    elif report["ok"]:
        print("ok")
    else:
        print("; ".join(error["step"] for error in report["errors"]))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
