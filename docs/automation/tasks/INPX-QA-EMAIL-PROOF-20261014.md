# INPX-QA-EMAIL-PROOF-20261014

Источники: InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:28, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:29, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:30.

Пробел: The four existing security tests verify a valid code, wrong code and expiry, subject separation and Telegram basics. They never assert rejection for a different challenge, email, signing key or modified proof, nor six-digit/privacy issuance at injected random endpoints. Production already binds these values; this is independent pure-core coverage, not auth routes/SDK/SMTP/session ownership work.

Только InplaceX-auth-core/src/test/kotlin/com/mirkori/inplacex/auth/AuthProviderSecurityTest.kt; production InplaceX-auth-core/src/main/kotlin/com/mirkori/inplacex/auth/PasswordlessEmailCodePolicy.kt доступен для чтения.

Критерии:

- Add one independent JUnit4 method issuing a challenge with a synthetic example.invalid email, valid challenge id, synthetic 32-byte key and fixed Instant. Confirm valid verification before expiry, then independently change the valid challenge id, normalized email, another 32-byte key, or one Base64URL proof character while retaining other inputs; each must return false. Do not generate an expected proof by copying HMAC or compare two invocations of its helper; test only public issue/verify behavior with literal true/false outcomes.
- Add a second independent JUnit4 method using separate SecureRandom fixtures that return 0 and bound-1 from nextInt(bound). Each public issue result must contain exactly six decimal characters (leading zeroes need not be forbidden), and its toString must contain neither the issued code nor synthetic email. Use fixed time and explicit positive TTL; no real email, token, user key or network call. Do not assert undocumented exact generated code or expiry defaults.
- Preserve all four baseline methods/assertions and the original DeterministicSecureRandom helper. Only AuthProviderSecurityTest.kt may change; insert the two methods inside its unique class boundary before the top-level helper. PasswordlessEmailCodePolicy and ProviderSubjectDeriver are read-only. No HMAC algorithm, issuer, provider subject, HTTP/SMTP/DB/SDK change or owner-gate bypass.
- The exact offline AuthProviderSecurityTest filter must pass at least six non-skipped tests and whitespace check. Initial four-test baseline and every paid receipt remain distinct; unit results do not close whole Core/auth adapter/provider/device/integration gates.

Проверки: gradlew.bat :InplaceX-auth-core:test --tests com.mirkori.inplacex.auth.AuthProviderSecurityTest --offline --no-daemon --console=plain; git diff --check. Baseline: 4 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-EMAIL-PROOF-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.


## Deferred host evidence

Generated insertion used an abbreviated anchor rejected by the host guard. Luna diagnosed a mechanical anchor normalization, but the library classified the supplied failure as blocked and rejected that repair category. Nothing applied.

Preserve the same ID, branch, three receipts and original answers. Do not rerun the implementation pipeline, retry the rejected diagnosis category, relabel evidence, or bypass this state with a new ID. The task has three of four calls used, not an exhausted call budget. A future repair requires a policy-valid plan for the host anchor format/classification; retain the baseline tests and original diagnosis. Continue other independent approved-documentation tasks meanwhile.

Report: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-EMAIL-PROOF-20261014\REPORT.md
