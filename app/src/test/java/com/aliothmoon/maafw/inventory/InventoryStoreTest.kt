package com.aliothmoon.maafw.inventory

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InventoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun write(path: String, body: String): File = File(temporary.root, path).apply {
        parentFile!!.mkdirs(); writeText(body)
    }
    private fun store() = InventoryStore(File(temporary.root, "data"), File(temporary.root, "pi"))

    @Test fun `partial snapshot preserves null conflicts unknown and box grouping`() {
        write("data/inventory/latest.json", """{"version":1,"captured_at":"2026-10-23T01:00:00+08:00","status":"partial",
          "currencies":{"异方晶":null,"狄斯币":0},"categories":{"消耗品":{"status":"partial","reason":"未识别",
          "items":{"a":{"name":"材料箱","quantity":null},"b":{"name":"搜索令","quantity":1,"conflicting_quantities":[1,2]}},
          "unknown":{"c":{"quantity":null,"image":"../../private.png"}}}}}""")
        val snapshot = store().load()!!
        assertNull(snapshot.currencies["异方晶"])
        assertEquals(0L, snapshot.currencies["狄斯币"])
        assertEquals("箱子", snapshot.items[0].group)
        assertEquals("未识别", snapshot.items[0].quantityText)
        assertEquals("数量冲突：1 / 2", snapshot.items[1].quantityText)
        assertTrue(snapshot.items.last().unknown)
        assertNull(snapshot.items.last().image)
        assertTrue(snapshot.markdown().contains("部分识别"))
        assertTrue(snapshot.markdown().contains("异方晶：未识别"))
    }

    @Test fun `catalog and captures cannot escape their allowed roots`() {
        val privateFile = write("private.png", "private")
        val root = File(temporary.root, "data/inventory/captures")
        write("data/inventory/captures/known.png", "image")
        assertNull(store().safeFile(root, "../../../private.png"))
        assertNull(store().safeFile(root, privateFile.absolutePath))
        assertNotNull(store().safeFile(root, "known.png"))
        write("pi/resource/base/image/仓库/good.png", "image")
        write("pi/resource/base/image/仓库/catalog.json", """{"items":[{"id":"a","template":"仓库/good.png"},{"id":"b","template":"../../../../private.png"}]}""")
        write("data/inventory/latest.json", """{"version":1,"categories":{"道具":{"items":{"a":{"name":"A","quantity":3},"b":{"name":"B","quantity":4}}}}}""")
        val snapshot = store().load()!!
        assertNotNull(snapshot.items[0].image)
        assertNull(snapshot.items[1].image)
    }

    @Test fun `missing file returns empty while malformed and future versions are reported`() {
        assertNull(store().load())
        write("data/inventory/latest.json", "{broken")
        assertThrows(Exception::class.java) { store().load() }
        write("data/inventory/latest.json", """{"version":2,"categories":{}}""")
        assertThrows(IllegalArgumentException::class.java) { store().load() }
    }

    @Test fun `history enumerates only direct valid snapshots and preserves partial readings`() {
        val partial = """{"version":1,"run_id":"20261022-233930-182857","captured_at":"2026-10-22T23:39:30+08:00",
          "status":"partial","currencies":{"异方晶":null},"categories":{"道具":{"status":"partial","reason":"用户停止",
          "items":{"ticket":{"name":"搜索令","quantity":null}}}}}"""
        write("data/inventory/history/20261022-233930-182857.json", partial)
        write("data/inventory/history/nested/hidden.json", partial)
        write("data/inventory/history/broken.json", "{broken")
        write("data/inventory/history/future.json", """{"version":2,"categories":{}}""")
        write("data/inventory/history/ignored.txt", partial)
        assertEquals(listOf("20261022-233930-182857.json"), store().history().map { it.fileName })
        val loaded = store().loadHistory("20261022-233930-182857.json")
        assertEquals("20261022-233930-182857", loaded.runId)
        assertEquals("partial", loaded.status)
        assertNull(loaded.currencies["异方晶"])
        assertNull(loaded.items.single().quantity)
        assertEquals("用户停止", loaded.categories.single().reason)
    }

    @Test fun `history rejects traversal absolute paths and nested files`() {
        val outside = write("outside.json", """{"version":1,"categories":{}}""")
        write("data/inventory/history/nested/hidden.json", outside.readText())
        listOf("../../../outside.json", outside.absolutePath, "nested/hidden.json", "..\\outside.json").forEach { path ->
            assertThrows(IllegalStateException::class.java) { store().loadHistory(path) }
        }
    }

    @Test fun `agent absolute captures remain limited to inventory captures directory`() {
        val capture = write("data/inventory/captures/run/unknown.png", "image")
        val outside = write("data/inventory/history/private.png", "private")
        fun jsonPath(file: File) = file.absolutePath.replace("\\", "\\\\")
        write("data/inventory/latest.json", """{"version":1,"categories":{"道具":{"unknown":{
          "good":{"quantity":3,"image":"${jsonPath(capture)}"},
          "bad":{"quantity":3,"image":"${jsonPath(outside)}"}}}}}""")
        val items = store().load()!!.items
        assertEquals(capture.canonicalFile, items.first().image)
        assertNull(items.last().image)
    }

    @Test fun `history supports custom rename clear and safe delete`() {
        val snap = """{"version":1,"captured_at":"2026-11-06T14:22:11+08:00","status":"partial","currencies":{},"categories":{"道具":{"status":"partial","reason":"","items":{}}}}"""
        write("data/inventory/latest.json", snap)
        write("data/inventory/history/20261106-142211-364634.json", snap)
        write("data/inventory/history/20261030-185016-567628.json", snap)

        val s = store()
        assertEquals("抽席宁前存货", s.renameHistory("20261106-142211-364634.json", "  抽席宁前存货  "))
        val entries = s.history()
        assertEquals("抽席宁前存货", entries.first { it.fileName == "20261106-142211-364634.json" }.label)
        assertEquals("", entries.first { it.fileName == "20261030-185016-567628.json" }.label)

        assertThrows(IllegalArgumentException::class.java) {
            s.renameHistory("20261106-142211-364634.json", "a".repeat(41))
        }
        assertThrows(IllegalStateException::class.java) {
            s.renameHistory("../latest.json", "越界")
        }

        assertEquals("", s.renameHistory("20261106-142211-364634.json", "   "))
        assertEquals("", s.history().first { it.fileName == "20261106-142211-364634.json" }.label)

        s.renameHistory("20261106-142211-364634.json", "待删快照")
        s.deleteHistory("20261106-142211-364634.json")
        assertEquals(listOf("20261030-185016-567628.json"), s.history().map { it.fileName })
        assertNotNull(s.load())
        assertThrows(IllegalStateException::class.java) { s.deleteHistory("../latest.json") }
        assertThrows(IllegalStateException::class.java) { s.deleteHistory("missing.json") }
    }
}
