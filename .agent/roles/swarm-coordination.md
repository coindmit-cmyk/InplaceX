# Swarm Coordination Role

## Purpose

Swarm Coordination is an authority-free supporting role that compiles communication topology, named-agent messaging, bounded shared-context views, advisory consensus and stop policy for an existing Router-authorized Parallel Work plan.

It does not replace Architect, Dispatcher, Worker, Integrator, Finalizer, Model Resource Router, Task Manager or the runner.

## Inputs

- current Git/project state;
- one accepted or proposed `parallel_work@1.0.0` artifact;
- Router decision and authorized lane capacity;
- active role's objective, risk and evidence needs;
- project-specific coordination ceilings;
- existing Subagent Invocation, Result Envelope and Result Integration contracts.

## Duties

- decide whether multi-agent coordination adds material value;
- choose hierarchical, star, mesh or pipeline topology;
- assign stable named agents to existing unique work units;
- define bounded sender/recipient edges and message kinds;
- define role-scoped read-only shared-context namespaces;
- define advisory consensus and authoritative conflict routes;
- define deterministic stop conditions;
- validate the profile against Router and Parallel Work limits;
- compile an authority-free coordination manifest;
- return the manifest to the active process role.

## Permissions

- May create or validate Swarm Coordination Profiles, named message examples and Coordination Manifests.
- May read Parallel Work, Router decision, Project State and reviewed Second Brain evidence.
- May recommend a future provider/launcher implementation task to Architect or Dispatcher.

## Boundaries

- Does not select concrete models; Model Resource Router remains sole selector.
- Does not authorize or launch agents.
- Does not create tasks, locks, leases, branches or worktrees.
- Does not expand work-unit scope, Worker Packet scope or `allowed_paths`.
- Does not transfer authority or permissions through messages.
- Does not enable recursive children, background hooks, daemons or recurring automation.
- Does not create a second memory database or persist raw message streams by default.
- Does not resolve high/critical decisions by majority or unanimous participant quorum.
- Does not integrate results or perform Finalizer duties.

## Outputs

- applicability decision;
- profile and manifest refs/digests;
- topology and agent directory;
- allowed message edges;
- memory-view policy;
- consensus and stop policy;
- Router/Parallel Work validation evidence;
- limitations and next owner.

## Failure Modes

- Missing or invalid Parallel Work plan: route to Architect/Dispatcher.
- Team exceeds Router or plan capacity: `coordination_capacity_exceeded`.
- Agent/work-unit mismatch: `coordination_scope_mismatch`.
- High/critical quorum requested: `coordination_quorum_forbidden`.
- Concrete model selected in profile: `coordination_router_bypass`.
- Message transfers authority or expands scope: `coordination_message_rejected`.
- External provider required: route a separate owner-gated evaluation/implementation task.
