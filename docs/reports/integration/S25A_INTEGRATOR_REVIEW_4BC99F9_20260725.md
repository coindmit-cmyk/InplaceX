# S25A integrator review — `4bc99f9`

## Решение

`REJECT`. Ветка и commit сохранены как read-only evidence. Прямой перенос в
`develop` запрещён.

## Блокеры

1. `PublicSessionIntent` остаётся caller-authored outcome: клиент может передать
   `exactMatches`, `solved`, `phase`, `currentActor` и `winner`.
   `AuthenticatedSessionCommand` связывает actor с таким готовым результатом,
   но не вычисляет его на authoritative server state.
2. `ServerEstablishedActorFactory` доступна всему backend-модулю и принимает
   произвольные UUID/participant id. Это не доказанная auth/session-membership
   boundary.
3. Duplicate-key scanner рекурсивен без ограничения глубины. Валидный frame
   размером 20 059 bytes с 10 000 вложенных arrays вызывает
   `StackOverflowError`.
4. `HmacSecretFingerprinter` создаёт `String(secret)`, поэтому raw secret может
   остаться в heap после очистки временного `ByteArray`.

## Подтверждённые части

- backend forced rerun: `41/41`;
- `verifyProject --rerun-tasks`: `43/43`;
- duplicate и Unicode-escaped duplicate keys;
- recursive forbidden key/value corpus;
- exact UTF-8 `64 KiB` boundary;
- injected keyed HMAC и domain/session/participant separation;
- pseudonymous safe logs;
- scope не содержит persistence, SQL или application-код;
- максимальный production-файл — 215 строк.

## Обязательный узкий retry

- S25A больше не моделирует client intent и authenticated actor. Эти границы
  принадлежат child S25B, где команда будет применяться к authoritative state.
- В S25A остаются только закрытые public snapshot/event/result contracts,
  canonical codec, frame/security policy, HMAC fingerprint и safe logging.
- JSON pre-scan должен быть iterative либо иметь жёсткий bounded depth и
  возвращать контролируемую ошибку на adversarial nesting.
- CharArray секрета кодируется без промежуточного immutable `String`; все
  очищаемые буферы обнуляются в `finally`.
- Повтор обязан добавить adversarial depth test и negative API tests,
  доказывающие отсутствие public intent/outcome decoder и actor factory.

