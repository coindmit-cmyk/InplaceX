# InplaceX Agent Guide

This file is the entry point for AI agents working in this repository.

## Required Reading

Before changing files, read:

1. `.agent/general.md`
2. `.agent/project.md`
3. `.agent/modules.md`
4. `.agent/workflows.md`

When the task touches architecture, public contracts, game modes, provider setup, or bot behavior, also read the matching canonical documents under `InplaceX-docs/Game/GPT/`.

## Rule Priority

Use this priority order when instructions conflict:

1. System and safety instructions.
2. The current user request in chat.
3. Project-specific rules from `.agent/project.md`.
4. General rules from `.agent/general.md`.
5. Supporting context from the rest of `.agent/` and `InplaceX-docs/`.

If a conflict can affect architecture, user data, secrets, production safety, or existing user changes, ask a concise clarifying question before proceeding.

## Repository Shape

InplaceX is a Kotlin/Gradle multi-module project:

- `InplaceX-android/app` is the Android client and current app runtime.
- `InplaceX-bot-core` is shared, transport-agnostic bot and game logic.
- `InplaceX-logging` is the shared logging contract and sanitization module.
- `InplaceX-backend` is the backend-facing JVM runtime layer, currently centered on server-side bot participation.
- `InplaceX-docs` is the canonical product and architecture documentation set.

Run root Gradle commands from the repository root unless a task explicitly requires a module directory.

## Collaboration Rules

- Respond to the project owner in Russian.
- Protect uncommitted user changes. Never revert or delete them unless explicitly asked.
- Keep changes scoped to the requested behavior and the responsible module.
- Add logging and tests alongside code when behavior crosses module, integration, runtime, or user-visible boundaries.
- Update practical documentation when behavior, architecture, setup, public contracts, or workflows change.
- Add or update tests for new behavior and bug fixes when practical.
