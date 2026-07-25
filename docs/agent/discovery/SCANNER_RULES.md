# Artifact Discovery Scanner Rules

## Purpose

Scanner inventories repository artifacts and emits findings. It never edits project files, maps, queues, indexes or reports in-place.

## Default Coverage

```text
.agent/**
agent-core/.agent/**
docs/agent/**
docs/automation/**
agent-core/docs/automation/**
docs/reports/**
scripts/agent_control/**
schemas/**
templates/**
AiStudio/Task_manager/**
PROJECT_MAP.json
PROJECT_MAP.md
README.md
CHANGELOG.md
PROJECT_VERSION.json
VERSION
src/**
app/**
apps/**
packages/**
services/**
modules/**
lib/**
api/**
backend/**
frontend/**
web/**
ui/**
components/**
pages/**
routes/**
tests/**
test/**
docs/**
```

## Ignored Low-value Paths

Generated caches, VCS internals, dependency folders, local runtime folders and
generated Artifact Discovery reports are ignored by default unless explicitly
included by config.

```text
.git/**
node_modules/**
.venv/**
__pycache__/**
.pytest_cache/**
runtime/**
agent-runtime/**
AiStudio/Task_manager/backups/**
docs/reports/discovery/**
AiStudio/Task_manager/reports/discovery/**
```

## Scanner Outputs

```yaml
inventory:
  - path:
    artifact_type:
    significant:
    flags:
    disposition:
findings:
  - id:
    category:
    severity:
    confidence:
    path:
    artifact_flags:
    artifact_disposition:
```

Inventory `flags` are lightweight file-level classifications such as
`significant`, `agent_surface`, `automation_surface`, `contract_surface`,
`evidence_surface`, `implementation_surface`, `task_manager_state` and
`cleanup_candidate`.

Inventory `disposition` is the scanner's next-step hint. Common values are
`inventory_only`, `needs_coverage_check`, `needs_project_map_backfill`,
`needs_index_or_exception`, `needs_script_catalog_or_exception`,
`needs_schema_template_or_exception`, `needs_cleanup_review` and
`needs_human_security_review`.

## First Detector Classes

- artifact inventory;
- missing map coverage;
- missing index link;
- missing script catalog entry;
- missing validator/template pair;
- legacy state reference;
- cleanup candidate;
- possible secret pattern;
- missing UX contract/waiver signal;
- missing integration surface signal.
