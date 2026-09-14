#!/usr/bin/env python3
"""Run deterministic pre-integrator sanitation and batching."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file, task_manager_dir


TERMINAL_TASK_STATUSES = {"done", "completed", "finalized", "released", "archived", "stale_or_superseded", "duplicate_linked", "split_into_children"}
TERMINAL_INTEGRATION_STATUSES = {"finalized", "closed_no_diff", "closed_coordination_only", "blocked", "already_integrated", "not_integrated_no_product_payload"}
PR_SNAPSHOT_FIELDS = (
    "number,title,state,isDraft,baseRefName,headRefName,headRefOid,"
    "mergeStateStatus,updatedAt,url,reviewDecision,statusCheckRollup"
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def script_path(name: str) -> Path:
    return Path(__file__).resolve().parent / name


def run_json(cmd: list[str]) -> dict[str, Any]:
    proc = subprocess.run(cmd, text=True, capture_output=True, check=False)
    data: dict[str, Any]
    if proc.stdout.strip():
        try:
            data = json.loads(proc.stdout)
        except json.JSONDecodeError:
            data = {"raw_stdout": proc.stdout}
    else:
        data = {}
    data["exit_code"] = proc.returncode
    if proc.stderr:
        data["stderr"] = proc.stderr
    if proc.returncode != 0:
        raise RuntimeError(json.dumps({"command": cmd, **data}, ensure_ascii=False, indent=2))
    return data


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def collect_github_pr_snapshot(project_root: Path, output: Path) -> dict[str, Any]:
    try:
        repo_proc = subprocess.run(
            ["gh", "repo", "view", "--json", "nameWithOwner"],
            cwd=project_root,
            text=True,
            capture_output=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"ok": False, "reason": "github_repository_lookup_failed", "error": str(exc)}
    if repo_proc.returncode != 0:
        return {
            "ok": False,
            "reason": "github_repository_lookup_failed",
            "error": (repo_proc.stderr or repo_proc.stdout).strip()[:500],
        }
    try:
        repo = str(json.loads(repo_proc.stdout or "{}").get("nameWithOwner") or "").strip()
    except json.JSONDecodeError as exc:
        return {"ok": False, "reason": "github_repository_lookup_invalid_json", "error": str(exc)}
    if not repo:
        return {"ok": False, "reason": "github_repository_identity_missing"}

    try:
        pr_proc = subprocess.run(
            [
                "gh",
                "pr",
                "list",
                "--repo",
                repo,
                "--state",
                "open",
                "--limit",
                "1000",
                "--json",
                PR_SNAPSHOT_FIELDS,
            ],
            cwd=project_root,
            text=True,
            capture_output=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"ok": False, "reason": "github_pr_snapshot_failed", "repository": repo, "error": str(exc)}
    if pr_proc.returncode != 0:
        return {
            "ok": False,
            "reason": "github_pr_snapshot_failed",
            "repository": repo,
            "error": (pr_proc.stderr or pr_proc.stdout).strip()[:500],
        }
    try:
        rows = json.loads(pr_proc.stdout or "[]")
    except json.JSONDecodeError as exc:
        return {"ok": False, "reason": "github_pr_snapshot_invalid_json", "repository": repo, "error": str(exc)}
    if not isinstance(rows, list):
        return {"ok": False, "reason": "github_pr_snapshot_invalid_shape", "repository": repo}

    snapshot = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "repository": repo,
        "pull_requests": [row for row in rows if isinstance(row, dict)],
    }
    write_json(output, snapshot)
    return {
        "ok": True,
        "repository": repo,
        "path": str(output),
        "pull_request_count": len(snapshot["pull_requests"]),
    }


def terminal_task_ids(project_root: Path) -> list[str]:
    queue_path = task_file(project_root, "task_queue.json")
    if not queue_path.exists():
        return []
    queue = load_json(queue_path)
    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        return []
    result: list[str] = []
    for task in tasks:
        if not isinstance(task, dict):
            continue
        tid = str(task.get("id") or task.get("task_id") or "").strip()
        if not tid:
            continue
        if str(task.get("status") or "") in TERMINAL_TASK_STATUSES or str(task.get("integration_status") or "") in TERMINAL_INTEGRATION_STATUSES:
            result.append(tid)
    return sorted(set(result))


def manual_review_task_ids(project_root: Path, *, reprocess_integrator_review: bool = False) -> list[str]:
    queue_path = task_file(project_root, "task_queue.json")
    if not queue_path.exists():
        return []
    queue = load_json(queue_path)
    tasks = queue.get("tasks") if isinstance(queue, dict) else None
    if not isinstance(tasks, list):
        return []
    result: list[str] = []
    for task in tasks:
        if not isinstance(task, dict):
            continue
        tid = str(task.get("id") or task.get("task_id") or "").strip()
        if not tid:
            continue
        status = str(task.get("status") or "").strip()
        integration_status = str(task.get("integration_status") or "").strip()
        decision = str(task.get("dispatcher_decision") or "").strip()
        owner = str(task.get("next_owner") or "").strip().lower()
        ownership_disposition = task.get("ownership_disposition")
        ownership_action = (
            str(ownership_disposition.get("action") or "").strip().lower()
            if isinstance(ownership_disposition, dict)
            else ""
        )
        hard_manual_route = (
            status == "needs_human"
            or integration_status == "needs_human"
            or owner in {"human", "owner", "manual"}
            or ownership_action == "semantic_review"
        )
        integrator_review_route = (
            integration_status == "needs_integrator_review"
            or decision == "needs_integrator_review"
        )
        if hard_manual_route or (integrator_review_route and not reprocess_integrator_review):
            result.append(tid)
    return sorted(set(result))


def write_batch_candidates(project_root: Path, batch: dict[str, Any]) -> Path:
    output = task_manager_dir(project_root) / "integration_candidates.batch.json"
    candidates = []
    for item in batch.get("included") or []:
        if not isinstance(item, dict):
            continue
        candidates.append(
            {
                "task_id": (item.get("task_ids") or [None])[0],
                "task_ids": item.get("task_ids") or [],
                "branch": item.get("branch"),
                "pr": item.get("pr"),
                "head_sha": item.get("head_sha"),
                "canonical_target_id": item.get("canonical_target_id"),
                "identity_status": item.get("identity_status"),
                "identity_valid": item.get("identity_valid"),
                "identity_provisional": item.get("identity_provisional"),
                "worker_report": item.get("worker_report"),
                "source_artifact_id": item.get("source_artifact_id"),
                "source_artifact": item.get("source_artifact"),
                "risk_class": item.get("risk_class"),
                "changed_paths": item.get("changed_paths") or [],
                "classification": item.get("classification"),
                "reason": item.get("reason"),
                "source": "integration_batch_builder",
            }
        )
    write_json(output, {"generated_at": utc_now(), "batch_id": batch.get("batch_id"), "candidates": candidates})
    return output


def render_report(summary: dict[str, Any]) -> str:
    return "\n".join(
        [
            "# Pre-Integrator Repair",
            "",
            f"- Generated: `{summary.get('created_at')}`",
            f"- Base ref: `{summary.get('base_ref')}`",
            f"- Preflight candidates: `{summary.get('preflight_candidate_count')}`",
            f"- Ready batch items: `{summary.get('batch_included_count')}`",
            f"- Excluded items: `{summary.get('batch_excluded_count')}`",
            f"- Batch file: `{summary.get('integration_batch')}`",
            f"- Batch candidates: `{summary.get('batch_candidates')}`",
            f"- Readiness counts: `{json.dumps(summary.get('readiness_counts') or {}, ensure_ascii=False, sort_keys=True)}`",
            f"- Identity counts: `{json.dumps(summary.get('identity_counts') or {}, ensure_ascii=False, sort_keys=True)}`",
            f"- Salvage counts: `{json.dumps(summary.get('salvage_counts') or {}, ensure_ascii=False, sort_keys=True)}`",
            f"- Clean rebuild counts: `{json.dumps(summary.get('clean_rebuild_counts') or {}, ensure_ascii=False, sort_keys=True)}`",
            f"- Rebuild route events: `{summary.get('rebuild_route_event_count', 0)}`",
            "",
        ]
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Run worker sync, preflight, readiness classification and batch building.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--emit-events", action="store_true")
    parser.add_argument("--skip-worker-bridge", action="store_true")
    parser.add_argument("--skip-worker-result-sync", action="store_true")
    parser.add_argument("--skip-pr-snapshot", action="store_true")
    parser.add_argument("--exclude-legacy-worker-branches", action="store_true")
    parser.add_argument("--require-checks", action="store_true")
    parser.add_argument("--block-drafts", action="store_true")
    parser.add_argument("--strict-fresh-base", action="store_true")
    parser.add_argument(
        "--reprocess-integrator-review",
        action="store_true",
        help="Re-enter Integrator-owned review rows while preserving hard human/owner routes.",
    )
    parser.add_argument("--skip-clean-rebuild-promotion", action="store_true")
    parser.add_argument("--max-clean-rebuild-promotions", type=int, default=0)
    parser.add_argument("--max-normal", type=int, default=12)
    parser.add_argument("--max-low-risk", type=int, default=20)
    parser.add_argument("--max-high-risk", type=int, default=4)
    parser.add_argument("--max-modules", type=int, default=4)
    parser.add_argument("--max-tasks-per-module", type=int, default=8)
    parser.add_argument("--include-high-risk", dest="include_high_risk", action="store_true", default=True)
    parser.add_argument("--exclude-high-risk", dest="include_high_risk", action="store_false")
    parser.add_argument("--allow-overlap", dest="allow_overlap", action="store_true", default=True)
    parser.add_argument("--disallow-overlap", dest="allow_overlap", action="store_false")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    reports = plans / "reports"
    preflight_path = plans / "integrator_preflight.json"
    readiness_path = plans / "pr_readiness_report.json"
    identity_audit_path = plans / "task_identity_audit.json"
    identity_readiness_path = plans / "pr_readiness_report.identity_filtered.json"
    salvage_path = plans / "candidate_salvage_audit.json"
    clean_rebuild_path = plans / "clean_rebuild_plan.json"
    rebuild_decision_path = plans / "rebuild_decision_report.json"
    rebuild_route_path = plans / "route_rebuild_and_integration_results.json"
    batch_path = plans / "integration_batch.json"
    pr_snapshot_path = plans / "integration_pr_snapshot_latest.json"

    clean_rebuild_promotion: dict[str, Any] | None = None
    if not args.skip_clean_rebuild_promotion and args.max_clean_rebuild_promotions > 0 and (plans / "clean_rebuild_plan.json").exists():
        clean_rebuild_cmd = [
            sys.executable,
            str(script_path("clean_rebuild_queue_bridge.py")),
            "--project-root",
            str(project_root),
            "--max-items",
            str(args.max_clean_rebuild_promotions),
            "--apply",
            "--json",
        ]
        clean_rebuild_promotion = run_json(clean_rebuild_cmd)

    worker_bridge_report: dict[str, Any] | None = None
    if not args.skip_worker_bridge:
        bridge_cmd = [
            sys.executable,
            str(script_path("worker_integrator_bridge.py")),
            "--project-root",
            str(project_root),
            "--base-ref",
            args.base_ref,
            "--json",
            "--apply",
        ]
        if not args.skip_worker_result_sync:
            bridge_cmd.append("--sync-worker-results")
            if not args.exclude_legacy_worker_branches:
                bridge_cmd.append("--include-legacy-worker-branches")
        if args.fetch:
            bridge_cmd.append("--fetch")
        if args.emit_events:
            bridge_cmd.append("--emit-events")
        worker_bridge_report = run_json(bridge_cmd)

    preflight_cmd = [
        sys.executable,
        str(script_path("integrator_preflight.py")),
        "--project-root",
        str(project_root),
        "--base",
        args.base_ref,
        "--use-repository-hygiene",
        "--output",
        str(preflight_path),
        "--json",
    ]
    if args.fetch:
        preflight_cmd.append("--fetch")
    preflight = run_json(preflight_cmd)

    pr_snapshot = (
        {"ok": False, "reason": "skipped_by_operator"}
        if args.skip_pr_snapshot
        else collect_github_pr_snapshot(project_root, pr_snapshot_path)
    )
    classifier_cmd = [
        sys.executable,
        str(script_path("pr_readiness_classifier.py")),
        "--project-root",
        str(project_root),
        "--preflight",
        str(preflight_path),
        "--output",
        str(readiness_path),
        "--json",
    ]
    if pr_snapshot.get("ok"):
        classifier_cmd.extend(["--pr-snapshot", str(pr_snapshot_path)])
    if args.require_checks:
        classifier_cmd.append("--require-checks")
    if args.block_drafts:
        classifier_cmd.append("--block-drafts")
    if args.strict_fresh_base:
        classifier_cmd.append("--strict-fresh-base")
    readiness = run_json(classifier_cmd)

    identity_audit_cmd = [
        sys.executable,
        str(script_path("task_identity_audit.py")),
        "--project-root",
        str(project_root),
        "--readiness",
        str(readiness_path),
        "--output",
        str(identity_audit_path),
        "--json",
    ]
    identity_audit = run_json(identity_audit_cmd)

    identity_filter_cmd = [
        sys.executable,
        str(script_path("integration_candidate_filter.py")),
        "--project-root",
        str(project_root),
        "--readiness",
        str(readiness_path),
        "--audit",
        str(identity_audit_path),
        "--output",
        str(identity_readiness_path),
        "--json",
    ]
    identity_readiness = run_json(identity_filter_cmd)
    terminal_ids = terminal_task_ids(project_root)
    if terminal_ids:
        identity_readiness["terminal_task_ids"] = terminal_ids
    manual_ids = manual_review_task_ids(
        project_root,
        reprocess_integrator_review=args.reprocess_integrator_review,
    )
    if manual_ids:
        identity_readiness["manual_review_task_ids"] = manual_ids
    if terminal_ids or manual_ids:
        write_json(identity_readiness_path, identity_readiness)

    salvage = run_json(
        [
            sys.executable,
            str(script_path("candidate_salvage_audit.py")),
            "--project-root",
            str(project_root),
            "--preflight",
            str(preflight_path),
            "--readiness",
            str(identity_readiness_path),
            "--audit",
            str(identity_audit_path),
            "--queue",
            str(task_file(project_root, "task_queue.json")),
            "--output",
            str(salvage_path),
            "--json",
        ]
    )
    clean_rebuild = run_json(
        [
            sys.executable,
            str(script_path("clean_rebuild_planner.py")),
            "--project-root",
            str(project_root),
            "--salvage",
            str(salvage_path),
            "--queue",
            str(task_file(project_root, "task_queue.json")),
            "--output",
            str(clean_rebuild_path),
            "--json",
        ]
    )
    rebuild_decisions = run_json(
        [
            sys.executable,
            str(script_path("rebuild_decision_classifier.py")),
            "--project-root",
            str(project_root),
            "--readiness",
            str(identity_readiness_path),
            "--clean-rebuild-plan",
            str(clean_rebuild_path),
            "--output",
            str(rebuild_decision_path),
            "--apply",
            "--json",
        ]
    )
    route_command = [
        sys.executable,
        str(script_path("route_rebuild_and_integration_results.py")),
        "--project-root",
        str(project_root),
        "--decisions",
        str(rebuild_decision_path),
        "--output",
        str(rebuild_route_path),
        "--json",
    ]
    if args.emit_events:
        route_command.append("--apply")
    rebuild_routes = run_json(route_command)

    batch_cmd = [
        sys.executable,
        str(script_path("integration_batch_builder.py")),
        "--project-root",
        str(project_root),
        "--readiness",
        str(identity_readiness_path),
        "--output",
        str(batch_path),
        "--max-normal",
        str(args.max_normal),
        "--max-low-risk",
        str(args.max_low_risk),
        "--max-high-risk",
        str(args.max_high_risk),
        "--max-modules",
        str(args.max_modules),
        "--max-tasks-per-module",
        str(args.max_tasks_per_module),
        "--json",
    ]
    if args.include_high_risk:
        batch_cmd.append("--include-high-risk")
    else:
        batch_cmd.append("--exclude-high-risk")
    if args.allow_overlap:
        batch_cmd.append("--allow-overlap")
    else:
        batch_cmd.append("--disallow-overlap")
    batch = run_json(batch_cmd)
    batch_candidates = write_batch_candidates(project_root, batch)

    summary = {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "base_ref": args.base_ref,
        "clean_rebuild_promotion": clean_rebuild_promotion,
        "worker_bridge": worker_bridge_report,
        "integrator_preflight": str(preflight_path),
        "pr_snapshot": pr_snapshot,
        "pr_readiness_report": str(readiness_path),
        "task_identity_audit": str(identity_audit_path),
        "identity_filtered_readiness": str(identity_readiness_path),
        "integration_batch": str(batch_path),
        "batch_candidates": str(batch_candidates),
        "preflight_candidate_count": preflight.get("candidate_count"),
        "readiness_counts": readiness.get("counts"),
        "identity_audit_counts": identity_audit.get("counts"),
        "identity_counts": identity_readiness.get("identity_counts"),
        "salvage_counts": salvage.get("counts"),
        "clean_rebuild_counts": clean_rebuild.get("clean_rebuild_counts") or clean_rebuild.get("counts"),
        "rebuild_decision_counts": rebuild_decisions.get("counts"),
        "rebuild_route_event_count": int(rebuild_routes.get("event_count") or 0),
        "batch_included_count": batch.get("included_count"),
        "batch_excluded_count": batch.get("excluded_count"),
        "manual_review_task_count": len(manual_ids),
        "reprocess_integrator_review": args.reprocess_integrator_review,
        "handoff_ready": batch.get("handoff_ready"),
    }
    summary_path = plans / "pre_integrator_repair.json"
    write_json(summary_path, summary)
    report_path = reports / f"PRE_INTEGRATOR_REPAIR_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_report(summary), encoding="utf-8")
    append_log(project_root, "pre-integrator", "repair_complete", severity="info", included=batch.get("included_count"), excluded=batch.get("excluded_count"))

    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print(f"included: {summary['batch_included_count']}")
        print(f"excluded: {summary['batch_excluded_count']}")
        print(f"written: {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
