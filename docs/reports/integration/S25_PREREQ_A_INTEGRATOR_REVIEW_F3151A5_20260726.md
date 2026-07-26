# S25 prerequisite A integrator review: f3151a5

Mode: `ManualIntegrationMode`

Disposition: `rejected`

Candidate `f3151a5cb058aba3a435a219b0acb57c35573de1` is preserved on its worker
branch and must not be integrated into `develop`.

## Blocking finding: reflected identity grant forges an accepted token

The candidate keeps a mutable private `identityGrants` registry and a reachable
private access-token issuance path in the same unrestricted JVM reflection
surface.

An isolated hostile test:

1. reflected the private `identityGrants` map;
2. registered a reflected `IdentityTokenGrant` for a caller-selected existing
   subject;
3. invoked the reachable private `issueAccessToken` path; and
4. passed the resulting JWT to canonical authentication.

Canonical authentication accepted the forged token (`FORGED_ACCEPTED`).
Private Kotlin/JVM visibility therefore does not establish an identity-owned
issuance authority under this task's hostile reflection contract.

The candidate also added a JDBC active-player existence query despite the
auth-only, no-persistence acceptance boundary and its own worker report.

## Required retry contract

- Keep the retry auth-only. Do not add session membership, JDBC, schema,
  transport, route, duel or other persistence implementation.
- Salvage only strict JWT parsing, malformed UTF-8 rejection and redaction
  ideas from the candidate.
- Enumerate and actively mutate private fields, maps, constructors, methods,
  synthetic/default bridges and every reachable issuer object.
- Add a hostile test that registers or mutates every reachable grant/issuer
  registry with a caller-selected existing subject and proves no resulting
  token is accepted by canonical authentication.
- Do not expose caller-owned `SessionMembershipPort`, resolver factories or
  concrete membership authority in this prerequisite. Production membership
  composition belongs to S25B.
- If the unrestricted-reflection contract cannot be satisfied inside one
  ordinary JVM module, return `needs_architect` with a concrete JVM
  module/composition boundary. Do not repeat another mutable private registry.

## Evidence

- Candidate: `f3151a5cb058aba3a435a219b0acb57c35573de1`
- Candidate branch:
  `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-authent-retry-20260726T131440Z`
- Isolated review worktree:
  `D:\Work\DevOps\MobileGame\InplaceX-worktrees\auth-f3151a5-review-20260726`
- Backend tests and `verifyProject`: passed before hostile reflection
- Hostile result: `FORGED_ACCEPTED`
- Persistence mismatch: JDBC existence query added outside the accepted
  auth-only boundary
