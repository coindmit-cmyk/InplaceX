# INPX-QA-TELEGRAM-PAYLOAD-20261014

Источники: InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:31, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:32, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:33, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:34.

Пробел: The existing Telegram test generates its signature with the same production helper, and covers only a much later stale time. There is no independent literal signed payload or explicit configured past/future freshness boundary test. Production already implements the contract. This is not email-proof repair, provider onboarding, new OIDC flow or live credentials work.

Только InplaceX-auth-core/src/test/kotlin/com/mirkori/inplacex/auth/TelegramSignedPayloadContractTest.kt; production InplaceX-auth-core/src/main/kotlin/com/mirkori/inplacex/auth/TelegramLoginVerifier.kt доступен для чтения.

Критерии:

- Create exactly two meaningful JUnit4 methods in the new TelegramSignedPayloadContractTest; do not edit the four baseline AuthProviderSecurityTest methods or any production file. Exercise only public TelegramLoginVerifier.verify.
- Use a fixed externally derived synthetic fixture, with token 123456789:local_fixture_token, fields {"auth_date": "1700000000", "first_name": "Mira", "id": "424242", "username": "fixture_player"}, and literal hash 6375a59f2689a8aec98812e6a7f96e8ab9d55c33c650290262abbb7486e7b6f8. The fixture follows https://core.telegram.org/widgets/login-legacy#checking-authorization. Put the hash literally in the test; never calculate an expected signature using target helpers, Mac, MessageDigest or a copied reference calculator. At Instant.ofEpochSecond(1700000000) valid verification must expose externalUserId=424242, displayName=Mira and authenticatedAt equal to that Instant. Use a different map insertion order to prove protocol behavior rather than depending on order.
- In the first method independently keep the same otherwise valid fixture and change first_name to Mallory, use a different synthetic valid-format token, add unexpected=value, omit hash, shorten hash to 63 characters, or replace it with 64 nonhex z characters. Each public verify result must be null. Do not re-sign tampered values or require any network/secret.
- In the second method explicitly configure maximumAge=Duration.ofSeconds(120), futureClockSkew=Duration.ofSeconds(5), use the same literal signed payload and move only the injected now: authAt, authAt+120 seconds and authAt-5 seconds must verify; authAt+121 and authAt-6 must be null. This tests the caller-selected fresh interval and one step outside it, without imposing new default durations, sleep, clock mock, replay store or live provider requirements.
- The exact offline TelegramSignedPayloadContractTest filter must pass two non-skipped tests and whitespace check. Compile-only initial qualification proves the environment, with original new-file test count 0 and tests NOT_RUN until this file exists. Preserve all receipts and unchanged production hashes. Full auth adapters/Core/device/integration closure remains NOT_RUN.

Проверки: gradlew.bat :InplaceX-auth-core:test --tests com.mirkori.inplacex.auth.TelegramSignedPayloadContractTest --offline --no-daemon --console=plain; git diff --check. Baseline: compile-only passed; tests NOT_RUN; original test count 0.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-TELEGRAM-PAYLOAD-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

Подготовлен локальный кандидат: 2 новых тестовых методов; адресная проверка 2 тестов, 0 failures/errors/skips. Исходные 0 методов и assertions сохранены. Production и read-only references совпадают с pre-call hashes. Карточка agent_done/needs_integrator_review, lock free, worker_ready false. Commit/integration и полный Core/Android/backend/device closure NOT_RUN.
