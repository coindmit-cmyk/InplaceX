#!/usr/bin/env python3
"""Build a small deterministic integration batch from readiness classifications."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_file, task_reports_dir


READY_CLASSES = {"ready_candidate", "salvage_ready", "needs_checks", "needs_integrator_review"}
DIRECT_READY_CLASSES = {"ready_candidate", "salvage_ready"}
CLASSIFICATION_ORDER = {
    "ready_candidate": 0,
    "salvage_ready": 1,
    "needs_checks": 2,
    "needs_integrator_review": 3,
}
ROUTING_LISTS = {
    "needs_human": "needs_human",
    "blocked": "blocked",
    "needs_rebase": "needs_rework",
    "needs_checks": "needs_rework",
    "needs_integrator_review": "needs_rework",
    "draft_only": "needs_rework",
    "coordination_only": "excluded_from_package",
    "duplicate": "cleanup_candidates",
    "cleanup_candidate": "cleanup_candidates",
    "needs_dispatcher": "needs_dispatcher",
    "needs_worker_fix": "needs_worker_fix",
    "needs_architect": "needs_architect",
}
RISK_ORDER = {"low": 0, "medium": 1, "high": 2}
DEFAULT_MAX_NORMAL = 12
DEFAULT_MAX_LOW_RISK = 20
DEFAULT_MAX_HIGH_RISK = 4
DEFAULT_MAX_MODULES = 4
DEFAULT_MAX_TASKS_PER_MODULE = 8


def normalize_module(value: Any) -> str:
    text = str(value or "").strip().lower().replace("\\", "/")
    return text or "unknown"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def item_key(item: dict[str, Any]) -> str:
    task_ids = item.get("task_ids")
    if isinstance(task_ids, list) and task_ids:
        return str(task_ids[0])
    return str(item.get("branch") or item.get("pr") or item.get("head_sha") or "unknown")


def path_set(item: dict[str, Any]) -> set[str]:
    paths = item.get("changed_paths") or []
    return {str(path) for path in paths if path}


def module_from_path(path: str) -> str:
    normalized = path.replace("\\", "/").strip("/")
    if not normalized:
        return "unknown"
    if normalized.startswith(".agent/"):
        return "agent"
    if normalized.startswith(("docs/plans/reports/", "docs/reports/", "AiStudio/Task_manager/reports/")):
        return "reports"
    return normalized.split("/", 1)[0] or "unknown"


def module_scope(item: dict[str, Any]) -> str:
    explicit = normalize_module(item.get("module_scope"))
    if explicit and explicit != "unknown":
        return explicit
    paths = item.get("changed_paths") or []
    modules = sorted({module_from_path(str(path)) for path in paths if path})
    if len(modules) == 1:
        return modules[0]
    if len(modules) > 1:
        return "multi:" + "+".join(modules)
    return "unknown"


def batch_limit_for(args: argparse.Namespace, risk: str) -> int:
    if risk == "high":
        return args.max_high_risk
    if risk == "low":
        return args.max_low_risk
    return args.max_normal


def arg_bool(args: argparse.Namespace, name: str, default: bool) -> bool:
    return bool(getattr(args, name, default))


def slim_item(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "branch": item.get("branch"),
        "pr": item.get("pr"),
        "pr_url": item.get("pr_url"),
        "head_sha": item.get("head_sha"),
        "task_ids": item.get("task_ids") or [],
        "canonical_target_id": item.get("canonical_target_id"),
        "classification": item.get("classification"),
        "readiness_blocker_type": item.get("readiness_blocker_type"),
        "code_payload_status": item.get("code_payload_status"),
        "risk_class": item.get("risk_class"),
        "module_scope": item.get("module_scope") or module_scope(item),
        "changed_paths": item.get("changed_paths") or [],
        "coordination_changed_paths": item.get("coordination_changed_paths") or [],
        "reason": item.get("reason"),
        "blocking_reasons": item.get("blocking_reasons") or [],
        "warnings": item.get("warnings") or [],
        "identity_status": item.get("identity_status"),
        "identity_valid": item.get("identity_valid"),
        "identity_provisional": item.get("identity_provisional"),
        "identity_issues": item.get("identity_issues") or [],
        "worker_report": item.get("worker_report"),
        "check_state": item.get("check_state"),
        "is_draft": item.get("is_draft"),
        "source_artifact": item.get("source_artifact"),
        "source_artifact_id": item.get("source_artifact_id"),
        "merge_target_allowed": item.get("merge_target_allowed"),
        "behind_base": item.get("behind_base"),
        "next_owner": item.get("next_owner"),
        "migration_sensitive": item.get("migration_sensitive"),
        "migration_compatibility_policy": item.get("migration_compatibility_policy"),
        "integrator_must_adapt_migrations": item.get("integrator_must_adapt_migrations"),
    }


def exclusion(item: dict[str, Any], reason: str, route: str | None = None) -> dict[str, Any]:
    return {
        "item": slim_item(item),
        "reason": reason,
        "route": route or ROUTING_LISTS.get(str(item.get("classification") or ""), "excluded_from_package"),
    }


def build_batch(report: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    included: list[dict[str, Any]] = []
    excluded: list[dict[str, Any]] = []
    selected_paths: set[str] = set()
    seen_tasks: set[str] = set()
    selected_modules: set[str] = set()
    terminal_task_ids = {str(value) for value in report.get("terminal_task_ids") or [] if value}
    manual_review_task_ids = {str(value) for value in report.get("manual_review_task_ids") or [] if value}

    items = [item for item in report.get("items") or [] if isinstance(item, dict)]
    include_high_risk = arg_bool(args, "include_high_risk", True)
    direct_ready_available = any(
        str(item.get("classification") or "") in DIRECT_READY_CLASSES
        and not ({str(task) for task in item.get("task_ids") or [] if task} & terminal_task_ids)
        and not ({str(task) for task in item.get("task_ids") or [] if task} & manual_review_task_ids)
        and bool(path_set(item))
        and (str(item.get("risk_class") or "medium") != "high" or include_high_risk)
        and batch_limit_for(args, str(item.get("risk_class") or "medium")) > 0
        for item in items
    )
    ready_module_counts = Counter(
        module_scope(item)
        for item in items
        if isinstance(item, dict) and str(item.get("classification") or "") in READY_CLASSES
    )
    ordered = sorted(
        items,
        key=lambda item: (
            CLASSIFICATION_ORDER.get(str(item.get("classification") or ""), len(CLASSIFICATION_ORDER)),
            -ready_module_counts.get(module_scope(item), 0),
            RISK_ORDER.get(str(item.get("risk_class") or "medium"), 1),
            len(item.get("changed_paths") or []),
            str(item.get("branch") or ""),
        ),
    )

    for item in ordered:
        classification = str(item.get("classification") or "")
        risk = str(item.get("risk_class") or "medium")
        module = module_scope(item)
        paths = path_set(item)
        task_ids = {str(task) for task in item.get("task_ids") or [] if task}

        if task_ids & terminal_task_ids:
            excluded.append(exclusion(item, "task already terminal in central queue", "cleanup_candidates"))
            continue
        if task_ids & manual_review_task_ids:
            excluded.append(exclusion(item, "task already routed to manual Integrator review in central queue", "excluded_from_package"))
            continue
        if classification not in READY_CLASSES:
            excluded.append(exclusion(item, f"classification {classification} is not batch-ready"))
            continue
        if not paths:
            excluded.append(exclusion(item, "ready candidate has no changed_paths", "needs_dispatcher"))
            continue
        if risk == "high" and not include_high_risk:
            excluded.append(exclusion(item, "high-risk item deferred by explicit batch policy", "deferred_next_batch"))
            continue
        if direct_ready_available and classification not in DIRECT_READY_CLASSES:
            excluded.append(
                exclusion(
                    item,
                    "directly routed ready candidates preempt salvage and manual-review backlog",
                    "deferred_next_batch",
                )
            )
            continue
        if task_ids & seen_tasks:
            excluded.append(exclusion(item, "task already represented in selected batch", "duplicate"))
            continue
        max_modules = int(getattr(args, "max_modules", DEFAULT_MAX_MODULES) or DEFAULT_MAX_MODULES)
        if module not in selected_modules and len(selected_modules) >= max_modules:
            excluded.append(exclusion(item, f"deferred to next batch for module {module}", "deferred_next_batch"))
            continue
        allow_overlap = arg_bool(args, "allow_overlap", True)
        if paths & selected_paths and not allow_overlap:
            excluded.append(exclusion(item, "changed-path overlap with selected batch", "needs_rework"))
            continue

        limit = batch_limit_for(args, risk)
        if len(included) >= limit:
            excluded.append(exclusion(item, f"deferred to next batch: size limit reached for {risk} risk", "deferred_next_batch"))
            continue
        max_tasks_per_module = int(getattr(args, "max_tasks_per_module", DEFAULT_MAX_TASKS_PER_MODULE) or DEFAULT_MAX_TASKS_PER_MODULE)
        module_included = [existing for existing in included if module_scope(existing) == module]
        if len(module_included) >= max_tasks_per_module:
            excluded.append(exclusion(item, f"deferred to next batch: module limit reached for {module}", "deferred_next_batch"))
            continue

        included.append(slim_item(item))
        selected_modules.add(module)
        selected_paths.update(paths)
        seen_tasks.update(task_ids)

    risk_class = "low"
    if any(item.get("risk_class") == "high" for item in included):
        risk_class = "high"
    elif any(item.get("risk_class") == "medium" for item in included):
        risk_class = "medium"

    excluded_counts = Counter(str(item.get("route") or "excluded_from_package") for item in excluded)
    deferred_count = int(excluded_counts.get("deferred_next_batch", 0))
    return {
        "schema_version": 1,
        "batch_id": f"BATCH-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}",
        "created_at": utc_now(),
        "target_branch": report.get("base_branch") or "develop",
        "base_sha": report.get("base_sha"),
        "risk_class": risk_class,
        "strategy": "auto_integrator_review_batch",
        "batch_policy": {
            "max_modules": int(getattr(args, "max_modules", DEFAULT_MAX_MODULES) or DEFAULT_MAX_MODULES),
            "max_tasks_per_module": int(getattr(args, "max_tasks_per_module", DEFAULT_MAX_TASKS_PER_MODULE) or DEFAULT_MAX_TASKS_PER_MODULE),
            "include_high_risk": arg_bool(args, "include_high_risk", True),
            "allow_overlap": arg_bool(args, "allow_overlap", True),
            "direct_ready_preemption": direct_ready_available,
            "module_scopes": sorted(selected_modules),
        },
        "included": included,
        "excluded": excluded,
        "included_count": len(included),
        "excluded_count": len(excluded),
        "deferred_count": deferred_count,
        "excluded_counts": dict(excluded_counts),
        "checks_required": ["integrator_preflight", "pr_readiness_classifier", "git diff --check"],
        "handoff_ready": bool(included),
    }


def render_markdown(batch: dict[str, Any]) -> str:
    lines = [
        "# Integration Batch",
        "",
        f"- Batch: `{batch.get('batch_id')}`",
        f"- Created: `{batch.get('created_at')}`",
        f"- Base: `{batch.get('target_branch')}` `{batch.get('base_sha')}`",
        f"- Included: `{batch.get('included_count')}`",
        f"- Excluded: `{batch.get('excluded_count')}`",
        f"- Deferred next batch: `{batch.get('deferred_count', 0)}`",
        f"- Handoff ready: `{batch.get('handoff_ready')}`",
        "",
        "## Included",
        "",
        "| Risk | Branch | Tasks | Paths |",
        "| --- | --- | --- | --- |",
    ]
    for item in batch.get("included") or []:
        tasks = ", ".join(item.get("task_ids") or []) or "-"
        paths = ", ".join(item.get("changed_paths") or []) or "-"
        lines.append(f"| `{item.get('risk_class')}` | `{item.get('branch')}` | `{tasks}` | {paths.replace('|', '/')} |")
    lines.extend(["", "## Excluded", "", "| Route | Branch | Reason |", "| --- | --- | --- |"])
    for item in batch.get("excluded") or []:
        branch = ((item.get("item") or {}).get("branch")) if isinstance(item, dict) else ""
        lines.append(f"| `{item.get('route')}` | `{branch}` | {str(item.get('reason') or '').replace('|', '/')} |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build a small safe integration batch.")
    parser.add_argument("--readiness", required=True, help="Path to pr_readiness_report.json.")
    parser.add_argument("--project-root", help="Project root, used for default output paths and process log.")
    parser.add_argument("--output", help="Output JSON path. Defaults to <project-root>/AiStudio/Task_manager/integration_batch.json.")
    parser.add_argument("--report", help="Optional Markdown report path.")
    parser.add_argument("--max-normal", type=int, default=DEFAULT_MAX_NORMAL)
    parser.add_argument("--max-low-risk", type=int, default=DEFAULT_MAX_LOW_RISK)
    parser.add_argument("--max-high-risk", type=int, default=DEFAULT_MAX_HIGH_RISK)
    parser.add_argument("--max-modules", type=int, default=DEFAULT_MAX_MODULES, help="Maximum distinct module scopes in one integration batch.")
    parser.add_argument("--max-tasks-per-module", type=int, default=DEFAULT_MAX_TASKS_PER_MODULE, help="Maximum ready tasks from the selected module scope.")
    parser.add_argument("--include-high-risk", dest="include_high_risk", action="store_true", default=True, help="Include high-risk Integrator-review candidates in the batch.")
    parser.add_argument("--exclude-high-risk", dest="include_high_risk", action="store_false", help="Defer high-risk candidates to a later explicit review batch.")
    parser.add_argument("--allow-overlap", dest="allow_overlap", action="store_true", default=True, help="Allow Integrator to receive overlapping path candidates for review/resolution.")
    parser.add_argument("--disallow-overlap", dest="allow_overlap", action="store_false", help="Defer overlapping changed paths instead of asking Integrator to resolve them.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    readiness = load_json(Path(args.readiness).resolve())
    batch = build_batch(readiness, args)
    project_root = Path(args.project_root).resolve() if args.project_root else None
    output = Path(args.output).resolve() if args.output else (task_file(project_root, "integration_batch.json") if project_root else None)
    if output:
        write_json(output, batch)
    if args.report:
        report_path = Path(args.report).resolve()
    elif project_root:
        report_path = task_reports_dir(project_root) / f"INTEGRATION_BATCH_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"
    else:
        report_path = None
    if report_path:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(render_markdown(batch), encoding="utf-8")
    if project_root:
        append_log(project_root, "pre-integrator", "integration_batch_built", severity="info", included=batch["included_count"], excluded=batch["excluded_count"])

    if args.json:
        print(json.dumps(batch, ensure_ascii=False, indent=2))
    else:
        print(f"included: {batch['included_count']}")
        print(f"excluded: {batch['excluded_count']}")
        print(f"handoff_ready: {batch['handoff_ready']}")
        if output:
            print(f"written: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
