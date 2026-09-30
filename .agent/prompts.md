# InplaceX Task Prompts

## Add Or Change A Game Mode

Read first:

- `InplaceX-docs/Game/GPT/Add New Mode.md`
- `InplaceX-docs/Game/GPT/Match Domain Model.md`
- `InplaceX-docs/Game/GPT/Module Map.md`

Implementation rule: extend `GameModeDefinition`, `OpponentProvider`, shell/UX configuration, and orchestration. Do not copy the match engine.

## Change Bot Behavior

Read first:

- `InplaceX-docs/Game/GPT/Bot Brain.md`
- `InplaceX-docs/Game/GPT/Server Bot Runtime.md`

Implementation rule: place shared bot logic in `BotAgent` / `InplaceX-bot-core`; use `BotSolver` only as a compatibility surface.

## Change Provider, Ads, Billing, Or Auth Setup

Read first:

- `InplaceX-docs/Game/GPT/Provider Runtime Config.md`
- `InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md`
- `InplaceX-android/provider-config.example.properties`

Implementation rule: never commit real provider ids or secrets.

## Change Android Shell Or Screen UI

Read first:

- `InplaceX-docs/Game/Human/Shell UI.md`
- `InplaceX-docs/Game/GPT/Shell Appearance Contracts.md`
- relevant screen files under `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/`

Implementation rule: keep shell behavior centralized and avoid duplicating layout constants in screens.

## Change Backend Bot Runtime

Read first:

- `InplaceX-docs/Game/GPT/Server Bot Runtime.md`
- `InplaceX-backend/README.md`

Implementation rule: preserve backend snapshot safety and pending-turn idempotency.
