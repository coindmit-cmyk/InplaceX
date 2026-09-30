# Role Rules Layer

## Decision

Agent Core role rules live in dedicated files under:

```text
.agent/roles/
```

`START_HERE.md` is a router. `agents.md` is a short role index. Prompt files stay short and execution-oriented.

## Reason

Agent Core is growing. Keeping all role rules inside `START_HERE.md`, `agents.md` or prompt files makes the system hard to read, update and validate. A durable role layer lets shared rules remain shared while each role owns only its specific duties, permissions, inputs, outputs, handoffs and failure modes.

## Structure

Required role files:

```text
.agent/roles/director.md
.agent/roles/architect.md
.agent/roles/dispatcher.md
.agent/roles/worker.md
.agent/roles/integrator.md
.agent/roles/finalizer.md
.agent/roles/doctor.md
.agent/roles/make-human.md
.agent/roles/reviewer.md
.agent/roles/script-writer.md
.agent/roles/agent-update-manager.md
.agent/roles/phase-activation-manager.md
.agent/roles/local-agent-runner.md
.agent/roles/remote-automation-host.md
```

Each file uses the same sections:

- Purpose
- Inputs
- Duties
- Permissions
- Boundaries
- Outputs
- Failure Modes

## Boundaries

Shared rules stay out of role files:

- GitHub freshness and push durability;
- branch and PR governance;
- queue, locks and event contracts;
- secrets and production safety;
- release/versioning gates;
- evidence and cleanup rules.

Those rules remain in `.agent/routing.md`, `.agent/permissions.md` and `docs/automation/`.

## Prompt Migration

Prompt files under `.agent/prompts/` should remain execution-oriented. They may state trigger names, read order, immediate workflow and role-specific commands, but long-lived role policy belongs in `.agent/roles/`.

When updating prompts:

1. keep the prompt short;
2. add the matching `.agent/roles/<role>.md` to "Read First";
3. remove duplicated durable role policy when the role file already owns it;
4. keep command examples and immediate run checklist in the prompt.

## Adoption

`scripts/dev-only/update_project_agent.py` copies `agent-core/.agent` as a directory, so the role layer is adopted with the rest of Agent Core.

Project adoption must verify:

- `.agent/roles/` exists after update;
- all required role files exist;
- `START_HERE.md` links to role files and prompt files;
- `agents.md` remains an index, not a duplicate rule book;
- protected project state is not overwritten.

## Acceptance Criteria

- All required role files exist in `agent-core/.agent/roles/`.
- `START_HERE.md` routes every supported trigger to a role file.
- `agents.md` contains only source-of-truth summary and role index.
- Role files do not duplicate broad shared rules.
- Prompt files remain short and point to matching role files.
- Update Manager copies role files into application projects.
- Tests fail if a required role file is missing or not referenced by `START_HERE.md` / `agents.md`.

## Migration Plan

1. Create role files under `agent-core/.agent/roles/`.
2. Move durable role duties and boundaries out of `START_HERE.md` and `agents.md`.
3. Keep `START_HERE.md` as role router and shared read-order entry point.
4. Keep `agents.md` as source-of-truth summary and role index.
5. Update prompts to reference role files during future prompt cleanup passes.
6. Add tests for required role files and router/index references.
7. Release through `develop` first, then promote to `release/main` after validation.
