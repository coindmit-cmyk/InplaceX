# Branch, Commit and Integration Protocol

Date: 2026-06-09
Status: reusable Phase 1 contract

## Purpose

This document defines how commits from different GPT chats, task makers, workers and module-focused chats are assembled into one working project structure.

The project must not depend on chat memory to reconstruct work. The final working structure is the Git tree on the accepted integration branch after PR review, Finalizer gate evidence or owner approval where required.

## GitHub Sync Invariant

GitHub is the center of truth for synchronization across local notebooks, remote worker machines, automation agents and manual GPT/Codex work.

Any meaningful repository change is incomplete until it is pushed to GitHub and referenced from the task, report, PR or status record. A local commit without push is only a local checkpoint; it is not visible coordination state.

Before an agent uses local files for planning, dispatch, implementation, integration or review, it must refresh and compare the checkout with the GitHub base ref:

```text
python scripts/agent_control/github_freshness_guard.py --project-root <project> --base-ref origin/develop --fetch --json
```

If GitHub has base commits that the local checkout does not contain, the run is `sync_blocked`. The agent must update the checkout or create a fresh worktree before reading local files as authoritative context. Dirty local changes must be preserved before any merge/rebase.

Every agent that edits a repository must:

1. create or use the correct role branch;
2. commit validated changes;
3. push the branch to GitHub;
4. open or update a PR/report/status record when the workflow requires it;
5. record the pushed branch and commit SHA in the task or run report.

If push fails, the agent must report the work as synchronization-blocked and include the local branch, commit SHA and exact push error. It must not mark the task complete.

## Integration Rule

Default integration branch is project-specific. A project should define it in `.agent/project.md`, `.agent/routing.md` or the task packet.

Common defaults:

```text
develop
main
```

Stable branches are project-specific, but commonly include:

```text
production
release/*
main
```

Rules:

- no role pushes directly to integration or stable branches;
- every meaningful change goes through a role branch and PR;
- the integration branch is the assembled working structure for active development;
- release branches are prepared from the integration branch only after checks and owner acceptance;
- production receives only owner-approved release results.

For the AiStudio Agent Core repository, the integration branch is `develop`.
All development work is closed into `develop` first. If the same work is needed
in the stable agent distribution, promote the accepted `develop` result to
`release/main` in a separate release step. Direct `release/main` work is limited
to emergency repair, and the repair must be mirrored back to `develop` before
the release is considered closed.

## Role Lanes

| Lane | Main job | Typical branch | Default write scope |
| --- | --- | --- | --- |
| `gpt-director` | Owner-facing product direction, base decisions, priorities and acceptance framing. | `docs/director/<YYYYMMDD>-short-name` | Issues, planning docs, decisions, task pages, changelog. |
| `gpt-architect` | Module architecture, contracts, task decomposition and acceptance criteria. | `docs/architect/<TASK-ID>-short-name` | Architecture docs, `.agent` project docs, plans, task pages, queues. |
| `gpt-dispatcher` | Build worker-ready task packets from docs and queue state on the GPT side. | `AiStudio/Agent/dispatcher/<TASK-ID>-short-name` | Plans, task pages, queue state. |
| `codex-dispatcher` | Build worker-ready task packets from local repository context. | `AiStudio/Agent/dispatcher/<TASK-ID>-short-name` | Plans, task pages, queue state. |
| `auto-make-tasks` | Convert accepted docs/architecture into worker-ready task packets by complexity. | `AiStudio/Agent/dispatcher/auto-make/<TASK-ID>-short-name` | Plans, sorter backlog, task pages, queue state. |
| `auto-worker-5.3-mini` | Execute `S` tasks only; model `5.3 mini`, medium reasoning effort. | `AiStudio/Agent/worker/<machine-id>/auto-worker-5-3-mini/<TASK-ID>-short-name` | Task allowed paths only. |
| `auto-worker-5.3` | Execute `M` tasks, then `S`; model `5.3`, very high reasoning effort. | `AiStudio/Agent/worker/<machine-id>/auto-worker-5-3/<TASK-ID>-short-name` | Task allowed paths only. |
| `auto-worker-5.5` | Execute `L` tasks, then `M`; model `5.5`, medium reasoning effort. | `AiStudio/Agent/worker/<machine-id>/auto-worker-5-5/<TASK-ID>-short-name` | Task allowed paths only. |
| `auto-worker-5.5max` | Execute worker-ready `XL`, then critically important `L`; model `5.5`, very high reasoning effort. | `AiStudio/Agent/worker/<machine-id>/auto-worker-5-5max/<TASK-ID>-short-name` | Task allowed paths only. |
| `auto-integrator` | Assemble ready branches/PRs into a safe order, create an integrator branch/report, and turn conflicts into a rebuild/split/merge route. | `AiStudio/Agent/integrator/<BATCH-ID>-short-name` | Integration reports, structured handoffs, PR ordering docs, non-final task/queue integration fields. |
| `auto-finalizer` | Return verified safe Integrator packages to `develop` when gates pass; route blocked/risky/ambiguous work to `needs_human`; sync statuses, locks, docs and final reports. | `AiStudio/Agent/finalizer/<BATCH-ID>-short-name` | Task status, locks, changelog/release notes, final reports. |
| `make-human` | Owner-directed human-mode task execution that carries a task through decisions, implementation, tests, docs, integration and merge/migration into `develop`. | `AiStudio/CodexDesktop/make-human/<TASK-ID>-short-name` | Explicit task scope plus task state, docs, changelog, tests and integration metadata required to close the task. |
| `module-companion` | Owner-led module work that records manual task evidence, blocks duplicate worker pickup and routes stale partial work to Dispatcher/Architect. | `AiStudio/CodexDesktop/module-companion/<TASK-ID>-short-name` or `codex/module-<MODULE-ID>/<TASK-ID>-short-name` | Explicit task/module scope plus task evidence and manual-work routing. |
| `reviewer` | Review specs, diffs, risks and missing tests. | `ai/review/<PR-ID>-short-name` | Read-only by default. |

