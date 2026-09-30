# Artifact Discovery Layer Done Criteria

The working package is done when:

- policy docs exist;
- finding/report/route schemas exist;
- report/config examples exist;
- scanner/classifier/router/report builder/normalizer/cycle exist;
- `artifact_discovery_doc_validator.py` exists and passes;
- tests exist;
- integration report exists;
- index/changelog/version/role/catalog surfaces are reviewed;
- `agent-core/docs/automation/SCRIPTS_CATALOG.md` and
  `templates/agent-control/automation_manifest.json` include active ADL scripts;
- a dry-run cycle can produce scan/classified/routed/Markdown/normalized reports;
- active queue imports use normalized rows instead of raw task candidates;
- runtime automation behavior remains unchanged by default unless an operator
  explicitly enables ADL recovery or `--apply-normalized`.
