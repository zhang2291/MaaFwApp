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

/** 只读 Agent 快照；未知数量保持 null，绝不当作库存为零。 */
class InventoryStore(private val dataRoot: File, private val piRoot: File) {
    fun load(): InventorySnapshot? {
        val root = File(dataRoot, "inventory").canonicalFile
        val file = safeFile(root, "latest.json") ?: return null
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
                val captureRoot = File(root, "captures")
                items += InventoryItem("unknown:$category:$id", "未识别物品 · ${id.take(8)}", category,
                    item.count("quantity"), image = safeFile(captureRoot, item.text("image")), unknown = true)
            }
            InventoryCategory(category, group.text("status"), group.text("reason"))
        }
        val currencies = (document["currencies"] as? JsonObject).orEmpty().mapValues { (_, value) ->
            (value as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        }
        return InventorySnapshot(document.text("captured_at"), document.text("status"), currencies, states, items, raw)
    }

    private fun loadCatalog(): Map<String, File?> {
        val imageRoot = File(piRoot, "resource/base/image")
        val file = safeFile(imageRoot, "仓库/catalog.json") ?: return emptyMap()
        return runCatching {
            val catalog = Json.parseToJsonElement(readLimited(file)).jsonObject
            (catalog["items"] as? JsonArray).orEmpty().mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                item.text("id") to safeFile(imageRoot, item.text("template"))
            }.toMap()
        }.getOrDefault(emptyMap()) // 图标坏了仍展示库存文字。
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
