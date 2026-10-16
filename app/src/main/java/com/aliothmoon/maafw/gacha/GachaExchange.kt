package com.aliothmoon.maafw.gacha

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Small OOXML interchange: text cells only, including upstream openpyxl exports. No formulas execute. */
object GachaExchange {
    private val headers = listOf("时间", "追踪类型", "卡池", "稀有度", "名称", "档案序号", "原始列")
    private fun xml(value: String): String = buildString {
        value.forEach { c ->
            append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"
                else -> if (c >= ' ' || c == '\n' || c == '\t' || c == '\r') c.toString() else "" })
        }
    }

    fun writeExcel(records: List<GachaRecord>, output: OutputStream) {
        writeWorkbook(listOf(SheetData("抽卡记录", headers, records.sortedWith(newestFirst).asSequence().map {
            listOf(it.time, it.category, it.pool, it.rarity.orEmpty(), it.name, it.index.toString(), Json.encodeToString(it.cells))
        }, 7)), output)
    }

    private data class SheetData(val name: String, val headers: List<String>, val rows: Sequence<List<String>>, val hiddenColumn: Int? = null)
    private val newestFirst = compareByDescending<GachaRecord> { it.time }.thenBy { it.index }

    private fun writeWorkbook(sheets: List<SheetData>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            val overrides = sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>$overrides</Types>""")
            entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val sheetDeclarations = sheets.mapIndexed { index, sheet -> "<sheet name=\"${xml(sheet.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>" }.joinToString("")
            entry("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$sheetDeclarations</sheets></workbook>""")
            val relationships = sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }
            entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relationships</Relationships>""")
            sheets.forEachIndexed { index, sheet ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml"))
                fun write(s: String) = zip.write(s.toByteArray(Charsets.UTF_8))
                val hidden = sheet.hiddenColumn?.let { "<col min=\"$it\" max=\"$it\" hidden=\"1\"/>" }.orEmpty()
                write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="${sheet.headers.size}" width="23" customWidth="1"/>$hidden</cols><sheetData>""")
                fun row(values: List<String>) {
                    write("<row>")
                    values.forEach { write("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(it)}</t></is></c>") }
                    write("</row>")
                }
                row(sheet.headers)
                var count = 1
                sheet.rows.forEach { row(it); count++ }
                val lastVisibleColumn = sheet.hiddenColumn?.minus(1) ?: sheet.headers.size
                write("</sheetData><autoFilter ref=\"A1:${('A'.code + lastVisibleColumn - 1).toChar()}$count\"/></worksheet>")
                zip.closeEntry()
            }
        }
    }

    private fun Element.elements(name: String): List<Element> = getElementsByTagNameNS("*", name)
        .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun parse(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", true)) { "不支持含 DTD 的工作簿" }
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }

    private data class ParsedSheet(val name: String, val columns: Set<String>, val rows: List<Map<String, String>>)

    private fun readWorkbook(input: InputStream): List<ParsedSheet> {
        val files = mutableMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(input).use { zip ->
            var entries = 0
            while (true) {
                val e = zip.nextEntry ?: break
                require(++entries <= 300) { "工作簿文件过多" }
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 32 * 1024 * 1024) { "工作簿解压后超过 32 MB" }
                    out.write(buffer, 0, n)
                }
                require(e.name !in files) { "工作簿含重复文件" }
                files[e.name] = out.toByteArray()
            }
        }
        val workbook = parse(files["xl/workbook.xml"] ?: error("不是受支持的 Excel 工作簿"))
        val relationships = parse(files["xl/_rels/workbook.xml.rels"] ?: error("缺少工作表关系"))
            .elements("Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
        val shared = files["xl/sharedStrings.xml"]?.let { parse(it).elements("si").map { si -> si.elements("t").joinToString("") { it.textContent } } }.orEmpty()
        return workbook.elements("sheet").map { sheet ->
            val target = relationships[sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")]
                ?: error("无法定位工作表")
            require(!target.contains("..")) { "工作表路径无效" }
            val path = if (target.startsWith('/')) target.trimStart('/') else "xl/$target"
            val rows = parse(files[path] ?: error("缺少工作表 $path")).elements("row")
            fun cells(row: Element): Map<Int, String> {
                val result = mutableMapOf<Int, String>()
                var ordinal = 0
                row.elements("c").forEach { cell ->
                    require(cell.elements("f").isEmpty()) { "请导入纯值记录，不支持公式" }
                    val letters = cell.getAttribute("r").takeWhile(Char::isLetter)
                    val column = if (letters.isEmpty()) ordinal else letters.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1
                    ordinal = column + 1
                    val value = when (cell.getAttribute("t")) {
                        "inlineStr" -> cell.elements("t").joinToString("") { it.textContent }
                        "s" -> shared.getOrNull(cell.elements("v").firstOrNull()?.textContent?.toIntOrNull() ?: -1) ?: error("共享字符串索引无效")
                        else -> cell.elements("v").firstOrNull()?.textContent.orEmpty()
                    }
                    result[column] = value
                }
                return result
            }
            val heading = rows.firstOrNull()?.let(::cells).orEmpty().filterValues(String::isNotBlank)
            require(heading.values.map(String::trim).distinct().size == heading.size) { "工作表含重复表头" }
            val columns = heading.entries.associate { it.value.trim() to it.key }
            val contents = rows.drop(1).mapNotNull { row ->
                val values = cells(row)
                if (values.values.all(String::isBlank)) null else columns.mapValues { (_, column) -> values[column].orEmpty() }
            }
            ParsedSheet(sheet.getAttribute("name"), columns.keys, contents)
        }
    }

    private fun readRecords(sheets: List<ParsedSheet>, includePoolCategory: Boolean): List<GachaRecord> {
        val records = mutableListOf<GachaRecord>()
        sheets.filter { it.name != "类型目录" }.forEach { sheet ->
            if (sheet.columns.isEmpty() && sheet.rows.isEmpty()) return@forEach
            require(listOf("时间", "卡池", "稀有度", "名称").all(sheet.columns::contains)) { "工作表 ${sheet.name} 缺少抽卡记录表头" }
            sheet.rows.forEach { row ->
                fun value(name: String) = row[name].orEmpty()
                val original = value("原始列")
                val rawCells = if (original.isNotBlank()) Json.decodeFromString<List<String>>(original)
                    else listOf(value("时间"), value("卡池"), value("名称"))
                if (original.isNotBlank()) {
                    val projected = GachaRecord(key = GachaStore.key(rawCells), cells = rawCells)
                    require(projected.time == value("时间") && projected.pool == value("卡池") && projected.name == value("名称")) { "明细已修改但原始列未同步，请移除原始列后再导入" }
                }
                val type = value("追踪类型").ifBlank { sheet.name }
                val poolCategory = if (!includePoolCategory) null else if ("池子类型" in sheet.columns)
                    value("池子类型").also { GachaArchiveStore.validateName(it) } else GachaArchiveStore.LEGACY_NAME
                val ordinal = value("档案序号").let { text ->
                    if (text.isBlank()) 0 else text.toIntOrNull()?.takeIf { it >= 0 } ?: error("档案序号无效")
                }
                val record = GachaRecord(index = ordinal, key = GachaStore.key(rawCells), type = type, rarity = value("稀有度"), cells = rawCells, poolCategory = poolCategory)
                GachaStore.validate(record)
                records += record
                require(records.size <= 100_000) { "最多导入 100000 条记录" }
            }
        }
        return records
    }

    fun readExcel(input: InputStream): List<GachaRecord> = readRecords(readWorkbook(input), includePoolCategory = false)

    fun readBundleExcel(input: InputStream): GachaBundle {
        val sheets = readWorkbook(input)
        val records = readRecords(sheets, includePoolCategory = true)
        val names = mutableListOf<String>()
        sheets.filter { it.name == "类型目录" }.forEach { sheet ->
            require("池子类型" in sheet.columns) { "类型目录格式无效" }
            sheet.rows.forEach { row ->
                val name = row["池子类型"].orEmpty()
                GachaArchiveStore.validateName(name)
                names += name
                require(names.size <= 201) { "类型数量过多" }
            }
        }
        val categories = (names + records.map { it.poolCategory!! }).distinct()
        require(categories.size <= 201) { "类型数量过多" }
        return GachaBundle(categories, records)
    }

    fun writeBundleExcel(bundle: GachaBundle, output: OutputStream) {
        val names = bundleNames(bundle)
        val grouped = bundle.records.groupBy { it.poolCategory ?: GachaArchiveStore.LEGACY_NAME }
        val rows = names.asSequence().flatMap { name -> grouped[name].orEmpty().sortedWith(newestFirst).asSequence().map {
            listOf(name, it.category, it.time, it.pool, it.rarity.orEmpty(), it.name, it.index.toString(), Json.encodeToString(it.cells))
        } }
        writeWorkbook(listOf(
            SheetData("抽卡记录", listOf("池子类型", "追踪类型", "时间", "卡池", "稀有度", "名称", "档案序号", "原始列"), rows, 8),
            SheetData("类型目录", listOf("池子类型"), names.asSequence().map { listOf(it) }),
        ), output)
    }

    private fun bundleNames(bundle: GachaBundle): List<String> {
        require(bundle.records.size <= 100_000) { "最多导出 100000 条记录" }
        bundle.records.forEach { GachaStore.validate(it) }
        val names = (bundle.categories + bundle.records.map { it.poolCategory ?: GachaArchiveStore.LEGACY_NAME }).distinct()
        require(names.size <= 201) { "类型数量过多" }
        names.forEach { GachaArchiveStore.validateName(it) }
        return names
    }

    fun markdownBundle(bundle: GachaBundle): String = buildString {
        val names = bundleNames(bundle)
        val groups = bundle.records.groupBy { it.poolCategory ?: GachaArchiveStore.LEGACY_NAME }
        append("# 全部抽卡记录\n\n共 ${bundle.records.size} 条。迁移档案请使用 Excel。\n\n")
        names.forEach { name -> append("- ${name.replace('\n', ' ')}：${groups[name].orEmpty().size} 抽\n") }
        append("\n|池子类型|时间|追踪类型|卡池|稀有度|角色|\n|---|---|---|---|---|---|\n")
        names.forEach { name -> groups[name].orEmpty().sortedWith(newestFirst).forEach { record ->
            append(listOf(name, record.time, record.category, record.pool, record.rarity.orEmpty(), record.name)
                .joinToString("|", "|", "|\n") { it.replace("|", "\\|").replace("\n", " ").replace("\r", " ") })
        } }
    }

    fun markdown(records: List<GachaRecord>): String = buildString {
        append("# 抽卡记录\n\n共 ${records.size} 条。此文件用于阅读；迁移档案请使用 Excel。\n\n")
        records.groupBy { it.category }.forEach { (type, rows) ->
            append("## $type\n\n${rows.size} 抽 · ")
            append(rows.groupingBy { it.rarity ?: "未判" }.eachCount().entries.joinToString(" / ") { "${it.key} ${it.value}" })
            append("\n\n")
        }
        append("|时间|追踪类型|卡池|稀有度|角色|\n|---|---|---|---|---|\n")
        records.sortedByDescending { it.time }.forEach { record ->
            append(listOf(record.time, record.category, record.pool, record.rarity.orEmpty(), record.name)
                .joinToString("|", "|", "|\n") { it.replace("|", "\\|").replace("\n", " ").replace("\r", " ") })
        }
    }
}
