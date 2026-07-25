# Compact Status Dashboard Spec

`compact_status_builder.py` writes:

```text
docs/plans/automation_status.json
docs/plans/automation_status.md
```

The owner should not need to open many intermediate JSON reports to know what
runs next.

## JSON Shape

```json
{
  "schema_version": 1,
  "project": "Project",
  "updated_at": "ISO-8601",
  "counts": {},
  "next_actions": [],
  "human_queue": [],
  "blocked": [],
  "latest_reports": []
}
```

## Required Signals

- worker-ready tasks;
- rebuild decision counts;
- pending events;
- human/blocked queue;
- handoff-ready/finalizer-ready items;
- latest report paths.
