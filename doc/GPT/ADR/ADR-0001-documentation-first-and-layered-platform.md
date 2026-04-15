# ADR-0001: Documentation-First And Layered Platform

## Status

Accepted

## Decision

Use documentation as the main source of truth and describe the system through three layers:

- game core
- game platform
- app/client

Keep Android as the first execution target, but design contracts so web and other clients remain feasible later.

## Consequences

- docs are canonical and must be updated with major architecture changes
- public contracts should exist before deep feature expansion
- central config is preferred over repeated screen-level hardcode
- backend/online is introduced through interfaces before full implementation
