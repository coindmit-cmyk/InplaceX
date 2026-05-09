# InplaceX Repository

Top-level structure:

- `InplaceX-android` - Android client and game runtime
- `InplaceX-backend` - JVM backend module, including the server-side bot player adapter
- `InplaceX-docs` - human and GPT documentation, design references, legacy notes

Local setup:

- Keep `local.properties` untracked. Android Studio can regenerate `sdk.dir`; CLI builds can also use `ANDROID_HOME` or `ANDROID_SDK_ROOT`.
- Use a local JDK through `JAVA_HOME`. Android Studio's bundled JBR is enough for local CLI checks.
- Run `./gradlew verifyProject` from the repository root for backend, bot-core, and Android debug unit tests.
- Run `./gradlew assembleDebug` from the repository root for the Android debug APK.
- Run `./gradlew cleanLocalDiagnostics` to remove local logcat dumps, heap dumps, and screenshot captures.

Recommended working split:

- gameplay, UI, mobile integration -> `InplaceX-android`
- sync, PvP services, server bot runtime, ranking, cloud logic -> `InplaceX-backend`
- product, architecture, ADR, design references -> `InplaceX-docs`
