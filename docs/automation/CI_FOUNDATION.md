# CI foundation and artifact identity

Репозиторий использует `.github/workflows/ci.yml` как базовый GitHub Actions
pipeline для проверяемого debug-артефакта.

## Blocking checks

Job `verify` запускается на Ubuntu с Java 21 как JVM, запускающей Gradle. Java
toolchains проекта остаются на Java 11: JVM-модули объявляют `jvmToolchain(11)`,
Android-модуль компилируется с Java 11, а Gradle может автоматически получить
нужный toolchain через Foojay resolver.

Блокирующие команды и jobs:

```bash
./gradlew verifyProject
./gradlew lint
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
bash scripts/ci/run_instrumentation.sh
```

Перед ними workflow печатает версии launcher и доступных Gradle toolchains,
чтобы несовпадение Java 21/11 было видно в логе.

## Artifact identity

После debug-сборки `scripts/ci/artifact_identity.sh` создаёт в одном каталоге:

- APK с именем `inplacex-debug-<version>-<commit>.apk`;
- JSON-манифест с `version`, `version_code`, полным `commit` и `sha256`;
- checksum-файл для независимой проверки SHA-256.

Скрипт получает version и version code из текущего `app/build.gradle.kts`, а
commit — из `GITHUB_SHA` или локального `HEAD`; при наличии `GITHUB_SHA` скрипт
обязательно сверяет его с реально checkout-нутым `HEAD`. Signing status
получается исполнением `apksigner`, а ожидаемый статус задаётся явно. Пример
локального запуска debug artifact:

```bash
bash scripts/ci/artifact_identity.sh \
  --apk InplaceX-android/app/build/outputs/apk/debug/app-debug.apk \
  --output-dir build/ci-artifacts \
  --artifact-type debug \
  --expected-signing verified
```

Release job собирает unsigned candidate, подтверждает через `apksigner` именно
`unverified` status и публикует identity bundle. Подписание production-ключом и
публикация в store в этот foundation не входят.

## Emulator and contract gate

Instrumentation и release являются блокирующими. API 35 emulator создаётся
прикреплённой к commit версией `android-emulator-runner`; тестовая команда
принадлежит репозиторию и перед connected-тестами проверяет KVM, единственный
готовый emulator и равенство `GITHUB_SHA == HEAD`.

Отдельный `ci-contract` job запускает pinned `actionlint` и структурный
`validate_ci_contract.py`. Guard требует точные workflow calls, сверяет
SHA-256 проверенных repository scripts, отвергает hostile fixtures с ожидаемым
diagnostic code и исполняет fake-apksigner success/failure/mismatch/missing
сценарии. Echo, comment, heredoc, short-circuit и raw-text имитации команд не
считаются выполнением gate.
