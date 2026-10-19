# INPX-QA-PROVIDER-IDENTITY-PRIVACY-20261014 — revision 2

Provider identity and verified-login diagnostics hide private data

Sources: .agent/project.md:100, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:21, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:36, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:37, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:31.

The existing AuthProviderSecurityTest checks the derived email identity representation, but no test directly checks ProviderIdentity diagnostic privacy for the retained Google provider-issued subject or Telegram identity. This task tests the identity value/diagnostic boundary, not HMAC derivation, email issue/verify, the deferred email-proof task, raw logger attribute masking, provider links or live SDK configuration. AuthProvider.kt is a clean local-develop source. Revision2: baseline Telegram security test excludes external id from the result representation but never asserts displayName privacy. The public DTO is independent of verifier/SDK behavior; extend this same diagnostic-privacy card.

Criteria:

- Preserve both existing ProviderIdentityPrivacyTest methods byte-for-byte: Google subject property retained, diagnostics redacted; EMAIL/TELEGRAM identity values retained with subjects absent from diagnostics. Keep AuthProvider.kt read-only and the original task/branch/four-call/0.5-credit budget.
- Add exactly one meaningful JUnit4 method constructing the public VerifiedTelegramLogin data object with externalUserId=424242424242, displayName=Fixture Player and authenticatedAt=java.time.Instant.parse("2026-10-14T00:00:00Z"). Assert all three properties retain those literal fixture values. Its toString must contain [redacted] and public VerifiedTelegramLogin context, and contain neither the synthetic ID nor name. No exact full string/punctuation, helper oracle, HMAC, raw email/code fixture, reflection, real user data or network/provider action.
- TelegramLoginVerifier.kt is a qualified read-only reference; give the model only its DTO declaration before class TelegramLoginVerifier, with explicit line range. This constructs a data object only and does not prove authentication, signature/freshness validation, linking, token issuance, provider SDK or a persistence migration.
- Only an insertion of this one new method before the existing unchanged test-class closing brace is allowed. Three non-skipped ProviderIdentityPrivacyTest methods and whitespace must pass. Production/full reference hashes and every baseline assertion stay unchanged. Commit/integration/full Core/provider/device closure NOT_RUN.

Files: InplaceX-auth-core/src/test/kotlin/com/mirkori/inplacex/auth/ProviderIdentityPrivacyTest.kt only; references read-only. Same original branch/call-credit budget; no new ID.

Checks: exact com.mirkori.inplacex.auth.ProviderIdentityPrivacyTest filter, 3 methods, failures/errors/skips=0, whitespace passed.

Report: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-PROVIDER-IDENTITY-PRIVACY-20261014\REPORT.md. Prior evidence in D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-PROVIDER-IDENTITY-PRIVACY-20261014\revision-02. Integration/full closure NOT_RUN.
