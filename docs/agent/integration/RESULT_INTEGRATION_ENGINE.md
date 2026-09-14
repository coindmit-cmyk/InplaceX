# Result Integration Engine

`scripts/agent_control/result_integration_engine.py` is the deterministic,
authority-free synthesis step between closed analytical execution and
Finalizer verification.

It extends the existing Integrator. It is not a second scheduler, Router,
queue, conflict-voting service or Finalizer.

## Inputs

The engine accepts one JSON object with:

- `integration_id`, `created_at`, `revision`, `supersedes` and an Integrator
  `producer`;
- one validated Parallel Work `plan`;
- the exact `expected_invocations` list and all terminal
  `result_envelopes`;
- `accounting_closed: true`;
- the exact `requirement_ids` denominator;
- complete Integrator `conflicts` using the conflict shape from
  `schemas/agent-control/result_integration.schema.json`;
- explicit package-level `preservation_checks`;
- an optional concise `summary`, bounded `residual_risks`, audit warnings and
  advisory `next_run_request`.

The plan and Result Envelopes remain separate versioned contracts. Use:

- `schemas/agent-control/parallel_work.schema.json`;
- `schemas/agent-control/subagent_result_envelope.schema.json`;
- `schemas/agent-control/result_integration.schema.json`;
- the matching examples under `templates/agent-control/`.

The engine does not accept an open accounting snapshot. Missing results become
an exact `missing` or `not_run` disposition only after the caller closes the
barrier.

## Deterministic synthesis

The engine:

1. semantically validates every Result Envelope;
2. checks correlation, immutable base-snapshot and audit-journal lineage;
3. calls `result_lane_accounting.account_parallel_work_results`;
4. retains every expected, failed, cancelled, missing, stale, excluded and
   orphan result disposition;
5. requires a disposition for every result-declared conflict;
6. resolves claims only from cited, known evidence;
7. records exact requirement coverage, artifacts and preservation checks;
8. bounds synthesis confidence by the weakest accepted critical claim;
9. emits canonical synthesis, package-evidence and integration digests;
10. validates the completed Result Integration Contract before returning it.

Input ordering does not change the emitted contract.

## Conflict policy

Conflict records name parties, claims, evidence, classification, risk and an
exact selected/rejected position set.

Resolution precedence remains:

1. owner decision and hard policy;
2. verified current reality and mandatory checks;
3. reproducible evidence;
4. domain expertise;
5. majority or confidence only as a final tie-breaker for a low/medium
   subjective conflict.

Factual, policy/authority, security, irreversible, high-risk and critical
conflicts cannot be resolved by majority or confidence. An unresolved blocking
conflict prevents Finalizer handoff. A result-declared conflict cannot
disappear merely because the final prose omits it.

## Status mapping

- All required lanes, requirements and preservation checks pass, with no
  residual risk: `ready_for_finalizer`.
- Required work passes but an optional lane or non-blocking issue leaves an
  explicit risk: `accepted_with_residual_risk`.
- Required accounting, requirement evidence, a package check or a blocking
  conflict is incomplete: `integration_incomplete`.
- A targeted retry or specialist run is recommended for a blocker:
  `rerun_required`.
- Owner escalation is recommended for a blocker:
  `owner_decision_required`.

Only the two Finalizer-ready states receive a digest-bound
`finalizer_handoff`. Finalizer may verify the exact package but may not modify
the synthesis.

## Manifest adoption

An Integration Manifest that adopts this contract records the aggregate
evidence reference, status, `synthesis_digest` and `integration_digest`. For
the two Finalizer-ready states it also records the exact
`finalizer_handoff` reference; for all other states it records the explicit
rerun or escalation route. The manifest is evidence about the aggregate, not a
second mutable synthesis.

## Finalizer readiness gate

An integration handoff may include the canonical `result_integration` object
(the compatibility alias `result_integration_contract` is also readable).
When present, the Finalizer gate validates the versioned contract and requires:

- `ready_for_finalizer` or `accepted_with_residual_risk` status;
- exact recomputed `synthesis_digest` and `integration_digest` values;
- complete required accounting and checks;
- no blockers or unresolved blocking conflicts;
- `finalizer_may_modify: false` and `merge_authority_granted: false`.

The gate is verification-only. It does not synthesize missing fields, repair
conflicts, choose among claims or change the contract. Legacy handoffs without
the opt-in object continue through the existing legacy gate until their
producer adopts Result Integration.

## Additional work boundary

`next_run_request` is a recommendation only. The engine requires:

```json
{
  "authorization_granted": false,
  "execution_started": false
}
```

Any additional run returns to the existing recommendation and Model Router
lifecycle. The engine never selects a model, consumes capacity, starts a
process, changes `worker_ready`, grants approval, or mutates Task Manager.

## CLI

```bash
python scripts/agent_control/result_integration_engine.py \
  --input /path/to/integrator-synthesis-input.json \
  --output /path/to/result-integration.json \
  --project-root . \
  --json
```

The command writes `--output` only when synthesis and final contract
validation succeed. Otherwise it exits non-zero and prints stable error codes.
It performs no other repository, runtime or external mutation.
