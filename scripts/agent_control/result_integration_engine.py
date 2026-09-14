#!/usr/bin/env python3
"""Build one deterministic Result Integration Contract from closed lane evidence.

This module is an authority-free Integrator support utility.  It accounts every
expected invocation through the existing lane-accounting API, validates complete
Result Envelopes, checks evidence-bound conflict dispositions, and emits an exact
digest-bound synthesis.  It does not select a model, authorize or launch another
run, mutate Task Manager, or grant Finalizer/merge authority.
"""

from __future__ import annotations

import argparse
import copy
import json
import re
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from execution_contract_validator import validate as validate_execution_contract
from result_lane_accounting import account_parallel_work_results, canonical_digest


CONTRACT_VERSION = "1.0.0"
ID_RE = re.compile(r"^[a-z][a-z0-9._:-]{2,127}$")
CONFLICT_CLASSES = {
    "factual",
    "evidence",
    "requirement_interpretation",
    "architecture",
    "implementation",
    "state",
    "path_artifact",
    "policy_authority",
    "staleness",
    "scope",
    "security",
    "irreversible",
}
CONFLICT_RISKS = {"low", "medium", "high", "critical"}
CONFLICT_RESOLUTION_STATUSES = {"resolved", "unresolved", "routed"}
CONFLICT_RESOLUTION_METHODS = {
    "owner_decision",
    "hard_policy",
    "verified_reality",
    "reproducible_evidence",
    "domain_expertise",
    "majority",
    "confidence",
    "unresolved",
}
SUBJECTIVE_CONFLICT_CLASSES = {
    "requirement_interpretation",
    "architecture",
    "implementation",
    "path_artifact",
    "scope",
}
READY_STATUSES = {"ready_for_finalizer", "accepted_with_residual_risk"}
NEXT_RUN_KINDS = {"targeted_retry", "specialist", "owner_escalation"}
CAPABILITY_PROFILES = {
    "efficient",
    "balanced",
    "deep",
    "maximum_coherent",
    "delegated_deep",
}
REASONING_EFFORTS = {"low", "medium", "high", "extra_high", "max", "ultra"}
AUTHORITY_GUARD = {
    "authority_granted": False,
    "role_permissions_changed": False,
    "approval_gates_bypassed": False,
    "worker_ready_changed": False,
    "merge_authority_granted": False,
    "release_authority_granted": False,
    "recurring_automation_changed": False,
}


def _issue(code: str, path: str, message: str) -> dict[str, str]:
    return {"code": code, "path": path, "message": message}


def _sorted_issues(values: Sequence[dict[str, str]]) -> list[dict[str, str]]:
    return sorted(values, key=lambda row: (row["code"], row["path"], row["message"]))


def _stable_unique(values: Sequence[str]) -> list[str]:
    return sorted({value for value in values if isinstance(value, str) and value})


def _as_list(
    value: Any,
    path: str,
    errors: list[dict[str, str]],
) -> list[Any]:
    if isinstance(value, list):
        return value
    errors.append(_issue("expected_array", path, "value must be an array"))
    return []


def _as_object(
    value: Any,
    path: str,
    errors: list[dict[str, str]],
) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    errors.append(_issue("expected_object", path, "value must be an object"))
    return {}


def _string_set(
    value: Any,
    path: str,
    errors: list[dict[str, str]],
    *,
    minimum: int = 0,
) -> set[str]:
    if not isinstance(value, list):
        errors.append(_issue("expected_array", path, "value must be an array"))
        return set()
    rows = [str(item) for item in value if isinstance(item, str)]
    if len(rows) != len(value):
        errors.append(
            _issue("string-array-required", path, "every array item must be a string")
        )
    if len(rows) != len(set(rows)):
        errors.append(_issue("duplicate-array-item", path, "array items must be unique"))
    if len(rows) < minimum:
        errors.append(
            _issue(
                "array-too-short",
                path,
                f"array requires at least {minimum} item(s)",
            )
        )
    return set(rows)


def _validate_id(
    value: Any,
    path: str,
    errors: list[dict[str, str]],
) -> str:
    if not isinstance(value, str) or ID_RE.fullmatch(value) is None:
        errors.append(
            _issue(
                "identity-invalid",
                path,
                "identity must match the Result Integration id contract",
            )
        )
        return ""
    return value


def _accounting_key(row: Mapping[str, Any]) -> tuple[str, str, str]:
    return (
        str(row.get("invocation_id") or ""),
        str(row.get("attempt_id") or ""),
        str(row.get("work_unit_id") or ""),
    )


def _invalid_report(
    errors: Sequence[dict[str, str]],
    *,
    warnings: Sequence[dict[str, str]] = (),
    accounting: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "valid": False,
        "status": "invalid",
        "errors": _sorted_issues(errors),
        "warnings": _sorted_issues(warnings),
        "accounting": accounting,
        "contract": None,
        "authority_granted": False,
        "execution_started": False,
    }


