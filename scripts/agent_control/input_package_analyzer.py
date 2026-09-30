#!/usr/bin/env python3
"""Classify one active Project Input package without granting authority."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import input_lifecycle_controller as lifecycle


def decision_for(manifest: dict[str, Any]) -> dict[str, str]:
    authority = manifest.get("authority") or {}
    if any(authority.get(field) is not False for field in ("execution_authorized", "worker_ready", "merge_authorized")):
        return {"route": "ask_owner", "reason": "input package attempts to grant authority"}
    proposals = manifest.get("proposals") or []
    safe_kinds = {"research_only", "decision_only"}
    direct = not proposals or all(
        item.get("change_kind") in safe_kinds
        and (item.get("target_file") is None or str(item.get("target_file")).startswith("AiStudio/Project_state/input/"))
        for item in proposals
    )
    if direct:
        return {"route": "direct_processing", "reason": "package is evidence/decision input with no canonical behavior change"}
    return {"route": "delegate_to_dispatcher", "reason": "package proposes a canonical project change"}


def analyze(project_root: Path, package_id: str, *, apply: bool) -> dict[str, Any]:
    registry = lifecycle.load_active(project_root)
    record = lifecycle.find_record(registry, package_id)
    if not record:
        return {"ok": False, "reason": "active_record_missing", "package_id": package_id}
    package_path = lifecycle.find_ref(record, "package:")
    package_dir = project_root / package_path
    manifest = lifecycle.load_manifest(package_dir, project_root)
    decision = decision_for(manifest)
    if apply:
        state_by_route = {
            "direct_processing": "awaiting_execution",
            "delegate_to_dispatcher": "awaiting_execution",
            "ask_owner": "blocked_human",
        }
        record["state"] = state_by_route[decision["route"]]
        record["updated_at"] = lifecycle.utc_now()
        lifecycle.set_ref(record, "decision:", decision["route"])
        lifecycle.set_ref(record, "decision_reason:", decision["reason"])
        lifecycle.save_active(project_root, registry)
    return {"ok": True, "applied": apply, "package_id": package_id, "decision": decision}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package_id")
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(json.dumps(analyze(args.project_root.resolve(), args.package_id, apply=args.apply), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
