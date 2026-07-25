#!/usr/bin/env python3
"""Small JSONL logging helpers for agent control scripts."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from project_paths import task_process_logs_dir


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def append_log(project_root: Path, process: str, event: str, **fields: Any) -> None:
    log_dir = task_process_logs_dir(project_root)
    log_dir.mkdir(parents=True, exist_ok=True)
    record = {
        "timestamp": utc_now(),
        "process": process,
        "event": event,
        **fields,
    }
    with (log_dir / f"{process}.jsonl").open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    if fields.get("severity") in {"error", "critical", "blocked"}:
        with (log_dir / "errors.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
