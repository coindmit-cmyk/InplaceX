#!/usr/bin/env python3
"""PostgreSQL repository for the shared AiStudio Task Control MVP."""

from __future__ import annotations

import hashlib
import json
import os
import re
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence

from task_state_invariants import normalize_terminal_task


TERMINAL_STATUSES = {"done", "stale_or_superseded"}
PROJECT_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{1,127}$")
MIGRATION_RE = re.compile(r"^(?P<version>[0-9]{3,})_(?P<name>[a-z0-9_]+)\.sql$")
DEFAULT_MIGRATIONS = Path(__file__).with_name("task_control_migrations")


class TaskControlError(RuntimeError):
    """Base error for the Task Control database."""


class TaskControlDependencyError(TaskControlError):
    """Raised when the optional PostgreSQL driver is unavailable."""


class TaskControlConflict(TaskControlError):
    """Raised for an optimistic transition or lease conflict."""


class TaskControlConfigurationError(TaskControlError):
    """Raised for a fail-closed configuration error."""


@dataclass
class CutoverAuthorityGuard:
    cursor: Any
    authority: tuple[str, str, bool]
    activated: bool = False

    def expire_timed_out_leases(self) -> int:
        self.cursor.execute(
            """
            UPDATE task_control.task_leases
            SET state = 'expired', released_at = clock_timestamp(),
                release_reason = 'ttl_expired_before_cutover'
            WHERE state = 'active' AND expires_at <= clock_timestamp()
            """
        )
        return int(self.cursor.rowcount)

    def active_lease_count(self) -> int:
        self.cursor.execute(
            """
            SELECT count(*) FROM task_control.task_leases
            WHERE state = 'active' AND expires_at > clock_timestamp()
            """
        )
        return int(self.cursor.fetchone()[0])

    def activate(self) -> dict[str, Any]:
        if self.authority != ("shadow", "json_git", False):
            raise TaskControlConflict(
                f"unexpected pre-cutover database authority: {self.authority!r}"
            )
        self.cursor.execute(
            """
            UPDATE task_control.runtime_configuration
            SET mode = 'cutover', source_of_truth = 'postgres',
                cutover_enabled = true, updated_at = clock_timestamp()
            WHERE singleton
            RETURNING updated_at
            """
        )
        updated_at = self.cursor.fetchone()[0]
        self.authority = ("cutover", "postgres", True)
        self.activated = True
        return {
            "ok": True,
            "mode": "cutover",
            "source_of_truth": "postgres",
            "cutover_enabled": True,
            "updated_at": updated_at.isoformat(),
        }


@dataclass(frozen=True)
class Migration:
    version: int
    name: str
    path: Path
    sql: str
    checksum: str


@dataclass(frozen=True)
class TaskSnapshot:
    project_id: str
    repository: str | None
    base_branch: str
    source_revision: str | None
    source_digest: str
    tasks: dict[str, dict[str, Any]]
    source_records: dict[tuple[str, str], dict[str, Any]] | None = None
    source_documents: dict[str, dict[str, Any]] | None = None
    duplicate_task_ids: tuple[str, ...] = ()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def payload_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def integration_candidate_commit_state(
    candidate: dict[str, Any],
    *,
    task_status: str,
    merge_commit_sha: str,
) -> tuple[str, dict[str, Any]]:
    state = str(candidate.get("state") or "ready").strip()
    evidence = candidate.get("evidence") or {}
    evidence = evidence if isinstance(evidence, dict) else {}
    if task_status in TERMINAL_STATUSES and re.fullmatch(
        r"[0-9a-f]{40}", merge_commit_sha
    ):
        state = "merged" if task_status == "done" else "archived"
    if evidence.get("source") == "run_worker_cycle" and re.fullmatch(
        r"[0-9a-f]{40}", merge_commit_sha
    ):
        # An integrator may deliberately keep the task non-terminal while
        # closing its source PR. The recorded merge SHA still consumes the
        # worker candidate; leaving it ready would replay the already-
        # integrated worker branch next session.
        state = "merged"
    return state, evidence


def require_project_id(project_id: str) -> str:
    if not PROJECT_ID_RE.fullmatch(project_id):
        raise TaskControlConfigurationError(f"invalid project_id: {project_id!r}")
    return project_id


def normalized_task_id(task: dict[str, Any]) -> str:
    value = task.get("id") or task.get("task_id")
    if not isinstance(value, str) or not value.strip():
        raise TaskControlConfigurationError("task is missing a non-empty id/task_id")
    return value.strip()


def task_dependencies(task: dict[str, Any]) -> list[tuple[str, str]]:
    dependencies: set[tuple[str, str]] = set()
    for field, kind in (("blocked_by", "blocked_by"), ("depends_on", "depends_on")):
        values = task.get(field)
        if not isinstance(values, list):
            continue
        for value in values:
            if isinstance(value, str) and value.strip():
                dependencies.add((value.strip(), kind))
    return sorted(dependencies)


def snapshot_source_records(
    snapshot: TaskSnapshot,
) -> dict[tuple[str, str], dict[str, Any]]:
    if snapshot.source_records is not None:
        return snapshot.source_records
    return {
        (task_id, str(record["source_kind"])): record
        for task_id, record in snapshot.tasks.items()
    }


def snapshot_source_documents(snapshot: TaskSnapshot) -> dict[str, dict[str, Any]]:
    if snapshot.source_documents is not None:
        return snapshot.source_documents
    return {
        "queue": {"schema_version": 1},
        "history": {"schema_version": 1},
    }


def load_migrations(directory: Path = DEFAULT_MIGRATIONS) -> list[Migration]:
    migrations: list[Migration] = []
    for path in sorted(directory.glob("*.sql")):
        match = MIGRATION_RE.fullmatch(path.name)
        if not match:
            raise TaskControlConfigurationError(f"invalid migration filename: {path.name}")
        sql = path.read_text(encoding="utf-8")
        migrations.append(
            Migration(
                version=int(match.group("version")),
                name=match.group("name"),
                path=path,
                sql=sql,
                checksum=hashlib.sha256(sql.encode("utf-8")).hexdigest(),
            )
        )
    if not migrations:
        raise TaskControlConfigurationError(f"no SQL migrations found in {directory}")
    versions = [migration.version for migration in migrations]
    if len(versions) != len(set(versions)):
        raise TaskControlConfigurationError("duplicate SQL migration version")
    return migrations


def _psycopg_modules() -> tuple[Any, Any]:
    try:
        import psycopg
        from psycopg.types.json import Jsonb
    except ImportError as exc:
        raise TaskControlDependencyError(
            "psycopg 3 is required; install psycopg[binary]>=3.2"
        ) from exc
    return psycopg, Jsonb


