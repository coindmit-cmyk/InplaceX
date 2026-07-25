# Repository PR Decomposition

Status: active Dispatcher MVP.

## Purpose

Large repository PRs are not one Worker task. Before Direct Integrator receives
a clean non-draft PR, Dispatcher may split its product diff into disjoint
Worker Packet v2 children:

```text
repository PR parent
-> changed-path and feature-family analysis
-> deterministic validation gates
-> independent write slices
-> live-limit model routing
-> Worker results and child Integrator merges
-> parent reconciliation and source-PR closure
```

`repository_pr_decomposition_planner.py` is deterministic and read-only. It
does not edit Task Manager, select an actual model, issue authorization, claim
a task or launch a process. `dispatcher_integration_repair.py` is the only
write-capable owner of materialized children in this flow.

## Activation

The MVP considers decomposition when at least one condition is true:

- the parent explicitly sets `force_pr_decomposition: true`;
- the product diff has at least eight paths;
- at least four feature families span six or more paths;
- at least three surface categories span six or more paths;
- an L/XL parent has at least four product paths.

The planner keeps small or inseparable changes on the existing Direct
Integrator route. Drafts, dirty/high-risk owner-authorized repairs and parents
with an existing repair child keep their established fail-closed flows.
Sensitive paths route to Human; a plan exceeding the slice ceiling routes to
Architect.

## Slices

Paths are normalized, paired by stable feature family and packed into bounded
disjoint slices. Matching implementation, test, contract and documentation
files stay together where practical. A test-only slice depends on the code
slices, so it is not claimed until required implementation children are
terminal on `develop`.

Every materialized child:

- is a complete Worker Packet v2;
- has an exact, non-overlapping `allowed_paths` set;
- records source PR branch and head SHA;
- rebuilds compatible source intent on current `develop`;
- carries executable `script_actions` for deterministic checks;
- is integrated and finalized independently.

The existing repository-hygiene parent reconciliation already waits for every
linked child, verifies terminal integration evidence and merge SHAs, then
returns the parent to Integrator for source-PR closure.

## Script And Model Routing

Deterministic work remains script-owned: JSON validation, Python compilation,
focused tests, `git diff --check`, project tests and capability-preservation
comparison. Code or documentation mutation remains a Worker action unless a
separate allow-listed script runner owns that mutation.

Packets carry capability and candidate hints; the central Router makes the
actual selection from fresh limits:

| Slice | Preferred route |
| --- | --- |
| Small docs/tests | efficient: Spark or GPT-5.6 Luna |
| Ordinary isolated implementation | balanced: GPT-5.6 Luna/Terra, Spark fallback |
| Cross-surface, automation, migration or security | deep: GPT-5.6 Terra/Sol |
| Aggregate coherent review | GPT-5.6 Sol, Terra fallback |

Profile ids such as `auto-worker-5.3` are execution envelopes, not proof of the
actual model. `model_routing_decision` and Worker evidence record actual use.

## Synthesis Boundary

The plan recommends GPT-5.6 Sol Ultra only for read-only synthesis over two or
more independent results. Under the current Execution Contract, Ultra has no
write or merge authority. The write-capable Integrator remains a separate
Sol Max/Extra High step with normal lease, preservation, CI and Finalizer
gates. A future aggregate-synthesis runtime may automate the read-only stage
without weakening this boundary.

## Commands

Read-only planner:

```bash
python scripts/agent_control/repository_pr_decomposition_planner.py \
  --task-file AiStudio/Task_manager/task_queue.json \
  --task-id REPO-PR-123
```

Dispatcher dry-run and apply remain unchanged:

```bash
python scripts/agent_control/dispatcher_integration_repair.py --project-root . --base-ref origin/develop --json
python scripts/agent_control/dispatcher_integration_repair.py --project-root . --base-ref origin/develop --apply --json
```

## Evidence

The parent stores `repository_pr_decomposition`, `split_into`,
`integration_repair_child_ids`, model/synthesis hints and the independent work
unit count. Dispatcher emits `repository_pr_decomposed`; each child emits the
normal `worker_ready_available` and integration-repair events.
