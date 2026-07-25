#!/usr/bin/env python3
"""Validate worker branch names carry the task identity."""

from __future__ import annotations

import argparse
import json
from typing import Any


WORKER_PREFIXES = (
    "AiStudio/Agent/worker/",
    "origin/AiStudio/Agent/worker/",
    "remote/",
    "origin/remote/",
)


def validate(branch: str, task_id: str) -> dict[str, Any]:
    errors: list[str] = []
    if not task_id.strip():
        errors.append("missing task_id")
    if not branch.strip():
        errors.append("missing branch")
    if task_id and branch and task_id.upper() not in branch.upper():
        errors.append("branch missing task_id")
    if branch and not any(branch.startswith(prefix) for prefix in WORKER_PREFIXES):
        errors.append("worker branch must start with AiStudio/Agent/worker/ or legacy remote/")
    return {
        "ok": not errors,
        "branch": branch,
        "task_id": task_id,
        "errors": errors,
        "reason": "; ".join(errors) if errors else "branch identity ok",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate worker branch name identity.")
    parser.add_argument("--branch", required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = validate(args.branch, args.task_id)
    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else result["reason"])
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
