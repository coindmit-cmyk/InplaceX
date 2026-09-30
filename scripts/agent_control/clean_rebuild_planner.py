#!/usr/bin/env python3
"""Plan deterministic clean rebuild routes for stale integration candidates."""

from __future__ import annotations

import argparse
import fnmatch
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_manager_dir


AUTO_ROUTE = "auto_clean_rebuild_possible"
AUTO_SMALL_ROUTE = "auto_clean_rebuild_small"
AUTO_MEDIUM_ROUTE = "auto_clean_rebuild_medium"
AUTO_LARGE_ROUTE = "auto_clean_rebuild_large"
AUTO_ROUTES = {AUTO_ROUTE, AUTO_SMALL_ROUTE, AUTO_MEDIUM_ROUTE, AUTO_LARGE_ROUTE}
DISPATCHER_ROUTE = "needs_dispatcher_rebuild"
HUMAN_ROUTE = "needs_human_rebuild"
SKIP_ROUTE = "not_clean_rebuild"

HIGH_RISK_PREFIXES = (
    ".github/workflows/",
    "deploy/",
    "infra/",
    "migrations/",
    "security/",
)
HIGH_RISK_KEYWORDS = (
    "credential",
    "payment",
    "permission",
    "production",
    "secret",
    "token",
)

def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_path(path: Any) -> str:
    return str(path or "").replace("\\", "/").strip()


def sample(values: list[str], limit: int = 12) -> list[str]:
    return values[:limit]


def task_ids(item: dict[str, Any]) -> list[str]:
    values = item.get("known_task_ids") or item.get("task_ids") or []
    return [str(value) for value in values if str(value or "").strip()]


def changed_paths(item: dict[str, Any]) -> list[str]:
    return [normalize_path(path) for path in item.get("changed_paths") or [] if normalize_path(path)]


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip().upper()


def queue_tasks_by_id(queue: dict[str, Any] | None) -> dict[str, dict[str, Any]]:
    if not isinstance(queue, dict):
        return {}
    return {
        task_id(task): task
        for task in queue.get("tasks") or []
        if isinstance(task, dict) and task_id(task)
    }


def repair_suggestions_by_branch(repair: dict[str, Any] | None) -> dict[str, list[str]]:
    if not isinstance(repair, dict):
        return {}
    result: dict[str, list[str]] = {}
    for item in repair.get("items") or []:
        if not isinstance(item, dict) or item.get("repair_route") != "suggest_allowed_paths":
            continue
        branch = str(item.get("branch") or "").strip()
        suggestions = [normalize_path(path) for path in item.get("suggested_allowed_paths") or [] if normalize_path(path)]
        if branch and suggestions:
            result[branch] = suggestions
    return result


def task_allowed_paths(task: dict[str, Any] | None) -> list[str]:
    if not isinstance(task, dict):
        return []
    values = task.get("allowed_paths") or []
    return [normalize_path(value) for value in values if normalize_path(value)] if isinstance(values, list) else []


def path_matches(pattern: str, path: str) -> bool:
    pattern = normalize_path(pattern)
    path = normalize_path(path)
    if not pattern:
        return False
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3].rstrip("/") + "/")
    return fnmatch.fnmatch(path, pattern)


def paths_covered_by_allowed(paths: list[str], allowed: list[str]) -> bool:
    if not paths or not allowed:
        return False
    return all(any(path_matches(pattern, path) for pattern in allowed) for path in paths)


def module_scope(path: str) -> str:
    normalized = normalize_path(path).strip("/")
    if not normalized:
        return "unknown"
    if normalized.startswith("docs/plans/tasks/"):
        return "task-docs"
    if normalized.startswith("docs/"):
        return "docs"
    return normalized.split("/", 1)[0] or "unknown"


def size_tier(path_count: int, *, small_limit: int, medium_limit: int) -> str:
    if path_count <= small_limit:
        return "small"
    if path_count <= medium_limit:
        return "medium"
    return "large"


def is_high_risk_path(path: str) -> bool:
    lowered = path.lower()
    if lowered == ".env.example":
        return False
    if lowered == ".env" or lowered.startswith(".env."):
        return True
    return lowered.startswith(HIGH_RISK_PREFIXES) or any(word in lowered for word in HIGH_RISK_KEYWORDS)


