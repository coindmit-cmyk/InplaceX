#!/usr/bin/env python3
"""Build role-specific next actions from workspace route decisions."""

from __future__ import annotations

import argparse
import json
from collections import Counter
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


def tasks_by_id(payload: dict[str, Any], key: str = "tasks") -> dict[str, dict[str, Any]]:
    tasks = payload.get(key)
    if not isinstance(tasks, list):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for task in tasks:
        if not isinstance(task, dict):
            continue
        tid = str(task.get("id") or "")
        if tid:
            result[tid] = task
    return result


def route_decisions_by_id(payload: dict[str, Any]) -> dict[str, dict[str, Any]]:
    routes = payload.get("routes")
    if not isinstance(routes, list):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for route in routes:
        if not isinstance(route, dict):
            continue
        rid = str(route.get("id") or "")
        if rid:
            result[rid] = route
    return result


def safe_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def action_kind(decision: str, seed: dict[str, Any]) -> str:
    category = str(seed.get("category") or "")
    if decision == "ready_for_packet":
        return "worker_packet_ready"
    if decision == "architect_review" and category == "documentation":
        return "architect_documentation_scope"
    if decision == "architect_review" and category == "automation_contract":
        return "architect_automation_contract_scope"
    if decision == "integrator_review" and seed.get("migration_sensitive"):
        return "integrator_migration_sensitive_plan"
    if decision == "integrator_review":
        return "integrator_product_integration_plan"
    if decision == "owner_review":
        return "manual_unknown_path_classification"
    if decision == "blocked_secret":
        return "owner_secret_config_decision"
    return "role_review"


def action_status(decision: str) -> str:
    if decision == "ready_for_packet":
        return "ready"
    if decision in {"blocked_secret", "owner_review"}:
        return "blocked"
    return "needs_review"


def required_outputs(kind: str) -> list[str]:
    mapping = {
        "worker_packet_ready": [
            "Worker execution result or explicit dispatcher split report.",
            "Validation evidence from queue/readiness checks.",
        ],
        "architect_documentation_scope": [
            "Architect decision on which documentation paths are canonical, superseded, or need merge.",
            "Worker-safe documentation packet list if changes remain actionable.",
        ],
        "architect_automation_contract_scope": [
            "Architect decision on automation contract compatibility with current agent-core.",
            "Explicit list of scripts/schemas that must be merged, skipped, or regenerated.",
        ],
        "integrator_migration_sensitive_plan": [
            "Integrator comparison against current code and migrations.",
            "Migration check result with makemigrations dry-run evidence.",
            "Concrete apply/skip/supersede decision per affected module group.",
        ],
        "integrator_product_integration_plan": [
            "Integrator comparison against current product code.",
            "Project test evidence or concrete blockers.",
        ],
        "manual_unknown_path_classification": [
            "Manual classification of unknown paths as product, docs, local-only, secret, or discard.",
            "Follow-up route task seeds if any paths are actionable.",
        ],
        "owner_secret_config_decision": [
            "Owner decision for secret/config sample paths.",
            "Confirmation that no real secrets enter shared packets.",
        ],
    }
    return mapping.get(kind, ["Role review decision and next owner."])


def build_action(seed: dict[str, Any], decision: dict[str, Any], queue_task: dict[str, Any] | None) -> dict[str, Any]:
    route_id = str(seed.get("id") or decision.get("id") or "")
    decision_value = str(decision.get("decision") or "needed")
    kind = action_kind(decision_value, seed)
    checks = [str(item) for item in seed.get("checks") or []]
    blockers = sorted({str(item) for item in [*(seed.get("blockers") or []), *(decision.get("blockers") or [])] if item})
    return {
        "id": f"{route_id}-ACTION",
        "route_id": route_id,
        "action_kind": kind,
        "decision": decision_value,
        "status": action_status(decision_value),
        "next_owner": decision.get("next_owner") or seed.get("owner"),
        "category": seed.get("category"),
        "source_action": seed.get("action"),
        "path_count": safe_int(seed.get("full_category_path_count") or seed.get("path_count")),
        "paths_sample": seed.get("paths_sample") or [],
        "allowed_paths": seed.get("allowed_paths") or [],
        "blockers": blockers,
        "checks": checks,
        "migration_sensitive": bool(seed.get("migration_sensitive") or decision.get("migration_sensitive")),
        "present_in_queue": bool(decision.get("present_in_queue")),
        "queue_status": queue_task.get("status") if isinstance(queue_task, dict) else None,
        "worker_ready": bool(decision.get("can_worker_claim") or (queue_task or {}).get("worker_ready")),
        "required_outputs": required_outputs(kind),
        "next_action": decision.get("next_action") or seed.get("next_action") or "",
        "mutates_state": False,
    }


def build_report(seeds_path: Path, route_decision_path: Path, queue_path: Path | None = None) -> dict[str, Any]:
    seeds = load_json(seeds_path)
    route_decision = load_json(route_decision_path)
    queue = load_json(queue_path) if queue_path and queue_path.exists() else {}
    seeds_by_id = tasks_by_id(seeds)
    decisions_by_id = route_decisions_by_id(route_decision)
    queue_by_id = tasks_by_id(queue)
    actions = [
        build_action(seed, decisions_by_id.get(route_id, {"id": route_id, "decision": "needed"}), queue_by_id.get(route_id))
        for route_id, seed in sorted(seeds_by_id.items())
    ]
    by_status = Counter(str(action.get("status") or "") for action in actions)
    by_owner = Counter(str(action.get("next_owner") or "") for action in actions)
    by_kind = Counter(str(action.get("action_kind") or "") for action in actions)
    return {
        "schema_version": "1.0",
        "mode": "workspace_route_action_plan",
        "generated_at": utc_now(),
        "seeds": str(seeds_path),
        "route_decision": str(route_decision_path),
        "queue": str(queue_path) if queue_path else None,
        "action_count": len(actions),
        "ready_count": int(by_status.get("ready", 0)),
        "blocked_count": int(by_status.get("blocked", 0)),
        "needs_review_count": int(by_status.get("needs_review", 0)),
        "by_status": dict(sorted(by_status.items())),
        "by_next_owner": dict(sorted(by_owner.items())),
        "by_action_kind": dict(sorted(by_kind.items())),
        "actions": actions,
        "mutates_state": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seeds", required=True, type=Path)
    parser.add_argument("--route-decision", required=True, type=Path)
    parser.add_argument("--queue", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(
        args.seeds.expanduser(),
        args.route_decision.expanduser(),
        queue_path=args.queue.expanduser() if args.queue else None,
    )
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(
            f"actions={report['action_count']} ready={report['ready_count']} "
            f"blocked={report['blocked_count']} needs_review={report['needs_review_count']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
