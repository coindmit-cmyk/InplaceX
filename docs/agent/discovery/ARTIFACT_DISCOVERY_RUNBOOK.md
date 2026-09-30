# Artifact Discovery Runbook

## Purpose

Artifact Discovery is a read-only scanner plus a dry-run-first routing pipeline.
The production-safe path is:

```text
scan -> classify -> route -> report -> normalize
```

Queue-visible work must go through `artifact_discovery_normalizer.py` or
`artifact_discovery_cycle.py`. Do not import every raw router task candidate into
`AiStudio/Task_manager/task_queue.json`.

## Safe One-command Dry-run

Use this for normal inspection:

```bash
python scripts/agent_control/artifact_discovery_cycle.py \
  --project-root . \
  --output-dir docs/reports/discovery/manual-run \
  --worker-ready-first-safe \
  --json
```

Expected behavior:

- writes scan/classified/routed/Markdown/normalized artifacts under the output directory;
- does not mutate `AiStudio/Task_manager/task_queue.json`;
- excludes generated discovery reports from the next scan;
- emits many raw candidates but normalizes them into a small grouped row set;
- marks at most one ADL Worker Packet v2 as `worker_ready=true` per run.

## Manual Step-by-step Dry-run

Use this when debugging a specific stage:

```bash
python scripts/agent_control/artifact_discovery_scanner.py \
  --project-root . \
  --output artifact-discovery-scan.json \
  --json

python scripts/agent_control/artifact_discovery_classifier.py \
  --input artifact-discovery-scan.json \
  --output artifact-discovery-classified.json \
  --json

python scripts/agent_control/artifact_discovery_router.py \
  --project-root . \
  --input artifact-discovery-classified.json \
  --output artifact-discovery-routed.json \
  --json

python scripts/agent_control/artifact_discovery_report_builder.py \
  --input artifact-discovery-routed.json \
  --output artifact-discovery-report.md \
  --json

python scripts/agent_control/artifact_discovery_normalizer.py \
  --project-root . \
  --input artifact-discovery-routed.json \
  --output artifact-discovery-normalized.json \
  --worker-ready-first-safe \
  --json
```

## Apply Normalized Queue Visibility

Only apply normalized rows after reviewing the normalized JSON:

```bash
python scripts/agent_control/artifact_discovery_cycle.py \
  --project-root . \
  --output-dir AiStudio/Task_manager/reports/discovery/manual-apply \
  --worker-ready-first-safe \
  --apply-normalized \
  --json
```

The apply path:

- refuses to write anything when the normalized JSON release gate fails;
- appends grouped Dispatcher/Human follow-up rows only when they do not already exist;
- skips rows already present in active queue or task history;
- skips a new ADL `worker_ready` row when an active ADL worker-ready packet already exists;
- does not apply raw router task candidates one-to-one.

## Router Apply Gate

`artifact_discovery_router.py --apply` is not the normal path. It is reserved
for reviewed Dispatcher gate work where raw task candidates have already been
audited for duplicates, ownership, sensitive-risk handling and scope.

Prefer `--apply-normalized`.

## Validation

Run after any apply:

```bash
python -m json.tool AiStudio/Task_manager/task_queue.json
python scripts/agent_control/validate_task_queue_readiness.py --queue AiStudio/Task_manager/task_queue.json --json
python scripts/agent_control/dispatcher_decision_guard.py --queue AiStudio/Task_manager/task_queue.json --json
git diff --check
```

Expected healthy result:

- queue JSON valid;
- normalized report has `summary.release_ready=true` and `release_gate.errors=[]`;
- readiness validator has `errors=0`;
- dispatcher guard has `errors=0`;
- ADL `worker_ready_count` does not increase above the intended active packet.

## Recovery-cycle Integration

Queue recovery can include Artifact Discovery as an explicit step:

```bash
python scripts/agent_control/run_worker_cycle.py \
  --project-root . \
  --base-ref origin/develop \
  --worker-id auto-worker-5.3-mini \
  --queue-recovery-only \
  --artifact-discovery-recovery \
  --dry-run \
  --json
```

Use `--recovery-apply` only on the approved automation host when queue-visible
normalization is intended.
