# Owner test: auth configuration, deduction and race

Base: `automation/develop` at `b5a4d5072a0c021d89a5df7b2ea939536c0640b5`.
Scope: owner-requested corrections; no public APK publication and no VPS build.

## Implemented

- Explicit configured device-test build gate; missing online/platform/Google
  configuration fails without printing values. Standard offline CI builds remain supported.
- Bounded subset-score deduction, including `000111=1`, `111323=0`, `000323=1`.
  Every `1` is excluded; the first three zeros remain individually unresolved.
- Four race difficulties, Easy default, actual per-level solver strategy and
  independent manual-table pacing. Shared secret and terminal-match rules retained.

## Verification

- `:app:testDebugUnitTest`: 249 tests, no failures/errors.
- `:InplaceX-bot-core:test`: 77 tests, no failures/errors, including exhaustive
  oracle comparisons and the long-code regression.
- `verifyProject`, `:app:lintDebug`, `:app:assembleDebugAndroidTest`: passed.
- API 35 emulator `RaceDifficultySelectorTest`: 2/2 passed; instrumentation
  ran only on the emulator, never on the owner's physical phone.
- `:app:validateDeviceTestConfig`: passed with external private config and
  correctly failed with a missing config. `:app:deviceTestApk`: passed.
- OnePlus 9 Pro update via `adb install -r`: success; existing app data retained.
  APK SHA-256: `ac84c84fa58c9c062ad875a7dd1c9f76d87e749e3b28c431594252e1a89f9188`.
  Existing and new signer SHA-256 both
  `0618289d7869fc71e642337db9874ce9c011f28a0a300a4b52e0dd3205d65ff3`.
- Physical screenshot of race setup inspected: all four radio controls and
  explanation readable, Easy selected, online action available.
- Google native sign-in reached server-confirmed existing-profile selection;
  selection was cancelled, no account/profile was silently changed.
- Supported-link selection enabled for `games.dmit.life` on the test phone;
  DEFAULT+BROWSABLE callback resolves to MainActivity. Production asset-links
  trust was not changed to include a debug certificate.

## Separate platform blocker

The real Mirkori browser confirmation still fails on the deployed Platform
source `53d9c4757fcaf5c7ca65a45cdd32f4527090e58f` when an account already owns
the game profile. `GameAuthService` calls `authorizeExistingProfile` while the
session is still PENDING, but that repository method requires CONFLICT.
This is a server transition defect, independent of the APK and Android link
association. A separate platform fix and an authorized server rollout are
required; do not describe browser login as end-to-end verified by this APK.

The incidental website Google One Tap rejection is not proof of native Google
failure: native credential validation reached the expected profile conflict.
The existing website session was successfully refreshed before reproducing the
Mirkori confirmation defect with a fresh game-auth session.