If a GPT chat creates repository changes directly through GitHub, it still uses a role branch and PR. GPT Director and GPT Architect are docs/architecture lanes by default, not application-code lanes.

## One Task, One Branch, One PR

Default unit of work:

```text
one task ID -> one role branch -> one PR -> integration branch
```

Allowed variations:

- one PR may contain several commits when they all belong to the same task;
- a docs-only architecture bundle may include several tightly related planning records when the PR body lists them;
- a worker may process multiple tasks sequentially in one session, but each task still gets its own traceable branch/PR unless the task packet explicitly permits a bundle.

Do not mix unrelated product work, architecture work and worker implementation in one branch.

## Commit Messages

Use concise conventional commits and include trace fields in the body:

```text
docs(agent): define branch integration protocol

Task: AUTO-009
Role: gpt-architect
Base: develop
Branch: docs/architect/AUTO-009-commit-integration
Checks:
- git diff --check
```

Every task-related commit should identify the task ID and role. If a commit is pure cleanup, explain why it is safe and which task or PR it supports.

## Assembly Lifecycle

1. Refresh GitHub Issues, PRs, recent commits, queue, locks and relevant docs.
2. Confirm or create a task ID.
3. Create a role branch from the fresh integration branch.
4. For executable worker tasks, set `in_progress` status and lock metadata before edits.
5. Make changes inside allowed paths only.
6. Update tests, docs, task page and changelog when required.
7. Run relevant checks.
8. Commit with task and role metadata.
9. Push the branch to GitHub and open a draft PR to the integration branch.
10. Set task status to `review` and record branch, commits and PR link.
11. Auto Integrator checks merge order, conflicts, stale branches and missing checks when multiple branches/PRs need assembly, then records a concrete integration route on its own integrator branch/PR.
12. Reviewer/owner reviews the PR when the task or project policy requires it.
13. Auto Finalizer may merge a verified safe Integrator package into `develop` when the finalizer merge gate passes; risky, ambiguous or owner-only items go to `needs_human`.
14. If accepted work changed product/runtime code, Auto Finalizer or the responsible human-mode agent updates the project code version before marking the closure complete.
15. Auto Finalizer records accepted status, releases/flags locks and synchronizes final docs/reports.
16. Each role performs end-of-run cleanup: archive transient artifacts, remove safe local worktrees/scratch files, record cleanup candidates and report anything intentionally left behind.
17. After accepted commits are present on the integration branch, temporary role
branches become cleanup candidates unless an open PR, task link or owner note
keeps them active.
18. Mark task `owner_approved` or `done` only after finalizer-gate, merge or owner-approval evidence supports that exact status. Repository Hygiene independently verifies source-tip ancestry, patch/capability equivalence or an explicit no-product-payload disposition; missing proof creates an integration recovery task instead of cleanup.
19. Release work later flows from the integration branch to release and production branches according to project rules.

