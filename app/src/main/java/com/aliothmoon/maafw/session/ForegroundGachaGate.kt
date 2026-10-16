package com.aliothmoon.maafw.session

import com.aliothmoon.maafw.runner.GachaCollectionRequest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PendingForegroundGacha(val token: String, val request: GachaCollectionRequest)

/** One-shot ownership: stale lifecycle callbacks cannot start a cancelled or newer capture. */
internal class ForegroundGachaGate {
    private val state = MutableStateFlow<PendingForegroundGacha?>(null)
    val pending = state.asStateFlow()

    fun reserve(request: GachaCollectionRequest): PendingForegroundGacha? {
        val value = PendingForegroundGacha(UUID.randomUUID().toString(), request)
        return if (state.compareAndSet(null, value)) value else null
    }

    fun take(token: String): GachaCollectionRequest? {
        val value = state.value ?: return null
        return if (value.token == token && state.compareAndSet(value, null)) value.request else null
    }

    fun cancel(token: String? = null): Boolean {
        val value = state.value ?: return false
        return (token == null || value.token == token) && state.compareAndSet(value, null)
    }
}
