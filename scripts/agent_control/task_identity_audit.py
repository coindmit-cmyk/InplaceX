#!/usr/bin/env python3
"""Audit task identity across queue rows and integration candidates."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file, task_reports_dir
from repository_pr_identity import resolve_repository_pr_task_ids, validate_repository_pr_identity


TASK_ID_RE = re.compile(r"(?<![A-Z0-9])([A-Z][A-Z0-9]{0,12}-[0-9][0-9A-Z]*(?:\.[0-9A-Z]+)*|[A-Z]{1,6}[0-9][A-Z0-9]*(?:\.[0-9A-Z]+)*)(?![A-Z0-9])", re.IGNORECASE)
SERVICE_TOKEN_RE = re.compile(
    r"^(?:P[0-9]+|WORKER-[0-9]+(?:\.[0-9]+)?|BATCH-[0-9]{8}|[A-Z]+-[0-9]{8}T[0-9A-Z]*|[0-9]{8}T[0-9A-Z]*)$",
    re.IGNORECASE,
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def canonical_target(task_id_value: str) -> str:
    return f"task:{task_id_value}" if task_id_value else ""


def is_service_token(value: str) -> bool:
    return bool(SERVICE_TOKEN_RE.match(value.strip()))


def infer_task_ids(*values: Any, known_ids: set[str] | None = None) -> list[str]:
    known_upper = {item.upper() for item in known_ids or set() if item}
    known_found: set[str] = set()
    found: set[str] = set()
    for value in values:
        if isinstance(value, (list, tuple, set)):
            nested = infer_task_ids(*value, known_ids=known_ids)
            known_found.update(item for item in nested if item.upper() in known_upper)
            found.update(nested)
            continue
        text = str(value or "")
        upper_text = text.upper()
        for known in known_upper:
            if known and known in upper_text:
                known_found.add(known)
        for match in TASK_ID_RE.findall(text):
            token = match.upper()
            if not is_service_token(token):
                found.add(token)
    if known_upper:
        return sorted(known_found or (found & known_upper))
    return sorted(found)


def report_paths(project_root: Path, task_id_value: str) -> list[str]:
    if not task_id_value:
        return []
    roots = [project_root / "docs/reports", task_reports_dir(project_root), task_reports_dir(project_root) / "workers"]
    matches: list[str] = []
    for root in roots:
        if root.exists():
            for path in root.rglob(f"*{task_id_value}*"):
                if path.is_file():
                    matches.append(path.relative_to(project_root).as_posix())
    return sorted(set(matches))


def branch_has_task_id(branch: str, task_id_value: str) -> bool:
    return bool(branch and task_id_value and task_id_value.upper() in branch.upper())


def task_lock_state(task: dict[str, Any]) -> str:
    value = task.get("lock")
    if isinstance(value, dict):
        return str(value.get("state") or value.get("status") or "").strip()
    return str(value or "").strip()


def repository_pr_binding_required(task: dict[str, Any]) -> bool:
    status = str(task.get("status") or "").strip()
    integration_status = str(task.get("integration_status") or "").strip()
    decision = str(task.get("dispatcher_decision") or "").strip()
    route = str(task.get("repository_hygiene_route") or "").strip()
    return (
        route == "dispatcher_integration"
        or status in {"agent_done", "review", "integration_ready", "integration_requested"}
        or integration_status in {"pending", "integration_ready"}
        or decision == "integration_ready"
    )


def queue_task_ids(queue: dict[str, Any]) -> set[str]:
    return {
        task_id(task).upper()
        for task in queue.get("tasks") or []
        if isinstance(task, dict) and task_id(task)
    }


def queue_task_index(queue: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        task_id(task).upper(): task
        for task in queue.get("tasks") or []
        if isinstance(task, dict) and task_id(task)
    }


def normalize_branch(ref: Any) -> str:
    value = str(ref or "").strip()
    if value.startswith("refs/remotes/origin/"):
        return value.removeprefix("refs/remotes/origin/")
    if value.startswith("refs/heads/"):
        return value.removeprefix("refs/heads/")
    if value.startswith("origin/"):
        return value.removeprefix("origin/")
    return value


def clean_rebuild_recovery_by_branch(project_root: Path, queue: dict[str, Any]) -> dict[str, dict[str, Any]]:
    plan = load_json(task_file(project_root, "clean_rebuild_plan.json"))
    if not isinstance(plan, dict):
        return {}
    crb_by_source: dict[tuple[str, str], str] = {}
    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        current = task_id(task)
        branch = normalize_branch(task.get("clean_rebuild_source_branch") or task.get("source_branch"))
        head = str(task.get("clean_rebuild_source_head_sha") or task.get("source_head_sha") or "").strip()
        if current and (branch or head):
            crb_by_source[(branch, head)] = current

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
        recovered_id = crb_by_source.get((branch, head)) or crb_by_source.get((branch, "")) or ids[0]
        result[branch] = {
            "task_id": recovered_id,
            "source_task_id": ids[0],
            "worker_report": "AiStudio/Task_manager/clean_rebuild_plan.json",
            "source_branch": branch,
            "source_head_sha": head,
        }
    return result


def audit_queue(project_root: Path, queue: dict[str, Any], locks: dict[str, Any]) -> list[dict[str, Any]]:
    lock_ids = {
        str(lock.get("task_id") or "").strip()
        for lock in locks.get("locks", [])
        if isinstance(lock, dict) and lock.get("task_id")
    }
    seen: dict[str, int] = defaultdict(int)
    known_ids = queue_task_ids(queue)
    rows: list[dict[str, Any]] = []
    for index, task in enumerate(queue.get("tasks") or []):
        if not isinstance(task, dict):
            continue
        tid = task_id(task)
        if tid:
            seen[tid] += 1
        branch = str(task.get("branch") or task.get("github_branch") or "")
        repository_binding = validate_repository_pr_identity(task, candidate_branch=branch)
        inferred = infer_task_ids(branch, task.get("title"), task.get("last_agent_report"), task.get("worker_report"), known_ids=known_ids)
        reports = report_paths(project_root, tid)
        issues: list[str] = []
        status = "identity_ok"
        if not tid:
            status = "identity_recoverable" if inferred else "identity_missing"
            issues.append("missing task_id")
        elif repository_binding.get("applicable") and repository_pr_binding_required(task):
            if not repository_binding.get("valid"):
                status = "identity_conflict"
                issues.extend(str(value) for value in repository_binding.get("issues") or [])
        elif len(inferred) > 1 and tid.upper() not in inferred:
            status = "identity_conflict"
            issues.append("branch/report/title evidence points to another task id")
        elif branch and not branch_has_task_id(branch, tid):
            status = "identity_conflict"
            issues.append("branch missing task_id")
        if tid and seen[tid] > 1:
            status = "duplicate_identity"
            issues.append("duplicate task id in queue")
        if tid and task_lock_state(task) in {"locked", "in_progress", "review"} and tid not in lock_ids:
            issues.append("task lock state has no matching agent_locks row")
        if tid and not (reports or task.get("worker_report") or task.get("last_agent_report")) and str(task.get("status") or "") in {"agent_done", "review", "integration_ready"}:
            issues.append("completed/review task has no worker report evidence")
        rows.append(
            {
                "kind": "queue_task",
                "index": index,
                "task_id": tid,
                "canonical_target_id": canonical_target(tid),
                "status": status,
                "task_status": task.get("status"),
                "branch": branch or None,
                "github_pr": task.get("github_pr"),
                "worker_report": task.get("worker_report"),
                "report_paths": reports,
                "inferred_task_ids": inferred,
                "repository_pr_identity": repository_binding if repository_binding.get("applicable") else None,
                "issues": issues,
                "next_owner": "dispatcher" if status in {"identity_missing", "identity_recoverable", "identity_conflict", "duplicate_identity"} else None,
            }
        )
    return rows


def cleanup_candidate_without_product_identity(item: dict[str, Any]) -> bool:
    if str(item.get("classification") or "") != "cleanup_candidate":
        return False
    if item.get("task_ids"):
        return False
    changed_paths = item.get("changed_paths")
    if isinstance(changed_paths, list) and changed_paths:
        return False
    return str(item.get("code_payload_status") or "") in {"not_code_payload", "coordination_only"}


def audit_candidates(
    project_root: Path,
    readiness: dict[str, Any],
    queue_ids: set[str],
    queue_tasks: dict[str, dict[str, Any]],
    queue_reports: dict[str, str],
    recovery_by_branch: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for index, item in enumerate(readiness.get("items") or []):
        if not isinstance(item, dict):
            continue
        task_ids = [str(value) for value in item.get("task_ids") or [] if value]
        branch = str(item.get("branch") or "")
        recovery = recovery_by_branch.get(normalize_branch(branch))
        if not task_ids and recovery:
            task_ids = [str(recovery["task_id"])]
        repository_resolution = resolve_repository_pr_task_ids(
            task_ids,
            queue_tasks,
            candidate_branch=branch,
            candidate_pr=item.get("pr"),
            candidate_head_sha=item.get("head_sha"),
        )
        if repository_resolution.get("valid"):
            task_ids = [str(repository_resolution["selected_task_id"])]
        inferred = infer_task_ids(item.get("branch"), item.get("reason"), item.get("evidence"), item.get("warnings"), known_ids=queue_ids)
        tid = task_ids[0] if len(task_ids) == 1 else ""
        repository_binding = validate_repository_pr_identity(
            queue_tasks.get(tid.upper()),
            candidate_branch=branch,
            candidate_pr=item.get("pr"),
            candidate_head_sha=item.get("head_sha"),
            require_candidate_pr=True,
            require_candidate_head=True,
        )
        reports = report_paths(project_root, tid)
        queue_report = queue_reports.get(tid)
        if queue_report:
            reports = sorted(set([queue_report, *reports]))
        if recovery and recovery.get("worker_report"):
            reports = sorted(set([str(recovery["worker_report"]), *reports]))
        issues: list[str] = []
        status = "identity_ok"
        if not task_ids:
            if cleanup_candidate_without_product_identity(item):
                status = "cleanup_identity_not_required"
                issues.append("cleanup candidate has no product task identity")
            else:
                status = "identity_recoverable" if len(inferred) == 1 else "identity_missing"
                issues.append("candidate missing task_id")
        elif len(task_ids) > 1:
            status = "identity_conflict"
            issues.append("candidate has multiple task_ids")
        elif task_ids[0] not in queue_ids:
            status = "identity_conflict"
            issues.append("candidate task_id not present in queue")
        elif repository_binding.get("applicable"):
            if not repository_binding.get("valid"):
                status = "identity_conflict"
                issues.extend(str(value) for value in repository_binding.get("issues") or [])
        elif item.get("branch") and not recovery and not branch_has_task_id(str(item.get("branch")), task_ids[0]):
            status = "identity_conflict"
            issues.append("candidate branch missing task_id")
        if tid and not reports and not repository_binding.get("valid"):
            issues.append("worker report file not found")
        rows.append(
            {
                "kind": "integration_candidate",
                "index": index,
                "task_id": tid,
                "canonical_target_id": canonical_target(tid),
                "status": status,
                "classification": item.get("classification"),
                "branch": item.get("branch"),
                "pr": item.get("pr"),
                "report_paths": reports,
                "inferred_task_ids": inferred,
                "identity_recovered_from": "clean_rebuild_plan" if recovery else None,
                "source_task_id": recovery.get("source_task_id") if recovery else None,
                "repository_pr_identity": repository_binding if repository_binding.get("applicable") else None,
                "identity_superseded_task_ids": repository_resolution.get("superseded_task_ids") or [],
                "issues": issues,
                "next_owner": "dispatcher" if status not in {"identity_ok", "cleanup_identity_not_required"} else None,
            }
        )
    return rows


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Task Identity Audit",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Project: `{report.get('project_root')}`",
        f"- Counts: `{json.dumps(report.get('counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        "",
        "| Status | Kind | Task | Branch/PR | Issues |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in report.get("items") or []:
        target = item.get("branch") or item.get("pr") or "-"
        issues = "; ".join(item.get("issues") or []) or "-"
        lines.append(f"| `{item.get('status')}` | `{item.get('kind')}` | `{item.get('task_id') or '-'}` | `{target}` | {issues.replace('|', '/')} |")
    lines.append("")
    return "\n".join(lines)


def build_report(project_root: Path, queue_path: Path, locks_path: Path, readiness_path: Path | None) -> dict[str, Any]:
    queue = load_json(queue_path) or {"tasks": []}
    locks = load_json(locks_path) or {"locks": []}
    readiness = load_json(readiness_path) if readiness_path and readiness_path.exists() else {}
    queue_items = audit_queue(project_root, queue, locks)
    queue_ids = {item["task_id"] for item in queue_items if item.get("task_id")}
    queue_tasks = queue_task_index(queue)
    queue_reports = {
        task_id(task): str(task.get("worker_report") or task.get("last_agent_report") or "")
        for task in queue.get("tasks") or []
        if isinstance(task, dict) and task_id(task) and (task.get("worker_report") or task.get("last_agent_report"))
    }
    recovery_by_branch = clean_rebuild_recovery_by_branch(project_root, queue)
    candidate_items = audit_candidates(project_root, readiness or {}, queue_ids, queue_tasks, queue_reports, recovery_by_branch)
    items = queue_items + candidate_items
    counts = Counter(str(item.get("status") or "unknown") for item in items)
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "queue": str(queue_path),
        "locks": str(locks_path),
        "readiness": str(readiness_path) if readiness_path else None,
        "clean_rebuild_recovery_count": len(recovery_by_branch),
        "counts": dict(counts),
        "items": items,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit task identity across queue and integration candidates.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue")
    parser.add_argument("--locks")
    parser.add_argument("--readiness")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")
    readiness_path = Path(args.readiness).resolve() if args.readiness else task_file(project_root, "pr_readiness_report.json")
    output = Path(args.output).resolve() if args.output else task_file(project_root, "task_identity_audit.json")
    report_path = Path(args.report).resolve() if args.report else task_reports_dir(project_root) / f"TASK_IDENTITY_AUDIT_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    report = build_report(project_root, queue_path, locks_path, readiness_path)
    write_json(output, report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(report), encoding="utf-8")
    append_log(project_root, "identity", "task_identity_audit", severity="info", counts=report["counts"])

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"items: {len(report['items'])}")
        print(f"counts: {report['counts']}")
        print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
