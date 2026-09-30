package com.mirkori.inplacex.core.model

enum class AnalysisCellState {
    EMPTY,
    NO,
    MAYBE,
    YES;

    fun next(): AnalysisCellState {
        return when (this) {
            EMPTY -> NO
            NO -> MAYBE
            MAYBE -> YES
            YES -> EMPTY
        }
    }
}