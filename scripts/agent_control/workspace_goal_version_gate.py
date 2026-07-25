#!/usr/bin/env python3
"""Gate workspace migration goal artifacts by goal, contract and artifact versions."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def check_gate(
    goal: dict[str, Any],
    *,
    expected_goal_version: str | None = None,
    expected_contract_version: str | None = None,
    expected_artifact_version: str | None = None,
    require_applied: bool = False,
) -> dict[str, Any]:
    blockers: list[str] = []
    warnings: list[str] = []
    decision = str(goal.get("decision") or "")
    if expected_goal_version and goal.get("goal_version") != expected_goal_version:
        blockers.append("goal_version_mismatch")
    if expected_contract_version and goal.get("contract_version") != expected_contract_version:
        blockers.append("contract_version_mismatch")
    if expected_artifact_version and goal.get("artifact_version") != expected_artifact_version:
        blockers.append("artifact_version_mismatch")
    if goal.get("stale") or decision == "stale":
        blockers.append("goal_stale")
    if decision == "unsafe":
        blockers.append("goal_unsafe")
    if require_applied and decision != "applied":
        blockers.append("goal_not_applied")
    if decision == "ready_to_apply" and not goal.get("can_apply"):
        blockers.append("ready_to_apply_without_can_apply")
    if decision in {"owner_review", "architect_review", "integrator_review", "dispatcher_review"}:
        warnings.append(f"review_required:{decision}")
    return {
        "schema_version": "1.0",
        "mode": "workspace_goal_version_gate",
        "ok": not blockers,
        "blockers": blockers,
        "warnings": warnings,
        "decision": decision,
        "can_apply": bool(goal.get("can_apply")),
        "project_id": goal.get("project_id"),
        "goal_version": goal.get("goal_version"),
        "contract_version": goal.get("contract_version"),
        "artifact_version": goal.get("artifact_version"),
        "expected": {
            "goal_version": expected_goal_version,
            "contract_version": expected_contract_version,
            "artifact_version": expected_artifact_version,
            "require_applied": require_applied,
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--goal", required=True, type=Path)
    parser.add_argument("--expected-goal-version")
    parser.add_argument("--expected-contract-version")
    parser.add_argument("--expected-artifact-version")
    parser.add_argument("--require-applied", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = check_gate(
        load_json(args.goal.expanduser()),
        expected_goal_version=args.expected_goal_version,
        expected_contract_version=args.expected_contract_version,
        expected_artifact_version=args.expected_artifact_version,
        require_applied=args.require_applied,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"ok={report['ok']} blockers={','.join(report['blockers']) or '-'}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
