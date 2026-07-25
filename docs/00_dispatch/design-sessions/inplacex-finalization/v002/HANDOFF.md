# InplaceX finalization handoff v002

## Goal

Bring the game to a verified playable release without losing current work:

1. preserve and remotely publish foundation, baseline, and the fixes for
   validation feedback, latest-attempt scrolling, and evidence deduction;
2. split match state, rules, inference, persistence, and presentation into
   testable boundaries, completing `GameScreen` as the stateless renderer;
3. repair RU/EN localization and eliminate string-driven business logic;
4. add recreation, contradiction, SQLite migration, UI, lint, CI, release, and
   artifact identity gates;
5. implement an authoritative Ktor/PostgreSQL backend, Android REST/WebSocket
   adapters, staging deployment, and two-client E2E;
6. isolate debug and mock capabilities from release only after the owner test
   loop no longer depends on them;
7. continue the UX/UI redesign as vertical slices after the gameplay core is
   stable.

## Verified reality base

- Source content: `fix/gamefield-validation-scroll-deduction@2ee8252`.
- Owner-controlled remote: `https://github.com/coindmit-cmyk/InplaceX`.
- Upstream is retained as a read-only comparison remote.
- Agent Core adoption: `v0.4.22.327@7023d024b`.
- Android launcher requirement: Java 21.
- Gradle module toolchain requirement: Java 11.
- Current developer/debug controls remain available for owner testing.

## Non-negotiable routing

- Only `INPX-ARC-107` may restructure `GameFieldScreen.kt`.
- After `INPX-LOC-101`, feature workers edit their catalog, not the central
  localization aggregator.
- `INPX-DB-*` may not overlap an online/cloud-save worker that changes
  `data/local/**`.
- `CHANGELOG.md` and canonical architecture documentation are integrated by
  `INPX-DOC-901`, not edited by parallel workers.
- Every worker uses a separate clean worktree and returns a worker report with
  `integration_requested`.
- Failed mandatory checks produce `needs_worker_fix`, never partial
  integration.
- Physical phone/emulator access is serialized.
- VPS activation, DNS, TLS, firewall, signing, and real provider credentials
  require exact-target evidence and an owner/integrator gate.

## Initial parallel wave

After the remote managed checkout and toolchain pass:

- `INPX-ARC-101`: behavior characterization tests only;
- `INPX-ARC-102`: typed validation in bot-core;
- `INPX-ARC-103`: pure evidence/deduction engine;
- `INPX-LOC-101`: localization catalogs and parity tests;
- `INPX-DB-101`: SQLite test seam;
- `INPX-CI-301`: non-blocking CI foundation;
- `INPX-API-401`: API/WS/security contracts only.

These packets have disjoint production paths. Dispatcher must still run the
packet selection gate immediately before each claim.

## Release policy

Debug fixtures, secret visibility, mock purchase, and fake sign-in are kept
for current owner testing. They become release blockers in `INPX-REL-310` and
`INPX-REL-311`. Release must fail closed when signing credentials or production
provider configuration are absent; secrets are never committed.

## Completion definition

Completion requires green unit, lint, UI smoke, migration/recreation, backend,
online E2E, release assemble, signing/version identity, and physical-phone
acceptance evidence. A healthy staging VPS alone is not a finished game.
