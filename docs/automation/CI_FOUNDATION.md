# CI foundation and artifact identity

Репозиторий использует `.github/workflows/ci.yml` как базовый GitHub Actions
pipeline для проверяемого debug-артефакта.

## Blocking checks

Job `verify` запускается на Ubuntu с Java 21 как JVM, запускающей Gradle. Java
toolchains проекта остаются на Java 11: JVM-модули объявляют `jvmToolchain(11)`,
Android-модуль компилируется с Java 11, а Gradle может автоматически получить
нужный toolchain через Foojay resolver.

Блокирующие команды:

```bash
./gradlew verifyProject
./gradlew assembleDebug
```

Перед ними workflow печатает версии launcher и доступных Gradle toolchains,
чтобы несовпадение Java 21/11 было видно в логе.

## Artifact identity

После debug-сборки `scripts/ci/artifact_identity.sh` создаёт в одном каталоге:

- APK с именем `inplacex-debug-<version>-<commit>.apk`;
- JSON-манифест с `version`, `version_code`, полным `commit` и `sha256`;
- checksum-файл для независимой проверки SHA-256.

Скрипт получает version и version code из текущего `app/build.gradle.kts`, а
commit — из `GITHUB_SHA` или локального `HEAD`. Пример локального запуска:

```bash
bash scripts/ci/artifact_identity.sh \
  --apk InplaceX-android/app/build/outputs/apk/debug/app-debug.apk \
  --output-dir build/ci-artifacts
```

Подписывание и публикация release-артефактов в этот foundation не входят.

## Non-blocking visibility

Jobs `instrumentation` и `release` видны в каждом workflow run, но помечены
`continue-on-error: true`. Instrumentation пока включает сборку test APK и
connected-тесты; release job собирает unsigned release candidate. После
принятия dedicated-задач эти jobs можно сделать блокирующими отдельным
изменением workflow.
