#!/usr/bin/env python3
"""Complete deterministic evidence-only input without changing canonical behavior."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import input_lifecycle_controller as lifecycle
import input_package_analyzer


def process(project_root: Path, package_id: str, *, apply: bool) -> dict[str, Any]:
    registry = lifecycle.load_active(project_root)
    record = lifecycle.find_record(registry, package_id)
    if not record:
        return {"ok": False, "reason": "active_record_missing", "package_id": package_id}
    if lifecycle.find_ref(record, "decision:") != "direct_processing":
        return {"ok": False, "reason": "direct_route_not_approved", "package_id": package_id}
    package_dir = project_root / lifecycle.find_ref(record, "package:")
    manifest = lifecycle.load_manifest(package_dir, project_root)
    if input_package_analyzer.decision_for(manifest)["route"] != "direct_processing":
        return {"ok": False, "reason": "package_is_not_deterministic_direct_input", "package_id": package_id}
    if apply:
        record["state"] = "awaiting_finalization"
        record["updated_at"] = lifecycle.utc_now()
        lifecycle.set_ref(record, "direct_result:", "merged-package-retained-as-project-input")
        lifecycle.save_active(project_root, registry)
    return {
        "ok": True,
        "applied": apply,
        "package_id": package_id,
        "result": "merged-package-retained-as-project-input",
        "canonical_files_changed": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package_id")
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(json.dumps(process(args.project_root.resolve(), args.package_id, apply=args.apply), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
