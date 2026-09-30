#!/usr/bin/env python3
"""Host-level execution lease manager for AiStudio automation."""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import json
import os
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


def now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def iso(value: dt.datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def load_json(path: Path, default: dict[str, Any]) -> dict[str, Any]:
    if not path.exists():
        return dict(default)
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON object expected: {path}")
    return data


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def default_policy() -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "global_max_workers": 10,
        "project_max_workers": 8,
        "model_max_workers": {
            "gpt-5.3-codex-spark": 6,
            "gpt-5.5": 1,
            "gpt-5.6-luna": 4,
            "gpt-5.6-terra": 3,
            "gpt-5.6-sol": 2,
        },
        "default_ttl_seconds": 7200,
    }


def lease_path(runtime_root: Path) -> Path:
    return runtime_root / "execution-leases" / "leases.json"


@contextmanager
def file_lock(path: Path, timeout_seconds: float = 5.0) -> Iterator[None]:
    lock = path.with_suffix(path.suffix + ".lock")
    lock.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout_seconds
    fd: int | None = None
    while fd is None:
        try:
            fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, str(os.getpid()).encode("ascii"))
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"lease state lock timeout: {lock}")
            time.sleep(0.05)
    try:
        yield
    finally:
        if fd is not None:
            os.close(fd)
        try:
            lock.unlink()
        except FileNotFoundError:
            pass


def active_leases(state: dict[str, Any], at: dt.datetime | None = None) -> list[dict[str, Any]]:
    current = at or now()
    leases = state.get("leases") if isinstance(state.get("leases"), list) else []
    result = []
    for lease in leases:
        if not isinstance(lease, dict):
            continue
        expires_at = str(lease.get("expires_at") or "")
        try:
            if parse_time(expires_at) <= current:
                continue
        except ValueError:
            continue
        result.append(lease)
    return result


def counts(leases: list[dict[str, Any]]) -> dict[str, Any]:
    by_project: dict[str, int] = {}
    by_model: dict[str, int] = {}
    for lease in leases:
        project_id = str(lease.get("project_id") or "unknown")
        model = str(lease.get("model") or "unknown")
        by_project[project_id] = by_project.get(project_id, 0) + 1
        by_model[model] = by_model.get(model, 0) + 1
    return {"total": len(leases), "by_project": by_project, "by_model": by_model}


def _parallel_work_consumptions(state: dict[str, Any]) -> list[dict[str, Any]]:
    values = state.get("parallel_work_consumptions")
    return [value for value in values if isinstance(value, dict)] if isinstance(values, list) else []


def _parallel_work_rejection(
    reason: str,
    *,
    leases: list[dict[str, Any]] | None = None,
    consumptions: list[dict[str, Any]] | None = None,
    idempotent: bool = False,
) -> dict[str, Any]:
    return {
        "acquired": False,
        "consumed": False,
        "idempotent": idempotent,
        "reason": reason,
        "counts": counts(leases or []),
        "parallel_work_consumption_count": len(consumptions or []),
    }


def _parallel_work_identity(
    invocation: dict[str, Any],
    aggregate_authorization: dict[str, Any],
) -> dict[str, Any]:
    authorization = invocation["authorization"]
    plan_ref = invocation["plan_ref"]
    return {
        "idempotency_key": invocation["idempotency_key"],
        "aggregate_authorization_id": aggregate_authorization["authorization_id"],
        "authorization_id": authorization["authorization_id"],
        "grant_digest": authorization["grant_digest"],
        "plan_id": plan_ref["plan_id"],
        "plan_content_digest": plan_ref["plan_content_digest"],
        "budget_digest": aggregate_authorization["budget_digest"],
        "work_unit_id": plan_ref["work_unit_id"],
        "input_bundle_digest": authorization["input_bundle_digest"],
        "invocation_id": invocation["invocation_id"],
        "attempt_id": invocation["attempt_id"],
        "attempt_number": invocation["attempt_number"],
        "retry_of_attempt_id": invocation.get("retry_of_attempt_id"),
        "lease_id": authorization["lease_id"],
        "lease_digest": authorization["lease_digest"],
    }


