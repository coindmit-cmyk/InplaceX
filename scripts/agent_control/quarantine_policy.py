#!/usr/bin/env python3
"""Staged advisory quarantine policy for Project Standard v2."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


HARD_SAFETY_CODES = {
    "checkout_escapes_workspace",
    "git_store_escapes_workspace",
    "secret_value_detected",
    "archive_checksum_mismatch",
}


def evaluate_policy(
    *,
    health_score: int,
    health_threshold: int = 85,
    deductions: list[dict[str, Any]] | None = None,
    hard_quarantine_enabled: bool = False,
) -> dict[str, Any]:
    deductions = deductions or []
    hard_reasons = [
        item
        for item in deductions
        if any(str(item.get("code") or "").endswith(code) or str(item.get("code") or "") == code for code in HARD_SAFETY_CODES)
    ]
    advisory_reasons = list(deductions)
    state = "advisory"
    if hard_quarantine_enabled and hard_reasons:
        state = "hard_quarantine"
    elif int(health_score) < int(health_threshold):
        state = "advisory_attention"
    return {
        "schema_version": "1.0",
        "state": state,
        "hard_quarantine_enabled": bool(hard_quarantine_enabled),
        "blocks_project": state == "hard_quarantine",
        "health_score": int(health_score),
        "health_threshold": int(health_threshold),
        "hard_reasons": hard_reasons,
        "advisory_reasons": advisory_reasons,
        "recovery_lanes_available": ["doctor", "rebuilder", "cleanup", "owner"],
        "next_owner": "owner" if state == "hard_quarantine" else "workspace-doctor",
        "next_action": "Review deterministic hard safety evidence and run recovery lane." if state == "hard_quarantine" else "Review advisory health evidence; do not block solely on score.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--doctor-report", type=Path)
    parser.add_argument("--project-id")
    parser.add_argument("--hard-quarantine-enabled", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if not args.doctor_report:
        raise SystemExit("--doctor-report is required")
    data = json.loads(args.doctor_report.read_text(encoding="utf-8"))
    projects = data.get("projects") if isinstance(data, dict) else []
    project = next((item for item in projects if isinstance(item, dict) and (not args.project_id or item.get("project_id") == args.project_id)), None)
    if not isinstance(project, dict):
        raise SystemExit("project not found")
    report = evaluate_policy(
        health_score=int(project.get("health_score") or 0),
        health_threshold=int(project.get("health_threshold") or 85),
        deductions=project.get("deductions") if isinstance(project.get("deductions"), list) else [],
        hard_quarantine_enabled=args.hard_quarantine_enabled,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(report["state"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
