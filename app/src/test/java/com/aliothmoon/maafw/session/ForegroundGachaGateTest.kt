package com.aliothmoon.maafw.session

import com.aliothmoon.maafw.runner.GachaCollectionRequest
import org.junit.Assert.*
import org.junit.Test

class ForegroundGachaGateTest {
    @Test fun `late lifecycle callback cannot start a cancelled or newer request`() {
        val gate = ForegroundGachaGate()
        val old = gate.reserve(GachaCollectionRequest("default-0", false))!!
        assertNull(gate.reserve(GachaCollectionRequest("default-1", true)))
        assertTrue(gate.cancel(old.token))
        val next = gate.reserve(GachaCollectionRequest("default-1", true))!!
        assertNull(gate.take(old.token))
        assertFalse(gate.cancel(old.token))
        assertEquals(next.request, gate.take(next.token))
        assertNull(gate.take(next.token))
        assertNull(gate.pending.value)
    }

    @Test fun `stop and timeout both invalidate pending startup`() {
        val gate = ForegroundGachaGate()
        val pending = gate.reserve(GachaCollectionRequest("default-0", false))!!
        assertTrue(gate.cancel())
        assertNull(gate.take(pending.token))
        assertFalse(gate.cancel(pending.token))
    }
}
