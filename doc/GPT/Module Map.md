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

## Physical Status

Current repo status:

- all code still lives inside one Android app module
- logical separation exists in package structure, not yet in Gradle modules
