#!/usr/bin/env python3
"""Dry-run preservation plan for dirty, unpushed and legacy workspace state."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any

import project_rebuilder
import workspace_cleanup


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def run_git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
        timeout=60,
    )


def run_git_bytes(repo: Path, args: list[str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, check=False, timeout=60)


def ref_component(value: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-")
    return normalized or "project"


def command(repo: str, args: list[str]) -> list[str]:
    return ["git", "-C", repo, *args]


def build_project_plan(rebuild_item: dict[str, Any], cleanup_item: dict[str, Any]) -> dict[str, Any]:
    project_id = str(rebuild_item.get("project_id") or cleanup_item.get("project_id") or "unknown")
    inventory = rebuild_item.get("inventory") if isinstance(rebuild_item.get("inventory"), dict) else {}
    repo = str(inventory.get("root") or "")
    dirty_entries = [str(item) for item in inventory.get("dirty_entries") or []]
    unpushed_commits = [str(item) for item in inventory.get("unpushed_commits") or []]
    moves = [item for item in cleanup_item.get("moves") or [] if isinstance(item, dict)]
    archive_root = str(cleanup_item.get("archive_root") or "")

    actions: list[dict[str, Any]] = [
        {
            "id": f"{project_id}.record-inventory",
            "kind": "record_inventory_hash",
            "required": True,
            "mutates_project": False,
            "evidence": {
                "head": inventory.get("head"),
                "content_manifest_hash": inventory.get("content_manifest_hash"),
                "tracked_product_file_count": inventory.get("tracked_product_file_count"),
            },
        }
    ]
    if dirty_entries:
        actions.append({
            "id": f"{project_id}.dirty-patch",
            "kind": "capture_dirty_patch",
            "required": True,
            "mutates_project": False,
            "reason": "dirty checkout must be reviewable before rebuild or migration",
            "dirty_entry_count": len(dirty_entries),
            "suggested_outputs": [
                f"{archive_root}/preservation/dirty-worktree.patch",
                f"{archive_root}/preservation/dirty-status.txt",
            ],
            "suggested_commands": [
                command(repo, ["status", "--porcelain"]),
                command(repo, ["diff", "--binary"]),
                command(repo, ["diff", "--cached", "--binary"]),
            ],
        })
    if unpushed_commits:
        actions.append({
            "id": f"{project_id}.rescue-ref",
            "kind": "create_rescue_ref_or_push",
            "required": True,
            "mutates_project": False,
            "reason": "unpushed commits must be reachable before rebuild or checkout replacement",
            "unpushed_commit_count": len(unpushed_commits),
            "suggested_ref": f"refs/heads/rescue/{project_id}/pre-migration",
            "suggested_commands": [
                command(repo, ["log", "--oneline", f"{inventory.get('base_ref') or 'origin/develop'}..HEAD"]),
                command(repo, ["branch", f"rescue/{project_id}/pre-migration", "HEAD"]),
            ],
        })
    if moves:
        actions.append({
            "id": f"{project_id}.legacy-workspace-archive",
            "kind": "archive_legacy_workspace_candidates",
            "required": True,
            "mutates_project": False,
            "reason": "legacy sibling workspaces require archive manifest before cleanup",
            "move_count": len(moves),
            "moves": moves,
            "suggested_command": [
                "python",
                "scripts/agent_control/workspace_cleanup.py",
                "--registry",
                "<registry>",
                "--project-id",
                project_id,
                "--json",
            ],
        })

    blockers = [str(item) for item in rebuild_item.get("blockers") or []]
    non_preservation_blockers = {"base_ref_unresolved_or_missing_source_ref"}
    preservation_blockers = [item for item in blockers if item not in non_preservation_blockers]
    preservation_required = bool(dirty_entries or unpushed_commits or moves or preservation_blockers)
    return {
        "project_id": project_id,
        "preservation_required": preservation_required,
        "ready_for_rebuild": not preservation_required,
        "dirty_entry_count": len(dirty_entries),
        "unpushed_commit_count": len(unpushed_commits),
        "legacy_workspace_move_count": len(moves),
        "archive_root": archive_root,
        "rebuilder_blockers": blockers,
        "actions": actions,
        "apply_supported": False,
    }


def build_report(registry_path: Path, *, project_id: str | None = None, devops_root: Path | None = None) -> dict[str, Any]:
    rebuild = project_rebuilder.build_plan(registry_path, project_id=project_id, level=0)
    cleanup = workspace_cleanup.plan_cleanup(registry_path, project_id=project_id, devops_root=devops_root)
    cleanup_by_project = {
        str(item.get("project_id")): item
        for item in cleanup.get("projects") or []
        if isinstance(item, dict)
    }
    projects = [
        build_project_plan(item, cleanup_by_project.get(str(item.get("project_id")), {}))
        for item in rebuild.get("projects") or []
        if isinstance(item, dict)
    ]
    report = {
        "schema_version": "1.0",
        "mode": "workspace_preservation_plan",
        "registry": str(registry_path),
        "devops_root": str(devops_root) if devops_root else None,
        "project_count": len(projects),
        "preservation_required_count": sum(1 for item in projects if item["preservation_required"]),
        "ready_for_rebuild_count": sum(1 for item in projects if item["ready_for_rebuild"]),
        "mutates_project_state": False,
        "apply_supported": False,
        "product_code_changes_allowed": False,
        "projects": projects,
    }
    report["plan_hash"] = project_rebuilder.stable_hash(report)
    return attach_preservation_evidence(report)


def next_artifact_dir(path: Path) -> Path:
    if not path.exists():
        return path
    for index in range(2, 100):
        candidate = path.with_name(f"{path.name}-attempt-{index}")
        if not candidate.exists():
            return candidate
    raise RuntimeError(f"no available artifact directory near {path}")


def ensure_new_dir(path: Path) -> None:
    path.mkdir(parents=True)


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def write_bytes(path: Path, value: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(value)


def capture_git_text(repo: Path, args: list[str]) -> dict[str, Any]:
    proc = run_git(repo, args)
    return {
        "command": ["git", *args],
        "returncode": proc.returncode,
        "stdout": proc.stdout,
        "stderr": proc.stderr,
    }


def capture_git_bytes(repo: Path, args: list[str]) -> dict[str, Any]:
    proc = run_git_bytes(repo, args)
    return {
        "command": ["git", *args],
        "returncode": proc.returncode,
        "stdout": proc.stdout,
        "stderr": proc.stderr.decode("utf-8", errors="replace"),
    }


def project_repo(project: dict[str, Any]) -> Path | None:
    for action in project.get("actions") or []:
        if isinstance(action, dict) and action.get("kind") == "capture_dirty_patch":
            commands = action.get("suggested_commands") if isinstance(action.get("suggested_commands"), list) else []
            if commands and isinstance(commands[0], list) and len(commands[0]) >= 3:
                return Path(str(commands[0][2])).expanduser()
    return None


def expected_artifact_files(project: dict[str, Any]) -> list[str]:
    names = ["preservation_project_plan.json"]
    if int(project.get("dirty_entry_count") or 0) > 0:
        names.extend(["dirty-status.txt", "dirty-worktree.patch", "dirty-index.patch"])
    if int(project.get("unpushed_commit_count") or 0) > 0:
        names.append("unpushed-commits.txt")
    if int(project.get("legacy_workspace_move_count") or 0) > 0:
        names.append("legacy-workspace-cleanup-plan.json")
    return names


def preservation_evidence(project: dict[str, Any], plan_hash: str) -> dict[str, Any]:
    project_id = str(project.get("project_id") or "unknown")
    archive_root = Path(str(project.get("archive_root") or "")).expanduser()
    root = archive_root / "preservation" / plan_hash[:12]
    expected = expected_artifact_files(project)
    dirs = sorted(root.glob(f"{ref_component(project_id)}*")) if root.exists() else []
    artifact_dirs: list[dict[str, Any]] = []
    for directory in dirs:
        if not directory.is_dir():
            continue
        present = sorted(path.name for path in directory.iterdir() if path.is_file())
        missing = [name for name in expected if name not in present]
        artifact_dirs.append({
            "path": str(directory),
            "present_files": present,
            "missing_files": missing,
            "complete": not missing,
        })
    repo = project_repo(project)
    rescue_ref = f"rescue/{ref_component(project_id)}/pre-migration-{plan_hash[:12]}"
    rescue_required = int(project.get("unpushed_commit_count") or 0) > 0
    rescue_exists = False
    if repo and repo.exists() and rescue_required:
        rescue_exists = run_git(repo, ["rev-parse", "--verify", f"refs/heads/{rescue_ref}"]).returncode == 0
    artifacts_complete = any(item["complete"] for item in artifact_dirs)
    captured = artifacts_complete and (rescue_exists or not rescue_required)
    return {
        "captured": captured,
        "artifact_root": str(root),
        "artifact_dirs": artifact_dirs,
        "expected_files": expected,
        "rescue_ref": rescue_ref if rescue_required else None,
        "rescue_ref_exists": rescue_exists if rescue_required else None,
    }


def attach_preservation_evidence(report: dict[str, Any]) -> dict[str, Any]:
    plan_hash = str(report.get("plan_hash") or "")
    for project in report.get("projects") or []:
        if isinstance(project, dict):
            project["preservation_evidence"] = preservation_evidence(project, plan_hash)
    return report


def apply_project_preservation(project: dict[str, Any], plan_hash: str) -> dict[str, Any]:
    project_id = str(project.get("project_id") or "unknown")
    archive_root = Path(str(project.get("archive_root") or "")).expanduser()
    repo = project_repo(project)
    base_artifact_dir = archive_root / "preservation" / plan_hash[:12] / ref_component(project_id)
    artifact_dir = next_artifact_dir(base_artifact_dir)
    ensure_new_dir(artifact_dir)
    result: dict[str, Any] = {
        "project_id": project_id,
        "artifact_dir": str(artifact_dir),
        "state": "created",
        "reason": None,
        "files": [],
        "rescue_ref": None,
    }

    plan_path = artifact_dir / "preservation_project_plan.json"
    write_json_atomic(plan_path, project)
    result["files"].append(str(plan_path))

    if repo and repo.exists():
        status = capture_git_text(repo, ["status", "--porcelain"])
        status_path = artifact_dir / "dirty-status.txt"
        write_text(status_path, status["stdout"])
        result["files"].append(str(status_path))

        diff = capture_git_bytes(repo, ["diff", "--binary"])
        diff_path = artifact_dir / "dirty-worktree.patch"
        write_bytes(diff_path, diff["stdout"])
        result["files"].append(str(diff_path))

        cached = capture_git_bytes(repo, ["diff", "--cached", "--binary"])
        cached_path = artifact_dir / "dirty-index.patch"
        write_bytes(cached_path, cached["stdout"])
        result["files"].append(str(cached_path))

        if int(project.get("unpushed_commit_count") or 0) > 0:
            rescue_ref = f"rescue/{ref_component(project_id)}/pre-migration-{plan_hash[:12]}"
            exists = run_git(repo, ["rev-parse", "--verify", f"refs/heads/{rescue_ref}"])
            if exists.returncode == 0:
                result.update({"state": "blocked", "reason": "rescue_ref_exists", "rescue_ref": rescue_ref})
                return result
            branch = run_git(repo, ["branch", rescue_ref, "HEAD"])
            if branch.returncode != 0:
                result.update({"state": "blocked", "reason": "rescue_ref_create_failed", "stderr": branch.stderr, "rescue_ref": rescue_ref})
                return result
            result["rescue_ref"] = rescue_ref
            commits = capture_git_text(repo, ["log", "--oneline", "--decorate", "-n", str(project.get("unpushed_commit_count") or 1)])
            commits_path = artifact_dir / "unpushed-commits.txt"
            write_text(commits_path, commits["stdout"])
            result["files"].append(str(commits_path))

    legacy_moves = []
    for action in project.get("actions") or []:
        if isinstance(action, dict) and action.get("kind") == "archive_legacy_workspace_candidates":
            legacy_moves = action.get("moves") if isinstance(action.get("moves"), list) else []
    if legacy_moves:
        cleanup_path = artifact_dir / "legacy-workspace-cleanup-plan.json"
        write_json_atomic(cleanup_path, {"moves": legacy_moves, "apply_performed": False})
        result["files"].append(str(cleanup_path))
    return result


def apply_preservation(report: dict[str, Any], *, approved_plan_hash: str | None, apply: bool = False) -> dict[str, Any]:
    plan_hash = str(report.get("plan_hash") or "")
    errors: list[dict[str, str]] = []
    if not approved_plan_hash:
        errors.append({"code": "approved_plan_hash_missing", "message": "apply requires --approved-plan-hash"})
    elif approved_plan_hash != plan_hash:
        errors.append({"code": "approved_plan_hash_mismatch", "message": "approved plan hash does not match current preservation plan"})
    if errors or not apply:
        return {
            "schema_version": "1.0",
            "mode": "workspace_preservation_apply",
            "apply": bool(apply),
            "ok": not errors,
            "plan_hash": plan_hash,
            "errors": errors,
            "projects": [],
        }
    projects = [
        apply_project_preservation(project, plan_hash)
        for project in report.get("projects") or []
        if isinstance(project, dict) and project.get("preservation_required")
    ]
    return {
        "schema_version": "1.0",
        "mode": "workspace_preservation_apply",
        "apply": True,
        "ok": all(item.get("state") == "created" for item in projects),
        "plan_hash": plan_hash,
        "errors": [],
        "projects": projects,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--devops-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--approved-plan-hash")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = build_report(
        args.registry.expanduser(),
        project_id=args.project_id,
        devops_root=args.devops_root.expanduser() if args.devops_root else None,
    )
    output = apply_preservation(report, approved_plan_hash=args.approved_plan_hash, apply=args.apply) if args.apply else report
    if args.output:
        write_json_atomic(args.output.expanduser(), output)
    if args.json:
        print(json.dumps(output, ensure_ascii=False, indent=2))
    else:
        print(f"preservation projects={report['project_count']} required={report['preservation_required_count']}")
    return 0 if output.get("ok", True) is not False else 2


if __name__ == "__main__":
    raise SystemExit(main())
