# Artifact Discovery Validation

## Normal Operator Validation

```bash
python scripts/agent_control/artifact_discovery_cycle.py --project-root . --output-dir <temp-or-report-dir> --worker-ready-first-safe --json
python scripts/agent_control/artifact_discovery_doc_validator.py --project-root . --json
python scripts/agent_control/validate_automation_manifest.py --project-root . --json
python -m pytest tests/test_artifact_discovery_scanner.py tests/test_artifact_discovery_classifier.py tests/test_artifact_discovery_router.py tests/test_artifact_discovery_report_builder.py tests/test_artifact_discovery_normalizer.py tests/test_artifact_discovery_cycle.py tests/test_artifact_discovery_doc_validator.py tests/test_artifact_discovery_examples.py tests/test_artifact_discovery_cli_imports.py -q
```

## Stage-by-stage Debugging

```bash
python scripts/agent_control/artifact_discovery_scanner.py --project-root . --output artifact-discovery-scan.json --json
python scripts/agent_control/artifact_discovery_classifier.py --input artifact-discovery-scan.json --output artifact-discovery-classified.json --json
python scripts/agent_control/artifact_discovery_router.py --project-root . --input artifact-discovery-classified.json --output artifact-discovery-routed.json --json
python scripts/agent_control/artifact_discovery_report_builder.py --input artifact-discovery-routed.json --output artifact-discovery-report.md --json
python scripts/agent_control/artifact_discovery_normalizer.py --project-root . --input artifact-discovery-routed.json --output artifact-discovery-normalized.json --worker-ready-first-safe --json
```

## Gate Meaning

- Scanner must be read-only.
- Router must default to dry-run.
- Queue-visible rows must go through the normalizer or cycle `--apply-normalized`.
- Router `--apply` is reserved for reviewed Dispatcher-owned exceptional raw route creation.
- Existing legacy findings are expected during migration.
- New/current significant artifacts require discovery disposition before integration/finalization.
- `artifact_discovery_doc_validator.py` must pass before treating discovery docs as working.
