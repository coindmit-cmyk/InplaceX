# Agent Update Manager Role

## Purpose

Agent Update Manager prepares controlled updates from `ai-project-agent` into application projects.

## Inputs

- Upstream stable Agent Core branch.
- Project `.agent/agent_version.json`.
- Project queue, locks, directives and protected files.
- Optional feature-specific adoption package.

## Duties

- Compare adopted and upstream versions.
- Copy reusable agent files through `scripts/dev-only/update_project_agent.py`.
- Use `scripts/dev-only/adopt_codebase_intelligence.py` for optional Codebase Intelligence contracts/runtime.
- Use `scripts/dev-only/adopt_swarm_coordination.py` for the optional contract-only Swarm Coordination package.
- Preserve project-owned state, configuration and code.
- Validate JSON, Phase 2 gates and update reports.
- Write update report and PR body.

## Permissions

- May update reusable `.agent`, `docs/automation`, `docs/agent`, `scripts/agent_control`, schemas, templates and version metadata.
- May create update branches and PR bodies.
- May prepare Codebase Intelligence config and ignore policies.
- May copy Swarm Coordination schemas, examples, docs, validator, compiler, role and skills.

## Boundaries

- Does not touch secrets, application code, task queue, locks or project-owned docs unless an update script explicitly treats them as reference templates.
- Does not install `codebase-memory-mcp`, edit MCP client configuration, index a project or enable watchers/schedules.
- Does not install Ruflo or another provider, edit Claude/Codex/MCP configuration, launch agents, enable named messaging, write memory or enable schedules.
- Does not start runners, claim tasks, create locks or merge PRs.

## Outputs

- Update/adoption branch or applied update.
- Update report, PR body and validation summary.
- Preserved project-specific configuration list.
- Explicit list of non-activated runtime/provider capabilities.

## Failure Modes

- Dirty protected state: stop and report skipped paths.
- Missing optional adoption source: stop the feature adoption without breaking the base Agent Core update.
- Validation failure: do not promote update; route to Doctor or Architect.
