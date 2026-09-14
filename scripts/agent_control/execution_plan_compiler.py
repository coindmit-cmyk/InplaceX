#!/usr/bin/env python3
"""Deterministically compile read-only analytical proposals into Parallel Work.

The compiler is deliberately authority-free. It reuses the existing Router's
canonical digest projection and the existing execution-contract validator, but
does not select a route, inspect runtime state, issue authorization, acquire a
lease, or launch work. Every value that can affect the result is explicit.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import fnmatch
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from execution_contract_validator import validate as validate_execution_contract
from model_resource_router import (
    canonical_digest as router_canonical_digest,
    compute_input_scope_digest_v1,
    compute_plan_content_digest_v1,
    plan_content_projection_v1,
    SKILL_BINDING_FIELDS,
    SKILL_VERSION_RE,
)


COMPILER_ID = "execution_plan_compiler"
COMPILER_VERSION = "1.0.0"
CONTRACT_VERSION = "1.0.0"
DIGEST_PROFILE = "jcs-sha256-v1"
EXPECTED_RESULT_SCHEMA = (
    "https://schemas.aistudio.local/agent-control/"
    "subagent_result_envelope.schema.json"
)

ID_RE = re.compile(r"^[a-z][a-z0-9._:-]{2,127}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
WILDCARD_RE = re.compile(r"[*?\[\]]")
WINDOWS_DRIVE_PREFIX_RE = re.compile(r"^[A-Za-z]:")
MAX_READ_REFS_PER_UNIT = 128
MAX_READ_REF_LENGTH = 2048
MAX_EXCLUSIVE_KEYS_PER_UNIT = 64
MAX_EXCLUSIVE_KEY_LENGTH = 256
MAX_ACCEPTANCE_CRITERIA_PER_UNIT = 64
MAX_DEPENDENCIES = 512
SENSITIVE_READ_SEGMENTS = {
    ".git",
    ".gnupg",
    ".ssh",
    "credentials",
    "secrets",
}
PATTERN_PROTECTED_SEGMENTS = SENSITIVE_READ_SEGMENTS | {".env"}
DEPENDENCY_TYPES = {
    "success",
    "completion",
    "artifact_ready",
    "approval",
    "integration_accepted",
}
CAPABILITY_PROFILES = {
    "efficient",
    "balanced",
    "deep",
    "maximum_coherent",
    "delegated_deep",
}
RISK_VALUES = {"low", "medium", "high", "critical"}
AUTHORITY_GUARD_FIELDS = (
    "authority_granted",
    "role_permissions_changed",
    "approval_gates_bypassed",
    "worker_ready_changed",
    "merge_authority_granted",
    "release_authority_granted",
    "recurring_automation_changed",
)
AUTHORITY_GUARD = {field: False for field in AUTHORITY_GUARD_FIELDS}

PROPOSAL_KEYS = {
    "contract_version",
    "contract_kind",
    "proposal_id",
    "correlation_id",
    "producer",
    "created_at",
    "revision",
    "supersedes",
    "digest_profile",
    "authority_guard",
    "audit",
    "plan_id",
    "risk",
    "requested_lanes",
    "max_attempts_per_unit",
    "child_depth",
    "work_units",
    "dependencies",
    "barriers",
    "cancellation_grace_seconds",
    "revision_change",
}
PRODUCER_KEYS = {"role", "stage", "project_id"}
AUDIT_KEYS = {"journal_ref"}
WORK_UNIT_KEYS = {
    "work_unit_id",
    "id",
    "skill_bindings",
    "order",
    "criticality",
    "objective",
    "acceptance_criteria",
    "target_role",
    "target_stage",
    "capability_profile",
    "access_mode",
    "mutation_allowed",
    "external_mutation",
    "spawn_children",
    "inherit_parent_authority",
    "write_paths",
    "resources",
    "expected_result_schema",
    "timeout_seconds",
    "depends_on",
}
RESOURCE_KEYS = {"read_refs", "exclusive_keys"}
DEPENDENCY_KEYS = {"from", "to", "type"}
BARRIER_KEYS = {
    "barrier_id",
    "id",
    "members",
    "required_members",
    "release_condition",
    "bypass_allowed",
}
REVISION_CHANGE_KEYS = {
    "progress_denominator_changed",
    "reason",
    "approved_before_execution",
}


def _normalize_skill_bindings(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> list[dict[str, Any]]:
    """Normalize exact ordered bindings while keeping legacy empty lanes valid."""

    if value is None:
        return []
    if not isinstance(value, list):
        _add_issue(issues, "plan_skill_bindings_invalid", path, "skill_bindings must be an array")
        return []
    if len(value) > 32:
        _add_issue(issues, "plan_skill_bindings_limit_exceeded", path, "a work unit cannot bind more than 32 skills")
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(value[:32]):
        item_path = f"{path}[{index}]"
        if not isinstance(raw, dict) or set(raw) != set(SKILL_BINDING_FIELDS):
            _add_issue(
                issues,
                "plan_skill_binding_shape_invalid",
                item_path,
                "each binding must contain exactly skill id, version, bundle, decision, snapshot and load-order fields",
            )
            continue
        skill_id = raw.get("skill_id")
        decision_id = raw.get("selection_decision_id")
        if not isinstance(skill_id, str) or ID_RE.fullmatch(skill_id) is None:
            _add_issue(issues, "plan_skill_binding_id_invalid", f"{item_path}.skill_id", "skill_id must be a canonical contract id")
        if not isinstance(decision_id, str) or ID_RE.fullmatch(decision_id) is None:
            _add_issue(issues, "plan_skill_binding_decision_invalid", f"{item_path}.selection_decision_id", "selection_decision_id must be a canonical contract id")
        version = raw.get("version")
        if not isinstance(version, str) or SKILL_VERSION_RE.fullmatch(version) is None:
            _add_issue(issues, "plan_skill_binding_version_invalid", f"{item_path}.version", "version must be a semantic version")
        for field in ("bundle_digest", "selection_decision_digest", "registry_snapshot_digest"):
            if not isinstance(raw.get(field), str) or DIGEST_RE.fullmatch(raw[field]) is None:
                _add_issue(issues, "plan_skill_binding_digest_invalid", f"{item_path}.{field}", "binding digests must use sha256:<64 lowercase hex>")
        load_order = raw.get("load_order")
        if not isinstance(load_order, int) or isinstance(load_order, bool) or load_order < 0:
            _add_issue(issues, "plan_skill_binding_order_invalid", f"{item_path}.load_order", "load_order must be an integer >= 0")
        normalized.append(dict(raw))
    orders = [item.get("load_order") for item in normalized]
    if len(orders) != len(set(orders)) or sorted(orders) != list(range(len(orders))):
        _add_issue(issues, "plan_skill_binding_order_not_contiguous", path, "load_order must be unique and contiguous from zero")
    normalized.sort(key=lambda item: (item.get("load_order", 0), item.get("skill_id", "")))
    return normalized

# These fields have meaning only when emitted by the Router or launcher.  A
# producer cannot smuggle them into a role-authored proposal at any depth.
ROUTE_FIELDS = {
    "model",
    "model_id",
    "model_candidates",
    "selected_model",
    "reasoning_effort",
    "selected_route",
    "selected_route_digest",
    "router_authorization",
    "authorization_id",
    "grant_digest",
}
INHERITED_AUTHORITY_FIELDS = {
    "credentials",
    "credential_refs",
    "inherited_authority",
    "inherit_permissions",
    "parent_permissions",
    "approval_token",
    "merge_approval",
    "release_approval",
    "worker_ready",
    "lease_id",
    "lock_id",
}


def canonical_digest(value: Any) -> str:
    """Use the existing central Router canonical digest boundary."""

    _assert_finite_json(value)
    return router_canonical_digest(value)


def _assert_finite_json(value: Any) -> None:
    """Reject values which Python can serialize but canonical JSON cannot."""

    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("non-finite JSON number")
    if isinstance(value, Mapping):
        for key, child in value.items():
            if not isinstance(key, str):
                raise TypeError("JSON object keys must be strings")
            _assert_finite_json(child)
    elif isinstance(value, (list, tuple)):
        for child in value:
            _assert_finite_json(child)


def _glob_segment_matches_any(segment: str, protected: set[str]) -> bool:
    if WILDCARD_RE.search(segment) is None:
        return False
    if not any(character.isalnum() or character in "._-" for character in segment):
        return False
    return any(fnmatch.fnmatchcase(name, segment) for name in protected)


def _glob_tokens(pattern: str) -> list[tuple[str, str]]:
    tokens: list[tuple[str, str]] = []
    index = 0
    while index < len(pattern):
        character = pattern[index]
        if character == "*":
            if not tokens or tokens[-1][0] != "star":
                tokens.append(("star", "*"))
            index += 1
        elif character == "?":
            tokens.append(("any", "?"))
            index += 1
        elif character == "[":
            end = pattern.find("]", index + 1)
            if end < 0:
                tokens.append(("literal", character))
                index += 1
            else:
                tokens.append(("class", pattern[index : end + 1]))
                index = end + 1
        else:
            tokens.append(("literal", character))
            index += 1
    return tokens


def _star_closure(states: set[int], tokens: Sequence[tuple[str, str]]) -> set[int]:
    closed = set(states)
    pending = list(states)
    while pending:
        index = pending.pop()
        if index < len(tokens) and tokens[index][0] == "star" and index + 1 not in closed:
            closed.add(index + 1)
            pending.append(index + 1)
    return closed


def _glob_can_match_prefix(pattern: str, prefix: str) -> bool:
    tokens = _glob_tokens(pattern)
    states = _star_closure({0}, tokens)
    for character in prefix:
        advanced: set[int] = set()
        for index in states:
            if index >= len(tokens):
                continue
            kind, value = tokens[index]
            if kind == "star":
                advanced.add(index)
            elif kind == "any":
                advanced.add(index + 1)
            elif kind == "literal" and value == character:
                advanced.add(index + 1)
            elif kind == "class" and fnmatch.fnmatchcase(character, value):
                advanced.add(index + 1)
        states = _star_closure(advanced, tokens)
        if not states:
            return False
    return bool(states)


def _glob_targets_prefix(pattern: str, prefix: str) -> bool:
    if WILDCARD_RE.search(pattern) is None:
        return False
    if not any(character.isalnum() or character in "._-" for character in pattern):
        return False
    if not _glob_can_match_prefix(pattern, prefix):
        return False
    first_wildcard = min(
        (
            position
            for token in "*?["
            if (position := pattern.find(token)) >= 0
        ),
        default=len(pattern),
    )
    if first_wildcard > 0 or pattern[0] in "?[":
        return True
    trimmed = pattern.lstrip("*")
    return bool(trimmed) and any(
        _glob_can_match_prefix(trimmed, prefix[index:])
        for index in range(len(prefix))
    )


def issue(code: str, path: str, message: str) -> dict[str, str]:
    return {"code": code, "path": path, "message": message}


def _add_issue(
    issues: list[dict[str, str]], code: str, path: str, message: str
) -> None:
    candidate = issue(code, path, message)
    if candidate not in issues:
        issues.append(candidate)


def _sorted_issues(values: Iterable[dict[str, str]]) -> list[dict[str, str]]:
    return sorted(values, key=lambda row: (row["path"], row["code"], row["message"]))


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _normalized_id(
    value: Any, path: str, issues: list[dict[str, str]]
) -> str | None:
    if not isinstance(value, str) or not value.strip():
        _add_issue(issues, "plan_id_invalid", path, "id must be a non-empty string")
        return None
    normalized = re.sub(r"[\s/]+", "-", value.strip().lower())
    if not ID_RE.fullmatch(normalized):
        _add_issue(
            issues,
            "plan_id_invalid",
            path,
            "normalized id must match ^[a-z][a-z0-9._:-]{2,127}$",
        )
        return None
    return normalized


def _valid_digest(
    value: Any, path: str, issues: list[dict[str, str]]
) -> str | None:
    if not isinstance(value, str) or not DIGEST_RE.fullmatch(value):
        _add_issue(
            issues,
            "plan_digest_invalid",
            path,
            "digest must use sha256:<64 lowercase hex> format",
        )
        return None
    return value


def _normalized_timestamp(
    value: Any, path: str, issues: list[dict[str, str]]
) -> str | None:
    if not isinstance(value, str):
        _add_issue(issues, "plan_timestamp_invalid", path, "timestamp must be a string")
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        parsed = None
    if parsed is None or parsed.tzinfo is None:
        _add_issue(
            issues,
            "plan_timestamp_invalid",
            path,
            "timestamp must be an ISO-8601 value with an explicit timezone",
        )
        return None
    parsed = parsed.astimezone(dt.timezone.utc)
    timespec = "microseconds" if parsed.microsecond else "seconds"
    return parsed.isoformat(timespec=timespec).replace("+00:00", "Z")


def _reject_unexpected(
    value: Any,
    allowed: set[str],
    path: str,
    issues: list[dict[str, str]],
) -> None:
    if not isinstance(value, Mapping):
        _add_issue(issues, "plan_expected_object", path, "value must be an object")
        return
    unexpected = [key for key in value if not isinstance(key, str) or key not in allowed]
    for key in sorted(unexpected, key=lambda item: (type(item).__name__, repr(item))):
        rendered = key if isinstance(key, str) else repr(key)
        _add_issue(
            issues,
            "plan_unexpected_field",
            f"{path}.{rendered}",
            "field is not a string member of the typed proposal contract",
        )


def _scan_forbidden_fields(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            child_path = f"{path}.{key}"
            if key in ROUTE_FIELDS:
                _add_issue(
                    issues,
                    "plan_route_selection_forbidden",
                    child_path,
                    "role proposals cannot select or bind a concrete route",
                )
            if key in INHERITED_AUTHORITY_FIELDS:
                _add_issue(
                    issues,
                    "plan_inherited_authority_forbidden",
                    child_path,
                    "role proposals cannot inherit control-plane authority",
                )
            _scan_forbidden_fields(child, child_path, issues)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _scan_forbidden_fields(child, f"{path}[{index}]", issues)


def _normalize_string_list(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
    *,
    minimum: int = 0,
) -> list[str]:
    if not isinstance(value, list):
        _add_issue(issues, "plan_expected_array", path, "value must be an array")
        return []
    normalized: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item.strip():
            _add_issue(
                issues,
                "plan_string_invalid",
                f"{path}[{index}]",
                "value must be a non-empty string",
            )
            continue
        normalized.append(item.strip())
    if len(normalized) < minimum:
        _add_issue(
            issues,
            "plan_array_too_short",
            path,
            f"at least {minimum} item(s) are required",
        )
    return sorted(set(normalized))


def _normalize_authority_guard(
    value: Any, path: str, issues: list[dict[str, str]]
) -> dict[str, bool]:
    if not isinstance(value, Mapping):
        _add_issue(
            issues,
            "plan_authority_guard_required",
            path,
            "the complete false authority guard is required",
        )
        return copy.deepcopy(AUTHORITY_GUARD)
    _reject_unexpected(value, set(AUTHORITY_GUARD_FIELDS), path, issues)
    for field in AUTHORITY_GUARD_FIELDS:
        if value.get(field) is not False:
            _add_issue(
                issues,
                "plan_authority_escalation_forbidden",
                f"{path}.{field}",
                "every authority guard field must be false",
            )
    return copy.deepcopy(AUTHORITY_GUARD)


def _normalize_router_decision(
    value: Any, issues: list[dict[str, str]]
) -> dict[str, Any]:
    path = "$.router_decision"
    if not isinstance(value, Mapping):
        _add_issue(issues, "plan_router_decision_invalid", path, "Router decision is required")
        return {}
    if value.get("status") != "selected":
        _add_issue(
            issues,
            "plan_router_decision_not_selected",
            f"{path}.status",
            "only a primary selected Router decision can be compiled",
        )
    if value.get("delegation_authorized") is not True:
        _add_issue(
            issues,
            "plan_router_delegation_forbidden",
            f"{path}.delegation_authorized",
            "Router decision must explicitly allow delegated execution",
        )
    decision_id = value.get("decision_id")
    if not isinstance(decision_id, str) or not ID_RE.fullmatch(decision_id):
        _add_issue(
            issues,
            "plan_router_binding_invalid",
            f"{path}.decision_id",
            "Router decision id must already be canonical",
        )
    decision_digest = _valid_digest(
        value.get("router_decision_digest"),
        f"{path}.router_decision_digest",
        issues,
    )
    selected = value.get("selected")
    if not isinstance(selected, Mapping):
        _add_issue(
            issues,
            "plan_router_selection_missing",
            f"{path}.selected",
            "selected Router route is required",
        )
        return {}
    route_digest = _valid_digest(
        selected.get("selected_route_digest"),
        f"{path}.selected.selected_route_digest",
        issues,
    )
    unsigned_route = dict(selected)
    unsigned_route.pop("selected_route_digest", None)
    try:
        computed_route_digest = canonical_digest(unsigned_route)
    except (TypeError, ValueError):
        computed_route_digest = None
        _add_issue(
            issues,
            "plan_router_decision_not_canonicalizable",
            f"{path}.selected",
            "selected route must contain only finite canonical JSON values",
        )
    if route_digest and computed_route_digest != route_digest:
        _add_issue(
            issues,
            "plan_selected_route_digest_mismatch",
            f"{path}.selected.selected_route_digest",
            "selected route content does not match its Router digest",
        )
    unsigned_decision = dict(value)
    unsigned_decision.pop("router_decision_digest", None)
    try:
        computed_decision_digest = canonical_digest(unsigned_decision)
    except (TypeError, ValueError):
        computed_decision_digest = None
        _add_issue(
            issues,
            "plan_router_decision_not_canonicalizable",
            path,
            "Router decision must contain only finite canonical JSON values",
        )
    if decision_digest and computed_decision_digest != decision_digest:
        _add_issue(
            issues,
            "plan_router_decision_digest_mismatch",
            f"{path}.router_decision_digest",
            "Router decision content does not match its recorded digest",
        )
    delegation = selected.get("delegation")
    if delegation not in {"allowed", "required"}:
        _add_issue(
            issues,
            "plan_router_delegation_forbidden",
            f"{path}.selected.delegation",
            "selected Router route must permit delegation",
        )
    lanes = selected.get("parallel_lanes")
    if not _is_int(lanes) or not 2 <= lanes <= 64:
        _add_issue(
            issues,
            "plan_router_capacity_invalid",
            f"{path}.selected.parallel_lanes",
            "selected Router lanes must be an integer between 2 and 64",
        )
    profile = selected.get("capability_profile")
    if profile not in CAPABILITY_PROFILES:
        _add_issue(
            issues,
            "plan_router_profile_invalid",
            f"{path}.selected.capability_profile",
            "selected capability profile is unsupported by Parallel Work v1",
        )
    return {
        "decision_id": decision_id,
        "decision_digest": decision_digest,
        "selected_route_digest": route_digest,
        "parallel_lanes": lanes,
        "capability_profile": profile,
    }


def _normalize_policy(value: Any, issues: list[dict[str, str]]) -> dict[str, int]:
    path = "$.policy"
    if not isinstance(value, Mapping):
        _add_issue(issues, "plan_policy_invalid", path, "explicit policy ceilings are required")
        return {}
    nested = value.get("capacity_limits")
    capacities = nested if isinstance(nested, Mapping) else value
    result: dict[str, int] = {}
    for name in ("project_policy", "worker_policy", "runtime"):
        ceiling = capacities.get(name)
        if not _is_int(ceiling) or not 1 <= ceiling <= 64:
            _add_issue(
                issues,
                "plan_policy_ceiling_invalid",
                f"{path}.{name}",
                "capacity ceiling must be an integer between 1 and 64",
            )
        else:
            result[name] = ceiling
    optional_defaults = {
        "max_work_units": (32, 32),
        "max_attempts_per_unit": (2, 2),
        "max_timeout_seconds": (86400, 86400),
        "default_timeout_seconds": (1800, 86400),
        "max_child_depth": (1, 1),
    }
    for name, (default, upper) in optional_defaults.items():
        candidate = value.get(name, default)
        if not _is_int(candidate) or not 1 <= candidate <= upper:
            _add_issue(
                issues,
                "plan_policy_ceiling_invalid",
                f"{path}.{name}",
                f"{name} must be an integer between 1 and {upper}",
            )
        else:
            result[name] = candidate
    if result.get("max_child_depth") != 1:
        _add_issue(
            issues,
            "plan_child_depth_forbidden",
            f"{path}.max_child_depth",
            "Parallel Work v1 has an immutable child-depth ceiling of one",
        )
    if result.get("default_timeout_seconds", 1) > result.get("max_timeout_seconds", 0):
        _add_issue(
            issues,
            "plan_policy_ceiling_invalid",
            f"{path}.default_timeout_seconds",
            "default timeout cannot exceed the maximum timeout",
        )
    return result


def _normalize_base_state(value: Any, issues: list[dict[str, str]]) -> dict[str, Any]:
    path = "$.base_state"
    if not isinstance(value, Mapping):
        _add_issue(issues, "plan_base_snapshot_invalid", path, "base snapshot is required")
        return {}
    snapshot = value.get("base_snapshot") if isinstance(value.get("base_snapshot"), Mapping) else value
    allowed = {"ref", "digest", "immutable"}
    _reject_unexpected(snapshot, allowed, path, issues)
    ref = snapshot.get("ref")
    if not isinstance(ref, str) or not ref.strip():
        _add_issue(
            issues,
            "plan_base_snapshot_invalid",
            f"{path}.ref",
            "base snapshot ref must be a non-empty string",
        )
    digest = _valid_digest(snapshot.get("digest"), f"{path}.digest", issues)
    if snapshot.get("immutable") is not True:
        _add_issue(
            issues,
            "plan_base_snapshot_mutable",
            f"{path}.immutable",
            "compiler input must identify an immutable base snapshot",
        )
    return {"ref": str(ref or "").strip(), "digest": digest, "immutable": True}


def _normalize_resources(
    value: Any, path: str, issues: list[dict[str, str]]
) -> dict[str, list[str]]:
    if not isinstance(value, Mapping):
        _add_issue(issues, "plan_resources_required", path, "declared resources are required")
        return {"read_refs": [], "exclusive_keys": []}
    _reject_unexpected(value, RESOURCE_KEYS, path, issues)
    raw_read_refs = value.get("read_refs")
    if isinstance(raw_read_refs, list) and len(raw_read_refs) > MAX_READ_REFS_PER_UNIT:
        _add_issue(
            issues,
            "plan_read_ref_limit_exceeded",
            f"{path}.read_refs",
            f"at most {MAX_READ_REFS_PER_UNIT} bounded read refs are allowed",
        )
    read_refs = _normalize_string_list(value.get("read_refs"), f"{path}.read_refs", issues)
    for index, ref in enumerate(read_refs):
        ref_path = f"{path}.read_refs[{index}]"
        if len(ref) > MAX_READ_REF_LENGTH:
            _add_issue(
                issues,
                "plan_read_ref_limit_exceeded",
                ref_path,
                f"read refs cannot exceed {MAX_READ_REF_LENGTH} characters",
            )
        lowered = ref.lower()
        segments = lowered.split("/")
        wildcard_at = min(
            (position for token in "*?[" if (position := ref.find(token)) >= 0),
            default=len(ref),
        )
        literal_prefix = ref[:wildcard_at].strip("/")
        root_segment = segments[0] if segments else ""
        read_enters_runtime = (
            root_segment == "runtime"
            or _glob_segment_matches_any(root_segment, {"runtime"})
        )
        pattern_targets_sensitive_segment = any(
            _glob_segment_matches_any(segment, PATTERN_PROTECTED_SEGMENTS)
            for segment in segments
        )
        pattern_targets_env_prefix = any(
            _glob_targets_prefix(segment, ".env")
            for segment in segments
        )
        if (
            ref.startswith(("/", "~"))
            or WINDOWS_DRIVE_PREFIX_RE.match(ref) is not None
            or "\\" in ref
            or "//" in ref
            or any(ord(character) < 32 for character in ref)
            or any(segment in {".", ".."} for segment in segments)
            or not literal_prefix
        ):
            _add_issue(
                issues,
                "plan_unbounded_read_ref_forbidden",
                ref_path,
                "read refs must be bounded canonical repository-relative paths or anchored globs",
            )
        if (
            any(segment in SENSITIVE_READ_SEGMENTS for segment in segments)
            or any(segment.startswith(".env") for segment in segments)
            or read_enters_runtime
            or pattern_targets_sensitive_segment
            or pattern_targets_env_prefix
            or (
                lowered.startswith("runtime/agent-control/")
                and any(segment.endswith(".local.json") for segment in segments[2:])
            )
        ):
            _add_issue(
                issues,
                "plan_sensitive_read_ref_forbidden",
                ref_path,
                "ephemeral work cannot request secrets, credentials, local runtime state, or VCS internals",
            )
    raw_exclusive_keys = value.get("exclusive_keys")
    if (
        isinstance(raw_exclusive_keys, list)
        and len(raw_exclusive_keys) > MAX_EXCLUSIVE_KEYS_PER_UNIT
    ):
        _add_issue(
            issues,
            "plan_exclusive_key_limit_exceeded",
            f"{path}.exclusive_keys",
            f"at most {MAX_EXCLUSIVE_KEYS_PER_UNIT} exclusive keys are allowed",
        )
    exclusive_keys = _normalize_string_list(
        value.get("exclusive_keys"), f"{path}.exclusive_keys", issues
    )
    for index, key in enumerate(exclusive_keys):
        if len(key) > MAX_EXCLUSIVE_KEY_LENGTH:
            _add_issue(
                issues,
                "plan_exclusive_key_limit_exceeded",
                f"{path}.exclusive_keys[{index}]",
                f"exclusive keys cannot exceed {MAX_EXCLUSIVE_KEY_LENGTH} characters",
            )
        if WILDCARD_RE.search(key):
            _add_issue(
                issues,
                "plan_broad_exclusive_resource_forbidden",
                f"{path}.exclusive_keys[{index}]",
                "wildcards cannot establish safe exclusive ownership",
            )
    return {"read_refs": read_refs, "exclusive_keys": exclusive_keys}


def _normalize_work_units(
    value: Any,
    *,
    base_snapshot: Mapping[str, Any],
    router: Mapping[str, Any],
    policy: Mapping[str, int],
    issues: list[dict[str, str]],
    warnings: list[dict[str, str]],
) -> tuple[list[dict[str, Any]], dict[str, list[Any]]]:
    if not isinstance(value, list):
        _add_issue(issues, "plan_expected_array", "$.proposal.work_units", "work units must be an array")
        return [], {}
    if not 2 <= len(value) <= policy.get("max_work_units", 32):
        _add_issue(
            issues,
            "plan_fan_out_exceeded",
            "$.proposal.work_units",
            "delegated plans require 2..policy.max_work_units work units",
        )
    staged: list[tuple[int | None, str, dict[str, Any], list[Any]]] = []
    normalized_ids: dict[str, str] = {}
    explicit_orders: dict[int, str] = {}
    for index, raw in enumerate(value):
        path = f"$.proposal.work_units[{index}]"
        if not isinstance(raw, Mapping):
            _add_issue(issues, "plan_expected_object", path, "work unit must be an object")
            continue
        _reject_unexpected(raw, WORK_UNIT_KEYS, path, issues)
        raw_id = raw.get("work_unit_id", raw.get("id"))
        if "work_unit_id" in raw and "id" in raw:
            _add_issue(
                issues,
                "plan_work_unit_id_ambiguous",
                path,
                "use work_unit_id or legacy id, never both",
            )
        unit_id = _normalized_id(raw_id, f"{path}.work_unit_id", issues)
        if unit_id is None:
            continue
        if unit_id in normalized_ids:
            _add_issue(
                issues,
                "plan_work_unit_id_ambiguous",
                f"{path}.work_unit_id",
                f"id collides after normalization with {normalized_ids[unit_id]}",
            )
        else:
            normalized_ids[unit_id] = path
        declared_order = raw.get("order")
        if declared_order is not None:
            if not _is_int(declared_order) or declared_order < 0:
                _add_issue(
                    issues,
                    "plan_order_invalid",
                    f"{path}.order",
                    "order must be an integer >= 0",
                )
                declared_order = None
            elif declared_order in explicit_orders:
                _add_issue(
                    issues,
                    "plan_order_ambiguous",
                    f"{path}.order",
                    f"order is already used by {explicit_orders[declared_order]}",
                )
            else:
                explicit_orders[declared_order] = unit_id
        criticality = raw.get("criticality", "required")
        if criticality not in {"required", "optional"}:
            _add_issue(
                issues,
                "plan_criticality_invalid",
                f"{path}.criticality",
                "criticality must be required or optional",
            )
            criticality = "required"
        objective = raw.get("objective")
        if not isinstance(objective, str) or not objective.strip():
            _add_issue(
                issues,
                "plan_objective_required",
                f"{path}.objective",
                "one non-empty objective is required",
            )
            objective = ""
        elif len(objective) > 4000:
            _add_issue(
                issues,
                "plan_objective_too_long",
                f"{path}.objective",
                "objective cannot exceed 4000 characters",
            )
        raw_criteria = raw.get("acceptance_criteria")
        if (
            isinstance(raw_criteria, list)
            and len(raw_criteria) > MAX_ACCEPTANCE_CRITERIA_PER_UNIT
        ):
            _add_issue(
                issues,
                "plan_acceptance_criteria_exceeded",
                f"{path}.acceptance_criteria",
                f"at most {MAX_ACCEPTANCE_CRITERIA_PER_UNIT} acceptance criteria are allowed",
            )
        criteria = _normalize_string_list(
            raw_criteria,
            f"{path}.acceptance_criteria",
            issues,
            minimum=1,
        )
        if len(criteria) > MAX_ACCEPTANCE_CRITERIA_PER_UNIT:
            _add_issue(
                issues,
                "plan_acceptance_criteria_exceeded",
                f"{path}.acceptance_criteria",
                f"at most {MAX_ACCEPTANCE_CRITERIA_PER_UNIT} acceptance criteria are allowed",
            )
        target_role = _normalized_id(raw.get("target_role"), f"{path}.target_role", issues)
        target_stage = _normalized_id(raw.get("target_stage"), f"{path}.target_stage", issues)
        recommended_profile = raw.get("capability_profile")
        if recommended_profile not in CAPABILITY_PROFILES:
            _add_issue(
                issues,
                "plan_capability_profile_invalid",
                f"{path}.capability_profile",
                "work units must recommend one stable capability profile",
            )
        elif recommended_profile != router.get("capability_profile"):
            warnings.append(
                issue(
                    "plan_capability_profile_overridden",
                    f"{path}.capability_profile",
                    "the central Router selected a different stable capability profile",
                )
            )
        if raw.get("access_mode", "read_only") != "read_only":
            _add_issue(
                issues,
                "plan_write_intent_forbidden",
                f"{path}.access_mode",
                "ephemeral v1 work must be read_only; durable mutation requires Dispatcher and a Worker Packet",
            )
        for key in ("mutation_allowed", "external_mutation", "spawn_children", "inherit_parent_authority"):
            if raw.get(key, False) is not False:
                code = (
                    "plan_child_spawn_forbidden"
                    if key == "spawn_children"
                    else "plan_inherited_authority_forbidden"
                    if key == "inherit_parent_authority"
                    else "plan_write_intent_forbidden"
                )
                _add_issue(
                    issues,
                    code,
                    f"{path}.{key}",
                    "read-only direct children cannot request this capability; durable mutation requires Dispatcher",
                )
        write_paths = raw.get("write_paths", [])
        if not isinstance(write_paths, list) or write_paths:
            _add_issue(
                issues,
                "plan_write_intent_forbidden",
                f"{path}.write_paths",
                "write_paths must be empty; proposed writes require Dispatcher and a Worker Packet",
            )
        resources = _normalize_resources(raw.get("resources"), f"{path}.resources", issues)
        skill_bindings = _normalize_skill_bindings(
            raw.get("skill_bindings"), f"{path}.skill_bindings", issues
        )
        output_schema = raw.get("expected_result_schema", EXPECTED_RESULT_SCHEMA)
        if output_schema != EXPECTED_RESULT_SCHEMA:
            _add_issue(
                issues,
                "plan_output_contract_invalid",
                f"{path}.expected_result_schema",
                "Parallel Work v1 requires the Subagent Result Envelope schema",
            )
        timeout = raw.get("timeout_seconds", policy.get("default_timeout_seconds", 1800))
        if (
            not _is_int(timeout)
            or timeout < 1
            or timeout > policy.get("max_timeout_seconds", 86400)
        ):
            _add_issue(
                issues,
                "plan_timeout_ceiling_exceeded",
                f"{path}.timeout_seconds",
                "timeout must be within the explicit policy ceiling",
            )
            timeout = min(
                policy.get("default_timeout_seconds", 1800),
                policy.get("max_timeout_seconds", 86400),
            )
        legacy = raw.get("depends_on", [])
        if not isinstance(legacy, list):
            _add_issue(
                issues,
                "plan_expected_array",
                f"{path}.depends_on",
                "legacy depends_on must be an array of ids",
            )
            legacy = []
        elif len(legacy) > MAX_DEPENDENCIES:
            _add_issue(
                issues,
                "plan_dependency_limit_exceeded",
                f"{path}.depends_on",
                f"at most {MAX_DEPENDENCIES} legacy dependencies are allowed",
            )
        elif legacy:
            warnings.append(
                issue(
                    "plan_legacy_dependency_normalized",
                    f"{path}.depends_on",
                    "legacy untyped dependencies were normalized to success edges",
                )
            )
        input_seed = {
            "base_snapshot_digest": base_snapshot.get("digest"),
            "read_refs": resources["read_refs"],
            "skill_bindings": skill_bindings,
        }
        input_bundle_digest = canonical_digest(input_seed)
        unit = {
            "work_unit_id": unit_id,
            "order": 0,
            "criticality": criticality,
            "objective": str(objective).strip(),
            "acceptance_criteria": criteria,
            "target_role": target_role,
            "target_stage": target_stage,
            "capability_profile": router.get("capability_profile"),
            "access_mode": "read_only",
            "mutation_allowed": False,
            "resources": resources,
            "expected_result_schema": EXPECTED_RESULT_SCHEMA,
            "timeout_seconds": timeout,
            "input_bundle_digest": input_bundle_digest,
            "skill_bindings": skill_bindings,
        }
        staged.append((declared_order, unit_id, unit, legacy))
    staged.sort(
        key=lambda row: (
            row[0] is None,
            row[0] if row[0] is not None else 0,
            row[1],
        )
    )
    units: list[dict[str, Any]] = []
    legacy_by_unit: dict[str, list[Any]] = {}
    for order, (_, unit_id, unit, legacy) in enumerate(staged):
        unit["order"] = order
        units.append(unit)
        legacy_by_unit[unit_id] = legacy
    return units, legacy_by_unit


def _normalize_dependencies(
    value: Any,
    *,
    units: Sequence[Mapping[str, Any]],
    legacy_by_unit: Mapping[str, list[Any]],
    issues: list[dict[str, str]],
) -> list[dict[str, str]]:
    if value is None:
        value = []
    if not isinstance(value, list):
        _add_issue(issues, "plan_expected_array", "$.proposal.dependencies", "dependencies must be an array")
        value = []
    elif len(value) > MAX_DEPENDENCIES:
        _add_issue(
            issues,
            "plan_dependency_limit_exceeded",
            "$.proposal.dependencies",
            f"at most {MAX_DEPENDENCIES} typed dependencies are allowed",
        )
    candidates: list[tuple[Any, Any, Any, str]] = []
    for index, raw in enumerate(value):
        path = f"$.proposal.dependencies[{index}]"
        if not isinstance(raw, Mapping):
            _add_issue(issues, "plan_expected_object", path, "dependency must be an object")
            continue
        _reject_unexpected(raw, DEPENDENCY_KEYS, path, issues)
        candidates.append((raw.get("from"), raw.get("to"), raw.get("type"), path))
    for target, sources in legacy_by_unit.items():
        for index, source in enumerate(sources):
            candidates.append(
                (source, target, "success", f"$.proposal.work_units[{target}].depends_on[{index}]")
            )
    if len(candidates) > MAX_DEPENDENCIES:
        _add_issue(
            issues,
            "plan_dependency_limit_exceeded",
            "$.proposal.dependencies",
            f"typed and legacy declarations together cannot exceed {MAX_DEPENDENCIES} dependencies",
        )
    unit_ids = {str(unit["work_unit_id"]) for unit in units}
    edges: dict[tuple[str, str], str] = {}
    for raw_source, raw_target, dep_type, path in candidates:
        source = _normalized_id(raw_source, f"{path}.from", issues)
        target = _normalized_id(raw_target, f"{path}.to", issues)
        if dep_type not in DEPENDENCY_TYPES:
            _add_issue(
                issues,
                "plan_dependency_type_invalid",
                f"{path}.type",
                "dependency type is not supported by Parallel Work v1",
            )
            continue
        if source not in unit_ids:
            _add_issue(
                issues,
                "plan_dependency_source_unknown",
                f"{path}.from",
                "dependency source does not name a work unit",
            )
        if target not in unit_ids:
            _add_issue(
                issues,
                "plan_dependency_target_unknown",
                f"{path}.to",
                "dependency target does not name a work unit",
            )
        if source and source == target:
            _add_issue(
                issues,
                "plan_self_dependency",
                path,
                "work unit cannot depend on itself",
            )
        if source not in unit_ids or target not in unit_ids or source == target:
            continue
        pair = (source, target)
        previous = edges.get(pair)
        if previous is not None:
            if previous != dep_type:
                _add_issue(
                    issues,
                    "plan_dependency_ambiguous",
                    path,
                    f"the same edge is declared as both {previous} and {dep_type}",
                )
            else:
                _add_issue(
                    issues,
                    "plan_dependency_duplicate",
                    path,
                    "the same dependency edge may be declared only once",
                )
        else:
            edges[pair] = str(dep_type)
    return [
        {"from": source, "to": target, "type": dep_type}
        for (source, target), dep_type in sorted(
            edges.items(), key=lambda row: (row[0][0], row[0][1], row[1])
        )
    ]


def _topological_order(
    units: Sequence[Mapping[str, Any]],
    dependencies: Sequence[Mapping[str, str]],
) -> tuple[list[str], bool]:
    ranks = {
        str(unit["work_unit_id"]): (int(unit["order"]), str(unit["work_unit_id"]))
        for unit in units
    }
    incoming = {unit_id: 0 for unit_id in ranks}
    outgoing = {unit_id: [] for unit_id in ranks}
    for dependency in dependencies:
        source = dependency["from"]
        target = dependency["to"]
        outgoing[source].append(target)
        incoming[target] += 1
    ready = sorted(
        (unit_id for unit_id, count in incoming.items() if count == 0),
        key=ranks.__getitem__,
    )
    ordered: list[str] = []
    while ready:
        current = ready.pop(0)
        ordered.append(current)
        for target in sorted(outgoing[current], key=ranks.__getitem__):
            incoming[target] -= 1
            if incoming[target] == 0:
                ready.append(target)
                ready.sort(key=ranks.__getitem__)
    return ordered, len(ordered) != len(units)


def _reachable(source: str, target: str, adjacency: Mapping[str, set[str]]) -> bool:
    pending = [source]
    seen: set[str] = set()
    while pending:
        current = pending.pop()
        if current == target:
            return True
        if current in seen:
            continue
        seen.add(current)
        pending.extend(sorted(adjacency.get(current, set()) - seen, reverse=True))
    return False


def _validate_resource_conflicts(
    units: Sequence[Mapping[str, Any]],
    dependencies: Sequence[Mapping[str, str]],
    issues: list[dict[str, str]],
) -> None:
    adjacency = {str(unit["work_unit_id"]): set() for unit in units}
    for dependency in dependencies:
        adjacency[dependency["from"]].add(dependency["to"])
    for left_index, left in enumerate(units):
        left_id = str(left["work_unit_id"])
        left_keys = set(left["resources"]["exclusive_keys"])
        for right in units[left_index + 1 :]:
            right_id = str(right["work_unit_id"])
            shared = left_keys & set(right["resources"]["exclusive_keys"])
            if shared and not (
                _reachable(left_id, right_id, adjacency)
                or _reachable(right_id, left_id, adjacency)
            ):
                _add_issue(
                    issues,
                    "plan_parallel_resource_conflict",
                    "$.proposal.work_units.resources.exclusive_keys",
                    f"{left_id} and {right_id} can run concurrently but share {sorted(shared)}",
                )


def _normalize_barriers(
    value: Any,
    *,
    units: Sequence[Mapping[str, Any]],
    issues: list[dict[str, str]],
) -> list[dict[str, Any]]:
    unit_ids = [str(unit["work_unit_id"]) for unit in units]
    unit_set = set(unit_ids)
    required_set = {
        str(unit["work_unit_id"])
        for unit in units
        if unit.get("criticality") == "required"
    }
    if not required_set:
        _add_issue(
            issues,
            "plan_required_work_unit_missing",
            "$.proposal.work_units",
            "at least one required work unit is needed for the integration gate",
        )
    if value is None:
        value = []
    if not isinstance(value, list):
        _add_issue(issues, "plan_expected_array", "$.proposal.barriers", "barriers must be an array")
        value = []
    elif len(value) > 128:
        _add_issue(
            issues,
            "plan_barrier_limit_exceeded",
            "$.proposal.barriers",
            "at most 128 barriers are allowed",
        )
    if not value and unit_ids:
        return [
            {
                "barrier_id": "barrier-integration",
                "members": unit_ids,
                "required_members": [unit_id for unit_id in unit_ids if unit_id in required_set],
                "release_condition": "all_required_success",
                "bypass_allowed": False,
            }
        ]
    barriers: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    covered: set[str] = set()
    order = {unit_id: index for index, unit_id in enumerate(unit_ids)}
    for index, raw in enumerate(value):
        path = f"$.proposal.barriers[{index}]"
        if not isinstance(raw, Mapping):
            _add_issue(issues, "plan_expected_object", path, "barrier must be an object")
            continue
        _reject_unexpected(raw, BARRIER_KEYS, path, issues)
        if "barrier_id" in raw and "id" in raw:
            _add_issue(issues, "plan_barrier_id_ambiguous", path, "use barrier_id or legacy id, never both")
        barrier_id = _normalized_id(raw.get("barrier_id", raw.get("id")), f"{path}.barrier_id", issues)
        if barrier_id in seen_ids:
            _add_issue(
                issues,
                "plan_barrier_id_ambiguous",
                f"{path}.barrier_id",
                "barrier id collides after normalization",
            )
        elif barrier_id:
            seen_ids.add(barrier_id)
        members_raw = raw.get("members")
        if not isinstance(members_raw, list):
            _add_issue(issues, "plan_expected_array", f"{path}.members", "members must be an array")
            members_raw = []
        elif len(members_raw) > len(unit_ids):
            _add_issue(
                issues,
                "plan_barrier_member_limit_exceeded",
                f"{path}.members",
                "barrier members cannot exceed the work-unit count",
            )
        normalized_members = [
            member
            for item in members_raw
            if (member := _normalized_id(item, f"{path}.members", issues))
        ]
        members = set(normalized_members)
        if len(normalized_members) != len(members):
            _add_issue(
                issues,
                "plan_barrier_member_duplicate",
                f"{path}.members",
                "barrier members must remain unique after id normalization",
            )
        unknown = members - unit_set
        if unknown:
            _add_issue(
                issues,
                "plan_barrier_member_unknown",
                f"{path}.members",
                f"unknown work units: {sorted(unknown)}",
            )
        required_raw = raw.get("required_members")
        if required_raw is None:
            required = members & required_set
        elif isinstance(required_raw, list):
            if len(required_raw) > len(unit_ids):
                _add_issue(
                    issues,
                    "plan_barrier_member_limit_exceeded",
                    f"{path}.required_members",
                    "required barrier members cannot exceed the work-unit count",
                )
            normalized_required = [
                member
                for item in required_raw
                if (member := _normalized_id(item, f"{path}.required_members", issues))
            ]
            required = set(normalized_required)
            if len(normalized_required) != len(required):
                _add_issue(
                    issues,
                    "plan_barrier_member_duplicate",
                    f"{path}.required_members",
                    "required members must remain unique after id normalization",
                )
        else:
            _add_issue(
                issues,
                "plan_expected_array",
                f"{path}.required_members",
                "required_members must be an array",
            )
            required = set()
        if not required <= members:
            _add_issue(
                issues,
                "plan_barrier_required_member_missing",
                f"{path}.required_members",
                "required members must also be barrier members",
            )
        expected_required = members & required_set
        if required != expected_required:
            _add_issue(
                issues,
                "plan_barrier_criticality_mismatch",
                f"{path}.required_members",
                "required_members must exactly match required work units in the barrier",
            )
        release = raw.get("release_condition", "all_required_success")
        if release not in {
            "all_required_success",
            "all_completion",
            "approval",
            "integration_accepted",
        }:
            _add_issue(
                issues,
                "plan_barrier_release_invalid",
                f"{path}.release_condition",
                "barrier release condition is unsupported",
            )
        if raw.get("bypass_allowed", False) is not False:
            _add_issue(
                issues,
                "plan_barrier_bypass_forbidden",
                f"{path}.bypass_allowed",
                "required barriers cannot be bypassed",
            )
        known_members = sorted(members & unit_set, key=order.__getitem__)
        covered.update(known_members)
        barriers.append(
            {
                "barrier_id": barrier_id,
                "members": known_members,
                "required_members": [member for member in known_members if member in required],
                "release_condition": release,
                "bypass_allowed": False,
            }
        )
    missing = unit_set - covered
    if missing:
        _add_issue(
            issues,
            "plan_barrier_coverage_incomplete",
            "$.proposal.barriers",
            f"work units are absent from every barrier: {sorted(missing)}",
        )
    return sorted(barriers, key=lambda row: str(row["barrier_id"]))


def _normalize_revision_change(
    proposal: Mapping[str, Any], revision: int, issues: list[dict[str, str]]
) -> dict[str, Any] | None:
    value = proposal.get("revision_change")
    if revision == 1:
        if value is not None:
            _add_issue(
                issues,
                "plan_revision_change_unexpected",
                "$.proposal.revision_change",
                "revision 1 cannot declare a revision change",
            )
        return None
    if not isinstance(value, Mapping):
        _add_issue(
            issues,
            "plan_revision_change_required",
            "$.proposal.revision_change",
            "revisions after 1 require an explicit progress-denominator decision",
        )
        return None
    _reject_unexpected(value, REVISION_CHANGE_KEYS, "$.proposal.revision_change", issues)
    changed = value.get("progress_denominator_changed")
    approved = value.get("approved_before_execution")
    reason = value.get("reason")
    if not isinstance(changed, bool) or not isinstance(approved, bool):
        _add_issue(
            issues,
            "plan_revision_change_invalid",
            "$.proposal.revision_change",
            "revision change booleans must be explicit",
        )
    if not isinstance(reason, str) or not reason.strip():
        _add_issue(
            issues,
            "plan_revision_change_invalid",
            "$.proposal.revision_change.reason",
            "revision change reason is required",
        )
    if approved is True:
        _add_issue(
            issues,
            "plan_approval_claim_forbidden",
            "$.proposal.revision_change.approved_before_execution",
            "an untrusted role proposal cannot attest approval; Dispatcher must supply a newly reviewed plan",
        )
    if changed is True:
        _add_issue(
            issues,
            "plan_progress_denominator_change_unapproved",
            "$.proposal.revision_change.approved_before_execution",
            "progress denominator changes leave the ephemeral compiler path and require Dispatcher approval",
        )
    return {
        "progress_denominator_changed": bool(changed),
        "reason": str(reason or "").strip(),
        "approved_before_execution": False,
    }


def plan_content_projection(plan: Mapping[str, Any]) -> dict[str, Any]:
    """Return the central Router's immutable Parallel Work v1 projection."""

    projection = plan_content_projection_v1(plan)
    if projection is None:
        raise ValueError("plan is missing an execution-defining Router field")
    return copy.deepcopy(projection)


