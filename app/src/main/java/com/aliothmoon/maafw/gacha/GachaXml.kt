package com.aliothmoon.maafw.gacha

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/** The preflight and Reader input enforce safety even when Android rejects desktop JAXP features. */
internal object GachaXml {
    private const val UNSAFE_XML = "不支持含 DTD 或实体声明的 Excel，请使用纯值工作簿"
    private const val BAD_ENCODING = "Excel XML 编码无效，仅支持 UTF-8 或 UTF-16，请重新导出后导入"
    private const val BAD_XML = "Excel XML 内容损坏或格式不受支持，请重新导出后导入"
    private const val EXTERNAL_ENTITY = "Excel 含外部实体引用，已拒绝导入"
    private val declarations = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
    private val prolog = Regex("^<\\?xml\\s[^?]*\\?>", RegexOption.IGNORE_CASE)
    private val encodingAttribute = Regex("\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1", RegexOption.IGNORE_CASE)

    internal fun parse(bytes: ByteArray, factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()): Element {
        // Decode once, before invoking any XML implementation. Passing a Reader below prevents
        // a BOM/declaration mismatch from making the DOM parser see different text than this check.
        val text = decode(bytes)
        require(!declarations.containsMatchIn(text)) { UNSAFE_XML }
        try {
            factory.isNamespaceAware = true
            optional { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            optional { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            optional { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            optional { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            optional { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            optional { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            optional { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            optional { factory.isXIncludeAware = false }
            optional { factory.isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw ExternalEntityRejected() }
            builder.setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
            return builder.parse(InputSource(StringReader(text))).documentElement
                ?: throw IllegalArgumentException(BAD_XML)
        } catch (e: ExternalEntityRejected) {
            throw IllegalArgumentException(EXTERNAL_ENTITY, e)
        } catch (e: ParserConfigurationException) {
            throw IllegalStateException("设备无法初始化 Excel XML 解析器，请更新应用后重试", e)
        } catch (e: SAXException) {
            throw IllegalArgumentException(BAD_XML, e)
        } catch (e: IOException) {
            throw IllegalArgumentException(BAD_XML, e)
        }
    }

    private class ExternalEntityRejected : SAXException(EXTERNAL_ENTITY)

    /** Unsupported knobs are optional; the independent DTD check and rejecting resolver are not. */
    private inline fun optional(setting: () -> Unit) {
        try { setting() }
        catch (_: ParserConfigurationException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: UnsupportedOperationException) { }
        catch (_: AbstractMethodError) { }
    }

    private fun decode(bytes: ByteArray): String {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
        val charset: Charset
        val offset: Int
        when {
            starts(0x00, 0x00, 0xFE, 0xFF) || starts(0xFF, 0xFE, 0x00, 0x00) -> throw IllegalArgumentException(BAD_ENCODING)
            starts(0xEF, 0xBB, 0xBF) -> { charset = Charsets.UTF_8; offset = 3 }
            starts(0xFF, 0xFE) -> { charset = Charsets.UTF_16LE; offset = 2 }
            starts(0xFE, 0xFF) -> { charset = Charsets.UTF_16BE; offset = 2 }
            bytes.size >= 2 && bytes[0] == 0.toByte() && asciiXmlStart(bytes[1]) -> { charset = Charsets.UTF_16BE; offset = 0 }
            bytes.size >= 2 && bytes[1] == 0.toByte() && asciiXmlStart(bytes[0]) -> { charset = Charsets.UTF_16LE; offset = 0 }
            else -> { charset = Charsets.UTF_8; offset = 0 }
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) { throw IllegalArgumentException(BAD_ENCODING, e) }
        require('\u0000' !in text) { BAD_ENCODING }
        val declared = prolog.find(text)?.value?.let { encodingAttribute.find(it)?.groupValues?.get(2) }
        val expected = when (charset) {
            Charsets.UTF_8 -> setOf("UTF-8", "UTF8")
            Charsets.UTF_16LE -> setOf("UTF-16", "UTF-16LE")
            else -> setOf("UTF-16", "UTF-16BE")
        }
        require(declared == null || declared.uppercase(java.util.Locale.ROOT) in expected) { BAD_ENCODING }
        return text
    }

    private fun asciiXmlStart(byte: Byte): Boolean = (byte.toInt() and 255) in listOf(0x3C, 0x20, 0x09, 0x0A, 0x0D)
}
