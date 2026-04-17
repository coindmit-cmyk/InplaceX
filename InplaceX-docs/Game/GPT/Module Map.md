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
- `platform.navigation`
- `platform.online`
- `platform.services`

Responsibilities:

- reusable app/platform contracts
- central configuration
- feature flags
- localization abstraction
- service boundaries

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
- server-side bot adapter now lives in `InplaceX-backend`
- Android client lives in `InplaceX-android:app`
- bot rules, bot agent, grid catalog, solver facade, score calculator, validator, secret generator, and `GameConfig` are now physically shared through the bot-core module
- UI, platform services, and Android rendering remain inside the Android app module
- backend matchmaking, persistence, and transport layers are still future work, but the bot participant contract is now started in the backend module
