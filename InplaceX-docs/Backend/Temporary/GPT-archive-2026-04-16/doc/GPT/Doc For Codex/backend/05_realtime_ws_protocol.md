---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/backend/05_realtime_ws_protocol.md
version: v1
date: 2026-04-16
---

# Realtime WebSocket protocol

Endpoint:
`/api/v1/realtime/ws?accessToken=...`

## Envelope

Все сообщения идут в таком формате:
```json
{
  "type": "room.subscribe",
  "requestId": "uuid",
  "payload": {}
}
```

`requestId` обязателен для client-originated commands.

## Client -> server commands

### room.subscribe
```json
{
  "type": "room.subscribe",
  "requestId": "uuid",
  "payload": {
    "roomId": "uuid"
  }
}
```

### room.ready
```json
{
  "type": "room.ready",
  "requestId": "uuid",
  "payload": {
    "roomId": "uuid"
  }
}
```

### match.submitTurn
```json
{
  "type": "match.submitTurn",
  "requestId": "uuid",
  "payload": {
    "matchId": "uuid",
    "clientTurnId": "uuid",
    "guess": "123456"
  }
}
```

### chat.send
```json
{
  "type": "chat.send",
  "requestId": "uuid",
  "payload": {
    "roomId": "uuid",
    "text": "go"
  }
}
```

### presence.ping
```json
{
  "type": "presence.ping",
  "requestId": "uuid",
  "payload": {}
}
```

## Server -> client events

### auth.ok
Подтверждение соединения.

### room.snapshot
Полный снимок комнаты после подписки.

### player.joined
Игрок вошёл в комнату.

### player.left
Игрок вышел.

### player.readyChanged
Статус готовности изменился.

### match.started
Матч стартовал.

### turn.accepted
Сервер принял ход.

### turn.result
Итог хода.

Example:
```json
{
  "type": "turn.result",
  "payload": {
    "matchId": "uuid",
    "playerId": "uuid",
    "turnIndex": 4,
    "guess": "123456",
    "score": 2,
    "isSolved": false,
    "remainingAttempts": 8
  }
}
```

### match.finished
Матч завершён.

### chat.message
Сообщение чата.

### error
Ошибка команды.

## Reconnect rules

- клиент должен уметь переподключаться
- после reconnect клиент снова делает `room.subscribe`
- сервер обязан вернуть `room.snapshot`
- клиент не должен считать локальный transient state источником истины

## Server authority

WebSocket используется только как realtime transport.
Источник истины всё равно сервер:
- state комнаты
- статус матча
- score хода
- победитель
- таймеры
