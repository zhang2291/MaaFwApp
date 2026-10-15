package com.aliothmoon.maafw.gacha

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class GachaExchangeTest {
    private fun record(name: String = "测试角色", type: String = "活动", time: String = "2026-10-15 10:00:00"): GachaRecord {
        val cells = listOf(time, "测试卡池", name)
        return GachaRecord(key = GachaStore.key(cells), rarity = "狂", type = type, cells = cells)
    }

    @Test fun repeatedImportsPreserveMultiplicity() {
        val a = record()
        val first = GachaStore.merge(emptyList(), listOf(a, a, a))
        assertEquals(3, first.size)
        assertEquals(3, GachaStore.merge(first, listOf(a, a, a)).size)
        assertEquals(4, GachaStore.merge(first, listOf(a, a, a, a)).size)
        assertEquals(4, GachaStore.merge(first, listOf(record(type = "常驻"))).size)
    }

    @Test fun excelRoundTripPreservesRawCellsAndChineseText() {
        val records = listOf(record("角色 & <特殊>|", time = "2026-10-1510:00:00"), record(), record())
        val bytes = ByteArrayOutputStream().also { GachaExchange.writeExcel(records, it) }.toByteArray()
        val restored = GachaExchange.readExcel(ByteArrayInputStream(bytes))
        assertEquals(records.map { it.cells }, restored.map { it.cells })
        assertEquals(records.map { it.identity }, restored.map { it.identity })
        val output = java.io.File("build/gacha-interop.xlsx")
        output.parentFile?.mkdirs()
        output.writeBytes(bytes)
    }

    @Test fun readsUpstreamOpenpyxlSheetsAndRepeatedPulls() {
        val records = javaClass.getResourceAsStream("/gacha/upstream.xlsx")!!.use(GachaExchange::readExcel)
        assertEquals(4, records.size)
        assertEquals(listOf("活动", "活动", "常驻", "常驻"), records.map { it.category })
        assertEquals(4, GachaStore.merge(records, records).size)
    }

    @Test fun importDoesNotOverwriteCollectedFilesAndRejectsInvalidBatch() {
        val root = Files.createTempDirectory("gacha-test").toFile()
        try {
            val store = GachaStore(root)
            assertEquals(2, store.importRecords(listOf(record(), record())))
            assertEquals(0, store.importRecords(listOf(record(), record())))
            try {
                store.importRecords(listOf(record("新角色"), record().copy(key = "invalid")))
                fail("invalid batch accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(2, store.read().size)
        } finally { root.deleteRecursively() }
    }

    @Test fun malformedWorkbookDoesNotImport() {
        try {
            GachaExchange.readExcel(ByteArrayInputStream("not xlsx".toByteArray()))
            fail("invalid workbook accepted")
        } catch (_: IllegalStateException) { }
    }
}
