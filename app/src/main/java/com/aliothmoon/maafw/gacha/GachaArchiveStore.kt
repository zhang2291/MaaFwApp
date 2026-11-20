package com.aliothmoon.maafw.gacha

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
data class GachaCategory(val id: String, val name: String)

data class GachaBundle(val categories: List<String>, val records: List<GachaRecord>)

data class GachaArchiveSnapshot(val categories: List<GachaCategory>, val records: List<GachaRecord>)

/** Desktop-compatible category catalog; all mutations coordinate with the collector's POSIX lock. */
class GachaArchiveStore(dataRoot: File) {
    private val root = dataRoot.canonicalFile
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val catalog get() = checked(File(root, "gacha-categories.json"))
    private val hiddenLegacy get() = checked(File(root, ".gacha-legacy-hidden"))

    private fun <T> locked(block: () -> T): T {
        check(root.isDirectory || root.mkdirs()) { "无法创建抽卡数据目录" }
        return RandomAccessFile(checked(File(root, ".gacha.lock")), "rw").use { file ->
            val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            checkNotNull(lock) { "正在保存采集记录，请稍后重试" }.use { block() }
        }
    }

    /** Reject linked parents too: checking only the leaf does not protect a linked record directory. */
    private fun checked(file: File): File {
        val path = file.toPath().toAbsolutePath().normalize()
        val base = root.toPath()
        require(path.startsWith(base) && path != base) { "档案路径超出数据目录" }
        var current = root
        base.relativize(path).forEach { part ->
            current = File(current, part.toString())
            require(!Files.isSymbolicLink(current.toPath())) { "档案路径含符号链接，拒绝操作" }
        }
        require(file.canonicalFile.toPath().startsWith(base)) { "档案路径超出数据目录" }
        return path.toFile()
    }

    private fun archive(id: String): File {
        validateId(id)
        return checked(File(root, if (id == LEGACY_ID) "record/抽卡记录" else "record/gacha/$id"))
    }

    private fun catalogUnlocked(): List<GachaCategory> {
        val categories = if (catalog.exists()) json.decodeFromString<List<GachaCategory>>(catalog.readText(Charsets.UTF_8))
            else DEFAULT_CATEGORIES
        require(categories.isNotEmpty() && categories.size <= MAX_CATEGORIES) { "池子类型目录数量无效" }
        require(categories.map { it.id }.distinct().size == categories.size && categories.map { it.name }.distinct().size == categories.size) { "池子类型目录存在重复 ID 或名称" }
        categories.forEach {
            validateId(it.id)
            require(it.id != LEGACY_ID && it.name != LEGACY_NAME) { "旧记录类型不能写入普通目录" }
            validateName(it.name)
        }
        return categories
    }