def _consumed_parallel_work_invocation(
    invocation: dict[str, Any],
    consumed_at: str,
) -> dict[str, Any]:
    result = copy.deepcopy(invocation)
    authorization = result["authorization"]
    authorization["consumed"] = True
    authorization["lease_consumed"] = True
    authorization["consumed_at"] = consumed_at
    authorization["lease_consumed_at"] = consumed_at
    result["status"] = "queued"
    result["queued_at"] = consumed_at
    return result


def consume_parallel_work_launch(
    runtime_root: Path,
    policy: dict[str, Any],
    plan: dict[str, Any],
    invocation: dict[str, Any],
    project_id: str,
    worker_id: str,
    model: str,
    *,
    authorization_verified: bool = False,
    retry_evidence: dict[str, Any] | None = None,
    ttl_seconds: int | None = None,
    at: dt.datetime | None = None,
) -> dict[str, Any]:
    """Atomically consume one invocation grant, launch lease and plan budget.

    Consumption records live beside existing host execution leases.  The
    append-only records are the replay and aggregate-budget ledger; there is no
    second scheduler, lease file or independent budget store.

    The caller (the bounded launcher) must verify the Router HMAC and complete
    invocation contract first and attest that with ``authorization_verified``.
    This function rechecks exact bindings and owns only the atomic runtime
    transition.
    """

    if authorization_verified is not True:
        return _parallel_work_rejection("authorization_not_verified")
    if not isinstance(plan, dict) or not isinstance(invocation, dict):
        return _parallel_work_rejection("parallel_work_input_invalid")
    if not project_id or not worker_id or not model:
        return _parallel_work_rejection("execution_identity_invalid")
    if (
        plan.get("contract_kind") != "parallel_work"
        or plan.get("contract_version") != "1.0.0"
        or str(plan.get("status") or "") not in {"authorized", "running"}
    ):
        return _parallel_work_rejection("parallel_work_plan_not_authorized")
    if (
        invocation.get("contract_kind") != "subagent_invocation"
        or invocation.get("contract_version") != "1.0.0"
        or str(invocation.get("status") or "") not in {"authorized", "queued"}
    ):
        return _parallel_work_rejection("invocation_not_launchable")

    plan_ref = invocation.get("plan_ref")
    authorization = invocation.get("authorization")
    input_bundle = invocation.get("input_bundle")
    aggregate = plan.get("router_authorization")
    capacity = plan.get("capacity")
    if not all(
        isinstance(value, dict)
        for value in (plan_ref, authorization, input_bundle, aggregate, capacity)
    ):
        return _parallel_work_rejection("parallel_work_binding_missing")
    assert isinstance(plan_ref, dict)
    assert isinstance(authorization, dict)
    assert isinstance(input_bundle, dict)
    assert isinstance(aggregate, dict)
    assert isinstance(capacity, dict)

    identity_fields = (
        "invocation_id",
        "attempt_id",
        "idempotency_key",
    )
    if any(not str(invocation.get(field) or "").strip() for field in identity_fields):
        return _parallel_work_rejection("invocation_identity_invalid")
    attempt_number = invocation.get("attempt_number")
    if (
        not isinstance(attempt_number, int)
        or isinstance(attempt_number, bool)
        or attempt_number not in {1, 2}
    ):
        return _parallel_work_rejection("attempt_number_invalid")

    plan_id = str(plan.get("plan_id") or "")
    plan_digest = str(plan.get("plan_content_digest") or "")
    work_unit_id = str(plan_ref.get("work_unit_id") or "")
    units = {
        str(unit.get("work_unit_id") or ""): unit
        for unit in plan.get("work_units", [])
        if isinstance(unit, dict)
    }
    unit = units.get(work_unit_id)
    binding_pairs = (
        (plan_ref.get("plan_id"), plan_id),
        (plan_ref.get("plan_content_digest"), plan_digest),
        (authorization.get("plan_id"), plan_id),
        (authorization.get("plan_content_digest"), plan_digest),
        (authorization.get("work_unit_id"), work_unit_id),
        (authorization.get("router_decision_id"), plan.get("router_decision_id")),
        (authorization.get("router_decision_digest"), plan.get("router_decision_digest")),
        (aggregate.get("plan_id"), plan_id),
        (aggregate.get("plan_content_digest"), plan_digest),
        (aggregate.get("router_decision_id"), plan.get("router_decision_id")),
        (aggregate.get("router_decision_digest"), plan.get("router_decision_digest")),
        (aggregate.get("budget_digest"), capacity.get("budget_digest")),
        (aggregate.get("max_total_invocations"), capacity.get("max_total_invocations")),
        (aggregate.get("max_attempts_per_unit"), capacity.get("max_attempts_per_unit")),
    )
    expected_skill_bindings = unit.get("skill_bindings", []) if isinstance(unit, dict) else []
    aggregate_skill_bindings = next(
        (
            row.get("skill_bindings")
            for row in aggregate.get("skill_bindings", [])
            if isinstance(row, dict) and row.get("work_unit_id") == work_unit_id
        ),
        expected_skill_bindings if "skill_bindings" not in aggregate else None,
    )
    if (
        unit is None
        or any(not expected or actual != expected for actual, expected in binding_pairs)
        or authorization.get("input_bundle_digest") != input_bundle.get("bundle_digest")
        or authorization.get("input_bundle_digest") != unit.get("input_bundle_digest")
        or ("skill_bindings" in unit and invocation.get("plan_ref", {}).get("skill_bindings") != expected_skill_bindings)
        or ("skill_bindings" in unit and authorization.get("skill_bindings") != expected_skill_bindings)
        or aggregate_skill_bindings != expected_skill_bindings
    ):
        return _parallel_work_rejection("parallel_work_binding_mismatch")

    selected_route = invocation.get("selected_route")
    if not isinstance(selected_route, dict):
        return _parallel_work_rejection("selected_route_missing")
    selected_model = str(selected_route.get("model_id") or "")
    if not selected_model or selected_model != model:
        return _parallel_work_rejection("selected_route_model_mismatch")
    selected_route_digest = str(selected_route.get("route_digest") or "")
    if (
        not selected_route_digest
        or authorization.get("selected_route_digest") != selected_route_digest
        or aggregate.get("selected_route_digest") != selected_route_digest
    ):
        return _parallel_work_rejection("selected_route_binding_mismatch")
    if (
        authorization.get("issuer") != "model_resource_router"
        or authorization.get("status") != "granted"
        or authorization.get("single_use") is not True
        or authorization.get("lease_single_use") is not True
        or authorization.get("consumed") is not False
        or authorization.get("lease_consumed") is not False
        or aggregate.get("issuer") != "model_resource_router"
        or aggregate.get("status") != "granted"
        or aggregate.get("invocation_grants_single_use") is not True
    ):
        return _parallel_work_rejection("authorization_not_consumable")
    for field in (
        "authorization_id",
        "grant_digest",
        "lease_id",
        "lease_digest",
    ):
        if not str(authorization.get(field) or "").strip():
            return _parallel_work_rejection("authorization_binding_invalid")
    if not str(aggregate.get("authorization_id") or "").strip():
        return _parallel_work_rejection("aggregate_authorization_invalid")

    max_attempts = capacity.get("max_attempts_per_unit")
    max_total = capacity.get("max_total_invocations")
    effective_lanes = capacity.get("effective_lanes")
    if any(
        not isinstance(value, int) or isinstance(value, bool) or value < 1
        for value in (max_attempts, max_total, effective_lanes)
    ):
        return _parallel_work_rejection("parallel_work_capacity_invalid")
    if (
        authorization.get("max_attempts") != max_attempts
        or attempt_number > int(max_attempts)
    ):
        return _parallel_work_rejection("attempt_exceeds_authorization")

    if attempt_number == 1:
        if (
            invocation.get("retry_of_attempt_id") is not None
            or invocation.get("retry_invariants") is not None
        ):
            return _parallel_work_rejection("unexpected_retry_metadata")
    else:
        retry = invocation.get("retry_invariants")
        evidence = retry_evidence if isinstance(retry_evidence, dict) else {}
        invariant_fields = (
            "route_unchanged",
            "scope_unchanged",
            "tools_unchanged",
            "input_unchanged",
            "permissions_unchanged",
            "budget_unchanged",
            "timeout_unchanged",
        )
        if (
            not isinstance(retry, dict)
            or any(retry.get(field) is not True for field in invariant_fields)
            or retry.get("scope_digest") != authorization.get("bound_scope_digest")
        ):
            return _parallel_work_rejection("retry_expansion_requires_new_router_decision")
        if (
            evidence.get("recorded") is not True
            or evidence.get("retryable") is not True
            or evidence.get("deterministic") is True
            or evidence.get("prior_attempt_id") != invocation.get("retry_of_attempt_id")
        ):
            return _parallel_work_rejection("retry_evidence_invalid")

    current = at or now()
    if current.tzinfo is None:
        current = current.replace(tzinfo=dt.timezone.utc)
    expiries: list[dt.datetime] = []
    for value in (
        aggregate.get("expires_at"),
        authorization.get("expires_at"),
        authorization.get("lease_expires_at"),
    ):
        if not isinstance(value, str):
            return _parallel_work_rejection("authorization_time_invalid")
        try:
            parsed = parse_time(value)
        except ValueError:
            return _parallel_work_rejection("authorization_time_invalid")
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=dt.timezone.utc)
        expiries.append(parsed)
    if any(expiry <= current for expiry in expiries):
        return _parallel_work_rejection("authorization_expired")
    requested_ttl = int(ttl_seconds or policy.get("default_ttl_seconds") or 7200)
    if requested_ttl < 1:
        return _parallel_work_rejection("lease_ttl_invalid")
    ttl = min(
        requested_ttl,
        *(max(0, int((expiry - current).total_seconds())) for expiry in expiries),
    )
    if ttl < 1:
        return _parallel_work_rejection("authorization_expired")

    path = lease_path(runtime_root)
    with file_lock(path):
        state = load_json(path, {"schema_version": "1.0", "leases": []})
        leases = active_leases(state, at=current)
        consumptions = _parallel_work_consumptions(state)
        identity = _parallel_work_identity(invocation, aggregate)

        same_key = [
            item
            for item in consumptions
            if item.get("idempotency_key") == identity["idempotency_key"]
        ]
        if same_key:
            existing = same_key[0]
            same_identity = all(
                existing.get(field) == value for field, value in identity.items()
            )
            reason = (
                "idempotency_key_consumed"
                if same_identity
                else "idempotency_key_conflict"
            )
            return _parallel_work_rejection(
                reason,
                leases=leases,
                consumptions=consumptions,
                idempotent=same_identity,
            )
        if any(
            item.get("authorization_id") == identity["authorization_id"]
            or item.get("lease_id") == identity["lease_id"]
            for item in consumptions
        ):
            return _parallel_work_rejection(
                "authorization_replayed",
                leases=leases,
                consumptions=consumptions,
            )
        if any(
            lease.get("lease_id") == identity["lease_id"] for lease in leases
        ):
            return _parallel_work_rejection(
                "lease_id_active",
                leases=leases,
                consumptions=consumptions,
            )

        plan_consumptions = [
            item
            for item in consumptions
            if item.get("aggregate_authorization_id")
            == identity["aggregate_authorization_id"]
        ]
        if len(plan_consumptions) >= int(max_total):
            return _parallel_work_rejection(
                "aggregate_invocation_budget_exhausted",
                leases=leases,
                consumptions=consumptions,
            )
        unit_consumptions = [
            item
            for item in plan_consumptions
            if item.get("work_unit_id") == work_unit_id
        ]
        if len(unit_consumptions) >= int(max_attempts):
            return _parallel_work_rejection(
                "work_unit_attempt_budget_exhausted",
                leases=leases,
                consumptions=consumptions,
            )
        if any(
            item.get("attempt_number") == attempt_number
            for item in unit_consumptions
        ):
            return _parallel_work_rejection(
                "work_unit_attempt_already_consumed",
                leases=leases,
                consumptions=consumptions,
            )
        if attempt_number == 2:
            prior = next(
                (
                    item
                    for item in unit_consumptions
                    if item.get("attempt_number") == 1
                ),
                None,
            )
            if (
                prior is None
                or prior.get("attempt_id") != invocation.get("retry_of_attempt_id")
            ):
                return _parallel_work_rejection(
                    "retry_prior_attempt_missing",
                    leases=leases,
                    consumptions=consumptions,
                )

        current_counts = counts(leases)
        global_max = int(policy.get("global_max_workers") or 0)
        project_max = int(policy.get("project_max_workers") or 0)
        model_caps = (
            policy.get("model_max_workers")
            if isinstance(policy.get("model_max_workers"), dict)
            else {}
        )
        model_max = int(model_caps.get(model) or 0)
        if global_max and current_counts["total"] >= global_max:
            reason = "global_worker_limit"
        elif project_max and current_counts["by_project"].get(project_id, 0) >= project_max:
            reason = "project_worker_limit"
        elif model_max and current_counts["by_model"].get(model, 0) >= model_max:
            reason = "model_worker_limit"
        elif (
            sum(1 for lease in leases if lease.get("plan_id") == plan_id)
            >= int(effective_lanes)
        ):
            reason = "parallel_work_lane_limit"
        else:
            reason = ""
        if reason:
            return _parallel_work_rejection(
                reason,
                leases=leases,
                consumptions=consumptions,
            )

        consumed_at = iso(current)
        lease = {
            "lease_id": identity["lease_id"],
            "project_id": project_id,
            "worker_id": worker_id,
            "model": model,
            "pid": os.getpid(),
            "created_at": consumed_at,
            "heartbeat_at": consumed_at,
            "expires_at": iso(current + dt.timedelta(seconds=ttl)),
            "lease_kind": "parallel_work_invocation",
            "plan_id": plan_id,
            "plan_content_digest": plan_digest,
            "work_unit_id": work_unit_id,
            "invocation_id": identity["invocation_id"],
            "attempt_id": identity["attempt_id"],
            "authorization_id": identity["authorization_id"],
            "aggregate_authorization_id": identity["aggregate_authorization_id"],
            "idempotency_key": identity["idempotency_key"],
        }
        consumption = {
            **identity,
            "consumed_at": consumed_at,
            "project_id": project_id,
            "worker_id": worker_id,
            "model": model,
            "authorization_verified": True,
            "retry_evidence_recorded": attempt_number == 2,
        }
        leases.append(lease)
        consumptions.append(consumption)
        state.update(
            {
                "schema_version": "1.0",
                "updated_at": consumed_at,
                "leases": leases,
                "parallel_work_consumptions": consumptions,
            }
        )
        write_json(path, state)
        return {
            "acquired": True,
            "consumed": True,
            "idempotent": False,
            "lease": lease,
            "invocation": _consumed_parallel_work_invocation(
                invocation, consumed_at
            ),
            "counts": counts(leases),
            "parallel_work_consumption_count": len(consumptions),
            "plan_budget": {
                "max_total_invocations": int(max_total),
                "consumed_invocations": len(plan_consumptions) + 1,
                "remaining_invocations": int(max_total) - len(plan_consumptions) - 1,
                "max_attempts_per_unit": int(max_attempts),
                "work_unit_attempts_consumed": len(unit_consumptions) + 1,
            },
        }


