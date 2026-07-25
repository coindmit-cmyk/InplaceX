#!/usr/bin/env python3
"""Run a disposable Project Standard v2 workspace pilot."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import project_doctor
import project_rebuilder
import quarantine_policy
import workspace_cleanup
import workspace_migration_task_builder


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run(command: list[str], cwd: Path) -> None:
    proc = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed\nstdout={proc.stdout}\nstderr={proc.stderr}")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_project_files(root: Path, project_id: str, branch_role: str) -> None:
    (root / "README.md").write_text(f"# {project_id} {branch_role}\n", encoding="utf-8")
    (root / "PROJECT_INDEX.md").write_text(f"# {project_id}\n\n- README.md\n", encoding="utf-8")
    write_json(root / "DOCUMENTATION_MANIFEST.json", {
        "schema_version": 1,
        "documents": [
            {"path": "README.md", "status": "current"},
            {"path": "PROJECT_INDEX.md", "status": "current"},
        ],
    })
    write_json(root / "PROJECT_VERSION.json", {
        "schema_version": 2,
        "project_id": project_id,
        "branch_role": branch_role,
        "product_version": "0.0.0-pilot",
        "state_revision": 1,
        "documentation_revision": 1,
        "content_base_commit": "0" * 40,
        "recorded_by_commit": "0" * 40,
        "source_of_truth": "github",
        "updated_at": utc_now(),
        "updated_by": "workspace_pilot_runner",
        "project_index": "PROJECT_INDEX.md",
        "documentation_manifest": "DOCUMENTATION_MANIFEST.json",
        "task_manager": "AiStudio/Task_manager/task_queue.json",
    })
    task_manager = root / "AiStudio" / "Task_manager"
    write_json(task_manager / "task_queue.json", {"tasks": []})
    write_json(task_manager / "agent_locks.json", {})
    write_json(task_manager / "owner_directives.json", {"directives": []})


def init_checkout(path: Path, project_id: str, branch: str, branch_role: str) -> None:
    path.mkdir(parents=True, exist_ok=True)
    run(["git", "init"], path)
    run(["git", "config", "user.email", "pilot@example.invalid"], path)
    run(["git", "config", "user.name", "Pilot Runner"], path)
    write_project_files(path, project_id, branch_role)
    run(["git", "add", "."], path)
    run(["git", "commit", "-m", f"pilot {branch_role}"], path)
    run(["git", "branch", "-M", branch], path)


def build_fixture(base_dir: Path) -> tuple[Path, Path]:
    project_id = "pilot-agent"
    workspace = base_dir / project_id
    init_checkout(workspace / "develop", project_id, "develop", "develop")
    init_checkout(workspace / "codex", project_id, "codex", "codex")
    init_checkout(workspace / "release", project_id, "release", "release")
    for rel in ("temp/builds", "temp/worktrees", "temp/imports", "temp/exports", "temp/scratch", "temp/rebuild", "runtime", "archive", "backups"):
        (workspace / rel).mkdir(parents=True, exist_ok=True)
    write_json(workspace / "PROJECT_WORKSPACE.json", {
        "schema_version": 2,
        "project_id": project_id,
        "workspace_root": str(workspace),
        "git_store": str(workspace / ".git-store"),
        "checkouts": {
            "develop": str(workspace / "develop"),
            "codex": str(workspace / "codex"),
            "release": str(workspace / "release"),
        },
        "temporary": [
            str(workspace / "temp" / "builds"),
            str(workspace / "temp" / "worktrees"),
            str(workspace / "temp" / "imports"),
            str(workspace / "temp" / "exports"),
            str(workspace / "temp" / "scratch"),
            str(workspace / "temp" / "rebuild"),
        ],
        "runtime": str(workspace / "runtime"),
        "archive": str(workspace / "archive"),
        "backups": str(workspace / "backups"),
        "archive_before_delete": True,
        "automatic_delete_enabled": False,
    })
    registry = base_dir / "projects.json"
    write_json(registry, {
        "schema_version": "2.0",
        "projects": [
            {
                "project_id": project_id,
                "name": "Pilot Agent",
                "enabled": True,
                "workspace_root": str(workspace),
                "git_store_path": str(workspace / ".git-store"),
                "checkouts": {
                    "develop": "develop",
                    "codex": "codex",
                    "release": "release",
                },
                "branches": {
                    "develop": "develop",
                    "codex": "codex",
                    "release": "release",
                },
                "github_repo": "example/pilot-agent",
                "base_branch": "develop",
                "code_base_ref": "HEAD",
                "state_ref": "HEAD",
                "push_ref": "codex",
                "version_file": "PROJECT_VERSION.json",
                "project_index": "PROJECT_INDEX.md",
                "documentation_manifest": "DOCUMENTATION_MANIFEST.json",
                "task_manager_branch_role": "codex",
                "health_threshold": 85,
                "quarantine_mode": "advisory",
            }
        ],
    })
    return registry, workspace


def run_pilot(base_dir: Path | None = None, *, keep: bool = False) -> dict[str, Any]:
    temp_dir: tempfile.TemporaryDirectory[str] | None = None
    if base_dir is None:
        temp_dir = tempfile.TemporaryDirectory(prefix="aistd2-pilot-")
        base = Path(temp_dir.name)
    else:
        base = base_dir
        base.mkdir(parents=True, exist_ok=True)
    try:
        registry, workspace = build_fixture(base)
        doctor = project_doctor.build_report(registry, devops_root=base)
        rebuild = project_rebuilder.build_plan(registry, level=0)
        cleanup = workspace_cleanup.plan_cleanup(registry)
        tasks = workspace_migration_task_builder.build_report(registry, devops_root=base)
        project = doctor["projects"][0]
        quarantine = quarantine_policy.evaluate_policy(
            health_score=int(project.get("health_score") or 0),
            health_threshold=int(project.get("health_threshold") or 85),
            deductions=project.get("deductions") if isinstance(project.get("deductions"), list) else [],
        )
        checks = {
            "doctor_healthy": project.get("status") == "healthy",
            "rebuild_not_blocked": int(rebuild.get("blocked_count") or 0) == 0,
            "cleanup_no_moves": int(cleanup.get("move_count") or 0) == 0,
            "no_task_seeds": int(tasks.get("task_count") or 0) == 0,
            "quarantine_not_blocking": quarantine.get("blocks_project") is False,
        }
        ok = all(checks.values())
        return {
            "schema_version": "1.0",
            "mode": "workspace_pilot",
            "ok": ok,
            "base_dir": str(base),
            "workspace": str(workspace),
            "registry": str(registry),
            "keep": keep,
            "checks": checks,
            "doctor_summary": {
                "project_count": doctor.get("project_count"),
                "attention_count": doctor.get("attention_count"),
                "average_health_score": doctor.get("average_health_score"),
                "health_score": project.get("health_score"),
            },
            "rebuild_summary": {
                "project_count": rebuild.get("project_count"),
                "blocked_count": rebuild.get("blocked_count"),
                "plan_hash": rebuild.get("plan_hash"),
            },
            "cleanup_summary": {
                "project_count": cleanup.get("project_count"),
                "move_count": cleanup.get("move_count"),
                "archive_only": cleanup.get("archive_only"),
            },
            "task_seed_summary": {
                "task_count": tasks.get("task_count"),
                "mutates_project_queues": tasks.get("mutates_project_queues"),
            },
            "quarantine": quarantine,
        }
    finally:
        if temp_dir is not None and keep:
            temp_dir.cleanup = lambda: None  # type: ignore[method-assign]
        if temp_dir is not None and not keep:
            temp_dir.cleanup()
        elif base_dir is not None and not keep:
            shutil.rmtree(base_dir, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-dir", type=Path)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = run_pilot(args.base_dir.expanduser() if args.base_dir else None, keep=args.keep)
    if args.output:
        write_json(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("ok" if report["ok"] else "failed")
    return 0 if report["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
