#!/usr/bin/env python3
"""Suggest allowed_paths repairs for clean rebuild candidates."""

from __future__ import annotations

import argparse
import fnmatch
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from process_log import append_log
from project_paths import task_manager_dir


SUGGEST = "suggest_allowed_paths"
ALREADY_COVERED = "already_covered"
NEEDS_SPLIT = "needs_split"
NEEDS_HUMAN = "needs_human"
NOT_APPLICABLE = "not_applicable"

HIGH_RISK_PREFIXES = (".github/workflows/", "deploy/", "infra/", "migrations/", "security/")
HIGH_RISK_KEYWORDS = ("credential", "payment", "permission", "production", "secret", "token")


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


def task_ids(item: dict[str, Any]) -> list[str]:
    values = item.get("known_task_ids") or item.get("task_ids") or []
    return [str(value).upper() for value in values if str(value or "").strip()]


def changed_paths(item: dict[str, Any]) -> list[str]:
    return [normalize_path(path) for path in item.get("changed_paths") or [] if normalize_path(path)]


def as_list(value: Any) -> list[str]:
    return [normalize_path(item) for item in value or [] if normalize_path(item)] if isinstance(value, list) else []


def path_matches(pattern: str, path: str) -> bool:
    pattern = normalize_path(pattern)
    path = normalize_path(path)
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3].rstrip("/") + "/")
    return fnmatch.fnmatch(path, pattern)


def paths_covered_by_allowed(paths: list[str], allowed: list[str]) -> bool:
    return bool(paths) and bool(allowed) and all(any(path_matches(pattern, path) for pattern in allowed) for path in paths)


def matches_any(path: str, patterns: list[str]) -> bool:
    return any(path_matches(pattern, path) for pattern in patterns)


def is_high_risk_path(path: str) -> bool:
    lowered = path.lower()
    if lowered == ".env.example":
        return False
    if lowered == ".env" or lowered.startswith(".env."):
        return True
    return lowered.startswith(HIGH_RISK_PREFIXES) or any(word in lowered for word in HIGH_RISK_KEYWORDS)


def module_scope(path: str) -> str:
    normalized = normalize_path(path).strip("/")
    if not normalized:
        return "unknown"
    if "/" not in normalized:
        return "root"
    if normalized.startswith("docs/plans/tasks/"):
        return "task-docs"
    if normalized.startswith("docs/"):
        return "docs"
    return normalized.split("/", 1)[0] or "unknown"


def suggest_allowed_paths(paths: list[str]) -> list[str]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for path in paths:
        grouped[module_scope(path)].append(path)

    suggestions: list[str] = []
    for module, module_paths in sorted(grouped.items()):
        if module in {"root", "docs", "task-docs"}:
            suggestions.extend(sorted(module_paths))
            continue
        if len(module_paths) >= 3:
            suggestions.append(f"{module}/**")
        else:
            suggestions.extend(sorted(module_paths))
    return sorted(dict.fromkeys(suggestions))


