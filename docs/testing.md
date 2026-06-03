# Тестирование

## Полная проектная проверка

```powershell
.\gradlew.bat verifyProject
```

Эта задача запускает:

- `:InplaceX-backend:test`
- `:InplaceX-bot-core:test`
- `:InplaceX-logging:test`
- `:InplaceX-test-support:test`
- `:app:testDebugUnitTest`

## Узкие проверки

Bot/core:

```powershell
.\gradlew.bat :InplaceX-bot-core:test
```

Backend:

```powershell
.\gradlew.bat :InplaceX-backend:test
```

Logging:

```powershell
.\gradlew.bat :InplaceX-logging:test
```

Test support:

```powershell
.\gradlew.bat :InplaceX-test-support:test
```

Android unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Android APK:

```powershell
.\gradlew.bat assembleDebug
```

## Где лежат тесты

- `InplaceX-bot-core/src/test`
- `InplaceX-logging/src/test`
- `InplaceX-test-support/src/test`
- `InplaceX-backend/src/test`
- `InplaceX-android/app/src/test`
- `InplaceX-android/app/src/androidTest`

## Правила для новых тестов

- Проверяйте поведение через публичные входы и выходы модулей.
- Для багов добавляйте воспроизводящий тест, если это практически возможно.
- Для изменений в bot-core сначала добавляйте unit-тесты в `InplaceX-bot-core/src/test`.
- Для изменений в logging contract добавляйте unit-тесты в `InplaceX-logging/src/test`.
- Для общих test-only helper'ов и sink'ов используйте `InplaceX-test-support/src/test`.
- Для backend bot runtime проверяйте безопасность snapshots и idempotency pending turns.
- Для Android UI/state логики используйте `:app:testDebugUnitTest`, если сценарий можно проверить без instrumented runtime.
