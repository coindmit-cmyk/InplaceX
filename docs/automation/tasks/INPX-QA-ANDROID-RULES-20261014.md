# INPX-QA-ANDROID-RULES-20261014

Источники: InplaceX-docs/Game/GPT/Core Engine Acceptance.md:27, InplaceX-docs/Game/GPT/Core Engine Acceptance.md:31.

Пробел: Existing state-holder/viewmodel tests cover default configuration, feedback, restoration and timers, but no direct converter assertion exercises the Android duplicate-rule fields. The current bridge and canonical GameConfig are clean on local develop. This is a different physical boundary from the completed GameConfig constructor and BotMatchRules cards; their test paths and goals stay unchanged.

Изменяемый файл: InplaceX-android/app/src/test/java/com/mirkori/inplacex/ui/screens/game/state/GameFieldParametersContractTest.kt. Production InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/game/state/GameFieldUiState.kt и reference InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/model/GameConfig.kt только для чтения.

Критерии:

- Add only the specified new JUnit4 test class after exact Android scope/transport and environment qualification. Observe actual GameFieldMatchParameters.toGameConfig() output. Production and all current owner/existing tests remain read-only; no copied converter or reference calculator.
- One meaningful rule-field method covers explicit enabled and disabled fixtures using literal expected GameConfig values. Enabled fixture: codeLength=8, attemptLimit=17, allowDuplicates=true, forbidAllSameDigitsGuess=false, forbidAdjacentDuplicates=true, forbidTripleDuplicates=true, maxConsecutiveDuplicateDigits=3. Disabled fixture: codeLength=4, attemptLimit=3, allowDuplicates=false, forbidAllSameDigitsGuess=false, forbidAdjacentDuplicates=false, forbidTripleDuplicates=false, maxConsecutiveDuplicateDigits=null. Assert every named canonical field directly; do not compute expected values from the converter or silently replace the runtime source.
- A separate method proves configured turn time survives the boundary as a positive value (61 seconds) and the existing Android zero-disabled sentinel maps to canonical null. Do not assert total-time/UI/hint/boost/entitlement fields as GameConfig members, invent a timer policy or claim Android state recreation.
- Run only :app:testDebugUnitTest --tests com.mirkori.inplacex.ui.screens.game.state.GameFieldParametersContractTest --offline --no-daemon --console=plain and whitespace once a real baseline/adapter is qualified. Require at least two meaningful non-skipped methods, source hashes unchanged, all earlier tests untouched. Test compilation/runtime must not be bypassed or replaced to make this prescribed check green.
- Full Core closure, Android lint/assembly, device/route recreation, integration, provider/SDK activation, persistence/DB work, purchases/ads and owner-paused tasks remain separate NOT_RUN gates. No paid implementation until qualified; all later failures count in the original four-call/0.5-credit scope.

Gates:

- Current bounded test_only_pipeline supports exact JVM source/test pairs, not this Android java-source pair. Qualify a genuine :app unit-test adapter before worker-ready; do not use production patch as a substitute.
- Android testImplementation includes InplaceX-backend. Prior offline backend runtime qualification failed for missing JAR cache; this pass did not rerun it.
- S17/S29 and all existing paused, owner-only, SDK/provider and hardware gates stay unchanged. A new unit packet does not unlock them.

Зависимости задач: нет; общий environment prerequisite не заменяет task authority и не возобновляет capped ServerBot. Ветка ещё не создана; при реальной квалификации использовать текущий локальный develop, затем сохранить ту же ветку/квитанции.

Проверки NOT_RUN, реализация NOT_STARTED, модельных вызовов 0. Planned/needs_task_packet/worker_ready=false. Report: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-ANDROID-RULES-20261014\REPORT.md.

Историческое runtime evidence после восстановления compile cache: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-SERVER-BOT-20261014\environment-repair\offline-check.log. Current cache NOT_RECHECKED; источник сохранённого блокера не считается новой failed проверкой.

## Фактическое отложенное состояние

Original four-call budget exhausted during qualification diagnoses/one allowed paths-only retry; Android test implementation not started.

AAPT2 exact cache fetch прошёл; исходная offline компиляция всё ещё FAILED перед tests, новый classpath resolver FAILED. Последний диагноз не принят по категории blocked, дальнейшего format retry нет. Tests NOT_RUN, implementation NOT_STARTED, four of four calls, estimate 0.096905.

Точное продолжение: Preserve this same task/branch, four paid receipts, every failed baseline and both host init scripts. No fifth call, format/category retry, fresh-run wrapper or new-ID copy in the current packet. A future explicitly authorized continuation must first review the invalid blocked-category host diagnosis and original cap, establish a policy-valid same-project configuration-resolution mechanism, address missing appcompat-resources cache using only already-declared dependencies, and rerun the unchanged original offline compiler command. Implementation and exact unit filter only after real qualification; no previous failed/NOT_RUN state becomes done.
