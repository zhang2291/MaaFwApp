package com.aliothmoon.maafw.ui.gacha

import com.aliothmoon.maafw.gacha.GachaRecord
import com.aliothmoon.maafw.gacha.UpStatus
import java.util.Locale

data class GachaTotals(val pulls: Int, val gold: Int) {
    val average: String get() = if (gold == 0) "—" else String.format(Locale.ROOT, "%.1f", pulls.toDouble() / gold)
    val rate: String get() = if (pulls == 0) "—" else String.format(Locale.ROOT, "%.1f%%", gold * 100.0 / pulls)
}

data class GachaUpStats(
    val wins: Int,
    val losses: Int,
    val guaranteed: Int = 0,
    val trackedKnownCount: Int = 0,
    val trackedAvgGold: Double? = null,
) {
    val total: Int get() = wins + losses
    val rate: String get() = if (total == 0) "—" else String.format(Locale.ROOT, "%.1f%%", wins * 100.0 / total)
    val expectedGoldsPerUp: Double? get() = if (total == 0) null else 2.0 - (wins.toDouble() / total.toDouble())
    val expectedUpPulls: Double?
        get() {
            val avg = trackedAvgGold ?: return null
            val mult = expectedGoldsPerUp ?: return null
            return avg * mult
        }
    val expectedUpText: String
        get() = expectedUpPulls?.let { String.format(Locale.ROOT, "%.1f 抽", it) } ?: "—"
    val expectedUpSummary: String
        get() {
            val avg = trackedAvgGold ?: return "未判定池不纳入计算"
            val mult = expectedGoldsPerUp ?: return "未判定池不纳入计算"
            return String.format(Locale.ROOT, "均金 %.1f × %.2f（计 %d 狂）", avg, mult, trackedKnownCount)
        }
    val summary: String get() = when {
        total > 0 && guaranteed > 0 -> "$wins 不歪 / $losses 歪 · $guaranteed 大保底"
        total > 0 -> "$wins 不歪 / $losses 歪"
        guaranteed > 0 -> "$guaranteed 大保底"
        else -> ""
    }
}

data class GachaHit(
    val record: GachaRecord,
    val interval: Int,
    val isFirstGold: Boolean = false,
    val upStatus: UpStatus? = null,
)

/** One game action: records sharing the same second and pool (ten-pull or single pull). Newest first inside. */
data class GachaPullGroup(val time: String, val pool: String, val records: List<GachaRecord>) {
    val size: Int get() = records.size
    val gold: Int get() = records.count { it.rarity == "狂" }
    val purple: Int get() = records.count { it.rarity == "危" }
    val best: GachaRecord get() = records.firstOrNull { it.rarity == "狂" } ?: records.firstOrNull { it.rarity == "危" } ?: records.first()
}

/** Interval distribution of known (non-first) gold pulls. */
data class GachaIntervalStats(val buckets: List<Int>, val median: Int?, val best: Int?, val worst: Int?)

object GachaStatistics {
    const val HARD_PITY = 80
    const val SOFT_PITY = 65

    val newestFirst: Comparator<GachaRecord> = compareByDescending<GachaRecord> { it.time }.thenBy { it.index }
    fun totals(records: List<GachaRecord>) = GachaTotals(records.size, records.count { it.rarity == "狂" })
    fun pools(records: List<GachaRecord>): List<String> = records.sortedWith(newestFirst).map { it.pool }.distinct()

