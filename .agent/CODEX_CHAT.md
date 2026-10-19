<!-- luna-pipeline-context:start -->
## Active execution policy — 2026-09-30

Use the script + Luna pipeline for implementation; Luna diagnoses failures and
Sol is a bounded fallback. Start from `.agent/PIPELINE_CONTEXT.md` only, then the
assigned task and relevant project facts. Do not preload AiStudio, all `.agent/`,
skill catalogs, earlier chats, role procedures or complete task registries into models.
The host reads the existing task authority and gives models only the selected task.
For each call pass only the role-specific input described in that short document.
This owner decision supersedes older mandatory bootstrap/reading, chat-only and
multi-role procedure requirements below; retain product rules, privacy, hardware
gates, paused work, existing task authority and unrelated changes. Do not activate
timers, migrate queues, publish externally or start additional work by adopting this policy.
<!-- luna-pipeline-context:end -->

# Codex Chat Route

Use Codex Chat for repository-grounded execution of accepted, authorized scope: implementation, focused tests, commits, PRs and evidence.

Before editing, verify the accepted scope against current `develop`, project links and owner directives. Keep the change bounded to the selected module.

Work discovered inside the accepted scope may be handled in the same PR. A new defect, idea, reusable component, architecture conflict or adjacent improvement outside that scope becomes a separate package under:

```text
AiStudio/Project_state/input/Codex/PR-<number>-<topic>/
```

If the boundary is ambiguous, route the finding to input rather than silently expanding the implementation.

Codex does not edit `ACTIVE_REGISTRY.json` or `HISTORY.jsonl` as part of an input package PR. Lifecycle state belongs to the later deterministic input automation.

## Minimal First Execution

1. Define the smallest contract as `input → expected output`.
2. Execute a local, unambiguous task directly.
3. Touch only required files and run one to three contract tests.
4. Do not start architecture analysis, full review, scanners or repair without a concrete failure, conflict or risk.
5. Add complexity only after that reason is recorded.
6. Stop after the accepted scope is complete; do not create follow-up work independently.

Preserve existing material: add or make the smallest required change instead of deleting or rewriting unrelated content.
