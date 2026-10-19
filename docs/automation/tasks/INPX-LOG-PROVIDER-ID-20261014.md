# INPX-LOG-PROVIDER-ID-20261014

Источники: .agent/project.md:98, .agent/project.md:100.

Пробел: DefaultSensitiveKeyFragments lacks providerid. containsSensitiveFragment lowercases keys, so the public providerId and ProviderId attribute keys pass their raw values to LogSink; no existing logger test covers these id fields. This is current local-develop behavior, not a retry of closed PR #7 integration or blocked auth/session work.

Production patch и meaningful tests разрешены только в InplaceX-logging/src/main/kotlin/com/mirkori/inplacex/logging/InplaceXLog.kt и InplaceX-logging/src/test/kotlin/com/mirkori/inplacex/logging/InplaceXLoggerTest.kt. Planned/needs_task_packet до квалификации стандартного host adapter.

Критерии:

- Make the minimal default sanitizer change so providerId and ProviderId keys redact their synthetic id values before the event reaches RecordingLogSink. Preserve existing customizable sanitizer API and all unrelated source behavior; do not broaden to every provider* key or scan arbitrary free-text messages.
- Add a meaningful JUnit4 method covering both providerId and ProviderId at the public default InplaceXLogger-to-sink boundary. Each id attribute must equal SensitiveKeyLogSanitizer.RedactedValue and neither synthetic id may appear in event.toString(); use only synthetic fixtures and generic assertion messages.
- Add an independent method proving public providerName and environment values remain unchanged alongside a redacted providerId. Preserve all four baseline tests/assertions and require at least six non-skipped tests in the exact InplaceXLoggerTest filter.
- Only the two allowed logging files may change. Scoped offline unit test and git diff --check must pass. No old PR/queue reactivation, provider/production/network service operation, commits or integration; full Core/app/backend/device closure remains NOT_RUN.

Проверки: gradlew.bat :InplaceX-logging:test --tests com.mirkori.inplacex.logging.InplaceXLoggerTest --offline --no-daemon --console=plain; git diff --check. Baseline: 4 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-LOG-PROVIDER-ID-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

Локальный кандидат agent_done/needs_integrator_review; 6 scoped тестов, 0 failures/errors/skips; два новых метода. Host review сохранён. Full Core closure/commit/integration/device acceptance NOT_RUN.
