# Artifact Discovery Owner Decisions

- Layer name: Artifact Discovery Layer.
- No new role for the first package.
- Scanner is read-only.
- Router may write with `--apply` under Dispatcher ownership.
- Scanner coverage should be broad.
- New/current significant findings may block.
- Legacy findings create task/route records and do not block by default.
- Sensitive-risk findings block.
- Cleanup never auto-deletes.
- Dispatcher creates backfill/triage tasks.
- Project Map consumer is included immediately.
- First package includes docs, schemas, templates, scripts, tests and integration surfaces.
