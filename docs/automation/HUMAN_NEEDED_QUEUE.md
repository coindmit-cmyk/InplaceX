# Human Needed Queue

Human-needed lane handles owner decisions and manual verification.

It handles:

- `needs_human` tasks;
- product decisions;
- provider choices;
- MVP/release scope;
- manual QA;
- PR acceptance or rejection.

Workers must not guess owner decisions.

## Decision Packet

```json
{
  "decision_id": "DEC-001",
  "related_task": "TASK-001",
  "question": "What should the owner decide?",
  "options": ["option-a", "option-b"],
  "recommended_default": "option-a",
  "impact": "Explains what this decision affects",
  "required_by": "before assigning TASK-001"
}
```
