package com.aliothmoon.maafw.gacha

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GachaArchiveStoreTest {
    private fun record(name: String = "角色", category: String? = "新角色", index: Int = 0, type: String = "活动"): GachaRecord {
        val cells = listOf("2026-10-1510:00:00", "同名实际卡池", name)
        return GachaRecord(index = index, key = GachaStore.key(cells), rarity = "狂", type = type, cells = cells, poolCategory = category)
    }

    private fun temporary(block: (File, GachaArchiveStore) -> Unit) {
        val root = Files.createTempDirectory("gacha-archive-").toFile()
        try { block(root, GachaArchiveStore(root)) } finally { root.deleteRecursively() }
    }

    private fun collected(root: File, categoryId: String, records: List<GachaRecord>, filename: String = "活动.jsonl"): File {
        val directory = File(root, if (categoryId == "legacy") "record/抽卡记录" else "record/gacha/$categoryId")
        directory.mkdirs()
        return File(directory, filename).also { file ->
            file.writeText(records.joinToString("", transform = { Json.encodeToString(it.copy(poolCategory = null, categoryId = null)) + "\n" }))
        }
    }

    @Test fun defaultsAndLegacyKeepTrackingTypeSeparateAndNeverMigrateOldFiles() = temporary { root, store ->
        val legacy = collected(root, "legacy", listOf(record(category = null, type = "常驻")))
        val bytes = legacy.readBytes()
        val snapshot = store.snapshot()
        assertEquals(listOf("default-0", "default-1", "default-2", "default-3", "default-4", "legacy"), snapshot.categories.map { it.id })
        assertEquals("未分类（旧记录）", snapshot.records.single().poolCategory)
        assertEquals("legacy", snapshot.records.single().categoryId)
        assertEquals("常驻", snapshot.records.single().type)
        assertArrayEquals(bytes, legacy.readBytes())
        assertEquals(1, GachaStore(root).read().size)
        val catalog = Json.decodeFromString<List<GachaCategory>>(File(root, "gacha-categories.json").readText())
        assertEquals(5, catalog.size)
        assertFalse(catalog.any { it.id == "legacy" })
    }

    @Test fun allCategoryMergeIsIsolatedIdempotentAndPreservesEmptyTypes() = temporary { _, store ->
        val same = record()
        val incoming = GachaBundle(listOf("新角色", "限定", "自定义空类型"), listOf(same, same, same.copy(poolCategory = "限定")))
        assertEquals(3, store.importAll(incoming))
        assertEquals(0, store.importAll(incoming))
        val snapshot = store.snapshot()
        assertEquals(2, snapshot.records.count { it.poolCategory == "新角色" })
        assertEquals(1, snapshot.records.count { it.poolCategory == "限定" })
        assertNotNull(snapshot.categories.firstOrNull { it.name == "自定义空类型" })
        assertTrue(snapshot.records.all { it.category == "活动" })
        assertTrue(snapshot.records.filter { it.poolCategory == "新角色" }.all { it.categoryId == "default-0" })
    }

    @Test fun invalidLastRecordDoesNotPublishEarlierCategoriesOrCatalog() = temporary { root, store ->
        store.snapshot()
        val catalog = File(root, "gacha-categories.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            store.importAll(GachaBundle(listOf("新的空类型"), listOf(record(), record(category = "限定").copy(key = "wrong"))))
        }
        assertArrayEquals(catalog, File(root, "gacha-categories.json").readBytes())
        assertTrue(store.snapshot().records.isEmpty())
        assertFalse(File(root, "record/gacha/default-0/导入.jsonl").exists())
    }

    @Test fun damagedLastArchiveDoesNotPartiallyImportOtherCategory() = temporary { root, store ->
        store.snapshot()
        val first = collected(root, "default-0", listOf(record(index = 1)))
        val before = first.readBytes()
        val broken = File(root, "record/gacha/default-1/活动.jsonl")
        broken.parentFile!!.mkdirs()
        broken.writeText("not json\n")
        assertThrows(IllegalStateException::class.java) {
            store.importAll(GachaBundle(listOf("新角色", "限定"), listOf(record("新记录"), record(category = "限定"))))
        }
        assertArrayEquals(before, first.readBytes())
        assertFalse(File(root, "record/gacha/default-0/导入.jsonl").exists())
    }

    @Test fun collectedFilesAreNeverRewrittenAndForeignIdsAreMatchedByName() = temporary { root, store ->
        val original = collected(root, "default-0", listOf(record(index = 1)))
        val before = original.readBytes()
        assertEquals(1, store.importAll(GachaBundle(listOf("新角色"), listOf(record(index = 1), record("新增").copy(categoryId = "foreign-id")))))
        assertArrayEquals(before, original.readBytes())
        assertTrue(File(root, "record/gacha/default-0/导入.jsonl").isFile)
        assertFalse(File(root, "record/gacha/foreign-id").exists())
        assertTrue(store.snapshot().records.all { it.categoryId == "default-0" })
    }

    @Test fun bundleRoundtripPreservesSameSecondOrderAcrossFiles() = temporary { root, store ->
        collected(root, "default-0", listOf(record("最新", index = 1), record("中间", index = 2)))
        collected(root, "default-0", listOf(record("最旧", index = 3)), "导入.jsonl")
        store.addCategory("空类型")
        val bytes = ByteArrayOutputStream().also { GachaExchange.writeBundleExcel(store.bundle(), it) }.toByteArray()
        val bundle = GachaExchange.readBundleExcel(ByteArrayInputStream(bytes))
        assertEquals(listOf("最新", "中间", "最旧"), bundle.records.map { it.name })
        val restoredRoot = Files.createTempDirectory("gacha-restored-").toFile()
        try {
            val restored = GachaArchiveStore(restoredRoot)
            assertEquals(3, restored.importAll(bundle))
            assertEquals(0, restored.importAll(bundle))
            assertEquals(listOf("最新", "中间", "最旧"), restored.snapshot().records.sortedBy { it.index }.map { it.name })
            assertTrue(restored.bundle().categories.contains("空类型"))
            assertEquals(store.bundle().records.map { it.cells }.toSet(), restored.bundle().records.map { it.cells }.toSet())
        } finally { restoredRoot.deleteRecursively() }
    }

    @Test fun backupDeletionPreservesCategoryAndRecordsAndPermanentDeletionRemovesExports() = temporary { root, store ->
        val category = store.addCategory("自定义")
        collected(root, category.id, listOf(record(category = category.name)))
        val archive = File(root, "record/gacha/${category.id}")
        File(archive.path + ".xlsx").writeText("xlsx")
        File(archive.path + ".md").writeText("md")
        val backup = File(store.deleteCategory(category.id, permanent = false)!!)
        assertTrue(File(backup, "category.json").isFile)
        assertTrue(File(backup, "records/活动.jsonl").isFile)
        assertEquals("xlsx", File(backup, "records.xlsx").readText())
        assertFalse(archive.exists())
        assertFalse(store.snapshot().categories.any { it.id == category.id })
        val second = store.addCategory("彻底删除")
        collected(root, second.id, listOf(record(category = second.name)))
        val secondArchive = File(root, "record/gacha/${second.id}")
        File(secondArchive.path + ".md").writeText("report")
        assertNull(store.deleteCategory(second.id, permanent = true))
        assertFalse(secondArchive.exists())
        assertFalse(File(secondArchive.path + ".md").exists())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".gacha-delete-") })
    }

    @Test fun legacyCanBeHiddenAndRestoredByBundleImport() = temporary { root, store ->
        collected(root, "legacy", listOf(record(category = null)))
        val bundle = store.bundle()
        assertNotNull(store.deleteCategory("legacy", permanent = false))
        assertFalse(store.snapshot().categories.any { it.id == "legacy" })
        assertEquals(1, store.importAll(bundle))
        assertTrue(store.snapshot().categories.any { it.id == "legacy" })
        assertEquals(1, store.snapshot().records.size)
    }

    @Test fun cannotDeleteLastRegularCategoryOrEscapeThroughCatalog() = temporary { root, store ->
        val categories = store.snapshot().categories.filter { it.id != "legacy" }
        categories.drop(1).forEach { store.deleteCategory(it.id, permanent = true) }
        assertThrows(IllegalArgumentException::class.java) { store.deleteCategory(categories.first().id, permanent = true) }
        val catalog = File(root, "gacha-categories.json")
        catalog.writeText("[{\"id\":\"../outside\",\"name\":\"坏路径\"}]")
        assertThrows(IllegalArgumentException::class.java) { store.snapshot() }
        assertThrows(IllegalArgumentException::class.java) { store.deleteCategory("../outside", permanent = true) }
    }

    @Test fun categoryValidationAndSharedFileLockPreventUnsafeMutation() = temporary { root, store ->
        store.snapshot()
        assertThrows(IllegalArgumentException::class.java) { store.addCategory("常驻") }
        assertThrows(IllegalArgumentException::class.java) { store.addCategory("坏\n名称") }
        assertThrows(IllegalArgumentException::class.java) {
            store.importAll(GachaBundle(emptyList(), listOf(record().copy(categoryId = "../../outside"))))
        }
        RandomAccessFile(File(root, ".gacha.lock"), "rw").use { file ->
            file.channel.lock().use {
                assertThrows(IllegalStateException::class.java) { store.addCategory("锁定期间") }
            }
        }
        assertFalse(store.snapshot().categories.any { it.name == "锁定期间" })
        assertNotNull(store.addCategory("正常新增"))
    }

    @Test fun dottedOcrTimestampIsValidAndNormalized() = temporary { root, store ->
        val cells = listOf("2026-11-14.18:50:50", "慈·爱·愈", "玛奇朵")
        val dottedRecord = GachaRecord(index = 11, key = GachaStore.key(cells), rarity = "普", type = "活动", cells = cells)
        GachaStore.validate(dottedRecord)
        assertEquals("2026-11-14 18:50:50", dottedRecord.time)
        collected(root, "default-0", listOf(dottedRecord))
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.records.size)
        assertEquals("玛奇朵", snapshot.records.single().name)
        assertEquals("2026-11-14 18:50:50", snapshot.records.single().time)
    }

    @Test fun snapshotIsResilientToDamagedLines() = temporary { root, store ->
        val good = record("正常角色", index = 1)
        val file = collected(root, "default-0", listOf(good))
        file.appendText("bad non json line\n")
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.records.size)
        assertEquals("正常角色", snapshot.records.single().name)
    }

    @Test fun snapshotReadsSparseCategoriesWithUnicodeFilenamesAndRejectsTraversal() = temporary { root, store ->
        collected(root, "default-0", listOf(record("角色A", index = 1)), "活动.jsonl")
        collected(root, "default-4", listOf(record("角色B", category = "复刻", index = 1)), "常规.jsonl")
        val snapshot = store.snapshot()
        assertEquals(2, snapshot.records.size)
        assertEquals(setOf("角色A", "角色B"), snapshot.records.map { it.name }.toSet())
        val catalog = File(root, "gacha-categories.json")
        catalog.writeText("[{\"id\":\"../outside\",\"name\":\"越界\"}]")
        assertThrows(IllegalArgumentException::class.java) { store.snapshot() }
    }

    @Test fun concurrentSnapshotsInSameJvmDoNotCollide() = temporary { root, store ->
        collected(root, "default-0", listOf(record("角色A", index = 1), record("角色B", index = 2)), "活动.jsonl")
        val workers = 4
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val counts = Collections.synchronizedList(mutableListOf<Int>())
        repeat(workers) {
            Thread {
                try {
                    start.await()
                    counts += store.snapshot().records.size
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("Unexpected errors: $errors", errors.isEmpty())
        assertEquals(List(workers) { 2 }, counts)
    }

    @Test fun collectorCanonicalKeyWithMiddleDotPoolPassesValidation() = temporary { root, store ->
        val cells = listOf("2026-11-25 17:02:01", "慈·爱·愈", "玛奇朵")
        val collectorKey = "20261125170201慈爱愈玛奇朵"
        val directory = File(root, "record/gacha/default-0").apply { mkdirs() }
        val line = Json.encodeToString(
            GachaRecord(index = 501, key = collectorKey, rarity = "普", type = "活动", cells = cells)
        )
        File(directory, "活动.jsonl").writeText(line + "\n", Charsets.UTF_8)

        val snapshot = store.snapshot()
        assertTrue("Unexpected skipped lines: ${snapshot.skipped}", snapshot.skipped.isEmpty())
        assertEquals(1, snapshot.records.size)
        assertEquals(collectorKey, snapshot.records.single().canonicalKey)

        val legacyRecord = GachaRecord(
            index = 502,
            key = "20261125170201慈·爱·愈玛奇朵",
            rarity = "普",
            type = "活动",
            cells = cells,
        )
        GachaStore.validate(legacyRecord)

        val mismatchedRecord = legacyRecord.copy(key = "20261125170201其他池玛奇朵")
        assertThrows(IllegalArgumentException::class.java) { GachaStore.validate(mismatchedRecord) }
    }

    @Test fun progressCompletionSummaryDistinguishesCompletedPartialAndStopped() {
        val completed = GachaProgress(state = "done", status = "completed", new = 3, pages = 2).completionSummary()
        assertEquals("采集完成：本次新增 3 条（共扫 2 页）" to false, completed)

        val partial = GachaProgress(
            state = "done",
            status = "partial",
            reason = "脱离抽卡记录页",
            new = 1,
            pages = 1,
        ).completionSummary()
        assertEquals("采集未完整结束（脱离抽卡记录页）：已保留新增 1 条（共扫 1 页）" to true, partial)

        val stopped = GachaProgress(state = "running", status = "running", new = 5, pages = 3).completionSummary()
        assertEquals("采集已提前停止：已保留新增 5 条（共扫 3 页）" to false, stopped)
    }

    @Test fun progressReadRejectsPreviousRunAndClearDeletesFile() = temporary { root, _ ->
        val progressFile = File(root, "record/gacha/.progress.json").apply { parentFile!!.mkdirs() }
        val startedAtMs = 1_795_600_000_000L
        progressFile.writeText(
            """{"category":"default-0","state":"done","pages":1,"new":1,"status":"completed","updated":${(startedAtMs - 1_000L) / 1000.0}}""",
            Charsets.UTF_8,
        )
        assertNull(GachaProgress.read(root, startedAtMs))
        GachaProgress.clear(root, startedAtMs)
        assertFalse(progressFile.exists())

        progressFile.writeText(
            """{"category":"default-0","state":"done","pages":2,"new":3,"status":"completed","updated":${(startedAtMs + 500L) / 1000.0}}""",
            Charsets.UTF_8,
        )
        assertEquals(3, GachaProgress.read(root, startedAtMs)?.new)
        GachaProgress.clear(root)
        assertFalse(progressFile.exists())
    }
}
