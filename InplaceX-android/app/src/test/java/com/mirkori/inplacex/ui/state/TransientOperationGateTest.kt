package com.mirkori.inplacex.ui.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientOperationGateTest {
    @Test
    fun duplicateStartIsRejectedUntilCurrentOperationFinishes() {
        val gate = TransientOperationGate()
        val operationId = requireNotNull(gate.start())

        assertTrue(gate.inProgress)
        assertNull(gate.start())
        assertTrue(gate.finish(operationId))
        assertFalse(gate.inProgress)
        assertNotNull(gate.start())
    }

    @Test
    fun cancelledOperationCannotFinishOrOverwriteNewOperation() {
        val gate = TransientOperationGate()
        val cancelledId = requireNotNull(gate.start())

        gate.cancel()
        val currentId = requireNotNull(gate.start())

        assertNotEquals(cancelledId, currentId)
        assertFalse(gate.isCurrent(cancelledId))
        assertFalse(gate.finish(cancelledId))
        assertTrue(gate.isCurrent(currentId))
        assertTrue(gate.inProgress)
    }
}
