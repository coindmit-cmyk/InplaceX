#!/usr/bin/env python3
"""Tenant-isolated Project Memory intake, review, retrieval and deletion runtime.

The active SQLite database belongs on reliable SSD/NVMe storage. Large raw
sources are stored as content-addressed JSON blobs under a separate bulk archive
root (for example, the AiStudio HDD). Nothing is retrieved across tenant or
project boundaries unless an owner explicitly promotes a de-identified item to
the internal pattern library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence


IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
WORD_RE = re.compile(r"\w+", re.UNICODE)

MEMORY_TYPES = {
    "project_profile",
    "decision",
    "rejected_idea",
    "task_context",
    "source_note",
    "customer_idea",
    "pattern",
}
ACCESS_LEVELS = {"project", "user", "internal", "owner_only", "public"}
CONSENT_STATES = {"unknown", "granted", "denied", "not_required"}
FRESHNESS_STATES = {"current", "review_required", "stale", "historical"}
PRINCIPAL_ROLES = {"project_member", "project_admin", "internal", "owner"}

SECRET_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("private_key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", re.I)),
    ("openai_key", re.compile(r"\bsk-[A-Za-z0-9_-]{16,}\b")),
    ("github_token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b", re.I)),
    ("bearer_token", re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{16,}\b", re.I)),
    (
        "assigned_secret",
        re.compile(
            r"\b(?:password|passwd|secret|api[_-]?key|access[_-]?token)\s*[:=]\s*[^\s,;]{8,}",
            re.I,
        ),
    ),
)
PII_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("email", re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.I)),
    ("phone", re.compile(r"(?<!\d)(?:\+?\d[\s().-]*){10,15}(?!\d)")),
)


class ProjectMemoryError(RuntimeError):
    """Base error for safe, user-facing Project Memory failures."""


class ValidationError(ProjectMemoryError):
    """Input or policy validation failed."""


class NotFoundError(ProjectMemoryError):
    """A memory item was not found inside the requested scope."""


@dataclass(frozen=True)
class StorageConfig:
    active_db: Path
    bulk_archive_root: Path
    pattern_tenant_id: str = "aistudio-internal"
    pattern_project_id: str = "pattern-library"
    max_results: int = 50


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _json_list(value: Any, field: str) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise ValidationError(f"{field} must be an array of non-empty strings")
    return [item.strip() for item in value]


def _identifier(field: str, value: Any) -> str:
    if not isinstance(value, str) or not IDENTIFIER_RE.fullmatch(value):
        raise ValidationError(f"{field} must match {IDENTIFIER_RE.pattern}")
    return value


def _required_text(field: str, value: Any, *, maximum: int = 20_000) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{field} must be a non-empty string")
    result = value.strip()
    if len(result) > maximum:
        raise ValidationError(f"{field} exceeds {maximum} characters")
    return result


def _optional_timestamp(field: str, value: Any) -> str | None:
    if value in (None, ""):
        return None
    if not isinstance(value, str):
        raise ValidationError(f"{field} must be an ISO-8601 string")
    candidate = value.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError as exc:
        raise ValidationError(f"{field} must be an ISO-8601 string") from exc
    if parsed.tzinfo is None:
        raise ValidationError(f"{field} must include a timezone")
    return value


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _resolve_config_path(config_file: Path, raw: Any, field: str) -> Path:
    text = _required_text(field, raw, maximum=4_096)
    expanded = Path(os.path.expandvars(os.path.expanduser(text)))
    if not expanded.is_absolute():
        expanded = config_file.parent / expanded
    return expanded.resolve()


def load_config(path: Path) -> StorageConfig:
    config_file = path.resolve()
    try:
        payload = json.loads(config_file.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ValidationError(f"config not found: {config_file}") from exc
    except json.JSONDecodeError as exc:
        raise ValidationError(f"invalid config JSON: {exc}") from exc

    if payload.get("schema_version") != 1:
        raise ValidationError("config schema_version must be 1")
    storage = payload.get("storage")
    if not isinstance(storage, dict):
        raise ValidationError("config.storage must be an object")
    active_db = _resolve_config_path(config_file, storage.get("active_db"), "storage.active_db")
    archive = _resolve_config_path(
        config_file,
        storage.get("bulk_archive_root"),
        "storage.bulk_archive_root",
    )
    if active_db == archive or _is_relative_to(active_db, archive):
        raise ValidationError("active_db must not be stored under bulk_archive_root")

    pattern = payload.get("pattern_library") or {}
    if not isinstance(pattern, dict):
        raise ValidationError("config.pattern_library must be an object")
    tenant_id = _identifier("pattern_library.tenant_id", pattern.get("tenant_id", "aistudio-internal"))
    project_id = _identifier("pattern_library.project_id", pattern.get("project_id", "pattern-library"))
    policy = payload.get("policy") or {}
    if not isinstance(policy, dict):
        raise ValidationError("config.policy must be an object")
    max_results = policy.get("max_results", 50)
    if not isinstance(max_results, int) or not 1 <= max_results <= 200:
        raise ValidationError("policy.max_results must be an integer from 1 to 200")
    return StorageConfig(active_db, archive, tenant_id, project_id, max_results)


def scan_sensitive_text(text: str) -> dict[str, list[str]]:
    return {
        "secrets": [name for name, pattern in SECRET_PATTERNS if pattern.search(text)],
        "personal_data": [name for name, pattern in PII_PATTERNS if pattern.search(text)],
    }


class ProjectMemoryStore:
    """SQLite-backed, tenant-scoped memory store with HDD raw-blob archiving."""

    def __init__(self, config: StorageConfig):
        self.config = config

    def connect(self) -> sqlite3.Connection:
        self.config.active_db.parent.mkdir(parents=True, exist_ok=True)
        self.config.bulk_archive_root.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(self.config.active_db)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA synchronous = NORMAL")
        self._ensure_schema(conn)
        return conn

    @staticmethod
    def _ensure_schema(conn: sqlite3.Connection) -> None:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS schema_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            INSERT OR REPLACE INTO schema_meta(key, value) VALUES ('schema_version', '1');

            CREATE TABLE IF NOT EXISTS memory_items (
                memory_id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                subject_user_id TEXT,
                memory_type TEXT NOT NULL,
                status TEXT NOT NULL,
                access_level TEXT NOT NULL,
                summary TEXT NOT NULL,
                searchable_text TEXT NOT NULL,
                facts_json TEXT NOT NULL,
                decisions_json TEXT NOT NULL,
                rejected_ideas_json TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                source_refs_json TEXT NOT NULL,
                evidence_refs_json TEXT NOT NULL,
                confidence REAL NOT NULL,
                freshness_state TEXT NOT NULL,
                freshness_reason TEXT NOT NULL,
                consent_state TEXT NOT NULL,
                contains_personal_data INTEGER NOT NULL,
                sensitivity_findings_json TEXT NOT NULL,
                raw_blob_ref TEXT,
                content_hash TEXT NOT NULL,
                idempotency_key TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                next_review_at TEXT,
                retention_until TEXT,
                reviewed_by TEXT,
                review_reason TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_memory_scope_status
                ON memory_items(tenant_id, project_id, status, freshness_state);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_idempotency
                ON memory_items(tenant_id, project_id, idempotency_key)
                WHERE idempotency_key IS NOT NULL;

            CREATE TABLE IF NOT EXISTS audit_events (
                event_id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                memory_id TEXT,
                event_type TEXT NOT NULL,
                actor_id TEXT NOT NULL,
                event_at TEXT NOT NULL,
                payload_json TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_audit_scope_time
                ON audit_events(tenant_id, project_id, event_at);
            """
        )
        try:
            conn.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                    memory_id UNINDEXED,
                    tenant_id UNINDEXED,
                    project_id UNINDEXED,
                    summary,
                    searchable_text,
                    tokenize='unicode61'
                )
                """
            )
            conn.execute("INSERT OR REPLACE INTO schema_meta(key, value) VALUES ('fts5', 'available')")
        except sqlite3.OperationalError:
            conn.execute("INSERT OR REPLACE INTO schema_meta(key, value) VALUES ('fts5', 'unavailable')")
        conn.commit()

    @staticmethod
    def _audit(
        conn: sqlite3.Connection,
        *,
        tenant_id: str,
        project_id: str,
        memory_id: str | None,
        event_type: str,
        actor_id: str,
        payload: dict[str, Any] | None = None,
    ) -> None:
        conn.execute(
            """
            INSERT INTO audit_events(
                event_id, tenant_id, project_id, memory_id, event_type,
                actor_id, event_at, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                str(uuid.uuid4()),
                tenant_id,
                project_id,
                memory_id,
                event_type,
                actor_id,
                utc_now(),
                _canonical_json(payload or {}),
            ),
        )

    @staticmethod
    def _fts_available(conn: sqlite3.Connection) -> bool:
        row = conn.execute("SELECT value FROM schema_meta WHERE key = 'fts5'").fetchone()
        return bool(row and row["value"] == "available")

    @classmethod
    def _index_item(cls, conn: sqlite3.Connection, row: sqlite3.Row) -> None:
        if not cls._fts_available(conn):
            return
        conn.execute("DELETE FROM memory_fts WHERE memory_id = ?", (row["memory_id"],))
        if row["status"] == "active" and row["freshness_state"] not in {"stale", "historical"}:
            conn.execute(
                """
                INSERT INTO memory_fts(memory_id, tenant_id, project_id, summary, searchable_text)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    row["memory_id"],
                    row["tenant_id"],
                    row["project_id"],
                    row["summary"],
                    row["searchable_text"],
                ),
            )

    def initialize(self) -> dict[str, Any]:
        with self.connect() as conn:
            fts = self._fts_available(conn)
        return {
            "ok": True,
            "schema_version": 1,
            "active_db": str(self.config.active_db),
            "bulk_archive_root": str(self.config.bulk_archive_root),
            "storage_tiers_separate": True,
            "fts5": fts,
        }

    def _write_raw_blob(
        self,
        *,
        tenant_id: str,
        project_id: str,
        payload: dict[str, Any],
        raw_text: str,
        content_hash: str,
    ) -> str:
        relative = Path("raw") / tenant_id / project_id / content_hash[:2] / f"{content_hash}.json"
        target = self.config.bulk_archive_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            body = {
                "schema_version": 1,
                "tenant_id": tenant_id,
                "project_id": project_id,
                "received_at": utc_now(),
                "source_refs": payload["source_refs"],
                "raw_text": raw_text,
            }
            temporary = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
            temporary.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            os.replace(temporary, target)
        return relative.as_posix()

    def intake(self, payload: dict[str, Any], *, actor_id: str) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValidationError("intake payload must be an object")
        actor_id = _identifier("actor_id", actor_id)
        tenant_id = _identifier("tenant_id", payload.get("tenant_id"))
        project_id = _identifier("project_id", payload.get("project_id"))
        subject_user_id = payload.get("subject_user_id")
        if subject_user_id is not None:
            subject_user_id = _identifier("subject_user_id", subject_user_id)
        memory_type = payload.get("memory_type", "source_note")
        if memory_type not in MEMORY_TYPES:
            raise ValidationError(f"memory_type must be one of {sorted(MEMORY_TYPES)}")
        access_level = payload.get("access_level", "project")
        if access_level not in ACCESS_LEVELS:
            raise ValidationError(f"access_level must be one of {sorted(ACCESS_LEVELS)}")
        if access_level == "user" and not subject_user_id:
            raise ValidationError("subject_user_id is required for user-scoped memory")
        consent_state = payload.get("consent_state", "unknown")
        if consent_state not in CONSENT_STATES:
            raise ValidationError(f"consent_state must be one of {sorted(CONSENT_STATES)}")
        if consent_state == "denied":
            raise ValidationError("content with denied consent must not be stored")

        summary = _required_text("summary", payload.get("summary"))
        facts = _json_list(payload.get("facts"), "facts")
        decisions = _json_list(payload.get("decisions"), "decisions")
        rejected_ideas = _json_list(payload.get("rejected_ideas"), "rejected_ideas")
        tags = _json_list(payload.get("tags"), "tags")
        source_refs = _json_list(payload.get("source_refs"), "source_refs")
        evidence_refs = _json_list(payload.get("evidence_refs"), "evidence_refs")
        if not source_refs:
            raise ValidationError("source_refs must contain at least one source")
        raw_text = payload.get("raw_text") or ""
        if not isinstance(raw_text, str):
            raise ValidationError("raw_text must be a string")
        if len(raw_text) > 5_000_000:
            raise ValidationError("raw_text exceeds the 5,000,000 character MVP limit")
        confidence = payload.get("confidence", 0.5)
        if not isinstance(confidence, (int, float)) or isinstance(confidence, bool) or not 0 <= confidence <= 1:
            raise ValidationError("confidence must be a number from 0 to 1")
        freshness_state = payload.get("freshness_state", "current")
        if freshness_state not in FRESHNESS_STATES:
            raise ValidationError(f"freshness_state must be one of {sorted(FRESHNESS_STATES)}")
        freshness_reason = _required_text(
            "freshness_reason",
            payload.get("freshness_reason", "new intake requires review"),
            maximum=2_000,
        )
        next_review_at = _optional_timestamp("next_review_at", payload.get("next_review_at"))
        retention_until = _optional_timestamp("retention_until", payload.get("retention_until"))
        idempotency_key = payload.get("idempotency_key")
        if idempotency_key is not None:
            idempotency_key = _identifier("idempotency_key", idempotency_key)

        searchable_parts = [summary, *facts, *decisions, *rejected_ideas, *tags]
        scan_text = "\n".join([*searchable_parts, raw_text])
        sensitivity = scan_sensitive_text(scan_text)
        if sensitivity["secrets"]:
            names = ", ".join(sensitivity["secrets"])
            raise ValidationError(f"secret-like content detected ({names}); item was not stored")
        contains_personal_data = bool(sensitivity["personal_data"])
        if contains_personal_data and access_level == "public":
            raise ValidationError("memory containing personal data cannot use public access")

        canonical_for_hash = {
            "tenant_id": tenant_id,
            "project_id": project_id,
            "summary": summary,
            "source_refs": source_refs,
            "raw_text": raw_text,
        }
        content_hash = hashlib.sha256(_canonical_json(canonical_for_hash).encode("utf-8")).hexdigest()
        memory_id = str(uuid.uuid4())
        created_at = utc_now()
        raw_blob_ref: str | None = None

        with self.connect() as conn:
            if idempotency_key:
                existing = conn.execute(
                    """
                    SELECT * FROM memory_items
                    WHERE tenant_id = ? AND project_id = ? AND idempotency_key = ?
                    """,
                    (tenant_id, project_id, idempotency_key),
                ).fetchone()
                if existing:
                    return {"ok": True, "created": False, "item": self._public_item(existing)}
            if raw_text:
                raw_blob_ref = self._write_raw_blob(
                    tenant_id=tenant_id,
                    project_id=project_id,
                    payload={"source_refs": source_refs},
                    raw_text=raw_text,
                    content_hash=content_hash,
                )
            conn.execute(
                """
                INSERT INTO memory_items(
                    memory_id, tenant_id, project_id, subject_user_id, memory_type,
                    status, access_level, summary, searchable_text, facts_json,
                    decisions_json, rejected_ideas_json, tags_json, source_refs_json,
                    evidence_refs_json, confidence, freshness_state, freshness_reason,
                    consent_state, contains_personal_data, sensitivity_findings_json,
                    raw_blob_ref, content_hash, idempotency_key, created_at, updated_at,
                    next_review_at, retention_until
                ) VALUES (?, ?, ?, ?, ?, 'candidate', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    memory_id,
                    tenant_id,
                    project_id,
                    subject_user_id,
                    memory_type,
                    access_level,
                    summary,
                    "\n".join(searchable_parts),
                    _canonical_json(facts),
                    _canonical_json(decisions),
                    _canonical_json(rejected_ideas),
                    _canonical_json(tags),
                    _canonical_json(source_refs),
                    _canonical_json(evidence_refs),
                    float(confidence),
                    freshness_state,
                    freshness_reason,
                    consent_state,
                    int(contains_personal_data),
                    _canonical_json(sensitivity),
                    raw_blob_ref,
                    content_hash,
                    idempotency_key,
                    created_at,
                    created_at,
                    next_review_at,
                    retention_until,
                ),
            )
            self._audit(
                conn,
                tenant_id=tenant_id,
                project_id=project_id,
                memory_id=memory_id,
                event_type="intake_created",
                actor_id=actor_id,
                payload={
                    "access_level": access_level,
                    "contains_personal_data": contains_personal_data,
                    "raw_blob_archived": bool(raw_blob_ref),
                },
            )
            row = conn.execute("SELECT * FROM memory_items WHERE memory_id = ?", (memory_id,)).fetchone()
        return {"ok": True, "created": True, "item": self._public_item(row)}

    @staticmethod
    def _scoped_item(
        conn: sqlite3.Connection,
        *,
        tenant_id: str,
        project_id: str,
        memory_id: str,
    ) -> sqlite3.Row:
        row = conn.execute(
            """
            SELECT * FROM memory_items
            WHERE tenant_id = ? AND project_id = ? AND memory_id = ?
            """,
            (tenant_id, project_id, memory_id),
        ).fetchone()
        if not row:
            raise NotFoundError("memory item not found in the requested tenant/project scope")
        return row

    def review(
        self,
        *,
        tenant_id: str,
        project_id: str,
        memory_id: str,
        decision: str,
        reviewer: str,
        reason: str,
    ) -> dict[str, Any]:
        tenant_id = _identifier("tenant_id", tenant_id)
        project_id = _identifier("project_id", project_id)
        memory_id = _required_text("memory_id", memory_id, maximum=128)
        reviewer = _identifier("reviewer", reviewer)
        reason = _required_text("reason", reason, maximum=2_000)
        if decision not in {"approve", "reject"}:
            raise ValidationError("decision must be approve or reject")
        with self.connect() as conn:
            row = self._scoped_item(
                conn,
                tenant_id=tenant_id,
                project_id=project_id,
                memory_id=memory_id,
            )
            if row["status"] != "candidate":
                raise ValidationError(f"only candidate items can be reviewed; current status is {row['status']}")
            if decision == "approve" and row["consent_state"] not in {"granted", "not_required"}:
                raise ValidationError("approval requires consent_state=granted or not_required")
            status = "active" if decision == "approve" else "rejected"
            conn.execute(
                """
                UPDATE memory_items
                SET status = ?, reviewed_by = ?, review_reason = ?, updated_at = ?
                WHERE tenant_id = ? AND project_id = ? AND memory_id = ?
                """,
                (status, reviewer, reason, utc_now(), tenant_id, project_id, memory_id),
            )
            updated = self._scoped_item(
                conn,
                tenant_id=tenant_id,
                project_id=project_id,
                memory_id=memory_id,
            )
            self._index_item(conn, updated)
            self._audit(
                conn,
                tenant_id=tenant_id,
                project_id=project_id,
                memory_id=memory_id,
                event_type=f"review_{decision}",
                actor_id=reviewer,
                payload={"reason": reason},
            )
        return {"ok": True, "item": self._public_item(updated)}

    @staticmethod
    def _access_allowed(row: sqlite3.Row, *, principal_id: str, principal_role: str) -> bool:
        access = row["access_level"]
        if access in {"project", "public"}:
            return True
        if access == "user":
            return row["subject_user_id"] == principal_id or principal_role in {"project_admin", "owner"}
        if access == "internal":
            return principal_role in {"internal", "owner"}
        if access == "owner_only":
            return principal_role == "owner"
        return False

    @staticmethod
    def _fts_query(query: str) -> str:
        words = [word for word in WORD_RE.findall(query) if len(word) > 1]
        return " AND ".join(f'"{word.replace(chr(34), chr(34) * 2)}"*' for word in words[:20])

    def retrieve(
        self,
        *,
        tenant_id: str,
        project_id: str,
        query: str,
        principal_id: str,
        principal_role: str = "project_member",
        limit: int = 10,
    ) -> dict[str, Any]:
        tenant_id = _identifier("tenant_id", tenant_id)
        project_id = _identifier("project_id", project_id)
        principal_id = _identifier("principal_id", principal_id)
        query = _required_text("query", query, maximum=2_000)
        if principal_role not in PRINCIPAL_ROLES:
            raise ValidationError(f"principal_role must be one of {sorted(PRINCIPAL_ROLES)}")
        if not isinstance(limit, int) or not 1 <= limit <= self.config.max_results:
            raise ValidationError(f"limit must be from 1 to {self.config.max_results}")

        with self.connect() as conn:
            candidates: Sequence[sqlite3.Row]
            fts_query = self._fts_query(query)
            if self._fts_available(conn) and fts_query:
                candidates = conn.execute(
                    """
                    SELECT m.*, bm25(memory_fts) AS rank
                    FROM memory_fts
                    JOIN memory_items AS m ON m.memory_id = memory_fts.memory_id
                    WHERE memory_fts MATCH ?
                      AND m.tenant_id = ? AND m.project_id = ?
                      AND m.status = 'active'
                      AND m.freshness_state NOT IN ('stale', 'historical')
                    ORDER BY rank, m.updated_at DESC
                    LIMIT ?
                    """,
                    (fts_query, tenant_id, project_id, limit * 4),
                ).fetchall()
            else:
                like = f"%{query.lower()}%"
                candidates = conn.execute(
                    """
                    SELECT * FROM memory_items
                    WHERE tenant_id = ? AND project_id = ?
                      AND status = 'active'
                      AND freshness_state NOT IN ('stale', 'historical')
                      AND lower(searchable_text) LIKE ?
                    ORDER BY updated_at DESC
                    LIMIT ?
                    """,
                    (tenant_id, project_id, like, limit * 4),
                ).fetchall()
            allowed = [
                row
                for row in candidates
                if self._access_allowed(row, principal_id=principal_id, principal_role=principal_role)
            ][:limit]
            query_hash = hashlib.sha256(query.encode("utf-8")).hexdigest()
            self._audit(
                conn,
                tenant_id=tenant_id,
                project_id=project_id,
                memory_id=None,
                event_type="retrieval",
                actor_id=principal_id,
                payload={
                    "query_sha256": query_hash,
                    "principal_role": principal_role,
                    "result_ids": [row["memory_id"] for row in allowed],
                },
            )
        return {
            "ok": True,
            "tenant_id": tenant_id,
            "project_id": project_id,
            "query_sha256": query_hash,
            "items": [self._public_item(row) for row in allowed],
        }

    def forget(
        self,
        *,
        tenant_id: str,
        project_id: str,
        memory_id: str,
        requester: str,
        reason: str,
    ) -> dict[str, Any]:
        tenant_id = _identifier("tenant_id", tenant_id)
        project_id = _identifier("project_id", project_id)
        requester = _identifier("requester", requester)
        reason = _required_text("reason", reason, maximum=2_000)
        with self.connect() as conn:
            row = self._scoped_item(
                conn,
                tenant_id=tenant_id,
                project_id=project_id,
                memory_id=memory_id,
            )
            blob_ref = row["raw_blob_ref"]
            conn.execute(
                """
                UPDATE memory_items
                SET status = 'deleted', summary = '[deleted]', searchable_text = '',
                    facts_json = '[]', decisions_json = '[]', rejected_ideas_json = '[]',
                    tags_json = '[]', source_refs_json = '[]', evidence_refs_json = '[]',
                    raw_blob_ref = NULL, updated_at = ?, review_reason = ?
                WHERE tenant_id = ? AND project_id = ? AND memory_id = ?
                """,
                (utc_now(), reason, tenant_id, project_id, memory_id),
            )
            if self._fts_available(conn):
                conn.execute("DELETE FROM memory_fts WHERE memory_id = ?", (memory_id,))
            self._audit(
                conn,
                tenant_id=tenant_id,
                project_id=project_id,
                memory_id=memory_id,
                event_type="forgotten",
                actor_id=requester,
                payload={"reason": reason, "raw_blob_removed": bool(blob_ref)},
            )
        if blob_ref:
            blob_path = (self.config.bulk_archive_root / blob_ref).resolve()
            if _is_relative_to(blob_path, self.config.bulk_archive_root.resolve()) and blob_path.exists():
                blob_path.unlink()
        return {"ok": True, "memory_id": memory_id, "status": "deleted"}

    def promote_pattern(
        self,
        *,
        source_tenant_id: str,
        source_project_id: str,
        memory_id: str,
        deidentified_summary: str,
        reviewer: str,
        consent_ref: str,
    ) -> dict[str, Any]:
        source_tenant_id = _identifier("source_tenant_id", source_tenant_id)
        source_project_id = _identifier("source_project_id", source_project_id)
        reviewer = _identifier("reviewer", reviewer)
        deidentified_summary = _required_text("deidentified_summary", deidentified_summary)
        consent_ref = _required_text("consent_ref", consent_ref, maximum=2_000)
        findings = scan_sensitive_text(deidentified_summary)
        if findings["secrets"] or findings["personal_data"]:
            raise ValidationError("deidentified_summary still contains secret-like or personal data")
        with self.connect() as conn:
            source = self._scoped_item(
                conn,
                tenant_id=source_tenant_id,
                project_id=source_project_id,
                memory_id=memory_id,
            )
            if source["status"] != "active":
                raise ValidationError("only active memory can be promoted")
            if source["contains_personal_data"]:
                raise ValidationError("memory containing personal data cannot be promoted")
            if source["consent_state"] != "granted":
                raise ValidationError("pattern promotion requires explicit consent_state=granted")

        intake_result = self.intake(
            {
                "tenant_id": self.config.pattern_tenant_id,
                "project_id": self.config.pattern_project_id,
                "memory_type": "pattern",
                "access_level": "internal",
                "summary": deidentified_summary,
                "source_refs": [
                    f"memory://{source_tenant_id}/{source_project_id}/{memory_id}",
                    consent_ref,
                ],
                "evidence_refs": json.loads(source["evidence_refs_json"]),
                "consent_state": "not_required",
                "confidence": source["confidence"],
                "freshness_reason": "owner-reviewed de-identified pattern promotion",
                "idempotency_key": f"promotion-{memory_id}",
            },
            actor_id=reviewer,
        )
        promoted_id = intake_result["item"]["memory_id"]
        reviewed = self.review(
            tenant_id=self.config.pattern_tenant_id,
            project_id=self.config.pattern_project_id,
            memory_id=promoted_id,
            decision="approve",
            reviewer=reviewer,
            reason="explicitly consented, de-identified pattern promotion",
        )
        return {
            "ok": True,
            "source_memory_id": memory_id,
            "promoted": reviewed["item"],
        }

    def maintenance(
        self,
        *,
        tenant_id: str,
        project_id: str,
        actor_id: str,
        apply: bool = False,
        now: str | None = None,
    ) -> dict[str, Any]:
        """Report or apply review-due and retention-expired transitions."""

        tenant_id = _identifier("tenant_id", tenant_id)
        project_id = _identifier("project_id", project_id)
        actor_id = _identifier("actor_id", actor_id)
        effective_now = _optional_timestamp("now", now) or utc_now()
        now_dt = datetime.fromisoformat(effective_now.replace("Z", "+00:00"))
        expired_blobs: list[Path] = []
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT * FROM memory_items
                WHERE tenant_id = ? AND project_id = ? AND status != 'deleted'
                ORDER BY created_at, memory_id
                """,
                (tenant_id, project_id),
            ).fetchall()
            due_review: list[str] = []
            expired: list[str] = []
            for row in rows:
                review_at = row["next_review_at"]
                retention_at = row["retention_until"]
                if retention_at and datetime.fromisoformat(retention_at.replace("Z", "+00:00")) <= now_dt:
                    expired.append(row["memory_id"])
                    continue
                if (
                    row["status"] == "active"
                    and row["freshness_state"] == "current"
                    and review_at
                    and datetime.fromisoformat(review_at.replace("Z", "+00:00")) <= now_dt
                ):
                    due_review.append(row["memory_id"])

            if apply:
                for memory_id in due_review:
                    conn.execute(
                        """
                        UPDATE memory_items
                        SET freshness_state = 'review_required',
                            freshness_reason = 'next_review_at reached', updated_at = ?
                        WHERE tenant_id = ? AND project_id = ? AND memory_id = ?
                        """,
                        (utc_now(), tenant_id, project_id, memory_id),
                    )
                    updated = self._scoped_item(
                        conn,
                        tenant_id=tenant_id,
                        project_id=project_id,
                        memory_id=memory_id,
                    )
                    self._index_item(conn, updated)
                    self._audit(
                        conn,
                        tenant_id=tenant_id,
                        project_id=project_id,
                        memory_id=memory_id,
                        event_type="freshness_review_required",
                        actor_id=actor_id,
                        payload={"effective_now": effective_now},
                    )
                for memory_id in expired:
                    row = self._scoped_item(
                        conn,
                        tenant_id=tenant_id,
                        project_id=project_id,
                        memory_id=memory_id,
                    )
                    if row["raw_blob_ref"]:
                        expired_blobs.append((self.config.bulk_archive_root / row["raw_blob_ref"]).resolve())
                    conn.execute(
                        """
                        UPDATE memory_items
                        SET status = 'deleted', summary = '[deleted]', searchable_text = '',
                            facts_json = '[]', decisions_json = '[]', rejected_ideas_json = '[]',
                            tags_json = '[]', source_refs_json = '[]', evidence_refs_json = '[]',
                            raw_blob_ref = NULL, updated_at = ?,
                            review_reason = 'retention period expired'
                        WHERE tenant_id = ? AND project_id = ? AND memory_id = ?
                        """,
                        (utc_now(), tenant_id, project_id, memory_id),
                    )
                    if self._fts_available(conn):
                        conn.execute("DELETE FROM memory_fts WHERE memory_id = ?", (memory_id,))
                    self._audit(
                        conn,
                        tenant_id=tenant_id,
                        project_id=project_id,
                        memory_id=memory_id,
                        event_type="retention_expired",
                        actor_id=actor_id,
                        payload={"effective_now": effective_now},
                    )
        if apply:
            archive_root = self.config.bulk_archive_root.resolve()
            for blob_path in expired_blobs:
                if _is_relative_to(blob_path, archive_root) and blob_path.exists():
                    blob_path.unlink()
        return {
            "ok": True,
            "tenant_id": tenant_id,
            "project_id": project_id,
            "effective_now": effective_now,
            "apply": apply,
            "due_review": due_review,
            "expired": expired,
        }

    def audit_events(self, *, tenant_id: str, project_id: str) -> list[dict[str, Any]]:
        tenant_id = _identifier("tenant_id", tenant_id)
        project_id = _identifier("project_id", project_id)
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT * FROM audit_events
                WHERE tenant_id = ? AND project_id = ?
                ORDER BY event_at, event_id
                """,
                (tenant_id, project_id),
            ).fetchall()
        return [
            {
                "event_id": row["event_id"],
                "memory_id": row["memory_id"],
                "event_type": row["event_type"],
                "actor_id": row["actor_id"],
                "event_at": row["event_at"],
                "payload": json.loads(row["payload_json"]),
            }
            for row in rows
        ]

    def stats(self, *, tenant_id: str, project_id: str) -> dict[str, Any]:
        tenant_id = _identifier("tenant_id", tenant_id)
        project_id = _identifier("project_id", project_id)
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT status, COUNT(*) AS count
                FROM memory_items
                WHERE tenant_id = ? AND project_id = ?
                GROUP BY status
                """,
                (tenant_id, project_id),
            ).fetchall()
        return {
            "ok": True,
            "tenant_id": tenant_id,
            "project_id": project_id,
            "status_counts": {row["status"]: row["count"] for row in rows},
        }

    @staticmethod
    def _public_item(row: sqlite3.Row | None) -> dict[str, Any]:
        if row is None:
            raise NotFoundError("memory item not found")
        return {
            "schema_version": 1,
            "memory_id": row["memory_id"],
            "tenant_id": row["tenant_id"],
            "project_id": row["project_id"],
            "subject_user_id": row["subject_user_id"],
            "memory_type": row["memory_type"],
            "status": row["status"],
            "access_level": row["access_level"],
            "summary": row["summary"],
            "facts": json.loads(row["facts_json"]),
            "decisions": json.loads(row["decisions_json"]),
            "rejected_ideas": json.loads(row["rejected_ideas_json"]),
            "tags": json.loads(row["tags_json"]),
            "source_refs": json.loads(row["source_refs_json"]),
            "evidence_refs": json.loads(row["evidence_refs_json"]),
            "confidence": row["confidence"],
            "freshness": {
                "state": row["freshness_state"],
                "reason": row["freshness_reason"],
            },
            "consent_state": row["consent_state"],
            "contains_personal_data": bool(row["contains_personal_data"]),
            "raw_blob_ref": row["raw_blob_ref"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
            "next_review_at": row["next_review_at"],
            "retention_until": row["retention_until"],
            "reviewed_by": row["reviewed_by"],
        }


def _read_payload(path: str) -> dict[str, Any]:
    if path == "-":
        raw = sys.stdin.read()
    else:
        raw = Path(path).read_text(encoding="utf-8")
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValidationError("payload JSON must be an object")
    return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        default="runtime/agent-control/second-brain.local.json",
        help="Local-only JSON config path.",
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init")

    intake = sub.add_parser("intake")
    intake.add_argument("--input", required=True, help="JSON file or - for stdin")
    intake.add_argument("--actor", required=True)

    review = sub.add_parser("review")
    review.add_argument("--tenant", required=True)
    review.add_argument("--project", required=True)
    review.add_argument("--memory-id", required=True)
    review.add_argument("--decision", choices=["approve", "reject"], required=True)
    review.add_argument("--reviewer", required=True)
    review.add_argument("--reason", required=True)

    retrieve = sub.add_parser("retrieve")
    retrieve.add_argument("--tenant", required=True)
    retrieve.add_argument("--project", required=True)
    retrieve.add_argument("--query", required=True)
    retrieve.add_argument("--principal", required=True)
    retrieve.add_argument("--role", choices=sorted(PRINCIPAL_ROLES), default="project_member")
    retrieve.add_argument("--limit", type=int, default=10)

    forget = sub.add_parser("forget")
    forget.add_argument("--tenant", required=True)
    forget.add_argument("--project", required=True)
    forget.add_argument("--memory-id", required=True)
    forget.add_argument("--requester", required=True)
    forget.add_argument("--reason", required=True)

    promote = sub.add_parser("promote-pattern")
    promote.add_argument("--tenant", required=True)
    promote.add_argument("--project", required=True)
    promote.add_argument("--memory-id", required=True)
    promote.add_argument("--summary", required=True)
    promote.add_argument("--reviewer", required=True)
    promote.add_argument("--consent-ref", required=True)

    maintenance = sub.add_parser("maintenance")
    maintenance.add_argument("--tenant", required=True)
    maintenance.add_argument("--project", required=True)
    maintenance.add_argument("--actor", required=True)
    maintenance.add_argument("--now")
    maintenance.add_argument("--apply", action="store_true")

    stats = sub.add_parser("stats")
    stats.add_argument("--tenant", required=True)
    stats.add_argument("--project", required=True)
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        store = ProjectMemoryStore(load_config(Path(args.config)))
        if args.command == "init":
            result = store.initialize()
        elif args.command == "intake":
            result = store.intake(_read_payload(args.input), actor_id=args.actor)
        elif args.command == "review":
            result = store.review(
                tenant_id=args.tenant,
                project_id=args.project,
                memory_id=args.memory_id,
                decision=args.decision,
                reviewer=args.reviewer,
                reason=args.reason,
            )
        elif args.command == "retrieve":
            result = store.retrieve(
                tenant_id=args.tenant,
                project_id=args.project,
                query=args.query,
                principal_id=args.principal,
                principal_role=args.role,
                limit=args.limit,
            )
        elif args.command == "forget":
            result = store.forget(
                tenant_id=args.tenant,
                project_id=args.project,
                memory_id=args.memory_id,
                requester=args.requester,
                reason=args.reason,
            )
        elif args.command == "promote-pattern":
            result = store.promote_pattern(
                source_tenant_id=args.tenant,
                source_project_id=args.project,
                memory_id=args.memory_id,
                deidentified_summary=args.summary,
                reviewer=args.reviewer,
                consent_ref=args.consent_ref,
            )
        elif args.command == "maintenance":
            result = store.maintenance(
                tenant_id=args.tenant,
                project_id=args.project,
                actor_id=args.actor,
                apply=bool(args.apply),
                now=args.now,
            )
        else:
            result = store.stats(tenant_id=args.tenant, project_id=args.project)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (OSError, sqlite3.Error, json.JSONDecodeError, ProjectMemoryError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
