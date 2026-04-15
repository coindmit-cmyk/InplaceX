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

## Stability Rule

These types are the public planning surface. Prefer extending them instead of bypassing them with direct UI-level hardcodes.
