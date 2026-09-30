# Rebuild And Integration Decision Table

This table is the control-layer contract for routing Integrator and rebuild
outputs. It keeps safe work moving while routing bad or ambiguous work to the
right owner.

| Input condition | Route | Next owner | Next event |
| --- | --- | --- | --- |
| `ready_candidate` with task identity | `ready_candidate` | `auto-integrator` | `integration_requested` |
| `ready_candidate` without task identity but with safe evidence | `integrator_identity_recovery` | `auto-integrator` | `integration_requested` |
| safe broad/module package | `integrator_module_batch` | `auto-integrator` | `integration_requested` |
| `ready_to_finalize` / handoff ready | `ready_to_finalize` | `auto-finalizer` | `finalization_requested` |
| `cleanup_candidate`, duplicate, stale, coordination-only | `cleanup_candidate` | `cleanup_script` | `cleanup_requested` |
| auto clean rebuild plan item | `crb_auto_task` | `auto-dispatcher` | `crb_task_created` |
| `needs_clean_rebuild` + one task + bounded safe paths | `crb_auto_task` | `auto-dispatcher` | `crb_task_created` |
| `needs_clean_rebuild` + safe broad/multi-module evidence | `integrator_module_batch` | `auto-integrator` | `integration_requested` |
| `needs_clean_rebuild` + ambiguous/no evidence | `dispatcher_rebuild` | `auto-dispatcher` | `dispatcher_rebuild_requested` |
| metadata-only `needs_dispatcher` + safe changed paths | `integrator_identity_recovery` or `integrator_module_batch` | `auto-integrator` | `integration_requested` |
| no task id + coordination-only | `cleanup_consumed` | `cleanup_script` | `cleanup_requested` |
| no task id + low-risk docs-only | `provisional_crb` | `auto-dispatcher` | `provisional_crb_requested` |
| no task id + safe bounded product payload | `integrator_identity_recovery` | `auto-integrator` | `integration_requested` |
| no task id + safe broad product payload | `integrator_module_batch` | `auto-integrator` | `integration_requested` |
| no task id + no branch/PR/path evidence | `dispatcher_rebuild` | `auto-dispatcher` | `dispatcher_rebuild_requested` |
| no task id + high risk | `needs_human` | `human` | `needs_human_created` |
| forbidden/high-risk/security/deploy/payment paths | `needs_human` | `human` | `needs_human_created` |
| worker result defect | `needs_worker_fix` | `worker` | `worker_fix_requested` |
| integration repair needed | `needs_rework` | `auto-integrator` | `integration_rework_requested` |

## Hard Rules

```text
no task_id -> Integrator metadata recovery when branch/PR/path evidence is safe
package size -> not a blocker by itself
no worker report -> never ready_to_finalize
high risk -> no local LLM auto-ready
cleanup candidate -> no deletion without cleanup/finalizer policy
```

Integrator may assign a provisional identity or source artifact id before
building a package branch. It must not mark the work final without recovered
identity/evidence in the handoff.

## Provisional CRB

Used only when the item is useful backlog/documentation but is not integration
work yet:

```text
no task_id
AND low risk
AND docs-only or coordination payload
AND no protected/secrets paths
```

The provisional row is created as `needs_task_packet`; workers cannot claim it
until Dispatcher completes the packet.
