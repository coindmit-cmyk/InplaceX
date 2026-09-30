# General Agent Rules

These rules apply to AI work in this repository unless a more specific project rule overrides them.

## Communication

- Reply to the project owner in Russian unless they explicitly request another language.
- Human-facing documentation should be written in Russian.
- Machine-oriented agent instructions, structured contracts, schemas, and context files should be written in English.
- Code comments should be rare and useful. In this project, write necessary code comments in Russian.

## Before Changes

- Inspect the existing structure before editing.
- Prefer `rg` / `rg --files` for repository search.
- Read the relevant documentation before changing documented behavior or architecture.
- Do not remove, reset, or overwrite user changes without explicit permission.
- If unrelated files are dirty, leave them alone.

## Architecture

- Prefer the repository's existing module boundaries and local patterns.
- Put behavior in the module that owns it instead of wiring fixes around it at the UI edge.
- Avoid new abstractions unless they reduce real duplication, clarify responsibility, or match an existing pattern.
- Keep public inputs, outputs, dependencies, and side effects clear for each important module.

## Documentation

- Update documentation when a change affects behavior, architecture, public contracts, setup, testing, or user workflows.
- Keep documentation practical: how to run, how to verify, where code lives, and what constraints matter.
- Use `CHANGELOG.md` for meaningful changes that affect architecture, setup, workflows, testing, public contracts, or user-visible behavior.

## Tests And Verification

- Add or update tests with new behavior and bug fixes when practical.
- Test through public module boundaries rather than private implementation details.
- Run the narrowest relevant verification first, then broader checks when the blast radius justifies it.
- If verification cannot be run, explain why in the final response.

## Logging And Secrets

- Add useful logging only for important operations, integration boundaries, errors, and state transitions.
- Do not log secrets, tokens, passwords, private keys, cookies, personal data, or other sensitive values.
- Do not commit local provider ids, credentials, generated diagnostics, heap dumps, screenshots, or local machine configuration.

## Dev/Test Tools

- Keep dev/test/debug tools separate from normal user-facing features.
- Such tools must be clearly marked or isolated through build types, environment/config flags, separate commands, internal screens, or test-only code paths.
- Production/final builds must not expose debug panels, test routes, mock data, seed data, unsafe commands, or diagnostic screens.
