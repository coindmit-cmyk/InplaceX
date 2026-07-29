# Public Interfaces

## Core

- `MatchEngine`
- `OpponentProvider`
- `GameModeDefinition`

## Platform

- `PlatformConfig`
- `BrandingConfig`
- `LocalizationProvider`
- `AdService`
- `AnalyticsService`
- `ProfileService`
- `SocialService`
- `NavigationDestination`
- `OnlineSession`
- `MatchmakingStub`
- `TransportBoundary`

## Online contract seam

Online interfaces bind to the versioned transport-neutral contracts in
[`Online Contracts v1`](../../Backend/Online%20Contracts.md):

- `OnlineSession` consumes and exposes an authoritative `DuelSnapshot`,
  revision, event cursor, reconnect result, and typed command errors.
- `MatchmakingStub` creates, reads, and cancels idempotent tickets; a matched
  ticket yields a server-owned session id.
- Friend invite operations create a bounded private code, allow one different
  authenticated player to accept it, and yield one server-owned human session.
- `TransportBoundary` maps REST and authenticated WebSocket frames to the same
  command, snapshot, and event types. It owns retry, token refresh, reconnect,
  and backpressure handling, not game rules.

Secrets, access tokens, refresh tokens, provider payloads, and server-only
scoring data are outside these public interfaces. A client receives only
submission status and redacted snapshots.

## Stability Rule

These types are the public planning surface. Prefer extending them instead of bypassing them with direct UI-level hardcodes.
