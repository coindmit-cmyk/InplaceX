# Worker Result INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S19

- Generated: `2026-07-25T23:06:08Z`
- Worker: `auto-worker-5.3`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s19/retry-s19-variant-provider-config-wiring-and-rel-retry-20260725T225755Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s19-20260725T225757Z`
- Result: `agent_done`
- Check evidence: `passed`
- Next owner: `Integrator`

## Check Evidence

```json
{
  "ok": true,
  "route": "agent_done",
  "check_status": "passed",
  "integration_status": "pending",
  "reason": "worker output contains explicit passed check_status",
  "negative_markers": [],
  "positive_markers": [
    "passed",
    "git diff --check",
    "check_status=passed"
  ]
}
```

## stdout

```text
Готово: debug stubs и test IDs изолированы, release factory fail-closed и не выбирает stubs при `SANDBOX`; успешный sign-in больше не сохраняется без реальной авторизации.

Отчёт: [WORKER_RESULT…md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s19-20260725T225757Z/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S19_20260725T230456Z)

Проверки:

- `bash gradlew :app:testDebugUnitTest` — passed
- `bash gradlew :app:testReleaseUnitTest` — passed
- `bash gradlew assembleRelease` — passed
- `git diff --check` — passed
- Проверены generated release `BuildConfig` (пустые provider IDs) и отсутствие stub-классов в release artifact.

`check_status=passed`

Изменены только разрешённые пути. Runner должен закоммитить/запушить ветку и записать runner-owned `integration_requested` event.

```

## stderr

```text
lingService.class",
         )
     }
 }
diff --git a/InplaceX-android/app/src/testDebug/java/com/mirkori/inplacex/platform/services/DebugProviderServicesTest.kt b/InplaceX-android/app/src/testDebug/java/com/mirkori/inplacex/platform/services/DebugProviderServicesTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..7165acfdf2a7336e5488f2b64edae323d7f2d7de
--- /dev/null
+++ b/InplaceX-android/app/src/testDebug/java/com/mirkori/inplacex/platform/services/DebugProviderServicesTest.kt
@@ -0,0 +1,26 @@
+package com.mirkori.inplacex.platform.services
+
+import android.content.ContextWrapper
+import com.mirkori.inplacex.BuildConfig
+import com.mirkori.inplacex.platform.config.PlatformConfig
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class DebugProviderServicesTest {
+    @Test
+    fun `debug factory provides sandbox success adapters`() {
+        val services = ProviderServicesFactory.create(
+            context = ContextWrapper(null),
+            platformConfig = PlatformConfig(navigationItems = emptyList()),
+        )
+
+        assertEquals("sandbox", BuildConfig.PROVIDER_ENVIRONMENT)
+        assertTrue(services.authService is StubGooglePlayAuthService)
+        assertTrue(services.adService is StubAdService)
+        assertTrue(services.billingService is StubBillingService)
+        assertTrue(services.authService.signInWithGooglePlay().isSignedIn)
+        assertTrue(services.adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD))
+        assertTrue(services.billingService.purchase(BillingProductId.REMOVE_ADS))
+    }
+}
diff --git a/InplaceX-android/app/src/testRelease/java/com/mirkori/inplacex/ReleaseVariantIsolationTest.kt b/InplaceX-android/app/src/testRelease/java/com/mirkori/inplacex/ReleaseVariantIsolationTest.kt
index e68c2e48285fc1243c27c31e013d6ee3292d1a4a..9c5eb9e0dddc6342a0b90e507811e985d959c434
--- a/InplaceX-android/app/src/testRelease/java/com/mirkori/inplacex/ReleaseVariantIsolationTest.kt
+++ b/InplaceX-android/app/src/testRelease/java/com/mirkori/inplacex/ReleaseVariantIsolationTest.kt
@@ -50,6 +50,9 @@
             "com/mirkori/inplacex/ui/screens/game/GameFieldDebugScreenKt.class",
             "com/mirkori/inplacex/ui/screens/game/GameDebugAdSlotKt.class",
             "com/mirkori/inplacex/ui/screens/game/presentation/GameDebugAdSlotContentKt.class",
