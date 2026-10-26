package com.aliothmoon.maafw.inventory

/** 缺失、未识别和冲突都不是 0；仅两侧可信的整数库存可以相减。 */
data class InventoryComparisonRow(
    val key: String,
    val name: String,
    val previousText: String,
    val currentText: String,
    val delta: Long?,
    val reason: String = "",
) {
    val deltaText: String get() = delta?.let { if (it > 0) "+$it" else it.toString() } ?: "不可比较"
}

object InventoryComparison {
    fun compare(previous: InventorySnapshot, current: InventorySnapshot): List<InventoryComparisonRow> {
        val before = quantities(previous)
        val after = quantities(current)
        return (before.keys + after.keys).map { key ->
            val old = before[key]
            val new = after[key]
            val delta = if (old?.value != null && new?.value != null) new.value - old.value else null
            InventoryComparisonRow(
                key = key,
                name = new?.name ?: old!!.name,
                previousText = old?.text ?: "快照中缺失",
                currentText = new?.text ?: "快照中缺失",
                delta = delta,
                reason = if (delta != null) "" else when {
                    old == null || new == null -> "缺失不代表库存为零"
                    old.unknown || new.unknown -> "未知物品无法确认身份"
                    else -> "存在未识别数量或数量冲突"
                },
            )
        }
    }

    private data class Quantity(val name: String, val text: String, val value: Long?, val unknown: Boolean = false)

    private fun quantities(snapshot: InventorySnapshot): Map<String, Quantity> = buildMap {
        snapshot.currencies.forEach { (name, count) ->
            put("currency:$name", Quantity(name, count?.toString() ?: "未识别", count))
        }
        snapshot.items.forEach { item ->
            // 币若同时出现在顶栏与格子，以格子的冲突/未知语义为准，避免忽略冲突。
            val key = if (item.name in snapshot.currencies || item.name in currencyNames) "currency:${item.name}"
                else "${item.category}:${item.id}"
            put(key, Quantity(item.name, item.quantityText,
                item.quantity.takeIf { !item.unknown && item.conflicts.isEmpty() }, item.unknown))
        }
    }

    private val currencyNames = setOf("异方晶", "狄斯币", "搜索令", "都市搜索令")
}
