# Task Traceability Contract

Every executable task should be traceable:

```text
Task ID -> GitHub Issue(s) -> task_queue.json -> lock -> source role/chat -> base branch -> branch -> commit(s) -> draft PR -> integration result -> finalizer gate / merge / owner decision -> finalization result -> done
```

## GitHub Visibility Requirement

Task state is durable only after the relevant branch, commit or report is pushed to GitHub.

Agents must not treat local-only commits, local-only reports or unpushed queue edits as completed work. If work cannot be pushed, record `sync_blocked` or an equivalent blocker with:

- local branch;
- local commit SHA, if any;
- files changed;
- checks run;
- push command attempted;
- exact error or missing credential/network condition.

The task may move to `review`, `agent_done`, `owner_approved` or `done` only when the corresponding pushed GitHub evidence exists.

## Suggested Fields

```json
{
  "id": "TASK-001",
  "source_lane": "gpt-architect",
  "source_chat": "architect",
  "base_branch": "develop",
  "github_issue": 1,
  "related_issues": [1],
  "github_pr": 2,
  "machine_id": "ubuntu-agent-server",
  "worker_id": "auto-worker-5.5",
  "github_branch": "remote/ubuntu-agent-server/auto-worker-5-5/TASK-001-example",
  "integration_status": "integration_ready",
  "integration_report": null,
  "finalization_status": "pending_finalizer_gate",
  "finalization_report": null,
  "commits": [],
  "changed_paths": [".agent/**", "docs/automation/**"],
  "status": "review",
  "status_reason": "draft PR is open",
  "last_agent": "auto-worker-5.5",
  "last_agent_report": null,
  "previous_complexity": null,
  "return_to_dispatcher_count": 0,
  "derived_from": null,
  "retry_after_split": false,
  "handoff_note": null,
  "owner_review_required": false
}
```

Codex must not mark final `done` without accepted-state evidence. Auto Finalizer may record `done` only when finalizer-gate, merge or owner-approval evidence exists and the project workflow allows it.
