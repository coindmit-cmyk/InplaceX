---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/connection/04_failures_retries_integrity.md
version: v1
date: 2026-04-16
---

# Failures, retries, integrity

## Failure classes

Клиент должен различать:
- offline
- DNS/connect failure
- timeout
- 401/403 auth failure
- 409 conflict
- 429 rate limit
- provider error
- no fill
- cancelled by user

## Retry policy

### Safe to retry automatically
- GET requests
- config fetch
- room snapshot
- reward status polling

### Retry with idempotency key
- create reward session
- client-complete reward session
- submit turn
- progress update

### Do not blind-retry
- auth/link provider
- operations with expired session
- user-cancelled ad flow

## Local outbox

Для нестабильной сети полезен local outbox для:
- progress update
- reward status poll request scheduling
- analytics events

Но не хранить там “виртуальную выданную награду”.
Пока backend не подтвердил `granted`, UI не должен считать награду полученной.

## Integrity

Sensitive operations:
- reward creation
- reward completion
- progress write
- room join
- turn submit

Для них допустимо требовать integrity token.
Вызов integrity API должен быть отдельным gateway, а не размазан по экранам.

## Session recovery

Если access token истёк:
1. central authenticator refreshes token
2. original request retries once
3. if refresh failed -> session invalid -> route to auth restore

## Reconnect

После websocket reconnect:
- resubscribe room
- request room snapshot
- reconcile local state with server snapshot

## UX rule

Плохая сеть не должна ломать core gameplay loop.
Если онлайн-операция невозможна:
- показать controlled error
- не крашить экран
- не засчитывать непонятные состояния как успех
