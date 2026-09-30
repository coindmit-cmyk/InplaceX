# Pre-Integrator Sanitary Layer

Date: 2026-06-12
Status: reusable Phase 2.1 contract

## Purpose

`Auto Integrator` must not receive hundreds of raw worker branches as its first
input. Before any LLM integration run, deterministic scripts classify the stack,
separate coordination noise from product changes and build a small safe batch.

## Pipeline

```text
workers
-> sync_worker_results.py
-> worker_integrator_bridge.py
-> integrator_preflight.py
-> pr_readiness_classifier.py
-> integration_batch_builder.py
-> Auto Integrator
-> Auto Finalizer
```

Use the wrapper for normal automation:

```bash
python scripts/agent_control/pre_integrator_repair.py \
  --project-root /path/to/project \
  --base-ref origin/develop \
  --fetch \
  --emit-events \
  --json
```

## Artifacts

The sanitary layer writes:

```text
docs/plans/integrator_preflight.json
docs/plans/pr_readiness_report.json
docs/plans/integration_batch.json
docs/plans/integration_candidates.batch.json
docs/plans/pre_integrator_repair.json
docs/plans/reports/PR_READINESS_<date>.md
docs/plans/reports/INTEGRATION_BATCH_<date>.md
docs/plans/reports/PRE_INTEGRATOR_REPAIR_<date>.md
```

`integration_batch.json` is the active input for `Auto Integrator`.
`integrator_preflight.json` remains audit evidence, not the active work pile.

## Classifier Routes

`pr_readiness_classifier.py` routes every candidate to one of:

```text
ready_candidate
coordination_only
needs_rebase
needs_checks
draft_only
duplicate
cleanup_candidate
needs_dispatcher
needs_worker_fix
needs_architect
needs_human
blocked
```

Draft worker PRs are not final merge targets. They are valid source artifacts
for Auto Integrator when they have:

- task id traceability;
- worker report, checks or equivalent evidence;
- changed paths;
- understandable base/head SHA evidence;
- no forbidden paths;
- no high-risk scope without review.

The classifier should keep such PRs as `ready_candidate` with
`source_artifact = true` and `merge_target_allowed = false`. Auto Integrator
then consumes them into a package branch/PR. Auto Finalizer validates and merges
the package, not the worker draft PR. After successful package return, the
worker draft PR may be marked `consumed`, `superseded` or `cleanup_candidate`
with evidence.

The classifier does not merge, delete branches, close PRs, release locks or mark
tasks done.

## Batch Rules

`integration_batch_builder.py` includes only `ready_candidate` items by default.
It excludes high-risk items unless explicitly allowed, prevents task/path
duplicates and keeps the batch small.

Typical limits:

```text
low risk docs/tests/scripts: up to 10
normal code: up to 5
high risk: 0 by default, 1 with explicit flag
```

Every excluded candidate keeps a reason and route so humans, Dispatcher, Worker
or Integrator can pick it up later.

## Integrator Rule

When `docs/plans/integration_batch.json` exists, `Auto Integrator` starts from
that batch. It must not sort the raw preflight pile manually unless the batch is
empty and the report explains why.
