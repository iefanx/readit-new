package com.iefan.readout.utils

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

object DocumentText {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_TEXT_CHARS = 2_000_000
    const val MAX_XML_BYTES = 16L * 1024 * 1024

    fun copyLimited(input: InputStream, output: OutputStream, limit: Long = MAX_FILE_BYTES) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "File exceeds the ${limit / 1024 / 1024} MB limit." }
            output.write(buffer, 0, read)
        }
    }

    fun read(input: InputStream, limit: Long = MAX_XML_BYTES): String {
        val bytes = java.io.ByteArrayOutputStream().use { out -> copyLimited(input, out, limit); out.toByteArray() }
        if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) || (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))) {
            return bytes.toString(Charsets.UTF_16).removePrefix("\uFEFF")
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            bytes.toString(java.nio.charset.Charset.forName("windows-1252"))
        }
    }

    fun docx(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("*|p").joinToString("\n\n") { paragraph ->
            buildString {
                fun visit(element: Element) {
                    when (element.tagName().substringAfter(':')) {
                        "t" -> element.childNodes().filterIsInstance<TextNode>().forEach { append(it.wholeText) }
                        "tab" -> append('\t')
                        "br", "cr" -> append('\n')
                        else -> element.children().forEach { visit(it) }
                    }
                }
                visit(paragraph)
            }
        }
    }

    fun html(html: String): String = html(Jsoup.parse(html))
    fun html(doc: org.jsoup.nodes.Document): String {
        doc.select("script, style, head, header, footer, nav, iframe, noscript, aside").remove()
        val root = doc.selectFirst("article, main, .post-content, .mw-parser-output") ?: doc.body()
        val names = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre")
        val blocks = root.select(names.joinToString(",")).filter { element ->
            element.parents().none { it !== root && it.tagName() in names }
        }
        return if (blocks.isEmpty()) root.text() else blocks.joinToString("\n\n") { it.wholeText().trim() }
    }

    fun validateZip(zip: ZipFile) {
        var total = 0L
        var count = 0
        zip.entries().asSequence().forEach {
            count++
            require(count <= 10000) { "Archive contains too many entries." }
            require(it.size >= 0) { "Archive has an unknown entry size." }
            total += it.size
            require(total <= MAX_FILE_BYTES) { "Expanded archive exceeds 64 MB." }
        }
    }

    fun resolveEntry(base: String, href: String): String {
        val resolved = URI(base).resolve(href.substringBefore('#')).normalize()
        require(!resolved.isAbsolute && resolved.authority == null) { "External EPUB resource is unsupported." }
        val path = resolved.path
        require(!path.startsWith("/") && !path.startsWith("../")) { "Invalid EPUB path." }
        return path
    }

    fun opfPath(zip: ZipFile): String {
        val container = zip.getEntry("META-INF/container.xml") ?: error("EPUB container is missing.")
        val xml = zip.getInputStream(container).use { read(it) }
        val path = Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("rootfile")?.attr("full-path")
        require(!path.isNullOrBlank() && zip.getEntry(path) != null) { "EPUB package is missing." }
        return path
    }
}
