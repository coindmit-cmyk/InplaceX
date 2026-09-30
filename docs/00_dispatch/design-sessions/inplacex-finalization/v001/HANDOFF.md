# InplaceX Finalization Design Handoff

status: blocked_prerequisites
type: design_package
next_owner: Dispatcher

## Package

- package_id: PD-INPLACEX-FINAL-20260725-v001
- project_id: InplaceX
- package_version: 1.0.0-design
- repository: GoodEvil11/InplaceX
- reality_base_ref: feature/project-foundation
- reality_base_commit: 4ba40978d06e6bc3d9ce2d4a6e97bd7f287e921a
- planning_backlog: backlog/backlog.json
- implementation_started: true
- current_manual_slice: feature/ui-redesign-foundation

## Preservation Evidence

- The source working tree was clean before work.
- Baseline tag: `baseline/pre-redesign-2026-07-25`.
- Verified local bundle: `InplaceX-pre-redesign-2026-07-25.bundle`.
- `verifyProject` and `assembleDebug` passed against the baseline.
- Current source is not present on `origin/master`.
- Remote push is blocked because the active GitHub identity has read-only access.

## Blocking Prerequisites

1. Grant push access to `GoodEvil11/InplaceX` or select an owner-approved canonical fork.
2. Publish `feature/project-foundation` and the baseline tag without rewriting history.
3. Choose and create canonical remote `develop` and `production` lines from the preserved source.
4. Materialize the exact Git ref on the AiStudio Linux PC.
5. Install or point Gradle at JDK 11 and validate Android SDK/ADB paths on the worker host.
6. Run both queue readiness and Dispatcher decision guards before any Worker claim.

Do not launch unattended Workers before these prerequisites are satisfied.

## Owner Intent

- Redesign the Android game without losing existing behavior or assets.
- Complete local, bot, campaign, monetization, profile, and online product paths.
- Build the online service on an available VPS.
- Use AiStudio to split, execute, integrate, and verify bounded tasks.
- Preserve current match contracts and two-field (`Duel`, `Race`) architecture.

## Dispatcher Instructions

1. Refresh the canonical Git ref and compare it with `reality_base_commit`.
2. Import accepted candidates only as `planned, worker_ready:false`.
3. Split any `XL` candidate before Worker pickup.
4. Keep UI, domain convergence, backend, VPS, and release work in separate worktrees.
5. Require tests, logging, docs, and preservation evidence in every production packet.
6. Never place secrets, provider ids, signing material, or VPS credentials in task packets.
7. Treat VPS activation, DNS, TLS, firewall, and production data migration as separate owner-approved operations.

## Boundary

This handoff is a planning package. It does not authorize queue mutation, Worker launch, integration, deployment, credential changes, or production traffic.
