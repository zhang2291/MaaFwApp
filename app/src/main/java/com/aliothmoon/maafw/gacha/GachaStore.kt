package com.aliothmoon.maafw.gacha

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class GachaRecord(
    val index: Int = 0,
    val key: String,
    val rarity: String? = null,
    val type: String? = null,
    val cells: List<String>,
    val poolCategory: String? = null,
    val categoryId: String? = null,
) {
    val time: String get() = cells.firstOrNull().orEmpty().replace(Regex("""^(\d{4}-\d{2}-\d{2})[.\s_T]?(\d{2}:)"""), "$1 $2")
    val pool: String get() = cells.getOrNull(1).orEmpty()
    val name: String get() {
        val raw = cells.lastOrNull().orEmpty().trim().replace(Regex("""^[·\s\-_]+|[·\s\-_]+$"""), "")
        return when (raw) {
            "县" -> "昙"
            "卡茲安" -> "卡兹安"
            "克斯蒂娜" -> "克里斯蒂娜"
            "雷比妮斯" -> "雷比尼斯"
            "澈澈", "澈县" -> "澈"
            "諾克斯", "诺克断" -> "诺克斯"
            "溫蒂" -> "温蒂"
            "蘭利" -> "兰利"
            "切爾西伯爵" -> "切尔西伯爵"
            "露薇婭·蕾" -> "露薇娅·蕾"
            "愛緹" -> "爱缇"
            "觀星者" -> "观星者"
            "麥昆" -> "麦昆"
            "萊塔" -> "莱塔"
            "瑪奇朵" -> "玛奇朵"
            else -> raw
        }
    }
    val category: String get() = type?.takeIf(String::isNotBlank) ?: "未判"
    val canonicalKey: String get() {
        if (cells.size >= 3) {
            val tsMatches = Regex("""\d+""").findAll(cells[0]).map { it.value }.toList()
            val tsDigits = if (tsMatches.size >= 6) tsMatches.take(6).joinToString("")
            else cells[0].replace(Regex("""[\s._T\-:]+"""), "")
            val p = cells[1].replace(Regex("""[\s·.•\u2022\u25cf\u00b7]+"""), "")
            return "$tsDigits$p$name"
        }
        return key
    }
    val identity: String get() = category + "\u0000" + canonicalKey
}

/** Independent of the replaceable PI directory. JSONL remains compatible with the upstream collector. */
class GachaStore(private val dataRoot: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val archive = File(dataRoot, "record/抽卡记录")

    private fun <T> locked(block: () -> T): T {
        check(dataRoot.isDirectory || dataRoot.mkdirs()) { "无法创建抽卡数据目录" }
        return RandomAccessFile(File(dataRoot, ".gacha.lock"), "rw").use { file ->
            val lock = file.channel.tryLock() ?: error("正在保存采集记录，请稍后重试")
            lock.use { block() }
        }
    }

    fun read(): List<GachaRecord> = locked { readUnlocked() }

    private fun readUnlocked(): List<GachaRecord> = archive.listFiles()
        .orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }
        .flatMap { file ->
            file.readLines(Charsets.UTF_8).filter(String::isNotBlank).mapIndexed { index, line ->
                runCatching { json.decodeFromString<GachaRecord>(line).also(::validate) }
                    .getOrElse { error("档案 ${file.name} 第 ${index + 1} 行损坏，未修改原文件") }
            }
        }

    fun importRecords(incoming: List<GachaRecord>): Int = locked {
        require(incoming.size <= 100_000) { "最多导入 100000 条记录" }
        incoming.forEach(::validate)
        val existing = readUnlocked()
        val added = merge(existing, incoming).drop(existing.size)
        if (added.isEmpty()) return@locked 0
        check(archive.isDirectory || archive.mkdirs()) { "无法创建档案目录" }
        // One atomic file replacement: a failed import cannot leave a half-imported set of pools.
        val target = File(archive, "导入.jsonl")
        val previous = if (target.exists()) target.readText(Charsets.UTF_8) else ""
        val temp = File(archive, "导入.tmp")
        try {
            temp.outputStream().use { out ->
                out.write((previous.trimEnd() + if (previous.isBlank()) "" else "\n").toByteArray(Charsets.UTF_8))
                added.forEach { out.write((json.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
        added.size
    }

    companion object {
        fun key(cells: List<String>): String = cells.joinToString("").replace(Regex("[\\s\\p{Z}]+"), "")

        fun validate(record: GachaRecord) {
            require(record.cells.size >= 3 && record.cells.all { it.length <= 2000 }) { "记录必须包含时间、卡池和角色" }
            require(record.cells.first().matches(Regex("""\d{4}-\d{2}-\d{2}[.\s_T]?\d{2}:\d{2}:\d{2}"""))) { "记录时间格式不正确" }
            require(record.pool.isNotBlank() && record.name.isNotBlank()) { "卡池或角色为空" }
            val literal = key(record.cells)
            val legacyCanonical = if (record.cells.size >= 3) {
                val tsDigits = Regex("""\d+""").findAll(record.cells[0]).map { it.value }.take(6).joinToString("")
                val p = record.cells[1].replace(Regex("""\s+"""), "")
                "$tsDigits$p${record.name}"
            } else record.key
            require(
                record.key.isBlank() ||
                    record.key == literal ||
                    record.key == record.canonicalKey ||
                    record.key == legacyCanonical
            ) { "记录身份字段与明细不一致" }
            require(record.category.length <= 100) { "追踪类型过长" }
        }

        /** Multiset union: repeated imports are idempotent; identical pulls within one ten-pull survive. */
        fun merge(existing: List<GachaRecord>, incoming: List<GachaRecord>): List<GachaRecord> {
            val counts = existing.groupingBy { it.identity }.eachCount()
            val seen = mutableMapOf<String, Int>()
            var index = existing.maxOfOrNull { it.index } ?: 0
            val additions = incoming.filter {
                val n = (seen[it.identity] ?: 0) + 1
                seen[it.identity] = n
                n > (counts[it.identity] ?: 0)
            }.map { it.copy(index = ++index) }
            return existing + additions
        }
    }
}
