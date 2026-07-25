#!/usr/bin/env python3
"""Classify Artifact Discovery scanner output.

This script is intentionally small: scanner produces initial findings, classifier
normalizes severity/routing hints for downstream routers.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

BLOCKING_CATEGORIES = {"possible_secret_pattern"}
NEW_CURRENT_CATEGORIES = {
    "missing_project_map_coverage",
    "unmapped_artifact",
    "missing_index_link",
    "missing_integration_surface",
    "missing_ux_contract_or_waiver",
}


def load_report(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("discovery report must be a JSON object")
    return data


def classify_finding(finding: dict[str, Any]) -> dict[str, Any]:
    item = dict(finding)
    category = str(item.get("category") or "")
    current_scope = bool(item.get("current_scope"))
    if category in BLOCKING_CATEGORIES:
        item["severity"] = "blocking"
        item["blocking_gate"] = item.get("blocking_gate") or "human_security_review"
        item["auto_task_allowed"] = False
    elif current_scope and category in NEW_CURRENT_CATEGORIES:
        item["severity"] = "blocking"
        item["blocking_gate"] = item.get("blocking_gate") or "integration"
    elif not item.get("severity"):
        item["severity"] = "warning"
    item["classification"] = "classified"
    return item


def classify_report(report: dict[str, Any]) -> dict[str, Any]:
    findings = [classify_finding(item) for item in report.get("findings") or [] if isinstance(item, dict)]
    out = dict(report)
    out["findings"] = findings
    out["summary"] = dict(out.get("summary") or {})
    out["summary"]["finding_count"] = len(findings)
    out["summary"]["blocking_count"] = sum(1 for item in findings if item.get("severity") in {"blocking", "critical"})
    out.setdefault("checks", []).append({"name": "artifact_discovery_classification", "result": "completed"})
    return out


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    report = classify_report(load_report(Path(args.input)))
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.json or not args.output:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
