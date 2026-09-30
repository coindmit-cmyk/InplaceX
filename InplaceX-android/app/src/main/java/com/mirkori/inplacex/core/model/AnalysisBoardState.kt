package com.mirkori.inplacex.core.model

data class AnalysisBoardState(
    val codeLength: Int,
    val cells: List<List<AnalysisCellState>> = List(10) {
        List(codeLength) { AnalysisCellState.EMPTY }
    }
) {
    fun toggleCell(digit: Int, position: Int): AnalysisBoardState {
        if (digit !in 0..9) return this
        if (position !in 0 until codeLength) return this

        val updatedRows = cells.mapIndexed { rowIndex, row ->
            if (rowIndex != digit) {
                row
            } else {
                row.mapIndexed { colIndex, cell ->
                    if (colIndex == position) cell.next() else cell
                }
            }
        }

        return copy(cells = updatedRows)
    }

    companion object {
        fun create(codeLength: Int): AnalysisBoardState {
            return AnalysisBoardState(codeLength = codeLength)
        }
    }
}