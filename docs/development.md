# Локальная разработка

## Требования

- JDK 11 или совместимый JBR из Android Studio.
- Android SDK, настроенный через Android Studio, `ANDROID_HOME` или `ANDROID_SDK_ROOT`.
- Gradle wrapper из репозитория.

## Настройка

`local.properties` не коммитится. Android Studio может создать `sdk.dir` автоматически. Provider-настройки для локальной сборки можно брать из безопасного примера:

```text
InplaceX-android/provider-config.example.properties
```

Реальные provider ids, токены, ключи и секреты не должны попадать в репозиторий.

## Основные команды

Из корня репозитория:

```powershell
.\gradlew.bat verifyProject
.\gradlew.bat assembleDebug
.\gradlew.bat :app:validateProductionReleaseConfig `
  -PinplacexProviderConfigFile=D:\private\inplacex-provider.properties
.\gradlew.bat cleanLocalDiagnostics
```

`validateProductionReleaseConfig` не печатает значения: он сообщает только
имена отсутствующих release-полей, проверяет HTTPS origins online backend и
Mirkori Platform, а также запрещает повторяющиеся, управляющие или чрезмерно
длинные Yandex placement ID.

В изолированном worktree можно использовать уже существующий приватный файл
настроек без копирования:

```powershell
.\gradlew.bat :app:validateProductionReleaseConfig `
  -PinplacexProviderConfigFile=D:\private\inplacex-provider.properties
```

Обычный `:app:assembleRelease` собирает unsigned APK и подходит для PR CI.
Подписанный кандидат создаётся только через `:app:releaseCandidate` с внешним
provider config и полным signing config. Его безопасный формат показан в
`InplaceX-android/release-signing.example.properties`; файл с реальными
значениями и keystore должны оставаться вне Git. В config обязателен ожидаемый
SHA-256 owner-сертификата. Обычные `assembleRelease` и
`assembleInternalDistribution` остаются unsigned даже с настроенным ключом;
подпись получает только отдельный `signedReleaseCandidate`. Проверенный bundle
появляется атомарно в `build/release-candidates/<releaseId>`.

На Unix-like shell используйте `./gradlew`.

## Git-ветки

Рабочая схема проекта:

- `production` — финальная пользовательская линия.
- `develop` — основная линия разработки.
- `feature/*` — новые функции и проектная инфраструктура.
- `fix/*` — исправления.
- `test/*` — тестовые эксперименты.
- `debug/*` — диагностика и отладка.
- `release/*` — подготовка релизов.

Новые задачи не начинаем в `codex/*`. Если инструмент создал такую ветку автоматически, локально переименовываем ее в подходящий проектный формат перед продолжением.

`master` пока остается только как legacy-ссылка на `origin/master`, потому что remote default branch еще не перенесен на `production` или `develop`.

## Код, логирование и тесты

Новое production-поведение пишем вместе с:

- кодом в ответственном модуле;
- логированием через `InplaceX-logging`, если поведение затрагивает интеграции, runtime-состояния, ошибки, provider/backend/session границы или пользовательские recovery-сценарии;
- тестами через публичные входы и выходы модуля.

Не добавляем шумные логи для частых UI recomposition, tight loops и очевидных локальных присваиваний.

## Рабочие зоны

- Gameplay, UI, Android-интеграция: `InplaceX-android/app`.
- Bot logic и общая игровая логика: `InplaceX-bot-core`.
- Logging contracts и sanitization: `InplaceX-logging`.
- Backend runtime и серверный бот: `InplaceX-backend`.
- Product/architecture/ADR/design docs: `InplaceX-docs`.

## Перед изменениями

1. Проверьте `git status`.
2. Прочитайте релевантные документы из `AGENTS.md` и `.agent/`.
3. Для архитектурных изменений проверьте `InplaceX-docs/Game/GPT/`.
4. Для пользовательских сценариев проверьте `InplaceX-docs/Game/Human/`.