    private fun readArchive(category: GachaCategory, strict: Boolean = true): List<GachaRecord> {
        val directory = archive(category.id)
        require(!directory.exists() || directory.isDirectory) { "档案目录格式无效" }
        return directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }.flatMap { file ->
            checked(file)
            require(file.isFile) { "档案不是普通文件" }
            file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                val content = if (index == 0) line.removePrefix("\uFEFF") else line
                if (content.isBlank()) null else runCatching {
                    json.decodeFromString<GachaRecord>(content).also { GachaStore.validate(it) }
                        .copy(poolCategory = category.name, categoryId = category.id)
                }.getOrElse {
                    if (strict) error("档案 ${file.name} 第 ${index + 1} 行损坏，未修改原文件")
                    else null
                }
            }
        }
    }

    fun snapshot(): GachaArchiveSnapshot = locked {
        val categories = catalogUnlocked()
        val visible = categories + if (hiddenLegacy.exists()) emptyList() else listOf(LEGACY_CATEGORY)
        val records = visible.flatMap { readArchive(it, strict = false) }
        if (!catalog.exists()) transaction(mapOf(catalog to catalogBytes(categories)))
        GachaArchiveSnapshot(visible, records)
    }

    fun bundle(): GachaBundle = snapshot().let { GachaBundle(it.categories.map(GachaCategory::name), it.records) }

    fun addCategory(name: String): GachaCategory = locked {
        val normalized = name.trim()
        validateName(normalized)
        val categories = catalogUnlocked()
        require(normalized != LEGACY_NAME && categories.none { it.name == normalized }) { "该类型已存在" }
        require(categories.size < MAX_CATEGORIES) { "类型数量过多" }
        val category = GachaCategory(UUID.randomUUID().toString().replace("-", ""), normalized)
        transaction(mapOf(catalog to catalogBytes(categories + category)))
        category
    }

    /** Backup deletion moves records out of the active tree; permanent deletion uses a rollback staging area. */
    fun deleteCategory(id: String, permanent: Boolean): String? = locked {
        val categories = catalogUnlocked()
        val category = if (id == LEGACY_ID && !hiddenLegacy.exists()) LEGACY_CATEGORY
            else categories.firstOrNull { it.id == id } ?: error("池子类型不存在")
        require(id == LEGACY_ID || categories.size > 1) { "请至少保留一个池子类型" }
        val directory = archive(id)
        val targets = listOf(directory, File(directory.path + ".xlsx"), File(directory.path + ".md"))
        targets.forEach(::validateTree)
        val backup = checked(File(root, if (permanent) ".gacha-delete-${UUID.randomUUID()}"
            else "deleted-gacha/${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}-${UUID.randomUUID().toString().take(8)}"))
        require(!backup.exists()) { "删除备份目录已存在" }
        val metadata = if (id == LEGACY_ID) mapOf(hiddenLegacy to "hidden".toByteArray(Charsets.UTF_8))
            else mapOf(catalog to catalogBytes(categories.filter { it.id != id }))
        // Validate every metadata target before moving records.
        metadata.keys.forEach { target -> require(!target.exists() || target.isFile) { "类型元数据不是普通文件" } }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            check(backup.mkdirs()) { "无法创建删除备份目录" }
            File(backup, "category.json").writeText(json.encodeToString(category), Charsets.UTF_8)
            targets.zip(listOf("records", "records.xlsx", "records.md")).forEach { (source, name) ->
                if (source.exists()) {
                    val destination = File(backup, name)
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    moved += source to destination
                }
            }
            transaction(metadata)
        } catch (failure: Exception) {
            moved.asReversed().forEach { (source, destination) ->
                try { Files.move(destination.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                catch (rollback: Exception) { failure.addSuppressed(rollback) }
            }
            if (failure.suppressed.isEmpty()) backup.deleteRecursively()
            throw failure
        }
        if (permanent) {
            check(backup.deleteRecursively()) { "类型已删除，但临时清理未完成：${backup.absolutePath}" }
            null
        } else backup.absolutePath
    }

    /** Validate and stage the complete workbook before replacing any live archive or catalog. */
    fun importAll(bundle: GachaBundle): Int = locked {
        require(bundle.records.size <= 100_000) { "最多导入 100000 条记录" }
        require(bundle.categories.size <= MAX_CATEGORIES + 1) { "类型数量过多" }
        val categories = catalogUnlocked().toMutableList()
        val byName = (categories + LEGACY_CATEGORY).associateBy { it.name }.toMutableMap()
        val byId = byName.values.associateBy { it.id }
        val incoming = bundle.records.map { record ->
            GachaStore.validate(record)
            record.categoryId?.let(::validateId)
            val name = record.poolCategory ?: record.categoryId?.let { byId[it]?.name ?: error("记录缺少可识别的池子类型名称") } ?: LEGACY_NAME
            validateName(name)
            record.copy(poolCategory = name)
        }
        val names = (bundle.categories + incoming.map { it.poolCategory!! }).distinct()
        names.forEach(::validateName)
        names.forEach { name ->
            if (name !in byName) {
                require(categories.size < MAX_CATEGORIES) { "类型数量过多" }
                val category = GachaCategory(UUID.randomUUID().toString().replace("-", ""), name)
                categories += category
                byName[name] = category
            }
        }
        val changes = linkedMapOf<File, ByteArray?>()
        var count = 0
        incoming.groupBy { it.poolCategory!! }.forEach { (name, records) ->
            val category = byName.getValue(name)
            val existing = readArchive(category)
            val additions = GachaStore.merge(existing, records).drop(existing.size)
            if (additions.isNotEmpty()) {
                val target = checked(File(archive(category.id), "导入.jsonl"))
                val previous = if (target.exists()) target.readText(Charsets.UTF_8) else ""
                val text = buildString {
                    if (previous.isNotBlank()) append(previous.trimEnd()).append('\n')
                    additions.forEach { append(json.encodeToString(it.copy(poolCategory = null, categoryId = null))).append('\n') }
                }
                changes[target] = text.toByteArray(Charsets.UTF_8)
                count += additions.size
            }
        }
        if (!catalog.exists() || categories != catalogUnlocked()) changes[catalog] = catalogBytes(categories)
        if (LEGACY_NAME in names && hiddenLegacy.exists()) changes[hiddenLegacy] = null
        transaction(changes)
        count
    }

    private fun validateTree(file: File) {
        checked(file)
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::validateTree)
    }

    private fun catalogBytes(categories: List<GachaCategory>) = json.encodeToString(categories).toByteArray(Charsets.UTF_8)

    private fun replace(target: File, bytes: ByteArray) {
        checked(target)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建档案目录" }
        val temp = checked(File(target.parentFile, ".gacha-${UUID.randomUUID()}.tmp"))
        try {
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }

    private fun transaction(changes: Map<File, ByteArray?>) {
        if (changes.isEmpty()) return
        val before = changes.keys.associateWith { target ->
            checked(target)
            require(!target.exists() || target.isFile) { "档案目标不是普通文件" }
            if (target.exists()) target.readBytes() else null
        }
        val staged = mutableMapOf<File, File>()
        val committed = mutableListOf<File>()
        try {
            changes.forEach { (target, content) ->
                if (content != null) {
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建档案目录" }
                    val temporary = checked(File(target.parentFile, ".gacha-${UUID.randomUUID()}.tmp"))
                    staged[target] = temporary
                    temporary.outputStream().use { it.write(content); it.fd.sync() }
                }
            }
            changes.forEach { (target, content) ->
                if (content == null) Files.deleteIfExists(target.toPath())
                else Files.move(staged.getValue(target).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                committed += target
            }
        } catch (failure: Exception) {
            committed.asReversed().forEach { target ->
                try {
                    val content = before[target]
                    if (content == null) Files.deleteIfExists(target.toPath()) else replace(target, content)
                } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            }
            throw failure
        } finally { staged.values.forEach { it.delete() } }
    }

    companion object {
        const val LEGACY_ID = "legacy"
        const val LEGACY_NAME = "未分类（旧记录）"
        private const val MAX_CATEGORIES = 200
        val LEGACY_CATEGORY = GachaCategory(LEGACY_ID, LEGACY_NAME)
        val DEFAULT_CATEGORIES = listOf("新角色", "限定", "联动", "常驻", "复刻").mapIndexed { index, name -> GachaCategory("default-$index", name) }

        internal fun validateName(name: String) {
            require(name.isNotBlank() && name == name.trim() && name.length <= 40 && name.none { it.code < 32 }) { "类型名称须为 1–40 个可见字符，且不能包含首尾空白" }
        }

        private fun validateId(id: String) {
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) { "池子类型 ID 无效" }
        }
    }
}
