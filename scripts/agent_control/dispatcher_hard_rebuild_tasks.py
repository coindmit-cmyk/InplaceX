#!/usr/bin/env python3
"""Rebuild task_queue.json from current project documentation.

This is the hard Dispatcher rebuild path: archive the old machine queue,
derive a fresh queue from owner-facing project docs, and write a comparison
report so old automation residue can be audited instead of silently carried
forward.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from dispatcher_packet_repair import apply_v2_packet

from process_log import append_log
from project_paths import task_file, task_manager_dir, task_reports_dir


DONE_STATUSES = {"done", "agent_done", "owner_approved", "mvp_done", "merged", "closed"}
ACTIVE_STATUSES = {"planned", "partial", "watch", "needs_task_packet", "worker_ready", ""}
POSTPONED_STATUSES = {"postponed", "stale_or_superseded"}
TASK_ID_RE = re.compile(r"^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*(?:\.\d+)?$")
PROFILE_BY_COMPLEXITY = {
    "S": ["auto-worker-5.3-mini", "auto-worker-5.3"],
    "M": ["auto-worker-5.3"],
    "L": ["auto-worker-5.5", "auto-worker-5.5max"],
    "XL": ["auto-worker-5.5max"],
}
DEFAULT_FORBIDDEN_PATHS = [
    ".git/**",
    ".env",
    ".env.*",
    "**/__pycache__/**",
    "**/.pytest_cache/**",
    "node_modules/**",
    "old/**",
    "AiStudio/Task_manager/archive/**",
    "db.sqlite3",
    "*.sqlite3",
    "secrets/**",
]
DEFAULT_DOC_SOURCES = [
    "AiStudio/Task_manager/MVP_TASK_QUEUE.md",
    "AiStudio/Task_manager/COMMERCE_TASK_BREAKDOWN.md",
    "AiStudio/Task_manager/CUSTOMER_EXPERIENCE_BACKLOG.md",
    "AiStudio/Task_manager/NEXT_WORK.md",
]
GLOBAL_CONTEXT_DOCS = [
    "AiStudio/Task_manager/README.md",
    "AiStudio/Task_manager/DEVELOPMENT_MAP.md",
    "AiStudio/Task_manager/DEVELOPMENT_STATUS.md",
    "AiStudio/Task_manager/MVP_SCOPE.md",
    "AiStudio/Task_manager/TASK_INDEX.md",
    "docs/architecture.md",
    "docs/modules.md",
    "docs/testing.md",
    "docs/commerce_operating_model.md",
]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def unique(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        normalized = value.strip()
        if normalized and normalized not in seen:
            result.append(normalized)
            seen.add(normalized)
    return result


def clean_cell(value: str) -> str:
    value = re.sub(r"<br\s*/?>", "; ", value, flags=re.IGNORECASE)
    value = re.sub(r"\[\[([^]|]+)(?:\|([^]]+))?\]\]", lambda m: m.group(2) or m.group(1), value)
    value = re.sub(r"\[([^]]+)\]\(([^)]+)\)", r"\1", value)
    return value.replace("`", "").replace("**", "").strip()


def split_markdown_row(line: str) -> list[str]:
    return [clean_cell(cell) for cell in line.strip().strip("|").split("|")]


def is_separator_row(cells: list[str]) -> bool:
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", cell.strip()) for cell in cells)


def normalize_key(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")


def existing_doc(project_root: Path, relative: str) -> bool:
    return bool(relative) and (project_root / relative).exists()


def section_for_line(lines: list[str], index: int) -> str:
    section = ""
    for line in lines[: index + 1]:
        if line.startswith("#"):
            section = clean_cell(line.lstrip("#").strip())
    return section


def markdown_tables(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    tables: list[dict[str, Any]] = []
    index = 0
    while index < len(lines):
        if not lines[index].lstrip().startswith("|"):
            index += 1
            continue
        header = split_markdown_row(lines[index])
        if index + 1 >= len(lines):
            index += 1
            continue
        separator = split_markdown_row(lines[index + 1])
        if not is_separator_row(separator):
            index += 1
            continue
        rows: list[list[str]] = []
        row_index = index + 2
        while row_index < len(lines) and lines[row_index].lstrip().startswith("|"):
            rows.append(split_markdown_row(lines[row_index]))
            row_index += 1
        tables.append({"header": header, "rows": rows, "section": section_for_line(lines, index)})
        index = row_index
    return tables


def value(row: dict[str, str], *keys: str) -> str:
    for key in keys:
        item = row.get(key)
        if item:
            return item
    return ""


def normalize_status(status: str, complexity: str) -> tuple[str, bool, str, str]:
    raw = status.lower().strip()
    if raw in DONE_STATUSES:
        return "done", False, "done", "completed source row archived out of active hard rebuild"
    if raw in POSTPONED_STATUSES:
        return "postponed", False, "stale_or_superseded", "source row is postponed"
    if raw == "needs_human":
        return "needs_human", False, "needs_human", "owner decision is required before implementation"
    if raw == "blocked":
        return "blocked", False, "blocked_by_missing_environment", "source row is blocked"
    if complexity.upper() == "XL":
        return "needs_architect", False, "needs_architect", "XL source row must be split or narrowed before worker execution"
    if raw in ACTIVE_STATUSES:
        return "planned", True, "worker_ready", "current documentation provides a complete task packet"
    return "needs_task_packet", False, "needs_task_packet", f"unrecognized source status: {status or 'empty'}"


def infer_complexity(row: dict[str, str]) -> str:
    raw = value(row, "complexity", "size").upper().strip()
    if raw in PROFILE_BY_COMPLEXITY:
        return raw
    title = value(row, "task", "title", "feature", "result").lower()
    if any(word in title for word in ("docs", "decide", "document", "changelog", "fixture")):
        return "S"
    if any(word in title for word in ("flow", "service", "adapter", "admin", "ui", "import", "sync")):
        return "M"
    return "M"


def infer_priority(task_id: str, row: dict[str, str], section: str) -> str:
    raw = value(row, "priority").upper().strip()
    if raw in {"P0", "P1", "P2"}:
        return raw
    if "P0" in section.upper() or task_id.startswith("MVPQ-6."):
        return "P0"
    if "P2" in section.upper() or value(row, "status").lower().strip() == "postponed":
        return "P2"
    return "P1"


def infer_type(row: dict[str, str], title: str) -> str:
    raw = value(row, "type").strip()
    if raw:
        return raw
    modules = value(row, "module", "modules", "primary_modules").lower()
    if "docs" in modules or "docs" in title.lower() or "document" in title.lower():
        return "docs"
    if "test" in modules or "qa" in modules or "smoke" in title.lower():
        return "tests"
    if "owner" in modules or "decide" in title.lower():
        return "decision"
    return "implementation"


def module_allowed_paths(modules: str, title: str) -> list[str]:
    text = f"{modules} {title}".lower()
    paths: list[str] = []
    mapping = {
        "catalog": ["apps/catalog/**", "templates/catalog/**", "tests/test_catalog.py"],
        "orders": ["apps/orders/**", "tests/test_cart_checkout.py"],
        "inventory": ["apps/inventory/**", "tests/test_inventory_orders.py"],
        "pricing": ["apps/pricing/**", "tests/test_pricing.py"],
        "payments": ["apps/payments/**", "tests/test_payment_layer.py"],
        "delivery": ["apps/delivery/**", "tests/test_delivery_module.py"],
        "notifications": ["apps/notifications/**", "tests/test_notifications.py"],
        "integrations": ["apps/integrations/**", "tests/test_integrations.py"],
        "crm": ["apps/crm/**", "templates/crm/**", "tests/test_crm_admin.py"],
        "core": ["apps/core/**", "tests/test_admin_rbac.py"],
        "customers": ["apps/customers/**", "tests/test_customers.py"],
        "support": ["apps/support/**", "tests/test_support.py"],
        "wholesale": ["apps/wholesale/**", "tests/test_customers.py"],
        "rma": ["apps/rma/**", "tests/**"],
        "design": ["apps/design_core/**", "templates/**", "static/**", "tests/test_design_core.py"],
        "theme": ["apps/design_core/**", "templates/**", "static/**", "tests/test_design_core.py"],
        "qa": ["docs/testing.md", "tests/**"],
        "tests": ["tests/**"],
        "docs": ["docs/**", ".agent/**", "CHANGELOG.md"],
    }
    for key, candidates in mapping.items():
        if key in text:
            paths.extend(candidates)
    if any(word in text for word in ("homepage", "menu", "seo", "storefront", "frontend", "browser")):
        paths.extend(["templates/**", "static/**"])
    if not paths:
        paths.extend(["docs/**", "CHANGELOG.md"])
    return unique(paths)


def infer_checks(project_root: Path, row: dict[str, str], task_type: str) -> list[str]:
    raw_check = value(row, "check")
    checks = [raw_check] if raw_check else []
    if existing_doc(project_root, "manage.py") and task_type != "docs":
        checks.extend(["python manage.py check", "python manage.py test"])
    elif task_type == "docs":
        checks.append("manual markdown review")
    if existing_doc(project_root, "pyproject.toml"):
        checks.append("ruff check .")
    checks.append("git diff --check")
    return unique(checks)


def acceptance_from_row(row: dict[str, str], title: str) -> list[str]:
    result = value(row, "result")
    task_text = value(row, "task", "feature", "title") or title
    check = value(row, "check")
    items = [f"Implement or verify: {task_text}."]
    if result and result != task_text:
        items.append(result)
    if check:
        items.append(f"Required evidence: {check}.")
    items.append("Update relevant project documentation and CHANGELOG when behavior changes.")
    return unique(items)


def context_docs(project_root: Path, source_file: str) -> list[str]:
    docs = [source_file]
    docs.extend([doc for doc in GLOBAL_CONTEXT_DOCS if existing_doc(project_root, doc)])
    return unique(docs)


def row_to_task(project_root: Path, source_file: str, section: str, row: dict[str, str], captured_at: str) -> dict[str, Any] | None:
    task_id = value(row, "queue_id", "id")
    if not TASK_ID_RE.fullmatch(task_id):
        return None
    title = value(row, "task", "feature", "title", "result") or task_id
    complexity = infer_complexity(row)
    status, worker_ready, decision, reason = normalize_status(value(row, "status"), complexity)
    if status == "done":
        return None
    modules = value(row, "module", "modules", "primary_modules")
    task_type = infer_type(row, title)
    allowed_paths = module_allowed_paths(modules, title)
    dependencies = value(row, "dependencies", "source_mvp", "source")
    task: dict[str, Any] = {
        "id": task_id,
        "task_id": task_id,
        "canonical_task_id": task_id,
        "canonical_target_id": f"task:{task_id}",
        "title": title,
        "status": status,
        "worker_ready": worker_ready,
        "dispatcher_decision": decision,
        "dispatcher_decision_reason": reason,
        "priority": infer_priority(task_id, row, section),
        "complexity": complexity,
        "type": task_type,
        "base_branch": "develop",
        "source_file": source_file,
        "source_section": section,
        "context_docs": context_docs(project_root, source_file),
        "allowed_paths": allowed_paths,
        "forbidden_paths": DEFAULT_FORBIDDEN_PATHS,
        "acceptance_criteria": acceptance_from_row(row, title),
        "checks": infer_checks(project_root, row, task_type),
        "inputs": unique([source_file, dependencies, *context_docs(project_root, source_file)]),
        "outputs": unique(allowed_paths + ["CHANGELOG.md"]),
        "provenance": [{
            "source_type": "project_documentation",
            "source_file": source_file,
            "source_section": section,
            "source_item_id": task_id,
            "captured_at": captured_at,
            "summary": title,
        }],
        "packet_status": "worker_ready" if worker_ready else decision,
        "normalization_status": "worker_ready" if worker_ready else decision,
        "eligible_worker_profiles": PROFILE_BY_COMPLEXITY.get(complexity, PROFILE_BY_COMPLEXITY["M"]),
    }
    if modules:
        task["area"] = modules
    if dependencies:
        task["dependencies"] = dependencies
    if decision == "needs_architect":
        task["not_worker_ready_reason"] = reason
        task["split_reason"] = reason
        task["architect_request"] = "Split this XL documentation row into S/M/L worker-ready implementation packets."
    if decision == "needs_human":
        task["not_worker_ready_reason"] = reason
        task["owner_question"] = title
    if decision == "blocked_by_missing_environment":
        task["not_worker_ready_reason"] = reason
        task["blocked_by"] = [dependencies or reason]
    if worker_ready:
        task = apply_v2_packet(task, captured_at)
    return task


def collect_tasks(project_root: Path, sources: list[str]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    captured_at = utc_now()
    tasks: list[dict[str, Any]] = []
    reports: list[dict[str, Any]] = []
    seen: set[str] = set()
    for source in sources:
        path = project_root / source
        if not path.exists():
            reports.append({"source_file": source, "status": "missing", "imported_count": 0})
            continue
        imported = 0
        skipped_duplicate = 0
        for table in markdown_tables(path):
            header = [normalize_key(cell) for cell in table["header"]]
            if "id" not in header and "queue_id" not in header:
                continue
            for cells in table["rows"]:
                row = dict(zip(header, cells))
                task = row_to_task(project_root, source, table["section"], row, captured_at)
                if not task:
                    continue
                task_id = str(task["id"])
                if task_id in seen:
                    skipped_duplicate += 1
                    continue
                seen.add(task_id)
                tasks.append(task)
                imported += 1
        reports.append({"source_file": source, "status": "scanned", "imported_count": imported, "skipped_duplicate_count": skipped_duplicate})
    return tasks, reports


def task_ids(queue: dict[str, Any]) -> set[str]:
    return {str(task.get("id") or task.get("task_id")) for task in queue.get("tasks", []) if isinstance(task, dict)}


def status_counts(tasks: list[dict[str, Any]]) -> dict[str, int]:
    return dict(Counter(str(task.get("status") or "") for task in tasks))


def render_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Dispatcher Hard Task Rebuild",
        "",
        f"- Generated: `{summary['generated_at']}`",
        f"- Apply: `{str(summary['apply']).lower()}`",
        f"- Old tasks: `{summary['old_task_count']}`",
        f"- New tasks: `{summary['new_task_count']}`",
        f"- Worker-ready: `{summary['worker_ready_count']}`",
        f"- Needs architect: `{summary['needs_architect_count']}`",
        f"- Needs human/blocked: `{summary['needs_human_or_blocked_count']}`",
        f"- Archive: `{summary.get('archive_path') or ''}`",
        "",
        "## Sources",
        "",
        "| Source | Status | Imported | Duplicates |",
        "| --- | --- | ---: | ---: |",
    ]
    for item in summary["sources"]:
        lines.append(f"| `{item['source_file']}` | `{item['status']}` | {item.get('imported_count', 0)} | {item.get('skipped_duplicate_count', 0)} |")
    lines.extend([
        "",
        "## Comparison",
        "",
        f"- Kept old ids: `{summary['kept_old_id_count']}`",
        f"- New ids: `{summary['new_id_count']}`",
        f"- Archived-only old ids: `{summary['archived_only_old_id_count']}`",
        "",
        "Archived-only sample:",
        "",
    ])
    for task_id in summary["archived_only_old_id_sample"]:
        lines.append(f"- `{task_id}`")
    lines.append("")
    return "\n".join(lines)


def build_summary(old_queue: dict[str, Any], new_queue: dict[str, Any], sources: list[dict[str, Any]], apply: bool, archive_path: str | None) -> dict[str, Any]:
    old_tasks = [task for task in old_queue.get("tasks", []) if isinstance(task, dict)]
    new_tasks = [task for task in new_queue.get("tasks", []) if isinstance(task, dict)]
    old_ids = task_ids(old_queue)
    new_ids = task_ids(new_queue)
    archived_only = sorted(old_ids - new_ids)
    created_only = sorted(new_ids - old_ids)
    return {
        "schema_version": 1,
        "generated_at": utc_now(),
        "apply": apply,
        "archive_path": archive_path,
        "old_task_count": len(old_tasks),
        "new_task_count": len(new_tasks),
        "old_status_counts": status_counts(old_tasks),
        "new_status_counts": status_counts(new_tasks),
        "worker_ready_count": sum(1 for task in new_tasks if task.get("worker_ready") is True),
        "needs_architect_count": sum(1 for task in new_tasks if task.get("dispatcher_decision") == "needs_architect"),
        "needs_human_or_blocked_count": sum(1 for task in new_tasks if str(task.get("dispatcher_decision")) in {"needs_human", "blocked_by_missing_environment"}),
        "kept_old_id_count": len(old_ids & new_ids),
        "new_id_count": len(created_only),
        "new_id_sample": created_only[:50],
        "archived_only_old_id_count": len(archived_only),
        "archived_only_old_id_sample": archived_only[:50],
        "sources": sources,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Hard rebuild AiStudio task_queue.json from project documentation.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--queue")
    parser.add_argument("--source", action="append", default=[])
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--replace", action="store_true", help="Required with --apply when queue exists.")
    parser.add_argument("--output")
    parser.add_argument("--archive-dir")
    parser.add_argument("--report")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    task_manager = task_manager_dir(project_root)
    output_path = Path(args.output).resolve() if args.output else task_file(project_root, "task_queue.dispatcher_hard_rebuild.json")
    archive_dir = Path(args.archive_dir).resolve() if args.archive_dir else task_manager / "archive"
    report_path = Path(args.report).resolve() if args.report else task_reports_dir(project_root) / f"DISPATCHER_HARD_TASK_REBUILD_{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.md"

    if args.apply and queue_path.exists() and not args.replace:
        raise SystemExit(f"queue already exists, refusing to replace without --replace: {queue_path}")

    sources = unique(DEFAULT_DOC_SOURCES + args.source)
    tasks, source_reports = collect_tasks(project_root, sources)
    new_queue = {
        "schema_version": 1,
        "updated_at": utc_now(),
        "base_branch": "develop",
        "source": "dispatcher_hard_rebuild_tasks.py",
        "rebuild_policy": "hard rebuild from current project documentation; old task_queue archived for comparison",
        "sources": source_reports,
        "tasks": tasks,
    }
    old_queue = load_json(queue_path)
    archive_path: Path | None = None
    write_json(output_path, new_queue)
    if args.apply:
        if queue_path.exists():
            archive_dir.mkdir(parents=True, exist_ok=True)
            archive_path = archive_dir / f"task_queue.before_hard_rebuild.{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
            shutil.copy2(queue_path, archive_path)
        write_json(queue_path, new_queue)

    summary = build_summary(old_queue, new_queue, source_reports, args.apply, str(archive_path) if archive_path else None)
    write_json(task_file(project_root, "dispatcher_hard_task_rebuild.summary.json"), summary)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_markdown(summary), encoding="utf-8")
    append_log(project_root, "dispatcher", "dispatcher_hard_task_rebuild", severity="info", apply=args.apply, task_count=len(tasks))

    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print(f"old tasks: {summary['old_task_count']}")
        print(f"new tasks: {summary['new_task_count']}")
        print(f"worker_ready: {summary['worker_ready_count']}")
        print(f"written: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