    /** Input must be one user-defined category. A pool-name filter is applied AFTER computing intervals & guarantees. */
    fun hits(
        records: List<GachaRecord>,
        upResolver: ((GachaRecord) -> UpStatus?)? = null,
    ): List<GachaHit> {
        var sinceGold = 0
        var previousGold = false
        var guaranteedNext = false
        val hits = mutableListOf<GachaHit>()
        records.sortedWith(compareBy<GachaRecord> { it.time }.thenByDescending { it.index }).forEach {
            sinceGold++
            if (it.rarity == "狂") {
                val rawStatus = upResolver?.invoke(it)
                val upStatus = when (rawStatus) {
                    null -> {
                        if (it.type != "常驻") guaranteedNext = false
                        null
                    }
                    UpStatus.OFF -> {
                        guaranteedNext = true
                        UpStatus.OFF
                    }
                    UpStatus.UP, UpStatus.GUARANTEED -> {
                        if (guaranteedNext) {
                            guaranteedNext = false
                            UpStatus.GUARANTEED
                        } else {
                            UpStatus.UP
                        }
                    }
                }
                hits += GachaHit(
                    record = it,
                    interval = sinceGold,
                    isFirstGold = !previousGold,
                    upStatus = upStatus,
                )
                sinceGold = 0
                previousGold = true
            }
        }
        return hits.asReversed()
    }

    /** Computes gold intervals strictly within each pool category, then merges newest-first. */
    fun hitsAcrossCategories(
        records: List<GachaRecord>,
        upResolver: ((GachaRecord) -> UpStatus?)? = null,
    ): List<GachaHit> =
        records.groupBy { it.categoryId ?: "legacy" }
            .values
            .flatMap { hits(it, upResolver) }
            .sortedWith(compareByDescending<GachaHit> { it.record.time }.thenBy { it.record.index })

    fun upStats(hits: List<GachaHit>): GachaUpStats {
        val tracked = hits.filter { it.upStatus != null }
        val knownTracked = tracked.filterNot { it.isFirstGold }
        val avgGold = if (knownTracked.isEmpty()) null else knownTracked.map { it.interval }.average()
        return GachaUpStats(
            wins = tracked.count { it.upStatus == UpStatus.UP },
            losses = tracked.count { it.upStatus == UpStatus.OFF },
            guaranteed = tracked.count { it.upStatus == UpStatus.GUARANTEED },
            trackedKnownCount = knownTracked.size,
            trackedAvgGold = avgGold,
        )
    }

    /** Number of pulls since the latest SSR ("狂"), or total pulls if none found. */
    fun pity(records: List<GachaRecord>): Int {
        var count = 0
        records.sortedWith(newestFirst).forEach {
            if (it.rarity == "狂") return count
            count++
        }
        return count
    }

    /** 8 buckets of 10 pulls (1–10 … 71–80+); first gold excluded because its prior pulls are unknown. */
    fun intervals(hits: List<GachaHit>): GachaIntervalStats {
        val known = hits.filterNot { it.isFirstGold }.map { it.interval }
        val buckets = MutableList(8) { 0 }
        known.forEach { buckets[((it - 1) / 10).coerceIn(0, 7)]++ }
        val sorted = known.sorted()
        val median = if (sorted.isEmpty()) null else sorted[sorted.size / 2]
        return GachaIntervalStats(buckets, median, sorted.firstOrNull(), sorted.lastOrNull())
    }

    /** Groups consecutive records of the same second + pool; keeps newest-first order. */
    fun groups(records: List<GachaRecord>): List<GachaPullGroup> {
        val out = mutableListOf<GachaPullGroup>()
        records.sortedWith(newestFirst).forEach { r ->
            val last = out.lastOrNull()
            if (last != null && last.time == r.time && last.pool == r.pool && last.size < 10) {
                out[out.lastIndex] = last.copy(records = last.records + r)
            } else out += GachaPullGroup(r.time, r.pool, listOf(r))
        }
        return out
    }

    /** Most common game tracking type written by the collector, e.g. 新角色 → 活动. */
    fun gameType(records: List<GachaRecord>): String? =
        records.mapNotNull { it.type?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun intervalColor(interval: Int): Long = when {
        interval <= 30 -> 0xFF3F7A5E
        interval <= 55 -> 0xFFAD833E
        else -> 0xFFA84944
    }
}
