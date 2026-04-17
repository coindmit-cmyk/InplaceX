package com.mirkori.inplacex.ui.utils

import com.mirkori.inplacex.core.model.AnalysisBoardState
import com.mirkori.inplacex.core.model.AnalysisCellState

fun buildKnownDigitsFromAnalysis(
    analysisBoard: AnalysisBoardState
): List<Char?> {
    return List(analysisBoard.codeLength) { position ->
        val yesDigits = (0..9).filter { digit ->
            analysisBoard.cells[digit][position] == AnalysisCellState.YES
        }

        if (yesDigits.size == 1) {
            yesDigits.first().digitToChar()
        } else {
            null
        }
    }
}
