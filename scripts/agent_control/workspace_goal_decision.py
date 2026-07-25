#!/usr/bin/env python3
"""Classify a workspace migration goal status into an actionable decision."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


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


def safe_int(value: Any) -> int:
    try:
        if value is None:
            return 0
        return int(value)
    except Exception:
        return 0


def decide(status: dict[str, Any]) -> dict[str, Any]:
    validation = status.get("validation") if isinstance(status.get("validation"), dict) else {}
    prepared = validation.get("prepared_queue") if isinstance(validation.get("prepared_queue"), dict) else {}
    target = validation.get("target_queue") if isinstance(validation.get("target_queue"), dict) else {}
    diff = status.get("diff") if isinstance(status.get("diff"), dict) else {}
    route_tasks = status.get("route_tasks") if isinstance(status.get("route_tasks"), dict) else {}
    route_decisions = status.get("route_decisions") if isinstance(status.get("route_decisions"), dict) else {}
    route_packets = status.get("route_packets") if isinstance(status.get("route_packets"), dict) else {}
    decisions = route_tasks.get("by_decision") if isinstance(route_tasks.get("by_decision"), dict) else {}
    by_owner = route_tasks.get("by_owner") if isinstance(route_tasks.get("by_owner"), dict) else {}
    decision_counts = route_decisions.get("by_decision") if isinstance(route_decisions.get("by_decision"), dict) else {}
    next_owner_counts = route_decisions.get("by_next_owner") if isinstance(route_decisions.get("by_next_owner"), dict) else {}
    stale_reasons = status.get("stale_reasons") if isinstance(status.get("stale_reasons"), list) else []
    next_actions: list[str] = []
    decision = str(status.get("decision") or "needed")
    can_apply = bool(status.get("can_apply"))
    next_owner = str(status.get("next_owner") or "dispatcher")

    if stale_reasons:
        decision = "stale"
        can_apply = False
        next_owner = "dispatcher"
        next_actions.append("Regenerate stale or missing goal artifacts.")
    elif safe_int(prepared.get("errors")) > 0:
        decision = "dispatcher_review"
        can_apply = False
        next_owner = "dispatcher"
        next_actions.append("Fix prepared queue validation errors before apply.")
    elif diff.get("available") and not any(safe_int(diff.get(key)) for key in ("added_count", "changed_count", "removed_count")):
        decision = "applied"
        can_apply = False
        next_owner = "dispatcher"
        next_actions.append("No queue diff remains; continue role-specific integration tasks.")
    elif diff.get("available") and safe_int(diff.get("removed_count")) > 0:
        decision = "unsafe"
        can_apply = False
        next_owner = "owner"
        next_actions.append("Prepared queue removes tasks; owner review required.")
    elif diff.get("available") and safe_int(prepared.get("errors")) == 0 and (
        safe_int(diff.get("added_count")) or safe_int(diff.get("changed_count"))
    ):
        decision = "ready_to_apply"
        can_apply = True
        next_owner = "dispatcher"
        next_actions.append("Apply prepared queue through workspace_queue_apply.py.")
    elif safe_int(target.get("errors")) or safe_int(target.get("warnings")):
        decision = "needed"
        can_apply = False
        next_owner = "dispatcher"
        next_actions.append("Build or refresh prepared queue artifacts.")
    else:
        decision = "not_needed"
        can_apply = False
        next_owner = "dispatcher"
        next_actions.append("No queue migration action is currently required.")

    if route_packets.get("exists"):
        packet_owners = route_packets.get("by_next_owner") if isinstance(route_packets.get("by_next_owner"), dict) else {}
        owner_review_count = safe_int(packet_owners.get("owner"))
        architect_review_count = safe_int(packet_owners.get("architect"))
        integrator_review_count = safe_int(packet_owners.get("integrator"))
        dispatcher_review_count = safe_int(packet_owners.get("dispatcher"))
        worker_ready_count = safe_int(route_packets.get("worker_ready_count"))
    elif route_decisions.get("exists"):
        owner_review_count = safe_int(decision_counts.get("owner_review")) + safe_int(decision_counts.get("blocked_secret"))
        architect_review_count = safe_int(next_owner_counts.get("architect"))
        integrator_review_count = safe_int(next_owner_counts.get("integrator"))
        dispatcher_review_count = safe_int(next_owner_counts.get("dispatcher"))
        worker_ready_count = safe_int(decision_counts.get("ready_for_packet"))
    else:
        owner_review_count = safe_int(by_owner.get("owner")) + safe_int(decisions.get("needs_human"))
        architect_review_count = safe_int(by_owner.get("architect")) + safe_int(decisions.get("needs_architect"))
        integrator_review_count = safe_int(by_owner.get("integrator")) + safe_int(decisions.get("needs_integrator_review"))
        dispatcher_review_count = safe_int(by_owner.get("dispatcher")) + safe_int(decisions.get("needs_task_packet")) + safe_int(decisions.get("needs_dispatcher_repair"))
        worker_ready_count = safe_int(decisions.get("worker_ready"))
    role_reviews = {
        "owner_review": owner_review_count,
        "architect_review": architect_review_count,
        "integrator_review": integrator_review_count,
        "dispatcher_review": dispatcher_review_count,
        "worker_ready": worker_ready_count,
    }
    if decision in {"applied", "not_needed"}:
        if owner_review_count:
            next_actions.append("Owner route tasks still require explicit decision.")
        if architect_review_count:
            next_actions.append("Architect route tasks still require scope review.")
        if integrator_review_count:
            next_actions.append("Integrator route tasks still require integration planning.")
    if route_packets.get("import_exists"):
        if safe_int(route_packets.get("pending_approval_count")):
            next_actions.append("Ready route packets are pending explicit import approval.")
        if safe_int(route_packets.get("review_required_count")):
            next_actions.append("Route packets still require architect or integrator review.")
        if safe_int(route_packets.get("blocked_count")):
            next_actions.append("Blocked route packets require owner, environment or manual decisions.")

    result = dict(status)
    result["mode"] = "workspace_goal_decision"
    result["generated_at"] = utc_now()
    result["decision"] = decision
    result["can_apply"] = can_apply
    result["next_owner"] = next_owner
    result["decisions"] = {
        "role_reviews": role_reviews,
        "queue_diff_action": decision,
    }
    result["next_actions"] = next_actions
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--status", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    result = decide(load_json(args.status.expanduser()))
    if args.output:
        write_json_atomic(args.output.expanduser(), result)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"decision={result['decision']} can_apply={result['can_apply']} next_owner={result['next_owner']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
