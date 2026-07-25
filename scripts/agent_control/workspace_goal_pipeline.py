#!/usr/bin/env python3
"""Build workspace migration goal status, decision and version gate artifacts."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import workspace_goal_decision
import workspace_goal_status
import workspace_goal_version_gate


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def build_pipeline(
    *,
    project_id: str,
    goal_version: str,
    contract_version: str,
    output_dir: Path,
    route_seeds: Path | None = None,
    classification: Path | None = None,
    route_plan: Path | None = None,
    preservation: Path | None = None,
    route_decision: Path | None = None,
    route_packets: Path | None = None,
    packet_import: Path | None = None,
    final_queue: Path | None = None,
    target_queue: Path | None = None,
    apply_report: Path | None = None,
    require_applied: bool = False,
) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    prefix = f"{project_id}-migration-goal"
    status_path = output_dir / f"{prefix}.status.json"
    decision_path = output_dir / f"{prefix}.decision.json"
    gate_path = output_dir / f"{prefix}.gate.json"

    status = workspace_goal_status.build_status(
        project_id=project_id,
        goal_version=goal_version,
        contract_version=contract_version,
        route_seeds=route_seeds,
        classification=classification,
        route_plan=route_plan,
        preservation=preservation,
        route_decision=route_decision,
        route_packets=route_packets,
        packet_import=packet_import,
        final_queue=final_queue,
        target_queue=target_queue,
        apply_report=apply_report,
    )
    decision = workspace_goal_decision.decide(status)
    gate = workspace_goal_version_gate.check_gate(
        decision,
        expected_goal_version=goal_version,
        expected_contract_version=contract_version,
        expected_artifact_version=decision.get("artifact_version"),
        require_applied=require_applied,
    )
    write_json_atomic(status_path, status)
    write_json_atomic(decision_path, decision)
    write_json_atomic(gate_path, gate)
    return {
        "schema_version": "1.0",
        "mode": "workspace_goal_pipeline",
        "project_id": project_id,
        "goal_version": goal_version,
        "contract_version": contract_version,
        "decision": decision["decision"],
        "can_apply": decision["can_apply"],
        "gate_ok": gate["ok"],
        "gate_blockers": gate["blockers"],
        "artifacts": {
            "status": str(status_path),
            "decision": str(decision_path),
            "gate": str(gate_path),
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--goal-version", default=workspace_goal_status.DEFAULT_GOAL_VERSION)
    parser.add_argument("--contract-version", default=workspace_goal_status.DEFAULT_CONTRACT_VERSION)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--route-seeds", type=Path)
    parser.add_argument("--classification", type=Path)
    parser.add_argument("--route-plan", type=Path)
    parser.add_argument("--preservation", type=Path)
    parser.add_argument("--route-decision", type=Path)
    parser.add_argument("--route-packets", type=Path)
    parser.add_argument("--packet-import", type=Path)
    parser.add_argument("--final-queue", type=Path)
    parser.add_argument("--target-queue", type=Path)
    parser.add_argument("--apply-report", type=Path)
    parser.add_argument("--require-applied", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_pipeline(
        project_id=args.project_id,
        goal_version=args.goal_version,
        contract_version=args.contract_version,
        output_dir=args.output_dir.expanduser(),
        route_seeds=args.route_seeds.expanduser() if args.route_seeds else None,
        classification=args.classification.expanduser() if args.classification else None,
        route_plan=args.route_plan.expanduser() if args.route_plan else None,
        preservation=args.preservation.expanduser() if args.preservation else None,
        route_decision=args.route_decision.expanduser() if args.route_decision else None,
        route_packets=args.route_packets.expanduser() if args.route_packets else None,
        packet_import=args.packet_import.expanduser() if args.packet_import else None,
        final_queue=args.final_queue.expanduser() if args.final_queue else None,
        target_queue=args.target_queue.expanduser() if args.target_queue else None,
        apply_report=args.apply_report.expanduser() if args.apply_report else None,
        require_applied=args.require_applied,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"decision={report['decision']} gate_ok={report['gate_ok']}")
    return 0 if report["gate_ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
