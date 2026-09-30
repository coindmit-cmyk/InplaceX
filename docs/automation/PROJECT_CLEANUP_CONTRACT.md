# Project Cleanup Contract

Status: Project Standard v2 contract

## Purpose

Cleanup archives legacy material with evidence and restore paths. It does not delete by default.

## Pipeline

```text
scan -> classify -> cleanup plan -> approval -> archive move -> checksum manifest -> validate -> report -> optional retention review
```

## Categories

- sibling folders;
- stale worktrees;
- old runtime/log/cache;
- old builds/imports/exports;
- legacy Task Manager paths;
- obsolete generated reports;
- duplicate checkouts;
- old docs;
- merged or stale branches.

## Archive Manifest

Every archived object records source, target, classification, project id, size, hash or inventory hash, reason, approval evidence, restore procedure and retention metadata.

## Restore

Restore validates the manifest, refuses target overwrite, restores into a staging path and verifies hashes before any owner cutover.

## Forbidden

- Unplanned glob delete.
- Automatic delete while `automatic_delete_enabled=false`.
- Moving unknown or low-confidence objects without human routing.
- Overwriting archive targets.
