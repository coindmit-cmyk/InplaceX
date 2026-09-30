# InplaceX online contract schemas

This directory contains the machine-readable v1 contract for online identity,
cloud save, matchmaking, duel commands, snapshots, and WebSocket messages.

## Versioning

- `v1` is the wire contract version and is carried by `/api/v1`, the WebSocket
  subprotocol `inplacex.online.v1`, and `schemaVersion: "1.0"` in every JSON
  WebSocket envelope.
- A breaking change creates a new directory and schema `$id`. Additive fields
  remain optional and clients must ignore unknown fields.
- The schemas use JSON Schema Draft 2020-12. Relative `$ref` links are kept
  inside `schemas/online/v1` so the catalog can be validated offline.

## Catalog

- `v1/common.schema.json` — identifiers, timestamps, revisions, errors, game
  configuration, participant views, and redacted duel snapshots.
- `v1/rest.schema.json` — transport-neutral commands and REST response bodies.
- `v1/websocket.schema.json` — authenticated client envelopes, server event
  envelopes, event cursors, replay gaps, and heartbeat payloads.

HTTP headers, authentication requirements, idempotency, concurrency, reconnect,
backpressure, and secret ownership are normative in
[`InplaceX-docs/Backend/Online Contracts.md`](../../InplaceX-docs/Backend/Online%20Contracts.md).
