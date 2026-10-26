package com.aliothmoon.maafw.inventory

import org.junit.Assert.*
import org.junit.Test

class InventoryComparisonTest {
    private fun item(id: String, quantity: Long?, conflicts: List<String> = emptyList(), unknown: Boolean = false) =
        InventoryItem(id, id, "道具", quantity, conflicts = conflicts, unknown = unknown)

    private fun snapshot(vararg items: InventoryItem, currencies: Map<String, Long?> = emptyMap(), status: String = "partial") =
        InventorySnapshot("2026-10-22T23:39:30+08:00", status, currencies,
            listOf(InventoryCategory("道具", status, "")), items.toList(), "{}")

    @Test fun `confirmed quantities include spending zero and unchanged in partial snapshots`() {
        val previous = snapshot(item("spent", 15), item("gained", 3), item("same", 4), item("zero", 0))
        val current = snapshot(item("spent", 2), item("gained", 8), item("same", 4), item("zero", 0))
        val rows = InventoryComparison.compare(previous, current).associateBy { it.name }
        assertEquals(-13L, rows["spent"]!!.delta)
        assertEquals("-13", rows["spent"]!!.deltaText)
        assertEquals("+5", rows["gained"]!!.deltaText)
        assertEquals(0L, rows["same"]!!.delta)
        assertEquals(0L, rows["zero"]!!.delta)
    }

    @Test fun `missing null conflict and unknown never become zero even for complete snapshots`() {
        val previous = snapshot(item("gone", 5), item("null", null), item("conflict", 5, listOf("5", "7")),
            item("unknown", 3, unknown = true), status = "complete")
        val current = snapshot(item("new", 2), item("null", 4), item("conflict", 6),
            item("unknown", 10, unknown = true), status = "complete")
        val rows = InventoryComparison.compare(previous, current)
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.delta == null && it.deltaText == "不可比较" })
        assertEquals("快照中缺失", rows.first { it.name == "gone" }.currentText)
        assertEquals("快照中缺失", rows.first { it.name == "new" }.previousText)
        assertEquals("数量冲突：5 / 7", rows.first { it.name == "conflict" }.previousText)
    }

    @Test fun `currencies and both ticket types are compared once`() {
        val previous = snapshot(item("搜索令", 17), item("都市搜索令", 5),
            currencies = mapOf("异方晶" to 41514L, "狄斯币" to 200L))
        val current = snapshot(item("搜索令", 14), item("都市搜索令", 6),
            currencies = mapOf("异方晶" to 41000L, "狄斯币" to 150L))
        val rows = InventoryComparison.compare(previous, current).associateBy { it.name }
        assertEquals(4, rows.size)
        assertEquals(-514L, rows["异方晶"]!!.delta)
        assertEquals(-50L, rows["狄斯币"]!!.delta)
        assertEquals(-3L, rows["搜索令"]!!.delta)
        assertEquals(1L, rows["都市搜索令"]!!.delta)
    }

    @Test fun `currency duplicates preserve conflicts and null currency readings stay unknown`() {
        val previous = snapshot(item("狄斯币", 100), currencies = mapOf("狄斯币" to 100L, "异方晶" to null))
        val current = snapshot(item("狄斯币", 120, listOf("120", "125")),
            currencies = mapOf("狄斯币" to 120L, "异方晶" to 1000L))
        val rows = InventoryComparison.compare(previous, current)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.delta == null })
    }
}
