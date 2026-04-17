# InplaceX Backend

This module now contains the first backend-facing runtime contract for PvP bots.

Planned responsibilities:

- player profile sync
- cloud save
- PvP matchmaking
- server-side bot player runtime
- rankings and seasonal progression
- entitlement validation for ads, Pro, and Pro+

Current state:

- JVM Gradle module
- `ServerBotPlayer` wraps the shared `InplaceX-bot-core` brain as a backend match participant
- future transport, matchmaking, persistence, and room/session code will build around this module
