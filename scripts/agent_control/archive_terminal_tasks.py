#!/usr/bin/env python3
"""Move terminal AiStudio tasks from the active queue into task history."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from runtime_state_io import ExclusiveFileLock, unlink_durable, write_json_atomic
from task_state_invariants import normalize_terminal_task, validate_task_state


TERMINAL_STATUSES = {"done", "stale_or_superseded"}
EMPTY_QUEUE = {"schema_version": 1, "tasks": []}
EMPTY_HISTORY = {"schema_version": 1, "tasks": []}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path, default: dict[str, Any] | None = None) -> dict[str, Any]:
    if not path.exists():
        return copy.deepcopy(default or {})
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    write_json_atomic(path, payload)


def payload_digest(payload: dict[str, Any]) -> str:
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def archive_transaction_paths(queue_path: Path) -> tuple[Path, Path]:
    runtime_state = queue_path.parent / ".runtime-state"
    return (
        runtime_state / "archive-terminal-tasks.lock",
        runtime_state / "archive-terminal-tasks.transaction.json",
    )


class InjectedArchiveCrash(RuntimeError):
    pass


def _require_expected_state(
    path: Path,
    *,
    before_digest: str,
    after_digest: str,
    missing_default: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], str]:
    current = load_json(path, missing_default)
    current_digest = payload_digest(current)
    if current_digest not in {before_digest, after_digest}:
        raise RuntimeError(f"archive transaction conflict: {path} changed outside the transaction")
    return current, current_digest


def recover_archive_transaction(queue_path: Path, history_path: Path, journal_path: Path) -> dict[str, Any] | None:
    if not journal_path.exists():
        return None
    journal = load_json(journal_path)
    if journal.get("schema_version") != 1 or journal.get("operation") != "archive_terminal_tasks":
        raise RuntimeError(f"unsupported archive transaction journal: {journal_path}")
    if Path(str(journal.get("queue_path") or "")).resolve() != queue_path.resolve():
        raise RuntimeError("archive transaction queue path mismatch")
    if Path(str(journal.get("history_path") or "")).resolve() != history_path.resolve():
        raise RuntimeError("archive transaction history path mismatch")

    queue_after = journal.get("queue_after")
    history_after = journal.get("history_after")
    if not isinstance(queue_after, dict) or not isinstance(history_after, dict):
        raise RuntimeError("archive transaction journal has no durable target payloads")

    _history, history_digest = _require_expected_state(
        history_path,
        before_digest=str(journal.get("history_before_digest") or ""),
        after_digest=str(journal.get("history_after_digest") or ""),
        missing_default=EMPTY_HISTORY,
    )
    if history_digest != journal.get("history_after_digest"):
        write_json_atomic(history_path, history_after)

    _queue, queue_digest = _require_expected_state(
        queue_path,
        before_digest=str(journal.get("queue_before_digest") or ""),
        after_digest=str(journal.get("queue_after_digest") or ""),
        missing_default=EMPTY_QUEUE,
    )
    if queue_digest != journal.get("queue_after_digest"):
        write_json_atomic(queue_path, queue_after)

    transaction_id = str(journal.get("transaction_id") or "unknown")
    unlink_durable(journal_path)
    return {"recovered": True, "transaction_id": transaction_id}


def apply_archive_transaction(
    queue_path: Path,
    history_path: Path,
    *,
    archived_by: str,
    fault_after_stage: str | None = None,
    prepare: Callable[[dict[str, Any], dict[str, Any]], dict[str, Any] | None] | None = None,
) -> dict[str, Any]:
    queue_path = queue_path.resolve()
    history_path = history_path.resolve()
    lock_path, journal_path = archive_transaction_paths(queue_path)
    transaction_id = f"archive-{utc_now().replace(':', '').replace('-', '')}"

    with ExclusiveFileLock(lock_path, run_id=transaction_id, ttl_minutes=30):
        recovery = recover_archive_transaction(queue_path, history_path, journal_path)
        queue_before = load_json(queue_path, EMPTY_QUEUE)
        history_before = load_json(history_path, EMPTY_HISTORY)
        queue_after = copy.deepcopy(queue_before)
        history_after = copy.deepcopy(history_before)
        preparation = prepare(queue_after, history_after) if prepare is not None else None
        preparation_changed_state = (
            payload_digest(queue_before) != payload_digest(queue_after)
            or payload_digest(history_before) != payload_digest(history_after)
        )
        result = archive(queue_after, history_after, archived_by=archived_by)
        result["recovery"] = recovery
        result["preparation"] = preparation or {}
        state_changed = (
            preparation_changed_state
            or result["archived_count"] > 0
            or result["skipped_existing_count"] > 0
        )
        if not state_changed:
            result["transaction_id"] = None
            result["transaction_committed"] = False
            return result

        journal = {
            "schema_version": 1,
            "operation": "archive_terminal_tasks",
            "transaction_id": transaction_id,
            "state": "prepared",
            "prepared_at": utc_now(),
            "queue_path": str(queue_path),
            "history_path": str(history_path),
            "queue_before_digest": payload_digest(queue_before),
            "history_before_digest": payload_digest(history_before),
            "queue_after_digest": payload_digest(queue_after),
            "history_after_digest": payload_digest(history_after),
            "queue_after": queue_after,
            "history_after": history_after,
        }
        write_json_atomic(journal_path, journal)
        if fault_after_stage == "prepared":
            raise InjectedArchiveCrash("injected crash after transaction journal")

        _require_expected_state(
            history_path,
            before_digest=journal["history_before_digest"],
            after_digest=journal["history_after_digest"],
            missing_default=EMPTY_HISTORY,
        )
        write_json_atomic(history_path, history_after)
        journal.update({"state": "history_written", "history_written_at": utc_now()})
        write_json_atomic(journal_path, journal)
        if fault_after_stage == "history_written":
            raise InjectedArchiveCrash("injected crash after history write")

        _require_expected_state(
            queue_path,
            before_digest=journal["queue_before_digest"],
            after_digest=journal["queue_after_digest"],
            missing_default=EMPTY_QUEUE,
        )
        write_json_atomic(queue_path, queue_after)
        journal.update({"state": "queue_written", "queue_written_at": utc_now()})
        write_json_atomic(journal_path, journal)
        if fault_after_stage == "queue_written":
            raise InjectedArchiveCrash("injected crash after queue write")

        unlink_durable(journal_path)
        result["transaction_id"] = transaction_id
        result["transaction_committed"] = True
        return result


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def history_tasks(history: dict[str, Any]) -> list[dict[str, Any]]:
    tasks = history.setdefault("tasks", [])
    if not isinstance(tasks, list):
        raise SystemExit("task history field 'tasks' must be an array")
    return tasks


def archive(queue: dict[str, Any], history: dict[str, Any], *, archived_by: str) -> dict[str, Any]:
    now = utc_now()
    active: list[dict[str, Any]] = []
    archived: list[dict[str, Any]] = []
    skipped_existing_count = 0
    existing_ids = {task_id(item) for item in history_tasks(history)}

    for task in queue.get("tasks") or []:
        if not isinstance(task, dict):
            active.append(task)
            continue
        status = str(task.get("status") or "")
        tid = task_id(task)
        if status not in TERMINAL_STATUSES:
            active.append(task)
            continue
        if tid in existing_ids:
            skipped_existing_count += 1
            continue
        archived_task = normalize_terminal_task(task, now=now)
        invariant_errors = [
            issue for issue in validate_task_state(archived_task) if issue.get("severity") == "error"
        ]
        if invariant_errors:
            codes = ", ".join(str(issue.get("code")) for issue in invariant_errors)
            raise ValueError(f"terminal task {tid or 'missing-id'} violates state invariants: {codes}")
        archived_task.setdefault("final_status", status)
        archived_task.setdefault("archived_at", now)
        archived_task.setdefault("archived_by", archived_by)
        archived.append(archived_task)
        existing_ids.add(tid)

    history.setdefault("schema_version", 1)
    history["updated_at"] = now
    history_tasks(history).extend(archived)
    queue["tasks"] = active
    queue["updated_at"] = now
    return {
        "archived_count": len(archived),
        "active_count": len(active),
        "history_count": len(history_tasks(history)),
        "skipped_existing_count": skipped_existing_count,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", default="AiStudio/Task_manager/task_queue.json")
    parser.add_argument("--history", default="AiStudio/Task_manager/task_history.json")
    parser.add_argument("--archived-by", default="archive_terminal_tasks")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    queue_path = Path(args.queue)
    history_path = Path(args.history)
    if args.apply:
        result = apply_archive_transaction(
            queue_path,
            history_path,
            archived_by=args.archived_by,
        )
    else:
        queue = load_json(queue_path)
        history = load_json(history_path, EMPTY_HISTORY)
        result = archive(queue, history, archived_by=args.archived_by)
    result.update({"queue": str(queue_path), "history": str(history_path), "applied": args.apply})
    print(json.dumps(result, indent=2, ensure_ascii=False) if args.json else result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
