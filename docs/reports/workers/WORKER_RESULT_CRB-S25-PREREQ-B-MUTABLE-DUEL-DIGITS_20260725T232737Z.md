# Worker Result CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS

- Generated: `2026-07-25T23:27:37Z`
- Worker: `auto-worker-5.5max`
- Model: `gpt-5.6-sol`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5max/crb-s25-prereq-b-mutable-duel-digits/s25-prerequisite-b-mutable-duel-digits-and-zeroi`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-s25-prereq-b-mutable-duel-digits-20260725T231157Z`
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
Задача `CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS` выполнена.

- Добавлены `CharArray`-пути validation/scoring без преобразования в `String`.
- Реализованы single-use mutable-команды с очисткой caller/owned buffers.
- `DuelMatch` больше не хранит raw guesses и очищает секреты при finish, close и unexpected failure.
- Добавлены hostile-тесты zeroization, atomicity, non-ASCII, reflection и bytecode guards.
- Обновлён `CHANGELOG.md`.
- Task_manager и runner-owned lock не изменялись.

Worker report: [WORKER_RESULT_CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS_20260725T232625Z.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5max/crb-s25-prereq-b-mutable-duel-digits-20260725T231157Z/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS_20260725T232625Z.md)

Проверки:

- `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-bot-core:test --rerun-tasks` — passed, `BUILD SUCCESSFUL`.
- `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew :InplaceX-backend:test --rerun-tasks` — passed, `BUILD SUCCESSFUL`.
- `JAVA_HOME=/home/main/.local/jdk21 JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11 ANDROID_HOME=/home/main/.local/android-sdk ANDROID_SDK_ROOT=/home/main/.local/android-sdk bash gradlew verifyProject` — passed, `BUILD SUCCESSFUL`.
- `git diff --check` — passed.

`check_status=passed`
`task_status=agent_done`
`next_owner=Integrator`

```

## stderr

```text
 No newline at end of file
+}
diff --git a/InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/GuessValidatorTest.kt b/InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/GuessValidatorTest.kt
index f9439b676e29eaa1faecaab4fbee1f9fea3995e2..5f87ede69186c22081b53a58c0dff4431dba6280
--- a/InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/GuessValidatorTest.kt
+++ b/InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/GuessValidatorTest.kt
@@ -79,8 +79,59 @@
         )
     }

+    @Test
+    fun mutablePathMatchesStringRulesWithoutChangingTheInput() {
+        val cases = listOf(
+            "123456" to config(),
+            "12345" to config(),
+            "1234a6" to config(),
+            "123\u066456" to config(),
+            "112345" to config(allowDuplicates = false),
+            "111111" to config(),
+            "112345" to config(forbidAdjacentDuplicates = true),
+            "111234" to config(forbidTripleDuplicates = true),
+            "111234" to config(
+                forbidAllSameDigitsGuess = false,
+                forbidAdjacentDuplicates = false,
+                forbidTripleDuplicates = false,
+            ),
+        )
+
+        cases.forEach { (value, gameConfig) ->
+            val mutableValue = value.toCharArray()
+            val expectedInput = mutableValue.copyOf()
+
+            assertEquals(
+                "Typed reason differs for $value",
+                GuessValidator.validateOrReason(value, gameConfig),
+                GuessValidator.validateOrReason(mutableValue, gameConfig),
+            )
+            assertEquals(
+                "Boolean result differs for $value",
+                GuessValidator.validate(value, gameConfig),
+                GuessValidator.validate(mutableValue, gameConfig),
+            )
+            assertTrue(mutableValue.contentEquals(expectedInput))
+        }
+    }
+
+    @Test
+    fun bothPathsRejectNonAsciiDigits() {
+        val nonAsciiDigit = "123\u066456"
+
+        assertEquals(
+            GuessValidationReason.NON_DIGIT,
+            GuessValidator.validateOrReason(nonAsciiDigit, config()),
+        )
+        assertEquals(
+            GuessValidationReason.NON_DIGIT,
+            GuessValidator.validateOrReason(nonAsciiDigit.toCharArray(), config()),
+        )
+    }
+
     private fun config(
         allowDuplicates: Boolean = true,
+        forbidAllSameDigitsGuess: Boolean = true,
         forbidAdjacentDuplicates: Boolean = false,
         forbidTripleDuplicates: Boolean = false,
     ): GameConfig {
@@ -88,7 +139,7 @@
             codeLength = 6,
             allowDuplicates = allowDuplicates,
             attemptLimit = 20,
-            forbidAllSameDigitsGuess = true,
+            forbidAllSameDigitsGuess = forbidAllSameDigitsGuess,
             forbidAdjacentDuplicates = forbidAdjacentDuplicates,
             forbidTripleDuplicates = forbidTripleDuplicates,
         )
