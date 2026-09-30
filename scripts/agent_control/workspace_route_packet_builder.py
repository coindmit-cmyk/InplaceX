#!/usr/bin/env python3
"""Build dry-run role packet seeds from a workspace route action plan."""

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


def action_items(plan: dict[str, Any]) -> list[dict[str, Any]]:
    actions = plan.get("actions")
    if not isinstance(actions, list):
        return []
    return [item for item in actions if isinstance(item, dict)]


def packet_type(action: dict[str, Any]) -> str:
    kind = str(action.get("action_kind") or "")
    if kind == "worker_packet_ready":
        return "worker_packet"
    if kind.startswith("architect_"):
        return "architect_review_packet"
    if kind.startswith("integrator_") or kind == "manual_unknown_path_classification":
        return "integrator_review_packet"
    if kind == "owner_secret_config_decision":
        return "owner_hold_packet"
    return "role_review_packet"


def packet_status(action: dict[str, Any]) -> str:
    status = str(action.get("status") or "")
    if status == "ready":
        return "ready"
    if status == "blocked":
        return "blocked"
    return "needs_review"


def title(action: dict[str, Any]) -> str:
    owner = str(action.get("next_owner") or "role").title()
    category = str(action.get("category") or "route").replace("_", " ")
    return f"{owner}: {category} route packet"


def build_packet(action: dict[str, Any], created_at: str) -> dict[str, Any]:
    route_id = str(action.get("route_id") or "")
    ptype = packet_type(action)
    status = packet_status(action)
    checks = [str(item) for item in action.get("checks") or []]
    required_outputs = [str(item) for item in action.get("required_outputs") or []]
    packet = {
        "id": f"{route_id}-PACKET",
        "route_id": route_id,
        "action_id": action.get("id"),
        "packet_type": ptype,
        "packet_status": status,
        "title": title(action),
        "next_owner": action.get("next_owner"),
        "category": action.get("category"),
        "action_kind": action.get("action_kind"),
        "allowed_paths": action.get("allowed_paths") or [],
        "paths_sample": action.get("paths_sample") or [],
        "checks": checks,
        "required_outputs": required_outputs,
        "blockers": action.get("blockers") or [],
        "migration_sensitive": bool(action.get("migration_sensitive")),
        "worker_ready": bool(action.get("worker_ready") and ptype == "worker_packet"),
        "instructions": packet_instructions(action, ptype),
        "acceptance_criteria": acceptance_criteria(action, ptype),
        "created_at": created_at,
        "created_by": "scripts/agent_control/workspace_route_packet_builder.py",
        "mutates_state": False,
    }
    if ptype == "worker_packet":
        packet["eligible_worker_profiles"] = ["auto-worker-5.5", "auto-worker-5.5max"]
    return packet


def packet_instructions(action: dict[str, Any], ptype: str) -> list[str]:
    base = [
        "Use current target code, docs and task state before changing anything.",
        "Do not overwrite preserved project work; integrate compatible changes into current state.",
    ]
    if ptype == "worker_packet":
        return [
            *base,
            "Execute only the ready route scope and record validation evidence.",
        ]
    if ptype == "architect_review_packet":
        return [
            *base,
            "Classify route paths as canonical, superseded, duplicate, or requiring worker packets.",
            "Produce worker-safe follow-up packet seeds only for actionable paths.",
        ]
    if ptype == "integrator_review_packet":
        result = [
            *base,
            "Compare preserved route paths with current product code before deciding apply/skip/supersede.",
        ]
        if action.get("migration_sensitive"):
            result.append("Run migration dry-run checks and adapt migration intent to current target state.")
        return result
    if ptype == "owner_hold_packet":
        return [
            "Owner decision is required before automation can proceed.",
            "Do not copy secret or local config values into shared task packets.",
        ]
    return base


def acceptance_criteria(action: dict[str, Any], ptype: str) -> list[str]:
    criteria = [
        "Packet decision references current route evidence.",
        "No secret values are introduced into tracked artifacts.",
        *[str(item) for item in action.get("required_outputs") or []],
    ]
    if ptype == "worker_packet":
        criteria.append("Checks listed in the packet are run or explicitly blocked with evidence.")
    return criteria


def build_report(action_plan_path: Path) -> dict[str, Any]:
    plan = load_json(action_plan_path)
    created_at = utc_now()
    packets = [build_packet(action, created_at) for action in action_items(plan)]
    by_type = Counter(str(packet.get("packet_type") or "") for packet in packets)
    by_status = Counter(str(packet.get("packet_status") or "") for packet in packets)
    by_owner = Counter(str(packet.get("next_owner") or "") for packet in packets)
    return {
        "schema_version": "1.0",
        "mode": "workspace_route_packet_builder",
        "generated_at": created_at,
        "action_plan": str(action_plan_path),
        "packet_count": len(packets),
        "by_packet_type": dict(sorted(by_type.items())),
        "by_packet_status": dict(sorted(by_status.items())),
        "by_next_owner": dict(sorted(by_owner.items())),
        "packets": packets,
        "mutates_state": False,
        "import_ready": False,
        "next_step": "Review packet seeds before converting any item into task_queue mutations.",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action-plan", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(args.action_plan.expanduser())
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"packets={report['packet_count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
