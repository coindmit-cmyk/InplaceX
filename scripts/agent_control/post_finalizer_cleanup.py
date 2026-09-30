#!/usr/bin/env python3
"""Archive or remove transient agent run artifacts after finalization."""

from __future__ import annotations

import argparse
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


EXACT_ARTIFACTS = (
    "AiStudio/Task_manager/integration_handoff.json",
    "AiStudio/Task_manager/integration_batch.json",
    "AiStudio/Task_manager/integration_candidates.batch.json",
    "AiStudio/Task_manager/allowed_paths_repair_plan.json",
    "AiStudio/Task_manager/candidate_salvage_audit.json",
    "AiStudio/Task_manager/clean_rebuild_plan.json",
    "AiStudio/Task_manager/integrator_preflight.json",
    "AiStudio/Task_manager/pr_readiness_report.json",
    "AiStudio/Task_manager/pr_readiness_report.identity_filtered.json",
    "AiStudio/Task_manager/pre_integrator_repair.json",
    "AiStudio/Task_manager/task_identity_audit.json",
    "docs/plans/integration_handoff.json",
    "docs/plans/integration_batch.json",
    "docs/plans/integration_candidates.batch.json",
    "docs/plans/allowed_paths_repair_plan.json",
    "docs/plans/candidate_salvage_audit.json",
    "docs/plans/clean_rebuild_plan.json",
    "docs/plans/integrator_preflight.json",
    "docs/plans/pr_readiness_report.json",
    "docs/plans/pr_readiness_report.identity_filtered.json",
    "docs/plans/pre_integrator_repair.json",
    "docs/plans/task_identity_audit.json",
)
REPORT_GLOBS = (
    "AiStudio/Task_manager/reports/INTEGRATION_BATCH_*.md",
    "AiStudio/Task_manager/reports/ALLOWED_PATHS_REPAIR_PLAN_*.md",
    "AiStudio/Task_manager/reports/INTEGRATION_CANDIDATE_FILTER_*.md",
    "AiStudio/Task_manager/reports/CANDIDATE_SALVAGE_AUDIT_*.md",
    "AiStudio/Task_manager/reports/CLEAN_REBUILD_PLAN_*.md",
    "AiStudio/Task_manager/reports/PRE_INTEGRATOR_REPAIR_*.md",
    "AiStudio/Task_manager/reports/PR_READINESS_*.md",
    "AiStudio/Task_manager/reports/TASK_IDENTITY_AUDIT_*.md",
    "docs/plans/reports/INTEGRATION_BATCH_*.md",
    "docs/plans/reports/ALLOWED_PATHS_REPAIR_PLAN_*.md",
    "docs/plans/reports/INTEGRATION_CANDIDATE_FILTER_*.md",
    "docs/plans/reports/CANDIDATE_SALVAGE_AUDIT_*.md",
    "docs/plans/reports/CLEAN_REBUILD_PLAN_*.md",
    "docs/plans/reports/PRE_INTEGRATOR_REPAIR_*.md",
    "docs/plans/reports/PR_READINESS_*.md",
    "docs/plans/reports/TASK_IDENTITY_AUDIT_*.md",
)
PROCESS_LOG_GLOBS = (
    "AiStudio/Task_manager/process-logs/*.jsonl",
    "docs/plans/process-logs/*.jsonl",
)
KEEP_NAMES = {".gitkeep"}


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def rel_path(project_root: Path, path: Path) -> str:
    return path.relative_to(project_root).as_posix()


def unique_paths(paths: list[Path]) -> list[Path]:
    seen: set[Path] = set()
    result: list[Path] = []
    for path in paths:
        resolved = path.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        result.append(path)
    return result


def collect_artifacts(
    project_root: Path,
    *,
    include_reports: bool = True,
    include_process_logs: bool = True,
) -> list[Path]:
    paths = [project_root / item for item in EXACT_ARTIFACTS]
    if include_reports:
        for pattern in REPORT_GLOBS:
            paths.extend(project_root.glob(pattern))
    if include_process_logs:
        for pattern in PROCESS_LOG_GLOBS:
            paths.extend(project_root.glob(pattern))
    return unique_paths(
        sorted(
            path
            for path in paths
            if path.exists() and path.is_file() and path.name not in KEEP_NAMES
        )
    )


def build_plan(
    project_root: Path,
    artifacts: list[Path],
    *,
    archive_root: Path,
    mode: str,
) -> list[dict[str, Any]]:
    planned: list[dict[str, Any]] = []
    for source in artifacts:
        relative = rel_path(project_root, source)
        item: dict[str, Any] = {
            "source": relative,
            "mode": mode,
        }
        if mode == "archive":
            item["target"] = (archive_root / relative).relative_to(project_root).as_posix()
        planned.append(item)
    return planned


def apply_plan(project_root: Path, archive_root: Path, plan: list[dict[str, Any]]) -> None:
    for item in plan:
        source = project_root / str(item["source"])
        mode = str(item["mode"])
        if not source.exists():
            item["action"] = "skipped_missing"
            continue
        if mode == "delete":
            source.unlink()
            item["action"] = "deleted"
            continue
        target = project_root / str(item["target"])
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(source), str(target))
        item["action"] = "archived"

    if plan:
        archive_root.mkdir(parents=True, exist_ok=True)
        manifest = {
            "schema_version": 1,
            "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "description": "Post-finalizer cleanup manifest for transient agent run artifacts.",
            "items": plan,
        }
        (archive_root / "FINALIZER_CLEANUP_MANIFEST.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Archive or remove transient agent artifacts after finalization.")
    parser.add_argument("--project-root", default=".", help="Repository root. Defaults to current directory.")
    parser.add_argument("--archive-root", help="Archive root. Defaults to old/agent-runs/finalized/<timestamp>.")
    parser.add_argument("--mode", choices=("archive", "delete"), default="archive", help="Cleanup mode. Defaults to archive.")
    parser.add_argument("--skip-reports", action="store_true", help="Do not include generated integration report markdown files.")
    parser.add_argument("--skip-process-logs", action="store_true", help="Do not include Task_manager/process-logs/*.jsonl.")
    parser.add_argument("--apply", action="store_true", help="Apply cleanup. Without this, only prints the plan.")
    parser.add_argument("--json", action="store_true", help="Emit JSON report.")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    archive_root = (
        Path(args.archive_root).resolve()
        if args.archive_root
        else project_root / "old" / "agent-runs" / "finalized" / utc_stamp()
    )
    artifacts = collect_artifacts(
        project_root,
        include_reports=not args.skip_reports,
        include_process_logs=not args.skip_process_logs,
    )
    plan = build_plan(project_root, artifacts, archive_root=archive_root, mode=args.mode)
    if args.apply:
        apply_plan(project_root, archive_root, plan)
    else:
        for item in plan:
            item["action"] = "dry_run"

    report = {
        "project_root": str(project_root),
        "archive_root": str(archive_root) if args.mode == "archive" else None,
        "mode": args.mode,
        "apply": bool(args.apply),
        "count": len(plan),
        "items": plan,
    }
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"project_root: {project_root}")
        print(f"mode: {args.mode}")
        if args.mode == "archive":
            print(f"archive_root: {archive_root}")
        print(f"items: {len(plan)}")
        for item in plan:
            target = f" -> {item['target']}" if "target" in item else ""
            print(f"- {item['action']}: {item['source']}{target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
