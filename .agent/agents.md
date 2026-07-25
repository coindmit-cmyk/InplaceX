# AI Agent Roles

Short index of available roles. Detailed role-specific rules live in `.agent/roles/`.

## Source Of Truth

Shared project state lives in:

- GitHub Issues and Pull Requests;
- repository docs;
- `AiStudio/Task_manager/task_queue.json`;
- `AiStudio/Task_manager/agent_locks.json`;
- `AiStudio/Task_manager/agent_events.jsonl`;
- `AiStudio/Task_manager/agent_process_state.json`;
- `AiStudio/Task_manager/model_budget_state.json`.

The task queue is project-wide. Do not split backlog state into per-agent, per-model or per-worker queues.

## Role Index

| Role | Rule file | Purpose |
| --- | --- | --- |
| Director | `.agent/roles/director.md` | Clarify owner intent, then version product, requirements and documentation context before tasks. |
| Project Design | `.agent/roles/project-design.md` | GPT chat workflow role for reality-checked, ADC-gated pre-implementation design packages. |
| UX Design | `.agent/roles/ux-design.md` | Standalone UX department for human-facing contracts, waivers, flows, states, visual direction and review. |
| Codebase Intelligence | `.agent/roles/codebase-intelligence.md` | Supporting Scout/Verify/Auditor evidence for code structure, dependency paths and change impact. |
| Swarm Coordination | `.agent/roles/swarm-coordination.md` | Authority-free topology, named-message, shared-context, consensus and stop-policy overlay for an existing Parallel Work plan. |
| Architect | `.agent/roles/architect.md` | Architecture decisions, decomposition and acceptance criteria. |
| Dispatcher | `.agent/roles/dispatcher.md` | Worker-ready packet creation, repair, split and routing. |
| Worker | `.agent/roles/worker.md` | Scoped task execution from complete Worker Packet v2 tasks. |
| Integrator | `.agent/roles/integrator.md` | Auto/manual integration owner for branches, PRs, worker evidence, manual changes and Integration Protection manifests. |
| Finalizer | `.agent/roles/finalizer.md` | Close accepted work after merge/approval evidence. |
| Doctor | `.agent/roles/doctor.md` | Diagnose automation/project state and route fixes to the next owner. |
| Make human | `.agent/roles/make-human.md` | Owner-directed full task lifecycle into `develop`. |
| Module Companion | `.agent/roles/module-companion.md` | Owner-led module chats, manual work evidence and stale task replan routing. |
| Reviewer | `.agent/roles/reviewer.md` | Read-only review of specs, diffs, risks and missing tests. |
| Script Writer | `.agent/roles/script-writer.md` | Safe helper-script design when explicitly tasked. |
| Agent Update Manager | `.agent/roles/agent-update-manager.md` | Controlled Agent Core adoption/update into projects. |
| Phase Activation Manager | `.agent/roles/phase-activation-manager.md` | Phase 2 activation after owner approval and validation. |
| Local Agent Runner | `.agent/roles/local-agent-runner.md` | Local/manual runner control when explicitly started. |
| Remote Automation Host | `.agent/roles/remote-automation-host.md` | Remote PC execution environment for workers, integrator/finalizer and local LLM. |

Read `.agent/START_HERE.md` first, then the matching role file and prompt.
