# Worker Result INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S10

- Generated: `2026-07-25T23:47:57Z`
- Worker: `auto-worker-5.3`
- Model: `gpt-5.6-luna`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s10/home-and-shell-localization-retry-20260725T234004Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s10-20260725T234007Z`
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
Реализована локализация Home/Shell RU/EN:

- Каталог ключей для home, PvE/PvP, дуэли, setup-диалогов и bottom reserve.
- Убраны прямые UI-строки и ручная конкатенация.
- Добавлены typed placeholder helpers и parity-тесты.
- `StaticLocalizationProvider.kt` не изменён.

Проверки:

- `bash gradlew :app:testDebugUnitTest` — passed.
- `bash gradlew assembleDebug` — passed.
- `git diff --check` — passed.
- Scope/whitespace audit — passed.

`check_status=passed`

Отчёт: [worker result](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s10-20260725T234007Z/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S10_20260725T234710Z.md)

Next owner: Integrator.

```

## stderr

```text
                 }
             }
diff --git a/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/HomeText.kt b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/HomeText.kt
new file mode 100644
index 0000000000000000000000000000000000000000..2104519f9231e8c0263c8b1c64e0b4e3f50a4597
--- /dev/null
+++ b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/HomeText.kt
@@ -0,0 +1,31 @@
+package com.mirkori.inplacex.ui.screens.home
+
+import com.mirkori.inplacex.platform.localization.LocalizationProvider
+import java.util.Locale
+
+internal fun LocalizationProvider.homeCodeLength(codeLength: Int): String =
+    formatHomeText("home.code_length", codeLength)
+
+internal fun LocalizationProvider.homeDuelResultBotWin(score: Int): String =
+    formatHomeText("home.duel.result.bot_win", score)
+
+internal fun LocalizationProvider.homeDuelStatus(score: Int, confirmed: Int, codeLength: Int): String =
+    formatHomeText("home.duel.status.with_score", score, confirmed, codeLength)
+
+internal fun LocalizationProvider.homeDuelWaiting(confirmed: Int, codeLength: Int): String =
+    formatHomeText("home.duel.status.waiting", confirmed, codeLength)
+
+internal fun LocalizationProvider.homeSecretLabel(codeLength: Int): String =
+    formatHomeText("home.dialog.setup.secret_label", codeLength)
+
+internal fun LocalizationProvider.homeTimeLeft(seconds: Int): String =
+    formatHomeText("home.dialog.setup.time_left", seconds)
+
+internal fun LocalizationProvider.homeBotReady(seconds: Int): String =
+    formatHomeText("home.dialog.setup.bot_ready", seconds)
+
+internal fun LocalizationProvider.homeEnterDigits(codeLength: Int): String =
+    formatHomeText("home.dialog.setup.enter_digits", codeLength)
+
+private fun LocalizationProvider.formatHomeText(key: String, vararg args: Any): String =
+    String.format(Locale.ROOT, text(key), *args)
diff --git a/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PveModesScreen.kt b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PveModesScreen.kt
index 07ba716a9d409da883442e02f474dc31340201a8..0a63400474eaa688973cb9bd7027baf975d8f666
--- a/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PveModesScreen.kt
+++ b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PveModesScreen.kt
@@ -12,11 +12,13 @@
 import androidx.compose.runtime.Composable
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
+import com.mirkori.inplacex.platform.localization.LocalAppStrings

 @Composable
 fun PveModesScreen(
     onBack: () -> Unit
 ) {
+    val strings = LocalAppStrings.current
     BoxWithConstraints(
         modifier = Modifier.fillMaxSize()
     ) {
@@ -29,12 +31,12 @@
             horizontalAlignment = Alignment.CenterHorizontally
         ) {
             Text(
-                text = "PvE режимы",
+                text = strings.text("home.pve.screen.title"),
                 style = MaterialTheme.typography.headlineSmall
             )

             Text(
-                text = "Здесь потом будут режимы против бота и одиночные режимы.",
+                text = strings.text("home.pve.screen.description"),
                 style = MaterialTheme.typography.bodyLarge
             )

@@ -42,21 +44,21 @@
                 onClick = { },
                 modifier = Modifier.fillMaxWidth(fraction = 0.68f)
             ) {
-                Text("Гонка с ботом")
+                Text(strings.text("home.pve.race"))
             }

             FilledTonalButton(
                 onClick = { },
                 modifier = Modifier.fillMaxWidth(fraction = 0.68f)
             ) {
-                Text("Кампания")
+                Text(strings.text("home.pve.campaign"))
             }

             OutlinedButton(
                 onClick = onBack,
                 modifier = Modifier.fillMaxWidth(fraction = 0.42f)
             ) {
-                Text("Назад")
+                Text(strings.text("top.back"))
             }
         }
     }
diff --git a/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PvpModesScreen.kt b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PvpModesScreen.kt
index 6630ebf134e067fba2639cc4b8f75c740c2f9f3b..8372e3b1c284efe16e091a18e14504c63d1cbde5
--- a/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PvpModesScreen.kt
+++ b/InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PvpModesScreen.kt
@@ -12,11 +12,13 @@
 import androidx.compose.runtime.Composable
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
+import com.mirkori.inplacex.platform.localization.LocalAppStrings

 @Composable
 fun PvpModesScreen(
     onBack: () -> Unit
 ) {
+    val strings = LocalAppStrings.current
     BoxWithConstraints(
         modifier = Modifier.fillMaxSize()
     ) {
@@ -29,12 +31,12 @@
             horizontalAlignment = Alignment.CenterHorizontally
         ) {
             Text(
-                text = "PvP режимы",
+                text = strings.text("home.pvp.screen.title"),
                 style = MaterialTheme.typography.headlineSmall
             )

             Text(
-                text = "Здесь потом будут режимы с друзьями и онлайн.",
+                text = strings.text("home.pvp.screen.description"),
                 style = MaterialTheme.typography.bodyLarge
             )

@@ -42,21 +44,21 @@
                 onClick = { },
                 modifier = Modifier.fillMaxWidth(fraction = 0.68f)
             ) {
-                Text("Играть с другом")
+                Text(strings.text("home.pvp.friend"))
             }

             FilledTonalButton(
                 onClick = { },
                 modifier = Modifier.fillMaxWidth(fraction = 0.68f)
             ) {
-                Text("Онлайн матч")
+                Text(strings.text("home.pvp.online"))
             }

             OutlinedButton(
                 onClick = onBack,
                 modifier = Modifier.fillMaxWidth(fraction = 0.42f)
             ) {
-                Text("Назад")
+                Text(strings.text("top.back"))
             }
         }
     }
diff --git a/InplaceX-android/app/src/test/java/com/mirkori/inplacex/ui/screens/home/HomeLocalizationCatalogTest.kt b/InplaceX-android/app/src/test/java/com/mirkori/inplacex/ui/screens/home/HomeLocalizationCatalogTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..1c891b3b62b8d3b6bb44cfe8173ab6d1956cbd32
--- /dev/null
+++ b/InplaceX-android/app/src/test/java/com/mirkori/inplacex/ui/screens/home/HomeLocalizationCatalogTest.kt
@@ -0,0 +1,65 @@
+package com.mirkori.inplacex.ui.screens.home
+
+import com.mirkori.inplacex.platform.localization.AppLanguage
+import com.mirkori.inplacex.platform.localization.HomeCatalog
+import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class HomeLocalizationCatalogTest {
+    @Test
+    fun `home catalogs have matching keys and typed placeholders`() {
+        assertEquals(HomeCatalog.ru.keys.sorted(), HomeCatalog.en.keys.sorted())
+
+        HomeCatalog.ru.keys.forEach { key ->
+            assertEquals(
+                "placeholder mismatch for $key",
+                placeholders(HomeCatalog.ru.values.getValue(key)),
+                placeholders(HomeCatalog.en.values.getValue(key)),
+            )
+        }
+    }
+
+    @Test
+    fun `home keys resolve in both languages`() {
+        AppLanguage.values().forEach { language ->
+            val strings = StaticLocalizationProvider.forLanguage(language)
+            HomeCatalog.ru.keys.forEach { key ->
+                assertFalse("$language falls back to key $key", strings.text(key) == key)
+            }
+        }
+    }
+
+    @Test
+    fun `home placeholders are formatted through typed helpers`() {
+        val ru = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
+        val en = StaticLocalizationProvider.forLanguage(AppLanguage.EN)
+
+        assertEquals("6 цифр", ru.homeCodeLength(6))
+        assertEquals("6 digits", en.homeCodeLength(6))
+        assertEquals(
+            "Последний счёт соперника: 4 • Подтверждено: 2/6",
+            ru.homeDuelStatus(score = 4, confirmed = 2, codeLength = 6),
+        )
+        assertEquals(
+            "Opponent's last score: 4 • Confirmed: 2/6",
+            en.homeDuelStatus(score = 4, confirmed = 2, codeLength = 6),
+        )
+        assertEquals("Введите 6 цифр", ru.homeEnterDigits(6))
+        assertEquals("Enter 6 digits", en.homeEnterDigits(6))
+    }
+
+    @Test
+    fun `russian home copy does not retain premium english word`() {
+        assertTrue(HomeCatalog.ru.values.values.none { it.contains("premium", ignoreCase = true) })
+    }
+
+    private fun placeholders(value: String): Set<String> =
+        placeholderPattern.findAll(value).map { it.value }.toSet()
+
+    private companion object {
+        val placeholderPattern = Regex("""%[ds]""")
+    }
+}
diff --git a/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S10_20260725T234710Z.md b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S10_20260725T234710Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..7f2f3128b71023d12c3b07d7306eb77ca0b9dab9
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S10_20260725T234710Z.md
@@ -0,0 +1,39 @@
+# Worker result: INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S10
+
+status: agent_done
+check_status: passed
+next_owner: Integrator
+event: integration_requested
+
+## Summary
+
+Localized the home and shell surfaces in RU and EN without changing the
+localization aggregator or existing navigation contracts. Added complete home,
+PvE/PvP, duel setup/result, status, and bottom-reserve keys. Replaced direct UI
+phrases and manual string concatenation with catalog lookups and typed numeric
+placeholder helpers.
+
+## Changed paths
+
+- `InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/localization/HomeCatalog.kt`
+- `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/common/ScreenBottomReserve.kt`
+- `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/HomeRootScreen.kt`
+- `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/HomeScreen.kt`
+- `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/HomeText.kt`
+- `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PveModesScreen.kt`
+- `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/home/PvpModesScreen.kt`
+- `InplaceX-android/app/src/test/java/com/mirkori/inplacex/ui/screens/home/HomeLocalizationCatalogTest.kt`
+
+## Checks
+
+- `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :app:testDebugUnitTest` — passed (`BUILD SUCCESSFUL`)
+- `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew assembleDebug` — passed (`BUILD SUCCESSFUL`)
+- `git diff --check` — passed
+- allowed-path audit — passed; no forbidden localization provider/config files changed
+
+## Integration notes
+
+The task packet's repaired path points to the existing
+`platform/localization/HomeCatalog.kt`; `StaticLocalizationProvider.kt` was
+left untouched as required. The runner should commit/push this worker branch
+and emit the canonical `integration_requested` event for Integrator.

tokens used
157 478

```
