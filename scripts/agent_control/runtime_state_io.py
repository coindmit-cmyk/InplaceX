#!/usr/bin/env python3
"""Crash-safe local filesystem primitives for Agent Core runtime state."""

from __future__ import annotations

import json
import os
import socket
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def pid_is_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        import ctypes

        process_query_limited_information = 0x1000
        handle = ctypes.windll.kernel32.OpenProcess(process_query_limited_information, False, pid)
        if handle:
            ctypes.windll.kernel32.CloseHandle(handle)
            return True
        return ctypes.get_last_error() == 5
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def fsync_directory(path: Path) -> None:
    if os.name != "posix":
        return
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def unlink_durable(path: Path) -> None:
    path.unlink(missing_ok=True)
    if path.parent.exists():
        fsync_directory(path.parent)


class LockBusyError(RuntimeError):
    def __init__(self, holder: dict[str, Any] | None = None):
        super().__init__("runtime state lock is already held")
        self.holder = holder or {}


@dataclass(frozen=True)
class LockFileSnapshot:
    holder: dict[str, Any]
    identity: tuple[int, int, int, int, int]
    age_seconds: float


class RecoveryGuard:
    """OS-released advisory guard for lock recovery and ownership changes."""

    def __init__(self, path: Path, *, timeout_seconds: float = 5.0):
        self.path = path
        self.timeout_seconds = timeout_seconds
        self.handle: Any = None

    def _try_lock(self) -> bool:
        assert self.handle is not None
        if os.name == "nt":
            import msvcrt

            self.handle.seek(0)
            try:
                msvcrt.locking(self.handle.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                return False
            return True

        import fcntl

        try:
            fcntl.flock(self.handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return False
        return True

    def __enter__(self) -> "RecoveryGuard":
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = self.path.open("a+b")
        if os.name == "nt" and self.path.stat().st_size == 0:
            self.handle.write(b"\0")
            self.handle.flush()
        deadline = time.monotonic() + self.timeout_seconds
        while not self._try_lock():
            if time.monotonic() >= deadline:
                self.handle.close()
                self.handle = None
                raise LockBusyError({"recovery_guard": str(self.path)})
            time.sleep(0.002)
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        assert self.handle is not None
        if os.name == "nt":
            import msvcrt

            self.handle.seek(0)
            msvcrt.locking(self.handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(self.handle.fileno(), fcntl.LOCK_UN)
        self.handle.close()
        self.handle = None


class ExclusiveFileLock:
    """Portable O_EXCL lock whose metadata survives process crashes."""

    def __init__(
        self,
        path: Path,
        *,
        run_id: str,
        ttl_minutes: int = 30,
        incomplete_grace_seconds: float = 5.0,
    ):
        self.path = path
        self.run_id = run_id
        self.ttl_minutes = ttl_minutes
        self.incomplete_grace_seconds = incomplete_grace_seconds
        self.acquired = False

    @property
    def recovery_guard_path(self) -> Path:
        return self.path.with_name(f".{self.path.name}.recovery.guard")

    def _payload(self) -> dict[str, Any]:
        now = datetime.now(timezone.utc)
        return {
            "schema_version": 1,
            "run_id": self.run_id,
            "pid": os.getpid(),
            "host": socket.gethostname(),
            "acquired_at": now.isoformat(timespec="seconds").replace("+00:00", "Z"),
            "expires_at": (now + timedelta(minutes=self.ttl_minutes))
            .isoformat(timespec="seconds")
            .replace("+00:00", "Z"),
        }

    def read_holder(self) -> dict[str, Any]:
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
        return value if isinstance(value, dict) else {}

    def read_snapshot(self) -> LockFileSnapshot | None:
        try:
            with self.path.open("rb") as handle:
                stat_result = os.fstat(handle.fileno())
                raw = handle.read()
        except FileNotFoundError:
            return None
        except OSError:
            return None
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            value = {}
        holder = value if isinstance(value, dict) else {}
        identity = (
            int(stat_result.st_dev),
            int(stat_result.st_ino),
            int(stat_result.st_size),
            int(stat_result.st_mtime_ns),
            int(stat_result.st_ctime_ns),
        )
        return LockFileSnapshot(
            holder=holder,
            identity=identity,
            age_seconds=max(0.0, time.time() - stat_result.st_mtime),
        )

    def read_snapshot_bounded(
        self,
        *,
        attempts: int = 50,
        delay_seconds: float = 0.002,
    ) -> LockFileSnapshot | None:
        """Wait briefly for the O_EXCL winner to publish its lock metadata."""
        snapshot: LockFileSnapshot | None = None
        for attempt in range(attempts):
            snapshot = self.read_snapshot()
            if snapshot is None or snapshot.holder.get("run_id"):
                return snapshot
            if attempt + 1 < attempts:
                time.sleep(delay_seconds)
        return snapshot

    def read_holder_bounded(self, *, attempts: int = 50, delay_seconds: float = 0.002) -> dict[str, Any]:
        """Wait briefly for the O_EXCL winner to publish its lock metadata."""
        snapshot = self.read_snapshot_bounded(attempts=attempts, delay_seconds=delay_seconds)
        return snapshot.holder if snapshot is not None else {}

    def _holder_is_stale(self, holder: dict[str, Any]) -> bool:
        expires_at = parse_time(holder.get("expires_at"))
        if expires_at and expires_at <= datetime.now(timezone.utc):
            return True
        if holder.get("host") != socket.gethostname():
            return False
        pid = holder.get("pid")
        if isinstance(pid, str) and pid.isdigit():
            pid = int(pid)
        return isinstance(pid, int) and not pid_is_alive(pid)

    def _snapshot_is_recoverable(self, snapshot: LockFileSnapshot) -> bool:
        if snapshot.holder.get("run_id"):
            return self._holder_is_stale(snapshot.holder)
        return snapshot.age_seconds >= self.incomplete_grace_seconds

    @staticmethod
    def _same_lock_object(
        checked: LockFileSnapshot,
        current: LockFileSnapshot,
    ) -> bool:
        return (
            checked.identity == current.identity
            and checked.holder.get("run_id") == current.holder.get("run_id")
        )

    def _create_locked(self, payload: dict[str, Any], raw: bytes) -> dict[str, Any]:
        descriptor = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            os.write(descriptor, raw)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        fsync_directory(self.path.parent)
        self.acquired = True
        return payload

    def acquire(self) -> dict[str, Any]:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = self._payload()
        raw = (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        for _attempt in range(5):
            checked = self.read_snapshot_bounded()
            if checked is not None and not self._snapshot_is_recoverable(checked):
                raise LockBusyError(checked.holder)
            try:
                with RecoveryGuard(self.recovery_guard_path):
                    current = self.read_snapshot_bounded()
                    if current is None:
                        return self._create_locked(payload, raw)
                    if checked is None:
                        continue
                    if not self._same_lock_object(checked, current):
                        continue
                    if not self._snapshot_is_recoverable(current):
                        raise LockBusyError(current.holder)
                    unlink_durable(self.path)
                    return self._create_locked(payload, raw)
            except FileExistsError:
                continue
        raise LockBusyError(self.read_holder())

    def release(self) -> bool:
        if not self.acquired:
            return False
        with RecoveryGuard(self.recovery_guard_path):
            holder = self.read_holder()
            if holder.get("run_id") != self.run_id:
                self.acquired = False
                return False
            unlink_durable(self.path)
        self.acquired = False
        return True

    def __enter__(self) -> "ExclusiveFileLock":
        self.acquire()
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.release()
