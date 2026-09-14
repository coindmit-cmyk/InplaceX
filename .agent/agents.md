# AI Agent Roles

Detailed role rules live in `.agent/roles/`. Use exactly one active role for a run. Supporting Codebase Intelligence and Swarm Coordination may return evidence without replacing that role's authority.

## Source Of Truth

Shared project state remains in GitHub, repository docs and the existing Task Manager files. Do not split the backlog into per-agent, per-model or per-worker queues.

## Role Router

| Trigger | Role file | Prompt | Purpose |
| --- | --- | --- | --- |
| `GPT Director` | `.agent/roles/director.md` | none | Clarify owner intent and project direction. |
| `Project Design`, `Project Design Department`, `GPT Project Design`, `project_design`, `$aistudio-project-design` | `.agent/roles/project-design.md` | `docs/agent/prompts/ProjectDesign.md` | Prepare reality-checked design packages. |
| `UX Design`, `UX Design Department`, `GPT UX Design`, `ux_design`, `$aistudio-ux-design` | `.agent/roles/ux-design.md` | `docs/agent/prompts/UXDesign.md` | Define human-facing flows and contracts. |
| `Codebase Intelligence`, `codebase_intelligence`, `code graph`, `impact analysis`, `$aistudio-codebase-intelligence` | `.agent/roles/codebase-intelligence.md` | none | Gather code structure and impact evidence. |
| `Swarm Coordination`, `swarm_coordination`, `agent topology`, `named agents`, `$aistudio-swarm-coordination` | `.agent/roles/swarm-coordination.md` | none | Coordinate an authorized parallel plan. |
| `Video Idea Intake`, `Idea From Video`, `video_idea_intake`, `идея из видео`, `комитить идеи из видео` | `.agent/roles/video-idea-intake.md` | `docs/agent/prompts/VideoIdeaIntake.md` | Convert video evidence into project input. |
| `GPT Architect`, `GPT Planner` | `.agent/roles/architect.md` | `.agent/prompts/chatgpt-planner.md` | Define architecture and acceptance criteria. |
| `GPT Dispatcher`, `Codex Dispatcher`, `Auto Make Tasks` | `.agent/roles/dispatcher.md` | `.agent/prompts/chatgpt-planner.md` | Materialize complete executable task packets. |
| `Auto Worker 5.3 mini`, `Auto Worker 5.3`, `Auto Worker 5.5`, `Auto Worker 5.5max` | `.agent/roles/worker.md` | matching worker prompt in `.agent/prompts/` | Execute an accepted bounded task. |
| `Auto Integrator` | `.agent/roles/integrator.md` | `.agent/prompts/auto-integrator.md` | Verify and integrate accepted changes. |
| `Manual Integration`, `Manual Integrator`, `Integration Protection`, `manual_integration` | `.agent/roles/integrator.md` | none | Review manual integration evidence. |
| `Auto Finalizer` | `.agent/roles/finalizer.md` | `.agent/prompts/auto-finalizer.md` | Verify terminal evidence and close work. |
| `Doctor`, `Project Doctor`, `Automation Doctor` | `.agent/roles/doctor.md` | none | Diagnose project and automation state. |
| `Make human` | `.agent/roles/make-human.md` | `.agent/prompts/make-human.md` | Run an owner-directed task lifecycle. |
| `Reviewer`, `External AI`, `Gemini Reviewer` | `.agent/roles/reviewer.md` | `.agent/prompts/gemini-reviewer.md` | Review sources and changes read-only. |
| `Script Writer` | `.agent/roles/script-writer.md` | `.agent/prompts/gpt-script-writer.md` | Design an explicitly requested helper script. |
| `Agent Update Manager` | `.agent/roles/agent-update-manager.md` | `.agent/prompts/agent-update-manager.md` | Adopt tested Agent Core updates. |
| `Phase Activation Manager` | `.agent/roles/phase-activation-manager.md` | `.agent/prompts/phase-activation-manager.md` | Activate an approved automation phase. |
| `Local Agent Runner` | `.agent/roles/local-agent-runner.md` | `.agent/prompts/local-agent-runner.md` | Control an explicitly started local runner. |
| `Remote Automation Host` | `.agent/roles/remote-automation-host.md` | `.agent/prompts/local-agent-runner.md` | Operate an approved remote execution host. |
| `Module Companion` | `.agent/roles/module-companion.md` | `.agent/prompts/module-companion.md` | Support owner-led bounded module work. |

Read `.agent/START_HERE.md`, the environment route and the matching role file before substantive work.
