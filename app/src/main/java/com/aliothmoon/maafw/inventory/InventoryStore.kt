package com.aliothmoon.maafw.inventory

import kotlinx.serialization.json.*
import java.io.File

data class InventoryItem(
    val id: String, val name: String, val category: String, val quantity: Long?,
    val conflicts: List<String> = emptyList(), val image: File? = null, val unknown: Boolean = false,
) {
    val quantityText: String get() = if (conflicts.isNotEmpty()) "数量冲突：" + conflicts.joinToString(" / ") else quantity?.toString() ?: "未识别"
    val group: String get() = when {
        unknown -> "未识别"
        name.contains("箱") || name.contains("匣") || name.contains("礼盒") -> "箱子"
        else -> category
    }
}
data class InventoryCategory(val name: String, val status: String, val reason: String)
data class InventorySnapshot(
    val capturedAt: String, val status: String, val currencies: Map<String, Long?>,
    val categories: List<InventoryCategory>, val items: List<InventoryItem>, val json: String,
    val runId: String = "",
) {
    fun markdown(): String = buildString {
        append("# 仓库资源\n\n采集时间：$capturedAt\n\n状态：${if (status == "complete") "完整" else "部分识别"}\n\n")
        currencies.forEach { (name, count) -> append("- ${cell(name)}：${count ?: "未识别"}\n") }
        append("\n| 分类 | 物品 | 数量 |\n|---|---|---|\n")
        items.forEach { append("| ${cell(it.category)} | ${cell(it.name)} | ${cell(it.quantityText)} |\n") }
        categories.forEach { append("\n${cell(it.name)}：${if (it.status == "complete") "完整" else "部分"}，${cell(it.reason)}\n") }
    }
    private fun cell(value: String) = value.replace("|", "\\|").replace("\n", " ").replace("\r", " ")
}

data class InventoryHistoryEntry(
    val fileName: String,
    val capturedAt: String,
    val status: String,
    val label: String = "",
)

data class InventoryCurrencySummary(val capturedAt: String, val crystals: Long?, val dis: Long?, val essence: Long?)

/** 只读 Agent 快照；未知数量保持 null，绝不当作库存为零。 */
class InventoryStore(private val dataRoot: File, private val piRoot: File) {
    private val root: File get() = File(dataRoot, "inventory").canonicalFile
    private val historyRoot: File get() = File(root, "history").canonicalFile.also {
        require(it.parentFile == root) { "历史快照目录无效" }
    }
    private val historyMetaFile: File get() = File(root, "history-meta.json")

    fun latestLastModified(): Long = File(root, "latest.json").takeIf { it.isFile }?.lastModified() ?: 0L