def _validate_result_envelopes(
    plan: Mapping[str, Any],
    result_envelopes: list[Any],
    *,
    project_root: Path,
    errors: list[dict[str, str]],
) -> tuple[list[dict[str, Any]], dict[tuple[str, str, str], str]]:
    prepared: list[dict[str, Any]] = []
    stale_reasons: dict[tuple[str, str, str], str] = {}
    correlation_id = plan.get("correlation_id")
    base_snapshot = plan.get("base_snapshot")
    base_digest = (
        base_snapshot.get("digest") if isinstance(base_snapshot, Mapping) else None
    )
    expected_journal_ref = (
        f"runtime://audit/{correlation_id}"
        if isinstance(correlation_id, str) and correlation_id
        else None
    )

    for index, raw in enumerate(result_envelopes):
        path = f"$.result_envelopes[{index}]"
        if not isinstance(raw, dict):
            errors.append(
                _issue(
                    "result_envelope_invalid",
                    path,
                    "Result Envelope must be an object",
                )
            )
            continue
        validation = validate_execution_contract(
            raw,
            kind="subagent_result_envelope",
            project_root=project_root,
        )
        for item in validation["errors"]:
            errors.append(
                _issue(
                    f"result-envelope-{item['code']}",
                    f"{path}.{item['path']}",
                    item["message"],
                )
            )

        candidate = copy.deepcopy(raw)
        key = _accounting_key(candidate)
        stale_reason: str | None = None
        if candidate.get("correlation_id") != correlation_id:
            stale_reason = "stale-result-correlation-mismatch"
        elif candidate.get("base_snapshot_digest") != base_digest:
            stale_reason = "stale-result-base-binding-mismatch"
        else:
            audit = candidate.get("audit")
            if (
                expected_journal_ref is not None
                and isinstance(audit, Mapping)
                and audit.get("journal_ref") != expected_journal_ref
            ):
                stale_reason = "stale-result-audit-lineage-mismatch"
        if stale_reason is not None:
            candidate["plan_content_digest"] = canonical_digest(
                {
                    "stale_reason": stale_reason,
                    "result_id": candidate.get("result_id"),
                }
            )
            stale_reasons[key] = stale_reason
        prepared.append(candidate)
    return prepared, stale_reasons


def _replace_stale_reasons(
    accounting: dict[str, Any],
    stale_reasons: Mapping[tuple[str, str, str], str],
) -> None:
    stale_by_unit: dict[str, str] = {}
    for row in accounting.get("result_accounting", []):
        key = _accounting_key(row)
        reason = stale_reasons.get(key)
        if reason and row.get("disposition") == "stale":
            row["reason_code"] = reason
            stale_by_unit[key[2]] = reason
    for row in accounting.get("lane_coverage", []):
        reason = stale_by_unit.get(str(row.get("work_unit_id") or ""))
        if reason and row.get("status") == "stale":
            row["reason_code"] = reason
    for row in accounting.get("required_failures", []):
        reason = stale_by_unit.get(str(row.get("work_unit_id") or ""))
        if reason:
            row["reason_code"] = reason
    for row in accounting.get("optional_degradations", []):
        reason = stale_by_unit.get(str(row.get("work_unit_id") or ""))
        if reason:
            row["reason_code"] = reason
    for row in accounting.get("blockers", []):
        if row.get("reason_code") == "required-lane-not-successful":
            unit_id = next(
                (
                    unit
                    for unit in stale_by_unit
                    if unit in str(row.get("human_reason") or "")
                ),
                None,
            )
            if unit_id:
                row["human_reason"] = (
                    f"Required lane {unit_id} has stale evidence "
                    f"({stale_by_unit[unit_id]})."
                )
    if stale_by_unit:
        accounting["reason_codes"] = _stable_unique(
            [
                *accounting.get("reason_codes", []),
                *stale_by_unit.values(),
            ]
        )


def _index_result_content(
    result_envelopes: list[dict[str, Any]],
    errors: list[dict[str, str]],
) -> tuple[
    dict[str, dict[str, Any]],
    dict[str, tuple[str, dict[str, Any]]],
    dict[str, tuple[str, dict[str, Any]]],
    dict[str, tuple[str, dict[str, Any]]],
]:
    results: dict[str, dict[str, Any]] = {}
    claims: dict[str, tuple[str, dict[str, Any]]] = {}
    evidence: dict[str, tuple[str, dict[str, Any]]] = {}
    declared_conflicts: dict[str, tuple[str, dict[str, Any]]] = {}
    for result_index, result in enumerate(result_envelopes):
        result_id = str(result.get("result_id") or "")
        if result_id in results:
            errors.append(
                _issue(
                    "duplicate-result-id",
                    f"$.result_envelopes[{result_index}].result_id",
                    "Result Envelope ids must be globally unique",
                )
            )
        results[result_id] = result
        for evidence_index, row in enumerate(result.get("evidence", [])):
            evidence_id = str(row.get("evidence_id") or "")
            if evidence_id in evidence:
                errors.append(
                    _issue(
                        "duplicate-evidence-id",
                        (
                            f"$.result_envelopes[{result_index}]"
                            f".evidence[{evidence_index}].evidence_id"
                        ),
                        "evidence ids must be globally unique across the result set",
                    )
                )
            evidence[evidence_id] = (result_id, row)
        outcome = result.get("outcome")
        if not isinstance(outcome, Mapping):
            continue
        for claim_index, row in enumerate(outcome.get("claims", [])):
            claim_id = str(row.get("claim_id") or "")
            if claim_id in claims:
                errors.append(
                    _issue(
                        "duplicate-claim-id",
                        (
                            f"$.result_envelopes[{result_index}]"
                            f".outcome.claims[{claim_index}].claim_id"
                        ),
                        "claim ids must be globally unique across the result set",
                    )
                )
            claims[claim_id] = (result_id, row)
        for conflict_index, row in enumerate(outcome.get("conflicts", [])):
            conflict_id = str(row.get("conflict_id") or "")
            previous = declared_conflicts.get(conflict_id)
            if previous and previous[1].get("classification") != row.get(
                "classification"
            ):
                errors.append(
                    _issue(
                        "conflict-classification-drift",
                        (
                            f"$.result_envelopes[{result_index}]"
                            f".outcome.conflicts[{conflict_index}].classification"
                        ),
                        "one conflict id cannot change classification across results",
                    )
                )
            declared_conflicts[conflict_id] = (result_id, row)
    return results, claims, evidence, declared_conflicts


