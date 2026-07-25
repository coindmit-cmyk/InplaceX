# Dispatcher Rebuild Layer

The Dispatcher rebuild layer makes `needs_dispatcher_rebuild` actionable.

## Scripts

```text
rebuild_decision_classifier.py
dispatcher_rebuild_planner.py
provisional_crb_task_builder.py
route_rebuild_and_integration_results.py
```

## Contract

Dispatcher rebuild is for ambiguous or under-evidenced items. It is not the
default route for every missing `task_id`.

`dispatcher_rebuild_planner.py` writes:

```text
docs/plans/dispatcher_rebuild_plan.json
docs/plans/reports/DISPATCHER_REBUILD_PLAN_<date>.md
```

`provisional_crb_task_builder.py --apply` may append low-risk provisional rows
to `AiStudio/Task_manager/task_queue.json`, but those rows stay `needs_task_packet`.

## Not Allowed

- merging product code;
- marking tasks done;
- finalizing no-task-id product branches without Integrator identity recovery;
- deleting branches without cleanup/finalizer policy.
