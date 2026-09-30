---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/ads/04_reward_verification_flow.md
version: v1
date: 2026-04-16
---

# Reward verification flow

## Strict flow

Использовать всегда, когда provider поддерживает server-side verification.

### Steps
1. Client -> backend: create reward session
2. Backend -> client: provider + providerPayload
3. Client -> provider SDK: show rewarded ad
4. Provider -> backend callback: provider verification
5. Client -> backend: client-complete
6. Backend marks session `granted`
7. Client polls `GET /ads/reward-sessions/{id}`
8. UI updates after `status = granted`

## Soft flow

Использовать только как fallback для provider without strict SSV in current integration.

### Steps
1. create reward session
2. show ad
3. client-complete
4. backend marks `completedClient`
5. backend may require extra anti-abuse checks
6. backend grants only low-value reward types
7. client polls final status

## State machine

```text
created
  -> shown
  -> completedClient
  -> verifiedProvider
  -> granted

created
  -> shown
  -> cancelled/rejected

created
  -> expired
```

## Hard rules

- one reward session -> one reward grant maximum
- reward session has TTL
- duplicate provider callback must be idempotent
- duplicate client-complete must be idempotent
- reward ledger update and session state update must be transactional

## Suggested anti-abuse checks

- player authenticated
- active reward session not expired
- integrity token on sensitive flows
- provider transaction unique
- per-device / per-account rate limits
