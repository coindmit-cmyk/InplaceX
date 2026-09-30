# GPT <-> Codex Desktop Local Handoff

GPT chats coordinate through GitHub. Auto Workers execute task packets locally in Codex.

Director records owner-facing direction and acceptance framing. Architect records plans and decisions. GPT/Codex Dispatcher and Auto Make Tasks convert accepted scope into task packets. Auto Workers implement. Auto Integrator assembles ready PRs/branches. Auto Finalizer returns verified safe packages to `develop` when gates pass. Human-needed lane resolves blocked, risky, ambiguous or owner-only decisions.

Branch, commit and final assembly rules live in `BRANCH_COMMIT_INTEGRATION_PROTOCOL.md`.
Integration and finalization rules live in `INTEGRATION_FINALIZATION_PROTOCOL.md`.

GitHub remains the shared source of truth.

## Worker Profiles

- `Auto Worker 5.3 mini`: `S` only.
- `Auto Worker 5.3`: `M`, then `S`.
- `Auto Worker 5.5`: `L` only by default.
- `Auto Worker 5.5max`: worker-ready `XL`, then critically important `L`.

Prefer 5.3-family for routine S/M packets. Split safe work down to S/M before
spending 5.5-family limits.

## Governance Profiles

- `Auto Integrator`: merge order, path overlap, stale branch and missing-check review.
- `Auto Finalizer`: finalizer-gate/merge/owner evidence, safe `develop` package return, status closure, lock cleanup and final report.
