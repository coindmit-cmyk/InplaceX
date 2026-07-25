# Artifact Discovery Backlog Notes

Current implementation includes the read-only scanner, classifier, router,
report builder, normalizer, cycle command, documentation validator and controlled
Dispatcher-owned normalized task candidate writing.

Remaining work should be handled as scoped Dispatcher packets:

- Project Map batches for high-value current-scope files;
- missing index link triage;
- script catalog expansion as new automation entrypoints appear;
- schema/template pair triage;
- sensitive-risk routing smoke and Human/Doctor/security review policy checks;
- optional CI or finalizer gate integration after owner approval.

Do not import every raw router candidate into `task_queue.json`. Use
`artifact_discovery_normalizer.py` or `artifact_discovery_cycle.py
--apply-normalized`, then let Dispatcher create small Worker Packet v2 rows.
