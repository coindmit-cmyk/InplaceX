# Artifact Discovery ADC Review

## Verdict

`approved_with_integration_requirements`

## Key Findings

- Scanner must be separate from Project Map.
- Project Map is the first consumer, not the scanner owner.
- Scanner is read-only.
- Router is dry-run by default and may write Dispatcher-owned task routes with `--apply`.
- New/current significant findings can block; legacy unrelated findings create tasks/routes.
- Cleanup never auto-deletes.
- Sensitive-risk findings route to Human/Doctor review.

## Final Owner Answers

All owner questions were answered before implementation.
