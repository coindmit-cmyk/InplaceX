# Action Report Contract

Status: Project Standard v2 contract

## Purpose

Every dry-run and apply action writes an auditable report. Dashboard, Command Bus and future Telegram controls read the same contract.

## Required Fields

- `action_id`
- `action_type`
- `project_id`
- `actor`
- `mode`
- `started_at`
- `finished_at`
- `input_refs`
- `before_state`
- `actions_planned`
- `actions_executed`
- `actions_skipped`
- `actions_failed`
- `affected_paths`
- `validation`
- `result`
- `next_owner`
- `next_action`

Blocked or failed reports without `next_owner` and `next_action` are invalid.

## Safety

Reports must not contain secrets. Paths and refs are evidence, not executable shell. Writes are atomic where supported.

## Schema

`schemas/agent-control/action_report.schema.json` defines the shared JSON report contract. Markdown summaries may accompany JSON reports, but JSON is authoritative.
