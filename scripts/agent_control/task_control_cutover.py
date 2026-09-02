#!/usr/bin/env python3
"""Fail-closed PostgreSQL authority and compatibility sessions for Task Control."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from runtime_state_io import write_json_atomic
from task_control_backup import verify_manifest
from task_control_postgres import (
    TaskControlConfigurationError,
    TaskControlConflict,
    TaskControlPostgres,
    TaskSnapshot,
    normalized_task_id,
    payload_digest,
    require_project_id,
    utc_now,
)
from task_control_shadow import build_fleet_snapshots


DEFAULT_CONFIG = Path("~/.config/aistudio/task-control.json").expanduser()
CONFIG_ENV = "AISTUDIO_TASK_CONTROL_CONFIG"
TASK_MANAGER_ENV = "AISTUDIO_TASK_MANAGER_DIR"
SESSION_ENV = "AISTUDIO_TASK_CONTROL_SESSION_ID"
AUTHORITY_ENV = "AISTUDIO_TASK_CONTROL_AUTHORITY"
PROJECT_ENV = "AISTUDIO_TASK_CONTROL_PROJECT_ID"
DSN_ENV_NAME_ENV = "AISTUDIO_TASK_DB_DSN_ENV"
COMPATIBILITY_ARTIFACTS = (
    "agent_activity_state.json",
    "agent_events.jsonl",
    "agent_process_state.json",
    "automation_bridge_state.json",
    "repository_hygiene_state.json",
    "worker_pool_last_plan.json",
)
RECOVERABLE_REPOSITORY_ROOTS = (
    "AiStudio/Project_state/intake/inbox",
    "docs/reports/change-intake/pr-cycle",
    "docs/reports/workers",
    "docs/plans",
    "old/agent-runs/finalized",
)


@dataclass(frozen=True)
class TaskControlConfig:
    mode: str
    source_of_truth: str
    cutover_enabled: bool
    dsn_env: str
    registry_path: Path
    required_project_ids: tuple[str, ...]
    include_disabled_projects: bool
    max_shadow_age_seconds: int
    backup_manifest: Path | None
    runtime_root: Path
    session_ttl_seconds: int


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise TaskControlConfigurationError(f"required JSON file is missing: {path}") from None
    except json.JSONDecodeError as exc:
        raise TaskControlConfigurationError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise TaskControlConfigurationError(f"JSON root must be an object: {path}")
    return value


def load_runtime_config(path: Path) -> TaskControlConfig:
    raw = load_json(path.expanduser())
    mode = str(raw.get("mode") or "")
    source = str(raw.get("source_of_truth") or "")
    enabled = raw.get("cutover_enabled")
    if (mode, source, enabled) not in {
        ("shadow", "json_git", False),
        ("cutover", "postgres", True),
    }:
        raise TaskControlConfigurationError(
            "runtime authority must be shadow/json_git/false or cutover/postgres/true"
        )
    dsn_env = str(raw.get("dsn_env") or "")
    if not dsn_env or not dsn_env.replace("_", "").isalnum():
        raise TaskControlConfigurationError("dsn_env must name an environment variable")
    required = raw.get("required_project_ids") or []
    if not isinstance(required, list):
        raise TaskControlConfigurationError("required_project_ids must be an array")
    ttl = int(raw.get("session_ttl_seconds") or 14400)
    if ttl < 60 or ttl > 86400:
        raise TaskControlConfigurationError("session_ttl_seconds must be between 60 and 86400")
    registry = str(raw.get("registry_path") or "")
    if not registry:
        raise TaskControlConfigurationError("registry_path is required")
    manifest = str(raw.get("backup_manifest") or "").strip()
    runtime = str(raw.get("runtime_root") or "~/agent-runtime/task-control").strip()
    return TaskControlConfig(
        mode=mode,
        source_of_truth=source,
        cutover_enabled=enabled is True,
        dsn_env=dsn_env,
        registry_path=Path(registry).expanduser(),
        required_project_ids=tuple(require_project_id(str(item)) for item in required),
        include_disabled_projects=raw.get("include_disabled_projects") is True,
        max_shadow_age_seconds=int(raw.get("max_shadow_age_seconds") or 900),
        backup_manifest=Path(manifest).expanduser() if manifest else None,
        runtime_root=Path(runtime).expanduser(),
        session_ttl_seconds=ttl,
    )


def configured_path(explicit: str | Path | None = None) -> Path | None:
    value = str(explicit or os.environ.get(CONFIG_ENV, "")).strip()
    if value:
        return Path(value).expanduser()
    return DEFAULT_CONFIG if DEFAULT_CONFIG.is_file() else None


def database_for(config: TaskControlConfig) -> TaskControlPostgres:
    dsn = os.environ.get(config.dsn_env, "")
    if not dsn:
        raise TaskControlConfigurationError(
            f"required DSN environment variable is missing: {config.dsn_env}"
        )
    return TaskControlPostgres(dsn)


def validate_documents(queue: dict[str, Any], history: dict[str, Any]) -> None:
    queue_tasks = queue.get("tasks")
    history_tasks = history.get("tasks")
    if not isinstance(queue_tasks, list) or not isinstance(history_tasks, list):
        raise TaskControlConfigurationError("queue and history must contain tasks arrays")
    queue_ids = [normalized_task_id(task) for task in queue_tasks if isinstance(task, dict)]
    history_ids = [normalized_task_id(task) for task in history_tasks if isinstance(task, dict)]
    if len(queue_ids) != len(queue_tasks) or len(history_ids) != len(history_tasks):
        raise TaskControlConfigurationError("every queue/history task must be an object")
    duplicates = sorted(
        {task_id for task_id in queue_ids if queue_ids.count(task_id) > 1}
        | {task_id for task_id in history_ids if history_ids.count(task_id) > 1}
    )
    overlap = sorted(set(queue_ids) & set(history_ids))
    if duplicates:
        raise TaskControlConfigurationError(f"duplicate task ids: {duplicates}")
    if overlap:
        raise TaskControlConfigurationError(f"queue/history task overlap: {overlap}")


def snapshot_from_documents(
    *,
    project_id: str,
    queue: dict[str, Any],
    history: dict[str, Any],
    source_revision: str | None,
) -> TaskSnapshot:
    validate_documents(queue, history)
    records: dict[str, dict[str, Any]] = {}
    sources: dict[tuple[str, str], dict[str, Any]] = {}
    for source_kind, document in (("history", history), ("queue", queue)):
        for ordinal, task in enumerate(document["tasks"]):
            task_id = normalized_task_id(task)
            record = {
                "source_kind": source_kind,
                "source_ordinal": ordinal,
                "source_digest": payload_digest(task),
                "payload": task,
            }
            records[task_id] = record
            sources[(task_id, source_kind)] = record
    envelopes = {
        "queue": {key: value for key, value in queue.items() if key != "tasks"},
        "history": {key: value for key, value in history.items() if key != "tasks"},
    }
    digest = payload_digest({"queue": queue, "history": history})
    return TaskSnapshot(
        project_id=project_id,
        repository=None,
        base_branch="develop",
        source_revision=source_revision,
        source_digest=digest,
        tasks=records,
        source_records=sources,
        source_documents=envelopes,
    )


class CutoverSession:
    def __init__(
        self,
        *,
        database: TaskControlPostgres,
        config: TaskControlConfig,
        project_id: str,
        project_root: Path,
        run_id: str,
        owner_id: str,
    ) -> None:
        self.database = database
        self.config = config
        self.project_id = require_project_id(project_id)
        self.project_root = project_root.expanduser().resolve()
        self.run_id = run_id
        self.owner_id = owner_id
        self.session_id = ""
        self.session_root: Path | None = None
        self.task_manager: Path | None = None
        self.base_revision: str | None = None
        self.finished = False
        self._previous_env: dict[str, str | None] = {}
        self._publication_guard: Any | None = None
        self._recovered_publication_markers: list[Path] = []
        self._compatibility_publication_generation = 0
        self._current_publication_marker: Path | None = None
        self._recovered_repository_originals: dict[str, bytes | None] = {}
        self._heartbeat: CutoverSessionHeartbeat | None = None

    def prepare(self) -> dict[str, Any]:
        if not self.config.cutover_enabled:
            raise TaskControlConfigurationError("cutover session requires cutover configuration")
        health = self.database.health()
        if (
            health.get("mode"),
            health.get("source_of_truth"),
            health.get("cutover_enabled"),
        ) != ("cutover", "postgres", True):
            raise TaskControlConfigurationError("database is not the active Task Control authority")
        started = self.database.start_project_session(
            self.project_id,
            run_id=self.run_id,
            owner_id=self.owner_id,
            ttl_seconds=self.config.session_ttl_seconds,
            metadata={"project_root": str(self.project_root)},
        )
        if not started.get("ok"):
            raise TaskControlConflict(str(started.get("reason") or "project session unavailable"))
        self.session_id = str(started["session_id"])
        self._publication_guard = self.database.project_publication_guard(self.project_id)
        try:
            self._publication_guard.__enter__()
        except Exception:
            self._publication_guard = None
            self.database.abort_project_session(
                self.session_id,
                reason="project_publication_guard_failed",
            )
            raise
        integration_recovery = self._recover_aborted_integrations()
        if integration_recovery["recovered"]:
            started["snapshot"] = self.database.export_project(self.project_id)
        self.session_root = (
            self.config.runtime_root / "sessions" / self.project_id / self.session_id
        ).resolve()
        self.task_manager = self.session_root / "Task_manager"
        self.task_manager.mkdir(parents=True, exist_ok=False)
        legacy = self.project_root / "AiStudio" / "Task_manager"
        if legacy.is_dir():
            shutil.copytree(legacy, self.task_manager, dirs_exist_ok=True)
        publication_recovery = self._recover_pending_compatibility_publication()
        exported = started["snapshot"]
        write_json_atomic(self.task_manager / "task_queue.json", exported["queue"])
        write_json_atomic(self.task_manager / "task_history.json", exported["history"])
        # Git locks are frozen compatibility data after cutover. SQL leases
        # are authoritative and each session must build its own lock mirror.
        write_json_atomic(
            self.task_manager / "agent_locks.json",
            {"schema_version": 1, "locks": []},
        )
        self.base_revision = str(exported.get("source_revision") or "") or None
        write_json_atomic(
            self.session_root / "session.json",
            {
                "schema_version": 1,
                "state": "prepared",
                "session_id": self.session_id,
                "run_id": self.run_id,
                "project_id": self.project_id,
                "project_root": str(self.project_root),
                "task_manager": str(self.task_manager),
                "base_state_version": started["base_state_version"],
                "created_at": utc_now(),
            },
        )
        self._set_environment()
        return {
            "ok": True,
            "session_id": self.session_id,
            "task_manager": str(self.task_manager),
            "base_state_version": started["base_state_version"],
            "integration_recovery": integration_recovery,
            "compatibility_publication_recovery": publication_recovery,
        }

    def _recover_pending_compatibility_publication(self) -> dict[str, Any]:
        if self.task_manager is None:
            raise TaskControlConfigurationError("session task manager is not prepared")
        sessions_root = self.config.runtime_root / "sessions" / self.project_id
        pending: list[tuple[int, str, int, Path, dict[str, Any]]] = []
        if sessions_root.is_dir():
            for marker in sessions_root.glob("*/compatibility-publication.json"):
                if self.session_root is not None and marker.parent == self.session_root:
                    continue
                try:
                    payload = json.loads(marker.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError) as exc:
                    raise TaskControlConfigurationError(
                        f"invalid compatibility publication marker {marker}: {exc}"
                    ) from exc
                if payload.get("state") in {"pending", "prepared"}:
                    marker_session_id = str(payload.get("session_id") or "").strip()
                    status = self.database.project_session_status(
                        self.project_id,
                        marker_session_id,
                    )
                    if not status.get("ok"):
                        raise TaskControlConfigurationError(
                            f"cannot verify compatibility publication session {marker}"
                        )
                    if status.get("state") != "committed":
                        continue
                    try:
                        generation = int(payload.get("generation") or 0)
                    except (TypeError, ValueError) as exc:
                        raise TaskControlConfigurationError(
                            f"invalid compatibility publication generation {marker}"
                        ) from exc
                    if generation < 0:
                        raise TaskControlConfigurationError(
                            f"invalid compatibility publication generation {marker}"
                        )
                    pending.append(
                        (
                            generation,
                            str(payload.get("created_at") or ""),
                            marker.stat().st_mtime_ns,
                            marker,
                            payload,
                        )
                    )
        if not pending:
            return {"ok": True, "recovered": False, "markers": [], "artifacts": []}
        ordered_pending = sorted(
            pending,
            key=lambda item: (item[0], item[1], item[2], str(item[3])),
        )
        generation, _created_at, _mtime_ns, marker, payload = ordered_pending[-1]
        self._compatibility_publication_generation = generation
        source_task_manager = marker.parent / "Task_manager"
        artifacts = [
            str(name)
            for name in payload.get("artifacts") or []
            if str(name) in COMPATIBILITY_ARTIFACTS
        ]
        if not artifacts:
            raise TaskControlConfigurationError(
                f"compatibility publication marker has no recoverable artifacts: {marker}"
            )
        recovered: list[str] = []
        for name in artifacts:
            source = source_task_manager / name
            if not source.is_file():
                raise TaskControlConfigurationError(
                    f"compatibility publication artifact is missing: {source}"
                )
            shutil.copy2(source, self.task_manager / name)
            recovered.append(name)
        recovered_repository_artifacts = self._recover_repository_artifacts(
            marker,
            payload,
        )
        # Every later failed session starts from the newest pending snapshot,
        # so that snapshot supersedes all older pending markers. Resolve the
        # whole accumulated chain only after this snapshot is published.
        self._recovered_publication_markers = [item[3] for item in ordered_pending]
        return {
            "ok": True,
            "recovered": True,
            "source_marker": str(marker),
            "source_generation": generation,
            "markers": [str(item) for item in self._recovered_publication_markers],
            "artifacts": recovered,
            "repository_artifacts": recovered_repository_artifacts,
        }

    @staticmethod
    def _repository_path_allowed(path: str) -> bool:
        return any(
            path == root or path.startswith(f"{root}/")
            for root in RECOVERABLE_REPOSITORY_ROOTS
        )

    @classmethod
    def _validated_repository_path(cls, value: Any) -> str:
        path = str(value or "").replace("\\", "/").strip().strip('"')
        candidate = Path(path)
        if (
            not path
            or candidate.is_absolute()
            or ".." in candidate.parts
            or not cls._repository_path_allowed(path)
        ):
            raise TaskControlConfigurationError(
                f"invalid recoverable repository artifact path: {value!r}"
            )
        return path

    @staticmethod
    def _resolved_child(root: Path, relative_path: str) -> Path:
        resolved_root = root.resolve()
        target = (resolved_root / relative_path).resolve()
        try:
            target.relative_to(resolved_root)
        except ValueError as exc:
            raise TaskControlConfigurationError(
                f"repository artifact escapes its recovery root: {relative_path}"
            ) from exc
        return target

    def _capture_repository_artifacts(self, evidence: dict[str, Any]) -> list[dict[str, Any]]:
        if self.session_root is None:
            raise TaskControlConfigurationError("session is not prepared")
        requested_roots = {
            str(root or "").replace("\\", "/").strip().rstrip("/")
            for root in evidence.get("repository_roots") or []
        }
        roots = sorted(
            root for root in requested_roots if root in RECOVERABLE_REPOSITORY_ROOTS
        )
        if not roots:
            return []
        status = subprocess.run(
            [
                "git",
                "-c",
                "core.quotepath=false",
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
                "--",
                *roots,
            ],
            cwd=self.project_root,
            text=True,
            capture_output=True,
            check=False,
        )
        if status.returncode != 0:
            raise TaskControlConfigurationError(
                f"cannot capture recoverable repository artifacts: {status.stderr.strip()}"
            )
        changed: dict[str, str] = {}
        for line in status.stdout.splitlines():
            if len(line) < 4:
                continue
            raw_path = line[3:].strip()
            if " -> " in raw_path:
                old_path, new_path = raw_path.split(" -> ", 1)
                changed[self._validated_repository_path(old_path)] = "deleted"
                changed[self._validated_repository_path(new_path)] = "present"
            else:
                path = self._validated_repository_path(raw_path)
                changed[path] = (
                    "present"
                    if self._resolved_child(self.project_root, path).is_file()
                    else "deleted"
                )
        snapshot_root = self.session_root / "Repository_artifacts"
        artifacts: list[dict[str, Any]] = []
        for path, state in sorted(changed.items()):
            baseline = subprocess.run(
                ["git", "show", f"HEAD:{path}"],
                cwd=self.project_root,
                capture_output=True,
                check=False,
            )
            entry: dict[str, Any] = {
                "path": path,
                "state": state,
                "base_state": "present" if baseline.returncode == 0 else "absent",
            }
            if baseline.returncode == 0:
                entry["base_sha256"] = hashlib.sha256(baseline.stdout).hexdigest()
            if state == "present":
                source = self._resolved_child(self.project_root, path)
                content = source.read_bytes()
                target = self._resolved_child(snapshot_root, path)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                entry.update(
                    {
                        "sha256": hashlib.sha256(content).hexdigest(),
                        "size": len(content),
                    }
                )
            artifacts.append(entry)
        return artifacts

    def _recover_repository_artifacts(
        self,
        marker: Path,
        payload: dict[str, Any],
    ) -> list[str]:
        recovered: list[str] = []
        for raw_entry in payload.get("repository_artifacts") or []:
            if not isinstance(raw_entry, dict):
                raise TaskControlConfigurationError(
                    f"invalid repository artifact entry in {marker}"
                )
            path = self._validated_repository_path(raw_entry.get("path"))
            state = str(raw_entry.get("state") or "")
            target = self._resolved_child(self.project_root, path)
            if target.exists() and not target.is_file():
                raise TaskControlConfigurationError(
                    f"repository recovery target is not a file: {target}"
                )
            current = target.read_bytes() if target.is_file() else None
            current_sha256 = hashlib.sha256(current).hexdigest() if current is not None else None
            base_state = str(raw_entry.get("base_state") or "")
            base_sha256 = str(raw_entry.get("base_sha256") or "") or None
            desired_sha256 = str(raw_entry.get("sha256") or "") or None
            already_applied = (
                state == "present"
                and current_sha256 is not None
                and current_sha256 == desired_sha256
            ) or (state == "deleted" and current is None)
            baseline_matches = (
                base_state == "absent" and current is None
            ) or (
                base_state == "present"
                and current_sha256 is not None
                and current_sha256 == base_sha256
            )
            if not already_applied and not baseline_matches:
                raise TaskControlConflict(
                    f"repository artifact diverged before recovery: {path}"
                )
            self._recovered_repository_originals.setdefault(path, current)
            if state == "deleted":
                target.unlink(missing_ok=True)
            elif state == "present":
                source = self._resolved_child(
                    marker.parent / "Repository_artifacts",
                    path,
                )
                if not source.is_file():
                    raise TaskControlConfigurationError(
                        f"repository recovery artifact is missing: {source}"
                    )
                content = source.read_bytes()
                if hashlib.sha256(content).hexdigest() != str(
                    raw_entry.get("sha256") or ""
                ):
                    raise TaskControlConfigurationError(
                        f"repository recovery artifact checksum mismatch: {source}"
                    )
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
            else:
                raise TaskControlConfigurationError(
                    f"invalid repository artifact state in {marker}: {state!r}"
                )
            recovered.append(path)
        return recovered

    def _rollback_recovered_repository_artifacts(self) -> dict[str, Any]:
        restored: list[str] = []
        try:
            for path, content in reversed(
                list(self._recovered_repository_originals.items())
            ):
                target = self._resolved_child(self.project_root, path)
                if content is None:
                    target.unlink(missing_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(content)
                restored.append(path)
        except OSError as exc:
            return {
                "ok": False,
                "reason": "recovered_repository_artifact_rollback_failed",
                "restored": restored,
                "error": str(exc),
            }
        self._recovered_repository_originals.clear()
        return {
            "ok": True,
            "reason": "recovered_repository_artifacts_rolled_back",
            "restored": restored,
        }

    def mark_compatibility_publication_pending(
        self,
        evidence: dict[str, Any],
    ) -> dict[str, Any]:
        if self.session_root is None or self.task_manager is None:
            raise TaskControlConfigurationError("session is not prepared for publication recovery")
        artifacts = [
            name
            for name in COMPATIBILITY_ARTIFACTS
            if (self.task_manager / name).is_file()
        ]
        if not artifacts:
            raise TaskControlConfigurationError(
                "failed compatibility publication has no durable session artifacts"
            )
        marker = self.session_root / "compatibility-publication.json"
        existing: dict[str, Any] = {}
        if marker.is_file():
            try:
                existing = json.loads(marker.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                raise TaskControlConfigurationError(
                    f"invalid current compatibility publication marker {marker}: {exc}"
                ) from exc
        generation = int(
            existing.get("generation")
            or (self._compatibility_publication_generation + 1)
        )
        repository_artifacts = list(existing.get("repository_artifacts") or [])
        if not existing:
            try:
                repository_artifacts = self._capture_repository_artifacts(evidence)
            except (OSError, TaskControlConfigurationError) as exc:
                return {
                    "ok": False,
                    "marker": str(marker),
                    "generation": generation,
                    "artifacts": artifacts,
                    "error_type": type(exc).__name__,
                    "message": str(exc),
                    "recovery_prepared": False,
                }
        payload = {
            **existing,
            "schema_version": 1,
            "state": "pending",
            "session_id": self.session_id,
            "project_id": self.project_id,
            "generation": generation,
            "created_at": utc_now(),
            "artifacts": artifacts,
            "repository_artifacts": repository_artifacts,
            "evidence": evidence,
        }
        try:
            write_json_atomic(marker, payload)
        except OSError as exc:
            return {
                "ok": False,
                "marker": str(marker),
                "generation": generation,
                "artifacts": artifacts,
                "error_type": type(exc).__name__,
                "message": str(exc),
                "recovery_prepared": bool(existing),
            }
        self._compatibility_publication_generation = generation
        self._current_publication_marker = marker
        return {
            "ok": True,
            "marker": str(marker),
            "generation": generation,
            "artifacts": artifacts,
        }

    def complete_compatibility_publication(self) -> dict[str, Any]:
        resolved: list[str] = []
        markers = list(self._recovered_publication_markers)
        if (
            self._current_publication_marker is not None
            and self._current_publication_marker not in markers
        ):
            markers.append(self._current_publication_marker)
        for marker in markers:
            try:
                payload = json.loads(marker.read_text(encoding="utf-8"))
                payload.update(
                    {
                        "state": "resolved",
                        "resolved_at": utc_now(),
                        "resolved_by_session": self.session_id,
                    }
                )
                write_json_atomic(marker, payload)
            except (OSError, json.JSONDecodeError) as exc:
                return {
                    "ok": False,
                    "reason": "compatibility_publication_marker_resolution_failed",
                    "marker": str(marker),
                    "error": str(exc),
                    "resolved": resolved,
                }
            resolved.append(str(marker))
        self._recovered_publication_markers.clear()
        self._current_publication_marker = None
        self._recovered_repository_originals.clear()
        return {
            "ok": True,
            "reason": (
                "recovered_compatibility_publication_resolved"
                if resolved
                else "no_recovered_compatibility_publication"
            ),
            "resolved": resolved,
        }

    def _recover_aborted_integrations(self) -> dict[str, Any]:
        recovered: list[dict[str, Any]] = []
        not_merged: list[dict[str, Any]] = []
        for candidate in self.database.integration_recovery_candidates(self.project_id):
            base_branch = str(candidate.get("base_branch") or "develop").strip()
            head_sha = str(candidate.get("head_sha") or "").strip().lower()
            remote_ref = f"refs/remotes/origin/{base_branch}"
            check = subprocess.run(
                ["git", "merge-base", "--is-ancestor", head_sha, remote_ref],
                cwd=self.project_root,
                text=True,
                capture_output=True,
                check=False,
            )
            if check.returncode == 1:
                work_branch = str(candidate.get("work_branch") or "").strip()
                if candidate.get("source") != "run_worker_cycle" or not work_branch:
                    not_merged.append(
                        {
                            "candidate_id": candidate["candidate_id"],
                            "head_sha": head_sha,
                            "remote_ref": remote_ref,
                            "work_branch": work_branch,
                        }
                    )
                    continue
                remote_branch_ref = f"refs/heads/{work_branch}"
                remote_check = subprocess.run(
                    ["git", "ls-remote", "--heads", "origin", remote_branch_ref],
                    cwd=self.project_root,
                    text=True,
                    capture_output=True,
                    check=False,
                )
                if remote_check.returncode != 0:
                    detail = (
                        remote_check.stderr
                        or remote_check.stdout
                        or "remote worker branch check failed"
                    ).strip()
                    raise TaskControlConflict(
                        f"cannot verify pushed integration candidate {candidate['candidate_id']}: {detail}"
                    )
                remote_heads = {
                    parts[0].strip().lower()
                    for line in remote_check.stdout.splitlines()
                    if len(parts := line.split()) == 2
                    and parts[1] == remote_branch_ref
                }
                if remote_heads == {head_sha}:
                    recovered.append(
                        self.database.recover_pushed_integration_candidate(
                            self.project_id,
                            str(candidate["candidate_id"]),
                            recovery_session_id=self.session_id,
                            evidence={
                                "recovery": "verified_remote_worker_branch",
                                "verified_head_sha": head_sha,
                                "verified_remote_ref": remote_branch_ref,
                                "recovery_session_id": self.session_id,
                                "verified_at": utc_now(),
                            },
                        )
                    )
                    continue
                not_merged.append(
                    {
                        "candidate_id": candidate["candidate_id"],
                        "head_sha": head_sha,
                        "remote_ref": remote_ref,
                        "work_branch": work_branch,
                    }
                )
                continue
            if check.returncode != 0:
                detail = (check.stderr or check.stdout or "git ancestry check failed").strip()
                raise TaskControlConflict(
                    f"cannot verify integration candidate {candidate['candidate_id']}: {detail}"
                )
            recovered.append(
                self.database.recover_integration_candidate(
                    self.project_id,
                    str(candidate["candidate_id"]),
                    recovery_session_id=self.session_id,
                    evidence={
                        "recovery": "verified_remote_ancestry",
                        "verified_head_sha": head_sha,
                        "verified_remote_ref": remote_ref,
                        "recovery_session_id": self.session_id,
                        "verified_at": utc_now(),
                    },
                )
            )
        return {"recovered": recovered, "not_merged": not_merged}

    def _set_environment(self) -> None:
        if self.task_manager is None:
            raise TaskControlConfigurationError("session is not prepared")
        values = {
            TASK_MANAGER_ENV: str(self.task_manager),
            SESSION_ENV: self.session_id,
            AUTHORITY_ENV: "postgres",
            PROJECT_ENV: self.project_id,
            DSN_ENV_NAME_ENV: self.config.dsn_env,
        }
        for key, value in values.items():
            self._previous_env[key] = os.environ.get(key)
            os.environ[key] = value

    def restore_environment(self) -> None:
        heartbeat_error: Exception | None = None
        try:
            self.stop_heartbeat()
        except Exception as exc:
            heartbeat_error = exc
        try:
            for key, previous in self._previous_env.items():
                if previous is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = previous
            self._previous_env.clear()
        finally:
            self.finish_publication()
        if heartbeat_error is not None:
            raise heartbeat_error

    def finish_publication(self) -> None:
        guard = self._publication_guard
        if guard is None:
            return
        self._publication_guard = None
        guard.__exit__(None, None, None)

    def renew(self) -> dict[str, Any]:
        if not self.session_id or self.finished:
            raise TaskControlConflict("cutover session is not active for renewal")
        return self.database.renew_project_session(
            self.session_id,
            owner_id=self.owner_id,
            ttl_seconds=self.config.session_ttl_seconds,
        )

    def start_heartbeat(self) -> "CutoverSessionHeartbeat":
        if self._heartbeat is not None:
            raise TaskControlConflict("project session heartbeat is already active")
        heartbeat = CutoverSessionHeartbeat(
            self,
            interval_seconds=max(
                1.0,
                min(30.0, float(self.config.session_ttl_seconds) / 3.0),
            ),
        )
        self._heartbeat = heartbeat
        try:
            heartbeat.start()
        except Exception:
            self._heartbeat = None
            raise
        return heartbeat

    def stop_heartbeat(self) -> None:
        heartbeat = self._heartbeat
        if heartbeat is None:
            return
        self._heartbeat = None
        heartbeat.stop()

    def commit(self) -> dict[str, Any]:
        if self.task_manager is None or self.session_root is None:
            raise TaskControlConfigurationError("session is not prepared")
        queue = load_json(self.task_manager / "task_queue.json")
        history = load_json(self.task_manager / "task_history.json")
        snapshot = snapshot_from_documents(
            project_id=self.project_id,
            queue=queue,
            history=history,
            source_revision=self.base_revision,
        )
        heartbeat_error: Exception | None = None
        try:
            result = self.database.commit_project_session(
                self.session_id,
                snapshot,
                actor=self.owner_id,
                idempotency_key=f"{self.session_id}:commit",
                metadata={"run_id": self.run_id, "task_manager": str(self.task_manager)},
            )
        except Exception:
            try:
                self.stop_heartbeat()
            except Exception:
                pass
            raise
        self.finished = True
        try:
            self.stop_heartbeat()
        except Exception as exc:
            heartbeat_error = exc
        outcome = dict(result)
        if heartbeat_error is not None:
            outcome["heartbeat"] = {
                "ok": False,
                "error_type": type(heartbeat_error).__name__,
                "message": str(heartbeat_error),
                "durable_result_preserved": True,
            }
        try:
            write_json_atomic(
                self.session_root / "session.json",
                {
                    "schema_version": 1,
                    "state": "committed",
                    "session_id": self.session_id,
                    "run_id": self.run_id,
                    "project_id": self.project_id,
                    "task_manager": str(self.task_manager),
                    "result": result,
                    "finished_at": utc_now(),
                },
            )
        except OSError as exc:
            # The PostgreSQL transaction is already durable. Local runtime
            # evidence can be repaired later and must not turn success into a
            # false database failure or trigger an ineffective abort.
            outcome["session_report"] = {
                "ok": False,
                "error_type": type(exc).__name__,
                "message": str(exc),
                "recovery_required": True,
            }
        return outcome

    def abort(self, reason: str) -> dict[str, Any]:
        if not self.session_id:
            self.finish_publication()
            return {"ok": True, "aborted": False, "reason": "session_not_started"}
        try:
            heartbeat_error: BaseException | None = None
            try:
                self.stop_heartbeat()
            except BaseException as exc:
                heartbeat_error = exc
            result: dict[str, Any] | None = None
            try:
                result = self.database.abort_project_session(self.session_id, reason=reason)
            finally:
                repository_rollback = self._rollback_recovered_repository_artifacts()
            if result is None:
                raise TaskControlConflict("project session abort returned no result")
            result = {
                **result,
                "recovered_repository_rollback": repository_rollback,
            }
            if heartbeat_error is not None:
                result["heartbeat"] = {
                    "ok": False,
                    "error_type": type(heartbeat_error).__name__,
                    "message": str(heartbeat_error),
                }
            self.finished = True
            if self.session_root is not None:
                write_json_atomic(
                    self.session_root / "session.json",
                    {
                        "schema_version": 1,
                        "state": "aborted",
                        "session_id": self.session_id,
                        "run_id": self.run_id,
                        "project_id": self.project_id,
                        "reason": reason,
                        "finished_at": utc_now(),
                    },
                )
            if heartbeat_error is not None and not isinstance(
                heartbeat_error,
                Exception,
            ):
                raise heartbeat_error
            return result
        finally:
            self.finish_publication()


class CutoverSessionHeartbeat:
    def __init__(self, session: CutoverSession, *, interval_seconds: float) -> None:
        self.session = session
        self.interval_seconds = max(0.01, float(interval_seconds))
        self._stop_event = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name=f"task-control-heartbeat-{session.session_id}",
            daemon=True,
        )
        self._error: Exception | None = None

    def start(self) -> None:
        self._thread.start()

    def _run(self) -> None:
        while not self._stop_event.wait(self.interval_seconds):
            try:
                self.session.renew()
            except Exception as exc:  # surfaced synchronously by stop()
                self._error = exc
                self._stop_event.set()
                return

    def stop(self) -> None:
        self._stop_event.set()
        self._thread.join(timeout=max(1.0, self.interval_seconds * 2.0))
        if self._thread.is_alive():
            raise TaskControlConflict("project session heartbeat did not stop")
        if self._error is not None:
            raise TaskControlConflict(
                f"project session heartbeat failed: {self._error}"
            ) from self._error


def validate_backup_for_runtime(
    manifest_payload: dict[str, Any], runtime: dict[str, Any]
) -> None:
    backup_database = str(manifest_payload.get("database_name") or "")
    runtime_database = str(runtime.get("database") or "")
    if not backup_database or backup_database != runtime_database:
        raise TaskControlConfigurationError(
            "verified backup database does not match the cutover target database"
        )
    backup_cluster = str(manifest_payload.get("cluster_system_identifier") or "")
    runtime_cluster = str(runtime.get("cluster_system_identifier") or "")
    if not backup_cluster or backup_cluster != runtime_cluster:
        raise TaskControlConfigurationError(
            "verified backup cluster does not match the cutover target cluster"
        )
    if "last_shadow_sync" not in manifest_payload:
        raise TaskControlConfigurationError(
            "verified backup is missing its shadow watermark"
        )
    backup_shadow_watermark = manifest_payload.get("last_shadow_sync")
    runtime_shadow_watermark = runtime.get("last_shadow_sync")
    if backup_shadow_watermark != runtime_shadow_watermark:
        raise TaskControlConfigurationError(
            "verified backup shadow watermark does not match the cutover target"
        )
    try:
        backup_created_at = datetime.fromisoformat(
            str(manifest_payload.get("created_at") or "").replace("Z", "+00:00")
        )
        migration_applied_at = datetime.fromisoformat(
            str(runtime.get("latest_migration_applied_at") or "").replace("Z", "+00:00")
        )
        last_shadow_sync_raw = str(runtime.get("last_shadow_sync") or "")
        last_shadow_sync = (
            datetime.fromisoformat(last_shadow_sync_raw.replace("Z", "+00:00"))
            if last_shadow_sync_raw
            else None
        )
    except ValueError as exc:
        raise TaskControlConfigurationError(
            "backup and runtime timestamps are required for cutover"
        ) from exc
    timestamps = [backup_created_at, migration_applied_at]
    if last_shadow_sync is not None:
        timestamps.append(last_shadow_sync)
    if any(value.tzinfo is None for value in timestamps):
        raise TaskControlConfigurationError("backup and runtime timestamps must include timezone")
    backup_utc = backup_created_at.astimezone(timezone.utc)
    if backup_utc < migration_applied_at.astimezone(timezone.utc):
        raise TaskControlConfigurationError(
            "verified backup predates the latest Task Control migration"
        )
    if last_shadow_sync is not None and backup_utc < last_shadow_sync.astimezone(timezone.utc):
        raise TaskControlConfigurationError(
            "verified backup predates the latest successful shadow sync"
        )


def activate_cutover(config_path: Path, *, confirm: bool) -> dict[str, Any]:
    if not confirm:
        raise TaskControlConfigurationError("cutover activation requires explicit confirmation")
    config = load_runtime_config(config_path)
    if (config.mode, config.source_of_truth, config.cutover_enabled) != (
        "cutover",
        "postgres",
        True,
    ):
        raise TaskControlConfigurationError("activation config must request cutover/postgres/true")
    if config.backup_manifest is None:
        raise TaskControlConfigurationError("cutover activation requires backup_manifest")
    database = database_for(config)
    migration = database.migrate()
    before = database.health()
    backup = verify_manifest(config.backup_manifest)
    if not backup.get("ok"):
        raise TaskControlConfigurationError(
            f"backup verification failed: {backup.get('reason')}"
        )
    manifest_payload = load_json(config.backup_manifest)
    validate_backup_for_runtime(manifest_payload, before)
    with database.cutover_activation_guard() as activation:
        # The advisory + runtime-row locks prevent another shadow import from
        # landing between this final evidence read and the authority switch.
        expired_leases = activation.expire_timed_out_leases()
        before = database.health()
        before["active_leases"] = activation.active_lease_count()
        validate_backup_for_runtime(manifest_payload, before)
        if activation.authority == ("cutover", "postgres", True):
            return {
                "ok": True,
                "idempotent_replay": True,
                "migration": migration,
                "backup": backup,
                "expired_leases": expired_leases,
                "authority": before,
            }
        if activation.authority != ("shadow", "json_git", False):
            raise TaskControlConfigurationError(
                f"unexpected pre-cutover database authority: {activation.authority!r}"
            )
        if int(before.get("active_leases") or 0) != 0:
            raise TaskControlConfigurationError("cutover requires zero active task leases")
        snapshots = build_fleet_snapshots(config)
        selected_ids = {snapshot.project_id for snapshot in snapshots}
        missing = sorted(set(config.required_project_ids) - selected_ids)
        if missing:
            raise TaskControlConfigurationError(f"required projects missing from cutover: {missing}")
        duplicates = {
            snapshot.project_id: list(snapshot.duplicate_task_ids)
            for snapshot in snapshots
            if snapshot.duplicate_task_ids
        }
        if duplicates:
            raise TaskControlConfigurationError(
                f"queue/history overlap must be repaired before cutover: {duplicates}"
            )
        reconciliations = [database.reconcile_snapshot(snapshot) for snapshot in snapshots]
        failed = [row for row in reconciliations if not row.get("ok")]
        if failed:
            raise TaskControlConfigurationError(
                f"PostgreSQL shadow is not reconciled with Git JSON: {failed}"
            )
        authority = activation.activate()
    return {
        "ok": True,
        "idempotent_replay": False,
        "migration": migration,
        "backup": backup,
        "expired_leases": expired_leases,
        "reconciliations": reconciliations,
        "authority": authority,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    parser.add_argument("--activate", action="store_true")
    parser.add_argument("--confirm-cutover", action="store_true")
    parser.add_argument("--health", action="store_true")
    parser.add_argument("--export-project", default="")
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.activate:
            result = activate_cutover(Path(args.config), confirm=args.confirm_cutover)
        elif args.health:
            config = load_runtime_config(Path(args.config))
            result = database_for(config).health()
        elif args.export_project:
            if args.output_dir is None:
                raise TaskControlConfigurationError("--export-project requires --output-dir")
            config = load_runtime_config(Path(args.config))
            payload = database_for(config).export_project(args.export_project)
            output_dir = args.output_dir.expanduser().resolve()
            output_dir.mkdir(parents=True, exist_ok=True)
            queue_path = output_dir / "task_queue.json"
            history_path = output_dir / "task_history.json"
            manifest_path = output_dir / "manifest.json"
            write_json_atomic(queue_path, payload["queue"])
            write_json_atomic(history_path, payload["history"])
            write_json_atomic(
                manifest_path,
                {
                    "schema_version": 1,
                    "project_id": args.export_project,
                    "state_version": payload["state_version"],
                    "source_digest": payload["source_digest"],
                    "source_revision": payload.get("source_revision"),
                    "queue_digest": payload_digest(payload["queue"]),
                    "history_digest": payload_digest(payload["history"]),
                    "exported_at": utc_now(),
                },
            )
            result = {
                "ok": True,
                "project_id": args.export_project,
                "state_version": payload["state_version"],
                "paths": {
                    "queue": str(queue_path),
                    "history": str(history_path),
                    "manifest": str(manifest_path),
                },
            }
        else:
            raise TaskControlConfigurationError("choose --activate, --health or --export-project")
    except (TaskControlConfigurationError, TaskControlConflict, OSError, ValueError) as exc:
        result = {"ok": False, "error": type(exc).__name__, "message": str(exc)}
    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else result)
    return 0 if result.get("ok") else 2


if __name__ == "__main__":
    raise SystemExit(main())