def authorization_binding_from_plan(plan: Mapping[str, Any]) -> dict[str, Any]:
    """Return a detached full-plan copy accepted by the hardened S05 issuer."""

    return copy.deepcopy(dict(plan))


def _invalid_result(
    proposal_digest: str | None,
    issues: list[dict[str, str]],
    warnings: list[dict[str, str]],
) -> dict[str, Any]:
    return {
        "compiler_id": COMPILER_ID,
        "compiler_version": COMPILER_VERSION,
        "digest_profile": DIGEST_PROFILE,
        "valid": False,
        "proposal_digest": proposal_digest,
        "errors": _sorted_issues(issues),
        "warnings": _sorted_issues(warnings),
        "plan": None,
        "authorization_binding": None,
        "capacity_limits": None,
    }


def compile_plan(
    proposal: Mapping[str, Any],
    router_decision: Mapping[str, Any],
    policy: Mapping[str, Any],
    base_state: Mapping[str, Any],
) -> dict[str, Any]:
    """Compile explicit inputs into a validated, authority-free plan artifact.

    Invalid input returns stable structured errors and never emits a partial
    plan or authorization binding.
    """

    issues: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    if not isinstance(proposal, Mapping):
        _add_issue(issues, "plan_proposal_invalid", "$.proposal", "proposal must be an object")
        return _invalid_result(None, issues, warnings)
    try:
        # Proposal identity is intentionally independent from trusted Router,
        # policy, and base-state inputs.  Derived normalization is bound by the
        # separate plan-content digest below.
        proposal_digest: str | None = canonical_digest(proposal)
    except (TypeError, ValueError):
        proposal_digest = None
        _add_issue(
            issues,
            "plan_proposal_not_canonicalizable",
            "$.proposal",
            "proposal must contain only finite JSON values",
        )
    _scan_forbidden_fields(proposal, "$.proposal", issues)
    _reject_unexpected(proposal, PROPOSAL_KEYS, "$.proposal", issues)
    if proposal.get("contract_version") != CONTRACT_VERSION:
        _add_issue(
            issues,
            "plan_contract_version_invalid",
            "$.proposal.contract_version",
            f"expected {CONTRACT_VERSION}",
        )
    if proposal.get("contract_kind") != "execution_plan_proposal":
        _add_issue(
            issues,
            "plan_contract_kind_invalid",
            "$.proposal.contract_kind",
            "expected execution_plan_proposal",
        )
    if proposal.get("digest_profile", DIGEST_PROFILE) != DIGEST_PROFILE:
        _add_issue(
            issues,
            "plan_digest_profile_invalid",
            "$.proposal.digest_profile",
            f"expected {DIGEST_PROFILE}",
        )
    proposal_id = _normalized_id(proposal.get("proposal_id"), "$.proposal.proposal_id", issues)
    correlation_id = _normalized_id(
        proposal.get("correlation_id"), "$.proposal.correlation_id", issues
    )
    plan_id = _normalized_id(proposal.get("plan_id"), "$.proposal.plan_id", issues)
    created_at = _normalized_timestamp(proposal.get("created_at"), "$.proposal.created_at", issues)
    revision = proposal.get("revision", 1)
    if not _is_int(revision) or revision < 1:
        _add_issue(
            issues,
            "plan_revision_invalid",
            "$.proposal.revision",
            "revision must be an integer >= 1",
        )
        revision = 1
    supersedes = proposal.get("supersedes")
    if revision == 1:
        if supersedes is not None:
            _add_issue(
                issues,
                "plan_supersedes_unexpected",
                "$.proposal.supersedes",
                "revision 1 cannot supersede an earlier plan",
            )
        normalized_supersedes = None
    else:
        normalized_supersedes = _normalized_id(
            supersedes, "$.proposal.supersedes", issues
        )
    producer = proposal.get("producer")
    if not isinstance(producer, Mapping):
        _add_issue(issues, "plan_producer_invalid", "$.proposal.producer", "producer is required")
        producer = {}
    _reject_unexpected(producer, PRODUCER_KEYS, "$.proposal.producer", issues)
    normalized_producer = {
        key: _normalized_id(producer.get(key), f"$.proposal.producer.{key}", issues)
        for key in ("role", "stage", "project_id")
    }
    _normalize_authority_guard(proposal.get("authority_guard"), "$.proposal.authority_guard", issues)
    audit = proposal.get("audit", {})
    if not isinstance(audit, Mapping):
        _add_issue(issues, "plan_audit_invalid", "$.proposal.audit", "audit must be an object")
        audit = {}
    _reject_unexpected(audit, AUDIT_KEYS, "$.proposal.audit", issues)
    journal_ref = audit.get("journal_ref", f"proposal://{proposal_id or 'invalid'}")
    if not isinstance(journal_ref, str) or not journal_ref.strip():
        _add_issue(
            issues,
            "plan_audit_invalid",
            "$.proposal.audit.journal_ref",
            "journal_ref must be a non-empty string",
        )
        journal_ref = f"proposal://{proposal_id or 'invalid'}"
    risk = proposal.get("risk")
    if risk not in RISK_VALUES:
        _add_issue(
            issues,
            "plan_risk_invalid",
            "$.proposal.risk",
            "risk must be low, medium, high, or critical",
        )
        risk = "medium"
    requested_lanes = proposal.get("requested_lanes")
    if not _is_int(requested_lanes) or not 2 <= requested_lanes <= 64:
        _add_issue(
            issues,
            "plan_requested_capacity_invalid",
            "$.proposal.requested_lanes",
            "requested lanes must be an integer between 2 and 64",
        )
        requested_lanes = 2
    max_attempts = proposal.get("max_attempts_per_unit", 1)
    if not _is_int(max_attempts) or not 1 <= max_attempts <= 2:
        _add_issue(
            issues,
            "plan_attempt_budget_invalid",
            "$.proposal.max_attempts_per_unit",
            "v1 permits one initial attempt and at most one identical retry",
        )
        max_attempts = 1
    child_depth = proposal.get("child_depth", 1)
    if child_depth != 1:
        _add_issue(
            issues,
            "plan_child_depth_forbidden",
            "$.proposal.child_depth",
            "Parallel Work v1 has child depth one",
        )
    normalized_router = _normalize_router_decision(router_decision, issues)
    normalized_policy = _normalize_policy(policy, issues)
    normalized_base = _normalize_base_state(base_state, issues)
    if max_attempts > normalized_policy.get("max_attempts_per_unit", 2):
        _add_issue(
            issues,
            "plan_attempt_budget_exceeded",
            "$.proposal.max_attempts_per_unit",
            "attempt count exceeds the explicit policy ceiling",
        )
    units, legacy_by_unit = _normalize_work_units(
        proposal.get("work_units"),
        base_snapshot=normalized_base,
        router=normalized_router,
        policy=normalized_policy,
        issues=issues,
        warnings=warnings,
    )
    if requested_lanes > len(units):
        _add_issue(
            issues,
            "plan_capacity_exceeds_work_units",
            "$.proposal.requested_lanes",
            "requested lanes cannot exceed the normalized work-unit count",
        )
    dependencies = _normalize_dependencies(
        proposal.get("dependencies"),
        units=units,
        legacy_by_unit=legacy_by_unit,
        issues=issues,
    )
    ready_order, cyclic = _topological_order(units, dependencies)
    if cyclic:
        _add_issue(
            issues,
            "plan_dependency_cycle",
            "$.proposal.dependencies",
            "work-unit dependency graph must be acyclic",
        )
    _validate_resource_conflicts(units, dependencies, issues)
    barriers = _normalize_barriers(
        proposal.get("barriers"), units=units, issues=issues
    )
    revision_change = _normalize_revision_change(proposal, revision, issues)
    grace = proposal.get("cancellation_grace_seconds", 30)
    if not _is_int(grace) or not 0 <= grace <= 300:
        _add_issue(
            issues,
            "plan_cancellation_grace_invalid",
            "$.proposal.cancellation_grace_seconds",
            "cancellation grace must be an integer from 0 through 300",
        )

    if issues:
        return _invalid_result(proposal_digest, issues, warnings)

    effective_lanes = min(
        int(normalized_router["parallel_lanes"]),
        int(requested_lanes),
        len(units),
        normalized_policy["project_policy"],
        normalized_policy["worker_policy"],
        normalized_policy["runtime"],
    )
    if effective_lanes < 2:
        _add_issue(
            issues,
            "plan_delegation_capacity_unavailable",
            "$.policy",
            "the combined Router/policy capacity cannot provide two lanes",
        )
        return _invalid_result(proposal_digest, issues, warnings)
    max_total_invocations = len(units) * max_attempts
    capacity_seed = {
        "router_authorized_lanes": normalized_router["parallel_lanes"],
        "plan_requested_lanes": requested_lanes,
        "plan_work_units": len(units),
        "project_policy_ceiling": normalized_policy["project_policy"],
        "worker_policy_ceiling": normalized_policy["worker_policy"],
        "runtime_capacity_ceiling": normalized_policy["runtime"],
        "authorized_lanes": effective_lanes,
        "max_attempts_per_unit": max_attempts,
        "max_total_invocations": max_total_invocations,
    }
    budget_digest = canonical_digest(capacity_seed)
    binding_units = sorted(
        (
            {
                "work_unit_id": unit["work_unit_id"],
                "input_bundle_digest": unit["input_bundle_digest"],
            }
            for unit in units
        ),
        key=lambda row: row["work_unit_id"],
    )
    work_unit_scope_digest = canonical_digest(
        [unit["work_unit_id"] for unit in binding_units]
    )
    input_scope_digest = canonical_digest(binding_units)
    reason_codes = ["plan-compiled"]
    if warnings:
        reason_codes.extend(warning["code"].replace("_", "-") for warning in warnings)
    required_ids = [
        unit["work_unit_id"]
        for unit in units
        if unit["criticality"] == "required"
    ]
    plan: dict[str, Any] = {
        "contract_version": CONTRACT_VERSION,
        "contract_kind": "parallel_work",
        "correlation_id": correlation_id,
        "producer": {
            "role": "plan-compiler",
            "stage": "execution-contracts",
            "project_id": normalized_producer["project_id"],
        },
        "created_at": created_at,
        "revision": revision,
        "supersedes": normalized_supersedes,
        "digest_profile": DIGEST_PROFILE,
        "authority_guard": copy.deepcopy(AUTHORITY_GUARD),
        "audit": {
            "journal_ref": str(journal_ref).strip(),
            "reason_codes": sorted(set(reason_codes)),
            "warnings": [warning["message"] for warning in _sorted_issues(warnings)],
        },
        "plan_id": plan_id,
        "status": "validated",
        "base_snapshot": normalized_base,
        "plan_content_digest": "",
        "router_decision_id": normalized_router["decision_id"],
        "router_decision_digest": normalized_router["decision_digest"],
        "selected_route_digest": normalized_router["selected_route_digest"],
        "work_unit_scope_digest": work_unit_scope_digest,
        "input_scope_digest": input_scope_digest,
        "risk": risk,
        "capacity": {
            "requested_lanes": requested_lanes,
            "authorized_lanes": effective_lanes,
            "effective_lanes": effective_lanes,
            "max_total_invocations": max_total_invocations,
            "max_attempts_per_unit": max_attempts,
            "budget_digest": budget_digest,
        },
        "work_units": units,
        "dependencies": dependencies,
        "barriers": barriers,
        "completion_policy": {
            "required_units": "all_success",
            "optional_units": "allow_explicit_residual_risk",
            "quorum_allowed": False,
            "minimum_success_allowed": False,
        },
        "failure_policy": {
            "required_failure": "block_integration",
            "optional_failure": "record_residual_risk",
            "dependent_failure": "block_hard_dependents",
        },
        "cancellation_policy": {
            "parent_cancel_queued": True,
            "running_grace_seconds": grace,
            "retain_evidence": True,
        },
        "integration_gate": {
            "required_work_unit_ids": required_ids,
            "all_required_must_succeed": True,
            "missing_required_blocks": True,
        },
        "deterministic_ready_order": ready_order,
        "progress_denominator": len(units),
    }
    if revision_change is not None:
        plan["revision_change"] = revision_change
    expected_input_scope = compute_input_scope_digest_v1(plan)
    if expected_input_scope is None or expected_input_scope != input_scope_digest:
        _add_issue(
            issues,
            "plan_input_scope_projection_failed",
            "$.plan.work_units",
            "the central Router could not derive the compiler input scope",
        )
        return _invalid_result(proposal_digest, issues, warnings)
    plan_digest = compute_plan_content_digest_v1(plan)
    if plan_digest is None:
        _add_issue(
            issues,
            "plan_content_projection_failed",
            "$.plan",
            "the central Router could not derive the execution-defining plan projection",
        )
        return _invalid_result(proposal_digest, issues, warnings)
    plan["plan_content_digest"] = plan_digest
    semantic_report = validate_execution_contract(
        copy.deepcopy(plan),
        kind="parallel_work",
        mode="strict",
        now=created_at,
    )
    if not semantic_report["valid"]:
        for row in semantic_report["errors"]:
            _add_issue(
                issues,
                f"plan_existing_validator_{row['code']}",
                f"$.plan.{row['path']}" if row["path"] != "$" else "$.plan",
                row["message"],
            )
        return _invalid_result(proposal_digest, issues, warnings)
    binding = authorization_binding_from_plan(plan)
    return {
        "compiler_id": COMPILER_ID,
        "compiler_version": COMPILER_VERSION,
        "digest_profile": DIGEST_PROFILE,
        "valid": True,
        "proposal_digest": proposal_digest,
        "errors": [],
        "warnings": _sorted_issues(warnings),
        "plan": plan,
        "authorization_binding": binding,
        "capacity_limits": {
            name: normalized_policy[name]
            for name in ("project_policy", "worker_policy", "runtime")
        },
    }


compile_execution_plan = compile_plan
compile_proposal = compile_plan
build_authorization_binding = authorization_binding_from_plan


def _load_json(path: str) -> Any:
    if path == "-":
        return json.load(sys.stdin)
    return json.loads(Path(path).read_text(encoding="utf-8"))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Compile an explicit read-only execution-plan proposal to stdout."
    )
    parser.add_argument("--proposal", required=True, help="proposal JSON path")
    parser.add_argument("--router-decision", required=True, help="selected Router decision JSON path")
    parser.add_argument("--policy", required=True, help="explicit compiler/capacity policy JSON path")
    parser.add_argument("--base-state", required=True, help="immutable base snapshot JSON path")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        payloads = {
            "proposal": _load_json(args.proposal),
            "router_decision": _load_json(args.router_decision),
            "policy": _load_json(args.policy),
            "base_state": _load_json(args.base_state),
        }
        result = compile_plan(**payloads)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        result = _invalid_result(
            None,
            [issue("plan_input_unreadable", "$", str(exc))],
            [],
        )
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if result["valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
