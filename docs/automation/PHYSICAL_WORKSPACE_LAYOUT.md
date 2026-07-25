# Physical Workspace Layout

Status: Project Standard v2 contract

## Purpose

This contract defines the filesystem layout for AiStudio-managed projects. It extends the existing registry, worktree planner and provisioner; it does not replace `automation_worktree_planner.py`, `automation_worktree_provisioner.py`, `automation_controller.py` or the Command Bus.

## Required Container

Each managed project has one registered container:

```text
<workspace_root>/
  PROJECT_WORKSPACE.json
  .git-store/
  Develop/
  Codex/
  Release/
  worktrees/
  temp/
    builds/
    worktrees/
    imports/
    exports/
    scratch/
    rebuild/
  runtime/
  archive/
  backups/
```

`Develop`, `Codex` and `Release` are independent Git checkouts for their configured branch roles. Temporary worker checkouts live under `temp/worktrees/`.

## Container Root Rule

The registered project container is not a normal coding checkout. For a project
named `MyVPN`, the intended local shape is:

```text
D:/Work/DevOps/MyVPN/
  PROJECT_WORKSPACE.json
  Develop/                 # clean branch role: develop
  Codex/                   # durable coordination checkout when configured
  Release/                 # clean branch role: release
  worktrees/
    codex-install-identity-accounting/
    codex-fix-tg-admin-regression/
  temp/worktrees/          # disposable worker worktrees
  runtime/
  archive/
  backups/
```

Agents must treat `D:/Work/DevOps/MyVPN/Develop` as the primary clean
development checkout, not `D:/Work/DevOps/MyVPN` itself. The container root may
hold manifests, worktree folders, runtime artifacts, archives and local
metadata, but it must not accumulate ordinary feature edits.

If the container root is itself a dirty Git checkout or sits on a feature
branch, agents must report `workspace_layout_violation` and create/repair a
clean `Develop` checkout before doing new work. They must not "fix" this by
resetting the dirty root without an explicit preservation plan and owner
approval.

## Release Checkout Rule

`Release` is a separate clean checkout for the configured release/stable branch.
It is not a place for ordinary development, worker execution or Codex feature
work. Agents may use it for release verification, release promotion evidence
and approved direct hotfixes only.

Direct edits in `Release` require owner approval and must produce a backport or
equivalent patch verification against `Develop`. If `Release` is dirty,
diverged, or contains task/runtime state that belongs to development, agents
must stop as `release_checkout_violation` before proceeding.

## Safety Rules

- Resolve all paths from Registry v2 or `PROJECT_WORKSPACE.json`; never discover writable roots by broad filesystem search.
- Refuse paths outside the registered container.
- Refuse symlink, junction or nested repository escapes.
- Refuse automatic writes when the target checkout is dirty, diverged or on the wrong branch role.
- Archive before moving legacy material; automatic delete is disabled by default.

## Existing Components To Extend

- `automation_worktree_planner.py` for dry-run layout diagnostics.
- `automation_worktree_provisioner.py` for approved worktree provisioning.
- `github_freshness_guard.py` and `release_branch_guard.py` for Git freshness and release order.
- `remote_dashboard_stub.py` for reporting layout and command state.

## Schema

`schemas/agent-control/project_workspace.schema.json` defines `PROJECT_WORKSPACE.json`.
