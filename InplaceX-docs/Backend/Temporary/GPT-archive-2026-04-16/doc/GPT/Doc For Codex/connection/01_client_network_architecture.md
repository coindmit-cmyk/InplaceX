---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/connection/01_client_network_architecture.md
version: v1
date: 2026-04-16
---

# Client network architecture

## Canonical Android stack

### HTTP
- Retrofit
- OkHttp
- Kotlin serialization or Moshi

### WebSocket
- OkHttp WebSocket
or
- Ktor client WebSocket

For MVP choose one and keep it isolated behind interface.

## Package layout

```text
app/src/main/java/com/mirkori/inplacex/
  network/
    api/
      AuthApi.kt
      ConfigApi.kt
      ProgressApi.kt
      AdsApi.kt
      InplaceXApi.kt
    auth/
      TokenStore.kt
      SessionRepository.kt
      AuthInterceptor.kt
      TokenAuthenticator.kt
    ws/
      RealtimeClient.kt
      RealtimeEnvelope.kt
      RealtimeDispatcher.kt
  sync/
    SyncCoordinator.kt
    ProgressConflictResolver.kt
  config/
    AppConfigRepository.kt
  integrity/
    PlayIntegrityGateway.kt
```

## Responsibilities

### network/api
Only DTO + Retrofit interfaces.

### network/auth
Only tokens and auth transport plumbing.

### network/ws
Only realtime transport and event dispatch.

### sync
Only cloud synchronization rules.

### config
Only app config fetch/cache.

## Hard rules

- UI does not call Retrofit directly
- ViewModel does not store refresh token
- auth refresh is centralized
- websocket reconnect is centralized
- base URL and ws URL come from config/env, not hardcoded in screens