def _validate_conflicts(
    conflicts: list[Any],
    *,
    work_unit_ids: set[str],
    result_ids: set[str],
    claims: Mapping[str, tuple[str, dict[str, Any]]],
    evidence: Mapping[str, tuple[str, dict[str, Any]]],
    declared_conflicts: Mapping[str, tuple[str, dict[str, Any]]],
    errors: list[dict[str, str]],
) -> tuple[list[dict[str, Any]], set[str], set[str], list[dict[str, str]], list[str]]:
    normalized: list[dict[str, Any]] = []
    selected_positions: set[str] = set()
    rejected_positions: set[str] = set()
    blockers: list[dict[str, str]] = []
    residual_risks: list[str] = []
    seen_ids: set[str] = set()
    known_parties = work_unit_ids | result_ids

    for index, raw in enumerate(conflicts):
        path = f"$.conflicts[{index}]"
        conflict = _as_object(raw, path, errors)
        if not conflict:
            continue
        conflict_id = _validate_id(
            conflict.get("conflict_id"),
            f"{path}.conflict_id",
            errors,
        )
        if conflict_id in seen_ids:
            errors.append(
                _issue(
                    "duplicate-conflict-id",
                    f"{path}.conflict_id",
                    "each conflict must be accounted exactly once",
                )
            )
        seen_ids.add(conflict_id)
        declared = declared_conflicts.get(conflict_id)
        if declared and declared[1].get("classification") != conflict.get(
            "classification"
        ):
            errors.append(
                _issue(
                    "conflict-classification-mismatch",
                    f"{path}.classification",
                    "Integrator classification must match the declared result conflict",
                )
            )
        if conflict.get("classification") not in CONFLICT_CLASSES:
            errors.append(
                _issue(
                    "conflict-classification-invalid",
                    f"{path}.classification",
                    "classification is not supported by Result Integration v1",
                )
            )
        if conflict.get("risk") not in CONFLICT_RISKS:
            errors.append(
                _issue(
                    "conflict-risk-invalid",
                    f"{path}.risk",
                    "risk must be low, medium, high or critical",
                )
            )
        if not isinstance(conflict.get("blocking"), bool):
            errors.append(
                _issue(
                    "conflict-blocking-invalid",
                    f"{path}.blocking",
                    "blocking must be a boolean",
                )
            )

        parties = _string_set(
            conflict.get("parties"),
            f"{path}.parties",
            errors,
            minimum=2,
        )
        unknown_parties = parties - known_parties
        if unknown_parties:
            errors.append(
                _issue(
                    "conflict-party-unknown",
                    f"{path}.parties",
                    f"unknown parties: {sorted(unknown_parties)}",
                )
            )
        claim_refs = _string_set(
            conflict.get("claim_refs"),
            f"{path}.claim_refs",
            errors,
            minimum=2,
        )
        unknown_claims = claim_refs - set(claims)
        if unknown_claims:
            errors.append(
                _issue(
                    "conflict-claim-unknown",
                    f"{path}.claim_refs",
                    f"unknown claims: {sorted(unknown_claims)}",
                )
            )
        evidence_refs = _string_set(
            conflict.get("evidence_refs"),
            f"{path}.evidence_refs",
            errors,
        )
        unknown_evidence = evidence_refs - set(evidence)
        if unknown_evidence:
            errors.append(
                _issue(
                    "conflict-evidence-unknown",
                    f"{path}.evidence_refs",
                    f"unknown evidence: {sorted(unknown_evidence)}",
                )
            )

        resolution = _as_object(
            conflict.get("resolution"),
            f"{path}.resolution",
            errors,
        )
        status = resolution.get("status")
        method = resolution.get("method")
        if status not in CONFLICT_RESOLUTION_STATUSES:
            errors.append(
                _issue(
                    "conflict-resolution-status-invalid",
                    f"{path}.resolution.status",
                    "resolution status must be resolved, unresolved or routed",
                )
            )
        if method not in CONFLICT_RESOLUTION_METHODS:
            errors.append(
                _issue(
                    "conflict-resolution-method-invalid",
                    f"{path}.resolution.method",
                    "resolution method is not supported by Result Integration v1",
                )
            )
        if not isinstance(resolution.get("rationale"), str) or not resolution.get(
            "rationale"
        ).strip():
            errors.append(
                _issue(
                    "conflict-rationale-required",
                    f"{path}.resolution.rationale",
                    "resolution rationale must be non-empty",
                )
            )
        selected = _string_set(
            resolution.get("selected_positions"),
            f"{path}.resolution.selected_positions",
            errors,
        )
        rejected = _string_set(
            resolution.get("rejected_positions"),
            f"{path}.resolution.rejected_positions",
            errors,
        )
        residual_risk = resolution.get("residual_risk")
        if residual_risk is not None and (
            not isinstance(residual_risk, str) or not residual_risk.strip()
        ):
            errors.append(
                _issue(
                    "conflict-residual-risk-invalid",
                    f"{path}.resolution.residual_risk",
                    "residual risk must be null or a non-empty string",
                )
            )
        if selected & rejected:
            errors.append(
                _issue(
                    "conflict-position-overlap",
                    f"{path}.resolution",
                    "selected and rejected positions must be disjoint",
                )
            )
        if status == "resolved":
            if method == "unresolved":
                errors.append(
                    _issue(
                        "resolved-conflict-method-invalid",
                        f"{path}.resolution.method",
                        "resolved conflict cannot retain method=unresolved",
                    )
                )
            if not evidence_refs:
                errors.append(
                    _issue(
                        "conflict-resolution-evidence-required",
                        f"{path}.evidence_refs",
                        "resolved conflicts require evidence",
                    )
                )
            if selected | rejected != claim_refs:
                errors.append(
                    _issue(
                        "conflict-resolution-not-exact",
                        f"{path}.resolution",
                        "resolved conflict must select or reject every conflicting claim",
                    )
                )
            if not selected:
                errors.append(
                    _issue(
                        "conflict-selection-required",
                        f"{path}.resolution.selected_positions",
                        "resolved conflict must select at least one position",
                    )
                )
            if method in {"majority", "confidence"} and (
                conflict.get("classification") not in SUBJECTIVE_CONFLICT_CLASSES
                or conflict.get("risk") in {"high", "critical"}
            ):
                errors.append(
                    _issue(
                        "conflict-tiebreaker-forbidden",
                        f"{path}.resolution.method",
                        "majority/confidence is only a final tie-breaker for low/medium subjective conflicts",
                    )
                )
            for claim_id in selected:
                claim = claims.get(claim_id, ("", {}))[1]
                claim_evidence = set(
                    str(value) for value in claim.get("evidence_refs", [])
                )
                if claim.get("support_status") != "supported":
                    errors.append(
                        _issue(
                            "conflict-selected-claim-unsupported",
                            f"{path}.resolution.selected_positions",
                            f"selected claim is not supported: {claim_id}",
                        )
                    )
                if not claim_evidence.intersection(evidence_refs):
                    errors.append(
                        _issue(
                            "conflict-selected-claim-evidence-mismatch",
                            f"{path}.evidence_refs",
                            f"selected claim lacks cited resolution evidence: {claim_id}",
                        )
                    )
            selected_positions.update(selected)
            rejected_positions.update(rejected)
        else:
            if selected or rejected:
                errors.append(
                    _issue(
                        "unresolved-conflict-has-disposition",
                        f"{path}.resolution",
                        "unresolved/routed conflicts cannot select or reject positions",
                    )
                )
            if method != "unresolved":
                errors.append(
                    _issue(
                        "unresolved-conflict-method-invalid",
                        f"{path}.resolution.method",
                        "unresolved/routed conflicts must retain method=unresolved",
                    )
                )
            risk_text = (
                str(resolution.get("residual_risk") or "").strip()
                or f"Conflict {conflict_id} remains unresolved."
            )
            if conflict.get("blocking") is True:
                blockers.append(
                    {
                        "reason_code": "blocking-conflict-unresolved",
                        "human_reason": risk_text,
                    }
                )
            else:
                residual_risks.append(risk_text)
            rejected_positions.update(claim_refs)
        normalized_conflict = copy.deepcopy(conflict)
        normalized_conflict["parties"] = sorted(parties)
        normalized_conflict["claim_refs"] = sorted(claim_refs)
        normalized_conflict["evidence_refs"] = sorted(evidence_refs)
        normalized_conflict["resolution"]["selected_positions"] = sorted(selected)
        normalized_conflict["resolution"]["rejected_positions"] = sorted(rejected)
        normalized.append(normalized_conflict)

    missing = set(declared_conflicts) - seen_ids
    for conflict_id in sorted(missing):
        errors.append(
            _issue(
                "declared-conflict-not-accounted",
                "$.conflicts",
                f"result-declared conflict lacks Integrator disposition: {conflict_id}",
            )
        )
    return (
        sorted(normalized, key=lambda row: str(row.get("conflict_id") or "")),
        selected_positions,
        rejected_positions,
        blockers,
        residual_risks,
    )