def auto_route_for(tier: str) -> str:
    if tier == "small":
        return AUTO_SMALL_ROUTE
    if tier == "medium":
        return AUTO_MEDIUM_ROUTE
    return AUTO_LARGE_ROUTE


def classify_item(
    item: dict[str, Any],
    *,
    small_limit: int,
    medium_limit: int,
    tasks_by_id: dict[str, dict[str, Any]],
    repair_by_branch: dict[str, list[str]],
) -> dict[str, Any]:
    classification = str(item.get("classification") or "")
    paths = changed_paths(item)
    ids = task_ids(item)
    reason = str(item.get("reason") or "")
    blocking: list[str] = []
    warnings: list[str] = []
    tier = size_tier(len(paths), small_limit=small_limit, medium_limit=medium_limit)
    task = tasks_by_id.get(ids[0].upper()) if len(ids) == 1 else None
    branch = str(item.get("branch") or "").strip()
    repair_allowed = repair_by_branch.get(branch, [])
    allowed = sorted(dict.fromkeys(task_allowed_paths(task) + repair_allowed))
    allowed_coverage = paths_covered_by_allowed(paths, allowed)
    modules = sorted({module_scope(path) for path in paths})

    if classification != "needs_clean_rebuild":
        return {
            "branch": item.get("branch"),
            "source_classification": classification,
            "rebuild_route": SKIP_ROUTE,
            "task_ids": ids,
            "changed_paths": paths,
            "path_count": len(paths),
            "size_tier": tier,
            "module_scopes": modules,
            "allowed_paths_covered": allowed_coverage,
            "reason": "source item is not needs_clean_rebuild",
            "blocking_reasons": [],
            "warnings": [],
            "recommended_next_action": "ignore_in_clean_rebuild_planner",
        }

    if not paths:
        blocking.append("no product paths available for rebuild")
    if len(ids) != 1:
        blocking.append("requires exactly one task_id")
    if int(item.get("noisy_path_count") or 0) > 0:
        warnings.append("source branch also contains noisy nested/upstream/worktree paths; rebuild must use product paths only")
    if len(modules) > 1 and not allowed_coverage:
        warnings.append("product payload spans multiple module scopes without full task allowed_paths coverage")
    if tier == "large" and len(modules) > 1 and not allowed_coverage:
        blocking.append("large cross-module payload needs task allowed_paths coverage")
    elif len(modules) > 4 and not allowed_coverage:
        blocking.append("payload spans too many module scopes without task allowed_paths coverage")
    risk_paths = [path for path in paths if is_high_risk_path(path)]
    if risk_paths:
        blocking.append("contains high-risk paths: " + ", ".join(sample(risk_paths, 5)))

    if risk_paths:
        route = HUMAN_ROUTE
        next_owner = "human"
        action = "human_review_before_rebuild"
    elif blocking:
        route = DISPATCHER_ROUTE
        next_owner = "auto-dispatcher"
        action = "create_fresh_task_packet_or_split"
    else:
        route = auto_route_for(tier)
        next_owner = "clean-rebuild-script"
        action = "create_clean_branch_from_current_develop_and_apply_merge_base_patch"

    return {
        "branch": item.get("branch"),
        "normalized_branch": item.get("normalized_branch"),
        "source_classification": classification,
        "rebuild_route": route,
        "next_owner": next_owner,
        "task_ids": ids,
        "canonical_target_id": item.get("canonical_target_id"),
        "changed_paths": paths,
        "path_count": len(paths),
        "size_tier": tier,
        "module_scopes": modules,
        "allowed_paths_covered": allowed_coverage,
        "allowed_paths_source": "repair_suggestion" if repair_allowed and allowed_coverage else ("task_packet" if allowed_coverage else None),
        "allowed_paths_sample": sample(allowed),
        "path_source": item.get("path_source"),
        "head_sha": item.get("head_sha"),
        "behind_base": item.get("behind_base"),
        "reason": "eligible for automatic clean rebuild" if not blocking else "; ".join(blocking),
        "blocking_reasons": blocking,
        "warnings": warnings,
        "recommended_next_action": action,
        "source_reason": reason,
    }


