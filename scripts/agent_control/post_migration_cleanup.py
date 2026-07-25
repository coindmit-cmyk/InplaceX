#!/usr/bin/env python3
"""Archive transient project migration artifacts after AiStudio migration.

The script is intentionally conservative:
- dry-run by default;
- archive by default, delete only when explicitly requested;
- never touches canonical task state, durable project docs, product code or
  release branches;
- records a manifest in the archive and a short report in Task_manager.
"""

from __future__ import annotations

import argparse
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_manager_dir


LEGACY_MACHINE_GLOBS = (
    "docs/plans/*.json",
    "docs/plans/*.jsonl",
    "docs/plans/process-logs/*.jsonl",
    "docs/plans/reports/*.json",
    "docs/plans/reports/INTEGRATION_BATCH_*.md",
    "docs/plans/reports/INTEGRATION_CANDIDATE_FILTER_*.md",
    "docs/plans/reports/PRE_INTEGRATOR_REPAIR_*.md",
    "docs/plans/reports/PR_READINESS_*.md",
    "docs/plans/reports/TASK_IDENTITY_AUDIT_*.md",
    "docs/plans/reports/CLEAN_REBUILD_PLAN_*.md",
    "docs/plans/reports/CANDIDATE_SALVAGE_AUDIT_*.md",
    "docs/plans/reports/ALLOWED_PATHS_REPAIR_PLAN_*.md",
    "docs/plans/reports/workers/WORKER_RESULT_*.md",
)

ACTIVE_RUNTIME_GLOBS = (
    "agent-runtime/**",
    "agent-worktrees/**",
    "AiStudio/Agent/worker-worktrees/**",
    "AiStudio/Agent/integrator-worktrees/**",
    "AiStudio/Agent/finalizer-worktrees/**",
    "AiStudio/Agent/cleanup-worktrees/**",
    "AiStudio/Task_manager/process-logs/*.jsonl",
)

TRANSIENT_TASK_MANAGER_FILES = (
    "AiStudio/Task_manager/integration_handoff.json",
    "AiStudio/Task_manager/integration_batch.json",
    "AiStudio/Task_manager/integration_batch.routed.json",
    "AiStudio/Task_manager/integration_candidates.batch.json",
    "AiStudio/Task_manager/integrator_preflight.json",
    "AiStudio/Task_manager/pr_readiness_report.json",
    "AiStudio/Task_manager/pr_readiness_report.identity_filtered.json",
    "AiStudio/Task_manager/pre_integrator_repair.json",
    "AiStudio/Task_manager/task_identity_audit.json",
    "AiStudio/Task_manager/allowed_paths_repair_plan.json",
    "AiStudio/Task_manager/candidate_salvage_audit.json",
    "AiStudio/Task_manager/clean_rebuild_plan.json",
    "AiStudio/Task_manager/rebuild_decision_report.json",
    "AiStudio/Task_manager/route_rebuild_and_integration_results.json",
    "AiStudio/Task_manager/loop_agent_orchestrator.json",
    "AiStudio/Task_manager/auto_finalizer_merge.json",
)

CANONICAL_KEEP = {
    "AiStudio/Task_manager/task_queue.json",
    "AiStudio/Task_manager/agent_locks.json",
    "AiStudio/Task_manager/agent_events.jsonl",
    "AiStudio/Task_manager/owner_directives.json",
    "AiStudio/Task_manager/agent_runner_state.json",
    "AiStudio/Task_manager/agent_activity_state.json",
    "AiStudio/Task_manager/agent_process_state.json",
    "AiStudio/Task_manager/model_budget_state.json",
    "AiStudio/Task_manager/process_locks.json",
    "AiStudio/Task_manager/worker_pool_last_plan.json",
    "AiStudio/Task_manager/tasks",
    ".agent",
}

