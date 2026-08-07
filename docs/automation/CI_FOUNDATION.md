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
./gradlew :app:testReleaseUnitTest
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
bash scripts/ci/run_instrumentation.sh
```

Перед ними workflow печатает версии launcher и доступных Gradle toolchains,
чтобы несовпадение Java 21/11 было видно в логе.

## Artifact identity

После debug-сборки `scripts/ci/artifact_identity.sh` создаёт в одном каталоге:

- APK с именем `inplacex-debug-<version>-<commit>.apk`;
- JSON-манифест с package, version name/code, minSdk, размером, полным
  `commit`, `sha256`, debuggable/signing status, release ID и отпечатком
  сертификата для подписанного APK;
- checksum-файл для независимой проверки SHA-256.

Скрипт извлекает Android-метаданные из фактического APK через `aapt`, а
подпись и SHA-256 сертификата — через `apksigner`. Commit берётся из
`GITHUB_SHA` или локального `HEAD`; при наличии `GITHUB_SHA` он обязательно
сверяется с реально checkout-нутым `HEAD`. Ожидаемый signing status задаётся
явно. Пример локального запуска debug artifact:

```bash
bash scripts/ci/artifact_identity.sh \
  --apk InplaceX-android/app/build/outputs/apk/debug/app-debug.apk \
  --output-dir build/ci-artifacts \
  --artifact-type debug \
  --expected-signing verified
```

Release job собирает unsigned candidate, подтверждает через `apksigner` именно
`unverified` status и публикует identity bundle. Это позволяет каждому PR
компилировать release-код без доступа к production signing material.

## Signed release candidate

Каноническая версия Android находится в
`InplaceX-android/version.properties`. Для подписанного production-кандидата
используется отдельная команда:

```bash
./gradlew :app:releaseCandidate \
  -PinplacexProviderConfigFile=/secure/inplacex-provider.properties \
  -PinplacexReleaseSigningFile=/secure/inplacex-signing.properties
```

Signing properties содержат только `storeFile`, `storePassword`, `keyAlias` и
`keyPassword`, а также обязательный owner-policy fingerprint
`expectedCertificateSha256`; безопасный шаблон находится в
`InplaceX-android/release-signing.example.properties`. Вместо файла допускаются
`INPLACEX_RELEASE_STORE_FILE`, `INPLACEX_RELEASE_STORE_PASSWORD`,
`INPLACEX_RELEASE_KEY_ALIAS`, `INPLACEX_RELEASE_KEY_PASSWORD` и
`INPLACEX_RELEASE_EXPECTED_CERT_SHA256`. Частичный набор отклоняется на
конфигурации без вывода значений. Ожидаемый SHA-256 сертификата не является
секретом, но служит обязательной политикой владельца: фактический signer APK
сверяется с ним через `apksigner`.

`releaseCandidate` дополнительно требует production HTTPS origins для
online/platform и обязательные Yandex banner/rewarded placement IDs. Он
собирает отдельный `signedReleaseCandidate`, проверяет подпись и атомарно пишет
ровно один чистый bundle в `build/release-candidates/<releaseId>`. `releaseId`
совпадает с контрактом каталога Mirkori (`[a-z0-9][a-z0-9._-]{1,63}`). Уже
существующий ID разрешено повторно проверить только для полностью идентичного
bundle; другой APK SHA-256, неполный или содержащий stale-файлы каталог
отклоняется без перезаписи.

Обычные `assembleRelease` и `assembleInternalDistribution` всегда остаются
unsigned, даже если external signing config доступен. Они никогда не получают
debug или production key. Прямой `assembleSignedReleaseCandidate` также
защищён обоими production/signing validators, но каноническая команда для
публикуемого identity bundle — `releaseCandidate`.

## Emulator and contract gate

Instrumentation и release являются блокирующими. API 35 emulator создаётся
прикреплённой к commit версией `android-emulator-runner`; тестовая команда
принадлежит репозиторию и перед connected-тестами проверяет KVM, единственный
готовый emulator и равенство `GITHUB_SHA == HEAD`.

Отдельный `ci-contract` job запускает pinned `actionlint` и структурный
`validate_ci_contract.py`. Guard требует точные workflow calls, сверяет
SHA-256 проверенных repository scripts, отвергает hostile fixtures с ожидаемым
diagnostic code и исполняет fake-apksigner success/failure/mismatch/missing
и fake-aapt metadata-сценарии. Echo, comment, heredoc, short-circuit и raw-text
имитации команд не считаются выполнением gate.
