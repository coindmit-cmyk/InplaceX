# AiStudio Second Brain MVP

## Outcome

The Second Brain MVP turns project and customer conversations into reviewed,
source-backed memory without creating one global client knowledge pool.

```text
source event
  -> tenant/project scoped candidate
  -> secret and personal-data scan
  -> explicit review and consent gate
  -> active searchable memory
  -> cited retrieval plus audit
```

The runtime entrypoint is:

```text
scripts/agent_control/project_memory_engine.py
```

## Trust Boundaries

Every intake and retrieval request requires both `tenant_id` and `project_id`.
Database queries apply both values before access-level checks. The runtime has no
cross-tenant search command.

Access levels:

| Level | Retrieval rule |
| --- | --- |
| `project` | Member of the same tenant and project. |
| `user` | Matching subject user, project administrator or owner. |
| `internal` | AiStudio internal principal or owner only. |
| `owner_only` | Owner only. |
| `public` | Still constrained to the requested tenant and project. |

Customer ideas use `project` by default. A useful idea does not enter the shared
pattern library automatically. Promotion requires all of the following:

- the source item is active;
- `consent_state=granted`;
- the source is not classified as containing personal data;
- the promoted summary passes a second secret/PII scan;
- an owner supplies a consent reference and a de-identified summary.

The promoted item is written to the configured internal pattern-library tenant
with `access_level=internal`. Original raw customer content is not copied.

## Storage Tiers

The local-only configuration separates two paths:

| Storage | Content | Requirement |
| --- | --- | --- |
| Active SSD/NVMe | SQLite database, FTS index and audit | Reliable filesystem; low latency. |
| Bulk HDD/archive | Content-addressed raw source JSON | Separate from active database; backup source, not the only backup. |

The runtime refuses a configuration where `active_db` is under
`bulk_archive_root`. This preserves the current AiStudio policy that the exFAT
HDD must not host live databases.

The live configuration belongs at:

```text
runtime/agent-control/second-brain.local.json
```

It is local-only. Start from
`templates/agent-control/second_brain_config.example.json`; never commit the
resulting file, database, WAL files or raw client content.

Recommended remote AiStudio paths:

```text
active_db: /home/main/.local/state/aistudio/second-brain/second-brain.sqlite3
bulk_archive_root: /srv/aistudio-data/archive/second-brain
```

## Lifecycle

1. `intake` validates the source contract and creates a `candidate`.
2. Secret-like content is rejected before database or raw-blob persistence.
3. Personal data is classified; public access is rejected.
4. `review --decision approve` requires explicit consent or `not_required` for
   trusted project-owned material.
5. Only active, non-stale memory participates in retrieval.
6. Retrieval logs a SHA-256 of the query and result IDs, not the query text.
7. `forget` tombstones the database record, removes searchable content and
   deletes the raw archive blob.
8. `maintenance` is dry-run-first; with `--apply` it marks review-due records
   and deletes retention-expired content and raw blobs.

Rejected and stale items are never presented as current results.

## Operator Commands

### AiStudio host activation

Activation is dry-run-first and validates filesystem type, free space, expected
HDD mount and separate device ids before writing anything:

```bash
python scripts/agent_control/second_brain_activation.py \
  --project-root . \
  --config /home/main/.config/aistudio/second-brain.local.json \
  --active-db /home/main/.local/state/aistudio/second-brain/second-brain.sqlite3 \
  --archive-root /srv/aistudio-data/archive/second-brain \
  --expected-archive-mount /srv/aistudio-hdd \
  --json
```

After owner approval, repeat with `--apply --smoke`. The smoke creates only a
synthetic record, proves intake/review/retrieval/source/freshness/audit behavior,
checks the SQLite and raw blob device ids differ, then forgets the record and
verifies the raw blob is removed.

The activation command does not install a service, timer or recurring
maintenance job.

Initialize:

```powershell
python scripts/agent_control/project_memory_engine.py `
  --config runtime/agent-control/second-brain.local.json init
```

Intake a source event:

```powershell
python scripts/agent_control/project_memory_engine.py `
  --config runtime/agent-control/second-brain.local.json `
  intake --input intake.json --actor eshop-intake
```

Approve a candidate:

```powershell
python scripts/agent_control/project_memory_engine.py `
  --config runtime/agent-control/second-brain.local.json `
  review --tenant customer-acme --project eshop-storefront `
  --memory-id MEMORY_ID --decision approve --reviewer owner `
  --reason "source, consent and scope verified"
```

Retrieve:

```powershell
python scripts/agent_control/project_memory_engine.py `
  --config runtime/agent-control/second-brain.local.json `
  retrieve --tenant customer-acme --project eshop-storefront `
  --query "repeat delivery presets" --principal user-42
```

Forget:

```powershell
python scripts/agent_control/project_memory_engine.py `
  --config runtime/agent-control/second-brain.local.json `
  forget --tenant customer-acme --project eshop-storefront `
  --memory-id MEMORY_ID --requester privacy-admin `
  --reason "customer deletion request"
```

Review retention/freshness transitions, then apply the same report:

```powershell
python scripts/agent_control/project_memory_engine.py `
  --config runtime/agent-control/second-brain.local.json `
  maintenance --tenant customer-acme --project eshop-storefront `
  --actor retention-worker

python scripts/agent_control/project_memory_engine.py `
  --config runtime/agent-control/second-brain.local.json `
  maintenance --tenant customer-acme --project eshop-storefront `
  --actor retention-worker --apply
```

## MVP Limitations

- The scanner is a deterministic safety gate, not a complete DLP system.
- Retrieval is lexical SQLite FTS5 with a portable `LIKE` fallback; embeddings
  are deliberately deferred until isolation and evidence behavior are stable.
- Review is CLI/API-library based; there is no human review UI yet.
- Retention enforcement is available as a dry-run-first command, but recurring
  scheduling is deliberately not enabled without owner approval.
- The HDD is same-machine bulk storage and therefore not a sufficient backup by
  itself.
