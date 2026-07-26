# S25 prerequisite A integrator review: d2ef53c

Mode: `ManualIntegrationMode`

Disposition: `rejected`

Candidate `d2ef53c6a670f41717549db82b90a23f8584bc5e` is preserved on its worker
branch and must not be integrated into `develop`.

## Blocking finding: caller-owned membership authority

The candidate pins a principal to the canonical authentication authority, but
the membership boundary remains constructible by arbitrary JVM code:

- `GuestIdentityService.authenticationAuthority()` exposes the canonical
  `JwtAccessTokenService`;
- `SessionMembershipResolver` publicly accepts an arbitrary
  `SessionMembershipPort` plus that authority;
- `SessionMembershipRecord` publicly accepts session, participant and player
  identifiers.

An in-process caller with a genuine authenticated principal can supply a
caller-owned membership port whose record uses the genuine player id and an
attacker-selected participant id. The resulting resolver mints a valid
session-participant capability for the attacker-selected participant.

An isolated hostile test constructed this exact resolver and returned
`PARTICIPANT_B` for the genuine `PLAYER_A` principal. The focused backend test
completed successfully, proving the bypass is accepted by candidate code.

## Required retry contract

- The canonical membership repository, resolver and capability issuer must be
  owned and pinned by a non-exported session authorization authority.
- Arbitrary JVM code must not be able to supply a membership port or construct
  a resolver whose output is accepted by downstream authorization.
- Keep route-bound session id and authenticated principal as inputs; never
  accept participant id from caller payload or caller-owned records.
- Add a hostile test using the real authentication authority plus a
  caller-owned membership port and attacker-selected participant id; capability
  issuance must fail.
- Preserve prior token-issuer, synthetic-constructor, cross-authority and
  redaction gates.

## Evidence

- Candidate: `d2ef53c6a670f41717549db82b90a23f8584bc5e`
- Candidate branch:
  `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-princip-retry-20260726T111228Z`
- Isolated review worktree:
  `D:\Work\DevOps\MobileGame\InplaceX-worktrees\s25-d2ef-review-20260726`
- Focused hostile test:
  `caller owned membership port can forge participant capability with canonical authority`
- Command:
  `gradlew.bat :InplaceX-backend:test --tests '*caller owned membership port can forge participant capability with canonical authority' --rerun-tasks`
- Result: build succeeded, demonstrating the unauthorized capability was
  minted.