acquire_parallel_work_lease = consume_parallel_work_launch


def acquire(runtime_root: Path, policy: dict[str, Any], project_id: str, worker_id: str, model: str, ttl_seconds: int | None = None, lease_id: str | None = None) -> dict[str, Any]:
    path = lease_path(runtime_root)
    with file_lock(path):
        state = load_json(path, {"schema_version": "1.0", "leases": []})
        leases = active_leases(state)
        current_counts = counts(leases)
        global_max = int(policy.get("global_max_workers") or 0)
        project_max = int(policy.get("project_max_workers") or 0)
        model_caps = policy.get("model_max_workers") if isinstance(policy.get("model_max_workers"), dict) else {}
        model_max = int(model_caps.get(model) or 0)
        if global_max and current_counts["total"] >= global_max:
            reason = "global_worker_limit"
        elif project_max and current_counts["by_project"].get(project_id, 0) >= project_max:
            reason = "project_worker_limit"
        elif model_max and current_counts["by_model"].get(model, 0) >= model_max:
            reason = "model_worker_limit"
        else:
            reason = ""
        if reason:
            state["leases"] = leases
            write_json(path, state)
            return {"acquired": False, "reason": reason, "counts": current_counts}
        ttl = int(ttl_seconds or policy.get("default_ttl_seconds") or 7200)
        created = now()
        lease = {"lease_id": lease_id or f"lease-{uuid.uuid4().hex[:12]}", "project_id": project_id, "worker_id": worker_id, "model": model, "pid": os.getpid(), "created_at": iso(created), "heartbeat_at": iso(created), "expires_at": iso(created + dt.timedelta(seconds=ttl))}
        leases.append(lease)
        state.update({"schema_version": "1.0", "updated_at": iso(created), "leases": leases})
        write_json(path, state)
        return {"acquired": True, "lease": lease, "counts": counts(leases)}


