# Manual Changes Protection

Manual commits, manual PRs and unexpected local changes are protected state.

Dispatcher and workers must check:

```text
open PRs
recent commits
changed paths
task_queue.json
agent_locks.json
related task pages
```

If an open PR or manual edit touches the same path, skip, block or ask Human-needed Chat.

Workers must not overwrite or revert manual changes.
