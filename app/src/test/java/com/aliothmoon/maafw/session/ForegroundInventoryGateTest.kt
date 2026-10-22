package com.aliothmoon.maafw.session

import org.junit.Assert.*
import org.junit.Test

class ForegroundInventoryGateTest {
    @Test fun `late lifecycle callback cannot start a cancelled or newer request`() {
        val gate = ForegroundInventoryGate()
        val old = gate.reserve(Unit)!!
        assertNull(gate.reserve(Unit))
        assertTrue(gate.cancel(old.token))
        val next = gate.reserve(Unit)!!
        assertNull(gate.take(old.token))
        assertFalse(gate.cancel(old.token))
        assertEquals(next.request, gate.take(next.token))
        assertNull(gate.take(next.token))
        assertNull(gate.pending.value)
    }

    @Test fun `stop and timeout both invalidate pending startup`() {
        val gate = ForegroundInventoryGate()
        val pending = gate.reserve(Unit)!!
        assertTrue(gate.cancel())
        assertNull(gate.take(pending.token))
        assertFalse(gate.cancel(pending.token))
    }
}