KEEP_NAMES = {".gitkeep"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def rel_path(project_root: Path, path: Path) -> str:
    return path.relative_to(project_root).as_posix()


def is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def unique_existing(paths: list[Path]) -> list[Path]:
    seen: set[Path] = set()
    result: list[Path] = []
    for path in paths:
        if not path.exists() or path.name in KEEP_NAMES:
            continue
        resolved = path.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        result.append(path)
    return sorted(result, key=lambda item: item.as_posix())


def prune_nested(paths: list[Path]) -> list[Path]:
    result: list[Path] = []
    selected_dirs: list[Path] = []
    for path in sorted(paths, key=lambda item: (len(item.parts), item.as_posix())):
        resolved = path.resolve()
        if any(is_relative_to(resolved, directory) for directory in selected_dirs):
            continue
        result.append(path)
        if path.is_dir():
            selected_dirs.append(resolved)
    return sorted(result, key=lambda item: item.as_posix())


def expand_globs(project_root: Path, patterns: tuple[str, ...]) -> list[Path]:
    paths: list[Path] = []
    for pattern in patterns:
        paths.extend(project_root.glob(pattern))
    return paths


def canonical_protected(project_root: Path, path: Path) -> bool:
    relative = rel_path(project_root, path)
    if relative in CANONICAL_KEEP:
        return True
    for item in CANONICAL_KEEP:
        keep = project_root / item
        if keep.exists() and keep.is_dir() and is_relative_to(path.resolve(), keep.resolve()):
            return True
    return False


def collect_artifacts(
    project_root: Path,
    *,
    include_legacy: bool,
    include_runtime: bool,
    include_transient_task_manager: bool,
) -> list[Path]:
    paths: list[Path] = []
    if include_legacy:
        paths.extend(expand_globs(project_root, LEGACY_MACHINE_GLOBS))
    if include_runtime:
        paths.extend(expand_globs(project_root, ACTIVE_RUNTIME_GLOBS))
    if include_transient_task_manager:
        paths.extend(project_root / item for item in TRANSIENT_TASK_MANAGER_FILES)

    collected = [
        path
        for path in unique_existing(paths)
        if not canonical_protected(project_root, path)
    ]
    return prune_nested(collected)


def build_plan(project_root: Path, archive_root: Path, artifacts: list[Path], mode: str) -> list[dict[str, Any]]:
    plan: list[dict[str, Any]] = []
    for source in artifacts:
        relative = rel_path(project_root, source)
        item: dict[str, Any] = {
            "source": relative,
            "kind": "dir" if source.is_dir() else "file",
            "mode": mode,
        }
        if mode == "archive":
            item["target"] = (archive_root / relative).relative_to(project_root).as_posix()
        plan.append(item)
    return plan


def remove_or_move(source: Path, target: Path | None, mode: str) -> str:
    if not source.exists():
        return "skipped_missing"
    if mode == "delete":
        if source.is_dir():
            shutil.rmtree(source)
        else:
            source.unlink()
        return "deleted"
    if target is None:
        raise ValueError("archive target is required")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink()
    shutil.move(str(source), str(target))
    return "archived"


def apply_plan(project_root: Path, archive_root: Path, plan: list[dict[str, Any]], mode: str) -> None:
    for item in plan:
        source = project_root / str(item["source"])
        target = project_root / str(item["target"]) if item.get("target") else None
        item["action"] = remove_or_move(source, target, mode)

    if mode == "archive":
        archive_root.mkdir(parents=True, exist_ok=True)
        manifest = {
            "schema_version": 1,
            "created_at": utc_now(),
            "description": "Post-migration cleanup manifest for archived AiStudio project artifacts.",
            "items": plan,
        }
        (archive_root / "POST_MIGRATION_CLEANUP_MANIFEST.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


def write_report(project_root: Path, report: dict[str, Any]) -> Path:
    reports = task_manager_dir(project_root) / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    path = reports / f"POST_MIGRATION_CLEANUP_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"
    lines = [
        "# Post-Migration Cleanup",
        "",
        f"- Generated: `{report['created_at']}`",
        f"- Mode: `{report['mode']}`",
        f"- Apply: `{report['apply']}`",
        f"- Items: `{report['count']}`",
    ]
    if report.get("archive_root"):
        lines.append(f"- Archive root: `{report['archive_root']}`")
    lines.extend(["", "## Items", ""])
    for item in report["items"]:
        target = f" -> `{item['target']}`" if item.get("target") else ""
        lines.append(f"- `{item.get('action', 'dry_run')}` `{item['source']}`{target}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description="Archive transient project migration artifacts.")
    parser.add_argument("--project-root", default=".", help="Repository root. Defaults to current directory.")
    parser.add_argument("--archive-root", help="Archive root. Defaults to old/agent-runs/migrations/<timestamp>.")
    parser.add_argument("--mode", choices=("archive", "delete"), default="archive")
    parser.add_argument("--skip-legacy", action="store_true", help="Do not include legacy docs/plans machine-state.")
    parser.add_argument("--skip-runtime", action="store_true", help="Do not include agent-runtime/worktree folders.")
    parser.add_argument("--skip-transient-task-manager", action="store_true", help="Do not include generated Task_manager run files.")
    parser.add_argument("--write-report", action="store_true", help="Write a Task_manager report even in dry-run mode.")
    parser.add_argument("--apply", action="store_true", help="Apply cleanup. Without this, only prints the plan.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    archive_root = (
        Path(args.archive_root).resolve()
        if args.archive_root
        else project_root / "old" / "agent-runs" / "migrations" / utc_stamp()
    )
    artifacts = collect_artifacts(
        project_root,
        include_legacy=not args.skip_legacy,
        include_runtime=not args.skip_runtime,
        include_transient_task_manager=not args.skip_transient_task_manager,
    )
    plan = build_plan(project_root, archive_root, artifacts, args.mode)
    if args.apply:
        apply_plan(project_root, archive_root, plan, args.mode)
    else:
        for item in plan:
            item["action"] = "dry_run"

    report: dict[str, Any] = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "mode": args.mode,
        "apply": bool(args.apply),
        "archive_root": str(archive_root) if args.mode == "archive" else None,
        "count": len(plan),
        "items": plan,
    }
    if args.apply or args.write_report:
        report["report"] = str(write_report(project_root, report))

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"project_root: {project_root}")
        print(f"mode: {args.mode}")
        print(f"apply: {args.apply}")
        if args.mode == "archive":
            print(f"archive_root: {archive_root}")
        print(f"items: {len(plan)}")
        for item in plan:
            target = f" -> {item['target']}" if item.get("target") else ""
            print(f"- {item['action']}: {item['source']}{target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
