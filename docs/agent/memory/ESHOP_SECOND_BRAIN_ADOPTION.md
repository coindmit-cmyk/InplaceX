# E-SHOP Second Brain Adoption Contract

## Boundary

E-SHOP is a source and consumer of project memory. It does not own the shared
memory engine, bypass review, or query other tenants.

Adoption must go through the AiStudio update/adoption flow. Do not copy runtime
databases, local configs or customer raw content into the E-SHOP repository.

## Source Event Mapping

An E-SHOP conversation or approved product signal maps to
`second_brain_intake.schema.json` as follows:

| E-SHOP field | Second Brain field |
| --- | --- |
| Organization/account stable id | `tenant_id` |
| Store/site/project stable id | `project_id` |
| Customer stable id, when needed | `subject_user_id` |
| Message or insight summary | `summary` |
| Conversation/message URI | `source_refs[]` |
| Supporting analytics/order evidence | `evidence_refs[]` |
| Customer sharing choice | `consent_state` |
| Stable event id | `idempotency_key` |

Never derive tenant scope from free-form chat text. Tenant and project identity
must come from the authenticated E-SHOP request context.

## Required E-SHOP Gates

- opt-in or contract-defined consent before approval;
- server-side tenant/project identity, not client-supplied scope alone;
- source reference without embedding secrets or session tokens;
- idempotent event delivery;
- candidate status visible to an authorized reviewer;
- deletion propagation to Second Brain `forget`;
- no direct pattern-library writes;
- no retrieval result without memory IDs, source references and freshness.

## Phased Adoption

1. Shadow intake: produce and validate events without persistence.
2. Project-only persistence: review candidates, retrieval disabled in customer UX.
3. Internal retrieval: staff/agents consume cited project-local results.
4. Customer retrieval: enable only after authorization and deletion tests pass.
5. Pattern promotion: owner-only, explicitly consented and de-identified.

The AiStudio MVP completes the shared engine and contract. E-SHOP implementation
remains a separate repository change after its current branch and Agent Core
version are verified.
