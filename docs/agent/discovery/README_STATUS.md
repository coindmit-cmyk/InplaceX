# Artifact Discovery Status

Status: active implementation package.

This layer is active as documentation, schemas, templates, read-only scanner,
classifier, router/report builder, normalizer, one-command cycle and tests.

Runtime automation behavior changes only when explicitly called by an operator,
Dispatcher, Integrator, Doctor or recovery cycle.

Current safe automation path:

```text
artifact_discovery_cycle.py --worker-ready-first-safe [--apply-normalized]
```

`artifact_discovery_router.py --apply` remains an exceptional reviewed
Dispatcher gate and is not the default import mechanism.
