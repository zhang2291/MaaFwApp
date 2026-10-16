package com.aliothmoon.maafw.ui.gacha

import com.aliothmoon.maafw.gacha.GachaRecord
import java.util.Locale

data class GachaTotals(val pulls: Int, val gold: Int) {
    val average: String get() = if (gold == 0) "—" else String.format(Locale.ROOT, "%.1f", pulls.toDouble() / gold)
}

data class GachaHit(val record: GachaRecord, val interval: Int?)

object GachaStatistics {
    val newestFirst: Comparator<GachaRecord> = compareByDescending<GachaRecord> { it.time }.thenBy { it.index }
    fun totals(records: List<GachaRecord>) = GachaTotals(records.size, records.count { it.rarity == "狂" })
    fun pools(records: List<GachaRecord>): List<String> = records.sortedWith(newestFirst).map { it.pool }.distinct()

    /** Input must be one user-defined category. A pool-name filter is applied AFTER computing intervals. */
    fun hits(records: List<GachaRecord>): List<GachaHit> {
        var sinceGold = 0
        var previousGold = false
        val hits = mutableListOf<GachaHit>()
        records.sortedWith(compareBy<GachaRecord> { it.time }.thenByDescending { it.index }).forEach {
            sinceGold++
            if (it.rarity == "狂") {
                hits += GachaHit(it, if (previousGold) sinceGold else null)
                sinceGold = 0
                previousGold = true
            }
        }
        return hits.asReversed()
    }
}