+            "com/mirkori/inplacex/platform/services/StubGooglePlayAuthService.class",
+            "com/mirkori/inplacex/platform/services/StubAdService.class",
+            "com/mirkori/inplacex/platform/services/StubBillingService.class",
         )
     }
 }
diff --git a/InplaceX-android/app/src/testRelease/java/com/mirkori/inplacex/platform/services/ReleaseProviderServicesTest.kt b/InplaceX-android/app/src/testRelease/java/com/mirkori/inplacex/platform/services/ReleaseProviderServicesTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..d2b66fc002dd26076d1e55d6c8c466fcb2d5d866
--- /dev/null
+++ b/InplaceX-android/app/src/testRelease/java/com/mirkori/inplacex/platform/services/ReleaseProviderServicesTest.kt
@@ -0,0 +1,50 @@
+package com.mirkori.inplacex.platform.services
+
+import android.content.ContextWrapper
+import com.mirkori.inplacex.BuildConfig
+import com.mirkori.inplacex.platform.config.AdsProviderConfig
+import com.mirkori.inplacex.platform.config.BillingProviderConfig
+import com.mirkori.inplacex.platform.config.GooglePlayProviderConfig
+import com.mirkori.inplacex.platform.config.PlatformConfig
+import com.mirkori.inplacex.platform.config.ProviderConfig
+import com.mirkori.inplacex.platform.config.ProviderEnvironment
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertNull
+import org.junit.Test
+
+class ReleaseProviderServicesTest {
+    @Test
+    fun `release factory fails closed even when runtime config requests sandbox`() {
+        val services = ProviderServicesFactory.create(
+            context = ContextWrapper(null),
+            platformConfig = PlatformConfig(
+                navigationItems = emptyList(),
+                providers = ProviderConfig(
+                    environment = ProviderEnvironment.SANDBOX,
+                    googlePlay = GooglePlayProviderConfig(webClientId = "configured-id"),
+                    ads = AdsProviderConfig(admobAppId = "configured-id"),
+                    billing = BillingProviderConfig(
+                        removeAdsProductId = "remove_ads",
+                        proSubscriptionId = "pro",
+                        proPlusSubscriptionId = "pro_plus",
+                    ),
+                ),
+            ),
+        )
+
+        val beforeSignIn = services.authService.currentSession()
+
+        assertEquals("live", BuildConfig.PROVIDER_ENVIRONMENT)
+        assertEquals(beforeSignIn, services.authService.signInWithGooglePlay())
+        assertFalse(services.adService.showBanner("game"))
+        assertFalse(services.adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD))
+        assertFalse(services.adService.showInterstitial(InterstitialPlacement.POST_MATCH))
+        assertFalse(services.billingService.purchase(BillingProductId.REMOVE_ADS))
+        assertNull(
+            javaClass.classLoader?.getResource(
+                "com/mirkori/inplacex/platform/services/StubGooglePlayAuthService.class",
+            ),
+        )
+    }
+}
diff --git a/InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md b/InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md
index 6dfb7d9126b578db924c8b9198e05bb548856582..628d4c909fc0b94bbf7d26f804ca34d60ff7f388
--- a/InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md
+++ b/InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md
@@ -54,5 +54,7 @@
 ## Integration Rule

 - game code uses platform contracts only
-- current implementation uses `StubGooglePlayAuthService`, `StubAdService`, and `StubBillingService`
-- later SDK integration must preserve these interfaces
+- debug builds use `StubGooglePlayAuthService`, `StubAdService`, and `StubBillingService`
+- release builds never contain or resolve those stub classes, even if runtime configuration says `SANDBOX`
+- until the actual provider SDK integration is complete, release adapters fail closed: sign-in stays signed out, purchases return `false`, and ad methods return `false`; caller state must change only after a successful contract result
+- later SDK integration must preserve these interfaces and may enable release operations only after the SDK proves the provider result
diff --git a/InplaceX-docs/Game/GPT/Provider Runtime Config.md b/InplaceX-docs/Game/GPT/Provider Runtime Config.md
index 2b46e4235eed36a68e31c10841b24f51cdce2c73..f16a4d196978b3f2677cf62d304ea7c5323d0c6d
--- a/InplaceX-docs/Game/GPT/Provider Runtime Config.md
+++ b/InplaceX-docs/Game/GPT/Provider Runtime Config.md
@@ -2,9 +2,12 @@

 ## Source of Provider IDs