def build_report(
    project_root: Path,
    salvage: dict[str, Any],
    *,
    max_auto_paths: int,
    medium_auto_paths: int = 25,
    queue: dict[str, Any] | None = None,
    allowed_paths_repair: dict[str, Any] | None = None,
) -> dict[str, Any]:
    tasks_by_id = queue_tasks_by_id(queue)
    repair_by_branch = repair_suggestions_by_branch(allowed_paths_repair)
    items = [
        classify_item(
            item,
            small_limit=max_auto_paths,
            medium_limit=medium_auto_paths,
            tasks_by_id=tasks_by_id,
            repair_by_branch=repair_by_branch,
        )
        for item in salvage.get("items") or []
        if isinstance(item, dict)
    ]
    auto_by_task: dict[str, list[dict[str, Any]]] = {}
    for item in items:
        if item.get("rebuild_route") not in AUTO_ROUTES:
            continue
        ids = item.get("task_ids") or []
        if len(ids) == 1:
            auto_by_task.setdefault(str(ids[0]), []).append(item)
    for task_id, duplicates in auto_by_task.items():
        if len(duplicates) < 2:
            continue
        for item in duplicates:
            item["rebuild_route"] = DISPATCHER_ROUTE
            item["next_owner"] = "auto-dispatcher"
            item["reason"] = f"multiple auto clean rebuild candidates exist for {task_id}"
            item["blocking_reasons"] = [item["reason"]]
            item["recommended_next_action"] = "select_single_source_or_split"

    counts = Counter(str(item.get("rebuild_route") or "unknown") for item in items)
    clean_items = [item for item in items if item.get("source_classification") == "needs_clean_rebuild"]
    clean_counts = Counter(str(item.get("rebuild_route") or "unknown") for item in clean_items)
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "source": "clean_rebuild_planner.py",
        "policy": "plan clean rebuild routes only; do not mutate product code",
        "small_auto_paths": max_auto_paths,
        "medium_auto_paths": medium_auto_paths,
        "item_count": len(items),
        "needs_clean_rebuild_count": len(clean_items),
        "counts": dict(counts),
        "clean_rebuild_counts": dict(clean_counts),
        "items": items,
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Clean Rebuild Plan",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Items: `{report.get('item_count')}`",
        f"- Needs clean rebuild: `{report.get('needs_clean_rebuild_count')}`",
        f"- Counts: `{json.dumps(report.get('clean_rebuild_counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        f"- Small auto paths: `{report.get('small_auto_paths')}`",
        f"- Medium auto paths: `{report.get('medium_auto_paths')}`",
        "",
        "| Route | Tier | Branch | Tasks | Paths | Reason |",
        "| --- | --- | --- | --- | ---: | --- |",
    ]
    for item in report.get("items") or []:
        if item.get("source_classification") != "needs_clean_rebuild":
            continue
        tasks = ", ".join(item.get("task_ids") or []) or "-"
        reason = str(item.get("reason") or "").replace("|", "/")
        lines.append(
            f"| `{item.get('rebuild_route')}` | `{item.get('size_tier')}` | `{item.get('branch')}` | `{tasks}` | "
            f"{item.get('path_count') or 0} | {reason} |"
        )
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--salvage")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--queue")
    parser.add_argument("--allowed-paths-repair")
    parser.add_argument("--max-auto-paths", type=int, default=8, help="Small auto tier path limit. Kept for backward compatibility.")
    parser.add_argument("--medium-auto-paths", type=int, default=25, help="Medium auto tier path limit. Larger valid items become large auto candidates.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    salvage_path = Path(args.salvage).resolve() if args.salvage else plans / "candidate_salvage_audit.json"
    queue_path = Path(args.queue).resolve() if args.queue else plans / "task_queue.json"
    repair_path = Path(args.allowed_paths_repair).resolve() if args.allowed_paths_repair else plans / "allowed_paths_repair_plan.json"
    output_path = Path(args.output).resolve() if args.output else plans / "clean_rebuild_plan.json"
    report_path = Path(args.report).resolve() if args.report else plans / "reports" / f"CLEAN_REBUILD_PLAN_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    report = build_report(
        project_root,
        load_json(salvage_path),
        max_auto_paths=args.max_auto_paths,
        medium_auto_paths=args.medium_auto_paths,
        queue=load_json(queue_path),
        allowed_paths_repair=load_json(repair_path),
    )
    write_json(output_path, report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(report), encoding="utf-8")
    append_log(project_root, "pre-integrator", "clean_rebuild_planned", severity="info", counts=report["clean_rebuild_counts"])

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"needs_clean_rebuild: {report['needs_clean_rebuild_count']}")
        print(f"counts: {report['clean_rebuild_counts']}")
        print(f"written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
