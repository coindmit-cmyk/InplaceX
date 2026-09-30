# Dashboard Map

`remote_dashboard_stub.py` is the current compatibility server. It already gathers project, task, run, resource and limit information and exposes local run endpoints, but it remains a monolith.

## Planes

- Snapshot plane: remote host publishes status snapshots to the dashboard mirror.
- Command plane: Dashboard writes commands to a durable queue; the remote host pulls and executes through approved controllers.

Mirror mode remains read-only until the Command Bus is accepted.

## V1 Reliability Requirements

- health endpoints;
- snapshot schema version;
- stale-data badge;
- command lifecycle visibility;
- remote consumer heartbeat;
- bounded scan timeout;
- last-good snapshot;
- structured errors;
- smoke tests for local control mode and mirror read-only mode.
