#!/usr/bin/env python3
"""Gate reviewed route packet seeds into a staged task_queue copy."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import validate_task_queue_readiness


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def queue_tasks(queue: dict[str, Any]) -> list[dict[str, Any]]:
    tasks = queue.get("tasks")
    return tasks if isinstance(tasks, list) else []


def packet_items(packet_seed_report: dict[str, Any]) -> list[dict[str, Any]]:
    packets = packet_seed_report.get("packets")
    if not isinstance(packets, list):
        return []
    return [item for item in packets if isinstance(item, dict)]


def is_ready_worker_packet(packet: dict[str, Any]) -> bool:
    return (
        packet.get("packet_type") == "worker_packet"
        and packet.get("packet_status") == "ready"
        and packet.get("worker_ready") is True
    )


def selected_for_import(packet_id: str, packet: dict[str, Any], approvals: set[str], approve_ready: bool) -> bool:
    if packet_id in approvals:
        return True
    return approve_ready and is_ready_worker_packet(packet)


def packet_task(packet: dict[str, Any], now: str, verified_by: str) -> dict[str, Any]:
    packet_id = str(packet.get("id") or "")
    route_id = str(packet.get("route_id") or "")
    allowed_paths = [str(item) for item in packet.get("allowed_paths") or []]
    paths_sample = [str(item) for item in packet.get("paths_sample") or []]
    checks = [str(item) for item in packet.get("checks") or []]
    acceptance_criteria = [str(item) for item in packet.get("acceptance_criteria") or []]
    instructions = [str(item) for item in packet.get("instructions") or []]
    required_outputs = [str(item) for item in packet.get("required_outputs") or []]
    blockers = [str(item) for item in packet.get("blockers") or []]
    doc_refs = [str(item) for item in packet.get("doc_refs") or []]
    if not doc_refs:
        doc_refs = [str(packet.get("source_report") or "")] if packet.get("source_report") else []
    if not doc_refs:
        doc_refs = ["runtime/agent-control route packet seeds"]
    base_branch = str(packet.get("base_branch") or "develop")

    return {
        "id": packet_id,
        "canonical_task_id": packet_id,
        "derived_from": route_id,
        "title": str(packet.get("title") or packet_id),
        "type": "workspace_route_packet",
        "category": str(packet.get("category") or "workspace_route"),
        "status": "planned",
        "owner": "worker",
        "priority": "P0",
        "complexity": "L",
        "worker_ready": True,
        "packet_status": "ready",
        "normalization_status": "worker_ready",
        "dispatcher_decision": "worker_ready",
        "packet_schema_version": 2,
        "base_branch": base_branch,
        "recommended_agent": "auto-worker-5.5",
        "eligible_worker_profiles": packet.get("eligible_worker_profiles") or ["auto-worker-5.5", "auto-worker-5.5max"],
        "allowed_paths": allowed_paths,
        "forbidden_paths": [".env", ".env.*", "secrets/**", "runtime/**/secrets/**"],
        "acceptance_criteria": acceptance_criteria,
        "checks": checks,
        "worker_instructions": instructions,
        "traceability": {
            "route_id": route_id,
            "action_id": packet.get("action_id"),
            "packet_id": packet_id,
            "packet_seed_created_at": packet.get("created_at"),
            "packet_seed_created_by": packet.get("created_by"),
        },
        "context_inventory": {
            "code_refs": paths_sample or allowed_paths,
            "doc_refs": doc_refs,
            "task_refs": [route_id or packet_id],
            "review_policy": "Integrate compatible changes into the current target code instead of replaying stale edits blindly.",
        },
        "doc_refs": doc_refs,
        "input_refs": {
            "base_branch": base_branch,
            "allowed_paths": [],
            "declaration_source": "none",
            "paths_sample": paths_sample,
            "packet_id": packet_id,
            "route_id": route_id,
        },
        "output_contract": {
            "required_outputs": required_outputs or ["Record validation evidence and final task state."],
            "worker_report_required": True,
            "validation_evidence_required": True,
        },
        "script_actions": checks or ["No automated check declared; record explicit blocker evidence."],
        "existing_behavior": [
            "Read current code, docs and task state before edits.",
            "Preserve compatible existing behavior and documented project contracts.",
        ],
        "preserve_contract": [
            "Do not delete or overwrite preserved project work without a current-state compatibility decision.",
            "Integrate compatible updates into the current target state.",
        ],
        "regression_guards": checks or acceptance_criteria,
        "code_refs": paths_sample or allowed_paths,
        "integration_notes": [
            "This task was imported from a reviewed route packet seed.",
            "If current code drifted, adapt the packet intent to current state and document the decision.",
        ],
        "migration_compatibility_policy": {
            "mode": "integrate_with_current_target_state",
            "required_integrator_behavior": [
                "Compare packet intent with current target code before applying changes.",
                "Adapt compatible migration intent to current files rather than replaying stale patches.",
            ],
            "required_checks": checks,
        },
        "requires_current_context_review": True,
        "current_context_verified_at": now,
        "current_context_verified_by": verified_by,
        "blockers": blockers,
        "source_file": str(packet.get("source_report") or "workspace_route_packet_seed"),
        "provenance": {
            "import_source": "workspace_route_packet_import_gate",
            "source_packet": packet_id,
            "source_route": route_id,
        },
        "created_at": now,
        "imported_at": now,
        "import_source": "workspace_route_packet_import_gate",
    }


def build_report(
    packet_seeds_path: Path,
    queue_path: Path | None = None,
    output_path: Path | None = None,
    approvals: list[str] | None = None,
    approve_ready: bool = False,
    verified_by: str = "dispatcher",
) -> dict[str, Any]:
    packet_seed_report = load_json(packet_seeds_path)
    packets = packet_items(packet_seed_report)
    approval_set = {str(item) for item in approvals or [] if str(item)}
    if queue_path and queue_path.exists():
        queue = load_json(queue_path)
    else:
        queue = {"schema_version": 1, "tasks": []}

    existing = queue_tasks(queue)
    existing_ids = {str(task.get("id") or "") for task in existing if isinstance(task, dict)}
    now = utc_now()
    additions: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    pending_approval: list[dict[str, Any]] = []
    review_required: list[dict[str, Any]] = []
    blocked: list[dict[str, Any]] = []

    for packet in packets:
        packet_id = str(packet.get("id") or "")
        packet_ref = {
            "id": packet_id,
            "route_id": packet.get("route_id"),
            "packet_type": packet.get("packet_type"),
            "packet_status": packet.get("packet_status"),
            "next_owner": packet.get("next_owner"),
        }
        if not packet_id:
            skipped.append({**packet_ref, "reason": "missing_id"})
            continue
        if not is_ready_worker_packet(packet):
            if packet.get("packet_status") == "blocked":
                blocked.append({**packet_ref, "reason": "packet_blocked"})
            else:
                review_required.append({**packet_ref, "reason": "packet_not_worker_ready"})
            continue
        if not selected_for_import(packet_id, packet, approval_set, approve_ready):
            pending_approval.append({**packet_ref, "reason": "approval_required"})
            continue
        if packet_id in existing_ids:
            skipped.append({**packet_ref, "reason": "already_exists"})
            continue
        additions.append(packet_task({**packet, "source_report": str(packet_seeds_path)}, now, verified_by))
        existing_ids.add(packet_id)

    staged_queue = dict(queue)
    staged_queue["tasks"] = [*existing, *additions]
    staged_queue.setdefault("schema_version", 1)
    staged_queue["route_packet_import_gate"] = {
        "source": str(packet_seeds_path),
        "generated_at": now,
        "approve_ready": approve_ready,
        "approved_packet_ids": sorted(approval_set),
        "added_count": len(additions),
        "pending_approval_count": len(pending_approval),
        "review_required_count": len(review_required),
        "blocked_count": len(blocked),
        "skipped_count": len(skipped),
    }
    if output_path:
        write_json_atomic(output_path, staged_queue)

    staged_issues: list[dict[str, str]] = []
    for index, task in enumerate(staged_queue.get("tasks") or []):
        if isinstance(task, dict):
            validate_task_queue_readiness.validate_task(task, index, staged_issues)
    added_ids = {str(task.get("id") or "") for task in additions}
    added_issues = [
        issue for issue in staged_issues
        if any(f"({task_id})" in str(issue.get("path") or "") for task_id in added_ids)
    ]
    inherited_issues = [issue for issue in staged_issues if issue not in added_issues]

    return {
        "schema_version": "1.0",
        "mode": "workspace_route_packet_import_gate",
        "packet_seeds": str(packet_seeds_path),
        "queue": str(queue_path) if queue_path else None,
        "output": str(output_path) if output_path else None,
        "packet_count": len(packets),
        "approved_packet_ids": sorted(approval_set),
        "approve_ready": approve_ready,
        "added_count": len(additions),
        "pending_approval_count": len(pending_approval),
        "review_required_count": len(review_required),
        "blocked_count": len(blocked),
        "skipped_count": len(skipped),
        "pending_approval": pending_approval,
        "review_required": review_required,
        "blocked": blocked,
        "skipped": skipped,
        "staged_queue_validation": {
            "errors": sum(1 for item in staged_issues if item["severity"] == "error"),
            "warnings": sum(1 for item in staged_issues if item["severity"] == "warning"),
            "added_task_errors": sum(1 for item in added_issues if item["severity"] == "error"),
            "added_task_warnings": sum(1 for item in added_issues if item["severity"] == "warning"),
            "inherited_errors": sum(1 for item in inherited_issues if item["severity"] == "error"),
            "inherited_warnings": sum(1 for item in inherited_issues if item["severity"] == "warning"),
            "added_issue_sample": added_issues[:20],
            "inherited_issue_sample": inherited_issues[:20],
        },
        "mutates_input_queue": False,
        "mutates_state": False,
        "next_step": "Review the staged output and apply it separately with workspace_queue_apply.py if approved.",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packet-seeds", required=True, type=Path)
    parser.add_argument("--queue", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--approve", action="append", default=[], help="Approve one packet id for staging. Repeatable.")
    parser.add_argument("--approve-ready", action="store_true", help="Approve every ready worker packet in the seed report.")
    parser.add_argument("--verified-by", default="dispatcher")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(
        args.packet_seeds.expanduser(),
        queue_path=args.queue.expanduser() if args.queue else None,
        output_path=args.output.expanduser() if args.output else None,
        approvals=args.approve,
        approve_ready=args.approve_ready,
        verified_by=args.verified_by,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(
            "added={added} pending_approval={pending} review_required={review} blocked={blocked}".format(
                added=report["added_count"],
                pending=report["pending_approval_count"],
                review=report["review_required_count"],
                blocked=report["blocked_count"],
            )
        )
    return 0 if report["staged_queue_validation"]["added_task_errors"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
