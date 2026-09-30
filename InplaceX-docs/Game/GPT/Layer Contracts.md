# Layer Contracts

## Allowed Dependencies

- `app/client -> game platform`
- `app/client -> game core`
- `game platform -> game core contracts only when needed`
- `game core -> no Android dependencies`

## Forbidden Dependencies

- `game core -> ui.*`
- `game core -> Android framework`
- `game platform -> concrete game screen internals`

## Rule of Change

If a change requires Android imports in game logic, the design is probably wrong.

If a new game mode requires copying the engine, the design is wrong.
