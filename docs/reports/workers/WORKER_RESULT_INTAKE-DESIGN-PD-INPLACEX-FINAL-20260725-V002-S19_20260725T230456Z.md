# Worker Result: INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S19

## Result

- Status: `integration_requested`
- Base: `601c0d558ec75c06f76db00bd1d8f7895a50f29c`
- Scope: variant-specific Android provider wiring and release fail-closed behavior.

## Delivered

- Moved sandbox provider stubs and the stub factory into `src/debug`.
- Added a release-only factory that never selects a debug stub, including when runtime provider config says `SANDBOX`.
- Made the SDK-ready release adapters fail closed until real provider SDK callbacks exist: authentication remains signed out, purchases and ads return `false`, and no success placeholder is derived from configuration strings.
- Kept `MainActivity` from persisting Google Play sign-in unless the provider contract reports an authenticated session.
- Moved test IDs and sandbox product defaults from shared `defaultConfig` into debug-only fields. Release reads only `provider.release.*` fields and missing values are empty.
- Added debug/release variant contract tests and updated the canonical provider documentation and changelog.

## Verification

| Command | Outcome |
| --- | --- |
| `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :app:testDebugUnitTest` | passed |
| `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :app:testReleaseUnitTest` | passed |
| `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew assembleRelease` | passed |
| `git diff --check` | passed |

Additional artifact evidence: generated release `BuildConfig` has `PROVIDER_ENVIRONMENT = "live"` and empty provider IDs with no release local properties; the release classes jar and unsigned APK contain release adapters and no `StubGooglePlayAuthService`, `StubAdService`, or `StubBillingService` classes. The debug classes jar contains all three stubs.

## Handoff

The worker did not edit runner-owned `AiStudio/Task_manager` state. The central runner should commit/push this branch and record the required `integration_requested` event when it syncs this report.
