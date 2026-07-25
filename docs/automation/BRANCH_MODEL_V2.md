# Branch Model v2

Status: Project Standard v2 contract

## Roles

AiStudio uses logical branch roles:

- `develop`: accepted development baseline.
- `codex`: durable coordination state and pre-integration artifacts.
- `release`: stable tested release line.

Registry maps these roles to physical branch names. Agent Core commonly uses `release/main`; application projects may use `release` or `release/<version>`.

Registry also exposes three execution refs:

- `code_base_ref`: fresh code/content base used for worker execution and integration checks.
- `state_ref`: durable coordination state ref used for Task Manager queue/locks reads.
- `push_ref`: branch that receives durable coordination commits.

Default mapping is `code_base_ref = origin/<branches.develop>` and `state_ref/push_ref = <task_manager_branch_role>`. This keeps worker code based on accepted `develop` while Task Manager coordination can live on the `codex` branch.

## Temporary Branches

Automation branches use:

```text
AiStudio/Agent/worker/*
AiStudio/Agent/dispatcher/*
AiStudio/Agent/integrator/*
AiStudio/Agent/finalizer/*
AiStudio/Agent/rebuild/*
AiStudio/Agent/cleanup/*
```

Every temporary branch must end with a disposition: `merged`, `archived`, `stale`, `duplicate`, `needs_rebuild` or `needs_human`.

## Release Rule

Release promotion originates from tested `develop`. `release/main` is not a working branch. Direct release hotfixes require owner approval and a backport to `develop`.

The physical release checkout is named `Release` under the project container.
It must stay clean and mapped to the configured release branch role.

`scripts/agent_control/release_branch_guard.py` validates this rule and distinguishes SHA-only release commits from patch-equivalent changes already present in `develop`.

## Forbidden

- Worker scratch work in clean `develop`.
- Feature, fix or Codex branch work in the registered project container root.
- Treating `<ProjectContainer>/` as the primary development checkout when
  `<ProjectContainer>/Develop` exists or is configured.
- Live Task Manager state in application release branches.
- Force-push based reconciliation.
- Treating patch-equivalent release commits as missing backports.
- Worker, feature or Codex branch edits in `<ProjectContainer>/Release`.

## Physical Checkout Mapping

Branch roles map to physical checkout names inside the registered project
container:

```text
develop -> D:/Work/DevOps/MyVPN/Develop
codex   -> D:/Work/DevOps/MyVPN/Codex
release -> D:/Work/DevOps/MyVPN/Release
```

Temporary feature, fix, Codex and recovery branches must use separate worktrees,
preferably under:

```text
D:/Work/DevOps/MyVPN/worktrees/<branch-purpose>
```

The container root is a workspace manager boundary, not a safe place for normal
branch edits.