def release(
    runtime_root: Path,
    lease_id: str,
    at: dt.datetime | None = None,
) -> dict[str, Any]:
    path = lease_path(runtime_root)
    with file_lock(path):
        current = at or now()
        state = load_json(path, {"schema_version": "1.0", "leases": []})
        leases = active_leases(state, at=current)
        kept = [lease for lease in leases if str(lease.get("lease_id")) != lease_id]
        state.update({"updated_at": iso(current), "leases": kept})
        write_json(path, state)
        return {"released": len(kept) != len(leases), "lease_id": lease_id, "counts": counts(kept)}


def heartbeat(runtime_root: Path, lease_id: str, ttl_seconds: int) -> dict[str, Any]:
    path = lease_path(runtime_root)
    with file_lock(path):
        state = load_json(path, {"schema_version": "1.0", "leases": []})
        leases = active_leases(state)
        current = now()
        updated = False
        for lease in leases:
            if str(lease.get("lease_id")) == lease_id:
                lease["heartbeat_at"] = iso(current)
                lease["expires_at"] = iso(current + dt.timedelta(seconds=ttl_seconds))
                updated = True
        state.update({"updated_at": iso(current), "leases": leases})
        write_json(path, state)
        return {"updated": updated, "lease_id": lease_id, "counts": counts(leases)}


