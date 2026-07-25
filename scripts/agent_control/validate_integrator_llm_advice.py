#!/usr/bin/env python3
"""Validate advisory-only local LLM output for Auto Integrator."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


VALID_STATUSES = {"ok", "invalid", "blocked", "dry_run"}
VALID_CLASSIFICATIONS = {
    "ready_candidate",
    "ready_to_finalize",
    "needs_rebase",
    "needs_rework",
    "needs_worker_fix",
    "needs_dispatcher",
    "needs_architect",
    "needs_human",
    "coordination_only",
    "cleanup_candidate",
    "duplicate",
    "stale",
    "excluded",
    "blocked",
    "no_ready_items",
}
HIGH_RISK_HINTS = ("auth", "credential", "deploy", "migration", "payment", "permission", "production", "secret", "security")
FORBIDDEN_ACTION_PATTERNS = (
    re.compile(r"(?<!do not )\bmerge\b", re.IGNORECASE),
    re.compile(r"(?<!do not )\bpush\b", re.IGNORECASE),
    re.compile(r"(?<!do not )\bdelete (?:the )?(?:remote )?branch\b", re.IGNORECASE),
    re.compile(r"(?<!do not )\bclose (?:the )?pr\b", re.IGNORECASE),
    re.compile(r"(?<!do not )\brelease (?:the )?lock\b", re.IGNORECASE),
    re.compile(r"(?<!do not )\bmark (?:task )?(?:as )?done\b", re.IGNORECASE),
    re.compile(r"\b(delete_branch|close_pr|release_lock|mark_done|edit_queue|edit_locks|edit_events|edit_handoff)\b", re.IGNORECASE),
)
FORBIDDEN_MUTATION_KEYS = {
    "branch_operations",
    "close_pr",
    "delete_branch",
    "edit_events",
    "edit_handoff",
    "edit_locks",
    "edit_queue",
    "files_to_write",
    "git_commands",
    "mark_done",
    "merge",
    "mutations",
    "push",
    "release_lock",
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict)):
        return bool(value)
    return True


def add_issue(issues: list[dict[str, str]], severity: str, code: str, path: str, message: str) -> None:
    issues.append({"severity": severity, "code": code, "path": path, "message": message})


def known_branches(context: dict[str, Any]) -> set[str]:
    branches: set[str] = set()
    for item in context.get("candidates") or []:
        if isinstance(item, dict):
            for key in ("branch", "normalized_branch"):
                if item.get(key):
                    branches.add(str(item[key]))
    batch = context.get("batch") if isinstance(context.get("batch"), dict) else {}
    for section in ("included", "excluded"):
        for item in batch.get(section) or []:
            if isinstance(item, dict) and item.get("branch"):
                branches.add(str(item["branch"]))
    return branches


def walk(value: Any, path: str = "$") -> list[tuple[str, Any]]:
    items = [(path, value)]
    if isinstance(value, dict):
        for key, child in value.items():
            items.extend(walk(child, f"{path}.{key}"))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            items.extend(walk(child, f"{path}[{index}]"))
    return items


def detect_forbidden_actions(advice: dict[str, Any]) -> list[str]:
    found: set[str] = set()
    for path, value in walk(advice):
        if isinstance(value, dict):
            for key in value:
                if str(key).lower() in FORBIDDEN_MUTATION_KEYS:
                    found.add(f"{path}.{key}")
        elif isinstance(value, str):
            for pattern in FORBIDDEN_ACTION_PATTERNS:
                if pattern.search(value):
                    found.add(f"{path}:{pattern.pattern}")
    return sorted(found)


def validate(context: dict[str, Any], advice: dict[str, Any]) -> dict[str, Any]:
    issues: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []

    if advice.get("schema_version") != 1:
        add_issue(issues, "error", "schema_version", "schema_version", "schema_version must be 1")
    if str(advice.get("source_context_hash") or "") != str(context.get("source_context_hash") or ""):
        add_issue(issues, "error", "context_hash_mismatch", "source_context_hash", "advice must reference the exact context hash")
    if str(advice.get("route") or "") != "LOCAL_INTEGRATOR_ASSISTANT":
        add_issue(issues, "error", "invalid_route", "route", "route must be LOCAL_INTEGRATOR_ASSISTANT")
    if str(advice.get("status") or "") not in VALID_STATUSES:
        add_issue(issues, "error", "invalid_status", "status", f"status must be one of {sorted(VALID_STATUSES)}")
    confidence = advice.get("confidence")
    if not isinstance(confidence, (int, float)) or confidence < 0 or confidence > 1:
        add_issue(issues, "error", "invalid_confidence", "confidence", "confidence must be a number from 0 to 1")
    if not has_value(advice.get("overall_summary")):
        add_issue(issues, "error", "missing_summary", "overall_summary", "overall_summary is required")

    explicit_forbidden = advice.get("forbidden_actions_detected")
    if explicit_forbidden:
        add_issue(issues, "error", "forbidden_actions_detected", "forbidden_actions_detected", "advice reports forbidden actions")
    detected = detect_forbidden_actions(advice)
    if detected:
        add_issue(issues, "error", "forbidden_action_terms", "$", "; ".join(detected[:20]))

    branches = known_branches(context)
    for index, item in enumerate(advice.get("candidate_advice") or []):
        path = f"candidate_advice[{index}]"
        if not isinstance(item, dict):
            add_issue(issues, "error", "candidate_advice_not_object", path, "candidate_advice item must be an object")
            continue
        branch = str(item.get("branch") or "").strip()
        if branch and branch not in branches:
            add_issue(issues, "error", "unknown_branch", f"{path}.branch", f"unknown branch {branch}")
        classification = str(item.get("suggested_classification") or "")
        if classification and classification not in VALID_CLASSIFICATIONS:
            add_issue(issues, "error", "unknown_classification", f"{path}.suggested_classification", f"unknown classification {classification}")
        item_text = json.dumps(item, ensure_ascii=False).lower()
        if classification in {"ready_candidate", "ready_to_finalize"} and any(hint in item_text for hint in HIGH_RISK_HINTS):
            add_issue(issues, "error", "high_risk_auto_ready", path, "local advice may not mark high-risk items ready")
        item_confidence = item.get("confidence")
        if item_confidence is not None and (not isinstance(item_confidence, (int, float)) or item_confidence < 0 or item_confidence > 1):
            add_issue(issues, "error", "invalid_item_confidence", f"{path}.confidence", "candidate confidence must be from 0 to 1")

    for index, item in enumerate(advice.get("batch_suggestions") or []):
        path = f"batch_suggestions[{index}]"
        if not isinstance(item, dict):
            add_issue(issues, "error", "batch_suggestion_not_object", path, "batch suggestion item must be an object")
            continue
        for branch in item.get("branches") or []:
            if str(branch) not in branches:
                add_issue(issues, "error", "unknown_batch_branch", f"{path}.branches", f"unknown branch {branch}")
        risk = str(item.get("risk_class") or "").lower()
        if risk == "high":
            add_issue(issues, "error", "high_risk_batch_suggestion", path, "local advice may not suggest high-risk ready batches")

    if context.get("redaction", {}).get("redaction_count", 0):
        add_issue(warnings, "warning", "context_redacted", "context.redaction", "context had redacted values; advice must not infer hidden details")

    return {
        "ok": not issues,
        "errors": len(issues),
        "warnings": len(warnings),
        "issues": issues + warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate advisory-only Integrator local LLM advice.")
    parser.add_argument("--context", required=True)
    parser.add_argument("--advice", required=True)
    parser.add_argument("--output")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    context = load_json(Path(args.context).resolve())
    advice = load_json(Path(args.advice).resolve())
    report = validate(context, advice)
    if args.output:
        Path(args.output).resolve().parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).resolve().write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.json or not args.output:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
