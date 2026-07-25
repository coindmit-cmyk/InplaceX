# Project Memory Engine

## Source Idea

Video intake from the Obsidian/Claude second-brain video.

The useful pattern is an explicit memory workspace with:

- a top-level agent instruction file;
- a stable folder taxonomy;
- separate facts, decisions, goals, tasks and notes;
- graph/search surfaces for discovery;
- a rule that new conversations write durable memory entries instead of relying on chat memory.

## Agent Core Adaptation

Agent Core must not create one global memory for all projects. Memory is project-scoped, time-aware and evidence-linked.

```text
raw source -> extracted memory entry -> review/status -> project memory card -> retrieval for future agents
```

## Memory Levels

| Level | Purpose |
| --- | --- |
| L0 project profile | Short project identity, owner goals and active constraints. |
| L1 decisions | Accepted decisions with date, owner and evidence. |
| L2 rejected ideas | Ideas explicitly not chosen, with reason and expiry review date. |
| L3 task context | Current tasks, blocked items and handoff notes. |
| L4 source notes | Raw or lightly processed notes from videos, chats, docs and PRs. |

## Required Entry Fields

Every memory entry records:

- `project_id`
- `memory_id`
- `memory_type`
- `status`
- `created_at`
- `summary`
- `facts`
- `decisions`
- `rejected_ideas`
- `source_refs`
- `evidence_refs`
- `freshness`
- `next_review_at`

## Retrieval Rule

Agents may retrieve memory only when it is relevant to the current project and task. Output must state which memory entries were used. Stale or rejected entries must not be presented as current decisions.

## Storage Layout

```text
AiStudio/ProjectMemory/
  project_profile.json
  entries/
  decisions/
  rejected_ideas/
  source_notes/
  retrieval_logs/
```

## Runtime MVP

The operational MVP is implemented by
`scripts/agent_control/project_memory_engine.py`. It adds:

- tenant and project scoped intake;
- candidate review before retrieval;
- SQLite FTS retrieval with a deterministic fallback;
- role/access checks and hashed-query retrieval audit;
- secret-like content rejection and personal-data classification;
- SSD/NVMe live database plus separate HDD/bulk raw-source archive;
- deletion of active content and archived raw blobs;
- explicit-consent, de-identified promotion into an internal pattern library.

Runtime databases, configuration and raw client content remain local-only and
must not be committed under `AiStudio/ProjectMemory/`.

## Backlog

1. Add evidence-gated automatic freshness transitions and expiry execution.
2. Add optional local embedding reranking after lexical retrieval is stable.
3. Integrate cited retrieval cards into Worker/Integrator prompts.
4. Add an operator UI for candidate review, consent and deletion requests.
5. Add optional Obsidian export/import after tenant and retention controls are proven.