diff --git a/InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculatorTest.kt b/InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculatorTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..9645127bc94840b2265a190576afb627b0706aff
--- /dev/null
+++ b/InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculatorTest.kt
@@ -0,0 +1,91 @@
+package com.mirkori.inplacex.core.engine
+
+import java.io.DataInputStream
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertThrows
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class ScoreCalculatorTest {
+
+    @Test
+    fun mutablePathMatchesStringScoringWithoutChangingInputs() {
+        val cases = listOf(
+            "1234" to "1234",
+            "1234" to "1030",
+            "1234" to "4321",
+            "1122" to "1221",
+        )
+
+        cases.forEach { (secret, guess) ->
+            val mutableSecret = secret.toCharArray()
+            val mutableGuess = guess.toCharArray()
+
+            assertEquals(
+                ScoreCalculator.countExactMatches(secret, guess),
+                ScoreCalculator.countExactMatches(mutableSecret, mutableGuess),
+            )
+            assertTrue(mutableSecret.contentEquals(secret.toCharArray()))
+            assertTrue(mutableGuess.contentEquals(guess.toCharArray()))
+        }
+    }
+
+    @Test
+    fun bothPathsRejectDifferentLengths() {
+        assertThrows(IllegalArgumentException::class.java) {
+            ScoreCalculator.countExactMatches("1234", "123")
+        }
+        assertThrows(IllegalArgumentException::class.java) {
+            ScoreCalculator.countExactMatches(
+                charArrayOf('1', '2', '3', '4'),
+                charArrayOf('1', '2', '3'),
+            )
+        }
+    }
+
+    @Test
+    fun engineBytecodeContainsNoMutableDigitToStringConversion() {
+        listOf(GuessValidator::class.java, ScoreCalculator::class.java).forEach { type ->
+            val utf8Entries = classUtf8Entries(type)
+            assertFalse("${type.name} references concatToString", "concatToString" in utf8Entries)
+            assertFalse("${type.name} references String(char[])", "([C)V" in utf8Entries)
+            assertFalse("${type.name} references String(char[], int, int)", "([CII)V" in utf8Entries)
+        }
+    }
+
+    private fun classUtf8Entries(type: Class<*>): Set<String> {
+        val resourceName = "/${type.name.replace('.', '/')}.class"
+        val stream = requireNotNull(type.getResourceAsStream(resourceName))
+        return DataInputStream(stream.buffered()).use { input ->
+            assertEquals(0xCAFEBABE.toInt(), input.readInt())
+            input.readUnsignedShort()
+            input.readUnsignedShort()
+            val entries = linkedSetOf<String>()
+            val constantPoolCount = input.readUnsignedShort()
+            var index = 1
+            while (index < constantPoolCount) {
+                when (val tag = input.readUnsignedByte()) {
+                    1 -> entries += input.readUTF()
+                    3, 4 -> input.readInt()
+                    5, 6 -> {
+                        input.readLong()
+                        index += 1
+                    }
+                    7, 8, 16, 19, 20 -> input.readUnsignedShort()
+                    9, 10, 11, 12, 17, 18 -> {
+                        input.readUnsignedShort()
+                        input.readUnsignedShort()
+                    }
+                    15 -> {
+                        input.readUnsignedByte()
+                        input.readUnsignedShort()
+                    }
+                    else -> throw AssertionError("Unsupported class constant tag $tag")
+                }
+                index += 1
+            }
+            entries
+        }
+    }
+}
diff --git a/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS_20260725T232625Z.md b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS_20260725T232625Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..3be41636f759a4e72e7bef739e77d9a2a1477127
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS_20260725T232625Z.md
@@ -0,0 +1,103 @@
+# Worker result: CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS
+
+## Итог
+
+- Task: `CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS`
+- Worker: `auto-worker-5.5max`
+- Result: `agent_done`
+- `check_status=passed`
+- Next owner: `Integrator`
+- Required event: `integration_requested`
+
+Канонический duel engine переведён на одноразовые mutable digit commands без
+создания immutable plaintext-копий. `DuelMatch` больше не имеет `String`
+overload для секрета или догадки, не сохраняет raw guesses и очищает private
+secret buffers при завершении матча, явном `close()` и неожиданной ошибке.
+
+## Execution authority и freshness
+
+- Immutable execution base:
+  `f0f79da20dbcb0ebdab3c4670e313f268b0d6c2c`.
+- До первой записи `HEAD` и свежий `origin/develop` совпадали с immutable base.
+- Task row имел `worker_ready=true`, `packet_schema_version=2` и
+  `status=in_progress`.
+- Task row, runner-owned lock, worker id, machine id, branch и isolated
+  worktree совпали; lease действовал до `2026-07-26T07:11:54Z`.
+- Task Manager, lock, events и runner-owned state Worker не изменял.
+
+## Реализация
+
+- `GuessValidator` получил parity overload для `CharArray`; обе input-дорожки
+  теперь принимают только ASCII `0..9` и сохраняют прежний порядок typed
+  validation reasons.
+- `ScoreCalculator` получил allocation-free `CharArray` overload с прежней
+  exact-position семантикой.
+- `MutableDuelCommand.Secret` и `.Guess`:
+  - копируют digits во owned buffer;
+  - немедленно очищают caller buffer;
+  - допускают только одно consume;
+  - очищают owned buffer в `finally` на success, rejection и exception;
+  - очищают неиспользованный buffer при `close()`;
+  - не имеют `String` factory/getter и возвращают только redacted `toString()`.
+- `DuelMatch`:
+  - принимает только типизированные mutable-команды;
+  - хранит секреты только в private `CharArray`;
+  - сохраняет в `DuelAttempt` только attacker, exact score и turn number;
+  - очищает оба секрета при win/attempt-limit finish, close и fail-closed
+    exception route;
+  - сохраняет viewer-neutral readiness после terminal zeroization;
+  - отклоняет повторную установку секрета вне setup;
+  - сохраняет atomic state на typed rejection.
+- Добавлены reflection и classfile constant-pool guards против `String(char[])`
+  и `concatToString`, а также hostile zeroization, non-ASCII, config parity,
+  double-consume и state-atomicity тесты.
+- `CHANGELOG.md` обновлён записью о secure mutable duel boundary.
+
+## Изменённые пути
+
+- `InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/GuessValidator.kt`
+- `InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculator.kt`
+- `InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/GuessValidatorTest.kt`
+- `InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculatorTest.kt`
+- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/domain/duel/DuelMatch.kt`
+- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/domain/MutableDuelCommand.kt`
+- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/domain/duel/DuelMatchTest.kt`
+- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/domain/MutableDuelCommandTest.kt`
+- `CHANGELOG.md`
+- этот Worker report.
+
+## Проверки
+
+Process-only environment:
+
+```text
+JAVA_HOME=/home/main/.local/jdk21
+JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
+ANDROID_HOME=/home/main/.local/android-sdk
+ANDROID_SDK_ROOT=/home/main/.local/android-sdk
+```
+
+1. `bash gradlew :InplaceX-bot-core:test --rerun-tasks`
+   — `PASSED`, `BUILD SUCCESSFUL`.
+2. `bash gradlew :InplaceX-backend:test --rerun-tasks`
+   — final run `PASSED`, `BUILD SUCCESSFUL`.
+3. `bash gradlew verifyProject`
+   — `PASSED`, `BUILD SUCCESSFUL`.
+4. `git diff --check`
+   — `PASSED`.
+
+Во время разработки первый backend compile выявил недоступность private nested
+constructor, а следующий тестовый прогон — ложноположительный descriptor-only
+bytecode guard. Оба дефекта исправлены до финальных обязательных прогонов:
+фабрики остались ownership-safe, а guard теперь разрешает собственный
+`CharArray` constructor и отклоняет только MethodRef на
+`java/lang/String.<init>([C...)`.
+
+## Cleanup и handoff
+
+- Временные файлы и фоновые процессы не создавались.
+- Worktree и branch оставлены центральному runner для commit/push.
+- Runner-owned lock оставлен активным и не изменялся.
+- Cleanup candidates отсутствуют.
+- Integrator должен выполнить independent strong review и принять
+  `integration_requested` handoff; direct merge не авторизован.

tokens used
228 395

```
