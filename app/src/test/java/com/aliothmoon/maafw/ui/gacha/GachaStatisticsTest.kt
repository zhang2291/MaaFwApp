package com.aliothmoon.maafw.ui.gacha

import com.aliothmoon.maafw.gacha.GachaRecord
import com.aliothmoon.maafw.gacha.GachaStore
import com.aliothmoon.maafw.gacha.UpBannerCatalog
import com.aliothmoon.maafw.gacha.UpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GachaStatisticsTest {
    private fun record(index: Int, time: String, pool: String, rarity: String, categoryId: String? = null): GachaRecord {
        val cells = listOf(time, pool, "角色$index")
        return GachaRecord(index = index, key = GachaStore.key(cells), cells = cells, type = "活动", rarity = rarity, categoryId = categoryId)
    }

    @Test fun hitsAcrossCategoriesIsolatesIntervalsAndFirstGoldPerCategory() {
        val catA = listOf(
            record(10, "2026-10-15 10:00:01", "新角色池", "普", categoryId = "default-0"),
            record(9, "2026-10-15 10:00:02", "新角色池", "狂", categoryId = "default-0"),
        )
        val catB = listOf(
            record(8, "2026-10-15 10:00:03", "复刻池", "狂", categoryId = "default-4"),
            record(7, "2026-10-15 10:00:04", "复刻池", "普", categoryId = "default-4"),
            record(6, "2026-10-15 10:00:05", "复刻池", "狂", categoryId = "default-4"),
        )
        val merged = GachaStatistics.hitsAcrossCategories(catA + catB)
        val expected = (GachaStatistics.hits(catA) + GachaStatistics.hits(catB))
            .sortedWith(compareByDescending<GachaHit> { it.record.time }.thenBy { it.record.index })

        assertEquals(expected.map { Triple(it.record.index, it.interval, it.isFirstGold) },
            merged.map { Triple(it.record.index, it.interval, it.isFirstGold) })
    }

    @Test fun intervalsCountAcrossActualPoolsWithinOneCategory() {
        val rows = listOf(
            record(5, "2026-10-15 10:00:01", "A", "普"),
            record(4, "2026-10-15 10:00:02", "A", "狂"),
            record(3, "2026-10-15 10:00:03", "B", "普"),
            record(2, "2026-10-15 10:00:04", "B", "狂"),
            record(1, "2026-10-15 10:00:05", "C", "狂"),
        )
        assertEquals(listOf(1, 2, 2), GachaStatistics.hits(rows).map { it.interval })
        assertEquals(listOf("C", "B", "A"), GachaStatistics.pools(rows))
        assertEquals("1.7", GachaStatistics.totals(rows).average)
    }

    @Test fun identicalTimestampUsesArchiveSequenceAndNoGoldAverageIsUnknown() {
        val rows = listOf(
            record(1, "2026-10-15 10:00:00", "B", "狂"),
            record(2, "2026-10-15 10:00:00", "A", "普"),
            record(3, "2026-10-15 10:00:00", "A", "狂"),
        )
        assertEquals(listOf(2, 1), GachaStatistics.hits(rows).map { it.interval })
        assertEquals("—", GachaStatistics.totals(emptyList()).average)
    }

    @Test fun pityCountsPullsSinceLatestGold() {
        val rows = listOf(
            record(5, "2026-10-15 10:00:05", "A", "普"),
            record(4, "2026-10-15 10:00:04", "A", "普"),
            record(3, "2026-10-15 10:00:03", "B", "狂"),
            record(2, "2026-10-15 10:00:02", "B", "普"),
            record(1, "2026-10-15 10:00:01", "C", "狂"),
        )
        // 2 pulls after the latest gold at 10:00:03
        assertEquals(2, GachaStatistics.pity(rows))
    }

    @Test fun groupsMergeSameSecondAndIntervalsExcludeFirstGold() {
        val rows = listOf(
            record(1, "2026-10-15 10:00:10", "A", "狂"),
            record(2, "2026-10-15 10:00:10", "A", "危"),
            record(3, "2026-10-15 10:00:10", "A", "普"),
            record(4, "2026-10-15 10:00:01", "A", "狂"),
        )
        val groups = GachaStatistics.groups(rows)
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].size)
        assertEquals(1, groups[0].gold)
        assertEquals(1, groups[0].purple)
        assertEquals("活动", GachaStatistics.gameType(rows))
        assertEquals("50.0%", GachaStatistics.totals(rows).rate)
        val stats = GachaStatistics.intervals(GachaStatistics.hits(rows))
        // Older gold is first gold (excluded); newer gold has interval 3 -> bucket 0
        assertEquals(3, stats.median)
        assertEquals(1, stats.buckets[0])
    }

    @Test fun upBannerCatalogNormalizesPoolsAndSkipsStandardOrUnknown() {
        val catalog = UpBannerCatalog.parse(
            """
            {
              "version": 1,
              "standard_pools": ["混沌都市"],
              "banners": {
                "慈·爱·愈": ["莎缇拉"],
                "诸道无我": ["承霄"]
              }
            }
            """.trimIndent(),
        )
        assertEquals(UpStatus.UP, catalog.classify("慈 • 爱 · 愈", "活动", "莎缇拉"))
        assertEquals(UpStatus.OFF, catalog.classify("诸道无我", "活动", "诺克斯"))
        assertNull(catalog.classify("混沌都市", "常驻", "诺克斯"))
        assertNull(catalog.classify("未知新池", "活动", "某角色"))

        val records = listOf(
            record(1, "2026-10-15 10:00:01", "慈·爱·愈", "狂").copy(cells = listOf("2026-10-15 10:00:01", "慈·爱·愈", "莎缇拉")),
            record(2, "2026-10-15 10:00:02", "诸道无我", "狂").copy(cells = listOf("2026-10-15 10:00:02", "诸道无我", "诺克斯")),
            record(3, "2026-10-15 10:00:03", "未知新池", "狂").copy(cells = listOf("2026-10-15 10:00:03", "未知新池", "某角色")),
        )
        val hits = GachaStatistics.hits(records) { r -> catalog.classify(r.pool, r.type, r.name) }
        val up = GachaStatistics.upStats(hits)
        assertEquals(1, up.wins)
        assertEquals(1, up.losses)
        assertEquals(0, up.guaranteed)
        assertEquals("50.0%", up.rate)
        assertEquals("1 不歪 / 1 歪", up.summary)
    }

    @Test fun guaranteedUpAfterLossIsExcludedAndIsolatedPerCategory() {
        val catalog = UpBannerCatalog.parse(
            """
            {
              "version": 1,
              "standard_pools": [],
              "banners": {
                "诸道无我": ["承霄"],
                "光影留痕": ["黛伦"]
              }
            }
            """.trimIndent(),
        )
        // Category 1: 歪 -> UP(大保底) -> 歪 -> UP(大保底) => 0 wins / 2 losses => 0.0%
        val cat1 = listOf(
            record(1, "2026-10-15 10:00:01", "诸道无我", "狂", "default-0").copy(cells = listOf("2026-10-15 10:00:01", "诸道无我", "诺克斯")),
            record(2, "2026-10-15 10:00:02", "诸道无我", "狂", "default-0").copy(cells = listOf("2026-10-15 10:00:02", "诸道无我", "承霄")),
            record(3, "2026-10-15 10:00:03", "诸道无我", "狂", "default-0").copy(cells = listOf("2026-10-15 10:00:03", "诸道无我", "卓娅")),
            record(4, "2026-10-15 10:00:04", "诸道无我", "狂", "default-0").copy(cells = listOf("2026-10-15 10:00:04", "诸道无我", "承霄")),
        )
        // Category 2 (interleaved timestamps): UP -> 歪 -> UP(大保底) -> UP => 2 wins / 1 loss => 66.7%
        val cat2 = listOf(
            record(5, "2026-10-15 10:00:01", "光影留痕", "狂", "default-4").copy(cells = listOf("2026-10-15 10:00:01", "光影留痕", "黛伦")),
            record(6, "2026-10-15 10:00:02", "光影留痕", "狂", "default-4").copy(cells = listOf("2026-10-15 10:00:02", "光影留痕", "观星者")),
            record(7, "2026-10-15 10:00:03", "光影留痕", "狂", "default-4").copy(cells = listOf("2026-10-15 10:00:03", "光影留痕", "黛伦")),
            record(8, "2026-10-15 10:00:05", "光影留痕", "狂", "default-4").copy(cells = listOf("2026-10-15 10:00:05", "光影留痕", "黛伦")),
        )

        val resolver: (GachaRecord) -> UpStatus? = { r -> catalog.classify(r.pool, r.type, r.name) }
        val hits1 = GachaStatistics.hits(cat1, resolver)
        val stats1 = GachaStatistics.upStats(hits1)
        assertEquals(0, stats1.wins)
        assertEquals(2, stats1.losses)
        assertEquals(2, stats1.guaranteed)
        assertEquals("0.0%", stats1.rate)
        assertEquals(listOf(UpStatus.GUARANTEED, UpStatus.OFF, UpStatus.GUARANTEED, UpStatus.OFF), hits1.map { it.upStatus })

        val hits2 = GachaStatistics.hits(cat2, resolver)
        val stats2 = GachaStatistics.upStats(hits2)
        assertEquals(2, stats2.wins)
        assertEquals(1, stats2.losses)
        assertEquals(1, stats2.guaranteed)
        assertEquals("66.7%", stats2.rate)
        assertEquals("2 不歪 / 1 歪 · 1 大保底", stats2.summary)

        val merged = GachaStatistics.hitsAcrossCategories(cat1 + cat2, resolver)
        val mergedStats = GachaStatistics.upStats(merged)
        assertEquals(2, mergedStats.wins)
        assertEquals(3, mergedStats.losses)
        assertEquals(3, mergedStats.guaranteed)
        assertEquals("40.0%", mergedStats.rate)
    }

    @Test fun expectedUpPullsExcludesUntrackedPoolsAndFirstGold() {
        val catalog = UpBannerCatalog.parse(
            """
            {
              "version": 1,
              "standard_pools": ["混沌都市"],
              "banners": {
                "诸道无我": ["承霄"]
              }
            }
            """.trimIndent(),
        )
        // Category 1 (tracked + one unknown pool that must be excluded):
        // 1st gold (excluded from known avg): 诺克斯 (1 pull -> ignored in interval avg, but counts as OFF)
        // Actually put 诺克斯 as 2nd gold (40 pulls), 承霄 as 3rd (20 pulls, GUARANTEED), 承霄 as 4th (30 pulls, UP)
        // Tracked known intervals = [40, 20, 30] -> avg = 30.0; wins=1, losses=1 -> mult = 1.5 -> expectedUp = 45.0
        val trackedRows = mutableListOf<GachaRecord>()
        var seq = 100
        fun addRows(count: Int, pool: String, rarity: String, name: String, categoryId: String, type: String = "活动") {
            repeat(count) { i ->
                val ts = String.format("2026-10-15 10:%02d:%02d", (seq / 60), (seq % 60))
                seq++
                val cells = listOf(ts, pool, name)
                trackedRows += GachaRecord(index = 1000 - seq, key = GachaStore.key(cells), cells = cells, type = type, rarity = rarity, categoryId = categoryId)
            }
        }
        addRows(1, "诸道无我", "普", "垫刀", "default-0")
        addRows(1, "诸道无我", "狂", "承霄", "default-0") // isFirstGold = true -> UP win #1, excluded from interval avg
        addRows(39, "诸道无我", "普", "垫刀", "default-0")
        addRows(1, "诸道无我", "狂", "诺克斯", "default-0") // interval 40 -> OFF #1
        addRows(19, "诸道无我", "普", "垫刀", "default-0")
        addRows(1, "诸道无我", "狂", "承霄", "default-0") // interval 20 -> GUARANTEED
        addRows(29, "诸道无我", "普", "垫刀", "default-0")
        addRows(1, "诸道无我", "狂", "承霄", "default-0") // interval 30 -> UP win #2
        // Untracked pool in same category & custom-named standard category (must be excluded completely)
        addRows(79, "未知池", "普", "垫刀", "default-0")
        addRows(1, "未知池", "狂", "卓娅", "default-0")
        addRows(1, "混沌都市", "狂", "卓娅", "custom-std", type = "常规")
        addRows(79, "混沌都市", "普", "垫刀", "custom-std", type = "常规")
        addRows(1, "混沌都市", "狂", "席宁", "custom-std", type = "常规")

        val resolver: (GachaRecord) -> UpStatus? = { r -> catalog.classify(r.pool, r.type, r.name) }
        val hits = GachaStatistics.hitsAcrossCategories(trackedRows, resolver)
        val stats = GachaStatistics.upStats(hits)
        // wins=2, losses=1, total=3 -> mult = 2 - 2/3 = 4/3; known tracked intervals = [40, 20, 30] -> avg = 30.0 -> expected = 40.0
        assertEquals(2, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(1, stats.guaranteed)
        assertEquals(3, stats.trackedKnownCount)
        assertEquals(30.0, stats.trackedAvgGold!!, 1e-6)
        assertEquals(40.0, stats.expectedUpPulls!!, 1e-6)
        assertEquals("40.0 抽", stats.expectedUpText)

        // Standard-only subset returns null / "—"
        val stdOnly = GachaStatistics.upStats(GachaStatistics.hits(trackedRows.filter { it.categoryId == "custom-std" }, resolver))
        assertNull(stdOnly.expectedUpPulls)
        assertEquals("—", stdOnly.expectedUpText)
        assertEquals("未判定池不纳入计算", stdOnly.expectedUpSummary)
    }
}
