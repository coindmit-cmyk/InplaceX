#!/usr/bin/env python3
"""Universal AiStudio action report builder and validator."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
from pathlib import Path
from typing import Any


REQUIRED_FIELDS = (
    "schema_version",
    "action_id",
    "action_type",
    "project_id",
    "actor",
    "mode",
    "started_at",
    "finished_at",
    "input_refs",
    "before_state",
    "actions_planned",
    "actions_executed",
    "actions_skipped",
    "actions_failed",
    "affected_paths",
    "validation",
    "result",
    "next_owner",
    "next_action",
)
RESULTS = {"succeeded", "blocked", "failed", "no_op"}
MODES = {"dry_run", "apply"}
SECRET_PATTERNS = (
    re.compile(r"ghp_[A-Za-z0-9_]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"(?i)(api[_-]?key|token|password|secret)\s*[:=]\s*['\"]?[^'\"\s]{8,}"),
)


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def contains_secret(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str) if not isinstance(value, str) else value
    return any(pattern.search(text) for pattern in SECRET_PATTERNS)


def build_report(
    *,
    action_id: str,
    action_type: str,
    project_id: str,
    actor: str,
    mode: str,
    result: str,
    next_owner: str,
    next_action: str,
    started_at: str | None = None,
    finished_at: str | None = None,
    input_refs: list[str] | None = None,
    before_state: dict[str, Any] | None = None,
    after_state: dict[str, Any] | None = None,
    actions_planned: list[dict[str, Any]] | None = None,
    actions_executed: list[dict[str, Any]] | None = None,
    actions_skipped: list[dict[str, Any]] | None = None,
    actions_failed: list[dict[str, Any]] | None = None,
    affected_paths: list[str] | None = None,
    validation: dict[str, Any] | None = None,
    artifacts: list[str] | None = None,
    rollback: dict[str, Any] | None = None,
    residual_risks: list[str] | None = None,
    source: str = "",
) -> dict[str, Any]:
    start = started_at or now_utc()
    finish = finished_at or start
    return {
        "schema_version": 1,
        "action_id": action_id,
        "action_type": action_type,
        "project_id": project_id,
        "actor": actor,
        "source": source,
        "mode": mode,
        "started_at": start,
        "finished_at": finish,
        "input_refs": input_refs or [],
        "before_state": before_state or {},
        "after_state": after_state or {},
        "actions_planned": actions_planned or [],
        "actions_executed": actions_executed or [],
        "actions_skipped": actions_skipped or [],
        "actions_failed": actions_failed or [],
        "affected_paths": affected_paths or [],
        "validation": validation or {"ok": result in {"succeeded", "no_op"}},
        "artifacts": artifacts or [],
        "rollback": rollback or {},
        "result": result,
        "next_owner": next_owner,
        "next_action": next_action,
        "residual_risks": residual_risks or [],
    }


def validate_report(report: dict[str, Any]) -> dict[str, Any]:
    errors: list[dict[str, str]] = []
    for field in REQUIRED_FIELDS:
        if field not in report:
            errors.append({"code": "missing_required_field", "field": field})
    if report.get("schema_version") != 1:
        errors.append({"code": "invalid_schema_version", "field": "schema_version"})
    if report.get("mode") not in MODES:
        errors.append({"code": "invalid_mode", "field": "mode"})
    if report.get("result") not in RESULTS:
        errors.append({"code": "invalid_result", "field": "result"})
    if report.get("result") in {"blocked", "failed"}:
        if not str(report.get("next_owner") or "").strip():
            errors.append({"code": "missing_next_owner", "field": "next_owner"})
        if not str(report.get("next_action") or "").strip():
            errors.append({"code": "missing_next_action", "field": "next_action"})
    for field in ("input_refs", "actions_planned", "actions_executed", "actions_skipped", "actions_failed", "affected_paths"):
        if field in report and not isinstance(report.get(field), list):
            errors.append({"code": "field_must_be_array", "field": field})
    for field in ("before_state", "after_state", "validation", "rollback"):
        if field in report and not isinstance(report.get(field), dict):
            errors.append({"code": "field_must_be_object", "field": field})
    if contains_secret(report):
        errors.append({"code": "secret_value_detected", "field": "*"})
    return {"ok": not errors, "errors": errors}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validate", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if not args.validate:
        raise SystemExit("--validate is required")
    data = json.loads(args.validate.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise SystemExit("report must be JSON object")
    report = validate_report(data)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print("ok" if report["ok"] else "invalid")
    return 0 if report["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
