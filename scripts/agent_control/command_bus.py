#!/usr/bin/env python3
"""Durable AiStudio Command Bus.

The bus stores approved high-level automation commands. It never stores raw
shell input; consumers translate commands to automation_controller.py calls.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

COMMAND_ACTIONS = {"automation.run"}
COMMAND_STATES = {"queued", "leased", "running", "succeeded", "no_op", "blocked", "failed", "cancelled", "expired"}
AUTOMATION_MODES = {"full", "project", "role", "one-task", "worktrees", "status"}
ROLE_MODES = {"all", "architect", "dispatcher", "workers", "integrator", "finalizer", "model_limit_retries", "release_locks", "pr_intake", "result_handoff", "full_intake"}
DEFAULT_LEASE_SECONDS = 300


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_utc(value: Any) -> dt.datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = dt.datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def format_utc(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def command_store_path(runtime_root: Path) -> Path:
    return runtime_root / "command-bus" / "commands.json"


def load_json(path: Path, default: dict[str, Any]) -> dict[str, Any]:
    if not path.exists():
        return dict(default)
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def process_is_alive(pid_text: str) -> bool:
    try:
        pid = int(pid_text.strip())
    except (TypeError, ValueError):
        return False
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def maybe_remove_stale_lock(lock: Path) -> bool:
    try:
        pid_text = lock.read_text(encoding="ascii").strip()
    except FileNotFoundError:
        return False
    except Exception:
        return False
    if process_is_alive(pid_text):
        return False
    try:
        lock.unlink()
    except FileNotFoundError:
        return False
    return True


@contextmanager
def file_lock(path: Path, timeout_seconds: float = 5.0) -> Iterator[None]:
    lock = path.with_suffix(path.suffix + ".lock")
    lock.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout_seconds
    fd: int | None = None
    while fd is None:
        try:
            fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, str(os.getpid()).encode("ascii"))
        except FileExistsError:
            if maybe_remove_stale_lock(lock):
                continue
            if time.monotonic() >= deadline:
                raise TimeoutError(f"command bus lock timeout: {lock}")
            time.sleep(0.05)
    try:
        yield
    finally:
        if fd is not None:
            os.close(fd)
        try:
            lock.unlink()
        except FileNotFoundError:
            pass


def normalize_request(request: dict[str, Any]) -> dict[str, Any]:
    action = str(request.get("action") or "automation.run").strip()
    if action not in COMMAND_ACTIONS:
        raise ValueError(f"unsupported action: {action}")
    mode = str(request.get("mode") or "project").strip()
    if mode == "all":
        mode = "project"
    if mode not in AUTOMATION_MODES:
        raise ValueError(f"unsupported mode: {mode}")
    role = str(request.get("role") or request.get("run_mode") or "all").strip()
    if role not in ROLE_MODES:
        raise ValueError(f"unsupported role: {role}")
    project_id = str(request.get("project_id") or "").strip()
    task_id = str(request.get("task_id") or "").strip()
    worker_id = str(request.get("worker_id") or "auto-worker-5.3-mini").strip()
    worktree_root = str(request.get("worktree_root") or "").strip()
    no_remote_check = bool(request.get("no_remote_check", False))
    max_total_workers = request.get("max_total_workers")
    if max_total_workers is not None:
        try:
            max_total_workers = max(0, int(max_total_workers))
        except (TypeError, ValueError) as exc:
            raise ValueError("max_total_workers must be an integer") from exc
    max_tasks_per_lane = request.get("max_tasks_per_lane")
    if max_tasks_per_lane is not None:
        try:
            max_tasks_per_lane = max(0, int(max_tasks_per_lane))
        except (TypeError, ValueError) as exc:
            raise ValueError("max_tasks_per_lane must be an integer") from exc
    model_limit_retry_limit = request.get("model_limit_retry_limit")
    if model_limit_retry_limit is not None:
        try:
            model_limit_retry_limit = max(0, int(model_limit_retry_limit))
        except (TypeError, ValueError) as exc:
            raise ValueError("model_limit_retry_limit must be an integer") from exc
    if mode in {"project", "role", "one-task"} and not project_id:
        raise ValueError(f"{mode} mode requires project_id")
    if mode == "one-task" and not task_id:
        raise ValueError("one-task mode requires task_id")
    apply = bool(request.get("apply", True))
    return {
        "action": action,
        "mode": mode,
        "role": role,
        "project_id": project_id or None,
        "task_id": task_id or None,
        "worker_id": worker_id,
        "worktree_root": worktree_root or None,
        "no_remote_check": no_remote_check,
        "max_total_workers": max_total_workers,
        "max_tasks_per_lane": max_tasks_per_lane,
        "model_limit_retry_limit": model_limit_retry_limit,
        "apply": apply,
    }


def enqueue(runtime_root: Path, request: dict[str, Any], actor: str = "dashboard", idempotency_key: str = "") -> dict[str, Any]:
    command = normalize_request(request)
    path = command_store_path(runtime_root)
    with file_lock(path):
        store = load_json(path, {"schema_version": "1.0", "commands": []})
        commands = store.get("commands") if isinstance(store.get("commands"), list) else []
        if idempotency_key:
            for existing in commands:
                if isinstance(existing, dict) and existing.get("idempotency_key") == idempotency_key:
                    return {"created": False, "command": existing}
        created_at = now_utc()
        item = {
            "command_id": f"cmd-{uuid.uuid4().hex[:12]}",
            "idempotency_key": idempotency_key or None,
            "state": "queued",
            "created_at": created_at,
            "updated_at": created_at,
            "actor": actor,
            "risk": "write" if command["apply"] else "dry_run",
            **command,
        }
        commands.append(item)
        store.update({"schema_version": "1.0", "updated_at": created_at, "commands": commands})
        write_json(path, store)
        return {"created": True, "command": item}


def list_commands(runtime_root: Path) -> dict[str, Any]:
    path = command_store_path(runtime_root)
    with file_lock(path):
        store = load_json(path, {"schema_version": "1.0", "commands": []})
        commands = store.get("commands") if isinstance(store.get("commands"), list) else []
        return {"schema_version": "1.0", "commands": [c for c in commands if isinstance(c, dict)]}


def update_command(runtime_root: Path, command_id: str, updates: dict[str, Any]) -> dict[str, Any]:
    path = command_store_path(runtime_root)
    with file_lock(path):
        store = load_json(path, {"schema_version": "1.0", "commands": []})
        commands = store.get("commands") if isinstance(store.get("commands"), list) else []
        for item in commands:
            if isinstance(item, dict) and item.get("command_id") == command_id:
                item.update(updates)
                item["updated_at"] = now_utc()
                store["updated_at"] = item["updated_at"]
                write_json(path, store)
                return item
    raise KeyError(command_id)


def reclaim_expired_leases(commands: list[Any], now: dt.datetime, lease_seconds: int) -> int:
    reclaimed = 0
    fallback_ttl = max(1, int(lease_seconds))
    for item in commands:
        if not isinstance(item, dict) or item.get("state") != "leased":
            continue
        expires_at = parse_utc(item.get("lease_expires_at"))
        if expires_at is None:
            leased_at = parse_utc(item.get("leased_at"))
            if leased_at is None:
                continue
            expires_at = leased_at + dt.timedelta(seconds=fallback_ttl)
        if expires_at > now:
            continue
        previous_reclaim_count = item.get("lease_reclaim_count")
        item["state"] = "queued"
        item["previous_lease_owner"] = item.get("lease_owner")
        item["previous_leased_at"] = item.get("leased_at")
        item["lease_expired_at"] = format_utc(now)
        item["lease_reclaim_count"] = int(previous_reclaim_count or 0) + 1
        item.pop("lease_owner", None)
        item.pop("leased_at", None)
        item.pop("lease_expires_at", None)
        item["updated_at"] = item["lease_expired_at"]
        reclaimed += 1
    return reclaimed


def claim_next(runtime_root: Path, lease_owner: str, lease_seconds: int = DEFAULT_LEASE_SECONDS, now: str | None = None) -> dict[str, Any] | None:
    path = command_store_path(runtime_root)
    with file_lock(path):
        store = load_json(path, {"schema_version": "1.0", "commands": []})
        commands = store.get("commands") if isinstance(store.get("commands"), list) else []
        now_dt = parse_utc(now) if now is not None else None
        if now_dt is None:
            now_dt = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
        reclaimed = reclaim_expired_leases(commands, now_dt, lease_seconds)
        for item in commands:
            if not isinstance(item, dict) or item.get("state") != "queued":
                continue
            item["state"] = "leased"
            item["lease_owner"] = lease_owner
            item["leased_at"] = format_utc(now_dt)
            item["lease_expires_at"] = format_utc(now_dt + dt.timedelta(seconds=max(1, int(lease_seconds))))
            item["updated_at"] = item["leased_at"]
            store["updated_at"] = item["updated_at"]
            write_json(path, store)
            return item
        if reclaimed:
            store["updated_at"] = format_utc(now_dt)
            write_json(path, store)
    return None



def cancel_command(runtime_root: Path, command_id: str, reason: str = "cancelled_by_operator") -> dict[str, Any]:
    path = command_store_path(runtime_root)
    with file_lock(path):
        store = load_json(path, {"schema_version": "1.0", "commands": []})
        commands = store.get("commands") if isinstance(store.get("commands"), list) else []
        for item in commands:
            if not isinstance(item, dict) or item.get("command_id") != command_id:
                continue
            if item.get("state") not in {"queued", "leased"}:
                return {"cancelled": False, "reason": "command_not_cancellable", "command": item}
            item["state"] = "cancelled"
            item["cancel_reason"] = reason
            item["updated_at"] = now_utc()
            store["updated_at"] = item["updated_at"]
            write_json(path, store)
            return {"cancelled": True, "command": item}
    raise KeyError(command_id)

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["enqueue", "list", "claim", "update", "cancel"])
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--actor", default="cli")
    parser.add_argument("--idempotency-key", default="")
    parser.add_argument("--lease-owner", default="command-consumer")
    parser.add_argument("--command-id", default="")
    parser.add_argument("--state", choices=sorted(COMMAND_STATES))
    parser.add_argument("--mode", default="project")
    parser.add_argument("--role", default="all")
    parser.add_argument("--project-id", default="")
    parser.add_argument("--task-id", default="")
    parser.add_argument("--worker-id", default="auto-worker-5.3-mini")
    parser.add_argument("--worktree-root", default="")
    parser.add_argument("--no-remote-check", action="store_true")
    parser.add_argument("--max-total-workers", type=int)
    parser.add_argument("--max-tasks-per-lane", type=int)
    parser.add_argument("--model-limit-retry-limit", type=int)
    parser.add_argument("--dry-run-command", action="store_true")
    parser.add_argument("--reason", default="cancelled_by_operator")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    runtime_root = Path(args.runtime_root).expanduser()
    if args.action == "enqueue":
        result = enqueue(
            runtime_root,
            {
                "mode": args.mode,
                "role": args.role,
                "project_id": args.project_id,
                "task_id": args.task_id,
                "worker_id": args.worker_id,
                "worktree_root": args.worktree_root,
                "no_remote_check": args.no_remote_check,
                "max_total_workers": args.max_total_workers,
                "max_tasks_per_lane": args.max_tasks_per_lane,
                "model_limit_retry_limit": args.model_limit_retry_limit,
                "apply": not args.dry_run_command,
            },
            args.actor,
            args.idempotency_key,
        )
    elif args.action == "list":
        result = list_commands(runtime_root)
    elif args.action == "claim":
        result = {"command": claim_next(runtime_root, args.lease_owner)}
    elif args.action == "update":
        if not args.command_id or not args.state:
            raise SystemExit("update requires --command-id and --state")
        result = update_command(runtime_root, args.command_id, {"state": args.state})
    else:
        if not args.command_id:
            raise SystemExit("cancel requires --command-id")
        result = cancel_command(runtime_root, args.command_id, args.reason)
    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
