# Artifact Discovery Change Policy

Artifact Discovery Layer starts as an advisory and routing layer.

## Phase 1

- Scanner read-only.
- Classifier read-only.
- Report builder read-only.
- Router dry-run by default.
- Router `--apply` may write Dispatcher-owned task candidates only.

## Later Hardening

Projects may later wire Artifact Discovery into Dispatcher, Integrator or Finalizer gates after the first backfill pass has reduced expected legacy findings.
