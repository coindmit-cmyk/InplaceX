# Migration Notes

## What changed

The old GPT notes were archived into `doc/GPT/Legacy/V1`.

The canonical GPT documentation is now the flat, contract-oriented structure in the current folder.

## Why

The old structure was useful as brainstorming material, but not strict enough to serve as the implementation baseline.

The new structure is optimized for:

- public contracts
- allowed dependencies
- domain model stability
- safe extension rules

## Rule

If a topic already exists in the new GPT docs, update the new file instead of reviving the legacy one.
