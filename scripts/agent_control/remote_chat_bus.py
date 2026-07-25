#!/usr/bin/env python3
"""Durable chat store for the remote AiStudio chat bridge."""

from __future__ import annotations

import datetime as dt
import json
import os
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


MESSAGE_STATES = {"queued", "leased", "running", "answered", "failed", "cancelled"}
HUMAN_INTERVENTION_REQUEST_STATES = {"pending", "notified", "accepted", "expired", "cancelled", "superseded"}
HUMAN_INTERVENTION_RESPONSE_STATES = {"accepted", "rejected", "duplicate", "invalid"}
MESSAGE_ROLES = {"user", "assistant", "system"}
SESSION_STATUSES = {"planning", "running", "paused", "blocked", "done", "cancelled"}
TASK_STATUSES = {"pending", "in_progress", "done", "blocked", "cancelled"}
CHAT_MODES = {
    "general",
    "worker",
    "architect",
    "dispatcher",
    "integrator",
    "finalizer",
    "automation_debug",
    "decision_council",
    "project_design",
}
DEFAULT_LEASE_SECONDS = 900


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


def store_path(runtime_root: Path) -> Path:
    return runtime_root / "chat-bridge" / "messages.json"


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


def normalize_session_progress(session: dict[str, Any], tasks: list[dict[str, Any]]) -> None:
    total = int(max(0, int(session.get("progress_total") or len(tasks) or 0)))
    if tasks:
        total = len(tasks)
    done = int(sum(1 for item in tasks if str(item.get("status") or "") == "done"))
    eta = session.get("eta_minutes_estimate")
    if total:
        percent = int(round((done / total) * 100))
    else:
        percent = 0
    active_step = next(
        (
            str(item.get("text") or "").strip()
            for item in sorted(tasks, key=lambda item: (safe_int(item.get("order_index") or 999), str(item.get("created_at") or "")))
            if str(item.get("status") or "") in {"running", "in_progress"}
        ),
        "",
    )
    if not active_step:
        active_step = next(
            (
                str(item.get("text") or "").strip()
                for item in sorted(tasks, key=lambda item: (safe_int(item.get("order_index") or 999), str(item.get("created_at") or "")))
                if str(item.get("status") or "") == "pending"
            ),
            "",
        )
    session["progress_total"] = total
    session["progress_done"] = done
    session["progress_percent"] = percent
    session["active_step"] = active_step
    session["progress_line"] = f"{done}/{total} ({percent}%)"
    if eta is not None and str(eta).strip():
        session["eta_minutes_estimate"] = int(max(0, safe_int(str(eta).strip())))
    elif session.get("eta_minutes_estimate") is not None:
        session.pop("eta_minutes_estimate", None)


def estimate_remaining_session_eta(tasks: list[dict[str, Any]]) -> int:
    total = 0
    for task in tasks:
        if not isinstance(task, dict):
            continue
        status = str(task.get("status") or "").strip()
        if status in {"done", "cancelled"}:
            continue
        eta = task.get("eta_minutes")
        if eta is None:
            continue
        total += max(0, safe_int(eta))
    return total


def safe_int(value: Any, fallback: int = 0) -> int:
    try:
        return int(float(str(value).strip()))
    except (TypeError, ValueError):
        return fallback


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
                raise TimeoutError(f"chat bridge lock timeout: {lock}")
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


def empty_store() -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "sessions": [],
        "messages": [],
        "tasks": [],
        "human_interventions": [],
        "human_intervention_responses": [],
    }


def normalize_store(store: dict[str, Any]) -> dict[str, Any]:
    sessions = store.get("sessions") if isinstance(store.get("sessions"), list) else []
    messages = store.get("messages") if isinstance(store.get("messages"), list) else []
    tasks = store.get("tasks") if isinstance(store.get("tasks"), list) else []
    human_interventions = store.get("human_interventions") if isinstance(store.get("human_interventions"), list) else []
    human_intervention_responses = (
        store.get("human_intervention_responses") if isinstance(store.get("human_intervention_responses"), list) else []
    )
    store["tasks"] = [m for m in tasks if isinstance(m, dict)]
    store["schema_version"] = str(store.get("schema_version") or "1.0")
    store["sessions"] = [s for s in sessions if isinstance(s, dict)]
    store["messages"] = [m for m in messages if isinstance(m, dict)]
    store["human_interventions"] = [item for item in human_interventions if isinstance(item, dict)]
    store["human_intervention_responses"] = [
        item for item in human_intervention_responses if isinstance(item, dict)
    ]
    return store


