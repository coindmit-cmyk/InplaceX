#!/usr/bin/env python3
"""Typed, append-only runtime execution audit journal.

The journal records lifecycle evidence supplied by existing execution owners.
It never schedules work, grants authority, launches a process, or writes to Git
or Task Manager.  Detailed records remain below a caller-supplied runtime root.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import time
from typing import Any, Iterator


CONTRACT_VERSION = "1.0.0"
DIGEST_PROFILE = "jcs-sha256-v1"
ID_RE = re.compile(r"^[a-z][a-z0-9._:-]{2,127}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
REASON_RE = re.compile(r"^[a-z][a-z0-9._:-]{2,127}$")
SAFE_REF_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/@#-]{0,511}$")

EVENT_KINDS = {
    "recommendation_recorded",
    "route_selected",
    "authorization_recorded",
    "invocation_started",
    "actual_route_recorded",
    "result_recorded",
    "integration_recorded",
    "execution_completed",
    "execution_blocked",
    "execution_cancelled",
    "execution_timed_out",
    "override_recorded",
    "retry_recorded",
    "result_excluded",
    "degradation_recorded",
    "conflict_recorded",
    "additional_run_recommended",
}

TERMINAL_KINDS = {
    "execution_completed",
    "execution_blocked",
    "execution_cancelled",
    "execution_timed_out",
}

REASON_REQUIRED_KINDS = {
    "execution_blocked",
    "execution_cancelled",
    "execution_timed_out",
    "override_recorded",
    "retry_recorded",
    "result_excluded",
    "degradation_recorded",
    "conflict_recorded",
    "additional_run_recommended",
}

STAGE_RANK = {
    "recommendation_recorded": 10,
    "route_selected": 20,
    "authorization_recorded": 30,
    "invocation_started": 40,
    "actual_route_recorded": 50,
    "result_recorded": 60,
    "integration_recorded": 70,
    "execution_completed": 80,
    "execution_blocked": 80,
    "execution_cancelled": 80,
    "execution_timed_out": 80,
}

LINEAGE_FIELDS = {
    "recommendation_id",
    "recommendation_digest",
    "router_decision_id",
    "router_decision_digest",
    "plan_id",
    "plan_digest",
    "work_unit_id",
    "authorization_id",
    "authorization_digest",
    "invocation_id",
    "attempt_id",
    "retry_of_attempt_id",
    "result_id",
    "result_digest",
    "integration_id",
    "integration_digest",
}

STABLE_LINEAGE_FIELDS = {
    "recommendation_id",
    "recommendation_digest",
    "router_decision_id",
    "router_decision_digest",
    "plan_id",
    "plan_digest",
    "work_unit_id",
    "authorization_id",
    "authorization_digest",
    "invocation_id",
}

DIGEST_LINEAGE_FIELDS = {field for field in LINEAGE_FIELDS if field.endswith("_digest")}

REQUIRED_LINEAGE_BY_KIND = {
    "recommendation_recorded": {"recommendation_id", "recommendation_digest"},
    "route_selected": {"router_decision_id", "router_decision_digest"},
    "authorization_recorded": {
        "plan_id",
        "plan_digest",
        "work_unit_id",
        "authorization_id",
        "authorization_digest",
    },
    "invocation_started": {"invocation_id", "attempt_id"},
    "actual_route_recorded": {"invocation_id", "attempt_id"},
    "result_recorded": {"invocation_id", "attempt_id", "result_id", "result_digest"},
    "retry_recorded": {"invocation_id", "attempt_id", "result_id", "result_digest"},
    "result_excluded": {"invocation_id", "attempt_id", "result_id", "result_digest"},
    "integration_recorded": {
        "invocation_id",
        "attempt_id",
        "result_id",
        "result_digest",
        "integration_id",
        "integration_digest",
    },
    "execution_completed": {
        "invocation_id",
        "attempt_id",
        "result_id",
        "result_digest",
        "integration_id",
        "integration_digest",
    },
}

DETAIL_FIELDS = {
    "summary",
    "status",
    "retryable",
    "deterministic",
    "next_attempt_id",
    "conflict_class",
    "residual_risk",
    "selected_route_ref",
    "actual_route_ref",
}

REQUIRED_DETAIL_BY_KIND = {
    "route_selected": {"selected_route_ref"},
    "actual_route_recorded": {"actual_route_ref"},
}

AUTHORITY_FIELDS = {
    "authority_granted",
    "role_permissions_changed",
    "approval_gates_bypassed",
    "worker_ready_changed",
    "merge_authority_granted",
    "release_authority_granted",
    "recurring_automation_changed",
}

SENSITIVE_KEY_RE = re.compile(
    r"(?:^|_)(?:secret|token|password|credential|private_key|api_key|raw_prompt|"
    r"environment_dump|command_output|shell_command|host_path)(?:_|$)",
    re.IGNORECASE,
)
SENSITIVE_VALUE_RE = re.compile(
    r"(?:-----BEGIN [A-Z ]*PRIVATE KEY-----|\bBearer\s+[A-Za-z0-9._~+/-]+=*|"
    r"\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{12,}|\bsk-[A-Za-z0-9_-]{12,}|"
    r"(?:^|[\s(\"'=])/(?!/)[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*|"
    r"(?:^|\s)(?:[A-Za-z]:[\\/]|\\\\)|file://)",
    re.IGNORECASE,
)

MAX_DETAIL_BYTES = 4096
MAX_STRING_LENGTH = 512
MAX_COLLECTION_ITEMS = 32
MAX_NESTING_DEPTH = 4
MAX_EXECUTION_ATTEMPTS = 2
MAX_SUMMARY_REASON_CODES = 16


class AuditJournalError(ValueError):
    """Fail-closed journal error with a stable machine code."""

    def __init__(self, code: str, message: str, **details: Any) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details

    def to_dict(self) -> dict[str, Any]:
        result = {"code": self.code, "message": self.message}
        if self.details:
            result["details"] = self.details
        return result


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def parse_time(value: Any, field: str = "event_time") -> dt.datetime:
    if not isinstance(value, str) or not value:
        raise AuditJournalError("invalid_datetime", f"{field} must be an RFC 3339 timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise AuditJournalError("invalid_datetime", f"{field} must be an RFC 3339 timestamp") from exc
    if parsed.tzinfo is None:
        raise AuditJournalError("timezone_required", f"{field} must include a timezone")
    return parsed.astimezone(dt.timezone.utc)


def validate_id(value: Any, field: str) -> str:
    if not isinstance(value, str) or ID_RE.fullmatch(value) is None:
        raise AuditJournalError("invalid_id", f"{field} must be a stable lowercase id", field=field)
    return value


def validate_digest(value: Any, field: str) -> str:
    if not isinstance(value, str) or DIGEST_RE.fullmatch(value) is None:
        raise AuditJournalError("invalid_digest", f"{field} must be sha256:<64 lowercase hex>", field=field)
    return value


def expected_journal_ref(correlation_id: str) -> str:
    validate_id(correlation_id, "correlation_id")
    return f"runtime://audit/{correlation_id}"


def journal_path(runtime_root: Path, correlation_id: str) -> Path:
    validate_id(correlation_id, "correlation_id")
    root = Path(runtime_root).expanduser().resolve()
    suffix = hashlib.sha256(correlation_id.encode("utf-8")).hexdigest()
    path = (root / "execution-audit" / f"{suffix}.jsonl").resolve()
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise AuditJournalError("runtime_path_escape", "journal path escapes the supplied runtime root") from exc
    return path


def _validate_bounded_value(value: Any, path: str, depth: int = 0) -> None:
    if depth > MAX_NESTING_DEPTH:
        raise AuditJournalError("detail_too_deep", "detail nesting exceeds the accepted depth", path=path)
    if isinstance(value, str):
        if len(value) > MAX_STRING_LENGTH:
            raise AuditJournalError("detail_string_too_long", "detail string exceeds the accepted length", path=path)
        if SENSITIVE_VALUE_RE.search(value):
            raise AuditJournalError("sensitive_value_forbidden", "sensitive or host-local value is forbidden", path=path)
        return
    if value is None or isinstance(value, (bool, int, float)):
        if isinstance(value, float) and (value != value or value in {float("inf"), float("-inf")}):
            raise AuditJournalError("non_finite_number", "non-finite numbers are forbidden", path=path)
        return
    if isinstance(value, list):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise AuditJournalError("detail_collection_too_large", "detail list is too large", path=path)
        for index, item in enumerate(value):
            _validate_bounded_value(item, f"{path}[{index}]", depth + 1)
        return
    if isinstance(value, dict):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise AuditJournalError("detail_collection_too_large", "detail object is too large", path=path)
        for key, item in value.items():
            if not isinstance(key, str) or SENSITIVE_KEY_RE.search(key):
                raise AuditJournalError("sensitive_field_forbidden", "sensitive detail field is forbidden", path=f"{path}.{key}")
            _validate_bounded_value(item, f"{path}.{key}", depth + 1)
        return
    raise AuditJournalError("unsupported_detail_type", "detail contains a non-JSON value", path=path)


def _validate_producer(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        raise AuditJournalError("producer_not_object", "producer must be an object")
    required = {"role", "component"}
    allowed = required | {"project_id", "stage"}
    missing = sorted(required - set(value))
    unknown = sorted(set(value) - allowed)
    if missing:
        raise AuditJournalError("producer_field_missing", "producer is missing required fields", fields=missing)
    if unknown:
        raise AuditJournalError("producer_field_unknown", "producer contains unsupported fields", fields=unknown)
    result: dict[str, str] = {}
    for field, item in value.items():
        result[field] = validate_id(item, f"producer.{field}")
    return result


def _validate_lineage(value: Any) -> dict[str, str]:
    if not isinstance(value, dict) or not value:
        raise AuditJournalError("lineage_not_object", "lineage must be a non-empty object")
    unknown = sorted(set(value) - LINEAGE_FIELDS)
    if unknown:
        raise AuditJournalError("lineage_field_unknown", "lineage contains unsupported fields", fields=unknown)
    result: dict[str, str] = {}
    for field, item in value.items():
        if field in DIGEST_LINEAGE_FIELDS:
            result[field] = validate_digest(item, f"lineage.{field}")
        else:
            result[field] = validate_id(item, f"lineage.{field}")
    return result


def _validate_authority_effect(value: Any) -> dict[str, bool]:
    if not isinstance(value, dict) or set(value) != AUTHORITY_FIELDS:
        raise AuditJournalError(
            "authority_effect_invalid",
            "authority_effect must contain the complete non-authoritative guard",
        )
    if any(item is not False for item in value.values()):
        raise AuditJournalError("authority_escalation_forbidden", "audit evidence cannot grant authority")
    return {field: False for field in sorted(AUTHORITY_FIELDS)}


def _validate_evidence_refs(value: Any) -> list[str]:
    if not isinstance(value, list) or len(value) > MAX_COLLECTION_ITEMS:
        raise AuditJournalError("evidence_refs_invalid", "evidence_refs must be a bounded list")
    result: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or SAFE_REF_RE.fullmatch(item) is None:
            raise AuditJournalError("evidence_ref_invalid", "evidence ref is invalid", index=index)
        if SENSITIVE_VALUE_RE.search(item):
            raise AuditJournalError("sensitive_value_forbidden", "host-local evidence ref is forbidden", index=index)
        if item in result:
            raise AuditJournalError("evidence_ref_duplicate", "evidence_refs must not contain duplicates", index=index)
        result.append(item)
    return result


def _validate_detail(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) - DETAIL_FIELDS:
        raise AuditJournalError("detail_field_unknown", "detail contains unsupported fields")
    string_limits = {
        "summary": MAX_STRING_LENGTH,
        "status": 128,
        "conflict_class": 128,
        "residual_risk": MAX_STRING_LENGTH,
    }
    for field, limit in string_limits.items():
        if field not in value:
            continue
        item = value[field]
        if not isinstance(item, str):
            raise AuditJournalError("detail_type_invalid", f"detail.{field} must be a string", field=field)
        if len(item) > limit:
            raise AuditJournalError("detail_string_too_long", f"detail.{field} exceeds {limit} characters", field=field)
    for field in ("retryable", "deterministic"):
        if field in value and not isinstance(value[field], bool):
            raise AuditJournalError("detail_type_invalid", f"detail.{field} must be a boolean", field=field)
    if "next_attempt_id" in value:
        validate_id(value["next_attempt_id"], "detail.next_attempt_id")
    for field in ("selected_route_ref", "actual_route_ref"):
        if field not in value:
            continue
        item = value[field]
        if not isinstance(item, str) or SAFE_REF_RE.fullmatch(item) is None:
            raise AuditJournalError("detail_ref_invalid", f"detail.{field} must be a bounded logical reference", field=field)
    _validate_bounded_value(value, "detail")
    if len(canonical_json_bytes(value)) > MAX_DETAIL_BYTES:
        raise AuditJournalError("detail_too_large", "detail exceeds the accepted byte size")
    return value


def event_projection(event: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in event.items() if key not in {"sequence", "previous_event_digest", "event_digest"}}


def digest_projection(event: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in event.items() if key != "event_digest"}


def validate_event(event: Any, *, stored: bool) -> dict[str, Any]:
    if not isinstance(event, dict):
        raise AuditJournalError("event_not_object", "event must be an object")
    required = {
        "contract_version",
        "event_id",
        "event_time",
        "producer",
        "event_kind",
        "correlation_id",
        "journal_ref",
        "lineage",
        "reason_code",
        "detail",
        "evidence_refs",
        "authority_effect",
        "digest_profile",
    }
    integrity = {"sequence", "previous_event_digest", "event_digest"}
    allowed = required | integrity
    missing = sorted(required - set(event))
    unknown = sorted(set(event) - allowed)
    if missing:
        raise AuditJournalError("event_field_missing", "event is missing required fields", fields=missing)
    if unknown:
        raise AuditJournalError("event_field_unknown", "event contains unsupported fields", fields=unknown)
    if stored and not integrity <= set(event):
        raise AuditJournalError("integrity_field_missing", "stored event is missing integrity fields")
    if not stored and integrity & set(event):
        raise AuditJournalError("caller_integrity_forbidden", "sequence and digests are assigned by the journal")
    if event.get("contract_version") != CONTRACT_VERSION:
        raise AuditJournalError("contract_version_unsupported", "unsupported audit event contract version")
    if event.get("digest_profile") != DIGEST_PROFILE:
        raise AuditJournalError("digest_profile_unsupported", "unsupported digest profile")
    event_id = validate_id(event.get("event_id"), "event_id")
    correlation_id = validate_id(event.get("correlation_id"), "correlation_id")
    parse_time(event.get("event_time"))
    kind = event.get("event_kind")
    if kind not in EVENT_KINDS:
        raise AuditJournalError("event_kind_unknown", "event_kind is not supported", event_kind=kind)
    journal_ref = event.get("journal_ref")
    if journal_ref != expected_journal_ref(correlation_id):
        raise AuditJournalError("journal_ref_mismatch", "journal_ref does not match correlation_id")
    producer = _validate_producer(event.get("producer"))
    lineage = _validate_lineage(event.get("lineage"))
    required_lineage = REQUIRED_LINEAGE_BY_KIND.get(kind, set())
    missing_lineage = sorted(required_lineage - set(lineage))
    if missing_lineage:
        raise AuditJournalError(
            "lineage_field_missing",
            "event lineage is incomplete for event_kind",
            fields=missing_lineage,
        )
    reason = event.get("reason_code")
    if reason is not None and (not isinstance(reason, str) or REASON_RE.fullmatch(reason) is None):
        raise AuditJournalError("reason_code_invalid", "reason_code must be null or a stable lowercase code")
    if kind in REASON_REQUIRED_KINDS and reason is None:
        raise AuditJournalError("reason_code_required", "event_kind requires a reason_code")
    detail = _validate_detail(event.get("detail"))
    missing_detail = sorted(REQUIRED_DETAIL_BY_KIND.get(kind, set()) - set(detail))
    if missing_detail:
        raise AuditJournalError(
            "detail_field_missing",
            "event detail is incomplete for event_kind",
            fields=missing_detail,
        )
    evidence_refs = _validate_evidence_refs(event.get("evidence_refs"))
    authority_effect = _validate_authority_effect(event.get("authority_effect"))
    normalized: dict[str, Any] = {
        "contract_version": CONTRACT_VERSION,
        "event_id": event_id,
        "event_time": event["event_time"],
        "producer": producer,
        "event_kind": kind,
        "correlation_id": correlation_id,
        "journal_ref": journal_ref,
        "lineage": lineage,
        "reason_code": reason,
        "detail": detail,
        "evidence_refs": evidence_refs,
        "authority_effect": authority_effect,
        "digest_profile": DIGEST_PROFILE,
    }
    if stored:
        sequence = event.get("sequence")
        if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 1:
            raise AuditJournalError("sequence_invalid", "sequence must be an integer >= 1")
        previous = event.get("previous_event_digest")
        if previous is not None:
            validate_digest(previous, "previous_event_digest")
        digest = validate_digest(event.get("event_digest"), "event_digest")
        normalized.update(
            {
                "sequence": sequence,
                "previous_event_digest": previous,
                "event_digest": digest,
            }
        )
    return normalized


def _new_state(correlation_id: str) -> dict[str, Any]:
    return {
        "correlation_id": correlation_id,
        "stable_lineage": {},
        "last_rank": 0,
        "terminal_kind": None,
        "started_attempts": set(),
        "actual_attempts": set(),
        "result_by_attempt": {},
        "retry_next_by_attempt": {},
        "consumed_retry_attempts": set(),
        "last_result_attempt": None,
        "integration_lineage": None,
    }


def _apply_semantics(event: dict[str, Any], state: dict[str, Any]) -> None:
    kind = event["event_kind"]
    lineage = event["lineage"]
    if event["correlation_id"] != state["correlation_id"]:
        raise AuditJournalError("correlation_drift", "event correlation differs from the journal")
    terminal_kind = state["terminal_kind"]
    if terminal_kind is not None:
        raise AuditJournalError(
            "terminal_reopen",
            "no event may be appended after a terminal execution event",
            terminal_kind=terminal_kind,
        )
    for field in STABLE_LINEAGE_FIELDS:
        if field in state["stable_lineage"]:
            if field not in lineage:
                raise AuditJournalError("lineage_field_dropped", "established lineage field was omitted", field=field)
            if lineage[field] != state["stable_lineage"][field]:
                raise AuditJournalError("lineage_drift", "established lineage field changed", field=field)
        elif field in lineage:
            state["stable_lineage"][field] = lineage[field]
    rank = STAGE_RANK.get(kind)
    if rank is not None and rank < state["last_rank"]:
        retry_of = lineage.get("retry_of_attempt_id")
        retry_start = (
            kind == "invocation_started"
            and retry_of in state["retry_next_by_attempt"]
            and state["retry_next_by_attempt"][retry_of] == lineage.get("attempt_id")
            and retry_of not in state["consumed_retry_attempts"]
        )
        if not retry_start:
            raise AuditJournalError("lifecycle_regression", "event regresses the established lifecycle")
    attempt_id = lineage.get("attempt_id")
    if kind == "invocation_started":
        if attempt_id in state["started_attempts"]:
            raise AuditJournalError("attempt_already_started", "attempt was already started", attempt_id=attempt_id)
        if len(state["started_attempts"]) >= MAX_EXECUTION_ATTEMPTS:
            raise AuditJournalError(
                "retry_limit_exceeded",
                "execution contract permits one initial attempt and one technical retry",
            )
        retry_of = lineage.get("retry_of_attempt_id")
        if state["started_attempts"]:
            expected_attempt = state["retry_next_by_attempt"].get(retry_of)
            if retry_of in state["consumed_retry_attempts"]:
                raise AuditJournalError("retry_evidence_consumed", "retry evidence already started its next attempt")
            if expected_attempt is None:
                raise AuditJournalError("retry_evidence_missing", "a later attempt requires a prior retry_recorded event")
            if attempt_id != expected_attempt:
                raise AuditJournalError(
                    "retry_attempt_mismatch",
                    "started attempt does not match retry detail.next_attempt_id",
                    expected_attempt_id=expected_attempt,
                )
            state["consumed_retry_attempts"].add(retry_of)
        elif retry_of is not None:
            raise AuditJournalError("unexpected_retry_lineage", "the first attempt cannot link to retry evidence")
        state["started_attempts"].add(attempt_id)
        state["last_rank"] = STAGE_RANK[kind]
    elif kind == "actual_route_recorded":
        if attempt_id not in state["started_attempts"]:
            raise AuditJournalError("actual_before_start", "actual-use evidence requires a started attempt")
        if attempt_id in state["actual_attempts"]:
            raise AuditJournalError("actual_already_recorded", "attempt already has actual-use evidence")
        state["actual_attempts"].add(attempt_id)
        state["last_rank"] = max(state["last_rank"], STAGE_RANK[kind])
    elif kind == "result_recorded":
        if attempt_id not in state["started_attempts"]:
            raise AuditJournalError("result_before_start", "result evidence requires a started attempt")
        result_identity = (lineage["result_id"], lineage["result_digest"])
        prior_result = state["result_by_attempt"].get(attempt_id)
        if prior_result is not None:
            code = "result_lineage_drift" if prior_result != result_identity else "result_already_recorded"
            raise AuditJournalError(code, "attempt already has immutable result evidence")
        state["result_by_attempt"][attempt_id] = result_identity
        state["last_result_attempt"] = attempt_id
        state["last_rank"] = max(state["last_rank"], STAGE_RANK[kind])
    elif kind == "retry_recorded":
        if attempt_id not in state["result_by_attempt"]:
            raise AuditJournalError("retry_before_result", "retry evidence requires a recorded result")
        if (lineage["result_id"], lineage["result_digest"]) != state["result_by_attempt"][attempt_id]:
            raise AuditJournalError("retry_result_mismatch", "retry references a different result")
        if len(state["started_attempts"]) >= MAX_EXECUTION_ATTEMPTS:
            raise AuditJournalError("retry_limit_exceeded", "execution contract permits at most one technical retry")
        next_attempt_id = event["detail"].get("next_attempt_id")
        if not isinstance(next_attempt_id, str):
            raise AuditJournalError("next_attempt_required", "retry_recorded requires detail.next_attempt_id")
        validate_id(next_attempt_id, "detail.next_attempt_id")
        if next_attempt_id in state["started_attempts"]:
            raise AuditJournalError("next_attempt_reused", "retry next attempt id was already used")
        if attempt_id in state["retry_next_by_attempt"]:
            raise AuditJournalError("retry_already_recorded", "attempt already has retry evidence")
        state["retry_next_by_attempt"][attempt_id] = next_attempt_id
    elif kind == "result_excluded":
        expected_result = state["result_by_attempt"].get(attempt_id)
        if expected_result is None:
            raise AuditJournalError("exclusion_before_result", "result exclusion requires recorded result evidence")
        if (lineage["result_id"], lineage["result_digest"]) != expected_result:
            raise AuditJournalError("exclusion_result_mismatch", "exclusion references a different result")
    elif kind == "integration_recorded":
        if attempt_id not in state["result_by_attempt"]:
            raise AuditJournalError("integration_before_result", "integration evidence requires a recorded result")
        expected_result = state["result_by_attempt"][attempt_id]
        if (lineage["result_id"], lineage["result_digest"]) != expected_result:
            raise AuditJournalError("integration_result_mismatch", "integration references a different result")
        if state["integration_lineage"] is not None:
            raise AuditJournalError("integration_already_recorded", "execution already has integration evidence")
        state["integration_lineage"] = {
            field: lineage[field]
            for field in (
                "invocation_id",
                "attempt_id",
                "result_id",
                "result_digest",
                "integration_id",
                "integration_digest",
            )
        }
        state["last_rank"] = max(state["last_rank"], STAGE_RANK[kind])
    elif kind == "execution_completed":
        integrated = state["integration_lineage"]
        if integrated is None:
            raise AuditJournalError("completion_before_integration", "completed execution requires integration evidence")
        for field, expected in integrated.items():
            if lineage.get(field) != expected:
                raise AuditJournalError(
                    "completion_integration_mismatch",
                    "completion must preserve the integrated result lineage",
                    field=field,
                )
        state["last_rank"] = STAGE_RANK[kind]
    elif rank is not None:
        state["last_rank"] = max(state["last_rank"], rank)
    if kind in TERMINAL_KINDS:
        state["terminal_kind"] = kind


def _read_records(path: Path) -> list[Any]:
    if not path.exists():
        return []
    records: list[Any] = []
    try:
        with path.open("r", encoding="utf-8") as handle:
            for line_number, raw in enumerate(handle, start=1):
                line = raw.strip()
                if not line:
                    raise AuditJournalError("blank_journal_line", "journal contains a blank line", line=line_number)
                try:
                    records.append(json.loads(line))
                except json.JSONDecodeError as exc:
                    raise AuditJournalError("malformed_jsonl", "journal contains malformed JSON", line=line_number) from exc
    except UnicodeDecodeError as exc:
        raise AuditJournalError("malformed_utf8", "journal is not valid UTF-8") from exc
    return records


def verify_records(records: list[Any], correlation_id: str) -> dict[str, Any]:
    validate_id(correlation_id, "correlation_id")
    state = _new_state(correlation_id)
    event_ids: set[str] = set()
    previous_digest: str | None = None
    normalized_events: list[dict[str, Any]] = []
    for index, raw in enumerate(records, start=1):
        event = validate_event(raw, stored=True)
        if event["sequence"] != index:
            raise AuditJournalError(
                "sequence_gap",
                "journal sequence is not contiguous",
                expected=index,
                actual=event["sequence"],
            )
        if event["previous_event_digest"] != previous_digest:
            raise AuditJournalError("previous_digest_mismatch", "journal digest chain is broken", sequence=index)
        expected_digest = canonical_digest(digest_projection(event))
        if event["event_digest"] != expected_digest:
            raise AuditJournalError("event_digest_mismatch", "event digest does not match canonical content", sequence=index)
        if event["event_id"] in event_ids:
            raise AuditJournalError("duplicate_event_id", "journal contains a duplicate event id", event_id=event["event_id"])
        _apply_semantics(event, state)
        event_ids.add(event["event_id"])
        previous_digest = event["event_digest"]
        normalized_events.append(event)
    return {
        "valid": True,
        "correlation_id": correlation_id,
        "journal_ref": expected_journal_ref(correlation_id),
        "event_count": len(normalized_events),
        "last_event_digest": previous_digest,
        "terminal_kind": state["terminal_kind"],
        "events": normalized_events,
    }


def verify_journal(runtime_root: Path, correlation_id: str) -> dict[str, Any]:
    path = journal_path(runtime_root, correlation_id)
    try:
        if not path.exists():
            raise AuditJournalError("journal_not_found", "execution audit journal does not exist")
        records = _read_records(path)
        if not records:
            raise AuditJournalError("journal_empty", "execution audit journal contains no events")
        return verify_records(records, correlation_id)
    except (AuditJournalError, OSError) as exc:
        if isinstance(exc, AuditJournalError):
            error = exc.to_dict()
        else:
            error = {"code": "journal_io_error", "message": str(exc)}
        return {
            "valid": False,
            "correlation_id": correlation_id,
            "journal_ref": expected_journal_ref(correlation_id),
            "event_count": 0,
            "error": error,
        }


@contextmanager
def journal_lock(path: Path, timeout_seconds: float = 5.0) -> Iterator[None]:
    lock_path = path.with_suffix(path.suffix + ".lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + max(0.0, timeout_seconds)
    fd: int | None = None
    while fd is None:
        try:
            fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError as exc:
            if time.monotonic() >= deadline:
                raise AuditJournalError("journal_lock_timeout", "journal append lock was not available") from exc
            time.sleep(0.05)
            continue
        try:
            os.write(fd, str(os.getpid()).encode("ascii"))
            os.fsync(fd)
        except OSError:
            os.close(fd)
            fd = None
            try:
                lock_path.unlink()
            except FileNotFoundError:
                pass
            raise
    try:
        yield
    finally:
        if fd is not None:
            os.close(fd)
        try:
            lock_path.unlink()
        except FileNotFoundError:
            pass


def append_event(
    runtime_root: Path,
    event: Any,
    *,
    lock_timeout_seconds: float = 5.0,
) -> dict[str, Any]:
    candidate = validate_event(event, stored=False)
    path = journal_path(runtime_root, candidate["correlation_id"])
    path.parent.mkdir(parents=True, exist_ok=True)
    with journal_lock(path, timeout_seconds=lock_timeout_seconds):
        records = _read_records(path)
        verified = verify_records(records, candidate["correlation_id"])
        for existing in verified["events"]:
            if existing["event_id"] != candidate["event_id"]:
                continue
            if event_projection(existing) == candidate:
                return {
                    "appended": False,
                    "idempotent": True,
                    "event": existing,
                    "journal_ref": candidate["journal_ref"],
                    "journal_path": str(path),
                }
            raise AuditJournalError(
                "divergent_event_reuse",
                "event_id already exists with different canonical content",
                event_id=candidate["event_id"],
            )
        state = _new_state(candidate["correlation_id"])
        for existing in verified["events"]:
            _apply_semantics(existing, state)
        _apply_semantics(candidate, state)
        stored = dict(candidate)
        stored["sequence"] = len(records) + 1
        stored["previous_event_digest"] = verified["last_event_digest"]
        stored["event_digest"] = canonical_digest(digest_projection(stored))
        line = canonical_json_bytes(stored) + b"\n"
        with path.open("ab") as handle:
            handle.write(line)
            handle.flush()
            os.fsync(handle.fileno())
        return {
            "appended": True,
            "idempotent": False,
            "event": stored,
            "journal_ref": candidate["journal_ref"],
            "journal_path": str(path),
        }


def summarize_journal(runtime_root: Path, correlation_id: str) -> dict[str, Any]:
    report = verify_journal(runtime_root, correlation_id)
    if not report["valid"]:
        return report
    events = report.pop("events")
    all_reasons = sorted({event["reason_code"] for event in events if event["reason_code"]})
    reasons = all_reasons[:MAX_SUMMARY_REASON_CODES]
    kind_counts = {kind: 0 for kind in sorted(EVENT_KINDS)}
    for event in events:
        kind_counts[event["event_kind"]] += 1
    kind_counts = {kind: count for kind, count in kind_counts.items() if count}
    return {
        "valid": True,
        "correlation_id": correlation_id,
        "journal_ref": report["journal_ref"],
        "event_count": len(events),
        "first_event_time": events[0]["event_time"] if events else None,
        "last_event_time": events[-1]["event_time"] if events else None,
        "last_event_kind": events[-1]["event_kind"] if events else None,
        "terminal_kind": report["terminal_kind"],
        "reason_codes": reasons,
        "reason_code_count": len(all_reasons),
        "reason_codes_truncated": len(all_reasons) > len(reasons),
        "event_kind_counts": kind_counts,
        "last_event_digest": report["last_event_digest"],
        "authority_granted": False,
    }


def _print(report: dict[str, Any], as_json: bool) -> None:
    if as_json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(report)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    for action in ("append", "verify", "summary"):
        sub = subparsers.add_parser(action)
        sub.add_argument("--runtime-root", required=True)
        sub.add_argument("--json", action="store_true")
        if action == "append":
            sub.add_argument("--event", required=True, help="Path to one event JSON object")
            sub.add_argument("--lock-timeout-seconds", type=float, default=5.0)
        else:
            sub.add_argument("--correlation-id", required=True)
    args = parser.parse_args()
    try:
        if args.action == "append":
            payload = json.loads(Path(args.event).read_text(encoding="utf-8"))
            report = append_event(
                Path(args.runtime_root),
                payload,
                lock_timeout_seconds=args.lock_timeout_seconds,
            )
        elif args.action == "verify":
            report = verify_journal(Path(args.runtime_root), args.correlation_id)
        else:
            report = summarize_journal(Path(args.runtime_root), args.correlation_id)
    except (AuditJournalError, json.JSONDecodeError, OSError) as exc:
        if isinstance(exc, AuditJournalError):
            error = exc.to_dict()
        else:
            error = {"code": "journal_io_error", "message": str(exc)}
        report = {"valid": False, "error": error}
        _print(report, args.json)
        return 2
    _print(report, args.json)
    if args.action in {"verify", "summary"} and not report.get("valid"):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
