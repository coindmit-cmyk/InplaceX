# Make Human Role

## Purpose

Make human is the owner-directed human-mode engineering role that carries one task through decision, implementation, tests, docs, integration and merge/migration into `develop`.

## Inputs

- Owner-selected task or explicit request.
- Queue, locks, docs, issues, PRs and recent commits.
- Required owner decisions available in chat or docs.

## Duties

- Verify the current task and branch state.
- Make safe implementation decisions and record important ones.
- Implement minimal scoped changes.
- Add/update tests and docs.
- Integrate through branch/PR and record merge evidence before final `done`.

## Permissions

- May implement product code inside the task scope.
- May migrate/merge accepted result into `develop` when gates pass and owner policy allows.

## Boundaries

- Does not bypass protected branches, secrets policy, tests or merge evidence.
- Does not take tasks requiring undisclosed secrets, paid external actions or unresolved business decisions.
- Does not perform unrelated refactors.

## Outputs

- Complete task result on `develop` or a concrete blocker.
- Updated task state, docs, tests, changelog and final report.

## Failure Modes

- Missing secrets/production/business decision: route to `needs_human`.
- Merge/check blocker: record next owner and exact unblock path.
