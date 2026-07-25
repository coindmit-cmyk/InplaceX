#!/usr/bin/env python3
"""Build a versioned workspace migration goal status artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import validate_task_queue_readiness


DEFAULT_CONTRACT_VERSION = "workspace-migration-goal/v1"
DEFAULT_GOAL_VERSION = "AISTD2-MIGRATION-GOAL-v1"


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


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def validation_counts(path: Path | None) -> dict[str, Any]:
    if not path or not path.exists():
        return {"errors": None, "warnings": None, "issues_sample": [], "exists": False}
    queue = load_json(path)
    issues: list[dict[str, str]] = []
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    for index, task in enumerate(tasks):
        if isinstance(task, dict):
            validate_task_queue_readiness.validate_task(task, index, issues)
    return {
        "errors": sum(1 for item in issues if item["severity"] == "error"),
        "warnings": sum(1 for item in issues if item["severity"] == "warning"),
        "issues_sample": issues[:20],
        "exists": True,
    }


def task_map(queue: dict[str, Any]) -> dict[str, dict[str, Any]]:
    tasks = queue.get("tasks") if isinstance(queue.get("tasks"), list) else []
    result: dict[str, dict[str, Any]] = {}
    for task in tasks:
        if isinstance(task, dict):
            tid = str(task.get("id") or task.get("task_id") or "").strip()
            if tid:
                result[tid] = task
    return result


def queue_diff(prepared_path: Path | None, target_path: Path | None) -> dict[str, Any]:
    if not prepared_path or not target_path or not prepared_path.exists() or not target_path.exists():
        return {
            "available": False,
            "added_count": None,
            "changed_count": None,
            "removed_count": None,
            "added_task_ids": [],
            "changed_task_ids": [],
            "removed_task_ids": [],
        }
    prepared = task_map(load_json(prepared_path))
    target = task_map(load_json(target_path))
    prepared_ids = set(prepared)
    target_ids = set(target)
    changed = [tid for tid in sorted(prepared_ids & target_ids) if prepared[tid] != target[tid]]
    return {
        "available": True,
        "added_count": len(prepared_ids - target_ids),
        "changed_count": len(changed),
        "removed_count": len(target_ids - prepared_ids),
        "added_task_ids": sorted(prepared_ids - target_ids),
        "changed_task_ids": changed,
        "removed_task_ids": sorted(target_ids - prepared_ids),
    }


def artifact_info(path: Path | None) -> dict[str, Any]:
    if not path:
        return {"path": "", "exists": False, "sha256": None, "generated_at": None}
    exists = path.exists()
    info: dict[str, Any] = {
        "path": str(path),
        "exists": exists,
        "sha256": sha256_file(path) if exists and path.is_file() else None,
        "generated_at": None,
    }
    if exists and path.is_file() and path.suffix.lower() == ".json":
        try:
            data = load_json(path)
            info["generated_at"] = data.get("generated_at") or data.get("updated_at")
        except Exception:
            info["generated_at"] = None
    return info


def route_task_summary(route_seeds_path: Path | None, final_queue_path: Path | None) -> dict[str, Any]:
    seed_tasks: list[dict[str, Any]] = []
    final_tasks: dict[str, dict[str, Any]] = {}
    if route_seeds_path and route_seeds_path.exists():
        seeds = load_json(route_seeds_path)
        seed_tasks = [task for task in seeds.get("tasks") or [] if isinstance(task, dict)]
    if final_queue_path and final_queue_path.exists():
        final_tasks = task_map(load_json(final_queue_path))
    by_owner: dict[str, int] = {}
    by_decision: dict[str, int] = {}
    missing: list[str] = []
    for seed in seed_tasks:
        owner = str(seed.get("owner") or "unknown")
        by_owner[owner] = by_owner.get(owner, 0) + 1
        tid = str(seed.get("id") or "")
        task = final_tasks.get(tid)
        if not task:
            missing.append(tid)
            continue
        decision = str(task.get("dispatcher_decision") or task.get("status") or "unknown")
        by_decision[decision] = by_decision.get(decision, 0) + 1
    return {
        "seed_count": len(seed_tasks),
        "present_in_final_count": len(seed_tasks) - len(missing),
        "missing_from_final": missing,
        "by_owner": by_owner,
        "by_decision": by_decision,
    }


def route_decision_summary(route_decision_path: Path | None) -> dict[str, Any]:
    if not route_decision_path or not route_decision_path.exists():
        return {"exists": False, "route_count": 0, "by_decision": {}, "by_next_owner": {}}
    data = load_json(route_decision_path)
    return {
        "exists": True,
        "route_count": safe_int(data.get("route_count")),
        "ready_for_packet_count": safe_int(data.get("ready_for_packet_count")),
        "blocked_count": safe_int(data.get("blocked_count")),
        "needs_review_count": safe_int(data.get("needs_review_count")),
        "by_decision": data.get("by_decision") if isinstance(data.get("by_decision"), dict) else {},
        "by_next_owner": data.get("by_next_owner") if isinstance(data.get("by_next_owner"), dict) else {},
    }


def route_packet_summary(route_packets_path: Path | None, packet_import_path: Path | None) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "exists": False,
        "packet_count": 0,
        "by_packet_type": {},
        "by_packet_status": {},
        "by_next_owner": {},
        "worker_ready_count": 0,
        "pending_approval_count": 0,
        "review_required_count": 0,
        "blocked_count": 0,
        "import_added_count": 0,
        "import_skipped_count": 0,
        "import_exists": False,
    }
    if route_packets_path and route_packets_path.exists():
        data = load_json(route_packets_path)
        packets = [item for item in data.get("packets") or [] if isinstance(item, dict)]
        summary.update({
            "exists": True,
            "packet_count": safe_int(data.get("packet_count")) or len(packets),
            "by_packet_type": data.get("by_packet_type") if isinstance(data.get("by_packet_type"), dict) else {},
            "by_packet_status": data.get("by_packet_status") if isinstance(data.get("by_packet_status"), dict) else {},
            "by_next_owner": data.get("by_next_owner") if isinstance(data.get("by_next_owner"), dict) else {},
            "worker_ready_count": sum(
                1
                for item in packets
                if item.get("packet_type") == "worker_packet"
                and item.get("packet_status") == "ready"
                and item.get("worker_ready") is True
            ),
        })
    if packet_import_path and packet_import_path.exists():
        data = load_json(packet_import_path)
        gate = data.get("route_packet_import_gate") if isinstance(data.get("route_packet_import_gate"), dict) else data
        pending = safe_int(gate.get("pending_approval_count"))
        added = safe_int(gate.get("added_count"))
        summary.update({
            "import_exists": True,
            "pending_approval_count": pending,
            "review_required_count": safe_int(gate.get("review_required_count")),
            "blocked_count": safe_int(gate.get("blocked_count")),
            "import_added_count": added,
            "import_skipped_count": safe_int(gate.get("skipped_count")),
            "worker_ready_count": added + pending,
        })
    return summary


def artifact_version(artifacts: dict[str, dict[str, Any]]) -> str:
    h = hashlib.sha256()
    for name in sorted(artifacts):
        info = artifacts[name]
        h.update(name.encode("utf-8"))
        h.update(str(info.get("sha256") or "").encode("utf-8"))
    return "sha256:" + h.hexdigest()[:16]


def classify_status(
    *,
    prepared_validation: dict[str, Any],
    target_validation: dict[str, Any],
    diff: dict[str, Any],
    route_tasks: dict[str, Any],
    stale_reasons: list[str],
    applied_report_path: Path | None,
) -> tuple[str, bool, str, list[str]]:
    if stale_reasons:
        return "stale", False, "dispatcher", ["Regenerate stale artifacts before apply."]
    if safe_int(prepared_validation.get("errors")) > 0:
        return "dispatcher_review", False, "dispatcher", ["Fix prepared queue validation errors."]
    if applied_report_path and applied_report_path.exists() and diff.get("available") and not any(safe_int(diff.get(key)) for key in ("added_count", "changed_count", "removed_count")):
        return "applied", False, "dispatcher", ["Monitor dashboard and route remaining owner/architect/integrator review tasks."]
    if safe_int(route_tasks.get("seed_count")) == 0:
        return "not_needed", False, "dispatcher", ["No route task seeds were found."]
    if diff.get("available") and any(safe_int(diff.get(key)) for key in ("added_count", "changed_count", "removed_count")):
        return "ready_to_apply", True, "dispatcher", ["Run workspace_queue_apply.py after review."]
    if safe_int(target_validation.get("errors")) > 0 or safe_int(target_validation.get("warnings")) > 0:
        return "needed", False, "dispatcher", ["Build a prepared queue copy from current target."]
    return "not_needed", False, "dispatcher", ["No queue diff remains."]


def safe_int(value: Any) -> int:
    try:
        if value is None:
            return 0
        return int(value)
    except Exception:
        return 0


def stale_reasons(artifacts: dict[str, dict[str, Any]], expected_goal_version: str | None) -> list[str]:
    reasons: list[str] = []
    required = ("final_queue", "route_seeds")
    for name in required:
        if not artifacts.get(name, {}).get("exists"):
            reasons.append(f"missing_artifact:{name}")
    if expected_goal_version and not expected_goal_version.strip():
        reasons.append("missing_goal_version")
    return reasons


def build_status(
    *,
    project_id: str,
    goal_version: str,
    contract_version: str,
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
) -> dict[str, Any]:
    artifacts = {
        "route_seeds": artifact_info(route_seeds),
        "classification": artifact_info(classification),
        "route_plan": artifact_info(route_plan),
        "preservation": artifact_info(preservation),
        "route_decision": artifact_info(route_decision),
        "route_packets": artifact_info(route_packets),
        "packet_import": artifact_info(packet_import),
        "final_queue": artifact_info(final_queue),
        "target_queue": artifact_info(target_queue),
        "apply_report": artifact_info(apply_report),
    }
    prepared_validation = validation_counts(final_queue)
    target_validation = validation_counts(target_queue)
    diff = queue_diff(final_queue, target_queue)
    routes = route_task_summary(route_seeds, final_queue)
    route_decisions = route_decision_summary(route_decision)
    route_packets_summary = route_packet_summary(route_packets, packet_import)
    stale = stale_reasons(artifacts, goal_version)
    decision, can_apply, next_owner, next_actions = classify_status(
        prepared_validation=prepared_validation,
        target_validation=target_validation,
        diff=diff,
        route_tasks=routes,
        stale_reasons=stale,
        applied_report_path=apply_report,
    )
    return {
        "schema_version": "1.0",
        "mode": "workspace_goal_status",
        "goal_version": goal_version,
        "contract_version": contract_version,
        "artifact_version": artifact_version(artifacts),
        "project_id": project_id,
        "generated_at": utc_now(),
        "decision": decision,
        "can_apply": can_apply,
        "next_owner": next_owner,
        "stale": bool(stale),
        "stale_reasons": stale,
        "artifacts": artifacts,
        "validation": {
            "prepared_queue": prepared_validation,
            "target_queue": target_validation,
        },
        "diff": diff,
        "route_tasks": routes,
        "route_decisions": route_decisions,
        "route_packets": route_packets_summary,
        "next_actions": next_actions,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--goal-version", default=DEFAULT_GOAL_VERSION)
    parser.add_argument("--contract-version", default=DEFAULT_CONTRACT_VERSION)
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
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_status(
        project_id=args.project_id,
        goal_version=args.goal_version,
        contract_version=args.contract_version,
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
    )
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"decision={report['decision']} can_apply={report['can_apply']} next_owner={report['next_owner']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
