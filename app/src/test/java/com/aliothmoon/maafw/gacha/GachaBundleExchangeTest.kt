package com.aliothmoon.maafw.gacha

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class GachaBundleExchangeTest {
    private fun record(name: String = "角色", category: String = "新角色", index: Int = 1): GachaRecord {
        val cells = listOf("2026-10-1510:00:00", "同名卡池", name)
        return GachaRecord(index = index, key = GachaStore.key(cells), type = "活动", rarity = "狂", cells = cells, poolCategory = category)
    }

    private fun workbook(bundle: GachaBundle) = ByteArrayOutputStream().also { GachaExchange.writeBundleExcel(bundle, it) }.toByteArray()

    private fun editSheet(bytes: ByteArray, sheet: String = "xl/worksheets/sheet1.xml", edit: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { target ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val content = source.readBytes()
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(if (entry.name == sheet) edit(content.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else content)
                    target.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test fun readsDesktopFullWorkbookAndKeepsEmptyCategories() {
        val bundle = javaClass.getResourceAsStream("/gacha/desktop-all.xlsx")!!.use(GachaExchange::readBundleExcel)
        assertEquals(listOf("新角色", "限定", "联动", "常驻", "复刻", "桌面空类型", "未分类（旧记录）"), bundle.categories)
        assertEquals(4, bundle.records.size)
        assertEquals(listOf("甲", "乙"), bundle.records.filter { it.poolCategory == "新角色" }.map { it.name })
        assertEquals(listOf("活动", "活动", "活动", "常驻"), bundle.records.map { it.type })
        val root = Files.createTempDirectory("gacha-desktop-interop-").toFile()
        try {
            val store = GachaArchiveStore(root)
            assertEquals(4, store.importAll(bundle))
            assertEquals(0, store.importAll(bundle))
            assertTrue(store.bundle().categories.contains("桌面空类型"))
            assertEquals(1, store.snapshot().records.count { it.poolCategory == "限定" })
        } finally { root.deleteRecursively() }
    }

    @Test fun mobileBundleRoundTripKeepsCategoriesTrackingTypesAndDuplicatePulls() {
        val first = record("角色 & <特殊>|", index = 1)
        val records = listOf(first.copy(index = 2), first, first.copy(poolCategory = "限定"))
        val source = GachaBundle(listOf("新角色", "限定", "空类型"), records)
        val bytes = workbook(source)
        val bundle = GachaExchange.readBundleExcel(ByteArrayInputStream(bytes))
        assertEquals(source.categories, bundle.categories)
        assertEquals(listOf(1, 2, 1), bundle.records.map { it.index })
        assertEquals(listOf("新角色", "新角色", "限定"), bundle.records.map { it.poolCategory })
        assertEquals(3, bundle.records.size)
        assertTrue(bundle.records.all { it.category == "活动" && it.cells == first.cells })
        val output = File("build/gacha-bundle-interop.xlsx")
        output.parentFile?.mkdirs()
        output.writeBytes(bytes)
    }

    @Test fun visibleCorrectionsWithStaleRawCellsAreRejected() {
        val source = workbook(GachaBundle(listOf("新角色"), listOf(record())))
        listOf("2026-10-15 10:00:00" to "2026-10-16 10:00:00", "同名卡池" to "修正卡池", "角色" to "修正角色").forEach { (old, corrected) ->
            val bytes = editSheet(source) { it.replace("<t xml:space=\"preserve\">$old</t>", "<t xml:space=\"preserve\">$corrected</t>") }
            val error = assertThrows(IllegalArgumentException::class.java) { GachaExchange.readBundleExcel(ByteArrayInputStream(bytes)) }
            assertTrue(error.message!!.contains("原始列未同步"))
        }
    }

    @Test fun formulaInCategoryCatalogAndBlankCategoryAreRejected() {
        val source = workbook(GachaBundle(listOf("新角色", "空类型"), listOf(record())))
        val formula = editSheet(source, "xl/worksheets/sheet2.xml") { it.replace("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">空类型</t></is></c>", "<c><f>1+1</f><v>2</v></c>") }
        assertThrows(IllegalArgumentException::class.java) { GachaExchange.readBundleExcel(ByteArrayInputStream(formula)) }
        val blank = editSheet(source) { it.replace("<t xml:space=\"preserve\">新角色</t>", "<t xml:space=\"preserve\"></t>") }
        assertThrows(IllegalArgumentException::class.java) { GachaExchange.readBundleExcel(ByteArrayInputStream(blank)) }
    }

    @Test fun olderExcelCanStillBeReadThroughBothApis() {
        val old = ByteArrayOutputStream().also { GachaExchange.writeExcel(listOf(record().copy(poolCategory = null)), it) }.toByteArray()
        assertEquals("活动", GachaExchange.readExcel(ByteArrayInputStream(old)).single().category)
        val bundle = GachaExchange.readBundleExcel(ByteArrayInputStream(old))
        assertEquals(listOf("未分类（旧记录）"), bundle.categories)
        assertEquals("未分类（旧记录）", bundle.records.single().poolCategory)
        assertEquals("活动", bundle.records.single().type)
    }

    @Test fun markdownIncludesAllCategoriesAndEscapesDetailCells() {
        val markdown = GachaExchange.markdownBundle(GachaBundle(listOf("新角色", "空类型"), listOf(record("角色|甲"))))
        assertTrue(markdown.contains("空类型：0 抽"))
        assertTrue(markdown.contains("|新角色|2026-10-15 10:00:00|活动|同名卡池|狂|角色\\|甲|"))
    }
}