def compact_session_task_orders(store: dict[str, Any], session_id: str) -> None:
    tasks = [task for task in store.get("tasks", []) if str(task.get("session_id") or "") == str(session_id)]
    tasks.sort(key=lambda item: (safe_int(item.get("order_index") or 0), str(item.get("created_at") or ""), str(item.get("task_id") or "")))
    for index, task in enumerate(tasks, start=1):
        if task.get("order_index") != index:
            task["order_index"] = index
            task["updated_at"] = now_utc()


def recompute_project_eta(
    runtime_root: Path,
    *,
    project_id: str | None = None,
) -> list[dict[str, Any]]:
    project_value = (project_id or "").strip()
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        updated_at = now_utc()
        target_sessions = store["sessions"]
        if project_value and project_value != "all":
            target_sessions = [item for item in store["sessions"] if str(item.get("project_id") or "") == project_value]
        updated: list[dict[str, Any]] = []
        for session in target_sessions:
            session_id = str(session.get("session_id") or "")
            if not session_id:
                continue
            session_tasks = [task for task in store["tasks"] if str(task.get("session_id") or "") == session_id]
            session["eta_minutes_estimate"] = estimate_remaining_session_eta(session_tasks)
            normalize_session_progress(session, session_tasks)
            session["updated_at"] = updated_at
            updated.append(dict(session))
        if target_sessions:
            store["updated_at"] = updated_at
            write_json(path, store)
        return updated


def delete_session_task(
    runtime_root: Path,
    *,
    task_id: str,
) -> dict[str, Any]:
    if not task_id:
        raise KeyError(task_id)
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        removed_task = None
        session_id = ""
        for index, task in enumerate(store["tasks"]):
            if task.get("task_id") == task_id:
                removed_task = store["tasks"].pop(index)
                session_id = str(removed_task.get("session_id") or "")
                break
        if removed_task is None:
            raise KeyError(task_id)
        if session_id:
            compact_session_task_orders(store, session_id)
            for session in store["sessions"]:
                if session.get("session_id") == session_id:
                    normalize_session_progress(session, [item for item in store["tasks"] if str(item.get("session_id")) == session_id])
                    break
        store["updated_at"] = now_utc()
        write_json(path, store)
        return dict(removed_task)


def get_or_create_session(
    runtime_root: Path,
    *,
    channel: str = "web",
    external_id: str = "",
    title: str = "",
    actor: str = "user",
    project_id: str = "",
    status: str = "planning",
    eta_minutes_estimate: int | None = None,
    chat_mode: str = "general",
    skill: str = "",
) -> dict[str, Any]:
    path = store_path(runtime_root)
    channel = str(channel or "web").strip() or "web"
    external_id = str(external_id or "").strip()
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        for session in store["sessions"]:
            if session.get("channel") == channel and external_id and session.get("external_id") == external_id:
                return session
        created_at = now_utc()
        session = {
            "session_id": f"chat-{uuid.uuid4().hex[:12]}",
            "channel": channel,
            "external_id": external_id or None,
            "title": title.strip() or None,
            "actor": actor,
            "project_id": project_id.strip() or None,
            "status": status if status in SESSION_STATUSES else "planning",
            "chat_mode": chat_mode if chat_mode in CHAT_MODES else "general",
            "skill": skill.strip() or None,
            "progress_total": 0,
            "progress_done": 0,
            "progress_percent": 0,
            "eta_minutes_estimate": int(eta_minutes_estimate) if eta_minutes_estimate is not None else None,
            "created_at": created_at,
            "updated_at": created_at,
        }
        store["sessions"].append(session)
        store["updated_at"] = created_at
        write_json(path, store)
        return session


