#!/usr/bin/env python3
"""Choose a Codex model, reasoning effort and safe worker capacity."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import re
import uuid
from pathlib import Path
from typing import Any


CANONICAL_EFFORTS = ("low", "medium", "high", "extra_high", "max", "ultra")
LEGACY_EFFORT_ALIASES = {"xhigh": "extra_high", "very_high": "extra_high"}
# Kept as a public name for callers that imported EFFORTS from the legacy Router.
# Values written by the Router are canonical from lifecycle v1 onward.
EFFORTS = CANONICAL_EFFORTS
CAPABILITY_PROFILES = (
    "efficient",
    "balanced",
    "deep",
    "maximum_coherent",
    "delegated_deep",
)
TASK_COMPLEXITIES = ("S", "M", "L", "XL")
PROFILE_BY_EFFORT = {
    "low": "efficient",
    "medium": "efficient",
    "high": "balanced",
    "extra_high": "deep",
    "max": "maximum_coherent",
    "ultra": "delegated_deep",
}
SPARK = "gpt-5.3-codex-spark"
MODEL_LIMIT_NAMES = {SPARK: "GPT-5.3-Codex-Spark"}
CONTRACT_ID_RE = re.compile(r"^[a-z][a-z0-9._:-]{2,127}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SKILL_VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
HMAC_PROOF_RE = re.compile(r"^hmac-sha256:[0-9a-f]{64}$")
AUTHORIZATION_VERSION = "1.0.0"
AUTHORIZATION_DIGEST_PROFILE = "jcs-sha256-v1"
PROVENANCE_VERSION = "1.0.0"
MIN_PROVENANCE_KEY_BYTES = 32
DEFAULT_AUTHORIZATION_TTL_SECONDS = 900
MAX_AUTHORIZATION_TTL_SECONDS = 3600
MAX_AUTHORIZED_WORK_UNITS = 32
MAX_ATTEMPTS_PER_UNIT = 2
AUTHORITY_GUARD_FIELDS = (
    "authority_granted",
    "role_permissions_changed",
    "approval_gates_bypassed",
    "worker_ready_changed",
    "merge_authority_granted",
    "release_authority_granted",
    "recurring_automation_changed",
)
PLAN_CONTENT_FIELDS = (
    "contract_version",
    "contract_kind",
    "correlation_id",
    "producer",
    "created_at",
    "revision",
    "supersedes",
    "digest_profile",
    "authority_guard",
    "plan_id",
    "router_decision_id",
    "router_decision_digest",
    "base_snapshot",
    "risk",
    "work_units",
    "dependencies",
    "barriers",
    "completion_policy",
    "failure_policy",
    "cancellation_policy",
    "integration_gate",
    "deterministic_ready_order",
    "progress_denominator",
)
PLAN_WORK_UNIT_FIELDS = (
    "work_unit_id",
    "input_bundle_digest",
    "order",
    "criticality",
    "objective",
    "acceptance_criteria",
    "target_role",
    "target_stage",
    "capability_profile",
    "access_mode",
    "mutation_allowed",
    "resources",
    "expected_result_schema",
    "timeout_seconds",
)
DEFAULT_POLICY: dict[str, Any] = {
    "schema_version": "1.0",
    "limits_max_age_minutes": 60,
    "manual_reserve_percent": 10,
    "capacity": {
        "minimum_workers": 2,
        "normal_workers": 4,
        "high_workers": 6,
        "maximum_workers": 10,
        "high_remaining_percent": 60,
        "maximum_remaining_percent": 80,
    },
    "roles": {
        "scanner": {"models": ["gpt-5.6-luna", SPARK], "effort": "low"},
        "normalizer": {"models": [SPARK, "gpt-5.6-luna"], "effort": "medium"},
        "worker": {"models": [SPARK, "gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol"], "effort": "medium"},
        "dispatcher": {"models": ["gpt-5.6-terra", "gpt-5.6-sol"], "effort": "high"},
        "architect": {"models": ["gpt-5.6-sol", "gpt-5.6-terra"], "effort": "high"},
        "integrator": {"models": ["gpt-5.6-terra", "gpt-5.6-sol"], "effort": "high"},
        "finalizer": {"models": ["gpt-5.6-terra", "gpt-5.6-sol"], "effort": "medium"},
        "doctor": {"models": ["gpt-5.6-terra", "gpt-5.6-sol", SPARK], "effort": "high"},
    },
    "complexity": {
        "S": {"models": [SPARK, "gpt-5.6-luna"], "effort": "medium"},
        "M": {"models": [SPARK, "gpt-5.6-luna", "gpt-5.6-terra"], "effort": "high"},
        "L": {"models": ["gpt-5.6-terra", "gpt-5.6-sol"], "effort": "high"},
        "XL": {"models": ["gpt-5.6-sol", "gpt-5.6-terra"], "effort": "xhigh"},
    },
}


def now_utc() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def parse_time(value: Any) -> dt.datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def load_json(path: Path | None) -> Any:
    if path is None or not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def merge_dict(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    result = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = merge_dict(result[key], value)
        else:
            result[key] = value
    return result


def load_policy(project_root: Path, explicit: Path | None = None) -> dict[str, Any]:
    path = explicit or project_root / ".agent" / "model_routing_policy.json"
    value = load_json(path)
    return merge_dict(DEFAULT_POLICY, value) if isinstance(value, dict) else dict(DEFAULT_POLICY)


def normalize_role(value: str) -> str:
    role = value.strip().lower().replace("auto-", "").replace("_", "-")
    aliases = {
        "workers": "worker",
        "worker-pool": "worker",
        "artifact-scanner": "scanner",
        "artifact-normalizer": "normalizer",
        "integration": "integrator",
    }
    return aliases.get(role, role)


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def plan_content_projection_v1(plan: Any) -> dict[str, Any] | None:
    """Return the immutable execution-defining Parallel Work projection.

    Lifecycle, derived Router proof, derived capacity, authorization and output
    fields are deliberately excluded so one sealed plan remains stable while
    it moves from validated through terminal states. The exact Router decision
    id and content digest remain inside the projection to prevent plan rebinds.
    """

    if not isinstance(plan, dict) or any(field not in plan for field in PLAN_CONTENT_FIELDS):
        return None
    capacity = plan.get("capacity")
    if not isinstance(capacity, dict) or any(
        field not in capacity for field in ("requested_lanes", "max_attempts_per_unit")
    ):
        return None
    units = plan.get("work_units")
    if not isinstance(units, list) or any(
        not isinstance(unit, dict)
        or any(field not in unit for field in PLAN_WORK_UNIT_FIELDS)
        for unit in units
    ):
        return None

    projection = {field: plan[field] for field in PLAN_CONTENT_FIELDS}
    projection["capacity"] = {
        "requested_lanes": capacity["requested_lanes"],
        "max_attempts_per_unit": capacity["max_attempts_per_unit"],
    }
    if "revision_change" in plan:
        projection["revision_change"] = plan["revision_change"]
    return projection


def compute_plan_content_digest_v1(plan: Any) -> str | None:
    projection = plan_content_projection_v1(plan)
    if projection is None:
        return None
    try:
        return canonical_digest(projection)
    except (TypeError, ValueError):
        return None


def compute_input_scope_digest_v1(plan: Any) -> str | None:
    if not isinstance(plan, dict) or not isinstance(plan.get("work_units"), list):
        return None
    units: list[dict[str, Any]] = []
    seen: set[str] = set()
    for unit in plan["work_units"]:
        if not isinstance(unit, dict):
            return None
        unit_id = unit.get("work_unit_id")
        input_digest = unit.get("input_bundle_digest")
        if (
            not isinstance(unit_id, str)
            or CONTRACT_ID_RE.fullmatch(unit_id) is None
            or not isinstance(input_digest, str)
            or DIGEST_RE.fullmatch(input_digest) is None
            or unit_id in seen
        ):
            return None
        seen.add(unit_id)
        units.append(
            {"work_unit_id": unit_id, "input_bundle_digest": input_digest}
        )
    units.sort(key=lambda item: item["work_unit_id"])
    return canonical_digest(units)


SKILL_BINDING_FIELDS = (
    "skill_id",
    "version",
    "bundle_digest",
    "selection_decision_id",
    "selection_decision_digest",
    "registry_snapshot_digest",
    "load_order",
)


def skill_bindings_digest_v1(bindings: Any) -> str | None:
    """Digest an ordered, exact skill-binding list without granting authority."""

    if not isinstance(bindings, list):
        return None
    for index, binding in enumerate(bindings):
        if not isinstance(binding, dict) or set(binding) != set(SKILL_BINDING_FIELDS):
            return None
        if any(
            not isinstance(binding.get(field), str)
            or not valid_contract_id(binding.get(field))
            for field in ("skill_id", "selection_decision_id")
        ):
            return None
        if not isinstance(binding.get("version"), str) or SKILL_VERSION_RE.fullmatch(binding["version"]) is None:
            return None
        if any(
            not valid_digest(binding.get(field))
            for field in ("bundle_digest", "selection_decision_digest", "registry_snapshot_digest")
        ):
            return None
        if binding.get("load_order") != index:
            return None
    try:
        return canonical_digest(bindings)
    except (TypeError, ValueError):
        return None


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


def build_hmac_proof(
    subject: str,
    payload: dict[str, Any],
    provenance_key: bytes | str | None,
) -> dict[str, Any] | None:
    key = normalize_provenance_key(provenance_key)
    if key is None:
        return None
    proof_seed = {"subject": subject, "payload": payload}
    signature = hmac.new(key, canonical_json_bytes(proof_seed), hashlib.sha256).hexdigest()
    return {
        "proof_version": PROVENANCE_VERSION,
        "issuer": "model_resource_router",
        "algorithm": "hmac-sha256",
        "subject": subject,
        "key_id": provenance_key_id(key),
        "proof": "hmac-sha256:" + signature,
    }


def verify_hmac_proof(
    proof: Any,
    subject: str,
    payload: dict[str, Any],
    provenance_key: bytes | str | None,
) -> bool:
    key = normalize_provenance_key(provenance_key)
    if key is None or not isinstance(proof, dict):
        return False
    expected = build_hmac_proof(subject, payload, key)
    if expected is None or set(proof) != set(expected):
        return False
    if any(proof.get(field) != expected[field] for field in expected if field != "proof"):
        return False
    value = proof.get("proof")
    return (
        isinstance(value, str)
        and HMAC_PROOF_RE.fullmatch(value) is not None
        and hmac.compare_digest(value, expected["proof"])
    )


def decision_provenance_payload(router_decision: dict[str, Any]) -> dict[str, Any]:
    selected = router_decision.get("selected")
    return {
        "decision_id": router_decision.get("decision_id"),
        "router_decision_digest": router_decision.get("router_decision_digest"),
        "selected_route_digest": selected.get("selected_route_digest")
        if isinstance(selected, dict)
        else None,
        "status": router_decision.get("status"),
    }


def build_decision_provenance(
    router_decision: dict[str, Any],
    provenance_key: bytes | str | None,
) -> dict[str, Any] | None:
    if not isinstance(router_decision, dict):
        return None
    return build_hmac_proof(
        "router_decision",
        decision_provenance_payload(router_decision),
        provenance_key,
    )


def canonical_effort(policy: dict[str, Any], value: Any, default: str = "medium") -> str:
    aliases = dict(LEGACY_EFFORT_ALIASES)
    configured = (policy.get("reasoning_effort_policy") or {}).get("read_aliases")
    if isinstance(configured, dict):
        aliases.update({str(key): str(item) for key, item in configured.items()})
    candidate = aliases.get(str(value or "").strip().lower(), str(value or "").strip().lower())
    if candidate in CANONICAL_EFFORTS:
        return candidate
    fallback = aliases.get(str(default).strip().lower(), str(default).strip().lower())
    return fallback if fallback in CANONICAL_EFFORTS else "medium"


def recognized_effort(policy: dict[str, Any], value: Any) -> bool:
    raw = str(value or "").strip().lower()
    configured = (policy.get("reasoning_effort_policy") or {}).get("read_aliases")
    aliases = set(LEGACY_EFFORT_ALIASES)
    if isinstance(configured, dict):
        aliases.update(str(key) for key in configured)
    return raw in CANONICAL_EFFORTS or raw in aliases


def registry(policy: dict[str, Any]) -> dict[str, dict[str, Any]]:
    value = policy.get("model_registry")
    if not isinstance(value, dict):
        return {}
    return {str(alias): entry for alias, entry in value.items() if isinstance(entry, dict)}


def enabled_model_ids(policy: dict[str, Any]) -> set[str] | None:
    entries = registry(policy)
    if not entries:
        return None
    return {
        str(entry.get("model_id"))
        for entry in entries.values()
        if entry.get("enabled") is True
        and isinstance(entry.get("model_id"), str)
        and entry.get("model_id")
    }


def resolve_model_reference(policy: dict[str, Any], value: Any) -> str | None:
    reference = str(value or "")
    entries = registry(policy)
    if not entries:
        return reference or None
    if reference in entries:
        entry = entries[reference]
        return str(entry.get("model_id")) if entry.get("enabled") is True and entry.get("model_id") else None
    for entry in entries.values():
        if entry.get("model_id") == reference:
            return reference if entry.get("enabled") is True else None
    return None


def recommendation_from_task(task: dict[str, Any]) -> tuple[dict[str, Any] | None, str]:
    recommendation = task.get("next_run_recommendation")
    if isinstance(recommendation, dict):
        return recommendation, "next_run_recommendation"
    profile = task.get("capability_profile") or task.get("capability_profile_hint")
    if isinstance(profile, str) and profile:
        return {
            "capability_profile": profile,
            "reasoning_effort": task.get("reasoning_effort") or task.get("reasoning_effort_hint"),
            "delegation": task.get("delegation") or "forbidden",
            "independent_work_units": task.get("independent_work_units"),
            "synthesis_required": task.get("synthesis_required"),
            "hop_count": task.get("hop_count"),
            "escalation_budget": task.get("escalation_budget"),
            "fallback": task.get("fallback"),
            "risk": task.get("risk") or task.get("risk_level"),
        }, "capability_hint"
    return None, "legacy_role_complexity"


def declared_task_complexity(task: dict[str, Any]) -> str | None:
    value = task.get("complexity")
    if value is None and isinstance(task.get("next_run_recommendation"), dict):
        value = task["next_run_recommendation"].get("complexity")
    normalized = str(value or "").strip().upper()
    normalized = {"XS": "S", "S-M": "M"}.get(normalized, normalized)
    return normalized or None


def limit_key(model: str) -> tuple[str, str | None]:
    name = MODEL_LIMIT_NAMES.get(model)
    return ("model", name) if name else ("global", None)


def fresh_limits(payload: Any, max_age_minutes: int, at: dt.datetime | None = None) -> list[dict[str, Any]]:
    at = at or now_utc()
    rows = payload.get("limits") if isinstance(payload, dict) else None
    result: list[dict[str, Any]] = []
    for row in rows if isinstance(rows, list) else []:
        if not isinstance(row, dict):
            continue
        observed = parse_time(row.get("observed_at"))
        if observed is None or (at - observed).total_seconds() > max_age_minutes * 60:
            continue
        result.append(row)
    return result


def model_headroom(model: str, limits: list[dict[str, Any]], at: dt.datetime | None = None) -> dict[str, Any]:
    at = at or now_utc()
    scope, name = limit_key(model)
    matching = [row for row in limits if row.get("scope") == scope and row.get("model") == name]
    by_window: dict[str, dict[str, Any]] = {}
    for row in matching:
        window = str(row.get("window") or "")
        current = by_window.get(window)
        if current is None or str(row.get("observed_at") or "") > str(current.get("observed_at") or ""):
            by_window[window] = row
    short = by_window.get("5h")
    weekly = by_window.get("weekly")
    short_remaining = (
        int(short.get("remaining_percent"))
        if short and isinstance(short.get("remaining_percent"), (int, float))
        else None
    )
    weekly_remaining = (
        int(weekly.get("remaining_percent"))
        if weekly and isinstance(weekly.get("remaining_percent"), (int, float))
        else None
    )
    values = [value for value in (short_remaining, weekly_remaining) if value is not None]
    remaining = min(values) if values else None
    reset = parse_time(short.get("reset_at")) if short else None
    reset_minutes = max(0, int((reset - at).total_seconds() // 60)) if reset else None
    return {
        "short_remaining_percent": short_remaining,
        "weekly_remaining_percent": weekly_remaining,
        "effective_remaining_percent": remaining,
        "short_reset_at": short.get("reset_at") if short else None,
        "minutes_to_short_reset": reset_minutes,
        "source_available": bool(values),
    }


def task_risk(task: dict[str, Any]) -> str:
    recommendation, _ = recommendation_from_task(task)
    explicit = str(
        (recommendation or {}).get("risk")
        or task.get("risk")
        or task.get("risk_level")
        or ""
    ).lower()
    text = " ".join(
        str(task.get(key) or "") for key in ("title", "type", "description", "integration_status")
    ).lower()
    high_markers = ("security", "secret", "payment", "production", "migration", "release-critical", "destructive")
    if explicit == "critical":
        return "critical"
    if explicit == "high" or any(marker in text for marker in high_markers):
        return "high"
    if explicit == "low":
        return "low"
    return "normal"


def step_effort(effort: str, delta: int) -> str:
    effort = LEGACY_EFFORT_ALIASES.get(str(effort), str(effort))
    try:
        index = CANONICAL_EFFORTS.index(effort)
    except ValueError:
        index = CANONICAL_EFFORTS.index("medium")
    return CANONICAL_EFFORTS[max(0, min(len(CANONICAL_EFFORTS) - 1, index + delta))]


def raised_effort(effort: str, profile_name: str) -> str:
    candidate = step_effort(effort, 1)
    if candidate == "ultra" and profile_name != "delegated_deep":
        return "max"
    return candidate


def profile_models(policy: dict[str, Any], profile_name: str, task: dict[str, Any]) -> list[str]:
    profile = (policy.get("capability_profiles") or {}).get(profile_name)
    references = profile.get("model_preferences") if isinstance(profile, dict) else []
    models: list[str] = []
    for reference in references if isinstance(references, list) else []:
        model = resolve_model_reference(policy, reference)
        if model and model not in models:
            models.append(model)
    task_candidates = task.get("model_candidates")
    if isinstance(task_candidates, list) and task_candidates:
        allowed = {
            model
            for item in task_candidates
            if (model := resolve_model_reference(policy, item)) is not None
        }
        models = [model for model in models if model in allowed]
    return models


def legacy_models_and_effort(
    policy: dict[str, Any], role: str, task: dict[str, Any]
) -> tuple[list[str], str]:
    role_policy = (policy.get("roles") or {}).get(role) or (policy.get("roles") or {}).get("worker") or {}
    references = [str(item) for item in role_policy.get("models") or []]
    effort = canonical_effort(policy, role_policy.get("effort"), "medium")
    complexity = str(task.get("complexity") or "").upper()
    if role == "worker" and complexity in (policy.get("complexity") or {}):
        complexity_policy = policy["complexity"][complexity]
        references = [str(item) for item in complexity_policy.get("models") or references]
        effort = canonical_effort(policy, complexity_policy.get("effort"), effort)
    models: list[str] = []
    for reference in references:
        model = resolve_model_reference(policy, reference)
        if model and model not in models:
            models.append(model)
    task_candidates = task.get("model_candidates")
    if isinstance(task_candidates, list) and task_candidates:
        allowed = {
            model
            for item in task_candidates
            if (model := resolve_model_reference(policy, item)) is not None
        }
        models = [model for model in models if model in allowed]
    effort_hint = task.get("reasoning_effort_hint")
    if recognized_effort(policy, effort_hint):
        normalized_hint = canonical_effort(policy, effort_hint)
        effort = CANONICAL_EFFORTS[
            max(CANONICAL_EFFORTS.index(effort), CANONICAL_EFFORTS.index(normalized_hint))
        ]
    return models, effort


def candidate_models(policy: dict[str, Any], role: str, task: dict[str, Any]) -> tuple[list[str], str]:
    recommendation, _ = recommendation_from_task(task)
    profile_name = str((recommendation or {}).get("capability_profile") or "")
    profile = (policy.get("capability_profiles") or {}).get(profile_name)
    if isinstance(recommendation, dict) and isinstance(profile, dict):
        requested_effort = recommendation.get("reasoning_effort")
        default_effort = profile.get("default_reasoning_effort") or "medium"
        effort = canonical_effort(
            policy,
            requested_effort if recognized_effort(policy, requested_effort) else default_effort,
            str(default_effort),
        )
        return profile_models(policy, profile_name, task), effort
    if isinstance(recommendation, dict):
        return [], canonical_effort(policy, recommendation.get("reasoning_effort"), "medium")
    return legacy_models_and_effort(policy, role, task)


def score_models(
    policy: dict[str, Any],
    models: list[str],
    limits: list[dict[str, Any]],
    unavailable: set[str],
    *,
    allow_constrained: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    reserve = int(policy.get("manual_reserve_percent") or 0)
    scored: list[dict[str, Any]] = []
    for index, model in enumerate(models):
        if model in unavailable:
            continue
        headroom = model_headroom(model, limits)
        remaining = headroom["effective_remaining_percent"]
        usable = remaining is None or remaining > reserve
        reset_bonus = bool(
            headroom["minutes_to_short_reset"] is not None
            and headroom["minutes_to_short_reset"] <= 90
            and (headroom["short_remaining_percent"] or 0) >= 25
        )
        score = 200 - index * 40
        if remaining is not None:
            score += remaining
        if reset_bonus:
            score += 15
        if not usable:
            score -= 1000
        scored.append({"model": model, "score": score, "usable": usable, "headroom": headroom})
    usable = [item for item in scored if item["usable"]]
    selectable = usable or (scored if allow_constrained else [])
    return scored, max(selectable, key=lambda item: item["score"], default=None)


def ultra_ineligibility(
    policy: dict[str, Any],
    profile_name: str,
    recommendation: dict[str, Any],
    capacity_ceiling: int,
    task: dict[str, Any],
) -> tuple[str, str] | None:
    ultra = (policy.get("reasoning_effort_policy") or {}).get("ultra") or {}
    required_profile = str(ultra.get("requires_capability_profile") or "delegated_deep")
    allowed_delegation = set(ultra.get("requires_delegation") or ("allowed", "required"))
    minimum_units = int(ultra.get("minimum_independent_work_units") or 2)
    if profile_name != required_profile:
        return "ultra_profile_required", "Ultra requires the delegated_deep capability profile."
    if recommendation.get("delegation") not in allowed_delegation or task.get("delegation_permitted") is False:
        return "ultra_delegation_unavailable", "Ultra requires Router-eligible delegation."
    units = recommendation.get("independent_work_units")
    if (
        not isinstance(units, int)
        or isinstance(units, bool)
        or units < minimum_units
        or units > 32
    ):
        return "ultra_independent_units_required", "Ultra requires at least two independent work units."
    if recommendation.get("synthesis_required") is not True:
        return "ultra_synthesis_required", "Ultra requires an explicit later synthesis step."
    hop_count = recommendation.get("hop_count")
    budget = recommendation.get("escalation_budget")
    if (
        not isinstance(hop_count, int)
        or isinstance(hop_count, bool)
        or hop_count < 0
        or hop_count > 32
        or not isinstance(budget, int)
        or isinstance(budget, bool)
        or budget < 0
        or budget > 8
    ):
        return "escalation_guard_required", "Ultra requires a valid bounded hop count and escalation budget."
    if hop_count > budget:
        return "escalation_budget_exhausted", "The bounded recommendation escalation budget is exhausted."
    if capacity_ceiling < minimum_units:
        return "ultra_capacity_unavailable", "Current Router capacity cannot provide two independent lanes."
    return None


def required_delegation_ineligibility(
    profile: dict[str, Any],
    recommendation: dict[str, Any],
    capacity_ceiling: int,
    task: dict[str, Any],
) -> tuple[str, str] | None:
    if recommendation.get("delegation") != "required":
        return None
    if profile.get("delegation") not in {"allowed", "required"} or task.get("delegation_permitted") is False:
        return "required_delegation_unavailable", "Central profile policy does not permit required delegation."
    units = recommendation.get("independent_work_units")
    if not isinstance(units, int) or isinstance(units, bool) or not 2 <= units <= 32:
        return "required_delegation_units_missing", "Required delegation needs at least two independent work units."
    if recommendation.get("synthesis_required") is not True:
        return "required_delegation_synthesis_missing", "Required delegation needs an explicit later synthesis step."
    hop_count = recommendation.get("hop_count")
    budget = recommendation.get("escalation_budget")
    if (
        not isinstance(hop_count, int)
        or isinstance(hop_count, bool)
        or hop_count < 0
        or hop_count > 32
        or not isinstance(budget, int)
        or isinstance(budget, bool)
        or budget < 0
        or budget > 8
        or hop_count > budget
    ):
        return "required_delegation_budget_invalid", "Required delegation needs a valid bounded escalation budget."
    if capacity_ceiling < 2:
        return "required_delegation_capacity_unavailable", "Current Router capacity cannot provide required delegation."
    return None


def route_change_direction(recommendation: dict[str, Any], selected: dict[str, Any]) -> str:
    profile_rank = {name: index for index, name in enumerate(CAPABILITY_PROFILES)}
    effort_rank = {name: index for index, name in enumerate(CANONICAL_EFFORTS)}
    delegation_rank = {"forbidden": 0, "allowed": 1, "required": 2}
    recommended_values = (
        profile_rank.get(str(recommendation.get("capability_profile"))),
        effort_rank.get(str(recommendation.get("reasoning_effort"))),
        delegation_rank.get(str(recommendation.get("delegation"))),
    )
    selected_values = (
        profile_rank.get(str(selected.get("capability_profile"))),
        effort_rank.get(str(selected.get("reasoning_effort"))),
        delegation_rank.get(str(selected.get("delegation"))),
    )
    if None in recommended_values or None in selected_values:
        return "substituted"
    deltas = [selected_values[index] - recommended_values[index] for index in range(3)]
    if not any(deltas):
        return "none"
    if all(delta >= 0 for delta in deltas):
        return "raised"
    if all(delta <= 0 for delta in deltas):
        return "lowered"
    return "substituted"


def choose_model(
    policy: dict[str, Any],
    role: str,
    task: dict[str, Any],
    limits: list[dict[str, Any]],
    unavailable: set[str] | None = None,
    unavailable_efforts: set[str] | None = None,
    capacity_ceiling: int | None = None,
) -> dict[str, Any]:
    unavailable_ids: set[str] = set()
    for item in unavailable or set():
        resolved = resolve_model_reference(policy, item)
        if resolved or str(item):
            unavailable_ids.add(resolved or str(item))
    unavailable_efforts = {
        canonical_effort(policy, item)
        for item in (unavailable_efforts or set())
        if recognized_effort(policy, item)
    }
    capacity_ceiling = max(1, int(capacity_ceiling or 1))
    if task.get("next_run") == "none":
        return {
            "status": "terminal_none",
            "reason": "terminal_next_run_none",
            "reason_code": "terminal_next_run_none",
            "model": None,
            "reasoning_effort": None,
            "candidates": [],
            "selection_source": "next_run_contract",
        }

    complexity = declared_task_complexity(task)
    task_identity = task.get("id") or task.get("task_id")
    if task_identity and complexity is None:
        return {
            "status": "blocked",
            "reason": "missing_task_complexity",
            "reason_code": "missing_task_complexity",
            "human_reason": "The identified executable task has no S, M, L or XL complexity classification.",
            "model": None,
            "reasoning_effort": None,
            "candidates": [],
            "selection_source": "task_complexity_gate",
        }
    if complexity is not None and complexity not in TASK_COMPLEXITIES:
        return {
            "status": "blocked",
            "reason": "unknown_task_complexity",
            "reason_code": "unknown_task_complexity",
            "human_reason": "The executable task complexity must be one of S, M, L or XL.",
            "model": None,
            "reasoning_effort": None,
            "candidates": [],
            "selection_source": "task_complexity_gate",
        }

    recommendation, source = recommendation_from_task(task)
    profiles = policy.get("capability_profiles") or {}
    fallback: dict[str, Any] | None = None
    fallback_reason: tuple[str, str] | None = None
    risk = task_risk(task)
    attempts = int(task.get("attempt_count") or task.get("worker_attempt_count") or 0)
    adjustment_reason: tuple[str, str] | None = None
    recommended_profile_name: str | None = None
    recommended_effort: str | None = None
    legacy_floor_applied = False
    legacy_effort_downgraded = False
    if isinstance(recommendation, dict):
        if not registry(policy):
            return {
                "status": "blocked",
                "reason": "model_registry_unavailable",
                "reason_code": "model_registry_unavailable",
                "human_reason": "The central model registry is unavailable, so a capability route cannot be selected safely.",
                "model": None,
                "reasoning_effort": None,
                "candidates": [],
                "selection_source": source,
            }
        profile_name = str(recommendation.get("capability_profile") or "")
        profile = profiles.get(profile_name)
        if profile_name not in CAPABILITY_PROFILES or not isinstance(profile, dict):
            return {
                "status": "blocked",
                "reason": "unknown_capability_profile",
                "reason_code": "unknown_capability_profile",
                "human_reason": "The requested capability profile is not defined by central routing policy.",
                "model": None,
                "reasoning_effort": None,
                "candidates": [],
                "selection_source": source,
            }
        requested_effort = recommendation.get("reasoning_effort")
        if requested_effort is not None and not recognized_effort(policy, requested_effort):
            return {
                "status": "blocked",
                "reason": "unknown_reasoning_effort",
                "reason_code": "unknown_reasoning_effort",
                "human_reason": "The requested reasoning effort is not recognized by central routing policy.",
                "model": None,
                "reasoning_effort": None,
                "candidates": [],
                "selection_source": source,
            }
        effort = canonical_effort(
            policy,
            requested_effort or profile.get("default_reasoning_effort"),
            str(profile.get("default_reasoning_effort") or "medium"),
        )
        recommended_profile_name = profile_name
        recommended_effort = effort
        if risk == "critical":
            raised = raised_effort(effort, profile_name)
            if raised != effort:
                effort = raised
                adjustment_reason = (
                    "critical_risk_floor",
                    "Router raised the reasoning floor because the route is critical risk.",
                )
        elif attempts > 0:
            raised = raised_effort(effort, profile_name)
            if raised != effort:
                effort = raised
                adjustment_reason = (
                    "retry_reasoning_floor",
                    "Router raised the reasoning floor after an unsuccessful prior attempt.",
                )
        models = profile_models(policy, profile_name, task)
        fallback_value = recommendation.get("fallback")
        fallback = fallback_value if isinstance(fallback_value, dict) else None
        if effort in unavailable_efforts:
            fallback_reason = (
                "ultra_capacity_unavailable"
                if effort == "ultra"
                else "required_override_effort_unavailable"
                if adjustment_reason is not None
                else "reasoning_effort_unavailable",
                "The required reasoning mode is unavailable; Router evaluated the configured fallback.",
            )
        elif effort == "ultra":
            fallback_reason = ultra_ineligibility(policy, profile_name, recommendation, capacity_ceiling, task)
        elif recommendation.get("delegation") == "required":
            fallback_reason = required_delegation_ineligibility(
                profile,
                recommendation,
                capacity_ceiling,
                task,
            )
    else:
        models, effort = legacy_models_and_effort(policy, role, task)
        if risk in {"high", "critical"} or attempts > 0:
            effort = raised_effort(effort, PROFILE_BY_EFFORT[effort])
            legacy_floor_applied = True
        if role not in {"architect", "integrator"} and effort in {"max", "ultra"}:
            effort = "extra_high"
        if effort in unavailable_efforts:
            effort_index = CANONICAL_EFFORTS.index(effort)
            available_lower = [
                item
                for item in CANONICAL_EFFORTS[: effort_index + 1]
                if item not in unavailable_efforts
            ]
            if available_lower:
                effort = available_lower[-1]
                legacy_effort_downgraded = True
            else:
                fallback_reason = (
                    "reasoning_effort_unavailable",
                    "No compatible legacy reasoning effort is currently available.",
                )
        profile_name = PROFILE_BY_EFFORT[effort]
        profile = profiles.get(profile_name) or {}

    scored: list[dict[str, Any]] = []
    selected: dict[str, Any] | None = None
    lifecycle_status = "selected"
    fallback_from: str | None = None
    if fallback_reason is None:
        scored, selected = score_models(
            policy,
            models,
            limits,
            unavailable_ids,
            allow_constrained=recommendation is None,
        )
        if selected is None:
            fallback_reason = (
                "recommended_route_unavailable",
                "No enabled model has usable capacity for the recommended route.",
            )

    if fallback_reason is not None and isinstance(recommendation, dict) and fallback is not None:
        fallback_profile_name = str(fallback.get("capability_profile") or "")
        fallback_profile = profiles.get(fallback_profile_name)
        fallback_effort_value = fallback.get("reasoning_effort")
        if (
            fallback_profile_name not in CAPABILITY_PROFILES
            or not isinstance(fallback_profile, dict)
            or not recognized_effort(policy, fallback_effort_value)
        ):
            selected = None
            scored = []
            fallback_reason = (
                "invalid_fallback_route",
                "The configured fallback is not defined by central routing policy.",
            )
        else:
            fallback_effort = canonical_effort(policy, fallback_effort_value)
            primary_profile_rank = CAPABILITY_PROFILES.index(recommended_profile_name or profile_name)
            fallback_profile_rank = CAPABILITY_PROFILES.index(fallback_profile_name)
            if (
                fallback_profile_rank > primary_profile_rank
                or CANONICAL_EFFORTS.index(fallback_effort)
                > CANONICAL_EFFORTS.index(recommended_effort or effort)
                or (
                    fallback_profile_rank == primary_profile_rank
                    and fallback_effort == (recommended_effort or effort)
                )
            ):
                selected = None
                scored = []
                fallback_reason = (
                    "invalid_fallback_escalation",
                    "Fallback must be a different route and cannot exceed the recommendation.",
                )
            elif fallback_effort in unavailable_efforts:
                selected = None
                scored = []
                fallback_reason = (
                    "fallback_reasoning_effort_unavailable",
                    "The configured fallback reasoning effort is unavailable.",
                )
            else:
                fallback_models = profile_models(policy, fallback_profile_name, task)
                scored, selected = score_models(policy, fallback_models, limits, unavailable_ids)
                if selected is not None:
                    fallback_from = (
                        f"{recommended_profile_name or profile_name}/"
                        f"{recommended_effort or effort}"
                    )
                    profile_name = fallback_profile_name
                    profile = fallback_profile
                    effort = fallback_effort
                    lifecycle_status = "fallback_selected"
                else:
                    fallback_reason = (
                        "fallback_route_unavailable",
                        "No enabled model has usable capacity for the configured fallback route.",
                    )

    if selected is None:
        reason_code, human_reason = fallback_reason or (
            "no_available_model",
            "No enabled model is available for the requested route.",
        )
        return {
            "status": "blocked",
            "reason": reason_code,
            "reason_code": reason_code,
            "human_reason": human_reason,
            "model": None,
            "reasoning_effort": effort,
            "risk": risk,
            "candidates": scored,
            "selection_source": source,
        }

    remaining = selected["headroom"]["effective_remaining_percent"]
    reset_minutes = selected["headroom"]["minutes_to_short_reset"]
    reset_soon = reset_minutes is not None and reset_minutes <= 90
    if (
        lifecycle_status == "selected"
        and recommendation is None
        and not legacy_floor_applied
        and remaining is not None
        and remaining >= 70
        and reset_soon
    ):
        raised = raised_effort(effort, PROFILE_BY_EFFORT[effort])
        if raised not in unavailable_efforts:
            effort = raised
    if recommendation is None and role not in {"architect", "integrator"} and effort in {"max", "ultra"}:
        effort = "extra_high"
    small_task_budget_applied = False
    if (
        recommendation is None
        and role == "worker"
        and complexity == "S"
        and risk not in {"high", "critical"}
        and attempts == 0
        and not task.get("reasoning_effort_hint")
        and not task.get("reasoning_effort")
    ):
        effort = "low"
        small_task_budget_applied = True
    if recommendation is None:
        profile_name = PROFILE_BY_EFFORT[effort]
        profile = profiles.get(profile_name) or {}

    requested_delegation = (
        "forbidden"
        if lifecycle_status == "fallback_selected"
        else str((recommendation or {}).get("delegation") or "forbidden")
    )
    policy_delegation = str(profile.get("delegation") or "forbidden")
    if policy_delegation == "forbidden" or requested_delegation not in {"allowed", "required"}:
        delegation = "forbidden"
    else:
        delegation = requested_delegation
    units = (recommendation or {}).get("independent_work_units")
    parallel_lanes = 1
    delegation_authorized = False
    if (
        (effort == "ultra" or delegation == "required")
        and delegation in {"allowed", "required"}
        and isinstance(units, int)
        and not isinstance(units, bool)
    ):
        parallel_lanes = max(2, min(units, capacity_ceiling, 64))
        delegation_authorized = parallel_lanes > 1

    selected_route = {
        "capability_profile": profile_name,
        "model_id": selected["model"],
        "reasoning_effort": effort,
        "delegation": delegation,
        "parallel_lanes": parallel_lanes,
        "cost_class": str(profile.get("cost_class") or "standard"),
    }
    override = {"applied": False, "direction": "none"}
    if lifecycle_status == "fallback_selected":
        reason_code, human_reason = fallback_reason or (
            "recommended_route_unavailable",
            "Router selected the configured fallback route.",
        )
        override = {
            "applied": True,
            "direction": "fallback",
            "reason_code": reason_code,
            "human_reason": human_reason,
        }
    elif isinstance(recommendation, dict):
        normalized_recommendation = dict(recommendation)
        normalized_recommendation["reasoning_effort"] = canonical_effort(
            policy,
            recommendation.get("reasoning_effort") or profile.get("default_reasoning_effort"),
        )
        direction = route_change_direction(normalized_recommendation, selected_route)
        if direction != "none":
            reason_code, human_reason = adjustment_reason or (
                "policy_capability_boundary",
                "Router changed the recommendation to remain within central capability policy.",
            )
            override = {
                "applied": True,
                "direction": direction,
                "reason_code": reason_code,
                "human_reason": human_reason,
            }
    return {
        "status": "selected",
        "reason": "limit_aware_role_and_task_routing",
        "reason_code": (
            "fallback_selected"
            if lifecycle_status == "fallback_selected"
            else "small_task_resource_budget"
            if small_task_budget_applied
            else "legacy_effort_downgraded"
            if legacy_effort_downgraded
            else "route_selected"
        ),
        "model": selected["model"],
        "reasoning_effort": effort,
        "capability_profile": profile_name,
        "delegation": delegation,
        "parallel_lanes": parallel_lanes,
        "cost_class": selected_route["cost_class"],
        "delegation_authorized": delegation_authorized,
        "risk": risk,
        "candidates": scored,
        "headroom": selected["headroom"],
        "selection_source": source,
        "lifecycle_status": lifecycle_status,
        "fallback_from": fallback_from,
        "override": override,
    }


def recommend_capacity(policy: dict[str, Any], limits: list[dict[str, Any]]) -> dict[str, Any]:
    capacity = policy.get("capacity") or {}
    models = (SPARK, "gpt-5.6-luna")
    remaining = [model_headroom(model, limits)["effective_remaining_percent"] for model in models]
    known = [value for value in remaining if value is not None]
    effective = max(known) if known else None
    if effective is None:
        workers = int(capacity.get("normal_workers") or 4)
        tier = "unknown_limits"
    elif effective >= int(capacity.get("maximum_remaining_percent") or 80):
        workers = int(capacity.get("maximum_workers") or 10)
        tier = "maximum"
    elif effective >= int(capacity.get("high_remaining_percent") or 60):
        workers = int(capacity.get("high_workers") or 6)
        tier = "high"
    elif effective > int(policy.get("manual_reserve_percent") or 10):
        workers = int(capacity.get("normal_workers") or 4)
        tier = "normal"
    else:
        workers = int(capacity.get("minimum_workers") or 2)
        tier = "constrained"
    return {"recommended_max_workers": workers, "capacity_tier": tier, "effective_remaining_percent": effective}


def utc_timestamp(value: dt.datetime | None = None) -> str:
    current = (value or now_utc()).astimezone(dt.timezone.utc)
    return current.isoformat(timespec="microseconds").replace("+00:00", "Z")


def build_router_decision(
    policy: dict[str, Any],
    result: dict[str, Any],
    task: dict[str, Any],
    role: str,
    limits_ref: str,
    limits: list[dict[str, Any]],
) -> dict[str, Any]:
    lifecycle_status = str(result.get("lifecycle_status") or result.get("status") or "blocked")
    if lifecycle_status == "selected":
        status = "selected"
    elif lifecycle_status == "fallback_selected":
        status = "fallback_selected"
    elif lifecycle_status == "terminal_none":
        status = "terminal_none"
    else:
        status = "blocked"
    selected_route: dict[str, Any] | None = None
    if status in {"selected", "fallback_selected"}:
        selected_route = {
            "capability_profile": result["capability_profile"],
            "model_id": result["model"],
            "reasoning_effort": result["reasoning_effort"],
            "delegation": result["delegation"],
            "parallel_lanes": result["parallel_lanes"],
            "cost_class": result["cost_class"],
        }
        selected_route["selected_route_digest"] = canonical_digest(selected_route)
    decided_at = utc_timestamp()
    identity_seed = {
        "decision_nonce": uuid.uuid4().hex,
        "policy_version": str(policy.get("schema_version") or "1.0"),
        "policy_digest": canonical_digest(policy),
        "status": status,
        "decided_at": decided_at,
        "role": role,
        "task_id": task.get("id") or task.get("task_id"),
        "correlation_id": task.get("correlation_id"),
        "task_digest": canonical_digest(task),
        "recommendation_digest": canonical_digest(task.get("next_run_recommendation"))
        if isinstance(task.get("next_run_recommendation"), dict)
        else None,
        "selected": selected_route,
        "reason_code": result.get("reason_code"),
        "override": result.get("override"),
        "limits_digest": canonical_digest(limits),
    }
    decision_id = "router:decision:" + canonical_digest(identity_seed).split(":", 1)[1][:24]
    decision: dict[str, Any] = {
        "status": status,
        "policy_version": str(policy.get("schema_version") or "1.0"),
        "decided_at": decided_at,
        "decision_id": decision_id,
    }
    if status in {"selected", "fallback_selected"}:
        decision.update({
            "selected": selected_route,
            "override": result.get("override") or {"applied": False, "direction": "none"},
            "limits_snapshot_ref": limits_ref,
            "delegation_authorized": bool(result.get("delegation_authorized")),
        })
        if status == "fallback_selected":
            decision["fallback_from"] = str(result.get("fallback_from") or "recommended-route")
    elif status == "blocked":
        decision.update({
            "block_reason_code": str(result.get("reason_code") or "no_available_model"),
            "human_reason": str(result.get("human_reason") or "Router could not select an enabled route."),
        })
    decision["router_decision_digest"] = canonical_digest(decision)
    return decision


def authorization_denied(reason_code: str, human_reason: str) -> dict[str, Any]:
    return {
        "status": "denied",
        "reason_code": reason_code,
        "human_reason": human_reason,
        "authorization": None,
        "capacity_evaluation": None,
        "execution_state": {
            "authorization_status": "not_issued",
            "launch_status": "not_started",
            "actually_used": None,
        },
    }


def authorization_time(value: dt.datetime | str | None) -> dt.datetime | None:
    if value is None:
        return now_utc()
    if isinstance(value, dt.datetime):
        if value.tzinfo is None:
            return None
        return value.astimezone(dt.timezone.utc)
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(dt.timezone.utc)


def valid_contract_id(value: Any) -> bool:
    return isinstance(value, str) and CONTRACT_ID_RE.fullmatch(value) is not None


def valid_digest(value: Any) -> bool:
    return isinstance(value, str) and DIGEST_RE.fullmatch(value) is not None


def authorize_delegation(
    router_decision: dict[str, Any],
    plan_binding: dict[str, Any],
    capacity_limits: dict[str, Any],
    *,
    decision_provenance: dict[str, Any] | None = None,
    provenance_key: bytes | str | None = None,
    issued_at: dt.datetime | str | None = None,
    ttl_seconds: int = DEFAULT_AUTHORIZATION_TTL_SECONDS,
) -> dict[str, Any]:
    """Issue one immutable aggregate Router grant without launching or consuming it."""

    if not isinstance(router_decision, dict):
        return authorization_denied(
            "router_decision_invalid",
            "Delegation authorization requires an exact Router decision object.",
        )
    if router_decision.get("status") != "selected":
        return authorization_denied(
            "router_decision_not_authorizable",
            "Only a primary selected Router decision can authorize delegated execution.",
        )
    if router_decision.get("delegation_authorized") is not True:
        return authorization_denied(
            "router_delegation_not_authorized",
            "The selected Router decision does not authorize a parallel delegation envelope.",
        )
    selected = router_decision.get("selected")
    if not isinstance(selected, dict):
        return authorization_denied(
            "selected_route_missing",
            "Delegation authorization requires the exact selected route.",
        )
    decision_id = router_decision.get("decision_id")
    decision_digest = router_decision.get("router_decision_digest")
    selected_route_digest = selected.get("selected_route_digest")
    if not valid_contract_id(decision_id) or not valid_digest(decision_digest):
        return authorization_denied(
            "router_decision_binding_invalid",
            "Router decision identity and digest must use canonical contract formats.",
        )
    if not valid_digest(selected_route_digest):
        return authorization_denied(
            "selected_route_binding_invalid",
            "Selected route digest must use the canonical contract format.",
        )
    unsigned_decision = dict(router_decision)
    unsigned_decision.pop("router_decision_digest", None)
    if canonical_digest(unsigned_decision) != decision_digest:
        return authorization_denied(
            "router_decision_digest_mismatch",
            "Router decision content does not match its recorded digest.",
        )
    unsigned_route = dict(selected)
    unsigned_route.pop("selected_route_digest", None)
    if canonical_digest(unsigned_route) != selected_route_digest:
        return authorization_denied(
            "selected_route_digest_mismatch",
            "Selected route content does not match its recorded digest.",
        )
    key = normalize_provenance_key(provenance_key)
    if key is None:
        return authorization_denied(
            "router_provenance_key_invalid",
            "Delegation authorization requires a trusted Router provenance key of at least 32 bytes.",
        )
    if not isinstance(decision_provenance, dict):
        return authorization_denied(
            "router_decision_provenance_missing",
            "The exact selected Router decision must carry separate issuer provenance.",
        )
    if not verify_hmac_proof(
        decision_provenance,
        "router_decision",
        decision_provenance_payload(router_decision),
        key,
    ):
        return authorization_denied(
            "router_decision_provenance_mismatch",
            "Router decision provenance does not verify against the trusted control-plane key.",
        )
    decision_provenance_digest = canonical_digest(decision_provenance)
    router_provenance_key_id = provenance_key_id(key)
    router_lanes = selected.get("parallel_lanes")
    if (
        selected.get("delegation") not in {"allowed", "required"}
        or not isinstance(router_lanes, int)
        or isinstance(router_lanes, bool)
        or not 2 <= router_lanes <= 64
    ):
        return authorization_denied(
            "selected_route_not_delegable",
            "Selected route must permit delegation and contain at least two bounded lanes.",
        )

    issued = authorization_time(issued_at)
    if issued is None:
        return authorization_denied(
            "authorization_time_invalid",
            "Authorization issue time must be a valid timezone-aware timestamp.",
        )
    if (
        not isinstance(ttl_seconds, int)
        or isinstance(ttl_seconds, bool)
        or not 1 <= ttl_seconds <= MAX_AUTHORIZATION_TTL_SECONDS
    ):
        return authorization_denied(
            "authorization_ttl_invalid",
            "Authorization TTL must be an integer between 1 and 3600 seconds.",
        )
    decided_at = router_decision.get("decided_at")
    decided = authorization_time(decided_at) if decided_at is not None else None
    if decided is None or issued < decided:
        return authorization_denied(
            "router_decision_time_invalid",
            "Authorization cannot predate its Router decision.",
        )
    if (issued - decided).total_seconds() > MAX_AUTHORIZATION_TTL_SECONDS:
        return authorization_denied(
            "router_decision_stale",
            "Router decision is too old to issue a fresh delegation authorization.",
        )

    if not isinstance(plan_binding, dict) or plan_binding.get("status") != "validated":
        return authorization_denied(
            "validated_plan_required",
            "Delegation authorization requires a caller-supplied validated plan binding.",
        )
    if (
        plan_binding.get("contract_kind") != "parallel_work"
        or plan_binding.get("contract_version") != "1.0.0"
    ):
        return authorization_denied(
            "plan_contract_invalid",
            "Validated plan binding must identify Parallel Work contract 1.0.0.",
        )
    authority_guard = plan_binding.get("authority_guard")
    if (
        not isinstance(authority_guard, dict)
        or set(authority_guard) != set(AUTHORITY_GUARD_FIELDS)
        or any(authority_guard.get(field) is not False for field in AUTHORITY_GUARD_FIELDS)
    ):
        return authorization_denied(
            "plan_authority_forbidden",
            "Validated plan binding must preserve every authority guard as false.",
        )
    plan_id = plan_binding.get("plan_id")
    plan_digest = plan_binding.get("plan_content_digest")
    if not valid_contract_id(plan_id) or not valid_digest(plan_digest):
        return authorization_denied(
            "plan_binding_invalid",
            "Plan identity and content digest must use canonical contract formats.",
        )
    if (
        plan_binding.get("router_decision_id") != decision_id
        or plan_binding.get("router_decision_digest") != decision_digest
    ):
        return authorization_denied(
            "plan_router_binding_mismatch",
            "Validated plan must seal the exact Router decision id and digest being authorized.",
        )
    expected_plan_digest = compute_plan_content_digest_v1(plan_binding)
    if expected_plan_digest is None:
        return authorization_denied(
            "plan_content_projection_invalid",
            "Delegation authorization requires the complete execution-defining Parallel Work plan.",
        )
    if plan_digest != expected_plan_digest:
        return authorization_denied(
            "plan_content_digest_mismatch",
            "Plan content does not match its sealed execution-defining digest.",
        )
    raw_units = plan_binding.get("work_units")
    if not isinstance(raw_units, list) or not 2 <= len(raw_units) <= MAX_AUTHORIZED_WORK_UNITS:
        return authorization_denied(
            "work_unit_scope_invalid",
            "Delegated authorization requires between 2 and 32 bound work units.",
        )
    units: list[dict[str, str]] = []
    seen_units: set[str] = set()
    for item in raw_units:
        if not isinstance(item, dict):
            return authorization_denied(
                "work_unit_scope_invalid",
                "Every authorization work unit must be an object with exact input binding.",
            )
        unit_id = item.get("work_unit_id")
        input_digest = item.get("input_bundle_digest")
        if item.get("access_mode") != "read_only" or item.get("mutation_allowed") is not False:
            return authorization_denied(
                "work_unit_authority_forbidden",
                "Every authorized delegated work unit must remain explicitly read-only.",
            )
        if not valid_contract_id(unit_id) or not valid_digest(input_digest) or unit_id in seen_units:
            return authorization_denied(
                "work_unit_scope_invalid",
                "Work-unit ids must be unique and every unit must bind a canonical input digest.",
            )
        seen_units.add(unit_id)
        skill_bindings = item.get("skill_bindings", [])
        if skill_bindings_digest_v1(skill_bindings) is None:
            return authorization_denied(
                "skill_binding_scope_invalid",
                "Every authorized work unit must carry an exact ordered skill-binding list.",
            )
        units.append(
            {
                "work_unit_id": unit_id,
                "input_bundle_digest": input_digest,
                "skill_bindings": skill_bindings,
            }
        )
    units.sort(key=lambda item: item["work_unit_id"])
    plan_capacity = plan_binding.get("capacity")
    if not isinstance(plan_capacity, dict):
        return authorization_denied(
            "plan_capacity_invalid",
            "Validated delegated plan must contain a canonical capacity object.",
        )
    requested_lanes = plan_capacity.get("requested_lanes")
    max_attempts = plan_capacity.get("max_attempts_per_unit")
    if (
        not isinstance(requested_lanes, int)
        or isinstance(requested_lanes, bool)
        or not 2 <= requested_lanes <= len(units)
    ):
        return authorization_denied(
            "plan_capacity_invalid",
            "Validated delegated plan must request between two lanes and its work-unit count.",
        )
    if (
        not isinstance(max_attempts, int)
        or isinstance(max_attempts, bool)
        or not 1 <= max_attempts <= MAX_ATTEMPTS_PER_UNIT
    ):
        return authorization_denied(
            "attempt_budget_invalid",
            "A work unit may have one initial attempt and at most one unchanged technical retry.",
        )

    if not isinstance(capacity_limits, dict) or set(capacity_limits) != {
        "project_policy",
        "worker_policy",
        "runtime",
    }:
        return authorization_denied(
            "capacity_envelope_invalid",
            "Exactly project, worker and runtime capacity ceilings are required.",
        )
    capacity_values: dict[str, int] = {}
    for source in ("project_policy", "worker_policy", "runtime"):
        value = capacity_limits.get(source)
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= 64:
            return authorization_denied(
                "capacity_envelope_invalid",
                "Every capacity ceiling must be an integer between 1 and 64.",
            )
        capacity_values[source] = value
    authorized_lanes = min(
        router_lanes,
        requested_lanes,
        len(units),
        capacity_values["project_policy"],
        capacity_values["worker_policy"],
        capacity_values["runtime"],
    )
    if authorized_lanes < 2:
        return authorization_denied(
            "delegation_capacity_unavailable",
            "The aggregate capacity envelope cannot provide two delegated lanes.",
        )
    max_total_invocations = len(units) * max_attempts
    capacity_seed = {
        "router_authorized_lanes": router_lanes,
        "plan_requested_lanes": requested_lanes,
        "plan_work_units": len(units),
        "project_policy_ceiling": capacity_values["project_policy"],
        "worker_policy_ceiling": capacity_values["worker_policy"],
        "runtime_capacity_ceiling": capacity_values["runtime"],
        "authorized_lanes": authorized_lanes,
        "max_attempts_per_unit": max_attempts,
        "max_total_invocations": max_total_invocations,
    }
    budget_digest = canonical_digest(capacity_seed)
    capacity_envelope = dict(capacity_seed)
    capacity_envelope["budget_digest"] = budget_digest
    work_unit_scope_digest = canonical_digest([item["work_unit_id"] for item in units])
    input_scope_digest = compute_input_scope_digest_v1(plan_binding)
    if input_scope_digest is None:
        return authorization_denied(
            "input_scope_binding_invalid",
            "Every work unit must expose a canonical input bundle digest on the validated plan.",
        )
    skill_binding_rows = [
        {"work_unit_id": item["work_unit_id"], "skill_bindings": item["skill_bindings"]}
        for item in units
    ]
    skill_bindings_digest = canonical_digest(skill_binding_rows)
    scope_seed = {
        "router_decision_id": decision_id,
        "router_decision_digest": decision_digest,
        "router_decision_provenance_digest": decision_provenance_digest,
        "router_provenance_key_id": router_provenance_key_id,
        "selected_route_digest": selected_route_digest,
        "plan_id": plan_id,
        "plan_content_digest": plan_digest,
        "work_unit_scope_digest": work_unit_scope_digest,
        "input_scope_digest": input_scope_digest,
        "skill_bindings_digest": skill_bindings_digest,
    }
    bound_scope_digest = canonical_digest(scope_seed)
    expires = issued + dt.timedelta(seconds=ttl_seconds)
    issued_text = utc_timestamp(issued)
    expires_text = utc_timestamp(expires)
    authorization_seed = {
        "bound_scope_digest": bound_scope_digest,
        "budget_digest": budget_digest,
        "issued_at": issued_text,
        "expires_at": expires_text,
    }
    authorization_id = "router:authorization:" + canonical_digest(authorization_seed).split(":", 1)[1][:24]
    grant: dict[str, Any] = {
        "authorization_version": AUTHORIZATION_VERSION,
        "authorization_id": authorization_id,
        "issuer": "model_resource_router",
        "status": "granted",
        "issued_at": issued_text,
        "expires_at": expires_text,
        "digest_profile": AUTHORIZATION_DIGEST_PROFILE,
        "immutable": True,
        "router_decision_id": decision_id,
        "router_decision_digest": decision_digest,
        "router_decision_provenance_digest": decision_provenance_digest,
        "router_provenance_key_id": router_provenance_key_id,
        "selected_route_digest": selected_route_digest,
        "plan_id": plan_id,
        "plan_content_digest": plan_digest,
        "work_unit_scope_digest": work_unit_scope_digest,
        "input_scope_digest": input_scope_digest,
        "skill_bindings_digest": skill_bindings_digest,
        "skill_bindings": skill_binding_rows,
        "bound_scope_digest": bound_scope_digest,
        "authorized_lanes": authorized_lanes,
        "max_attempts_per_unit": max_attempts,
        "max_total_invocations": max_total_invocations,
        "budget_digest": budget_digest,
        "invocation_grants_single_use": True,
        "capacity_envelope": capacity_envelope,
    }
    grant["grant_digest"] = canonical_digest(grant)
    proof_payload = {
        "authorization_id": authorization_id,
        "grant_digest": grant["grant_digest"],
        "router_decision_provenance_digest": decision_provenance_digest,
    }
    grant["authorization_proof"] = build_hmac_proof(
        "delegation_authorization",
        proof_payload,
        key,
    )
    return {
        "status": "authorized",
        "reason_code": "delegation_authorization_granted",
        "human_reason": "Central Router issued an immutable bounded plan authorization; no execution was started.",
        "authorization": grant,
        "capacity_evaluation": capacity_envelope,
        "execution_state": {
            "authorization_status": "issued",
            "launch_status": "not_started",
            "actually_used": None,
        },
    }


def recommended_route_summary(policy: dict[str, Any], task: dict[str, Any]) -> dict[str, Any] | None:
    recommendation, source = recommendation_from_task(task)
    if not isinstance(recommendation, dict):
        return None
    profile_name = str(recommendation.get("capability_profile") or "")
    profile = (policy.get("capability_profiles") or {}).get(profile_name) or {}
    effort_value = recommendation.get("reasoning_effort") or profile.get("default_reasoning_effort")
    effort = canonical_effort(policy, effort_value) if recognized_effort(policy, effort_value) else None
    return {
        "source": source,
        "complexity": declared_task_complexity(task),
        "capability_profile": profile_name or None,
        "reasoning_effort": effort,
        "delegation": recommendation.get("delegation") or "forbidden",
        "independent_work_units": recommendation.get("independent_work_units"),
        "synthesis_required": recommendation.get("synthesis_required"),
    }


def route(
    project_root: Path,
    runtime_root: Path,
    role: str,
    task: dict[str, Any] | None = None,
    policy_path: Path | None = None,
    limits_path: Path | None = None,
    unavailable: set[str] | None = None,
    unavailable_efforts: set[str] | None = None,
    provenance_key: bytes | str | None = None,
) -> dict[str, Any]:
    policy = load_policy(project_root, policy_path)
    limits_file = limits_path or runtime_root / "codex-limits" / "latest.json"
    limits_payload = load_json(limits_file)
    limits = fresh_limits(limits_payload, int(policy.get("limits_max_age_minutes") or 60))
    normalized_role = normalize_role(role)
    task_value = task or {}
    capacity = recommend_capacity(policy, limits)
    result = choose_model(
        policy,
        normalized_role,
        task_value,
        limits,
        unavailable,
        unavailable_efforts,
        int(capacity["recommended_max_workers"]),
    )
    result.update(capacity)
    result["router_decision"] = build_router_decision(
        policy,
        result,
        task_value,
        normalized_role,
        "runtime/codex-limits/latest.json",
        limits,
    )
    decision_proof = build_decision_provenance(
        result["router_decision"],
        provenance_key,
    )
    if decision_proof is not None:
        result["router_decision_provenance"] = decision_proof
    result["recommended_route"] = recommended_route_summary(policy, task_value)
    if result["router_decision"]["status"] == "terminal_none":
        result["execution_evidence"] = {
            "status": "not_applicable",
            "reconciliation_status": "not_applicable",
        }
        result["execution_state"] = {
            "authorization_status": "not_applicable",
            "launch_status": "not_applicable",
            "actually_used": None,
        }
    else:
        result["execution_evidence"] = {
            "status": "not_started",
            "reconciliation_status": "pending",
        }
        result["execution_state"] = {
            "authorization_status": "not_issued",
            "launch_status": "not_started",
            "actually_used": None,
        }
    result.update({
        "schema_version": "1.0",
        "router_lifecycle_version": "1.0",
        "role": normalized_role,
        "task_id": task_value.get("id") or task_value.get("task_id"),
        "task_complexity": declared_task_complexity(task_value),
        "limits_path": str(limits_file),
        "limits_fresh": bool(limits),
    })
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--role", required=True)
    parser.add_argument("--task-json")
    parser.add_argument("--task-file")
    parser.add_argument("--policy")
    parser.add_argument("--limits")
    parser.add_argument("--unavailable-model", action="append", default=[])
    parser.add_argument("--unavailable-effort", action="append", default=[])
    parser.add_argument("--format", choices=("json", "tsv"), default="json")
    args = parser.parse_args()
    task: dict[str, Any] = {}
    if args.task_json:
        value = json.loads(args.task_json)
        task = value if isinstance(value, dict) else {}
    elif args.task_file:
        value = load_json(Path(args.task_file).expanduser())
        task = value if isinstance(value, dict) else {}
    result = route(
        Path(args.project_root).resolve(),
        Path(args.runtime_root).expanduser().resolve(),
        args.role,
        task,
        Path(args.policy).resolve() if args.policy else None,
        Path(args.limits).resolve() if args.limits else None,
        set(args.unavailable_model),
        set(args.unavailable_effort),
    )
    if args.format == "tsv":
        print(f"{result.get('model') or ''}\t{result.get('reasoning_effort') or ''}\t{result.get('recommended_max_workers') or 0}")
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("status") in {"selected", "terminal_none"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
