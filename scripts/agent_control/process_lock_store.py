#!/usr/bin/env python3
"""Atomic local process locks with a tracked JSON audit projection."""

from __future__ import annotations

import json
import os
import re
import socket
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

from runtime_state_io import ExclusiveFileLock, LockBusyError, parse_time, utc_now, write_json_atomic


LiveProcessChecker = Callable[[dict[str, Any]], bool | None]


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def lock_path(project_root: Path, process: str) -> Path:
    safe_process = re.sub(r"[^A-Za-z0-9_.-]+", "-", process).strip("-") or "process"
    return project_root / "AiStudio" / "Task_manager" / ".runtime-state" / f"{safe_process}.lock"


def acquire_process_lock(
    project_root: Path,
    process: str,
    run_id: str,
    ttl_minutes: int,
    *,
    live_process_checker: LiveProcessChecker | None = None,
) -> tuple[bool, str | None]:
    audit_path = project_root / "AiStudio" / "Task_manager" / "process_locks.json"
    physical = ExclusiveFileLock(lock_path(project_root, process), run_id=run_id, ttl_minutes=ttl_minutes)
    try:
        physical.acquire()
    except LockBusyError as exc:
        return False, str(exc.holder.get("run_id") or "unknown")

    try:
        data = load_json(audit_path) or {"schema_version": 1, "locks": []}
        locks = data.setdefault("locks", [])
        if not isinstance(locks, list):
            raise ValueError("process_locks.json field 'locks' must be an array")
        now = datetime.now(timezone.utc)
        for lock in locks:
            if not isinstance(lock, dict):
                continue
            if lock.get("process") != process or lock.get("state") != "active":
                continue
            expires_at = parse_time(lock.get("expires_at"))
            if expires_at and expires_at <= now:
                lock.update({"state": "expired", "expired_at": utc_now()})
                continue
            live_process = live_process_checker(lock) if live_process_checker else None
            if live_process is False:
                lock.update(
                    {
                        "state": "released",
                        "released_at": utc_now(),
                        "release_reason": "dead_process",
                        "released_by": "status_orchestrator",
                    }
                )
                continue
            physical.release()
            return False, str(lock.get("run_id") or "unknown")

        now_text = utc_now()
        expires = (now + timedelta(minutes=ttl_minutes)).isoformat(timespec="seconds").replace("+00:00", "Z")
        locks.append(
            {
                "process": process,
                "state": "active",
                "by": "status_orchestrator",
                "at": now_text,
                "expires_at": expires,
                "run_id": run_id,
                "pid": os.getpid(),
                "host": socket.gethostname(),
                "project_root": str(project_root),
                "atomic_lock": str(lock_path(project_root, process).relative_to(project_root)).replace("\\", "/"),
            }
        )
        data["updated_at"] = now_text
        write_json_atomic(audit_path, data)
        return True, None
    except Exception:
        physical.release()
        raise


def release_process_lock(project_root: Path, process: str, run_id: str) -> None:
    audit_path = project_root / "AiStudio" / "Task_manager" / "process_locks.json"
    physical = ExclusiveFileLock(lock_path(project_root, process), run_id=run_id)
    physical.acquired = True
    holder = physical.read_holder()
    if holder.get("run_id") != run_id:
        physical.acquired = False
        return

    data = load_json(audit_path)
    locks = data.get("locks", []) if isinstance(data, dict) else []
    for lock in locks if isinstance(locks, list) else []:
        if (
            isinstance(lock, dict)
            and lock.get("process") == process
            and lock.get("run_id") == run_id
            and lock.get("state") == "active"
        ):
            lock["state"] = "released"
            lock["released_at"] = utc_now()
    if isinstance(data, dict):
        data["updated_at"] = utc_now()
        write_json_atomic(audit_path, data)
    physical.release()
