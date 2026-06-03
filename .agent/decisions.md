# InplaceX Architecture Decisions

## ADR-0001: Documentation-First And Layered Platform

- Status: accepted.
- Source: `InplaceX-docs/Game/GPT/ADR/ADR-0001-documentation-first-and-layered-platform.md`.
- Decision: use documentation as the source of truth and describe the system through game core, game platform, and app/client layers.
- Consequence: public contracts and canonical docs should be updated with meaningful architecture changes.

## Current Physical Split

- `InplaceX-bot-core` physically owns shared bot and game logic that must remain transport-agnostic.
- `InplaceX-backend` owns backend/runtime adapters and must not push backend concerns back into shared bot-core.
- `InplaceX-android/app` owns Android composition, UI, shell, resources, and integration wiring.

## Root Documentation Decision

- Root `docs/` is an onboarding layer only.
- `InplaceX-docs/` remains the canonical documentation location.
- Avoid copying long canonical docs into root docs; link and summarize instead.
