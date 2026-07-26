# CI foundation and artifact identity

Репозиторий использует `.github/workflows/ci.yml` как базовый GitHub Actions
pipeline для проверяемого debug- и release-артефактов.

## Блокирующие проверки

В текущем варианте CI блокируют следующие проверки:

- `verify`:
  - `./gradlew verifyProject`
  - `./gradlew lint`
  - `./gradlew :app:assembleDebugAndroidTest`
  - `./gradlew assembleDebug`
  - фиксирование debug-идентичности артефакта
  - проверка debug-архивации подписи и `manifest`
  - запуск `python scripts/ci/validate_ci_contract.py`
- `instrumentation` (зависит от `verify`):
  - установка платформы + Android API-образы
  - проверка Linux KVM (`/dev/kvm`)
  - создание AVD и запуск эмулятора
  - bounded wait для `adb` + `sys.boot_completed`
  - запуск `./gradlew :app:connectedDebugAndroidTest`
- `release` (зависит от `verify`):
  - `./gradlew :app:assembleRelease`
  - фиксирование release-идентичности артефакта
  - проверка release-свидетельства подписи и `manifest`

Ни `verify`, ни `instrumentation`, ни `release` не используют
`continue-on-error` и не имеют условных `if` в `jobs` или `steps` для сквозного
пропуска критичных действий.

## Artifact identity

Скрипт `scripts/ci/artifact_identity.sh`:

- копирует APK в `build/ci-artifacts` / `build/ci-release-artifacts`;
- пишет JSON-манифест, содержащий `artifact`, `version`, `version_code`, `commit`,
  `signing_status` и `sha256`;
- пишет checksum для независимой проверки SHA-256;
- пытается определить `apksigner` через `ANDROID_HOME/build-tools/*/apksigner` и
  всегда запускает `apksigner verify`;
- при отсутствии верификатора завершает CI с ошибкой (fail-closed);
- записывает в `GITHUB_OUTPUT` `manifest_path`, `artifact_path`, `signing_status`
  и связанные метаданные.

## Контракт валидации CI

Скрипт `scripts/ci/validate_ci_contract.py` проверяет не только наличие строк,
а их семантику:

- обязательное наличие трех jobs с зависимостями `instrumentation/release -> verify`;
- отсутствие `continue-on-error`/`if` на job и step уровне;
- обязательные команды сборки, AVD-провиженинга, bounded boot wait и запуск
  instrumentation-теста;
- обязательное создание release-артефакта и сигнатурной проверки;
- обязательное содержание `manifest_path`/`artifact_path`/`signing_status` в скрипте
  identity.

Контракт дополнительно покрыт негативными fixture для проверки `--self-test`:

- `scripts/ci/contract_mutations/pass_baseline.yml`
- `scripts/ci/contract_mutations/pass_artifact.yml`
- `scripts/ci/contract_mutations/fail_continue_on_error.yml`
- `scripts/ci/contract_mutations/fail_instrumentation_if_false.yml`
- `scripts/ci/contract_mutations/fail_boot_wait_bypass.yml`
- `scripts/ci/contract_mutations/fail_kvm_skipped.yml`
- `scripts/ci/contract_mutations/fail_release_missing_assemble.yml`
- `scripts/ci/contract_mutations/fail_artifact_static_signing.yml`
- `scripts/ci/contract_mutations/fail_emulator_noop.yml`
- `scripts/ci/contract_mutations/fail_connected_noop.yml`
- `scripts/ci/contract_mutations/fail_short_circuit.yml`