def _build_requirement_coverage(
    requirement_ids: list[Any],
    *,
    accepted_result_ids: set[str],
    results: Mapping[str, dict[str, Any]],
    known_evidence_ids: set[str],
    errors: list[dict[str, str]],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    normalized_ids = [
        _validate_id(
            value,
            f"$.requirement_ids[{index}]",
            errors,
        )
        for index, value in enumerate(requirement_ids)
    ]
    normalized_ids = sorted(value for value in normalized_ids if value)
    if not normalized_ids:
        errors.append(
            _issue(
                "requirement-set-empty",
                "$.requirement_ids",
                "Integrator must declare the exact requirement denominator",
            )
        )
        return [], []
    if len(normalized_ids) != len(set(normalized_ids)):
        errors.append(
            _issue(
                "duplicate-requirement-id",
                "$.requirement_ids",
                "requirement ids must be unique",
            )
        )

    observations: dict[str, list[dict[str, Any]]] = {
        requirement_id: [] for requirement_id in normalized_ids
    }
    for result_id in sorted(accepted_result_ids):
        outcome = results.get(result_id, {}).get("outcome")
        if not isinstance(outcome, Mapping):
            continue
        for row in outcome.get("acceptance_evidence", []):
            criterion_id = str(row.get("criterion_id") or "")
            if criterion_id in observations:
                observations[criterion_id].append(row)

    coverage: list[dict[str, Any]] = []
    blockers: list[dict[str, str]] = []
    for requirement_id in normalized_ids:
        rows = observations[requirement_id]
        statuses = {str(row.get("status") or "") for row in rows}
        evidence_refs = _stable_unique(
            [
                str(ref)
                for row in rows
                for ref in row.get("evidence_refs", [])
                if str(ref) in known_evidence_ids
            ]
        )
        if rows and statuses == {"met"} and evidence_refs:
            status = "covered"
        elif "unmet" in statuses and "met" not in statuses:
            status = "blocked"
        elif rows:
            status = "partial"
        else:
            status = "missing"
        coverage.append(
            {
                "requirement_id": requirement_id,
                "status": status,
                "evidence_refs": evidence_refs,
            }
        )
        if status != "covered":
            blockers.append(
                {
                    "reason_code": "requirement-coverage-incomplete",
                    "human_reason": (
                        f"Requirement {requirement_id} is {status}; "
                        "Finalizer readiness requires covered evidence."
                    ),
                }
            )
    return coverage, blockers


def _build_preservation_checks(
    raw_checks: list[Any],
    errors: list[dict[str, str]],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    checks: list[dict[str, Any]] = []
    blockers: list[dict[str, str]] = []
    seen: set[str] = set()
    if not raw_checks:
        blockers.append(
            {
                "reason_code": "preservation-checks-missing",
                "human_reason": (
                    "At least one explicit preservation/package check is required "
                    "before Finalizer handoff."
                ),
            }
        )
    for index, raw in enumerate(raw_checks):
        path = f"$.preservation_checks[{index}]"
        row = _as_object(raw, path, errors)
        if not row:
            continue
        name = str(row.get("name") or "")
        status = row.get("status")
        evidence_ref = str(row.get("evidence_ref") or "")
        if not name:
            errors.append(
                _issue(
                    "preservation-check-name-required",
                    f"{path}.name",
                    "preservation check name must be non-empty",
                )
            )
        if not evidence_ref:
            errors.append(
                _issue(
                    "preservation-check-evidence-required",
                    f"{path}.evidence_ref",
                    "preservation check evidence reference must be non-empty",
                )
            )
        if name in seen:
            errors.append(
                _issue(
                    "duplicate-preservation-check",
                    f"{path}.name",
                    "preservation check names must be unique",
                )
            )
        seen.add(name)
        if status not in {"passed", "failed", "not_run"}:
            errors.append(
                _issue(
                    "preservation-check-status-invalid",
                    f"{path}.status",
                    "status must be passed, failed or not_run",
                )
            )
        checks.append(
            {
                "name": name,
                "status": status,
                "evidence_ref": evidence_ref,
            }
        )
        if status != "passed":
            blockers.append(
                {
                    "reason_code": "preservation-check-not-passed",
                    "human_reason": f"Preservation check {name} is {status}.",
                }
            )
    return sorted(checks, key=lambda row: row["name"]), blockers


def _build_artifacts(
    accepted_result_ids: set[str],
    results: Mapping[str, dict[str, Any]],
    errors: list[dict[str, str]],
) -> list[dict[str, str]]:
    by_ref: dict[str, str] = {}
    for result_id in sorted(accepted_result_ids):
        outcome = results.get(result_id, {}).get("outcome")
        if not isinstance(outcome, Mapping):
            continue
        for artifact in outcome.get("artifacts", []):
            ref = str(artifact.get("ref") or "")
            digest = str(artifact.get("digest") or "")
            previous = by_ref.get(ref)
            if previous is not None and previous != digest:
                errors.append(
                    _issue(
                        "artifact-digest-conflict-requires-resolution",
                        "$.result_envelopes",
                        (
                            f"accepted results cite divergent digests for {ref}; "
                            "supply an evidence-bound conflict disposition"
                        ),
                    )
                )
            by_ref[ref] = digest
    return [
        {"ref": ref, "digest": digest}
        for ref, digest in sorted(by_ref.items())
    ]


def _validate_next_run_request(
    value: Any,
    errors: list[dict[str, str]],
) -> dict[str, Any] | None:
    if value is None:
        return None
    path = "$.next_run_request"
    if not isinstance(value, dict):
        errors.append(
            _issue(
                "next-run-request-invalid",
                path,
                "next-run request must be an object",
            )
        )
        return None
    required = {
        "request_id",
        "kind",
        "reason_code",
        "target_role",
        "target_stage",
        "capability_profile",
        "reasoning_effort",
        "authorization_granted",
        "execution_started",
    }
    unknown = set(value) - required
    missing = required - set(value)
    if unknown:
        errors.append(
            _issue(
                "next-run-field-unknown",
                path,
                f"unknown fields: {sorted(unknown)}",
            )
        )
    if missing:
        errors.append(
            _issue(
                "next-run-field-missing",
                path,
                f"missing fields: {sorted(missing)}",
            )
        )
    for field in ("request_id", "reason_code", "target_role", "target_stage"):
        _validate_id(value.get(field), f"{path}.{field}", errors)
    if value.get("kind") not in NEXT_RUN_KINDS:
        errors.append(
            _issue(
                "next-run-kind-invalid",
                f"{path}.kind",
                "kind must be targeted_retry, specialist or owner_escalation",
            )
        )
    if value.get("capability_profile") not in CAPABILITY_PROFILES:
        errors.append(
            _issue(
                "next-run-capability-invalid",
                f"{path}.capability_profile",
                "capability profile is not supported",
            )
        )
    if value.get("reasoning_effort") not in REASONING_EFFORTS:
        errors.append(
            _issue(
                "next-run-effort-invalid",
                f"{path}.reasoning_effort",
                "reasoning effort is not supported",
            )
        )
    if (
        value.get("authorization_granted") is not False
        or value.get("execution_started") is not False
    ):
        errors.append(
            _issue(
                "next-run-authority-forbidden",
                path,
                "Integrator may recommend another run but cannot authorize or start it",
            )
        )
    return copy.deepcopy(value)


def _dedupe_blockers(values: Sequence[dict[str, str]]) -> list[dict[str, str]]:
    unique = {
        (str(row.get("reason_code") or ""), str(row.get("human_reason") or ""))
        for row in values
    }
    return [
        {"reason_code": reason_code, "human_reason": human_reason}
        for reason_code, human_reason in sorted(unique)
    ]


def synthesize_result_integration(
    payload: Any,
    *,
    project_root: Path | str = ".",
) -> dict[str, Any]:
    """Return a validated synthesis report with a Result Integration contract."""

    errors: list[dict[str, str]] = []
    warnings: list[dict[str, str]] = []
    source = _as_object(payload, "$", errors)
    if not source:
        return _invalid_report(errors)
    plan = _as_object(source.get("plan"), "$.plan", errors)
    expected_invocations = _as_list(
        source.get("expected_invocations"),
        "$.expected_invocations",
        errors,
    )
    raw_results = _as_list(
        source.get("result_envelopes"),
        "$.result_envelopes",
        errors,
    )
    raw_conflicts = _as_list(source.get("conflicts", []), "$.conflicts", errors)
    requirement_ids = _as_list(
        source.get("requirement_ids"),
        "$.requirement_ids",
        errors,
    )
    raw_checks = _as_list(
        source.get("preservation_checks"),
        "$.preservation_checks",
        errors,
    )
    raw_residual_risks = _as_list(
        source.get("residual_risks", []),
        "$.residual_risks",
        errors,
    )
    producer = _as_object(source.get("producer"), "$.producer", errors)
    for index, value in enumerate(raw_residual_risks):
        if not isinstance(value, str) or not value.strip():
            errors.append(
                _issue(
                    "residual-risk-invalid",
                    f"$.residual_risks[{index}]",
                    "residual risk must be a non-empty string",
                )
            )
    if source.get("audit") is not None and not isinstance(
        source.get("audit"), Mapping
    ):
        errors.append(
            _issue(
                "audit-invalid",
                "$.audit",
                "audit input must be an object",
            )
        )
    if errors:
        return _invalid_report(errors)

    if (
        plan.get("contract_kind") != "parallel_work"
        or plan.get("contract_version") != CONTRACT_VERSION
    ):
        errors.append(
            _issue(
                "parallel-work-contract-invalid",
                "$.plan",
                "synthesis requires Parallel Work contract 1.0.0",
            )
        )
    base_snapshot = plan.get("base_snapshot")
    if not isinstance(base_snapshot, Mapping) or base_snapshot.get("immutable") is not True:
        errors.append(
            _issue(
                "immutable-base-snapshot-required",
                "$.plan.base_snapshot",
                "evidence freshness requires one immutable plan base snapshot",
            )
        )
    if source.get("accounting_closed") is not True:
        errors.append(
            _issue(
                "result-accounting-not-closed",
                "$.accounting_closed",
                "Integrator synthesis starts only after the exact result barrier closes",
            )
        )
    if errors:
        return _invalid_report(errors)

    prepared_results, stale_reasons = _validate_result_envelopes(
        plan,
        raw_results,
        project_root=Path(project_root).resolve(),
        errors=errors,
    )
    if errors:
        return _invalid_report(errors)

    accounting = account_parallel_work_results(
        plan,
        expected_invocations,
        prepared_results,
        accounting_closed=True,
    )
    if accounting.get("valid") is not True:
        errors.extend(
            _issue(row["code"], row["path"], row["message"])
            for row in accounting.get("errors", [])
        )
        return _invalid_report(errors, accounting=accounting)
    _replace_stale_reasons(accounting, stale_reasons)
    if accounting.get("accounting_closed") is not True:
        errors.append(
            _issue(
                "result-accounting-not-closed",
                "$.accounting",
                "lane accounting did not close",
            )
        )
        return _invalid_report(errors, accounting=accounting)

    results, claims, evidence, declared_conflicts = _index_result_content(
        prepared_results,
        errors,
    )
    work_unit_ids = {
        str(row.get("work_unit_id") or "")
        for row in accounting.get("lane_coverage", [])
    }
    (
        conflicts,
        _selected_positions,
        rejected_positions,
        conflict_blockers,
        conflict_risks,
    ) = _validate_conflicts(
        raw_conflicts,
        work_unit_ids=work_unit_ids,
        result_ids=set(results),
        claims=claims,
        evidence=evidence,
        declared_conflicts=declared_conflicts,
        errors=errors,
    )

    accepted_result_ids = {
        str(row.get("result_id") or "")
        for row in accounting.get("result_accounting", [])
        if row.get("disposition") == "accepted"
    }
    excluded_results = sorted(
        (
            {
                "result_id": str(row.get("result_id") or ""),
                "reason_code": str(row.get("reason_code") or ""),
            }
            for row in accounting.get("result_accounting", [])
            if row.get("disposition") != "accepted" and row.get("result_id")
        ),
        key=lambda row: row["result_id"],
    )

    unresolved_claims = {
        str(claim_id)
        for conflict in conflicts
        if conflict.get("resolution", {}).get("status") != "resolved"
        for claim_id in conflict.get("claim_refs", [])
    }
    accepted_claim_ids: list[str] = []
    excluded_claims: list[dict[str, str]] = []
    critical_confidences: list[float] = []
    all_confidences: list[float] = []
    criticality_by_unit = {
        str(row.get("work_unit_id") or ""): str(row.get("criticality") or "")
        for row in accounting.get("expected_results", [])
    }
    for claim_id, (result_id, claim) in sorted(claims.items()):
        reason: str | None = None
        if result_id not in accepted_result_ids:
            reason = "result-not-accepted"
        elif claim_id in rejected_positions:
            reason = (
                "conflict-unresolved"
                if claim_id in unresolved_claims
                else "conflict-position-rejected"
            )
        elif claim.get("support_status") == "unsupported":
            reason = "claim-unsupported"
        elif claim.get("support_status") == "contradicted":
            reason = "claim-contradicted"
        if reason is not None:
            excluded_claims.append({"claim_id": claim_id, "reason_code": reason})
            continue
        accepted_claim_ids.append(claim_id)
        confidence = claim.get("confidence")
        if isinstance(confidence, (int, float)) and not isinstance(confidence, bool):
            all_confidences.append(float(confidence))
            result_unit = str(results[result_id].get("work_unit_id") or "")
            if criticality_by_unit.get(result_unit) == "required":
                critical_confidences.append(float(confidence))

    coverage, coverage_blockers = _build_requirement_coverage(
        requirement_ids,
        accepted_result_ids=accepted_result_ids,
        results=results,
        known_evidence_ids=set(evidence),
        errors=errors,
    )
    checks, check_blockers = _build_preservation_checks(raw_checks, errors)
    artifacts = _build_artifacts(accepted_result_ids, results, errors)

    next_run = _validate_next_run_request(
        source.get("next_run_request"),
        errors,
    )
    if errors:
        return _invalid_report(errors, warnings=warnings, accounting=accounting)

    blockers = _dedupe_blockers(
        [
            *accounting.get("blockers", []),
            *conflict_blockers,
            *coverage_blockers,
            *check_blockers,
        ]
    )
    residual_risks = _stable_unique(
        [
            *accounting.get("residual_risks", []),
            *conflict_risks,
            *[
                str(value)
                for value in raw_residual_risks
                if isinstance(value, str)
            ],
        ]
    )
    if isinstance(next_run, dict) and not blockers:
        if next_run.get("kind") == "owner_escalation":
            blockers.append(
                {
                    "reason_code": "owner-decision-recommended",
                    "human_reason": (
                        "Integrator recommends an owner decision before synthesis "
                        "can become final."
                    ),
                }
            )
        else:
            residual_risks = _stable_unique(
                [
                    *residual_risks,
                    (
                        "Additional targeted work is recommended and remains "
                        "subject to Router authorization."
                    ),
                ]
            )

    if blockers:
        if isinstance(next_run, dict) and next_run.get("kind") == "owner_escalation":
            status = "owner_decision_required"
        elif isinstance(next_run, dict):
            status = "rerun_required"
        else:
            status = "integration_incomplete"
    elif residual_risks:
        status = "accepted_with_residual_risk"
    else:
        status = "ready_for_finalizer"

    weakest = (
        min(critical_confidences)
        if critical_confidences
        else (min(all_confidences) if all_confidences else 0.0)
    )
    confidence = weakest
    summary = str(source.get("summary") or "").strip()
    if not summary:
        summary = (
            f"Integrated {len(accepted_result_ids)} accepted result(s) across "
            f"{len(work_unit_ids)} planned lane(s); status={status}."
        )
    synthesis: dict[str, Any] = {
        "summary": summary,
        "accepted_result_ids": sorted(accepted_result_ids),
        "excluded_results": excluded_results,
        "accepted_claim_ids": sorted(accepted_claim_ids),
        "excluded_claims": sorted(
            excluded_claims,
            key=lambda row: row["claim_id"],
        ),
        "requirement_coverage": sorted(
            row["requirement_id"]
            for row in coverage
            if row["status"] == "covered"
        ),
        "artifacts": artifacts,
        "preservation_checks": checks,
        "residual_risks": residual_risks,
        "confidence": confidence,
        "confidence_method": "weakest_critical_evidence",
        "weakest_critical_evidence_confidence": weakest,
    }
    synthesis["synthesis_digest"] = canonical_digest(synthesis)

    correlation_id = str(plan.get("correlation_id") or "")
    audit_source = source.get("audit")
    audit_warning_values = (
        audit_source.get("warnings", [])
        if isinstance(audit_source, Mapping)
        else []
    )
    if not isinstance(audit_warning_values, list):
        errors.append(
            _issue(
                "audit-warnings-invalid",
                "$.audit.warnings",
                "audit warnings must be an array",
            )
        )
        audit_warning_values = []
    audit_warnings = (
        [
            str(value)
            for value in audit_warning_values
            if isinstance(value, str)
        ]
    )
    if errors:
        return _invalid_report(errors, warnings=warnings, accounting=accounting)
    reason_codes = _stable_unique(
        [
            *accounting.get("reason_codes", []),
            *(row["reason_code"] for row in blockers),
            *(
                "conflict-resolved"
                for conflict in conflicts
                if conflict.get("resolution", {}).get("status") == "resolved"
            ),
            *(
                ["additional-run-recommended"]
                if isinstance(next_run, dict)
                else []
            ),
        ]
    )
    contract: dict[str, Any] = {
        "contract_version": CONTRACT_VERSION,
        "contract_kind": "result_integration",
        "correlation_id": correlation_id,
        "producer": copy.deepcopy(producer),
        "created_at": source.get("created_at"),
        "revision": source.get("revision", 1),
        "supersedes": source.get("supersedes"),
        "digest_profile": "jcs-sha256-v1",
        "authority_guard": copy.deepcopy(AUTHORITY_GUARD),
        "audit": {
            "journal_ref": f"runtime://audit/{correlation_id}",
            "reason_codes": reason_codes,
            "warnings": audit_warnings,
        },
        "integration_id": source.get("integration_id"),
        "status": status,
        "plan_id": accounting["plan_id"],
        "plan_content_digest": accounting["plan_content_digest"],
        "input_result_set_digest": accounting["input_result_set_digest"],
        "expected_results": copy.deepcopy(accounting["expected_results"]),
        "received_results": copy.deepcopy(accounting["received_results"]),
        "orphan_results": copy.deepcopy(accounting["orphan_results"]),
        "result_accounting": copy.deepcopy(accounting["result_accounting"]),
        "requirement_coverage": coverage,
        "lane_coverage": copy.deepcopy(accounting["lane_coverage"]),
        "conflicts": conflicts,
        "blockers": blockers,
        "synthesis": synthesis,
    }
    if isinstance(next_run, dict):
        contract["next_run_request"] = copy.deepcopy(next_run)

    package_evidence_digest = canonical_digest(
        {
            "plan_content_digest": contract["plan_content_digest"],
            "input_result_set_digest": contract["input_result_set_digest"],
            "synthesis_digest": synthesis["synthesis_digest"],
            "requirement_coverage": coverage,
            "conflicts": conflicts,
            "preservation_checks": checks,
        }
    )
    integration_seed = copy.deepcopy(contract)
    contract["integration_digest"] = canonical_digest(integration_seed)
    if status in READY_STATUSES:
        contract["finalizer_handoff"] = {
            "handoff_id": (
                "handoff:finalizer:"
                + contract["integration_digest"].split(":", 1)[1][:24]
            ),
            "integration_id": contract["integration_id"],
            "integration_digest": contract["integration_digest"],
            "synthesis_digest": synthesis["synthesis_digest"],
            "input_result_set_digest": contract["input_result_set_digest"],
            "package_evidence_digest": package_evidence_digest,
            "required_accounting_complete": True,
            "blocking_conflicts_resolved": True,
            "required_checks_passed": True,
            "finalizer_may_modify": False,
            "merge_authority_granted": False,
        }

    semantic = validate_execution_contract(
        contract,
        kind="result_integration",
        project_root=Path(project_root).resolve(),
    )
    if semantic["valid"] is not True:
        errors.extend(
            _issue(
                f"integration-{row['code']}",
                f"$.contract.{row['path']}",
                row["message"],
            )
            for row in semantic["errors"]
        )
        return _invalid_report(errors, warnings=warnings, accounting=accounting)

    return {
        "valid": True,
        "status": status,
        "errors": [],
        "warnings": _sorted_issues(warnings),
        "accounting": accounting,
        "contract": contract,
        "authority_granted": False,
        "execution_started": False,
    }


build_result_integration = synthesize_result_integration


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Integrator synthesis input JSON")
    parser.add_argument("--output", help="Write the valid Result Integration contract")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        payload = _load_json(Path(args.input))
    except (OSError, json.JSONDecodeError) as exc:
        report = _invalid_report(
            [_issue("input-read-error", "$.input", str(exc))]
        )
    else:
        report = synthesize_result_integration(
            payload,
            project_root=Path(args.project_root),
        )
    if report["valid"] and args.output:
        Path(args.output).write_text(
            json.dumps(report["contract"], ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    if args.json or not args.output or not report["valid"]:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = [
    "build_result_integration",
    "synthesize_result_integration",
]