def classify_item(item: dict[str, Any], tasks_by_id: dict[str, dict[str, Any]]) -> dict[str, Any]:
    route = str(item.get("rebuild_route") or "")
    ids = task_ids(item)
    paths = changed_paths(item)
    modules = sorted({module_scope(path) for path in paths})
    task = tasks_by_id.get(ids[0]) if len(ids) == 1 else None
    existing_allowed = as_list((task or {}).get("allowed_paths"))
    forbidden = as_list((task or {}).get("forbidden_paths"))
    risk_paths = [path for path in paths if is_high_risk_path(path)]
    forbidden_hits = [path for path in paths if matches_any(path, forbidden)]
    suggested = suggest_allowed_paths(paths)

    if route not in {"needs_dispatcher_rebuild", "auto_clean_rebuild_small", "auto_clean_rebuild_medium", "auto_clean_rebuild_large"}:
        repair_route = NOT_APPLICABLE
        reason = "source rebuild route does not need allowed_paths repair"
    elif len(ids) != 1:
        repair_route = NEEDS_SPLIT
        reason = "requires exactly one task_id before allowed_paths can be repaired"
    elif risk_paths or forbidden_hits:
        repair_route = NEEDS_HUMAN
        reason = "high-risk or forbidden paths require human review"
    elif paths_covered_by_allowed(paths, existing_allowed):
        repair_route = ALREADY_COVERED
        reason = "existing task allowed_paths already cover product payload"
    elif not paths:
        repair_route = NEEDS_SPLIT
        reason = "no product paths available for allowed_paths repair"
    else:
        repair_route = SUGGEST
        reason = "suggested allowed_paths inferred from product payload"

    return {
        "branch": item.get("branch"),
        "task_ids": ids,
        "canonical_target_id": item.get("canonical_target_id"),
        "source_rebuild_route": route,
        "repair_route": repair_route,
        "reason": reason,
        "changed_paths": paths,
        "path_count": len(paths),
        "module_scopes": modules,
        "existing_allowed_paths": existing_allowed,
        "suggested_allowed_paths": suggested if repair_route == SUGGEST else [],
        "forbidden_paths": forbidden,
        "forbidden_hits": forbidden_hits,
        "high_risk_paths": risk_paths,
        "recommended_next_action": (
            "use_suggested_allowed_paths_for_clean_rebuild_planning"
            if repair_route == SUGGEST
            else "route_to_owner"
        ),
    }


def build_report(project_root: Path, clean_plan: dict[str, Any], queue: dict[str, Any]) -> dict[str, Any]:
    tasks_by_id = queue_tasks_by_id(queue)
    items = [
        classify_item(item, tasks_by_id)
        for item in clean_plan.get("items") or []
        if isinstance(item, dict)
    ]
    counts = Counter(str(item.get("repair_route") or "unknown") for item in items)
    return {
        "schema_version": 1,
        "created_at": utc_now(),
        "project_root": str(project_root),
        "source": "allowed_paths_repair_planner.py",
        "policy": "suggest allowed_paths only; do not mutate task_queue",
        "item_count": len(items),
        "counts": dict(counts),
        "items": items,
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Allowed Paths Repair Plan",
        "",
        f"- Generated: `{report.get('created_at')}`",
        f"- Items: `{report.get('item_count')}`",
        f"- Counts: `{json.dumps(report.get('counts') or {}, ensure_ascii=False, sort_keys=True)}`",
        "",
        "| Repair | Source route | Branch | Tasks | Paths | Suggested | Reason |",
        "| --- | --- | --- | --- | ---: | ---: | --- |",
    ]
    for item in report.get("items") or []:
        tasks = ", ".join(item.get("task_ids") or []) or "-"
        reason = str(item.get("reason") or "").replace("|", "/")
        lines.append(
            f"| `{item.get('repair_route')}` | `{item.get('source_rebuild_route')}` | `{item.get('branch')}` | "
            f"`{tasks}` | {item.get('path_count') or 0} | {len(item.get('suggested_allowed_paths') or [])} | {reason} |"
        )
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--clean-plan")
    parser.add_argument("--queue")
    parser.add_argument("--output")
    parser.add_argument("--report")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_root = Path(args.project_root).resolve()
    plans = task_manager_dir(project_root)
    clean_plan_path = Path(args.clean_plan).resolve() if args.clean_plan else plans / "clean_rebuild_plan.json"
    queue_path = Path(args.queue).resolve() if args.queue else plans / "task_queue.json"
    output_path = Path(args.output).resolve() if args.output else plans / "allowed_paths_repair_plan.json"
    report_path = Path(args.report).resolve() if args.report else plans / "reports" / f"ALLOWED_PATHS_REPAIR_PLAN_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    report = build_report(project_root, load_json(clean_plan_path), load_json(queue_path))
    write_json(output_path, report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(report), encoding="utf-8")
    append_log(project_root, "pre-integrator", "allowed_paths_repair_planned", severity="info", counts=report["counts"])

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"items: {report['item_count']}")
        print(f"counts: {report['counts']}")
        print(f"written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
