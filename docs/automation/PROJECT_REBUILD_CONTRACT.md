# Project Rebuild Contract

Status: Project Standard v2 contract

## Purpose

The Project Rebuilder normalizes old project layouts without rewriting product code. It extends existing worktree and cleanup tooling.

## Levels

- Level 0: scan and report only.
- Level 1: version, index, manifest and docs metadata.
- Level 2: `.agent`, Task Manager and policies.
- Level 3: physical workspace container and clean checkouts.
- Level 4: branch normalization and stale branch archive plan.
- Level 5: combined 1-4.

All levels are dry-run by default. Apply requires `--apply` and an approved plan id/hash.

## Product Preservation

Before apply, create inventory evidence for tracked paths, HEAD SHA, content hashes, uncommitted files, unpushed commits, branches, tags and size counts.

After apply, validate:

```text
product payload unchanged
or
all differences are explicitly allowed service/docs paths
```

Unexpected product diffs stop cutover and create a rollback report.

## Existing Components To Extend

- `automation_worktree_planner.py`
- `automation_worktree_provisioner.py`
- `branch_cleanup_planner.py`
- `post_migration_cleanup.py`
- `validate_agent_reports.py`

## Forbidden

- Refactoring or formatting product code during rebuild.
- Using arbitrary local copies as the product source.
- `git reset --hard` as cleanup.
- Making a new layout active after failed validation.
