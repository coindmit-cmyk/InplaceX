# INPX-LOG-EMAIL-REDACTION-20261014 — revision 3

Shared logging diagnostics hide private attributes and throwable data

Sources: InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:30, .agent/project.md:98, .agent/project.md:99, .agent/project.md:100.

The current default sensitive fragments do not include email, so public InplaceXLogger.info emits email/Email/accountEmail attribute values unchanged to LogSink. Existing four tests cover tokens, private keys, cookie, guesses, provider payload and purchases but no email attributes. This is an independent shared-logger privacy defect, not the deferred PasswordlessEmailCodePolicy proof/tostring tests, not SMTP/provider activation, and not a repeat/integration of the existing providerId candidate. Revision 2: verificationCode/VerificationCode attributes still bypass the same default sanitizer. This extends the same successful logger privacy goal; previous candidate, branch, receipts and caps are retained. Revision 3: the prior throwable test exercises ERROR with one message; no test exercises public WARN with an outer token message and nested private password cause. Approved .agent/project.md:100 applies to diagnostics at both levels. This is coverage of existing external privacy behavior, not a new production requirement.

Criteria:

- Preserve all eight existing InplaceXLoggerTest methods/assertions byte-for-byte and current InplaceXLog.kt byte-for-byte. Same task, branch, receipts, six-call and 0.5 estimated-credit budget; exactly one test-only insertion.
- Add one meaningful JUnit4 test of the public logger.warn API with a RecordingLogSink, WARN minimum level and deterministic clock 500L. Use safe static tag Auth, message auth request failed and environment=sandbox. Throwable is IllegalStateException with synthetic message accessToken=fixture-private-token and cause IllegalArgumentException with synthetic message password=fixture-private-password. This is a separate public WARN surface and nested-cause privacy gap, not a sanitizer implementation oracle.
- Require one emitted event, literal WARN level, Auth tag, auth request failed message, sandbox environment, timestamp 500L and errorClass java.lang.IllegalStateException. Its diagnostic representation must exclude both complete private messages and both private fixture values. Do not expect the cause class/stack/message, use real data, assert an exact whole event string, scan safe free text or change production.
- Only insert this method before the unchanged test-class closing newline brace. Exact InplaceXLoggerTest filter must pass nine non-skipped tests and whitespace. Prior email/code tests and original assertions remain unchanged; no commit/integration/provider/device/Core closure.

Current revision edits only InplaceX-logging/src/test/kotlin/com/mirkori/inplacex/logging/InplaceXLoggerTest.kt. Existing source candidate unchanged. Same branch/cap: six of six calls, 0.5-credit maximum; no new ID.

Checks: exact InplaceXLoggerTest filter, nine methods, errors/failures/skips=0; whitespace passed. Report: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-LOG-EMAIL-REDACTION-20261014\REPORT.md. Integration/full Core/provider/device closure NOT_RUN.
