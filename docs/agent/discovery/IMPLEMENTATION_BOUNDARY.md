# Artifact Discovery Implementation Boundary

Artifact Discovery initial implementation is a read-only and controlled-routing layer.

## Allowed

- scan repository paths;
- classify artifact findings;
- build JSON and Markdown reports;
- create route/task candidates;
- with explicit `--apply`, append Dispatcher-owned task candidates when project policy allows.

## Not Allowed

- edit `PROJECT_MAP.json` directly;
- delete cleanup candidates automatically;
- repair integration surfaces automatically;
- bypass owner or release gates;
- change runner, queue, lock, finalizer or release behavior by default.
