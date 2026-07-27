#!/usr/bin/env python3
"""Verify terminal Project Input evidence and move active state to history."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import input_lifecycle_controller as lifecycle


TERMINAL_BY_STATE = {
    "awaiting_finalization": "completed",
    "archive_pending": "archived",
    "duplicate_pending": "duplicate",
    "failed_terminal": "failed_terminal",
}


def finalize(project_root: Path, package_id: str, *, apply: bool) -> dict[str, Any]:
    registry = lifecycle.load_active(project_root)
    record = lifecycle.find_record(registry, package_id)
    if not record:
        existing = next((item for item in lifecycle.history_records(project_root) if item.get("package_id") == package_id), None)
        return {"ok": bool(existing), "duplicate": bool(existing), "record": existing}
    terminal_state = TERMINAL_BY_STATE.get(str(record.get("state") or ""))
    if not terminal_state:
        return {"ok": False, "reason": "package_not_terminal", "state": record.get("state")}
    merge_commit = lifecycle.find_ref(record, "merge_commit:")
    package_path = lifecycle.find_ref(record, "package:")
    if not merge_commit or not package_path or not (project_root / package_path / "manifest.json").is_file():
        return {"ok": False, "reason": "terminal_evidence_missing"}
    result = lifecycle.move_to_history(
        project_root,
        package_id,
        terminal_state=terminal_state,
        merge_commit=merge_commit,
        apply=apply,
    )
    return {"package_id": package_id, **result}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package_id")
    parser.add_argument("--project-root", type=Path, default=Path("."))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(json.dumps(finalize(args.project_root.resolve(), args.package_id, apply=args.apply), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
