#!/usr/bin/env python3
"""Evaluate model budget state before launching expensive agent lanes."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_manager_dir


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def is_paused(limit: dict[str, Any], threshold: int) -> bool:
    state = str(limit.get("state") or "ok")
    if state in {"low", "exhausted", "paused"}:
        return True
    remaining = limit.get("remaining_estimate")
    if isinstance(remaining, (int, float)) and remaining < threshold:
        return True
    return False


def evaluate(data: dict[str, Any]) -> dict[str, Any]:
    limits = data.get("limits") if isinstance(data.get("limits"), dict) else {}
    policy = data.get("policy") if isinstance(data.get("policy"), dict) else {}
    threshold_55 = int(policy.get("pause_5_5_below_percent") or 15)
    limit_53 = limits.get("codex_5_3") if isinstance(limits.get("codex_5_3"), dict) else {}
    limit_55 = limits.get("codex_5_5") if isinstance(limits.get("codex_5_5"), dict) else {}
    paused_53 = is_paused(limit_53, 1)
    paused_55 = is_paused(limit_55, threshold_55)
    return {
        "checked_at": utc_now(),
        "state": "blocked" if paused_53 and paused_55 else ("degraded" if paused_53 or paused_55 else "ok"),
        "script_only": paused_53 and paused_55,
        "allow_worker_profiles": {
            "auto-worker-5.3-mini": not paused_53,
            "auto-worker-5.3": not paused_53,
            "auto-worker-5.5": not paused_55,
            "auto-worker-5.5max": not paused_55,
        },
        "paused_lanes": [
            lane
            for lane, paused in {
                "codex_5_3": paused_53,
                "codex_5_5": paused_55,
            }.items()
            if paused
        ],
        "policy": policy,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate model budget guard.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--budget-state", help="Defaults to AiStudio/Task_manager/model_budget_state.json.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    path = Path(args.budget_state).resolve() if args.budget_state else task_manager_dir(project_root) / "model_budget_state.json"
    report = evaluate(load_json(path))
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"state: {report['state']}")
        print(f"paused_lanes: {', '.join(report['paused_lanes']) or '-'}")
    return 0 if report["state"] != "blocked" else 2


if __name__ == "__main__":
    raise SystemExit(main())
