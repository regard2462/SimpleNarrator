package app.simplenarrator.export

import app.simplenarrator.parser.EpubParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** EPUB 3 mit Media Overlays: Aufbau, Verweise, Wohlgeformtheit und Rückimport über EpubParser. */
class SyncEpubTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun audio(name: String, size: Int) = File(tmp.root, name).apply { writeBytes(ByteArray(size) { (it * 7).toByte() }) }

    private fun book(): Pair<File, List<SyncChapter>> {
        val a1 = audio("kapitel 1.mp3", 300)
        val a2 = audio("buch-rest.mp3", 500)
        val chapters = listOf(
            SyncChapter(
                "Der Anfang",
                listOf(
                    listOf(TextCue("Der Anfang", 0, 900)),
                    listOf(TextCue("Es war einmal ein König, der hatte drei Töchter.", 900, 4200), TextCue("Die jüngste war die schönste von allen.", 4200, 7000)),
                    listOf(TextCue("Eines Tages ging sie hinaus in den Wald.", 7250, 9800)),
                ),
                a1, "audio/mpeg",
            ),
            SyncChapter(
                "Zweites Kapitel <mit> & \"Zeichen\"",
                listOf(
                    listOf(TextCue("Im Wald stand ein alter Brunnen aus grauem Stein.", 0, 3100), TextCue("Sie setzte sich an den Rand & wartete <still> \u0001lange.", 3100, 6400)),
                    listOf(TextCue("", 6400, 6500), TextCue("Ohne Dauer.", 6500, 6500)),
                ),
                a2, "audio/mpeg",
            ),
            SyncChapter(
                "Drittes Kapitel",
                listOf(listOf(TextCue("Dann kam ein Frosch aus dem Wasser und sprach zu ihr.", 6500, 9900))),
                a2, "audio/mpeg",
            ),
        )
        val out = File(tmp.root, "buch.epub")
        SyncEpub.write(out, AudioMeta("Märchen & Sagen", "Brüder Grimm", jpeg), "de", chapters, identifier = "book-123", modifiedMillis = 1_760_000_000_000)
        return out to chapters
    }

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(bytes.inputStream())

    private fun ZipFile.bytes(name: String): ByteArray = getInputStream(getEntry(name) ?: error("missing $name")).use { it.readBytes() }

    private fun Element.all(local: String): List<Element> {
        val nl = getElementsByTagNameNS("*", local)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    /** "1:02:03.456" bzw. "12.345s" in Millisekunden. */
    private fun ms(clock: String): Long {
        if (clock.endsWith("s")) return Math.round(clock.dropLast(1).toDouble() * 1000)
        val (h, m, s) = clock.split(':')
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + Math.round(s.toDouble() * 1000)
    }

    @Test
    fun structure() {
        val (epub, _) = book()
        val raw = epub.readBytes()
        // mimetype als erster Eintrag, unkomprimiert, ohne Extrafeld
        assertEquals("PK\u0003\u0004", String(raw, 0, 4, Charsets.ISO_8859_1))
        assertEquals(0, raw[8].toInt() or raw[9].toInt())                  // Methode STORED
        assertEquals(8, raw[26].toInt()); assertEquals(0, raw[27].toInt())  // Namenslänge
        assertEquals(0, raw[28].toInt()); assertEquals(0, raw[29].toInt())  // Extrafeld
        assertEquals("mimetype", String(raw, 30, 8, Charsets.ISO_8859_1))
        assertEquals("application/epub+zip", String(raw, 38, 20, Charsets.ISO_8859_1))
        ZipInputStream(epub.inputStream()).use { z ->
            val first = z.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
        }

        ZipFile(epub).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(ZipEntry.STORED, zip.getEntry("OEBPS/audio/a1.mp3").method)
            // alle XML-Dateien wohlgeformt
            names.filter { it.endsWith(".xml") || it.endsWith(".opf") || it.endsWith(".xhtml") || it.endsWith(".smil") }
                .forEach { xml(zip.bytes(it)) }

            val container = xml(zip.bytes("META-INF/container.xml")).documentElement
            val opfPath = container.all("rootfile").single().getAttribute("full-path")
            assertEquals("OEBPS/content.opf", opfPath)
            val opf = xml(zip.bytes(opfPath)).documentElement
            assertEquals("3.0", opf.getAttribute("version"))
            val uid = opf.getAttribute("unique-identifier")
            val ident = opf.all("identifier").single()
            assertEquals(uid, ident.getAttribute("id"))
            assertTrue(ident.textContent, ident.textContent.matches(Regex("urn:uuid:[0-9a-f-]{36}")))
            assertEquals("Märchen & Sagen", opf.all("title").single().textContent)
            assertEquals("Brüder Grimm", opf.all("creator").single().textContent)
            assertEquals("de", opf.all("language").single().textContent)
            val metas = opf.all("meta")
            val modified = metas.single { it.getAttribute("property") == "dcterms:modified" }.textContent
            assertTrue(modified, modified.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
            assertEquals(SyncEpub.ACTIVE_CLASS, metas.single { it.getAttribute("property") == "media:active-class" }.textContent)

            val items = opf.all("item").associateBy { it.getAttribute("id") }
            for (item in items.values) assertTrue("manifest href ${item.getAttribute("href")}", "OEBPS/" + item.getAttribute("href") in names)
            assertEquals("nav.xhtml", items.values.single { it.getAttribute("properties") == "nav" }.getAttribute("href"))
            val cover = items.values.single { it.getAttribute("properties") == "cover-image" }
            assertEquals("image/jpeg", cover.getAttribute("media-type"))
            assertArrayEquals(jpeg, zip.bytes("OEBPS/" + cover.getAttribute("href")))
            // gemeinsame Tondatei nur einmal
            assertEquals(2, items.values.count { it.getAttribute("media-type") == "audio/mpeg" })
            assertTrue(names.none { it.endsWith(".mp3") && it.contains(" ") })

            val durations = metas.filter { it.getAttribute("property") == "media:duration" }
            val total = durations.single { !it.hasAttribute("refines") }
            val perOverlay = durations.filter { it.hasAttribute("refines") }.associate { it.getAttribute("refines").removePrefix("#") to ms(it.textContent) }
            assertEquals(perOverlay.values.sum(), ms(total.textContent))

            val spine = opf.all("itemref").map { it.getAttribute("idref") }
            assertEquals(3, spine.size)
            for (id in spine) {
                val item = items.getValue(id)
                val moId = item.getAttribute("media-overlay")
                val smilItem = items.getValue(moId)
                assertEquals("application/smil+xml", smilItem.getAttribute("media-type"))
                val smil = xml(zip.bytes("OEBPS/" + smilItem.getAttribute("href"))).documentElement
                var sum = 0L
                for (par in smil.all("par")) {
                    val src = par.all("text").single().getAttribute("src")
                    val (file, frag) = src.split('#')
                    assertEquals(item.getAttribute("href"), file)
                    val xhtml = xml(zip.bytes("OEBPS/$file"))
                    assertNotNull("fragment $frag in $file", xhtml.getElementById(frag) ?: xhtml.documentElement.all("span").firstOrNull { it.getAttribute("id") == frag })
                    val a = par.all("audio").single()
                    assertTrue(a.getAttribute("src"), "OEBPS/" + a.getAttribute("src") in names)
                    assertTrue(items.values.any { it.getAttribute("href") == a.getAttribute("src") })
                    val b = ms(a.getAttribute("clipBegin")); val e = ms(a.getAttribute("clipEnd"))
                    assertTrue(e > b)
                    sum += e - b
                }
                assertEquals(perOverlay.getValue(moId), sum)
            }

            // Kapitel 1: vorgelesene Überschrift wird zur h1 mit eigenem span, Sätze nummeriert
            val c1 = String(zip.bytes("OEBPS/chap1.xhtml"), Charsets.UTF_8)
            assertTrue(c1, c1.contains("<h1><span id=\"c1-s1\">Der Anfang</span></h1>"))
            assertTrue(c1.contains("<span id=\"c1-s4\">Eines Tages ging sie hinaus in den Wald.</span>"))
            val c2 = String(zip.bytes("OEBPS/chap2.xhtml"), Charsets.UTF_8)
            assertTrue(c2, c2.contains("<h1>Zweites Kapitel &lt;mit&gt; &amp; &quot;Zeichen&quot;</h1>"))
            assertTrue(c2.contains("Rand &amp; wartete &lt;still&gt; lange."))
            val s1 = String(zip.bytes("OEBPS/chap1.smil"), Charsets.UTF_8)
            assertTrue(s1, s1.contains("<par id=\"c1-s2-par\"><text src=\"chap1.xhtml#c1-s2\"/><audio src=\"audio/a1.mp3\" clipBegin=\"0.900s\" clipEnd=\"4.200s\"/></par>"))
            // Satz ohne Dauer steht im Text, aber nicht im Overlay
            assertTrue(c2.contains("Ohne Dauer."))
            assertTrue(!String(zip.bytes("OEBPS/chap2.smil"), Charsets.UTF_8).contains("c2-s3\""))
            assertTrue(String(zip.bytes("OEBPS/style.css"), Charsets.UTF_8).contains("." + SyncEpub.ACTIVE_CLASS))
        }
    }

    @Test
    fun sameIdentifierForSameBook() {
        val (epub, _) = book()
        val id1 = ZipFile(epub).use { String(it.bytes("OEBPS/content.opf"), Charsets.UTF_8) }.substringAfter("urn:uuid:").take(36)
        val (epub2, _) = book()
        val id2 = ZipFile(epub2).use { String(it.bytes("OEBPS/content.opf"), Charsets.UTF_8) }.substringAfter("urn:uuid:").take(36)
        assertEquals(id1, id2)
    }

    @Test
    fun importWithEpubParser() {
        val (epub, chapters) = book()
        val parsed = EpubParser.parse(epub, tmp.newFolder("import"))
        assertEquals("Märchen & Sagen", parsed.title)
        assertEquals("Brüder Grimm", parsed.author)
        assertNotNull(parsed.coverFile)
        assertEquals(chapters.map { it.title }, parsed.chapters.map { it.title })
        val texts = parsed.chapters.map { ch -> ch.paragraphs.flatMap { p -> p.sentences.map { it.text } }.joinToString(" ") }
        assertTrue(texts[0], texts[0].contains("Es war einmal ein König, der hatte drei Töchter."))
        assertTrue(texts[0].contains("Die jüngste war die schönste von allen."))
        assertTrue(texts[0].contains("Eines Tages ging sie hinaus in den Wald."))
        assertTrue(texts[1], texts[1].contains("Im Wald stand ein alter Brunnen aus grauem Stein."))
        assertTrue(texts[2], texts[2].contains("Dann kam ein Frosch aus dem Wasser und sprach zu ihr."))
        // Die vorgelesene Überschrift ist Überschriftenabsatz
        assertTrue(parsed.chapters[0].paragraphs.first().isHeading)
    }
}