## Code Version Step

When product/runtime code enters `develop`, versioning uses four numeric points:

```text
r.m.t.f
```

- `r` means MVP/global-release line. Increment only for major global project milestones and reset `m.t.f` to `0.0.0`.
- `m` means completed large module, phase or task package. Treat `0..9` as the normal package scale and reset `t.f` to `0.0`.
- `t` means completed code task count. Increment by `1` for every completed code task merged into `develop`; it may grow beyond `9`. Reset `f` to `0`.
- `f` means code fix count. Increment for fixes that do not complete a new task; it may grow beyond `9`.

The version update belongs to the same closure package as the code migration into
`develop`. Workers, Integrator, Finalizer and human-mode agents must not mark
code work fully done if the required version bump is missing. Documentation-only
work follows a separate documentation version policy unless the project explicitly
couples documentation and product versions.

## Make Human Lifecycle

`Make human` is the exception for owner-directed human-mode work where one chat is expected to finish the task all the way into `develop`.

Required order:

1. inspect the task, current docs, queue, locks and relevant PRs;
2. read module and project documentation;
3. make and record necessary decisions;
4. implement the task;
5. write/update tests and run checks;
6. write/update documentation, task state and changelog;
7. open or update a PR into `develop`;
8. merge/migrate into `develop` after checks pass and no protected/secret/production blocker remains;
9. record merge evidence and final status.

Make human must still use branch and PR flow. It must not push directly to `develop`, bypass checks, touch stable branches, hide unresolved human decisions, or overwrite unrelated user/agent work.

## Parallel Work Rules

Parallel chats and workers are allowed only through coordination state:

- task lock prevents two workers from taking the same task;
- open PR changed paths prevent another agent from editing the same paths without explicit coordination;
- a worker must refresh GitHub/docs/queue/locks before taking the next task;
- a branch should be updated from the integration branch before final checks if the integration branch changed during the work;
- a worker must not branch from another worker branch unless the task explicitly states the dependency;
- do not cherry-pick or manually replay another role's commit without recording the reason in the PR/task report.

Use normal PR merges for integration. Do not design manual multi-parent commit flows for routine work.

Integrator and Finalizer governance is defined in `INTEGRATION_FINALIZATION_PROTOCOL.md`.

End-of-run cleanup rules are defined in `AGENT_CLEANUP_CONTRACT.md`.

## Auto Integrator Branch Rule

When an Auto Integrator run discovers a messy PR stack, it should create its own branch from the fresh integration base and commit the ordering/handoff there.

The integrator branch is not a replacement for worker PRs. It is a governance artifact that says:

- which PRs can merge as-is;
- which PRs must be rebased;
- which PRs must be split or rebuilt;
- which PRs appear superseded;
- which paths must be excluded from rebuilt PRs;
- which checks must rerun;
- what remains for Auto Finalizer after gate validation, merge evidence or owner-only decisions.

The integrator must not silently absorb broad worker changes into its own branch unless the owner explicitly authorizes an integration branch that carries code.

Detailed Auto Integrator behavior is defined in `AUTO_INTEGRATOR_OPERATING_CONTRACT.md`.

## Traceability Fields

Task records and PR reports should include:

```json
{
  "id": "TASK-001",
  "source_lane": "gpt-architect",
  "source_chat": "architect",
  "base_branch": "develop",
"github_branch": "docs/architect/TASK-001-example",
"machine_id": null,
"worker_id": null,
"github_pr": null,
  "commits": [],
  "changed_paths": [
    ".agent/**",
    "docs/automation/**"
  ],
  "checks": [
    "git diff --check"
  ],
  "integration_status": "branch-ready",
  "finalization_status": "pending-owner-approval"
}
```

## Final Structure Rule

The assembled project is not:

```text
Director chat + Architect chat + worker chat memories
```

The assembled project is:

```text
merged PRs on the integration branch -> release branch -> owner-approved production branch/tag
```

Anything important that is not in GitHub Issues, repository docs, task queue, task pages, commits or PRs is not durable project state.
