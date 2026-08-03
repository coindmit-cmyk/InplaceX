package com.mirkori.inplacex.ui.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Composition-scoped guard for work that cannot survive Activity recreation.
 *
 * The gate is deliberately remembered, never saveable. A generation token prevents a late
 * completion from mutating UI after the operation was cancelled or superseded.
 */
@Stable
internal class TransientOperationGate {
    private var generation by mutableLongStateOf(0L)
    private var activeGeneration by mutableStateOf<Long?>(null)

    val inProgress: Boolean
        get() = activeGeneration != null

    fun start(): Long? {
        if (activeGeneration != null) return null
        generation += 1L
        return generation.also { activeGeneration = it }
    }

    fun isCurrent(operationId: Long): Boolean = activeGeneration == operationId

    fun finish(operationId: Long): Boolean {
        if (!isCurrent(operationId)) return false
        activeGeneration = null
        return true
    }

    fun cancel() {
        generation += 1L
        activeGeneration = null
    }
}
