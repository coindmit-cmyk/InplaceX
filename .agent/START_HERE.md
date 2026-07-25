# AI Agent Start Here

Universal router for GPT chats, Codex sessions, automation runners and external reviewers.

Codex Chat must read `.agent/CODEX_CHAT.md` after this entry point and before editing.

## Identity

- Project id: `inplacex`.
- Owner-controlled repository: `coindmit-cmyk/InplaceX`.
- Comparison-only upstream: `GoodEvil11/InplaceX`.
- Canonical integration branch: `develop`.
- Canonical unattended writer host: `remote_aistudio_pc`.
- Canonical remote checkout: `/home/main/agent-runtime/managed-checkouts/inplacex`.

## First Steps

1. Read `AGENTS.md`, `.agent/general.md`, `.agent/project.md`,
   `.agent/modules.md`, and `.agent/workflows.md`.
2. Fetch the owner-controlled remote and prove the checkout is clean and exact
   at `origin/develop`.
3. Read `.agent/agent_version.json`, this file, the project Registry entry,
   `AiStudio/Task_manager/task_queue.json`, locks, and owner directives.
4. Read the canonical architecture or game-mode documents required by the task.
5. If any required orientation source is missing, stale, contradictory, or
   unsafe to refresh, stop with `orientation_blocked`.

## Branch Model

`develop` is the only normal integration branch. Workers use separate clean
worktrees and task branches created from the exact current base. The
owner-controlled fork is the write remote; upstream is never force-pushed.
Release and production refs are created only by their explicit gates.

## Version Contract

`PROJECT_VERSION.json` is the future single project release identity and must
carry `component_versions` once introduced by the release/CI task. Until that
file exists, use `.agent/agent_version.json` for Agent Core identity and the
Android/backend build files for component versions. Never infer a shipped
version from a branch name, chat history, or an unsigned artifact.

## Navigation

- Android runtime: `InplaceX-android/app`.
- Shared game and bot domain: `InplaceX-bot-core`.
- Backend runtime: `InplaceX-backend`.
- Logging contract: `InplaceX-logging`.
- Canonical product and architecture docs: `InplaceX-docs`.
- Finalization handoff: `docs/00_dispatch/design-sessions/inplacex-finalization/v002`.

## Tasks And State

The project-local authority is `AiStudio/Task_manager/`. Queue rows are not
claimable merely because they are visible. Dispatcher must prove dependencies,
Worker Packet v2, allowed paths, checks, freshness, locks, and model routing.
The remote Registry is inventory and host authority; it does not replace the
project queue.

## Architecture And Specs

Architecture, public contracts, game modes, providers, bot behavior, and online
work must follow the matching canonical documents under
`InplaceX-docs/Game/GPT/`, `InplaceX-docs/Game/Human/`, and
`InplaceX-docs/Backend/`. `GameFieldScreen` is a route target, the ViewModel and
domain own state/rules, and `GameScreen` is the stateless presentation boundary.

## Local-Only Access

Machine paths, SSH aliases, device serials, tokens, signing material, provider
credentials, and VPS details stay in local-only configuration or an approved
secret store. The runtime Registry path is local-only. Never copy its access
material into Git, task packets, logs, screenshots, or reports.

## Work Protocol

Protect owner changes, use clean worktrees, stay inside `allowed_paths`, add
tests and sanitized logging at behavior boundaries, and return a worker report
with exact checks and `integration_requested`. Failed checks route back to
`needs_worker_fix`; they are not partially integrated.

## Approval Gates

Explicit approval or owner/integrator evidence is required for production/VPS
activation, DNS/TLS/firewall changes, signing, provider-console credentials,
physical-phone use, destructive migrations, cleanup, branch deletion, and
release publication. Current developer/debug tools remain available for owner
testing until their later release-isolation tasks.

## Start Rule

Do not work from chat memory alone. Refresh GitHub and repository documentation before planning, assigning, reviewing or implementing work.

Use this file only to identify the role and the files to read next. Role-specific rules live in `.agent/roles/`. Shared GitHub, branch, task, lock, secrets, release, evidence and cleanup rules live in `.agent/routing.md`, `.agent/permissions.md` and `docs/automation/`.

## Freshness Gate

Before using local project files as source of truth, run or require the GitHub freshness guard:

