#!/usr/bin/env python3
"""Classify workspace route tasks against the current queue state."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SECRET_BLOCKERS = {"owner_secret_config_decision_required"}
MANUAL_BLOCKERS = {"unknown_paths_require_manual_classification"}


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


def tasks_by_id(queue: dict[str, Any] | None) -> dict[str, dict[str, Any]]:
    if not queue:
        return {}
    tasks = queue.get("tasks")
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


def seed_tasks(seeds: dict[str, Any]) -> list[dict[str, Any]]:
    tasks = seeds.get("tasks")
    if not isinstance(tasks, list):
        return []
    return [task for task in tasks if isinstance(task, dict)]


def blockers_for(task: dict[str, Any]) -> set[str]:
    return {str(item) for item in task.get("blockers") or []}


def queue_task(seed: dict[str, Any], queue_index: dict[str, dict[str, Any]]) -> dict[str, Any] | None:
    tid = str(seed.get("id") or "")
    return queue_index.get(tid)


def decision_for(seed: dict[str, Any], current: dict[str, Any] | None) -> tuple[str, str, bool]:
    source = current or seed
    owner = str(source.get("owner") or seed.get("owner") or "")
    status = str(source.get("status") or seed.get("status") or "")
    packet_status = str(source.get("packet_status") or "")
    dispatcher_decision = str(source.get("dispatcher_decision") or "")
    blockers = blockers_for(seed) | blockers_for(source)

    if blockers & SECRET_BLOCKERS or owner == "owner":
        return "blocked_secret", "owner", False
    if blockers & MANUAL_BLOCKERS:
        return "owner_review", owner or "integrator", False
    if bool(source.get("worker_ready")) or packet_status == "worker_ready" or dispatcher_decision == "worker_ready":
        return "ready_for_packet", "worker", True
    if owner == "architect" or status == "needs_architect":
        return "architect_review", "architect", False
    if owner == "integrator":
        return "integrator_review", "integrator", False
    if owner == "dispatcher" or status in {"needs_task_packet", "planned"}:
        return "dispatcher_review", "dispatcher", False
    return "needed", owner or "dispatcher", False


def next_action(decision: str) -> str:
    return {
        "ready_for_packet": "Worker can claim only after the usual packet and context gates pass.",
        "architect_review": "Architect must decide scope and produce role-safe packets before worker execution.",
        "integrator_review": "Integrator must compare preserved paths with current code and migrations before apply.",
        "owner_review": "Manual classification is required before this route can become an automated packet.",
        "blocked_secret": "Owner decision is required; secrets or local config must not enter shared packets.",
        "dispatcher_review": "Dispatcher must split or repair the task packet from current route evidence.",
        "needed": "Route still needs role review against current code, docs and task state.",
    }.get(decision, "Review route against current project state.")


def classify_route(seed: dict[str, Any], current: dict[str, Any] | None) -> dict[str, Any]:
    source = current or seed
    decision, next_owner, can_worker_claim = decision_for(seed, current)
    return {
        "id": seed.get("id"),
        "source_route_id": seed.get("source_route_id"),
        "owner": source.get("owner") or seed.get("owner"),
        "category": source.get("category") or seed.get("category"),
        "status": source.get("status") or seed.get("status"),
        "worker_ready": bool(source.get("worker_ready")),
        "packet_status": source.get("packet_status"),
        "dispatcher_decision": source.get("dispatcher_decision"),
        "migration_sensitive": bool(source.get("migration_sensitive") or seed.get("migration_sensitive")),
        "blockers": sorted(blockers_for(seed) | blockers_for(source)),
        "decision": decision,
        "next_owner": next_owner,
        "can_worker_claim": can_worker_claim,
        "present_in_queue": current is not None,
        "next_action": next_action(decision),
    }


def build_report(seeds_path: Path, queue_path: Path | None = None) -> dict[str, Any]:
    seeds = load_json(seeds_path)
    queue = load_json(queue_path) if queue_path else None
    queue_index = tasks_by_id(queue)
    routes = [classify_route(seed, queue_task(seed, queue_index)) for seed in seed_tasks(seeds)]
    by_decision = Counter(str(route["decision"]) for route in routes)
    by_owner = Counter(str(route["next_owner"]) for route in routes)
    return {
        "schema_version": "1.0",
        "mode": "workspace_route_decision",
        "generated_at": utc_now(),
        "seeds": str(seeds_path),
        "queue": str(queue_path) if queue_path else None,
        "route_count": len(routes),
        "ready_for_packet_count": int(by_decision.get("ready_for_packet", 0)),
        "blocked_count": int(by_decision.get("blocked_secret", 0) + by_decision.get("owner_review", 0)),
        "needs_review_count": sum(
            int(by_decision.get(name, 0))
            for name in ("architect_review", "integrator_review", "dispatcher_review", "needed")
        ),
        "by_decision": dict(sorted(by_decision.items())),
        "by_next_owner": dict(sorted(by_owner.items())),
        "routes": routes,
        "mutates_state": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seeds", required=True, type=Path)
    parser.add_argument("--queue", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    report = build_report(args.seeds.expanduser(), queue_path=args.queue.expanduser() if args.queue else None)
    if args.output:
        write_json_atomic(args.output.expanduser(), report)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(
            " ".join(
                [
                    f"routes={report['route_count']}",
                    f"ready_for_packet={report['ready_for_packet_count']}",
                    f"blocked={report['blocked_count']}",
                    f"needs_review={report['needs_review_count']}",
                ]
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
