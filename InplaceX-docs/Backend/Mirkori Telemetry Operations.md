# Mirkori trusted telemetry operations

Status: implemented, disabled by default, production activation owner-gated.

## Runtime contract

The InplaceX backend is the only telemetry authority. Android does not submit
trusted achievements or gameplay duration and never contains the Mirkori
game-server credential.

For every human Mirkori game profile in an authoritative online duel, the
backend records an ordered stream:

1. `start`, sequence `0`, when the duel becomes active;
2. `heartbeat` every four minutes while the duel remains active;
3. `end` with the next sequence when the duel finishes.

When a human wins, the same duel-state transaction also records the idempotent
achievement `first_win`. Mirkori Platform owns referral eligibility, award
limits and the final points decision; InplaceX only reports the authoritative
fact.

Migration `V11__add_mirkori_telemetry_outbox.sql` creates the gameplay tracker
and durable outbox. Event and platform-session IDs are deterministic hashes, so
a retry or command replay cannot mint a second fact. On every scheduled poll,
the worker drains a bounded batch of up to 100 available facts, claims each fact
with a lease, sends gameplay facts in sequence order and preserves the same event
ID after network timeout, HTTP 408/425/429 or HTTP 5xx. If backend shutdown
interrupts an active delivery, the worker releases that claim back to `pending`
without consuming an attempt. Permanent contract,
authentication and conflict errors are dead-lettered. If a gameplay predecessor
is dead-lettered, later events from that stream are also dead-lettered rather
than delivered out of order.

The outbox is recorded even while delivery is disabled. This preserves evidence
until the owner activates the connector. A long outage can make Platform mark a
gameplay stream as conflicting; that is intentional and routes duration-based
refund decisions to manual review instead of fabricating heartbeats.

## Configuration

Delivery exists only when all of these inputs are present:

```text
INPLACEX_MIRKORI_TELEMETRY_ENABLED=true
INPLACEX_MIRKORI_PLATFORM_BASE_URL=https://<platform-host>
INPLACEX_MIRKORI_GAME_ID=inplacex
INPLACEX_MIRKORI_GAME_CREDENTIAL_FILE=/run/secrets/inplacex_mirkori_game_credential
```

`INPLACEX_MIRKORI_GAME_ID` defaults to `inplacex`. The credential path must be
absolute and point to a regular non-symlink UTF-8 file containing exactly the
Platform game-server token without whitespace. The token must have the
`gameplay.write` authority for `inplacex`. Do not place the token in Compose,
GitHub variables, command arguments, logs, the repository or an Android build.

Cleartext is rejected except for an explicitly enabled loopback development
endpoint:

```text
INPLACEX_MIRKORI_ALLOW_CLEARTEXT_LOOPBACK=true
```

That switch is rejected in production.

## Owner activation gate

Production activation is not part of this code change. The owner must:

1. issue or rotate an `inplacex` game-server credential in Mirkori Platform;
2. install it as the external secret file with the service-user-only mode;
3. set the disabled-by-default environment values in the reviewed deployment
   manifest;
4. deploy through the normal immutable InplaceX release process;
5. verify that backlog rows move from `pending` to `delivered` and that no
   unexpected `dead` rows appear;
6. verify the matching Mirkori Platform achievement/gameplay audit records;
7. keep rollback available by setting
   `INPLACEX_MIRKORI_TELEMETRY_ENABLED=false` and restarting the backend. The
   outbox remains intact while delivery is disabled.

Never delete or rewrite pending/dead telemetry manually. Investigation and any
replay approval are manual operator actions because these facts may affect
referral points or duration-based refund eligibility.
