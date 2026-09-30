#!/usr/bin/env python3
"""Validate workspace route task seed reports before queue import."""

from __future__ import annotations

import argparse
import json
import shlex
from pathlib import Path
from typing import Any


VALID_STATUSES = {"needs_architect", "needs_task_packet", "needs_human", "planned"}
VALID_OWNERS = {"architect", "dispatcher", "integrator", "owner"}
INVALID_CHECK_FRAGMENTS = {
    "validate_task_queue_readiness.py": ["--project-root"],
}


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def add_issue(issues: list[dict[str, str]], severity: str, code: str, task_id: str, message: str) -> None:
    issues.append({"severity": severity, "code": code, "task_id": task_id, "message": message})


def check_tokens(command: str) -> list[str]:
    try:
        return shlex.split(command, posix=False)
    except ValueError:
        return command.split()


def validate_check_command(task_id: str, command: str, issues: list[dict[str, str]]) -> None:
    tokens = check_tokens(command)
    text = " ".join(tokens)
    for script, invalid_args in INVALID_CHECK_FRAGMENTS.items():
        if script not in text:
            continue
        for invalid in invalid_args:
            if invalid in tokens or invalid in text:
                add_issue(issues, "error", "invalid_check_argument", task_id, f"{script} does not accept {invalid}")


def validate_task(task: dict[str, Any], issues: list[dict[str, str]]) -> None:
    task_id = str(task.get("id") or "missing-id")
    owner = str(task.get("owner") or "")
    status = str(task.get("status") or "")
    if owner not in VALID_OWNERS:
        add_issue(issues, "error", "invalid_owner", task_id, f"invalid owner: {owner}")
    if status not in VALID_STATUSES:
        add_issue(issues, "error", "invalid_status", task_id, f"invalid status: {status}")
    for field in ("allowed_paths", "forbidden_paths", "checks", "acceptance_criteria"):
        value = task.get(field)
        if not isinstance(value, list) or not value:
            add_issue(issues, "error", f"missing_{field}", task_id, f"{field} must be a non-empty list")
    if task.get("preservation_captured") is not True:
        add_issue(issues, "error", "preservation_not_captured", task_id, "route task requires captured preservation evidence")
    if task.get("migration_sensitive") is True and not any("makemigrations" in str(check) for check in task.get("checks") or []):
        add_issue(issues, "error", "migration_check_missing", task_id, "migration-sensitive route needs makemigrations check")
    if task.get("category") == "secret_config" and "owner_secret_config_decision_required" not in [str(item) for item in task.get("blockers") or []]:
        add_issue(issues, "error", "secret_config_without_owner_blocker", task_id, "secret config route needs owner blocker")
    for command in task.get("checks") or []:
        validate_check_command(task_id, str(command), issues)


def build_report(path: Path) -> dict[str, Any]:
    data = load_json(path)
    issues: list[dict[str, str]] = []
    tasks = data.get("tasks") if isinstance(data.get("tasks"), list) else []
    if not isinstance(data.get("tasks"), list):
        add_issue(issues, "error", "tasks_not_array", "report", "tasks must be an array")
    for task in tasks:
        if not isinstance(task, dict):
            add_issue(issues, "error", "task_not_object", "unknown", "task must be an object")
            continue
        validate_task(task, issues)
    return {
        "schema_version": "1.0",
        "mode": "workspace_route_task_validation",
        "path": str(path),
        "task_count": len(tasks),
        "errors": sum(1 for item in issues if item["severity"] == "error"),
        "warnings": sum(1 for item in issues if item["severity"] == "warning"),
        "issues": issues,
        "ok": not any(item["severity"] == "error" for item in issues),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    report = build_report(args.input.expanduser())
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"ok={report['ok']} errors={report['errors']} warnings={report['warnings']}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
