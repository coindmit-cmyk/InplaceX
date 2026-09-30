#!/usr/bin/env python3
"""Create an existing Project State intake handoff for Dispatcher."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

import input_lifecycle_controller as lifecycle


def bridge(project_root: Path, package_id: str, *, apply: bool, task_id: str = "") -> dict[str, Any]:
    registry = lifecycle.load_active(project_root)
    record = lifecycle.find_record(registry, package_id)
    if not record:
        return {"ok": False, "reason": "active_record_missing", "package_id": package_id}
    if lifecycle.find_ref(record, "decision:") != "delegate_to_dispatcher":
        return {"ok": False, "reason": "dispatcher_route_not_approved", "package_id": package_id}
    package_path = lifecycle.find_ref(record, "package:")
    manifest = lifecycle.load_manifest(project_root / package_path, project_root)
    created_at = lifecycle.utc_now()
    intake = {
        "schema_version": "1.0",
        "id": f"input:{package_id}",
        "type": "handoff",
        "subtype": "project_input",
        "status": "inbox",
        "access_level": "project",
        "summary": manifest["intent"],
        "source_refs": [package_path, f"pr:{record['source_pr']}"],
        "evidence_refs": [f"merge_commit:{lifecycle.find_ref(record, 'merge_commit:')}"],
        "suggested_routes": ["Dispatcher"],
        "created_at": created_at,
        "updated_at": created_at,
        "worker_ready": False,
        "task_queue_mutation_allowed": False,
    }
    intake_path = project_root / "AiStudio" / "Project_state" / "intake" / "inbox" / f"input-{package_id}.json"
    if apply and not intake_path.exists():
        intake_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = intake_path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(intake, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, intake_path)
    if apply:
        record["state"] = "delegated_to_dispatcher"
        record["updated_at"] = lifecycle.utc_now()
        lifecycle.set_ref(record, "intake:", intake_path.relative_to(project_root).as_posix())
        if task_id:
            lifecycle.set_ref(record, "task:", task_id)
        lifecycle.save_active(project_root, registry)
    return {
        "ok": True,
        "applied": apply,
        "package_id": package_id,
        "intake_path": intake_path.relative_to(project_root).as_posix(),
        "worker_ready": False,
        "task_queue_mutated": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package_id")
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--task-id", default="")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(
        json.dumps(
            bridge(args.project_root.resolve(), args.package_id, apply=args.apply, task_id=args.task_id),
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
