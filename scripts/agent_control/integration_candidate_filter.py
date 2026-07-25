#!/usr/bin/env python3
"""Hard identity gate between readiness classification and Auto Integrator."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file, task_reports_dir
from repository_pr_identity import resolve_repository_pr_task_ids, validate_repository_pr_identity


READY_CLASS = "ready_candidate"
BLOCKED_RESULTS = {"needs_human", "needs_worker_fix", "packet_defect", "needs_dispatcher", "needs_task_packet"}
BRANCH_NAME_ONLY_ISSUES = {
    "candidate missing task_id",
    "candidate branch missing task_id",
    "branch missing task_id",
}
TERMINAL_CLEAN_REBUILD_STATUSES = {
    "agent_done",
    "archived",
    "cancelled",
    "closed",
    "deferred",
    "done",
    "finalization_ready",
    "finalized",
    "integration_handoff_ready",
    "integration_requested",
    "stale_or_superseded",
}
TERMINAL_CLEAN_REBUILD_INTEGRATION_STATUSES = {
    "closed_coordination_only",
    "finalized",
    "integrated",
    "stale_or_superseded",
    "superseded_by_source_pr",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_branch(branch: Any) -> str:
    value = str(branch or "").strip()
    if value.startswith("refs/remotes/origin/"):
        return value.removeprefix("refs/remotes/origin/")
    if value.startswith("origin/"):
        return value.removeprefix("origin/")
    if value.startswith("refs/heads/"):
        return value.removeprefix("refs/heads/")
    return value


def audit_candidates(audit: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in audit.get("items") or []:
        if not isinstance(item, dict) or item.get("kind") != "integration_candidate":
            continue
        branch = normalize_branch(item.get("branch"))
        if branch:
            result[branch] = item
    return result


def report_paths_for(project_root: Path, task_id: str, audit_item: dict[str, Any] | None) -> list[str]:
    paths = [str(path) for path in (audit_item or {}).get("report_paths") or [] if path]
    if paths:
        return sorted(set(paths))
    roots = [project_root / "docs/reports", task_reports_dir(project_root), task_reports_dir(project_root) / "workers"]
    matches: list[str] = []
    for root in roots:
        if root.exists():
            for path in root.rglob(f"*{task_id}*"):
                if path.is_file():
                    matches.append(path.relative_to(project_root).as_posix())
    return sorted(set(matches))


def queue_report_paths(project_root: Path) -> dict[str, str]:
    queue = load_json(task_file(project_root, "task_queue.json"))
    result: dict[str, str] = {}
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        report = str(task.get("worker_report") or task.get("last_agent_report") or "").strip()
        if task_id and report:
            result[task_id] = report
    return result


def queue_task_index(project_root: Path) -> dict[str, dict[str, Any]]:
    queue = load_json(task_file(project_root, "task_queue.json"))
    return {
        str(task.get("id") or task.get("task_id") or "").strip().upper(): task
        for task in queue.get("tasks") or []
        if isinstance(task, dict) and str(task.get("id") or task.get("task_id") or "").strip()
    }


def clean_rebuild_recovery_by_branch(project_root: Path) -> dict[str, dict[str, Any]]:
    plan = load_json(task_file(project_root, "clean_rebuild_plan.json"))
    queue = load_json(task_file(project_root, "task_queue.json"))
    crb_by_source: dict[tuple[str, str], str] = {}
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        branch = normalize_branch(task.get("clean_rebuild_source_branch") or task.get("source_branch"))
        head = str(task.get("clean_rebuild_source_head_sha") or task.get("source_head_sha") or "").strip()
        if task_id and (branch or head):
            crb_by_source[(branch, head)] = task_id

    result: dict[str, dict[str, Any]] = {}
    for item in plan.get("items") or []:
        if not isinstance(item, dict):
            continue
        route = str(item.get("rebuild_route") or "")
        ids = [str(value) for value in item.get("task_ids") or [] if str(value or "").strip()]
        paths = [str(path) for path in item.get("changed_paths") or [] if str(path or "").strip()]
        branch = normalize_branch(item.get("branch"))
        head = str(item.get("head_sha") or "").strip()
        if not route.startswith("auto_clean_rebuild") or len(ids) != 1 or not branch or not paths:
            continue
        result[branch] = {
            "task_id": crb_by_source.get((branch, head)) or crb_by_source.get((branch, "")) or ids[0],
            "source_task_id": ids[0],
            "worker_report": "AiStudio/Task_manager/clean_rebuild_plan.json",
            "reason": "identity recovered from clean_rebuild_plan",
        }
    return result


def pending_clean_rebuild_source_by_branch(project_root: Path) -> dict[str, dict[str, Any]]:
    queue = load_json(task_file(project_root, "task_queue.json"))
    result: dict[str, dict[str, Any]] = {}
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        branch = normalize_branch(task.get("clean_rebuild_source_branch") or task.get("source_branch"))
        task_id = str(task.get("id") or task.get("task_id") or "").strip()
        task_type = str(task.get("type") or "")
        if not branch or not task_id or "clean-rebuild" not in task_type:
            continue
        status = str(task.get("status") or "").strip()
        integration_status = str(task.get("integration_status") or "").strip()
        if (
            status in TERMINAL_CLEAN_REBUILD_STATUSES
            or integration_status in TERMINAL_CLEAN_REBUILD_INTEGRATION_STATUSES
        ):
            continue
        result[branch] = task
    return result


def canonical_target(task_id: str) -> str:
    return f"task:{task_id}" if task_id else ""


def task_aliases(task_id: str) -> list[str]:
    value = str(task_id or "").strip()
    if not value:
        return []
    aliases = {value}
    if value.upper().startswith("CRB-"):
        aliases.add(value[4:])
    return sorted(aliases, key=len, reverse=True)


def branch_mentions_task(branch: str, task_id: str) -> bool:
    text = str(branch or "").upper()
    return any(alias.upper() in text for alias in task_aliases(task_id))


def changed_paths(item: dict[str, Any]) -> list[str]:
    return [str(path) for path in item.get("changed_paths") or [] if str(path or "").strip()]


def provisional_source_id(item: dict[str, Any]) -> str:
    seed = {
        "branch": item.get("branch"),
        "pr": item.get("pr") or item.get("pr_url"),
        "head_sha": item.get("head_sha"),
        "changed_paths": changed_paths(item),
    }
    digest = hashlib.sha1(json.dumps(seed, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
    return f"SRC-{digest[:12].upper()}"


def source_evidence_ref(item: dict[str, Any]) -> str:
    return str(
        item.get("worker_report")
        or item.get("last_agent_report")
        or item.get("integration_report")
        or item.get("pr_url")
        or item.get("pr")
        or item.get("branch")
        or item.get("head_sha")
        or "source_artifact"
    )


def can_assign_provisional_identity(item: dict[str, Any]) -> bool:
    classification = str(item.get("classification") or "")
    if classification in BLOCKED_RESULTS or has_human_blocker(item):
        return False
    if str(item.get("risk_class") or "medium") == "high":
        return False
    if not changed_paths(item):
        return False
    return bool(item.get("branch") or item.get("pr") or item.get("pr_url") or item.get("head_sha"))


def route_item(item: dict[str, Any], classification: str, next_owner: str, reason: str, issues: list[str]) -> dict[str, Any]:
    routed = dict(item)
    routed["classification"] = classification
    routed["next_owner"] = next_owner
    routed["reason"] = reason
    routed["identity_valid"] = False
    routed["identity_issues"] = issues
    return routed


def is_branch_name_only_issue(issue: str) -> bool:
    return issue.strip().lower() in BRANCH_NAME_ONLY_ISSUES


def human_blockers(item: dict[str, Any]) -> list[str]:
    blockers = [str(value) for value in item.get("blocking_reasons") or [] if str(value or "").strip()]
    classification = str(item.get("classification") or "")
    reason = str(item.get("reason") or "")
    if classification == "needs_human" and reason:
        blockers.append(reason)
    return sorted(set(blockers))


def has_human_blocker(item: dict[str, Any]) -> bool:
    text = " ".join(human_blockers(item)).lower()
    return str(item.get("classification") or "") == "needs_human" or "human" in text


def filter_item(
    project_root: Path,
    item: dict[str, Any],
    audit_by_branch: dict[str, dict[str, Any]],
    queue_tasks: dict[str, dict[str, Any]],
    queue_reports: dict[str, str],
    recovery_by_branch: dict[str, dict[str, Any]],
    pending_clean_rebuild_by_branch: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    original_class = str(item.get("classification") or "")
    task_ids = [str(value) for value in item.get("task_ids") or [] if value]
    branch = str(item.get("branch") or "")
    normalized = normalize_branch(branch)
    audit_item = audit_by_branch.get(normalized)
    recovery = recovery_by_branch.get(normalized)
    pending_clean_rebuild = pending_clean_rebuild_by_branch.get(normalized)
    issues: list[str] = []
    recovered_identity = False
    if pending_clean_rebuild:
        crb_id = str(pending_clean_rebuild.get("id") or pending_clean_rebuild.get("task_id") or "clean rebuild task")
        return route_item(
            item,
            "needs_worker_fix",
            "worker",
            f"source branch is waiting for clean rebuild worker result: {crb_id}",
            ["clean rebuild source branch is not integration-ready"],
        )
    if not task_ids and audit_item and audit_item.get("status") == "identity_recoverable":
        inferred = [str(value) for value in audit_item.get("inferred_task_ids") or [] if value]
        if len(inferred) == 1:
            task_ids = [inferred[0]]
            recovered_identity = True
    if not task_ids and recovery:
        task_ids = [str(recovery["task_id"])]
        recovered_identity = True
    if not task_ids:
        if can_assign_provisional_identity(item):
            task_ids = [provisional_source_id(item)]
            recovered_identity = True
            provisional_identity = True
        else:
            return route_item(item, "needs_dispatcher", "auto-dispatcher", "missing task identity", ["missing task_id"])
    else:
        provisional_identity = False
    repository_resolution = resolve_repository_pr_task_ids(
        task_ids,
        queue_tasks,
        candidate_branch=branch,
        candidate_pr=item.get("pr"),
        candidate_head_sha=item.get("head_sha"),
    )
    if repository_resolution.get("valid"):
        task_ids = [str(repository_resolution["selected_task_id"])]
        recovered_identity = True
    elif repository_resolution.get("applicable") and repository_resolution.get("issues"):
        return route_item(
            item,
            "needs_dispatcher",
            "auto-dispatcher",
            "; ".join(str(value) for value in repository_resolution["issues"]),
            [str(value) for value in repository_resolution["issues"]],
        )
    if len(task_ids) > 1:
        return route_item(item, "needs_dispatcher", "auto-dispatcher", "ambiguous task identity", ["multiple task_ids"])
    task_id = task_ids[0]
    canonical = canonical_target(task_id)
    repository_binding = validate_repository_pr_identity(
        queue_tasks.get(task_id.upper()),
        candidate_branch=branch,
        candidate_pr=item.get("pr"),
        candidate_head_sha=item.get("head_sha"),
        require_candidate_pr=True,
        require_candidate_head=True,
    )
    repository_binding_valid = bool(repository_binding.get("valid"))
    if repository_binding.get("applicable") and not repository_binding_valid:
        issues.extend(str(value) for value in repository_binding.get("issues") or [])
    reports = report_paths_for(project_root, task_id, audit_item)
    if queue_reports.get(task_id):
        reports = sorted(set([queue_reports[task_id], *reports]))
    if recovery and recovery.get("worker_report"):
        reports = sorted(set([str(recovery["worker_report"]), *reports]))
    branch_name_warnings: list[str] = []
    branch_has_claimed_task = recovered_identity or branch_mentions_task(branch, task_id) or repository_binding_valid
    if not branch_has_claimed_task:
        issues.append("branch missing claimed task_id")
    if not recovered_identity and not branch_has_claimed_task:
        branch_name_warnings.append("branch missing task_id")
    if audit_item and audit_item.get("status") != "identity_ok":
        for value in audit_item.get("issues") or []:
            issue = str(value)
            if repository_resolution.get("valid") and issue == "candidate has multiple task_ids":
                continue
            if is_branch_name_only_issue(issue):
                if branch_has_claimed_task:
                    branch_name_warnings.append(issue)
                else:
                    issues.append("branch missing claimed task_id")
                continue
            issues.append(issue)
    if not reports and provisional_identity:
        reports = [source_evidence_ref(item)]
    if not reports and not repository_binding_valid:
        issues.append("worker report missing")
    result_hint = str(item.get("worker_result") or item.get("result") or "").strip()
    if result_hint in BLOCKED_RESULTS:
        issues.append(f"worker result is not integration-ready: {result_hint}")
    if issues:
        blockers = human_blockers(item)
        if has_human_blocker(item):
            reason = "; ".join(sorted(set([*issues, *blockers])))
            return route_item(item, "needs_human", "human", reason, issues)
        route = "needs_dispatcher" if any("task" in issue and "missing" in issue for issue in issues) else "needs_worker_fix"
        owner = "auto-dispatcher" if route == "needs_dispatcher" else "worker"
        return route_item(item, route, owner, "; ".join(issues), issues)
    filtered = dict(item)
    filtered["task_ids"] = task_ids
    filtered["canonical_target_id"] = canonical
    filtered["identity_status"] = "identity_ok"
    filtered["identity_valid"] = True
    filtered["identity_recovered"] = recovered_identity
    if provisional_identity:
        filtered["identity_status"] = "provisional_source_identity"
        filtered["identity_provisional"] = True
        filtered["source_artifact_id"] = task_id
        filtered["source_artifact"] = source_evidence_ref(item)
        filtered["canonical_target_id"] = f"source-artifact:{task_id}"
    if recovery:
        filtered["identity_recovered_from"] = "clean_rebuild_plan"
        filtered["source_task_id"] = recovery.get("source_task_id")
    if reports:
        filtered["worker_report"] = reports[0]
    filtered["identity_issues"] = []
    if repository_binding_valid:
        filtered["identity_status"] = "repository_pr_identity"
        filtered["repository_pr_identity"] = repository_binding
        filtered["identity_evidence"] = repository_binding.get("evidence") or []
    if repository_resolution.get("valid"):
        filtered["identity_superseded_task_ids"] = repository_resolution.get("superseded_task_ids") or []
    if branch_name_warnings:
        filtered["identity_warnings"] = sorted(set(branch_name_warnings))
    if original_class == READY_CLASS:
        filtered["next_owner"] = "integration_batch_builder"
    return filtered


def build_report(readiness: dict[str, Any], audit: dict[str, Any], project_root: Path) -> dict[str, Any]:
    audit_by_branch = audit_candidates(audit)
    queue_tasks = queue_task_index(project_root)
    queue_reports = queue_report_paths(project_root)
    recovery_by_branch = clean_rebuild_recovery_by_branch(project_root)
    pending_clean_rebuild_by_branch = pending_clean_rebuild_source_by_branch(project_root)
    items = [
        filter_item(project_root, item, audit_by_branch, queue_tasks, queue_reports, recovery_by_branch, pending_clean_rebuild_by_branch)
        for item in readiness.get("items") or []
        if isinstance(item, dict)
    ]
    counts = Counter(str(item.get("classification") or "unknown") for item in items)
    identity_counts = Counter("identity_valid" if item.get("identity_valid") else "identity_invalid" for item in items)
    return {
        **{key: value for key, value in readiness.items() if key != "items"},
        "schema_version": max(int(readiness.get("schema_version") or 1), 1),
        "created_at": utc_now(),
        "identity_filter": {
            "source": "integration_candidate_filter.py",
            "audit_created_at": audit.get("created_at"),
            "policy": "missing task_id can become provisional source identity when safe evidence exists; no evidence, no integration candidate",
            "clean_rebuild_recovery_count": len(recovery_by_branch),
            "pending_clean_rebuild_source_count": len(pending_clean_rebuild_by_branch),
        },
        "items": items,
        "counts": dict(counts),
        "identity_counts": dict(identity_counts),
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Integration Candidate Filter",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Counts: `{json.dumps(report.get('counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        f"- Identity counts: `{json.dumps(report.get('identity_counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        "",
        "| Classification | Identity | Branch | Tasks | Reason |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in report.get("items") or []:
        tasks = ", ".join(item.get("task_ids") or []) or "-"
        lines.append(
            f"| `{item.get('classification')}` | `{str(bool(item.get('identity_valid'))).lower()}` | "
            f"`{item.get('branch')}` | `{tasks}` | {str(item.get('reason') or '').replace('|', '/')} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Filter integration candidates by canonical task identity.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--readiness")
    parser.add_argument("--audit")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    readiness_path = Path(args.readiness).resolve() if args.readiness else task_file(project_root, "pr_readiness_report.json")
    audit_path = Path(args.audit).resolve() if args.audit else task_file(project_root, "task_identity_audit.json")
    output = Path(args.output).resolve() if args.output else task_file(project_root, "pr_readiness_report.identity_filtered.json")
    report_path = Path(args.report).resolve() if args.report else task_reports_dir(project_root) / f"INTEGRATION_CANDIDATE_FILTER_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    report = build_report(load_json(readiness_path), load_json(audit_path), project_root)
    write_json(output, report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(report), encoding="utf-8")
    append_log(project_root, "pre-integrator", "integration_candidates_filtered", severity="info", counts=report["counts"], identity_counts=report["identity_counts"])

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"items: {len(report['items'])}")
        print(f"counts: {report['counts']}")
        print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
