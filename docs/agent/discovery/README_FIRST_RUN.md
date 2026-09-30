# Artifact Discovery First Run

Recommended first run is read-only and report-only.

```bash
python scripts/agent_control/artifact_discovery_cycle.py \
  --project-root . \
  --output-dir docs/reports/discovery/manual-first-run \
  --worker-ready-first-safe \
  --json
```

Review the generated Markdown and normalized JSON before any
`--apply-normalized` run.

Do not use raw router `--apply` for normal first-run imports.

Then validate the documentation and catalog surface:

```bash
python scripts/agent_control/artifact_discovery_doc_validator.py --project-root . --json
```