```text
python scripts/agent_control/github_freshness_guard.py --project-root . --base-ref origin/develop --fetch --json
```

If the checkout is behind GitHub or cannot be refreshed safely, stop normal work and report `sync_blocked`.

<!-- BEGIN AISTUDIO MANAGED: execution-recommendation -->
## Execution Recommendation Gate

Before the first substantive task action, every interactive GPT or Codex chat
must tell the owner which concrete model and reasoning effort it recommends.
Mandatory orientation reads may happen first when they are needed to classify
the task, but implementation, mutation, delegation and long-running checks must
wait until the recommendation is visible.

Use this compact format once per task, and repeat it only when scope or risk
changes materially:

```text
Execution recommendation: <model>, reasoning <effort>, complexity <S|M|L|XL> - <short reason>.
```

Resolve the recommendation through `.agent/model_routing_policy.json` and the
task rules in `.agent/routing.md`. This is a recommendation, not a claim that
the chat changed its own model. If the active model is materially weaker than
the recommendation for high-risk work, report the mismatch before continuing.

Automation uses the same classification. Every identified executable task
must carry `complexity = S|M|L|XL`; the central Router records the selected
model and canonical reasoning effort. A task without a valid complexity must
fail closed for Dispatcher repair instead of inheriting a role-only default.
<!-- END AISTUDIO MANAGED: execution-recommendation -->
## Develop-First Rule

For AiStudio Agent Core changes, `develop` is the only normal
working integration branch. `release/main` is a stable distribution branch:
release-bound changes must be accepted on `develop` first, then promoted to
`release/main` by a release gate. Old feature, worker, integrator and Codex
branches should be archived or deleted after their accepted changes are present
on `develop`.

- Development changes are committed, pushed and integrated into `develop`.
- Temporary role branches are cleanup candidates after their accepted commits
  are present on `develop`.
- Release changes also land in `develop` first, then move to `release/main`
  through an explicit release promotion and gate checks.
- Do not use `release/main` as a normal development or automation branch.

## Agent Version Gate

Before substantive work in an application project, read:

```text
.agent/agent_version.json
```

Stable source is:

```text
coindmit-cmyk/ai-project-agent:release/main
```

Before comparing, read the stable Agent Core version from GitHub `release/main`
or the central runner that has just fast-forwarded from that ref. Do not treat
project-local `.agent/agent_version.json`, `develop`, chat memory, or a stale
local checkout as the current Agent Core version.

Agent Update Manager materializes the exact remote release commit without
checking out or modifying the Agent Core `develop` worktree. Application
projects must not request `develop` as their update source.

```text
git fetch origin +refs/heads/release/main:refs/remotes/origin/release/main
git show origin/release/main:VERSION
```

If project-local metadata is behind stable Agent Core rules, report it as
`project_agent_copy_stale` and use the central runner/release source for
automation. Run Agent Update Manager for project adoption/update work, but do
not answer "current Agent Core version" from project-local metadata.

## Read Shared Rules

Read these shared files before the role file when they exist:

1. `AGENTS.md`
2. `.agent/project.md`
3. `.agent/agents.md`
4. `.agent/routing.md`
5. `.agent/permissions.md`
6. `docs/automation/BRANCH_COMMIT_INTEGRATION_PROTOCOL.md`
7. `docs/automation/LOCK_PROTOCOL.md`
8. `docs/automation/TASK_TRACEABILITY_CONTRACT.md`
9. `docs/automation/AGENT_CLEANUP_CONTRACT.md`
10. `AiStudio/Task_manager/task_queue.json`
11. `AiStudio/Task_manager/agent_locks.json`
12. `AiStudio/Task_manager/owner_directives.json`, if present

Canonical machine state lives under `AiStudio/Task_manager/`. `docs/plans/` machine-state files are legacy fallback only.

## Role Router

Use exactly one role for the current run. If the role is unclear, ask the owner before continuing. Supporting Codebase Intelligence and Swarm Coordination may return evidence to that active role without replacing its authority.

