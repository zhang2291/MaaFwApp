package com.aliothmoon.maafw.ui.gacha

import com.aliothmoon.maafw.gacha.GachaRecord
import com.aliothmoon.maafw.gacha.GachaStore
import org.junit.Assert.assertEquals
import org.junit.Test

class GachaStatisticsTest {
    private fun record(index: Int, time: String, pool: String, rarity: String): GachaRecord {
        val cells = listOf(time, pool, "角色$index")
        return GachaRecord(index = index, key = GachaStore.key(cells), cells = cells, type = "活动", rarity = rarity)
    }

    @Test fun intervalsCountAcrossActualPoolsWithinOneCategory() {
        val rows = listOf(
            record(5, "2026-10-15 10:00:01", "A", "普"),
            record(4, "2026-10-15 10:00:02", "A", "狂"),
            record(3, "2026-10-15 10:00:03", "B", "普"),
            record(2, "2026-10-15 10:00:04", "B", "狂"),
            record(1, "2026-10-15 10:00:05", "C", "狂"),
        )
        assertEquals(listOf(1, 2, null), GachaStatistics.hits(rows).map { it.interval })
        assertEquals(listOf("C", "B", "A"), GachaStatistics.pools(rows))
        assertEquals("1.7", GachaStatistics.totals(rows).average)
    }

    @Test fun identicalTimestampUsesArchiveSequenceAndNoGoldAverageIsUnknown() {
        val rows = listOf(
            record(1, "2026-10-15 10:00:00", "B", "狂"),
            record(2, "2026-10-15 10:00:00", "A", "普"),
            record(3, "2026-10-15 10:00:00", "A", "狂"),
        )
        assertEquals(listOf(2, null), GachaStatistics.hits(rows).map { it.interval })
        assertEquals("—", GachaStatistics.totals(emptyList()).average)
    }
}
