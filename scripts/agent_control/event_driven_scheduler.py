#!/usr/bin/env python3
"""Choose the next automation role from project events and queue state."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import shutil
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from codex_host_readiness import codex_host_readiness
import authorize_model_limit_retries
import promote_worker_ready_tasks
from project_paths import task_file


CONTRACT_VERSION = "1.0.0"
ID_RE = re.compile(r"^[a-z][a-z0-9._:-]{2,127}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
TERMINAL_RESULT_STATUSES = {
    "success",
    "partial",
    "failed",
    "timed_out",
    "cancelled",
    "blocked",
    "rejected",
}
NOT_RUN_INVOCATION_STATUSES = {
    "authorized",
    "queued",
    "cancelled_before_start",
    "not_run",
}
TERMINAL_NOT_RUN_INVOCATION_STATUSES = {
    "cancelled_before_start",
    "not_run",
}
FAILURE_DISPOSITIONS = {
    "failed": "failed",
    "timed_out": "failed",
    "blocked": "failed",
    "cancelled": "cancelled",
    "rejected": "rejected",
}
AUTHORITY_EFFECT = {
    "authority_granted": False,
    "worker_ready_changed": False,
    "merge_authority_granted": False,
    "release_authority_granted": False,
    "execution_started": False,
}


def canonical_digest(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def _issue(code: str, path: str, message: str) -> dict[str, str]:
    return {"code": code, "path": path, "message": message}


def _sorted_issues(values: Sequence[dict[str, str]]) -> list[dict[str, str]]:
    return sorted(values, key=lambda row: (row["code"], row["path"], row["message"]))


def _identity(value: Any, path: str, issues: list[dict[str, str]]) -> str:
    if not isinstance(value, str) or ID_RE.fullmatch(value) is None:
        issues.append(
            _issue(
                "result_accounting_identity_invalid",
                path,
                "identity must match the Result Integration id contract",
            )
        )
        return ""
    return value


def _digest(value: Any, path: str, issues: list[dict[str, str]]) -> str:
    if not isinstance(value, str) or DIGEST_RE.fullmatch(value) is None:
        issues.append(
            _issue(
                "result_accounting_digest_invalid",
                path,
                "digest must use sha256:<64 lowercase hexadecimal characters>",
            )
        )
        return ""
    return value


def _attempt_number(value: Any, path: str, issues: list[dict[str, str]]) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        issues.append(
            _issue(
                "result_accounting_attempt_invalid",
                path,
                "attempt_number must be an integer greater than zero",
            )
        )
        return 0
    return value


def _accounting_key(row: Mapping[str, Any]) -> tuple[str, str, str]:
    return (
        str(row.get("invocation_id") or ""),
        str(row.get("attempt_id") or ""),
        str(row.get("work_unit_id") or ""),
    )


def validate_partial_result_policy(
    plan: Any,
    *,
    require_declared: bool = True,
) -> list[dict[str, str]]:
    """Return stable errors for quorum or non-exact completion policy."""

    issues: list[dict[str, str]] = []
    if not isinstance(plan, Mapping):
        return [
            _issue(
                "parallel_work_plan_invalid",
                "$.plan",
                "Parallel Work plan must be an object",
            )
        ]
    completion = plan.get("completion_policy")
    if completion is None and not require_declared:
        completion = {}
    elif not isinstance(completion, Mapping):
        issues.append(
            _issue(
                "partial_result_policy_missing",
                "$.plan.completion_policy",
                "exact required/optional completion policy must be declared",
            )
        )
        completion = {}
    if require_declared and not completion:
        issues.append(
            _issue(
                "partial_result_policy_missing",
                "$.plan.completion_policy",
                "exact required/optional completion policy must be declared",
            )
        )
    if completion:
        if completion.get("required_units") != "all_success":
            issues.append(
                _issue(
                    "required_completion_policy_invalid",
                    "$.plan.completion_policy.required_units",
                    "every required lane must succeed",
                )
            )
        if completion.get("optional_units") != "allow_explicit_residual_risk":
            issues.append(
                _issue(
                    "optional_completion_policy_invalid",
                    "$.plan.completion_policy.optional_units",
                    "optional degradation must remain explicit",
                )
            )
        if completion.get("quorum_allowed") is not False:
            issues.append(
                _issue(
                    "quorum_completion_forbidden",
                    "$.plan.completion_policy.quorum_allowed",
                    "planned lanes cannot disappear behind quorum completion",
                )
            )
        if completion.get("minimum_success_allowed") is not False:
            issues.append(
                _issue(
                    "minimum_success_completion_forbidden",
                    "$.plan.completion_policy.minimum_success_allowed",
                    "planned lanes cannot disappear behind minimum-success completion",
                )
            )
        for key, value in completion.items():
            normalized = str(key).lower().replace("-", "_")
            if (
                ("quorum" in normalized or "minimum_success" in normalized)
                and normalized
                not in {
                    "quorum_allowed",
                    "minimum_success_allowed",
                }
                and value is not False
                and value is not None
            ):
                issues.append(
                    _issue(
                        "quorum_completion_forbidden",
                        f"$.plan.completion_policy.{key}",
                        "quorum/minimum-success completion fields are forbidden",
                    )
                )
    for key in (
        "quorum",
        "quorum_count",
        "quorum_percent",
        "minimum_success",
        "minimum_success_count",
        "minimum_success_percent",
    ):
        if plan.get(key) is not None and plan.get(key) is not False:
            issues.append(
                _issue(
                    "quorum_completion_forbidden",
                    f"$.plan.{key}",
                    "quorum/minimum-success completion fields are forbidden",
                )
            )
    return _sorted_issues(issues)


def _invalid_result(
    plan_id: str | None,
    plan_digest: str | None,
    issues: Sequence[dict[str, str]],
) -> dict[str, Any]:
    errors = _sorted_issues(issues)
    return {
        "valid": False,
        "status": "rejected",
        "plan_id": plan_id,
        "plan_content_digest": plan_digest,
        "reason_codes": sorted({row["code"] for row in errors}),
        "errors": errors,
        "expected_results": [],
        "received_results": [],
        "orphan_results": [],
        "result_accounting": [],
        "lane_coverage": [],
        "required_failures": [],
        "optional_degradations": [],
        "residual_risks": [],
        "blockers": [
            {
                "reason_code": row["code"],
                "human_reason": row["message"],
            }
            for row in errors
        ],
        "counts": {
            "planned_lanes": 0,
            "expected_attempts": 0,
            "received_results": 0,
            "accounted_attempts": 0,
            "orphan_results": 0,
        },
        "input_result_set_digest": canonical_digest([]),
        "accounting_closed": False,
        "contract_ready": False,
        "integration_ready": False,
        "degraded": False,
        "authority_effect": copy.deepcopy(AUTHORITY_EFFECT),
    }


def account_parallel_work_results(
    plan: Any,
    expected_invocations: Any,
    result_envelopes: Any,
    *,
    accounting_closed: bool = False,
) -> dict[str, Any]:
    """Account every planned lane and expected invocation attempt exactly once.

    ``accounting_closed`` means no additional invocation or Result Envelope may
    arrive for this plan revision.  Before closure, absent results remain a
    pending snapshot; after closure, they become explicit ``missing`` or
    ``not_run`` dispositions.
    """

    issues: list[dict[str, str]] = []
    if not isinstance(plan, Mapping):
        return _invalid_result(None, None, validate_partial_result_policy(plan))
    plan_id = _identity(plan.get("plan_id"), "$.plan.plan_id", issues)
    plan_digest = _digest(
        plan.get("plan_content_digest"),
        "$.plan.plan_content_digest",
        issues,
    )
    if (
        plan.get("contract_kind") != "parallel_work"
        or plan.get("contract_version") != CONTRACT_VERSION
    ):
        issues.append(
            _issue(
                "parallel_work_plan_invalid",
                "$.plan",
                "result accounting requires Parallel Work contract 1.0.0",
            )
        )
    issues.extend(validate_partial_result_policy(plan))

    raw_units = plan.get("work_units")
    ready_order = plan.get("deterministic_ready_order")
    if not isinstance(raw_units, list) or not raw_units:
        issues.append(
            _issue(
                "planned_lane_set_invalid",
                "$.plan.work_units",
                "at least one planned work unit is required",
            )
        )
        raw_units = []
    units: dict[str, dict[str, Any]] = {}
    for index, raw in enumerate(raw_units):
        path = f"$.plan.work_units[{index}]"
        if not isinstance(raw, Mapping):
            issues.append(
                _issue("planned_lane_invalid", path, "work unit must be an object")
            )
            continue
        unit_id = _identity(raw.get("work_unit_id"), f"{path}.work_unit_id", issues)
        criticality = raw.get("criticality")
        if criticality not in {"required", "optional"}:
            issues.append(
                _issue(
                    "planned_lane_criticality_invalid",
                    f"{path}.criticality",
                    "planned lane must be required or optional",
                )
            )
        if unit_id in units:
            issues.append(
                _issue(
                    "duplicate_planned_lane",
                    f"{path}.work_unit_id",
                    "planned work-unit ids must be unique",
                )
            )
        elif unit_id:
            units[unit_id] = {
                "work_unit_id": unit_id,
                "criticality": criticality,
            }
    if (
        not isinstance(ready_order, list)
        or len(ready_order) != len(set(str(value) for value in ready_order))
        or set(str(value) for value in ready_order) != set(units)
    ):
        issues.append(
            _issue(
                "planned_lane_order_invalid",
                "$.plan.deterministic_ready_order",
                "ready order must contain every planned lane exactly once",
            )
        )
        lane_order = sorted(units)
    else:
        lane_order = [str(value) for value in ready_order]

    if not isinstance(expected_invocations, list):
        issues.append(
            _issue(
                "expected_invocations_invalid",
                "$.expected_invocations",
                "expected invocations must be an array",
            )
        )
        expected_invocations = []
    if not isinstance(result_envelopes, list):
        issues.append(
            _issue(
                "result_envelopes_invalid",
                "$.result_envelopes",
                "Result Envelopes must be an array",
            )
        )
        result_envelopes = []

    expected_internal: list[dict[str, Any]] = []
    expected_keys: set[tuple[str, str, str]] = set()
    expected_invocation_ids: set[str] = set()
    expected_attempt_ids: set[str] = set()
    attempt_numbers_by_unit: dict[str, set[int]] = {unit_id: set() for unit_id in units}
    for index, raw in enumerate(expected_invocations):
        path = f"$.expected_invocations[{index}]"
        if not isinstance(raw, Mapping):
            issues.append(
                _issue("expected_invocation_invalid", path, "invocation must be an object")
            )
            continue
        plan_ref = raw.get("plan_ref")
        invocation_id = _identity(raw.get("invocation_id"), f"{path}.invocation_id", issues)
        attempt_id = _identity(raw.get("attempt_id"), f"{path}.attempt_id", issues)
        work_unit_id = _identity(
            raw.get("work_unit_id")
            or (
                plan_ref.get("work_unit_id")
                if isinstance(plan_ref, Mapping)
                else None
            ),
            f"{path}.work_unit_id",
            issues,
        )
        attempt_number = _attempt_number(
            raw.get("attempt_number"),
            f"{path}.attempt_number",
            issues,
        )
        if (
            not isinstance(plan_ref, Mapping)
            or plan_ref.get("plan_id") != plan_id
            or plan_ref.get("plan_content_digest") != plan_digest
            or plan_ref.get("work_unit_id") != work_unit_id
        ):
            issues.append(
                _issue(
                    "expected_invocation_plan_mismatch",
                    f"{path}.plan_ref",
                    "expected invocation must bind the exact plan and lane",
                )
            )
        unit = units.get(work_unit_id)
        if unit is None:
            issues.append(
                _issue(
                    "expected_invocation_lane_unknown",
                    f"{path}.work_unit_id",
                    "expected invocation references an unplanned lane",
                )
            )
            criticality = ""
        else:
            criticality = str(unit["criticality"])
            if "criticality" in raw and raw.get("criticality") != criticality:
                issues.append(
                    _issue(
                        "lane_criticality_changed",
                        f"{path}.criticality",
                        "invocation cannot change planned lane criticality",
                    )
                )
        key = (invocation_id, attempt_id, work_unit_id)
        if key in expected_keys:
            issues.append(
                _issue(
                    "duplicate_expected_invocation",
                    path,
                    "each invocation attempt may be expected exactly once",
                )
            )
        expected_keys.add(key)
        if invocation_id in expected_invocation_ids:
            issues.append(
                _issue(
                    "duplicate_expected_invocation_id",
                    f"{path}.invocation_id",
                    "invocation ids must be unique",
                )
            )
        expected_invocation_ids.add(invocation_id)
        if attempt_id in expected_attempt_ids:
            issues.append(
                _issue(
                    "duplicate_expected_attempt_id",
                    f"{path}.attempt_id",
                    "attempt ids must be unique",
                )
            )
        expected_attempt_ids.add(attempt_id)
        if work_unit_id in attempt_numbers_by_unit:
            if attempt_number in attempt_numbers_by_unit[work_unit_id]:
                issues.append(
                    _issue(
                        "duplicate_lane_attempt_number",
                        f"{path}.attempt_number",
                        "one lane cannot have two invocations for the same attempt number",
                    )
                )
            attempt_numbers_by_unit[work_unit_id].add(attempt_number)
        expected_internal.append(
            {
                "invocation_id": invocation_id,
                "attempt_id": attempt_id,
                "work_unit_id": work_unit_id,
                "criticality": criticality,
                "attempt_number": attempt_number,
                "invocation_status": str(raw.get("status") or "").strip().lower(),
            }
        )

    received_internal: list[dict[str, Any]] = []
    result_ids: set[str] = set()
    received_keys: set[tuple[str, str, str]] = set()
    for index, raw in enumerate(result_envelopes):
        path = f"$.result_envelopes[{index}]"
        if not isinstance(raw, Mapping):
            issues.append(
                _issue("result_envelope_invalid", path, "Result Envelope must be an object")
            )
            continue
        result_id = _identity(raw.get("result_id"), f"{path}.result_id", issues)
        invocation_id = _identity(raw.get("invocation_id"), f"{path}.invocation_id", issues)
        attempt_id = _identity(raw.get("attempt_id"), f"{path}.attempt_id", issues)
        work_unit_id = _identity(raw.get("work_unit_id"), f"{path}.work_unit_id", issues)
        status = str(raw.get("status") or "").strip().lower()
        if status not in TERMINAL_RESULT_STATUSES:
            issues.append(
                _issue(
                    "result_status_invalid",
                    f"{path}.status",
                    "Result Envelope status must be terminal",
                )
            )
        envelope_digest = _digest(
            raw.get("result_envelope_digest"),
            f"{path}.result_envelope_digest",
            issues,
        )
        attempt_number = raw.get("attempt_number")
        if attempt_number is not None:
            attempt_number = _attempt_number(
                attempt_number,
                f"{path}.attempt_number",
                issues,
            )
        plan_binding_valid = bool(
            raw.get("plan_id") == plan_id
            and raw.get("plan_content_digest") == plan_digest
        )
        key = (invocation_id, attempt_id, work_unit_id)
        if result_id in result_ids:
            issues.append(
                _issue(
                    "duplicate_result_id",
                    f"{path}.result_id",
                    "Result Envelope ids must be unique",
                )
            )
        result_ids.add(result_id)
        if key in received_keys:
            issues.append(
                _issue(
                    "duplicate_invocation_result",
                    path,
                    "one invocation attempt cannot produce multiple terminal results",
                )
            )
        received_keys.add(key)
        received_internal.append(
            {
                "result_id": result_id,
                "invocation_id": invocation_id,
                "attempt_id": attempt_id,
                "work_unit_id": work_unit_id,
                "status": status,
                "result_envelope_digest": envelope_digest,
                "attempt_number": attempt_number,
                "plan_binding_valid": plan_binding_valid,
            }
        )

    if issues:
        return _invalid_result(plan_id or None, plan_digest or None, issues)

    order_index = {unit_id: index for index, unit_id in enumerate(lane_order)}
    expected_internal.sort(
        key=lambda row: (
            order_index[row["work_unit_id"]],
            row["attempt_number"],
            row["invocation_id"],
            row["attempt_id"],
        )
    )
    received_internal.sort(
        key=lambda row: (
            order_index.get(row["work_unit_id"], len(order_index)),
            row["attempt_number"] if isinstance(row["attempt_number"], int) else 0,
            row["invocation_id"],
            row["attempt_id"],
            row["result_id"],
        )
    )
    expected_by_key = {_accounting_key(row): row for row in expected_internal}
    received_by_key: dict[tuple[str, str, str], dict[str, Any]] = {}
    orphan_results: list[dict[str, str]] = []
    for row in received_internal:
        key = _accounting_key(row)
        if key not in expected_by_key:
            orphan_results.append(
                {
                    "result_id": row["result_id"],
                    "reason_code": (
                        "result-plan-binding-mismatch"
                        if not row["plan_binding_valid"]
                        else "orphan-result"
                    ),
                }
            )
        else:
            expected_attempt = expected_by_key[key]["attempt_number"]
            if not row["plan_binding_valid"]:
                row["accounting_exclusion_reason"] = (
                    "stale-result-plan-binding-mismatch"
                )
            elif (
                row["attempt_number"] is not None
                and row["attempt_number"] != expected_attempt
            ):
                row["accounting_exclusion_reason"] = (
                    "stale-result-attempt-number-mismatch"
                )
            received_by_key[key] = row

    expected_by_unit: dict[str, list[dict[str, Any]]] = {
        unit_id: [] for unit_id in lane_order
    }
    for row in expected_internal:
        expected_by_unit[row["work_unit_id"]].append(row)

    all_expected_terminal = bool(expected_internal) and all(
        _accounting_key(row) in received_by_key
        or row["invocation_status"] in TERMINAL_NOT_RUN_INVOCATION_STATUSES
        for row in expected_internal
    )
    every_lane_has_identity = all(expected_by_unit[unit_id] for unit_id in lane_order)
    closed = bool(accounting_closed or (all_expected_terminal and every_lane_has_identity))

    result_accounting: list[dict[str, Any]] = []
    lane_coverage: list[dict[str, Any]] = []
    required_failures: list[dict[str, str]] = []
    optional_degradations: list[dict[str, str]] = []
    residual_risks: list[str] = []
    pending_lanes: list[str] = []

    for unit_id in lane_order:
        unit = units[unit_id]
        attempts = expected_by_unit[unit_id]
        received_attempts = [
            (expected, received_by_key.get(_accounting_key(expected)))
            for expected in attempts
        ]
        successful = [
            (expected, received)
            for expected, received in received_attempts
            if received is not None
            and "accounting_exclusion_reason" not in received
            and received["status"] == "success"
        ]
        partial = [
            (expected, received)
            for expected, received in received_attempts
            if received is not None
            and "accounting_exclusion_reason" not in received
            and received["status"] == "partial"
        ]
        chosen: tuple[dict[str, Any], dict[str, Any]] | None = None
        if successful:
            chosen = successful[-1]
        elif unit["criticality"] == "optional" and partial:
            chosen = partial[-1]
        chosen_key = _accounting_key(chosen[0]) if chosen else None

        for expected, received in received_attempts:
            key = _accounting_key(expected)
            accounting: dict[str, Any] = {
                "invocation_id": expected["invocation_id"],
                "attempt_id": expected["attempt_id"],
                "work_unit_id": unit_id,
            }
            if received is None:
                if closed and expected["invocation_status"] in NOT_RUN_INVOCATION_STATUSES:
                    accounting.update(
                        {
                            "disposition": "not_run",
                            "reason_code": "invocation-not-run",
                        }
                    )
                else:
                    accounting.update(
                        {
                            "disposition": "missing",
                            "reason_code": (
                                "result-missing" if closed else "result-pending"
                            ),
                        }
                    )
            elif "accounting_exclusion_reason" in received:
                accounting.update(
                    {
                        "disposition": "stale",
                        "result_id": received["result_id"],
                        "reason_code": received["accounting_exclusion_reason"],
                    }
                )
            elif key == chosen_key:
                accounting.update(
                    {
                        "disposition": "accepted",
                        "result_id": received["result_id"],
                        "reason_code": (
                            "accepted-verified"
                            if received["status"] == "success"
                            else "accepted-optional-partial"
                        ),
                    }
                )
            elif received["status"] in {"success", "partial"}:
                accounting.update(
                    {
                        "disposition": "excluded",
                        "result_id": received["result_id"],
                        "reason_code": (
                            "required-partial-result"
                            if received["status"] == "partial"
                            and unit["criticality"] == "required"
                            else "superseded-attempt"
                        ),
                    }
                )
            else:
                accounting.update(
                    {
                        "disposition": FAILURE_DISPOSITIONS[received["status"]],
                        "result_id": received["result_id"],
                        "reason_code": f"result-{received['status'].replace('_', '-')}",
                    }
                )
            result_accounting.append(accounting)

        if chosen:
            chosen_expected, chosen_received = chosen
            chosen_attempt_id: str | None = chosen_expected["attempt_id"]
            chosen_result_id: str | None = chosen_received["result_id"]
            superseded = [
                row["attempt_id"]
                for row in attempts
                if row["attempt_id"] != chosen_attempt_id
            ]
            lane_status = "accepted"
            lane_reason = (
                "accepted-latest-success"
                if chosen_received["status"] == "success"
                else "accepted-optional-partial"
            )
        else:
            chosen_attempt_id = None
            chosen_result_id = None
            superseded = []
            latest_received = next(
                (
                    received
                    for _expected, received in reversed(received_attempts)
                    if received is not None
                    and "accounting_exclusion_reason" not in received
                ),
                None,
            )
            if latest_received is not None:
                if latest_received["status"] == "partial":
                    lane_status = "excluded"
                    lane_reason = "required-partial-result"
                else:
                    lane_status = FAILURE_DISPOSITIONS[latest_received["status"]]
                    lane_reason = (
                        f"result-{latest_received['status'].replace('_', '-')}"
                    )
            elif any(
                received is not None
                and "accounting_exclusion_reason" in received
                for _expected, received in received_attempts
            ):
                latest_stale = next(
                    received
                    for _expected, received in reversed(received_attempts)
                    if received is not None
                    and "accounting_exclusion_reason" in received
                )
                lane_status = "stale"
                lane_reason = latest_stale["accounting_exclusion_reason"]
            elif attempts and closed and all(
                row["invocation_status"] in NOT_RUN_INVOCATION_STATUSES
                for row in attempts
            ):
                lane_status = "not_run"
                lane_reason = "lane-not-run"
            else:
                lane_status = "missing"
                lane_reason = "lane-missing" if closed else "lane-pending"

        lane_coverage.append(
            {
                "work_unit_id": unit_id,
                "status": lane_status,
                "chosen_attempt_id": chosen_attempt_id,
                "chosen_result_id": chosen_result_id,
                "superseded_attempt_ids": superseded,
                "reason_code": lane_reason,
            }
        )

        lane_succeeded = (
            chosen is not None and chosen[1]["status"] == "success"
        )
        if not closed and not lane_succeeded:
            pending_lanes.append(unit_id)
        elif unit["criticality"] == "required" and not lane_succeeded:
            required_failures.append(
                {
                    "work_unit_id": unit_id,
                    "reason_code": lane_reason,
                }
            )
        elif unit["criticality"] == "optional" and (
            not lane_succeeded
            or (chosen is not None and chosen[1]["status"] == "partial")
        ):
            optional_degradations.append(
                {
                    "work_unit_id": unit_id,
                    "reason_code": lane_reason,
                }
            )
            residual_risks.append(
                (
                    f"Optional lane {unit_id} returned a partial result; "
                    "its unmet coverage remains explicit."
                    if chosen is not None and chosen[1]["status"] == "partial"
                    else f"Optional lane {unit_id} did not succeed ({lane_reason})."
                )
            )

    missing_invocation_lanes = [
        unit_id for unit_id in lane_order if not expected_by_unit[unit_id]
    ]
    blockers: list[dict[str, str]] = []
    reason_codes: set[str] = set()
    if missing_invocation_lanes:
        reason_codes.add("planned_lane_invocation_missing")
        blockers.append(
            {
                "reason_code": "planned-lane-invocation-missing",
                "human_reason": (
                    "Planned lanes lack an expected invocation identity: "
                    + ", ".join(missing_invocation_lanes)
                ),
            }
        )
    if orphan_results:
        reason_codes.add("orphan_results_excluded")
    if not closed:
        status = "awaiting_results"
        reason_codes.add("result_accounting_pending")
    elif missing_invocation_lanes or required_failures:
        status = "integration_incomplete"
        reason_codes.add("required_lane_not_successful")
        for failure in required_failures:
            blockers.append(
                {
                    "reason_code": "required-lane-not-successful",
                    "human_reason": (
                        f"Required lane {failure['work_unit_id']} did not succeed "
                        f"({failure['reason_code']})."
                    ),
                }
            )
    elif optional_degradations:
        status = "accepted_with_residual_risk"
        reason_codes.add("optional_lane_degraded")
    else:
        status = "ready_for_integration"
        reason_codes.add("exact_result_accounting_complete")

    received_projection = [
        {
            key: row[key]
            for key in (
                "result_id",
                "invocation_id",
                "attempt_id",
                "work_unit_id",
                "status",
                "result_envelope_digest",
            )
        }
        for row in received_internal
    ]
    result_set_rows = sorted(
        (
            row["invocation_id"],
            (
                row["attempt_number"]
                if isinstance(row["attempt_number"], int)
                else expected_by_key.get(_accounting_key(row), {}).get(
                    "attempt_number", 0
                )
            ),
            row["result_envelope_digest"],
        )
        for row in received_internal
    )
    integration_ready = status in {
        "ready_for_integration",
        "accepted_with_residual_risk",
    }
    return {
        "valid": True,
        "status": status,
        "plan_id": plan_id,
        "plan_content_digest": plan_digest,
        "reason_codes": sorted(reason_codes),
        "errors": [],
        "expected_results": [
            {
                key: row[key]
                for key in (
                    "invocation_id",
                    "attempt_id",
                    "work_unit_id",
                    "criticality",
                )
            }
            for row in expected_internal
        ],
        "received_results": received_projection,
        "orphan_results": orphan_results,
        "result_accounting": result_accounting,
        "lane_coverage": lane_coverage,
        "required_failures": required_failures,
        "optional_degradations": optional_degradations,
        "pending_lanes": pending_lanes,
        "residual_risks": residual_risks,
        "blockers": blockers,
        "counts": {
            "planned_lanes": len(lane_order),
            "expected_attempts": len(expected_internal),
            "received_results": len(received_internal),
            "accounted_attempts": len(result_accounting),
            "orphan_results": len(orphan_results),
        },
        "input_result_set_digest": canonical_digest(result_set_rows),
        "accounting_closed": closed,
        "contract_ready": bool(
            closed
            and every_lane_has_identity
            and len(result_accounting) == len(expected_internal)
        ),
        "integration_ready": integration_ready,
        "degraded": status == "accepted_with_residual_risk",
        "authority_effect": copy.deepcopy(AUTHORITY_EFFECT),
    }


exact_lane_accounting = account_parallel_work_results


REBUILD_EVENTS = {"dispatcher_rebuild_requested", "provisional_crb_requested", "llm_advisory_requested", "crb_task_created"}
DISPATCHER_EVENTS = {"task_packet_defect", "human_answered", "queue_changed", "integration_routed"}
WORKER_EVENTS = {"worker_ready_available"}
INTEGRATOR_EVENTS = {"integration_requested", "task_worker_done"}
INTEGRATOR_REVIEW_EVENTS = {"integrator_review_required"}
FINALIZER_EVENTS = {"integration_handoff_ready", "finalization_requested"}
BLOCKED_EVENTS = {"task_blocked", "finalization_blocked", "integration_blocked", "needs_human_created"}
ACTIVE_LOCK_STATES = {"locked", "in_progress", "review"}
NON_INTEGRATION_STATUSES = {
    "finalized",
    "needs_dispatcher",
    "needs_integrator_review",
    "blocked",
    "blocked_model_limit",
    "blocked_by_missing_environment",
    "returned_to_worker",
    "source_evidence_unavailable",
    "closed_no_diff",
    "closed_coordination_only",
}
INTEGRATOR_REVIEW_STATUSES = {
    "integrator_check_required",
    "needs_integrator_review",
}
NON_INTEGRATOR_DECISIONS = {
    "needs_dispatcher",
    "needs_dispatcher_repair",
    "needs_task_packet",
    "needs_worker_fix",
    "worker_ready",
    "needs_architect",
    "needs_human",
    "needs_integrator_review",
    "blocked_by_missing_environment",
    "done",
    "stale_or_superseded",
}
TERMINAL_DISPATCHER_DECISIONS = {
    "blocked",
    "blocked_by_missing_environment",
    "duplicate_linked",
    "needs_human",
    "split_into_children",
    "stale_or_superseded",
    "done",
}
NON_DISPATCHER_OWNERS = {
    "architect",
    "finalizer",
    "human",
    "integrator",
    "worker_pool",
}
SOFT_OPEN_PR_LIMIT = 5
HARD_OPEN_PR_LIMIT = 12
# The scheduler itself runs every five minutes. A longer window lets fresh
# Dispatcher events continuously postpone an already-ready Worker lane.
WORKER_FAIRNESS_MAX_WAIT_SECONDS = 5 * 60
IMMEDIATE_REBUILD_SEVERITIES = {"blocked", "critical"}
PARALLEL_WORK_LAUNCHABLE_STATUSES = {"authorized", "running"}
PARALLEL_WORK_ACTIVE_UNIT_STATUSES = {"queued", "running", "cancelling"}
PARALLEL_WORK_SUCCESS_UNIT_STATUSES = {"completed", "success", "succeeded"}
PARALLEL_WORK_TERMINAL_UNIT_STATUSES = {
    *PARALLEL_WORK_SUCCESS_UNIT_STATUSES,
    "partial",
    "failed",
    "blocked",
    "cancelled",
    "timed_out",
    "rejected",
}
PARALLEL_WORK_PARENT_CANCEL_STATUSES = {
    "cancel_requested",
    "cancellation_requested",
    "cancelled",
}
MODEL_LIMIT_RETRY_AUTO_BATCH_SIZE = 1
MODEL_LIMIT_RETRY_AUTO_MAX_ATTEMPTS = 3
MODEL_LIMIT_RETRY_AUTO_COOLDOWN_SECONDS = 1800
MODEL_LIMIT_RETRY_AUTO_MIN_REMAINING_PERCENT = 10
MODEL_LIMIT_RETRY_AUTO_MAX_EVIDENCE_AGE_MINUTES = 60


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(text)
    except ValueError:
        return None


def _parallel_work_unit_states(runtime_state: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(runtime_state, dict):
        return {}
    raw = runtime_state.get("unit_states", runtime_state.get("units", {}))
    if isinstance(raw, dict):
        return {
            str(key): value
            for key, value in raw.items()
            if str(key) and isinstance(value, dict)
        }
    if isinstance(raw, list):
        result: dict[str, dict[str, Any]] = {}
        for value in raw:
            if not isinstance(value, dict):
                continue
            unit_id = str(value.get("work_unit_id") or "").strip()
            if unit_id:
                result[unit_id] = value
        return result
    return {}


def _parallel_work_attempts_started(state: dict[str, Any]) -> int:
    value = state.get("attempts_started")
    if isinstance(value, int) and not isinstance(value, bool):
        return max(0, value)
    attempts = state.get("attempts")
    if isinstance(attempts, list):
        return len([attempt for attempt in attempts if isinstance(attempt, dict)])
    value = state.get("attempt_number")
    if isinstance(value, int) and not isinstance(value, bool):
        return max(0, value)
    return 0 if str(state.get("status") or "pending").lower() == "pending" else 1


def _parallel_work_unit_status(state: dict[str, Any]) -> str:
    return str(state.get("status") or "pending").strip().lower()


def _parallel_work_terminal(state: dict[str, Any]) -> bool:
    return _parallel_work_unit_status(state) in PARALLEL_WORK_TERMINAL_UNIT_STATUSES


def _parallel_work_success(state: dict[str, Any]) -> bool:
    return _parallel_work_unit_status(state) in PARALLEL_WORK_SUCCESS_UNIT_STATUSES


def _parallel_work_retry_ready(
    state: dict[str, Any],
    *,
    max_attempts: int,
) -> bool:
    attempts_started = _parallel_work_attempts_started(state)
    if _parallel_work_unit_status(state) not in {
        "failed",
        "timed_out",
        "blocked",
        "retry_ready",
    }:
        return False
    if attempts_started < 1 or attempts_started >= max_attempts:
        return False
    if state.get("retry_recorded") is not True:
        return False
    if state.get("retryable") is not True or state.get("deterministic") is True:
        return False
    return state.get("retry_invariants_unchanged") is True


def _parallel_work_dependency_satisfied(
    dependency: dict[str, Any],
    source_state: dict[str, Any],
) -> bool:
    kind = str(dependency.get("type") or "success")
    if kind == "success":
        return _parallel_work_success(source_state)
    if kind == "completion":
        return _parallel_work_terminal(source_state)
    if kind == "artifact_ready":
        return source_state.get("artifact_ready") is True
    if kind == "approval":
        return source_state.get("approved") is True
    if kind == "integration_accepted":
        return source_state.get("integration_accepted") is True
    return False


def _parallel_work_dependency_blocked(
    dependency: dict[str, Any],
    source_state: dict[str, Any],
) -> bool:
    if _parallel_work_dependency_satisfied(dependency, source_state):
        return False
    kind = str(dependency.get("type") or "success")
    if kind == "completion":
        return False
    return _parallel_work_terminal(source_state)


def _parallel_work_barrier_states(
    plan: dict[str, Any],
    unit_states: dict[str, dict[str, Any]],
    runtime_state: dict[str, Any],
) -> list[dict[str, Any]]:
    runtime_barriers = runtime_state.get("barriers")
    if not isinstance(runtime_barriers, dict):
        runtime_barriers = {}
    result: list[dict[str, Any]] = []
    for barrier in plan.get("barriers", []):
        if not isinstance(barrier, dict):
            continue
        barrier_id = str(barrier.get("barrier_id") or "").strip()
        members = [str(value) for value in barrier.get("members", [])]
        required = [str(value) for value in barrier.get("required_members", [])]
        condition = str(barrier.get("release_condition") or "")
        runtime_barrier = runtime_barriers.get(barrier_id)
        if not isinstance(runtime_barrier, dict):
            runtime_barrier = {}
        if condition == "all_required_success":
            released = all(
                _parallel_work_success(unit_states.get(unit_id, {}))
                for unit_id in required
            )
        elif condition == "all_completion":
            released = bool(members) and all(
                _parallel_work_terminal(unit_states.get(unit_id, {}))
                for unit_id in members
            )
        elif condition == "approval":
            released = runtime_barrier.get("approved") is True
        elif condition == "integration_accepted":
            released = runtime_barrier.get("integration_accepted") is True
        else:
            released = False
        result.append(
            {
                "barrier_id": barrier_id,
                "release_condition": condition,
                "released": released,
                "bypass_allowed": False,
                "pending_members": [
                    unit_id
                    for unit_id in members
                    if not _parallel_work_terminal(unit_states.get(unit_id, {}))
                ],
            }
        )
    return result


def parallel_work_ready_set(
    plan: Any,
    runtime_state: Any,
    *,
    live_capacity: int | None = None,
    at: datetime | None = None,
) -> dict[str, Any]:
    """Adapt one authorized Parallel Work plan to the existing scheduler.

    The function is deliberately pure.  It computes deterministic launch,
    barrier, retry, cancellation and integration state but does not write a
    queue, acquire a lease, consume authorization or start a process.
    """

    if not isinstance(plan, dict) or not isinstance(runtime_state, dict):
        return {
            "valid": False,
            "status": "blocked",
            "reason_codes": ["parallel_work_input_invalid"],
            "ready": [],
            "ready_work_unit_ids": [],
            "integration_ready": False,
        }
    plan_id = str(plan.get("plan_id") or "").strip()
    plan_digest = str(plan.get("plan_content_digest") or "").strip()
    reason_codes: set[str] = set()
    if (
        plan.get("contract_kind") != "parallel_work"
        or plan.get("contract_version") != "1.0.0"
        or not plan_id
        or not plan_digest
    ):
        reason_codes.add("parallel_work_plan_invalid")
    plan_status = str(plan.get("status") or "")
    if plan_status not in {
        *PARALLEL_WORK_LAUNCHABLE_STATUSES,
        "awaiting_integration",
        "completed",
        "blocked",
        "cancelled",
    }:
        reason_codes.add("parallel_work_plan_not_authorized")
    reason_codes.update(
        row["code"]
        for row in validate_partial_result_policy(
            plan,
            require_declared=False,
        )
    )
    state_plan_id = str(runtime_state.get("plan_id") or "").strip()
    state_plan_digest = str(runtime_state.get("plan_content_digest") or "").strip()
    if state_plan_id and state_plan_id != plan_id:
        reason_codes.add("parallel_work_runtime_plan_mismatch")
    if state_plan_digest and state_plan_digest != plan_digest:
        reason_codes.add("parallel_work_runtime_digest_mismatch")
    if plan_status in PARALLEL_WORK_LAUNCHABLE_STATUSES:
        router_authorization = plan.get("router_authorization")
        plan_capacity = plan.get("capacity")
        if (
            not isinstance(router_authorization, dict)
            or not isinstance(plan_capacity, dict)
            or router_authorization.get("status") != "granted"
            or router_authorization.get("plan_id") != plan_id
            or router_authorization.get("plan_content_digest") != plan_digest
            or router_authorization.get("budget_digest")
            != plan_capacity.get("budget_digest")
        ):
            reason_codes.add("parallel_work_authorization_binding_invalid")

    raw_units = plan.get("work_units")
    raw_dependencies = plan.get("dependencies")
    ready_order = plan.get("deterministic_ready_order")
    capacity = plan.get("capacity")
    if (
        not isinstance(raw_units, list)
        or not raw_units
        or not isinstance(raw_dependencies, list)
        or not isinstance(ready_order, list)
        or not isinstance(capacity, dict)
    ):
        reason_codes.add("parallel_work_plan_shape_invalid")
        raw_units = raw_units if isinstance(raw_units, list) else []
        raw_dependencies = raw_dependencies if isinstance(raw_dependencies, list) else []
        ready_order = ready_order if isinstance(ready_order, list) else []
        capacity = capacity if isinstance(capacity, dict) else {}

    units = {
        str(unit.get("work_unit_id")): unit
        for unit in raw_units
        if isinstance(unit, dict) and str(unit.get("work_unit_id") or "").strip()
    }
    if len(units) != len(raw_units) or set(str(value) for value in ready_order) != set(units):
        reason_codes.add("parallel_work_unit_identity_invalid")
    effective_lanes = capacity.get("effective_lanes")
    max_attempts = capacity.get("max_attempts_per_unit")
    max_total_invocations = capacity.get("max_total_invocations")
    for value in (effective_lanes, max_attempts, max_total_invocations):
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            reason_codes.add("parallel_work_capacity_invalid")
    if live_capacity is not None and (
        not isinstance(live_capacity, int)
        or isinstance(live_capacity, bool)
        or live_capacity < 0
    ):
        reason_codes.add("parallel_work_live_capacity_invalid")

    if reason_codes:
        return {
            "valid": False,
            "plan_id": plan_id or None,
            "status": "blocked",
            "reason_codes": sorted(reason_codes),
            "ready": [],
            "ready_work_unit_ids": [],
            "integration_ready": False,
        }

    unit_states = _parallel_work_unit_states(runtime_state)
    incoming: dict[str, list[dict[str, Any]]] = {unit_id: [] for unit_id in units}
    for dependency in raw_dependencies:
        if not isinstance(dependency, dict):
            reason_codes.add("parallel_work_dependency_invalid")
            continue
        source = str(dependency.get("from") or "")
        target = str(dependency.get("to") or "")
        kind = str(dependency.get("type") or "")
        if (
            source not in units
            or target not in units
            or kind
            not in {
                "success",
                "completion",
                "artifact_ready",
                "approval",
                "integration_accepted",
            }
        ):
            reason_codes.add("parallel_work_dependency_invalid")
            continue
        incoming[target].append(dependency)
    if reason_codes:
        return {
            "valid": False,
            "plan_id": plan_id,
            "status": "blocked",
            "reason_codes": sorted(reason_codes),
            "ready": [],
            "ready_work_unit_ids": [],
            "integration_ready": False,
        }

    active_ids = [
        unit_id
        for unit_id in ready_order
        if _parallel_work_unit_status(unit_states.get(str(unit_id), {}))
        in PARALLEL_WORK_ACTIVE_UNIT_STATUSES
    ]
    attempts_consumed = sum(
        _parallel_work_attempts_started(unit_states.get(unit_id, {}))
        for unit_id in units
    )
    parent_status = str(runtime_state.get("parent_status") or plan_status).lower()
    parent_cancelled = (
        parent_status in PARALLEL_WORK_PARENT_CANCEL_STATUSES
        or plan_status == "cancelled"
    )
    current = at or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)

    cancellation = {
        "requested": parent_cancelled,
        "queued_to_cancel": [],
        "running_in_grace": [],
        "running_grace_expired": [],
        "retain_evidence": True,
    }
    if parent_cancelled:
        grace_seconds = int(
            (plan.get("cancellation_policy") or {}).get("running_grace_seconds") or 0
        )
        requested_at = parse_time(
            runtime_state.get("cancellation_requested_at")
            or runtime_state.get("cancelled_at")
        )
        if requested_at is not None and requested_at.tzinfo is None:
            requested_at = requested_at.replace(tzinfo=timezone.utc)
        deadline = (
            requested_at.timestamp() + grace_seconds
            if requested_at is not None
            else None
        )
        for unit_id in active_ids:
            status = _parallel_work_unit_status(unit_states.get(str(unit_id), {}))
            if status == "queued":
                cancellation["queued_to_cancel"].append(str(unit_id))
            elif deadline is None or current.timestamp() >= deadline:
                cancellation["running_grace_expired"].append(str(unit_id))
            else:
                cancellation["running_in_grace"].append(str(unit_id))

    active_exclusive: set[str] = set()
    for unit_id in active_ids:
        resources = units[str(unit_id)].get("resources")
        if isinstance(resources, dict):
            active_exclusive.update(str(value) for value in resources.get("exclusive_keys", []))

    blocked: dict[str, list[str]] = {}
    candidates: list[dict[str, Any]] = []
    selected_exclusive = set(active_exclusive)
    for ordered_id in ready_order:
        unit_id = str(ordered_id)
        state = unit_states.get(unit_id, {})
        status = _parallel_work_unit_status(state)
        if status in PARALLEL_WORK_ACTIVE_UNIT_STATUSES or _parallel_work_terminal(state):
            retry_ready = _parallel_work_retry_ready(
                state, max_attempts=int(max_attempts)
            )
            if not retry_ready:
                continue
        else:
            retry_ready = _parallel_work_retry_ready(
                state, max_attempts=int(max_attempts)
            )
            if _parallel_work_attempts_started(state) > 0 and not retry_ready:
                continue
        dependency_reasons: list[str] = []
        for dependency in incoming[unit_id]:
            source = str(dependency["from"])
            source_state = unit_states.get(source, {})
            if _parallel_work_dependency_satisfied(dependency, source_state):
                continue
            code = (
                "dependency_blocked"
                if _parallel_work_dependency_blocked(dependency, source_state)
                else "dependency_pending"
            )
            dependency_reasons.append(
                f"{code}:{source}:{str(dependency.get('type') or 'success')}"
            )
        if dependency_reasons:
            blocked[unit_id] = dependency_reasons
            continue
        resources = units[unit_id].get("resources")
        exclusive = {
            str(value)
            for value in (
                resources.get("exclusive_keys", [])
                if isinstance(resources, dict)
                else []
            )
        }
        if exclusive & selected_exclusive:
            blocked[unit_id] = ["exclusive_resource_active"]
            continue
        selected_exclusive.update(exclusive)
        candidates.append(
            {
                "work_unit_id": unit_id,
                "attempt_number": _parallel_work_attempts_started(state) + 1,
                "retry": retry_ready,
            }
        )

    plan_headroom = max(0, int(effective_lanes) - len(active_ids))
    live_headroom = plan_headroom if live_capacity is None else int(live_capacity)
    budget_headroom = max(0, int(max_total_invocations) - attempts_consumed)
    launch_slots = min(plan_headroom, live_headroom, budget_headroom)
    if parent_cancelled or plan_status not in PARALLEL_WORK_LAUNCHABLE_STATUSES:
        launch_slots = 0
    ready = candidates[:launch_slots]

    barrier_states = _parallel_work_barrier_states(plan, unit_states, runtime_state)
    required_ids = [
        unit_id
        for unit_id, unit in units.items()
        if str(unit.get("criticality") or "") == "required"
    ]
    optional_ids = [unit_id for unit_id in units if unit_id not in required_ids]
    required_failures = [
        unit_id
        for unit_id in required_ids
        if _parallel_work_terminal(unit_states.get(unit_id, {}))
        and not _parallel_work_success(unit_states.get(unit_id, {}))
        and not _parallel_work_retry_ready(
            unit_states.get(unit_id, {}), max_attempts=int(max_attempts)
        )
    ]
    optional_failures = [
        unit_id
        for unit_id in optional_ids
        if _parallel_work_terminal(unit_states.get(unit_id, {}))
        and not _parallel_work_success(unit_states.get(unit_id, {}))
        and not _parallel_work_retry_ready(
            unit_states.get(unit_id, {}), max_attempts=int(max_attempts)
        )
    ]
    all_accounted = all(
        _parallel_work_terminal(unit_states.get(unit_id, {}))
        and not _parallel_work_retry_ready(
            unit_states.get(unit_id, {}), max_attempts=int(max_attempts)
        )
        for unit_id in units
    )
    barriers_released = all(item["released"] for item in barrier_states)
    exact_result_accounting: dict[str, Any] | None = None
    exact_result_evidence_present = any(
        key in runtime_state
        for key in (
            "expected_invocations",
            "result_envelopes",
            "received_results",
            "result_accounting_closed",
        )
    )
    if exact_result_evidence_present:
        result_envelopes = runtime_state.get(
            "result_envelopes",
            runtime_state.get("received_results", []),
        )
        exact_result_accounting = (
            account_parallel_work_results(
                plan,
                runtime_state.get("expected_invocations", []),
                result_envelopes,
                accounting_closed=bool(
                    runtime_state.get("result_accounting_closed") is True
                    or all_accounted
                ),
            )
        )
        if exact_result_accounting["valid"] is not True:
            reason_codes.update(exact_result_accounting["reason_codes"])
            reason_codes.add("exact_result_accounting_invalid")
        exact_required_failures = {
            str(row.get("work_unit_id") or "")
            for row in exact_result_accounting.get("required_failures", [])
            if str(row.get("work_unit_id") or "")
        }
        exact_optional_degradations = {
            str(row.get("work_unit_id") or "")
            for row in exact_result_accounting.get("optional_degradations", [])
            if str(row.get("work_unit_id") or "")
        }
        required_failures = [
            unit_id
            for unit_id in ready_order
            if str(unit_id) in {*required_failures, *exact_required_failures}
        ]
        optional_failures = [
            unit_id
            for unit_id in ready_order
            if str(unit_id) in {*optional_failures, *exact_optional_degradations}
        ]
    integration_ready = bool(
        all_accounted
        and not required_failures
        and barriers_released
        and not parent_cancelled
        and plan_status != "blocked"
        and (
            exact_result_accounting is None
            or exact_result_accounting.get("integration_ready") is True
        )
    )
    if parent_cancelled:
        status = "cancelled"
        reason_codes.add("parent_cancelled")
    elif (
        exact_result_accounting is not None
        and exact_result_accounting.get("valid") is not True
    ):
        status = "blocked"
    elif (
        exact_result_accounting is not None
        and exact_result_accounting.get("status") == "integration_incomplete"
    ):
        status = "blocked"
        reason_codes.add("exact_result_accounting_incomplete")
    elif required_failures:
        status = "ready_with_blocked_branches" if ready else "blocked"
        reason_codes.add("required_work_unit_failed")
    elif integration_ready:
        status = "accepted_with_residual_risk" if optional_failures else "ready_for_integration"
        if optional_failures:
            reason_codes.add("optional_work_unit_failed")
    elif ready:
        status = "ready"
    elif active_ids:
        status = "running"
    elif all_accounted and not barriers_released:
        status = "waiting_for_barrier"
        reason_codes.add("barrier_pending")
    else:
        status = "waiting"

    return {
        "valid": True,
        "plan_id": plan_id,
        "plan_content_digest": plan_digest,
        "status": status,
        "reason_codes": sorted(reason_codes),
        "ready": ready,
        "ready_work_unit_ids": [item["work_unit_id"] for item in ready],
        "candidate_work_unit_ids": [item["work_unit_id"] for item in candidates],
        "blocked": blocked,
        "active_work_unit_ids": [str(value) for value in active_ids],
        "capacity": {
            "effective_lanes": int(effective_lanes),
            "active_lanes": len(active_ids),
            "plan_headroom": plan_headroom,
            "live_headroom": live_headroom,
            "budget_headroom": budget_headroom,
            "launch_slots": launch_slots,
        },
        "progress": {
            "denominator": int(plan.get("progress_denominator") or len(units)),
            "accounted": sum(
                1
                for unit_id in units
                if _parallel_work_terminal(unit_states.get(unit_id, {}))
            ),
            "attempts_consumed": attempts_consumed,
        },
        "barriers": barrier_states,
        "required_failures": required_failures,
        "optional_failures": optional_failures,
        "residual_risk": bool(optional_failures),
        "residual_risks": (
            list(exact_result_accounting.get("residual_risks", []))
            if exact_result_accounting is not None
            else [
                f"Optional lane {unit_id} did not succeed."
                for unit_id in optional_failures
            ]
        ),
        "result_accounting_mode": (
            "exact_result_envelopes"
            if exact_result_accounting is not None
            else "unit_state_compatibility"
        ),
        "result_accounting": exact_result_accounting,
        "integration_ready": integration_ready,
        "cancellation": cancellation,
    }


parallel_work_schedule = parallel_work_ready_set


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_events(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    events: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            events.append({"event_id": f"invalid-line-{line_number}", "event": "invalid_event", "severity": "critical", "line": line_number})
            continue
        if isinstance(item, dict):
            events.append(item)
    return events


def write_events(path: Path, events: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps(event, ensure_ascii=False, sort_keys=True) for event in events]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def unconsumed(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [event for event in events if not event.get("consumed_by")]


def apply_model_limit_recovery(
    queue: dict[str, Any],
    locks: dict[str, Any] | None,
    runtime_root: Path,
    *,
    batch_size: int = MODEL_LIMIT_RETRY_AUTO_BATCH_SIZE,
    max_attempts: int = MODEL_LIMIT_RETRY_AUTO_MAX_ATTEMPTS,
    cooldown_seconds: int = MODEL_LIMIT_RETRY_AUTO_COOLDOWN_SECONDS,
    min_remaining_percent: int = MODEL_LIMIT_RETRY_AUTO_MIN_REMAINING_PERCENT,
    max_age_minutes: int = MODEL_LIMIT_RETRY_AUTO_MAX_EVIDENCE_AGE_MINUTES,
) -> tuple[dict[str, Any], dict[str, Any], bool]:
    active_locks = authorize_model_limit_retries.active_lock_task_ids(locks)
    authorized_queue, approved, skipped, counters = authorize_model_limit_retries.process_automatic_queue(
        queue,
        active_locks,
        evidence_root=runtime_root,
        batch_size=max(0, batch_size),
        max_attempts=max(1, max_attempts),
        cooldown_seconds=max(0, cooldown_seconds),
        min_remaining_percent=max(0, min_remaining_percent),
        max_age_minutes=max(0, max_age_minutes),
    )
    auto_authorized_ids = {str(item.get("task_id") or "").strip() for item in approved}
    promoted_queue, promoted, promotion_skipped = promote_worker_ready_tasks.process_queue(
        authorized_queue,
        active_locks,
    )
    auto_promotion_summary: list[str] = []
    for index, task in enumerate(tasks(promoted_queue)):
        if not isinstance(task, dict):
            continue
        tid = task_id(task) or f"index-{index}"
        if tid not in auto_authorized_ids:
            continue
        if task.get("model_limit_retry_source") != "automatic_capacity_recovery":
            continue
        if task.get("status") == "planned" and task.get("worker_ready") is True and task.get("dispatcher_decision") == "worker_ready":
            if tid not in {entry["task_id"] for entry in promoted}:
                auto_promotion_summary.append(tid)

    changed = promoted_queue != queue
    if auto_promotion_summary:
        changed = True
    summary: dict[str, Any] = {
        "enabled": True,
        "approved": approved,
        "skipped": skipped,
        "promoted": promoted,
        "promotion_skipped": promotion_skipped,
        "counters": counters,
        "runtime_root": str(runtime_root),
        "changed": changed,
    }
    return promoted_queue, summary, changed


def event_id(event: dict[str, Any]) -> str:
    explicit = str(event.get("event_id") or "").strip()
    if explicit:
        return explicit
    legacy = {
        "event": event.get("event"),
        "task_id": event.get("task_id"),
        "ts": event.get("ts") or event.get("created_at"),
        "owner": event.get("owner") or event.get("role") or event.get("source"),
    }
    payload = json.dumps(legacy, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "legacy-event-" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:24]


def tasks(queue: Any) -> list[dict[str, Any]]:
    if not isinstance(queue, dict):
        return []
    values = queue.get("tasks", queue.get("queue", []))
    return [task for task in values if isinstance(task, dict)] if isinstance(values, list) else []


def tasks_by_id(queue: Any) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for task in tasks(queue):
        task_id = str(task.get("id") or "").strip()
        if task_id:
            result[task_id] = task
            result[f"task:{task_id}"] = task
    return result


def has_list(task: dict[str, Any], field: str) -> bool:
    value = task.get(field)
    return isinstance(value, list) and bool(value)


def as_list(value: Any) -> list[Any]:
    if isinstance(value, list):
        return value
    return []


def has_value(task: dict[str, Any], field: str) -> bool:
    value = task.get(field)
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return bool(value)
    return True


def has_current_context_verification(task: dict[str, Any]) -> bool:
    return has_value(task, "current_context_verified_at") and (
        has_value(task, "current_context_verified_by")
        or has_value(task, "current_context_reviewed_by")
    )


def task_id(task: dict[str, Any]) -> str:
    return str(task.get("id") or task.get("task_id") or "").strip()


def completed_task_ids(queue: Any) -> set[str]:
    done_statuses = {"done", "completed", "finalized", "released", "archived", "owner_approved"}
    result: set[str] = set()
    for task in tasks(queue):
        if str(task.get("status") or "").lower() in done_statuses:
            value = task_id(task)
            if value:
                result.add(value)
                result.add(f"task:{value}")
    return result


def unresolved_dependencies(task: dict[str, Any], completed_ids: set[str] | None = None) -> list[str]:
    if completed_ids is None:
        completed_ids = set()
    return [
        str(item).strip()
        for item in as_list(task.get("depends_on"))
        if str(item).strip() and str(item).strip() not in completed_ids
    ]


def is_worker_ready(task: dict[str, Any], completed_ids: set[str] | None = None) -> bool:
    if task.get("worker_ready") is not True:
        return False
    if task.get("dispatcher_decision") != "worker_ready":
        return False
    if str(task.get("status") or "") not in {"planned", "needs_stronger_agent", "worker_ready"}:
        return False
    lock = task.get("lock")
    if isinstance(lock, dict) and lock.get("state") not in (None, "free"):
        return False
    if isinstance(lock, str) and lock.lower() not in {"", "free"}:
        return False
    if unresolved_dependencies(task, completed_ids):
        return False
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        return False
    return (
        has_value(task, "complexity")
        and has_list(task, "allowed_paths")
        and has_list(task, "forbidden_paths")
        and has_list(task, "acceptance_criteria")
        and has_list(task, "checks")
    )


def count_status(queue: Any, names: set[str]) -> int:
    return sum(1 for task in tasks(queue) if str(task.get("status") or "") in names)


def is_integration_ready_task(task: dict[str, Any]) -> bool:
    status = str(task.get("status") or "")
    integration_status = str(task.get("integration_status") or "")
    dispatcher_decision = str(task.get("dispatcher_decision") or "")
    if integration_status in NON_INTEGRATION_STATUSES:
        return False
    integration_statuses = {"agent_done", "review", "integration_ready", "integration_requested"}
    if dispatcher_decision in NON_INTEGRATOR_DECISIONS and status not in integration_statuses:
        return False
    if dispatcher_decision in NON_INTEGRATOR_DECISIONS - {"worker_ready"}:
        return False
    return status in integration_statuses


def count_integration_ready(queue: Any) -> int:
    return sum(1 for task in tasks(queue) if is_integration_ready_task(task))


def is_dispatcher_repair_ready(task: dict[str, Any]) -> bool:
    if str(task.get("integration_status") or "") == "needs_dispatcher":
        return True
    if str(task.get("status") or "") not in {"needs_task_packet", "needs_dispatcher_repair", "needs_worker_fix"} and str(task.get("dispatcher_decision") or "") not in {
        "needs_dispatcher",
        "needs_dispatcher_repair",
        "needs_task_packet",
    }:
        return False
    if task.get("requires_current_context_review") is True and not has_current_context_verification(task):
        return False
    return True


def count_dispatcher_repair_ready(queue: Any) -> int:
    return sum(1 for task in tasks(queue) if is_dispatcher_repair_ready(task))


def count_needs_architect(queue: Any) -> int:
    return sum(
        1
        for task in tasks(queue)
        if str(task.get("status") or "") == "needs_architect"
        or str(task.get("dispatcher_decision") or "") == "needs_architect"
    )


def has_explicit_non_dispatcher_route(task: dict[str, Any]) -> bool:
    owner = str(task.get("next_owner") or "").strip().lower()
    return owner in NON_DISPATCHER_OWNERS and bool(str(task.get("next_action") or "").strip())


def has_manual_owner_route(task: dict[str, Any]) -> bool:
    owner = str(task.get("next_owner") or "").strip().lower()
    return owner in {"human", "owner", "operator", "manual"} and bool(str(task.get("next_action") or "").strip())


def has_repository_draft_hold(task: dict[str, Any]) -> bool:
    return bool(task.get("repository_hygiene_draft_prs"))


def count_stalled_dispatcher_review(queue: Any) -> int:
    return sum(
        1
        for task in tasks(queue)
        if str(task.get("status") or "") in {"blocked", "needs_human", "agent_done"}
        and str(task.get("integration_status") or "") not in NON_INTEGRATION_STATUSES
        and str(task.get("integration_status") or "") not in INTEGRATOR_REVIEW_STATUSES
        and str(task.get("dispatcher_decision") or "") not in TERMINAL_DISPATCHER_DECISIONS
        and str(task.get("dispatcher_decision") or "") != "needs_integrator_review"
        and not has_explicit_non_dispatcher_route(task)
    )


def requires_integrator_review(task: dict[str, Any]) -> bool:
    return not has_manual_owner_route(task) and not has_repository_draft_hold(task) and (
        str(task.get("integration_status") or "") in INTEGRATOR_REVIEW_STATUSES
        or str(task.get("dispatcher_decision") or "") == "needs_integrator_review"
    )


def count_integrator_review(queue: Any) -> int:
    return sum(1 for task in tasks(queue) if requires_integrator_review(task))


def queue_needs_dispatcher_review(queue: Any, activity: Any) -> bool:
    if not isinstance(queue, dict):
        return False
    queue_updated_at = str(queue.get("updated_at") or "")
    if not queue_updated_at:
        return False
    candidates: list[str] = []
    last_dispatcher_pass = queue.get("last_dispatcher_pass")
    if isinstance(last_dispatcher_pass, dict):
        ran_at = str(last_dispatcher_pass.get("ran_at") or "")
        if ran_at:
            candidates.append(ran_at)
    if isinstance(activity, dict):
        role_activity = activity.get("role_activity")
        if isinstance(role_activity, dict):
            dispatcher = role_activity.get("auto_dispatcher")
            if isinstance(dispatcher, dict):
                for key in ("last_checked_at", "last_finished_at"):
                    value = str(dispatcher.get(key) or "")
                    if value:
                        candidates.append(value)
    last_ran_at = max(candidates) if candidates else ""
    return not last_ran_at or queue_updated_at > last_ran_at


def queue_needs_integrator_review(queue: Any, activity: Any) -> bool:
    if not isinstance(queue, dict):
        return False
    queue_updated_at = str(queue.get("updated_at") or "")
    if not queue_updated_at:
        return True
    candidates: list[str] = []
    if isinstance(activity, dict):
        role_activity = activity.get("role_activity")
        if isinstance(role_activity, dict):
            integrator = role_activity.get("auto_integrator")
            if isinstance(integrator, dict):
                for key in ("last_checked_at", "last_finished_at"):
                    value = str(integrator.get(key) or "")
                    if value:
                        candidates.append(value)
    last_ran_at = max(candidates) if candidates else ""
    return not last_ran_at or queue_updated_at > last_ran_at


def normalized_time(value: Any) -> datetime | None:
    parsed = parse_time(value)
    if parsed is None:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def latest_role_activity_time(activity: Any, role: str) -> datetime | None:
    if not isinstance(activity, dict):
        return None
    role_activity = activity.get("role_activity")
    if not isinstance(role_activity, dict):
        return None
    item = role_activity.get(role)
    if not isinstance(item, dict):
        return None
    timestamps = [
        parsed
        for key in ("last_started_at", "last_finished_at", "last_checked_at")
        if (parsed := normalized_time(item.get(key))) is not None
    ]
    return max(timestamps) if timestamps else None


def worker_wait_reference(worker_events: list[dict[str, Any]], activity: Any) -> datetime | None:
    event_times = [
        parsed
        for event in worker_events
        if (parsed := normalized_time(event.get("created_at") or event.get("ts"))) is not None
    ]
    last_activity = latest_role_activity_time(activity, "auto_workers")
    if last_activity is None:
        return min(event_times) if event_times else None
    pending_after_activity = [timestamp for timestamp in event_times if timestamp > last_activity]
    return min(pending_after_activity) if pending_after_activity else last_activity


def worker_lane_overdue(
    worker_events: list[dict[str, Any]],
    activity: Any,
    *,
    now: datetime | None = None,
) -> tuple[bool, datetime | None]:
    reference = worker_wait_reference(worker_events, activity)
    if reference is None:
        return False, None
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    return (current - reference).total_seconds() >= WORKER_FAIRNESS_MAX_WAIT_SECONDS, reference


def immediate_lane_pending(events: list[dict[str, Any]]) -> bool:
    for event in events:
        severity = str(event.get("severity") or "").strip().lower()
        required_lane = event.get("required_lane")
        required = required_lane is True or str(required_lane or "").strip().lower() in {
            "blocking",
            "required",
            "true",
        }
        if severity in IMMEDIATE_REBUILD_SEVERITIES or required:
            return True
    return False


def count_clean_rebuild_worker_ready(queue: Any, completed_ids: set[str] | None = None) -> int:
    return sum(1 for task in tasks(queue) if is_worker_ready(task, completed_ids) and str(task.get("type") or "") == "clean-rebuild")


def worker_host_available(codex_bin: str) -> bool:
    return codex_host_readiness(codex_bin).ok


def worker_host_diagnostic(codex_bin: str) -> dict[str, Any]:
    return codex_host_readiness(codex_bin).to_dict()


def active_locks(locks: Any) -> int:
    if not isinstance(locks, dict):
        return 0
    values = locks.get("locks", [])
    if not isinstance(values, list):
        return 0
    now = datetime.now(timezone.utc)
    count = 0
    for lock in values:
        if not isinstance(lock, dict) or lock.get("state") not in ACTIVE_LOCK_STATES:
            continue
        expires_at = parse_time(lock.get("expires_at"))
        if expires_at is not None:
            if expires_at.tzinfo is None:
                expires_at = expires_at.replace(tzinfo=timezone.utc)
            if expires_at <= now:
                continue
        count += 1
    return count


def worker_blocking_locks(locks: Any) -> int:
    if not isinstance(locks, dict):
        return 0
    values = locks.get("locks", [])
    if not isinstance(values, list):
        return 0
    now = datetime.now(timezone.utc)
    count = 0
    for lock in values:
        if not isinstance(lock, dict) or lock.get("state") not in {"locked", "in_progress"}:
            continue
        expires_at = parse_time(lock.get("expires_at"))
        if expires_at is not None:
            if expires_at.tzinfo is None:
                expires_at = expires_at.replace(tzinfo=timezone.utc)
            if expires_at <= now:
                continue
        count += 1
    return count


def event_names(events: list[dict[str, Any]]) -> set[str]:
    return {str(event.get("event") or "") for event in events}


def matching_events(events: list[dict[str, Any]], names: set[str]) -> list[dict[str, Any]]:
    return [event for event in events if str(event.get("event") or "") in names]


def pending_dispatcher_events(pending_events: list[dict[str, Any]], queue: Any, completed_ids: set[str] | None = None) -> list[dict[str, Any]]:
    by_id = tasks_by_id(queue)
    completed_ids = completed_ids or set()
    result: list[dict[str, Any]] = []
    for event in matching_events(pending_events, DISPATCHER_EVENTS):
        target_keys = event_target_keys(event)
        if target_keys and target_keys & completed_ids:
            continue
        if str(event.get("event") or "") == "integration_routed":
            target = next((by_id[key] for key in target_keys if key in by_id), None)
            if target is not None and (has_manual_owner_route(target) or has_repository_draft_hold(target)):
                continue
        result.append(event)
    return result


def event_target_keys(event: dict[str, Any]) -> set[str]:
    keys: set[str] = set()
    for field in ("task_id", "canonical_target_id"):
        value = str(event.get(field) or "").strip()
        if not value:
            continue
        keys.add(value)
        if value.startswith("task:"):
            keys.add(value.removeprefix("task:"))
        else:
            keys.add(f"task:{value}")
    return keys


def finalization_recorded_task_ids(events: list[dict[str, Any]]) -> set[str]:
    recorded: set[str] = set()
    for event in events:
        event_name = str(event.get("event") or "")
        targets = event_target_keys(event)
        if event_name in {"integration_invalidated", "finalization_invalidated"}:
            recorded.difference_update(targets)
        elif event_name == "finalization_recorded":
            recorded.update(targets)
    return recorded


def integration_recorded_task_ids(events: list[dict[str, Any]]) -> set[str]:
    integrated: set[str] = set()
    finalized: set[str] = set()
    for event in events:
        event_name = str(event.get("event") or "")
        targets = event_target_keys(event)
        if event_name == "integration_invalidated":
            integrated.difference_update(targets)
            finalized.difference_update(targets)
        elif event_name == "finalization_invalidated":
            finalized.difference_update(targets)
        elif event_name in {"integration_recorded", "direct_merge_recorded"}:
            integrated.update(targets)
        elif event_name == "finalization_recorded":
            integrated.update(targets)
            finalized.update(targets)
    return integrated | finalized


def pending_finalizer_events(pending_events: list[dict[str, Any]], all_events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    recorded = finalization_recorded_task_ids(all_events)
    result: list[dict[str, Any]] = []
    for event in matching_events(pending_events, FINALIZER_EVENTS):
        target_keys = event_target_keys(event)
        if target_keys and target_keys & recorded:
            continue
        result.append(event)
    return result


def pending_integrator_events(project_root: Path, pending_events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    all_events = read_events(task_file(project_root, "agent_events.jsonl"))
    queue = load_json(task_file(project_root, "task_queue.json"))
    by_id = tasks_by_id(queue)
    recorded = integration_recorded_task_ids(all_events)
    report = load_json(task_file(project_root, "integrator_direct_merge.json"))
    report_at = parse_time(report.get("created_at")) if isinstance(report, dict) else None
    result: list[dict[str, Any]] = []
    for event in matching_events(pending_events, INTEGRATOR_EVENTS):
        target_keys = event_target_keys(event)
        if target_keys and target_keys & recorded:
            continue
        target = next((by_id[key] for key in target_keys if key in by_id), None)
        if target is not None and not is_integration_ready_task(target):
            continue
        event_name = str(event.get("event") or "")
        task_id = str(event.get("task_id") or "").strip()
        if event_name == "task_worker_done" and not task_id and report_at:
            event_at = parse_time(event.get("created_at"))
            if event_at and report_at >= event_at:
                continue
        result.append(event)
    return result


def pending_integrator_review_events(pending_events: list[dict[str, Any]], queue: Any) -> list[dict[str, Any]]:
    by_id = tasks_by_id(queue)
    result: list[dict[str, Any]] = []
    for event in matching_events(pending_events, INTEGRATOR_REVIEW_EVENTS):
        target_keys = event_target_keys(event)
        target = next((by_id[key] for key in target_keys if key in by_id), None)
        if target is None or not requires_integrator_review(target):
            continue
        result.append(event)
    return result


def has_fresh_integration_ready_after(queue: Any, report_at: datetime | None) -> bool:
    if report_at is None:
        return False
    for task in tasks(queue):
        if not is_integration_ready_task(task):
            continue
        for key in ("worker_result_synced_at", "updated_at", "completed_at"):
            value = parse_time(task.get(key))
            if value and value > report_at:
                return True
    return False


def has_integration_payload(queue: Any) -> bool:
    for task in tasks(queue):
        if not is_integration_ready_task(task):
            continue
        if not (has_value(task, "branch") or has_value(task, "github_branch")):
            continue
        if has_list(task, "changed_paths") or has_list(task, "integration_changed_paths"):
            return True
    return False


def integrator_report_exhausted(project_root: Path, queue: Any = None) -> bool:
    report = load_json(task_file(project_root, "integrator_direct_merge.json"))
    if not isinstance(report, dict):
        return False
    status = str(report.get("status") or "")
    if status not in {"no_candidates", "no_ready_items", "routed_no_direct_merge_candidates"}:
        return False
    report_at = parse_time(report.get("created_at"))
    if queue is not None and has_fresh_integration_ready_after(queue, report_at):
        return False
    ready = report.get("ready")
    return not isinstance(ready, list) or not ready


def pending_blocked_events(pending_events: list[dict[str, Any]], *, active_handoff: bool) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for event in matching_events(pending_events, BLOCKED_EVENTS):
        if str(event.get("event") or "") == "finalization_blocked" and not active_handoff:
            continue
        result.append(event)
    return result


def active_finalizer_handoff(project_root: Path, all_events: list[dict[str, Any]] | None = None) -> bool:
    path = task_file(project_root, "integration_handoff.json")
    data = load_json(path)
    if not isinstance(data, dict):
        return False
    ready = data.get("ready_to_finalize")
    status = str(data.get("integration_status") or "")
    if not isinstance(ready, list) or status not in {"integration_package_ready", "partial_package_ready", "ready_to_finalize", "integration_handoff_ready"}:
        return False
    ready_keys: set[str] = set()
    for item in ready:
        value = str(item or "").strip()
        if not value:
            continue
        ready_keys.add(value)
        ready_keys.add(value.removeprefix("task:") if value.startswith("task:") else f"task:{value}")
    if not ready_keys:
        return False
    recorded = finalization_recorded_task_ids(all_events or [])
    return bool(ready_keys - recorded)


def decide(project_root: Path, args: argparse.Namespace) -> dict[str, Any]:
    queue_path = Path(args.queue).resolve() if args.queue else task_file(project_root, "task_queue.json")
    history_path = Path(args.history).resolve() if getattr(args, "history", None) else task_file(project_root, "task_history.json")
    locks_path = Path(args.locks).resolve() if args.locks else task_file(project_root, "agent_locks.json")
    activity_path = Path(args.activity).resolve() if args.activity else task_file(project_root, "agent_activity_state.json")
    events_path = Path(args.events).resolve() if args.events else task_file(project_root, "agent_events.jsonl")

    queue = load_json(queue_path)
    queue_snapshot = copy.deepcopy(queue)
    history = load_json(history_path)
    locks = load_json(locks_path)
    activity = load_json(activity_path)
    all_events = read_events(events_path)
    pending = unconsumed(all_events)
    names = event_names(pending)
    open_pr_stack = args.open_pr_stack
    done_ids = completed_task_ids(queue) | completed_task_ids(history)
    model_limit_recovery_runtime_root = Path(getattr(args, "runtime_root", Path.home() / "agent-runtime")).expanduser()
    model_limit_recovery_enabled = bool(getattr(args, "auto_model_limit_retries", False))
    if model_limit_recovery_enabled:
        queue, recovery, _ = apply_model_limit_recovery(
            queue,
            locks,
            model_limit_recovery_runtime_root,
            batch_size=getattr(args, "model_limit_retry_batch_size", MODEL_LIMIT_RETRY_AUTO_BATCH_SIZE),
            max_attempts=getattr(args, "model_limit_retry_max_attempts", MODEL_LIMIT_RETRY_AUTO_MAX_ATTEMPTS),
            cooldown_seconds=getattr(args, "model_limit_retry_cooldown_seconds", MODEL_LIMIT_RETRY_AUTO_COOLDOWN_SECONDS),
            min_remaining_percent=getattr(args, "model_limit_retry_min_remaining_percent", MODEL_LIMIT_RETRY_AUTO_MIN_REMAINING_PERCENT),
            max_age_minutes=getattr(args, "model_limit_retry_max_age_minutes", MODEL_LIMIT_RETRY_AUTO_MAX_EVIDENCE_AGE_MINUTES),
        )
    else:
        recovery = {
            "enabled": False,
            "approved": [],
            "skipped": [],
            "promoted": [],
            "promotion_skipped": [],
            "counters": {
                "waiting": 0,
                "eligible": 0,
                "authorized": 0,
                "cooldown": 0,
                "exhausted": 0,
                "batch_limited": 0,
            },
            "runtime_root": str(model_limit_recovery_runtime_root),
        }

    if getattr(args, "apply", False) and queue != queue_snapshot:
        write_json(queue_path, queue)

    worker_ready_count = sum(1 for task in tasks(queue) if is_worker_ready(task, done_ids))
    clean_rebuild_worker_ready_count = count_clean_rebuild_worker_ready(queue, done_ids)
    integration_ready_count = count_integration_ready(queue)
    integrator_review_count = count_integrator_review(queue)
    needs_architect_count = count_needs_architect(queue)
    dispatcher_repair_count = count_dispatcher_repair_ready(queue)
    stalled_dispatcher_review_count = count_stalled_dispatcher_review(queue)
    handoff_ready_count = count_status(queue, {"integration_handoff_ready", "finalization_ready"})
    lock_count = active_locks(locks)
    worker_lock_count = worker_blocking_locks(locks)
    check_worker_host = hasattr(args, "codex_bin")
    codex_bin = str(getattr(args, "codex_bin", os.environ.get("CODEX_BIN", "codex")) or "codex")
    worker_host = worker_host_diagnostic(codex_bin) if worker_ready_count and check_worker_host else {
        "ok": True,
        "reason": "not_checked",
        "codex_bin": codex_bin,
        "doctor_checked": False,
    }
    worker_host_ready = bool(worker_host.get("ok"))

    decision = {
        "project_root": str(project_root),
        "checked_at": utc_now(),
        "mode": "event_driven",
        "should_run": False,
        "run_class": "scan_only",
        "role": None,
        "reason": "no_unconsumed_event_or_ready_state",
        "trigger_event_ids": [],
        "counts": {
            "pending_events": len(pending),
            "unconsumed_events_total": len(pending),
            "actionable_events": len(pending),
            "ignored_unconsumed_events": 0,
            "worker_ready": worker_ready_count,
            "clean_rebuild_worker_ready": clean_rebuild_worker_ready_count,
            "integration_ready": integration_ready_count,
            "integrator_review": integrator_review_count,
            "needs_architect": needs_architect_count,
            "handoff_ready": handoff_ready_count,
            "dispatcher_repair": dispatcher_repair_count,
            "stalled_dispatcher_review": stalled_dispatcher_review_count,
            "active_locks": lock_count,
            "worker_blocking_locks": worker_lock_count,
            "open_pr_stack": open_pr_stack,
            "model_limit_retry_eligible": recovery.get("counters", {}).get("eligible", 0),
            "model_limit_retry_authorized": recovery.get("counters", {}).get("authorized", 0),
            "model_limit_retry_waiting": recovery.get("counters", {}).get("waiting", 0),
            "model_limit_retry_exhausted": recovery.get("counters", {}).get("exhausted", 0),
            "model_limit_retry_batch_limited": recovery.get("counters", {}).get("batch_limited", 0),
            "model_limit_retry_cooldown": recovery.get("counters", {}).get("cooldown", 0),
        },
        "throttle": {
            "worker_creation": open_pr_stack > SOFT_OPEN_PR_LIMIT,
            "worker_creation_blocked": open_pr_stack >= HARD_OPEN_PR_LIMIT,
            "soft_open_pr_limit": SOFT_OPEN_PR_LIMIT,
            "hard_open_pr_limit": HARD_OPEN_PR_LIMIT,
        },
        "host_capabilities": {
            "codex_bin": codex_bin,
            "worker_host_checked": check_worker_host,
            "worker_host_available": worker_host_ready,
            "worker_host_reason": worker_host.get("reason"),
            "worker_host_readiness": worker_host,
        },
        "model_limit_recovery": recovery,
        "event_file": str(events_path),
        "activity_file": str(activity_path),
    }

    rebuild = matching_events(pending, REBUILD_EVENTS)
    dispatcher = pending_dispatcher_events(pending, queue, done_ids)
    worker = matching_events(pending, WORKER_EVENTS)
    if not worker_ready_count:
        worker = []
    finalizer = pending_finalizer_events(pending, all_events)
    active_handoff = active_finalizer_handoff(project_root, all_events)
    if finalizer and not active_handoff:
        finalizer = []
    integrator_review_events = pending_integrator_review_events(pending, queue)
    integrator = pending_integrator_events(project_root, pending)
    integration_exhausted = False
    exhausted_integration_ready_count = 0
    if integration_ready_count and not integrator and integrator_report_exhausted(project_root, queue):
        integration_exhausted = True
        exhausted_integration_ready_count = integration_ready_count
        integration_ready_count = 0
        decision["counts"]["integration_ready"] = 0
        decision["counts"]["exhausted_integration_ready"] = exhausted_integration_ready_count
        decision["integration_suppressed_by"] = "latest_integrator_report_exhausted"
    blocked = pending_blocked_events(pending, active_handoff=active_handoff)
    actionable_events = [
        event
        for group in (rebuild, integrator_review_events, dispatcher, worker, finalizer, integrator, blocked)
        for event in group
    ]
    actionable_ids = {event_id(event) for event in actionable_events}
    ignored_events = [event for event in pending if event_id(event) not in actionable_ids]
    actionable_event_count = len(actionable_events)
    decision["counts"]["pending_events"] = actionable_event_count
    decision["counts"]["actionable_events"] = actionable_event_count
    decision["counts"]["ignored_unconsumed_events"] = len(ignored_events)
    decision["ignored_event_ids"] = [event_id(event) for event in ignored_events]

    worker_overdue, worker_wait_started_at = worker_lane_overdue(worker, activity)
    immediate_rebuild = immediate_lane_pending(rebuild)
    immediate_dispatcher = immediate_lane_pending(dispatcher)
    integrator_review_required_now = bool(integrator_review_events) or (
        integrator_review_count and queue_needs_integrator_review(queue, activity)
    )
    recent_integrator_review = bool(
        integrator_review_count and not queue_needs_integrator_review(queue, activity)
    )
    required_lane_pending = bool(
        integrator_review_required_now
        or integrator
        or integration_ready_count
        or ((finalizer or handoff_ready_count) and active_handoff)
    )
    worker_fairness_eligible = bool(
        worker_ready_count
        and worker_overdue
        and worker_host_ready
        and not worker_lock_count
        and open_pr_stack <= SOFT_OPEN_PR_LIMIT
        and not immediate_rebuild
        and not immediate_dispatcher
        and not required_lane_pending
    )
    decision["fairness"] = {
        "worker_lane_overdue": worker_overdue,
        "worker_lane_eligible": worker_fairness_eligible,
        "worker_max_wait_seconds": WORKER_FAIRNESS_MAX_WAIT_SECONDS,
        "worker_wait_reference_at": (
            worker_wait_started_at.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
            if worker_wait_started_at is not None
            else None
        ),
        "required_lane_pending": required_lane_pending,
        "immediate_rebuild_pending": immediate_rebuild,
        "immediate_dispatcher_pending": immediate_dispatcher,
    }

    if worker_fairness_eligible:
        decision.update({
            "should_run": True,
            "run_class": "worker_run",
            "role": "auto_workers",
            "reason": (
                "worker_ready_after_recent_integrator_review"
                if recent_integrator_review
                else "worker_starvation_prevention"
            ),
            "trigger_event_ids": [event_id(event) for event in worker],
        })
    elif rebuild and immediate_rebuild:
        decision.update({
            "should_run": True,
            "run_class": "rebuild_route",
            "role": "auto_dispatcher",
            "reason": "immediate_rebuild_route_event_pending",
            "trigger_event_ids": [event_id(event) for event in rebuild],
        })
    elif integrator_review_events:
        decision.update({
            "should_run": True,
            "run_class": "integrator_review",
            "role": "auto_integrator",
            "reason": "integrator_review_event_pending",
            "trigger_event_ids": [event_id(event) for event in integrator_review_events],
        })
    elif integrator:
        decision.update({
            "should_run": True,
            "run_class": "integration_run",
            "role": "auto_integrator",
            "reason": "integration_event_pending",
            "trigger_event_ids": [event_id(event) for event in integrator],
        })
    elif (finalizer or handoff_ready_count) and active_handoff:
        decision.update({
            "should_run": True,
            "run_class": "finalization_run",
            "role": "auto_finalizer",
            "reason": "finalization_event_or_handoff_ready",
            "trigger_event_ids": [event_id(event) for event in finalizer],
        })
    elif integration_ready_count:
        decision.update({
            "should_run": True,
            "run_class": "integration_run",
            "role": "auto_integrator",
            "reason": "integration_event_or_queue_ready",
            "trigger_event_ids": [event_id(event) for event in integrator],
        })
    elif dispatcher or (dispatcher_repair_count and not worker_ready_count):
        decision.update({
            "should_run": True,
            "run_class": "dispatcher_plan",
            "role": "auto_dispatcher",
            "reason": "dispatcher_event_or_repair_state_pending",
            "trigger_event_ids": [event_id(event) for event in dispatcher],
        })
    elif rebuild:
        decision.update({
            "should_run": True,
            "run_class": "rebuild_route",
            "role": "auto_dispatcher",
            "reason": "rebuild_route_event_pending",
            "trigger_event_ids": [event_id(event) for event in rebuild],
        })
    elif (
        integrator_review_count
        and worker_ready_count
        and not worker_lock_count
        and open_pr_stack < HARD_OPEN_PR_LIMIT
        and not queue_needs_integrator_review(queue, activity)
    ):
        decision.update({
            "should_run": True,
            "run_class": "worker_run",
            "role": "auto_workers",
            "reason": "worker_ready_after_recent_integrator_review",
            "trigger_event_ids": [event_id(event) for event in worker],
        })
    elif integrator_review_count:
        decision.update({
            "should_run": True,
            "run_class": "integrator_review",
            "role": "auto_integrator",
            "reason": "integrator_review_required",
            "trigger_event_ids": [],
        })
    elif open_pr_stack > SOFT_OPEN_PR_LIMIT:
        decision.update({
            "should_run": True,
            "run_class": "integration_run",
            "role": "auto_integrator",
            "reason": "open_pr_stack_above_soft_limit",
            "trigger_event_ids": [],
        })
    elif needs_architect_count and not lock_count:
        decision.update({
            "should_run": True,
            "run_class": "architect_plan",
            "role": "auto_architect",
            "reason": "needs_architect_queue_ready",
            "trigger_event_ids": [],
        })
    elif blocked:
        decision.update({
            "should_run": True,
            "run_class": "dispatcher_plan",
            "role": "auto_dispatcher",
            "reason": "blocked_event_needs_routing",
            "trigger_event_ids": [event_id(event) for event in blocked],
        })
    elif (
        stalled_dispatcher_review_count
        and (not lock_count or integration_exhausted)
        and queue_needs_dispatcher_review(queue, activity)
    ):
        decision.update({
            "should_run": True,
            "run_class": "dispatcher_plan",
            "role": "auto_dispatcher",
            "reason": "stalled_queue_needs_dispatcher_review",
            "trigger_event_ids": [],
        })
    elif integration_exhausted and exhausted_integration_ready_count and queue_needs_dispatcher_review(queue, activity):
        decision.update({
            "should_run": True,
            "run_class": "dispatcher_plan",
            "role": "auto_dispatcher",
            "reason": "exhausted_integration_ready_needs_dispatcher_review",
            "trigger_event_ids": [],
        })
    elif worker_ready_count and not worker_host_ready:
        decision["reason"] = "worker_host_unavailable"
    elif worker_ready_count and clean_rebuild_worker_ready_count:
        decision.update({
            "should_run": True,
            "run_class": "worker_run",
            "role": "auto_workers",
            "reason": "clean_rebuild_worker_ready_after_pre_integrator" if not worker_lock_count else "worker_capacity_replenishment_candidate",
            "trigger_event_ids": [event_id(event) for event in worker],
        })
    elif worker_ready_count and (worker or worker_ready_count) and open_pr_stack < HARD_OPEN_PR_LIMIT:
        decision.update({
            "should_run": True,
            "run_class": "worker_run",
            "role": "auto_workers",
            "reason": "worker_ready_available" if not worker_lock_count else "worker_capacity_replenishment_candidate",
            "trigger_event_ids": [event_id(event) for event in worker],
        })

    if isinstance(activity, dict):
        decision["last_activity_updated_at"] = activity.get("updated_at")

    return decision


def consume_events(events_path: Path, event_ids: list[str], role: str) -> None:
    if not event_ids:
        return
    events = read_events(events_path)
    now = utc_now()
    wanted = set(event_ids)
    for event in events:
        if event_id(event) in wanted and not event.get("consumed_by"):
            event["consumed_by"] = role
            event["consumed_at"] = now
    write_events(events_path, events)


def update_activity(activity_path: Path, decision: dict[str, Any]) -> None:
    activity = load_json(activity_path)
    if not isinstance(activity, dict):
        activity = {"schema_version": 1, "role_activity": {}, "pending_signals": []}
    activity["updated_at"] = utc_now()
    role = str(decision.get("role") or "scheduler")
    role_activity = activity.setdefault("role_activity", {})
    if isinstance(role_activity, dict):
        item = role_activity.setdefault(role, {})
        if isinstance(item, dict):
            item["last_checked_at"] = decision["checked_at"]
            item["last_run_decision"] = decision.get("should_run")
            item["last_skip_reason"] = None if decision.get("should_run") else decision.get("reason")
            item["last_trigger_event_ids"] = decision.get("trigger_event_ids", [])
    write_json(activity_path, activity)


def main() -> int:
    parser = argparse.ArgumentParser(description="Pick the next event-driven automation run.")
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--events")
    parser.add_argument("--queue")
    parser.add_argument("--history")
    parser.add_argument("--locks")
    parser.add_argument("--activity")
    parser.add_argument("--open-pr-stack", type=int, default=0)
    parser.add_argument("--runtime-root", default=str(Path.home() / "agent-runtime"), help="Root containing codex-limits snapshots for model-limit auto recovery.")
    parser.add_argument("--auto-model-limit-retries", action="store_true", help="Enable automatic model-limit retry recovery in scheduler decision path.")
    parser.add_argument("--model-limit-retry-batch-size", type=int, default=MODEL_LIMIT_RETRY_AUTO_BATCH_SIZE)
    parser.add_argument("--model-limit-retry-max-attempts", type=int, default=MODEL_LIMIT_RETRY_AUTO_MAX_ATTEMPTS)
    parser.add_argument("--model-limit-retry-cooldown-seconds", type=int, default=MODEL_LIMIT_RETRY_AUTO_COOLDOWN_SECONDS)
    parser.add_argument("--model-limit-retry-min-remaining-percent", type=int, default=MODEL_LIMIT_RETRY_AUTO_MIN_REMAINING_PERCENT)
    parser.add_argument("--model-limit-retry-max-age-minutes", type=int, default=MODEL_LIMIT_RETRY_AUTO_MAX_EVIDENCE_AGE_MINUTES)
    parser.add_argument("--apply", action="store_true", help="Persist scheduler-authored model-limit retry updates to task queue.")
    parser.add_argument("--codex-bin", default=os.environ.get("CODEX_BIN", "codex"), help="Codex executable required for worker_run decisions.")
    parser.add_argument("--consume", action="store_true", help="Mark trigger events as consumed by the chosen role.")
    parser.add_argument("--consume-ignored", action="store_true", help="Mark stale ignored unconsumed events as consumed by scheduler_ignored.")
    parser.add_argument("--update-activity", action="store_true", help="Record scheduler decision in agent_activity_state.json.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    project_root = Path(args.project_root).resolve()
    events_path = Path(args.events).resolve() if args.events else task_file(project_root, "agent_events.jsonl")
    activity_path = Path(args.activity).resolve() if args.activity else task_file(project_root, "agent_activity_state.json")
    decision = decide(project_root, args)

    if args.consume and decision.get("should_run") and decision.get("role"):
        consume_events(events_path, [str(item) for item in decision.get("trigger_event_ids", [])], str(decision["role"]))
    if args.consume_ignored:
        consume_events(events_path, [str(item) for item in decision.get("ignored_event_ids", [])], "scheduler_ignored")
    if args.update_activity:
        update_activity(activity_path, decision)

    if args.json:
        print(json.dumps(decision, ensure_ascii=False, indent=2))
    else:
        print(f"should_run: {decision['should_run']}")
        print(f"run_class: {decision['run_class']}")
        print(f"role: {decision.get('role') or '-'}")
        print(f"reason: {decision['reason']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
