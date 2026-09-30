#!/usr/bin/env python3
"""Route stale Git branches to verified archive, recovery work, or retention."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import branch_cleanup_planner
import worktree_retirement
from project_paths import task_file

WORK_CLASSES = {
    "dirty_worker_candidate",
    "integration_recovery_candidate",
    "unknown_needs_review",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def logical_rows(branches: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    for row in branches:
        if not isinstance(row, dict):
            continue
        branch = str(row.get("name") or "")
        if not branch:
            continue
        current = rows.get(branch)
        if current is None or row.get("ref_kind") == "remote":
            rows[branch] = row
    return rows


def archive_evaluation_names(
    branch_report: dict[str, Any],
    *,
    stale_days: int,
) -> set[str]:
    activity = branch_report.get("codex_activity")
    if isinstance(activity, dict) and not activity.get("coverage_complete"):
        return set()
    names: set[str] = set()
    for branch, row in logical_rows(list(branch_report.get("branches") or [])).items():
        merged = bool(row.get("merged_into_develop") or row.get("merged_into_release_main"))
        stale = int(row.get("age_days") or 0) >= max(0, stale_days)
        pr = row.get("pr") if isinstance(row.get("pr"), dict) else {}
        open_pr = str(pr.get("state") or "").upper() == "OPEN"
        if merged and stale and not open_pr and row.get("classification") != "keep_active":
            names.add(branch)
    return names


def scan(
    branch_report: dict[str, Any],
    retirement_plan: dict[str, Any],
    *,
    stale_days: int,
) -> dict[str, Any]:
    rows = logical_rows(list(branch_report.get("branches") or []))
    retirement = {
        str(row.get("branch") or ""): row
        for row in retirement_plan.get("worktrees") or []
        if isinstance(row, dict) and row.get("branch")
    }
    activity = branch_report.get("codex_activity")
    coverage_complete = not isinstance(activity, dict) or bool(activity.get("coverage_complete"))
    decisions: list[dict[str, Any]] = []
    for branch, row in sorted(rows.items()):
        plan_row = retirement.get(branch)
        classification = str(row.get("classification") or "unknown_needs_review")
        age_days = int(row.get("age_days") or 0)
        stale = age_days >= max(0, stale_days)
        reasons = list((plan_row or {}).get("reasons") or [])
        if coverage_complete and plan_row and plan_row.get("eligible"):
            decision = "archive_ready"
            action = "archive_exact_tip"
            reason = "old merged branch passed archive retirement gates"
        elif stale and classification in WORK_CLASSES and classification != "keep_active":
            decision = "work_required"
            action = "create_or_update_recovery_task"
            reason = str(row.get("reason") or "old branch requires reconciliation")
        elif plan_row and any(item in reasons for item in ("tip_not_integrated", "remote_tip_mismatch")):
            decision = "work_required"
            action = "create_or_update_recovery_task"
            reason = ", ".join(reasons)
        else:
            decision = "keep"
            action = "retain"
            reason = str(row.get("reason") or ", ".join(reasons) or "retention gate")
        key = hashlib.sha256(f"{branch}|{row.get('sha')}|{decision}".encode("utf-8")).hexdigest()[:20]
        decisions.append(
            {
                "decision_id": f"branch-lifecycle:{key}",
                "branch": branch,
                "sha": row.get("sha"),
                "age_days": age_days,
                "decision": decision,
                "action": action,
                "source_classification": classification,
                "reason": reason,
                "retirement_reasons": reasons,
                "worktree_path": (plan_row or {}).get("path"),
            }
        )
    counts = {
        name: sum(1 for row in decisions if row["decision"] == name)
        for name in ("archive_ready", "work_required", "keep")
    }
    return {
        "schema_version": "1.0",
        "generated_at": utc_now(),
        "stale_days": max(0, stale_days),
        "codex_activity_coverage_complete": coverage_complete,
        "counts": counts,
        "decisions": decisions,
    }


def recovery_rows(
    branch_report: dict[str, Any],
    scan_report: dict[str, Any],
) -> list[dict[str, Any]]:
    rows = logical_rows(list(branch_report.get("branches") or []))
    work = {
        str(item.get("branch") or "")
        for item in scan_report.get("decisions") or []
        if item.get("decision") == "work_required"
    }
    result: list[dict[str, Any]] = []
    for branch, row in rows.items():
        item = dict(row)
        if branch in work:
            evidence = dict(item.get("integration_evidence") or {})
            evidence["scanner_source_classification"] = item.get("classification")
            item["integration_evidence"] = evidence
            item["classification"] = "integration_recovery_candidate"
        result.append(item)
    return result


def repo_slug(project_root: Path) -> str:
    proc = subprocess.run(
        ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
        cwd=project_root,
        text=True,
        capture_output=True,
        check=False,
    )
    return proc.stdout.strip() if proc.returncode == 0 and proc.stdout.strip() else "coindmit-cmyk/ai-project-agent"


def planner_report(args: argparse.Namespace) -> dict[str, Any]:
    namespace = argparse.Namespace(
        project_root=str(args.project_root),
        base=args.base,
        release_base=args.release_base,
        remote=args.remote,
        stale_days=max(0, args.stale_days),
        archive_prefix=args.archive_prefix,
        protection_mode=args.protection_mode,
        codex_activity_dir=args.codex_activity_dir,
        expected_codex_host=list(args.expected_codex_host or []),
        codex_activity_max_age_seconds=max(1, args.codex_activity_max_age_seconds),
        evidence_cache=args.evidence_cache,
        include_local=True,
        include_remote=True,
        fetch=bool(args.fetch),
        deep_metrics=bool(args.deep_metrics),
    )
    return branch_cleanup_planner.build_report(namespace)


def resolve_task_root(source_root: Path, configured: Path | None) -> Path:
    return configured.expanduser().resolve() if configured else source_root


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--task-project-root", type=Path)
    parser.add_argument("--base", default="develop")
    parser.add_argument("--release-base", default="release/main")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--stale-days", type=int, default=14)
    parser.add_argument("--archive-prefix", default="archive/branches")
    parser.add_argument(
        "--protection-mode",
        choices=branch_cleanup_planner.PROTECTION_MODES,
        default="default",
    )
    parser.add_argument("--archive-root")
    parser.add_argument("--archive-ssh-host")
    parser.add_argument("--max-archive-count", type=int, default=10)
    parser.add_argument("--max-task-count", type=int, default=10)
    parser.add_argument("--recovery-task-id-file", type=Path)
    parser.add_argument("--delete-local-branch", action="store_true")
    parser.add_argument("--delete-remote-branch", action="store_true")
    parser.add_argument("--codex-activity-dir")
    parser.add_argument("--expected-codex-host", action="append", default=[])
    parser.add_argument("--codex-activity-max-age-seconds", type=int, default=900)
    parser.add_argument("--evidence-cache")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--deep-metrics", action="store_true")
    parser.add_argument("--include-branch-report", action="store_true")
    parser.add_argument("--apply-archives", action="store_true")
    parser.add_argument("--apply-tasks", action="store_true")
    parser.add_argument("--yes", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    import repository_hygiene_cycle

    args = parse_args()
    root = args.project_root.expanduser().resolve()
    task_root = resolve_task_root(root, args.task_project_root)
    destructive = bool(args.apply_archives or args.apply_tasks)
    if destructive and not args.yes:
        raise SystemExit("apply modes require --yes")
    if args.apply_archives and not args.archive_root:
        raise SystemExit("--apply-archives requires --archive-root")

    branches = planner_report(args)
    selected = archive_evaluation_names(branches, stale_days=args.stale_days)
    protected = {
        str(row.get("name") or "")
        for row in branches.get("branches") or []
        if row.get("classification") == "keep_active"
    }
    if selected:
        retirement = worktree_retirement.build_plan(
            root,
            branches=selected,
            open_pr_heads=worktree_retirement.open_pr_heads(root),
            protected_branches=protected,
            base_refs=[
                args.base if args.base.startswith("origin/") else f"{args.remote}/{args.base}",
                args.release_base if args.release_base.startswith("origin/") else f"{args.remote}/{args.release_base}",
            ],
            min_age_days=max(0, args.stale_days),
            remote=args.remote,
            include_selected_without_worktree=True,
            protection_mode=args.protection_mode,
        )
    else:
        retirement = {
            "schema_version": "1.0",
            "generated_at": utc_now(),
            "min_age_days": max(0, args.stale_days),
            "base_refs": [args.base, args.release_base],
            "selected_branches": [],
            "candidate_count": 0,
            "eligible_count": 0,
            "blocked_count": 0,
            "worktrees": [],
        }
    scanner = scan(branches, retirement, stale_days=args.stale_days)
    now = utc_now()
    queue_path = task_file(task_root, "task_queue.json")
    if args.apply_tasks and not queue_path.exists():
        raise SystemExit(f"task queue not found under --task-project-root: {queue_path}")
    queue = repository_hygiene_cycle.load_json(queue_path, {"tasks": []})
    allowed_task_ids: set[str] | None = None
    if args.recovery_task_id_file:
        allowlist_payload = json.loads(
            args.recovery_task_id_file.expanduser().resolve().read_text(encoding="utf-8")
        )
        values = allowlist_payload.get("task_ids") if isinstance(allowlist_payload, dict) else allowlist_payload
        if not isinstance(values, list) or any(not isinstance(value, str) or not value for value in values):
            raise SystemExit("--recovery-task-id-file must contain a JSON string list")
        if len(values) != len(set(values)):
            raise SystemExit("--recovery-task-id-file contains duplicate task ids")
        allowed_task_ids = set(values)
    task_routing = repository_hygiene_cycle.apply_recovery_tasks(
        queue,
        repo_slug(root),
        recovery_rows(branches, scanner),
        apply=bool(args.apply_tasks),
        now=now,
        max_stage_count=max(0, args.max_task_count),
        allowed_task_ids=allowed_task_ids,
    )
    event: dict[str, Any] = {"event_appended": False, "reason": "dry_run"}
    if args.apply_tasks and task_routing.get("changed"):
        queue["updated_at"] = now
        repository_hygiene_cycle.write_json(queue_path, queue)
        event = repository_hygiene_cycle.append_queue_event(task_root, task_routing, [], now)

    archive_results: list[dict[str, Any]] = []
    archive_ready = [
        item
        for item in retirement.get("worktrees") or []
        if isinstance(item, dict) and item.get("eligible")
    ]
    if args.apply_archives and archive_ready:
        archive_results = worktree_retirement.apply_plan(
            root,
            retirement,
            archive_root=args.archive_root,
            archive_ssh_host=args.archive_ssh_host,
            remote=args.remote,
            max_count=max(0, args.max_archive_count),
            delete_local_branch=bool(args.delete_local_branch),
            delete_remote_branch=bool(args.delete_remote_branch),
        )
    payload = {
        "schema_version": "1.0",
        "generated_at": now,
        "mode": "apply" if destructive else "dry_run",
        "ok": all(row.get("status") == "retired" for row in archive_results),
        "task_project_root": str(task_root),
        "branch_counts": branches.get("counts"),
        "scanner": scanner,
        "retirement_plan": retirement,
        "task_routing": task_routing,
        "event": event,
        "archive_results": archive_results,
    }
    if args.include_branch_report:
        payload["branch_report"] = branches
    if args.output:
        repository_hygiene_cycle.write_json(args.output.expanduser().resolve(), payload)
    print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else json.dumps(payload, ensure_ascii=False))
    return 0 if payload["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