class TaskControlPostgres:
    """Transactional repository. It does not decide when PostgreSQL gains authority."""

    def __init__(
        self,
        dsn: str,
        *,
        migrations_dir: Path = DEFAULT_MIGRATIONS,
        application_name: str = "aistudio-task-control",
    ) -> None:
        if not dsn.strip():
            raise TaskControlConfigurationError("PostgreSQL DSN is empty")
        self.dsn = dsn
        self.migrations_dir = migrations_dir
        self.application_name = application_name

    @classmethod
    def from_env(
        cls,
        env_name: str = "AISTUDIO_TASK_DB_DSN",
        *,
        migrations_dir: Path = DEFAULT_MIGRATIONS,
    ) -> "TaskControlPostgres":
        dsn = os.environ.get(env_name, "")
        if not dsn:
            raise TaskControlConfigurationError(f"required DSN environment variable is missing: {env_name}")
        return cls(dsn, migrations_dir=migrations_dir)

    def connect(self) -> Any:
        psycopg, _jsonb = _psycopg_modules()
        return psycopg.connect(
            self.dsn,
            autocommit=False,
            application_name=self.application_name,
        )

    @staticmethod
    def _jsonb(value: Any) -> Any:
        _psycopg, jsonb = _psycopg_modules()
        return jsonb(value)

    def migrate(self) -> dict[str, Any]:
        migrations = load_migrations(self.migrations_dir)
        applied: list[int] = []
        verified: list[int] = []
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT pg_advisory_xact_lock(hashtext('aistudio-task-control-migrations'))")
                cursor.execute("CREATE SCHEMA IF NOT EXISTS task_control")
                cursor.execute(
                    """
                    CREATE TABLE IF NOT EXISTS task_control.schema_migrations (
                        version integer PRIMARY KEY,
                        name text NOT NULL,
                        checksum text NOT NULL,
                        applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
                    )
                    """
                )
                cursor.execute("SELECT version, checksum FROM task_control.schema_migrations")
                existing = {int(version): str(checksum) for version, checksum in cursor.fetchall()}
                for migration in migrations:
                    previous = existing.get(migration.version)
                    if previous is not None:
                        if previous != migration.checksum:
                            raise TaskControlConflict(
                                f"migration {migration.version} checksum changed after apply"
                            )
                        verified.append(migration.version)
                        continue
                    cursor.execute(migration.sql)
                    cursor.execute(
                        """
                        INSERT INTO task_control.schema_migrations (version, name, checksum)
                        VALUES (%s, %s, %s)
                        """,
                        (migration.version, migration.name, migration.checksum),
                    )
                    applied.append(migration.version)
        return {
            "ok": True,
            "applied": applied,
            "verified": verified,
            "latest_version": migrations[-1].version,
        }

    def health(self) -> dict[str, Any]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT current_database(), current_user, version()")
                database, user, server_version = cursor.fetchone()
                cursor.execute("SELECT system_identifier::text FROM pg_control_system()")
                cluster_system_identifier = str(cursor.fetchone()[0])
                cursor.execute(
                    """
                    SELECT mode, source_of_truth, cutover_enabled, updated_at
                    FROM task_control.runtime_configuration
                    WHERE singleton
                    """
                )
                mode, source_of_truth, cutover_enabled, updated_at = cursor.fetchone()
                cursor.execute("SELECT count(*), max(version), max(applied_at) FROM task_control.schema_migrations")
                migration_count, migration_version, latest_migration_applied_at = cursor.fetchone()
                cursor.execute(
                    """
                    SELECT
                        (SELECT count(*) FROM task_control.projects),
                        (SELECT count(*) FROM task_control.tasks WHERE shadow_present),
                        (SELECT count(*) FROM task_control.task_source_records WHERE source_present),
                        (SELECT count(*) FROM task_control.task_leases WHERE state = 'active'),
                        (SELECT max(finished_at) FROM task_control.shadow_runs WHERE state = 'succeeded')
                    """
                )
                project_count, task_count, source_record_count, active_leases, last_shadow_sync = cursor.fetchone()
        return {
            "ok": True,
            "database": database,
            "cluster_system_identifier": cluster_system_identifier,
            "user": user,
            "server_version": server_version,
            "mode": mode,
            "source_of_truth": source_of_truth,
            "cutover_enabled": bool(cutover_enabled),
            "configuration_updated_at": updated_at.isoformat() if updated_at else None,
            "migration_count": int(migration_count),
            "migration_version": int(migration_version) if migration_version is not None else None,
            "latest_migration_applied_at": (
                latest_migration_applied_at.isoformat() if latest_migration_applied_at else None
            ),
            "project_count": int(project_count),
            "task_count": int(task_count),
            "source_record_count": int(source_record_count),
            "active_leases": int(active_leases),
            "last_shadow_sync": last_shadow_sync.isoformat() if last_shadow_sync else None,
        }

    def _record_failed_shadow_run(
        self,
        run_id: str,
        *,
        started_at: str,
        error: Exception,
        metadata: dict[str, Any] | None,
    ) -> None:
        try:
            with self.connect() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        INSERT INTO task_control.shadow_runs
                            (run_id, state, started_at, finished_at, error, metadata)
                        VALUES (%s, 'failed', %s::timestamptz, clock_timestamp(), %s, %s)
                        ON CONFLICT (run_id) DO UPDATE SET
                            state = 'failed',
                            finished_at = clock_timestamp(),
                            error = EXCLUDED.error,
                            metadata = EXCLUDED.metadata
                        """,
                        (
                            run_id,
                            started_at,
                            self._jsonb({"type": type(error).__name__, "message": str(error)}),
                            self._jsonb(metadata or {}),
                        ),
                    )
        except Exception:
            pass

    def import_shadow_snapshots(
        self,
        snapshots: Sequence[TaskSnapshot],
        *,
        run_id: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if not snapshots:
            raise TaskControlConfigurationError("shadow import requires at least one project snapshot")
        run_id = run_id or f"shadow-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
        started_at = utc_now()
        project_ids = [require_project_id(snapshot.project_id) for snapshot in snapshots]
        if len(project_ids) != len(set(project_ids)):
            raise TaskControlConfigurationError("shadow import contains duplicate project_id")
        fleet_digest = payload_digest(
            [
                {
                    "project_id": snapshot.project_id,
                    "source_revision": snapshot.source_revision,
                    "source_digest": snapshot.source_digest,
                }
                for snapshot in snapshots
            ]
        )
        changed = 0
        inserted = 0
        missing = 0
        try:
            with self.connect() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT pg_try_advisory_xact_lock(hashtext('aistudio-task-control-shadow-import'))"
                    )
                    if cursor.fetchone()[0] is not True:
                        raise TaskControlConflict("another shadow import is active")
                    cursor.execute(
                        """
                        SELECT mode, source_of_truth, cutover_enabled
                        FROM task_control.runtime_configuration
                        WHERE singleton
                        FOR UPDATE
                        """
                    )
                    authority = cursor.fetchone()
                    if authority != ("shadow", "json_git", False):
                        raise TaskControlConflict(
                            f"shadow import is disabled for runtime authority {authority!r}"
                        )
                    cursor.execute(
                        """
                        INSERT INTO task_control.shadow_runs
                            (run_id, state, started_at, project_count, task_count, source_digest, metadata)
                        VALUES (%s, 'running', %s::timestamptz, %s, %s, %s, %s)
                        """,
                        (
                            run_id,
                            started_at,
                            len(snapshots),
                            sum(len(snapshot_source_records(snapshot)) for snapshot in snapshots),
                            fleet_digest,
                            self._jsonb(metadata or {}),
                        ),
                    )
                    for snapshot in snapshots:
                        source_documents = snapshot_source_documents(snapshot)
                        cursor.execute(
                            """
                            INSERT INTO task_control.projects
                                (project_id, repository, base_branch, source_revision, source_digest,
                                 queue_envelope, history_envelope, last_shadow_sync_at, updated_at)
                            VALUES (
                                %s, %s, %s, %s, %s, %s, %s,
                                clock_timestamp(), clock_timestamp()
                            )
                            ON CONFLICT (project_id) DO UPDATE SET
                                repository = EXCLUDED.repository,
                                base_branch = EXCLUDED.base_branch,
                                source_revision = EXCLUDED.source_revision,
                                source_digest = EXCLUDED.source_digest,
                                queue_envelope = EXCLUDED.queue_envelope,
                                history_envelope = EXCLUDED.history_envelope,
                                last_shadow_sync_at = clock_timestamp(),
                                updated_at = clock_timestamp()
                            """,
                            (
                                snapshot.project_id,
                                snapshot.repository,
                                snapshot.base_branch,
                                snapshot.source_revision,
                                snapshot.source_digest,
                                self._jsonb(source_documents["queue"]),
                                self._jsonb(source_documents["history"]),
                            ),
                        )
                        for task_id, record in snapshot.tasks.items():
                            task = record["payload"]
                            source_kind = record["source_kind"]
                            digest = record["source_digest"]
                            cursor.execute(
                                """
                                SELECT source_digest
                                FROM task_control.tasks
                                WHERE project_id = %s AND task_id = %s
                                """,
                                (snapshot.project_id, task_id),
                            )
                            previous = cursor.fetchone()
                            was_inserted = previous is None
                            was_changed = previous is not None and previous[0] != digest
                            inserted += int(was_inserted)
                            changed += int(was_changed)
                            status = str(task.get("status") or task.get("final_status") or "unknown")
                            cursor.execute(
                                """
                                INSERT INTO task_control.tasks (
                                    project_id, task_id, title, status, priority, complexity, task_type,
                                    worker_ready, terminal, source_kind, source_digest, source_revision,
                                    source_updated_at, payload, shadow_present, last_seen_run_id
                                )
                                VALUES (
                                    %s, %s, %s, %s, %s, %s, %s,
                                    %s, %s, %s, %s, %s,
                                    NULLIF(%s, '')::timestamptz, %s, true, %s
                                )
                                ON CONFLICT (project_id, task_id) DO UPDATE SET
                                    title = EXCLUDED.title,
                                    status = EXCLUDED.status,
                                    priority = EXCLUDED.priority,
                                    complexity = EXCLUDED.complexity,
                                    task_type = EXCLUDED.task_type,
                                    worker_ready = EXCLUDED.worker_ready,
                                    terminal = EXCLUDED.terminal,
                                    source_kind = EXCLUDED.source_kind,
                                    source_digest = EXCLUDED.source_digest,
                                    source_revision = EXCLUDED.source_revision,
                                    source_updated_at = EXCLUDED.source_updated_at,
                                    payload = EXCLUDED.payload,
                                    row_version = CASE
                                        WHEN task_control.tasks.source_digest <> EXCLUDED.source_digest
                                        THEN task_control.tasks.row_version + 1
                                        ELSE task_control.tasks.row_version
                                    END,
                                    shadow_present = true,
                                    last_seen_run_id = EXCLUDED.last_seen_run_id,
                                    updated_at = clock_timestamp()
                                """,
                                (
                                    snapshot.project_id,
                                    task_id,
                                    str(task.get("title") or task.get("summary") or task_id),
                                    status,
                                    task.get("priority"),
                                    task.get("complexity"),
                                    task.get("type"),
                                    task.get("worker_ready") is True,
                                    status in TERMINAL_STATUSES or source_kind == "history",
                                    source_kind,
                                    digest,
                                    snapshot.source_revision,
                                    str(task.get("updated_at") or ""),
                                    self._jsonb(task),
                                    run_id,
                                ),
                            )
                            cursor.execute(
                                """
                                DELETE FROM task_control.task_dependencies
                                WHERE project_id = %s AND task_id = %s
                                """,
                                (snapshot.project_id, task_id),
                            )
                            for dependency_id, dependency_kind in task_dependencies(task):
                                cursor.execute(
                                    """
                                    INSERT INTO task_control.task_dependencies
                                        (project_id, task_id, dependency_task_id, dependency_kind)
                                    VALUES (%s, %s, %s, %s)
                                    ON CONFLICT DO NOTHING
                                    """,
                                    (
                                        snapshot.project_id,
                                        task_id,
                                        dependency_id,
                                        dependency_kind,
                                    ),
                                )
                            if was_inserted or was_changed:
                                cursor.execute(
                                    """
                                    INSERT INTO task_control.task_events (
                                        idempotency_key, project_id, task_id, event_type,
                                        actor, payload
                                    )
                                    VALUES (%s, %s, %s, 'shadow_import', 'task-control-shadow', %s)
                                    ON CONFLICT (idempotency_key) DO NOTHING
                                    """,
                                    (
                                        f"{run_id}:{snapshot.project_id}:{task_id}",
                                        snapshot.project_id,
                                        task_id,
                                        self._jsonb(
                                            {
                                                "source_kind": source_kind,
                                                "source_digest": digest,
                                                "inserted": was_inserted,
                                            }
                                        ),
                                    ),
                                )
                        for (task_id, source_kind), record in snapshot_source_records(snapshot).items():
                            digest = str(record["source_digest"])
                            cursor.execute(
                                """
                                INSERT INTO task_control.task_source_records (
                                    project_id, task_id, source_kind, source_ordinal, source_digest,
                                    payload, source_present, last_seen_run_id
                                )
                                VALUES (%s, %s, %s, %s, %s, %s, true, %s)
                                ON CONFLICT (project_id, task_id, source_kind) DO UPDATE SET
                                    source_ordinal = EXCLUDED.source_ordinal,
                                    source_digest = EXCLUDED.source_digest,
                                    payload = EXCLUDED.payload,
                                    source_present = true,
                                    last_seen_run_id = EXCLUDED.last_seen_run_id,
                                    imported_at = clock_timestamp(),
                                    updated_at = clock_timestamp()
                                """,
                                (
                                    snapshot.project_id,
                                    task_id,
                                    source_kind,
                                    int(record.get("source_ordinal") or 0),
                                    digest,
                                    self._jsonb(record["payload"]),
                                    run_id,
                                ),
                            )
                            cursor.execute(
                                """
                                INSERT INTO task_control.shadow_run_tasks
                                    (run_id, project_id, task_id, source_kind, source_digest)
                                VALUES (%s, %s, %s, %s, %s)
                                """,
                                (run_id, snapshot.project_id, task_id, source_kind, digest),
                            )
                        cursor.execute(
                            """
                            UPDATE task_control.task_source_records
                            SET source_present = false, updated_at = clock_timestamp()
                            WHERE project_id = %s
                              AND last_seen_run_id IS DISTINCT FROM %s
                              AND source_present
                            """,
                            (snapshot.project_id, run_id),
                        )
                        cursor.execute(
                            """
                            UPDATE task_control.tasks
                            SET shadow_present = false, updated_at = clock_timestamp()
                            WHERE project_id = %s
                              AND source_kind <> 'native'
                              AND last_seen_run_id IS DISTINCT FROM %s
                              AND shadow_present
                            """,
                            (snapshot.project_id, run_id),
                        )
                        missing += cursor.rowcount
                    cursor.execute(
                        """
                        UPDATE task_control.shadow_runs
                        SET state = 'succeeded', finished_at = clock_timestamp()
                        WHERE run_id = %s
                        """,
                        (run_id,),
                    )
        except Exception as exc:
            self._record_failed_shadow_run(
                run_id,
                started_at=started_at,
                error=exc,
                metadata=metadata,
            )
            raise
        return {
            "ok": True,
            "run_id": run_id,
            "state": "succeeded",
            "project_count": len(snapshots),
            "task_count": sum(len(snapshot_source_records(snapshot)) for snapshot in snapshots),
            "inserted_count": inserted,
            "changed_count": changed,
            "missing_count": missing,
            "source_digest": fleet_digest,
        }

    def reconcile_snapshot(self, snapshot: TaskSnapshot) -> dict[str, Any]:
        expected = {
            task_id: (record["source_kind"], record["source_digest"])
            for task_id, record in snapshot.tasks.items()
        }
        expected_sources = {
            key: (
                int(record.get("source_ordinal") or 0),
                str(record["source_digest"]),
            )
            for key, record in snapshot_source_records(snapshot).items()
        }
        expected_documents = snapshot_source_documents(snapshot)
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT task_id, source_kind, source_digest
                    FROM task_control.tasks
                    WHERE project_id = %s AND shadow_present
                    """,
                    (snapshot.project_id,),
                )
                actual = {task_id: (source_kind, digest) for task_id, source_kind, digest in cursor.fetchall()}
                cursor.execute(
                    """
                    SELECT task_id, source_kind, source_ordinal, source_digest
                    FROM task_control.task_source_records
                    WHERE project_id = %s AND source_present
                    """,
                    (snapshot.project_id,),
                )
                actual_sources = {
                    (task_id, source_kind): (int(source_ordinal), digest)
                    for task_id, source_kind, source_ordinal, digest in cursor.fetchall()
                }
                cursor.execute(
                    """
                    SELECT queue_envelope, history_envelope
                    FROM task_control.projects
                    WHERE project_id = %s
                    """,
                    (snapshot.project_id,),
                )
                envelope_row = cursor.fetchone()
                actual_documents = (
                    {"queue": envelope_row[0], "history": envelope_row[1]}
                    if envelope_row is not None
                    else {}
                )
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        changed = sorted(
            task_id
            for task_id in set(expected) & set(actual)
            if expected[task_id][1] != actual[task_id][1]
        )
        source_kind_mismatches = sorted(
            task_id
            for task_id in set(expected) & set(actual)
            if expected[task_id][0] != actual[task_id][0]
        )
        missing_sources = sorted(set(expected_sources) - set(actual_sources))
        extra_sources = sorted(set(actual_sources) - set(expected_sources))
        changed_sources = sorted(
            key
            for key in set(expected_sources) & set(actual_sources)
            if expected_sources[key] != actual_sources[key]
        )
        changed_documents = sorted(
            source_kind
            for source_kind in {"queue", "history"}
            if expected_documents.get(source_kind) != actual_documents.get(source_kind)
        )
        return {
            "project_id": snapshot.project_id,
            "ok": not (
                missing
                or extra
                or changed
                or source_kind_mismatches
                or missing_sources
                or extra_sources
                or changed_sources
                or changed_documents
            ),
            "expected_count": len(expected),
            "actual_count": len(actual),
            "expected_source_record_count": len(expected_sources),
            "actual_source_record_count": len(actual_sources),
            "missing_task_ids": missing,
            "extra_task_ids": extra,
            "changed_task_ids": changed,
            "source_kind_mismatch_task_ids": source_kind_mismatches,
            "missing_source_records": [
                {"task_id": task_id, "source_kind": source_kind}
                for task_id, source_kind in missing_sources
            ],
            "extra_source_records": [
                {"task_id": task_id, "source_kind": source_kind}
                for task_id, source_kind in extra_sources
            ],
            "changed_source_records": [
                {"task_id": task_id, "source_kind": source_kind}
                for task_id, source_kind in changed_sources
            ],
            "changed_source_documents": changed_documents,
            "duplicate_task_ids": list(snapshot.duplicate_task_ids),
        }

    def export_project(self, project_id: str) -> dict[str, Any]:
        require_project_id(project_id)
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT queue_envelope, history_envelope, state_version,
                           source_digest, source_revision
                    FROM task_control.projects
                    WHERE project_id = %s
                    """,
                    (project_id,),
                )
                envelope_row = cursor.fetchone()
                if envelope_row is None:
                    raise TaskControlConflict(f"unknown project: {project_id}")
                queue_envelope, history_envelope, state_version, source_digest, source_revision = envelope_row
                cursor.execute(
                    """
                    SELECT source_kind, payload
                    FROM task_control.task_source_records
                    WHERE project_id = %s AND source_present
                    ORDER BY source_kind, source_ordinal, task_id
                    """,
                    (project_id,),
                )
                source_rows = cursor.fetchall()
                cursor.execute(
                    """
                    SELECT payload
                    FROM task_control.tasks
                    WHERE project_id = %s AND shadow_present AND source_kind = 'native'
                    ORDER BY task_id
                    """,
                    (project_id,),
                )
                native_rows = [row[0] for row in cursor.fetchall()]
                cursor.execute(
                    """
                    SELECT DISTINCT ON (candidates.task_id)
                           candidates.task_id, candidates.candidate_id,
                           COALESCE(candidates.merge_commit_sha, candidates.head_sha),
                           candidates.updated_at, sessions.state, candidates.evidence
                    FROM task_control.integration_candidates AS candidates
                    LEFT JOIN task_control.project_sessions AS sessions
                      ON sessions.session_id = candidates.owner_session_id
                    WHERE candidates.project_id = %s AND candidates.state = 'merged'
                    ORDER BY candidates.task_id, candidates.updated_at DESC,
                             candidates.candidate_id DESC
                    """,
                    (project_id,),
                )
                merged_candidates = {
                    row[0]: {
                        "candidate_id": row[1],
                        "merge_commit_sha": row[2],
                        "merged_at": row[3].isoformat(),
                        "source": str((row[5] or {}).get("source") or ""),
                    }
                    for row in cursor.fetchall()
                    if row[4] in {"aborted", "expired"}
                }
                cursor.execute(
                    """
                    SELECT DISTINCT ON (candidates.task_id)
                           candidates.task_id, candidates.candidate_id,
                           candidates.base_branch, candidates.base_sha,
                           candidates.work_branch, candidates.head_sha,
                           candidates.changed_paths, candidates.evidence,
                           candidates.updated_at
                    FROM task_control.integration_candidates AS candidates
                    JOIN task_control.tasks AS tasks
                      ON tasks.project_id = candidates.project_id
                     AND tasks.task_id = candidates.task_id
                    WHERE candidates.project_id = %s
                      AND candidates.state = 'ready'
                      AND candidates.evidence ->> 'source' = 'run_worker_cycle'
                      AND candidates.updated_at > tasks.updated_at
                    ORDER BY candidates.task_id, candidates.updated_at DESC,
                             candidates.candidate_id DESC
                    """,
                    (project_id,),
                )
                ready_worker_candidates = {
                    row[0]: {
                        "candidate_id": row[1],
                        "base_branch": row[2],
                        "base_sha": row[3],
                        "work_branch": row[4],
                        "head_sha": row[5],
                        "changed_paths": list(row[6] or []),
                        "evidence": dict(row[7] or {}),
                        "updated_at": row[8].isoformat(),
                    }
                    for row in cursor.fetchall()
                }
        queue = [payload for source_kind, payload in source_rows if source_kind == "queue"]
        queue.extend(native_rows)
        reconciled_queue: list[dict[str, Any]] = []
        for payload in queue:
            task_id = normalized_task_id(payload)
            candidate = merged_candidates.get(task_id)
            worker_candidate = ready_worker_candidates.get(task_id)
            if candidate is None and worker_candidate is None:
                reconciled_queue.append(payload)
                continue
            reconciled = dict(payload)
            if candidate is None and worker_candidate is not None:
                evidence = worker_candidate["evidence"]
                reconciled.update(
                    {
                        "status": "integration_requested",
                        "worker_ready": False,
                        "execution_ready": False,
                        "lock": "free",
                        "owner": None,
                        "worker_id": None,
                        "next_owner": "integrator",
                        "integration_status": "ready",
                        "worker_report": evidence.get("worker_report"),
                        "integration_candidate": {
                            "candidate_id": worker_candidate["candidate_id"],
                            "state": "ready",
                            "base_branch": worker_candidate["base_branch"],
                            "base_sha": worker_candidate["base_sha"],
                            "work_branch": worker_candidate["work_branch"],
                            "head_sha": worker_candidate["head_sha"],
                            "changed_paths": worker_candidate["changed_paths"],
                            "evidence": evidence,
                        },
                        "status_reason": "reconciled from durable pushed worker candidate",
                        "next_action": "Auto Integrator must validate and integrate the recorded worker branch without rerunning the worker.",
                        "updated_at": worker_candidate["updated_at"],
                    }
                )
                reconciled_queue.append(reconciled)
                continue
            if candidate["source"] == "integrator_direct_merge":
                reconciled.update(
                    {
                        "status": "integration_requested",
                        "worker_ready": False,
                        "execution_ready": False,
                        "lock": "free",
                        "owner": None,
                        "worker_id": None,
                        "next_owner": "integrator",
                        "merge_commit_sha": candidate["merge_commit_sha"],
                        "merge_commit": candidate["merge_commit_sha"],
                        "accepted_commit": candidate["merge_commit_sha"],
                        "integration_candidate_id": candidate["candidate_id"],
                        "integration_status": "needs_source_pr_close",
                        "finalization_status": "blocked_source_pr_open",
                        "status_reason": "recovered pushed integration; source PR closure pending",
                        "next_action": "Auto Integrator must close the source PR without reapplying the integrated payload.",
                        "updated_at": candidate["merged_at"],
                    }
                )
                reconciled_queue.append(reconciled)
                continue
            if str(reconciled.get("status") or "") not in TERMINAL_STATUSES:
                reconciled["previous_status"] = reconciled.get("status")
            reconciled.update(
                {
                    "status": "done",
                    "merge_commit_sha": candidate["merge_commit_sha"],
                    "accepted_commit": candidate["merge_commit_sha"],
                    "integration_candidate_id": candidate["candidate_id"],
                    "finalization_reason": "reconciled from durable merged SQL candidate",
                    "finalized_at": candidate["merged_at"],
                    "updated_at": candidate["merged_at"],
                }
            )
            reconciled_queue.append(
                normalize_terminal_task(reconciled, now=candidate["merged_at"])
            )
        queue = reconciled_queue
        history = [payload for source_kind, payload in source_rows if source_kind == "history"]
        return {
            "project_id": project_id,
            "state_version": int(state_version),
            "source_digest": source_digest,
            "source_revision": source_revision,
            "queue": {**queue_envelope, "tasks": queue},
            "history": {**history_envelope, "tasks": history},
        }

    def configure_runtime_authority(
        self,
        *,
        mode: str,
        source_of_truth: str,
        cutover_enabled: bool,
    ) -> dict[str, Any]:
        allowed = {
            ("shadow", "json_git", False),
            ("cutover", "postgres", True),
        }
        requested = (mode, source_of_truth, bool(cutover_enabled))
        if requested not in allowed:
            raise TaskControlConfigurationError(f"invalid runtime authority tuple: {requested!r}")
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE task_control.runtime_configuration
                    SET mode = %s, source_of_truth = %s, cutover_enabled = %s,
                        updated_at = clock_timestamp()
                    WHERE singleton
                    RETURNING updated_at
                    """,
                    requested,
                )
                updated_at = cursor.fetchone()[0]
        return {
            "ok": True,
            "mode": mode,
            "source_of_truth": source_of_truth,
            "cutover_enabled": bool(cutover_enabled),
            "updated_at": updated_at.isoformat(),
        }

    @contextmanager
    def cutover_activation_guard(self) -> Iterator[CutoverAuthorityGuard]:
        """Serialize the final shadow snapshot check with the authority switch."""
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT pg_advisory_xact_lock(hashtext('aistudio-task-control-shadow-import'))"
                )
                cursor.execute(
                    """
                    SELECT mode, source_of_truth, cutover_enabled
                    FROM task_control.runtime_configuration
                    WHERE singleton
                    FOR UPDATE
                    """
                )
                row = cursor.fetchone()
                if row is None:
                    raise TaskControlConflict("runtime authority row is missing")
                guard = CutoverAuthorityGuard(
                    cursor=cursor,
                    authority=(str(row[0]), str(row[1]), bool(row[2])),
                )
                yield guard

    @contextmanager
    def backup_snapshot_guard(self) -> Iterator[dict[str, Any]]:
        """Prevent shadow imports from crossing the pg_dump snapshot window."""
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT pg_advisory_xact_lock(hashtext('aistudio-task-control-shadow-import'))"
                )
                cursor.execute(
                    """
                    SELECT max(finished_at)
                    FROM task_control.shadow_runs
                    WHERE state = 'succeeded'
                    """
                )
                last_shadow_sync = cursor.fetchone()[0]
                yield {
                    "last_shadow_sync": (
                        last_shadow_sync.isoformat() if last_shadow_sync else None
                    )
                }

    def start_project_session(
        self,
        project_id: str,
        *,
        run_id: str,
        owner_id: str,
        ttl_seconds: int = 14400,
        session_id: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        require_project_id(project_id)
        if ttl_seconds < 60:
            raise TaskControlConfigurationError("project session ttl_seconds must be at least 60")
        session_id = session_id or f"session-{uuid.uuid4().hex}"
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT task_control.assert_runtime_authority('cutover', 'postgres', true)")
                cursor.execute(
                    "SELECT pg_advisory_xact_lock(hashtext('aistudio-task-control-project-publication'), hashtext(%s))",
                    (project_id,),
                )
                cursor.execute(
                    "SELECT clock_timestamp() + (%s * interval '1 second')",
                    (ttl_seconds,),
                )
                expires_at = cursor.fetchone()[0]
                cursor.execute(
                    """
                    WITH expired_sessions AS (
                        UPDATE task_control.project_sessions
                        SET state = 'expired', finished_at = clock_timestamp(),
                            finish_reason = 'ttl_expired'
                        WHERE project_id = %s AND state = 'active'
                          AND expires_at <= clock_timestamp()
                        RETURNING session_id
                    )
                    UPDATE task_control.task_leases
                    SET state = 'released', released_at = clock_timestamp(),
                        release_reason = 'project_session_ttl_expired'
                    WHERE project_id = %s AND state = 'active'
                      AND metadata ->> 'session_id' IN (
                          SELECT session_id FROM expired_sessions
                      )
                    """,
                    (project_id, project_id),
                )
                cursor.execute(
                    """
                    SELECT state_version, source_digest
                    FROM task_control.projects
                    WHERE project_id = %s
                    FOR UPDATE
                    """,
                    (project_id,),
                )
                project = cursor.fetchone()
                if project is None:
                    raise TaskControlConflict(f"unknown project: {project_id}")
                cursor.execute(
                    """
                    SELECT session_id, run_id, owner_id, expires_at
                    FROM task_control.project_sessions
                    WHERE project_id = %s AND state = 'active'
                    """,
                    (project_id,),
                )
                active = cursor.fetchone()
                if active is not None:
                    return {
                        "ok": False,
                        "reason": "project_session_active",
                        "session_id": active[0],
                        "run_id": active[1],
                        "owner_id": active[2],
                        "expires_at": active[3].isoformat(),
                    }
                cursor.execute(
                    """
                    INSERT INTO task_control.project_sessions (
                        session_id, project_id, run_id, owner_id, state,
                        base_state_version, expires_at, source_digest, metadata
                    ) VALUES (%s, %s, %s, %s, 'active', %s, %s, %s, %s)
                    """,
                    (
                        session_id,
                        project_id,
                        run_id,
                        owner_id,
                        int(project[0]),
                        expires_at,
                        project[1],
                        self._jsonb(metadata or {}),
                    ),
                )
        try:
            exported = self.export_project(project_id)
        except Exception:
            # Session creation committed before the compatibility snapshot is
            # exported. Never strand that session as active when export fails.
            self.abort_project_session(
                session_id,
                reason="project_session_snapshot_export_failed",
            )
            raise
        return {
            "ok": True,
            "session_id": session_id,
            "project_id": project_id,
            "run_id": run_id,
            "owner_id": owner_id,
            "base_state_version": int(project[0]),
            "expires_at": expires_at.isoformat(),
            "snapshot": exported,
        }

    @contextmanager
    def project_publication_guard(self, project_id: str) -> Iterator[None]:
        """Serialize session startup through compatibility publication."""
        require_project_id(project_id)
        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT pg_advisory_lock(hashtext('aistudio-task-control-project-publication'), hashtext(%s))",
                    (project_id,),
                )
            connection.commit()
            try:
                yield
            finally:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT pg_advisory_unlock(hashtext('aistudio-task-control-project-publication'), hashtext(%s))",
                        (project_id,),
                    )
                    unlocked = cursor.fetchone()
                connection.commit()
                if unlocked is None or unlocked[0] is not True:
                    raise TaskControlConflict(
                        f"project publication guard was not held: {project_id}"
                    )
        finally:
            connection.close()

    def commit_project_session(
        self,
        session_id: str,
        snapshot: TaskSnapshot,
        *,
        actor: str,
        idempotency_key: str,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        project_id = require_project_id(snapshot.project_id)
        source_records = snapshot_source_records(snapshot)
        source_documents = snapshot_source_documents(snapshot)
        primary: dict[str, dict[str, Any]] = {}
        for source_kind in ("history", "queue"):
            seen: set[str] = set()
            ordered = sorted(
                (
                    (task_id, record)
                    for (task_id, kind), record in source_records.items()
                    if kind == source_kind
                ),
                key=lambda item: (int(item[1].get("source_ordinal") or 0), item[0]),
            )
            for task_id, record in ordered:
                if task_id in seen:
                    raise TaskControlConfigurationError(
                        f"duplicate task id {task_id!r} inside {source_kind}"
                    )
                seen.add(task_id)
                primary[task_id] = record
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT task_control.assert_runtime_authority('cutover', 'postgres', true)")
                cursor.execute(
                    """
                    SELECT project_id, state, base_state_version, expires_at,
                           result_state_version, source_digest
                    FROM task_control.project_sessions
                    WHERE session_id = %s
                    FOR UPDATE
                    """,
                    (session_id,),
                )
                session = cursor.fetchone()
                if session is None or session[0] != project_id:
                    raise TaskControlConflict("unknown or mismatched project session")
                if session[1] == "committed":
                    cursor.execute(
                        """
                        UPDATE task_control.task_leases
                        SET state = 'released', released_at = clock_timestamp(),
                            release_reason = 'project_session_committed'
                        WHERE project_id = %s AND state = 'active'
                          AND metadata ->> 'session_id' = %s
                        """,
                        (project_id, session_id),
                    )
                    released_leases = cursor.rowcount
                    cursor.execute(
                        "SELECT result_digest FROM task_control.project_sessions WHERE session_id = %s",
                        (session_id,),
                    )
                    return {
                        "ok": True,
                        "idempotent_replay": True,
                        "state_version": int(session[4]),
                        "result_digest": cursor.fetchone()[0],
                        "released_leases": released_leases,
                    }
                if session[1] != "active":
                    raise TaskControlConflict(f"project session is not active: {session[1]}")
                if session[3] <= datetime.now(timezone.utc):
                    raise TaskControlConflict("project session expired before commit")
                cursor.execute(
                    """
                    SELECT state_version FROM task_control.projects
                    WHERE project_id = %s FOR UPDATE
                    """,
                    (project_id,),
                )
                current_version = int(cursor.fetchone()[0])
                if current_version != int(session[2]):
                    raise TaskControlConflict(
                        f"project state changed: expected {session[2]}, got {current_version}"
                    )
                cursor.execute(
                    """
                    SELECT to_state_version, result_digest
                    FROM task_control.project_state_commits
                    WHERE idempotency_key = %s
                    """,
                    (idempotency_key,),
                )
                replay = cursor.fetchone()
                if replay is not None:
                    return {
                        "ok": True,
                        "idempotent_replay": True,
                        "state_version": int(replay[0]),
                        "result_digest": replay[1],
                    }
                cursor.execute(
                    """
                    UPDATE task_control.task_source_records
                    SET source_present = false, updated_at = clock_timestamp()
                    WHERE project_id = %s AND source_present
                    """,
                    (project_id,),
                )
                cursor.execute(
                    """
                    UPDATE task_control.tasks
                    SET shadow_present = false, updated_at = clock_timestamp()
                    WHERE project_id = %s AND source_kind <> 'native' AND shadow_present
                    """,
                    (project_id,),
                )
                for (task_id, source_kind), record in source_records.items():
                    task = record["payload"]
                    digest = str(record.get("source_digest") or payload_digest(task))
                    status = str(task.get("status") or task.get("final_status") or "unknown")
                    cursor.execute(
                        """
                        INSERT INTO task_control.tasks (
                            project_id, task_id, title, status, worker_ready, terminal,
                            source_kind, source_digest, source_revision, payload, shadow_present
                        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, true)
                        ON CONFLICT (project_id, task_id) DO NOTHING
                        """,
                        (
                            project_id,
                            task_id,
                            str(task.get("title") or task.get("summary") or task_id),
                            status,
                            task.get("worker_ready") is True,
                            status in TERMINAL_STATUSES or source_kind == "history",
                            source_kind,
                            digest,
                            snapshot.source_revision,
                            self._jsonb(task),
                        ),
                    )
                    cursor.execute(
                        """
                        INSERT INTO task_control.task_source_records (
                            project_id, task_id, source_kind, source_ordinal,
                            source_digest, payload, source_present
                        ) VALUES (%s, %s, %s, %s, %s, %s, true)
                        ON CONFLICT (project_id, task_id, source_kind) DO UPDATE SET
                            source_ordinal = EXCLUDED.source_ordinal,
                            source_digest = EXCLUDED.source_digest,
                            payload = EXCLUDED.payload,
                            source_present = true,
                            updated_at = clock_timestamp()
                        """,
                        (
                            project_id,
                            task_id,
                            source_kind,
                            int(record.get("source_ordinal") or 0),
                            digest,
                            self._jsonb(task),
                        ),
                    )
                for task_id, record in primary.items():
                    task = record["payload"]
                    source_kind = str(record["source_kind"])
                    digest = str(record.get("source_digest") or payload_digest(task))
                    status = str(task.get("status") or task.get("final_status") or "unknown")
                    cursor.execute(
                        """
                        INSERT INTO task_control.tasks (
                            project_id, task_id, title, status, priority, complexity,
                            task_type, worker_ready, terminal, source_kind,
                            source_digest, source_revision, source_updated_at, payload,
                            shadow_present
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                            %s, %s, NULLIF(%s, '')::timestamptz, %s, true
                        )
                        ON CONFLICT (project_id, task_id) DO UPDATE SET
                            title = EXCLUDED.title,
                            status = EXCLUDED.status,
                            priority = EXCLUDED.priority,
                            complexity = EXCLUDED.complexity,
                            task_type = EXCLUDED.task_type,
                            worker_ready = EXCLUDED.worker_ready,
                            terminal = EXCLUDED.terminal,
                            source_kind = EXCLUDED.source_kind,
                            source_digest = EXCLUDED.source_digest,
                            source_revision = EXCLUDED.source_revision,
                            source_updated_at = EXCLUDED.source_updated_at,
                            payload = EXCLUDED.payload,
                            row_version = CASE
                                WHEN task_control.tasks.source_digest <> EXCLUDED.source_digest
                                THEN task_control.tasks.row_version + 1
                                ELSE task_control.tasks.row_version
                            END,
                            shadow_present = true,
                            updated_at = clock_timestamp()
                        """,
                        (
                            project_id,
                            task_id,
                            str(task.get("title") or task.get("summary") or task_id),
                            status,
                            task.get("priority"),
                            task.get("complexity"),
                            task.get("type"),
                            task.get("worker_ready") is True,
                            status in TERMINAL_STATUSES or source_kind == "history",
                            source_kind,
                            digest,
                            snapshot.source_revision,
                            str(task.get("updated_at") or ""),
                            self._jsonb(task),
                        ),
                    )
                    cursor.execute(
                        "DELETE FROM task_control.task_dependencies WHERE project_id = %s AND task_id = %s",
                        (project_id, task_id),
                    )
                    for dependency_id, dependency_kind in task_dependencies(task):
                        cursor.execute(
                            """
                            INSERT INTO task_control.task_dependencies
                                (project_id, task_id, dependency_task_id, dependency_kind)
                            VALUES (%s, %s, %s, %s) ON CONFLICT DO NOTHING
                            """,
                            (project_id, task_id, dependency_id, dependency_kind),
                        )
                    candidate = task.get("integration_candidate")
                    if isinstance(candidate, dict):
                        candidate_id = str(candidate.get("candidate_id") or "").strip()
                        base_sha = str(candidate.get("base_sha") or "").strip().lower()
                        head_sha = str(candidate.get("head_sha") or "").strip().lower()
                        work_branch = str(candidate.get("work_branch") or "").strip()
                        merge_commit_sha = str(
                            candidate.get("merge_commit_sha")
                            or task.get("merge_commit_sha")
                            or task.get("merge_commit")
                            or task.get("accepted_commit")
                            or ""
                        ).strip().lower()
                        state, candidate_evidence = integration_candidate_commit_state(
                            candidate,
                            task_status=status,
                            merge_commit_sha=merge_commit_sha,
                        )
                        if (
                            not candidate_id
                            or state not in {"draft", "ready", "integrating", "merged", "needs_human", "rejected", "archived"}
                            or not re.fullmatch(r"[0-9a-f]{40}", base_sha)
                            or not re.fullmatch(r"[0-9a-f]{40}", head_sha)
                            or not work_branch
                        ):
                            raise TaskControlConfigurationError(
                                f"invalid integration_candidate for {project_id}/{task_id}"
                            )
                        cursor.execute(
                            """
                            INSERT INTO task_control.integration_candidates (
                                project_id, task_id, candidate_id, state, base_branch,
                                base_sha, work_branch, head_sha, pull_request_number,
                                pull_request_url, latest_base_sha, merge_commit_sha,
                                changed_paths, evidence, owner_session_id
                            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                            ON CONFLICT (project_id, candidate_id) DO UPDATE SET
                                state = EXCLUDED.state,
                                base_branch = EXCLUDED.base_branch,
                                base_sha = EXCLUDED.base_sha,
                                work_branch = EXCLUDED.work_branch,
                                head_sha = EXCLUDED.head_sha,
                                pull_request_number = EXCLUDED.pull_request_number,
                                pull_request_url = EXCLUDED.pull_request_url,
                                latest_base_sha = EXCLUDED.latest_base_sha,
                                merge_commit_sha = EXCLUDED.merge_commit_sha,
                                changed_paths = EXCLUDED.changed_paths,
                                evidence = EXCLUDED.evidence,
                                owner_session_id = EXCLUDED.owner_session_id,
                                attempt_count = task_control.integration_candidates.attempt_count + 1,
                                updated_at = clock_timestamp()
                            """,
                            (
                                project_id,
                                task_id,
                                candidate_id,
                                state,
                                str(candidate.get("base_branch") or "develop"),
                                base_sha,
                                work_branch,
                                head_sha,
                                candidate.get("pull_request_number"),
                                candidate.get("pull_request_url"),
                                candidate.get("latest_base_sha"),
                                merge_commit_sha or None,
                                self._jsonb(list(candidate.get("changed_paths") or [])),
                                self._jsonb(candidate_evidence),
                                session_id,
                            ),
                        )
                    if str(task.get("lock") or "").lower() not in {"locked", "in_progress"}:
                        cursor.execute(
                            """
                            UPDATE task_control.task_leases
                            SET state = 'released', released_at = clock_timestamp(),
                                release_reason = 'project_state_commit'
                            WHERE project_id = %s AND task_id = %s AND state = 'active'
                            """,
                            (project_id, task_id),
                        )
                cursor.execute(
                    """
                    UPDATE task_control.task_leases AS leases
                    SET state = 'released', released_at = clock_timestamp(),
                        release_reason = 'task_absent_from_project_state'
                    WHERE leases.project_id = %s AND leases.state = 'active'
                      AND NOT EXISTS (
                          SELECT 1 FROM task_control.tasks AS tasks
                          WHERE tasks.project_id = leases.project_id
                            AND tasks.task_id = leases.task_id
                            AND tasks.shadow_present
                      )
                    """,
                    (project_id,),
                )
                next_version = current_version + 1
                cursor.execute(
                    """
                    UPDATE task_control.projects
                    SET source_revision = %s, source_digest = %s,
                        queue_envelope = %s, history_envelope = %s,
                        state_version = %s, updated_at = clock_timestamp()
                    WHERE project_id = %s
                    """,
                    (
                        snapshot.source_revision,
                        snapshot.source_digest,
                        self._jsonb(source_documents["queue"]),
                        self._jsonb(source_documents["history"]),
                        next_version,
                        project_id,
                    ),
                )
                cursor.execute(
                    """
                    INSERT INTO task_control.project_state_commits (
                        idempotency_key, project_id, session_id, from_state_version,
                        to_state_version, source_digest, result_digest, actor, metadata
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        idempotency_key,
                        project_id,
                        session_id,
                        current_version,
                        next_version,
                        str(session[5] or ""),
                        snapshot.source_digest,
                        actor,
                        self._jsonb(metadata or {}),
                    ),
                )
                cursor.execute(
                    """
                    UPDATE task_control.integration_candidates AS candidates
                    SET owner_session_id = %s,
                        evidence = candidates.evidence || jsonb_build_object(
                            'reconciled_by_session_id', %s::text,
                            'reconciled_at', clock_timestamp()
                        ),
                        updated_at = clock_timestamp()
                    FROM task_control.project_sessions AS owners
                    WHERE candidates.owner_session_id = owners.session_id
                      AND candidates.project_id = %s
                      AND candidates.state = 'merged'
                      AND owners.state IN ('aborted', 'expired')
                    """,
                    (session_id, session_id, project_id),
                )
                cursor.execute(
                    """
                    UPDATE task_control.project_sessions
                    SET state = 'committed', result_state_version = %s,
                        result_digest = %s, finished_at = clock_timestamp(),
                        finish_reason = 'committed'
                    WHERE session_id = %s
                    """,
                    (next_version, snapshot.source_digest, session_id),
                )
                cursor.execute(
                    """
                    UPDATE task_control.task_leases
                    SET state = 'released', released_at = clock_timestamp(),
                        release_reason = 'project_session_committed'
                    WHERE project_id = %s AND state = 'active'
                      AND metadata ->> 'session_id' = %s
                    """,
                    (project_id, session_id),
                )
                released_leases = cursor.rowcount
        return {
            "ok": True,
            "idempotent_replay": False,
            "state_version": next_version,
            "result_digest": snapshot.source_digest,
            "released_leases": released_leases,
        }

    def renew_project_session(
        self,
        session_id: str,
        *,
        owner_id: str,
        ttl_seconds: int,
    ) -> dict[str, Any]:
        if ttl_seconds < 60:
            raise TaskControlConfigurationError(
                "project session ttl_seconds must be at least 60"
            )
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE task_control.project_sessions
                    SET expires_at = clock_timestamp() + (%s * interval '1 second'),
                        metadata = metadata || %s
                    WHERE session_id = %s AND owner_id = %s
                      AND state = 'active' AND expires_at > clock_timestamp()
                    RETURNING project_id, expires_at
                    """,
                    (
                        ttl_seconds,
                        self._jsonb({"last_renewed_at": datetime.now(timezone.utc).isoformat()}),
                        session_id,
                        owner_id,
                    ),
                )
                renewed = cursor.fetchone()
                if renewed is None:
                    raise TaskControlConflict(
                        "project session cannot be renewed because it is not active"
                    )
        return {
            "ok": True,
            "session_id": session_id,
            "project_id": renewed[0],
            "expires_at": renewed[1].isoformat(),
        }

    def project_session_status(self, project_id: str, session_id: str) -> dict[str, Any]:
        require_project_id(project_id)
        if not str(session_id or "").strip():
            raise TaskControlConfigurationError("session_id is required")
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT state, result_state_version, result_digest, finished_at,
                           finish_reason
                    FROM task_control.project_sessions
                    WHERE project_id = %s AND session_id = %s
                    """,
                    (project_id, session_id),
                )
                row = cursor.fetchone()
        if row is None:
            return {
                "ok": False,
                "project_id": project_id,
                "session_id": session_id,
                "reason": "project_session_not_found",
            }
        return {
            "ok": True,
            "project_id": project_id,
            "session_id": session_id,
            "state": str(row[0]),
            "result_state_version": int(row[1]) if row[1] is not None else None,
            "result_digest": row[2],
            "finished_at": row[3].isoformat() if row[3] is not None else None,
            "finish_reason": row[4],
        }

    def abort_project_session(self, session_id: str, *, reason: str) -> dict[str, Any]:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT project_id FROM task_control.project_sessions WHERE session_id = %s FOR UPDATE",
                    (session_id,),
                )
                session = cursor.fetchone()
                if session is None:
                    return {
                        "ok": True,
                        "session_id": session_id,
                        "aborted": False,
                        "released_leases": 0,
                    }
                project_id = session[0]
                cursor.execute(
                    """
                    UPDATE task_control.project_sessions
                    SET state = 'aborted', finished_at = clock_timestamp(),
                        finish_reason = %s
                    WHERE session_id = %s AND state = 'active'
                    """,
                    (reason[:1000], session_id),
                )
                changed = cursor.rowcount
                cursor.execute(
                    """
                    UPDATE task_control.task_leases
                    SET state = 'released', released_at = clock_timestamp(),
                        release_reason = 'project_session_aborted'
                    WHERE project_id = %s AND state = 'active'
                      AND metadata ->> 'session_id' = %s
                    """,
                    (project_id, session_id),
                )
                released_leases = cursor.rowcount
        return {
            "ok": True,
            "session_id": session_id,
            "aborted": changed == 1,
            "released_leases": released_leases,
        }

    def upsert_integration_candidate(
        self,
        project_id: str,
        task_id: str,
        *,
        candidate_id: str,
        state: str,
        base_branch: str,
        base_sha: str,
        work_branch: str,
        head_sha: str,
        session_id: str,
        pull_request_number: int | None = None,
        pull_request_url: str | None = None,
        changed_paths: Sequence[str] = (),
        evidence: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        require_project_id(project_id)
        if not str(session_id or "").strip():
            raise TaskControlConfigurationError(
                "integration candidate requires an owning project session"
            )
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT task_control.assert_runtime_authority('cutover', 'postgres', true)")
                cursor.execute(
                    """
                    SELECT session_id
                    FROM task_control.project_sessions
                    WHERE session_id = %s AND project_id = %s
                      AND state = 'active' AND expires_at > clock_timestamp()
                    FOR UPDATE
                    """,
                    (session_id, project_id),
                )
                if cursor.fetchone() is None:
                    raise TaskControlConflict(
                        "integration candidate requires an active owning project session"
                    )
                cursor.execute(
                    """
                    INSERT INTO task_control.integration_candidates (
                        project_id, task_id, candidate_id, state, base_branch,
                        base_sha, work_branch, head_sha, pull_request_number,
                        pull_request_url, changed_paths, evidence, owner_session_id
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (project_id, candidate_id) DO UPDATE SET
                        state = EXCLUDED.state,
                        base_branch = EXCLUDED.base_branch,
                        base_sha = EXCLUDED.base_sha,
                        work_branch = EXCLUDED.work_branch,
                        head_sha = EXCLUDED.head_sha,
                        pull_request_number = EXCLUDED.pull_request_number,
                        pull_request_url = EXCLUDED.pull_request_url,
                        changed_paths = EXCLUDED.changed_paths,
                        evidence = EXCLUDED.evidence,
                        owner_session_id = EXCLUDED.owner_session_id,
                        attempt_count = task_control.integration_candidates.attempt_count + 1,
                        updated_at = clock_timestamp()
                    RETURNING attempt_count, updated_at
                    """,
                    (
                        project_id,
                        task_id,
                        candidate_id,
                        state,
                        base_branch,
                        base_sha,
                        work_branch,
                        head_sha,
                        pull_request_number,
                        pull_request_url,
                        self._jsonb(list(changed_paths)),
                        self._jsonb(evidence or {}),
                        session_id,
                    ),
                )
                attempt_count, updated_at = cursor.fetchone()
        return {
            "ok": True,
            "project_id": project_id,
            "task_id": task_id,
            "candidate_id": candidate_id,
            "state": state,
            "attempt_count": int(attempt_count),
            "updated_at": updated_at.isoformat(),
        }

    def integration_recovery_candidates(self, project_id: str) -> list[dict[str, Any]]:
        require_project_id(project_id)
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT task_control.assert_runtime_authority('cutover', 'postgres', true)")
                cursor.execute(
                    """
                    SELECT candidates.candidate_id, candidates.task_id,
                           candidates.base_branch, candidates.head_sha,
                           candidates.work_branch, candidates.evidence,
                           candidates.owner_session_id, sessions.state
                    FROM task_control.integration_candidates AS candidates
                    JOIN task_control.project_sessions AS sessions
                      ON sessions.session_id = candidates.owner_session_id
                    WHERE candidates.project_id = %s
                      AND candidates.state = 'integrating'
                      AND sessions.state IN ('aborted', 'expired')
                    ORDER BY candidates.updated_at, candidates.candidate_id
                    """,
                    (project_id,),
                )
                rows = cursor.fetchall()
        return [
            {
                "candidate_id": row[0],
                "task_id": row[1],
                "base_branch": row[2],
                "head_sha": row[3],
                "work_branch": row[4],
                "source": str((row[5] or {}).get("source") or ""),
                "owner_session_id": row[6],
                "owner_session_state": row[7],
            }
            for row in rows
        ]

    def recover_pushed_integration_candidate(
        self,
        project_id: str,
        candidate_id: str,
        *,
        recovery_session_id: str,
        evidence: dict[str, Any],
    ) -> dict[str, Any]:
        require_project_id(project_id)
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT task_control.assert_runtime_authority('cutover', 'postgres', true)")
                cursor.execute(
                    """
                    SELECT session_id
                    FROM task_control.project_sessions
                    WHERE session_id = %s AND project_id = %s
                      AND state = 'active' AND expires_at > clock_timestamp()
                    FOR UPDATE
                    """,
                    (recovery_session_id, project_id),
                )
                if cursor.fetchone() is None:
                    raise TaskControlConflict(
                        "pushed integration recovery requires an active project session"
                    )
                cursor.execute(
                    """
                    SELECT candidates.task_id, candidates.head_sha,
                           candidates.work_branch
                    FROM task_control.integration_candidates AS candidates
                    JOIN task_control.project_sessions AS sessions
                      ON sessions.session_id = candidates.owner_session_id
                    WHERE candidates.project_id = %s
                      AND candidates.candidate_id = %s
                      AND candidates.state = 'integrating'
                      AND sessions.state IN ('aborted', 'expired')
                    FOR UPDATE OF candidates
                    """,
                    (project_id, candidate_id),
                )
                candidate = cursor.fetchone()
                if candidate is None:
                    raise TaskControlConflict(
                        "integration candidate is not recoverable as a pushed branch"
                    )
                cursor.execute(
                    """
                    UPDATE task_control.integration_candidates
                    SET state = 'ready', owner_session_id = %s,
                        evidence = evidence || %s,
                        attempt_count = attempt_count + 1,
                        updated_at = clock_timestamp()
                    WHERE project_id = %s AND candidate_id = %s
                    RETURNING updated_at
                    """,
                    (
                        recovery_session_id,
                        self._jsonb(evidence),
                        project_id,
                        candidate_id,
                    ),
                )
                updated_at = cursor.fetchone()[0]
        return {
            "ok": True,
            "project_id": project_id,
            "task_id": candidate[0],
            "candidate_id": candidate_id,
            "state": "ready",
            "head_sha": candidate[1],
            "work_branch": candidate[2],
            "updated_at": updated_at.isoformat(),
        }

    def recover_integration_candidate(
        self,
        project_id: str,
        candidate_id: str,
        *,
        recovery_session_id: str,
        evidence: dict[str, Any],
    ) -> dict[str, Any]:
        require_project_id(project_id)
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT task_control.assert_runtime_authority('cutover', 'postgres', true)")
                cursor.execute(
                    """
                    SELECT session_id
                    FROM task_control.project_sessions
                    WHERE session_id = %s AND project_id = %s
                      AND state = 'active' AND expires_at > clock_timestamp()
                    FOR UPDATE
                    """,
                    (recovery_session_id, project_id),
                )
                if cursor.fetchone() is None:
                    raise TaskControlConflict(
                        "integration recovery requires an active project session"
                    )
                cursor.execute(
                    """
                    SELECT candidates.head_sha
                    FROM task_control.integration_candidates AS candidates
                    JOIN task_control.project_sessions AS sessions
                      ON sessions.session_id = candidates.owner_session_id
                    WHERE candidates.project_id = %s
                      AND candidates.candidate_id = %s
                      AND candidates.state = 'integrating'
                      AND sessions.state IN ('aborted', 'expired')
                    FOR UPDATE OF candidates
                    """,
                    (project_id, candidate_id),
                )
                row = cursor.fetchone()
                if row is None:
                    raise TaskControlConflict(
                        "integration candidate is not recoverable from an aborted session"
                    )
                cursor.execute(
                    """
                    UPDATE task_control.integration_candidates
                    SET state = 'merged', merge_commit_sha = head_sha,
                        evidence = evidence || %s,
                        attempt_count = attempt_count + 1,
                        updated_at = clock_timestamp()
                    WHERE project_id = %s AND candidate_id = %s
                    RETURNING task_id, head_sha, updated_at
                    """,
                    (self._jsonb(evidence), project_id, candidate_id),
                )
                task_id, head_sha, updated_at = cursor.fetchone()
        return {
            "ok": True,
            "project_id": project_id,
            "task_id": task_id,
            "candidate_id": candidate_id,
            "state": "merged",
            "merge_commit_sha": head_sha,
            "updated_at": updated_at.isoformat(),
        }

    def transition_task(
        self,
        project_id: str,
        task_id: str,
        *,
        expected_status: str,
        new_status: str,
        actor: str,
        idempotency_key: str,
        expected_version: int | None = None,
        event_payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        require_project_id(project_id)
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT mode, source_of_truth, cutover_enabled FROM task_control.runtime_configuration WHERE singleton"
                )
                authority = cursor.fetchone()
                if authority == ("cutover", "postgres", True):
                    cursor.execute(
                        """
                        SELECT session_id FROM task_control.project_sessions
                        WHERE project_id = %s AND state = 'active'
                          AND expires_at > clock_timestamp()
                        """,
                        (project_id,),
                    )
                    active_session = cursor.fetchone()
                    if active_session is not None:
                        raise TaskControlConflict(
                            f"project is owned by compatibility session {active_session[0]}"
                        )
                cursor.execute(
                    """
                    SELECT event_id FROM task_control.task_events
                    WHERE idempotency_key = %s
                    """,
                    (idempotency_key,),
                )
                if cursor.fetchone() is not None:
                    cursor.execute(
                        """
                        SELECT status, row_version FROM task_control.tasks
                        WHERE project_id = %s AND task_id = %s
                        """,
                        (project_id, task_id),
                    )
                    row = cursor.fetchone()
                    return {
                        "ok": True,
                        "idempotent_replay": True,
                        "status": row[0],
                        "row_version": int(row[1]),
                    }
                cursor.execute(
                    """
                    SELECT status, row_version, payload, source_kind
                    FROM task_control.tasks
                    WHERE project_id = %s AND task_id = %s
                    FOR UPDATE
                    """,
                    (project_id, task_id),
                )
                row = cursor.fetchone()
                if row is None:
                    raise TaskControlConflict(f"unknown task: {project_id}/{task_id}")
                current_status, row_version, payload, source_kind = row
                if current_status != expected_status:
                    raise TaskControlConflict(
                        f"task status changed: expected {expected_status!r}, got {current_status!r}"
                    )
                if expected_version is not None and int(row_version) != expected_version:
                    raise TaskControlConflict(
                        f"task version changed: expected {expected_version}, got {row_version}"
                    )
                updated_payload = dict(payload)
                updated_payload["status"] = new_status
                digest = payload_digest(updated_payload)
                cursor.execute(
                    """
                    UPDATE task_control.tasks
                    SET status = %s,
                        terminal = %s,
                        source_digest = %s,
                        payload = %s,
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE project_id = %s AND task_id = %s
                    RETURNING row_version
                    """,
                    (
                        new_status,
                        new_status in TERMINAL_STATUSES,
                        digest,
                        self._jsonb(updated_payload),
                        project_id,
                        task_id,
                    ),
                )
                new_version = int(cursor.fetchone()[0])
                cursor.execute(
                    """
                    UPDATE task_control.task_source_records
                    SET source_digest = %s, payload = %s,
                        updated_at = clock_timestamp()
                    WHERE project_id = %s AND task_id = %s
                      AND source_kind = %s AND source_present
                    """,
                    (
                        digest,
                        self._jsonb(updated_payload),
                        project_id,
                        task_id,
                        source_kind,
                    ),
                )
                cursor.execute(
                    """
                    SELECT queue_envelope, history_envelope
                    FROM task_control.projects
                    WHERE project_id = %s FOR UPDATE
                    """,
                    (project_id,),
                )
                queue_envelope, history_envelope = cursor.fetchone()
                cursor.execute(
                    """
                    SELECT source_kind, source_ordinal, task_id, payload
                    FROM task_control.task_source_records
                    WHERE project_id = %s AND source_present
                    ORDER BY source_kind, source_ordinal, task_id
                    """,
                    (project_id,),
                )
                records = cursor.fetchall()
                project_digest = payload_digest(
                    {
                        "queue": {
                            **queue_envelope,
                            "tasks": [row[3] for row in records if row[0] == "queue"],
                        },
                        "history": {
                            **history_envelope,
                            "tasks": [row[3] for row in records if row[0] == "history"],
                        },
                    }
                )
                cursor.execute(
                    """
                    UPDATE task_control.projects
                    SET source_digest = %s, state_version = state_version + 1,
                        updated_at = clock_timestamp()
                    WHERE project_id = %s
                    """,
                    (project_digest, project_id),
                )
                cursor.execute(
                    """
                    INSERT INTO task_control.task_events (
                        idempotency_key, project_id, task_id, event_type,
                        from_status, to_status, actor, payload
                    )
                    VALUES (%s, %s, %s, 'status_transition', %s, %s, %s, %s)
                    """,
                    (
                        idempotency_key,
                        project_id,
                        task_id,
                        current_status,
                        new_status,
                        actor,
                        self._jsonb(event_payload or {}),
                    ),
                )
                cursor.execute(
                    """
                    INSERT INTO task_control.outbox_events
                        (topic, aggregate_key, event_type, payload)
                    VALUES ('task.lifecycle', %s, 'status_transition', %s)
                    """,
                    (
                        f"{project_id}:{task_id}",
                        self._jsonb(
                            {
                                "project_id": project_id,
                                "task_id": task_id,
                                "from_status": current_status,
                                "to_status": new_status,
                                "row_version": new_version,
                            }
                        ),
                    ),
                )
        return {
            "ok": True,
            "idempotent_replay": False,
            "status": new_status,
            "row_version": new_version,
        }

    def acquire_lease(
        self,
        project_id: str,
        task_id: str,
        *,
        owner_id: str,
        ttl_seconds: int,
        lease_id: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        require_project_id(project_id)
        if ttl_seconds < 1:
            raise TaskControlConfigurationError("lease ttl_seconds must be positive")
        lease_id = lease_id or f"lease-{uuid.uuid4().hex}"
        lease_metadata = metadata or {}
        session_id = str(lease_metadata.get("session_id") or "").strip()
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT pg_advisory_xact_lock(hashtext('aistudio-task-control-shadow-import'))"
                )
                cursor.execute(
                    "SELECT clock_timestamp() + (%s * interval '1 second')",
                    (ttl_seconds,),
                )
                expires_at = cursor.fetchone()[0]
                cursor.execute(
                    """
                    SELECT mode, source_of_truth, cutover_enabled
                    FROM task_control.runtime_configuration
                    WHERE singleton
                    FOR SHARE
                    """
                )
                authority = cursor.fetchone()
                if authority == ("cutover", "postgres", True) and not session_id:
                    raise TaskControlConflict(
                        "cutover lease requires an active owning project session"
                    )
                if session_id:
                    cursor.execute(
                        """
                        SELECT session_id
                        FROM task_control.project_sessions
                        WHERE session_id = %s AND project_id = %s
                          AND state = 'active' AND expires_at > clock_timestamp()
                        FOR UPDATE
                        """,
                        (session_id, project_id),
                    )
                    if cursor.fetchone() is None:
                        raise TaskControlConflict(
                            "lease requires an active owning project session"
                        )
                cursor.execute(
                    """
                    SELECT status, terminal
                    FROM task_control.tasks
                    WHERE project_id = %s AND task_id = %s
                    FOR UPDATE
                    """,
                    (project_id, task_id),
                )
                task = cursor.fetchone()
                if task is None:
                    raise TaskControlConflict(f"unknown task: {project_id}/{task_id}")
                if task[1]:
                    raise TaskControlConflict("terminal task cannot be leased")
                cursor.execute(
                    """
                    UPDATE task_control.task_leases
                    SET state = 'expired', released_at = clock_timestamp(),
                        release_reason = 'ttl_expired'
                    WHERE project_id = %s AND task_id = %s
                      AND state = 'active' AND expires_at <= clock_timestamp()
                    """,
                    (project_id, task_id),
                )
                cursor.execute(
                    """
                    SELECT lease_id, owner_id, expires_at
                    FROM task_control.task_leases
                    WHERE project_id = %s AND task_id = %s AND state = 'active'
                    """,
                    (project_id, task_id),
                )
                active = cursor.fetchone()
                if active is not None:
                    return {
                        "ok": False,
                        "acquired": False,
                        "holder": active[1],
                        "lease_id": active[0],
                        "expires_at": active[2].isoformat(),
                    }
                cursor.execute(
                    """
                    INSERT INTO task_control.task_leases (
                        lease_id, project_id, task_id, owner_id, state, expires_at, metadata
                    )
                    VALUES (%s, %s, %s, %s, 'active', %s, %s)
                    """,
                    (
                        lease_id,
                        project_id,
                        task_id,
                        owner_id,
                        expires_at,
                        self._jsonb(lease_metadata),
                    ),
                )
        return {
            "ok": True,
            "acquired": True,
            "holder": owner_id,
            "lease_id": lease_id,
            "expires_at": expires_at.isoformat(),
        }

    def release_lease(
        self,
        lease_id: str,
        *,
        owner_id: str,
        reason: str = "completed",
    ) -> bool:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE task_control.task_leases
                    SET state = 'released', released_at = clock_timestamp(), release_reason = %s
                    WHERE lease_id = %s AND owner_id = %s AND state = 'active'
                    """,
                    (reason, lease_id, owner_id),
                )
                return cursor.rowcount == 1

    def upsert_attempt(
        self,
        project_id: str,
        task_id: str,
        *,
        attempt_id: str,
        stage: str,
        status: str,
        model: str | None = None,
        reasoning_effort: str | None = None,
        skills: Sequence[str] = (),
        accepted: bool | None = None,
        result_digest: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO task_control.task_attempts (
                        project_id, task_id, attempt_id, stage, status, model,
                        reasoning_effort, skills, accepted, result_digest, metadata,
                        started_at, finished_at
                    )
                    VALUES (
                        %s, %s, %s, %s, %s, %s,
                        %s, %s, %s, %s, %s,
                        CASE WHEN %s = 'running' THEN clock_timestamp() ELSE NULL END,
                        CASE WHEN %s IN ('succeeded', 'failed', 'cancelled') THEN clock_timestamp() ELSE NULL END
                    )
                    ON CONFLICT (project_id, task_id, attempt_id) DO UPDATE SET
                        stage = EXCLUDED.stage,
                        status = EXCLUDED.status,
                        model = COALESCE(EXCLUDED.model, task_control.task_attempts.model),
                        reasoning_effort = COALESCE(
                            EXCLUDED.reasoning_effort,
                            task_control.task_attempts.reasoning_effort
                        ),
                        skills = EXCLUDED.skills,
                        accepted = EXCLUDED.accepted,
                        result_digest = EXCLUDED.result_digest,
                        metadata = EXCLUDED.metadata,
                        started_at = COALESCE(task_control.task_attempts.started_at, EXCLUDED.started_at),
                        finished_at = COALESCE(EXCLUDED.finished_at, task_control.task_attempts.finished_at),
                        updated_at = clock_timestamp()
                    """,
                    (
                        project_id,
                        task_id,
                        attempt_id,
                        stage,
                        status,
                        model,
                        reasoning_effort,
                        self._jsonb(list(skills)),
                        accepted,
                        result_digest,
                        self._jsonb(metadata or {}),
                        status,
                        status,
                    ),
                )

    def record_usage(
        self,
        project_id: str,
        task_id: str,
        *,
        idempotency_key: str,
        stage: str,
        attempt_id: str | None = None,
        model: str | None = None,
        input_tokens: int = 0,
        output_tokens: int = 0,
        cached_input_tokens: int = 0,
        reasoning_tokens: int = 0,
        tool_tokens: int = 0,
        effective_tokens: int | None = None,
        cost_usd: float | None = None,
        wall_time_ms: int | None = None,
        cpu_time_ms: int | None = None,
        max_rss_kb: int | None = None,
        outcome: str | None = None,
        accepted: bool | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> bool:
        counts = [input_tokens, output_tokens, cached_input_tokens, reasoning_tokens, tool_tokens]
        if any(value < 0 for value in counts):
            raise TaskControlConfigurationError("token counts cannot be negative")
        effective = (
            effective_tokens
            if effective_tokens is not None
            else input_tokens + output_tokens + reasoning_tokens + tool_tokens
        )
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO task_control.resource_usage (
                        idempotency_key, project_id, task_id, attempt_id, stage, model,
                        input_tokens, output_tokens, cached_input_tokens, reasoning_tokens,
                        tool_tokens, effective_tokens, cost_usd, wall_time_ms, cpu_time_ms,
                        max_rss_kb, outcome, accepted, metadata
                    )
                    VALUES (
                        %s, %s, %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s, %s,
                        %s, %s, %s, %s
                    )
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """,
                    (
                        idempotency_key,
                        project_id,
                        task_id,
                        attempt_id,
                        stage,
                        model,
                        input_tokens,
                        output_tokens,
                        cached_input_tokens,
                        reasoning_tokens,
                        tool_tokens,
                        effective,
                        cost_usd,
                        wall_time_ms,
                        cpu_time_ms,
                        max_rss_kb,
                        outcome,
                        accepted,
                        self._jsonb(metadata or {}),
                    ),
                )
                return cursor.rowcount == 1

    def usage_summary(self, *, project_id: str | None = None) -> list[dict[str, Any]]:
        parameters: tuple[Any, ...] = ()
        where = ""
        if project_id is not None:
            require_project_id(project_id)
            where = "WHERE project_id = %s"
            parameters = (project_id,)
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    f"""
                    SELECT project_id, stage, model,
                           count(*) AS samples,
                           sum(effective_tokens) AS effective_tokens,
                           sum(cached_input_tokens) AS cached_input_tokens,
                           sum(wall_time_ms) AS wall_time_ms,
                           sum(cost_usd) AS cost_usd
                    FROM task_control.resource_usage
                    {where}
                    GROUP BY project_id, stage, model
                    ORDER BY project_id, stage, model NULLS FIRST
                    """,
                    parameters,
                )
                rows = cursor.fetchall()
        return [
            {
                "project_id": row[0],
                "stage": row[1],
                "model": row[2],
                "samples": int(row[3]),
                "effective_tokens": int(row[4] or 0),
                "cached_input_tokens": int(row[5] or 0),
                "wall_time_ms": int(row[6] or 0),
                "cost_usd": float(row[7]) if row[7] is not None else None,
            }
            for row in rows
        ]

    def record_backup(
        self,
        *,
        backup_id: str,
        created_at: str,
        database_name: str,
        sha256: str,
        size_bytes: int,
        verified: bool,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO task_control.backup_records (
                        backup_id, created_at, database_name, sha256,
                        size_bytes, verified_at, metadata
                    )
                    VALUES (
                        %s, %s::timestamptz, %s, %s,
                        %s, CASE WHEN %s THEN clock_timestamp() ELSE NULL END, %s
                    )
                    ON CONFLICT (backup_id) DO UPDATE SET
                        sha256 = EXCLUDED.sha256,
                        size_bytes = EXCLUDED.size_bytes,
                        verified_at = EXCLUDED.verified_at,
                        metadata = EXCLUDED.metadata
                    """,
                    (
                        backup_id,
                        created_at,
                        database_name,
                        sha256,
                        size_bytes,
                        verified,
                        self._jsonb(metadata or {}),
                    ),
                )
