# S25A integrator review — `e0232bf`

## Решение

`REJECT`. Commit сохранён как read-only evidence и не интегрируется.

## Блокер

Публичный decoder принимает lexical-invalid JSON:

- `connected: truE` нормализуется в `true`;
- `exactMatches: 01` нормализуется в `1`.

`BoundedJsonScanner.parsePrimitive()` читает token до delimiter, но не проверяет
JSON lexical grammar. Последующий parser допускает эти формы.

## Подтверждённые части

- forced backend tests — PASS;
- forced `verifyProject` — `43/43`;
- 10 000 уровней вложенности завершаются контролируемым
  `IllegalArgumentException`, не `StackOverflowError`;
- caller-authored intent/actor API и `String(secret)` отсутствуют;
- exact frame size, HMAC, redaction, safe logs, scope и line limits — PASS.

## Обязательный retry

Scanner должен принимать только `true`, `false`, `null` и JSON number grammar
`-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`. Добавить публичные decoder tests,
отклоняющие минимум `truE`, `FALSE`, `01`, `00`, `1.`, `+1`, а также
подтверждающие допустимые boundary numbers.

