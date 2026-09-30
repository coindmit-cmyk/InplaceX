package com.mirkori.inplacex.ui.screens.game.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mirkori.inplacex.MainActivity
import com.mirkori.inplacex.core.analysis.AcceptedAttemptEvidence
import com.mirkori.inplacex.core.analysis.DeductionResult
import com.mirkori.inplacex.core.analysis.ManualHypothesis
import com.mirkori.inplacex.core.analysis.ProvenFact
import com.mirkori.inplacex.ui.viewmodel.GameFieldViewModel
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameFieldActivityRecreationTest {
    @Test
    fun activityRecreationPreservesSecretAttemptsPartialInputAndAnalysis() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activeViewModel = AtomicReference<GameFieldViewModel>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.obtainGameFieldViewModel(activeViewModel, initialSecret = "1234")
            instrumentation.waitForIdleSync()

            val source = checkNotNull(activeViewModel.get())
            source.submit("5678")
            source.dispatch(GameFieldEvent.DigitEntered('9'))
            source.dispatch(
                GameFieldEvent.ManualMarkChanged(
                    position = 1,
                    symbol = '7',
                    type = GameFieldManualMarkType.MAYBE,
                ),
            )
            source.dispatch(GameFieldEvent.OpenPositionHintRequested(position = 2))
            val expected = source.snapshotForRecreation()

            activeViewModel.set(null)
            scenario.recreate()
            scenario.obtainGameFieldViewModel(activeViewModel, initialSecret = "9999")
            instrumentation.waitForIdleSync()

            assertEquals(expected, checkNotNull(activeViewModel.get()).snapshotForRecreation())
        }
    }

    private fun ActivityScenario<MainActivity>.obtainGameFieldViewModel(
        destination: AtomicReference<GameFieldViewModel>,
        initialSecret: String,
    ) {
        onActivity { activity ->
            val provider = ViewModelProvider(
                owner = activity,
                factory = GameFieldRecreationViewModelFactory(initialSecret),
            )
            destination.set(provider[VIEW_MODEL_KEY, GameFieldViewModel::class.java])
        }
    }

    private companion object {
        const val VIEW_MODEL_KEY = "game-field-recreation-test"
    }
}

private class GameFieldRecreationViewModelFactory(
    private val initialSecret: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T {
        return GameFieldViewModel(
            savedStateHandle = extras.createSavedStateHandle(),
            parameters = RECREATION_PARAMETERS,
            initialSecret = initialSecret,
        ) as T
    }
}

private data class RecreationSnapshot(
    val secret: String,
    val attempts: List<String>,
    val input: List<Char?>,
    val manualMarks: List<GameFieldManualMark>,
    val acceptedAttempts: List<AcceptedAttemptEvidence>,
    val hypotheses: List<ManualHypothesis>,
    val provenFacts: Set<ProvenFact>,
    val deduction: DeductionResult,
)

private fun GameFieldViewModel.snapshotForRecreation(): RecreationSnapshot {
    val state = uiState.value
    return RecreationSnapshot(
        secret = state.match.debugSecret,
        attempts = state.match.attempts.map { it.guess },
        input = state.input.slots,
        manualMarks = state.manualMarks,
        acceptedAttempts = state.evidence.acceptedAttempts,
        hypotheses = state.evidence.hypotheses,
        provenFacts = state.evidence.provenFacts,
        deduction = state.evidence.deduction,
    )
}

private val RECREATION_PARAMETERS = GameFieldMatchParameters(
    codeLength = 4,
    attemptLimit = 12,
)
