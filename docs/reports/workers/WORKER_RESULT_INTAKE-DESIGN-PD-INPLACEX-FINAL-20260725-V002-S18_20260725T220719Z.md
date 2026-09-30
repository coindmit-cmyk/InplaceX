# Worker result — S18 expanded debug and release isolation retry

- Task: `INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S18`
- Role: `auto-worker-5.5`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/intake-design-pd-inplacex-final-20260725-v002-s18/s18-expanded-debug-and-release-isolation-retry`
- Immutable base: `2a21242f0b9173f30056f8eb44822d8a082a196e`
- Result: `integration_requested`
- Check status: `passed`

## Implemented

- Moved `DeveloperRootScreen`, `BotLabScreen`, `GameFieldDebugScreen`,
  `DebugSecretAdSlot`, `GameDebugAdSlot` and `GameDebugAdSlotContent` out of
  `main` and into the `debug` source set.
- Replaced direct `MainActivity` and settings references with build-variant
  adapters. Debug keeps the existing owner controls; release supplies no
  developer route/control/secret renderer and retains only the normal ad-slot
  placeholder.
- Moved all `game.debug*`, `developer.*`, `settings.developer` and
  `profile.developer*` entries into a debug-only localization catalog. Release
  aggregates only common catalogs.
- Enabled the release unit-test component through the AGP Variant API and
  added separate `testDebug` and `testRelease` isolation tests.
- Preserved common game/race localization coverage while moving
  debug-screen-only presentation coverage to `testDebug`.

## Validation

Gradle environment used for every final Gradle command:

```bash
export JAVA_HOME=/mnt/d/software/jdks/jdk-21.0.12+8
export ANDROID_HOME=/home/main/.local/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

- `bash gradlew assembleDebug` — passed, `BUILD SUCCESSFUL`.
- `bash gradlew assembleRelease` — passed, `BUILD SUCCESSFUL`; generated
  `InplaceX-android/app/build/outputs/apk/release/app-release-unsigned.apk`.
- `bash gradlew :app:testDebugUnitTest` — passed, `BUILD SUCCESSFUL`.
  `DebugVariantIsolationTest` (1 test) and
  `DebugGameLocalizationPresentationTest` (3 tests) have zero
  failures/errors.
- `bash gradlew :app:testReleaseUnitTest` — passed, `BUILD SUCCESSFUL`.
  `ReleaseVariantIsolationTest` (1 test) has zero failures/errors and proves
  that release classpath/catalogs omit the assigned debug surfaces.
- Packet DEX gate:

```bash
bash -lc 'apk=InplaceX-android/app/build/outputs/apk/release/app-release-unsigned.apk; test -f "$apk"; ! for dex in $(unzip -Z1 "$apk" | grep -E "^classes[0-9]*\\.dex$"); do unzip -p "$apk" "$dex"; done | strings | grep -E "DeveloperRootScreen|BotLabScreen|DebugSecretAdSlot|GameFieldDebugScreen|GameDebugAdSlot|game\\.debug\\.|developer\\.action\\."'
```

  Passed with exit code `0` and no matches.

- Expanded DEX gate also rejected `game.debug_screen.*`,
  `developer.title/description/section/membership/bot_lab/user_data`,
  `settings.developer` and `profile.developer*`; passed with exit code `0` and
  no matches.
- `git diff --check` — passed with exit code `0` and no output.

## Notes

- Initial Gradle preflight attempts found missing `JAVA_HOME`, then missing
  `ANDROID_HOME`; both were resolved with the environment above without
  creating the forbidden `local.properties`.
- Final fetch observed `origin/develop` at
  `df21ec9b343a6f6b12fd27199eec6921d67aed4c`; intervening changes from the
  immutable base affect only runner-owned Task_manager state and an unrelated
  S25A integration report, not S18 implementation scope or required refs.
- AGP 9 did not create `testReleaseUnitTest` by default. The app build now
  explicitly enables the release unit-test component, after which the required
  command passed.
- `CHANGELOG.md` and canonical docs were not edited: the packet does not allow
  those paths, and the source handoff assigns shared documentation integration
  to `INPX-DOC-901`.
- No Task_manager queue, lock or event file was modified. The central runner
  should commit/push this branch and emit `integration_requested`.
