#!/usr/bin/env python3
"""Validate Next Run Recommendation Contract 1.1.0 without granting authority."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any


CONTRACT_VERSION = "1.1.0"
DEFAULT_MODE = "strict_new_warn_legacy"
MODES = {DEFAULT_MODE, "strict"}
CAPABILITY_PROFILES = (
    "efficient",
    "balanced",
    "deep",
    "maximum_coherent",
    "delegated_deep",
)
EFFORTS = ("low", "medium", "high", "extra_high", "max", "ultra")
LEGACY_EFFORT_ALIASES = {"xhigh": "extra_high", "very_high": "extra_high"}
DELEGATION = ("forbidden", "allowed", "required")
COMPLEXITIES = ("S", "M", "L", "XL")
RISKS = ("low", "normal", "high", "critical")
COHERENCE = ("bounded", "normal", "high", "maximum")
COST_CLASSES = ("low", "standard", "premium", "scarce")
OUTPUT_CLASSES = (
    "owner_visible_response",
    "inter_role_handoff",
    "machine_artifact",
    "low_level_event",
)
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
DIGEST_RE = re.compile(r"^sha256:[a-f0-9]{64}$")


def issue(code: str, path: str, message: str) -> dict[str, str]:
    return {"code": code, "path": path, "message": message}


def is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def enum_rank(mapping: dict[str, int], value: Any) -> int | None:
    return mapping.get(value) if isinstance(value, str) else None


def validate_object(
    value: Any,
    *,
    path: str,
    required: tuple[str, ...] = (),
    allowed: tuple[str, ...] = (),
    errors: list[dict[str, str]],
) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        errors.append(issue("invalid_type", path, "must be an object"))
        return None
    for key in required:
        if key not in value:
            errors.append(issue("missing_required_field", f"{path}.{key}", "field is required"))
    if allowed:
        for key in sorted(set(value) - set(allowed)):
            errors.append(issue("unknown_field", f"{path}.{key}", "field is not allowed by contract 1.1.0"))
    return value


def require_string(
    value: Any,
    *,
    path: str,
    errors: list[dict[str, str]],
    maximum: int | None = None,
) -> None:
    if not isinstance(value, str) or not value:
        errors.append(issue("invalid_string", path, "must be a non-empty string"))
    elif maximum is not None and len(value) > maximum:
        errors.append(issue("string_too_long", path, f"must be at most {maximum} characters"))


def require_enum(value: Any, allowed: tuple[str, ...], *, path: str, errors: list[dict[str, str]]) -> None:
    if value not in allowed:
        errors.append(issue("unknown_enum_value", path, f"must be one of: {', '.join(allowed)}"))


def require_id(value: Any, *, path: str, errors: list[dict[str, str]]) -> None:
    if not isinstance(value, str) or ID_RE.fullmatch(value) is None:
        errors.append(issue("invalid_id", path, "must be a stable 8-128 character contract id"))


def require_digest(value: Any, *, path: str, errors: list[dict[str, str]]) -> None:
    if not isinstance(value, str) or DIGEST_RE.fullmatch(value) is None:
        errors.append(issue("invalid_digest", path, "must use sha256:<64 lowercase hex>"))


def require_datetime(value: Any, *, path: str, errors: list[dict[str, str]]) -> None:
    if not isinstance(value, str):
        errors.append(issue("invalid_datetime", path, "must be an RFC 3339 date-time"))
        return
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        errors.append(issue("invalid_datetime", path, "must be an RFC 3339 date-time"))
        return
    if parsed.tzinfo is None:
        errors.append(issue("invalid_datetime", path, "date-time must include a timezone"))


def validate_effort(value: Any, *, path: str, errors: list[dict[str, str]]) -> None:
    if isinstance(value, str) and value in LEGACY_EFFORT_ALIASES:
        errors.append(
            issue(
                "legacy_effort_alias_in_new_contract",
                path,
                f"new producers must write {LEGACY_EFFORT_ALIASES[value]} instead of {value}",
            )
        )
    else:
        require_enum(value, EFFORTS, path=path, errors=errors)


def validate_producer(value: Any, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    path = "$.producer"
    obj = validate_object(
        value,
        path=path,
        required=("project_id", "role", "stage", "contract_mode"),
        allowed=("project_id", "role", "stage", "contract_mode", "output_class"),
        errors=errors,
    )
    if obj is None:
        return None
    for key in ("project_id", "role", "stage"):
        require_string(obj.get(key), path=f"{path}.{key}", errors=errors)
    require_enum(obj.get("contract_mode"), ("strict", "legacy_compatible"), path=f"{path}.contract_mode", errors=errors)
    if "output_class" in obj:
        require_enum(obj.get("output_class"), OUTPUT_CLASSES, path=f"{path}.output_class", errors=errors)
    return obj


def validate_authority_guard(value: Any, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    path = "$.authority_guard"
    obj = validate_object(
        value,
        path=path,
        required=(
            "role_permissions_changed",
            "approval_gates_bypassed",
            "worker_ready_changed_by_recommendation",
        ),
        allowed=(
            "role_permissions_changed",
            "approval_gates_bypassed",
            "worker_ready_changed_by_recommendation",
            "delegation_authority_ref",
        ),
        errors=errors,
    )
    if obj is None:
        return None
    for key in (
        "role_permissions_changed",
        "approval_gates_bypassed",
        "worker_ready_changed_by_recommendation",
    ):
        if obj.get(key) is not False:
            errors.append(issue("authority_escalation_forbidden", f"{path}.{key}", "recommendation cannot change authority"))
    if "delegation_authority_ref" in obj and obj["delegation_authority_ref"] is not None:
        require_string(obj["delegation_authority_ref"], path=f"{path}.delegation_authority_ref", errors=errors)
    return obj


def validate_presentation(
    value: Any,
    *,
    producer: dict[str, Any] | None,
    next_run: Any,
    errors: list[dict[str, str]],
    warnings: list[dict[str, str]],
) -> dict[str, Any] | None:
    path = "$.presentation"
    obj = validate_object(
        value,
        path=path,
        required=("footer_required", "variant", "debug_details_available"),
        allowed=("footer_required", "variant", "debug_details_available", "locale"),
        errors=errors,
    )
    if obj is None:
        return None
    if not isinstance(obj.get("footer_required"), bool):
        errors.append(issue("invalid_type", f"{path}.footer_required", "must be boolean"))
    if not isinstance(obj.get("debug_details_available"), bool):
        errors.append(issue("invalid_type", f"{path}.debug_details_available", "must be boolean"))
    require_enum(
        obj.get("variant"),
        ("normal", "recommendation_only", "override", "fallback", "terminal", "legacy", "blocked", "none"),
        path=f"{path}.variant",
        errors=errors,
    )
    if "locale" in obj:
        require_string(obj["locale"], path=f"{path}.locale", errors=errors)
    output_class = (producer or {}).get("output_class")
    if output_class in ("owner_visible_response", "inter_role_handoff") and obj.get("footer_required") is not True:
        errors.append(issue("required_footer_missing", f"{path}.footer_required", f"{output_class} requires a visible footer"))
    if output_class in ("machine_artifact", "low_level_event") and obj.get("footer_required") is not False:
        errors.append(issue("internal_footer_forbidden", f"{path}.footer_required", f"{output_class} must not emit a user footer"))
    if output_class is None and (producer or {}).get("contract_mode") != "strict":
        warnings.append(issue("output_class_missing", "$.producer.output_class", "footer applicability cannot be fully checked"))
    if next_run == "none" and obj.get("variant") != "terminal":
        errors.append(issue("terminal_variant_required", f"{path}.variant", "next_run none must use terminal presentation"))
    return obj


def validate_fallback(value: Any, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    path = "$.next_run_recommendation.fallback"
    obj = validate_object(
        value,
        path=path,
        required=("capability_profile", "reasoning_effort"),
        allowed=("capability_profile", "reasoning_effort"),
        errors=errors,
    )
    if obj is None:
        return None
    require_enum(obj.get("capability_profile"), CAPABILITY_PROFILES, path=f"{path}.capability_profile", errors=errors)
    validate_effort(obj.get("reasoning_effort"), path=f"{path}.reasoning_effort", errors=errors)
    return obj


def validate_recommendation(value: Any, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    path = "$.next_run_recommendation"
    required = (
        "next_role",
        "next_stage",
        "capability_profile",
        "reasoning_effort",
        "delegation",
        "complexity",
        "risk",
        "context_coherence",
        "confidence",
        "rationale",
        "fallback",
    )
    allowed = required + ("independent_work_units", "synthesis_required", "hop_count", "escalation_budget")
    obj = validate_object(value, path=path, required=required, allowed=allowed, errors=errors)
    if obj is None:
        return None
    for key in ("next_role", "next_stage"):
        require_string(obj.get(key), path=f"{path}.{key}", errors=errors)
    require_enum(obj.get("capability_profile"), CAPABILITY_PROFILES, path=f"{path}.capability_profile", errors=errors)
    validate_effort(obj.get("reasoning_effort"), path=f"{path}.reasoning_effort", errors=errors)
    require_enum(obj.get("delegation"), DELEGATION, path=f"{path}.delegation", errors=errors)
    require_enum(obj.get("complexity"), COMPLEXITIES, path=f"{path}.complexity", errors=errors)
    require_enum(obj.get("risk"), RISKS, path=f"{path}.risk", errors=errors)
    require_enum(obj.get("context_coherence"), COHERENCE, path=f"{path}.context_coherence", errors=errors)
    confidence = obj.get("confidence")
    if not is_number(confidence) or not 0 <= confidence <= 1:
        errors.append(issue("invalid_confidence", f"{path}.confidence", "must be a number from 0 through 1"))
    rationale = obj.get("rationale")
    if not isinstance(rationale, list) or not 1 <= len(rationale) <= 8:
        errors.append(issue("invalid_rationale", f"{path}.rationale", "must contain 1-8 short reasons"))
    else:
        for index, item in enumerate(rationale):
            require_string(item, path=f"{path}.rationale[{index}]", errors=errors, maximum=240)
    for key, maximum in (("independent_work_units", 32), ("hop_count", 32), ("escalation_budget", 8)):
        if key in obj and (not isinstance(obj[key], int) or isinstance(obj[key], bool) or not 0 <= obj[key] <= maximum):
            errors.append(issue("invalid_integer", f"{path}.{key}", f"must be an integer from 0 through {maximum}"))
    if "synthesis_required" in obj and not isinstance(obj["synthesis_required"], bool):
        errors.append(issue("invalid_type", f"{path}.synthesis_required", "must be boolean"))
    fallback = validate_fallback(obj.get("fallback"), errors)
    if isinstance(fallback, dict):
        profile_rank = {name: index for index, name in enumerate(CAPABILITY_PROFILES)}
        effort_rank = {name: index for index, name in enumerate(EFFORTS)}
        current_profile = enum_rank(profile_rank, obj.get("capability_profile"))
        fallback_profile = enum_rank(profile_rank, fallback.get("capability_profile"))
        current_effort = enum_rank(effort_rank, obj.get("reasoning_effort"))
        fallback_effort = enum_rank(effort_rank, fallback.get("reasoning_effort"))
        if None not in (current_profile, fallback_profile, current_effort, fallback_effort):
            if fallback_profile > current_profile or fallback_effort > current_effort:
                errors.append(issue("fallback_escalation_forbidden", path + ".fallback", "fallback cannot exceed the recommended capability or effort"))
            elif fallback_profile == current_profile and fallback_effort == current_effort:
                errors.append(issue("fallback_must_change_route", path + ".fallback", "fallback must be a lower alternative, not the same route"))
    if obj.get("reasoning_effort") == "ultra":
        if obj.get("capability_profile") != "delegated_deep":
            errors.append(issue("ultra_profile_required", f"{path}.capability_profile", "Ultra requires delegated_deep"))
        if obj.get("delegation") not in ("allowed", "required"):
            errors.append(issue("ultra_delegation_required", f"{path}.delegation", "Ultra requires delegation eligibility"))
        if (obj.get("independent_work_units") or 0) < 2:
            errors.append(issue("ultra_independent_units_required", f"{path}.independent_work_units", "Ultra requires at least two independent work units"))
        if obj.get("synthesis_required") is not True:
            errors.append(issue("ultra_synthesis_required", f"{path}.synthesis_required", "Ultra requires later synthesis"))
    if "hop_count" in obj and "escalation_budget" in obj and obj["hop_count"] > obj["escalation_budget"]:
        errors.append(issue("escalation_budget_exhausted", f"{path}.hop_count", "hop count exceeds the bounded escalation budget"))
    obj["_validated_fallback"] = fallback
    return obj


def validate_selected_route(value: Any, *, path: str, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    required = (
        "capability_profile",
        "model_id",
        "reasoning_effort",
        "delegation",
        "parallel_lanes",
        "cost_class",
        "selected_route_digest",
    )
    obj = validate_object(value, path=path, required=required, allowed=required, errors=errors)
    if obj is None:
        return None
    require_enum(obj.get("capability_profile"), CAPABILITY_PROFILES, path=f"{path}.capability_profile", errors=errors)
    require_string(obj.get("model_id"), path=f"{path}.model_id", errors=errors)
    validate_effort(obj.get("reasoning_effort"), path=f"{path}.reasoning_effort", errors=errors)
    require_enum(obj.get("delegation"), DELEGATION, path=f"{path}.delegation", errors=errors)
    lanes = obj.get("parallel_lanes")
    if not isinstance(lanes, int) or isinstance(lanes, bool) or not 1 <= lanes <= 64:
        errors.append(issue("invalid_parallel_lanes", f"{path}.parallel_lanes", "must be an integer from 1 through 64"))
    require_enum(obj.get("cost_class"), COST_CLASSES, path=f"{path}.cost_class", errors=errors)
    require_digest(obj.get("selected_route_digest"), path=f"{path}.selected_route_digest", errors=errors)
    if obj.get("reasoning_effort") == "ultra":
        if obj.get("capability_profile") != "delegated_deep":
            errors.append(issue("ultra_profile_required", f"{path}.capability_profile", "selected Ultra requires delegated_deep"))
        if obj.get("delegation") not in ("allowed", "required") or not isinstance(lanes, int) or lanes < 2:
            errors.append(issue("ultra_parallelism_required", path, "selected Ultra requires delegated execution with at least two lanes"))
    return obj


def validate_override(value: Any, *, path: str, fallback: bool, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    obj = validate_object(
        value,
        path=path,
        required=("applied", "direction"),
        allowed=("applied", "direction", "reason_code", "human_reason"),
        errors=errors,
    )
    if obj is None:
        return None
    applied = obj.get("applied")
    direction = obj.get("direction")
    if not isinstance(applied, bool):
        errors.append(issue("invalid_type", f"{path}.applied", "must be boolean"))
        return obj
    if fallback:
        if applied is not True or direction != "fallback":
            errors.append(issue("fallback_override_required", path, "fallback selection requires applied=true and direction=fallback"))
    elif applied:
        require_enum(direction, ("raised", "lowered", "substituted"), path=f"{path}.direction", errors=errors)
    elif direction != "none":
        errors.append(issue("invalid_override_direction", f"{path}.direction", "non-applied override must use none"))
    if applied:
        require_string(obj.get("reason_code"), path=f"{path}.reason_code", errors=errors)
        require_string(obj.get("human_reason"), path=f"{path}.human_reason", errors=errors, maximum=240)
    elif "reason_code" in obj or "human_reason" in obj:
        errors.append(issue("spurious_override_reason", path, "non-applied override cannot include a reason"))
    return obj


def validate_router_decision(value: Any, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    path = "$.router_decision"
    if not isinstance(value, dict):
        errors.append(issue("invalid_type", path, "must be an object"))
        return None
    status = value.get("status")
    common = ("status", "policy_version", "decided_at", "decision_id", "router_decision_digest")
    if status == "selected":
        allowed = common + ("selected", "override", "limits_snapshot_ref", "delegation_authorized")
        required = common + ("selected",)
    elif status == "fallback_selected":
        allowed = common + ("selected", "fallback_from", "override", "limits_snapshot_ref", "delegation_authorized")
        required = common + ("selected", "fallback_from", "override")
    elif status == "blocked":
        allowed = common + ("block_reason_code", "human_reason")
        required = common + ("block_reason_code", "human_reason")
    elif status == "terminal_none":
        allowed = common
        required = common
    else:
        errors.append(issue("unknown_router_status", f"{path}.status", "unknown Router lifecycle status"))
        return value
    obj = validate_object(value, path=path, required=required, allowed=allowed, errors=errors)
    if obj is None:
        return None
    require_string(obj.get("policy_version"), path=f"{path}.policy_version", errors=errors)
    require_datetime(obj.get("decided_at"), path=f"{path}.decided_at", errors=errors)
    require_id(obj.get("decision_id"), path=f"{path}.decision_id", errors=errors)
    require_digest(obj.get("router_decision_digest"), path=f"{path}.router_decision_digest", errors=errors)
    if status in ("selected", "fallback_selected"):
        obj["_validated_selected"] = validate_selected_route(obj.get("selected"), path=f"{path}.selected", errors=errors)
        if "delegation_authorized" in obj and not isinstance(obj["delegation_authorized"], bool):
            errors.append(issue("invalid_type", f"{path}.delegation_authorized", "must be boolean"))
        if "limits_snapshot_ref" in obj and obj["limits_snapshot_ref"] is not None:
            require_string(obj["limits_snapshot_ref"], path=f"{path}.limits_snapshot_ref", errors=errors)
        if status == "fallback_selected":
            require_string(obj.get("fallback_from"), path=f"{path}.fallback_from", errors=errors)
            obj["_validated_override"] = validate_override(obj.get("override"), path=f"{path}.override", fallback=True, errors=errors)
        elif "override" in obj:
            obj["_validated_override"] = validate_override(obj["override"], path=f"{path}.override", fallback=False, errors=errors)
    elif status == "blocked":
        require_string(obj.get("block_reason_code"), path=f"{path}.block_reason_code", errors=errors)
        require_string(obj.get("human_reason"), path=f"{path}.human_reason", errors=errors, maximum=240)
    return obj


def validate_actual_use(value: Any, *, path: str, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    required = ("model_id", "reasoning_effort", "delegation_used", "parallel_lanes")
    obj = validate_object(value, path=path, required=required, allowed=required, errors=errors)
    if obj is None:
        return None
    require_string(obj.get("model_id"), path=f"{path}.model_id", errors=errors)
    validate_effort(obj.get("reasoning_effort"), path=f"{path}.reasoning_effort", errors=errors)
    if not isinstance(obj.get("delegation_used"), bool):
        errors.append(issue("invalid_type", f"{path}.delegation_used", "must be boolean"))
    lanes = obj.get("parallel_lanes")
    if not isinstance(lanes, int) or isinstance(lanes, bool) or not 1 <= lanes <= 64:
        errors.append(issue("invalid_parallel_lanes", f"{path}.parallel_lanes", "must be an integer from 1 through 64"))
    return obj


def validate_execution_evidence(value: Any, errors: list[dict[str, str]]) -> dict[str, Any] | None:
    path = "$.execution_evidence"
    if not isinstance(value, dict):
        errors.append(issue("invalid_type", path, "must be an object"))
        return None
    status = value.get("status")
    if status == "not_started":
        required = allowed = ("status", "reconciliation_status")
    elif status == "started":
        required = allowed = ("status", "run_id", "started_at", "actually_used", "reconciliation_status")
    elif status in ("completed", "failed"):
        required = allowed = (
            "status",
            "run_id",
            "started_at",
            "completed_at",
            "actually_used",
            "evidence_ref",
            "reconciliation_status",
        )
    elif status == "not_applicable":
        required = allowed = ("status", "reconciliation_status")
    else:
        errors.append(issue("unknown_execution_status", f"{path}.status", "unknown execution evidence status"))
        return value
    obj = validate_object(value, path=path, required=required, allowed=allowed, errors=errors)
    if obj is None:
        return None
    if status == "not_started" and obj.get("reconciliation_status") != "pending":
        errors.append(issue("invalid_reconciliation_status", f"{path}.reconciliation_status", "not_started must be pending"))
    elif status == "not_applicable" and obj.get("reconciliation_status") != "not_applicable":
        errors.append(issue("invalid_reconciliation_status", f"{path}.reconciliation_status", "not_applicable evidence must say not_applicable"))
    elif status == "started":
        require_id(obj.get("run_id"), path=f"{path}.run_id", errors=errors)
        require_datetime(obj.get("started_at"), path=f"{path}.started_at", errors=errors)
        obj["_validated_actual"] = validate_actual_use(obj.get("actually_used"), path=f"{path}.actually_used", errors=errors)
        if obj.get("reconciliation_status") != "pending":
            errors.append(issue("invalid_reconciliation_status", f"{path}.reconciliation_status", "started evidence must be pending"))
    elif status in ("completed", "failed"):
        require_id(obj.get("run_id"), path=f"{path}.run_id", errors=errors)
        require_datetime(obj.get("started_at"), path=f"{path}.started_at", errors=errors)
        require_datetime(obj.get("completed_at"), path=f"{path}.completed_at", errors=errors)
        require_string(obj.get("evidence_ref"), path=f"{path}.evidence_ref", errors=errors)
        obj["_validated_actual"] = validate_actual_use(obj.get("actually_used"), path=f"{path}.actually_used", errors=errors)
        require_enum(obj.get("reconciliation_status"), ("matched", "mismatch"), path=f"{path}.reconciliation_status", errors=errors)
    return obj


def validate_plan_ref(value: Any, errors: list[dict[str, str]]) -> None:
    path = "$.execution_plan_ref"
    obj = validate_object(
        value,
        path=path,
        required=("plan_id", "plan_content_digest"),
        allowed=("plan_id", "plan_content_digest"),
        errors=errors,
    )
    if obj is not None:
        require_id(obj.get("plan_id"), path=f"{path}.plan_id", errors=errors)
        require_digest(obj.get("plan_content_digest"), path=f"{path}.plan_content_digest", errors=errors)


def validate_audit(value: Any, errors: list[dict[str, str]]) -> None:
    path = "$.audit"
    obj = validate_object(value, path=path, allowed=("journal_ref", "warnings"), errors=errors)
    if obj is None:
        return
    if "journal_ref" in obj and obj["journal_ref"] is not None:
        require_string(obj["journal_ref"], path=f"{path}.journal_ref", errors=errors)
    if "warnings" in obj:
        if not isinstance(obj["warnings"], list) or any(not isinstance(item, str) for item in obj["warnings"]):
            errors.append(issue("invalid_audit_warnings", f"{path}.warnings", "must be an array of strings"))


def route_change_direction(recommendation: dict[str, Any], selected: dict[str, Any]) -> str:
    profile_rank = {name: index for index, name in enumerate(CAPABILITY_PROFILES)}
    effort_rank = {name: index for index, name in enumerate(EFFORTS)}
    delegation_rank = {name: index for index, name in enumerate(DELEGATION)}
    rec_values = (
        enum_rank(profile_rank, recommendation.get("capability_profile")),
        enum_rank(effort_rank, recommendation.get("reasoning_effort")),
        enum_rank(delegation_rank, recommendation.get("delegation")),
    )
    selected_values = (
        enum_rank(profile_rank, selected.get("capability_profile")),
        enum_rank(effort_rank, selected.get("reasoning_effort")),
        enum_rank(delegation_rank, selected.get("delegation")),
    )
    if None in rec_values or None in selected_values:
        return "substituted"
    deltas = [selected_values[index] - rec_values[index] for index in range(3)]
    if all(delta >= 0 for delta in deltas) and any(delta > 0 for delta in deltas):
        return "raised"
    if all(delta <= 0 for delta in deltas) and any(delta < 0 for delta in deltas):
        return "lowered"
    if all(delta == 0 for delta in deltas):
        return "none"
    return "substituted"


def configured_models(policy: dict[str, Any] | None) -> set[str] | None:
    if not isinstance(policy, dict):
        return None
    registry = policy.get("model_registry")
    if not isinstance(registry, dict):
        return None
    result = {
        str(entry.get("model_id"))
        for entry in registry.values()
        if isinstance(entry, dict) and isinstance(entry.get("model_id"), str) and entry.get("enabled") is not False
    }
    return result or None


def validate_semantics(
    payload: dict[str, Any],
    *,
    recommendation: dict[str, Any] | None,
    decision: dict[str, Any] | None,
    evidence: dict[str, Any] | None,
    authority_guard: dict[str, Any] | None,
    presentation: dict[str, Any] | None,
    models: set[str] | None,
    errors: list[dict[str, str]],
    warnings: list[dict[str, str]],
) -> None:
    next_run = payload.get("next_run")
    if next_run == "recommended":
        if recommendation is None:
            errors.append(issue("recommendation_required", "$.next_run_recommendation", "next_run recommended requires a recommendation"))
        if (decision or {}).get("status") == "terminal_none":
            errors.append(issue("terminal_decision_conflict", "$.router_decision.status", "recommended run cannot have terminal_none decision"))
        if (evidence or {}).get("status") == "not_applicable":
            errors.append(issue("recommended_evidence_not_applicable", "$.execution_evidence.status", "recommended run requires pending or actual execution evidence"))
    elif next_run == "none":
        for key in ("next_run_recommendation", "execution_plan_ref"):
            if key in payload:
                errors.append(issue("terminal_payload_forbidden", f"$.{key}", f"next_run none forbids {key}"))
        if decision is not None and decision.get("status") != "terminal_none":
            errors.append(issue("terminal_selected_forbidden", "$.router_decision", "next_run none forbids selected, fallback or blocked route details"))
        if evidence is not None and evidence.get("status") != "not_applicable":
            errors.append(issue("terminal_actual_use_forbidden", "$.execution_evidence", "next_run none forbids actual launch evidence"))
    else:
        errors.append(issue("invalid_next_run", "$.next_run", "must be recommended or none"))

    decision_status = (decision or {}).get("status")
    selected = (decision or {}).get("_validated_selected")
    override = (decision or {}).get("_validated_override")
    if decision_status in ("selected", "fallback_selected") and selected is not None:
        if models is None:
            errors.append(issue("model_registry_unavailable", "$.router_decision.selected.model_id", "selected model cannot be verified against centralized policy"))
        elif isinstance(selected.get("model_id"), str) and selected.get("model_id") not in models:
            errors.append(issue("unknown_model", "$.router_decision.selected.model_id", "selected model is not enabled in centralized policy"))
        lanes = selected.get("parallel_lanes")
        multi_lane = isinstance(lanes, int) and not isinstance(lanes, bool) and lanes > 1
        if multi_lane and selected.get("delegation") == "forbidden":
            errors.append(issue("parallel_delegation_conflict", "$.router_decision.selected.delegation", "multi-lane route cannot forbid delegation"))
        if selected.get("delegation") == "required" and not multi_lane:
            errors.append(issue("required_delegation_missing", "$.router_decision.selected.parallel_lanes", "required delegation needs at least two lanes"))
        if multi_lane and decision.get("delegation_authorized") is not True:
            errors.append(issue("delegation_not_authorized", "$.router_decision.delegation_authorized", "multi-lane selection requires explicit Router authorization"))
        if multi_lane and not (authority_guard or {}).get("delegation_authority_ref"):
            errors.append(issue("delegation_authority_ref_required", "$.authority_guard.delegation_authority_ref", "authorized multi-lane selection requires an auditable authority reference"))
        if selected.get("delegation") == "forbidden" and decision.get("delegation_authorized") is True:
            errors.append(issue("delegation_authority_conflict", "$.router_decision.delegation_authorized", "forbidden delegation cannot be authorized"))
        if (selected.get("parallel_lanes") or 1) > 1 and "execution_plan_ref" not in payload:
            errors.append(issue("execution_plan_required", "$.execution_plan_ref", "multi-lane selection requires a digest-bound execution plan"))

    if decision_status == "selected" and recommendation is not None and selected is not None:
        direction = route_change_direction(recommendation, selected)
        applied = isinstance(override, dict) and override.get("applied") is True
        if direction != "none" and not applied:
            errors.append(issue("router_override_reason_required", "$.router_decision.override", "changed capability, effort or delegation requires an explicit override reason"))
        if applied and override.get("direction") != direction and direction != "none":
            errors.append(issue("router_override_direction_mismatch", "$.router_decision.override.direction", f"route change is {direction}"))
        if applied and direction == "none" and override.get("direction") != "substituted":
            errors.append(issue("spurious_router_override", "$.router_decision.override", "raised/lowered override requires a capability or effort change"))

    if decision_status == "fallback_selected" and recommendation is not None and selected is not None:
        fallback = recommendation.get("_validated_fallback")
        if isinstance(fallback, dict):
            if selected.get("capability_profile") != fallback.get("capability_profile"):
                errors.append(issue("fallback_profile_mismatch", "$.router_decision.selected.capability_profile", "must match recommended fallback profile"))
            if selected.get("reasoning_effort") != fallback.get("reasoning_effort"):
                errors.append(issue("fallback_effort_mismatch", "$.router_decision.selected.reasoning_effort", "must match recommended fallback effort"))

    variant = (presentation or {}).get("variant")
    if decision_status == "fallback_selected" and variant != "fallback":
        errors.append(issue("fallback_presentation_required", "$.presentation.variant", "applied fallback must be visible as fallback"))
    if decision_status == "selected" and isinstance(override, dict) and override.get("applied") is True and variant != "override":
        errors.append(issue("override_presentation_required", "$.presentation.variant", "Router override must be visible as override"))
    if decision_status == "blocked" and variant != "blocked":
        errors.append(issue("blocked_presentation_required", "$.presentation.variant", "blocked Router decision must be visible as blocked"))
    if variant == "fallback" and decision_status != "fallback_selected":
        errors.append(issue("spurious_fallback_presentation", "$.presentation.variant", "fallback presentation requires an applied fallback decision"))
    if variant == "override" and not (
        decision_status == "selected"
        and isinstance(override, dict)
        and override.get("applied") is True
    ):
        errors.append(issue("spurious_override_presentation", "$.presentation.variant", "override presentation requires an applied Router override"))
    if variant == "blocked" and decision_status != "blocked":
        errors.append(issue("spurious_blocked_presentation", "$.presentation.variant", "blocked presentation requires a blocked Router decision"))

    evidence_status = (evidence or {}).get("status")
    actual = (evidence or {}).get("_validated_actual")
    if evidence_status in ("started", "completed", "failed") and decision_status not in ("selected", "fallback_selected"):
        errors.append(issue("actual_without_selected_route", "$.execution_evidence", "actual use requires a selected or fallback Router route"))
    if actual is not None:
        if models is not None and isinstance(actual.get("model_id"), str) and actual.get("model_id") not in models:
            errors.append(issue("unknown_actual_model", "$.execution_evidence.actually_used.model_id", "actual model is not enabled in centralized policy"))
        if selected is not None:
            matched = (
                actual.get("model_id") == selected.get("model_id")
                and actual.get("reasoning_effort") == selected.get("reasoning_effort")
                and actual.get("parallel_lanes") == selected.get("parallel_lanes")
                and actual.get("delegation_used") == (selected.get("parallel_lanes", 1) > 1)
            )
            reconciliation = evidence.get("reconciliation_status")
            if reconciliation == "matched" and not matched:
                errors.append(issue("actual_selected_mismatch", "$.execution_evidence.reconciliation_status", "actual use differs from selected route"))
            if reconciliation == "mismatch" and matched:
                errors.append(issue("false_actual_mismatch", "$.execution_evidence.reconciliation_status", "actual use matches selected route"))


def validate(
    payload: Any,
    *,
    policy: dict[str, Any] | None = None,
    mode: str = DEFAULT_MODE,
    source: str = "<memory>",
) -> dict[str, Any]:
    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    if mode not in MODES:
        raise ValueError(f"unsupported validation mode: {mode}")
    if not isinstance(payload, dict):
        errors.append(issue("invalid_type", "$", "contract payload must be an object"))
        return {
            "schema_version": "1.0",
            "contract_version": None,
            "mode": mode,
            "source": source,
            "legacy": False,
            "ok": False,
            "errors": errors,
            "warnings": warnings,
            "authority_granted": False,
        }

    version = payload.get("contract_version")
    if version != CONTRACT_VERSION:
        if mode == DEFAULT_MODE and (version is None or version == "1.0.0"):
            warnings.append(
                issue(
                    "legacy_contract_warning",
                    "$.contract_version",
                    "legacy response has no Next Run 1.1.0 contract; accepted in warning mode only",
                )
            )
            return {
                "schema_version": "1.0",
                "contract_version": version,
                "mode": mode,
                "source": source,
                "legacy": True,
                "ok": True,
                "errors": [],
                "warnings": warnings,
                "authority_granted": False,
            }
        errors.append(issue("unsupported_contract_version", "$.contract_version", f"expected {CONTRACT_VERSION}"))
        return {
            "schema_version": "1.0",
            "contract_version": version,
            "mode": mode,
            "source": source,
            "legacy": version is None or str(version).startswith("1.0"),
            "ok": False,
            "errors": errors,
            "warnings": warnings,
            "authority_granted": False,
        }

    root_required = ("contract_version", "correlation_id", "producer", "next_run", "authority_guard", "presentation")
    root_allowed = root_required + (
        "next_run_recommendation",
        "router_decision",
        "execution_evidence",
        "execution_plan_ref",
        "audit",
    )
    validate_object(payload, path="$", required=root_required, allowed=root_allowed, errors=errors)
    require_id(payload.get("correlation_id"), path="$.correlation_id", errors=errors)
    producer = validate_producer(payload.get("producer"), errors)
    recommendation = validate_recommendation(payload.get("next_run_recommendation"), errors) if "next_run_recommendation" in payload else None
    if isinstance(producer, dict) and producer.get("contract_mode") == "strict" and "output_class" not in producer:
        errors.append(issue("output_class_required", "$.producer.output_class", "strict producer must classify footer applicability"))
    if (
        isinstance(producer, dict)
        and producer.get("contract_mode") == "strict"
        and isinstance(recommendation, dict)
    ):
        for key in ("hop_count", "escalation_budget"):
            if key not in recommendation:
                errors.append(issue("escalation_guard_required", f"$.next_run_recommendation.{key}", "strict recommendation must carry bounded anti-amplification state"))
    decision = validate_router_decision(payload.get("router_decision"), errors) if "router_decision" in payload else None
    evidence = validate_execution_evidence(payload.get("execution_evidence"), errors) if "execution_evidence" in payload else None
    if "execution_plan_ref" in payload:
        validate_plan_ref(payload["execution_plan_ref"], errors)
    authority_guard = validate_authority_guard(payload.get("authority_guard"), errors)
    presentation = validate_presentation(
        payload.get("presentation"),
        producer=producer,
        next_run=payload.get("next_run"),
        errors=errors,
        warnings=warnings,
    )
    if "audit" in payload:
        validate_audit(payload["audit"], errors)
    if payload.get("next_run") == "none" and isinstance(authority_guard, dict) and authority_guard.get("delegation_authority_ref") is not None:
        errors.append(issue("terminal_authority_ref_forbidden", "$.authority_guard.delegation_authority_ref", "terminal next run cannot retain delegation authority"))
    validate_semantics(
        payload,
        recommendation=recommendation,
        decision=decision,
        evidence=evidence,
        authority_guard=authority_guard,
        presentation=presentation,
        models=configured_models(policy),
        errors=errors,
        warnings=warnings,
    )
    for obj in (recommendation, decision, evidence):
        if isinstance(obj, dict):
            for key in [item for item in obj if item.startswith("_validated_")]:
                obj.pop(key, None)
    return {
        "schema_version": "1.0",
        "contract_version": version,
        "mode": mode,
        "source": source,
        "legacy": False,
        "ok": not errors,
        "errors": errors,
        "warnings": warnings,
        "authority_granted": False,
    }


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Next Run JSON payload.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--policy", help="Override centralized model routing policy path.")
    parser.add_argument("--mode", choices=sorted(MODES), default=DEFAULT_MODE)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    input_path = Path(args.input).resolve()
    project_root = Path(args.project_root).resolve()
    policy_path = Path(args.policy).resolve() if args.policy else project_root / ".agent" / "model_routing_policy.json"
    try:
        payload = load_json(input_path)
    except (OSError, json.JSONDecodeError) as exc:
        report = {
            "schema_version": "1.0",
            "contract_version": None,
            "mode": args.mode,
            "source": str(input_path),
            "legacy": False,
            "ok": False,
            "errors": [issue("input_read_error", "$", str(exc))],
            "warnings": [],
            "authority_granted": False,
        }
    else:
        try:
            policy = load_json(policy_path)
        except (OSError, json.JSONDecodeError):
            policy = None
        report = validate(payload, policy=policy, mode=args.mode, source=str(input_path))
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"ok={report['ok']} legacy={report['legacy']} errors={len(report['errors'])} warnings={len(report['warnings'])}")
        for item in report["errors"]:
            print(f"ERROR {item['code']} {item['path']}: {item['message']}")
        for item in report["warnings"]:
            print(f"WARNING {item['code']} {item['path']}: {item['message']}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
