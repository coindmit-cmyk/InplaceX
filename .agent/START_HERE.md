# InplaceX START_HERE

This is the mandatory first file for every Codex session, automation runner
and external review in InplaceX.

## Identity

- Project id: `inplacex`
- Repository: `GoodEvil11/InplaceX`
- Development source of truth: `origin/master` until the project adopts a
  different integration branch
- Host assignment table: `docs/automation/PROJECT_HOST_ASSIGNMENTS.md`

## First Steps

1. Read this file completely.
2. Read `docs/automation/PROJECT_HOST_ASSIGNMENTS.md` and resolve the
   `inplacex` row.
3. Confirm the live project Registry and runner state before automation.
4. Fetch the configured base ref and verify worktree freshness.
5. Inspect the task scope, current changes and relevant application files.
6. Keep credentials and local runtime data outside Git.

## Host Assignment

- Automation host id: `aistudio` (must match live Registry and runner state)
- Project SSH profile: `dmit-inplacex-space`
- Project workspace: `/srv/agent-projects/inplacex`
- Shared portfolio space: `dmit-mobile-games-space`

InplaceX uses its dedicated project space, not the shared mobile-games space,
unless the owner explicitly changes the assignment. The SSH profile is an
operator route, not automation authority. If the table conflicts with live
state, stop as `host_assignment_unknown`.

## Security

- Use safe SSH profile aliases in tracked docs.
- Keep real endpoints, private keys, passwords, tokens and provider credentials
  in the operator's local access vault.
- Do not copy secret material into chat, commits, reports or task packets.