def status(runtime_root: Path) -> dict[str, Any]:
    path = lease_path(runtime_root)
    with file_lock(path):
        state = load_json(path, {"schema_version": "1.0", "leases": []})
        leases = active_leases(state)
        state.update({"updated_at": iso(now()), "leases": leases})
        write_json(path, state)
        return {"schema_version": "1.0", "leases": leases, "counts": counts(leases)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["acquire", "release", "heartbeat", "status"])
    parser.add_argument("--runtime-root", default="~/agent-runtime")
    parser.add_argument("--policy", default="")
    parser.add_argument("--project-id", default="")
    parser.add_argument("--worker-id", default="")
    parser.add_argument("--model", default="unknown")
    parser.add_argument("--lease-id", default="")
    parser.add_argument("--ttl-seconds", type=int, default=0)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    runtime_root = Path(args.runtime_root).expanduser()
    policy = default_policy()
    if args.policy:
        policy.update(load_json(Path(args.policy).expanduser(), {}))

    if args.action == "status":
        result = status(runtime_root)
    elif args.action == "acquire":
        if not args.project_id or not args.worker_id:
            raise SystemExit("acquire requires --project-id and --worker-id")
        result = acquire(runtime_root, policy, args.project_id, args.worker_id, args.model, args.ttl_seconds or None, args.lease_id or None)
    elif args.action == "release":
        if not args.lease_id:
            raise SystemExit("release requires --lease-id")
        result = release(runtime_root, args.lease_id)
    else:
        if not args.lease_id:
            raise SystemExit("heartbeat requires --lease-id")
        ttl = args.ttl_seconds or int(policy.get("default_ttl_seconds") or 7200)
        result = heartbeat(runtime_root, args.lease_id, ttl)
    print(json.dumps(result, ensure_ascii=False, indent=2) if args.json else result)
    return 0 if result.get("acquired", True) is not False else 2


if __name__ == "__main__":
    raise SystemExit(main())