| Trigger | Role file | Prompt |
| --- | --- | --- |
| `GPT Director` | `.agent/roles/director.md` | none |
| `Project Design`, `Project Design Department`, `GPT Project Design`, `project_design`, `$aistudio-project-design` | `.agent/roles/project-design.md` | `docs/agent/prompts/ProjectDesign.md` |
| `UX Design`, `UX Design Department`, `GPT UX Design`, `ux_design`, `$aistudio-ux-design` | `.agent/roles/ux-design.md` | `docs/agent/prompts/UXDesign.md` |
| `Codebase Intelligence`, `codebase_intelligence`, `code graph`, `impact analysis`, `$aistudio-codebase-intelligence` | `.agent/roles/codebase-intelligence.md` | none |
| `Swarm Coordination`, `swarm_coordination`, `agent topology`, `named agents`, `$aistudio-swarm-coordination` | `.agent/roles/swarm-coordination.md` | none |
| `Video Idea Intake`, `Idea From Video`, `video_idea_intake`, `идея из видео`, `комитить идеи из видео` | `.agent/roles/video-idea-intake.md` | `docs/agent/prompts/VideoIdeaIntake.md` |
| `GPT Architect`, `GPT Planner` | `.agent/roles/architect.md` | `.agent/prompts/chatgpt-planner.md` |
| `GPT Dispatcher`, `Codex Dispatcher`, `Auto Make Tasks` | `.agent/roles/dispatcher.md` | `.agent/prompts/chatgpt-planner.md` |
| `Auto Worker 5.3 mini`, `Auto Worker 5.3`, `Auto Worker 5.5`, `Auto Worker 5.5max` | `.agent/roles/worker.md` | matching worker prompt in `.agent/prompts/` |
| `Auto Integrator` | `.agent/roles/integrator.md` | `.agent/prompts/auto-integrator.md` |
| `Manual Integration`, `Manual Integrator`, `Integration Protection`, `manual_integration` | `.agent/roles/integrator.md` | none |
| `Auto Finalizer` | `.agent/roles/finalizer.md` | `.agent/prompts/auto-finalizer.md` |
| `Doctor`, `Project Doctor`, `Automation Doctor` | `.agent/roles/doctor.md` | none |
| `Make human` | `.agent/roles/make-human.md` | `.agent/prompts/make-human.md` |
| `Reviewer`, `External AI`, `Gemini Reviewer` | `.agent/roles/reviewer.md` | `.agent/prompts/gemini-reviewer.md` |
| `Script Writer` | `.agent/roles/script-writer.md` | `.agent/prompts/gpt-script-writer.md` |
| `Agent Update Manager` | `.agent/roles/agent-update-manager.md` | `.agent/prompts/agent-update-manager.md` |
| `Phase Activation Manager` | `.agent/roles/phase-activation-manager.md` | `.agent/prompts/phase-activation-manager.md` |
| `Local Agent Runner` | `.agent/roles/local-agent-runner.md` | `.agent/prompts/local-agent-runner.md` |
| `Remote Automation Host` | `.agent/roles/remote-automation-host.md` | `.agent/prompts/local-agent-runner.md` |
| `Module Companion` | `.agent/roles/module-companion.md` | `.agent/prompts/module-companion.md` |

## Output Rule

Video Idea Intake uses `docs/agent/video-idea-intake/README.md`. It parses source material before integration, checks useful links when current facts matter, reviews repository reality before design, and reports GitHub evidence.

Project Design runs the workflow in `docs/agent/workflows/ProjectDesign/README.md`. It must review project reality before questions or product design, run ADC Pass 1 before clarification questions, stop at `owner_questions_required`, and run ADC Pass 2 before final design artifacts.

UX Design runs the workflow in `docs/agent/workflows/UXDesign/README.md`. Human-facing changes require UX Contract or UX Waiver; backend-only work can be waived with evidence.

Codebase Intelligence runs the supporting evidence contract in `docs/agent/code-intelligence/README.md`. It selects Scout, Verify or Auditor, treats graph output as advisory, verifies exact source and safely falls back when the provider is unavailable.

Swarm Coordination uses `docs/agent/orchestration/README.md`. It requires an existing Router decision and Parallel Work plan, forbids concrete model selection and authority transfer, and produces only a validated authority-free coordination manifest.

Manual Integration runs through Integrator `ManualIntegrationMode` and the Integration Protection docs in `docs/agent/integration/`. It must produce an Integration Manifest for non-trivial changes and must not treat draft PRs as final automation input.

Every role reports:

- role;
- task or event id;
- files changed or reviewed;
- checks run;
- branch/commit/PR/report links;
- status/result;
- blockers and next owner when work cannot complete.
