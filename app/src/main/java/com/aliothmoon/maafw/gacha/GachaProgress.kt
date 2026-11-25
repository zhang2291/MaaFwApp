package com.aliothmoon.maafw.gacha

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Live collection progress written by the agent to data/record/gacha/.progress.json after every page. */
@Serializable
data class GachaProgress(
    val category: String = "",
    val state: String = "",
    val page: Int? = null,
    val pages: Int = 0,
    val new: Int = 0,
    val known: Int = 0,
    val status: String = "",
    val reason: String = "",
    val elapsed: Double = 0.0,
    val updated: Double = 0.0,
) {
    val secondsPerPage: Double? get() = if (pages > 0) elapsed / pages else null

    fun completionSummary(): Pair<String, Boolean> {
        val reasonSuffix = reason.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        return when {
            state == "done" && status == "completed" ->
                "采集完成：本次新增 $new 条（共扫 $pages 页）" to false
            status == "partial" ->
                "采集未完整结束$reasonSuffix：已保留新增 $new 条（共扫 $pages 页）" to true
            state == "running" || state == "finishing" ->
                "采集已提前停止：已保留新增 $new 条（共扫 $pages 页）" to false
            else ->
                "采集已结束$reasonSuffix：本次新增 $new 条（共扫 $pages 页）" to (status != "completed")
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Returns progress only if it was written after [sinceMillis]; stale files from older runs are ignored. */
        fun read(dataRoot: File, sinceMillis: Long): GachaProgress? = runCatching {
            val file = File(dataRoot, "record/gacha/.progress.json")
            if (!file.isFile) return null
            json.decodeFromString<GachaProgress>(file.readText(Charsets.UTF_8))
                .takeIf { it.updated * 1000 >= sinceMillis - 2_000 }
        }.getOrNull()
    }
}