    fun loadCurrencySummary(): InventoryCurrencySummary? {
        val file = safeFile(root, "latest.json") ?: return null
        val raw = readLimited(file)
        val document = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (document["version"]?.jsonPrimitive?.intOrNull != 1) return null
        val currencies = (document["currencies"] as? JsonObject).orEmpty()
        var essence = (currencies["狂厄精粹"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            ?: (currencies["狂乱精粹"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        if (essence == null) {
            val props = (document["categories"] as? JsonObject)?.get("道具") as? JsonObject
            val itemsObj = props?.get("items") as? JsonObject
            essence = itemsObj?.values?.firstNotNullOfOrNull { el ->
                val obj = el as? JsonObject ?: return@firstNotNullOfOrNull null
                val name = obj.text("name")
                if (name == "狂厄精粹" || name == "狂乱精粹") obj.count("quantity") else null
            }
        }
        return InventoryCurrencySummary(
            capturedAt = document.text("captured_at"),
            crystals = (currencies["异方晶"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 },
            dis = (currencies["狄斯币"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 },
            essence = essence,
        )
    }

    fun load(): InventorySnapshot? {
        val file = safeFile(root, "latest.json") ?: return null
        return loadSnapshot(file)
    }

    /** 仅枚举固定 history 根的直接文件，不接收路径、不递归跟随目录。 */
    fun history(): List<InventoryHistoryEntry> {
        val base = historyRoot
        val labels = loadHistoryLabels()
        return base.listFiles().orEmpty().mapNotNull { file ->
            val allowed = historyFile(file.name) ?: return@mapNotNull null
            runCatching {
                val snapshot = loadSnapshot(allowed)
                InventoryHistoryEntry(file.name, snapshot.capturedAt, snapshot.status, labels[file.name].orEmpty())
            }.getOrNull() // 单份损坏的旧记录不妨碍选择其它快照。
        }.sortedByDescending { it.fileName }
    }

    fun renameHistory(fileName: String, label: String): String {
        historyFile(fileName) ?: error("历史快照不存在或路径无效，请重新选择")
        val normalized = label.trim()
        require(normalized.length <= 40 && normalized.none { it.code < 32 }) { "快照名称须在 40 个可见字符以内" }
        val labels = loadHistoryLabels().toMutableMap()
        if (normalized.isEmpty()) labels.remove(fileName) else labels[fileName] = normalized
        saveHistoryLabels(labels)
        return normalized
    }

    fun deleteHistory(fileName: String) {
        val file = historyFile(fileName) ?: error("历史快照不存在或路径无效，请重新选择")
        require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "历史快照路径无效" }
        check(file.delete()) { "删除历史快照失败，请稍后重试" }
        val labels = loadHistoryLabels().toMutableMap()
        if (labels.remove(fileName) != null) {
            runCatching { saveHistoryLabels(labels) }
        }
    }

    fun loadHistory(fileName: String): InventorySnapshot {
        val file = historyFile(fileName) ?: error("历史快照不存在或路径无效，请重新选择")
        return loadSnapshot(file)
    }

    private fun loadHistoryLabels(): Map<String, String> {
        val file = historyMetaFile
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return emptyMap()
        return runCatching {
            val obj = Json.parseToJsonElement(readLimited(file)) as? JsonObject ?: return emptyMap()
            buildMap {
                obj.forEach { (k, v) ->
                    val text = (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (text.isNotEmpty() && text.length <= 40 && text.none { it.code < 32 }) put(k, text)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveHistoryLabels(labels: Map<String, String>) {
        check(root.isDirectory || root.mkdirs()) { "无法创建仓库数据目录" }
        val target = historyMetaFile
        require(!java.nio.file.Files.isSymbolicLink(target.toPath())) { "快照元数据路径无效" }
        val json = buildJsonObject {
            labels.toSortedMap().forEach { (k, v) -> put(k, v) }
        }.toString()
        val temp = File(root, ".history-meta-${java.util.UUID.randomUUID()}.tmp")
        try {
            temp.writeText(json, Charsets.UTF_8)
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temp.delete()
        }
    }

    private fun historyFile(fileName: String): File? {
        if (!fileName.matches(Regex("[A-Za-z0-9_-]+\\.json"))) return null
        val base = historyRoot
        return safeFile(base, fileName)?.takeIf { it.parentFile == base }
    }

    private fun loadSnapshot(file: File): InventorySnapshot {
        val raw = readLimited(file)
        val document = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: error("仓库记录格式不正确，请重新识别后刷新")
        require(document["version"]?.jsonPrimitive?.intOrNull == 1) { "不支持的仓库记录版本" }
        val catalog = loadCatalog()
        val categories = document["categories"] as? JsonObject ?: error("仓库记录缺少分类")
        val items = mutableListOf<InventoryItem>()
        val states = categories.map { (category, value) ->
            val group = value as? JsonObject ?: error("仓库分类格式不正确")
            (group["items"] as? JsonObject).orEmpty().forEach { (id, value) ->
                val item = value as? JsonObject ?: error("仓库物品格式不正确")
                items += InventoryItem(id, item.text("name").ifBlank { "未命名物品" }, category,
                    item.count("quantity"), (item["conflicting_quantities"] as? JsonArray).orEmpty().map {
                        (it as? JsonPrimitive)?.longOrNull?.takeIf { n -> n >= 0 }?.toString() ?: "未识别"
                    }, catalog[id])
            }
            (group["unknown"] as? JsonObject).orEmpty().forEach { (id, value) ->
                val item = value as? JsonObject ?: error("未知物品格式不正确")
                // Agent 可返回绝对路径，但只允许读取本次应用 inventory/captures 下的截图。
                val captureRoot = File(root, "captures").canonicalFile.takeIf { it.parentFile == root }
                items += InventoryItem("unknown:$category:$id", "未识别物品 · ${id.take(8)}", category,
                    item.count("quantity"), image = captureRoot?.let { safeFile(it, item.text("image")) }, unknown = true)
            }
            InventoryCategory(category, group.text("status"), group.text("reason"))
        }
        val currencies = (document["currencies"] as? JsonObject).orEmpty().mapValues { (_, value) ->
            (value as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        }
        return InventorySnapshot(document.text("captured_at"), document.text("status"), currencies, states, items, raw,
            document.text("run_id"))
    }

    private fun loadCatalog(): Map<String, File?> {
        val imageRoot = File(piRoot, "resource/base/image")
        val file = safeFile(imageRoot, "仓库/catalog.json") ?: return emptyMap()
        val key = file.canonicalPath
        val mtime = file.lastModified()
        cachedCatalog?.takeIf { it.first == key && it.second == mtime }?.let { return it.third }
        return runCatching {
            val imageBase = imageRoot.canonicalFile
            val imageBasePath = imageBase.toPath()
            val catalog = Json.parseToJsonElement(readLimited(file)).jsonObject
            val map = (catalog["items"] as? JsonArray).orEmpty().mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                val rel = item.text("template")
                val resolved = if (rel.isBlank()) null else {
                    val candidate = File(rel).let { if (it.isAbsolute) it else File(imageBase, rel) }.normalize()
                    candidate.takeIf { it.toPath().startsWith(imageBasePath) && it.isFile }
                }
                item.text("id") to resolved
            }.toMap()
            cachedCatalog = Triple(key, mtime, map)
            map
        }.getOrDefault(emptyMap()) // 图标坏了仍展示库存文字。
    }

    private companion object {
        @Volatile
        private var cachedCatalog: Triple<String, Long, Map<String, File?>>? = null
    }

    private fun readLimited(file: File): String {
        require(file.length() <= 4 * 1024 * 1024) { "仓库记录过大，无法读取" }
        return file.readText(Charsets.UTF_8)
    }

    internal fun safeFile(root: File, path: String): File? {
        if (path.isBlank()) return null
        val base = root.canonicalFile
        val candidate = File(path).let { if (it.isAbsolute) it else File(base, path) }.canonicalFile
        return candidate.takeIf { it.toPath().startsWith(base.toPath()) && it.isFile }
    }
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.count(key: String) = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
