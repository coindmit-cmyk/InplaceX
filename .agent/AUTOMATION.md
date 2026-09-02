# Automation Route

Automation performs bounded, deterministic lifecycle work using existing Agent Control contracts.

- Load the project links and current Registry state.
- Require schema-valid input and explicit apply/authorization flags.
- Use the existing Model Resource Router for model and effort selection.
- Keep Task Manager, Dispatcher, Integrator and Finalizer authority unchanged.
- Default to dry-run and fail closed on stale state, conflicts, missing evidence or ambiguous authority.

## Fast Track

Use `automation_controller.py --mode fast-track` only for one exact, worker-ready
Worker Packet v2 with complexity `S`, no more than three allowed paths and no
more than three checks. Sensitive, security, locked, incomplete, dependent or
otherwise ineligible tasks return `route=standard_lifecycle` without execution.

Fast Track reuses the normal one-task worker cycle. It does not bypass claim,
isolated worktree, model routing, Integrator, Finalizer, entry preflight or
writer authority. Dry-run remains the default, and adding this route does not
enable a scheduler, autostart or unattended runner.

Input lifecycle collectors, analyzers and finalizers are a separate implementation module. Their future presence must not turn the input storage contract into a second scheduler.
