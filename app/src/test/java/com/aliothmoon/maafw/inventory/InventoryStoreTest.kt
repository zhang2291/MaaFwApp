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
}
