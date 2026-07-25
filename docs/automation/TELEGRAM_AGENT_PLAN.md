# Telegram Agent Plan

Telegram is a conversational operator for AiStudio. It must use the common Control API and Command Bus. It must not run shell directly.

Implementation starts only after Command Bus acceptance.

## Planned Role Files

- `agent-core/.agent/roles/telegram-operator.md`
- `agent-core/.agent/prompts/telegram-operator.md`

## Intent Classes

- `status_query`
- `task_query`
- `log_query`
- `error_explanation`
- `project_question`
- `draft_task`
- `run_task`
- `run_role`
- `pause_resume`
- `approval_request`

## Safety

Owner allowlist, explicit project resolution, confirmation for write actions, second confirmation for high-risk actions, redaction and chunked replies, and audited commands for every write action.
