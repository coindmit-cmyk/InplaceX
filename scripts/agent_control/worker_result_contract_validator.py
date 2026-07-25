#!/usr/bin/env python3
"""Validate worker result identity before it can become an integration candidate."""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
from pathlib import Path
from typing import Any

from project_paths import task_file


TASK_ID_RE = re.compile(r"(?<![A-Z0-9])([A-Z][A-Z0-9]{0,12}-[0-9][0-9A-Z]*(?:\.[0-9A-Z]+)*|[A-Z]{1,6}[0-9][A-Z0-9]*(?:\.[0-9A-Z]+)*)(?![A-Z0-9])", re.IGNORECASE)
DOCUMENTATION_IMPACT_VALUES = {"none", "updated_inline", "docs_task_required", "blocked_missing_docs"}
VALID_RESULTS = {"agent_done", "integration_ready"}
INVALID_RESULTS = {"packet_defect", "needs_worker_fix", "needs_human", "needs_dispatcher", "needs_task_packet"}
WORKER_BRANCH_PREFIXES = (
    "AiStudio/Agent/worker/",
    "origin/AiStudio/Agent/worker/",
    "remote/",
    "origin/remote/",
)


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def read_text(path: Path | None) -> str:
    if not path or not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="ignore")


def documentation_impact_from_report(report: dict[str, Any] | None, report_text: str) -> str:
    if report:
        value = report.get("documentation_impact")
        if isinstance(value, str) and value.strip():
            return value.strip().lower()
    match = re.search(r"(?im)^\s*(?:[-*]\s*)?documentation[_\-\s]?impact\s*[:=]\s*`?([a-z0-9_\-]+)`?\s*$", report_text or "")
    if match:
        return match.group(1).strip().lower().replace("-", "_")
    return ""


def task_requires_documentation_impact(task: dict[str, Any] | None) -> bool:
    if not task:
        return False
    if task.get("documentation_impact_required"):
        return True
    contract = task.get("output_contract")
    if isinstance(contract, dict) and contract.get("documentation_impact_required"):
        return True
    return False


def infer_task_ids(*values: Any) -> list[str]:
    found: set[str] = set()
    for value in values:
        if isinstance(value, (list, tuple, set)):
            found.update(infer_task_ids(*value))
            continue
        if isinstance(value, dict):
            found.update(infer_task_ids(*value.values()))
            continue
        for match in TASK_ID_RE.findall(str(value or "")):
            found.add(match.upper())
    return sorted(found)


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or task.get("canonical_task_id") or "").strip()


def find_task(queue: dict[str, Any] | None, task_id_value: str) -> dict[str, Any] | None:
    if not isinstance(queue, dict):
        return None
    for task in queue.get("tasks") or []:
        if isinstance(task, dict) and task_id(task).upper() == task_id_value.upper():
            return task
    return None


def match_any(path: str, patterns: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    for pattern in patterns:
        pattern = str(pattern).replace("\\", "/")
        if fnmatch.fnmatch(normalized, pattern) or normalized == pattern.rstrip("/"):
            return True
        if pattern.endswith("/**") and normalized.startswith(pattern[:-3]):
            return True
    return False


def report_payload(report_path: Path | None) -> tuple[dict[str, Any] | None, str]:
    if not report_path or not report_path.exists():
        return None, ""
    text = read_text(report_path)
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        data = None
    return data if isinstance(data, dict) else None, text


def validate(args: argparse.Namespace) -> dict[str, Any]:
    project_root = Path(args.project_root).resolve()
    queue = load_json(Path(args.queue).resolve()) if args.queue else load_json(task_file(project_root, "task_queue.json"))
    report_path = Path(args.report).resolve() if args.report else None
    report, report_text = report_payload(report_path)
    branch = str(args.branch or (report or {}).get("branch") or "")
    pr_title = str(args.pr_title or "")
    pr_body = str(args.pr_body or "")
    explicit_task_id = str(args.task_id or "").strip()
    changed_paths = list(args.changed_path or [])
    if report and isinstance(report.get("changed_paths"), list):
        changed_paths.extend(str(path) for path in report.get("changed_paths") if path)
    ids = infer_task_ids(explicit_task_id, branch, pr_title, pr_body, report, report_text)
    errors: list[str] = []
    warnings: list[str] = []
    if not ids:
        errors.append("missing task_id")
        canonical = ""
    elif len(ids) > 1:
        errors.append("conflicting task_id evidence: " + ", ".join(ids))
        canonical = ids[0]
    else:
        canonical = ids[0]
    if canonical and branch and canonical.upper() not in branch.upper():
        errors.append("branch missing task_id")
    if branch and not any(branch.startswith(prefix) for prefix in WORKER_BRANCH_PREFIXES):
        errors.append("worker branch must start with AiStudio/Agent/worker/ or legacy remote/")
    if not report_path or not report_path.exists():
        errors.append("worker report missing")
    elif canonical and canonical.upper() not in infer_task_ids(report, report_text):
        errors.append("worker report task_id mismatch")
    task = find_task(queue, canonical) if canonical else None
    if canonical and not task:
        errors.append("task_id not found in queue")
    result = str((report or {}).get("result") or (report or {}).get("status") or args.result or "").strip()
    if result in INVALID_RESULTS:
        errors.append(f"worker result is not integration-ready: {result}")
    elif result and result not in VALID_RESULTS:
        warnings.append(f"unknown worker result: {result}")
    elif not result:
        warnings.append("worker result missing")
    if task and changed_paths:
        forbidden = [str(item) for item in task.get("forbidden_paths") or [] if item]
        forbidden_hits = [path for path in changed_paths if match_any(path, forbidden)]
        if forbidden_hits:
            errors.append("changed forbidden paths: " + ", ".join(forbidden_hits[:10]))
    documentation_impact = documentation_impact_from_report(report, report_text)
    if canonical and task and task_requires_documentation_impact(task):
        if not documentation_impact:
            errors.append("documentation_impact missing")
        elif documentation_impact not in DOCUMENTATION_IMPACT_VALUES:
            errors.append(f"documentation_impact invalid: {documentation_impact}")
    if args.pr_title is not None and canonical and canonical.upper() not in pr_title.upper():
        errors.append("PR title missing task_id")
    if args.pr_body is not None and canonical and canonical.upper() not in pr_body.upper():
        errors.append("PR body missing task_id")

    route = "integration_requested" if not errors else ("needs_dispatcher" if any("task_id" in error or "queue" in error for error in errors) else "needs_worker_fix")
    return {
        "ok": not errors,
        "classification": "valid_worker_result" if not errors else "invalid_worker_result",
        "route": route,
        "task_id": canonical,
        "canonical_target_id": f"task:{canonical}" if canonical else None,
        "branch": branch,
        "worker_report": str(report_path) if report_path else None,
        "changed_paths": sorted(set(changed_paths)),
        "errors": errors,
        "warnings": warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate worker result identity and report contract.")
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--task-id")
    parser.add_argument("--queue")
    parser.add_argument("--report")
    parser.add_argument("--pr-title")
    parser.add_argument("--pr-body")
    parser.add_argument("--result")
    parser.add_argument("--changed-path", action="append")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    result = validate(args)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("ok" if result["ok"] else "; ".join(result["errors"]))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
