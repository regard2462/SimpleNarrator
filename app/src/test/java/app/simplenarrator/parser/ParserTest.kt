package app.simplenarrator.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Import von TXT und EPUB, Ersatztitel in der Sprache des Buchs. */
class ParserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun txt(text: String): File = tmp.newFile("book.txt").apply { writeText(text) }

    @Test
    fun txtGermanChaptersAndFallbackTitle() {
        val book = TxtParser.parse(txt(
            "Es war einmal ein König, der hatte eine Tochter und die war sehr schön.\n\n" +
                "Kapitel 1\n\nDer König ging mit seiner Tochter in den Wald und sie fanden dort einen Brunnen.\n\n" +
                "Kapitel 2\n\nAm Abend kehrten sie zurück und aßen gemeinsam in dem großen Saal des Schlosses."
        ))
        assertEquals(listOf("Anfang", "Kapitel 1", "Kapitel 2"), book.chapters.map { it.title })
        assertTrue(book.chapters.all { ch -> ch.paragraphs.all { p -> p.sentences.all { it.lang == "de" } } })
    }

    @Test
    fun txtEnglishFallbackTitle() {
        val book = TxtParser.parse(txt(
            "It was a bright cold day in April, and the clocks were striking thirteen when he came home.\n\n" +
                "Chapter 1\n\nThe hallway smelt of boiled cabbage and old rag mats, and he walked up the stairs."
        ))
        assertEquals(listOf("Beginning", "Chapter 1"), book.chapters.map { it.title })
        assertEquals("en", book.chapters[0].paragraphs[0].sentences[0].lang)
    }

    @Test
    fun epubTitlesFromNavAndFallback() {
        val epub = tmp.newFile("book.epub")
        ZipOutputStream(epub.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Test Book</dc:title><dc:creator>A. Writer</dc:creator></metadata>
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="c1" href="text/one.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="text/two.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
            put("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                <nav epub:type="toc"><ol><li><a href="text/one.xhtml">The First Part</a></li></ol></nav></body></html>""")
            put("OEBPS/text/one.xhtml", "<html><body><p>The night was cold and the wind was blowing from the north over the hills.</p></body></html>")
            put("OEBPS/text/two.xhtml", "<html><body><p>When the morning came, they packed their things and left the house by the river.</p></body></html>")
        }
        val book = EpubParser.parse(epub, tmp.newFolder("out"))
        assertEquals("The Test Book", book.title)
        assertEquals("A. Writer", book.author)
        assertEquals(listOf("The First Part", "Section 2"), book.chapters.map { it.title })
    }

    @Test
    fun gutenbergEpubSplitsAtAnchorsAndDropsBoilerplate() {
        val epub = tmp.newFile("pg.epub")
        ZipOutputStream(epub.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Die Probe</dc:title><dc:creator>Anna Beispiel</dc:creator></metadata>
                <manifest>
                  <item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="t0" href="book-0.xhtml" media-type="application/xhtml+xml"/>
                  <item id="t1" href="book-1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="t0"/><itemref idref="t1"/></spine></package>""")
            put("OEBPS/toc.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                <nav epub:type="toc"><ol>
                  <li><a href="book-0.xhtml#id1">I.</a></li>
                  <li><a href="book-0.xhtml#id2">II.</a></li>
                  <li><a href="book-1.xhtml#pg-footer-heading">THE FULL PROJECT GUTENBERG LICENSE</a></li>
                </ol></nav>
                <nav epub:type="landmarks"><ol><li><a href="book-0.xhtml#Page_5">5</a></li></ol></nav></body></html>""")
            put("OEBPS/book-0.xhtml", """<html><body>
                <header class="pg-boilerplate pgheader" id="pg-header"><h2>The Project Gutenberg eBook of Die Probe</h2>
                <div>This eBook is for the use of anyone anywhere in the United States.</div>
                <div id="pg-start-separator">*** START OF THE PROJECT GUTENBERG EBOOK DIE PROBE ***</div></header>
                <h2 id="id1">I.</h2>
                <p>Am Morgen lag dichter Nebel über dem Tal, und niemand wusste, wohin der Weg führen würde.</p>
                <p><a id="Page_5"></a>Erst gegen Mittag klarte es auf, und die Kinder liefen lachend voraus.</p>
                <h2 id="id2">II.</h2>
                <p>Am Abend kehrten alle müde, aber zufrieden in das kleine Haus am Fluss zurück.</p>
                </body></html>""")
            put("OEBPS/book-1.xhtml", """<html><body><footer class="pg-boilerplate" id="pg-footer">
                <div id="pg-end-separator">*** END OF THE PROJECT GUTENBERG EBOOK DIE PROBE ***</div>
                <h2 id="pg-footer-heading">THE FULL PROJECT GUTENBERG LICENSE</h2>
                <p>Updated editions will replace the previous one, the old editions will be renamed.</p></footer></body></html>""")
        }
        val book = EpubParser.parse(epub, tmp.newFolder("pg"))
        assertEquals(listOf("I.", "II."), book.chapters.map { it.title })
        val first = book.chapters[0].paragraphs.joinToString(" ") { p -> p.sentences.joinToString(" ") { it.text } }
        assertTrue(first, "Am Morgen lag dichter Nebel" in first && "Erst gegen Mittag" in first)
        assertTrue(first, "Gutenberg" !in first && "Am Abend" !in first)
    }

    @Test
    fun gedrucktesInhaltsverzeichnisUndTrennlinien() {
        val epub = tmp.newFile("toc.epub")
        ZipOutputStream(epub.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("content.opf", """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Probe</dc:title></metadata>
                <manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="t"/></spine></package>""")
            put("text.xhtml", """<html><body>
                <h1>Die Probe</h1>
                <h2>Contents</h2>
                <table>
                  <tr><td><a href="#c1">I.</a></td><td><a href="#c1">Der Aufbruch</a></td></tr>
                  <tr><td><a href="#c2">II.</a></td><td><a href="#c2">Der Wald</a></td></tr>
                  <tr><td><a href="#c3">III.</a></td><td><a href="#c3">Die Hütte</a></td></tr>
                </table>
                <hr/>
                <h2 id="c1">I. Der Aufbruch</h2>
                <p>Am Morgen packten sie ihre Sachen und gingen los, ohne sich umzusehen.</p>
                <hr class="tb"/>
                <p>Mittags rasteten sie an einem Bach, der aus den Bergen kam.</p>
                <p>Mehr dazu steht auf <a href="https://example.org/">der Seite des Verlags</a> und in <a href="#c2">Kapitel II</a>.</p>
                <hr/>
                </body></html>""")
        }
        val book = EpubParser.parse(epub, tmp.newFolder("toc"))
        val texts = book.chapters.flatMap { c -> c.paragraphs.flatMap { p -> p.sentences.map { it.text } } }
        assertTrue(texts.toString(), texts.none { "Contents" in it || it == "Der Wald" || it == "III." })
        // Die Trennlinie mitten im Text wird "* * *", die am Rand fällt weg
        assertEquals(1, texts.count { it == "* * *" })
        val i = texts.indexOf("* * *")
        assertTrue(texts.toString(), texts[i - 1].startsWith("Am Morgen") && texts[i + 1].startsWith("Mittags"))
        assertTrue(texts.toString(), texts.last().startsWith("Mehr dazu"))
    }
}