def add_user_message(
    runtime_root: Path,
    *,
    session_id: str,
    text: str,
    actor: str = "user",
    channel_message_id: str = "",
    metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    text = str(text or "").strip()
    if not text:
        raise ValueError("message text is required")
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        if not any(s.get("session_id") == session_id for s in store["sessions"]):
            raise KeyError(session_id)
        created_at = now_utc()
        item = {
            "message_id": f"msg-{uuid.uuid4().hex[:12]}",
            "session_id": session_id,
            "role": "user",
            "state": "queued",
            "text": text,
            "actor": actor,
            "channel_message_id": channel_message_id or None,
            "metadata": metadata or {},
            "created_at": created_at,
            "updated_at": created_at,
        }
        store["messages"].append(item)
        for session in store["sessions"]:
            if session.get("session_id") == session_id:
                session["updated_at"] = created_at
                if not session.get("title"):
                    session["title"] = text[:80]
                break
        store["updated_at"] = created_at
        write_json(path, store)
        return item


def add_system_message(
    runtime_root: Path,
    *,
    session_id: str,
    text: str,
    actor: str = "system",
    channel_message_id: str = "",
    metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    text = str(text or "").strip()
    if not text:
        raise ValueError("message text is required")
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        if not any(s.get("session_id") == session_id for s in store["sessions"]):
            raise KeyError(session_id)
        created_at = now_utc()
        item = {
            "message_id": f"msg-{uuid.uuid4().hex[:12]}",
            "session_id": session_id,
            "role": "system",
            "state": "answered",
            "text": text,
            "actor": actor,
            "channel_message_id": channel_message_id or None,
            "metadata": metadata or {},
            "created_at": created_at,
            "updated_at": created_at,
        }
        store["messages"].append(item)
        for session in store["sessions"]:
            if session.get("session_id") == session_id:
                session["updated_at"] = created_at
                break
        store["updated_at"] = created_at
        write_json(path, store)
        return item


def messages_for_session(runtime_root: Path, session_id: str, *, limit: int = 50) -> list[dict[str, Any]]:
    state = list_state(runtime_root)
    messages = [m for m in state["messages"] if m.get("session_id") == session_id]
    messages.sort(key=lambda m: str(m.get("created_at") or ""))
    if limit > 0:
        messages = messages[-limit:]
    return messages


def reclaim_expired_leases(messages: list[Any], now: dt.datetime, lease_seconds: int) -> int:
    reclaimed = 0
    ttl = max(1, int(lease_seconds))
    for item in messages:
        if not isinstance(item, dict) or item.get("state") != "leased":
            continue
        expires_at = parse_utc(item.get("lease_expires_at"))
        if expires_at is None:
            leased_at = parse_utc(item.get("leased_at"))
            if leased_at is None:
                continue
            expires_at = leased_at + dt.timedelta(seconds=ttl)
        if expires_at > now:
            continue
        item["state"] = "queued"
        item["previous_lease_owner"] = item.get("lease_owner")
        item["lease_expired_at"] = format_utc(now)
        item.pop("lease_owner", None)
        item.pop("leased_at", None)
        item.pop("lease_expires_at", None)
        item["updated_at"] = item["lease_expired_at"]
        reclaimed += 1
    return reclaimed


def claim_next(
    runtime_root: Path,
    lease_owner: str,
    *,
    lease_seconds: int = DEFAULT_LEASE_SECONDS,
    now: str | None = None,
) -> dict[str, Any] | None:
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        current = parse_utc(now) if now else dt.datetime.now(dt.timezone.utc)
        assert current is not None
        reclaimed = reclaim_expired_leases(store["messages"], current, lease_seconds)
        for item in store["messages"]:
            if item.get("role") != "user" or item.get("state") != "queued":
                continue
            leased_at = format_utc(current)
            item["state"] = "leased"
            item["lease_owner"] = lease_owner
            item["leased_at"] = leased_at
            item["lease_expires_at"] = format_utc(current + dt.timedelta(seconds=max(1, int(lease_seconds))))
            item["updated_at"] = leased_at
            store["updated_at"] = leased_at
            if reclaimed:
                store["lease_reclaimed_count"] = int(store.get("lease_reclaimed_count") or 0) + reclaimed
            write_json(path, store)
            return dict(item)
        if reclaimed:
            store["updated_at"] = format_utc(current)
            store["lease_reclaimed_count"] = int(store.get("lease_reclaimed_count") or 0) + reclaimed
            write_json(path, store)
    return None


def list_sessions(runtime_root: Path, *, project_id: str | None = None) -> list[dict[str, Any]]:
    state = list_state(runtime_root)
    sessions = state["sessions"]
    if project_id and project_id != "all":
        return [s for s in sessions if str(s.get("project_id") or "") == project_id]
    return sessions


def list_tasks(runtime_root: Path, *, session_id: str | None = None) -> list[dict[str, Any]]:
    state = list_state(runtime_root)
    if not session_id:
        return state["tasks"]
    return [t for t in state["tasks"] if str(t.get("session_id") or "") == str(session_id)]


def add_session_task(
    runtime_root: Path,
    *,
    session_id: str,
    text: str,
    status: str = "pending",
    eta_minutes: int | None = None,
    order_index: int | None = None,
) -> dict[str, Any]:
    text = str(text or "").strip()
    if not text:
        raise ValueError("task text is required")
    status = str(status).strip() if status else "pending"
    if status not in TASK_STATUSES:
        raise ValueError(f"invalid task status: {status}")
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        if not any(s.get("session_id") == session_id for s in store["sessions"]):
            raise KeyError(session_id)
        session_tasks = [t for t in store["tasks"] if str(t.get("session_id")) == str(session_id)]
        if order_index is None:
            order_index = len(session_tasks) + 1
        created_at = now_utc()
        item = {
            "task_id": f"task-{uuid.uuid4().hex[:12]}",
            "session_id": session_id,
            "text": text,
            "status": status,
            "eta_minutes": int(eta_minutes) if eta_minutes is not None else None,
            "order_index": int(order_index),
            "created_at": created_at,
            "updated_at": created_at,
        }
        store["tasks"].append(item)
        for session in store["sessions"]:
            if session.get("session_id") == session_id:
                normalize_session_progress(session, [t for t in store["tasks"] if str(t.get("session_id")) == str(session_id)])
                break
        store["updated_at"] = created_at
        write_json(path, store)
        return item


def update_session_task(
    runtime_root: Path,
    *,
    task_id: str,
    status: str | None = None,
    text: str | None = None,
    eta_minutes: int | None = None,
    order_index: int | None = None,
) -> dict[str, Any]:
    if not task_id:
        raise KeyError(task_id)
    if status is not None:
        status = str(status).strip()
        if status not in TASK_STATUSES:
            raise ValueError(f"invalid task status: {status}")
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        updated_task = None
        session_id = ""
        for item in store["tasks"]:
            if item.get("task_id") == task_id:
                if status is not None:
                    item["status"] = status
                if text is not None:
                    item["text"] = str(text).strip()
                if eta_minutes is not None:
                    item["eta_minutes"] = int(eta_minutes)
                if order_index is not None:
                    item["order_index"] = int(order_index)
                item["updated_at"] = now_utc()
                updated_task = item
                session_id = str(item.get("session_id") or "")
                break
        if updated_task is None:
            raise KeyError(task_id)
        for session in store["sessions"]:
            if session.get("session_id") == session_id:
                normalize_session_progress(session, [t for t in store["tasks"] if str(t.get("session_id")) == session_id])
                break
        store["updated_at"] = now_utc()
        write_json(path, store)
        return dict(updated_task)


def update_session(
    runtime_root: Path,
    *,
    session_id: str,
    title: str | None = None,
    project_id: str | None = None,
    status: str | None = None,
    eta_minutes_estimate: int | None = None,
    chat_mode: str | None = None,
    skill: str | None = None,
) -> dict[str, Any]:
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        for session in store["sessions"]:
            if session.get("session_id") == session_id:
                if title is not None:
                    session["title"] = str(title).strip() or session.get("title")
                if project_id is not None:
                    session["project_id"] = str(project_id).strip() or None
                if status is not None:
                    status = str(status).strip()
                    session["status"] = status if status in SESSION_STATUSES else session.get("status", "planning")
                if eta_minutes_estimate is not None:
                    session["eta_minutes_estimate"] = int(eta_minutes_estimate)
                if chat_mode is not None:
                    chat_mode = str(chat_mode).strip()
                    session["chat_mode"] = chat_mode if chat_mode in CHAT_MODES else session.get("chat_mode", "general")
                if skill is not None:
                    session["skill"] = str(skill).strip() or None
                updated_at = now_utc()
                session["updated_at"] = updated_at
                normalize_session_progress(session, [t for t in store["tasks"] if str(t.get("session_id")) == session_id])
                store["updated_at"] = updated_at
                write_json(path, store)
                return dict(session)
    raise KeyError(session_id)


def list_state(runtime_root: Path) -> dict[str, Any]:
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        tasks = store["tasks"]
        for session in store["sessions"]:
            if str(session.get("chat_mode") or "").strip() not in CHAT_MODES:
                session["chat_mode"] = "general"
            normalize_session_progress(session, [t for t in tasks if str(t.get("session_id")) == str(session.get("session_id"))])
        store["human_interventions"] = sorted(
            store["human_interventions"], key=lambda item: str(item.get("created_at") or ""), reverse=True
        )
        store["sessions"] = sorted(store["sessions"], key=lambda s: str(s.get("updated_at") or ""), reverse=True)
        store["messages"] = sorted(store["messages"], key=lambda m: str(m.get("created_at") or ""))
        return store


def mark_running(runtime_root: Path, message_id: str) -> dict[str, Any]:
    return update_message(runtime_root, message_id, {"state": "running", "started_at": now_utc()})


def answer_message(
    runtime_root: Path,
    message_id: str,
    *,
    text: str,
    worker_id: str,
    run: dict[str, Any] | None = None,
) -> dict[str, Any]:
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        finished_at = now_utc()
        source: dict[str, Any] | None = None
        for item in store["messages"]:
            if item.get("message_id") == message_id:
                source = item
                item.update(
                    {
                        "state": "answered",
                        "answered_at": finished_at,
                        "updated_at": finished_at,
                        "worker_id": worker_id,
                        "run": run or {},
                    }
                )
                item.pop("lease_owner", None)
                item.pop("lease_expires_at", None)
                break
        if source is None:
            raise KeyError(message_id)
        reply = {
            "message_id": f"msg-{uuid.uuid4().hex[:12]}",
            "session_id": source["session_id"],
            "reply_to": message_id,
            "role": "assistant",
            "state": "answered",
            "text": str(text or "").strip() or "(empty response)",
            "actor": worker_id,
            "created_at": finished_at,
            "updated_at": finished_at,
            "run": run or {},
        }
        store["messages"].append(reply)
        for session in store["sessions"]:
            if session.get("session_id") == source.get("session_id"):
                session["updated_at"] = finished_at
                break
        store["updated_at"] = finished_at
        write_json(path, store)
        return reply


def fail_message(runtime_root: Path, message_id: str, *, error: str, worker_id: str) -> dict[str, Any]:
    return update_message(
        runtime_root,
        message_id,
        {
            "state": "failed",
            "failed_at": now_utc(),
            "error": str(error or "unknown error")[:4000],
            "worker_id": worker_id,
            "lease_owner": None,
            "lease_expires_at": None,
        },
    )


def update_message(runtime_root: Path, message_id: str, updates: dict[str, Any]) -> dict[str, Any]:
    path = store_path(runtime_root)
    with file_lock(path):
        store = normalize_store(load_json(path, empty_store()))
        for item in store["messages"]:
            if item.get("message_id") == message_id:
                for key, value in updates.items():
                    if value is None:
                        item.pop(key, None)
                    else:
                        item[key] = value
                item["updated_at"] = now_utc()
                store["updated_at"] = item["updated_at"]
                write_json(path, store)
                return dict(item)
    raise KeyError(message_id)
