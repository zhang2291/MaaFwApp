package com.aliothmoon.maafw.gacha

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Runs the real Android JAXP implementation which differs from the host JVM's parser. */
@RunWith(AndroidJUnit4::class)
class GachaExchangeInstrumentedTest {
    private fun workbook(): ByteArray {
        val cells = listOf("2026-10-1510:00:00", "卡池 & 中文", "测试角色")
        val record = GachaRecord(index = 1, key = GachaStore.key(cells), cells = cells, rarity = "狂", type = "活动", poolCategory = "新角色")
        return ByteArrayOutputStream().also {
            GachaExchange.writeBundleExcel(GachaBundle(listOf("新角色", "空类型"), listOf(record, record.copy(index = 2))), it)
        }.toByteArray()
    }

    private fun rewriteXml(source: ByteArray, rewrite: (String, String) -> ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            ZipInputStream(ByteArrayInputStream(source)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val content = input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) rewrite(entry.name, content.toString(Charsets.UTF_8)) else content)
                    output.closeEntry()
                }
            }
        }
        return bytes.toByteArray()
    }

    @Test fun actualAndroidParserImportsFullWorkbookWithEmptyCategoryAndRepeatedPulls() {
        val bundle = GachaExchange.readBundleExcel(ByteArrayInputStream(workbook()))
        assertEquals(listOf("新角色", "空类型"), bundle.categories)
        assertEquals(2, bundle.records.size)
        assertEquals(listOf(1, 2), bundle.records.map { it.index })
        assertTrue(bundle.records.all { it.poolCategory == "新角色" && it.type == "活动" && it.pool == "卡池 & 中文" })
    }

    @Test fun actualAndroidParserImportsUtf16LeAndBeWorkbookParts() {
        listOf(Charsets.UTF_16LE, Charsets.UTF_16BE).forEach { charset ->
            listOf(false, true).forEach { bom ->
                val source = rewriteXml(workbook()) { _, text -> encode(text, charset, bom) }
                val bundle = GachaExchange.readBundleExcel(ByteArrayInputStream(source))
                assertEquals(2, bundle.records.size)
                assertEquals("测试角色", bundle.records.first().name)
            }
        }
    }

    @Test fun actualAndroidParserRejectsExternalDtdInUtf8AndUtf16BeforeResolving() {
        listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).forEach { charset ->
            val source = rewriteXml(workbook()) { name, original ->
                val text = original.replace(Regex("^<\\?xml[^?]*\\?>"), "")
                val unsafe = if (name == "xl/workbook.xml") "<!DOCTYPE workbook [<!ENTITY xxe SYSTEM 'file:///should-never-be-read'>]>$text" else text
                encode(unsafe, charset, bom = true)
            }
            val error = assertThrows(IllegalArgumentException::class.java) { GachaExchange.readBundleExcel(ByteArrayInputStream(source)) }
            assertTrue(error.message!!.contains("DTD 或实体声明"))
        }
    }

    private fun encode(original: String, charset: Charset, bom: Boolean): ByteArray {
        val text = original.replace(Regex("^<\\?xml[^?]*\\?>"), "")
        val declaration = if (charset == Charsets.UTF_8) "UTF-8" else "UTF-16"
        val marker = when (charset) {
            Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            else -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        }
        return (if (bom) marker else byteArrayOf()) + "<?xml version=\"1.0\" encoding=\"$declaration\"?>$text".toByteArray(charset)
    }
}
