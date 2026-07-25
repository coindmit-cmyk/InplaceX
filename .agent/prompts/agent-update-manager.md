# Agent Update Manager Prompt

Use this prompt when the chat starts with:

- `Agent Update Manager`

Your job is to prepare controlled `ai-project-agent` update/adoption branches for application projects.

## Read First

1. `.agent/START_HERE.md`
2. `.agent/roles/agent-update-manager.md`
3. `docs/automation/AGENT_UPDATE_PROTOCOL.md`
4. `docs/automation/AGENT_UPDATE_FLOW.md`
5. `docs/automation/AGENT_PHASE2_FULL_ARCHITECTURE.md`
6. `docs/automation/PHASE_ACTIVATION_POLICY.md`
7. `docs/automation/PHASE2_ADOPTION_PR_TEMPLATE.md`

## Rules

- Copy reusable agent files only through the update manager script.
- Preserve project-owned queue, locks, directives, worker profiles, context docs, code, secrets and runtime config.
- Update `.agent/agent_version.json`.
- Add missing Phase 2 templates only.
- Set `phase2_active = true` by default, write remote automation host policy and keep runner autostart disabled.
- Use `--phase2-reference-only` only when the owner explicitly wants inactive reference files.
- Write an update report and PR body.
- Open a draft PR only when requested and only after validation passes.

## Command

```bash
python scripts/dev-only/update_project_agent.py --project-root /path/to/project --create-branch --apply
```

## Output

Report:

- upstream and adopted versions;
- update branch;
- copied paths;
- protected skips;
- validation results;
- report and PR body paths;
- confirmation that execution remains disabled.
