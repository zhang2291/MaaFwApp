package com.aliothmoon.maafw.gacha

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.DOMImplementation
import org.w3c.dom.Document
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import java.nio.charset.Charset
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

class GachaXmlTest {
    /** Models Android's unsupported SAX feature URIs without requiring Robolectric or Android stubs. */
    private class UnsupportedFeaturesFactory(private val probeResolver: Boolean = false) : DocumentBuilderFactory() {
        var created = 0
        override fun setFeature(name: String, value: Boolean) { throw ParserConfigurationException(name) }
        override fun getFeature(name: String): Boolean = throw ParserConfigurationException(name)
        override fun setAttribute(name: String, value: Any?) { throw IllegalArgumentException(name) }
        override fun getAttribute(name: String): Any = throw IllegalArgumentException(name)
        override fun setXIncludeAware(state: Boolean) { throw UnsupportedOperationException("XInclude") }
        override fun setExpandEntityReferences(expandEntityRef: Boolean) { throw UnsupportedOperationException("entities") }
        override fun newDocumentBuilder(): DocumentBuilder {
            created++
            val delegate = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = isNamespaceAware }.newDocumentBuilder()
            return if (probeResolver) ResolverProbe(delegate) else delegate
        }
    }

    private class ResolverProbe(private val delegate: DocumentBuilder) : DocumentBuilder() {
        private var resolver: EntityResolver? = null
        override fun setEntityResolver(er: EntityResolver?) { resolver = er }
        override fun setErrorHandler(eh: ErrorHandler?) { delegate.setErrorHandler(eh) }
        override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware
        override fun isValidating(): Boolean = delegate.isValidating
        override fun newDocument(): Document = delegate.newDocument()
        override fun getDOMImplementation(): DOMImplementation = delegate.domImplementation
        override fun parse(input: InputSource): Document {
            checkNotNull(resolver).resolveEntity(null, "file:///should-never-be-read")
            error("External resolver accepted a request")
        }
    }

    private fun encoded(xml: String, charset: Charset, bom: Boolean): ByteArray {
        val marker = when (charset) {
            Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            else -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        }
        return (if (bom) marker else byteArrayOf()) + xml.toByteArray(charset)
    }

    @Test fun unsupportedAndroidFeaturesDoNotPreventSafeNamespaceAwareParsing() {
        val factory = UnsupportedFeaturesFactory()
        val root = GachaXml.parse("<workbook xmlns=\"urn:test\"><name>角色 &amp; 卡池</name></workbook>".toByteArray(), factory)
        assertEquals("workbook", root.localName)
        assertEquals("urn:test", root.namespaceURI)
        assertEquals("角色 & 卡池", root.textContent)
        assertEquals(1, factory.created)
    }

    @Test fun safeUtf8AndUtf16WithOrWithoutBomUseTheSameText() {
        listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).forEach { charset ->
            listOf(false, true).forEach { bom ->
                val declaration = if (charset == Charsets.UTF_8) "UTF-8" else "UTF-16"
                val bytes = encoded("<?xml version=\"1.0\" encoding=\"$declaration\"?><root>中文 &amp; &#x7532;</root>", charset, bom)
                assertEquals("中文 & 甲", GachaXml.parse(bytes, UnsupportedFeaturesFactory()).textContent)
            }
        }
    }

    @Test fun dtdAndExternalEntitiesAreRejectedBeforeAnyBuilderForEverySupportedEncoding() {
        val declarations = listOf(
            "<!DOCTYPE root SYSTEM 'https://example.invalid/external.dtd'>",
            "<!DOCTYPE root [<!ENTITY xxe SYSTEM 'file:///should-never-be-read'>]>",
            "<!DOCTYPE root [<!ENTITY % external SYSTEM 'https://example.invalid/parameter.dtd'>%external;]>",
            "<!DOCTYPE root [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;&a;'>]>",
            "<!ENTITY orphan SYSTEM 'file:///should-never-be-read'>",
        )
        listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE).forEach { charset ->
            listOf(false, true).forEach { bom ->
                declarations.forEach { dtd ->
                    val encoding = if (charset == Charsets.UTF_8) "UTF-8" else "UTF-16"
                    val factory = UnsupportedFeaturesFactory()
                    val bytes = encoded("<?xml version='1.0' encoding='$encoding'?>$dtd<root/>", charset, bom)
                    val error = assertThrows(IllegalArgumentException::class.java) { GachaXml.parse(bytes, factory) }
                    assertTrue(error.message!!.contains("DTD 或实体声明"))
                    assertFalse(error.message!!.contains("http://xml.org"))
                    assertEquals(0, factory.created)
                }
            }
        }
    }

    @Test fun encodingMismatchMalformedBytesAndUtf32AreRejectedBeforeDom() {
        val malicious = "<?xml version='1.0' encoding='UTF-8'?><!DOCTYPE root SYSTEM 'file:///outside'><root/>"
        val inputs = listOf(
            encoded(malicious, Charsets.UTF_16LE, true),
            encoded(malicious, Charsets.UTF_16BE, false),
            "<?xml version='1.0' encoding='ISO-8859-1'?><root/>".toByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x3C),
            byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()) + "<root/>".toByteArray(Charset.forName("UTF-32BE")),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + "<root/>".toByteArray(Charset.forName("UTF-32LE")),
            "<root/>".toByteArray(Charset.forName("UTF-32BE")),
            "<root/>".toByteArray(Charset.forName("UTF-32LE")),
        )
        inputs.forEach { bytes ->
            val factory = UnsupportedFeaturesFactory()
            val error = assertThrows(IllegalArgumentException::class.java) { GachaXml.parse(bytes, factory) }
            assertTrue(error.message!!.contains("编码无效"))
            assertEquals(0, factory.created)
        }
    }

    @Test fun rejectingResolverRemainsInstalledEvenWhenEverySecurityFeatureIsUnsupported() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GachaXml.parse("<root/>".toByteArray(), UnsupportedFeaturesFactory(probeResolver = true))
        }
        assertTrue(error.message!!.contains("外部实体引用"))
    }

    @Test fun malformedXmlReturnsFriendlyMessageInsteadOfParserDetails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GachaXml.parse("<root>".toByteArray(), UnsupportedFeaturesFactory())
        }
        assertEquals("Excel XML 内容损坏或格式不受支持，请重新导出后导入", error.message)
    }
}
