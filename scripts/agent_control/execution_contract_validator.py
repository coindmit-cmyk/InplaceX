#!/usr/bin/env python3
"""Read-only semantic validation for the AIStudio execution contract family.

The validator checks cross-field invariants that JSON Schema cannot express.
It does not compile plans, select resources, consume authorization, schedule,
launch, integrate, mutate the queue, or grant authority.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import re
from pathlib import Path
from typing import Any, Iterable

from model_resource_router import (
    compute_input_scope_digest_v1,
    compute_plan_content_digest_v1,
    SKILL_BINDING_FIELDS,
    SKILL_VERSION_RE,
    skill_bindings_digest_v1,
)


CONTRACT_VERSION = "1.0.0"
DEFAULT_MODE = "strict_new_warn_legacy"
MODES = ("strict", DEFAULT_MODE)
KINDS = (
    "subagent_invocation",
    "parallel_work",
    "subagent_result_envelope",
    "result_integration",
)
ID_RE = re.compile(r"^[a-z][a-z0-9._:-]{2,127}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
HMAC_PROOF_RE = re.compile(r"^hmac-sha256:[0-9a-f]{64}$")
MIN_PROVENANCE_KEY_BYTES = 32
TERMINAL_INVOCATION_STATUSES = {
    "completed",
    "partial",
    "failed",
    "blocked",
    "cancelled",
    "timed_out",
}
FAILURE_RESULT_STATUSES = {"failed", "timed_out", "cancelled", "blocked", "rejected"}
READY_INTEGRATION_STATUSES = {"ready_for_finalizer", "accepted_with_residual_risk"}
PARALLEL_STATUSES = {
    "proposed",
    "validated",
    "authorized",
    "running",
    "awaiting_integration",
    "completed",
    "blocked",
    "cancelled",
}
AUTHORIZATION_V1_ONLY_FIELDS = {
    "issuer",
    "router_decision_digest",
    "router_decision_provenance_digest",
    "router_provenance_key_id",
    "selected_route_digest",
    "work_unit_scope_digest",
    "input_scope_digest",
    "bound_scope_digest",
    "digest_profile",
    "immutable",
    "max_attempts_per_unit",
    "invocation_grants_single_use",
    "capacity_envelope",
    "authorization_proof",
}
CRITICAL_CONFLICT_CLASSES = {
    "factual",
    "policy_authority",
    "security",
    "irreversible",
}
REQUIRED_TOP_LEVEL = {
    "subagent_invocation": {
        "invocation_id",
        "attempt_id",
        "attempt_number",
        "status",
        "router_decision_id",
        "router_decision_digest",
        "plan_ref",
        "input_bundle",
        "selected_route",
        "authorization",
        "objective",
        "acceptance_criteria",
        "tools",
        "prohibited_actions",
        "expected_result_schema",
        "execution_policy",
        "retry_policy",
        "idempotency_key",
        "invocation_digest",
    },
    "parallel_work": {
        "plan_id",
        "status",
        "base_snapshot",
        "plan_content_digest",
        "router_decision_id",
        "risk",
        "capacity",
        "work_units",
        "dependencies",
        "barriers",
        "completion_policy",
        "failure_policy",
        "cancellation_policy",
        "integration_gate",
        "deterministic_ready_order",
        "progress_denominator",
    },
    "subagent_result_envelope": {
        "result_id",
        "status",
        "invocation_id",
        "attempt_id",
        "attempt_number",
        "plan_id",
        "plan_content_digest",
        "work_unit_id",
        "input_bundle_digest",
        "base_snapshot_digest",
        "selected_route_digest",
        "actual_use",
        "route_reconciliation",
        "started_at",
        "completed_at",
        "evidence",
        "privacy_guard",
        "payload_digest",
        "result_envelope_digest",
    },
    "result_integration": {
        "integration_id",
        "status",
        "plan_id",
        "plan_content_digest",
        "input_result_set_digest",
        "expected_results",
        "received_results",
        "orphan_results",
        "result_accounting",
        "requirement_coverage",
        "lane_coverage",
        "conflicts",
        "blockers",
        "synthesis",
        "integration_digest",
    },
}


def issue(
    issues: list[dict[str, str]],
    code: str,
    path: str,
    message: str,
    *,
    severity: str = "error",
) -> None:
    issues.append(
        {
            "severity": severity,
            "code": code,
            "path": path,
            "message": message,
        }
    )


def is_object(value: Any) -> bool:
    return isinstance(value, dict)


def object_value(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
    *,
    required: bool = True,
) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if required or value is not None:
        issue(issues, "expected_object", path, "value must be an object")
    return {}


def list_value(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
    *,
    required: bool = True,
) -> list[Any]:
    if isinstance(value, list):
        return value
    if required or value is not None:
        issue(issues, "expected_array", path, "value must be an array")
    return []


def object_list(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> list[dict[str, Any]]:
    rows = list_value(value, path, issues)
    result: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        if isinstance(row, dict):
            result.append(row)
        else:
            issue(
                issues,
                "expected_object",
                f"{path}[{index}]",
                "array item must be an object",
            )
    return result


def string_list(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> list[str]:
    rows = list_value(value, path, issues)
    result: list[str] = []
    for index, row in enumerate(rows):
        if isinstance(row, str) and row:
            result.append(row)
        else:
            issue(
                issues,
                "expected_nonempty_string",
                f"{path}[{index}]",
                "array item must be a non-empty string",
            )
    return result


def id_value(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> str:
    if not isinstance(value, str) or not ID_RE.fullmatch(value):
        issue(issues, "invalid_id", path, "value must be a stable lowercase id")
        return ""
    return value


def digest_value(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> str:
    if not isinstance(value, str) or not DIGEST_RE.fullmatch(value):
        issue(issues, "invalid_digest", path, "value must be sha256:<64 lowercase hex>")
        return ""
    return value


def validate_skill_bindings(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> str | None:
    """Validate an ordered binding list and return its deterministic digest."""

    if value is None:
        return None
    if not isinstance(value, list):
        issue(issues, "skill_bindings_invalid", path, "skill_bindings must be an array")
        return None
    for index, binding in enumerate(value):
        item_path = f"{path}[{index}]"
        if not isinstance(binding, dict) or set(binding) != set(SKILL_BINDING_FIELDS):
            issue(issues, "skill_binding_shape_invalid", item_path, "skill binding fields must be exact")
            continue
        for field in ("skill_id", "selection_decision_id"):
            id_value(binding.get(field), f"{item_path}.{field}", issues)
        version = binding.get("version")
        if not isinstance(version, str) or SKILL_VERSION_RE.fullmatch(version) is None:
            issue(issues, "skill_binding_version_invalid", f"{item_path}.version", "binding version must be semantic")
        for field in ("bundle_digest", "selection_decision_digest", "registry_snapshot_digest"):
            digest_value(binding.get(field), f"{item_path}.{field}", issues)
        order = binding.get("load_order")
        if not isinstance(order, int) or isinstance(order, bool) or order != index:
            issue(issues, "skill_binding_order_invalid", f"{item_path}.load_order", "binding load_order must be contiguous from zero")
    digest = skill_bindings_digest_v1(value)
    if digest is None:
        issue(issues, "skill_bindings_digest_unverifiable", path, "skill bindings cannot be deterministically digested")
    return digest


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def normalize_provenance_key(value: bytes | str | None) -> bytes | None:
    if isinstance(value, str):
        key = value.encode("utf-8")
    elif isinstance(value, bytes):
        key = value
    else:
        return None
    return key if len(key) >= MIN_PROVENANCE_KEY_BYTES else None


def provenance_key_id(key: bytes) -> str:
    return "router:key:" + hashlib.sha256(key).hexdigest()[:24]


def expected_hmac_proof(
    subject: str,
    payload: dict[str, Any],
    key: bytes,
) -> str:
    seed = {"subject": subject, "payload": payload}
    return "hmac-sha256:" + hmac.new(
        key,
        canonical_json_bytes(seed),
        hashlib.sha256,
    ).hexdigest()


def parse_time(
    value: Any,
    path: str,
    issues: list[dict[str, str]],
) -> dt.datetime | None:
    if not isinstance(value, str) or not value:
        issue(issues, "invalid_datetime", path, "value must be an RFC 3339 timestamp")
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        issue(issues, "invalid_datetime", path, "value must be an RFC 3339 timestamp")
        return None
    if parsed.tzinfo is None:
        issue(issues, "timezone_required", path, "timestamp must include a timezone")
        return None
    return parsed.astimezone(dt.timezone.utc)


def parse_now(
    value: dt.datetime | str | None,
    issues: list[dict[str, str]],
) -> dt.datetime:
    if isinstance(value, dt.datetime):
        parsed = value
    elif isinstance(value, str):
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            issue(
                issues,
                "invalid_validation_time",
                "$validation.now",
                "validation time must be an RFC 3339 timestamp with a timezone",
            )
            return dt.datetime.max.replace(tzinfo=dt.timezone.utc)
    elif value is None:
        return dt.datetime.now(dt.timezone.utc)
    else:
        issue(
            issues,
            "invalid_validation_time",
            "$validation.now",
            "validation time must be a datetime, RFC 3339 string, or null",
        )
        return dt.datetime.max.replace(tzinfo=dt.timezone.utc)
    if parsed.tzinfo is None:
        issue(
            issues,
            "invalid_validation_time",
            "$validation.now",
            "validation time must include a timezone",
        )
        return dt.datetime.max.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def unique_values(
    values: Iterable[str],
    path: str,
    issues: list[dict[str, str]],
    *,
    code: str = "duplicate_value",
) -> set[str]:
    seen: set[str] = set()
    for value in values:
        if not value:
            continue
        if value in seen:
            issue(issues, code, path, f"duplicate value: {value}")
        seen.add(value)
    return seen


def configured_models(project_root: Path | None) -> set[str] | None:
    if project_root is None:
        return None
    path = project_root / ".agent" / "model_routing_policy.json"
    try:
        policy = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    result: set[str] = set()
    registry = policy.get("model_registry") if isinstance(policy, dict) else None
    if isinstance(registry, dict):
        for row in registry.values():
            if (
                isinstance(row, dict)
                and row.get("enabled") is True
                and isinstance(row.get("model_id"), str)
            ):
                result.add(row["model_id"])
        return result
    roles = policy.get("roles") if isinstance(policy, dict) else None
    if isinstance(roles, dict):
        for row in roles.values():
            if isinstance(row, dict):
                result.update(
                    model
                    for model in row.get("models", [])
                    if isinstance(model, str)
                )
    return result


def validate_authority_guard(
    payload: dict[str, Any],
    issues: list[dict[str, str]],
) -> None:
    guard = object_value(payload.get("authority_guard"), "authority_guard", issues)
    fields = (
        "authority_granted",
        "role_permissions_changed",
        "approval_gates_bypassed",
        "worker_ready_changed",
        "merge_authority_granted",
        "release_authority_granted",
        "recurring_automation_changed",
    )
    for field in fields:
        if guard.get(field) is not False:
            issue(
                issues,
                "authority_escalation_forbidden",
                f"authority_guard.{field}",
                "execution contracts cannot grant or expand authority",
            )


def validate_common(
    payload: dict[str, Any],
    kind: str,
    issues: list[dict[str, str]],
) -> None:
    if payload.get("contract_version") != CONTRACT_VERSION:
        issue(
            issues,
            "unsupported_contract_version",
            "contract_version",
            f"expected {CONTRACT_VERSION}",
        )
    if payload.get("contract_kind") != kind:
        issue(
            issues,
            "contract_kind_mismatch",
            "contract_kind",
            f"expected {kind}",
        )
    id_value(payload.get("correlation_id"), "correlation_id", issues)
    parse_time(payload.get("created_at"), "created_at", issues)
    revision = payload.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        issue(issues, "invalid_revision", "revision", "revision must be an integer >= 1")
        revision = 1
    supersedes = payload.get("supersedes")
    if revision > 1:
        id_value(supersedes, "supersedes", issues)
    elif supersedes is not None:
        issue(
            issues,
            "unexpected_supersedes",
            "supersedes",
            "revision 1 cannot supersede an earlier artifact",
        )
    if payload.get("digest_profile") != "jcs-sha256-v1":
        issue(
            issues,
            "unsupported_digest_profile",
            "digest_profile",
            "digest profile must be jcs-sha256-v1",
        )
    validate_authority_guard(payload, issues)


def compare_binding(
    left: Any,
    right: Any,
    path: str,
    code: str,
    issues: list[dict[str, str]],
) -> None:
    if left != right:
        issue(issues, code, path, "bound values do not match")


def validate_model(
    model: Any,
    path: str,
    models: set[str] | None,
    issues: list[dict[str, str]],
) -> None:
    if not isinstance(model, str) or not model:
        issue(issues, "invalid_model_id", path, "model id must be a non-empty string")
        return
    if models is None:
        issue(
            issues,
            "model_registry_unavailable",
            path,
            "central model registry is unavailable; model validation fails closed",
        )
    elif model not in models:
        issue(
            issues,
            "unknown_model_id",
            path,
            "model id is not configured in the central routing policy",
        )


def validate_invocation(
    payload: dict[str, Any],
    issues: list[dict[str, str]],
    *,
    models: set[str] | None,
    now: dt.datetime,
) -> None:
    plan = object_value(payload.get("plan_ref"), "plan_ref", issues)
    bundle = object_value(payload.get("input_bundle"), "input_bundle", issues)
    route = object_value(payload.get("selected_route"), "selected_route", issues)
    auth = object_value(payload.get("authorization"), "authorization", issues)
    policy = object_value(payload.get("execution_policy"), "execution_policy", issues)
    retry = object_value(payload.get("retry_policy"), "retry_policy", issues)

    id_value(payload.get("invocation_id"), "invocation_id", issues)
    id_value(payload.get("attempt_id"), "attempt_id", issues)
    id_value(payload.get("router_decision_id"), "router_decision_id", issues)
    digest_value(payload.get("router_decision_digest"), "router_decision_digest", issues)
    id_value(plan.get("plan_id"), "plan_ref.plan_id", issues)
    id_value(plan.get("work_unit_id"), "plan_ref.work_unit_id", issues)
    digest_value(plan.get("plan_content_digest"), "plan_ref.plan_content_digest", issues)
    plan_skill_digest = validate_skill_bindings(
        plan.get("skill_bindings"), "plan_ref.skill_bindings", issues
    )
    digest_value(bundle.get("bundle_digest"), "input_bundle.bundle_digest", issues)
    digest_value(route.get("route_digest"), "selected_route.route_digest", issues)
    digest_value(auth.get("grant_digest"), "authorization.grant_digest", issues)
    auth_skill_digest = validate_skill_bindings(
        auth.get("skill_bindings"), "authorization.skill_bindings", issues
    )
    if plan_skill_digest is not None or auth_skill_digest is not None:
        compare_binding(
            plan_skill_digest,
            auth_skill_digest,
            "authorization.skill_bindings",
            "authorization_skill_bindings_mismatch",
            issues,
        )
        compare_binding(
            plan_skill_digest,
            auth.get("skill_bindings_digest"),
            "authorization.skill_bindings_digest",
            "authorization_skill_bindings_digest_mismatch",
            issues,
        )
    digest_value(auth.get("router_decision_digest"), "authorization.router_decision_digest", issues)
    id_value(auth.get("lease_id"), "authorization.lease_id", issues)
    digest_value(auth.get("lease_digest"), "authorization.lease_digest", issues)
    digest_value(payload.get("invocation_digest"), "invocation_digest", issues)

    compare_binding(
        payload.get("router_decision_id"),
        auth.get("router_decision_id"),
        "authorization.router_decision_id",
        "authorization_router_mismatch",
        issues,
    )
    compare_binding(
        payload.get("router_decision_digest"),
        auth.get("router_decision_digest"),
        "authorization.router_decision_digest",
        "authorization_router_digest_mismatch",
        issues,
    )
    if auth.get("issuer") != "model_resource_router":
        issue(
            issues,
            "authorization_issuer_invalid",
            "authorization.issuer",
            "only the central model_resource_router may issue invocation authorization",
        )
    for field, plan_key, auth_key in (
        ("plan id", "plan_id", "plan_id"),
        ("plan digest", "plan_content_digest", "plan_content_digest"),
        ("work unit", "work_unit_id", "work_unit_id"),
    ):
        compare_binding(
            plan.get(plan_key),
            auth.get(auth_key),
            f"authorization.{auth_key}",
            f"authorization_{field.replace(' ', '_')}_mismatch",
            issues,
        )
    compare_binding(
        bundle.get("bundle_digest"),
        auth.get("input_bundle_digest"),
        "authorization.input_bundle_digest",
        "authorization_input_mismatch",
        issues,
    )
    compare_binding(
        route.get("route_digest"),
        auth.get("selected_route_digest"),
        "authorization.selected_route_digest",
        "authorization_route_mismatch",
        issues,
    )

    issued = parse_time(auth.get("issued_at"), "authorization.issued_at", issues)
    expires = parse_time(auth.get("expires_at"), "authorization.expires_at", issues)
    lease_expires = parse_time(
        auth.get("lease_expires_at"),
        "authorization.lease_expires_at",
        issues,
    )
    if issued and expires and issued >= expires:
        issue(
            issues,
            "authorization_window_invalid",
            "authorization.expires_at",
            "authorization must expire after it is issued",
        )
    status = payload.get("status")
    if expires and status in {"authorized", "queued", "running"} and now >= expires:
        issue(
            issues,
            "authorization_expired",
            "authorization.expires_at",
            "authorization is expired for a launchable/running invocation",
        )
    if lease_expires and expires and lease_expires > expires:
        issue(
            issues,
            "lease_window_exceeds_authorization",
            "authorization.lease_expires_at",
            "single-use launch lease cannot outlive Router authorization",
        )
    if lease_expires and status in {"authorized", "queued"} and now >= lease_expires:
        issue(
            issues,
            "launch_lease_expired",
            "authorization.lease_expires_at",
            "unconsumed launch lease is expired",
        )
    if auth.get("status") != "granted":
        issue(
            issues,
            "authorization_not_granted",
            "authorization.status",
            "invocation must reference an explicit Router grant",
        )
    if auth.get("single_use") is not True:
        issue(
            issues,
            "authorization_not_single_use",
            "authorization.single_use",
            "v1 authorization must be single use",
        )
    if auth.get("lease_single_use") is not True:
        issue(
            issues,
            "launch_lease_not_single_use",
            "authorization.lease_single_use",
            "v1 launch lease must be single use",
        )
    consumed = auth.get("consumed")
    lease_consumed = auth.get("lease_consumed")
    if consumed is not lease_consumed:
        issue(
            issues,
            "authorization_lease_consumption_mismatch",
            "authorization.lease_consumed",
            "authorization and launch lease must be consumed atomically",
        )
    if status in {"authorized", "queued"} and consumed is not False:
        issue(
            issues,
            "authorization_replayed",
            "authorization.consumed",
            "unstarted invocation requires unconsumed authorization",
        )
    if status in {"authorized", "queued"} and lease_consumed is not False:
        issue(
            issues,
            "launch_lease_replayed",
            "authorization.lease_consumed",
            "unstarted invocation requires an unconsumed launch lease",
        )
    if status in {"running", *TERMINAL_INVOCATION_STATUSES} and consumed is not True:
        issue(
            issues,
            "authorization_not_consumed",
            "authorization.consumed",
            "started invocation requires consumed authorization evidence",
        )
    if status in {"running", *TERMINAL_INVOCATION_STATUSES} and lease_consumed is not True:
        issue(
            issues,
            "launch_lease_not_consumed",
            "authorization.lease_consumed",
            "started invocation requires consumed launch-lease evidence",
        )
    if consumed is True and lease_consumed is True:
        consumed_at = parse_time(
            auth.get("consumed_at"),
            "authorization.consumed_at",
            issues,
        )
        lease_consumed_at = parse_time(
            auth.get("lease_consumed_at"),
            "authorization.lease_consumed_at",
            issues,
        )
        if consumed_at and lease_consumed_at and consumed_at != lease_consumed_at:
            issue(
                issues,
                "authorization_lease_consumption_time_mismatch",
                "authorization.lease_consumed_at",
                "authorization and launch lease consumption timestamps must match",
            )

    attempt = payload.get("attempt_number")
    max_attempts = auth.get("max_attempts")
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt not in {1, 2}:
        issue(issues, "invalid_attempt_number", "attempt_number", "attempt must be 1 or 2")
    elif isinstance(max_attempts, int) and attempt > max_attempts:
        issue(
            issues,
            "attempt_exceeds_authorization",
            "attempt_number",
            "attempt exceeds Router authorization",
        )
    if attempt == 2:
        id_value(payload.get("retry_of_attempt_id"), "retry_of_attempt_id", issues)
        invariants = object_value(payload.get("retry_invariants"), "retry_invariants", issues)
        for field in (
            "route_unchanged",
            "scope_unchanged",
            "tools_unchanged",
            "input_unchanged",
            "permissions_unchanged",
            "budget_unchanged",
            "timeout_unchanged",
        ):
            if invariants.get(field) is not True:
                issue(
                    issues,
                    "retry_expansion_requires_new_router_decision",
                    f"retry_invariants.{field}",
                    "technical retry must be identical to the authorized attempt",
                )
        compare_binding(
            invariants.get("scope_digest"),
            auth.get("bound_scope_digest"),
            "retry_invariants.scope_digest",
            "retry_scope_digest_mismatch",
            issues,
        )
    elif attempt == 1 and (
        payload.get("retry_of_attempt_id") is not None
        or payload.get("retry_invariants") is not None
    ):
        issue(
            issues,
            "unexpected_retry_metadata",
            "retry_invariants",
            "first attempt cannot carry retry metadata",
        )
    if retry.get("max_technical_retries") not in {0, 1}:
        issue(
            issues,
            "retry_budget_unbounded",
            "retry_policy.max_technical_retries",
            "v1 permits at most one technical retry",
        )

    false_fields = (
        "mutation_allowed",
        "external_side_effects_allowed",
        "child_spawn_allowed",
        "parent_authority_inherited",
        "parent_credentials_inherited",
        "parent_lock_inherited",
    )
    if policy.get("access_mode") != "read_only":
        issue(
            issues,
            "write_capable_child_forbidden",
            "execution_policy.access_mode",
            "direct children are read-only in v1",
        )
    for field in false_fields:
        if policy.get(field) is not False:
            issue(
                issues,
                "child_authority_inheritance_forbidden",
                f"execution_policy.{field}",
                "direct child cannot inherit or gain write/side-effect authority",
            )
    if policy.get("child_depth") != 1 or policy.get("max_child_depth") != 1:
        issue(
            issues,
            "child_depth_exceeded",
            "execution_policy.child_depth",
            "v1 supports one direct-child level only",
        )
    tools = object_list(payload.get("tools"), "tools", issues)
    for index, tool in enumerate(tools):
        if tool.get("mode") != "read_only":
            issue(
                issues,
                "write_tool_forbidden",
                f"tools[{index}].mode",
                "every direct-child tool must be read-only",
            )
    if bundle.get("bounded") is not True:
        issue(issues, "unbounded_context_forbidden", "input_bundle.bounded", "input must be bounded")
    for field in ("unrestricted_parent_context", "secrets_included", "raw_prompts_included"):
        if bundle.get(field) is not False:
            issue(
                issues,
                "sensitive_context_forbidden",
                f"input_bundle.{field}",
                "raw parent context, prompts and secrets are forbidden",
            )
    validate_model(route.get("model_id"), "selected_route.model_id", models, issues)
    effort = route.get("reasoning_effort")
    if effort == "ultra" and (
        route.get("capability_profile") != "delegated_deep"
        or route.get("parallel_lanes", 0) < 2
        or route.get("delegation") not in {"allowed", "required"}
    ):
        issue(
            issues,
            "ultra_without_delegated_units",
            "selected_route",
            "Ultra requires delegated_deep and at least two authorized lanes",
        )
    actual = payload.get("actual_route")
    if isinstance(actual, dict):
        validate_model(actual.get("model_id"), "actual_route.model_id", models, issues)
        if actual.get("route_digest") != route.get("route_digest"):
            issue(
                issues,
                "actual_route_mismatch",
                "actual_route.route_digest",
                "actual route differs from the selected route and blocks normal integration",
            )


def topological_order(
    unit_ids: set[str],
    order_rank: dict[str, tuple[int, str]],
    edges: list[tuple[str, str]],
) -> tuple[list[str], bool]:
    incoming = {unit_id: 0 for unit_id in unit_ids}
    outgoing = {unit_id: [] for unit_id in unit_ids}
    for source, target in edges:
        if source in unit_ids and target in unit_ids:
            outgoing[source].append(target)
            incoming[target] += 1
    ready = sorted(
        (unit_id for unit_id, count in incoming.items() if count == 0),
        key=lambda value: order_rank[value],
    )
    result: list[str] = []
    while ready:
        current = ready.pop(0)
        result.append(current)
        for target in sorted(outgoing[current], key=lambda value: order_rank[value]):
            incoming[target] -= 1
            if incoming[target] == 0:
                ready.append(target)
                ready.sort(key=lambda value: order_rank[value])
    return result, len(result) != len(unit_ids)


def reachable(
    source: str,
    target: str,
    adjacency: dict[str, set[str]],
) -> bool:
    pending = [source]
    seen: set[str] = set()
    while pending:
        current = pending.pop()
        if current == target:
            return True
        if current in seen:
            continue
        seen.add(current)
        pending.extend(adjacency.get(current, set()) - seen)
    return False


def validate_plan_authorization_v1(
    payload: dict[str, Any],
    auth: dict[str, Any],
    capacity: dict[str, Any],
    units: list[dict[str, Any]],
    issues: list[dict[str, str]],
    *,
    provenance_key: bytes | str | None = None,
) -> None:
    """Validate an immutable Router-issued aggregate plan grant.

    This verifies evidence only. It does not consume the grant, create an
    invocation lease, schedule a lane or start execution.
    """

    required_fields = (
        "authorization_version",
        "issuer",
        "router_decision_digest",
        "router_decision_provenance_digest",
        "router_provenance_key_id",
        "selected_route_digest",
        "work_unit_scope_digest",
        "input_scope_digest",
        "bound_scope_digest",
        "digest_profile",
        "immutable",
        "max_attempts_per_unit",
        "invocation_grants_single_use",
        "capacity_envelope",
        "authorization_proof",
    )
    for field in required_fields:
        if field not in auth:
            issue(
                issues,
                "authorization_v1_field_missing",
                f"router_authorization.{field}",
                "versioned Router authorization is missing a required field",
            )

    if auth.get("authorization_version") != "1.0.0":
        issue(
            issues,
            "unsupported_authorization_version",
            "router_authorization.authorization_version",
            "supported plan authorization version is 1.0.0",
        )
    if auth.get("status") != "granted":
        issue(
            issues,
            "authorization_not_granted",
            "router_authorization.status",
            "plan authorization must be an explicit Router grant",
        )
    if auth.get("issuer") != "model_resource_router":
        issue(
            issues,
            "authorization_issuer_invalid",
            "router_authorization.issuer",
            "only the existing central model_resource_router may issue this grant",
        )
    if auth.get("digest_profile") != "jcs-sha256-v1":
        issue(
            issues,
            "authorization_digest_profile_invalid",
            "router_authorization.digest_profile",
            "authorization digests must use jcs-sha256-v1",
        )
    if auth.get("immutable") is not True:
        issue(
            issues,
            "authorization_not_immutable",
            "router_authorization.immutable",
            "aggregate Router authorization must be immutable",
        )
    if auth.get("invocation_grants_single_use") is not True:
        issue(
            issues,
            "invocation_grants_not_single_use",
            "router_authorization.invocation_grants_single_use",
            "later per-invocation grants must be single use",
        )

    id_value(auth.get("authorization_id"), "router_authorization.authorization_id", issues)
    for field in (
        "router_decision_digest",
        "router_decision_provenance_digest",
        "selected_route_digest",
        "plan_content_digest",
        "work_unit_scope_digest",
        "input_scope_digest",
        "bound_scope_digest",
        "budget_digest",
        "grant_digest",
    ):
        digest_value(auth.get(field), f"router_authorization.{field}", issues)

    binding_fields = (
        "router_decision_digest",
        "router_decision_provenance_digest",
        "selected_route_digest",
        "work_unit_scope_digest",
        "input_scope_digest",
    )
    for field in binding_fields:
        if field not in payload:
            issue(
                issues,
                "authorization_binding_reference_missing",
                field,
                "versioned plan authorization requires the bound reference on the plan",
            )
        else:
            digest_value(payload.get(field), field, issues)
            compare_binding(
                payload.get(field),
                auth.get(field),
                f"router_authorization.{field}",
                f"authorization_{field}_mismatch",
                issues,
            )

    id_value(
        auth.get("router_provenance_key_id"),
        "router_authorization.router_provenance_key_id",
        issues,
    )
    if "router_provenance_key_id" not in payload:
        issue(
            issues,
            "authorization_binding_reference_missing",
            "router_provenance_key_id",
            "versioned plan authorization requires the Router provenance key id",
        )
    else:
        id_value(payload.get("router_provenance_key_id"), "router_provenance_key_id", issues)
        compare_binding(
            payload.get("router_provenance_key_id"),
            auth.get("router_provenance_key_id"),
            "router_authorization.router_provenance_key_id",
            "authorization_router_provenance_key_mismatch",
            issues,
        )

    expected_plan_digest = compute_plan_content_digest_v1(payload)
    if expected_plan_digest is None:
        issue(
            issues,
            "authorization_plan_content_unverifiable",
            "plan_content_digest",
            "versioned authorization requires a complete execution-defining plan projection",
        )
    else:
        compare_binding(
            expected_plan_digest,
            payload.get("plan_content_digest"),
            "plan_content_digest",
            "plan_content_digest_mismatch",
            issues,
        )
        compare_binding(
            expected_plan_digest,
            auth.get("plan_content_digest"),
            "router_authorization.plan_content_digest",
            "authorization_plan_content_digest_mismatch",
            issues,
        )

    for index, unit in enumerate(units):
        digest_value(
            unit.get("input_bundle_digest"),
            f"work_units[{index}].input_bundle_digest",
            issues,
        )
    expected_input_scope = compute_input_scope_digest_v1(payload)
    if expected_input_scope is None:
        issue(
            issues,
            "authorization_input_scope_unverifiable",
            "work_units",
            "versioned authorization requires exact per-unit input bundle digests",
        )
    else:
        compare_binding(
            expected_input_scope,
            payload.get("input_scope_digest"),
            "input_scope_digest",
            "input_scope_digest_mismatch",
            issues,
        )
        compare_binding(
            expected_input_scope,
            auth.get("input_scope_digest"),
            "router_authorization.input_scope_digest",
            "authorization_input_scope_digest_mismatch",
            issues,
        )

    work_unit_ids = sorted(
        unit.get("work_unit_id")
        for unit in units
        if isinstance(unit.get("work_unit_id"), str)
        and ID_RE.fullmatch(unit["work_unit_id"])
    )
    expected_work_scope = canonical_digest(work_unit_ids)
    compare_binding(
        expected_work_scope,
        auth.get("work_unit_scope_digest"),
        "router_authorization.work_unit_scope_digest",
        "authorization_work_unit_scope_mismatch",
        issues,
    )

    skill_binding_rows = sorted(
        [
        {
            "work_unit_id": unit.get("work_unit_id"),
            "skill_bindings": unit.get("skill_bindings", []),
        }
        for unit in units
        ],
        key=lambda row: str(row.get("work_unit_id") or ""),
    )
    expected_skill_bindings_digest = canonical_digest(skill_binding_rows)
    if "skill_bindings_digest" in auth or "skill_bindings" in auth:
        compare_binding(
            expected_skill_bindings_digest,
            auth.get("skill_bindings_digest"),
            "router_authorization.skill_bindings_digest",
            "authorization_skill_bindings_digest_mismatch",
            issues,
        )
        grant_rows = object_list(
            auth.get("skill_bindings"), "router_authorization.skill_bindings", issues
        )
        expected_row_ids = [row.get("work_unit_id") for row in skill_binding_rows]
        actual_row_ids = [row.get("work_unit_id") for row in grant_rows]
        if actual_row_ids != expected_row_ids:
            issue(
                issues,
                "authorization_skill_bindings_scope_mismatch",
                "router_authorization.skill_bindings",
                "Router grant must echo bindings for every work unit in plan order",
            )
        for index, row in enumerate(grant_rows):
            validate_skill_bindings(
                row.get("skill_bindings"),
                f"router_authorization.skill_bindings[{index}].skill_bindings",
                issues,
            )
            if index < len(skill_binding_rows):
                compare_binding(
                    skill_binding_rows[index].get("skill_bindings"),
                    row.get("skill_bindings"),
                    f"router_authorization.skill_bindings[{index}].skill_bindings",
                    "authorization_skill_bindings_mismatch",
                    issues,
                )

    envelope = object_value(
        auth.get("capacity_envelope"),
        "router_authorization.capacity_envelope",
        issues,
    )
    envelope_bounds = {
        "router_authorized_lanes": (2, 64),
        "plan_requested_lanes": (2, 64),
        "plan_work_units": (2, 32),
        "project_policy_ceiling": (1, 64),
        "worker_policy_ceiling": (1, 64),
        "runtime_capacity_ceiling": (1, 64),
        "authorized_lanes": (2, 64),
        "max_attempts_per_unit": (1, 2),
        "max_total_invocations": (2, 64),
    }
    envelope_is_numeric = True
    for field, (minimum, maximum) in envelope_bounds.items():
        value = envelope.get(field)
        if (
            not isinstance(value, int)
            or isinstance(value, bool)
            or not minimum <= value <= maximum
        ):
            envelope_is_numeric = False
            issue(
                issues,
                "authorization_capacity_envelope_invalid",
                f"router_authorization.capacity_envelope.{field}",
                f"value must be an integer between {minimum} and {maximum}",
            )
    digest_value(
        envelope.get("budget_digest"),
        "router_authorization.capacity_envelope.budget_digest",
        issues,
    )

    if envelope_is_numeric:
        expected_lanes = min(
            envelope["router_authorized_lanes"],
            envelope["plan_requested_lanes"],
            envelope["plan_work_units"],
            envelope["project_policy_ceiling"],
            envelope["worker_policy_ceiling"],
            envelope["runtime_capacity_ceiling"],
        )
        if expected_lanes < 2 or envelope["authorized_lanes"] != expected_lanes:
            issue(
                issues,
                "authorization_capacity_derivation_mismatch",
                "router_authorization.capacity_envelope.authorized_lanes",
                "authorized lanes must be the exact minimum of every declared ceiling and at least two",
            )
        expected_invocations = (
            envelope["plan_work_units"] * envelope["max_attempts_per_unit"]
        )
        if envelope["max_total_invocations"] != expected_invocations:
            issue(
                issues,
                "authorization_invocation_budget_derivation_mismatch",
                "router_authorization.capacity_envelope.max_total_invocations",
                "total invocation budget must equal work units multiplied by attempts per unit",
            )
        for left, right, path, code in (
            (
                capacity.get("requested_lanes"),
                envelope["plan_requested_lanes"],
                "capacity.requested_lanes",
                "authorization_requested_capacity_mismatch",
            ),
            (
                len(units),
                envelope["plan_work_units"],
                "router_authorization.capacity_envelope.plan_work_units",
                "authorization_work_unit_count_mismatch",
            ),
            (
                capacity.get("authorized_lanes"),
                envelope["authorized_lanes"],
                "capacity.authorized_lanes",
                "authorization_capacity_mismatch",
            ),
            (
                capacity.get("max_attempts_per_unit"),
                envelope["max_attempts_per_unit"],
                "capacity.max_attempts_per_unit",
                "authorization_attempt_budget_mismatch",
            ),
            (
                capacity.get("max_total_invocations"),
                envelope["max_total_invocations"],
                "capacity.max_total_invocations",
                "authorization_invocation_budget_mismatch",
            ),
            (
                auth.get("authorized_lanes"),
                envelope["authorized_lanes"],
                "router_authorization.authorized_lanes",
                "authorization_capacity_mismatch",
            ),
            (
                auth.get("max_attempts_per_unit"),
                envelope["max_attempts_per_unit"],
                "router_authorization.max_attempts_per_unit",
                "authorization_attempt_budget_mismatch",
            ),
            (
                auth.get("max_total_invocations"),
                envelope["max_total_invocations"],
                "router_authorization.max_total_invocations",
                "authorization_invocation_budget_mismatch",
            ),
        ):
            compare_binding(left, right, path, code, issues)

    budget_seed = {
        key: value
        for key, value in envelope.items()
        if key != "budget_digest"
    }
    expected_budget_digest = canonical_digest(budget_seed)
    for value, path, code in (
        (
            envelope.get("budget_digest"),
            "router_authorization.capacity_envelope.budget_digest",
            "authorization_budget_digest_mismatch",
        ),
        (
            auth.get("budget_digest"),
            "router_authorization.budget_digest",
            "authorization_budget_digest_mismatch",
        ),
        (
            capacity.get("budget_digest"),
            "capacity.budget_digest",
            "authorization_budget_mismatch",
        ),
    ):
        compare_binding(expected_budget_digest, value, path, code, issues)

    scope_seed = {
        "router_decision_id": auth.get("router_decision_id"),
        "router_decision_digest": auth.get("router_decision_digest"),
        "router_decision_provenance_digest": auth.get(
            "router_decision_provenance_digest"
        ),
        "router_provenance_key_id": auth.get("router_provenance_key_id"),
        "selected_route_digest": auth.get("selected_route_digest"),
        "plan_id": auth.get("plan_id"),
        "plan_content_digest": auth.get("plan_content_digest"),
        "work_unit_scope_digest": auth.get("work_unit_scope_digest"),
        "input_scope_digest": auth.get("input_scope_digest"),
    }
    if "skill_bindings_digest" in auth:
        scope_seed["skill_bindings_digest"] = auth.get("skill_bindings_digest")
    compare_binding(
        canonical_digest(scope_seed),
        auth.get("bound_scope_digest"),
        "router_authorization.bound_scope_digest",
        "authorization_bound_scope_digest_mismatch",
        issues,
    )
    authorization_seed = {
        "bound_scope_digest": auth.get("bound_scope_digest"),
        "budget_digest": auth.get("budget_digest"),
        "issued_at": auth.get("issued_at"),
        "expires_at": auth.get("expires_at"),
    }
    expected_authorization_id = (
        "router:authorization:"
        + canonical_digest(authorization_seed).split(":", 1)[1][:24]
    )
    compare_binding(
        expected_authorization_id,
        auth.get("authorization_id"),
        "router_authorization.authorization_id",
        "authorization_id_digest_mismatch",
        issues,
    )
    unsigned_grant = dict(auth)
    unsigned_grant.pop("authorization_proof", None)
    unsigned_grant.pop("grant_digest", None)
    compare_binding(
        canonical_digest(unsigned_grant),
        auth.get("grant_digest"),
        "router_authorization.grant_digest",
        "authorization_grant_digest_mismatch",
        issues,
    )

    proof = object_value(
        auth.get("authorization_proof"),
        "router_authorization.authorization_proof",
        issues,
    )
    expected_proof_fields = {
        "proof_version",
        "issuer",
        "algorithm",
        "subject",
        "key_id",
        "proof",
    }
    if set(proof) != expected_proof_fields:
        issue(
            issues,
            "authorization_proof_shape_invalid",
            "router_authorization.authorization_proof",
            "authorization proof must contain the exact HMAC provenance fields",
        )
    for field, expected in (
        ("proof_version", "1.0.0"),
        ("issuer", "model_resource_router"),
        ("algorithm", "hmac-sha256"),
        ("subject", "delegation_authorization"),
    ):
        if proof.get(field) != expected:
            issue(
                issues,
                "authorization_proof_metadata_invalid",
                f"router_authorization.authorization_proof.{field}",
                f"expected {expected}",
            )
    compare_binding(
        auth.get("router_provenance_key_id"),
        proof.get("key_id"),
        "router_authorization.authorization_proof.key_id",
        "authorization_proof_key_mismatch",
        issues,
    )
    proof_value = proof.get("proof")
    if not isinstance(proof_value, str) or HMAC_PROOF_RE.fullmatch(proof_value) is None:
        issue(
            issues,
            "authorization_proof_invalid",
            "router_authorization.authorization_proof.proof",
            "proof must be hmac-sha256:<64 lowercase hex>",
        )
    key = normalize_provenance_key(provenance_key)
    if provenance_key is None:
        issue(
            issues,
            "authorization_provenance_key_required",
            "$validation.provenance_key",
            "versioned Router authorization requires trusted-key HMAC verification",
        )
    elif key is None:
        issue(
            issues,
            "authorization_provenance_key_invalid",
            "$validation.provenance_key",
            "provenance verifier key must contain at least 32 bytes",
        )
    if key is not None:
        compare_binding(
            provenance_key_id(key),
            proof.get("key_id"),
            "router_authorization.authorization_proof.key_id",
            "authorization_proof_key_mismatch",
            issues,
        )
        proof_payload = {
            "authorization_id": auth.get("authorization_id"),
            "grant_digest": auth.get("grant_digest"),
            "router_decision_provenance_digest": auth.get(
                "router_decision_provenance_digest"
            ),
        }
        expected_proof = expected_hmac_proof(
            "delegation_authorization",
            proof_payload,
            key,
        )
        if not isinstance(proof_value, str) or not hmac.compare_digest(
            proof_value,
            expected_proof,
        ):
            issue(
                issues,
                "authorization_proof_mismatch",
                "router_authorization.authorization_proof.proof",
                "authorization proof does not verify against the trusted Router key",
            )


def validate_parallel(
    payload: dict[str, Any],
    issues: list[dict[str, str]],
    warnings: list[dict[str, str]],
    *,
    now: dt.datetime,
    provenance_key: bytes | str | None = None,
) -> bool:
    eligible_for_required_critical_lane = True
    plan_id = id_value(payload.get("plan_id"), "plan_id", issues)
    plan_digest = digest_value(payload.get("plan_content_digest"), "plan_content_digest", issues)
    units = object_list(payload.get("work_units"), "work_units", issues)
    dependencies = object_list(payload.get("dependencies"), "dependencies", issues)
    barriers = object_list(payload.get("barriers"), "barriers", issues)
    capacity = object_value(payload.get("capacity"), "capacity", issues)
    raw_status = payload.get("status")
    if not isinstance(raw_status, str) or raw_status not in PARALLEL_STATUSES:
        issue(
            issues,
            "invalid_parallel_status",
            "status",
            "Parallel Work status must be one of the versioned lifecycle states",
        )
        status: str | None = None
    else:
        status = raw_status
    if status == "validated":
        digest_value(
            payload.get("router_decision_digest"),
            "router_decision_digest",
            issues,
        )

    started = (
        parse_time(payload.get("started_at"), "started_at", issues)
        if "started_at" in payload
        else None
    )
    completed = (
        parse_time(payload.get("completed_at"), "completed_at", issues)
        if "completed_at" in payload
        else None
    )
    if started and completed and completed < started:
        issue(
            issues,
            "parallel_execution_time_order_invalid",
            "completed_at",
            "completed_at cannot predate started_at",
        )
    if status in {"running", "awaiting_integration", "completed"} and "started_at" not in payload:
        issue(
            issues,
            "parallel_started_at_required",
            "started_at",
            "this lifecycle state requires a start timestamp",
        )
    if status in {"awaiting_integration", "completed", "blocked", "cancelled"} and "completed_at" not in payload:
        issue(
            issues,
            "parallel_completed_at_required",
            "completed_at",
            "this lifecycle state requires a completion timestamp",
        )
    if status in {"proposed", "validated", "authorized"}:
        for field in ("started_at", "completed_at", "integration_ref"):
            if field in payload:
                issue(
                    issues,
                    "parallel_lifecycle_field_unexpected",
                    field,
                    f"{status} state cannot carry {field}",
                )
    elif status == "running":
        for field in ("completed_at", "integration_ref"):
            if field in payload:
                issue(
                    issues,
                    "parallel_lifecycle_field_unexpected",
                    field,
                    f"running state cannot carry {field}",
                )
    elif status in {"awaiting_integration", "blocked", "cancelled"}:
        if "integration_ref" in payload:
            issue(
                issues,
                "parallel_lifecycle_field_unexpected",
                "integration_ref",
                f"{status} state cannot carry integration_ref",
            )
    elif status == "completed" and "integration_ref" not in payload:
        issue(
            issues,
            "parallel_integration_ref_required",
            "integration_ref",
            "completed state requires exact integration evidence",
        )
    if status in {"blocked", "cancelled"} and not isinstance(payload.get("terminal_reason"), dict):
        issue(
            issues,
            "parallel_terminal_reason_required",
            "terminal_reason",
            "terminal blocked/cancelled state requires a structured reason",
        )

    unit_ids = [
        id_value(unit.get("work_unit_id"), f"work_units[{index}].work_unit_id", issues)
        for index, unit in enumerate(units)
    ]
    unit_set = unique_values(unit_ids, "work_units", issues, code="duplicate_work_unit_id")
    order_values: list[int] = []
    order_rank: dict[str, tuple[int, str]] = {}
    required_units: set[str] = set()
    resource_keys: dict[str, set[str]] = {}
    for index, unit in enumerate(units):
        unit_id = unit_ids[index]
        order = unit.get("order")
        if not isinstance(order, int) or isinstance(order, bool) or order < 0:
            issue(issues, "invalid_work_unit_order", f"work_units[{index}].order", "order must be >= 0")
            order = index
        order_values.append(order)
        if unit_id:
            order_rank[unit_id] = (order, unit_id)
        if unit.get("criticality") == "required" and unit_id:
            required_units.add(unit_id)
        if unit.get("access_mode") != "read_only" or unit.get("mutation_allowed") is not False:
            issue(
                issues,
                "write_capable_work_unit_forbidden",
                f"work_units[{index}]",
                "ephemeral v1 work units must be read-only",
            )
        if status == "validated":
            digest_value(
                unit.get("input_bundle_digest"),
                f"work_units[{index}].input_bundle_digest",
                issues,
            )
        resources = object_value(
            unit.get("resources"),
            f"work_units[{index}].resources",
            issues,
        )
        keys = set(string_list(resources.get("exclusive_keys"), f"work_units[{index}].resources.exclusive_keys", issues))
        for key in keys:
            if any(marker in key for marker in ("*", "?", "[", "]")):
                issue(
                    issues,
                    "broad_exclusive_resource_forbidden",
                    f"work_units[{index}].resources.exclusive_keys",
                    "wildcards are not proof of safe parallel resource ownership",
                )
        if unit_id:
            resource_keys[unit_id] = keys
    unique_values((str(value) for value in order_values), "work_units.order", issues, code="duplicate_work_unit_order")

    edges: list[tuple[str, str]] = []
    edge_keys: set[tuple[str, str, str]] = set()
    adjacency = {unit_id: set() for unit_id in unit_set}
    for index, dependency in enumerate(dependencies):
        source = id_value(dependency.get("from"), f"dependencies[{index}].from", issues)
        target = id_value(dependency.get("to"), f"dependencies[{index}].to", issues)
        dep_type = dependency.get("type")
        if source not in unit_set:
            issue(issues, "unknown_dependency_source", f"dependencies[{index}].from", "source work unit is unknown")
        if target not in unit_set:
            issue(issues, "unknown_dependency_target", f"dependencies[{index}].to", "target work unit is unknown")
        if source and source == target:
            issue(issues, "self_dependency", f"dependencies[{index}]", "work unit cannot depend on itself")
        key = (source, target, str(dep_type))
        if key in edge_keys:
            issue(issues, "duplicate_dependency", f"dependencies[{index}]", "dependency is duplicated")
        edge_keys.add(key)
        if source in unit_set and target in unit_set and source != target:
            edges.append((source, target))
            adjacency[source].add(target)
    computed_order, cyclic = topological_order(unit_set, order_rank, edges)
    if cyclic:
        issue(issues, "cyclic_dependency_graph", "dependencies", "parallel work graph must be acyclic")
    declared_order = string_list(payload.get("deterministic_ready_order"), "deterministic_ready_order", issues)
    if declared_order != computed_order:
        issue(
            issues,
            "deterministic_order_mismatch",
            "deterministic_ready_order",
            "declared ready order does not match deterministic topological order",
        )

    for left_index, left in enumerate(unit_ids):
        if not left:
            continue
        for right in unit_ids[left_index + 1 :]:
            if not right:
                continue
            shared = resource_keys.get(left, set()) & resource_keys.get(right, set())
            if shared and not (
                reachable(left, right, adjacency)
                or reachable(right, left, adjacency)
            ):
                issue(
                    issues,
                    "parallel_exclusive_resource_conflict",
                    "work_units.resources.exclusive_keys",
                    f"{left} and {right} can run concurrently but share {sorted(shared)}",
                )

    for index, barrier in enumerate(barriers):
        members = set(string_list(barrier.get("members"), f"barriers[{index}].members", issues))
        required = set(string_list(barrier.get("required_members"), f"barriers[{index}].required_members", issues))
        unknown = (members | required) - unit_set
        if unknown:
            issue(
                issues,
                "unknown_barrier_member",
                f"barriers[{index}]",
                f"barrier references unknown units: {sorted(unknown)}",
            )
        if not required <= members:
            issue(
                issues,
                "barrier_required_member_missing",
                f"barriers[{index}].required_members",
                "required barrier members must also be members",
            )
        if barrier.get("bypass_allowed") is not False:
            issue(
                issues,
                "required_barrier_bypass_forbidden",
                f"barriers[{index}].bypass_allowed",
                "required barriers cannot be bypassed",
            )

    gate = object_value(payload.get("integration_gate"), "integration_gate", issues)
    gate_required = set(string_list(gate.get("required_work_unit_ids"), "integration_gate.required_work_unit_ids", issues))
    if gate_required != required_units:
        issue(
            issues,
            "required_integration_gate_mismatch",
            "integration_gate.required_work_unit_ids",
            "integration gate must list every and only required work unit",
        )
    if payload.get("progress_denominator") != len(units):
        issue(
            issues,
            "progress_denominator_mismatch",
            "progress_denominator",
            "progress denominator must equal the declared work-unit count",
        )

    requested = capacity.get("requested_lanes")
    authorized = capacity.get("authorized_lanes")
    effective = capacity.get("effective_lanes")
    max_invocations = capacity.get("max_total_invocations")
    if not all(isinstance(value, int) and not isinstance(value, bool) for value in (requested, authorized, effective, max_invocations)):
        issue(issues, "invalid_capacity", "capacity", "capacity fields must be integers")
    else:
        if not (1 <= effective <= authorized <= requested):
            issue(
                issues,
                "capacity_ceiling_exceeded",
                "capacity",
                "effective <= authorized <= requested lanes is required",
            )
        if effective > len(units) or requested > len(units):
            issue(
                issues,
                "capacity_exceeds_work_units",
                "capacity",
                "lane capacity cannot exceed the work-unit count",
            )
        if max_invocations < len(units):
            issue(
                issues,
                "invocation_budget_too_small",
                "capacity.max_total_invocations",
                "budget must cover every planned work unit",
            )

    completion = object_value(payload.get("completion_policy"), "completion_policy", issues)
    if completion.get("quorum_allowed") is not False or completion.get("minimum_success_allowed") is not False:
        issue(
            issues,
            "quorum_completion_forbidden",
            "completion_policy",
            "planned lanes cannot disappear behind quorum/minimum-success completion",
        )

    auth = payload.get("router_authorization")
    active_statuses = {"authorized", "running", "awaiting_integration", "completed"}
    terminal_statuses = {"blocked", "cancelled"}
    if status in active_statuses or (status in terminal_statuses and auth is not None):
        auth_obj = object_value(auth, "router_authorization", issues)
        compare_binding(plan_id, auth_obj.get("plan_id"), "router_authorization.plan_id", "authorization_plan_mismatch", issues)
        compare_binding(plan_digest, auth_obj.get("plan_content_digest"), "router_authorization.plan_content_digest", "authorization_plan_digest_mismatch", issues)
        compare_binding(payload.get("router_decision_id"), auth_obj.get("router_decision_id"), "router_authorization.router_decision_id", "authorization_router_mismatch", issues)
        compare_binding(authorized, auth_obj.get("authorized_lanes"), "router_authorization.authorized_lanes", "authorization_capacity_mismatch", issues)
        compare_binding(max_invocations, auth_obj.get("max_total_invocations"), "router_authorization.max_total_invocations", "authorization_invocation_budget_mismatch", issues)
        compare_binding(capacity.get("budget_digest"), auth_obj.get("budget_digest"), "router_authorization.budget_digest", "authorization_budget_mismatch", issues)
        issued = parse_time(auth_obj.get("issued_at"), "router_authorization.issued_at", issues)
        expires = parse_time(auth_obj.get("expires_at"), "router_authorization.expires_at", issues)
        if issued and expires and issued >= expires:
            issue(issues, "authorization_window_invalid", "router_authorization.expires_at", "authorization must expire after issue")
        if issued and now < issued:
            issue(
                issues,
                "authorization_not_yet_valid",
                "router_authorization.issued_at",
                "plan authorization evidence cannot be used before its issue time",
            )
        if (
            "authorization_version" in auth_obj
            and issued
            and expires
            and (expires - issued).total_seconds() > 3600
        ):
            issue(
                issues,
                "authorization_window_too_long",
                "router_authorization.expires_at",
                "versioned plan authorization may live for at most 3600 seconds",
            )
        if expires and status in {"authorized", "running"} and now >= expires:
            issue(issues, "authorization_expired", "router_authorization.expires_at", "active plan authorization is expired")
        if "authorization_version" in auth_obj:
            validate_plan_authorization_v1(
                payload,
                auth_obj,
                capacity,
                units,
                issues,
                provenance_key=provenance_key,
            )
        else:
            partial_v1_fields = sorted(
                AUTHORIZATION_V1_ONLY_FIELDS.intersection(auth_obj)
            )
            if partial_v1_fields:
                eligible_for_required_critical_lane = False
                issue(
                    issues,
                    "legacy_authorization_v1_fields_forbidden",
                    "router_authorization",
                    "unversioned legacy evidence cannot carry v1-only fields: "
                    + ", ".join(partial_v1_fields),
                )
        if "authorization_version" not in auth_obj and status in {"authorized", "running"}:
            eligible_for_required_critical_lane = False
            issue(
                issues,
                "authenticated_router_authorization_required",
                "router_authorization.authorization_version",
                "active execution requires a fully authenticated Router authorization v1 grant",
            )
        elif (
            "authorization_version" not in auth_obj
            and isinstance(auth, dict)
            and not AUTHORIZATION_V1_ONLY_FIELDS.intersection(auth_obj)
        ):
            eligible_for_required_critical_lane = False
            issue(
                warnings,
                "legacy_router_authorization_unverified",
                "router_authorization",
                "legacy authorization remains readable only as historical audit evidence and cannot resume or satisfy a required critical lane",
                severity="warning",
            )
    elif auth is not None:
        issue(
            issues,
            "premature_router_authorization",
            "router_authorization",
            "proposed/validated/terminal pre-authorization state cannot carry a live grant",
        )

    revision = payload.get("revision")
    if isinstance(revision, int) and revision > 1:
        change = object_value(payload.get("revision_change"), "revision_change", issues)
        if (
            payload.get("status") in {"running", "awaiting_integration", "completed"}
            and change.get("progress_denominator_changed") is True
            and change.get("approved_before_execution") is not True
        ):
            issue(
                issues,
                "active_progress_denominator_changed",
                "revision_change",
                "active progress denominator cannot change silently",
            )

    return eligible_for_required_critical_lane


def validate_result(
    payload: dict[str, Any],
    issues: list[dict[str, str]],
    *,
    models: set[str] | None,
) -> None:
    id_value(payload.get("result_id"), "result_id", issues)
    id_value(payload.get("invocation_id"), "invocation_id", issues)
    id_value(payload.get("attempt_id"), "attempt_id", issues)
    id_value(payload.get("plan_id"), "plan_id", issues)
    id_value(payload.get("work_unit_id"), "work_unit_id", issues)
    result_skill_digest = validate_skill_bindings(
        payload.get("skill_bindings"), "skill_bindings", issues
    )
    if payload.get("skill_bindings") is not None:
        compare_binding(
            result_skill_digest,
            payload.get("skill_bindings_digest"),
            "skill_bindings_digest",
            "result_skill_bindings_digest_mismatch",
            issues,
        )
    for field in (
        "plan_content_digest",
        "input_bundle_digest",
        "base_snapshot_digest",
        "selected_route_digest",
        "payload_digest",
        "result_envelope_digest",
    ):
        digest_value(payload.get(field), field, issues)
    started = parse_time(payload.get("started_at"), "started_at", issues)
    completed = parse_time(payload.get("completed_at"), "completed_at", issues)
    if started and completed and completed < started:
        issue(issues, "result_time_reversed", "completed_at", "result cannot complete before start")

    actual = object_value(payload.get("actual_use"), "actual_use", issues)
    validate_model(actual.get("model_id"), "actual_use.model_id", models, issues)
    if actual.get("delegation_used") is not False:
        issue(
            issues,
            "child_self_delegation_forbidden",
            "actual_use.delegation_used",
            "direct child cannot delegate or spawn another child",
        )
    digest_value(actual.get("actual_route_digest"), "actual_use.actual_route_digest", issues)
    reconciliation = object_value(payload.get("route_reconciliation"), "route_reconciliation", issues)
    selected = payload.get("selected_route_digest")
    used = actual.get("actual_route_digest")
    expected_status = "matched" if selected == used else "mismatch"
    if reconciliation.get("status") != expected_status:
        issue(
            issues,
            "route_reconciliation_mismatch",
            "route_reconciliation.status",
            "route reconciliation does not match selected versus actual route",
        )
    if expected_status == "mismatch" and not reconciliation.get("reason_code"):
        issue(
            issues,
            "route_mismatch_reason_required",
            "route_reconciliation.reason_code",
            "actual-route mismatch requires an auditable reason",
        )

    evidence = object_list(payload.get("evidence"), "evidence", issues)
    evidence_ids = [
        id_value(row.get("evidence_id"), f"evidence[{index}].evidence_id", issues)
        for index, row in enumerate(evidence)
    ]
    evidence_set = unique_values(evidence_ids, "evidence", issues, code="duplicate_evidence_id")
    outcome = payload.get("outcome")
    status = payload.get("status")
    claims: list[dict[str, Any]] = []
    claim_ids: list[str] = []
    if isinstance(outcome, dict):
        claims = object_list(outcome.get("claims"), "outcome.claims", issues)
        claim_ids = [
            id_value(row.get("claim_id"), f"outcome.claims[{index}].claim_id", issues)
            for index, row in enumerate(claims)
        ]
        claim_set = unique_values(claim_ids, "outcome.claims", issues, code="duplicate_claim_id")
        for index, claim in enumerate(claims):
            refs = set(string_list(claim.get("evidence_refs"), f"outcome.claims[{index}].evidence_refs", issues))
            unknown = refs - evidence_set
            if unknown:
                issue(
                    issues,
                    "claim_unknown_evidence",
                    f"outcome.claims[{index}].evidence_refs",
                    f"claim references unknown evidence: {sorted(unknown)}",
                )
            if claim.get("support_status") in {"supported", "contradicted"} and not refs:
                issue(
                    issues,
                    "claim_evidence_required",
                    f"outcome.claims[{index}]",
                    "supported/contradicted claim needs evidence",
                )
        acceptance = object_list(outcome.get("acceptance_evidence"), "outcome.acceptance_evidence", issues)
        for index, row in enumerate(acceptance):
            refs = set(string_list(row.get("evidence_refs"), f"outcome.acceptance_evidence[{index}].evidence_refs", issues))
            unknown = refs - evidence_set
            if unknown:
                issue(
                    issues,
                    "acceptance_unknown_evidence",
                    f"outcome.acceptance_evidence[{index}].evidence_refs",
                    f"acceptance references unknown evidence: {sorted(unknown)}",
                )
        unmet = list_value(outcome.get("unmet_requirements"), "outcome.unmet_requirements", issues)
        if status == "success":
            if any(row.get("status") != "met" for row in acceptance):
                issue(
                    issues,
                    "success_with_unmet_acceptance",
                    "outcome.acceptance_evidence",
                    "successful result requires every criterion to be met",
                )
            if unmet:
                issue(
                    issues,
                    "success_with_unmet_requirements",
                    "outcome.unmet_requirements",
                    "successful result cannot retain unmet requirements",
                )
            if not claims:
                issue(
                    issues,
                    "success_without_claims",
                    "outcome.claims",
                    "successful result must include findings/claims evidence",
                )
            checks = object_list(outcome.get("checks"), "outcome.checks", issues)
            artifacts = object_list(outcome.get("artifacts"), "outcome.artifacts", issues)
            if not checks and not artifacts:
                issue(
                    issues,
                    "success_without_check_or_artifact",
                    "outcome",
                    "successful result needs check or artifact references",
                )
        if status == "partial" and not unmet:
            issue(
                issues,
                "partial_without_unmet_requirements",
                "outcome.unmet_requirements",
                "partial result must identify unmet requirements",
            )
    elif status in {"success", "partial"}:
        issue(issues, "outcome_required", "outcome", "success/partial result requires outcome")

    claim_set = set(claim_ids)
    for index, row in enumerate(evidence):
        for field in ("supports_claim_ids", "contradicts_claim_ids"):
            refs = set(string_list(row.get(field), f"evidence[{index}].{field}", issues))
            unknown = refs - claim_set
            if unknown:
                issue(
                    issues,
                    "evidence_unknown_claim",
                    f"evidence[{index}].{field}",
                    f"evidence references unknown claims: {sorted(unknown)}",
                )

    failure = payload.get("failure")
    if status in FAILURE_RESULT_STATUSES:
        failure_obj = object_value(failure, "failure", issues)
        refs = set(string_list(failure_obj.get("evidence_refs"), "failure.evidence_refs", issues))
        if not refs:
            issue(
                issues,
                "failure_reason_evidence_required",
                "failure.evidence_refs",
                "terminal failure requires reason evidence",
            )
        unknown = refs - evidence_set
        if unknown:
            issue(
                issues,
                "failure_unknown_evidence",
                "failure.evidence_refs",
                f"failure references unknown evidence: {sorted(unknown)}",
            )
        if outcome is not None:
            issue(
                issues,
                "failure_with_success_payload",
                "outcome",
                "failure status cannot carry success-only outcome",
            )
    elif failure is not None:
        issue(
            issues,
            "unexpected_failure_payload",
            "failure",
            "success/partial result cannot carry failure payload",
        )

    privacy = object_value(payload.get("privacy_guard"), "privacy_guard", issues)
    for field in (
        "raw_prompts_included",
        "secrets_included",
        "credentials_included",
        "unrestricted_logs_included",
        "unrestricted_parent_context_included",
    ):
        if privacy.get(field) is not False:
            issue(
                issues,
                "sensitive_result_content_forbidden",
                f"privacy_guard.{field}",
                "result envelopes cannot contain raw prompts, secrets or unrestricted logs/context",
            )
    next_run = payload.get("next_run_recommendation_ref")
    if isinstance(next_run, dict) and next_run.get("authorization_granted") is not False:
        issue(
            issues,
            "recommendation_authority_forbidden",
            "next_run_recommendation_ref.authorization_granted",
            "child recommendation is advisory only",
        )


def accounting_key(row: dict[str, Any]) -> tuple[str, str, str]:
    return (
        str(row.get("invocation_id") or ""),
        str(row.get("attempt_id") or ""),
        str(row.get("work_unit_id") or ""),
    )


def validate_integration(
    payload: dict[str, Any],
    issues: list[dict[str, str]],
) -> None:
    integration_id = id_value(payload.get("integration_id"), "integration_id", issues)
    integration_digest = digest_value(payload.get("integration_digest"), "integration_digest", issues)
    digest_value(payload.get("plan_content_digest"), "plan_content_digest", issues)
    result_set_digest = digest_value(payload.get("input_result_set_digest"), "input_result_set_digest", issues)
    expected = object_list(payload.get("expected_results"), "expected_results", issues)
    received = object_list(payload.get("received_results"), "received_results", issues)
    accounting = object_list(payload.get("result_accounting"), "result_accounting", issues)
    orphans = object_list(payload.get("orphan_results"), "orphan_results", issues)
    synthesis = object_value(payload.get("synthesis"), "synthesis", issues)

    expected_binding_digests: dict[str, str | None] = {}
    for index, row in enumerate(expected):
        digest = row.get("skill_bindings_digest")
        if digest is not None:
            digest_value(digest, f"expected_results[{index}].skill_bindings_digest", issues)
        expected_binding_digests[accounting_key(row)[2]] = digest

    for index, row in enumerate(received):
        digest = row.get("skill_bindings_digest")
        if digest is not None:
            digest_value(digest, f"received_results[{index}].skill_bindings_digest", issues)
        unit_id = accounting_key(row)[2]
        expected_digest = expected_binding_digests.get(unit_id)
        if (expected_digest is None) != (digest is None) or (
            expected_digest is not None and digest != expected_digest
        ):
            issue(
                issues,
                "skill_binding_mismatch",
                f"received_results[{index}].skill_bindings_digest",
                "result skill bindings do not match the plan lane binding",
            )

    expected_keys = [accounting_key(row) for row in expected]
    expected_set = set(expected_keys)
    if len(expected_keys) != len(expected_set):
        issue(
            issues,
            "duplicate_expected_invocation",
            "expected_results",
            "each invocation attempt may be expected exactly once",
        )
    accounting_keys = [accounting_key(row) for row in accounting]
    if len(accounting_keys) != len(set(accounting_keys)):
        issue(
            issues,
            "duplicate_result_accounting",
            "result_accounting",
            "each expected invocation must be accounted exactly once",
        )
    if set(accounting_keys) != expected_set:
        missing = expected_set - set(accounting_keys)
        extra = set(accounting_keys) - expected_set
        issue(
            issues,
            "result_accounting_not_exact",
            "result_accounting",
            f"missing={sorted(missing)} extra={sorted(extra)}",
        )
    accounting_by_key = {accounting_key(row): row for row in accounting}

    result_ids = [
        id_value(row.get("result_id"), f"received_results[{index}].result_id", issues)
        for index, row in enumerate(received)
    ]
    result_set = unique_values(result_ids, "received_results", issues, code="duplicate_result_id")
    received_by_key: dict[tuple[str, str, str], dict[str, Any]] = {}
    for index, row in enumerate(received):
        key = accounting_key(row)
        if key in received_by_key:
            issue(
                issues,
                "duplicate_invocation_result",
                f"received_results[{index}]",
                "one invocation attempt cannot produce multiple terminal results",
            )
        received_by_key[key] = row
    orphan_ids = {
        id_value(row.get("result_id"), f"orphan_results[{index}].result_id", issues)
        for index, row in enumerate(orphans)
    }
    unmatched_received = {
        str(row.get("result_id") or "")
        for row in received
        if accounting_key(row) not in expected_set
    }
    if unmatched_received != orphan_ids:
        issue(
            issues,
            "orphan_result_accounting_mismatch",
            "orphan_results",
            "every and only unmatched received result must have an orphan disposition",
        )

    accepted_ids: set[str] = set()
    excluded_received_ids: set[str] = set()
    for index, row in enumerate(accounting):
        key = accounting_key(row)
        disposition = row.get("disposition")
        result_id = row.get("result_id")
        received_row = received_by_key.get(key)
        if disposition in {"missing", "not_run"}:
            if result_id is not None:
                issue(
                    issues,
                    "non_result_disposition_has_result",
                    f"result_accounting[{index}].result_id",
                    "missing/not_run disposition cannot reference a result",
                )
            if received_row is not None:
                issue(
                    issues,
                    "received_result_marked_missing",
                    f"result_accounting[{index}].disposition",
                    "received terminal result cannot be silently accounted as missing/not_run",
                )
        else:
            if not isinstance(result_id, str) or result_id not in result_set:
                issue(
                    issues,
                    "accounting_result_missing",
                    f"result_accounting[{index}].result_id",
                    "disposition requires a received result id",
                )
            elif not received_row or received_row.get("result_id") != result_id:
                issue(
                    issues,
                    "accounting_result_binding_mismatch",
                    f"result_accounting[{index}].result_id",
                    "result id is not bound to the accounted invocation attempt",
                )
        if disposition == "accepted" and isinstance(result_id, str):
            accepted_ids.add(result_id)
            if received_row and received_row.get("status") not in {"success", "partial"}:
                issue(
                    issues,
                    "failed_result_accepted",
                    f"result_accounting[{index}]",
                    "failed terminal result cannot be accepted",
                )
        elif isinstance(result_id, str):
            excluded_received_ids.add(result_id)
        if received_row:
            received_status = received_row.get("status")
            allowed_statuses = {
                "accepted": {"success", "partial"},
                "failed": {"failed", "timed_out", "blocked"},
                "cancelled": {"cancelled"},
                "rejected": {"rejected"},
            }
            if disposition in allowed_statuses and received_status not in allowed_statuses[disposition]:
                issue(
                    issues,
                    "accounting_status_mismatch",
                    f"result_accounting[{index}].disposition",
                    f"disposition {disposition} is incompatible with result status {received_status}",
                )

    synthesis_accepted = set(string_list(synthesis.get("accepted_result_ids"), "synthesis.accepted_result_ids", issues))
    if synthesis_accepted != accepted_ids:
        issue(
            issues,
            "accepted_result_set_mismatch",
            "synthesis.accepted_result_ids",
            "synthesis accepted results must exactly match accounting",
        )
    synthesis_excluded = {
        str(row.get("result_id") or "")
        for row in object_list(synthesis.get("excluded_results"), "synthesis.excluded_results", issues)
    }
    if synthesis_excluded != excluded_received_ids:
        issue(
            issues,
            "excluded_result_set_mismatch",
            "synthesis.excluded_results",
            "synthesis excluded results must exactly match non-accepted received results",
        )

    expected_by_unit: dict[str, list[tuple[str, str, str]]] = {}
    criticality_by_unit: dict[str, str] = {}
    attempt_to_key: dict[str, tuple[str, str, str]] = {}
    for index, row in enumerate(expected):
        unit_id = id_value(row.get("work_unit_id"), f"expected_results[{index}].work_unit_id", issues)
        id_value(row.get("invocation_id"), f"expected_results[{index}].invocation_id", issues)
        attempt_id = id_value(row.get("attempt_id"), f"expected_results[{index}].attempt_id", issues)
        key = accounting_key(row)
        if attempt_id in attempt_to_key:
            issue(
                issues,
                "duplicate_expected_attempt_id",
                f"expected_results[{index}].attempt_id",
                "attempt ids must be globally unambiguous within one integration",
            )
        elif attempt_id:
            attempt_to_key[attempt_id] = key
        if unit_id:
            expected_by_unit.setdefault(unit_id, []).append(key)
            criticality = str(row.get("criticality") or "")
            previous = criticality_by_unit.setdefault(unit_id, criticality)
            if previous != criticality:
                issue(
                    issues,
                    "lane_criticality_changed_across_attempts",
                    f"expected_results[{index}].criticality",
                    "retry attempts cannot change required/optional lane criticality",
                )

    expected_lane_ids = set(expected_by_unit)
    lane_rows = object_list(payload.get("lane_coverage"), "lane_coverage", issues)
    lane_ids = [
        id_value(row.get("work_unit_id"), f"lane_coverage[{index}].work_unit_id", issues)
        for index, row in enumerate(lane_rows)
    ]
    if len(lane_ids) != len(set(lane_ids)) or set(lane_ids) != expected_lane_ids:
        issue(
            issues,
            "lane_coverage_not_exact",
            "lane_coverage",
            "every expected work unit must have exactly one lane-coverage row",
        )
    lane_by_id = {
        lane_id: row
        for lane_id, row in zip(lane_ids, lane_rows)
        if lane_id
    }
    required_failures: list[str] = []
    optional_failures: list[str] = []
    for unit_id, keys in expected_by_unit.items():
        lane = lane_by_id.get(unit_id, {})
        lane_status = lane.get("status")
        lane_binding_digest = lane.get("skill_bindings_digest")
        if lane_binding_digest is not None:
            digest_value(lane_binding_digest, f"lane_coverage[{unit_id}].skill_bindings_digest", issues)
            if lane_binding_digest != expected_binding_digests.get(unit_id):
                issue(
                    issues,
                    "skill_binding_lane_mismatch",
                    f"lane_coverage[{unit_id}].skill_bindings_digest",
                    "lane coverage must account for the exact plan skill binding digest",
                )
        chosen_attempt = lane.get("chosen_attempt_id")
        chosen_result = lane.get("chosen_result_id")
        superseded = set(
            string_list(
                lane.get("superseded_attempt_ids"),
                f"lane_coverage[{unit_id}].superseded_attempt_ids",
                issues,
            )
        )
        expected_attempts = {key[1] for key in keys}
        accepted_keys = [
            key
            for key in keys
            if accounting_by_key.get(key, {}).get("disposition") == "accepted"
        ]
        chosen_received: dict[str, Any] | None = None
        if lane_status == "accepted":
            if not isinstance(chosen_attempt, str) or chosen_attempt not in expected_attempts:
                issue(
                    issues,
                    "lane_chosen_attempt_invalid",
                    f"lane_coverage[{unit_id}].chosen_attempt_id",
                    "accepted lane must choose one expected attempt",
                )
            else:
                chosen_key = attempt_to_key.get(chosen_attempt)
                chosen_accounting = accounting_by_key.get(chosen_key or ("", "", ""), {})
                chosen_received = received_by_key.get(chosen_key or ("", "", ""))
                if accepted_keys != [chosen_key]:
                    issue(
                        issues,
                        "lane_accepted_attempt_not_exact",
                        f"lane_coverage[{unit_id}]",
                        "exactly the chosen attempt must be accepted for a lane",
                    )
                if (
                    not isinstance(chosen_result, str)
                    or chosen_accounting.get("result_id") != chosen_result
                    or not chosen_received
                    or chosen_received.get("result_id") != chosen_result
                ):
                    issue(
                        issues,
                        "lane_chosen_result_mismatch",
                        f"lane_coverage[{unit_id}].chosen_result_id",
                        "chosen result must bind to the accepted chosen attempt",
                    )
                required_superseded = expected_attempts - {chosen_attempt}
                if superseded != required_superseded:
                    issue(
                        issues,
                        "lane_superseded_attempts_not_exact",
                        f"lane_coverage[{unit_id}].superseded_attempt_ids",
                        "accepted lane must account every non-chosen attempt as superseded",
                    )
                for attempt_id in superseded:
                    superseded_key = attempt_to_key.get(attempt_id)
                    if accounting_by_key.get(superseded_key or ("", "", ""), {}).get("disposition") == "accepted":
                        issue(
                            issues,
                            "superseded_attempt_accepted",
                            f"lane_coverage[{unit_id}].superseded_attempt_ids",
                            "superseded attempt cannot remain accepted",
                        )
        else:
            if chosen_attempt is not None or chosen_result is not None:
                issue(
                    issues,
                    "unaccepted_lane_has_chosen_attempt",
                    f"lane_coverage[{unit_id}]",
                    "unaccepted lane cannot expose a chosen attempt or result",
                )
            if superseded:
                issue(
                    issues,
                    "unaccepted_lane_has_superseded_attempts",
                    f"lane_coverage[{unit_id}].superseded_attempt_ids",
                    "attempts become superseded only when a replacement is chosen",
                )
            if accepted_keys:
                issue(
                    issues,
                    "lane_coverage_acceptance_mismatch",
                    f"lane_coverage[{unit_id}].status",
                    "lane coverage must be accepted when accounting accepts an attempt",
                )

        lane_success = lane_status == "accepted" and bool(chosen_received)
        if criticality_by_unit.get(unit_id) == "required":
            if not lane_success or chosen_received.get("status") != "success":
                required_failures.append(unit_id)
        elif not lane_success:
            optional_failures.append(unit_id)

    status = payload.get("status")
    blockers = object_list(payload.get("blockers"), "blockers", issues)
    residual = list_value(synthesis.get("residual_risks"), "synthesis.residual_risks", issues)
    if status in READY_INTEGRATION_STATUSES and required_failures:
        issue(
            issues,
            "required_lane_not_successful",
            "result_accounting",
            f"required lanes are incomplete or non-successful: {required_failures}",
        )
    if required_failures and status not in {
        "integration_incomplete",
        "rerun_required",
        "owner_decision_required",
        "rejected",
    }:
        issue(
            issues,
            "required_failure_status_invalid",
            "status",
            "required-lane failure must block or request a new authorized run",
        )
    if optional_failures and not required_failures and status in READY_INTEGRATION_STATUSES:
        if status != "accepted_with_residual_risk" or not residual:
            issue(
                issues,
                "optional_failure_without_residual_risk",
                "status",
                "optional failure requires accepted_with_residual_risk and an explicit risk",
            )
    if status == "ready_for_finalizer" and (blockers or residual):
        issue(
            issues,
            "ready_state_has_blocker_or_risk",
            "status",
            "ready_for_finalizer cannot retain blockers or residual risks",
        )

    conflicts = object_list(payload.get("conflicts"), "conflicts", issues)
    unresolved_blocking = False
    for index, conflict in enumerate(conflicts):
        resolution = object_value(conflict.get("resolution"), f"conflicts[{index}].resolution", issues)
        if conflict.get("blocking") is True and resolution.get("status") != "resolved":
            unresolved_blocking = True
        if (
            resolution.get("status") == "resolved"
            and resolution.get("method") in {"majority", "confidence"}
            and (
                conflict.get("classification") in CRITICAL_CONFLICT_CLASSES
                or conflict.get("risk") in {"high", "critical"}
            )
        ):
            issue(
                issues,
                "critical_conflict_majority_resolution_forbidden",
                f"conflicts[{index}].resolution.method",
                "majority/confidence cannot resolve factual, policy, security, irreversible or high-risk conflicts",
            )
    if unresolved_blocking and status in READY_INTEGRATION_STATUSES:
        issue(
            issues,
            "blocking_conflict_unresolved",
            "conflicts",
            "unresolved blocking conflict prevents Finalizer readiness",
        )

    confidence = synthesis.get("confidence")
    weakest = synthesis.get("weakest_critical_evidence_confidence")
    if isinstance(confidence, (int, float)) and isinstance(weakest, (int, float)) and confidence > weakest:
        issue(
            issues,
            "synthesis_confidence_exceeds_weakest_evidence",
            "synthesis.confidence",
            "critical synthesis confidence cannot exceed the weakest critical evidence",
        )
    if synthesis.get("confidence_method") == "weakest_critical_evidence" and weakest is None:
        issue(
            issues,
            "weakest_evidence_confidence_required",
            "synthesis.weakest_critical_evidence_confidence",
            "weakest-evidence confidence method needs its bound",
        )

    handoff = payload.get("finalizer_handoff")
    if status in READY_INTEGRATION_STATUSES:
        handoff_obj = object_value(handoff, "finalizer_handoff", issues)
        compare_binding(integration_id, handoff_obj.get("integration_id"), "finalizer_handoff.integration_id", "finalizer_integration_mismatch", issues)
        compare_binding(integration_digest, handoff_obj.get("integration_digest"), "finalizer_handoff.integration_digest", "finalizer_integration_digest_mismatch", issues)
        compare_binding(synthesis.get("synthesis_digest"), handoff_obj.get("synthesis_digest"), "finalizer_handoff.synthesis_digest", "finalizer_synthesis_digest_mismatch", issues)
        compare_binding(result_set_digest, handoff_obj.get("input_result_set_digest"), "finalizer_handoff.input_result_set_digest", "finalizer_result_set_digest_mismatch", issues)
        for field in ("required_accounting_complete", "blocking_conflicts_resolved", "required_checks_passed"):
            if handoff_obj.get(field) is not True:
                issue(
                    issues,
                    "finalizer_readiness_incomplete",
                    f"finalizer_handoff.{field}",
                    "Finalizer handoff requires exact completed evidence",
                )
        if handoff_obj.get("finalizer_may_modify") is not False or handoff_obj.get("merge_authority_granted") is not False:
            issue(
                issues,
                "finalizer_authority_escalation_forbidden",
                "finalizer_handoff",
                "Finalizer verifies exact synthesis and gains no repair/merge authority",
            )
        coverage = object_list(payload.get("requirement_coverage"), "requirement_coverage", issues)
        if any(row.get("status") != "covered" for row in coverage):
            issue(
                issues,
                "finalizer_requirement_coverage_incomplete",
                "requirement_coverage",
                "Finalizer readiness requires complete requirement coverage",
            )
        checks = object_list(synthesis.get("preservation_checks"), "synthesis.preservation_checks", issues)
        if any(row.get("status") != "passed" for row in checks):
            issue(
                issues,
                "finalizer_check_not_passed",
                "synthesis.preservation_checks",
                "Finalizer readiness requires every declared preservation check to pass",
            )
    elif handoff is not None:
        issue(
            issues,
            "premature_finalizer_handoff",
            "finalizer_handoff",
            "incomplete/rerun/owner/rejected states cannot hand off to Finalizer",
        )
    next_run = payload.get("next_run_request")
    if isinstance(next_run, dict):
        if next_run.get("authorization_granted") is not False or next_run.get("execution_started") is not False:
            issue(
                issues,
                "next_run_request_authority_forbidden",
                "next_run_request",
                "Integrator may recommend, but cannot authorize or start, another run",
            )
    if status == "rerun_required" and not isinstance(next_run, dict):
        issue(
            issues,
            "rerun_request_missing",
            "next_run_request",
            "rerun_required needs a targeted recommendation",
        )


def validate(
    payload: Any,
    *,
    kind: str | None = None,
    project_root: Path | str | None = None,
    mode: str = DEFAULT_MODE,
    now: dt.datetime | str | None = None,
    provenance_key: bytes | str | None = None,
) -> dict[str, Any]:
    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    if mode not in MODES:
        issue(errors, "invalid_mode", "mode", f"mode must be one of {MODES}")
        mode = DEFAULT_MODE
    if not isinstance(payload, dict):
        issue(errors, "root_not_object", "$", "contract payload must be a JSON object")
        return {
            "status": "invalid",
            "valid": False,
            "kind": kind,
            "contract_version": None,
            "errors": errors,
            "warnings": warnings,
            "authority_granted": False,
            "execution_started": False,
            "eligible_for_required_critical_lane": False,
        }

    declared_kind = payload.get("contract_kind")
    version = payload.get("contract_version")
    legacy = not isinstance(declared_kind, str) or not isinstance(version, str)
    if legacy:
        if mode == DEFAULT_MODE:
            issue(
                warnings,
                "legacy_contract_warning",
                "$",
                "legacy/free-text result is readable only through an adapter and cannot satisfy a required critical lane",
                severity="warning",
            )
            return {
                "status": "legacy_warning",
                "valid": True,
                "kind": kind,
                "contract_version": version,
                "errors": errors,
                "warnings": warnings,
                "authority_granted": False,
                "execution_started": False,
                "eligible_for_required_critical_lane": False,
            }
        issue(
            errors,
            "legacy_contract_rejected",
            "$",
            "strict mode requires a versioned execution contract",
        )
        return {
            "status": "invalid",
            "valid": False,
            "kind": kind,
            "contract_version": version,
            "errors": errors,
            "warnings": warnings,
            "authority_granted": False,
            "execution_started": False,
            "eligible_for_required_critical_lane": False,
        }

    effective_kind = declared_kind if kind in (None, "auto") else kind
    critical_lane_eligible = True
    if effective_kind not in KINDS:
        issue(errors, "unknown_contract_kind", "contract_kind", f"unknown kind: {effective_kind}")
    else:
        validate_common(payload, effective_kind, errors)
        for field in sorted(REQUIRED_TOP_LEVEL[effective_kind]):
            if field not in payload:
                issue(
                    errors,
                    "missing_required_field",
                    field,
                    "required top-level contract field is missing",
                )
        root = Path(project_root).resolve() if project_root is not None else None
        models = configured_models(root)
        at = parse_now(now, errors)
        if effective_kind == "subagent_invocation":
            validate_invocation(payload, errors, models=models, now=at)
        elif effective_kind == "parallel_work":
            critical_lane_eligible = validate_parallel(
                payload,
                errors,
                warnings,
                now=at,
                provenance_key=provenance_key,
            )
        elif effective_kind == "subagent_result_envelope":
            validate_result(payload, errors, models=models)
        elif effective_kind == "result_integration":
            validate_integration(payload, errors)

    valid = not errors
    return {
        "status": "valid" if valid else "invalid",
        "valid": valid,
        "kind": effective_kind,
        "contract_version": version,
        "errors": errors,
        "warnings": warnings,
        "authority_granted": False,
        "execution_started": False,
        "eligible_for_required_critical_lane": valid and critical_lane_eligible,
    }


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Execution contract JSON path")
    parser.add_argument("--kind", choices=("auto", *KINDS), default="auto")
    parser.add_argument("--mode", choices=MODES, default=DEFAULT_MODE)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--now", help="Deterministic RFC 3339 validation time")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        payload = load_json(Path(args.input))
    except (OSError, json.JSONDecodeError) as exc:
        report = {
            "status": "invalid",
            "valid": False,
            "kind": None if args.kind == "auto" else args.kind,
            "contract_version": None,
            "errors": [
                {
                    "severity": "error",
                    "code": "input_read_error",
                    "path": "$",
                    "message": str(exc),
                }
            ],
            "warnings": [],
            "authority_granted": False,
            "execution_started": False,
            "eligible_for_required_critical_lane": False,
        }
    else:
        report = validate(
            payload,
            kind=None if args.kind == "auto" else args.kind,
            project_root=Path(args.project_root),
            mode=args.mode,
            now=args.now,
        )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"{report['status']}: {report.get('kind') or 'unknown'}")
        for row in report["errors"] + report["warnings"]:
            print(f"{row['severity'].upper()} {row['code']} {row['path']}: {row['message']}")
    return 0 if report["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
