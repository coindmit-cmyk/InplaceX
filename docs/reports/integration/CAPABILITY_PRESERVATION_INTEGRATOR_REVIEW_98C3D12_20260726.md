# Capability preservation integrator review: 98c3d12

Mode: `ManualIntegrationMode`

Disposition: `rejected`

Candidate `98c3d1292ea733082b72051d965ad48f2169f524` is preserved on its worker
branch and must not be integrated into `develop`.

## Blocking finding: deleted-file detection uses leaked loop state

In `capability_preservation_check.py`, `result_for` first iterates changed
entries as `(old_path, new_path)`. A later loop iterates `before.items()` but
tests `if new_path is None` without deriving `new_path` for the current
`old_path`.

The value therefore leaks from the last entry of the previous loop. File
removal attribution depends on changed-path ordering: a real deletion can miss
`file_removed`, while an unrelated path can be classified from stale state.
The existing single-delete fixture cannot expose this multi-entry failure.

## Required retry contract

- Derive head-path presence independently for every base path.
- Do not read loop variables outside the iteration that owns them.
- Add isolated repositories with at least two changed entries combining delete,
  modify, rename and move operations.
- Permute path order and require byte-identical deterministic JSON plus the same
  removal decision.
- Assert the deleted path is always reported and unrelated paths are never
  attributed as deleted.
- Preserve the read-only ref comparison, deterministic ordering and existing
  fixture coverage from this candidate as salvage evidence.

## Evidence

- Candidate: `98c3d1292ea733082b72051d965ad48f2169f524`
- Candidate branch:
  `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5max/crb-integration-capability-preservation-check/implement-fail-closed-capability-preservation-in`
- Blocking source is local to `result_for`; no candidate files were integrated.