-- provider ids are resolved from `local.properties` during Android build
-- `app/build.gradle.kts` exports them as `BuildConfig` string fields
-- ads app id also flows into `AndroidManifest.xml` via `manifestPlaceholders`
+- provider ids are resolved from `local.properties` during Android build and are scoped to a build variant
+- debug reads `provider.debug.*` keys and supplies Google test ad ids plus sandbox product defaults when those keys are absent
+- release reads `provider.release.*` keys; absent values are empty and the release environment is always `live`
+- `app/build.gradle.kts` exports those variant-specific values as `BuildConfig` string fields
+- the ads app id also flows into `AndroidManifest.xml` via variant-specific `manifestPlaceholders`
+- shared `defaultConfig` contains no sandbox provider mode, test ad id, or mock billing product default

 ## Canonical BuildConfig Fields

@@ -38,10 +41,9 @@
 ## Runtime Adapter Layer

 - `ProviderServicesFactory.create(...)` is the canonical entry point
-- `SANDBOX` environment returns stub services
-- `LIVE` environment returns SDK-ready adapters:
-  - `GooglePlayAuthService`
-  - `AdMobService`
-  - `GooglePlayBillingService`
+- the factory is selected by the compile-time Android variant, not by the runtime environment string
+- debug returns stub services for local test flows
+- release returns `GooglePlayAuthService`, `AdMobService`, and `GooglePlayBillingService`; before their SDK integrations exist, they fail closed and do not turn configured strings into provider success
+- a release artifact must not contain debug stub implementations or use a runtime `SANDBOX` value to select them

-These adapters are intentionally scaffolds right now. Later implementation should fill in SDK calls without changing the app-facing interfaces.
+Later implementation should fill in SDK calls without changing the app-facing interfaces and must preserve the fail-closed behavior for absent or failed provider responses.
diff --git a/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S19_20260725T230456Z.md b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S19_20260725T230456Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..e82c7721f0dc82e677971c2ebc2c2be17ce675c1
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S19_20260725T230456Z.md
@@ -0,0 +1,31 @@
+# Worker Result: INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S19
+
+## Result
+
+- Status: `integration_requested`
+- Base: `601c0d558ec75c06f76db00bd1d8f7895a50f29c`
+- Scope: variant-specific Android provider wiring and release fail-closed behavior.
+
+## Delivered
+
+- Moved sandbox provider stubs and the stub factory into `src/debug`.
+- Added a release-only factory that never selects a debug stub, including when runtime provider config says `SANDBOX`.
+- Made the SDK-ready release adapters fail closed until real provider SDK callbacks exist: authentication remains signed out, purchases and ads return `false`, and no success placeholder is derived from configuration strings.
+- Kept `MainActivity` from persisting Google Play sign-in unless the provider contract reports an authenticated session.
+- Moved test IDs and sandbox product defaults from shared `defaultConfig` into debug-only fields. Release reads only `provider.release.*` fields and missing values are empty.
+- Added debug/release variant contract tests and updated the canonical provider documentation and changelog.
+
+## Verification
+
+| Command | Outcome |
+| --- | --- |
+| `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :app:testDebugUnitTest` | passed |
+| `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :app:testReleaseUnitTest` | passed |
+| `env JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew assembleRelease` | passed |
+| `git diff --check` | passed |
+
+Additional artifact evidence: generated release `BuildConfig` has `PROVIDER_ENVIRONMENT = "live"` and empty provider IDs with no release local properties; the release classes jar and unsigned APK contain release adapters and no `StubGooglePlayAuthService`, `StubAdService`, or `StubBillingService` classes. The debug classes jar contains all three stubs.
+
+## Handoff
+
+The worker did not edit runner-owned `AiStudio/Task_manager` state. The central runner should commit/push this branch and record the required `integration_requested` event when it syncs this report.

tokens used
117 472

```
