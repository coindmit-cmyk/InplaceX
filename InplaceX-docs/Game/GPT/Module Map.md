# Module Map

## Logical Layers

### game core

Primary packages:

- `core.engine`
- `core.match`
- `core.model`

Responsibilities:

- match lifecycle
- secret generation
- guess validation
- score calculation
- mode definitions
- opponent contracts

### game platform

Primary packages:

- `platform.config`
- `platform.localization`
- `platform.mirkori`
- `platform.navigation`
- `platform.online`
- `platform.services`

Responsibilities:

- reusable app/platform contracts
- central configuration
- feature flags
- localization abstraction
- service boundaries
- global Mirkori Games account bootstrap, protected state, and browser login

### app/client

Primary packages:

- `ui.*`
- `MainActivity`

Responsibilities:

- Android composition
- rendering
- user interaction
- platform + game integration

### backend/runtime

Primary packages:

- `backend.bot`
- future `backend.match.*`
- future `backend.transport.*`

Responsibilities:

- server-side bot player runtime
- match participant adapters
- room/session orchestration
- backend-side PvP flow support

## Physical Status

Current repo status:

- shared bot logic now lives in `InplaceX-bot-core`
- shared logging contract now lives in `InplaceX-logging`
- shared test-only sinks and helpers now live in `InplaceX-test-support`
- server-side bot adapter now lives in `InplaceX-backend`
- Android client lives in `InplaceX-android:app`
- the reviewed `Mirkori-platform-game-sdk` source snapshot provides the
  transport-neutral cross-game identity client without cross-repository CI
  credentials
- bot rules, bot agent, grid catalog, solver facade, score calculator, validator, secret generator, and `GameConfig` are now physically shared through the bot-core module
- pure match lifecycle/contracts, mode definitions, evidence deduction, and
  campaign generation/rating/progression are physically shared through the
  bot-core module
- test runners and test sinks should use the shared test-support module instead of ad-hoc local helpers
- UI, platform services, and Android rendering remain inside the Android app module
- backend matchmaking, private invites, authoritative sessions, membership,
  persistence, identity routes, REST/WebSocket transport, and bot
  participation live in the backend module
