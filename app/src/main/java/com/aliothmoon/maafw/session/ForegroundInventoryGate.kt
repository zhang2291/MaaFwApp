package com.aliothmoon.maafw.session

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PendingForegroundInventory(val token: String, val request: Unit)

/** One-shot ownership: stale lifecycle callbacks cannot start a cancelled or newer capture. */
internal class ForegroundInventoryGate {
    private val state = MutableStateFlow<PendingForegroundInventory?>(null)
    val pending = state.asStateFlow()

    fun reserve(request: Unit): PendingForegroundInventory? {
        val value = PendingForegroundInventory(UUID.randomUUID().toString(), request)
        return if (state.compareAndSet(null, value)) value else null
    }

    fun take(token: String): Unit? {
        val value = state.value ?: return null
        return if (value.token == token && state.compareAndSet(value, null)) value.request else null
    }

    fun cancel(token: String? = null): Boolean {
        val value = state.value ?: return false
        return (token == null || value.token == token) && state.compareAndSet(value, null)
    }
}
