# InplaceX Project Map

`PROJECT_MAP.json` is the machine-readable authority. This document is the
human view used for orientation and task binding.

| Module ID | Area | Primary paths | Status |
| --- | --- | --- | --- |
| `inplacex.android-app` | Android application | `InplaceX-android/app/**` | implemented |
| `inplacex.bot-core` | Shared game/bot core | `InplaceX-bot-core/**` | implemented |
| `inplacex.backend` | Backend runtime | `InplaceX-backend/**` | implemented |
| `inplacex.logging` | Shared logging | `InplaceX-logging/**` | implemented |
| `inplacex.test-support` | Test infrastructure | `InplaceX-test-support/**` | existing support |
| `inplacex.docs-canonical` | Product/architecture authority | `InplaceX-docs/**` | existing support |
| `inplacex.docs-onboarding` | Root onboarding | `docs/**`, `README.md` | existing support |
| `inplacex.agent-rules` | Agent rules | `AGENTS.md`, `.agent/**` | existing support |
| `inplacex.build` | Gradle/build/scripts | root Gradle files, `gradle/**`, `scripts/**` | existing support |
| `inplacex.tests` | Verification | root and module test paths | existing support |
| `inplacex.root-metadata` | Root metadata | changelog, safe examples and map files | existing support |

## Boundaries

- Shared game and bot logic stays independent of Android and backend concerns.
- Canonical contract changes bind to `InplaceX-docs/**` as well as runtime code.
- Secrets, provider IDs and local machine configuration are not Project Map evidence and are never committed.
- A Project Map binding does not authorize release, provider activation or production mutation.
